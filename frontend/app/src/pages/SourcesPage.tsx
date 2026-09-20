import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  CheckCircle,
  Database01,
  Plus,
  Server01,
} from '@untitledui/icons';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../api/client';
import {
  CREDENTIAL_SCHEMES,
  ENFORCEMENT_MODES,
  createSource,
  deleteSource,
  fetchSources,
  setSourceEnabled,
  testSource,
  updateSource,
  type EnforcementMode,
  type ProbeResult,
  type Source,
  type SourceEngine,
  type SourceInput,
} from '../api/sources';
import { useAuthStore } from '../auth/authStore';
import { Field, Select, TextField } from './policies/controls';

/**
 * The source registry (FR-6.0a) — where enforcement lands.
 *
 * A policy in this product is a document until a source carries it, and which
 * of the three modes a source is set to decides what "carry" means: a security
 * policy on the production table, a generated view beside it, or a rewrite in
 * the query API. That choice is an operational decision about a database rather
 * than a governance decision about data, which is why the server only lets an
 * administrator make it while letting every data owner read it.
 *
 * The credential field holds a *reference* to a secret and the server rejects
 * anything else. That is deliberate and worth the friction: the moment a
 * console accepts a password in a text box, the password is in the database,
 * the backups and somebody's screen recording.
 */

const DEFAULT_PORT: Record<SourceEngine, number> = {
  POSTGRES: 5432,
  SQLSERVER: 1433,
};

const BLANK: SourceInput = {
  name: '',
  engine: 'POSTGRES',
  host: '',
  port: null,
  defaultDatabase: null,
  credentialRef: '',
  defaultEnforcementMode: 'NONE',
  omServiceFqn: null,
  secureSchema: 'sec',
  secureObjectPattern: '',
  enabled: true,
};

export default function SourcesPage() {
  const isAdmin = useAuthStore((state) => state.hasRole('PLATFORM_ADMIN'));
  const [editing, setEditing] = useState<Source | 'new' | null>(null);

  const {
    data: sources,
    error,
    isLoading,
  } = useQuery({ queryKey: ['sources'], queryFn: fetchSources });

  return (
    <div className="tw:flex tw:flex-col tw:gap-5">
      <header className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-3">
        <div>
          <h1 className="tw:text-display-xs tw:font-semibold tw:text-primary">
            Sources
          </h1>
          <p className="tw:mt-1 tw:max-w-3xl tw:text-sm tw:text-tertiary">
            The databases policy is enforced in. OpenMetadata says what exists;
            a source says how a decision about it is applied, and with which
            credential.
          </p>
        </div>
        {isAdmin && editing === null && (
          <Button
            color="primary"
            iconLeading={Plus}
            onPress={() => setEditing('new')}
            size="md">
            Register a source
          </Button>
        )}
      </header>

      {editing !== null && (
        <SourceForm
          onDone={() => setEditing(null)}
          source={editing === 'new' ? null : editing}
        />
      )}

      {error && (
        <Notice tone="error">
          {apiErrorMessage(error, 'The source list could not be loaded.')}
        </Notice>
      )}

      {isLoading && <p className="tw:text-sm tw:text-tertiary">Loading…</p>}

      {sources && sources.length === 0 && editing === null && (
        <div className="tw:flex tw:flex-col tw:items-center tw:gap-3 tw:rounded-xl tw:border tw:border-dashed tw:border-secondary tw:bg-primary tw:px-6 tw:py-12 tw:text-center">
          <Server01 className="tw:size-7 tw:text-fg-quaternary" />
          <p className="tw:max-w-md tw:text-sm tw:text-tertiary">
            No source is registered. Policies can still be written against the
            catalog, but nothing will be enforced anywhere until a database is
            registered here and given a mode.
          </p>
          {isAdmin && (
            <Button color="secondary" onPress={() => setEditing('new')} size="sm">
              Register the first one
            </Button>
          )}
        </div>
      )}

      {sources && sources.length > 0 && (
        <ul className="tw:flex tw:flex-col tw:gap-4">
          {sources.map((source) => (
            <SourceCard
              isAdmin={isAdmin}
              key={source.id}
              onEdit={() => setEditing(source)}
              source={source}
            />
          ))}
        </ul>
      )}
    </div>
  );
}

function SourceCard({
  source,
  isAdmin,
  onEdit,
}: {
  source: Source;
  isAdmin: boolean;
  onEdit: () => void;
}) {
  const queryClient = useQueryClient();
  const [probe, setProbe] = useState<ProbeResult | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: ['sources'] });

  const test = useMutation({
    mutationFn: () => testSource(source.id),
    onSuccess: (result) => {
      setProblem(null);
      setProbe(result);
      invalidate();
    },
    onError: (error) =>
      setProblem(apiErrorMessage(error, 'The connection could not be tested.')),
  });

  const toggle = useMutation({
    mutationFn: () => setSourceEnabled(source.id, !source.enabled),
    onSuccess: invalidate,
    onError: (error) =>
      setProblem(apiErrorMessage(error, 'The source could not be changed.')),
  });

  const remove = useMutation({
    mutationFn: () => deleteSource(source.id),
    onSuccess: invalidate,
    onError: (error) => {
      setConfirmingDelete(false);
      // The server refuses to delete a source that still governs rows. That is
      // the message worth showing verbatim: it names what would be orphaned.
      setProblem(apiErrorMessage(error, 'The source could not be removed.'));
    },
  });

  const mode = ENFORCEMENT_MODES.find(
    (entry) => entry.value === source.defaultEnforcementMode
  );

  return (
    <li className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
      <div className="tw:flex tw:flex-wrap tw:items-start tw:gap-4 tw:px-5 tw:py-4">
        <span
          aria-hidden
          className="tw:flex tw:size-10 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-utility-brand-50">
          <Database01 className="tw:size-5 tw:text-fg-brand-primary" />
        </span>

        <div className="tw:min-w-0 tw:flex-1">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <h2 className="tw:truncate tw:text-md tw:font-semibold tw:text-primary">
              {source.name}
            </h2>
            <Badge color="gray" size="sm" type="pill-color">
              {source.engine === 'SQLSERVER' ? 'SQL Server' : 'PostgreSQL'}
              {source.engineVersion ? ` ${source.engineVersion}` : ''}
            </Badge>
            <Badge color={modeColor(source.defaultEnforcementMode)} size="sm" type="pill-color">
              {mode?.label ?? source.defaultEnforcementMode}
            </Badge>
            {!source.enabled && (
              <Badge color="warning" size="sm" type="pill-color">
                Disabled
              </Badge>
            )}
          </div>

          <p className="tw:mt-1 tw:truncate tw:text-sm tw:text-tertiary">
            {source.host}:{source.port}
            {source.defaultDatabase ? ` · ${source.defaultDatabase}` : ''} ·{' '}
            {source.assetCount.toLocaleString()} catalogued{' '}
            {source.assetCount === 1 ? 'asset' : 'assets'}
          </p>

          {mode && (
            <p className="tw:mt-2 tw:max-w-3xl tw:text-sm tw:text-secondary">
              {mode.what}
            </p>
          )}

          <dl className="tw:mt-3 tw:grid tw:gap-x-6 tw:gap-y-1 tw:text-xs tw:sm:grid-cols-2">
            <Pair label="Credential" value={source.credentialRef} />
            <Pair
              label="OpenMetadata service"
              value={source.omServiceFqn ?? 'not linked'}
            />
            <Pair
              label="Secure objects"
              value={`${source.secureSchema}.${source.secureObjectPattern || '<name>'}`}
            />
            <Pair label="Updated" value={new Date(source.updatedAt).toLocaleString()} />
          </dl>
        </div>

        {isAdmin && (
          <div className="tw:flex tw:shrink-0 tw:flex-wrap tw:items-center tw:gap-2">
            <Button
              color="secondary"
              isDisabled={test.isPending}
              onPress={() => test.mutate()}
              size="sm">
              {test.isPending ? 'Testing…' : 'Test connection'}
            </Button>
            <Button color="tertiary" onPress={onEdit} size="sm">
              Edit
            </Button>
            <Button
              color="tertiary"
              isDisabled={toggle.isPending}
              onPress={() => toggle.mutate()}
              size="sm">
              {source.enabled ? 'Disable' : 'Enable'}
            </Button>
            {confirmingDelete ? (
              <>
                <Button
                  color="primary-destructive"
                  isDisabled={remove.isPending}
                  onPress={() => remove.mutate()}
                  size="sm">
                  Confirm
                </Button>
                <Button
                  color="tertiary"
                  onPress={() => setConfirmingDelete(false)}
                  size="sm">
                  Cancel
                </Button>
              </>
            ) : (
              <Button
                color="link-destructive"
                onPress={() => setConfirmingDelete(true)}
                size="sm">
                Remove
              </Button>
            )}
          </div>
        )}
      </div>

      {(probe || problem) && (
        <div className="tw:border-t tw:border-secondary tw:px-5 tw:py-3">
          {problem && <Notice tone="error">{problem}</Notice>}
          {probe && !problem && (
            <Notice tone={probe.reachable ? 'success' : 'error'}>
              {probe.reachable
                ? `${probe.productName} ${probe.engineVersion} answered in ${probe.millis} ms.`
                : probe.message}
            </Notice>
          )}
        </div>
      )}
    </li>
  );
}

/**
 * Register or edit one source.
 *
 * Inline rather than in a dialog: half of these fields need the page behind
 * them to be readable — the enforcement mode is chosen by comparing it against
 * what the other sources are set to.
 */
function SourceForm({
  source,
  onDone,
}: {
  source: Source | null;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<SourceInput>(() =>
    source
      ? {
          name: source.name,
          engine: source.engine,
          host: source.host,
          port: source.port,
          defaultDatabase: source.defaultDatabase,
          credentialRef: source.credentialRef,
          defaultEnforcementMode: source.defaultEnforcementMode,
          omServiceFqn: source.omServiceFqn,
          secureSchema: source.secureSchema,
          secureObjectPattern: source.secureObjectPattern,
          enabled: source.enabled,
          // Carried through rather than dropped: the server writes this column
          // from whatever the form sends, and the capability matrix reads it.
          engineVersion: source.engineVersion,
        }
      : BLANK
  );
  const [problem, setProblem] = useState<string | null>(null);

  function patch(next: Partial<SourceInput>) {
    setDraft((current) => ({ ...current, ...next }));
  }

  const save = useMutation({
    mutationFn: () =>
      source ? updateSource(source.id, draft) : createSource(draft),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sources'] });
      onDone();
    },
    onError: (error) =>
      setProblem(apiErrorMessage(error, 'The source could not be saved.')),
  });

  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
      <h2 className="tw:text-md tw:font-semibold tw:text-primary">
        {source ? `Edit ${source.name}` : 'Register a source'}
      </h2>

      <div className="tw:mt-4 tw:grid tw:gap-4 tw:sm:grid-cols-2">
        <Field
          hint="How this source is named in policies and reports."
          label="Name">
          <TextField
            onChange={(next) => patch({ name: next })}
            placeholder="prod-mssql"
            value={draft.name}
          />
        </Field>

        <Field label="Engine">
          <Select
            onChange={(next) => {
              const engine = next as SourceEngine;
              patch({
                engine,
                // Only when the box is empty, so a deliberate port survives a
                // change of mind about the engine.
                port: draft.port ?? DEFAULT_PORT[engine],
              });
            }}
            options={[
              { value: 'POSTGRES', label: 'PostgreSQL' },
              { value: 'SQLSERVER', label: 'SQL Server' },
            ]}
            value={draft.engine}
          />
        </Field>

        <Field label="Host">
          <TextField
            onChange={(next) => patch({ host: next })}
            placeholder="db.internal"
            value={draft.host}
          />
        </Field>

        <Field
          hint={`Blank uses the engine default (${DEFAULT_PORT[draft.engine]}).`}
          label="Port">
          <TextField
            onChange={(next) =>
              patch({ port: next.trim() === '' ? null : Number(next) })
            }
            placeholder={String(DEFAULT_PORT[draft.engine])}
            type="number"
            value={draft.port === null ? '' : String(draft.port)}
          />
        </Field>

        <Field label="Default database">
          <TextField
            onChange={(next) => patch({ defaultDatabase: next || null })}
            placeholder="SalesDB"
            value={draft.defaultDatabase ?? ''}
          />
        </Field>

        <Field
          hint={`A pointer to a secret, never the secret. Accepted: ${CREDENTIAL_SCHEMES.join(', ')}`}
          label="Credential reference">
          <TextField
            onChange={(next) => patch({ credentialRef: next })}
            placeholder="vault://secret/data/dac/prod-mssql"
            value={draft.credentialRef}
          />
        </Field>

        <Field
          className="tw:sm:col-span-2"
          hint={
            ENFORCEMENT_MODES.find(
              (entry) => entry.value === draft.defaultEnforcementMode
            )?.what
          }
          label="Default enforcement mode">
          <Select
            onChange={(next) =>
              patch({ defaultEnforcementMode: next as EnforcementMode })
            }
            options={ENFORCEMENT_MODES.map((entry) => ({
              value: entry.value,
              label: entry.label,
            }))}
            value={draft.defaultEnforcementMode}
          />
        </Field>

        <Field
          hint="The service FQN in OpenMetadata whose tables live on this database, so a crawled asset can be traced to the machine it is on."
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
          className="tw:sm:col-span-2"
          hint="Appended to the table name to form the view name. Blank keeps the table's own name inside the secure schema."
          label="Secure object suffix">
          <TextField
            onChange={(next) => patch({ secureObjectPattern: next })}
            placeholder="_secure"
            value={draft.secureObjectPattern}
          />
        </Field>
      </div>

      {problem && (
        <div className="tw:mt-4">
          <Notice tone="error">{problem}</Notice>
        </div>
      )}

      <div className="tw:mt-5 tw:flex tw:gap-3">
        <Button
          color="primary"
          isDisabled={save.isPending}
          onPress={() => save.mutate()}
          size="md">
          {save.isPending ? 'Saving…' : source ? 'Save changes' : 'Register'}
        </Button>
        <Button color="secondary" onPress={onDone} size="md">
          Cancel
        </Button>
      </div>
    </section>
  );
}

function Pair({ label, value }: { label: string; value: string }) {
  return (
    <div className="tw:flex tw:gap-2">
      <dt className="tw:shrink-0 tw:text-quaternary">{label}</dt>
      <dd className="tw:truncate tw:text-tertiary">{value}</dd>
    </div>
  );
}

function Notice({
  tone,
  children,
}: {
  tone: 'error' | 'success';
  children: React.ReactNode;
}) {
  const Icon = tone === 'error' ? AlertTriangle : CheckCircle;
  return (
    <p
      className={`tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:px-3 tw:py-2 tw:text-sm ${
        tone === 'error'
          ? 'tw:bg-utility-error-50 tw:text-error-primary'
          : 'tw:bg-utility-success-50 tw:text-success-primary'
      }`}>
      <Icon className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
      <span>{children}</span>
    </p>
  );
}

function modeColor(mode: EnforcementMode) {
  switch (mode) {
    case 'NATIVE_CONFIG':
      // Amber on purpose: this is the mode that alters production objects.
      return 'warning' as const;
    case 'SECURE_VIEW':
      return 'blue' as const;
    case 'PROXY':
      return 'purple' as const;
    default:
      return 'gray' as const;
  }
}
