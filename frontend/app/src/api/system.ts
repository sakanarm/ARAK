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
 * Both secrets arrive as booleans and nothing else. They are injected from the
 * process environment (`OM_JWT_TOKEN`, `OM_WEBHOOK_SECRET`) and there is no
 * endpoint that writes them: a console able to set its own upstream credential
 * is a console whose compromise hands over the catalog.
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
  configSource: string;
  sync: SyncState;
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

export async function testOpenMetadata(): Promise<ConnectionProbe> {
  const { data } = await apiClient.post<ConnectionProbe>(
    '/v1/settings/openmetadata/test'
  );
  return data;
}
