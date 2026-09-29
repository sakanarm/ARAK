import { useEffect, useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Columns01, Database01, SearchMd, Table } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { apiErrorMessage } from '../../api/client';
import {
  previewPolicyScope,
  type PolicyScopePreview,
  type ScopePreviewTable,
} from '../../api/policies';
import type { Policy } from '../../generated/entity/policy/policy';

/** How many tables are asked for. `matched` is counted in full either way. */
const PREVIEW_LIMIT = 200;

/** Below this many groups every group is shown open; above it, the list is searchable. */
const SEARCH_FROM = 8;

/**
 * The parts of a draft the preview depends on.
 *
 * Reduced before it is keyed so that typing a description or picking a
 * subject does not ask the server again: only what decides which tables and
 * columns are covered is in here.
 */
function scopeKey(draft: Policy): string {
  return JSON.stringify({
    policyType: draft.policyType,
    scopeLevel: draft.scopeLevel,
    scopeFqn: draft.scopeFqn ?? null,
    selector: draft.selector ?? null,
    columnRules:
      draft.policyType === 'DATA'
        ? (draft.data?.columnRules ?? []).map((rule) => rule.columns ?? null)
        : [],
  });
}

/**
 * Whether a selector says anything yet.
 *
 * A new form starts with an empty object, which the engine matches against
 * nothing; asking the server about it would only report "no tables" for a
 * question nobody has asked yet.
 */
export function hasCondition(selector: Policy['selector'] | undefined): boolean {
  return Object.values(selector ?? {}).some(
    (part) => part != null && (!Array.isArray(part) || part.length > 0)
  );
}

/** Waits for the author to stop editing before the value moves. */
function useSettled<T>(value: T, delay = 400): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);
  return settled;
}

interface Group {
  parent: string;
  tables: ScopePreviewTable[];
}

/** Splits an FQN into the container it sits in and its own name. */
function splitFqn(fqn: string): { parent: string; name: string } {
  const cut = fqn.lastIndexOf('.');
  return cut < 0
    ? { parent: '', name: fqn }
    : { parent: fqn.slice(0, cut), name: fqn.slice(cut + 1) };
}

function groupByParent(tables: ScopePreviewTable[]): Group[] {
  const groups = new Map<string, ScopePreviewTable[]>();
  for (const table of tables) {
    const { parent } = splitFqn(table.fqn);
    groups.set(parent, [...(groups.get(parent) ?? []), table]);
  }
  return [...groups.entries()]
    .map(([parent, list]) => ({ parent, tables: list }))
    .sort((a, b) => a.parent.localeCompare(b.parent));
}

/**
 * Which tables the draft covers, while it is still being written.
 *
 * Step 3 is written against tags and domains rather than table names, which is
 * what keeps a policy current -- and also what makes it hard to see, from the
 * conditions alone, what it lands on today. This answers that on every edit,
 * using the same matcher the binding job uses after a save, so the list here is
 * the list the policy will bind to. Only names come back: never a row.
 */
export default function ScopePreview({ draft }: { draft: Policy }) {
  // The draft as it stood when the author paused, so the key and the document
  // sent are always the same edit.
  const settled = useSettled(draft);
  const key = useMemo(() => scopeKey(settled), [settled]);
  const hasSelector = hasCondition(draft.selector);
  const [search, setSearch] = useState('');

  const { data, error, isFetching, isPending } = useQuery({
    queryKey: ['policy-scope-preview', key],
    queryFn: () => previewPolicyScope(settled, PREVIEW_LIMIT),
    enabled: hasSelector && hasCondition(settled.selector),
    placeholderData: keepPreviousData,
    staleTime: 30_000,
  });

  return (
    <section
      aria-label="Tables this policy covers"
      aria-live="polite"
      className="tw:mt-5 tw:overflow-hidden tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs"
    >
      <header className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-3 tw:border-b tw:border-secondary tw:bg-secondary tw:px-5 tw:py-4">
        <div className="tw:min-w-0">
          <p className="tw:text-sm tw:font-semibold tw:text-primary">
            What this covers right now
          </p>
          <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">
            {scopeLine(draft)} Updates as you edit; nothing is saved.
          </p>
        </div>
        {isFetching && hasSelector ? (
          <span className="tw:text-xs tw:text-quaternary">Checking…</span>
        ) : null}
      </header>

      <div className="tw:px-5 tw:py-4">
        {!hasSelector ? (
          <Empty
            text="Add a condition above to see which tables it picks. A policy with no condition covers nothing."
          />
        ) : error ? (
          <div className="tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-3 tw:text-sm tw:text-error-primary">
            {apiErrorMessage(error, 'Could not work out which tables this covers.')}
          </div>
        ) : isPending || !data ? (
          <div
            aria-label="Loading tables"
            className="tw:h-24 tw:animate-pulse tw:rounded-lg tw:bg-secondary"
          />
        ) : (
          <Result
            data={data}
            dataPolicy={draft.policyType === 'DATA'}
            search={search}
            setSearch={setSearch}
          />
        )}
      </div>
    </section>
  );
}

function scopeLine(draft: Policy): string {
  if (!draft.scopeLevel || draft.scopeLevel === 'ORG' || !draft.scopeFqn?.trim()) {
    return 'Across every catalogued table.';
  }
  return `Inside ${draft.scopeFqn.trim()} only.`;
}

function Empty({ text }: { text: string }) {
  return (
    <div className="tw:flex tw:items-center tw:gap-3 tw:rounded-lg tw:border tw:border-dashed tw:border-secondary tw:p-4 tw:text-sm tw:text-tertiary">
      <Table aria-hidden className="tw:size-5 tw:flex-none tw:text-quaternary" />
      {text}
    </div>
  );
}

function Result({
  data,
  dataPolicy,
  search,
  setSearch,
}: {
  data: PolicyScopePreview;
  dataPolicy: boolean;
  search: string;
  setSearch: (next: string) => void;
}) {
  const groups = useMemo(() => groupByParent(data.tables), [data.tables]);
  const needle = search.trim().toLowerCase();
  const shown = needle
    ? groups
        .map((group) => ({
          ...group,
          tables: group.tables.filter((table) =>
            table.fqn.toLowerCase().includes(needle)
          ),
        }))
        .filter((group) => group.tables.length > 0)
    : groups;
  const columnCount = data.tables.reduce(
    (sum, table) => sum + table.columns.length,
    0
  );
  const share = data.scanned > 0 ? Math.round((data.matched / data.scanned) * 100) : 0;

  return (
    <>
      <div className="tw:grid tw:gap-3 tw:sm:grid-cols-3">
        <Stat
          label="Tables covered"
          tone={data.matched > 0 ? 'brand' : 'muted'}
          value={data.matched.toLocaleString()}
        />
        <Stat label="Tables in scope" value={data.scanned.toLocaleString()} />
        {dataPolicy ? (
          <Stat
            label={data.truncated ? 'Columns picked (shown)' : 'Columns picked'}
            value={columnCount.toLocaleString()}
          />
        ) : (
          <Stat label="Share of scope" value={`${share}%`} />
        )}
      </div>

      <div
        aria-hidden
        className="tw:mt-3 tw:h-1.5 tw:overflow-hidden tw:rounded-full tw:bg-secondary"
      >
        <div
          className="tw:h-full tw:rounded-full tw:bg-brand-solid tw:transition-all"
          style={{ width: `${share}%` }}
        />
      </div>

      {data.matched === 0 ? (
        <div className="tw:mt-4">
          <Empty
            text={
              data.scanned === 0
                ? 'Nothing is catalogued in this scope yet. Check the anchor in step 2, or import the source.'
                : 'No table in scope matches these conditions today. The policy will pick up any that match later.'
            }
          />
        </div>
      ) : (
        <>
          {groups.length >= SEARCH_FROM || data.tables.length > 20 ? (
            <label className="tw:mt-4 tw:flex tw:items-center tw:gap-2 tw:rounded-lg tw:border tw:border-primary tw:px-3 tw:py-2 tw:text-sm tw:shadow-xs tw:focus-within:ring-2 tw:focus-within:ring-brand">
              <SearchMd aria-hidden className="tw:size-4 tw:text-quaternary" />
              <input
                aria-label="Filter covered tables"
                className="tw:w-full tw:bg-transparent tw:text-primary tw:outline-none tw:placeholder:text-placeholder"
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Filter by name"
                value={search}
              />
            </label>
          ) : null}

          <ul className="tw:mt-4 tw:flex tw:max-h-96 tw:flex-col tw:gap-3 tw:overflow-y-auto tw:pr-1">
            {shown.map((group) => (
              <li
                className="tw:rounded-lg tw:border tw:border-secondary"
                key={group.parent}
              >
                <div className="tw:flex tw:items-center tw:gap-2 tw:border-b tw:border-secondary tw:bg-secondary tw:px-3 tw:py-2">
                  <Database01 aria-hidden className="tw:size-4 tw:flex-none tw:text-quaternary" />
                  <span className="tw:min-w-0 tw:truncate tw:text-xs tw:font-medium tw:text-secondary" title={group.parent}>
                    {group.parent || 'Top level'}
                  </span>
                  <span className="tw:ml-auto tw:text-xs tw:text-quaternary">
                    {group.tables.length} {group.tables.length === 1 ? 'table' : 'tables'}
                  </span>
                </div>
                <ul className="tw:divide-y tw:divide-secondary">
                  {group.tables.map((table) => (
                    <TableRow dataPolicy={dataPolicy} key={table.fqn} table={table} />
                  ))}
                </ul>
              </li>
            ))}
            {shown.length === 0 ? (
              <li className="tw:text-sm tw:text-tertiary">No covered table matches “{search}”.</li>
            ) : null}
          </ul>

          {data.truncated ? (
            <p className="tw:mt-3 tw:text-xs tw:text-quaternary">
              Showing the first {data.tables.length.toLocaleString()} of{' '}
              {data.matched.toLocaleString()}. The rest are covered too; the full
              list is on the policy page once it is saved.
            </p>
          ) : null}
        </>
      )}
    </>
  );
}

function Stat({
  label,
  value,
  tone = 'default',
}: {
  label: string;
  value: string;
  tone?: 'default' | 'brand' | 'muted';
}) {
  const colour =
    tone === 'brand'
      ? 'tw:text-brand-secondary'
      : tone === 'muted'
        ? 'tw:text-quaternary'
        : 'tw:text-primary';
  return (
    <div className="tw:rounded-lg tw:border tw:border-secondary tw:px-4 tw:py-3">
      <p className="tw:text-xs tw:text-tertiary">{label}</p>
      <p className={`tw:mt-1 tw:text-display-xs tw:font-semibold ${colour}`}>{value}</p>
    </div>
  );
}

function TableRow({
  table,
  dataPolicy,
}: {
  table: ScopePreviewTable;
  dataPolicy: boolean;
}) {
  const { name } = splitFqn(table.fqn);
  return (
    <li className="tw:flex tw:flex-wrap tw:items-center tw:gap-x-3 tw:gap-y-1.5 tw:px-3 tw:py-2">
      <Table aria-hidden className="tw:size-4 tw:flex-none tw:text-brand-secondary" />
      <Link
        className="tw:min-w-0 tw:truncate tw:text-sm tw:font-medium tw:text-primary tw:hover:text-brand-secondary tw:hover:underline"
        rel="noreferrer"
        target="_blank"
        title={table.fqn}
        to={`/catalog/${encodeURIComponent(table.fqn)}`}
      >
        {name}
      </Link>
      {dataPolicy ? (
        table.columns.length > 0 ? (
          <span className="tw:ml-auto tw:flex tw:flex-wrap tw:items-center tw:gap-1">
            <Columns01 aria-hidden className="tw:size-3.5 tw:text-quaternary" />
            {table.columns.map((column) => (
              <Badge color="brand" key={column} size="sm">
                {column}
              </Badge>
            ))}
          </span>
        ) : (
          <span className="tw:ml-auto tw:text-xs tw:text-quaternary">
            Table only — no column rule picks a column here
          </span>
        )
      ) : null}
    </li>
  );
}
