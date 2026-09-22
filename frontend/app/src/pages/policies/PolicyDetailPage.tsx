import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  AlertTriangle,
  ArrowLeft,
  Columns03,
  Edit03,
  RefreshCw01,
  Table as TableIcon,
} from '@untitledui/icons';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../../api/client';
import {
  fetchPolicy,
  fetchPolicyConflicts,
  fetchPolicyCoverage,
  resolveBindings,
  transitionPolicy,
  type PolicyCoverage,
  type PolicyOverlap,
  type PolicyTarget,
} from '../../api/policies';
import { describePolicy, describeSelector } from './policyLanguage';

/**
 * One policy, read rather than edited.
 *
 * Opening a policy used to drop straight into the builder, which meant the
 * ordinary act of looking something up put every field one keystroke from
 * changing — and still did not answer the two questions people arrive with.
 * Those are "what does this actually cover" and "what else is in the way",
 * and neither can be read off the document: the first lives in the bindings
 * the materializer wrote, the second in the other policies bound beside them.
 *
 * Editing is one button away and lives on its own route.
 */

const STATE_TONE: Record<string, 'success' | 'gray' | 'warning'> = {
  ACTIVE: 'success',
  DRAFT: 'gray',
  PENDING_APPROVAL: 'warning',
  DISABLED: 'warning',
  ARCHIVED: 'gray',
};

/** The relations, worst first, with the word and colour each is shown in. */
const RELATION: Record<
  string,
  { label: string; tone: 'error' | 'warning' | 'gray'; rank: number }
> = {
  BLOCKED_BY: { label: 'Overrules this', tone: 'error', rank: 0 },
  BLOCKS: { label: 'Overruled by this', tone: 'warning', rank: 1 },
  CANNOT_LOOSEN: { label: 'Cannot be relaxed', tone: 'warning', rank: 2 },
  MASK_OVERLAP: { label: 'Same columns', tone: 'warning', rank: 3 },
  NARROWS: { label: 'Narrows this', tone: 'gray', rank: 4 },
  COMPOSES: { label: 'Applies alongside', tone: 'gray', rank: 5 },
};

export default function PolicyDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [actionError, setActionError] = useState<string | null>(null);

  const { data: policy, error } = useQuery({
    queryKey: ['policy', id],
    queryFn: () => fetchPolicy(id!),
    enabled: Boolean(id),
  });
  const coverage = useQuery({
    queryKey: ['policy-coverage', id],
    queryFn: () => fetchPolicyCoverage(id!),
    enabled: Boolean(id),
  });
  const conflicts = useQuery({
    queryKey: ['policy-conflicts', id],
    queryFn: () => fetchPolicyConflicts(id!),
    enabled: Boolean(id),
  });

  const lifecycle = useMutation({
    mutationFn: (state: string) => transitionPolicy(id!, state),
    onSuccess: () => {
      setActionError(null);
      queryClient.invalidateQueries({ queryKey: ['policy', id] });
      queryClient.invalidateQueries({ queryKey: ['policies'] });
    },
    onError: (e) =>
      setActionError(apiErrorMessage(e, 'The lifecycle change was refused.')),
  });

  const resolve = useMutation({
    mutationFn: () => resolveBindings(id!),
    onSuccess: () => {
      setActionError(null);
      queryClient.invalidateQueries({ queryKey: ['policy-coverage', id] });
      queryClient.invalidateQueries({ queryKey: ['policy-conflicts', id] });
    },
    onError: (e) =>
      setActionError(apiErrorMessage(e, 'The selector could not be resolved.')),
  });

  if (error) {
    return (
      <>
        <BackLink />
        <p className="tw:mt-6 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'Could not load this policy.')}
        </p>
      </>
    );
  }

  if (!policy) {
    return (
      <>
        <BackLink />
        <p className="tw:mt-6 tw:text-sm tw:text-tertiary">Loading…</p>
      </>
    );
  }

  const document = policy.document;
  const blocking = (conflicts.data ?? []).filter(
    (row) => row.relation === 'BLOCKED_BY'
  );

  return (
    <>
      <BackLink />

      <header className="tw:mt-4 tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-4">
        <div className="tw:min-w-0">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <h1 className="tw:text-display-xs tw:font-semibold tw:text-primary">
              {document.displayName || document.name}
            </h1>
            <Badge
              color={STATE_TONE[policy.lifecycleState] ?? 'gray'}
              size="sm"
              type="pill-color">
              {policy.lifecycleState.replace('_', ' ').toLowerCase()}
            </Badge>
            <Badge color="gray" size="sm" type="pill-color">
              {document.policyType === 'DATA'
                ? 'data — what they see'
                : 'subscription — who gets in'}
            </Badge>
            <Badge color="gray" size="sm" type="pill-color">
              {policy.environment}
            </Badge>
          </div>
          <p className="tw:mt-2 tw:font-mono tw:text-xs tw:break-all tw:text-quaternary">
            {document.name}
          </p>
        </div>

        <div className="tw:flex tw:shrink-0 tw:items-center tw:gap-2">
          {/* The only way into the form. Everything on this page is a reading
              of the document, so nothing here can change it by accident. */}
          <Button
            iconLeading={Edit03}
            onPress={() => navigate(`/policies/${policy.id}/edit`)}
            size="md">
            Edit
          </Button>
          {policy.lifecycleState === 'DRAFT' && (
            <Button
              color="secondary"
              isDisabled={lifecycle.isPending}
              onPress={() => lifecycle.mutate('ACTIVE')}
              size="md">
              Activate
            </Button>
          )}
          {policy.lifecycleState === 'ACTIVE' && (
            <Button
              color="secondary"
              isDisabled={lifecycle.isPending}
              onPress={() => lifecycle.mutate('DISABLED')}
              size="md">
              Disable
            </Button>
          )}
        </div>
      </header>

      {actionError && (
        <p className="tw:mt-4 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
          {actionError}
        </p>
      )}

      {/* Hoisted out of the conflict list. Somebody looking at a policy that
          grants nothing should not have to scroll to find that out. */}
      {blocking.length > 0 && (
        <div className="tw:mt-4 tw:flex tw:items-start tw:gap-2 tw:rounded-xl tw:border tw:border-error_subtle tw:bg-error-primary tw:p-4">
          <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-error-primary" />
          <p className="tw:text-sm tw:text-pretty tw:text-error-primary">
            {blocking.length === 1
              ? `On the targets they share, ${blocking[0].displayName || blocking[0].name} denies what this policy allows, so this policy grants nothing there.`
              : `${blocking.length} policies deny what this one allows on the targets they share.`}
          </p>
        </div>
      )}

      <div className="tw:mt-6 tw:grid tw:gap-6 tw:lg:grid-cols-3">
        <div className="tw:lg:col-span-2 tw:space-y-6">
          <Panel title="In plain words">
            <div className="tw:flex tw:flex-col tw:gap-1.5 tw:text-sm tw:text-secondary">
              {describePolicy(document).map((line, index) => (
                <p className="tw:text-pretty" key={index}>
                  {line}
                </p>
              ))}
            </div>
          </Panel>

          <Coverage
            data={coverage.data}
            error={coverage.error}
            isPending={resolve.isPending}
            onResolve={() => resolve.mutate()}
          />

          <Conflicts data={conflicts.data} error={conflicts.error} />
        </div>

        <aside className="tw:space-y-6">
          <Panel title="Configuration">
            <dl className="tw:space-y-2 tw:text-sm">
              <Row label="Kind" value={document.policyType} />
              <Row
                label="Level"
                value={
                  document.scopeLevel === 'ORG'
                    ? 'Organisation'
                    : document.scopeLevel
                }
              />
              <Row label="Anchor" value={document.scopeFqn} />
              {document.policyType === 'SUBSCRIPTION' && (
                <Row label="Effect" value={document.effect ?? 'ALLOW'} />
              )}
              <Row
                label="Lower layers"
                value={
                  document.allowLocalOverride
                    ? 'May relax this (audited)'
                    : 'May only add restrictions'
                }
              />
              <Row label="Environment" value={policy.environment} />
              <Row label="Selects" value={describeSelector(document.selector)} />
            </dl>
          </Panel>

          {document.description && (
            <Panel title="Why it exists">
              <p className="tw:text-pretty tw:text-sm tw:text-secondary">
                {document.description}
              </p>
            </Panel>
          )}

          <Panel title="History">
            <dl className="tw:space-y-2 tw:text-sm">
              <Row label="Version" value={`v${policy.version}`} />
              <Row label="Created by" value={policy.createdBy} />
              <Row label="Last edit" value={policy.updatedBy} />
              <Row label="When" value={when(policy.updatedAt)} />
            </dl>
            <Link
              className="tw:mt-3 tw:inline-block tw:text-sm tw:text-brand-secondary tw:hover:underline"
              to={`/policies/${policy.id}/edit`}>
              Open in the builder →
            </Link>
          </Panel>
        </aside>
      </div>
    </>
  );
}

// ------------------------------------------------------------------ coverage

/**
 * The tables and columns this policy landed on.
 *
 * Grouped by table rather than listed flat, because a masking policy binds
 * the table and each column under it, and a flat list of thirty rows hides
 * the shape of what is covered behind repetition of the same prefix.
 */
function Coverage({
  data,
  error,
  onResolve,
  isPending,
}: {
  data?: PolicyCoverage;
  error: unknown;
  onResolve: () => void;
  isPending: boolean;
}) {
  const groups = useMemo(() => groupByTable(data?.sample ?? []), [data]);

  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-between tw:gap-2">
        <div>
          <h2 className="tw:text-sm tw:font-semibold tw:text-primary">
            What it applies to
          </h2>
          <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">
            {data
              ? `${count(data.tableCount, 'table')} · ${count(data.columnCount, 'column')}${
                  data.resolvedAt ? ` · resolved ${when(data.resolvedAt)}` : ''
                }`
              : 'Reading the bindings…'}
          </p>
        </div>
        <Button
          color="secondary"
          iconLeading={RefreshCw01}
          isDisabled={isPending}
          onPress={onResolve}
          size="sm">
          {isPending ? 'Resolving…' : 'Re-resolve'}
        </Button>
      </div>

      {error != null && (
        <p className="tw:mt-3 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'Could not read what this covers.')}
        </p>
      )}

      {data && data.tableCount === 0 && data.columnCount === 0 && (
        // Not an error and not an empty state to be styled away: a policy
        // bound to nothing enforces nothing, and that is the single most
        // useful thing this page can tell someone.
        <p className="tw:mt-3 tw:text-pretty tw:text-sm tw:text-warning-primary">
          Nothing in the catalogue matches this selector, so this policy
          currently affects no data. Re-resolve after the next crawl, or widen
          what it selects.
        </p>
      )}

      {groups.length > 0 && (
        <ul className="tw:mt-3 tw:flex tw:flex-col tw:gap-2">
          {groups.map((group) => (
            <li
              className="tw:rounded-lg tw:border tw:border-secondary tw:p-3"
              key={group.table}>
              <div className="tw:flex tw:items-start tw:gap-2">
                <TableIcon className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-tertiary" />
                <div className="tw:min-w-0">
                  <Link
                    className="tw:font-mono tw:text-xs tw:break-all tw:text-brand-secondary tw:hover:underline"
                    to={`/catalog/${group.table}`}>
                    {group.table}
                  </Link>
                  {group.reason && (
                    <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">
                      {group.reason}
                    </p>
                  )}
                </div>
                {!group.boundDirectly && (
                  // The table is only in the list because a column under it
                  // matched. Saying so stops it reading as table-wide cover.
                  <span className="tw:ml-auto tw:shrink-0 tw:text-xs tw:text-quaternary">
                    columns only
                  </span>
                )}
              </div>

              {group.columns.length > 0 && (
                <div className="tw:mt-2 tw:flex tw:flex-wrap tw:gap-1.5 tw:pl-6">
                  {group.columns.map((column) => (
                    <span
                      className="tw:inline-flex tw:items-center tw:gap-1 tw:rounded-full tw:border tw:border-secondary tw:px-2 tw:py-0.5 tw:text-xs tw:text-secondary"
                      key={column.fqn}
                      title={reasonOf(column) ?? undefined}>
                      <Columns03 className="tw:size-3 tw:shrink-0 tw:text-quaternary" />
                      {column.name ?? column.fqn}
                      {column.dataType && (
                        <span className="tw:text-quaternary">
                          {column.dataType}
                        </span>
                      )}
                    </span>
                  ))}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      {data?.truncated && (
        <p className="tw:mt-3 tw:text-xs tw:text-tertiary">
          Showing the first {data.sample.length} of{' '}
          {data.tableCount + data.columnCount} targets.
        </p>
      )}
    </section>
  );
}

interface Group {
  table: string;
  boundDirectly: boolean;
  reason: string | null;
  columns: PolicyTarget[];
}

/**
 * Folds the flat binding list into one entry per table.
 *
 * A column whose parent was not itself bound still needs a heading, so the
 * table appears either way and carries a note saying which of the two it is.
 */
function groupByTable(targets: PolicyTarget[]): Group[] {
  const groups = new Map<string, Group>();

  function slot(table: string): Group {
    let group = groups.get(table);
    if (!group) {
      group = { table, boundDirectly: false, reason: null, columns: [] };
      groups.set(table, group);
    }
    return group;
  }

  for (const target of targets) {
    if (target.kind === 'TABLE') {
      const group = slot(target.fqn);
      group.boundDirectly = true;
      group.reason = reasonOf(target);
    } else {
      slot(target.parentFqn ?? target.fqn).columns.push(target);
    }
  }

  return [...groups.values()];
}

/** The stored match reason, as a sentence rather than as JSON. */
function reasonOf(target: PolicyTarget): string | null {
  const reason = target.matchReason;
  if (!reason || Object.keys(reason).length === 0) {
    return null;
  }
  const parts = Object.entries(reason)
    .filter(([, value]) => value !== null && value !== '')
    .map(([key, value]) =>
      typeof value === 'object'
        ? `${label(key)} ${JSON.stringify(value)}`
        : `${label(key)} ${String(value)}`
    );
  return parts.length ? `Matched on ${parts.join(', ')}` : null;
}

function label(key: string): string {
  return key.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
}

// ----------------------------------------------------------------- conflicts

function Conflicts({
  data,
  error,
}: {
  data?: PolicyOverlap[];
  error: unknown;
}) {
  const rows = data ?? [];

  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
      <h2 className="tw:text-sm tw:font-semibold tw:text-primary">
        Policies it meets
      </h2>
      <p className="tw:mt-0.5 tw:text-pretty tw:text-xs tw:text-tertiary">
        Other policies bound to the same tables or columns. Overlapping is
        normal — layers compose — so each row says what actually happens where
        the two meet.
      </p>

      {error != null && (
        <p className="tw:mt-3 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'Could not read the overlaps.')}
        </p>
      )}

      {data && rows.length === 0 && (
        <p className="tw:mt-3 tw:text-sm tw:text-tertiary">
          Nothing else is bound to these targets, so this policy stands alone.
        </p>
      )}

      <ul className="tw:mt-3 tw:flex tw:flex-col tw:gap-2">
        {rows.map((row) => {
          const relation = RELATION[row.relation] ?? {
            label: row.relation,
            tone: 'gray' as const,
          };
          return (
            <li
              className="tw:rounded-lg tw:border tw:border-secondary tw:p-3"
              key={row.policyId}>
              <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
                <Link
                  className="tw:text-sm tw:font-medium tw:text-brand-secondary tw:hover:underline"
                  to={`/policies/${row.policyId}`}>
                  {row.displayName || row.name}
                </Link>
                <Badge color={relation.tone} size="sm" type="pill-color">
                  {relation.label}
                </Badge>
                <Badge color="gray" size="sm" type="pill-color">
                  {row.policyType === 'DATA' ? 'data' : 'subscription'}
                  {row.policyType === 'SUBSCRIPTION'
                    ? ` · ${row.effect.toLowerCase()}`
                    : ''}
                </Badge>
                <Badge color="gray" size="sm" type="pill-color">
                  {row.scopeLevel.toLowerCase()}
                </Badge>
                {row.lifecycleState !== 'ACTIVE' && (
                  <Badge color="warning" size="sm" type="pill-color">
                    {row.lifecycleState.replace('_', ' ').toLowerCase()}
                  </Badge>
                )}
                <span className="tw:ml-auto tw:text-xs tw:text-tertiary">
                  {count(row.sharedTargets, 'shared target')}
                </span>
              </div>

              <p className="tw:mt-1.5 tw:text-pretty tw:text-sm tw:text-secondary">
                {row.explanation}
              </p>

              {/*
                Separate from the explanation above on purpose. That one says
                what the engine does where the two meet; this one says who is
                allowed to change it (FR-3.1.4). They can disagree — two
                policies can agree perfectly today and this still decides who
                may edit which of them tomorrow.
              */}
              {row.overrideNote != null && (
                <p className="tw:mt-1.5 tw:flex tw:items-start tw:gap-1.5 tw:text-pretty tw:text-sm tw:text-warning-primary">
                  <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
                  {row.overrideNote}
                </p>
              )}

              {row.examples.length > 0 && (
                <p className="tw:mt-1 tw:font-mono tw:text-xs tw:break-all tw:text-quaternary">
                  {row.examples.join(' · ')}
                  {row.sharedTargets > row.examples.length ? ' …' : ''}
                </p>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}

// ------------------------------------------------------------------- pieces

function Panel({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
      <h2 className="tw:text-sm tw:font-semibold tw:text-primary">{title}</h2>
      <div className="tw:mt-3">{children}</div>
    </section>
  );
}

function Row({ label, value }: { label: string; value?: string | null }) {
  if (!value) {
    return null;
  }
  return (
    <div className="tw:flex tw:gap-2">
      <dt className="tw:w-28 tw:shrink-0 tw:text-xs tw:text-tertiary">
        {label}
      </dt>
      <dd className="tw:min-w-0 tw:text-pretty tw:break-words tw:text-sm tw:text-primary">
        {value}
      </dd>
    </div>
  );
}

function BackLink() {
  return (
    <Link
      className="tw:inline-flex tw:items-center tw:gap-1 tw:text-sm tw:text-tertiary tw:hover:text-primary"
      to="/policies">
      <ArrowLeft className="tw:size-4" />
      Policies
    </Link>
  );
}

function count(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? '' : 's'}`;
}

function when(iso: string | null): string {
  if (!iso) {
    return '';
  }
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? iso : at.toLocaleString();
}
