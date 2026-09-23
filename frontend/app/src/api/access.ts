import { apiClient } from './client';

/**
 * Direct grants, and the access picture of one asset (FR-7).
 *
 * The distinction this module exists to keep visible: a grant is a *row*, and
 * access is a *verdict*. The two are not the same thing and the server does
 * not pretend they are — `GrantAccess.effectiveFor` is how a live grant admits
 * that a policy is currently overruling it. Nothing in this file should ever
 * compute access by filtering grants client-side; ask the server, which runs
 * the engine.
 */

/** Where one person's access comes from. */
export type AccessOrigin = 'GRANT' | 'POLICY' | 'BOTH';

/** One person who can read this asset right now, and why. */
export interface PersonAccess {
  principal: string;
  origin: AccessOrigin;
  viaPolicies: string[];
  /** Grant ids, not names: the page joins them back to the grant rows. */
  viaGrants: string[];
  /** True when they get in but see less than all of it. */
  restricted: boolean;
  maskedColumns: number;
  hiddenColumns: number;
  rowFilters: number;
}

/**
 * One grant on this asset, with what it is actually doing.
 *
 * `effectiveFor` is the count of evaluated people this grant lets in. Zero on
 * a `live` grant is the interesting case: the window is open, nobody is being
 * admitted by it, and a policy is the reason.
 */
export interface GrantAccess {
  id: string;
  principal: string;
  displayName: string | null;
  principalType: string | null;
  principalSource: string | null;
  validFrom: string | null;
  validUntil: string | null;
  reason: string | null;
  grantedBy: string | null;
  grantedAt: string | null;
  live: boolean;
  effectiveFor: number;
}

/**
 * The Access tab's whole answer.
 *
 * @param known false when the catalog has no such asset. The grants are still
 *   listed — a grant on an asset the crawl has lost is exactly the row someone
 *   needs to find — but `people` is empty rather than guessed at.
 * @param sampled true when more principals exist than were evaluated, so the
 *   page can say "of the first N" instead of quietly implying it is the lot.
 */
export interface AssetAccess {
  assetFqn: string;
  known: boolean;
  grants: GrantAccess[];
  people: PersonAccess[];
  principalsKnown: number;
  principalsEvaluated: number;
  sampled: boolean;
  evaluatedAt: string;
}

/** A grant as it is stored, which is what the write endpoints hand back. */
export interface StoredGrant {
  id: string;
  assetFqn: string;
  principalId: string;
  username: string;
  displayName: string | null;
  principalType: string | null;
  principalSource: string | null;
  source: 'manual' | 'request';
  requestId: string | null;
  validFrom: string | null;
  validUntil: string | null;
  reason: string;
  grantedBy: string;
  grantedAt: string;
  revokedAt: string | null;
  revokedBy: string | null;
  revokeReason: string | null;
}

/** One line of the append-only grant trail (FR-7.2, FR-8.1). */
export interface GrantHistoryEntry {
  /** The append-only sequence, which is also the tie-break for equal instants. */
  id: number;
  occurredAt: string;
  actor: string;
  action: 'GRANT' | 'REVOKE' | 'EXPIRE';
  grantId: string;
  assetFqn: string;
  targetUsername: string | null;
  targetSource: string | null;
  validFrom: string | null;
  validUntil: string | null;
  reason: string | null;
}

/**
 * Everyone who can reach one asset, and every grant on it (FR-7.3, FR-3.1.5).
 *
 * `encodeURI` rather than `encodeURIComponent`, as elsewhere for FQNs: the
 * path template on the server is greedy and the dots and slashes inside an FQN
 * are part of it, not separators to be escaped away.
 */
export async function fetchAssetAccess(fqn: string): Promise<AssetAccess> {
  const { data } = await apiClient.get<AssetAccess>(
    `/v1/access/assets/${encodeURI(fqn)}`
  );
  return data;
}

/** What has been granted and revoked on one asset over time. */
export async function fetchGrantHistory(
  fqn: string,
  limit = 100
): Promise<GrantHistoryEntry[]> {
  const { data } = await apiClient.get<GrantHistoryEntry[]>(
    `/v1/access/history/${encodeURI(fqn)}`,
    { params: { limit } }
  );
  return data;
}

/** What the signed-in person holds, directly or through a group. */
export async function fetchMyGrants(): Promise<StoredGrant[]> {
  const { data } = await apiClient.get<StoredGrant[]>('/v1/access/mine');
  return data;
}

/** What one named person holds. Same shape as {@link fetchMyGrants}. */
export async function fetchGrantsHeldBy(
  username: string
): Promise<StoredGrant[]> {
  const { data } = await apiClient.get<StoredGrant[]>(
    `/v1/access/principals/${encodeURIComponent(username)}`
  );
  return data;
}

/**
 * What the grant dialog sends.
 *
 * `validUntil` null means open-ended. That is allowed and deliberately not the
 * default in the UI: a grant nobody ever has to look at again is how access
 * accumulates.
 */
export interface NewGrant {
  assetFqn: string;
  principalId: string;
  validFrom?: string | null;
  validUntil?: string | null;
  reason: string;
}

export async function createGrant(request: NewGrant): Promise<StoredGrant> {
  const { data } = await apiClient.post<StoredGrant>(
    '/v1/access/grants',
    request
  );
  return data;
}

/**
 * Revokes a grant. The reason is required, as it is on creation.
 *
 * A POST, not a DELETE: the row survives as a tombstone so "who had access
 * last March" stays answerable.
 */
export async function revokeGrant(
  id: string,
  reason: string
): Promise<StoredGrant> {
  const { data } = await apiClient.post<StoredGrant>(
    `/v1/access/grants/${id}/revoke`,
    { reason }
  );
  return data;
}
