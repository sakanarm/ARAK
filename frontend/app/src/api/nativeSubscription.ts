import { apiClient } from './client';

/**
 * Enforcement mode 5.1.1 for subscription policies on PostgreSQL: one role
 * per policy and source, whose members are the logins of the people the
 * policy lets in (FR-6.2).
 *
 * As with the secure view, an apply carries the id of a plan and nothing else.
 * The server kept what it showed; it re-reads the source and refuses when
 * either side moved since.
 *
 * The push account is written here and never read back: the server answers
 * with whether one is set, by which scheme, when and by whom.
 */

export type NativeLevel = 'BROWSE' | 'READ';

export type NativeStatus = 'PENDING' | 'APPLIED' | 'DRIFTED' | 'FAILED' | 'ROLLED_BACK';

export type NativeStep =
  | 'CREATE_ROLE'
  | 'RESET_ROLE'
  | 'COMMENT_ROLE'
  | 'REVOKE_MEMBER'
  | 'REVOKE_SELECT'
  | 'REVOKE_USAGE'
  | 'REVOKE_CONNECT'
  | 'GRANT_CONNECT'
  | 'GRANT_USAGE'
  | 'GRANT_SELECT'
  | 'GRANT_MEMBER'
  | 'DROP_ROLE';

export interface NativeChange {
  step: NativeStep;
  target: string;
  sql: string;
}

export interface NativeCredentialInfo {
  configured: boolean;
  scheme: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface NativeSource {
  id: string;
  name: string;
  engine: string;
  enabled: boolean;
  database: string;
  /** The connection's enforcement mode; roles are pushed only under NATIVE_CONFIG. */
  mode: string | null;
  credential: NativeCredentialInfo;
  logins: number;
  roles: number;
}

export interface NativeSources {
  data: NativeSource[];
  sweepMinutes: number;
  populationLimit: number;
  decisionLimit: number;
}

export interface NativeLogin {
  principalId: string;
  username: string;
  displayName: string | null;
  principalType: string;
  login: string;
}

export interface NativeRole {
  id: string;
  policyId: string;
  dataSourceId: string;
  roleName: string;
  databaseName: string;
  accessLevel: NativeLevel;
  status: NativeStatus;
  appliedScript: string | null;
  members: string[];
  tables: string[];
  lastAppliedAt: string | null;
  lastAppliedBy: string | null;
  lastCheckedAt: string | null;
  lastError: string | null;
  detail: string | null;
  updatedAt: string | null;
}

export interface NativePolicy {
  policyId: string;
  name: string;
  policyType: string;
  effect: string;
  lifecycleState: string;
  environment: string;
  /** Why this policy cannot be a role; empty when it can. */
  unsupported: string[];
  roles: NativeRole[];
}

export interface NativeMember {
  login: string;
  people: string[];
}

export interface NativeExcluded {
  person: string;
  login: string;
  reasons: string[];
  /** The tables they would have had through the role and do not get. */
  lost: string[];
}

export interface NativePreview {
  reviewId: string;
  expiresAt: string;
  policyId: string;
  policyName: string;
  lifecycleState: string;
  dataSourceId: string;
  sourceName: string;
  role: string;
  database: string;
  level: NativeLevel;
  tables: string[];
  members: NativeMember[];
  excluded: NativeExcluded[];
  unmapped: string[];
  sharedRefused: string[];
  exemptions: string[];
  changes: NativeChange[];
  applyScript: string;
  rollbackScript: string;
  blockers: string[];
  warnings: string[];
  otherReaders: string[];
  satisfied: boolean;
  principals: number;
  serverVersionNum: number;
}

export interface NativeOutcome {
  role: NativeRole;
  statements: number;
  script: string;
  warnings: string[];
}

export interface NativeCheck {
  role: NativeRole;
  status: NativeStatus;
  drifted: boolean;
  satisfied: boolean;
  changes: NativeChange[];
  applyScript: string;
  blockers: string[];
  warnings: string[];
}

export interface NativeRollbackPreview {
  role: string;
  database: string;
  changes: NativeChange[];
  script: string;
  notes: string[];
}

export interface NativeAuditEntry {
  id: number;
  occurredAt: string;
  actor: string;
  action: string;
  outcome: string;
  reviewId: string | null;
  statements: number | null;
  detail: string | null;
}

const BASE = '/v1/native-subscription';

const source = (id: string) => `${BASE}/sources/${encodeURIComponent(id)}`;
const policy = (id: string) => `${BASE}/policies/${encodeURIComponent(id)}`;

export async function fetchNativeSources(): Promise<NativeSources> {
  const { data } = await apiClient.get<NativeSources>(`${BASE}/sources`);
  return data;
}

/** A typed account is sealed by the server; a reference is stored as given. */
export type NativeCredentialInput =
  | { username: string; password: string }
  | { credentialRef: string };

export async function setNativeCredential(
  sourceId: string,
  input: NativeCredentialInput,
): Promise<NativeCredentialInfo> {
  const { data } = await apiClient.put<NativeCredentialInfo>(
    `${source(sourceId)}/credential`,
    input,
  );
  return data;
}

export async function deleteNativeCredential(sourceId: string): Promise<boolean> {
  const { data } = await apiClient.delete<{ removed: boolean }>(`${source(sourceId)}/credential`);
  return data.removed;
}

export async function fetchNativeLogins(sourceId: string): Promise<NativeLogin[]> {
  const { data } = await apiClient.get<{ data: NativeLogin[] }>(`${source(sourceId)}/logins`);
  return data.data;
}

export async function mapNativeLogin(
  sourceId: string,
  username: string,
  login: string,
): Promise<NativeLogin> {
  const { data } = await apiClient.put<NativeLogin>(`${source(sourceId)}/logins`, {
    username,
    login,
  });
  return data;
}

export async function unmapNativeLogin(sourceId: string, username: string): Promise<string[]> {
  const { data } = await apiClient.delete<{ removed: string[] }>(
    `${source(sourceId)}/logins/${encodeURIComponent(username)}`,
  );
  return data.removed;
}

export async function fetchNativeSourceHistory(sourceId: string): Promise<NativeAuditEntry[]> {
  const { data } = await apiClient.get<{ data: NativeAuditEntry[] }>(
    `${source(sourceId)}/history`,
    { params: { limit: 20 } },
  );
  return data.data;
}

export async function fetchNativePolicy(policyId: string): Promise<NativePolicy> {
  const { data } = await apiClient.get<NativePolicy>(policy(policyId));
  return data;
}

/** A level of null keeps the one the role was applied at, else Read. */
export async function planNative(
  policyId: string,
  sourceId: string,
  level: NativeLevel | null,
): Promise<NativePreview> {
  const { data } = await apiClient.post<NativePreview>(`${policy(policyId)}/plan`, {
    sourceId,
    level,
  });
  return data;
}

export async function applyNative(
  policyId: string,
  sourceId: string,
  reviewId: string,
): Promise<NativeOutcome> {
  const { data } = await apiClient.post<NativeOutcome>(`${policy(policyId)}/apply`, {
    sourceId,
    reviewId,
  });
  return data;
}

export async function checkNative(policyId: string, sourceId: string): Promise<NativeCheck> {
  const { data } = await apiClient.post<NativeCheck>(`${policy(policyId)}/check`, { sourceId });
  return data;
}

export async function planNativeRollback(
  policyId: string,
  sourceId: string,
): Promise<NativeRollbackPreview> {
  const { data } = await apiClient.post<NativeRollbackPreview>(
    `${policy(policyId)}/rollback-plan`,
    { sourceId },
  );
  return data;
}

export async function rollbackNative(policyId: string, sourceId: string): Promise<NativeOutcome> {
  const { data } = await apiClient.post<NativeOutcome>(`${policy(policyId)}/rollback`, {
    sourceId,
  });
  return data;
}

export async function fetchNativeHistory(
  policyId: string,
  sourceId: string,
): Promise<NativeAuditEntry[]> {
  const { data } = await apiClient.get<{ data: NativeAuditEntry[] }>(
    `${policy(policyId)}/history`,
    { params: { sourceId, limit: 20 } },
  );
  return data.data;
}
