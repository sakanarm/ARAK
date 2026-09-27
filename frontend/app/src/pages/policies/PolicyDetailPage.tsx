import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  AlertTriangle,
  ArrowLeft,
  ClockRewind,
  Columns03,
  Edit03,
  EyeOff,
  Key01,
  RefreshCw01,
  Users01,
  Table as TableIcon,
} from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../../api/client';
import {
  fetchPolicy,
  fetchPolicyConflicts,
  fetchPolicyCoverage,
  fetchPolicyImpact,
  fetchPolicyVersions,
  fetchRollbackImpact,
  resolveBindings,
  rollbackPolicy,
  transitionPolicy,
  type PolicyAction,
  type PolicyCoverage,
  type PolicyImpact,
  type PolicyImpactChange,
  type PolicyOverlap,
  type PolicyRevision,
  type PolicyTarget,
  type StoredPolicy,
} from '../../api/policies';
import { describePolicy, describeSelector } from './policyLanguage';
import { diffPolicies } from './policyDiff';
import { useViewMode, ViewToggle } from './controls';
import PolicyFlowChart from './PolicyFlowChart';
import PolicyDiagram from './PolicyDiagram';
import TabStrip, { panelId, tabId, type TabItem } from '../../components/TabStrip';

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

type DetailTab = 'overview' | 'coverage' | 'impact' | 'conflicts' | 'history';
const TABS: DetailTab[] = ['overview', 'coverage', 'impact', 'conflicts', 'history'];

/** What made each version, in the words the timeline shows. */
const ACTION: Record<
  PolicyAction,
  { label: string; tone: 'success' | 'warning' | 'gray' | 'brand' }
> = {
  CREATE: { label: 'created', tone: 'gray' },
  UPDATE: { label: 'edited', tone: 'gray' },
  SUBMIT: { label: 'sent for review', tone: 'warning' },
  RETURN: { label: 'sent back', tone: 'warning' },
  PUBLISH: { label: 'activated', tone: 'success' },
  DISABLE: { label: 'disabled', tone: 'warning' },
  ARCHIVE: { label: 'archived', tone: 'gray' },
  ROLLBACK: { label: 'restored', tone: 'brand' },
};

const STATE_TONE: Record<string, 'success' | 'gray' | 'warning'> = {
  ACTIVE: 'success',
  DRAFT: 'gray',
  PENDING_APPROVAL: 'warning',
  DISABLED: 'warning',
  ARCHIVED: 'gray',
};

/**
 * The relations, worst first, with the word and colour each is shown in.
 *
 * Every one of these describes what the engine does where the two policies
 * meet. Who is allowed to change which of them is a different question with a
 * different answer -- `overrideNote` -- and it does not belong in this list.
 */
const RELATION: Record<
  string,
  { label: string; tone: 'error' | 'warning' | 'gray'; rank: number }
> = {
  BLOCKED_BY: { label: 'Overrules this', tone: 'error', rank: 0 },
  BLOCKS: { label: 'Overruled by this', tone: 'warning', rank: 1 },
  MASK_OVERLAP: { label: 'Same columns', tone: 'warning', rank: 2 },
  NARROWS: { label: 'Narrows this', tone: 'gray', rank: 3 },
  COMPOSES: { label: 'Applies alongside', tone: 'gray', rank: 4 },
};

/**
 * The six outcomes an impact run can report, worst first.
 *
 * Every label is written as if the policy were on, because that is the only
 * direction the arithmetic runs -- the engine is asked for a decision with the
 * policy in the stack and again without it, and these name the difference.
 * An already-active policy is measured the same way; only the sentence above
 * the list changes, from "would" to "is".
 */
const CHANGE: Record<
  PolicyImpactChange,
  { label: string; tone: 'error' | 'warning' | 'success' | 'gray' }
> = {
  LOSES_ACCESS: { label: 'loses the table', tone: 'error' },
  GAINS_ACCESS: { label: 'gains the table', tone: 'warning' },
  CHANGED: { label: 'sees different data', tone: 'warning' },
  SEES_LESS: { label: 'sees less', tone: 'warning' },
  SEES_MORE: { label: 'sees more', tone: 'warning' },
  UNCHANGED: { label: 'no change', tone: 'gray' },
};

const CHANGE_ORDER: PolicyImpactChange[] = [
  'LOSES_ACCESS',
  'GAINS_ACCESS',
  'CHANGED',
  'SEES_LESS',
  'SEES_MORE',
];

export default function PolicyDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [actionError, setActionError] = useState<string | null>(null);
  // Two readings of the same document, remembered per browser. Text stays the
  // default: the sentence is the one a reviewer can quote in an approval, and
  // nobody should have to switch back to a page they already knew.
  const [reading, setReading] = useViewMode<'text' | 'flow' | 'diagram'>(
    'arak.policy.reading',
    'text',
  );

  // The open section rides in the address, so a link can land on the impact
  // of a policy rather than on its overview.
  const [params, setParams] = useSearchParams();
  const asked = params.get('tab') as DetailTab | null;
  const tab: DetailTab = asked && TABS.includes(asked) ? asked : 'overview';
  const setTab = (next: DetailTab) =>
    setParams(next === 'overview' ? {} : { tab: next }, { replace: true });

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
  // Two engine evaluations per person per table, so it is deliberately not
  // refetched on focus -- the answer only moves when a policy or the directory
  // does, and both of those invalidate it explicitly.
  const impact = useQuery({
    queryKey: ['policy-impact', id],
    queryFn: () => fetchPolicyImpact(id!),
    enabled: Boolean(id),
    refetchOnWindowFocus: false,
  });
  const versions = useQuery({
    queryKey: ['policy-versions', id],
    queryFn: () => fetchPolicyVersions(id!),
    enabled: Boolean(id),
  });

  const lifecycle = useMutation({
    mutationFn: (state: string) => transitionPolicy(id!, state),
    onSuccess: () => {
      setActionError(null);
      queryClient.invalidateQueries({ queryKey: ['policy', id] });
      queryClient.invalidateQueries({ queryKey: ['policies'] });
      queryClient.invalidateQueries({ queryKey: ['policy-impact', id] });
      queryClient.invalidateQueries({ queryKey: ['policy-versions', id] });
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
      queryClient.invalidateQueries({ queryKey: ['policy-impact', id] });
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
  const isData = document.policyType === 'DATA';
  const tabs: TabItem<DetailTab>[] = [
    { id: 'overview', label: 'Overview' },
    { id: 'coverage', label: 'Applies to', count: coverage.data?.tableCount },
    { id: 'impact', label: 'Impact', count: impact.data?.principalsAffected },
    { id: 'conflicts', label: 'Other policies', count: conflicts.data?.length },
    { id: 'history', label: 'History', count: versions.data?.length },
  ];
  const diagram = reading === 'diagram';

  return (
    <div className="tw:flex tw:flex-col tw:gap-5">
      <BackLink />

      <header className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5 tw:shadow-xs">
        <div className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-4">
          <div className="tw:flex tw:min-w-0 tw:items-start tw:gap-4">
            <span
              className={`tw:flex tw:size-12 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-xl ${
                isData
                  ? 'tw:bg-utility-purple-50 tw:text-utility-purple-600'
                  : 'tw:bg-utility-brand-50 tw:text-utility-brand-600'
              }`}>
              {isData ? <EyeOff className="tw:size-6" /> : <Key01 className="tw:size-6" />}
            </span>
            <div className="tw:min-w-0">
              <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
                <h1 className="tw:text-xl tw:font-semibold tw:text-primary">
                  {document.displayName || document.name}
                </h1>
                <Badge
                  color={STATE_TONE[policy.lifecycleState] ?? 'gray'}
                  size="sm"
                  type="pill-color">
                  {policy.lifecycleState.replace('_', ' ').toLowerCase()}
                </Badge>
              </div>
              <p className="tw:mt-0.5 tw:font-mono tw:text-xs tw:break-all tw:text-quaternary">
                {document.name}
              </p>
              {document.description && (
                <p className="tw:mt-2 tw:max-w-3xl tw:text-pretty tw:text-sm tw:text-tertiary">
                  {document.description}
                </p>
              )}
            </div>
          </div>

          <div className="tw:flex tw:shrink-0 tw:items-center tw:gap-2">
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
            {/* The only way into the form. Everything on this page is a reading
                of the document, so nothing here can change it by accident. */}
            <Button
              iconLeading={Edit03}
              onPress={() => navigate(`/policies/${policy.id}/edit`)}
              size="md">
              Edit
            </Button>
          </div>
        </div>

        <dl className="tw:mt-5 tw:grid tw:grid-cols-2 tw:gap-4 tw:border-t tw:border-secondary tw:pt-4 tw:md:grid-cols-4">
          <Fact
            label="Kind"
            value={isData ? 'Data · what they see' : 'Subscription · who gets in'}
          />
          <Fact
            label="Applies at"
            mono={Boolean(document.scopeFqn)}
            value={document.scopeFqn || (document.scopeLevel === 'ORG' ? 'The organisation' : document.scopeLevel)}
          />
          <Fact
            label={isData ? 'Environment' : 'Effect'}
            value={isData ? policy.environment : `${document.effect ?? 'ALLOW'} · ${policy.environment}`}
          />
          <Fact
            detail={`edited ${when(policy.updatedAt)} by ${policy.updatedBy}`}
            label="Version"
            value={`v${policy.version} · ${policy.lifecycleState.toLowerCase().replace('_', ' ')}`}
          />
        </dl>
      </header>

      {actionError && (
        <p className="tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
          {actionError}
        </p>
      )}

      {/* Hoisted out of the conflict list. Somebody looking at a policy that
          grants nothing should not have to open a tab to find that out. */}
      {blocking.length > 0 && (
        <div className="tw:flex tw:items-start tw:gap-2 tw:rounded-xl tw:border tw:border-error_subtle tw:bg-error-primary tw:p-4">
          <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-error-primary" />
          <p className="tw:text-sm tw:text-pretty tw:text-error-primary">
            {blocking.length === 1
              ? `On the targets they share, ${blocking[0].displayName || blocking[0].name} denies what this policy allows, so this policy grants nothing there.`
              : `${blocking.length} policies deny what this one allows on the targets they share.`}
          </p>
        </div>
      )}

      <TabStrip idPrefix="policy" label="Policy sections" onChange={setTab} tabs={tabs} value={tab} />

      <div
        aria-labelledby={tabId('policy', tab)}
        id={panelId('policy', tab)}
        role="tabpanel">
        {tab === 'overview' && (
          <div className="tw:grid tw:gap-6 tw:lg:grid-cols-3">
            <div className={diagram ? 'tw:lg:col-span-3' : 'tw:lg:col-span-2'}>
              <Panel
                action={
                  <ViewToggle
                    label="How to read this policy"
                    onChange={setReading}
                    options={[
                      { value: 'text', label: 'Text' },
                      { value: 'flow', label: 'Flowchart' },
                      { value: 'diagram', label: 'Diagram' },
                    ]}
                    value={reading}
                  />
                }
                title={
                  reading === 'flow'
                    ? 'How a request runs through it'
                    : diagram
                      ? 'How it decides'
                      : 'In plain words'
                }>
                {reading === 'flow' ? (
                  <PolicyFlowChart policy={document} />
                ) : diagram ? (
                  <PolicyDiagram policy={document} />
                ) : (
                  <div className="tw:flex tw:flex-col tw:gap-2 tw:text-sm tw:leading-6 tw:text-secondary">
                    {describePolicy(document).map((line, index) => (
                      <p className="tw:text-pretty" key={index}>
                        {line}
                      </p>
                    ))}
                  </div>
                )}
              </Panel>
            </div>

            <aside
              className={
                diagram
                  ? 'tw:grid tw:gap-6 tw:lg:col-span-3 tw:lg:grid-cols-2'
                  : 'tw:flex tw:flex-col tw:gap-6'
              }>
              <Panel title="Configuration">
                <dl className="tw:space-y-2.5 tw:text-sm">
                  <Row label="Selects" value={describeSelector(document.selector)} />
                  <Row
                    label="Level"
                    value={document.scopeLevel === 'ORG' ? 'Organisation' : document.scopeLevel}
                  />
                  <Row
                    label="A grant may pass this"
                    value={
                      document.allowLocalOverride
                        ? 'Yes — recorded in the audit log'
                        : 'No — everybody must match this policy'
                    }
                  />
                </dl>
              </Panel>

              <Panel title="History">
                <dl className="tw:space-y-2.5 tw:text-sm">
                  <Row label="Version" value={`v${policy.version}`} />
                  <Row label="Created by" value={policy.createdBy} />
                  <Row label="Last edit" value={policy.updatedBy} />
                  <Row label="When" value={when(policy.updatedAt)} />
                </dl>
              </Panel>
            </aside>
          </div>
        )}

        {tab === 'coverage' && (
          <Coverage
            data={coverage.data}
            error={coverage.error}
            isPending={resolve.isPending}
            onResolve={() => resolve.mutate()}
          />
        )}

        {tab === 'impact' && <Impact data={impact.data} error={impact.error} />}

        {tab === 'conflicts' && <Conflicts data={conflicts.data} error={conflicts.error} />}

        {tab === 'history' && (
          <History current={policy} data={versions.data} error={versions.error} />
        )}
      </div>
    </div>
  );
}

/** One figure in the header's summary strip. */
function Fact({
  label,
  value,
  detail,
  mono = false,
}: {
  label: string;
  value: string;
  /** A second, quieter line under the value. */
  detail?: string;
  mono?: boolean;
}) {
  return (
    <div className="tw:min-w-0">
      <dt className="tw:text-xs tw:font-medium tw:text-quaternary">{label}</dt>
      <dd
        className={`tw:mt-0.5 tw:truncate tw:text-sm tw:font-medium tw:text-primary ${mono ? 'tw:font-mono tw:text-xs' : ''}`}
        title={value}>
        {value}
      </dd>
      {detail && (
        <dd className="tw:mt-0.5 tw:truncate tw:text-xs tw:text-tertiary" title={detail}>
          {detail}
        </dd>
      )}
    </div>
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

// -------------------------------------------------------------------- impact

/**
 * Who this policy actually changes things for (FR-5.3).
 *
 * The number people expect here is the number of bindings, and it is the
 * wrong one: a policy can be bound to forty tables and change nothing for
 * anybody, because everything it restricts is already restricted above it.
 * So the backend composes each person's decision on each bound table twice,
 * once with this policy and once without, and what is shown is only the
 * difference between the two.
 *
 * Two things must never be rounded off in the telling. The first is direction
 * -- every label reads as if the policy were on, and the sentence at the top
 * says whether that is the world today or the world after activating. The
 * second is sampling: the run is capped, and when the cap bites the counts
 * are floors, so the wording becomes "at least" rather than a bare figure.
 */
function Impact({
  data,
  error,
  restoring,
}: {
  data?: PolicyImpact;
  error: unknown;
  /**
   * Set when the report compares the current rules with an older version's
   * rather than the policy with its absence. The arithmetic is the same; only
   * what the sentences say is being switched changes.
   */
  restoring?: number;
}) {
  const affected = data?.principalsAffected ?? 0;
  // "at least" rather than a number, whenever the run did not see everything.
  const floor = data?.sampled ? 'at least ' : '';
  const sees = affected === 1 ? 'sees' : 'see';
  const rollback = restoring != null;

  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
      <div className="tw:flex tw:items-start tw:gap-2">
        <Users01 className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-tertiary" />
        <div className="tw:min-w-0">
          <h2 className="tw:text-sm tw:font-semibold tw:text-primary">
            {rollback ? `What restoring v${restoring} changes` : 'Who it changes things for'}
          </h2>
          <p className="tw:mt-0.5 tw:text-pretty tw:text-xs tw:text-tertiary">
            {rollback ? (
              <>
                Measured by asking the engine twice for every person on every
                table either version reaches &mdash; once with the rules in force
                now, once with v{restoring}&rsquo;s &mdash; and keeping the
                differences.
              </>
            ) : (
              <>
                Measured by asking the engine twice for every person on every table
                this covers &mdash; once with this policy, once without &mdash; and
                keeping the differences.
              </>
            )}
          </p>
        </div>
      </div>

      {error != null && (
        <p className="tw:mt-3 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'Could not measure the impact.')}
        </p>
      )}

      {!data && error == null && (
        <p className="tw:mt-3 tw:text-sm tw:text-tertiary">Measuring&hellip;</p>
      )}

      {data && data.tablesBound === 0 && (
        <p className="tw:mt-3 tw:text-pretty tw:text-sm tw:text-warning-primary">
          {rollback
            ? `Neither the current rules nor v${restoring}\u2019s reach any table, so restoring it changes nothing for anyone.`
            : 'This policy is bound to no tables, so there is nobody for it to affect. Re-resolve it above first.'}
        </p>
      )}

      {data && data.tablesBound > 0 && (
        <>
          <p className="tw:mt-3 tw:text-pretty tw:text-sm tw:text-secondary">
            {rollback
              ? affected === 0
                ? `Restoring v${restoring} would change nothing for anyone. The policies around it already decide the same way under either version.`
                : `Restoring v${restoring} would change what ${floor}${people(affected)} ${sees} on ${count(data.tablesAffected, 'table')}.`
              : affected === 0
              ? data.candidateActive
                ? 'Nobody\u2019s access depends on this policy. Everything it restricts is already restricted by the policies around it, so switching it off would change nothing.'
                : 'Activating this would change nothing for anyone. Everything it restricts is already restricted by the policies around it.'
              : data.candidateActive
                ? `This policy is the reason ${floor}${people(affected)} ${sees} what they see on ${count(data.tablesAffected, 'table')}.`
                : `Activating this would change what ${floor}${people(affected)} ${sees} on ${count(data.tablesAffected, 'table')}.`}
          </p>

          {affected > 0 && (
            <div className="tw:mt-3 tw:flex tw:flex-wrap tw:gap-1.5">
              {CHANGE_ORDER.filter(
                (kind) => (data.byChange[kind] ?? 0) > 0
              ).map((kind) => (
                <Badge
                  color={CHANGE[kind].tone}
                  key={kind}
                  size="sm"
                  type="pill-color">
                  {data.byChange[kind]} {CHANGE[kind].label}
                </Badge>
              ))}
            </div>
          )}

          {affected > 0 && (
            <ul className="tw:mt-3 tw:flex tw:flex-col tw:gap-2">
              {data.principals.map((person) => (
                <li
                  className="tw:rounded-lg tw:border tw:border-secondary tw:p-3"
                  key={person.principal}>
                  <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
                    <span className="tw:text-sm tw:font-medium tw:text-primary">
                      {person.principal}
                    </span>
                    <Badge
                      color={CHANGE[person.change].tone}
                      size="sm"
                      type="pill-color">
                      {CHANGE[person.change].label}
                    </Badge>
                    <span className="tw:ml-auto tw:text-xs tw:text-tertiary">
                      {count(person.tablesAffected, 'table')}
                    </span>
                  </div>
                  <ul className="tw:mt-1.5 tw:flex tw:flex-col tw:gap-1">
                    {person.tables.map((table) => (
                      <li
                        className="tw:text-pretty tw:text-xs tw:text-tertiary"
                        key={table.assetFqn}>
                        <span className="tw:font-mono tw:break-all">
                          {table.assetFqn}
                        </span>
                        {' \u2014 '}
                        {table.detail}
                      </li>
                    ))}
                  </ul>
                </li>
              ))}
            </ul>
          )}

          {data.principalsTruncated && (
            <p className="tw:mt-3 tw:text-xs tw:text-tertiary">
              Showing {data.principals.length} of {affected} people.
            </p>
          )}

          {/*
            The scope of the measurement, always, not only when it is partial.
            A number whose denominator is hidden is the thing this panel exists
            to avoid, and "3 of 3 people" costs one line to say.
          */}
          <p className="tw:mt-3 tw:text-pretty tw:text-xs tw:text-quaternary">
            {data.tablesMeasured} of {count(data.tablesBound, 'table')} &middot;{' '}
            {data.principalsMeasured} of {people(data.principalsKnown)} &middot;
            groups are not counted, nobody signs in as one
            {data.sampled
              ? ' \u00b7 capped for speed, so these are floors rather than totals'
              : ''}
          </p>
        </>
      )}
    </section>
  );
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

// ------------------------------------------------------------------- history

/**
 * Every version, newest first, and a way to put an older one back (FR-9.2).
 *
 * Restoring saves the older rules as a new version on top; it never rewinds.
 * The version it replaces stays in this list, so a restore is undone the same
 * way it was made. It asks for a reason, names the version this page was
 * showing so it cannot quietly undo an edit made in the meantime, and on a
 * policy in force it first shows who it would change things for.
 */
function History({
  current,
  data,
  error,
}: {
  current: StoredPolicy;
  data?: PolicyRevision[];
  error: unknown;
}) {
  const [open, setOpen] = useState<number | null>(null);

  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
      <div className="tw:flex tw:items-start tw:gap-2">
        <ClockRewind className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-tertiary" />
        <div className="tw:min-w-0">
          <h2 className="tw:text-sm tw:font-semibold tw:text-primary">Every version</h2>
          <p className="tw:mt-0.5 tw:text-pretty tw:text-xs tw:text-tertiary">
            Each save, review step and switch on or off is a version, kept as it
            was. Open an older one to see how it differs from now, or to put its
            rules back.
          </p>
        </div>
      </div>

      {error != null && (
        <p className="tw:mt-3 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'Could not load the history.')}
        </p>
      )}

      {!data && error == null && (
        <p className="tw:mt-3 tw:text-sm tw:text-tertiary">Loading&hellip;</p>
      )}

      {data && (
        <ol className="tw:mt-3 tw:flex tw:flex-col tw:gap-2">
          {data.map((revision) => (
            <Version
              current={current}
              key={revision.version}
              onToggle={() =>
                setOpen((was) => (was === revision.version ? null : revision.version))
              }
              open={open === revision.version}
              revision={revision}
            />
          ))}
        </ol>
      )}
    </section>
  );
}

function Version({
  current,
  revision,
  open,
  onToggle,
}: {
  current: StoredPolicy;
  revision: PolicyRevision;
  open: boolean;
  onToggle: () => void;
}) {
  const isCurrent = revision.version === current.version;
  const action = ACTION[revision.action] ?? {
    label: String(revision.action).toLowerCase(),
    tone: 'gray' as const,
  };

  return (
    <li className="tw:rounded-lg tw:border tw:border-secondary tw:p-3">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <span className="tw:font-mono tw:text-sm tw:font-medium tw:text-primary">
          v{revision.version}
        </span>
        <Badge color={action.tone} size="sm" type="pill-color">
          {action.label}
        </Badge>
        {revision.restoredFrom != null && (
          <span className="tw:text-xs tw:text-tertiary">
            a copy of v{revision.restoredFrom}
          </span>
        )}
        <span className="tw:text-xs tw:text-quaternary">
          {revision.lifecycleState.replace('_', ' ').toLowerCase()}
        </span>
        {isCurrent && (
          <Badge color="gray" size="sm" type="pill-color">
            current
          </Badge>
        )}
        <span className="tw:ml-auto tw:text-xs tw:text-tertiary">
          {revision.changedBy} &middot; {when(revision.changedAt)}
        </span>
      </div>

      {revision.changeReason && (
        <p className="tw:mt-1.5 tw:text-pretty tw:break-words tw:text-sm tw:text-secondary">
          {revision.changeReason}
        </p>
      )}

      {!isCurrent && (
        <div className="tw:mt-2">
          <Button color="link-gray" onPress={onToggle} size="sm">
            {open ? 'Hide the comparison' : 'Compare with now'}
          </Button>
        </div>
      )}

      {open && !isCurrent && <Restore current={current} onDone={onToggle} revision={revision} />}
    </li>
  );
}

function Restore({
  current,
  revision,
  onDone,
}: {
  current: StoredPolicy;
  revision: PolicyRevision;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const changes = useMemo(
    () => diffPolicies(current.document, revision.document),
    [current.document, revision.document]
  );
  const live = current.lifecycleState === 'ACTIVE';
  const archived = current.lifecycleState === 'ARCHIVED';
  const fieldId = `restore-reason-${revision.version}`;

  // Keyed on the current version too: the answer is a comparison with now,
  // and "now" moves with every save.
  const impact = useQuery({
    queryKey: ['policy-rollback-impact', current.id, revision.version, current.version],
    queryFn: () => fetchRollbackImpact(current.id, revision.version),
    enabled: confirming && live,
    refetchOnWindowFocus: false,
  });

  const restore = useMutation({
    mutationFn: () =>
      rollbackPolicy(current.id, revision.version, current.version, reason.trim()),
    onSuccess: () => {
      setProblem(null);
      for (const key of [
        'policy',
        'policy-versions',
        'policy-impact',
        'policy-coverage',
        'policy-conflicts',
      ]) {
        queryClient.invalidateQueries({ queryKey: [key, current.id] });
      }
      queryClient.invalidateQueries({ queryKey: ['policies'] });
      onDone();
    },
    onError: (e) => setProblem(apiErrorMessage(e, 'The restore was refused.')),
  });

  return (
    <div className="tw:mt-3 tw:flex tw:flex-col tw:gap-3 tw:border-t tw:border-secondary tw:pt-3">
      {changes.length === 0 ? (
        <p className="tw:text-pretty tw:text-sm tw:text-tertiary">
          v{revision.version} reads the same as the current version, so there is
          nothing to put back.
        </p>
      ) : (
        <dl className="tw:flex tw:flex-col tw:text-sm">
          <div className="tw:hidden tw:gap-3 tw:pb-1 tw:text-xs tw:font-medium tw:text-tertiary tw:md:grid tw:md:grid-cols-[9rem_1fr_1fr]">
            <span />
            <span>Now (v{current.version})</span>
            <span>v{revision.version}</span>
          </div>
          {changes.map((change) => (
            <div
              className="tw:grid tw:gap-1 tw:border-t tw:border-secondary tw:py-2 tw:md:grid-cols-[9rem_1fr_1fr] tw:md:gap-3"
              key={change.field}>
              <dt className="tw:text-xs tw:font-medium tw:text-tertiary">{change.field}</dt>
              <dd className="tw:min-w-0 tw:break-words tw:text-secondary">
                <span className="tw:text-xs tw:text-quaternary tw:md:hidden">Now: </span>
                {lines(change.before)}
              </dd>
              <dd className="tw:min-w-0 tw:break-words tw:text-primary">
                <span className="tw:text-xs tw:text-quaternary tw:md:hidden">
                  v{revision.version}:{' '}
                </span>
                {lines(change.after)}
              </dd>
            </div>
          ))}
        </dl>
      )}

      {archived && (
        <p className="tw:text-sm tw:text-tertiary">
          This policy is archived, which is final, so no version of it can be put back.
        </p>
      )}

      {!archived && changes.length > 0 && !confirming && (
        <div>
          <Button
            color="secondary"
            iconLeading={ClockRewind}
            onPress={() => setConfirming(true)}
            size="sm">
            Restore v{revision.version}
          </Button>
        </div>
      )}

      {!archived && changes.length > 0 && confirming && (
        <div className="tw:flex tw:flex-col tw:gap-3 tw:rounded-lg tw:bg-secondary tw:p-3">
          {live ? (
            <Impact
              data={impact.data}
              error={impact.error}
              restoring={revision.version}
            />
          ) : (
            <p className="tw:text-pretty tw:text-sm tw:text-tertiary">
              This policy is not in force, so restoring changes nobody&rsquo;s
              access until it is activated.
            </p>
          )}

          <p className="tw:text-pretty tw:text-xs tw:text-tertiary">
            Saved as v{current.version + 1}, a copy of v{revision.version}&rsquo;s
            rules. The policy stays{' '}
            {current.lifecycleState.replace('_', ' ').toLowerCase()}
            {live ? ', so the restored rules apply as soon as it is saved' : ''}. v
            {current.version} stays in the history.
          </p>

          <div>
            <label className="tw:text-xs tw:font-medium tw:text-secondary" htmlFor={fieldId}>
              Why
            </label>
            <textarea
              className="tw:mt-1 tw:w-full tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2.5 tw:py-2 tw:text-sm tw:text-primary tw:placeholder:text-quaternary"
              id={fieldId}
              onChange={(event) => setReason(event.target.value)}
              placeholder={`Why v${revision.version} is being put back`}
              rows={2}
              value={reason}
            />
            <p className="tw:mt-1 tw:text-xs tw:text-quaternary">
              Required. It goes into the history beside your name.
            </p>
          </div>

          {problem && <p className="tw:text-sm tw:text-error-primary">{problem}</p>}

          <div className="tw:flex tw:justify-end tw:gap-2">
            <Button
              color="secondary"
              onPress={() => {
                setConfirming(false);
                setProblem(null);
              }}
              size="sm">
              Cancel
            </Button>
            <Button
              isDisabled={!reason.trim() || restore.isPending}
              onPress={() => restore.mutate()}
              size="sm">
              {restore.isPending ? 'Restoring…' : `Restore v${revision.version}`}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

/** A side of a comparison: one line per rule, or a dash when it was not set. */
function lines(values: string[]) {
  if (!values.length) {
    return <span className="tw:text-quaternary">&mdash;</span>;
  }
  return values.map((value, index) => (
    <p className="tw:text-pretty" key={index}>
      {value}
    </p>
  ));
}

// ------------------------------------------------------------------- pieces

function Panel({
  title,
  children,
  action,
}: {
  title: string;
  children: React.ReactNode;
  action?: React.ReactNode;
}) {
  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-between tw:gap-3">
        <h2 className="tw:text-sm tw:font-semibold tw:text-primary">{title}</h2>
        {action}
      </div>
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

/** `count` for the one noun whose plural it gets wrong. */
function people(n: number): string {
  return n === 1 ? '1 person' : `${n} people`;
}

function count(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? '' : 's'}`;
}

function when(iso: string | null, withTime = true): string {
  if (!iso) {
    return '';
  }
  const at = new Date(iso);
  return Number.isNaN(at.getTime())
    ? iso
    : at.toLocaleString(undefined, withTime ? { dateStyle: 'medium', timeStyle: 'short' } : { dateStyle: 'medium' });
}
