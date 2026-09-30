import { type ReactNode, useEffect, useMemo, useState } from 'react';
import {
  Button as AriaButton,
  Dialog,
  Heading,
  Modal,
  ModalOverlay,
} from 'react-aria-components';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, Navigate, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  AlertTriangle,
  ArrowLeft,
  ArrowRight,
  CheckCircle,
  XCircle,
  XClose,
} from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../../api/client';
import { useAssistStore } from '../../assist/assistStore';
import { NokRakButton, NokRakPrompt } from '../../assist/NokRakAsk';
import { useAssistReady } from '../../assist/useAssist';
import { assistPolicy } from '../../api/llm';
import {
  fetchAttributeVocabulary,
  fetchPrincipals,
  fetchVocabulary,
} from '../../api/governance';
import {
  createPolicy,
  ENFORCED_ENVIRONMENT,
  fetchPolicy,
  resolveBindings,
  transitionPolicy,
  updatePolicy,
  type StoredPolicy,
} from '../../api/policies';
import type { Policy } from '../../generated/entity/policy/policy';
import { normaliseConditionValues } from './conditionValues';
import { Field, Select, Step, TextField, useViewMode, ViewToggle } from './controls';
import PolicyFlowChart from './PolicyFlowChart';
import PolicyDiagram from './PolicyDiagram';
import SelectorBuilder from './SelectorBuilder';
import ScopePreview from './ScopePreview';
import { capabilities, MODES, type Engine, type EnforcementMode } from './enforcement';
import { engineLabel, engineOptions, useSourceEngines } from '../../engines';
import { fetchSources } from '../../api/sources';
import PolicyTargetPicker, {
  isSourceSelector,
  modeNote,
  sourceSelector,
  type PolicyTarget,
} from './PolicyTargetPicker';
import { describePolicy } from './policyLanguage';
import { diffPolicies, type PolicyFieldChange } from './policyDiff';
import { NEW_POLICY_PATH, type PolicyKind, type PolicyType } from './policyKind';
import { SUBSCRIPTION_POLICY } from './subscription/subscriptionPolicy';
import { DATA_ACCESS_POLICY } from './data-access/dataAccessPolicy';

/**
 * The policy builder (FR-3, FR-4, M4).
 *
 * The form is on the left and what the form means is on the right, always
 * visible. Immuta and Denodo both learned the same thing: a rule builder that
 * only shows its own widgets produces policies nobody can approve, because the
 * person signing off reads JSON or reads nothing. Here the right rail reads the
 * document back as a sentence, says how many assets it will reach, and says
 * which of the three enforcement modes can actually carry it — before anyone
 * presses apply, not after.
 *
 * This file is the part every policy shares. The steps that belong to one kind
 * live with that kind -- subscription/ and data-access/ -- and so does the page
 * a new one of that kind opens on, so the two can be built separately.
 */

const KINDS: Record<PolicyType, PolicyKind> = {
  SUBSCRIPTION: SUBSCRIPTION_POLICY,
  DATA: DATA_ACCESS_POLICY,
};

/*
 * A new policy opens on the environment the engine decides in, not on `dev`.
 * It used to open on `dev`, which meant a policy written entirely with the
 * defaults was stored somewhere the engine never looks -- it saved, it
 * activated, it showed up in every list, and it was never enforced once.
 *
 * Opening on `prod` is safe because the environment is not what makes a policy
 * live: every policy is created in DRAFT and somebody has to transition it.
 * That transition is the deliberate act, and this field should not be a second
 * one that nobody knows they are performing.
 */
const EMPTY: Policy = {
  name: '',
  policyType: 'SUBSCRIPTION',
  scopeLevel: 'TABLE',
  selector: {},
  effect: 'ALLOW',
  environment: ENFORCED_ENVIRONMENT,
};

/** Every layer the engine composes, in the order it composes them. */
const SCOPE_LEVELS: { value: Policy['scopeLevel']; label: string }[] = [
  { value: 'ORG', label: 'Organisation' },
  { value: 'DOMAIN', label: 'Domain or sub-domain' },
  { value: 'SERVICE', label: 'Service' },
  { value: 'DATABASE', label: 'Database' },
  { value: 'SCHEMA', label: 'Schema' },
  { value: 'TABLE', label: 'Table' },
  { value: 'COLUMN', label: 'Column' },
];

/**
 * Where the Anchor field points, per layer, as an example of its shape.
 *
 * Every layer is offered to both kinds of policy. A subscription at an outer
 * layer gates everything beneath it, but only what step 3 selects there -- the
 * anchor narrows where the selector looks, it never widens what it picks -- and
 * the override question on this step says what happens to a grant below it.
 */
const ANCHOR_EXAMPLES: Partial<Record<Policy['scopeLevel'], string>> = {
  DOMAIN: 'Finance.Risk',
  SERVICE: 'prod-mssql',
  DATABASE: 'prod-mssql.SalesDB',
  SCHEMA: 'prod-mssql.SalesDB.dbo',
  TABLE: 'demo-pg.salesdb.sales.customer',
  COLUMN: 'demo-pg.salesdb.sales.customer.email',
};

/**
 * `kind` is set by the kind's own page (/policies/new/subscription or
 * /policies/new/data). Without it -- /policies/new, or editing a stored
 * policy -- the kind is the document's own.
 */
export default function PolicyBuilderPage({ kind }: { kind?: PolicyKind }) {
  const { id } = useParams();
  const [params] = useSearchParams();
  const location = useLocation();
  // The kind used to be asked for with `?kind=`; an old link lands on the
  // kind's own page, with the rest of its answers.
  const asked = params.get('kind');
  if (!kind && !id && (asked === 'DATA' || asked === 'SUBSCRIPTION')) {
    const rest = new URLSearchParams(params);
    rest.delete('kind');
    const query = rest.toString();
    return (
      <Navigate
        replace
        state={location.state}
        to={`${NEW_POLICY_PATH[asked]}${query ? `?${query}` : ''}`}
      />
    );
  }
  return <PolicyBuilder kind={kind} />;
}

function PolicyBuilder({ kind }: { kind?: PolicyKind }) {
  const { id } = useParams();
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const isNew = !id;

  // Subscription and data policies are different jobs; choosing between them
  // inside step one of a form is where that distinction goes to be missed. A
  // kind's own page has answered it, so neither offers the other kind again.
  const kindChosen = Boolean(kind);
  // A draft suggested by an access request's review arrives in the
  // navigation state. It fills the form and nothing more: it is saved only
  // when the author presses "Create draft", and activated only through the
  // lifecycle, like any other policy.
  const location = useLocation();
  const suggested = isNew ? suggestedDraft(location.state) : null;
  const [draft, setDraft] = useState<Policy>(() => {
    if (suggested) return { ...EMPTY, ...kind?.initial, ...suggested.draft };
    return kind ? { ...EMPTY, ...kind.initial } : EMPTY;
  });
  const [loaded, setLoaded] = useState<StoredPolicy | null>(null);
  // Which reading of the draft the form column shows. It defaults to the form
  // and is remembered per browser, so an author who never opens the chart sees
  // the page exactly as it has always been.
  const [view, setView] = useViewMode<'form' | 'flow' | 'diagram'>('arak.policy.view', 'form');
  // The step open in a dialog over the chart, if one is.
  const [editing, setEditing] = useState<number | null>(null);
  // Null until the engine list arrives, then the first one the server lists.
  // Naming one here would be this page keeping its own copy of a list that
  // exists precisely so it does not have to.
  const [engine, setEngine] = useState<Engine | null>(null);
  const { data: engines } = useSourceEngines();

  // Where a new policy runs, chosen on the page before the form: `source` is
  // a connection's id or `any`, `mode` how it will be enforced. Both live in
  // the address, so a reload or Back lands on the same answer. The mode is not
  // written into the document -- PolicyTargetPicker says why.
  const sourceParam = params.get('source');
  const modeParam = MODES.some((entry) => entry.mode === params.get('mode'))
    ? (params.get('mode') as EnforcementMode)
    : null;
  const { data: sources } = useQuery({
    queryKey: ['sources'],
    queryFn: fetchSources,
    enabled: isNew && Boolean(sourceParam) && sourceParam !== 'any',
    retry: false,
  });
  const target =
    sourceParam && sourceParam !== 'any'
      ? (sources?.find((entry) => entry.id === sourceParam) ?? null)
      : null;

  useEffect(() => {
    // A connection fixes the engine, so its menu has nothing left to choose.
    const fixed = target?.engine;
    const first = engines?.[0]?.id;
    if (fixed) setEngine(fixed);
    else if (first) setEngine((current) => current ?? first);
  }, [engines, target?.engine]);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [assistNote, setAssistNote] = useState<string | null>(() =>
    suggested
      ? `Drafted from an access request for ${suggested.assetFqn ?? 'a table'}. Nothing is saved`
        + ' until you create the draft, and it stays a draft until it is activated from its page.'
      : null
  );

  // The assistant drafts a whole document, so this page publishes that it is
  // open and then waits. It never asks for one: a policy nobody asked for
  // appearing in a half-filled form is how somebody saves a rule they did not
  // write.
  const offer = useAssistStore((state) => state.offer);
  const withdraw = useAssistStore((state) => state.withdraw);
  const drafted = useAssistStore((state) => state.policy);
  const takePolicy = useAssistStore((state) => state.takePolicy);

  useEffect(() => {
    offer('policy', null, null);
    return () => withdraw('policy');
  }, [offer, withdraw]);

  // A change NokRak made to a stored policy, until somebody saves it, undoes
  // it or puts it away: the form as it was before, and what the answer changed.
  const [suggestion, setSuggestion] = useState<{
    before: Policy;
    changes: PolicyFieldChange[];
  } | null>(null);

  // One way in for a drafted document, whether it came from the dock or from
  // the button on this page, so the two cannot load it differently.
  const loadDrafted = (text: string): boolean => {
    try {
      const document = JSON.parse(text) as unknown;
      if (!document || typeof document !== 'object' || Array.isArray(document)) {
        throw new Error('not a document');
      }
      // Stripped as a suggestion from a request is: an id or a lifecycle
      // state would make the form pass for a stored, perhaps active, policy,
      // and the version and the stamp are the store's to write on save.
      const {
        id: _id,
        lifecycleState: _state,
        version: _version,
        updatedAt: _at,
        updatedBy: _by,
        ...parsed
      } = document as Partial<Policy> & {
        id?: unknown;
        lifecycleState?: unknown;
        version?: unknown;
        updatedAt?: unknown;
        updatedBy?: unknown;
      };
      // Merged over the empty document rather than used as-is. A model that
      // leaves a field out would otherwise hand the form an undefined where
      // it expects a value, and the control bound to it would go uncontrolled
      // mid-edit -- which looks like the form losing your typing. Over a
      // stored policy the same merge means a field the answer forgot keeps
      // what it had, rather than being quietly cleared.
      const next = { ...EMPTY, ...draft, ...parsed };
      setDraft(next);
      if (isNew) {
        setAssistNote(
          'Loaded a draft from NokRak. Read every step before you save it'
            + ' — it is a suggestion, and it is your name on the policy.'
        );
      } else {
        // Kept from the first suggestion when a second one follows it, so
        // Undo goes back to the policy as it was, not to NokRak's last try.
        const before = suggestion?.before ?? draft;
        setSuggestion({ before, changes: diffPolicies(before, next) });
        setAssistNote(null);
      }
      return true;
    } catch {
      setAssistNote(
        'NokRak answered with something that was not a policy document,'
          + ' so nothing was loaded. Try saying the rule a different way.'
      );
      return false;
    }
  };

  // Whether a document NokRak drafted was taken into this form. Taking it
  // empties the store, so this is what remembers that the form is already
  // written and has no need of the page asking where it runs.
  const [tookDraft, setTookDraft] = useState(false);

  useEffect(() => {
    if (!drafted) {
      return;
    }
    takePolicy();
    setTookDraft(true);
    loadDrafted(drafted.text);
    // loadDrafted only calls state setters, which never change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [drafted, takePolicy]);

  // The same drafting on the page. For a new policy it fills the form; for a
  // stored one it is sent the form as it stands and answers with it changed,
  // which is shown as a list of what changed with a way back. Either way it
  // returns a document and stores nothing: "Create draft" or "Save" is still
  // the only save, under the name of whoever presses it, and the lifecycle the
  // only way to switch a policy on (FR-2.6).
  const draftReady = useAssistReady('DRAFT_POLICY');
  const [asking, setAsking] = useState(false);
  const ask = useMutation({
    mutationFn: ({ intent, current }: { intent: string; current?: string }) =>
      assistPolicy(current ? { intent, current } : { intent }),
    onSuccess: (answer) => {
      if (loadDrafted(answer.document)) setAsking(false);
    },
  });

  const { data: existing, error: loadError } = useQuery({
    queryKey: ['policy', id],
    queryFn: () => fetchPolicy(id!),
    enabled: Boolean(id),
  });

  useEffect(() => {
    if (existing) {
      setDraft(existing.document);
      setLoaded(existing);
    }
  }, [existing]);

  useEffect(() => {
    // Covers the connection's assets and nothing else, until the author writes
    // a selector of their own -- which a later change of connection leaves
    // alone rather than throwing away.
    if (!isNew || sourceParam === null) return;
    setDraft((current) => {
      const empty = !current.selector || Object.keys(current.selector).length === 0;
      if (!empty && !isSourceSelector(current.selector)) return current;
      if (sourceParam === 'any') return empty ? current : { ...current, selector: {} };
      return target ? { ...current, selector: sourceSelector(target) } : current;
    });
  }, [isNew, sourceParam, target]);

  const { data: vocabulary } = useQuery({
    queryKey: ['governance-vocabulary'],
    queryFn: fetchVocabulary,
    staleTime: 5 * 60 * 1000,
  });
  const { data: attributes } = useQuery({
    queryKey: ['attribute-vocabulary'],
    queryFn: fetchAttributeVocabulary,
    staleTime: 5 * 60 * 1000,
  });
  const { data: principals } = useQuery({
    queryKey: ['principals', 'builder'],
    queryFn: () => fetchPrincipals({ limit: 500 }),
    staleTime: 5 * 60 * 1000,
  });

  function patch(next: Partial<Policy>) {
    setDraft((current) => ({ ...current, ...next }));
  }

  const save = useMutation({
    mutationFn: async () => {
      // A list typed before "is one of" kept its items in `value`; the form
      // shows those as separate items, and this saves them that way.
      const document = normaliseConditionValues(draft);
      if (isNew) return createPolicy(document);
      // The version the form was opened on, not one read back out of the
      // document: this is the question "has anyone changed it since", and a
      // conflict here is the screen catching an overwrite in time.
      return updatePolicy(id!, document, loaded?.version ?? 1);
    },
    onSuccess: (saved) => {
      setSaveError(null);
      setLoaded(saved);
      setSuggestion(null);
      queryClient.invalidateQueries({ queryKey: ['policies'] });
      // A new policy lands on its own summary: the first thing an author
      // wants after saving is what the selector actually caught, which is
      // the one thing the form cannot show them.
      if (isNew) navigate(`/policies/${saved.id}`, { replace: true });
    },
    onError: (error) =>
      setSaveError(apiErrorMessage(error, 'The policy could not be saved.')),
  });

  const activate = useMutation({
    mutationFn: (state: string) => transitionPolicy(id!, state),
    onSuccess: (saved) => {
      setLoaded(saved);
      setDraft(saved.document);
      setSuggestion(null);
      queryClient.invalidateQueries({ queryKey: ['policies'] });
    },
    onError: (error) =>
      setSaveError(
        apiErrorMessage(error, 'The lifecycle change was refused.')
      ),
  });

  const bindings = useMutation({ mutationFn: () => resolveBindings(id!) });

  // On a stored policy only once it has arrived: before that the form holds
  // the empty document, and a change to it would be a change to nothing.
  // Never on an archived one, which is final.
  const canAsk =
    draftReady && (isNew || (loaded !== null && loaded.lifecycleState !== 'ARCHIVED'));
  const live = loaded?.lifecycleState === 'ACTIVE';

  const sentences = useMemo(() => describePolicy(draft), [draft]);
  const modes = useMemo(
    () => (engine ? capabilities(draft, engine) : []),
    [draft, engine]
  );

  const incomplete = !draft.name.trim() || !hasCondition(draft);
  // The kind the form is showing, which the header wears.
  const shown = KINDS[draft.policyType];

  if (loadError) {
    return (
      <p className="tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
        {apiErrorMessage(loadError, 'Could not load this policy.')}
      </p>
    );
  }

  // A new policy is asked where it runs first. Not one that arrives already
  // written -- from an access request or from NokRak -- which carries its
  // answer in its selector, and would be held behind a page it does not need.
  if (isNew && !suggested && !drafted && !tookDraft && !modeParam) {
    return (
      <PolicyTargetPicker
        kindChosen={kindChosen}
        initial={{
          kind: draft.policyType,
          sourceId: sourceParam === 'any' ? null : sourceParam,
          mode: modeParam,
        }}
        onPick={(picked: PolicyTarget) => {
          const answers = { source: picked.source?.id ?? 'any', mode: picked.mode };
          if (kind) {
            setParams(answers);
          } else {
            // On to the page of the kind picked, which opens its document as
            // that kind's own page would.
            navigate(`${NEW_POLICY_PATH[picked.kind]}?${new URLSearchParams(answers)}`);
          }
        }}
      />
    );
  }

  /*
   * Each step once: the form lists them, and a box in the chart opens the same
   * fields in a dialog over it -- one set of controls, two ways in.
   */
  const steps: StepSpec[] = [
    {
      step: 1,
      title: 'What this policy is',
      description:
        'The name is what everyone else will search for when they hit something they cannot explain.',
      body: (
        <>
          <div className="tw:grid tw:gap-4 tw:sm:grid-cols-2">
            <Field label="Name">
              <TextField
                onChange={(next) => patch({ name: next })}
                placeholder="mask-pii-outside-clearance"
                value={draft.name}
              />
            </Field>
            <Field label="Display name">
              <TextField
                onChange={(next) => patch({ displayName: next || undefined })}
                placeholder="Mask PII below clearance L2"
                value={draft.displayName ?? ''}
              />
            </Field>
            {!(isNew && kindChosen) && (
            <Field
              hint="Subscription decides who reaches the table at all. Data decides what they see inside it. They are authored by different people at different times, which is why they are separate documents."
              label="Kind">
              <Select
                onChange={(next) =>
                  patch({ policyType: next as Policy['policyType'] })
                }
                options={[
                  { value: 'SUBSCRIPTION', label: 'Subscription — who gets in' },
                  { value: 'DATA', label: 'Data — what they see' },
                ]}
                value={draft.policyType}
              />
            </Field>
            )}
            <Field
              hint={
                // Shown only when it matters. Saying "this one is enforced"
                // on every policy would be noise; saying nothing when the
                // policy is parked in an environment the engine never reads
                // is how the default used to hide.
                (draft.environment ?? ENFORCED_ENVIRONMENT) !==
                ENFORCED_ENVIRONMENT
                  ? `The engine decides in ${ENFORCED_ENVIRONMENT}. A policy in ` +
                    `${draft.environment} is authored and versioned, but it is ` +
                    `never enforced, however it is activated.`
                  : undefined
              }
              label="Environment"
            >
              <Select
                onChange={(next) =>
                  patch({ environment: next as Policy['environment'] })
                }
                options={[
                  {
                    value: 'dev',
                    label: 'dev',
                    hint: 'Authoring only — not enforced',
                  },
                  {
                    value: 'uat',
                    label: 'uat',
                    hint: 'Authoring only — not enforced',
                  },
                  {
                    value: 'prod',
                    label: 'prod',
                    hint: 'The environment the engine enforces',
                  },
                ]}
                value={draft.environment ?? ENFORCED_ENVIRONMENT}
              />
            </Field>
            <Field className="tw:sm:col-span-2" label="Description">
              <TextField
                onChange={(next) => patch({ description: next || undefined })}
                placeholder="Why this exists, for whoever reads it in a year."
                value={draft.description ?? ''}
              />
            </Field>
          </div>
        </>
      ),
    },
    {
      step: 2,
      title: 'Where it sits',
      description:
        'Higher layers cannot be relaxed by lower ones. A table-level policy adds to what the organisation already said; it never takes it away.',
      body: (
        <>
          <div className="tw:grid tw:gap-4 tw:sm:grid-cols-2">
            <Field
              hint={
                draft.scopeLevel === 'ORG'
                  ? 'Every asset step 3 selects, on every source. With nothing selected it covers nothing.'
                  : undefined
              }
              label="Level">
              <Select
                onChange={(next) =>
                  patch({ scopeLevel: next as Policy['scopeLevel'] })
                }
                options={SCOPE_LEVELS}
                value={draft.scopeLevel}
              />
            </Field>
            {draft.scopeLevel !== 'ORG' && (
              <Field
                hint="The fully qualified name this layer is measured from. Only assets under it are looked at; step 3 then picks among them."
                label="Anchor">
                <TextField
                  onChange={(next) => patch({ scopeFqn: next || undefined })}
                  placeholder={ANCHOR_EXAMPLES[draft.scopeLevel] ?? ''}
                  value={draft.scopeFqn ?? ''}
                />
              </Field>
            )}
            <Field
              className="tw:sm:col-span-2"
              hint="Off by default. While it is off, nobody reaches these assets without matching this policy — a direct grant on a single table will show as in force and still admit nobody, which is usually the point of a global policy and occasionally the thing that looks like a bug. Every time this is used it is recorded in the audit log."
              label="Can a grant let somebody past this policy?">
              <Select
                onChange={(next) =>
                  patch({ allowLocalOverride: next === 'yes' })
                }
                options={[
                  { value: 'no', label: 'No — everybody must match this policy' },
                  {
                    value: 'yes',
                    label: 'Yes — a grant or a table policy may let somebody in',
                  },
                ]}
                value={draft.allowLocalOverride ? 'yes' : 'no'}
              />
            </Field>
          </div>
        </>
      ),
    },
    {
      step: 3,
      title: 'Which assets it covers',
      description:
        'Written against tags, terms and domains rather than table names, so an asset tagged tomorrow is covered tomorrow without anyone editing this.',
      body: (
        <>
          <SelectorBuilder
            onChange={(next) => patch({ selector: next })}
            value={draft.selector}
            vocabulary={vocabulary}
          />
          <ScopePreview draft={draft} />
        </>
      ),
    },
    // The rest is the kind's own, numbered on from the shared steps.
    ...KINDS[draft.policyType]
      .steps({ draft, patch, vocabulary, attributes, principals })
      .map((entry, index) => ({ ...entry, step: 4 + index })),
  ];

  return (
    <>
      <Link
        className="tw:inline-flex tw:items-center tw:gap-1 tw:text-sm tw:text-tertiary tw:hover:text-primary"
        to={loaded ? `/policies/${loaded.id}` : '/policies'}>
        <ArrowLeft className="tw:size-4" />
        {loaded ? 'Back to the policy' : 'Policies'}
      </Link>

      <header className="tw:mt-4 tw:flex tw:flex-wrap tw:items-center tw:justify-between tw:gap-4 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5 tw:shadow-xs">
        <div className="tw:flex tw:min-w-0 tw:items-center tw:gap-4">
          <span
            className={`tw:flex tw:size-12 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-xl ${shown.tone}`}>
            <shown.icon className="tw:size-6" />
          </span>
          <div className="tw:min-w-0">
            <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
              <h1 className="tw:text-xl tw:font-semibold tw:text-primary">
                {isNew
                  ? kindChosen
                    ? shown.title
                    : 'New policy'
                  : draft.displayName || draft.name}
              </h1>
              {loaded && (
                <Badge color="gray" size="sm" type="pill-color">
                  v{loaded.version} · {loaded.lifecycleState.toLowerCase()}
                </Badge>
              )}
              {loaded && (
                // Who saved the version being edited, and when: an editor who
                // is about to overwrite a colleague's change from a minute ago
                // should see that before pressing Save, not in the 409.
                <span className="tw:text-xs tw:text-tertiary">
                  edited {editedAt(loaded.updatedAt)} by{' '}
                  <span className="tw:text-secondary">{loaded.updatedBy}</span>
                  {' · '}
                  <Link
                    className="tw:underline tw:decoration-transparent tw:underline-offset-2 tw:hover:decoration-current"
                    to={`/policies/${loaded.id}?tab=history`}>
                    History
                  </Link>
                </span>
              )}
            </div>
            <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">
              Written once; enforced the same wherever it runs.
            </p>
            {isNew && modeParam && (
              <div
                className="tw:mt-2 tw:flex tw:flex-wrap tw:items-center tw:gap-1.5"
                data-testid="policy-target">
                <Badge color="blue" size="sm" type="pill-color">
                  {target
                    ? `${target.name} · ${engineLabel(engines, target.engine)}`
                    : 'Every connection'}
                </Badge>
                <Badge color="purple" size="sm" type="pill-color">
                  {MODES.find((entry) => entry.mode === modeParam)?.title}
                </Badge>
                <button
                  className="tw:cursor-pointer tw:text-sm tw:font-medium tw:text-brand-secondary tw:hover:underline"
                  onClick={() => {
                    const next = new URLSearchParams(params);
                    next.delete('mode');
                    setParams(next);
                  }}
                  type="button">
                  Change
                </button>
              </div>
            )}
          </div>
        </div>
        <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          {loaded && loaded.lifecycleState === 'DRAFT' && (
            <Button
              color="secondary"
              isDisabled={activate.isPending}
              onPress={() => activate.mutate('ACTIVE')}
              size="md">
              Activate
            </Button>
          )}
          {loaded && loaded.lifecycleState === 'ACTIVE' && (
            <Button
              color="secondary"
              isDisabled={activate.isPending}
              onPress={() => activate.mutate('DISABLED')}
              size="md">
              Disable
            </Button>
          )}
          {loaded && (
            <Button
              color="secondary"
              onPress={() => navigate(`/policies/${loaded.id}`)}
              size="md">
              Done
            </Button>
          )}
          {canAsk && (
            <NokRakButton
              label="NokRak, help me"
              onPress={() => setAsking((open) => !open)}
              open={asking}
              size="md"
            />
          )}
          <Button
            isDisabled={incomplete || save.isPending}
            onPress={() => save.mutate()}
            size="md">
            {save.isPending ? 'Saving…' : isNew ? 'Create draft' : 'Save'}
          </Button>
        </div>
      </header>

      {canAsk && asking && isNew && (
        <div className="tw:mt-4">
          <NokRakPrompt
            askLabel="Draft it"
            error={
              ask.isError
                ? apiErrorMessage(ask.error, 'NokRak could not draft a policy.')
                : null
            }
            hint="Fills the form below. Nothing is saved until you press Create draft, and it stays a draft until it is activated."
            onAsk={(intent) => ask.mutate({ intent })}
            onClose={() => setAsking(false)}
            pending={ask.isPending}
            pendingLabel="Drafting…"
            placeholder="e.g. Mask every column tagged PII for anyone below clearance L2, and let the Finance team read the sales schema on weekdays."
            title="Tell NokRak the rule"
          />
        </div>
      )}

      {canAsk && asking && !isNew && (
        <div className="tw:mt-4">
          <NokRakPrompt
            askLabel="Suggest it"
            error={
              ask.isError
                ? apiErrorMessage(ask.error, 'NokRak could not suggest a change.')
                : null
            }
            hint={
              'Changes the form below and lists what changed, with a way back. Nothing is saved until you press Save'
              + (live
                ? ' — and this policy is active, so Save puts the change in force straight away.'
                : '.')
            }
            onAsk={(intent) => ask.mutate({ intent, current: JSON.stringify(draft) })}
            onClose={() => setAsking(false)}
            pending={ask.isPending}
            pendingLabel="Thinking…"
            placeholder="e.g. Also mask the phone column, and let the audit team read it until the end of the year."
            title="Tell NokRak what to change"
          />
        </div>
      )}

      {suggestion && (
        <SuggestionReview
          changes={suggestion.changes}
          live={live}
          onKeep={() => setSuggestion(null)}
          onUndo={() => {
            setDraft(suggestion.before);
            setSuggestion(null);
          }}
        />
      )}

      {assistNote && (
        <p className="tw:mt-4 tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-4 tw:text-sm tw:text-tertiary">
          {assistNote}
        </p>
      )}

      {saveError && (
        <p className="tw:mt-4 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
          {saveError}
        </p>
      )}

      {/* The diagram needs the width of the page to be read at a legible size,
          so beside it the rail drops below instead of narrowing it. */}
      <div
        className={`tw:mt-8 tw:grid tw:gap-6 ${
          view === 'diagram'
            ? 'tw:grid-cols-[minmax(0,1fr)]'
            : 'tw:xl:grid-cols-[minmax(0,1fr)_380px]'
        }`}>
        {/* ------------------------------------------------------- the form */}
        <div className="tw:flex tw:flex-col tw:gap-5">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-between tw:gap-3">
            <p className="tw:text-sm tw:text-tertiary">
              {view === 'form'
                ? 'Fill it in top to bottom; the summary beside it follows what you write.'
                : 'Click a step to change it here; the chart redraws as you do.'}
            </p>
            <ViewToggle
              label="How to show this policy"
              onChange={setView}
              options={[
                { value: 'form', label: 'Form' },
                { value: 'flow', label: 'Flowchart' },
                { value: 'diagram', label: 'Diagram' },
              ]}
              value={view}
            />
          </div>
          {view === 'flow' ? (
            <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
              <PolicyFlowChart onEdit={setEditing} policy={draft} />
            </section>
          ) : view === 'diagram' ? (
            <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
              <PolicyDiagram onEdit={setEditing} policy={draft} />
            </section>
          ) : (
            steps.map((entry) => (
              <Step
                description={entry.description}
                key={entry.step}
                step={entry.step}
                title={entry.title}>
                {entry.body}
              </Step>
            ))
          )}
        </div>

        {/* ------------------------------------------------------- the rail */}
        <aside
          className={
            view === 'diagram'
              ? 'tw:grid tw:items-start tw:gap-4 tw:xl:grid-cols-3'
              : 'tw:flex tw:flex-col tw:gap-4 tw:xl:sticky tw:xl:top-6 tw:xl:self-start'
          }>
          <section className="tw:rounded-xl tw:border tw:border-brand tw:bg-brand-primary tw:p-4">
            <h2 className="tw:text-sm tw:font-semibold tw:text-primary">
              In plain words
            </h2>
            <div className="tw:mt-2 tw:flex tw:flex-col tw:gap-1.5 tw:text-sm tw:text-secondary">
              {sentences.map((line, index) => (
                <p key={index}>{line}</p>
              ))}
            </div>
          </section>

          <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
            <div className="tw:flex tw:items-center tw:justify-between tw:gap-2">
              <h2 className="tw:text-sm tw:font-semibold tw:text-primary">
                What it reaches
              </h2>
              <Button
                color="secondary"
                isDisabled={isNew || bindings.isPending}
                onPress={() => bindings.mutate()}
                size="sm">
                {bindings.isPending ? 'Resolving…' : 'Resolve'}
              </Button>
            </div>
            {isNew ? (
              <p className="tw:mt-2 tw:text-sm tw:text-tertiary">
                Save the draft first. Resolving runs the selector against the
                whole estate, which is the cheapest impact analysis available
                before anything is enforced.
              </p>
            ) : bindings.data ? (
              <dl className="tw:mt-3 tw:grid tw:grid-cols-2 tw:gap-3 tw:text-sm">
                <Stat label="Assets matched" value={bindings.data.matched} />
                <Stat label="Assets scanned" value={bindings.data.scanned} />
                <Stat label="Newly covered" value={bindings.data.added} />
                <Stat label="No longer covered" value={bindings.data.removed} />
              </dl>
            ) : (
              <p className="tw:mt-2 tw:text-sm tw:text-tertiary">
                Not resolved in this session.
              </p>
            )}
          </section>

          <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
            <div className="tw:flex tw:items-center tw:justify-between tw:gap-2">
              <h2 className="tw:text-sm tw:font-semibold tw:text-primary">
                Where it can be enforced
              </h2>
              {target ? (
                <Badge color="blue" size="sm" type="pill-color">
                  {engineLabel(engines, target.engine)}
                </Badge>
              ) : (
                <Select
                  ariaLabel="Engine"
                  className="tw:w-32 tw:py-1"
                  onChange={(next) => setEngine(next as Engine)}
                  options={engineOptions(engines)}
                  value={engine ?? ''}
                />
              )}
            </div>
            <p className="tw:mt-2 tw:text-xs tw:text-tertiary">
              The mode is chosen per data source and can be overridden per asset.
              This says which of them would carry this policy whole.
            </p>
            {isNew && modeParam && modeNote(target, modeParam) && (
              <p className="tw:mt-2 tw:text-xs tw:text-warning-primary">
                {modeNote(target, modeParam)}
              </p>
            )}
            <div className="tw:mt-3 tw:flex tw:flex-col tw:gap-3">
              {modes.map((note) => {
                const mode = MODES.find((entry) => entry.mode === note.mode)!;
                const Icon =
                  note.support === 'full'
                    ? CheckCircle
                    : note.support === 'partial'
                      ? AlertTriangle
                      : XCircle;
                const tone =
                  note.support === 'full'
                    ? 'tw:text-success-primary'
                    : note.support === 'partial'
                      ? 'tw:text-warning-primary'
                      : 'tw:text-error-primary';
                const picked = isNew && note.mode === modeParam;
                return (
                  <div
                    className={
                      picked
                        ? 'tw:-mx-2 tw:rounded-lg tw:border tw:border-brand tw:bg-brand-primary tw:p-2'
                        : undefined
                    }
                    data-chosen={picked || undefined}
                    key={note.mode}>
                    <div className="tw:flex tw:items-center tw:gap-2">
                      <Icon className={`tw:size-4 ${tone}`} />
                      <span className="tw:text-sm tw:font-medium tw:text-primary">
                        {mode.title}
                      </span>
                      {picked && (
                        <Badge color="brand" size="sm" type="pill-color">
                          Chosen
                        </Badge>
                      )}
                    </div>
                    <p className="tw:mt-1 tw:text-xs tw:text-tertiary">
                      {mode.summary}
                    </p>
                    <p className="tw:mt-1 tw:text-xs tw:text-tertiary">
                      Needs: {mode.requirement}.
                    </p>
                    {note.gaps.map((gap, index) => (
                      <p
                        className="tw:mt-1 tw:text-xs tw:text-warning-primary"
                        key={index}>
                        {gap}
                      </p>
                    ))}
                  </div>
                );
              })}
            </div>
          </section>
        </aside>
      </div>

      <StepDialog
        onClose={() => setEditing(null)}
        onOpen={setEditing}
        open={view === 'form' ? null : editing}
        steps={steps}
      />
    </>
  );
}

interface StepSpec {
  step: number;
  title: string;
  description: string;
  body: ReactNode;
}

/**
 * One step of the form, opened over the chart from the box it writes.
 *
 * Clicking a box used to switch back to the form and scroll to the step, which
 * lost the overview the chart is there for: the author changed one field and
 * then had to switch back to see what it did. Here the chart stays where it is
 * and redraws behind a light overlay as the fields change, so closing the
 * dialog lands on the result. Previous and Next walk the steps in form order
 * without leaving it.
 */
function StepDialog({
  steps,
  open,
  onOpen,
  onClose,
}: {
  steps: StepSpec[];
  open: number | null;
  onOpen: (step: number) => void;
  onClose: () => void;
}) {
  const index = steps.findIndex((entry) => entry.step === open);
  const entry = index >= 0 ? steps[index] : null;
  const previous = index > 0 ? steps[index - 1] : null;
  const next = index >= 0 && index < steps.length - 1 ? steps[index + 1] : null;

  return (
    <ModalOverlay
      className="tw:fixed tw:inset-0 tw:z-50 tw:flex tw:items-center tw:justify-center tw:bg-overlay/40 tw:p-4"
      isDismissable
      isOpen={entry !== null}
      onOpenChange={(isOpen) => {
        if (!isOpen) onClose();
      }}>
      <Modal className="tw:w-full tw:max-w-3xl">
        <Dialog className="tw:flex tw:max-h-[85vh] tw:flex-col tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xl tw:outline-none">
          {entry && (
            <>
              <header className="tw:flex tw:items-start tw:gap-3 tw:border-b tw:border-secondary tw:px-5 tw:py-4">
                <span className="tw:mt-0.5 tw:flex tw:h-6 tw:w-6 tw:flex-none tw:items-center tw:justify-center tw:rounded-full tw:bg-brand-solid tw:text-xs tw:font-semibold tw:text-white">
                  {entry.step}
                </span>
                <div className="tw:min-w-0 tw:flex-1">
                  <Heading className="tw:text-md tw:font-semibold tw:text-primary" slot="title">
                    {entry.title}
                  </Heading>
                  <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">{entry.description}</p>
                </div>
                <AriaButton
                  aria-label="Close"
                  className="tw:flex tw:size-8 tw:shrink-0 tw:cursor-pointer tw:items-center tw:justify-center tw:rounded-lg tw:text-fg-quaternary tw:outline-none tw:hover:bg-primary_hover tw:focus-visible:outline-2 tw:focus-visible:outline-brand"
                  onPress={onClose}>
                  <XClose className="tw:size-5" />
                </AriaButton>
              </header>
              <div className="tw:min-h-0 tw:flex-1 tw:overflow-y-auto tw:p-5">{entry.body}</div>
              <footer className="tw:flex tw:items-center tw:justify-between tw:gap-2 tw:border-t tw:border-secondary tw:px-5 tw:py-3">
                <div className="tw:flex tw:items-center tw:gap-2">
                  <Button
                    color="secondary"
                    iconLeading={ArrowLeft}
                    isDisabled={!previous}
                    onPress={() => previous && onOpen(previous.step)}
                    size="sm">
                    Previous
                  </Button>
                  <Button
                    color="secondary"
                    iconTrailing={ArrowRight}
                    isDisabled={!next}
                    onPress={() => next && onOpen(next.step)}
                    size="sm">
                    Next
                  </Button>
                  <span className="tw:text-xs tw:text-quaternary">
                    Step {index + 1} of {steps.length}
                  </span>
                </div>
                <Button onPress={onClose} size="sm">
                  Done
                </Button>
              </footer>
            </>
          )}
        </Dialog>
      </Modal>
    </ModalOverlay>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <dt className="tw:text-xs tw:text-tertiary">{label}</dt>
      <dd className="tw:text-lg tw:font-semibold tw:text-primary">{value}</dd>
    </div>
  );
}

/** The draft an access request's review offered, if that is how the page was opened. */
function suggestedDraft(state: unknown): { draft: Partial<Policy>; assetFqn: string | null } | null {
  if (!state || typeof state !== 'object') return null;
  const { draft, from } = state as { draft?: unknown; from?: { assetFqn?: unknown } };
  if (!draft || typeof draft !== 'object') return null;
  // An id or a lifecycle state would make the form look like an existing,
  // perhaps active, policy. A suggestion is neither.
  const { id: _id, lifecycleState: _state, ...rest } = draft as Partial<Policy> & {
    id?: unknown;
    lifecycleState?: unknown;
  };
  return {
    draft: rest,
    assetFqn: typeof from?.assetFqn === 'string' ? from.assetFqn : null,
  };
}

/**
 * A selector with nothing in it binds to nothing, which makes the policy a
 * no-op that looks active in the list. Saving one is nearly always a mistake,
 * so it is blocked at the button rather than discovered later.
 */
function hasCondition(policy: Policy): boolean {
  const selector = policy.selector;
  return Boolean(
    selector.condition ||
      selector.and?.length ||
      selector.or?.length ||
      selector.not
  );
}

/**
 * What NokRak changed in a stored policy, before anybody saves it.
 *
 * Read as a diff because that is how a change to a policy somebody already
 * signed gets reviewed: the reader should not have to reread every step to
 * find the two lines that moved. The same sentences as the History tab, so a
 * suggestion and a past version read alike.
 */
function SuggestionReview({
  changes,
  live,
  onUndo,
  onKeep,
}: {
  changes: PolicyFieldChange[];
  live: boolean;
  onUndo: () => void;
  onKeep: () => void;
}) {
  return (
    <section
      aria-label="NokRak's suggestion"
      className="tw:mt-4 tw:flex tw:flex-col tw:gap-3 tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-4">
      <div>
        <h2 className="tw:text-sm tw:font-semibold tw:text-primary">
          {changes.length === 0
            ? 'NokRak’s answer changes nothing'
            : `NokRak suggests ${changes.length} ${changes.length === 1 ? 'change' : 'changes'}`}
        </h2>
        <p className="tw:mt-0.5 tw:text-pretty tw:text-sm tw:text-tertiary">
          {changes.length === 0
            ? 'It reads the same as the policy did. Try saying the change a different way.'
            : 'The form below has them now. Nothing is saved until you press Save — read'
              + ' them, change what you need, or undo. It is your name on the policy.'}
        </p>
      </div>

      {live && changes.length > 0 && (
        <p className="tw:flex tw:items-start tw:gap-2 tw:text-pretty tw:text-sm tw:text-warning-primary">
          <AlertTriangle aria-hidden className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
          This policy is active. Saving puts these rules in force straight away.
        </p>
      )}

      {changes.length > 0 && (
        <dl className="tw:flex tw:flex-col tw:text-sm">
          <div className="tw:hidden tw:gap-3 tw:pb-1 tw:text-xs tw:font-medium tw:text-tertiary tw:md:grid tw:md:grid-cols-[9rem_1fr_1fr]">
            <span />
            <span>Before</span>
            <span>Suggested</span>
          </div>
          {changes.map((change) => (
            <div
              className="tw:grid tw:gap-1 tw:border-t tw:border-secondary tw:py-2 tw:md:grid-cols-[9rem_1fr_1fr] tw:md:gap-3"
              key={change.field}>
              <dt className="tw:text-xs tw:font-medium tw:text-tertiary">{change.field}</dt>
              <dd className="tw:min-w-0 tw:break-words tw:text-secondary">
                <span className="tw:text-xs tw:text-quaternary tw:md:hidden">Before: </span>
                {changeLines(change.before)}
              </dd>
              <dd className="tw:min-w-0 tw:break-words tw:text-primary">
                <span className="tw:text-xs tw:text-quaternary tw:md:hidden">Suggested: </span>
                {changeLines(change.after)}
              </dd>
            </div>
          ))}
        </dl>
      )}

      <div className="tw:flex tw:flex-wrap tw:gap-2">
        {changes.length > 0 && (
          <Button color="secondary" onPress={onUndo} size="sm">
            Undo the suggestion
          </Button>
        )}
        <Button color="tertiary" onPress={onKeep} size="sm">
          {changes.length > 0 ? 'Keep editing' : 'Close'}
        </Button>
      </div>
    </section>
  );
}

function changeLines(values: string[]) {
  if (!values.length) {
    return <span className="tw:text-quaternary">&mdash;</span>;
  }
  return values.map((value, index) => (
    <p className="tw:text-pretty" key={index}>
      {value}
    </p>
  ));
}

/** When a version was saved, to the minute, in the reader's own zone. */
function editedAt(iso: string | null | undefined): string {
  if (!iso) {
    return 'at an unknown time';
  }
  const at = new Date(iso);
  return Number.isNaN(at.getTime())
    ? iso
    : at.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}
