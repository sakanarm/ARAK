import { useState, type ReactNode } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft,
  ArrowRight,
  Check,
  ChevronDown,
  Database01,
  Eye,
  EyeOff,
  Lock01,
  Plus,
  XClose,
} from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Chip as Badge } from '../../components/chips';
import { apiErrorMessage } from '../../api/client';
import {
  CREDENTIAL_SCHEMES,
  ENFORCEMENT_MODES,
  EVERY_TABLE,
  SEALED_CREDENTIAL,
  createSource,
  importSourceCatalog,
  isSealed,
  previewScope,
  testSourceTarget,
  updateSource,
  type EnforcementMode,
  type ScopeMatch,
  type ScopeRule,
  type Source,
  type SourceEngine,
  type SourceInput,
  type TableScope,
} from '../../api/sources';
import { enginePort, useSourceEngines } from '../../engines';
import { FIELD, Field, Select, TextField, ViewToggle } from '../policies/controls';
import { ImportReportView } from './ImportReport';
import { Notice } from './Notice';
import {
  MATCHES,
  MAX_VALUE,
  describeRule,
  describeScope,
  ruleProblem,
  scansEverything,
  scopeBadge,
  scopeProblem,
  scopeToSend,
} from './tableScope';

/**
 * Register or edit a source, in the three steps OpenMetadata's "add a service"
 * takes: choose the engine, connect, then read the catalog.
 *
 * The connect step is a column of cards rather than one long form, each card
 * saying on its face whether it still needs something — the connection by how
 * many required fields are empty, the credential by whether one is stored, the
 * scope by how many rules it has. The two cards most people never open, the
 * table scope and the enforcement settings, are folded with their current
 * value as the summary line, so a default is visible without being in the way.
 *
 * Inline rather than in a dialog, as the form it replaces was: the enforcement
 * mode is chosen by comparing it against what the other sources are set to.
 */

const STEPS = ['Choose the engine', 'Connect', 'Import the catalog'] as const;

type CredentialMode = 'typed' | 'pointer';

const BLANK: SourceInput = {
  name: '',
  // Chosen on the first step, from the server's list. Naming one here would be
  // a copy of a list this screen does not keep.
  engine: '',
  host: '',
  port: null,
  defaultDatabase: null,
  credentialRef: '',
  username: '',
  password: '',
  defaultEnforcementMode: 'NONE',
  omServiceFqn: null,
  secureSchema: 'sec',
  secureObjectPattern: '',
  enabled: true,
};

function draftOf(source: Source): SourceInput {
  return {
    name: source.name,
    engine: source.engine,
    host: source.host,
    port: source.port,
    defaultDatabase: source.defaultDatabase,
    // Served as `fernet:stored` for a sealed credential. Kept as-is and sent
    // back unchanged, which the server reads as "leave it alone"; blanking it
    // here would make every edit demand the password again.
    credentialRef: source.credentialRef,
    username: '',
    password: '',
    defaultEnforcementMode: source.defaultEnforcementMode,
    omServiceFqn: source.omServiceFqn,
    secureSchema: source.secureSchema,
    secureObjectPattern: source.secureObjectPattern,
    enabled: source.enabled,
    // Carried through rather than dropped: the server writes this column from
    // whatever the form sends, and the capability matrix reads it.
    engineVersion: source.engineVersion,
  };
}

export default function ConnectionWizard({
  source,
  onDone,
}: {
  source: Source | null;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const { data: engines, isLoading: enginesLoading } = useSourceEngines();
  const [step, setStep] = useState(source ? 1 : 0);
  const [draft, setDraft] = useState<SourceInput>(() => (source ? draftOf(source) : BLANK));
  const [scope, setScopeState] = useState<TableScope>(() => source?.tableScope ?? EVERY_TABLE);
  const [problem, setProblem] = useState<string | null>(null);
  const [saved, setSaved] = useState<Source | null>(null);

  const hasStoredCredential = source != null && isSealed(source.credentialRef);
  // An existing sealed credential opens on the mode that can replace it; a
  // pointer opens on the pointer. A new source opens on typing, because that is
  // what somebody registering their first source has in front of them.
  const [credentialMode, setCredentialMode] = useState<CredentialMode>(() =>
    source && !hasStoredCredential ? 'pointer' : 'typed'
  );

  const trial = useMutation({
    mutationFn: () =>
      testSourceTarget({
        ...draft,
        // Sent so the server can fall back to the stored credential for a
        // source being edited without its password retyped.
        id: source?.id,
      }),
    onMutate: () => setProblem(null),
    onError: (error) => setProblem(apiErrorMessage(error, 'The connection could not be tested.')),
  });

  const preview = useMutation({
    mutationFn: () =>
      previewScope({ ...draft, id: source?.id, tableScope: scopeToSend(scope) }),
  });

  function patch(next: Partial<SourceInput>) {
    setDraft((current) => ({ ...current, ...next }));
    // A result that outlived the host it was measured against reads as a
    // guarantee about the new one. The scope preview is measured against the
    // same connection, so it goes with it.
    trial.reset();
    preview.reset();
  }

  function setScope(next: TableScope) {
    setScopeState(next);
    preview.reset();
  }

  const save = useMutation({
    mutationFn: () => {
      const input = { ...draft, tableScope: scopeToSend(scope) };
      return source ? updateSource(source.id, input) : createSource(input);
    },
    onMutate: () => setProblem(null),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['sources'] });
      setSaved(result);
      setStep(2);
    },
    onError: (error) => setProblem(apiErrorMessage(error, 'The source could not be saved.')),
  });

  const engine = engines?.find((entry) => entry.id === draft.engine);
  const defaultPort = enginePort(engines, draft.engine);
  const scopeIssue = scopeProblem(scope);
  const credentialGiven =
    credentialMode === 'typed'
      ? Boolean(draft.username?.trim() && draft.password)
      : Boolean(draft.credentialRef.trim() && draft.credentialRef !== SEALED_CREDENTIAL);
  // Blank on an edit keeps what is stored, so only a new source must give one.
  const credentialReady = credentialGiven || hasStoredCredential;
  const missingConnection = draft.host.trim() ? 0 : 1;
  const canSave = Boolean(draft.name.trim() && draft.host.trim() && credentialReady && !scopeIssue);

  function chooseEngine(id: SourceEngine) {
    patch({
      engine: id,
      // Only when the box is empty, so a deliberate port survives a change of
      // mind about the engine.
      port: draft.port ?? enginePort(engines, id) ?? null,
    });
  }

  return (
    <section
      aria-label={source ? `Edit ${source.name}` : 'Add a new connection'}
      className="tw:flex tw:flex-col tw:gap-4">
      <header className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-5 tw:py-4">
        <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-between tw:gap-3">
          <div>
            <h2 className="tw:text-lg tw:font-semibold tw:text-primary">
              {source ? `Edit ${source.name}` : 'Add a new connection'}
            </h2>
            <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">
              {source
                ? 'Change how Arak reaches this database, what it reads, and how policy is applied there.'
                : 'Connect a database so its tables can be catalogued and its policies enforced.'}
            </p>
          </div>
          {step < 2 && (
            <Button color="tertiary" onPress={onDone} size="sm">
              Cancel
            </Button>
          )}
        </div>
        <Stepper
          current={step}
          // Back to the engine is a change of mind; forward past Connect is a
          // save, which only the Register button does.
          onPick={(index) => index < step && step < 2 && setStep(index)}
        />
      </header>

      {step === 0 && (
        <EnginePicker
          engines={engines}
          loading={enginesLoading}
          onChoose={chooseEngine}
          onNext={() => setStep(1)}
          selected={draft.engine}
        />
      )}

      {step === 1 && (
        <>
          <Card
            description="How this source is named in policies, reports and every asset's FQN."
            title="Name this source">
            <FormField label="Name" required>
              <TextField
                ariaLabel="Name"
                onChange={(next) => patch({ name: next })}
                placeholder="prod-mssql"
                value={draft.name}
              />
            </FormField>
          </Card>

          <Card
            badge={
              missingConnection > 0 ? (
                <Badge color="warning" size="sm" type="pill-color">
                  {missingConnection} required
                </Badge>
              ) : (
                <Badge color="success" size="sm" type="pill-color">
                  Complete
                </Badge>
              )
            }
            description={`Where Arak reaches ${engine?.displayName ?? 'the database'}. It connects read-only to test and to import.`}
            number={1}
            title="Connection">
            <div className="tw:grid tw:gap-4">
              <div className="tw:flex tw:flex-col tw:gap-1.5">
                <span className="tw:text-sm tw:font-medium tw:text-secondary">
                  Host and port
                  <Required />
                </span>
                <div className="tw:flex tw:gap-2">
                  <input
                    aria-label="Host"
                    className={`${FIELD} tw:min-w-0 tw:flex-1`}
                    onChange={(event) => patch({ host: event.target.value })}
                    placeholder="db.internal"
                    value={draft.host}
                  />
                  <input
                    aria-label="Port"
                    className={`${FIELD} tw:w-28`}
                    onChange={(event) =>
                      patch({ port: event.target.value.trim() === '' ? null : Number(event.target.value) })
                    }
                    placeholder={defaultPort ? String(defaultPort) : 'Port'}
                    type="number"
                    value={draft.port === null ? '' : String(draft.port)}
                  />
                </div>
                <span className="tw:text-xs tw:text-tertiary">
                  The address the Arak server can reach, not the one on your laptop.
                  {defaultPort ? ` A blank port uses ${defaultPort}.` : ''}
                </span>
              </div>
              <FormField
                hint="The database the import reads. Blank uses the login's default."
                label="Database">
                <TextField
                  ariaLabel="Database"
                  onChange={(next) => patch({ defaultDatabase: next || null })}
                  placeholder="SalesDB"
                  value={draft.defaultDatabase ?? ''}
                />
              </FormField>
            </div>
          </Card>

          <Card
            badge={
              credentialGiven ? (
                <Badge color="success" size="sm" type="pill-color">
                  Given
                </Badge>
              ) : hasStoredCredential ? (
                <Badge color="success" size="sm" type="pill-color">
                  Stored
                </Badge>
              ) : (
                <Badge color="warning" size="sm" type="pill-color">
                  Required
                </Badge>
              )
            }
            description="The login Arak uses to read the catalog and to create secure objects."
            number={2}
            title="Authentication">
            <ViewToggle
              label="How the credential is given"
              onChange={(next: CredentialMode) => {
                setCredentialMode(next);
                // Switching away from a half-typed credential must not leave it
                // queued behind the other mode's field.
                // A stored credential stays stored until something replaces it.
                patch(
                  next === 'typed'
                    ? { credentialRef: hasStoredCredential ? SEALED_CREDENTIAL : '' }
                    : { username: '', password: '' }
                );
              }}
              options={[
                { value: 'typed', label: 'Username and password' },
                { value: 'pointer', label: 'Secret store' },
              ]}
              value={credentialMode}
            />

            {credentialMode === 'typed' ? (
              <div className="tw:mt-4 tw:grid tw:gap-4 tw:sm:grid-cols-2">
                {hasStoredCredential && (
                  <p className="tw:text-xs tw:text-tertiary tw:sm:col-span-2">
                    A credential is already stored for this source. Leave both fields blank to keep
                    it, or fill both to replace it — the password cannot be re-sealed without the
                    username beside it.
                  </p>
                )}
                <FormField label="Username" required={!hasStoredCredential}>
                  <TextField
                    ariaLabel="Username"
                    onChange={(next) => patch({ username: next })}
                    placeholder="arak"
                    value={draft.username ?? ''}
                  />
                </FormField>
                <FormField
                  hint="Encrypted with the deployment key before it is stored."
                  label="Password"
                  required={!hasStoredCredential}>
                  <PasswordInput onChange={(next) => patch({ password: next })} value={draft.password ?? ''} />
                </FormField>
              </div>
            ) : (
              <div className="tw:mt-4">
                <FormField
                  hint={`A pointer to where the secret is kept. Accepted: ${CREDENTIAL_SCHEMES.join(', ')}`}
                  label="Credential reference"
                  required>
                  <TextField
                    ariaLabel="Credential reference"
                    onChange={(next) => patch({ credentialRef: next })}
                    placeholder="vault://secret/data/dac/prod-mssql"
                    value={draft.credentialRef === SEALED_CREDENTIAL ? '' : draft.credentialRef}
                  />
                </FormField>
              </div>
            )}

            <p className="tw:mt-4 tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-secondary tw:px-3 tw:py-2 tw:text-xs tw:text-tertiary">
              <Lock01 className="tw:mt-0.5 tw:size-3.5 tw:shrink-0" />
              One credential is kept per source. Whatever is typed here is never shown again once
              saved — the form shows that a credential exists, not what it is.
            </p>
          </Card>

          <ScopeCard
            defaultOpen={!scansEverything(source?.tableScope)}
            onChange={setScope}
            onPreview={() => preview.mutate()}
            preview={preview}
            previewDisabled={!draft.host.trim() || !draft.engine || scopeIssue !== null}
            scope={scope}
          />

          <AdvancedCard draft={draft} patch={patch} />

          {problem && <Notice tone="error">{problem}</Notice>}

          <ActionBar
            canSave={canSave}
            isNew={source === null}
            onBack={source ? undefined : () => setStep(0)}
            onSave={() => save.mutate()}
            onTest={() => trial.mutate()}
            saving={save.isPending}
            testDisabled={!draft.host.trim() || !draft.engine}
            testing={trial.isPending}
            trial={trial.data}
          />
        </>
      )}

      {step === 2 && saved && <ImportStep isNew={source === null} onDone={onDone} saved={saved} />}
    </section>
  );
}

function Stepper({ current, onPick }: { current: number; onPick: (index: number) => void }) {
  return (
    <ol aria-label="Steps" className="tw:mt-4 tw:flex tw:flex-wrap tw:items-center tw:gap-x-3 tw:gap-y-2">
      {STEPS.map((label, index) => {
        const done = index < current;
        const active = index === current;
        return (
          <li className="tw:flex tw:flex-1 tw:items-center tw:gap-3" key={label}>
            <button
              aria-current={active ? 'step' : undefined}
              className={`tw:flex tw:shrink-0 tw:items-center tw:gap-2 tw:text-sm ${
                done && current < 2 ? 'tw:cursor-pointer' : 'tw:cursor-default'
              }`}
              onClick={() => onPick(index)}
              type="button">
              <span
                className={`tw:flex tw:size-7 tw:items-center tw:justify-center tw:rounded-full tw:text-xs tw:font-semibold ${
                  done || active
                    ? 'tw:bg-brand-solid tw:text-white'
                    : 'tw:border tw:border-secondary tw:bg-primary tw:text-quaternary'
                }`}>
                {done ? <Check className="tw:size-4" /> : index + 1}
              </span>
              <span
                className={
                  active
                    ? 'tw:font-semibold tw:text-primary'
                    : done
                      ? 'tw:font-medium tw:text-secondary'
                      : 'tw:text-quaternary'
                }>
                {label}
              </span>
            </button>
            {index < STEPS.length - 1 && (
              <span
                aria-hidden
                className={`tw:hidden tw:h-0 tw:flex-1 tw:border-t tw:sm:block ${
                  done ? 'tw:border-brand' : 'tw:border-secondary'
                }`}
              />
            )}
          </li>
        );
      })}
    </ol>
  );
}

function EnginePicker({
  engines,
  loading,
  selected,
  onChoose,
  onNext,
}: {
  engines: { id: string; displayName: string; defaultPort: number; supportsSchemas: boolean }[] | undefined;
  loading: boolean;
  selected: string;
  onChoose: (id: SourceEngine) => void;
  onNext: () => void;
}) {
  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
      <h3 className="tw:text-md tw:font-semibold tw:text-primary">Which database is it?</h3>
      <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">
        The engines this build can govern. The choice decides the default port and what Arak can
        enforce there.
      </p>
      {loading && <p className="tw:mt-4 tw:text-sm tw:text-tertiary">Loading engines…</p>}
      <div
        aria-label="Engine"
        className="tw:mt-4 tw:grid tw:gap-3 tw:sm:grid-cols-2 tw:lg:grid-cols-3"
        role="radiogroup">
        {(engines ?? []).map((engine) => {
          const chosen = engine.id === selected;
          return (
            <button
              aria-checked={chosen}
              className={`tw:relative tw:flex tw:cursor-pointer tw:items-start tw:gap-3 tw:rounded-xl tw:border tw:p-4 tw:text-left tw:transition ${
                chosen
                  ? 'tw:border-brand tw:bg-utility-brand-50'
                  : 'tw:border-secondary tw:bg-primary tw:hover:bg-secondary'
              }`}
              key={engine.id}
              onClick={() => onChoose(engine.id)}
              onDoubleClick={() => {
                onChoose(engine.id);
                onNext();
              }}
              role="radio"
              type="button">
              <span
                aria-hidden
                className="tw:flex tw:size-10 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-utility-brand-50">
                <Database01 className="tw:size-5 tw:text-fg-brand-primary" />
              </span>
              <span className="tw:min-w-0">
                <span className="tw:block tw:text-sm tw:font-semibold tw:text-primary">
                  {engine.displayName}
                </span>
                <span className="tw:mt-0.5 tw:block tw:text-xs tw:text-tertiary">
                  Port {engine.defaultPort} ·{' '}
                  {engine.supportsSchemas ? 'database › schema › table' : 'database › table'}
                </span>
              </span>
              {chosen && (
                <span className="tw:absolute tw:top-3 tw:right-3 tw:flex tw:size-5 tw:items-center tw:justify-center tw:rounded-full tw:bg-brand-solid tw:text-white">
                  <Check className="tw:size-3.5" />
                </span>
              )}
            </button>
          );
        })}
      </div>
      <div className="tw:mt-5 tw:flex tw:justify-end">
        <Button color="primary" iconTrailing={ArrowRight} isDisabled={!selected} onPress={onNext} size="md">
          Next
        </Button>
      </div>
    </section>
  );
}

/** A card of the connect step, numbered like OpenMetadata's, optionally folded. */
function Card({
  number,
  title,
  description,
  badge,
  children,
  collapsible = false,
  open = true,
  onToggle,
  summary,
}: {
  number?: number;
  title: string;
  description: string;
  badge?: ReactNode;
  children: ReactNode;
  collapsible?: boolean;
  open?: boolean;
  onToggle?: () => void;
  summary?: string;
}) {
  const heading = (
    <div className="tw:flex tw:min-w-0 tw:flex-1 tw:items-start tw:gap-3">
      {number !== undefined && (
        <span className="tw:mt-0.5 tw:flex tw:size-6 tw:flex-none tw:items-center tw:justify-center tw:rounded-full tw:bg-brand-solid tw:text-xs tw:font-semibold tw:text-white">
          {number}
        </span>
      )}
      <div className="tw:min-w-0">
        <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          <h3 className="tw:text-md tw:font-semibold tw:text-primary">{title}</h3>
          {badge}
        </div>
        <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">{description}</p>
        {collapsible && !open && summary && (
          <p className="tw:mt-1.5 tw:text-sm tw:text-secondary">{summary}</p>
        )}
      </div>
    </div>
  );

  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
      {collapsible ? (
        <button
          aria-expanded={open}
          className="tw:flex tw:w-full tw:cursor-pointer tw:items-start tw:gap-3 tw:text-left"
          onClick={onToggle}
          type="button">
          {heading}
          <ChevronDown
            aria-hidden
            className={`tw:mt-1 tw:size-5 tw:shrink-0 tw:text-fg-quaternary tw:transition-transform ${
              open ? 'tw:rotate-180' : ''
            }`}
          />
        </button>
      ) : (
        heading
      )}
      {open && <div className="tw:mt-5">{children}</div>}
    </section>
  );
}

function Required() {
  return (
    <span aria-hidden className="tw:ml-0.5 tw:text-error-primary">
      *
    </span>
  );
}

/** The policy builder's Field, with a required mark. */
function FormField({
  label,
  hint,
  required = false,
  children,
}: {
  label: string;
  hint?: string;
  required?: boolean;
  children: ReactNode;
}) {
  return (
    <label className="tw:flex tw:flex-col tw:gap-1.5">
      <span className="tw:text-sm tw:font-medium tw:text-secondary">
        {label}
        {required && <Required />}
      </span>
      {children}
      {hint && <span className="tw:text-xs tw:text-tertiary">{hint}</span>}
    </label>
  );
}

function PasswordInput({ value, onChange }: { value: string; onChange: (next: string) => void }) {
  const [shown, setShown] = useState(false);
  return (
    <span className="tw:relative tw:flex">
      <input
        aria-label="Password"
        autoComplete="new-password"
        className={`${FIELD} tw:w-full tw:pr-10`}
        onChange={(event) => onChange(event.target.value)}
        type={shown ? 'text' : 'password'}
        value={value}
      />
      <button
        aria-label={shown ? 'Hide password' : 'Show password'}
        className="tw:absolute tw:inset-y-0 tw:right-0 tw:flex tw:w-10 tw:cursor-pointer tw:items-center tw:justify-center tw:text-fg-quaternary tw:hover:text-fg-secondary"
        onClick={(event) => {
          // Inside a label: without this the click also focuses the input and
          // a second click toggles twice.
          event.preventDefault();
          setShown((current) => !current);
        }}
        type="button">
        {shown ? <EyeOff className="tw:size-4" /> : <Eye className="tw:size-4" />}
      </button>
    </span>
  );
}

function ScopeCard({
  scope,
  onChange,
  defaultOpen,
  preview,
  previewDisabled,
  onPreview,
}: {
  scope: TableScope;
  onChange: (next: TableScope) => void;
  defaultOpen: boolean;
  preview: {
    data?: { total: number; inScope: number; excluded: number; inScopeSample: string[]; excludedSample: string[] };
    error: unknown;
    isPending: boolean;
  };
  previewDisabled: boolean;
  onPreview: () => void;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const issue = scopeProblem(scope);
  const everything = scansEverything(scope);

  return (
    <Card
      badge={
        <>
          <Badge color="gray" size="sm" type="pill-color">
            Optional
          </Badge>
          <Badge color={everything ? 'success' : 'brand'} size="sm" type="pill-color">
            {scopeBadge(scope)}
          </Badge>
        </>
      }
      collapsible
      description="Which tables the import reads. It decides what is catalogued — it hides nothing from anyone."
      number={3}
      onToggle={() => setOpen((current) => !current)}
      open={open}
      summary={describeScope(scope)}
      title="Scope & options">
      <div className="tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-4">
        <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-between tw:gap-2">
          <h4 className="tw:text-sm tw:font-semibold tw:text-primary">Tables</h4>
          <Badge color={everything ? 'success' : 'brand'} size="sm" type="pill-color">
            {scopeBadge(scope)}
          </Badge>
        </div>

        <div className="tw:mt-3 tw:flex tw:flex-col tw:gap-1.5">
          <span className="tw:text-sm tw:font-medium tw:text-secondary">What to scan</span>
          <div>
            <ViewToggle
              label="What to scan"
              onChange={(mode) => onChange({ ...scope, mode })}
              options={[
                { value: 'ALL', label: 'Scan all tables' },
                { value: 'ONLY', label: 'Only specific tables' },
              ]}
              value={scope.mode}
            />
          </div>
        </div>

        {scope.mode === 'ONLY' && (
          <RuleBuilder
            hint="A table is read when any of these match it."
            label="Scan tables where the name"
            onChange={(include) => onChange({ ...scope, include })}
            placeholder="e.g. dim_"
            rules={scope.include}
          />
        )}

        <RuleBuilder
          hint="Left out whichever way the scan is set — scratch copies, backups, staging."
          label="Always exclude where the name"
          onChange={(exclude) => onChange({ ...scope, exclude })}
          placeholder="e.g. TMP_"
          rules={scope.exclude}
        />

        <p className="tw:mt-3 tw:text-xs tw:text-tertiary">
          Case does not matter. Put a dot in the text to compare with schema.table instead —
          “staging.” covers a whole schema.
        </p>

        <div className="tw:mt-4 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:p-3">
          <div className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-3">
            <div className="tw:min-w-0 tw:flex-1">
              <span className="tw:text-xs tw:font-medium tw:uppercase tw:tracking-wide tw:text-quaternary">
                Preview
              </span>
              <p className="tw:mt-0.5 tw:text-sm tw:text-secondary" data-testid="scope-sentence">
                {describeScope(scope)}
              </p>
            </div>
            <Button
              color="secondary"
              isDisabled={previewDisabled || preview.isPending}
              onPress={onPreview}
              size="sm">
              {preview.isPending ? 'Listing tables…' : 'Check against the database'}
            </Button>
          </div>
          {issue && (
            <div className="tw:mt-3">
              <Notice tone="warning">{issue}</Notice>
            </div>
          )}
          {preview.error != null && (
            <div className="tw:mt-3">
              <Notice tone="error">
                {apiErrorMessage(preview.error, 'The tables could not be listed.')}
              </Notice>
            </div>
          )}
          {preview.data && (
            <div className="tw:mt-3 tw:flex tw:flex-col tw:gap-2">
              <p className="tw:text-sm tw:font-medium tw:text-primary">
                {preview.data.inScope.toLocaleString()} of {preview.data.total.toLocaleString()}{' '}
                tables and views are in scope · {preview.data.excluded.toLocaleString()} left out
              </p>
              <div className="tw:grid tw:gap-3 tw:sm:grid-cols-2">
                <Sample count={preview.data.inScope} names={preview.data.inScopeSample} title="Read" />
                <Sample count={preview.data.excluded} names={preview.data.excludedSample} title="Left out" />
              </div>
            </div>
          )}
        </div>
      </div>
    </Card>
  );
}

function Sample({ title, names, count }: { title: string; names: string[]; count: number }) {
  return (
    <div>
      <span className="tw:text-xs tw:font-medium tw:text-tertiary">{title}</span>
      {names.length === 0 ? (
        <p className="tw:text-xs tw:text-quaternary">None.</p>
      ) : (
        <ul aria-label={title} className="tw:mt-1 tw:max-h-40 tw:overflow-auto tw:font-mono tw:text-xs tw:text-secondary">
          {names.map((name) => (
            <li key={name}>{name}</li>
          ))}
          {count > names.length && (
            <li className="tw:font-sans tw:text-quaternary">
              and {(count - names.length).toLocaleString()} more
            </li>
          )}
        </ul>
      )}
    </div>
  );
}

function RuleBuilder({
  label,
  hint,
  placeholder,
  rules,
  onChange,
}: {
  label: string;
  hint: string;
  placeholder: string;
  rules: ScopeRule[];
  onChange: (next: ScopeRule[]) => void;
}) {
  const [match, setMatch] = useState<ScopeMatch>('STARTS_WITH');
  const [value, setValue] = useState('');
  const [problem, setProblem] = useState<string | null>(null);

  function add() {
    const candidate = { match, value: value.trim() };
    const reason = ruleProblem(rules, candidate);
    if (reason) {
      setProblem(reason);
      return;
    }
    onChange([...rules, candidate]);
    setValue('');
    setProblem(null);
  }

  return (
    <div className="tw:mt-4 tw:flex tw:flex-col tw:gap-1.5">
      <span className="tw:text-sm tw:font-medium tw:text-secondary">{label}</span>
      <div className="tw:flex tw:flex-wrap tw:gap-2">
        <Select
          ariaLabel={`${label}: comparison`}
          className="tw:w-40"
          onChange={(next) => setMatch(next as ScopeMatch)}
          options={MATCHES.map((entry) => ({ value: entry.value, label: entry.label }))}
          value={match}
        />
        <input
          aria-label={`${label}: text`}
          className={`${FIELD} tw:min-w-0 tw:flex-1`}
          maxLength={MAX_VALUE}
          onChange={(event) => {
            setValue(event.target.value);
            setProblem(null);
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              add();
            }
          }}
          placeholder={placeholder}
          value={value}
        />
        <Button color="secondary" iconLeading={Plus} onPress={add} size="sm">
          Add
        </Button>
      </div>
      {problem && <p className="tw:text-xs tw:text-error-primary">{problem}</p>}
      <span className="tw:text-xs tw:text-tertiary">{hint}</span>
      {rules.length > 0 && (
        <ul aria-label={label} className="tw:mt-1 tw:flex tw:flex-wrap tw:gap-2">
          {rules.map((rule, index) => (
            <li
              className="tw:flex tw:items-center tw:gap-1.5 tw:rounded-full tw:border tw:border-secondary tw:bg-primary tw:py-0.5 tw:pr-1 tw:pl-2.5 tw:text-xs"
              key={`${rule.match}:${rule.value}`}>
              <span className="tw:text-tertiary">
                {MATCHES.find((entry) => entry.value === rule.match)?.label.toLowerCase()}
              </span>
              <span className="tw:font-mono tw:text-primary">{rule.value}</span>
              <button
                aria-label={`Remove: ${describeRule(rule)}`}
                className="tw:flex tw:size-5 tw:cursor-pointer tw:items-center tw:justify-center tw:rounded-full tw:text-fg-quaternary tw:hover:bg-secondary tw:hover:text-fg-secondary"
                onClick={() => onChange(rules.filter((_, at) => at !== index))}
                type="button">
                <XClose className="tw:size-3" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function AdvancedCard({
  draft,
  patch,
}: {
  draft: SourceInput;
  patch: (next: Partial<SourceInput>) => void;
}) {
  const [open, setOpen] = useState(false);
  const mode = ENFORCEMENT_MODES.find((entry) => entry.value === draft.defaultEnforcementMode);

  return (
    <Card
      collapsible
      description="How policy is applied here, and where generated objects go. The defaults suit a first connection."
      onToggle={() => setOpen((current) => !current)}
      open={open}
      summary={`${mode?.label ?? draft.defaultEnforcementMode} · secure objects in ${draft.secureSchema || 'sec'}.${(draft.secureObjectPattern || '{table}').replaceAll('{table}', '<table>')}${
        draft.omServiceFqn ? ` · linked to ${draft.omServiceFqn}` : ''
      }`}
      title="Advanced config">
      <div className="tw:grid tw:gap-4 tw:sm:grid-cols-2">
        <Field className="tw:sm:col-span-2" hint={mode?.what} label="Default enforcement mode">
          <Select
            onChange={(next) => patch({ defaultEnforcementMode: next as EnforcementMode })}
            options={ENFORCEMENT_MODES.map((entry) => ({ value: entry.value, label: entry.label }))}
            value={draft.defaultEnforcementMode}
          />
        </Field>
        <Field
          className="tw:sm:col-span-2"
          hint="The service name in OpenMetadata whose tables live on this database. Once set, imported tables are named under it too, so a crawl and an import describe one asset rather than two."
          label="OpenMetadata service">
          <TextField
            onChange={(next) => patch({ omServiceFqn: next || null })}
            placeholder="prod-mssql"
            value={draft.omServiceFqn ?? ''}
          />
        </Field>
        <Field
          hint="Where generated secure views are created. Letters, digits and underscores only — it is concatenated into DDL."
          label="Secure schema">
          <TextField
            onChange={(next) => patch({ secureSchema: next })}
            placeholder="sec"
            value={draft.secureSchema}
          />
        </Field>
        <Field
          hint="Appended to the table name to form the view name. Blank keeps the table's own name inside the secure schema."
          label="Secure object suffix">
          <TextField
            onChange={(next) => patch({ secureObjectPattern: next })}
            placeholder="_secure"
            value={draft.secureObjectPattern}
          />
        </Field>
      </div>
    </Card>
  );
}

/**
 * The bar under the connect step: the connection's state on the left, what to
 * do next on the right — OpenMetadata's "test your connection to continue".
 *
 * Registering is not held behind a passing test. A database the server cannot
 * reach yet is still worth registering, so a policy can be written against it
 * before the firewall ticket is closed; the bar says so rather than refusing.
 */
function ActionBar({
  trial,
  testing,
  testDisabled,
  onTest,
  canSave,
  saving,
  isNew,
  onSave,
  onBack,
}: {
  trial?: { reachable: boolean; productName: string; engineVersion: string; millis: number; message: string };
  testing: boolean;
  testDisabled: boolean;
  onTest: () => void;
  canSave: boolean;
  saving: boolean;
  isNew: boolean;
  onSave: () => void;
  onBack?: () => void;
}) {
  return (
    <div className="tw:sticky tw:bottom-0 tw:z-10 tw:flex tw:flex-wrap tw:items-center tw:justify-between tw:gap-3 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-5 tw:py-3 tw:shadow-md">
      <div aria-live="polite" className="tw:min-w-0 tw:flex-1 tw:text-sm">
        {testing ? (
          <span className="tw:text-tertiary">Connecting…</span>
        ) : trial ? (
          trial.reachable ? (
            <span className="tw:flex tw:items-center tw:gap-2 tw:text-success-primary">
              <Check className="tw:size-4 tw:shrink-0" />
              {trial.productName} {trial.engineVersion} answered in {trial.millis} ms.
            </span>
          ) : (
            <span className="tw:text-error-primary">{trial.message}</span>
          )
        ) : (
          <span className="tw:text-tertiary">
            Test your connection before you {isNew ? 'register' : 'save'} — it opens one read-only
            connection and saves nothing.
          </span>
        )}
      </div>
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        {onBack && (
          <Button color="tertiary" iconLeading={ArrowLeft} onPress={onBack} size="md">
            Back
          </Button>
        )}
        <Button color="secondary" isDisabled={testDisabled || testing} onPress={onTest} size="md">
          {testing ? 'Connecting…' : 'Test connection'}
        </Button>
        <Button color="primary" isDisabled={!canSave || saving} onPress={onSave} size="md">
          {saving ? 'Saving…' : isNew ? 'Register' : 'Save changes'}
        </Button>
      </div>
    </div>
  );
}

function ImportStep({
  saved,
  isNew,
  onDone,
}: {
  saved: Source;
  isNew: boolean;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const run = useMutation({
    mutationFn: () => importSourceCatalog(saved.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sources'] }),
  });

  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
      <Notice tone="success">
        {isNew ? `${saved.name} is registered.` : `Changes to ${saved.name} are saved.`}
      </Notice>
      <h3 className="tw:mt-4 tw:text-md tw:font-semibold tw:text-primary">Import the catalog</h3>
      <p className="tw:mt-0.5 tw:max-w-3xl tw:text-sm tw:text-tertiary">
        Arak reads the names and column types of the tables this login can see — never a row — so
        policies can be written against a database OpenMetadata has never ingested.
        {isNew ? '' : ' Import again after changing the scope to apply it.'}
      </p>
      <p className="tw:mt-3 tw:flex tw:flex-wrap tw:items-center tw:gap-2 tw:text-sm tw:text-secondary">
        <Badge color={scansEverything(saved.tableScope) ? 'success' : 'brand'} size="sm" type="pill-color">
          {scopeBadge(saved.tableScope)}
        </Badge>
        {describeScope(saved.tableScope)}
      </p>

      {run.error != null && (
        <div className="tw:mt-4">
          <Notice tone="error">{apiErrorMessage(run.error, 'The catalog could not be imported.')}</Notice>
        </div>
      )}
      {run.data && (
        <div className="tw:mt-4">
          <ImportReportView report={run.data} />
        </div>
      )}

      <div className="tw:mt-5 tw:flex tw:flex-wrap tw:gap-3">
        {!run.data && (
          <Button color="primary" isDisabled={run.isPending} onPress={() => run.mutate()} size="md">
            {run.isPending ? 'Importing…' : 'Import tables now'}
          </Button>
        )}
        <Button color={run.data ? 'primary' : 'secondary'} onPress={onDone} size="md">
          {run.data ? 'Done' : 'Skip for now'}
        </Button>
      </div>
    </section>
  );
}
