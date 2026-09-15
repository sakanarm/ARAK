import axios from 'axios';

/**
 * One axios instance for the whole app.
 *
 * The base URL is relative on purpose: in development Vite proxies /api to the
 * Dropwizard service, and in production both are served from the same origin,
 * so no build knows or cares where the backend lives.
 */
export const apiClient = axios.create({
  baseURL: '/api',
  timeout: 60_000,
  headers: { 'Content-Type': 'application/json' },
});

export interface SystemVersion {
  version: string;
  openMetadataBaseUrl: string;
  openMetadataExpectedVersion: string;
}

export async function fetchSystemVersion(): Promise<SystemVersion> {
  const { data } = await apiClient.get<SystemVersion>('/v1/system/version');
  return data;
}
