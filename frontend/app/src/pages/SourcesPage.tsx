import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Database01, Plus, Server01 } from '@untitledui/icons';
import { Chip as Badge } from '../components/chips';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../api/client';
import {
  ENFORCEMENT_MODES,
  deleteSource,
  fetchSources,
  importSourceCatalog,
  isSealed,
  setSourceEnabled,
  testSource,
  type EnforcementMode,
  type ImportReport,
  type ProbeResult,
  type Source,
} from '../api/sources';
import { engineLabel, useSourceEngines } from '../engines';
import { useAuthStore } from '../auth/authStore';
import ConnectionWizard from './sources/ConnectionWizard';
import { ImportReportView } from './sources/ImportReport';
import { Notice } from './sources/Notice';
import { describeScope, scopeBadge } from './sources/tableScope';

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
 * A credential may be typed in or pointed at. Pointing at a vault stays the
 * better answer where there is a vault; refusing everything else did not keep
 * passwords out of the product, it pushed them into an environment variable
 * nobody could rotate or audit. What is typed here is sealed before it is
 * stored and is never served back — the form shows that a credential exists,
 * not what it is.
 */

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
          <p className="tw:mt-1 tw:max-w-3xl tw:text-pretty tw:text-sm tw:text-tertiary">
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
        <ConnectionWizard
          onDone={() => setEditing(null)}
          source={editing === 'new' ? null : editing}
        />
      )}

      {error && (
        <Notice tone="error">
          {apiErrorMessage(error, 'The source list could not be loaded.')}
        </Notice>
      )}

      {isLoading && editing === null && (
        <p className="tw:text-sm tw:text-tertiary">Loading…</p>
      )}

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

      {/* The form stands alone. A list underneath it repeats the source being
          edited a second time, a few hundred pixels below its own form, and
          puts every other source's Edit and Remove button within reach of
          somebody who is in the middle of changing this one. */}
      {sources && sources.length > 0 && editing === null && (
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
  const { data: engines } = useSourceEngines();
  const [probe, setProbe] = useState<ProbeResult | null>(null);
  const [report, setReport] = useState<ImportReport | null>(null);
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

  // Reads the source's own catalog, within its table scope. The report stays
  // under the card rather than in a toast: its new-column names are the part
  // somebody has to act on.
  const importTables = useMutation({
    mutationFn: () => importSourceCatalog(source.id),
    onMutate: () => {
      setProblem(null);
      setReport(null);
    },
    onSuccess: (result) => {
      setReport(result);
      invalidate();
    },
    onError: (error) =>
      setProblem(apiErrorMessage(error, 'The catalog could not be imported.')),
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
              {engineLabel(engines, source.engine)}
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
            <Pair
              label="Credential"
              value={
                isSealed(source.credentialRef)
                  ? 'Stored, encrypted'
                  : source.credentialRef
              }
            />
            <Pair
              label="OpenMetadata service"
              value={source.omServiceFqn ?? 'not linked'}
            />
            <Pair
              label="Secure objects"
              value={`${source.secureSchema}.${source.secureObjectPattern || '<name>'}`}
            />
            <Pair
              label="Tables"
              value={`${scopeBadge(source.tableScope)} — ${describeScope(source.tableScope)}`}
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
            <Button
              color="secondary"
              isDisabled={importTables.isPending || !source.enabled}
              onPress={() => importTables.mutate()}
              size="sm">
              {importTables.isPending ? 'Importing…' : 'Import tables'}
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

      {(probe || problem || report) && (
        <div className="tw:border-t tw:border-secondary tw:px-5 tw:py-3">
          {problem && <Notice tone="error">{problem}</Notice>}
          {probe && !problem && (
            <Notice tone={probe.reachable ? 'success' : 'error'}>
              {probe.reachable
                ? `${probe.productName} ${probe.engineVersion} answered in ${probe.millis} ms.`
                : probe.message}
            </Notice>
          )}
          {report && !problem && (
            <div className={probe ? 'tw:mt-3' : undefined}>
              <ImportReportView report={report} />
            </div>
          )}
        </div>
      )}
    </li>
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
