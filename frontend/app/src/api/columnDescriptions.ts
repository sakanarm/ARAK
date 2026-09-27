import { apiClient } from './client';

/**
 * Column descriptions written in ARAK, for columns OpenMetadata has none of.
 *
 * Whoever governs the table writes them; everybody who can open the table
 * reads them, on its page and in an access request for it. A sync never
 * overwrites one, and where OpenMetadata has a description too, this one is
 * the one shown.
 */

export interface WrittenDescription {
  columnFqn: string;
  assetFqn: string;
  description: string;
  /** Drafted with NokRak before the person saved it. */
  assisted: boolean;
  writtenBy: string;
  writtenAt: string;
}

export interface ColumnDescriptionListing {
  /** Whether the caller governs the table, and so may change its descriptions. */
  canEdit: boolean;
  descriptions: WrittenDescription[];
}

/** One column to describe; an empty description takes the one written here away. */
export interface DescriptionEntry {
  columnFqn: string;
  description: string;
  assisted: boolean;
}

export interface DescriptionSave {
  set: number;
  cleared: number;
  unchanged: number;
  descriptions: WrittenDescription[];
}

export async function fetchColumnDescriptions(
  assetFqn: string
): Promise<ColumnDescriptionListing> {
  const { data } = await apiClient.get<ColumnDescriptionListing>('/v1/column-descriptions', {
    params: { asset: assetFqn },
  });
  return data;
}

export async function saveColumnDescriptions(change: {
  assetFqn: string;
  entries: DescriptionEntry[];
}): Promise<DescriptionSave> {
  const { data } = await apiClient.put<DescriptionSave>('/v1/column-descriptions', change);
  return data;
}
