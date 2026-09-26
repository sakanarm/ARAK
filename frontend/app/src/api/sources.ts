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

/**
 * An engine id as the server spells it, such as `POSTGRES`.
 *
 * Deliberately a string rather than a union of the two we support today. The
 * union meant six screens each carried their own copy of the list, so adding an
 * engine was one backend change plus six frontend ones — and missing any of the
 * six produced a dropdown offering a source the backend would then refuse to
 * connect to. The list now comes from `fetchEngines()`; the one place a name is
 * still written down is the capability notes in the policy builder, which are
 * prose about a specific product rather than a list of products.
 */
export type SourceEngine = string;

/** One engine this build can govern, as the server describes it. */
export interface SourceEngineInfo {
  id: SourceEngine;
  displayName: string;
  defaultPort: number;
  /** False where a database is the schema, as in MySQL, which shortens the FQN. */
  supportsSchemas: boolean;
  /**
   * What the query proxy can express on this engine — not what the engine can
   * enforce natively. A policy needing a treatment missing from this list is
   * refused rather than run unprotected, so the console can warn before a
   * source is pointed at the proxy instead of after.
   */
  proxyCapabilities: string[];
}

export type EnforcementMode =
  | 'NATIVE_CONFIG'
  | 'SECURE_VIEW'
  | 'PROXY'
  | 'NONE';

/**
 * Which tables an import reads: every one, or only those named, less the
 * names always left out.
 *
 * A rule compares text, never a pattern. A rule with a dot in it is compared
 * with `schema.table`, which is how a whole schema is scoped (`staging.`);
 * without one it is compared with the table name. Case never matters.
 *
 * The scope decides what the catalog reads and nothing more. A table left out
 * is not hidden from anybody, and enforcement still reads every column of the
 * table it is enforcing.
 */
export type ScopeMode = 'ALL' | 'ONLY';
export type ScopeMatch = 'STARTS_WITH' | 'ENDS_WITH' | 'CONTAINS' | 'EQUALS';

export interface ScopeRule {
  match: ScopeMatch;
  value: string;
}

export interface TableScope {
  mode: ScopeMode;
  include: ScopeRule[];
  exclude: ScopeRule[];
}

export const EVERY_TABLE: TableScope = { mode: 'ALL', include: [], exclude: [] };

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
  /** Never null: a source that reads every table says so. */
  tableScope: TableScope;
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
  /** Left out, an edit keeps the stored scope. */
  tableScope?: TableScope | null;
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

/**
 * The engines this build can govern.
 *
 * Cache it generously: it changes when the backend is deployed, never while
 * somebody is filling in a form.
 */
export async function fetchEngines(): Promise<SourceEngineInfo[]> {
  const { data } = await apiClient.get<SourceEngineInfo[]>('/v1/sources/engines');
  return data;
}

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

/** What a scope would read, counted against the database itself. */
export interface ScopePreview {
  total: number;
  inScope: number;
  excluded: number;
  /** The first few names on each side; the counts above are exact. */
  inScopeSample: string[];
  excludedSample: string[];
}

/**
 * Lists the tables the form's login can see and says which the scope keeps.
 *
 * Names only — no column is read and nothing is written — over the same
 * connection Test makes, so it answers for what is on screen rather than what
 * was last saved.
 */
export async function previewScope(
  input: Partial<SourceInput> & { id?: string }
): Promise<ScopePreview> {
  const { data } = await apiClient.post<ScopePreview>('/v1/sources/scope-preview', input);
  return data;
}

/** What one import of a source's own catalog found. */
export interface ImportReport {
  source: string;
  tables: number;
  columns: number;
  newTables: number;
  /** Named, because each is unprotected until a policy covers it. */
  newColumns: string[];
  /** In scope, catalogued before, and gone from the database now. */
  missingTables: string[];
  /** How many tables the scope left out of this run. */
  excluded: number;
  /** Catalogued before and now outside the scope: kept, and not called gone. */
  outOfScope: string[];
}

/** Reads the source's catalog into Arak, within its table scope. */
export async function importSourceCatalog(id: string): Promise<ImportReport> {
  const { data } = await apiClient.post<ImportReport>(`/v1/sources/${id}/introspect`);
  return data;
}

export async function deleteSource(id: string): Promise<void> {
  await apiClient.delete(`/v1/sources/${id}`);
}
