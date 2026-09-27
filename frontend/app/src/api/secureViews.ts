import { apiClient } from './client';

/**
 * Enforcement mode 5.1.2 from the browser: review a secure view, apply it,
 * take it off again (FR-6.1, FR-6.4).
 *
 * An apply carries the id of a dry run and nothing else. The server kept what
 * it showed; this screen cannot send back a change it wrote itself, and should
 * not be able to.
 */

export type EnforcementStatus =
  | 'NOT_ENFORCED'
  | 'PENDING'
  | 'APPLIED'
  | 'DRIFTED'
  | 'FAILED';

export interface SecureViewCandidate {
  assetFqn: string;
  displayName: string | null;
  dataSourceId: string;
  sourceName: string;
  engine: string;
  defaultMode: string;
  schema: string;
  table: string;
  /** Where the view is, when applied; where it would go, when not. */
  secureObject: string;
  status: EnforcementStatus;
  lastAppliedAt: string | null;
  lastAppliedBy: string | null;
  lastError: string | null;
}

interface Subscription {
  principal: string;
  asset: string;
}
interface Entitlement {
  principal: string;
  asset: string;
  entitlementKey: string;
  value: string;
}
interface ColumnGrant {
  principal: string;
  asset: string;
  column: string;
  treatment: string;
}

export interface EntitlementRows {
  subscriptions: Subscription[];
  entitlements: Entitlement[];
  grants: ColumnGrant[];
}

export interface DryRun {
  installed: EntitlementRows;
  rows: {
    desired: EntitlementRows;
    insert: EntitlementRows;
    delete: EntitlementRows;
    notes: string[];
  };
  applyScript: string;
  rollbackScript: string;
  notes: string[];
  warnings: string[];
  signature: string;
}

export interface SecureViewPreview {
  reviewId: string;
  expiresAt: string;
  assetFqn: string;
  dataSourceId: string;
  sourceName: string;
  engine: string;
  secureObject: string;
  liveColumns: string[];
  uncataloguedColumns: string[];
  principals: number;
  allowed: number;
  dryRun: DryRun;
  warnings: string[];
}

export interface EnforcementState {
  status: EnforcementStatus;
  appliedDdl: string | null;
  rollbackDdl: string | null;
  lastAppliedAt: string | null;
  lastAppliedBy: string | null;
  lastError: string | null;
  secureSchema: string | null;
  secureView: string | null;
  updatedAt: string | null;
}

export interface EnforcementOutcome {
  state: EnforcementState;
  statements: number;
  inserted: number;
  deleted: number;
  notes: string[];
}

export interface EnforcementAuditEntry {
  id: number;
  occurredAt: string;
  actor: string;
  action: 'DRY_RUN' | 'APPLY' | 'ROLLBACK' | 'DIRECT_ACCESS_CHECK';
  outcome:
    | 'REVIEWED'
    | 'APPLIED'
    | 'ROLLED_BACK'
    | 'STALE'
    | 'FAILED'
    | 'REFUSED'
    | 'CHECKED';
  reviewId: string | null;
  statements: number | null;
  rowsInserted: number | null;
  rowsDeleted: number | null;
  detail: string | null;
}

export interface SecureViewHistory {
  assetFqn: string;
  state: EnforcementState | null;
  history: EnforcementAuditEntry[];
}

const BASE = '/v1/enforcement/secure-views';

export async function fetchSecureViewCandidates(q?: string): Promise<SecureViewCandidate[]> {
  const { data } = await apiClient.get<{ data: SecureViewCandidate[] }>(BASE, {
    params: q ? { q } : undefined,
  });
  return data.data;
}

export async function fetchSecureViewHistory(fqn: string): Promise<SecureViewHistory> {
  const { data } = await apiClient.get<SecureViewHistory>(`${BASE}/state`, {
    params: { fqn, limit: 20 },
  });
  return data;
}

export async function dryRunSecureView(assetFqn: string): Promise<SecureViewPreview> {
  const { data } = await apiClient.post<SecureViewPreview>(`${BASE}/dry-run`, { assetFqn });
  return data;
}

export async function applySecureView(
  assetFqn: string,
  reviewId: string,
): Promise<EnforcementOutcome> {
  const { data } = await apiClient.post<EnforcementOutcome>(`${BASE}/apply`, {
    assetFqn,
    reviewId,
  });
  return data;
}

export async function rollbackSecureView(assetFqn: string): Promise<EnforcementOutcome> {
  const { data } = await apiClient.post<EnforcementOutcome>(`${BASE}/rollback`, { assetFqn });
  return data;
}

/** Rows in all three tables, for a one-line summary of a diff. */
export function rowCount(rows: EntitlementRows): number {
  return rows.subscriptions.length + rows.entitlements.length + rows.grants.length;
}
