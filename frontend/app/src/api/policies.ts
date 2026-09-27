import { apiClient } from './client';
import type { Policy } from '../generated/entity/policy/policy';

/**
 * The policy API (FR-3, FR-9).
 *
 * The wire shape is the generated {@link Policy} — the same JSON Schema the
 * engine's Java classes come from — wrapped in the row the store keeps beside
 * it. Nothing here redefines a policy field; if the builder needs a new one it
 * is added to the schema in backend/dac-spec and both sides regenerate.
 */

export interface StoredPolicy {
  id: string;
  document: Policy;
  lifecycleState:
    | 'DRAFT'
    | 'PENDING_APPROVAL'
    | 'ACTIVE'
    | 'DISABLED'
    | 'ARCHIVED';
  environment: string;
  version: number;
  createdBy: string;
  updatedBy: string;
  updatedAt: string;
}

/** One table or column a policy resolved onto. */
export interface PolicyTarget {
  fqn: string;
  kind: 'TABLE' | 'COLUMN';
  name: string | null;
  parentFqn: string | null;
  dataType: string | null;
  matchReason: Record<string, unknown>;
  resolvedAt: string | null;
}

/** What a policy currently lands on. `sample` is capped; the counts are not. */
export interface PolicyCoverage {
  tableCount: number;
  columnCount: number;
  sample: PolicyTarget[];
  truncated: boolean;
  resolvedAt: string | null;
}

/**
 * How a policy sharing targets with this one affects it.
 *
 * `BLOCKED_BY` is the only one that means this policy is dead where they meet;
 * the rest are layering working as designed, in descending order of how much
 * they change the outcome.
 */
export type PolicyRelation =
  | 'BLOCKED_BY'
  | 'BLOCKS'
  | 'MASK_OVERLAP'
  | 'NARROWS'
  | 'COMPOSES';

export interface PolicyOverlap {
  policyId: string;
  name: string;
  displayName: string | null;
  policyType: 'SUBSCRIPTION' | 'DATA';
  effect: 'ALLOW' | 'DENY';
  scopeLevel: string;
  scopeFqn: string | null;
  lifecycleState: string;
  environment: string;
  allowLocalOverride: boolean;
  sharedTargets: number;
  examples: string[];
  relation: PolicyRelation;
  explanation: string;
  /** Set when that policy outranks this one and forbids relaxing it. */
  overrideNote: string | null;
}

export interface BindingResult {
  policyId: string;
  scanned: number;
  matched: number;
  added: number;
  removed: number;
  changed: boolean;
}

export async function fetchPolicies(query: {
  state?: string;
  type?: string;
  scopeLevel?: string;
  q?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<StoredPolicy[]> {
  const params = new URLSearchParams();
  if (query.state) params.set('state', query.state);
  if (query.type) params.set('type', query.type);
  if (query.scopeLevel) params.set('scopeLevel', query.scopeLevel);
  if (query.q?.trim()) params.set('q', query.q.trim());
  params.set('limit', String(query.limit ?? 100));
  params.set('offset', String(query.offset ?? 0));
  const { data } = await apiClient.get<StoredPolicy[]>(`/v1/policies?${params}`);
  return data;
}

/**
 * How many policies that filter matches.
 *
 * Asked separately from the page, and cached separately: clicking through
 * pages must not re-count, and a count is what lets the pager show numbered
 * pages rather than a Next button that may or may not do anything.
 */
export async function countPolicies(query: {
  state?: string;
  type?: string;
  scopeLevel?: string;
  q?: string;
} = {}): Promise<number> {
  const params = new URLSearchParams();
  if (query.state) params.set('state', query.state);
  if (query.type) params.set('type', query.type);
  if (query.scopeLevel) params.set('scopeLevel', query.scopeLevel);
  if (query.q?.trim()) params.set('q', query.q.trim());
  const { data } = await apiClient.get<{ total: number }>(
    `/v1/policies/count?${params}`
  );
  return data.total;
}

export async function fetchPolicy(id: string): Promise<StoredPolicy> {
  const { data } = await apiClient.get<StoredPolicy>(`/v1/policies/${id}`);
  return data;
}

/**
 * What produced a version (FR-9.2, FR-8.1).
 *
 * `ROLLBACK` is a new version that copies an older one, never the older one
 * brought back: history only grows, so the version a restore replaced is still
 * there to restore in turn.
 */
export type PolicyAction =
  | 'CREATE'
  | 'UPDATE'
  | 'SUBMIT'
  | 'RETURN'
  | 'PUBLISH'
  | 'DISABLE'
  | 'ARCHIVE'
  | 'ROLLBACK';

/** One saved version of a policy, and who made it and why. */
export interface PolicyRevision {
  policyId: string;
  version: number;
  document: Policy;
  lifecycleState: StoredPolicy['lifecycleState'];
  changedBy: string;
  changeReason: string | null;
  changedAt: string;
  action: PolicyAction;
  /** Set on a `ROLLBACK`: the version whose rules it copied. */
  restoredFrom: number | null;
}

/** Every version, newest first. */
export async function fetchPolicyVersions(id: string): Promise<PolicyRevision[]> {
  const { data } = await apiClient.get<PolicyRevision[]>(
    `/v1/policies/${id}/versions`
  );
  return data;
}

/**
 * What putting an older version's rules back would change, measured the same
 * way as {@link fetchPolicyImpact} but between the current version and that one.
 */
export async function fetchRollbackImpact(
  id: string,
  version: number
): Promise<PolicyImpact> {
  const { data } = await apiClient.get<PolicyImpact>(
    `/v1/policies/${id}/versions/${version}/impact`
  );
  return data;
}

/**
 * Saves an older version's rules as the next version.
 *
 * Like {@link updatePolicy} it names the version the screen is looking at, so a
 * restore made against a page somebody else has since changed is refused
 * rather than undoing their change unseen. The reason is required.
 */
export async function rollbackPolicy(
  id: string,
  version: number,
  expectedVersion: number,
  reason: string
): Promise<StoredPolicy> {
  const { data } = await apiClient.post<StoredPolicy>(
    `/v1/policies/${id}/rollback/${version}`,
    { expectedVersion, reason }
  );
  return data;
}

/** The tables and columns this policy actually landed on. */
export async function fetchPolicyCoverage(id: string): Promise<PolicyCoverage> {
  const { data } = await apiClient.get<PolicyCoverage>(
    `/v1/policies/${id}/bindings`
  );
  return data;
}

/**
 * What changes for real people if this policy is switched on (FR-5.3).
 *
 * Every field is a counterfactual, not a count of bindings: the backend
 * composes each person's decision on each bound table twice, once with this
 * policy in the stack and once without, and reports only the differences. A
 * policy that grants what something above it already grants therefore reports
 * nobody affected even though it binds to plenty.
 *
 * `sampled` says the run was capped. When it is true the counts are floors —
 * the screen must say "at least", never a bare number.
 */
export type PolicyImpactChange =
  | 'LOSES_ACCESS'
  | 'GAINS_ACCESS'
  | 'CHANGED'
  | 'SEES_LESS'
  | 'SEES_MORE'
  | 'UNCHANGED';

export interface PolicyImpactTable {
  assetFqn: string;
  change: PolicyImpactChange;
  detail: string;
}

export interface PolicyImpactPrincipal {
  principal: string;
  change: PolicyImpactChange;
  tablesAffected: number;
  tables: PolicyImpactTable[];
}

export interface PolicyImpact {
  policyId: string;
  policyName: string;
  /** True when the policy is already in force — the report then reads backwards. */
  candidateActive: boolean;
  environment: string;
  tablesBound: number;
  tablesMeasured: number;
  principalsKnown: number;
  principalsMeasured: number;
  sampled: boolean;
  principalsAffected: number;
  tablesAffected: number;
  byChange: Partial<Record<PolicyImpactChange, number>>;
  principals: PolicyImpactPrincipal[];
  principalsTruncated: boolean;
  measuredAt: string;
}

/** The other policies bound to the same targets, and what happens there. */
export async function fetchPolicyConflicts(
  id: string
): Promise<PolicyOverlap[]> {
  const { data } = await apiClient.get<PolicyOverlap[]>(
    `/v1/policies/${id}/conflicts`
  );
  return data;
}

/** Who this policy changes things for, and how (FR-5.3). */
export async function fetchPolicyImpact(id: string): Promise<PolicyImpact> {
  const { data } = await apiClient.get<PolicyImpact>(
    `/v1/policies/${id}/impact`
  );
  return data;
}

/**
 * The environment the engine decides in when a caller names none.
 *
 * It mirrors `DecisionService.DEFAULT_ENVIRONMENT` on the backend, and it is a
 * constant rather than a literal in each caller because the whole class of bug
 * it exists to prevent is one copy of it drifting: a policy authored into one
 * environment and enforced from another is invisible rather than wrong, which
 * is the harder kind to notice.
 */
export const ENFORCED_ENVIRONMENT = 'prod';

/** One policy reaching an asset, and where inside it the policy lands. */
export interface AppliedPolicy {
  policy: StoredPolicy;
  matchReason: Record<string, unknown>;
  columns: PolicyTarget[];
}

/** The policies governing one asset, with the columns each one reaches. */
export async function fetchPoliciesForAsset(
  fqn: string,
  environment = ENFORCED_ENVIRONMENT
): Promise<AppliedPolicy[]> {
  const { data } = await apiClient.get<AppliedPolicy[]>(
    `/v1/policies/for-asset/${encodeURI(fqn)}`,
    { params: { environment } }
  );
  return data;
}

/**
 * Every active policy reaching an asset, outermost layer first (FR-3.1.5).
 *
 * The default is the environment the engine decides in, not the one the
 * builder opens on. Those differ, and defaulting to the builder's would answer
 * "what governs this table" with a list that is never enforced.
 */
export async function fetchPoliciesAffecting(
  fqn: string,
  environment = ENFORCED_ENVIRONMENT
): Promise<StoredPolicy[]> {
  const { data } = await apiClient.get<StoredPolicy[]>(
    `/v1/policies/affecting/${encodeURIComponent(fqn)}?environment=${environment}`
  );
  return data;
}

export async function createPolicy(document: Policy): Promise<StoredPolicy> {
  const { data } = await apiClient.post<StoredPolicy>('/v1/policies', document);
  return data;
}

/**
 * Saves an edit against the version that was loaded.
 *
 * The version is not optional and never read from the document: it is the
 * answer to "has anyone changed this since I opened it", and a 409 here is the
 * screen finding out in time rather than overwriting somebody's restriction.
 */
export async function updatePolicy(
  id: string,
  document: Policy,
  expectedVersion: number,
  reason?: string
): Promise<StoredPolicy> {
  const params = new URLSearchParams({ version: String(expectedVersion) });
  if (reason) params.set('reason', reason);
  const { data } = await apiClient.put<StoredPolicy>(
    `/v1/policies/${id}?${params}`,
    document
  );
  return data;
}

export async function transitionPolicy(
  id: string,
  state: string,
  reason?: string
): Promise<StoredPolicy> {
  const { data } = await apiClient.post<StoredPolicy>(
    `/v1/policies/${id}/lifecycle`,
    { state, reason }
  );
  return data;
}

/** Re-resolves the selector against the estate, and reports what moved. */
export async function resolveBindings(id: string): Promise<BindingResult> {
  const { data } = await apiClient.post<BindingResult>(
    `/v1/policies/${id}/bindings/resolve`,
    {}
  );
  return data;
}

export type { Policy };
