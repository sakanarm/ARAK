import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { AlertTriangle, ArrowLeft, CheckCircle, EyeOff, Key01, XCircle } from '@untitledui/icons';
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
import { Field, Select, Step, TextField, useViewMode, ViewToggle } from './controls';
import DataPolicyBuilder from './DataPolicyBuilder';
import PolicyFlowChart from './PolicyFlowChart';
import PolicyDiagram from './PolicyDiagram';
import SelectorBuilder from './SelectorBuilder';
import SubjectBuilder from './SubjectBuilder';
import { capabilities, MODES, type Engine } from './enforcement';
import { engineOptions, useSourceEngines } from '../../engines';
import { describePolicy } from './policyLanguage';

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
 */

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
 * The layers a subscription policy may currently be written at.
 *
 * <p>One, for now. The engine composes all seven and the stored documents
 * carry all seven, but a subscription written at an outer layer gates
 * everything beneath it -- which is the point of it and also the reason a
 * direct grant on one table can come out in force and admitting nobody. Until
 * the screens explain that where somebody meets it, offering the outer layers
 * in a form is offering a foot-gun. Data policies keep the full set: those
 * only ever add masking, so an outer one cannot lock anybody out.
 */
const SUBSCRIPTION_LEVELS: Policy['scopeLevel'][] = ['TABLE'];

/**
 * What the Level menu offers.
 *
 * <p>A policy already stored at a hidden layer keeps its own level in the
 * list. Dropping it would leave the control showing a value it does not have,
 * and the first save of an unrelated edit would quietly move an
 * organisation-wide policy onto one table.
 */
function levelOptions(policy: Policy) {
  if (policy.policyType !== 'SUBSCRIPTION') return SCOPE_LEVELS;
  return SCOPE_LEVELS.filter(
    (level) =>
      SUBSCRIPTION_LEVELS.includes(level.value) || level.value === policy.scopeLevel
  );
}

export default function PolicyBuilderPage() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const isNew = !id;

  // `?kind=` lets the header's Create menu ask for the kind before the form
  // opens. Subscription and data policies are different jobs; choosing between
  // them inside step one of a form is where that distinction goes to be missed.
  const kind = params.get('kind');
  // A draft suggested by an access request's review arrives in the
  // navigation state. It fills the form and nothing more: it is saved only
  // when the author presses "Create draft", and activated only through the
  // lifecycle, like any other policy.
  const location = useLocation();
  const suggested = isNew ? suggestedDraft(location.state) : null;
  const [draft, setDraft] = useState<Policy>(() => {
    if (suggested) return { ...EMPTY, ...suggested.draft };
    // A data policy still opens on the organisation, where masking by tag is
    // written once and covers everything. Only the subscription default moved
    // down to the table.
    if (kind === 'DATA') return { ...EMPTY, policyType: 'DATA', scopeLevel: 'ORG' };
    if (kind === 'SUBSCRIPTION') return { ...EMPTY, policyType: 'SUBSCRIPTION' };
    return EMPTY;
  });
  const [loaded, setLoaded] = useState<StoredPolicy | null>(null);
  // Which reading of the draft the form column shows. It defaults to the form
  // and is remembered per browser, so an author who never opens the chart sees
  // the page exactly as it has always been.
  const [view, setView] = useViewMode<'form' | 'flow' | 'diagram'>('arak.policy.view', 'form');
  // Null until the engine list arrives, then the first one the server lists.
  // Naming one here would be this page keeping its own copy of a list that
  // exists precisely so it does not have to.
  const [engine, setEngine] = useState<Engine | null>(null);
  const { data: engines } = useSourceEngines();
  useEffect(() => {
    const first = engines?.[0]?.id;
    if (first) setEngine((current) => current ?? first);
  }, [engines]);
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

  // One way in for a drafted document, whether it came from the dock or from
  // the button on this page, so the two cannot load it differently.
  const loadDrafted = (text: string): boolean => {
    try {
      const document = JSON.parse(text) as unknown;
      if (!document || typeof document !== 'object' || Array.isArray(document)) {
        throw new Error('not a document');
      }
      // Stripped as a suggestion from a request is: an id or a lifecycle
      // state would make the form pass for a stored, perhaps active, policy.
      const { id: _id, lifecycleState: _state, ...parsed } = document as Partial<Policy> & {
        id?: unknown;
        lifecycleState?: unknown;
      };
      // Merged over the empty document rather than used as-is. A model that
      // leaves a field out would otherwise hand the form an undefined where
      // it expects a value, and the control bound to it would go uncontrolled
      // mid-edit -- which looks like the form losing your typing.
      setDraft((current) => ({ ...EMPTY, ...current, ...parsed }));
      setAssistNote(
        'Loaded a draft from NokRak. Read every step before you save it'
          + ' — it is a suggestion, and it is your name on the policy.'
      );
      return true;
    } catch {
      setAssistNote(
        'NokRak answered with something that was not a policy document,'
          + ' so nothing was loaded. Try saying the rule a different way.'
      );
      return false;
    }
  };

  useEffect(() => {
    if (!drafted) {
      return;
    }
    takePolicy();
    loadDrafted(drafted.text);
    // loadDrafted only calls state setters, which never change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [drafted, takePolicy]);

  // The same drafting on the page, for a new policy only: over a stored one
  // it would overwrite a document somebody already signed, field by field.
  // It returns a document and stores nothing; "Create draft" is still the
  // only save, and the lifecycle the only way to switch it on.
  const draftReady = useAssistReady('DRAFT_POLICY');
  const [asking, setAsking] = useState(false);
  const ask = useMutation({
    mutationFn: (intent: string) => assistPolicy({ intent }),
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
      if (isNew) return createPolicy(draft);
      // The version the form was opened on, not one read back out of the
      // document: this is the question "has anyone changed it since", and a
      // conflict here is the screen catching an overwrite in time.
      return updatePolicy(id!, draft, loaded?.version ?? 1);
    },
    onSuccess: (saved) => {
      setSaveError(null);
      setLoaded(saved);
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
      queryClient.invalidateQueries({ queryKey: ['policies'] });
    },
    onError: (error) =>
      setSaveError(
        apiErrorMessage(error, 'The lifecycle change was refused.')
      ),
  });

  const bindings = useMutation({ mutationFn: () => resolveBindings(id!) });

  const sentences = useMemo(() => describePolicy(draft), [draft]);
  const modes = useMemo(
    () => (engine ? capabilities(draft, engine) : []),
    [draft, engine]
  );

  const incomplete = !draft.name.trim() || !hasCondition(draft);

  if (loadError) {
    return (
      <p className="tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
        {apiErrorMessage(loadError, 'Could not load this policy.')}
      </p>
    );
  }

  /**
   * Take the author from a box in the chart to the fields that write it.
   *
   * The switch back to the form has to happen before the scroll, and the scroll
   * has to wait for the form to exist — hence the deferral. Landing on a step
   * that is not on screen yet would scroll to nothing and leave the author at
   * the top of the page, which is the failure this is meant to avoid.
   */
  const editStep = (step: number) => {
    setView('form');
    window.setTimeout(
      () =>
        document
          .getElementById(`policy-step-${step}`)
          ?.scrollIntoView({ behavior: 'smooth', block: 'start' }),
      0,
    );
  };

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
            className={`tw:flex tw:size-12 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-xl ${
              draft.policyType === 'DATA'
                ? 'tw:bg-utility-purple-50 tw:text-utility-purple-600'
                : 'tw:bg-utility-brand-50 tw:text-utility-brand-600'
            }`}>
            {draft.policyType === 'DATA' ? <EyeOff className="tw:size-6" /> : <Key01 className="tw:size-6" />}
          </span>
          <div className="tw:min-w-0">
            <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
              <h1 className="tw:text-xl tw:font-semibold tw:text-primary">
                {isNew ? 'New policy' : draft.displayName || draft.name}
              </h1>
              {loaded && (
                <Badge color="gray" size="sm" type="pill-color">
                  v{loaded.version} · {loaded.lifecycleState.toLowerCase()}
                </Badge>
              )}
            </div>
            <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">
              Written once; enforced the same wherever it runs.
            </p>
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
          {isNew && draftReady && (
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

      {isNew && draftReady && asking && (
        <div className="tw:mt-4">
          <NokRakPrompt
            askLabel="Draft it"
            error={
              ask.isError
                ? apiErrorMessage(ask.error, 'NokRak could not draft a policy.')
                : null
            }
            hint="Fills the form below. Nothing is saved until you press Create draft, and it stays a draft until it is activated."
            onAsk={(intent) => ask.mutate(intent)}
            onClose={() => setAsking(false)}
            pending={ask.isPending}
            pendingLabel="Drafting…"
            placeholder="e.g. Mask every column tagged PII for anyone below clearance L2, and let the Finance team read the sales schema on weekdays."
            title="Tell NokRak the rule"
          />
        </div>
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
                : 'Click a step to jump to the fields that write it.'}
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
              <PolicyFlowChart onEdit={editStep} policy={draft} />
            </section>
          ) : view === 'diagram' ? (
            <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
              <PolicyDiagram onEdit={editStep} policy={draft} />
            </section>
          ) : (
            <>
          <Step
            description="The name is what everyone else will search for when they hit something they cannot explain."
            step={1}
            title="What this policy is">
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
              <Field
                hint="Subscription decides who reaches the table at all. Data decides what they see inside it. They are authored by different people at different times, which is why they are separate documents."
                label="Kind">
                <Select
                  onChange={(next) => {
                    const policyType = next as Policy['policyType'];
                    // Switching to a subscription lands on a layer it is
                    // allowed to be written at, rather than leaving the menu
                    // displaying a level the draft no longer offers.
                    patch(
                      policyType === 'SUBSCRIPTION' &&
                        !SUBSCRIPTION_LEVELS.includes(draft.scopeLevel)
                        ? { policyType, scopeLevel: 'TABLE' }
                        : { policyType }
                    );
                  }}
                  options={[
                    { value: 'SUBSCRIPTION', label: 'Subscription — who gets in' },
                    { value: 'DATA', label: 'Data — what they see' },
                  ]}
                  value={draft.policyType}
                />
              </Field>
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
          </Step>

          <Step
            description="Higher layers cannot be relaxed by lower ones. A table-level policy adds to what the organisation already said; it never takes it away."
            step={2}
            title="Where it sits">
            <div className="tw:grid tw:gap-4 tw:sm:grid-cols-2">
              <Field label="Level">
                <Select
                  onChange={(next) =>
                    patch({ scopeLevel: next as Policy['scopeLevel'] })
                  }
                  options={levelOptions(draft)}
                  value={draft.scopeLevel}
                />
              </Field>
              {draft.scopeLevel !== 'ORG' && (
                <Field
                  hint="The anchor this level is measured from, as a fully qualified name."
                  label="Anchor">
                  <TextField
                    onChange={(next) => patch({ scopeFqn: next || undefined })}
                    placeholder={
                      draft.scopeLevel === 'TABLE'
                        ? 'demo-pg.salesdb.sales.customer'
                        : 'prod-mssql.SalesDB.dbo'
                    }
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
          </Step>

          <Step
            description="Written against tags, terms and domains rather than table names, so an asset tagged tomorrow is covered tomorrow without anyone editing this."
            step={3}
            title="Which assets it covers">
            <SelectorBuilder
              onChange={(next) => patch({ selector: next })}
              value={draft.selector}
              vocabulary={vocabulary}
            />
          </Step>

          {draft.policyType === 'SUBSCRIPTION' ? (
            <Step
              description="Roles, attributes, an expression across both sides, and the hours it holds — all ANDed into one predicate."
              step={4}
              title="Who it is about">
              <div className="tw:mb-5">
                <Field
                  hint="A deny always beats an allow, anywhere in the stack, and no match at all is already a deny."
                  label="Effect">
                  <Select
                    className="tw:w-56"
                    onChange={(next) => patch({ effect: next as Policy['effect'] })}
                    options={[
                      { value: 'ALLOW', label: 'Allow' },
                      { value: 'DENY', label: 'Deny' },
                    ]}
                    value={draft.effect ?? 'ALLOW'}
                  />
                </Field>
              </div>
              <SubjectBuilder
                attributes={attributes}
                onChange={(next) => patch({ subject: next })}
                principals={principals}
                value={draft.subject}
              />
            </Step>
          ) : (
            <>
              <Step
                description="Optional. Leave it empty and the restrictions below apply to everyone who gets past the subscription policies."
                step={4}
                title="Who it is about">
                <SubjectBuilder
                  attributes={attributes}
                  onChange={(next) => patch({ subject: next })}
                  principals={principals}
                  value={draft.subject}
                />
              </Step>
              <Step
                description="Row filters and column rules — the RLS and masking half of the policy."
                step={5}
                title="What they see">
                <DataPolicyBuilder
                  attributes={attributes}
                  onChange={(next) => patch({ data: next })}
                  value={draft.data}
                  vocabulary={vocabulary}
                />
              </Step>
            </>
          )}
            </>
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
              <Select
                ariaLabel="Engine"
                className="tw:w-32 tw:py-1"
                onChange={(next) => setEngine(next as Engine)}
                options={engineOptions(engines)}
                value={engine ?? ''}
              />
            </div>
            <p className="tw:mt-2 tw:text-xs tw:text-tertiary">
              The mode is chosen per data source and can be overridden per asset.
              This says which of them would carry this policy whole.
            </p>
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
                return (
                  <div key={note.mode}>
                    <div className="tw:flex tw:items-center tw:gap-2">
                      <Icon className={`tw:size-4 ${tone}`} />
                      <span className="tw:text-sm tw:font-medium tw:text-primary">
                        {mode.title}
                      </span>
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
    </>
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
