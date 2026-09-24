import { apiClient } from './client';

/**
 * The source registry (FR-6.0a).
 *
 * A source is where enforcement lands, so this is the screen that decides
 * whether a policy becomes a security policy on the original table, a `_secure`
 * view beside it, or a rewrite in the query proxy.
 *
 * `credentialRef` is a pointer to a secret and never a secret. The server
 * refuses anything that is not one of the schemes below, which is what stops
 * this console becoming the place somebody pastes a production password.
 */

export type SourceEngine = 'POSTGRES' | 'SQLSERVER';

export type EnforcementMode =
  | 'NATIVE_CONFIG'
  | 'SECURE_VIEW'
  | 'PROXY'
  | 'NONE';

export interface Source {
  id: string;
  name: string;
  engine: SourceEngine;
  engineVersion: string | null;
  host: string;
  port: number;
  defaultDatabase: string | null;
  credentialRef: string;
  defaultEnforcementMode: EnforcementMode;
  omServiceFqn: string | null;
  secureSchema: string;
  secureObjectPattern: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  /** Tables and views the crawl has attributed to this source. */
  assetCount: number;
}

export interface SourceInput {
  name: string;
  engine: SourceEngine;
  /**
   * Carried through on an edit, never typed. The server writes this column from
   * whatever the caller sends, so omitting it would erase what the last probe
   * recorded — and the capability matrix reads it to know whether a column-level
   * UNMASK is possible at all.
   */
  engineVersion?: string | null;
  host: string;
  port: number | null;
  defaultDatabase: string | null;
  credentialRef: string;
  defaultEnforcementMode: EnforcementMode;
  omServiceFqn: string | null;
  secureSchema: string;
  secureObjectPattern: string;
  enabled: boolean;
  /**
   * A username and password typed into the form rather than pointed at.
   *
   * Sent once and never read back: the server seals them into `credentialRef`
   * and answers `fernet:stored` from then on, so there is no field here for
   * receiving them again. A form that omits both leaves whatever is stored
   * alone.
   */
  username?: string | null;
  password?: string | null;
}

export interface ProbeResult {
  reachable: boolean;
  engineVersion: string;
  productName: string;
  message: string;
  millis: number;
}

/**
 * The pointer schemes the server will accept.
 *
 * `fernet:` is missing on purpose. It is what a typed-in credential is stored
 * as, not something anyone should be typing, and offering it here would invite
 * somebody to paste a ciphertext they got from somewhere else.
 */
export const CREDENTIAL_SCHEMES = [
  'vault://',
  'azurekeyvault://',
  'env:',
] as const;

/** What the server serves in place of a stored credential. */
export const SEALED_CREDENTIAL = 'fernet:stored';

/** Whether this source's credential was typed in rather than pointed at. */
export function isSealed(credentialRef: string): boolean {
  return credentialRef.toLowerCase().startsWith('fernet:');
}

export const ENFORCEMENT_MODES: {
  value: EnforcementMode;
  label: string;
  what: string;
}[] = [
  {
    value: 'NONE',
    label: 'Not enforced yet',
    what: 'Catalogued and policed on paper. Nothing is applied to the database. Every source starts here.',
  },
  {
    value: 'SECURE_VIEW',
    label: 'Secure view',
    what: 'A generated view beside the table, with the base table revoked. Covers row filters, column masks and cell masks.',
  },
  {
    value: 'NATIVE_CONFIG',
    label: 'Native source config',
    what: 'Row-level security and masking on the original object, so queries keep the table name. Alters production objects.',
  },
  {
    value: 'PROXY',
    label: 'Query proxy',
    what: 'Rewritten at the query API. Touches nothing in the database, but only protects traffic that comes through it.',
  },
];

export async function fetchSources(): Promise<Source[]> {
  const { data } = await apiClient.get<Source[]>('/v1/sources');
  return data;
}

export async function fetchSource(id: string): Promise<Source> {
  const { data } = await apiClient.get<Source>(`/v1/sources/${id}`);
  return data;
}

export async function createSource(input: SourceInput): Promise<Source> {
  const { data } = await apiClient.post<Source>('/v1/sources', input);
  return data;
}

export async function updateSource(
  id: string,
  input: SourceInput
): Promise<Source> {
  const { data } = await apiClient.put<Source>(`/v1/sources/${id}`, input);
  return data;
}

export async function setSourceEnabled(
  id: string,
  enabled: boolean
): Promise<Source> {
  const { data } = await apiClient.post<Source>(`/v1/sources/${id}/enabled`, {
    enabled,
  });
  return data;
}

/** Opens one read-only connection and records the engine version it reports. */
export async function testSource(id: string): Promise<ProbeResult> {
  const { data } = await apiClient.post<ProbeResult>(`/v1/sources/${id}/test`);
  return data;
}

/**
 * Tries a connection for a source that has not been saved.
 *
 * Takes the form as it stands, so the answer is about what is on screen rather
 * than about what was stored the last time Save worked. `id` is optional and is
 * how an edit reuses the saved password while changing a port: the server falls
 * back to the stored credential when the form has not been given a new one.
 */
export async function testSourceTarget(
  input: Partial<SourceInput> & { id?: string }
): Promise<ProbeResult> {
  const { data } = await apiClient.post<ProbeResult>('/v1/sources/test', input);
  return data;
}

export async function deleteSource(id: string): Promise<void> {
  await apiClient.delete(`/v1/sources/${id}`);
}
