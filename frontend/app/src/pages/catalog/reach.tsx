import { BookOpen01, Database01, Edit05 } from '@untitledui/icons';
import type { AssetSummary } from '../../api/client';

/*
 * Two questions a catalog row has to answer before anyone clicks it, and they
 * are independent of each other:
 *
 *   - where did ARAK learn about this? OpenMetadata, or by reading the source
 *     itself over JDBC -- or a steward typed it in here;
 *   - can a query reach it? Only if a registered, enabled source maps it.
 *     Otherwise ARAK holds its description and nothing else.
 *
 * Both are always shown, both ways round: a row that says nothing about
 * reach reads as "queryable" to someone who has only ever seen queryable ones.
 * The positive state is coloured and the negative one is grey, so the eye
 * finds the connected tables without the metadata-only ones shouting.
 */

type Size = 'sm' | 'md';

const ORIGIN: Record<string, { label: string; title: string; Icon: typeof BookOpen01 }> = {
  openmetadata: {
    label: 'OpenMetadata',
    title: 'Catalogued in OpenMetadata and synced into ARAK.',
    Icon: BookOpen01,
  },
  discovered: {
    label: 'Read from source',
    title: 'Not in OpenMetadata: ARAK read it off the database itself over JDBC.',
    Icon: Database01,
  },
  local: {
    label: 'ARAK only',
    title: 'Not in OpenMetadata: recorded in ARAK by hand.',
    Icon: Edit05,
  },
};

function pill(size: Size): string {
  return `tw:inline-flex tw:max-w-full tw:shrink-0 tw:items-center tw:gap-1.5 tw:rounded-full tw:border tw:font-medium tw:whitespace-nowrap ${
    size === 'md' ? 'tw:px-2.5 tw:py-1 tw:text-sm' : 'tw:px-2 tw:py-0.5 tw:text-xs'
  }`;
}

/** Where the entry came from. */
export function OriginBadge({ asset, size = 'sm' }: { asset: AssetSummary; size?: Size }) {
  const origin = ORIGIN[asset.provenance ?? 'openmetadata'] ?? ORIGIN.openmetadata;
  const fromOm = (asset.provenance ?? 'openmetadata') === 'openmetadata';
  return (
    <span
      className={`${pill(size)} ${
        fromOm
          ? 'tw:border-utility-purple-200 tw:bg-utility-purple-50 tw:text-utility-purple-700'
          : 'tw:border-secondary tw:bg-primary tw:text-tertiary'
      }`}
      title={origin.title}>
      <origin.Icon aria-hidden className={size === 'md' ? 'tw:size-4' : 'tw:size-3'} />
      <span className="tw:truncate">{origin.label}</span>
    </span>
  );
}

/** Whether a query can reach it, and through which source. */
export function ReachBadge({ asset, size = 'sm' }: { asset: AssetSummary; size?: Size }) {
  const container = !['TABLE', 'VIEW'].includes(asset.assetType);
  if (asset.querySource) {
    return (
      <span
        className={`${pill(size)} tw:border-utility-green-200 tw:bg-utility-green-50 tw:text-utility-green-700`}
        title={
          container
            ? `A query can reach tables under this through the ${asset.querySource} source.`
            : `Connected: a query reaches this through the ${asset.querySource} source.`
        }>
        <span className="tw:size-2 tw:shrink-0 tw:rounded-full tw:bg-utility-green-500" />
        <span className="tw:truncate">Queryable · {asset.querySource}</span>
      </span>
    );
  }
  return (
    <span
      className={`${pill(size)} tw:border-dashed tw:border-primary tw:bg-primary tw:text-quaternary`}
      title="No connected source maps this, so ARAK holds its metadata only. Register a source for it to query it.">
      <span className="tw:size-2 tw:shrink-0 tw:rounded-full tw:border tw:border-current" />
      <span className="tw:truncate">Metadata only</span>
    </span>
  );
}

/** Both, side by side -- the pair every row carries. */
export function ReachBadges({ asset, size = 'sm' }: { asset: AssetSummary; size?: Size }) {
  return (
    <span className="tw:inline-flex tw:flex-wrap tw:items-center tw:gap-1.5">
      <ReachBadge asset={asset} size={size} />
      <OriginBadge asset={asset} size={size} />
    </span>
  );
}

/** One sentence for the detail page: what ARAK can and cannot do with this. */
export function reachSentence(asset: AssetSummary): string {
  const origin = asset.provenance ?? 'openmetadata';
  const known =
    origin === 'openmetadata'
      ? 'Catalogued in OpenMetadata'
      : origin === 'discovered'
        ? 'Not in OpenMetadata — ARAK read it off the database'
        : 'Not in OpenMetadata — recorded in ARAK by hand';
  const container = !['TABLE', 'VIEW'].includes(asset.assetType);
  const reach = asset.querySource
    ? container
      ? `and a query can reach what is under it through ${asset.querySource}.`
      : `and connected: a query reaches it through ${asset.querySource}.`
    : 'but no connected source maps it, so ARAK holds its metadata only.';
  return `${known}, ${reach}`;
}
