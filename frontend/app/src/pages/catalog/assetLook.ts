import { Database01, Eye, Folder, Server01, Table } from '@untitledui/icons';
import type { BadgeColors } from '@openmetadata/ui-core-components/components/base/badges/badge-types';

/**
 * How each kind of asset is drawn — one glyph, one hue, one tinted tile.
 *
 * <p>Shared between the catalog list and the asset page on purpose. The icon is
 * how someone confirms, at a glance, that the page they landed on is the thing
 * they clicked; drawing a table as a table in the list and then as nothing at
 * all on its own page breaks that confirmation at exactly the moment it is
 * being asked for.
 *
 * <p>The hue is the same one the badge uses, so the two reinforce rather than
 * compete. Ordered the way the hierarchy nests — service contains database
 * contains schema contains table — with the hues walking the same direction, so
 * depth reads as a gradient rather than as noise.
 */
export interface AssetLook {
  Icon: typeof Database01;
  badge: BadgeColors;
  tile: string;
}

const ASSET_LOOK: Record<string, AssetLook> = {
  SERVICE: {
    Icon: Server01,
    badge: 'gray-blue',
    tile: 'tw:bg-utility-gray-blue-50 tw:text-utility-gray-blue-700',
  },
  DATABASE: {
    Icon: Database01,
    badge: 'blue',
    tile: 'tw:bg-utility-blue-50 tw:text-utility-blue-700',
  },
  SCHEMA: {
    Icon: Folder,
    badge: 'indigo',
    tile: 'tw:bg-utility-indigo-50 tw:text-utility-indigo-700',
  },
  TABLE: {
    Icon: Table,
    badge: 'brand',
    tile: 'tw:bg-utility-brand-50 tw:text-utility-brand-700',
  },
  VIEW: {
    Icon: Eye,
    badge: 'purple',
    tile: 'tw:bg-utility-purple-50 tw:text-utility-purple-700',
  },
};

/** Anything the crawl returns that this list was not told about. */
const UNKNOWN_LOOK: AssetLook = {
  Icon: Database01,
  badge: 'gray',
  tile: 'tw:bg-secondary tw:text-tertiary',
};

/** Never null: an unrecognised type still gets drawn, in neutral grey. */
export function lookFor(assetType: string | null | undefined): AssetLook {
  return (assetType ? ASSET_LOOK[assetType] : undefined) ?? UNKNOWN_LOOK;
}
