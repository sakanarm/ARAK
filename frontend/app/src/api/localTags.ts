import { apiClient } from './client';

/**
 * Tags attached in ARAK itself, on a table or one of its columns (FR-1.7).
 *
 * The vocabulary is still OpenMetadata's: only a tag the governance crawl
 * brought in can be attached, and only by whoever governs the table. A sync
 * never takes one away.
 */

export interface LocalTag {
  targetFqn: string;
  assetFqn: string;
  tagFqn: string;
  reason: string;
  addedBy: string;
  addedAt: string;
}

export interface LocalTagListing {
  /** Whether the caller governs the table, and so may change its tags. */
  canEdit: boolean;
  tags: LocalTag[];
}

export interface LocalTagChange {
  targetFqn: string;
  tagFqn: string;
  reason: string;
}

export async function fetchLocalTags(assetFqn: string): Promise<LocalTagListing> {
  const { data } = await apiClient.get<LocalTagListing>('/v1/local-tags', {
    params: { asset: assetFqn },
  });
  return data;
}

export async function addLocalTag(change: LocalTagChange): Promise<LocalTag> {
  const { data } = await apiClient.post<LocalTag>('/v1/local-tags', change);
  return data;
}

/** A POST: taking a tag off needs a reason too, and a DELETE has no body. */
export async function removeLocalTag(change: LocalTagChange): Promise<LocalTag> {
  const { data } = await apiClient.post<LocalTag>('/v1/local-tags/remove', change);
  return data;
}
