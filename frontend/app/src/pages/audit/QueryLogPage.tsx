import { useEffect, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useInfiniteQuery } from '@tanstack/react-query';
import {
  AlertTriangle,
  CheckCircle,
  ChevronDown,
  ChevronRight,
  ClipboardCheck,
  EyeOff,
  SearchRefraction,
  XCircle,
} from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { relativeTime } from '../../components/widgets';
import { apiErrorMessage } from '../../api/client';
import {
  CATEGORY_LABELS,
  fetchQueryLog,
  type QueryLogRow,
  type QueryLogScope,
  type QueryOutcome,
} from '../../api/audit';
import { Select, TextField } from '../policies/controls';

/**
 * The query log (FR-8.3): what was sent through the query proxy, what it
 * became, and how it ended.
 *
 * The filters live in the address, so a number elsewhere in the console -- a
 * refusal count on the dashboard, a table's page -- can link straight to the
 * rows behind it. The server decides which rows this reader gets and what of
 * each; this page draws what came back and says plainly when something was
 * withheld, rather than leaving a blank where a statement would be.
 */

const PAGE = 50;

const OUTCOMES: { value: QueryOutcome | ''; label: string }[] = [
  { value: '', label: 'All' },
  { value: 'EXECUTED', label: 'Ran' },
  { value: 'REJECTED', label: 'Refused' },
  { value: 'FAILED', label: 'Failed' },
];

const WINDOWS = [
  { value: '1', label: 'Last 24 hours' },
  { value: '7', label: 'Last 7 days' },
  { value: '30', label: 'Last 30 days' },
  { value: '90', label: 'Last 90 days' },
  { value: '365', label: 'Last year' },
];

const SCOPE_TEXT: Record<QueryLogScope, string> = {
  EVERYTHING:
    'Every statement sent through the query proxy, as it was written and as it ran after policy was compiled into it.',
  OWNED:
    'Your own queries, and every query that touched a table you own. A statement that also read somebody else’s table is shown without its text.',
  OWN: 'The queries you have run through the query proxy, and any an administrator ran as you.',
};

export default function QueryLogPage() {
  const [params, setParams] = useSearchParams();
  const raw = params.get('outcome');
  const outcome: QueryOutcome | '' =
    raw === 'EXECUTED' || raw === 'REJECTED' || raw === 'FAILED' ? raw : '';
  const days = WINDOWS.some((w) => w.value === params.get('days')) ? params.get('days')! : '30';
  const text = params.get('q') ?? '';
  const principal = params.get('principal') ?? '';
  const table = params.get('table') ?? '';

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params);
    for (const [key, value] of Object.entries(next)) {
      if (value === '') {
        merged.delete(key);
      } else {
        merged.set(key, value);
      }
    }
    setParams(merged, { replace: true });
  }

  const log = useInfiniteQuery({
    queryKey: ['audit', 'queries', outcome, days, text, principal, table],
    queryFn: ({ pageParam }) =>
      fetchQueryLog({
        outcome: outcome || null,
        days: Number(days),
        q: text || null,
        principal: principal || null,
        assetFqn: table || null,
        before: pageParam,
        limit: PAGE,
      }),
    initialPageParam: null as number | null,
    getNextPageParam: (last) => last.nextBefore ?? undefined,
  });

  const first = log.data?.pages[0];
  const counts = first?.counts ?? null;
  const scope = first?.scope;
  const rows = log.data?.pages.flatMap((page) => page.rows) ?? [];
  const filtered = outcome !== '' || text !== '' || principal !== '' || table !== '';

  return (
    <div className="tw:flex tw:flex-col">
      <header className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-6 tw:py-5 tw:shadow-xs">
        <div className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-4">
          <div className="tw:flex tw:min-w-0 tw:items-start tw:gap-4">
            <span
              aria-hidden
              className="tw:flex tw:size-12 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-xl tw:bg-utility-brand-50">
              <ClipboardCheck className="tw:size-6 tw:text-fg-brand-primary" />
            </span>
            <div className="tw:min-w-0">
              <h1 className="tw:text-display-xs tw:font-semibold tw:text-primary">Query log</h1>
              <p className="tw:mt-1 tw:max-w-2xl tw:text-pretty tw:text-sm tw:text-tertiary">
                {scope ? SCOPE_TEXT[scope] : 'Statements sent through the query proxy.'}
              </p>
            </div>
          </div>
          <Link
            className="tw:inline-flex tw:items-center tw:gap-1.5 tw:rounded-lg tw:border tw:border-primary tw:bg-primary tw:px-3 tw:py-2 tw:text-sm tw:font-semibold tw:text-secondary tw:shadow-xs tw:transition tw:hover:bg-primary_hover"
            to="/query">
            <SearchRefraction className="tw:size-4 tw:text-fg-quaternary" />
            Open Query
          </Link>
        </div>

        <dl className="tw:mt-5 tw:flex tw:flex-wrap tw:gap-y-4">
          <HeaderStat first label="Queries" value={counts?.total} />
          <HeaderStat label="Ran" value={counts?.executed} />
          <HeaderStat label="Refused" tone="error" value={counts?.rejected} />
          <HeaderStat label="Failed" tone="warning" value={counts?.failed} />
          <HeaderStat
            label="Refused share"
            text={
              counts && counts.total > 0
                ? `${Math.round((counts.rejected / counts.total) * 100)}%`
                : undefined
            }
          />
        </dl>
      </header>

      <section
        aria-label="Filters"
        className="tw:mt-4 tw:flex tw:flex-wrap tw:items-center tw:gap-3 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-3 tw:shadow-xs">
        <div aria-label="Outcome" className="tw:flex tw:gap-0.5" role="radiogroup">
          {OUTCOMES.map((option) => {
            const active = option.value === outcome;
            return (
              <button
                aria-checked={active}
                className={`tw:shrink-0 tw:cursor-pointer tw:rounded-md tw:px-2.5 tw:py-1 tw:text-xs tw:font-semibold tw:transition-colors ${
                  active
                    ? 'tw:bg-utility-brand-50 tw:text-brand-secondary'
                    : 'tw:text-tertiary tw:hover:bg-primary_hover tw:hover:text-secondary'
                }`}
                key={option.label}
                onClick={() => update({ outcome: option.value })}
                role="radio"
                type="button">
                {option.label}
              </button>
            );
          })}
        </div>
        <DeferredField
          ariaLabel="Search the SQL"
          className="tw:w-56"
          onCommit={(value) => update({ q: value })}
          placeholder="SQL contains…"
          value={text}
        />
        {scope !== 'OWN' && (
          <DeferredField
            ariaLabel="Principal"
            className="tw:w-40"
            onCommit={(value) => update({ principal: value })}
            placeholder="Principal"
            value={principal}
          />
        )}
        <DeferredField
          ariaLabel="Table"
          className="tw:w-72"
          onCommit={(value) => update({ table: value })}
          placeholder="Table FQN, e.g. demo-pg.salesdb.sales.customer"
          value={table}
        />
        <Select
          ariaLabel="Window"
          className="tw:w-44"
          onChange={(value) => update({ days: value === '30' ? '' : value })}
          options={WINDOWS}
          value={days}
        />
        {filtered && (
          <button
            className="tw:cursor-pointer tw:text-xs tw:font-semibold tw:text-brand-secondary tw:hover:underline"
            onClick={() => update({ outcome: '', q: '', principal: '', table: '' })}
            type="button">
            Clear filters
          </button>
        )}
      </section>

      <section
        aria-label="Queries"
        className="tw:mt-4 tw:overflow-hidden tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
        {log.isLoading ? (
          <p className="tw:px-5 tw:py-8 tw:text-sm tw:text-tertiary">Loading…</p>
        ) : log.isError ? (
          <p
            className="tw:m-4 tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
            role="alert">
            <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
            <span>{apiErrorMessage(log.error, 'The query log could not be read.')}</span>
          </p>
        ) : rows.length === 0 ? (
          <div className="tw:flex tw:flex-col tw:items-center tw:gap-3 tw:px-6 tw:py-12 tw:text-center">
            <ClipboardCheck className="tw:size-7 tw:text-fg-quaternary" />
            <p className="tw:max-w-sm tw:text-sm tw:text-tertiary">
              {filtered
                ? 'No query in this window matches these filters.'
                : 'Nothing has been sent through the query proxy in this window.'}
            </p>
          </div>
        ) : (
          <ul className="tw:divide-y tw:divide-secondary">
            {rows.map((row) => (
              <LogRow key={row.id} row={row} />
            ))}
          </ul>
        )}
        {log.hasNextPage && (
          <div className="tw:border-t tw:border-secondary tw:p-3 tw:text-center">
            <button
              className="tw:cursor-pointer tw:rounded-lg tw:border tw:border-primary tw:bg-primary tw:px-3 tw:py-1.5 tw:text-sm tw:font-semibold tw:text-secondary tw:shadow-xs tw:hover:bg-primary_hover tw:disabled:cursor-default tw:disabled:opacity-60"
              disabled={log.isFetchingNextPage}
              onClick={() => log.fetchNextPage()}
              type="button">
              {log.isFetchingNextPage ? 'Loading…' : 'Load older queries'}
            </button>
          </div>
        )}
      </section>
    </div>
  );
}

/**
 * A text filter that reaches the address a moment after typing stops, so each
 * keystroke is not a request and not an entry in the history.
 */
function DeferredField({
  value,
  onCommit,
  placeholder,
  ariaLabel,
  className,
}: {
  value: string;
  onCommit: (value: string) => void;
  placeholder: string;
  ariaLabel: string;
  className?: string;
}) {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);
  useEffect(() => {
    if (draft.trim() === value) return undefined;
    const timer = setTimeout(() => onCommit(draft.trim()), 350);
    return () => clearTimeout(timer);
    // onCommit is a fresh closure every render; the draft is what matters.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft, value]);
  return (
    <TextField
      ariaLabel={ariaLabel}
      className={className}
      onChange={setDraft}
      placeholder={placeholder}
      value={draft}
    />
  );
}

function HeaderStat({
  label,
  value,
  text,
  tone,
  first = false,
}: {
  label: string;
  value?: number;
  text?: string;
  tone?: 'error' | 'warning';
  first?: boolean;
}) {
  const shown = text ?? (value === undefined ? undefined : value.toLocaleString());
  const colour =
    tone === 'error' && value
      ? 'tw:text-error-primary'
      : tone === 'warning' && value
        ? 'tw:text-warning-primary'
        : 'tw:text-primary';
  return (
    <div className="tw:flex tw:items-stretch">
      {!first && (
        <span aria-hidden="true" className="tw:mx-6 tw:w-px tw:self-stretch tw:bg-border-secondary" />
      )}
      <div>
        <dt className="tw:text-sm tw:text-tertiary">{label}</dt>
        <dd className={`tw:mt-1 tw:text-display-xs tw:font-semibold tw:tabular-nums ${colour}`}>
          {shown ?? '–'}
        </dd>
      </div>
    </div>
  );
}

const OUTCOME_LOOK: Record<
  QueryOutcome,
  { label: string; icon: typeof CheckCircle; colour: 'success' | 'error' | 'warning'; fg: string }
> = {
  EXECUTED: { label: 'Ran', icon: CheckCircle, colour: 'success', fg: 'tw:text-fg-success-primary' },
  REJECTED: { label: 'Refused', icon: XCircle, colour: 'error', fg: 'tw:text-fg-error-primary' },
  FAILED: { label: 'Failed', icon: AlertTriangle, colour: 'warning', fg: 'tw:text-fg-warning-primary' },
};

/** One statement: a line to scan, and everything about it underneath. */
function LogRow({ row }: { row: QueryLogRow }) {
  const [open, setOpen] = useState(false);
  const look = OUTCOME_LOOK[row.outcome] ?? OUTCOME_LOOK.FAILED;
  const Icon = look.icon;
  const summary = row.sqlHidden
    ? 'Statement not shown: it also read a table that is not yours'
    : firstLine(row.originalSql);

  return (
    <li>
      <button
        aria-expanded={open}
        className="tw:flex tw:w-full tw:cursor-pointer tw:items-start tw:gap-3 tw:px-5 tw:py-3 tw:text-left tw:transition-colors tw:hover:bg-primary_hover"
        onClick={() => setOpen((was) => !was)}
        type="button">
        <Icon aria-hidden className={`tw:mt-0.5 tw:size-5 tw:shrink-0 ${look.fg}`} />
        <div className="tw:min-w-0 tw:flex-1">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-x-2 tw:gap-y-1 tw:text-sm">
            <span className="tw:font-semibold tw:text-primary">{row.principal}</span>
            {row.runBy && (
              <span className="tw:text-xs tw:text-tertiary">run by {row.runBy}</span>
            )}
            {row.sourceName && (
              <span className="tw:text-xs tw:text-tertiary">on {row.sourceName}</span>
            )}
            {row.category && (
              <Badge color={look.colour} size="sm" type="pill-color">
                {CATEGORY_LABELS[row.category] ?? row.category}
              </Badge>
            )}
          </div>
          <p
            className={`tw:mt-1 tw:truncate tw:text-xs ${
              row.sqlHidden ? 'tw:italic tw:text-quaternary' : 'tw:font-mono tw:text-secondary'
            }`}>
            {summary}
          </p>
        </div>
        <div className="tw:flex tw:shrink-0 tw:flex-col tw:items-end tw:gap-1 tw:text-xs tw:text-tertiary">
          <time dateTime={row.occurredAt} title={new Date(row.occurredAt).toLocaleString()}>
            {relativeTime(row.occurredAt)}
          </time>
          <span className="tw:tabular-nums">{measures(row)}</span>
        </div>
        {open ? (
          <ChevronDown aria-hidden className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
        ) : (
          <ChevronRight aria-hidden className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
        )}
      </button>
      {open && <RowDetail row={row} />}
    </li>
  );
}

function RowDetail({ row }: { row: QueryLogRow }) {
  return (
    <div className="tw:flex tw:flex-col tw:gap-4 tw:border-t tw:border-secondary tw:bg-secondary_subtle tw:px-5 tw:py-4 tw:pl-13">
      <dl className="tw:grid tw:gap-x-6 tw:gap-y-2 tw:text-sm tw:sm:grid-cols-4">
        <Fact label="When">{new Date(row.occurredAt).toLocaleString()}</Fact>
        <Fact label="Ran as">{row.principal}</Fact>
        <Fact label="Sent by">{row.runBy ?? row.principal}</Fact>
        <Fact label="Outcome">{OUTCOME_LOOK[row.outcome]?.label ?? row.outcome}</Fact>
      </dl>

      {row.outcome !== 'EXECUTED' && (
        <div>
          <h3 className="tw:text-xs tw:font-semibold tw:text-tertiary">Why it did not run</h3>
          <p className="tw:mt-1 tw:text-sm tw:text-secondary">
            {row.rejectReason ??
              (row.category
                ? `${CATEGORY_LABELS[row.category]}. The full message names a table that is not yours.`
                : 'No reason was recorded.')}
          </p>
        </div>
      )}

      <div>
        <h3 className="tw:text-xs tw:font-semibold tw:text-tertiary">Tables</h3>
        {row.assets.length === 0 && row.hiddenAssets === 0 ? (
          <p className="tw:mt-1 tw:text-sm tw:text-tertiary">
            No table was resolved before it stopped.
          </p>
        ) : (
          <ul className="tw:mt-1 tw:flex tw:flex-wrap tw:gap-1.5">
            {row.assets.map((fqn) => (
              <li key={fqn}>
                <Link
                  className="tw:inline-flex tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2 tw:py-0.5 tw:font-mono tw:text-xs tw:text-secondary tw:hover:border-brand tw:hover:text-brand-secondary"
                  to={`/catalog/${encodeURIComponent(fqn)}`}>
                  {fqn}
                </Link>
              </li>
            ))}
            {row.hiddenAssets > 0 && (
              <li className="tw:self-center tw:text-xs tw:text-tertiary">
                and {row.hiddenAssets} more {row.hiddenAssets === 1 ? 'table' : 'tables'} that
                {row.hiddenAssets === 1 ? ' is' : ' are'} not yours
              </li>
            )}
          </ul>
        )}
      </div>

      {row.sqlHidden ? (
        <p className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-primary tw:px-3 tw:py-2 tw:text-xs tw:text-tertiary">
          <EyeOff className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
          <span>
            {row.outcome === 'REJECTED'
              ? 'A refused statement is not shown to a table’s owner: it stopped at the refusal, and the rest of it may name tables that are not yours.'
              : 'The statement also read a table that is not yours, so its text is kept to the owners of every table in it.'}
          </span>
        </p>
      ) : (
        <>
          <SqlBlock label="As written">{row.originalSql}</SqlBlock>
          {row.rewrittenSql && row.rewrittenSql !== row.originalSql && (
            <SqlBlock label="As it ran, with policy compiled in">{row.rewrittenSql}</SqlBlock>
          )}
        </>
      )}
    </div>
  );
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="tw:min-w-0">
      <dt className="tw:text-xs tw:text-tertiary">{label}</dt>
      <dd className="tw:mt-0.5 tw:truncate tw:text-primary">{children}</dd>
    </div>
  );
}

function SqlBlock({ label, children }: { label: string; children: string | null }) {
  return (
    <div>
      <h3 className="tw:text-xs tw:font-semibold tw:text-tertiary">{label}</h3>
      <pre className="tw:mt-1 tw:max-h-64 tw:overflow-auto tw:whitespace-pre-wrap tw:break-words tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2 tw:font-mono tw:text-xs tw:text-primary">
        {children || '(empty)'}
      </pre>
    </div>
  );
}

function firstLine(sql: string | null): string {
  if (!sql || sql.trim() === '') return '(no SQL)';
  return sql.trim().replace(/\s+/g, ' ');
}

function measures(row: QueryLogRow): string {
  const parts: string[] = [];
  if (row.rowCount !== null) {
    parts.push(`${row.rowCount.toLocaleString()} ${row.rowCount === 1 ? 'row' : 'rows'}`);
  }
  if (row.durationMs !== null) {
    parts.push(`${row.durationMs.toLocaleString()} ms`);
  }
  return parts.join(' · ');
}
