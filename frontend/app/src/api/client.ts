import axios, { AxiosError } from 'axios';
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
 */
export const apiClient = axios.create({
  baseURL: '/api',
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
  if (axios.isAxiosError(error)) {
    const body = error.response?.data as { message?: string } | undefined;
    if (body?.message) {
      return body.message;
    }
    if (!error.response) {
      return 'The service is not reachable. Is the backend running?';
    }
  }
  return fallback;
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

export async function fetchMe(): Promise<SessionUser> {
  const { data } = await apiClient.get<SessionUser>('/v1/auth/me');
  return data;
}

export async function changePassword(
  currentPassword: string,
  newPassword: string
): Promise<void> {
  await apiClient.post('/v1/auth/password', { currentPassword, newPassword });
}
