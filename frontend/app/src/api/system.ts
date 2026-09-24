import { apiClient } from './client';

/**
 * The state of the OpenMetadata crawl (FR-1.5).
 *
 * Every screen in this console reads a cache. The one number that says whether
 * to trust it is when that cache was last filled, so it belongs in the header
 * rather than buried on a status page nobody opens until something is already
 * wrong.
 *
 * `GET /v1/sync/openmetadata` is admin-only, so callers must gate on the role
 * rather than letting a 403 render as an error to everyone else.
 */

export type SyncStatus =
  | 'NEVER_RUN'
  | 'IDLE'
  | 'RUNNING'
  | 'FAILED'
  | 'SUCCEEDED';

export interface SyncState {
  source: string;
  /** Widened: the server may add a state before this type is regenerated. */
  status: SyncStatus | string;
  /** Epoch millis of the newest change event consumed. */
  lastEventTs?: number | null;
  lastFullCrawlAt?: string | null;
  lastReconcileAt?: string | null;
  lastError?: string | null;
  updatedAt?: string | null;
}

export async function fetchSyncStatus(): Promise<SyncState> {
  const { data } = await apiClient.get<SyncState>('/v1/sync/openmetadata');
  return data;
}

export interface CrawlResult {
  classifications?: number;
  tags?: number;
  glossaryTerms?: number;
  domains?: number;
  tables?: number;
  columns?: number;
  facets?: number;
}

/** Runs a full crawl now. Answers 409 if one is already under way. */
export async function startCrawl(): Promise<CrawlResult> {
  const { data } = await apiClient.post<CrawlResult>('/v1/sync/openmetadata');
  return data;
}

/**
 * The OpenMetadata connection as the service is running it.
 *
 * Both secrets arrive as booleans and nothing else — not a value, not a prefix,
 * not a length. That much has not changed now that the connection is editable:
 * what a platform administrator may do is replace a secret, never read one
 * back, so a compromised session cannot exfiltrate the credential it is allowed
 * to overwrite.
 *
 * `configSource` says whether this came from the file or from somebody typing
 * it on the screen, because "why is it pointed there" has two different answers
 * and only one of them is fixable from the browser.
 */
export interface OpenMetadataSettings {
  baseUrl: string;
  expectedVersion: string;
  failOnVersionMismatch: boolean;
  connectTimeoutMs: number;
  readTimeoutMs: number;
  tokenConfigured: boolean;
  tokenSource: string;
  webhookSecretConfigured: boolean;
  webhookSecretSource: string;
  webhookPath: string;
  webhookSignatureHeader: string;
  pollEnabled: boolean;
  pollIntervalSeconds: number;
  maxEventsPerPoll: number;
  reconcileEnabled: boolean;
  reconcileAt: string;
  reconcileZone: string;
  editable: boolean;
  /** Where the value in force came from, for display. */
  source: string;
  /** True while nobody has changed it from what the file said. */
  isDefault: boolean;
  updatedAt: string | null;
  updatedBy: string | null;
  configSource: string;
  sync: SyncState;
}

/**
 * A change to the connection.
 *
 * Every field is optional and an omitted one keeps what is in force. That is
 * what makes moving the URL possible without re-typing a token, and it is why
 * the form must send `undefined` rather than `''` for a secret the operator
 * left alone: an empty string would read as "clear it".
 */
export interface OpenMetadataEdit {
  baseUrl?: string;
  jwtToken?: string;
  webhookSecret?: string;
  expectedVersion?: string;
  failOnVersionMismatch?: boolean;
  connectTimeoutMs?: number;
  readTimeoutMs?: number;
  reason?: string;
}

export interface ConnectionProbe {
  baseUrl: string;
  reachable: boolean;
  version: string | null;
  expectedVersion: string;
  versionMatches: boolean;
  tookMs: number;
  message: string;
}

export async function fetchOpenMetadataSettings(): Promise<OpenMetadataSettings> {
  const { data } = await apiClient.get<OpenMetadataSettings>(
    '/v1/settings/openmetadata'
  );
  return data;
}

/**
 * Points the platform at an instance.
 *
 * The response is the connection as it now stands, so the caller never has to
 * guess what the server made of a partial edit.
 */
export async function saveOpenMetadataSettings(
  edit: OpenMetadataEdit
): Promise<OpenMetadataSettings> {
  const { data } = await apiClient.put<OpenMetadataSettings>(
    '/v1/settings/openmetadata',
    edit
  );
  return data;
}

/**
 * Asks an instance who it is.
 *
 * With a candidate it asks that one instead of the one in force, which is the
 * whole point: an address that cannot be probed until it has been saved can
 * only be found wrong after it has already stopped the crawl. A candidate with
 * no token is probed with the stored one, so "does the new host answer" does
 * not require re-typing a credential.
 */
export async function testOpenMetadata(
  candidate?: OpenMetadataEdit
): Promise<ConnectionProbe> {
  const { data } = await apiClient.post<ConnectionProbe>(
    '/v1/settings/openmetadata/test',
    candidate ?? {}
  );
  return data;
}

/**
 * When the nightly reconcile runs (FR-1.5).
 *
 * Three times, not one, because they answer different questions: `at`/`zone`
 * is what is stored, `configuredAt`/`configuredZone` is what the service file
 * asked for before anybody touched it, and `nextRunAt` is what is actually
 * booked. People have been caught out by assuming the first implies the third
 * — a schedule that is switched on but unmanaged never fires.
 */
export interface SyncSchedule {
  enabled: boolean;
  /** Local time in `zone`, as HH:MM. Never UTC. */
  at: string;
  zone: string;
  /** True while nothing has been saved and the file's value is in force. */
  isDefault: boolean;
  updatedAt?: string | null;
  updatedBy?: string | null;
  configuredAt: string;
  configuredZone: string;
  nextRunAt?: string | null;
  /** False when nothing in this process is running the backstop. */
  managed: boolean;
}

export async function fetchSyncSchedule(): Promise<SyncSchedule> {
  const { data } = await apiClient.get<SyncSchedule>(
    '/v1/sync/openmetadata/schedule'
  );
  return data;
}

/** Any field left out is kept as it is. Rejected values come back as a 400. */
export async function saveSyncSchedule(edit: {
  enabled?: boolean;
  at?: string;
  zone?: string;
}): Promise<SyncSchedule> {
  const { data } = await apiClient.put<SyncSchedule>(
    '/v1/sync/openmetadata/schedule',
    edit
  );
  return data;
}
