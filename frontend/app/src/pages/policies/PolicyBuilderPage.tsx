import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { AlertTriangle, CheckCircle, XCircle } from '@untitledui/icons';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../../api/client';
import {
  fetchAttributeVocabulary,
  fetchPrincipals,
  fetchVocabulary,
} from '../../api/governance';
import {
  createPolicy,
  fetchPolicy,
  resolveBindings,
  transitionPolicy,
  updatePolicy,
  type StoredPolicy,
} from '../../api/policies';
import type { Policy } from '../../generated/entity/policy/policy';
import { Field, Select, Step, TextField } from './controls';
import DataPolicyBuilder from './DataPolicyBuilder';
import SelectorBuilder from './SelectorBuilder';
import SubjectBuilder from './SubjectBuilder';
import { capabilities, MODES, type Engine } from './enforcement';
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

const EMPTY: Policy = {
  name: '',
  policyType: 'SUBSCRIPTION',
  scopeLevel: 'ORG',
  selector: {},
  effect: 'ALLOW',
  environment: 'dev',
};

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
  const [draft, setDraft] = useState<Policy>(() =>
    kind === 'DATA' || kind === 'SUBSCRIPTION'
      ? { ...EMPTY, policyType: kind }
      : EMPTY
  );
  const [loaded, setLoaded] = useState<StoredPolicy | null>(null);
  const [engine, setEngine] = useState<Engine>('POSTGRES');
  const [saveError, setSaveError] = useState<string | null>(null);

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
  const modes = useMemo(() => capabilities(draft, engine), [draft, engine]);

  const incomplete = !draft.name.trim() || !hasCondition(draft);

  if (loadError) {
    return (
      <p className="tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
        {apiErrorMessage(loadError, 'Could not load this policy.')}
      </p>
    );
  }

  return (
    <>
      <header className="tw:flex tw:flex-wrap tw:items-end tw:justify-between tw:gap-4">
        <div>
          <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">
            {isNew ? 'New policy' : draft.displayName || draft.name}
          </h1>
          <p className="tw:mt-2 tw:text-md tw:text-tertiary">
            One document, three places it can be enforced. What you write here is
            the same in every mode; only where it is carried out changes.
          </p>
        </div>
        <div className="tw:flex tw:items-center tw:gap-2">
          {loaded && (
            <Badge color="gray" size="sm" type="pill-color">
              v{loaded.version} · {loaded.lifecycleState.toLowerCase()}
            </Badge>
          )}
          {loaded && (
            <Button
              color="link-gray"
              onPress={() => navigate(`/policies/${loaded.id}`)}
              size="md">
              Done
            </Button>
          )}
          <Button
            isDisabled={incomplete || save.isPending}
            onPress={() => save.mutate()}
            size="md">
            {save.isPending ? 'Saving…' : isNew ? 'Create draft' : 'Save'}
          </Button>
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
        </div>
      </header>

      {saveError && (
        <p className="tw:mt-4 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
          {saveError}
        </p>
      )}

      <div className="tw:mt-8 tw:grid tw:gap-6 tw:xl:grid-cols-[minmax(0,1fr)_380px]">
        {/* ------------------------------------------------------- the form */}
        <div className="tw:flex tw:flex-col tw:gap-5">
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
              <Field label="Environment">
                <Select
                  onChange={(next) =>
                    patch({ environment: next as Policy['environment'] })
                  }
                  options={[
                    { value: 'dev', label: 'dev' },
                    { value: 'uat', label: 'uat' },
                    { value: 'prod', label: 'prod' },
                  ]}
                  value={draft.environment ?? 'dev'}
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
                  options={[
                    { value: 'ORG', label: 'Organisation' },
                    { value: 'DOMAIN', label: 'Domain or sub-domain' },
                    { value: 'SERVICE', label: 'Service' },
                    { value: 'DATABASE', label: 'Database' },
                    { value: 'SCHEMA', label: 'Schema' },
                    { value: 'TABLE', label: 'Table' },
                    { value: 'COLUMN', label: 'Column' },
                  ]}
                  value={draft.scopeLevel}
                />
              </Field>
              {draft.scopeLevel !== 'ORG' && (
                <Field
                  hint="The anchor this level is measured from, as a fully qualified name."
                  label="Anchor">
                  <TextField
                    onChange={(next) => patch({ scopeFqn: next || undefined })}
                    placeholder="prod-mssql.SalesDB.dbo"
                    value={draft.scopeFqn ?? ''}
                  />
                </Field>
              )}
              <Field
                className="tw:sm:col-span-2"
                hint="Off by default. Turning it on is an audited decision: it lets someone below you widen what this policy restricted."
                label="May a lower layer relax this?">
                <Select
                  onChange={(next) =>
                    patch({ allowLocalOverride: next === 'yes' })
                  }
                  options={[
                    { value: 'no', label: 'No — lower layers may only add' },
                    { value: 'yes', label: 'Yes — and every use is recorded' },
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
        </div>

        {/* ------------------------------------------------------- the rail */}
        <aside className="tw:flex tw:flex-col tw:gap-4 tw:xl:sticky tw:xl:top-6 tw:xl:self-start">
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
                options={[
                  { value: 'POSTGRES', label: 'PostgreSQL' },
                  { value: 'SQLSERVER', label: 'SQL Server' },
                ]}
                value={engine}
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
