import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ChevronRight, SearchLg } from '@untitledui/icons';
import { lookFor } from './assetLook';
import { Chip as Badge } from '../../components/chips';
import { FacetChip, listFacets } from './facets';
import { Panel } from './panels';
import {
  apiErrorMessage,
  fetchAssets,
  type AssetSummary,
} from '../../api/client';
import { plainText } from '../../lib/text';

/**
 * Where assets live: service → database → schema (dataset) → table.
 *
 * <p>The list answers "what matches", and a flat list of 300 tables named
 * `customer` is the wrong tool for "what is in this database". These are the
 * two places that question is asked: a tree on the catalog, and the contents
 * of one container on its own page -- the way OpenMetadata shows a database's
 * schemas and a schema's tables.
 *
 * <p>Both read one level at a time (`?parent=`), so opening a database does
 * not pull every table in it.
 */

/** A branch holds at most this many children before it asks for a search. */
const BRANCH_LIMIT = 500;

/** What a kind of asset holds, in the words its page uses. */
const CHILD_NOUN: Record<string, [string, string]> = {
  SERVICE: ['database', 'databases'],
  DATABASE: ['schema', 'schemas'],
  SCHEMA: ['table', 'tables'],
};

/** Whether this kind of asset holds others, rather than columns. */
export function isContainer(assetType: string | null | undefined): boolean {
  return Boolean(assetType && assetType in CHILD_NOUN);
}

/** "3 schemas", "1 table" -- what sits under a container. */
export function childLabel(assetType: string, count: number): string {
  const [one, many] = CHILD_NOUN[assetType] ?? ['item', 'items'];
  return `${count} ${count === 1 ? one : many}`;
}

/** The name of the tab that lists a container's contents: "Schemas". */
export function childrenTitle(assetType: string): string {
  const many = CHILD_NOUN[assetType]?.[1] ?? 'contents';
  return many.charAt(0).toUpperCase() + many.slice(1);
}

/** What sits under one asset, as a sentence fragment for the right-hand side. */
function contents(asset: AssetSummary): string | null {
  if (isContainer(asset.assetType)) {
    return childLabel(asset.assetType, asset.childCount ?? 0);
  }
  if (asset.columnCount > 0) {
    return `${asset.columnCount} ${asset.columnCount === 1 ? 'column' : 'columns'}`;
  }
  return null;
}

function assetLink(fqn: string): string {
  return `/catalog/${encodeURIComponent(fqn)}`;
}

function useChildren(parent: string, enabled = true) {
  return useQuery({
    queryKey: ['catalog-children', parent],
    queryFn: () => fetchAssets({ parent, limit: BRANCH_LIMIT }),
    enabled,
    retry: false,
  });
}

// ------------------------------------------------------------------- tree

/**
 * The whole catalog as a tree, services at the top. Each branch is fetched when
 * it is first opened and kept while the page is open.
 */
export function AssetTree() {
  const roots = useQuery({
    queryKey: ['catalog-children', ''],
    queryFn: () => fetchAssets({ assetType: 'SERVICE', limit: BRANCH_LIMIT }),
    retry: false,
  });

  if (roots.isLoading) {
    return <p className="tw:mt-4 tw:text-sm tw:text-tertiary">Loading…</p>;
  }
  if (roots.error) {
    return (
      <p className="tw:mt-4 tw:text-sm tw:text-error-primary">
        {apiErrorMessage(roots.error, 'The catalog could not be read.')}
      </p>
    );
  }
  const items = roots.data?.items ?? [];
  if (items.length === 0) {
    return (
      <p className="tw:mt-4 tw:text-sm tw:text-tertiary">
        Nothing is cached yet. Run a sync from Settings → System.
      </p>
    );
  }
  return (
    <div className="tw:mt-4 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-2">
      <ul aria-label="Catalog hierarchy" className="tw:space-y-0.5" role="tree">
        {items.map((asset) => (
          <Branch asset={asset} depth={1} key={asset.id} lone={items.length === 1} />
        ))}
      </ul>
    </div>
  );
}

function Branch({
  asset,
  depth,
  lone,
}: {
  asset: AssetSummary;
  depth: number;
  /** The only child of its parent -- opened for the reader. */
  lone: boolean;
}) {
  const holds = isContainer(asset.assetType) && (asset.childCount ?? 0) > 0;
  // A service with one database is opened for the reader: one click that
  // could only ever go one way is a click nobody needs to make. Tables are
  // the leaves, so this never cascades past a schema.
  const [open, setOpen] = useState(lone);
  const children = useChildren(asset.fqn, holds && open);
  const look = lookFor(asset.assetType);
  const name = asset.displayName || asset.name;
  const what = contents(asset);

  return (
    <li
      aria-expanded={holds ? open : undefined}
      aria-label={name}
      aria-level={depth}
      role="treeitem">
      <div
        className="tw:group tw:flex tw:items-center tw:gap-1.5 tw:rounded-lg tw:py-1 tw:pr-2 tw:hover:bg-secondary_subtle"
        style={{ paddingLeft: `${(depth - 1) * 1.25 + 0.25}rem` }}>
        {holds ? (
          <button
            aria-label={`${open ? 'Collapse' : 'Expand'} ${name}`}
            className="tw:flex tw:size-6 tw:shrink-0 tw:cursor-pointer tw:items-center tw:justify-center tw:rounded tw:text-fg-quaternary tw:hover:bg-secondary tw:hover:text-fg-secondary"
            onClick={() => setOpen((was) => !was)}
            type="button">
            <ChevronRight
              aria-hidden
              className={`tw:size-4 tw:transition-transform ${open ? 'tw:rotate-90' : ''}`}
            />
          </button>
        ) : (
          <span aria-hidden className="tw:size-6 tw:shrink-0" />
        )}
        <span
          className={`tw:flex tw:size-6 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-md ${look.tile}`}>
          <look.Icon aria-hidden className="tw:size-3.5" />
        </span>
        <Link
          className="tw:min-w-0 tw:truncate tw:text-sm tw:font-medium tw:text-primary tw:hover:text-brand-secondary tw:hover:underline"
          title={asset.fqn}
          to={assetLink(asset.fqn)}>
          {name}
        </Link>
        <Badge color={look.badge} size="sm" type="color">
          {asset.assetType}
        </Badge>
        {what && (
          <span className="tw:ml-auto tw:shrink-0 tw:text-xs tw:tabular-nums tw:text-tertiary">
            {what}
          </span>
        )}
      </div>

      {holds && open && (
        <ul className="tw:space-y-0.5" role="group">
          {children.isLoading && (
            <li
              className="tw:py-1 tw:text-xs tw:text-tertiary"
              role="none"
              style={{ paddingLeft: `${depth * 1.25 + 2}rem` }}>
              Loading…
            </li>
          )}
          {children.error != null && (
            <li
              className="tw:py-1 tw:text-xs tw:text-error-primary"
              role="none"
              style={{ paddingLeft: `${depth * 1.25 + 2}rem` }}>
              {apiErrorMessage(children.error, 'This branch could not be read.')}
            </li>
          )}
          {children.data?.items.map((child) => (
            <Branch
              asset={child}
              depth={depth + 1}
              key={child.id}
              lone={children.data?.items.length === 1}
            />
          ))}
          {children.data && children.data.total > children.data.items.length && (
            <li
              className="tw:py-1 tw:text-xs tw:text-tertiary"
              role="none"
              style={{ paddingLeft: `${depth * 1.25 + 2}rem` }}>
              Showing {children.data.items.length} of {children.data.total} —{' '}
              <Link className="tw:underline" to={assetLink(asset.fqn)}>
                open {name} to search them
              </Link>
            </li>
          )}
        </ul>
      )}
    </li>
  );
}

// --------------------------------------------------------------- contents

/**
 * A container's contents on its own page: a database's schemas, a schema's
 * tables. With a filter, because a schema of four hundred tables is where
 * somebody goes looking for one of them.
 */
export function ChildrenPanel({ asset }: { asset: AssetSummary }) {
  const { data, isLoading, error } = useChildren(asset.fqn);
  const [filter, setFilter] = useState('');
  const title = childrenTitle(asset.assetType);
  const items = data?.items ?? [];
  const wanted = filter.trim().toLowerCase();
  const shown = useMemo(
    () =>
      wanted
        ? items.filter((child) =>
            [child.name, child.displayName, child.description]
              .filter((text): text is string => Boolean(text))
              .some((text) => text.toLowerCase().includes(wanted))
          )
        : items,
    [items, wanted]
  );
  const total = data?.total ?? asset.childCount ?? 0;

  return (
    <Panel
      action={
        items.length > 0 ? (
          <label className="tw:flex tw:items-center tw:gap-2 tw:rounded-lg tw:border tw:border-primary tw:px-2.5 tw:py-1.5">
            <SearchLg aria-hidden className="tw:size-4 tw:text-fg-quaternary" />
            <input
              aria-label={`Filter ${title.toLowerCase()}`}
              className="tw:w-44 tw:bg-transparent tw:text-sm tw:text-primary tw:outline-none tw:placeholder:text-placeholder"
              onChange={(event) => setFilter(event.target.value)}
              placeholder={`Filter ${title.toLowerCase()}`}
              type="search"
              value={filter}
            />
          </label>
        ) : undefined
      }
      subtitle={`${childLabel(asset.assetType, total)} in this ${asset.assetType.toLowerCase()}`}
      title={title}>
      {isLoading && <p className="tw:text-sm tw:text-tertiary">Loading…</p>}
      {error != null && (
        <p className="tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, `The ${title.toLowerCase()} could not be read.`)}
        </p>
      )}
      {data && items.length === 0 && (
        <p className="tw:text-sm tw:text-tertiary">
          The crawl found nothing under this {asset.assetType.toLowerCase()}.
        </p>
      )}
      {items.length > 0 && shown.length === 0 && (
        <p className="tw:text-sm tw:text-tertiary">Nothing here matches “{filter.trim()}”.</p>
      )}
      {shown.length > 0 && (
        <div className="tw:overflow-x-auto">
          <table className="tw:w-full tw:text-sm">
            <thead>
              <tr className="tw:border-b tw:border-secondary tw:text-left tw:text-xs tw:text-tertiary">
                <th className="tw:py-2 tw:pr-3 tw:font-medium">Name</th>
                <th className="tw:py-2 tw:pr-3 tw:font-medium">Contains</th>
                <th className="tw:py-2 tw:font-medium">Governance</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((child) => (
                <ChildRow asset={child} key={child.id} />
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data && data.total > items.length && (
        <p className="tw:mt-3 tw:text-xs tw:text-tertiary">
          Showing the first {items.length} of {data.total}. Search the catalog to find the
          rest.
        </p>
      )}
    </Panel>
  );
}

function ChildRow({ asset }: { asset: AssetSummary }) {
  const look = lookFor(asset.assetType);
  const facets = listFacets(asset.facets);
  const description = plainText(asset.description);
  return (
    <tr className="tw:border-b tw:border-secondary tw:last:border-0">
      <td className="tw:py-2 tw:pr-3 tw:align-top">
        <div className="tw:flex tw:items-center tw:gap-2">
          <span
            className={`tw:flex tw:size-6 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-md ${look.tile}`}>
            <look.Icon aria-hidden className="tw:size-3.5" />
          </span>
          <Link
            className="tw:font-medium tw:text-primary tw:hover:text-brand-secondary tw:hover:underline"
            to={assetLink(asset.fqn)}>
            {asset.displayName || asset.name}
          </Link>
          <Badge color={look.badge} size="sm" type="color">
            {asset.assetType}
          </Badge>
        </div>
        {description && (
          <p className="tw:mt-0.5 tw:max-w-xl tw:truncate tw:pl-8 tw:text-xs tw:text-tertiary">
            {description}
          </p>
        )}
      </td>
      <td className="tw:py-2 tw:pr-3 tw:align-top tw:text-xs tw:whitespace-nowrap tw:text-tertiary">
        {contents(asset) ?? '—'}
      </td>
      <td className="tw:py-2 tw:align-top">
        {facets.length === 0 ? (
          <span className="tw:text-xs tw:text-quaternary">—</span>
        ) : (
          <div className="tw:flex tw:flex-wrap tw:gap-1.5">
            {facets.map((facet) => (
              <FacetChip
                facet={facet}
                key={`${facet.facetType}:${facet.facetFqn}:${facet.property ?? ''}`}
              />
            ))}
          </div>
        )}
      </td>
    </tr>
  );
}
