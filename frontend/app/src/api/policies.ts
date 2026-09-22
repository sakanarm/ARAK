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
  | 'CANNOT_LOOSEN'
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
  limit?: number;
  offset?: number;
} = {}): Promise<StoredPolicy[]> {
  const params = new URLSearchParams();
  if (query.state) params.set('state', query.state);
  if (query.type) params.set('type', query.type);
  if (query.scopeLevel) params.set('scopeLevel', query.scopeLevel);
  params.set('limit', String(query.limit ?? 100));
  params.set('offset', String(query.offset ?? 0));
  const { data } = await apiClient.get<StoredPolicy[]>(`/v1/policies?${params}`);
  return data;
}

export async function fetchPolicy(id: string): Promise<StoredPolicy> {
  const { data } = await apiClient.get<StoredPolicy>(`/v1/policies/${id}`);
  return data;
}

export async function fetchPolicyVersions(id: string): Promise<StoredPolicy[]> {
  const { data } = await apiClient.get<StoredPolicy[]>(
    `/v1/policies/${id}/versions`
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

/** The other policies bound to the same targets, and what happens there. */
export async function fetchPolicyConflicts(
  id: string
): Promise<PolicyOverlap[]> {
  const { data } = await apiClient.get<PolicyOverlap[]>(
    `/v1/policies/${id}/conflicts`
  );
  return data;
}

/** One policy reaching an asset, and where inside it the policy lands. */
export interface AppliedPolicy {
  policy: StoredPolicy;
  matchReason: Record<string, unknown>;
  columns: PolicyTarget[];
}

/** The policies governing one asset, with the columns each one reaches. */
export async function fetchPoliciesForAsset(
  fqn: string,
  environment = 'prod'
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
  environment = 'prod'
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
