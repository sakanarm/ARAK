import { useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { Database01, SearchLg, XClose } from '@untitledui/icons';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Input } from '@openmetadata/ui-core-components/components/base/input/input';
import {
  apiErrorMessage,
  fetchAssets,
  fetchCatalogSummary,
  fetchFacetValues,
  type AssetSummary,
} from '../../api/client';
import {
  FILTERABLE_FACETS,
  FacetChip,
  OwnerChip,
  facetLabel,
  listFacets,
} from './facets';

const PAGE_SIZE = 25;
const ASSET_TYPES = ['TABLE', 'VIEW', 'SCHEMA', 'DATABASE', 'SERVICE'];

/**
 * The catalog: what the crawl has cached, and how it is governed (FR-1.2).
 *
 * <p>The filters are the same vocabulary a policy selector uses — tags, terms,
 * domains, data products, tier — on purpose. Someone about to write "everything
 * tagged PII.Sensitive in Finance" can select exactly that here first and see
 * the list it will reach, which is the cheapest form of the impact analysis that
 * FR-5.3 makes formal later.
 *
 * <p>Filter state lives in the URL. A page of governance findings is something
 * people send to each other, and a link that reopens somebody else's filters is
 * worth more than the scroll position React would otherwise keep.
 */
export default function CatalogPage() {
  const [params, setParams] = useSearchParams();
  const [searchDraft, setSearchDraft] = useState(params.get('q') ?? '');

  const search = params.get('q') ?? '';
  const assetType = params.get('type') ?? '';
  const facets = useMemo(() => params.getAll('facet'), [params]);
  const offset = Number(params.get('offset') ?? 0);

  const { data, isLoading, error, isFetching } = useQuery({
    queryKey: ['catalog-assets', search, assetType, facets, offset],
    queryFn: () =>
      fetchAssets({ search, assetType, facets, limit: PAGE_SIZE, offset }),
    // Without this the table empties on every keystroke-driven refetch and the
    // page jumps; the stale rows are correct until the new ones arrive.
    placeholderData: keepPreviousData,
  });
  const { data: summary } = useQuery({
    queryKey: ['catalog-summary'],
    queryFn: fetchCatalogSummary,
    retry: false,
  });

  function update(next: (draft: URLSearchParams) => void) {
    const draft = new URLSearchParams(params);
    next(draft);
    // Any change to what is being asked for starts at the first page: staying
    // on page four of a narrower result set shows an empty table and reads as
    // "nothing matched".
    draft.delete('offset');
    setParams(draft, { replace: true });
  }

  function toggleFacet(value: string) {
    update((draft) => {
      const current = draft.getAll('facet');
      draft.delete('facet');
      for (const facet of current) {
        if (facet !== value) {
          draft.append('facet', facet);
        }
      }
      if (!current.includes(value)) {
        draft.append('facet', value);
      }
    });
  }

  const total = data?.total ?? 0;
  const filtered = Boolean(search || assetType || facets.length);

  return (
    <>
      <header className="tw:flex tw:flex-wrap tw:items-end tw:justify-between tw:gap-4">
        <div>
          <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">Catalog</h1>
          <p className="tw:mt-2 tw:text-md tw:text-tertiary">
            Assets cached from OpenMetadata with the tags, terms, domains and owners a
            policy can select them by.
          </p>
        </div>
        {summary && (
          <dl className="tw:flex tw:gap-6 tw:text-sm">
            <Stat label="Tables" value={summary.assetsByType.TABLE ?? 0} />
            <Stat label="Columns" value={summary.columns} />
            <Stat label="Tagged columns" value={summary.taggedColumns} />
            <Stat
              label="Tables without an owner"
              tone={summary.assetsWithoutOwner > 0 ? 'warning' : undefined}
              value={summary.assetsWithoutOwner}
            />
          </dl>
        )}
      </header>

      <section className="tw:mt-8 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
        <form
          className="tw:flex tw:flex-wrap tw:items-center tw:gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            update((draft) => {
              if (searchDraft.trim()) {
                draft.set('q', searchDraft.trim());
              } else {
                draft.delete('q');
              }
            });
          }}>
          <div className="tw:min-w-64 tw:flex-1">
            <Input
              aria-label="Search the catalog"
              icon={SearchLg}
              onChange={setSearchDraft}
              placeholder="Search by name or fully qualified name"
              value={searchDraft}
            />
          </div>
          <select
            aria-label="Asset type"
            className="tw:rounded-lg tw:border tw:border-primary tw:bg-primary tw:px-3 tw:py-2 tw:text-sm tw:text-primary"
            onChange={(event) =>
              update((draft) => {
                if (event.target.value) {
                  draft.set('type', event.target.value);
                } else {
                  draft.delete('type');
                }
              })
            }
            value={assetType}>
            <option value="">All types</option>
            {ASSET_TYPES.map((type) => (
              <option key={type} value={type}>
                {type.charAt(0) + type.slice(1).toLowerCase()}
              </option>
            ))}
          </select>
          <Button size="md" type="submit">
            Search
          </Button>
          {filtered && (
            <Button
              color="tertiary"
              onPress={() => {
                setSearchDraft('');
                setParams(new URLSearchParams(), { replace: true });
              }}
              size="md">
              Clear
            </Button>
          )}
        </form>

        {facets.length > 0 && (
          <div className="tw:mt-3 tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <span className="tw:text-xs tw:text-tertiary">Filtering on</span>
            {facets.map((facet) => (
              <button
                className="tw:inline-flex tw:items-center tw:gap-1 tw:rounded-full tw:bg-brand-primary tw:px-2 tw:py-0.5 tw:text-xs tw:text-brand-secondary"
                key={facet}
                onClick={() => toggleFacet(facet)}
                type="button">
                {facet}
                <XClose className="tw:size-3" />
              </button>
            ))}
            {/* Said plainly because the opposite — OR — is what most search
                boxes do, and an author who assumes OR reads a narrow list as
                proof that nothing else is tagged. */}
            <span className="tw:text-xs tw:text-quaternary">
              (an asset must carry all of them)
            </span>
          </div>
        )}

        <FacetPicker active={facets} onToggle={toggleFacet} />
      </section>

      {error && (
        <p className="tw:mt-6 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'The catalog could not be read.')}
        </p>
      )}

      <section className="tw:mt-6">
        <div className="tw:flex tw:items-center tw:justify-between">
          <p className="tw:text-sm tw:text-tertiary">
            {isLoading
              ? 'Loading…'
              : `${total} ${total === 1 ? 'asset' : 'assets'}${filtered ? ' matching' : ' cached'}`}
            {isFetching && !isLoading && ' · refreshing'}
          </p>
          {total > PAGE_SIZE && (
            <div className="tw:flex tw:items-center tw:gap-2">
              <Button
                color="tertiary"
                isDisabled={offset === 0}
                onPress={() =>
                  setParams((draft) => {
                    draft.set('offset', String(Math.max(0, offset - PAGE_SIZE)));
                    return draft;
                  })
                }
                size="sm">
                Previous
              </Button>
              <span className="tw:text-xs tw:text-tertiary">
                {offset + 1}–{Math.min(offset + PAGE_SIZE, total)} of {total}
              </span>
              <Button
                color="tertiary"
                isDisabled={offset + PAGE_SIZE >= total}
                onPress={() =>
                  setParams((draft) => {
                    draft.set('offset', String(offset + PAGE_SIZE));
                    return draft;
                  })
                }
                size="sm">
                Next
              </Button>
            </div>
          )}
        </div>

        {!isLoading && data?.items.length === 0 && <Empty filtered={filtered} />}

        <ul className="tw:mt-4 tw:space-y-3">
          {data?.items.map((asset) => (
            <AssetRow asset={asset} key={asset.id} />
          ))}
        </ul>
      </section>
    </>
  );
}

function Stat({
  label,
  value,
  tone,
}: {
  label: string;
  value: number;
  tone?: 'warning';
}) {
  return (
    <div>
      <dt className="tw:text-xs tw:text-tertiary">{label}</dt>
      <dd
        className={
          tone === 'warning'
            ? 'tw:text-lg tw:font-semibold tw:text-warning-primary'
            : 'tw:text-lg tw:font-semibold tw:text-primary'
        }>
        {value}
      </dd>
    </div>
  );
}

function AssetRow({ asset }: { asset: AssetSummary }) {
  const facets = listFacets(asset.facets);

  return (
    <li className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4 tw:hover:border-brand">
      <div className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-3">
        <div className="tw:min-w-0">
          <Link
            className="tw:text-md tw:font-medium tw:text-primary tw:hover:text-brand-secondary"
            to={`/catalog/${encodeURIComponent(asset.fqn)}`}>
            {asset.displayName || asset.name}
          </Link>
          <p className="tw:mt-0.5 tw:truncate tw:font-mono tw:text-xs tw:text-quaternary">
            {asset.fqn}
          </p>
          {asset.description && (
            <p className="tw:mt-2 tw:line-clamp-2 tw:text-sm tw:text-tertiary">
              {asset.description}
            </p>
          )}
        </div>
        <div className="tw:flex tw:shrink-0 tw:items-center tw:gap-2">
          <Badge color="gray" size="sm" type="modern">
            {asset.assetType}
          </Badge>
          {asset.tier && (
            <Badge color="warning" size="sm" type="pill-color">
              {asset.tier}
            </Badge>
          )}
        </div>
      </div>

      {(facets.length > 0 || asset.owners.length > 0) && (
        <div className="tw:mt-3 tw:flex tw:flex-wrap tw:items-center tw:gap-1.5">
          {facets.map((facet) => (
            <FacetChip
              facet={facet}
              key={`${facet.facetType}:${facet.facetFqn}:${facet.property ?? ''}`}
            />
          ))}
          {asset.owners.map((owner) => (
            <OwnerChip key={`${owner.type}:${owner.name}`} owner={owner} />
          ))}
        </div>
      )}

      {asset.columnCount > 0 && (
        <p className="tw:mt-3 tw:text-xs tw:text-tertiary">
          {asset.columnCount} columns
          {asset.taggedColumnCount > 0 && (
            // The number that decides whether this table needs a data policy at
            // all, and the one the list would otherwise hide behind a click.
            <span className="tw:text-warning-primary">
              {' '}
              · {asset.taggedColumnCount} carrying a tag or term
            </span>
          )}
        </p>
      )}
    </li>
  );
}

/**
 * The facet values in use, as filters.
 *
 * <p>Read from what assets actually carry rather than from every tag defined in
 * OpenMetadata, so no option here returns nothing.
 */
function FacetPicker({
  active,
  onToggle,
}: {
  active: string[];
  onToggle: (value: string) => void;
}) {
  const { data } = useQuery({
    queryKey: ['catalog-facet-values'],
    queryFn: () => fetchFacetValues(undefined, 300),
    retry: false,
  });

  const byType = useMemo(() => {
    const groups = new Map<string, { facetFqn: string; assets: number }[]>();
    for (const value of data ?? []) {
      if (!FILTERABLE_FACETS.includes(value.facetType)) {
        continue;
      }
      const bucket = groups.get(value.facetType) ?? [];
      bucket.push(value);
      groups.set(value.facetType, bucket);
    }
    return FILTERABLE_FACETS.filter((type) => groups.has(type)).map(
      (type) => [type, groups.get(type)!] as const
    );
  }, [data]);

  if (byType.length === 0) {
    return null;
  }

  return (
    <div className="tw:mt-4 tw:space-y-3 tw:border-t tw:border-secondary tw:pt-4">
      {byType.map(([type, values]) => (
        <div className="tw:flex tw:flex-wrap tw:items-baseline tw:gap-2" key={type}>
          <span className="tw:w-32 tw:shrink-0 tw:text-xs tw:text-tertiary">
            {facetLabel(type)}
          </span>
          {values.slice(0, 12).map((value) => {
            const key = `${type}:${value.facetFqn}`;
            const on = active.includes(key);
            return (
              <button
                className={
                  on
                    ? 'tw:rounded-full tw:bg-brand-solid tw:px-2 tw:py-0.5 tw:text-xs tw:text-white'
                    : 'tw:rounded-full tw:border tw:border-secondary tw:px-2 tw:py-0.5 tw:text-xs tw:text-tertiary tw:hover:border-brand tw:hover:text-primary'
                }
                key={key}
                onClick={() => onToggle(key)}
                type="button">
                {value.facetFqn}
                <span className="tw:ml-1 tw:opacity-60">{value.assets}</span>
              </button>
            );
          })}
        </div>
      ))}
    </div>
  );
}

function Empty({ filtered }: { filtered: boolean }) {
  return (
    <div className="tw:mt-4 tw:rounded-xl tw:border tw:border-dashed tw:border-secondary tw:p-10 tw:text-center">
      <Database01 className="tw:mx-auto tw:size-6 tw:text-quaternary" />
      <p className="tw:mt-3 tw:text-sm tw:font-medium tw:text-primary">
        {filtered ? 'Nothing matches those filters' : 'The cache is empty'}
      </p>
      <p className="tw:mt-1 tw:text-sm tw:text-tertiary">
        {filtered
          ? 'Facet filters are AND-ed — an asset has to carry every one of them.'
          : 'No crawl has run yet, or it found nothing. A platform admin can start one from the sync endpoint.'}
      </p>
    </div>
  );
}
