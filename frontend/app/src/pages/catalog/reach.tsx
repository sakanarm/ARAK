import { BookOpen01, Database01, Edit05 } from '@untitledui/icons';
import type { AssetSummary } from '../../api/client';
import type { EligibilityBrief } from '../../api/accessRequests';

/*
 * Two questions a catalog row has to answer before anyone clicks it, and they
 * are independent of each other:
 *
 *   - where did ARAK learn about this? OpenMetadata, or by reading the source
 *     itself over JDBC -- or a steward typed it in here;
 *   - is it connected? Only if a registered, enabled source maps it can a
 *     query run on it. Otherwise ARAK holds its description and nothing else.
 *
 * Both are always shown, both ways round: a row that says nothing about
 * connection reads as "connected" to someone who has only ever seen connected
 * ones. The positive state is coloured and the negative one is grey, so the
 * eye finds the connected tables without the others shouting.
 *
 * A third, separate question is about the person looking: can *they* query
 * it, or ask to? That is the {@link AccessBadge}. The rule for telling them
 * apart on the page: a badge about the table never says "you", and a badge
 * about the person always starts with "You". "Metadata only" used to sit
 * where "Not connected" is and was read as "you may only see the metadata".
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

function isTable(asset: Pick<AssetSummary, 'assetType'>): boolean {
  return ['TABLE', 'VIEW'].includes(asset.assetType);
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

/** Whether the table is connected -- a query can run on it -- and through which source. */
export function ReachBadge({ asset, size = 'sm' }: { asset: AssetSummary; size?: Size }) {
  const table = isTable(asset);
  if (asset.querySource) {
    return (
      <span
        className={`${pill(size)} tw:border-utility-green-200 tw:bg-utility-green-50 tw:text-utility-green-700`}
        title={
          table
            ? `A data source registered in ARAK (${asset.querySource}) maps this table, so queries can run on it through ARAK. Whether you may query it is shown by your access badge.`
            : `Tables under this are connected through ${asset.querySource}.`
        }>
        <span className="tw:size-2 tw:shrink-0 tw:rounded-full tw:bg-utility-green-500" />
        <span className="tw:truncate">Connected · {asset.querySource}</span>
      </span>
    );
  }
  return (
    <span
      className={`${pill(size)} tw:border-dashed tw:border-primary tw:bg-primary tw:text-quaternary`}
      title={
        table
          ? 'No data source registered in ARAK maps this table, so nobody can query it through ARAK yet. This is about the table, not about your access. An admin can register its source to connect it.'
          : 'No data source registered in ARAK maps the tables under this, so nobody can query them through ARAK yet. This is about the tables, not about your access. An admin can register their source to connect them.'
      }>
      <span className="tw:size-2 tw:shrink-0 tw:rounded-full tw:border tw:border-current" />
      <span className="tw:truncate">Not connected</span>
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

/** The person's standing, in the words and colours of {@link AccessBadge}. */
export interface Standing {
  label: string;
  /** The badge's tooltip, in a catalog row and on the table's page alike. */
  title: string;
  tone: 'green' | 'blue' | 'neutral' | 'grey';
}

/**
 * What the "You …" badge says, or null when there is nothing to say about the
 * person -- the table is not connected, so nobody's access is the question.
 *
 * An open request wins over "can request" and "no access": the person has
 * already done the one thing either would tell them.
 */
export function standingOf(brief: EligibilityBrief | null | undefined): Standing | null {
  if (!brief || !brief.queryable) {
    return null;
  }
  if (brief.readable) {
    return {
      label: 'You can query',
      title:
        'A query you run on this table through ARAK is allowed now. Any masks or row filters that apply to you still do.',
      tone: 'green',
    };
  }
  if (brief.openRequestId) {
    return {
      label: 'You requested access',
      title: 'Your request for this table is waiting for an answer; follow it under Access requests.',
      tone: 'blue',
    };
  }
  if (brief.requestable) {
    return {
      label: 'You can request',
      title: 'You cannot query this table yet. Request access: an approved request lets you in.',
      tone: 'neutral',
    };
  }
  // What stands in the way is for whoever decides the request, who sees it
  // when they review it; the reader is told only that they can ask.
  return {
    label: 'You have no access',
    title: 'You cannot query this table yet, but you can still request access.',
    tone: 'grey',
  };
}

const TONE: Record<Standing['tone'], { pill: string; dot: string }> = {
  green: {
    pill: 'tw:border-utility-green-200 tw:bg-utility-green-50 tw:text-utility-green-700',
    dot: 'tw:bg-utility-green-500',
  },
  blue: {
    pill: 'tw:border-utility-blue-200 tw:bg-utility-blue-50 tw:text-utility-blue-700',
    dot: 'tw:bg-utility-blue-500',
  },
  neutral: {
    pill: 'tw:border-secondary tw:bg-primary tw:text-secondary',
    dot: 'tw:border tw:border-current',
  },
  grey: {
    pill: 'tw:border-secondary tw:bg-secondary tw:text-tertiary',
    dot: 'tw:bg-utility-gray-400',
  },
};

/**
 * Where the person looking stands on one table: "You can query", "You can
 * request", "You requested access" or "You have no access". Nothing while it
 * is loading, for a container, or for a table that is not connected.
 */
export function AccessBadge({
  asset,
  brief,
  size = 'sm',
}: {
  asset: Pick<AssetSummary, 'assetType'>;
  brief: EligibilityBrief | null | undefined;
  size?: Size;
}) {
  const standing = isTable(asset) ? standingOf(brief) : null;
  if (!standing) {
    return null;
  }
  const tone = TONE[standing.tone];
  return (
    <span className={`${pill(size)} ${tone.pill}`} title={standing.title}>
      <span className={`tw:size-2 tw:shrink-0 tw:rounded-full ${tone.dot}`} />
      <span className="tw:truncate">{standing.label}</span>
    </span>
  );
}
