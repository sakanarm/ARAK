import {
  fetchAsset,
  fetchAssets,
  type AssetDetail,
  type AssetQuery,
  type AssetSummary,
  type FacetRow,
} from '../../api/client';
import { isAncestor } from '../../lib/fqn';
import { plainText } from '../../lib/text';
import type { Grid } from '../../lib/tabular';

/**
 * The catalog as a spreadsheet: what the list shows, under the same filters,
 * with the governance a reviewer would otherwise copy off each asset page.
 *
 * <p>Metadata only. Nothing here reads a row of anyone's data, so the export
 * is no egress; it is the same list the person can already scroll, which is
 * also why it is filtered by the server exactly as the list is.
 */

/** A page the backend will serve in one go. */
const PAGE = 500;

/** Past this the export is a crawl, not a download. */
export const EXPORT_CAP = 5000;

/** Asset pages fetched at once when columns are wanted. */
const CONCURRENCY = 6;

export interface Progress {
  done: number;
  total: number;
}

/** Every asset the list's filters reach, up to {@link EXPORT_CAP}. */
export async function collectAssets(
  query: Omit<AssetQuery, 'limit' | 'offset'>,
  onProgress?: (progress: Progress) => void
): Promise<{ assets: AssetSummary[]; total: number }> {
  const assets: AssetSummary[] = [];
  let total = 0;
  for (let offset = 0; offset < EXPORT_CAP; offset += PAGE) {
    const page = await fetchAssets({ ...query, limit: PAGE, offset });
    total = page.total;
    assets.push(...page.items);
    onProgress?.({ done: assets.length, total: Math.min(total, EXPORT_CAP) });
    if (page.items.length < PAGE || assets.length >= total) {
      break;
    }
  }
  return { assets: assets.slice(0, EXPORT_CAP), total };
}

/**
 * The detail of each table and view, a few at a time.
 *
 * <p>An asset that fails to load is left out rather than failing the whole
 * file: one table the crawl has since lost should not cost the other four
 * thousand.
 */
export async function collectDetails(
  assets: AssetSummary[],
  onProgress?: (progress: Progress) => void
): Promise<AssetDetail[]> {
  const wanted = assets.filter((asset) => hasColumns(asset.assetType));
  const details: (AssetDetail | null)[] = new Array(wanted.length).fill(null);
  let next = 0;
  let done = 0;
  async function worker() {
    while (next < wanted.length) {
      const at = next++;
      try {
        details[at] = await fetchAsset(wanted[at].fqn);
      } catch {
        details[at] = null;
      }
      onProgress?.({ done: ++done, total: wanted.length });
    }
  }
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, wanted.length) }, worker));
  return details.filter((detail): detail is AssetDetail => detail !== null);
}

function hasColumns(assetType: string): boolean {
  return assetType === 'TABLE' || assetType === 'VIEW';
}

export const ASSET_COLUMNS = [
  'FQN',
  'Name',
  'Display name',
  'Type',
  'Service',
  'Database',
  'Schema',
  'Description',
  'Tier',
  'Certification',
  'Classifications',
  'Tags',
  'Glossaries',
  'Glossary terms',
  'Domains',
  'Data products',
  'Owners',
  'Columns',
  'Tagged columns',
  'Connected through',
  'Catalogued by',
  'Custom properties',
  'OpenMetadata URL',
];

/** One row per asset. `details` adds the OpenMetadata link where it is known. */
export function assetGrid(assets: AssetSummary[], details: AssetDetail[] = []): Grid {
  const byFqn = new Map(details.map((detail) => [detail.asset.fqn, detail]));
  return {
    columns: ASSET_COLUMNS,
    rows: assets.map((asset) => {
      const parts = asset.fqn.split('.');
      const level = (depth: number) => (parts.length > depth ? parts[depth] : '');
      const facets = asset.facets ?? [];
      return [
        asset.fqn,
        asset.name,
        asset.displayName ?? '',
        asset.assetType,
        level(0),
        asset.assetType === 'SERVICE' ? '' : level(1),
        ['SCHEMA', 'TABLE', 'VIEW'].includes(asset.assetType) ? level(2) : '',
        asset.description ? plainText(asset.description) : '',
        asset.tier ?? '',
        asset.certification ?? '',
        leaves(facets, 'classifications'),
        leaves(facets, 'tags'),
        leaves(facets, 'glossaries'),
        leaves(facets, 'terms'),
        leaves(facets, 'domains'),
        leaves(facets, 'dataProducts'),
        (asset.owners ?? []).map((owner) => owner.name).join('; '),
        hasColumns(asset.assetType) ? asset.columnCount : '',
        hasColumns(asset.assetType) ? asset.taggedColumnCount : '',
        asset.querySource ?? '',
        origin(asset.provenance),
        properties(facets),
        byFqn.get(asset.fqn)?.openMetadataUrl ?? '',
      ];
    }),
  };
}

export const COLUMN_COLUMNS = [
  'Table FQN',
  'Column',
  'Position',
  'Data type',
  'Length',
  'Nullable',
  'Description',
  'Classifications',
  'Tags',
  'Glossary terms',
];

/**
 * One row per column. Only what the column carries itself: the domain and
 * owners it inherits from its table are on the table's row already.
 */
export function columnGrid(details: AssetDetail[]): Grid {
  return {
    columns: COLUMN_COLUMNS,
    rows: details.flatMap((detail) =>
      [...detail.columns]
        .sort((a, b) => (a.ordinal ?? 0) - (b.ordinal ?? 0))
        .map((column) => {
          const own = column.facets.filter((facet) => !facet.inheritedFrom);
          return [
            detail.asset.fqn,
            column.name,
            column.ordinal ?? '',
            column.dataType ?? '',
            column.dataLength ?? '',
            column.nullable === null ? '' : column.nullable ? 'Yes' : 'No',
            column.description ? plainText(column.description) : '',
            leaves(own, 'classifications'),
            leaves(own, 'tags'),
            leaves(own, 'terms'),
          ];
        })
    ),
  };
}

/**
 * The most specific values of one facet type, joined.
 *
 * <p>The cache stores every ancestor beside the value (a column tagged
 * `PII.Sensitive` also has a `PII` row); a spreadsheet cell that says both
 * reads as two tags.
 */
function leaves(facets: FacetRow[], facetType: string): string {
  const values = [
    ...new Set(
      facets.filter((facet) => facet.facetType === facetType).map((facet) => facet.facetFqn)
    ),
  ];
  return values
    .filter((value) => !values.some((other) => isAncestor(value, other)))
    .sort()
    .join('; ');
}

function properties(facets: FacetRow[]): string {
  const pairs = new Set(
    facets
      .filter((facet) => facet.facetType === 'customProperty' && facet.property)
      .map((facet) => `${facet.property}=${facet.facetFqn}`)
  );
  return [...pairs].sort().join('; ');
}

function origin(provenance: string | null | undefined): string {
  switch (provenance) {
    case 'openmetadata':
      return 'OpenMetadata';
    case 'discovered':
      return 'Read from source';
    case 'local':
      return 'ARAK only';
    default:
      return provenance ?? '';
  }
}
