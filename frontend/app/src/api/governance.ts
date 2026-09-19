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
  memberCount: number;
  appRoles: string[];
}

export interface AttributeKey {
  key: string;
  source: string;
  principals: number;
  values: string[];
}

export interface AttributeVocabulary {
  keys: AttributeKey[];
  appRoles: string[];
}

export async function fetchPrincipals(query: {
  type?: string;
  source?: string;
  search?: string;
  limit?: number;
}): Promise<Principal[]> {
  const params = new URLSearchParams();
  if (query.type) params.set('type', query.type);
  if (query.source) params.set('source', query.source);
  if (query.search) params.set('q', query.search);
  params.set('limit', String(query.limit ?? 200));
  const { data } = await apiClient.get<Principal[]>(`/v1/principals?${params}`);
  return data;
}

export async function fetchAttributeVocabulary(): Promise<AttributeVocabulary> {
  const { data } = await apiClient.get<AttributeVocabulary>(
    '/v1/principals/attributes'
  );
  return data;
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
