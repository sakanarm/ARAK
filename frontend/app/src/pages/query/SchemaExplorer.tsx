import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  ChevronDown,
  ChevronRight,
  Database01,
  Folder,
  SearchLg,
  Table,
} from '@untitledui/icons';
import { fetchAsset, fetchAssets, type AssetSummary } from '../../api/client';
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
            // Expanded by default while searching: a hit three levels down is
            // invisible otherwise, which reads as "no results".
            startOpen={search.trim().length > 0}
          />
        ))}
      </div>
    </aside>
  );
}

function TreeNode({
  node,
  depth,
  onInsert,
  startOpen,
}: {
  node: Node;
  depth: number;
  onInsert: (text: string) => void;
  startOpen: boolean;
}) {
  const [open, setOpen] = useState(startOpen || depth < 2);
  const isAsset = Boolean(node.asset);
  const children = [...node.children.values()];

  return (
    <div>
      <div
        className="tw:flex tw:items-center tw:gap-1 tw:rounded-md tw:px-1 tw:py-1 tw:hover:bg-secondary"
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
          title={node.path}
          type="button">
          {node.name}
        </button>
      </div>

      {open && isAsset && node.asset && (
        <Columns
          depth={depth + 1}
          fqn={node.asset.fqn}
          onInsert={onInsert}
        />
      )}
      {open &&
        children.map((child) => (
          <TreeNode
            depth={depth + 1}
            key={child.path}
            node={child}
            onInsert={onInsert}
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
}: {
  fqn: string;
  depth: number;
  onInsert: (text: string) => void;
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
        <button
          className="tw:cursor-pointer tw:flex tw:w-full tw:items-center tw:gap-2 tw:rounded-md tw:py-0.5 tw:pr-2 tw:text-left tw:hover:bg-secondary"
          key={column.fqn}
          onClick={() => onInsert(column.name)}
          style={{ paddingLeft: `${depth * 12 + 24}px` }}
          // What the column means comes from OpenMetadata. The tree has no
          // room for it on the row, so it shows on hover instead.
          title={plainText(column.description) || undefined}
          type="button">
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
        </button>
      ))}
    </>
  );
}
