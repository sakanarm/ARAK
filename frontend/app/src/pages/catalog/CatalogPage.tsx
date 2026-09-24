import { useEffect, useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import {
  ChevronDown,
  Database01,
  FilterLines,
  SearchLg,
  XClose,
} from '@untitledui/icons';
import { lookFor } from './assetLook';
import { Chip as Badge } from '../../components/chips';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Checkbox } from '@openmetadata/ui-core-components/components/base/checkbox/checkbox';
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
import { Select } from '../policies/controls';
import { PAGE_SIZES, Pager } from '../../components/Pager';
import { leaf, segments, shortFqn } from '../../lib/fqn';
import { plainText } from '../../lib/text';

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
  // In the URL with everything else, so a link reopens the same page of the
  // same list rather than the first 25 of it.
  const sized = Number(params.get('size'));
  const pageSize = PAGE_SIZES.includes(sized) ? sized : PAGE_SIZE;

  const { data, isLoading, error, isFetching } = useQuery({
    queryKey: ['catalog-assets', search, assetType, facets, offset, pageSize],
    queryFn: () =>
      fetchAssets({ search, assetType, facets, limit: pageSize, offset }),
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

  /*
   * Ending the results where the filters end is a thing only the browser can
   * work out: the rail is as tall as however many tags this deployment
   * happens to have, which no constant here could know. So it is measured,
   * and the results are capped to it -- the two columns then bottom out on
   * the same line however the catalog is tagged.
   *
   * There is no feedback loop to worry about: the rail is `items-start`, so
   * its height is its own content and never a reaction to this one's.
   */
  const [rail, setRail] = useState<HTMLElement | null>(null);
  const [railHeight, setRailHeight] = useState<number | null>(null);

  useEffect(() => {
    if (
      rail === null ||
      typeof ResizeObserver === 'undefined' ||
      typeof window.matchMedia !== 'function'
    ) {
      return;
    }
    // Tailwind's `lg`. Below it the rail sits above the results rather than
    // beside them, and matching heights would squash the list for no reason.
    const beside = window.matchMedia('(min-width: 1024px)');
    const measure = () =>
      setRailHeight(beside.matches ? rail.offsetHeight : null);

    const observer = new ResizeObserver(measure);
    observer.observe(rail);
    beside.addEventListener('change', measure);
    measure();

    return () => {
      observer.disconnect();
      beside.removeEventListener('change', measure);
    };
  }, [rail]);
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
          <Select
            ariaLabel="Asset type"
            className="tw:min-w-44"
            onChange={(next) =>
              update((draft) => {
                if (next) {
                  draft.set('type', next);
                } else {
                  draft.delete('type');
                }
              })
            }
            options={[
              { value: '', label: 'All types' },
              ...ASSET_TYPES.map((type) => ({
                value: type,
                label: type.charAt(0) + type.slice(1).toLowerCase(),
              })),
            ]}
            value={assetType}
          />
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
            {facets.map((facet) => {
              // `type:fqn`, and only the first colon separates them — an FQN
              // may contain one.
              const cut = facet.indexOf(':');
              const type = facet.slice(0, cut);
              const value = facet.slice(cut + 1);
              return (
                <button
                  className="tw:cursor-pointer tw:inline-flex tw:max-w-80 tw:items-center tw:gap-1 tw:rounded-full tw:bg-brand-primary tw:px-2 tw:py-0.5 tw:text-xs tw:text-brand-secondary"
                  key={facet}
                  onClick={() => toggleFacet(facet)}
                  title={`${facetLabel(type)} · ${value}`}
                  type="button">
                  <span className="tw:opacity-70">{facetLabel(type)}</span>
                  <span className="tw:truncate">{shortFqn(value)}</span>
                  <XClose className="tw:size-3 tw:shrink-0" />
                </button>
              );
            })}
            {/* Said plainly because the opposite — OR — is what most search
                boxes do, and an author who assumes OR reads a narrow list as
                proof that nothing else is tagged. */}
            <span className="tw:text-xs tw:text-quaternary">
              (an asset must carry all of them)
            </span>
          </div>
        )}

      </section>

      {error && (
        <p className="tw:mt-6 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'The catalog could not be read.')}
        </p>
      )}

      <div className="tw:mt-6 tw:flex tw:flex-col tw:gap-6 tw:lg:flex-row tw:lg:items-start">
        <FacetRail active={facets} innerRef={setRail} onToggle={toggleFacet} />

        <section className="tw:min-w-0 tw:flex-1">
        <div className="tw:flex tw:items-center tw:justify-between">
          <p className="tw:text-sm tw:text-tertiary">
            {isLoading
              ? 'Loading…'
              : `${total} ${total === 1 ? 'asset' : 'assets'}${filtered ? ' matching' : ' cached'}`}
            {isFetching && !isLoading && ' · refreshing'}
          </p>
          {total > 0 && (
            <span className="tw:text-xs tw:text-tertiary">
              Showing {offset + 1}–{Math.min(offset + pageSize, total)}
            </span>
          )}
        </div>

        {!isLoading && data?.items.length === 0 && <Empty filtered={filtered} />}

        {/*
          * Boxes of one height in a column that scrolls on its own, the way
          * OpenMetadata's Explore lists what it found.
          *
          * The divided list this replaced let every row take the height its
          * description happened to need, so the page rocked as it was scrolled
          * -- three lines here, one there -- and the eye lost the column it was
          * tracking. `auto-rows-fr` makes every box as tall as the tallest, and
          * the footer of each sits on the same line as its neighbours', which
          * is what makes a list of boxes scannable rather than merely pretty.
          *
          * The region scrolls rather than the page so the filters stay put: the
          * rail beside it is how the list is narrowed, and having to scroll
          * back up to reach it is the whole reason this page felt long.
          */}
        {/* The measured height wins over the class when the rail is beside
          * the results; the class is what runs before the first measurement
          * and on a narrow window, where the rail is stacked above and its
          * height has nothing to do with how tall this should be. */}
        <div
          className="tw:mt-4 tw:max-h-[calc(100vh_-_18rem)] tw:overflow-y-auto tw:pr-1"
          style={railHeight === null ? undefined : { maxHeight: railHeight }}>
          <ul className="tw:grid tw:auto-rows-fr tw:gap-3">
            {data?.items.map((asset) => (
              <AssetCard asset={asset} key={asset.id} />
            ))}
          </ul>
        </div>

        <Pager
          label="Catalog pages"
          noun="Assets"
          offset={offset}
          onOffset={(next) =>
            setParams((draft) => {
              draft.set('offset', String(next));
              return draft;
            })
          }
          onPageSize={(next) =>
            setParams((draft) => {
              draft.set('size', String(next));
              // A different page size means different page boundaries, so the
              // offset it was on no longer points at anything the reader chose.
              draft.delete('offset');
              return draft;
            })
          }
          pageSize={pageSize}
          total={total}
        />
        </section>
      </div>
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

function AssetCard({ asset }: { asset: AssetSummary }) {
  const facets = listFacets(asset.facets);
  const look = lookFor(asset.assetType);
  const Icon = look.Icon;
  const description = plainText(asset.description);

  return (
    <li className="tw:group tw:relative tw:flex tw:flex-col tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:transition-colors tw:hover:bg-secondary_subtle">
      {/*
        * The whole box is the link, not just the name. A box whose only
        * navigable part is eighty pixels of text asks the reader to aim.
        */}
      {/* The name is the label, not a second copy of the text: a hidden
        * duplicate would make the box's own name ambiguous to a screen reader
        * and to any test that looks the box up by it. */}
      <Link
        aria-label={asset.displayName || asset.name}
        className="tw:absolute tw:inset-0 tw:z-0 tw:rounded-xl"
        to={`/catalog/${encodeURIComponent(asset.fqn)}`}
      />

      <div className="tw:pointer-events-none tw:relative tw:z-10 tw:flex tw:flex-1 tw:flex-col tw:px-4 tw:py-3.5">
        {/* Where it lives, above what it is called -- the breadcrumb reads
          * first because two tables named `customer` are told apart by it. */}
        <p className="tw:truncate tw:font-mono tw:text-xs tw:text-quaternary">
          {asset.fqn}
        </p>

        <div className="tw:mt-1.5 tw:flex tw:gap-3.5">
          <span
            className={`tw:mt-0.5 tw:flex tw:size-9 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg ${look.tile}`}>
            <Icon className="tw:size-4.5" />
          </span>

          <div className="tw:min-w-0 tw:flex-1">
            <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-x-2 tw:gap-y-1">
              <span className="tw:truncate tw:text-md tw:font-semibold tw:text-primary tw:group-hover:text-brand-secondary">
                {asset.displayName || asset.name}
              </span>
              {/* "color", not "modern": the modern badge is gray only, and gray
                * for every kind is the thing this box is trying to stop being. */}
              <Badge color={look.badge} size="sm" type="color">
                {asset.assetType}
              </Badge>
              {asset.tier && (
                <Badge color="warning" size="sm" type="pill-color">
                  {asset.tier}
                </Badge>
              )}
            </div>

            {/* Kept even when empty, and said out loud. An absent description
              * is a governance gap someone has to close, and a blank space
              * where the next box has two lines would only look like a
              * rendering fault. */}
            <p
              className={`tw:mt-1 tw:line-clamp-2 tw:text-sm ${description ? 'tw:text-tertiary' : 'tw:text-quaternary'}`}>
              {description || 'No description'}
            </p>
          </div>
        </div>

        {/* Pushed to the bottom so that every box's footer sits on one line. */}
        <div className="tw:mt-auto tw:pt-2.5">
          {(facets.length > 0 || asset.owners.length > 0) && (
            // Chips are their own links, so this box gets its clicks back.
            <div className="tw:pointer-events-auto tw:flex tw:flex-wrap tw:items-center tw:gap-1.5">
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
            <p className="tw:mt-2 tw:text-xs tw:text-tertiary">
              {asset.columnCount} columns
              {asset.taggedColumnCount > 0 && (
                // The number that decides whether this table needs a data
                // policy at all, and the one the list would otherwise hide
                // behind a click.
                <span className="tw:font-medium tw:text-warning-primary">
                  {' '}
                  · {asset.taggedColumnCount} carrying a tag or term
                </span>
              )}
            </p>
          )}
        </div>
      </div>
    </li>
  );
}

/** Values shown per group before "Show all" is offered. */
const FACET_PREVIEW = 6;

/** The point at which a group gets its own search box. */
const FACET_SEARCHABLE = 10;

/**
 * The facet values in use, as filters — OpenMetadata's Explore rail.
 *
 * <p>Read from what assets actually carry rather than from every tag defined in
 * OpenMetadata, so no option here returns nothing.
 *
 * <p>Vertical, one value per line, rather than the wrapped row of chips this
 * replaced. The chips were the wrong shape for the data: a sub-domain named
 * `Premium Service Delivery - IOS Data/DTP - Sub Domain` has to be cut to fit a
 * chip, and once several of them are cut they all read
 * `… / Premium Service Delivery - IOS …` and become impossible to tell apart —
 * which is fatal for a control whose entire job is picking the right one of
 * them. A line gives the name the full width of the rail, and the count sits in
 * a column where the eye can compare the numbers instead of hunting for them.
 */
function FacetRail({
  active,
  innerRef,
  onToggle,
}: {
  active: string[];
  /** Handed back so the results beside it can be capped to its height. */
  innerRef: (node: HTMLElement | null) => void;
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
    // By name, not by count. These facets are hierarchies, and sorting by
    // count scatters `PII.Sensitive` away from `PII` — which matters here
    // because the rail draws a child indented under its parent, and an indent
    // under an unrelated row is a lie about where the value sits.
    for (const bucket of groups.values()) {
      bucket.sort((a, b) => a.facetFqn.localeCompare(b.facetFqn));
    }
    return FILTERABLE_FACETS.filter((type) => groups.has(type)).map(
      (type) => [type, groups.get(type)!] as const
    );
  }, [data]);

  if (byType.length === 0) {
    return null;
  }

  // Stacked above the results when there is no room beside them, rather than
  // hidden: this is the only way to filter on a tag, and a narrow window is not
  // a reason to take that away.
  return (
    <aside
      className="tw:w-full tw:shrink-0 tw:overflow-hidden tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:lg:sticky tw:lg:top-4 tw:lg:w-64 tw:lg:self-start"
      ref={innerRef}>
      <div className="tw:flex tw:items-center tw:gap-2 tw:border-b tw:border-secondary tw:px-4 tw:py-3">
        <FilterLines className="tw:size-4 tw:text-tertiary" />
        <h2 className="tw:text-sm tw:font-semibold tw:text-primary">Filters</h2>
        {active.length > 0 && (
          <span className="tw:ml-auto tw:rounded-full tw:bg-brand-primary tw:px-2 tw:py-0.5 tw:text-xs tw:font-medium tw:text-brand-secondary">
            {active.length}
          </span>
        )}
      </div>

      {byType.map(([type, values]) => (
        <FacetGroup
          active={active}
          key={type}
          onToggle={onToggle}
          type={type}
          values={values}
        />
      ))}
    </aside>
  );
}

/**
 * How far to indent a facet value, by how deep its FQN is.
 *
 * <p>The rail prints the last segment only. That is what makes these readable
 * at 200 pixels — three sub-domains under `Premium Service Delivery` all begin
 * with the same twenty-four characters, so any rendering that keeps the prefix
 * truncates to three identical rows, and a filter whose options cannot be told
 * apart is worse than no filter. The indent puts the dropped prefix back as
 * position rather than as text, the values are sorted so the parent is the row
 * directly above, and the full name is on the row's tooltip.
 *
 * <p>Capped at three levels: past that the indent costs more width than the
 * nesting is worth, and the name is what the reader came for.
 */
function indent(fqn: string): number {
  return Math.min(segments(fqn).length - 1, 3) * 12;
}

/** One collapsible facet type in the rail. */
function FacetGroup({
  type,
  values,
  active,
  onToggle,
}: {
  type: string;
  values: { facetFqn: string; assets: number }[];
  active: string[];
  onToggle: (value: string) => void;
}) {
  const selected = values.filter((value) =>
    active.includes(`${type}:${value.facetFqn}`)
  ).length;

  // Open by default. A rail of closed headings makes the reader click four
  // times to find out what they can even filter by, and the counts — which are
  // the reason to look at all — are the part that stays hidden.
  const [open, setOpen] = useState(true);
  const [all, setAll] = useState(false);
  const [needle, setNeedle] = useState('');

  const matching = useMemo(() => {
    const term = needle.trim().toLowerCase();
    if (!term) {
      return values;
    }
    return values.filter((value) => value.facetFqn.toLowerCase().includes(term));
  }, [values, needle]);

  // Anything already ticked is always drawn, even when it falls outside the
  // preview or the search: a filter you cannot see is a filter you cannot undo.
  const shown = useMemo(() => {
    const head = all ? matching : matching.slice(0, FACET_PREVIEW);
    const missing = values.filter(
      (value) =>
        active.includes(`${type}:${value.facetFqn}`) && !head.includes(value)
    );
    return [...head, ...missing];
  }, [matching, all, values, active, type]);

  return (
    <div className="tw:border-b tw:border-secondary tw:last:border-b-0">
      <button
        aria-expanded={open}
        className="tw:cursor-pointer tw:flex tw:w-full tw:items-center tw:gap-2 tw:px-4 tw:py-2.5 tw:text-left tw:hover:bg-secondary"
        onClick={() => setOpen((it) => !it)}
        type="button">
        <span className="tw:flex-1 tw:truncate tw:text-sm tw:font-medium tw:text-secondary">
          {facetLabel(type)}
        </span>
        {selected > 0 && (
          <span className="tw:rounded-full tw:bg-brand-primary tw:px-1.5 tw:text-xs tw:font-medium tw:text-brand-secondary">
            {selected}
          </span>
        )}
        <ChevronDown
          className={`tw:size-4 tw:shrink-0 tw:text-quaternary tw:transition-transform tw:duration-150 ${
            open ? '' : 'tw:-rotate-90'
          }`}
        />
      </button>

      {open && (
        <div className="tw:px-2 tw:pb-3">
          {values.length >= FACET_SEARCHABLE && (
            <div className="tw:px-2 tw:pb-2">
              <Input
                aria-label={`Search ${facetLabel(type)}`}
                icon={SearchLg}
                onChange={setNeedle}
                placeholder="Search"
                size="sm"
                value={needle}
              />
            </div>
          )}

          {shown.length === 0 && (
            <p className="tw:px-2 tw:py-1 tw:text-xs tw:text-tertiary">
              Nothing matches.
            </p>
          )}

          {shown.map((value) => {
            const key = `${type}:${value.facetFqn}`;
            return (
              // Not a <label> wrapping the checkbox: react-aria's Checkbox
              // renders its own <label>, and a label inside a label leaves the
              // input with no accessible name at all. So the name is given
              // explicitly — as the full FQN, since "Sensitive" on its own does
              // not say which classification it belongs to — and the visible
              // row stays free to put the count in an aligned column.
              <div
                className="tw:flex tw:items-center tw:gap-2 tw:rounded-md tw:px-2 tw:py-1.5 tw:hover:bg-secondary"
                key={key}
                // The whole name, for anyone checking that this is the exact
                // sub-domain they meant rather than its sibling.
                title={value.facetFqn}>
                <Checkbox
                  aria-label={`${value.facetFqn} · ${value.assets} assets`}
                  isSelected={active.includes(key)}
                  onChange={() => onToggle(key)}
                  size="sm"
                />
                <button
                  className="tw:min-w-0 tw:flex-1 tw:cursor-pointer tw:truncate tw:text-left tw:text-sm tw:text-secondary"
                  onClick={() => onToggle(key)}
                  style={{ paddingLeft: `${indent(value.facetFqn)}px` }}
                  // The checkbox beside it already carries the name; announcing
                  // it twice makes the rail read as two controls per value.
                  tabIndex={-1}
                  type="button">
                  {leaf(value.facetFqn)}
                </button>
                {/* Tabular figures so the counts line up as a column. */}
                <span className="tw:shrink-0 tw:text-xs tw:tabular-nums tw:text-quaternary">
                  {value.assets}
                </span>
              </div>
            );
          })}

          {matching.length > FACET_PREVIEW && (
            <button
              className="tw:cursor-pointer tw:px-2 tw:pt-1 tw:text-xs tw:font-medium tw:text-fg-brand-primary tw:hover:underline"
              onClick={() => setAll((it) => !it)}
              type="button">
              {all ? 'Show less' : `Show all ${matching.length}`}
            </button>
          )}
        </div>
      )}
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
