import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import { useHref } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Button as AriaButton } from 'react-aria-components';
import { Tooltip } from '@openmetadata/ui-core-components/components/base/tooltip/tooltip';
import {
  ChevronDown,
  ChevronRight,
  Copy01,
  CornerDownLeft,
  Database01,
  Folder,
  LinkExternal01,
  SearchLg,
  Table,
} from '@untitledui/icons';
import {
  fetchAsset,
  fetchAssets,
  type AssetSummary,
  type ColumnDetail,
  type FacetRow,
} from '../../api/client';
import { isAncestor } from '../../lib/fqn';
import { plainText } from '../../lib/text';
import { TextField } from '../policies/controls';

/**
 * The tree on the left: what this source holds, as the catalog understands it.
 *
 * <p>Built from the asset cache rather than from a live `information_schema`
 * read, which means it shows what policy can be written against. A table the
 * crawl has not seen yet is a table the engine has no decision for, so leaving
 * it out of this tree is honest: clicking it would only produce a refusal.
 *
 * <p>Narrowed to the source the query will run on, by the same physical
 * mapping the proxy resolves against. The catalog holds tables from databases
 * ARAK has only read the metadata of and has no connection to; those cannot be
 * queried from here whatever is typed, so offering them is a trap.
 *
 * <p>Hovering a column says what it means; right-clicking a table or a column
 * offers its page in the Data Catalog, in a new tab, because this page does
 * not keep what is in the editor and leaving it would lose the statement.
 */

interface Node {
  name: string;
  path: string;
  children: Map<string, Node>;
  asset?: AssetSummary;
}

function buildTree(assets: AssetSummary[]): Node {
  const root: Node = { name: '', path: '', children: new Map() };
  for (const asset of assets) {
    // service.database.schema.table — the last segment is the asset itself and
    // everything before it is a container that may not exist as its own row.
    const parts = asset.fqn.split('.');
    let node = root;
    parts.forEach((part, index) => {
      let child = node.children.get(part);
      if (!child) {
        child = {
          name: part,
          path: parts.slice(0, index + 1).join('.'),
          children: new Map(),
        };
        node.children.set(part, child);
      }
      if (index === parts.length - 1) {
        child.asset = asset;
      }
      node = child;
    });
  }
  return root;
}

/** What a right-click landed on, and where on the screen. */
export interface MenuTarget {
  x: number;
  y: number;
  /** The table's FQN; for a column, the table it belongs to. */
  tableFqn: string;
  /** Set when the click was on a column rather than the table. */
  column?: { name: string; fqn: string };
}

type OpenMenu = (target: MenuTarget) => void;

function menuAt(
  event: ReactMouseEvent<HTMLElement>,
  target: Omit<MenuTarget, 'x' | 'y'>
): MenuTarget {
  event.preventDefault();
  // The context-menu key and Shift+F10 arrive with no pointer position, so
  // the menu opens under the row instead of in the corner of the window.
  if (event.clientX === 0 && event.clientY === 0) {
    const box = event.currentTarget.getBoundingClientRect();
    return { ...target, x: box.left + 16, y: box.bottom };
  }
  return { ...target, x: event.clientX, y: event.clientY };
}

/** The labels on a column that could change what a query shows of it. */
function governingLabels(facets: FacetRow[]): string[] {
  const values = [
    ...new Set(
      facets
        .filter((facet) => facet.facetType === 'tags' || facet.facetType === 'terms')
        .map((facet) => facet.facetFqn)
    ),
  ];
  // Facets arrive with every ancestor spelled out (PII and PII.Sensitive);
  // the most specific one says everything the rest do.
  return values.filter((value) => !values.some((other) => isAncestor(value, other))).sort();
}

const HINT_LENGTH = 400;

/**
 * What hovering a column says: what it means, then what governs it.
 *
 * <p>No description is said out loud rather than left as an empty card, so
 * the reader knows it is missing, not slow to load, and where to add one.
 */
export function columnHint(column: ColumnDetail): { description: string; labels: string[] } {
  const text = plainText(column.description);
  return {
    description: !text
      ? 'No description yet. Add one on the table’s Columns tab in the Data Catalog.'
      : text.length > HINT_LENGTH
        ? `${text.slice(0, HINT_LENGTH - 1)}…`
        : text,
    labels: governingLabels(column.facets),
  };
}

export interface SchemaExplorerProps {
  /** The registered source the query runs against, by id. */
  sourceId?: string | null;
  /** Inserted at the caret when a table is clicked. */
  onInsert: (text: string) => void;
  /** How wide the column is, in pixels. The reader drags this. */
  width?: number;
  /** Fill the height of a column it shares, instead of setting its own width. */
  fill?: boolean;
}

export default function SchemaExplorer({
  sourceId,
  onInsert,
  width,
  fill = false,
}: SchemaExplorerProps) {
  const [search, setSearch] = useState('');
  const [menu, setMenu] = useState<MenuTarget | null>(null);
  // Stable, so the menu's listeners are not taken down and put back every
  // time the tree re-renders underneath it.
  const closeMenu = useCallback(() => setMenu(null), []);

  const { data, isLoading } = useQuery({
    queryKey: ['query-explorer', search, sourceId ?? ''],
    // Filtered in the query rather than after it: the page is capped at 500
    // rows, and filtering the page would show an arbitrary slice of one source
    // whenever the catalog holds more than that.
    queryFn: () =>
      fetchAssets({ search, sourceId: sourceId ?? undefined, limit: 500 }),
    enabled: Boolean(sourceId),
    staleTime: 60_000,
  });

  const tree = useMemo(() => buildTree(data?.items ?? []), [data]);

  const roots = [...tree.children.values()];

  return (
    <aside
      className={`tw:flex tw:flex-col tw:overflow-hidden tw:rounded-lg tw:border tw:border-secondary tw:bg-primary ${
        fill ? 'tw:min-h-0 tw:flex-1' : 'tw:shrink-0'
      }`}
      style={fill ? undefined : { width: width ?? 256 }}>
      <div className="tw:border-b tw:border-secondary tw:p-3">
        <h2 className="tw:flex tw:items-center tw:gap-2 tw:text-sm tw:font-semibold tw:text-primary">
          <Database01 className="tw:size-4 tw:text-tertiary" />
          Explorer
        </h2>
        <div className="tw:mt-2 tw:flex tw:items-center tw:gap-2">
          <SearchLg className="tw:size-4 tw:shrink-0 tw:text-quaternary" />
          <TextField
            ariaLabel="Search assets"
            className="tw:min-w-0 tw:flex-1"
            onChange={setSearch}
            placeholder="Find a table"
            value={search}
          />
        </div>
      </div>

      <div className="tw:min-h-0 tw:flex-1 tw:overflow-auto tw:p-2">
        {isLoading && (
          <p className="tw:px-2 tw:py-1 tw:text-xs tw:text-tertiary">Loading…</p>
        )}
        {!sourceId && (
          <p className="tw:px-2 tw:py-1 tw:text-xs tw:text-tertiary">
            Pick a source above to see what it holds.
          </p>
        )}
        {sourceId && !isLoading && roots.length === 0 && (
          <p className="tw:px-2 tw:py-1 tw:text-xs tw:text-tertiary">
            {search.trim()
              ? 'Nothing on this source matches.'
              : 'ARAK has not read this source\u2019s catalog yet. Introspect it from ' +
                'Settings \u203a Sources and its tables appear here.'}
          </p>
        )}
        {roots.map((node) => (
          <TreeNode
            depth={0}
            key={node.path}
            node={node}
            onInsert={onInsert}
            onMenu={setMenu}
            // Expanded by default while searching: a hit three levels down is
            // invisible otherwise, which reads as "no results".
            startOpen={search.trim().length > 0}
          />
        ))}
      </div>
      {menu && (
        <ExplorerMenu onClose={closeMenu} onInsert={onInsert} target={menu} />
      )}
    </aside>
  );
}

function TreeNode({
  node,
  depth,
  onInsert,
  onMenu,
  startOpen,
}: {
  node: Node;
  depth: number;
  onInsert: (text: string) => void;
  onMenu: OpenMenu;
  startOpen: boolean;
}) {
  const [open, setOpen] = useState(startOpen || depth < 2);
  const isAsset = Boolean(node.asset);
  const children = [...node.children.values()];
  const tableFqn = node.asset?.fqn;

  return (
    <div>
      <div
        className="tw:flex tw:items-center tw:gap-1 tw:rounded-md tw:px-1 tw:py-1 tw:hover:bg-secondary"
        // Only a table has a menu. A database or schema keeps the browser's
        // own, which is still the more useful one there.
        onContextMenu={
          tableFqn ? (event) => onMenu(menuAt(event, { tableFqn })) : undefined
        }
        style={{ paddingLeft: `${depth * 12 + 4}px` }}>
        {children.length > 0 || isAsset ? (
          <button
            aria-expanded={open}
            aria-label={open ? `Collapse ${node.name}` : `Expand ${node.name}`}
            className="tw:cursor-pointer tw:shrink-0 tw:text-quaternary"
            onClick={() => setOpen((it) => !it)}
            type="button">
            {open ? (
              <ChevronDown className="tw:size-3.5" />
            ) : (
              <ChevronRight className="tw:size-3.5" />
            )}
          </button>
        ) : (
          <span className="tw:size-3.5 tw:shrink-0" />
        )}

        {isAsset ? (
          <Table className="tw:size-3.5 tw:shrink-0 tw:text-fg-brand-primary" />
        ) : (
          <Folder className="tw:size-3.5 tw:shrink-0 tw:text-quaternary" />
        )}

        <button
          className="tw:cursor-pointer tw:min-w-0 tw:flex-1 tw:truncate tw:text-left tw:text-xs tw:text-primary"
          onClick={() => {
            if (node.asset) {
              // Schema-qualified: the proxy refuses a bare table name rather
              // than guessing which of several schemas was meant.
              const parts = node.path.split('.');
              onInsert(parts.slice(-2).join('.'));
            } else {
              setOpen((it) => !it);
            }
          }}
          title={
            isAsset
              ? `${node.path}\nClick to insert it; right-click to open it in the Data Catalog.`
              : node.path
          }
          type="button">
          {node.name}
        </button>
      </div>

      {open && isAsset && node.asset && (
        <Columns
          depth={depth + 1}
          fqn={node.asset.fqn}
          onInsert={onInsert}
          onMenu={onMenu}
        />
      )}
      {open &&
        children.map((child) => (
          <TreeNode
            depth={depth + 1}
            key={child.path}
            node={child}
            onInsert={onInsert}
            onMenu={onMenu}
            startOpen={startOpen}
          />
        ))}
    </div>
  );
}

/** Lazy: a table's columns are fetched only once somebody opens it. */
function Columns({
  fqn,
  depth,
  onInsert,
  onMenu,
}: {
  fqn: string;
  depth: number;
  onInsert: (text: string) => void;
  onMenu: OpenMenu;
}) {
  const { data, isLoading } = useQuery({
    queryKey: ['query-explorer-columns', fqn],
    queryFn: () => fetchAsset(fqn),
    staleTime: 5 * 60 * 1000,
  });

  if (isLoading) {
    return (
      <p
        className="tw:py-0.5 tw:text-[11px] tw:text-quaternary"
        style={{ paddingLeft: `${depth * 12 + 24}px` }}>
        Loading columns…
      </p>
    );
  }

  return (
    <>
      {(data?.columns ?? []).map((column) => (
        // The wrapper takes the right-click: the tooltip needs its trigger to
        // be a react-aria Button, and that passes on only the props it knows.
        <div
          key={column.fqn}
          onContextMenu={(event) =>
            onMenu(
              menuAt(event, {
                tableFqn: fqn,
                column: { name: column.name, fqn: column.fqn },
              })
            )
          }>
          <ColumnHintTooltip column={column}>
            <AriaButton
              className="tw:cursor-pointer tw:flex tw:w-full tw:items-center tw:gap-2 tw:rounded-md tw:py-0.5 tw:pr-2 tw:text-left tw:outline-focus-ring tw:hover:bg-secondary tw:focus-visible:outline-2"
              onPress={() => onInsert(column.name)}
              style={{ paddingLeft: `${depth * 12 + 24}px` }}>
              <span className="tw:min-w-0 tw:truncate tw:text-[11px] tw:text-secondary">
                {column.name}
              </span>
              <span className="tw:shrink-0 tw:text-[10px] tw:text-quaternary">
                {column.dataType ?? ''}
              </span>
              {/* A governed column is the reason a mask will appear in the result,
                  so the tag that causes it belongs next to the name. */}
              {column.facets.some((facet) => facet.facetType === 'tags') && (
                <span className="tw:shrink-0 tw:rounded tw:bg-utility-warning-50 tw:px-1 tw:text-[10px] tw:text-warning-primary">
                  tagged
                </span>
              )}
            </AriaButton>
          </ColumnHintTooltip>
        </div>
      ))}
    </>
  );
}

/**
 * The card a column shows on hover: its full name and type, what it means, and
 * the labels that govern it.
 *
 * <p>The tree has no room for any of that on the row, and a browser `title`
 * waits a second and a half, cannot wrap a long description and says nothing
 * at all when there is no description, which reads as broken.
 */
function ColumnHintTooltip({ column, children }: { column: ColumnDetail; children: ReactNode }) {
  const hint = columnHint(column);
  return (
    <Tooltip
      delay={400}
      description={
        <>
          <span className="tw:block">{hint.description}</span>
          {hint.labels.length > 0 && (
            <span className="tw:mt-1 tw:block tw:text-white">
              {hint.labels.join(', ')}
            </span>
          )}
          <span className="tw:mt-1 tw:block tw:opacity-70">
            Click to insert · right-click for the Data Catalog
          </span>
        </>
      }
      placement="right"
      title={column.dataType ? `${column.name} · ${column.dataType}` : column.name}>
      {children}
    </Tooltip>
  );
}

/**
 * The right-click menu on a table or a column.
 *
 * <p>Drawn at the pointer, above everything, and gone on Escape, on a click
 * anywhere else, or on a scroll that would leave it pointing at nothing.
 * Arrow keys move between the items, the way a menu is expected to work.
 */
function ExplorerMenu({
  target,
  onInsert,
  onClose,
}: {
  target: MenuTarget;
  onInsert: (text: string) => void;
  onClose: () => void;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState({ left: target.x, top: target.y });
  const column = target.column;
  const catalogHref = useHref(
    `/catalog/${encodeURIComponent(target.tableFqn)}${column ? '?tab=columns' : ''}`
  );

  // Kept inside the window: a right-click near the bottom or the right edge
  // would otherwise open a menu half of which cannot be reached.
  useLayoutEffect(() => {
    const box = panel.current?.getBoundingClientRect();
    if (!box) return;
    setPosition({
      left: Math.max(4, Math.min(target.x, window.innerWidth - box.width - 4)),
      top: Math.max(4, Math.min(target.y, window.innerHeight - box.height - 4)),
    });
  }, [target.x, target.y]);

  useEffect(() => {
    panel.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    const away = (event: Event) => {
      if (!panel.current?.contains(event.target as globalThis.Node)) onClose();
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('mousedown', away);
    document.addEventListener('keydown', escape);
    window.addEventListener('scroll', onClose, true);
    window.addEventListener('resize', onClose);
    window.addEventListener('blur', onClose);
    return () => {
      document.removeEventListener('mousedown', away);
      document.removeEventListener('keydown', escape);
      window.removeEventListener('scroll', onClose, true);
      window.removeEventListener('resize', onClose);
      window.removeEventListener('blur', onClose);
    };
  }, [onClose]);

  const move = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    event.preventDefault();
    const items = [
      ...(panel.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []),
    ];
    const at = items.indexOf(document.activeElement as HTMLElement);
    const step = event.key === 'ArrowDown' ? 1 : -1;
    items[(at + step + items.length) % items.length]?.focus();
  };

  const name = column ? column.name : target.tableFqn.split('.').slice(-2).join('.');
  const fullName = column ? column.fqn : target.tableFqn;
  const item =
    'tw:flex tw:w-full tw:cursor-pointer tw:items-center tw:gap-2 tw:rounded-md tw:px-2 tw:py-1.5 tw:text-left tw:text-xs tw:text-secondary tw:outline-none tw:hover:bg-secondary tw:focus:bg-secondary';
  const icon = 'tw:size-3.5 tw:shrink-0 tw:text-fg-quaternary';

  return createPortal(
    <div
      aria-label={column ? `Column ${column.name}` : `Table ${name}`}
      className="tw:fixed tw:z-100 tw:min-w-52 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:p-1 tw:shadow-lg"
      onContextMenu={(event) => event.preventDefault()}
      onKeyDown={move}
      ref={panel}
      role="menu"
      style={position}>
      <p className="tw:truncate tw:px-2 tw:pt-1 tw:pb-1.5 tw:text-[11px] tw:font-semibold tw:text-tertiary">
        {fullName}
      </p>
      <a
        className={item}
        href={catalogHref}
        onClick={onClose}
        rel="noopener noreferrer"
        role="menuitem"
        target="_blank">
        <LinkExternal01 className={icon} />
        {column ? 'Open its table in the Data Catalog' : 'Open in the Data Catalog'}
      </a>
      <button
        className={item}
        onClick={() => {
          // Schema-qualified for a table, as a click on it inserts it.
          onInsert(name);
          onClose();
        }}
        role="menuitem"
        type="button">
        <CornerDownLeft className={icon} />
        Insert into the editor
      </button>
      <button
        className={item}
        onClick={() => {
          void navigator.clipboard?.writeText(fullName);
          onClose();
        }}
        role="menuitem"
        type="button">
        <Copy01 className={icon} />
        Copy the full name
      </button>
    </div>,
    document.body
  );
}
