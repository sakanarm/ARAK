import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  Button as AriaButton,
  Dialog,
  DialogTrigger,
  Popover,
} from 'react-aria-components';
import { useMutation, useQueries, useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Chip as Badge } from '../../components/chips';
import {
  AlertTriangle,
  ClipboardCheck,
  Copy01,
  Download01,
  Expand01,
  Eye,
  EyeOff,
  FilterLines,
  Minimize01,
  Play,
  Settings01,
  Shield01,
} from '@untitledui/icons';
import { apiErrorMessage, fetchAsset, fetchAssets } from '../../api/client';
import { checkReadable, refusalOf } from '../../api/accessRequests';
import { useAssistStore } from '../../assist/assistStore';
import { useAuthStore } from '../../auth/authStore';
import { fetchPrincipals } from '../../api/governance';
import { fetchSources } from '../../api/sources';
import {
  busyOf,
  DEFAULT_ROWS,
  formatCost,
  MAX_ROWS,
  runQuery,
  type Explanation,
  type QueryResult,
} from '../../api/query';
import { download, exportName, toCsv, toXlsx } from '../../lib/tabular';
import { Field, Select, TextField } from '../policies/controls';
import BusyNote from './BusyNote';
import CachedNote from './CachedNote';
import RequestAccess from './RequestAccess';
import {
  ExplainButton,
  ExplanationCard,
  FixWithAi,
  useAssistReady,
  useSqlExplanation,
  WriteWithNokRak,
} from './QueryAssist';
import { NokRakButton } from '../../assist/NokRakAsk';
import SchemaExplorer from './SchemaExplorer';
import { SavedQueriesPanel, SaveQueryButton } from './SavedQueries';
import type { SavedQuery } from '../../api/savedQueries';
import SqlEditor, { type SqlCompletionSource } from './SqlEditor';
import { asCompletionTable, type CompletionTable } from './sqlCompletion';

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
 * people trust it. Running as somebody else is a platform administrator's
 * power alone: the rows that come back are that person's rows, so it reads
 * data on their behalf. The control below is hidden from everybody else, and
 * the server — not this page — is what enforces that.
 */
export default function QueryPage() {
  const [sourceId, setSourceId] = useState('');
  const [sql, setSql] = useState('SELECT * FROM sales.customer');
  // The saved query the editor was last opened from or saved as, so Save can
  // offer to update it rather than keep a second copy.
  const [opened, setOpened] = useState<SavedQuery | null>(null);
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

  // The same argument sideways. A schema three levels deep with long table
  // names does not fit in 256px, and the fix for that was horizontal
  // scrolling inside a panel nobody thought to scroll.
  const [sidebarWidth, setSidebarWidth] = useState(readSidebarWidth);

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

  const isAdmin = useAuthStore((state) => state.hasRole('PLATFORM_ADMIN'));

  const { data: principals } = useQuery({
    queryKey: ['principals', 'query-as'],
    queryFn: () => fetchPrincipals({ type: 'USER', limit: 200 }),
    staleTime: 5 * 60 * 1000,
    // Not fetched for somebody who cannot use it. The list of every user on
    // the platform is not secret, but asking for it to populate a control
    // that is not drawn is a request nobody made.
    enabled: isAdmin,
  });

  const usable = useMemo(
    () => (sources ?? []).filter((source) => source.enabled),
    [sources]
  );

  // One source, so there is nothing to choose: pick it rather than making the
  // first query fail on an empty select.
  const effectiveSource = sourceId || (usable.length === 1 ? usable[0].id : '');

  // Told to the assistant, so the schema it writes against is the schema on
  // screen and the dialect is the one this source actually speaks. It is
  // published rather than asked for: the assistant lives in the shell and has
  // no way to know what this page is pointed at.
  const offer = useAssistStore((state) => state.offer);
  const withdraw = useAssistStore((state) => state.withdraw);
  const drafted = useAssistStore((state) => state.sql);
  const takeSql = useAssistStore((state) => state.takeSql);
  const engine = usable.find((source) => source.id === effectiveSource)?.engine;

  // Explain and Fix with AI (M26). Drawn only for an account that has an
  // assistant; neither runs anything, and the assistant never sees a row.
  const explainReady = useAssistReady('EXPLAIN_SQL');
  const fixReady = useAssistReady('FIX_SQL');
  const writeReady = useAssistReady('WRITE_SQL');
  const [writing, setWriting] = useState(false);
  const { explain, about: explained } = useSqlExplanation();

  // What the editor suggests: the same catalog page the Explorer beside it
  // shows, by the same key, so the two cannot offer different tables and the
  // second one costs nothing.
  const { data: explorerPage } = useQuery({
    queryKey: ['query-explorer', '', effectiveSource],
    queryFn: () => fetchAssets({ search: '', sourceId: effectiveSource, limit: 500 }),
    enabled: Boolean(effectiveSource),
    staleTime: 60_000,
  });
  const tables = useMemo(
    () =>
      (explorerPage?.items ?? [])
        .filter((asset) => asset.assetType === 'TABLE' || asset.assetType === 'VIEW')
        .map((asset) => asCompletionTable(asset.fqn))
        .filter((table): table is CompletionTable => table !== null),
    [explorerPage]
  );

  // Columns are fetched a table at a time, when a suggestion first needs them.
  const [wantedColumns, setWantedColumns] = useState<string[]>([]);
  const needColumns = useCallback((fqns: string[]) => {
    setWantedColumns((current) => {
      const added = fqns.filter((fqn) => !current.includes(fqn));
      return added.length > 0 ? [...current, ...added] : current;
    });
  }, []);
  const columnQueries = useQueries({
    queries: wantedColumns.map((fqn) => ({
      queryKey: ['query-columns', fqn],
      queryFn: () => fetchAsset(fqn),
      staleTime: 5 * 60_000,
    })),
  });
  const columnsKey = columnQueries.map((query) => query.dataUpdatedAt).join(',');
  const columns = useMemo(() => {
    const out: Record<string, string[] | undefined> = {};
    wantedColumns.forEach((fqn, index) => {
      out[fqn] = columnQueries[index]?.data?.columns.map((column) => column.name);
    });
    return out;
    // The key stands for the query results, which are a new array every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wantedColumns, columnsKey]);

  // Whether the reader will be let in, beside each suggested table. Asked only
  // about the reader: while running as somebody else, "readable" would describe
  // the wrong person, so the badges are left off rather than shown wrong.
  const { data: readable } = useQuery({
    queryKey: ['query-readable', effectiveSource, purpose, explorerPage?.total ?? 0, tables.length],
    queryFn: () => checkReadable(tables.map((table) => table.fqn), purpose || null),
    enabled: tables.length > 0 && !asPrincipal,
    staleTime: 60_000,
  });

  const completion: SqlCompletionSource = useMemo(
    () => ({
      tables,
      columns,
      readable: asPrincipal ? undefined : readable,
      onNeedColumns: needColumns,
    }),
    [tables, columns, readable, asPrincipal, needColumns]
  );

  useEffect(() => {
    offer('sql', effectiveSource || null, engine ?? null);
    return () => withdraw('sql');
  }, [effectiveSource, engine, offer, withdraw]);

  // A draft replaces the editor rather than being appended to it. Appending
  // produced two statements in one box and a syntax error on the first run,
  // which read as the assistant being broken when it was the paste that was.
  // A statement from the chat names the source it was written for; the editor
  // follows it there, so the tables in it are the tables in the explorer.
  useEffect(() => {
    if (!drafted) {
      return;
    }
    // Arriving from the chat, the source list may not have loaded yet; wait
    // for it rather than dropping the source on the floor.
    if (drafted.sourceId && sources === undefined) {
      return;
    }
    setSql(drafted.text);
    if (drafted.sourceId && usable.some((source) => source.id === drafted.sourceId)) {
      setSourceId(drafted.sourceId);
    }
    takeSql();
  }, [drafted, takeSql, usable, sources]);


  // What the last run was, for a request made from its refusal: the editor may
  // have moved on by the time somebody decides to ask.
  const ran = useRef({ sql: '', purpose: '', sourceId: '' });

  const run = useMutation({
    mutationFn: (options?: { fresh?: boolean }) => {
      const now = latest.current;
      const rows = Number.parseInt(now.maxRows, 10);
      ran.current = {
        sql: now.sql,
        purpose: now.purpose,
        sourceId: now.sourceId || (usable.length === 1 ? usable[0].id : ''),
      };
      return runQuery({
        sourceId: now.sourceId || (usable.length === 1 ? usable[0].id : ''),
        sql: now.sql,
        asPrincipal: now.asPrincipal || null,
        maxRows: Number.isFinite(rows) ? Math.min(rows, MAX_ROWS) : DEFAULT_ROWS,
        purpose: now.purpose || null,
        ...(options?.fresh ? { fresh: true } : {}),
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

  // Declared once and placed twice: it belongs at the top right, but full
  // screen removes the header it would sit in, so in that state it travels
  // with the toolbar -- which is the only chrome left to leave from.
  const fullscreenToggle = (
    <Button
      color="secondary"
      iconLeading={fullscreen ? Minimize01 : Expand01}
      onClick={() => setFullscreen((on) => !on)}
      size="sm">
      {fullscreen ? 'Exit full screen' : 'Full screen'}
    </Button>
  );

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
        <header className="tw:flex tw:shrink-0 tw:items-center tw:justify-between tw:gap-4">
          <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">
            Query
          </h1>
          <div className="tw:flex tw:items-center tw:gap-2">
            <Link
              className="tw:inline-flex tw:items-center tw:gap-1.5 tw:rounded-lg tw:border tw:border-primary tw:bg-primary tw:px-3 tw:py-1.5 tw:text-sm tw:font-semibold tw:text-secondary tw:shadow-xs tw:transition tw:hover:bg-primary_hover"
              to="/audit">
              <ClipboardCheck className="tw:size-4 tw:text-fg-quaternary" />
              Query log
            </Link>
            {fullscreenToggle}
          </div>
        </header>
      )}

      <div
        className={`tw:flex tw:min-h-0 tw:flex-1 ${fullscreen ? '' : 'tw:mt-4'}`}>
        <div className="tw:flex tw:min-h-0 tw:shrink-0 tw:flex-col tw:gap-2" style={{ width: sidebarWidth }}>
          <SchemaExplorer fill onInsert={insert} sourceId={effectiveSource || null} />
          <SavedQueriesPanel
            onOpen={(query) => {
              // Into the editor and no further: it runs when Run is pressed,
              // as whoever presses it.
              setSql(query.sql);
              if (query.sourceId && usable.some((source) => source.id === query.sourceId)) {
                setSourceId(query.sourceId);
              }
              setOpened(query);
              run.reset();
            }}
            openedId={opened?.id ?? null}
          />
        </div>

        <SideSplitter onChange={setSidebarWidth} width={sidebarWidth} />

        <div className="tw:flex tw:min-h-0 tw:min-w-0 tw:flex-1 tw:flex-col tw:gap-2">
          {/* Where the statement goes and whose access it is judged by: set
              once, then left alone, so it sits apart from the buttons pressed
              on every run -- the way BigQuery keeps the project out of the
              editor's own bar. */}
          <div className="tw:flex tw:items-center tw:gap-2">
            <Select
              ariaLabel="Source"
              className="tw:w-60 tw:shrink-0"
              onChange={setSourceId}
              options={usable.map((source) => ({
                value: source.id,
                label: source.name,
                hint: `${source.engine} · ${source.host}:${source.port}`,
              }))}
              placeholder="Choose a source"
              value={effectiveSource}
            />

            {isAdmin && (
              <Select
                ariaLabel="Run as"
                className="tw:w-56 tw:shrink-0"
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
            )}

            {/* Shown either way. Saying nothing when no role is picked is what
                made people read the blank state as "no policy" and then read
                the refusal that followed as a bug in the platform. One line
                now, beside the choice it describes; the whole of it is on
                hover. */}
            <p
              className="tw:flex tw:min-w-0 tw:flex-1 tw:items-center tw:gap-1.5 tw:text-xs tw:text-tertiary"
              title={
                asPrincipal
                  ? `Running as ${asPrincipal}. This is the enforcement path itself, not a preview of it — the rows below are the rows they would get, and the attempt is audited under your name.`
                  : 'Running as yourself. Being a platform administrator grants no access to data — every query is evaluated against the same policies, and an account no policy names is denied.'
              }>
              <Eye className="tw:size-3.5 tw:shrink-0 tw:text-fg-quaternary" />
              <span className="tw:truncate">
                {asPrincipal ? (
                  <>
                    Running as <strong>{asPrincipal}</strong> — the real enforcement
                    path, audited under your name.
                  </>
                ) : (
                  <>
                    Running as yourself. Being an administrator grants no data —
                    the same policies apply to you.
                  </>
                )}
              </span>
            </p>

            {fullscreen && <div className="tw:ml-auto tw:shrink-0">{fullscreenToggle}</div>}
          </div>

          {/* The editor's own bar: what you press on every statement, in one
              row that does not wrap. The NokRak panel opens over the editor
              rather than above it, so asking for help does not move the text
              you are asking about. */}
          <div className="tw:relative tw:shrink-0">
            <div className="tw:flex tw:items-center tw:gap-2 tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:px-2 tw:py-1.5">
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

              {writeReady && (
                <NokRakButton
                  label="NokRak, write it"
                  onPress={() => setWriting((open) => !open)}
                  open={writing}
                />
              )}

              {explainReady && (
                <ExplainButton
                  disabled={sql.trim().length === 0}
                  onClick={() =>
                    explain.mutate({ sql: latest.current.sql, sourceId: effectiveSource, engine })
                  }
                  pending={explain.isPending}
                />
              )}

              <span aria-hidden className="tw:mx-1 tw:h-5 tw:w-px tw:shrink-0 tw:bg-border-secondary" />

              <SaveQueryButton
                onSaved={setOpened}
                opened={opened}
                sourceId={effectiveSource}
                sql={sql}
              />

              <QuerySettings
                maxRows={maxRows}
                onMaxRows={setMaxRows}
                onPurpose={setPurpose}
                purpose={purpose}
                rowLimitNote={rowLimitNote}
              />

              <span className="tw:ml-auto tw:hidden tw:shrink-0 tw:pr-1 tw:text-xs tw:text-quaternary tw:lg:inline">
                Ctrl/⌘ + Enter to run
              </span>
            </div>

            {writeReady && writing && (
              <div className="tw:absolute tw:left-0 tw:top-full tw:z-30 tw:mt-2 tw:w-[min(40rem,100%)]">
                <WriteWithNokRak
                  engine={engine}
                  onClose={() => setWriting(false)}
                  onUse={(text) => {
                    setSql(text);
                    setWriting(false);
                  }}
                  sourceId={effectiveSource}
                />
              </div>
            )}
          </div>

          <div className="tw:flex tw:min-h-0 tw:flex-1 tw:flex-col" ref={setPaneNode}>
            <div
              className="tw:flex tw:shrink-0 tw:gap-3"
              style={{ height: shownEditorHeight }}>
              <SqlEditor
                completion={effectiveSource ? completion : undefined}
                disabled={run.isPending}
                onChange={setSql}
                onRun={() => run.mutate()}
                value={sql}
              />
              {/* Beside the statement it is about, as BigQuery puts Gemini
                  beside the editor: read side by side, and the editor keeps
                  its place. */}
              {(explain.isPending || explain.data || explain.error) && (
                <div className="tw:flex tw:min-h-0 tw:w-80 tw:shrink-0 tw:flex-col tw:*:h-full tw:*:max-h-full! tw:xl:w-96">
                  <ExplanationCard
                    about={explained}
                    current={sql}
                    error={explain.error}
                    explanation={explain.data}
                    onDismiss={() => explain.reset()}
                    pending={explain.isPending}
                  />
                </div>
              )}
            </div>

            <Splitter height={shownEditorHeight} onChange={setEditorHeight} />

            <ResultPanel
              error={run.error}
              fix={
                fixReady
                  ? {
                      engine,
                      // Into the editor, and the old refusal goes: it was about
                      // the statement that has just been replaced. Not run.
                      onUse: (text) => {
                        setSql(text);
                        run.reset();
                      },
                    }
                  : undefined
              }
              isPending={run.isPending}
              onRetry={() => run.mutate()}
              onRunFresh={() => run.mutate({ fresh: true })}
              ran={ran.current}
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

/**
 * Row limit and purpose, behind one button that says what they are set to.
 *
 * Both are set now and then, not per statement, so they do not earn a place in
 * the bar -- BigQuery keeps the same kind of thing under "Query settings". The
 * button reads "200 rows · No purpose", so nobody has to open it to know.
 */
function QuerySettings({
  maxRows,
  onMaxRows,
  purpose,
  onPurpose,
  rowLimitNote,
}: {
  maxRows: string;
  onMaxRows: (value: string) => void;
  purpose: string;
  onPurpose: (value: string) => void;
  rowLimitNote: string;
}) {
  const rows = maxRows || String(DEFAULT_ROWS);
  return (
    <DialogTrigger>
      <AriaButton className="tw:inline-flex tw:min-w-0 tw:cursor-pointer tw:items-center tw:gap-1.5 tw:rounded-lg tw:px-2.5 tw:py-1.5 tw:text-sm tw:font-semibold tw:text-secondary tw:outline-none tw:hover:bg-primary_hover tw:focus-visible:outline-2 tw:focus-visible:outline-brand">
        <Settings01 className="tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
        <span className="tw:sr-only">Query settings: </span>
        <span className="tw:whitespace-nowrap">
          {rows} rows{rowLimitNote}
        </span>
        <span aria-hidden className="tw:text-quaternary">
          ·
        </span>
        <span className="tw:truncate tw:font-medium tw:text-tertiary">{purpose || 'No purpose'}</span>
      </AriaButton>
      <Popover
        className="tw:w-72 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-lg tw:outline-none"
        offset={6}
        placement="bottom start">
        <Dialog aria-label="Query settings" className="tw:flex tw:flex-col tw:gap-4 tw:p-4 tw:outline-none">
          {/* Typed, not picked from a list: a fixed set of four numbers is
              never the number somebody wants, and the server clamps to
              MAX_ROWS anyway, so there is nothing a free field can break. */}
          <Field hint={`At most ${MAX_ROWS}; the server holds to that.`} label="Row limit">
            <TextField
              ariaLabel="Row limit"
              onChange={(value) => onMaxRows(value.replace(/[^0-9]/g, ''))}
              placeholder={String(DEFAULT_ROWS)}
              value={maxRows}
            />
          </Field>
          <Field hint="A policy that asks for a purpose allows only a query that states it." label="Purpose">
            <Select
              ariaLabel="Purpose"
              onChange={onPurpose}
              options={[
                { value: '', label: 'No purpose' },
                { value: 'fraud-analysis', label: 'fraud-analysis' },
                { value: 'reporting', label: 'reporting' },
                { value: 'support', label: 'support' },
              ]}
              value={purpose}
            />
          </Field>
        </Dialog>
      </Popover>
    </DialogTrigger>
  );
}

const SIDEBAR_WIDTH_KEY = 'arak.query.sidebarWidth';
const MIN_SIDEBAR_WIDTH = 160;
// Past this the tree is wider than the statement it exists to help write.
const MAX_SIDEBAR_WIDTH = 560;
const DEFAULT_SIDEBAR_WIDTH = 256;

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

/** As {@link readEditorHeight}, for the width of the explorer column. */
function readSidebarWidth() {
  try {
    const saved = Number(window.localStorage.getItem(SIDEBAR_WIDTH_KEY));
    if (Number.isFinite(saved) && saved >= MIN_SIDEBAR_WIDTH) {
      return Math.min(saved, MAX_SIDEBAR_WIDTH);
    }
  } catch {
    // No stored preference is not an error; it is the first visit.
  }
  return DEFAULT_SIDEBAR_WIDTH;
}

/**
 * Drag to decide how much of the row the schema tree gets.
 *
 * <p>The twin of {@link Splitter}, and deliberately drawn the same way: one
 * grip that appears where the pointer is, so the two resizable edges of this
 * screen look like one idea rather than two. It also supplies the gap between
 * the panels, which is why removing it would close it.
 */
function SideSplitter({
  width,
  onChange,
}: {
  width: number;
  onChange: (next: number) => void;
}) {
  function clamp(next: number) {
    return Math.round(
      Math.min(Math.max(next, MIN_SIDEBAR_WIDTH), MAX_SIDEBAR_WIDTH)
    );
  }

  function remember(value: number) {
    try {
      window.localStorage.setItem(SIDEBAR_WIDTH_KEY, String(value));
    } catch {
      // The width still applies for this visit; only the memory of it is lost.
    }
  }

  return (
    <div
      aria-label="Resize the explorer"
      aria-orientation="vertical"
      aria-valuenow={Math.round(width)}
      className="tw:group tw:flex tw:w-4 tw:shrink-0 tw:cursor-col-resize tw:items-center tw:justify-center"
      onKeyDown={(event) => {
        const step = event.shiftKey ? 64 : 16;
        const delta =
          event.key === 'ArrowLeft' ? -step : event.key === 'ArrowRight' ? step : 0;
        if (delta === 0) {
          return;
        }
        event.preventDefault();
        const next = clamp(width + delta);
        onChange(next);
        remember(next);
      }}
      onPointerDown={(event) => {
        event.preventDefault();
        const startX = event.clientX;
        const startWidth = width;
        let settled = startWidth;

        const move = (moved: PointerEvent) => {
          settled = clamp(startWidth + moved.clientX - startX);
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
      <span className="tw:h-10 tw:w-0.5 tw:rounded-full tw:bg-border-secondary tw:transition tw:group-hover:bg-brand-solid tw:group-focus:bg-brand-solid" />
    </div>
  );
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

/**
 * Take the rows away as a file.
 *
 * <p>What leaves is the grid, exactly: the rows the principal was entitled to,
 * with the masks the decision applied already in them. There is no unmasked
 * copy to export because the browser never received one -- the rewriting
 * happened at the proxy. Both formats are offered rather than one, because the
 * two are asked for by different people: CSV feeds a script, Excel gets opened
 * and read.
 *
 * <p>A truncated result exports truncated and says so, since a file that
 * silently holds the first two hundred of nine thousand rows is the kind of
 * thing somebody later reconciles a report against.
 */
function Export({ result }: { result: QueryResult }) {
  const grid = { columns: result.columns, rows: result.rows };
  const caveat = result.truncated
    ? ` Only the ${result.rows.length} rows shown, because the result was capped.`
    : '';

  return (
    <div className="tw:flex tw:items-center tw:gap-1">
      <Download01 className="tw:size-3.5 tw:text-quaternary" />
      <button
        className="tw:cursor-pointer tw:rounded tw:px-1.5 tw:py-0.5 tw:text-xs tw:font-semibold tw:text-tertiary tw:hover:bg-secondary tw:hover:text-primary"
        onClick={() =>
          download(toCsv(grid), exportName(result.assets, 'csv'))
        }
        title={`Download these rows as CSV.${caveat}`}
        type="button">
        CSV
      </button>
      <button
        className="tw:cursor-pointer tw:rounded tw:px-1.5 tw:py-0.5 tw:text-xs tw:font-semibold tw:text-tertiary tw:hover:bg-secondary tw:hover:text-primary"
        onClick={() =>
          download(toXlsx(grid), exportName(result.assets, 'xlsx'))
        }
        title={`Download these rows as an Excel workbook.${caveat}`}
        type="button">
        Excel
      </button>
    </div>
  );
}

function ResultPanel({
  result,
  error,
  isPending,
  tab,
  setTab,
  ran,
  fix,
  onRunFresh,
  onRetry,
}: {
  result: QueryResult | undefined;
  error: unknown;
  isPending: boolean;
  /** Runs the same statement again, straight at the source. */
  onRunFresh?: () => void;
  /** Sends the same statement again, after the service said it was busy. */
  onRetry?: () => void;
  /** Present when the reader has an assistant to ask for a corrected statement. */
  fix?: { engine?: string; onUse: (sql: string) => void };
  /** The statement the error is about, for a request made from it. */
  ran?: { sql: string; purpose: string; sourceId: string };
  tab: 'results' | 'sql' | 'details';
  setTab: (tab: 'results' | 'sql' | 'details') => void;
}) {
  const busy = error ? busyOf(error) : null;
  if (busy) {
    return <BusyNote busy={busy} onRetry={onRetry} />;
  }

  if (error) {
    const refusal = refusalOf(error);
    // Fills the pane and scrolls inside it. The console is sized to the
    // viewport and clips, so a refusal carrying the request form grew past
    // the bottom of the screen with no way to reach Submit.
    return (
      <section
        aria-label="Refused"
        className="tw:min-h-0 tw:flex-1 tw:overflow-y-auto tw:overscroll-contain tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4">
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
        {refusal && (
          <RequestAccess
            // A new refusal is a new question; a half-filled form from the last
            // one would be about a different table.
            key={`${refusal.assetFqn ?? ''}:${refusal.message}`}
            purpose={ran?.purpose || null}
            refusal={refusal}
            sourceId={ran?.sourceId || null}
            sql={ran?.sql ?? ''}
          />
        )}
        {refusal && fix && (
          <FixWithAi
            engine={fix.engine}
            key={`fix:${refusal.message}`}
            onUse={fix.onUse}
            refusal={refusal}
            sourceId={ran?.sourceId ?? ''}
            sql={ran?.sql ?? ''}
          />
        )}
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
      <section className="tw:flex tw:min-h-0 tw:flex-1 tw:items-center tw:justify-center tw:rounded-lg tw:border tw:border-dashed tw:border-secondary tw:p-6 tw:text-center tw:text-sm tw:text-tertiary">
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
            className={`tw:cursor-pointer tw:border-b-2 tw:px-3 tw:py-2 tw:text-sm ${
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
          {result.cached && (
            <CachedNote onRunFresh={onRunFresh} readAt={result.readAt} />
          )}
          <span className="tw:text-xs tw:text-quaternary">
            {result.millis} ms · as {result.principal}
          </span>
          <Export result={result} />
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
      <Detail label="Read">
        {result.cached
          ? `from the result cache — the source was read at ${
              result.readAt ? new Date(result.readAt).toLocaleString() : 'an unknown time'
            }`
          : 'from the source, for this run'}
      </Detail>
      <Detail label="Duration">{result.millis} ms</Detail>
      <Detail label="Planner estimate">
        {formatCost(result.estimatedCost) ? (
          `${formatCost(result.estimatedCost)}, in the source's own planner units`
        ) : (
          <span className="tw:text-tertiary">not priced</span>
        )}
      </Detail>
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
