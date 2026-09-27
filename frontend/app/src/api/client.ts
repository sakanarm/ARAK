import axios, { AxiosError } from 'axios';
import { withBase } from '../basePath';
import {
  clearSession,
  notifySessionExpired,
  readToken,
  type SessionUser,
} from '../auth/session';

/**
 * One axios instance for the whole app.
 *
 * The base URL is relative on purpose: in development Vite proxies /api to the
 * Dropwizard service, and in production both are served from the same origin,
 * so no build knows or cares where the backend lives.
 *
 * It carries the mount point because "same origin" is not the same as "root of
 * the origin". Behind a proxy that routes /Arak/ to this app, a request to
 * /api/... leaves the app's own prefix and reaches whatever else is routed at
 * the root — which is somebody else's application, not a 404 we would notice.
 */
export const apiClient = axios.create({
  baseURL: withBase('api'),
  timeout: 60_000,
  headers: { 'Content-Type': 'application/json' },
});

apiClient.interceptors.request.use((config) => {
  const token = readToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    // 401 means the token is gone or expired, and the answer is to sign in
    // again. 403 means the token is fine and this person may not do this, so
    // sending them to the login screen would only loop them back here.
    if (error.response?.status === 401) {
      clearSession();
      notifySessionExpired();
    }
    return Promise.reject(error);
  }
);

/** Pulls the message the backend sent, rather than showing an axios stack. */
export function apiErrorMessage(error: unknown, fallback: string): string {
  if (!axios.isAxiosError(error)) {
    return fallback;
  }

  const response = error.response;
  if (!response) {
    return 'The service is not reachable. Is the backend running?';
  }

  const body = response.data as { message?: string; errors?: string[] } | undefined;
  if (body?.message) {
    return body.message;
  }
  // Jersey answers a bean-validation failure with a list and no `message`.
  if (body?.errors?.length) {
    return body.errors.join(' ');
  }

  // A reply that is not one of ours. In development this is the Vite proxy
  // answering for a backend that is not listening: it returns 500 with an
  // HTML body rather than failing the connection, so the branch above never
  // fires and the caller's fallback used to be the whole story — which read
  // as "the server refused this", when nothing had reached the server at all.
  if (response.status >= 500) {
    return `${fallback} The service answered ${response.status} without a reason, which usually means it is restarting or not running.`;
  }
  return `${fallback} (HTTP ${response.status})`;
}

export interface SystemVersion {
  version: string;
  openMetadataBaseUrl: string;
  openMetadataExpectedVersion: string;
}

export async function fetchSystemVersion(): Promise<SystemVersion> {
  const { data } = await apiClient.get<SystemVersion>('/v1/system/version');
  return data;
}

export interface AuthConfig {
  localLoginEnabled: boolean;
  entraEnabled: boolean;
  entraTenantId: string | null;
  entraClientId: string | null;
}

/** What the login screen needs before anyone has authenticated. */
export async function fetchAuthConfig(): Promise<AuthConfig> {
  const { data } = await apiClient.get<AuthConfig>('/v1/auth/config');
  return data;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  mustChangePassword: boolean;
  user: SessionUser;
}

export async function login(
  username: string,
  password: string
): Promise<LoginResponse> {
  const { data } = await apiClient.post<LoginResponse>('/v1/auth/login', {
    username,
    password,
  });
  return data;
}

/**
 * The signed-in user as the backend holds them now, and whether the password
 * they signed in with still has to be replaced -- asked again on a reload, so
 * that reloading is not a way past the screen that asks for a new one.
 */
export interface Me extends SessionUser {
  mustChangePassword: boolean;
}

export async function fetchMe(): Promise<Me> {
  const { data } = await apiClient.get<Me>('/v1/auth/me');
  return data;
}

export async function changePassword(
  currentPassword: string,
  newPassword: string
): Promise<void> {
  await apiClient.post('/v1/auth/password', { currentPassword, newPassword });
}

// ------------------------------------------------------------------ catalog

/** A governance binding, with where it came from if it was inherited. */
export interface FacetRow {
  facetType: string;
  facetFqn: string;
  property: string | null;
  depth: number;
  direct: boolean;
  inheritedFrom: string | null;
  provenance: string;
  omState: string | null;
  omLabelType: string | null;
}

export interface AssetOwner {
  type: string;
  name: string;
  direct: boolean;
  inheritedFrom: string | null;
}

export interface AssetSummary {
  id: string;
  fqn: string;
  name: string;
  displayName: string | null;
  assetType: string;
  parentFqn: string | null;
  description: string | null;
  tier: string | null;
  certification: string | null;
  dataSource: string | null;
  columnCount: number;
  taggedColumnCount: number;
  facets: FacetRow[];
  owners: AssetOwner[];
  /** What sits directly under it: a database's schemas, a schema's tables. */
  childCount: number;
  /** Where ARAK learned of it: openmetadata, discovered (read off the source) or local. */
  provenance?: string | null;
  /** The connected source a query reaches it (or what is under it) through; null = not connected. */
  querySource?: string | null;
}

export interface AssetPage {
  items: AssetSummary[];
  total: number;
  limit: number;
  offset: number;
}

export interface ColumnDetail {
  id: string;
  fqn: string;
  name: string;
  ordinal: number | null;
  dataType: string | null;
  dataLength: number | null;
  nullable: boolean | null;
  /** Written in ARAK if anybody has, else OpenMetadata's. */
  description: string | null;
  /** Where the description came from: 'arak' or 'openmetadata'; null when there is none. */
  descriptionSource?: 'arak' | 'openmetadata' | null;
  facets: FacetRow[];
}

export interface AssetDetail {
  asset: AssetSummary;
  /** Null when this asset did not come from a crawl, or none is configured. */
  openMetadataUrl: string | null;
  customProperties: Record<string, unknown>;
  columns: ColumnDetail[];
  facets: FacetRow[];
  owners: AssetOwner[];
}

export interface FacetValue {
  facetType: string;
  facetFqn: string;
  assets: number;
}

export interface CatalogSummary {
  assetsByType: Record<string, number>;
  columns: number;
  taggedAssets: number;
  taggedColumns: number;
  assetsWithoutOwner: number;
  facetsByType: Record<string, number>;
}

export interface AssetQuery {
  search?: string;
  assetType?: string;
  /** Each entry is `<facetType>:<facetFqn>`; they are AND-ed by the backend. */
  facets?: string[];
  owner?: string;
  /** Narrows to what one registered source can serve; its UUID, not its name. */
  sourceId?: string;
  /** Only what sits directly under this FQN -- one branch of the hierarchy. */
  parent?: string;
  /** `queryable` (a connected source serves it) or `metadata` (ARAK holds its description only). */
  reach?: string;
  /** `openmetadata`, `discovered` or `local`. */
  origin?: string;
  limit?: number;
  offset?: number;
}

export async function fetchAssets(query: AssetQuery): Promise<AssetPage> {
  // URLSearchParams rather than axios params, so repeating `facet` stays
  // repeated: axios would fold a facets array into facet[]= and the backend
  // would see no filters at all.
  const params = new URLSearchParams();
  if (query.search) params.set('q', query.search);
  if (query.assetType) params.set('type', query.assetType);
  if (query.owner) params.set('owner', query.owner);
  if (query.sourceId) params.set('sourceId', query.sourceId);
  if (query.parent) params.set('parent', query.parent);
  if (query.reach) params.set('reach', query.reach);
  if (query.origin) params.set('origin', query.origin);
  for (const facet of query.facets ?? []) params.append('facet', facet);
  params.set('limit', String(query.limit ?? 50));
  params.set('offset', String(query.offset ?? 0));

  const { data } = await apiClient.get<AssetPage>(`/v1/catalog/assets?${params}`);
  return data;
}

export async function fetchAsset(fqn: string): Promise<AssetDetail> {
  const { data } = await apiClient.get<AssetDetail>(
    `/v1/catalog/assets/${encodeURIComponent(fqn)}`
  );
  return data;
}

export async function fetchFacetValues(
  facetType?: string,
  limit = 100
): Promise<FacetValue[]> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (facetType) params.set('type', facetType);
  const { data } = await apiClient.get<{ values: FacetValue[] }>(
    `/v1/catalog/facets?${params}`
  );
  return data.values;
}

export async function fetchCatalogSummary(): Promise<CatalogSummary> {
  const { data } = await apiClient.get<CatalogSummary>('/v1/catalog/summary');
  return data;
}
