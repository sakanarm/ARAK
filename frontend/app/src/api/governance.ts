import { apiClient } from './client';

/**
 * The governance vocabulary and the identity cache — the two lists a policy is
 * written against.
 *
 * Both are read-only. OpenMetadata owns the first and Entra the second; the
 * counts beside each value are ours, and they are the reason these screens
 * exist rather than a link back to OpenMetadata.
 */

export interface GovernanceValue {
  fqn: string;
  name: string;
  parentFqn: string | null;
  displayName: string | null;
  description: string | null;
  depth: number;
  provenance: string;
  disabled: boolean;
  mutuallyExclusive: boolean;
  /** Assets and columns carrying this value, inherited ones included. */
  assets: number;
  /** Of those, the ones where somebody attached it directly. */
  directAssets: number;
  /** Policies naming this value in a selector. */
  policies: number;
  children: GovernanceValue[];
}

export interface CustomPropertyDef {
  entityType: string;
  name: string;
  displayName: string | null;
  description: string | null;
  dataType: string;
  enumValues: string | null;
  multiSelect: boolean;
}

export interface Vocabulary {
  classifications: GovernanceValue[];
  glossaries: GovernanceValue[];
  domains: GovernanceValue[];
  dataProducts: GovernanceValue[];
  customProperties: CustomPropertyDef[];
}

export async function fetchVocabulary(): Promise<Vocabulary> {
  const { data } = await apiClient.get<Vocabulary>('/v1/governance/vocabulary');
  return data;
}

// ----------------------------------------------------------------- principals

export interface Principal {
  id: string;
  principalType: 'USER' | 'GROUP' | 'SERVICE';
  username: string;
  email: string | null;
  displayName: string | null;
  source: 'entra' | 'openmetadata' | 'local';
  enabled: boolean;
  attributeCount: number;
  /** People in this group. Zero for a user. */
  memberCount: number;
  /** Groups this principal belongs to. Zero for a group with no parent. */
  groupCount: number;
  /**
   * The first few of those groups, named, so a listing can link to one.
   * Capped by the server; `groupCount` remains the true total.
   */
  groups: GroupRef[];
  appRoles: string[];
}

/** A group named from somebody else's row: enough to show it and open it. */
export interface GroupRef {
  id: string;
  name: string;
}

/** One attribute this principal carries, and which sync put it there. */
export interface PrincipalAttribute {
  key: string;
  value: string;
  source: string;
}

export interface PrincipalDetail {
  principal: Principal;
  attributes: PrincipalAttribute[];
  /** The groups this principal is in. */
  groups: Principal[];
  /** The people in it, when this principal is a group. */
  members: Principal[];
}

export interface AttributeValue {
  value: string;
  principals: number;
}

export interface AttributeKey {
  key: string;
  source: string;
  principals: number;
  values: AttributeValue[];
}

export interface AttributeVocabulary {
  keys: AttributeKey[];
  appRoles: string[];
}

/** One attribute condition: a key alone, or a key pinned to one value. */
export interface AttributeCondition {
  key: string;
  /** Undefined asks who carries the key at all, whatever its value. */
  value?: string;
}

export async function fetchPrincipals(query: {
  type?: string;
  source?: string;
  search?: string;
  /** ANDed, the way a subject rule ANDs its own attribute list. */
  attributes?: AttributeCondition[];
  limit?: number;
}): Promise<Principal[]> {
  const params = new URLSearchParams();
  if (query.type) params.set('type', query.type);
  if (query.source) params.set('source', query.source);
  if (query.search) params.set('q', query.search);
  for (const condition of query.attributes ?? []) {
    params.append(
      'attr',
      condition.value ? `${condition.key}=${condition.value}` : condition.key
    );
  }
  params.set('limit', String(query.limit ?? 200));
  const { data } = await apiClient.get<Principal[]>(`/v1/principals?${params}`);
  return data;
}

/**
 * One principal with its attributes, its groups and — for a group — its
 * members.
 *
 * Addressed by id rather than by username: a username is unique only within a
 * source, so two directories may each hold a `Finance`, and opening a group
 * has to reach the one that was clicked.
 */
export async function fetchPrincipalDetail(
  idOrUsername: string
): Promise<PrincipalDetail> {
  const { data } = await apiClient.get<PrincipalDetail>(
    `/v1/principals/${encodeURIComponent(idOrUsername)}`
  );
  return data;
}

export async function fetchAttributeVocabulary(): Promise<AttributeVocabulary> {
  const { data } = await apiClient.get<AttributeVocabulary>(
    '/v1/principals/attributes'
  );
  return data;
}

// --------------------------------------------------- local accounts and roles

/**
 * The write half of the directory (FR-2.2, FR-2.6).
 *
 * Two different things live here, and the difference decides what the UI may
 * offer. A **local account** is ours to create and edit; an Entra or
 * OpenMetadata one is not, and editing it would be undone by the next sync. An
 * **app role** is ours outright, so it can be granted to anybody whatever
 * directory they came from.
 *
 * A granted role reaches this console at once — `/auth/me` re-reads the table —
 * but reaches *authorisation* only when the affected person signs in again,
 * because the filter reads the roles baked into their token. Screens that use
 * these calls have to say so.
 */

/** One role held by one principal, as the roles screen lists it. */
export interface RoleGrant {
  id: string;
  principalId: string;
  username: string;
  displayName: string | null;
  principalType: 'USER' | 'GROUP' | 'SERVICE';
  source: string;
  enabled: boolean;
  appRole: string;
  /** Set only for DATA_OWNER: the asset FQN the ownership applies to. */
  scopeFqn: string | null;
  grantedBy: string | null;
  grantedAt: string | null;
}

export interface RoleGrants {
  grants: RoleGrant[];
  appRoles: string[];
  /**
   * Enabled holders of a global PLATFORM_ADMIN. At one, the server refuses to
   * revoke or disable the last of them, and the UI says so before the attempt.
   */
  globalAdminCount: number;
}

export interface NewLocalPrincipal {
  username: string;
  displayName?: string | null;
  email?: string | null;
  principalType: 'USER' | 'SERVICE';
  password: string;
  roles: { appRole: string; scopeFqn?: string | null }[];
}

export async function fetchRoleGrants(): Promise<RoleGrants> {
  const { data } = await apiClient.get<RoleGrants>('/v1/principals/roles');
  return data;
}

export async function createLocalPrincipal(
  input: NewLocalPrincipal
): Promise<PrincipalDetail> {
  const { data } = await apiClient.post<PrincipalDetail>('/v1/principals', input);
  return data;
}

/** Answers `changed: false` when the principal already held the role. */
export async function grantAppRole(
  principalId: string,
  change: { appRole: string; scopeFqn?: string | null; reason?: string | null }
): Promise<boolean> {
  const { data } = await apiClient.post<{ changed: boolean }>(
    `/v1/principals/${encodeURIComponent(principalId)}/roles`,
    change
  );
  return data.changed;
}

/**
 * Withdraws a role.
 *
 * The role and scope travel as query parameters because they identify what is
 * being deleted, and a DELETE body is read inconsistently on the way.
 */
export async function revokeAppRole(
  principalId: string,
  change: { appRole: string; scopeFqn?: string | null; reason?: string | null }
): Promise<boolean> {
  const params = new URLSearchParams({ role: change.appRole });
  if (change.scopeFqn) params.set('scope', change.scopeFqn);
  if (change.reason) params.set('reason', change.reason);
  const { data } = await apiClient.delete<{ changed: boolean }>(
    `/v1/principals/${encodeURIComponent(principalId)}/roles?${params}`
  );
  return data.changed;
}

export async function setPrincipalEnabled(
  principalId: string,
  enabled: boolean,
  reason?: string
): Promise<PrincipalDetail> {
  const { data } = await apiClient.post<PrincipalDetail>(
    `/v1/principals/${encodeURIComponent(principalId)}/enabled`,
    { enabled, reason }
  );
  return data;
}

/** The holder must choose a new one at their next sign-in. */
export async function resetPrincipalPassword(
  principalId: string,
  password: string
): Promise<void> {
  await apiClient.post(
    `/v1/principals/${encodeURIComponent(principalId)}/password`,
    { password }
  );
}

/** Flattens a nested vocabulary tree into pickable options, depth-first. */
export function flatten(values: GovernanceValue[]): GovernanceValue[] {
  const out: GovernanceValue[] = [];
  for (const value of values) {
    out.push(value);
    out.push(...flatten(value.children));
  }
  return out;
}
