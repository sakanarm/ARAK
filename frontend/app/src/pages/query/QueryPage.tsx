import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import {
  AlertTriangle,
  Copy01,
  Expand01,
  Eye,
  EyeOff,
  FilterLines,
  Minimize01,
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
  const [fullscreen, setFullscreen] = useState(false);

  // The console fills whatever is left below the page chrome, measured rather
  // than assumed. The height used to be `calc(100vh - 8rem)`, which guessed at
  // the chrome above it; the guess was ~100px short, so on a 1366x768 screen
  // the bottom of the console -- the grid -- sat past the edge of a page that
  // does not scroll, and the rows were rendered but unreachable.
  const [shellNode, setShellNode] = useState<HTMLDivElement | null>(null);
  const available = useFillViewport(shellNode, fullscreen);

  // Esc leaves full screen. The button says so too, but a screen with no
  // visible chrome has to have the key that everything else uses to get out.
  useEffect(() => {
    if (!fullscreen) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setFullscreen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [fullscreen]);

  // Read at submit time rather than through state, so Ctrl+Enter runs the text
  // on screen and not the render before it.
  // The server clamps; saying so beside the field beats a silent truncation.
  const rowLimitNote =
    Number.parseInt(maxRows, 10) > MAX_ROWS ? ` (capped at ${MAX_ROWS})` : '';

  const latest = useRef({ sourceId, sql, asPrincipal, purpose, maxRows });
  latest.current = { sourceId, sql, asPrincipal, purpose, maxRows };

  // How much of the column the editor keeps. Everything below it is the grid,
  // so this is really "how many rows do I want to see at once" -- which depends
  // on the query and the screen, and is therefore the reader's decision rather
  // than a ratio we can pick for them.
  const [editorHeight, setEditorHeight] = useState(readEditorHeight);

  // A split remembered on a 27-inch monitor is taller than the whole pane on a
  // laptop. Clamped on the way out rather than on the way in, so the preference
  // survives and is merely not honoured where it would not fit.
  const [paneNode, setPaneNode] = useState<HTMLDivElement | null>(null);
  const paneHeight = useElementHeight(paneNode);
  const shownEditorHeight = paneHeight
    ? Math.min(
        editorHeight,
        Math.max(MIN_EDITOR_HEIGHT, paneHeight - MIN_RESULT_HEIGHT)
      )
    : editorHeight;

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

  const consoleTree = (
    <div
      className={
        fullscreen
          // Above the sticky top bar (z-50) and the mobile nav drawer
            // (z-60), because "full screen" that the chrome still
            // paints over is not full screen.
            ? 'tw:fixed tw:inset-0 tw:z-70 tw:flex tw:flex-col tw:bg-primary tw:p-4'
          : 'tw:flex tw:flex-col'
      }
      ref={setShellNode}
      style={fullscreen ? undefined : { height: available }}>
      {!fullscreen && (
        <header className="tw:shrink-0">
          <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">
            Query
          </h1>
          <p className="tw:mt-2 tw:max-w-3xl tw:text-md tw:text-tertiary">
            SQL runs through the platform, not beside it. Every table is
            resolved to an asset, the policy is compiled into the statement,
            and what you get back is what the policy allows. A statement the
            proxy cannot place a policy in front of is refused rather than
            sent.
          </p>
        </header>
      )}

      <div
        className={`tw:flex tw:min-h-0 tw:flex-1 tw:gap-4 ${
          fullscreen ? '' : 'tw:mt-6'
        }`}>
        <SchemaExplorer
          onInsert={insert}
          serviceFqn={selected?.omServiceFqn ?? null}
        />

        <div className="tw:flex tw:min-h-0 tw:min-w-0 tw:flex-1 tw:flex-col tw:gap-3">
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

            {/* One group, so the hint never wraps away from the button it
                sits beside and the pair stays inside the right edge. */}
            <div className="tw:ml-auto tw:flex tw:shrink-0 tw:items-center tw:gap-3">
              <span className="tw:hidden tw:text-xs tw:text-quaternary tw:xl:inline">
                Ctrl/⌘ + Enter to run
              </span>

              {/* The grid is the reason this screen exists, and on a laptop
                  the page chrome costs it about a third of its height. Giving
                  the console the whole viewport is cheaper than asking
                  somebody to drag the splitter every time they open it. */}
              <Button
                color="secondary"
                iconLeading={fullscreen ? Minimize01 : Expand01}
                onClick={() => setFullscreen((on) => !on)}
                size="sm">
                {fullscreen ? 'Exit full screen' : 'Full screen'}
              </Button>
            </div>
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

          <div className="tw:flex tw:min-h-0 tw:flex-1 tw:flex-col" ref={setPaneNode}>
            <div
              className="tw:flex tw:shrink-0"
              style={{ height: shownEditorHeight }}>
              <SqlEditor
                disabled={run.isPending}
                onChange={setSql}
                onRun={() => run.mutate()}
                value={sql}
              />
            </div>

            <Splitter height={shownEditorHeight} onChange={setEditorHeight} />

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
    </div>
  );

  // Portalled to the body rather than merely given a high z-index. The page
  // wrapper carries a filling `transform` animation, which makes it a
  // containing block for `position: fixed` and a stacking context of its own --
  // so `inset-0` resolved to the page area, not the viewport, and the sticky
  // top bar kept painting over an overlay nominally twenty layers above it.
  return fullscreen ? createPortal(consoleTree, document.body) : consoleTree;
}

const EDITOR_HEIGHT_KEY = 'arak.query.editorHeight';
const MIN_EDITOR_HEIGHT = 72;
// Tab bar, explanation strip and enough grid left over to read: a floor
// that only fits the chrome is a floor that guarantees an empty-looking grid.
const MIN_RESULT_HEIGHT = 240;
const DEFAULT_EDITOR_HEIGHT = 200;
// Below this the console is useless anyway, so a very short window gets a
// scrollbar rather than a console squeezed to nothing.
const MIN_CONSOLE_HEIGHT = 420;

/**
 * Remembered per browser, because the right split is a property of the screen
 * and of the work, not of the session. Wrapped because storage throws in a
 * private window and a thrown preference must not cost somebody the page.
 */
function readEditorHeight() {
  try {
    const saved = Number(window.localStorage.getItem(EDITOR_HEIGHT_KEY));
    if (Number.isFinite(saved) && saved >= MIN_EDITOR_HEIGHT) {
      return saved;
    }
  } catch {
    // No stored preference is not an error; it is the first visit.
  }
  return DEFAULT_EDITOR_HEIGHT;
}

/**
 * Drag to decide how much of the screen the rows get.
 *
 * <p>A fixed ratio cannot be right: a one-line statement against a thousand
 * rows and a forty-line statement against three want opposite splits, and both
 * are ordinary. Keyboard-operable as well as draggable -- a separator that only
 * answers to a mouse takes the grid away from anybody who cannot use one.
 */
function Splitter({
  height,
  onChange,
}: {
  height: number;
  onChange: (next: number) => void;
}) {
  function clamp(next: number, handle: HTMLElement | null) {
    // Measured against the column the handle actually sits in, so the grid
    // keeps a floor no matter how short the window is.
    const column = handle?.parentElement;
    const ceiling = column
      ? column.clientHeight - MIN_RESULT_HEIGHT
      : Number.MAX_SAFE_INTEGER;
    return Math.round(
      Math.min(Math.max(next, MIN_EDITOR_HEIGHT), Math.max(ceiling, MIN_EDITOR_HEIGHT))
    );
  }

  function remember(value: number) {
    try {
      window.localStorage.setItem(EDITOR_HEIGHT_KEY, String(value));
    } catch {
      // The split still applies for this visit; only the memory of it is lost.
    }
  }

  return (
    <div
      aria-label="Resize the editor"
      aria-orientation="horizontal"
      aria-valuenow={Math.round(height)}
      className="tw:group tw:-my-1.5 tw:flex tw:h-3 tw:shrink-0 tw:cursor-row-resize tw:items-center tw:justify-center"
      onKeyDown={(event) => {
        const step = event.shiftKey ? 64 : 16;
        const delta =
          event.key === 'ArrowUp' ? -step : event.key === 'ArrowDown' ? step : 0;
        if (delta === 0) {
          return;
        }
        event.preventDefault();
        const next = clamp(height + delta, event.currentTarget);
        onChange(next);
        remember(next);
      }}
      onPointerDown={(event) => {
        event.preventDefault();
        const handle = event.currentTarget;
        const startY = event.clientY;
        const startHeight = height;
        let settled = startHeight;

        const move = (moved: PointerEvent) => {
          settled = clamp(startHeight + moved.clientY - startY, handle);
          onChange(settled);
        };
        const stop = () => {
          window.removeEventListener('pointermove', move);
          window.removeEventListener('pointerup', stop);
          remember(settled);
        };
        window.addEventListener('pointermove', move);
        window.addEventListener('pointerup', stop);
      }}
      role="separator"
      tabIndex={0}>
      <span className="tw:h-0.5 tw:w-10 tw:rounded-full tw:bg-border-secondary tw:transition tw:group-hover:bg-brand-solid tw:group-focus:bg-brand-solid" />
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
    <section className="tw:flex tw:min-h-0 tw:flex-1 tw:flex-col tw:overflow-hidden tw:rounded-lg tw:border tw:border-secondary tw:bg-primary">
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
    // Capped and scrollable: an asset with a dozen masked columns must not be
    // able to push the rows it is describing off the screen. The explanation
    // exists to make the grid readable, so it never outranks the grid.
    <div className="tw:max-h-[30%] tw:shrink-0 tw:space-y-2 tw:overflow-auto tw:border-b tw:border-secondary tw:bg-secondary tw:px-3 tw:py-2">
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

/**
 * The height left between the top of an element and the bottom of the window.
 *
 * Measured, not computed from a constant: the chrome above this console is a
 * global header plus a page title whose description wraps to a different
 * number of lines at different widths, so any `calc(100vh - <constant>)` is
 * right at exactly one viewport size and too tall at every smaller one. Too
 * tall is the dangerous direction here, because the page does not scroll --
 * the overflow is simply off the screen.
 */
function useFillViewport(node: HTMLElement | null, disabled: boolean) {
  const [height, setHeight] = useState<number | undefined>(undefined);

  useLayoutEffect(() => {
    if (disabled) {
      setHeight(undefined);
      return;
    }
    if (!node) return;

    const measure = () => {
      // innerHeight and not 100vh: on a phone the two differ by the browser
      // chrome, in the direction that pushes content off the bottom.
      const top = node.getBoundingClientRect().top;
      setHeight(Math.max(MIN_CONSOLE_HEIGHT, window.innerHeight - top - 32));
    };

    measure();
    // The element's own top moves when the header above it rewraps, which a
    // window resize does not always accompany -- a collapsing sidebar, for one.
    const observer = new ResizeObserver(measure);
    observer.observe(document.body);
    window.addEventListener('resize', measure);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', measure);
    };
  }, [node, disabled]);

  return height;
}

/** The live height of an element, for layout that has to add up. */
function useElementHeight(node: HTMLElement | null) {
  const [height, setHeight] = useState(0);

  useLayoutEffect(() => {
    if (!node) return;
    const observer = new ResizeObserver(() => setHeight(node.clientHeight));
    observer.observe(node);
    setHeight(node.clientHeight);
    return () => observer.disconnect();
  }, [node]);

  return height;
}
