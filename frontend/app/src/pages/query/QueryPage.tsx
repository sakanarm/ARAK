import { useMemo, useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import {
  AlertTriangle,
  Copy01,
  Eye,
  EyeOff,
  FilterLines,
  Play,
  Shield01,
} from '@untitledui/icons';
import { apiErrorMessage } from '../../api/client';
import { fetchPrincipals } from '../../api/governance';
import { fetchSources } from '../../api/sources';
import {
  DEFAULT_ROWS,
  MAX_ROWS,
  runQuery,
  type Explanation,
  type QueryResult,
} from '../../api/query';
import { Select, TextField } from '../policies/controls';
import SchemaExplorer from './SchemaExplorer';
import SqlEditor from './SqlEditor';

/**
 * Enforcement mode 5.2, with a console in front of it (FR-6.3, 5.2a).
 *
 * <p>Nothing on this screen decides anything. The SQL goes to the proxy, which
 * resolves every table to an asset, asks the engine for a decision and rewrites
 * the statement so the row filter and the masks are part of it. What comes back
 * has already been through the policy, which is why the rewritten statement is
 * shown beside the rows rather than hidden: a data owner who cannot read what
 * ran is being asked to take the platform's word for it (FR-5.4).
 *
 * <h2>Why "run as" is here and not only in the simulator</h2>
 *
 * <p>It is the same code path as a real query, not a preview of one. A
 * simulator that approximates enforcement is worse than no simulator, because
 * people trust it. Impersonating someone needs POLICY_AUTHOR, DATA_OWNER,
 * AUDITOR or PLATFORM_ADMIN, and the server — not this page — enforces that.
 */
export default function QueryPage() {
  const [sourceId, setSourceId] = useState('');
  const [sql, setSql] = useState('SELECT * FROM sales.customer');
  const [asPrincipal, setAsPrincipal] = useState('');
  const [purpose, setPurpose] = useState('');
  const [maxRows, setMaxRows] = useState(String(DEFAULT_ROWS));
  const [tab, setTab] = useState<'results' | 'sql' | 'details'>('results');

  // Read at submit time rather than through state, so Ctrl+Enter runs the text
  // on screen and not the render before it.
  // The server clamps; saying so beside the field beats a silent truncation.
  const rowLimitNote =
    Number.parseInt(maxRows, 10) > MAX_ROWS ? ` (capped at ${MAX_ROWS})` : '';

  const latest = useRef({ sourceId, sql, asPrincipal, purpose, maxRows });
  latest.current = { sourceId, sql, asPrincipal, purpose, maxRows };

  const { data: sources } = useQuery({
    queryKey: ['sources'],
    queryFn: fetchSources,
    staleTime: 60_000,
  });

  const { data: principals } = useQuery({
    queryKey: ['principals', 'query-as'],
    queryFn: () => fetchPrincipals({ type: 'USER', limit: 200 }),
    staleTime: 5 * 60 * 1000,
  });

  const usable = useMemo(
    () => (sources ?? []).filter((source) => source.enabled),
    [sources]
  );

  // One source, so there is nothing to choose: pick it rather than making the
  // first query fail on an empty select.
  const effectiveSource = sourceId || (usable.length === 1 ? usable[0].id : '');
  const selected = usable.find((source) => source.id === effectiveSource);

  const run = useMutation({
    mutationFn: () => {
      const now = latest.current;
      const rows = Number.parseInt(now.maxRows, 10);
      return runQuery({
        sourceId: now.sourceId || (usable.length === 1 ? usable[0].id : ''),
        sql: now.sql,
        asPrincipal: now.asPrincipal || null,
        maxRows: Number.isFinite(rows) ? Math.min(rows, MAX_ROWS) : DEFAULT_ROWS,
        purpose: now.purpose || null,
      });
    },
    onSuccess: () => setTab('results'),
  });

  const result = run.data;

  function insert(text: string) {
    setSql((current) =>
      current.endsWith(' ') || current.length === 0
        ? current + text
        : `${current} ${text}`
    );
  }

  return (
    <div className="tw:flex tw:h-[calc(100vh-8rem)] tw:flex-col">
      <header className="tw:shrink-0">
        <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">
          Query
        </h1>
        <p className="tw:mt-2 tw:max-w-3xl tw:text-md tw:text-tertiary">
          SQL runs through the platform, not beside it. Every table is resolved
          to an asset, the policy is compiled into the statement, and what you
          get back is what the policy allows. A statement the proxy cannot place
          a policy in front of is refused rather than sent.
        </p>
      </header>

      <div className="tw:mt-6 tw:flex tw:min-h-0 tw:flex-1 tw:gap-4">
        <SchemaExplorer
          onInsert={insert}
          serviceFqn={selected?.omServiceFqn ?? null}
        />

        <div className="tw:flex tw:min-w-0 tw:flex-1 tw:flex-col tw:gap-3">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <Button
              color="primary"
              iconLeading={Play}
              isDisabled={
                run.isPending || !effectiveSource || sql.trim().length === 0
              }
              onClick={() => run.mutate()}
              size="sm">
              {run.isPending ? 'Running…' : 'Run'}
            </Button>

            <Select
              ariaLabel="Source"
              className="tw:w-56"
              onChange={setSourceId}
              options={usable.map((source) => ({
                value: source.id,
                label: source.name,
                hint: `${source.engine} · ${source.host}:${source.port}`,
              }))}
              placeholder="Choose a source"
              value={effectiveSource}
            />

            <Select
              ariaLabel="Run as"
              className="tw:w-52"
              onChange={setAsPrincipal}
              options={[
                // Spelled out because the obvious reading of "as myself" is
                // "unfiltered", and it is not: your own account is a principal
                // like any other and is denied by default like any other.
                { value: '', label: 'As myself (policies apply)' },
                ...(principals ?? []).map((principal) => ({
                  value: principal.username,
                  label: principal.displayName ?? principal.username,
                  hint: principal.username,
                })),
              ]}
              value={asPrincipal}
            />

            {/* Typed, not picked from a list: a fixed set of four numbers is
                never the number somebody wants, and the server clamps to
                MAX_ROWS anyway, so there is nothing a free field can break. */}
            <div className="tw:flex tw:items-center tw:gap-1.5">
              <TextField
                ariaLabel="Row limit"
                className="tw:w-24"
                onChange={(value) => setMaxRows(value.replace(/[^0-9]/g, ''))}
                placeholder={String(DEFAULT_ROWS)}
                value={maxRows}
              />
              <span className="tw:whitespace-nowrap tw:text-xs tw:text-tertiary">
                rows{rowLimitNote}
              </span>
            </div>

            <Select
              ariaLabel="Purpose"
              className="tw:w-48"
              onChange={setPurpose}
              options={[
                { value: '', label: 'No purpose' },
                { value: 'fraud-analysis', label: 'fraud-analysis' },
                { value: 'reporting', label: 'reporting' },
                { value: 'support', label: 'support' },
              ]}
              value={purpose}
            />

            <span className="tw:ml-auto tw:text-xs tw:text-quaternary">
              Ctrl/⌘ + Enter to run
            </span>
          </div>

          {/* Shown either way. Saying nothing when no role is picked is what
              made people read the blank state as "no policy" and then read the
              refusal that followed as a bug in the platform. */}
          <p className="tw:flex tw:items-center tw:gap-2 tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:px-3 tw:py-2 tw:text-xs tw:text-secondary">
            <Eye className="tw:size-3.5 tw:shrink-0 tw:text-tertiary" />
            {asPrincipal ? (
              <span>
                Running as <strong>{asPrincipal}</strong>. This is the
                enforcement path itself, not a preview of it — the rows below
                are the rows they would get, and the attempt is audited under
                your name.
              </span>
            ) : (
              <span>
                Running as yourself. Being a platform administrator grants no
                access to data — every query is evaluated against the same
                policies, and an account no policy names is denied. Pick
                somebody in <strong>Run as</strong> to see what they would get.
              </span>
            )}
          </p>

          <SqlEditor
            disabled={run.isPending}
            onChange={setSql}
            onRun={() => run.mutate()}
            value={sql}
          />

          <ResultPanel
            error={run.error}
            isPending={run.isPending}
            result={result}
            setTab={setTab}
            tab={tab}
          />
        </div>
      </div>
    </div>
  );
}

function ResultPanel({
  result,
  error,
  isPending,
  tab,
  setTab,
}: {
  result: QueryResult | undefined;
  error: unknown;
  isPending: boolean;
  tab: 'results' | 'sql' | 'details';
  setTab: (tab: 'results' | 'sql' | 'details') => void;
}) {
  if (error) {
    return (
      <section className="tw:shrink-0 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4">
        <h2 className="tw:flex tw:items-center tw:gap-2 tw:text-sm tw:font-semibold tw:text-error-primary">
          <Shield01 className="tw:size-4" />
          Refused
        </h2>
        <p className="tw:mt-1 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(
            error,
            'The statement was not run and the service did not say why.'
          )}
        </p>
      </section>
    );
  }

  if (isPending) {
    return (
      <section className="tw:shrink-0 tw:rounded-lg tw:border tw:border-secondary tw:p-4 tw:text-sm tw:text-tertiary">
        Compiling the policy into the statement…
      </section>
    );
  }

  if (!result) {
    return (
      <section className="tw:shrink-0 tw:rounded-lg tw:border tw:border-dashed tw:border-secondary tw:p-6 tw:text-center tw:text-sm tw:text-tertiary">
        Results appear here. Qualify every table with its schema — the proxy
        refuses a bare name rather than guessing which one was meant.
      </section>
    );
  }

  return (
    <section className="tw:flex tw:min-h-0 tw:flex-[1.1] tw:flex-col tw:overflow-hidden tw:rounded-lg tw:border tw:border-secondary tw:bg-primary">
      <div className="tw:flex tw:shrink-0 tw:items-center tw:gap-1 tw:border-b tw:border-secondary tw:px-2">
        {(
          [
            ['results', `Results (${result.rows.length})`],
            ['sql', 'Statement that ran'],
            ['details', 'Job details'],
          ] as const
        ).map(([key, label]) => (
          <button
            className={`tw:border-b-2 tw:px-3 tw:py-2 tw:text-sm ${
              tab === key
                ? 'tw:border-brand tw:font-semibold tw:text-primary'
                : 'tw:border-transparent tw:text-tertiary'
            }`}
            key={key}
            onClick={() => setTab(key)}
            type="button">
            {label}
          </button>
        ))}

        <div className="tw:ml-auto tw:flex tw:items-center tw:gap-2 tw:pr-2">
          {result.truncated && (
            <Badge color="warning" size="sm" type="pill-color">
              truncated
            </Badge>
          )}
          <span className="tw:text-xs tw:text-quaternary">
            {result.millis} ms · as {result.principal}
          </span>
        </div>
      </div>

      {result.unenforceable.length > 0 && (
        <div className="tw:shrink-0 tw:border-b tw:border-secondary tw:bg-warning-primary tw:px-3 tw:py-2">
          {result.unenforceable.map((item, index) => (
            <p
              className="tw:flex tw:items-start tw:gap-2 tw:text-xs tw:text-warning-primary"
              key={index}>
              <AlertTriangle className="tw:mt-0.5 tw:size-3.5 tw:shrink-0" />
              {/* Tightened, never dropped — so this is a note, not an error. */}
              <span>
                {item.detail}
                {item.suggestedMode && ` Try ${item.suggestedMode}.`}
              </span>
            </p>
          ))}
        </div>
      )}

      {tab === 'results' && <AppliedPolicies result={result} />}

      <div className="tw:min-h-0 tw:flex-1 tw:overflow-auto">
        {tab === 'results' && <ResultTable result={result} />}
        {tab === 'sql' && <RewrittenSql sql={result.rewrittenSql} />}
        {tab === 'details' && <JobDetails result={result} />}
      </div>
    </section>
  );
}

function ResultTable({ result }: { result: QueryResult }) {
  // Lower-cased on both sides: the source decides the case of the column names
  // it hands back, and the policy was written against the catalog's spelling.
  const masked = useMemo(() => {
    const out = new Map<string, string>();
    for (const explanation of result.explanations ?? []) {
      for (const [column, detail] of Object.entries(
        explanation.maskedColumns ?? {}
      )) {
        out.set(column.toLowerCase(), detail);
      }
    }
    return out;
  }, [result.explanations]);

  if (result.rows.length === 0) {
    return (
      <p className="tw:p-6 tw:text-center tw:text-sm tw:text-tertiary">
        No rows. That is a result, not a failure — a row filter that excludes
        everything looks exactly like this, and the filters that were applied
        are listed above.
      </p>
    );
  }

  return (
    <table className="tw:w-full tw:border-collapse tw:text-sm">
      <thead className="tw:sticky tw:top-0 tw:bg-secondary">
        <tr>
          <th className="tw:w-12 tw:px-3 tw:py-2 tw:text-right tw:text-xs tw:font-medium tw:text-quaternary">
            #
          </th>
          {result.columns.map((column, index) => {
            const masking = masked.get(column.toLowerCase());
            return (
              <th
                className="tw:whitespace-nowrap tw:px-3 tw:py-2 tw:text-left tw:text-xs tw:font-semibold tw:text-secondary"
                key={column}>
                {column}
                <span className="tw:ml-2 tw:font-normal tw:text-quaternary">
                  {result.columnTypes[index]}
                </span>
                {masking && (
                  // Marked in the header and not only in the strip above,
                  // because a masked column whose values still look plausible
                  // is the one most likely to be read as the real thing.
                  <span
                    className="tw:ml-2 tw:inline-flex tw:items-center tw:gap-1 tw:rounded tw:bg-warning-primary tw:px-1.5 tw:py-0.5 tw:font-normal tw:text-warning-primary"
                    title={masking}>
                    <EyeOff className="tw:size-3" />
                    masked
                  </span>
                )}
              </th>
            );
          })}
        </tr>
      </thead>
      <tbody>
        {result.rows.map((row, rowIndex) => (
          <tr className="tw:border-t tw:border-secondary" key={rowIndex}>
            <td className="tw:px-3 tw:py-1.5 tw:text-right tw:text-xs tw:text-quaternary">
              {rowIndex + 1}
            </td>
            {row.map((cell, cellIndex) => (
              <td
                className="tw:whitespace-nowrap tw:px-3 tw:py-1.5 tw:font-mono tw:text-[13px] tw:text-primary"
                key={cellIndex}>
                {cell === null ? (
                  <span className="tw:italic tw:text-quaternary">null</span>
                ) : (
                  String(cell)
                )}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * What the policy did to this result, in words.
 *
 * Sits above the grid rather than inside Job details on purpose: somebody
 * comparing one principal against another is looking at the rows, and a
 * difference they cannot account for is the reason they opened this screen at
 * all. The rewritten statement says exactly the same thing, but only to a
 * reader willing to parse generated SQL.
 */
function AppliedPolicies({ result }: { result: QueryResult }) {
  const explanations = (result.explanations ?? []).filter(
    (item) =>
      item.rowFilters.length > 0 ||
      Object.keys(item.maskedColumns ?? {}).length > 0 ||
      item.hiddenColumns.length > 0
  );

  if (explanations.length === 0) {
    return null;
  }

  return (
    <div className="tw:shrink-0 tw:space-y-2 tw:border-b tw:border-secondary tw:bg-secondary tw:px-3 tw:py-2">
      {explanations.map((explanation) => (
        <ExplanationRow explanation={explanation} key={explanation.asset} />
      ))}
    </div>
  );
}

function ExplanationRow({ explanation }: { explanation: Explanation }) {
  const masked = Object.entries(explanation.maskedColumns ?? {});

  return (
    <div className="tw:flex tw:flex-col tw:gap-1 tw:text-xs">
      <p className="tw:flex tw:flex-wrap tw:items-center tw:gap-1.5 tw:text-tertiary">
        <Shield01 className="tw:size-3.5 tw:shrink-0" />
        <span className="tw:font-medium tw:text-secondary">
          {explanation.asset}
        </span>
        {explanation.policies.map((policy) => (
          <Badge color="gray" key={policy} size="sm" type="pill-color">
            {policy}
          </Badge>
        ))}
      </p>

      {explanation.rowFilters.map((filter, index) => (
        <p
          className="tw:flex tw:items-start tw:gap-1.5 tw:pl-5 tw:text-secondary"
          key={index}>
          <FilterLines className="tw:mt-0.5 tw:size-3.5 tw:shrink-0 tw:text-tertiary" />
          <span>
            Rows kept where <strong>{filter}</strong>
          </span>
        </p>
      ))}

      {masked.map(([column, detail]) => (
        <p
          className="tw:flex tw:items-start tw:gap-1.5 tw:pl-5 tw:text-secondary"
          key={column}>
          <EyeOff className="tw:mt-0.5 tw:size-3.5 tw:shrink-0 tw:text-tertiary" />
          <span>
            <code className="tw:font-mono">{column}</code> {detail}
          </span>
        </p>
      ))}

      {explanation.hiddenColumns.length > 0 && (
        <p className="tw:flex tw:items-start tw:gap-1.5 tw:pl-5 tw:text-secondary">
          <EyeOff className="tw:mt-0.5 tw:size-3.5 tw:shrink-0 tw:text-tertiary" />
          <span>
            Dropped from the projection entirely, so they are absent from the
            schema rather than merely blanked:{' '}
            <strong>{explanation.hiddenColumns.join(', ')}</strong>
          </span>
        </p>
      )}
    </div>
  );
}

function RewrittenSql({ sql }: { sql: string }) {
  return (
    <div className="tw:p-3">
      <div className="tw:mb-2 tw:flex tw:items-center tw:gap-2">
        <p className="tw:text-xs tw:text-tertiary">
          This is the statement the source received. The policy is inside it —
          the derived table carries the row filter and the masked projection,
          which is why a hidden column cannot come back through a star.
        </p>
        <Button
          className="tw:ml-auto tw:shrink-0"
          color="secondary"
          iconLeading={Copy01}
          onClick={() => navigator.clipboard?.writeText(sql)}
          size="sm">
          Copy
        </Button>
      </div>
      <pre className="tw:overflow-auto tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-3 tw:font-mono tw:text-[12px] tw:leading-5 tw:text-primary">
        {sql}
      </pre>
    </div>
  );
}

function JobDetails({ result }: { result: QueryResult }) {
  return (
    <dl className="tw:grid tw:grid-cols-[auto_1fr] tw:gap-x-6 tw:gap-y-2 tw:p-4 tw:text-sm">
      <Detail label="Ran as">{result.principal}</Detail>
      <Detail label="Duration">{result.millis} ms</Detail>
      <Detail label="Rows returned">
        {result.rows.length}
        {result.truncated && ' (truncated at the row limit)'}
      </Detail>
      <Detail label="Governed assets">
        {result.assets.length === 0 ? (
          <span className="tw:text-tertiary">none</span>
        ) : (
          <span className="tw:flex tw:flex-wrap tw:gap-1">
            {result.assets.map((asset) => (
              <Badge color="gray" key={asset} size="sm" type="pill-color">
                {asset}
              </Badge>
            ))}
          </span>
        )}
      </Detail>
      <Detail label="Audit">
        Logged to <code>audit_query</code> and <code>audit_decision</code>, with
        the statement as sent and the statement as rewritten.
      </Detail>
    </dl>
  );
}

function Detail({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <>
      <dt className="tw:text-tertiary">{label}</dt>
      <dd className="tw:text-primary">{children}</dd>
    </>
  );
}
