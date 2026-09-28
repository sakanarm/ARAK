import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { busyOf, exportQuery, formatCost } from './query';
import { apiClient } from './client';

jest.mock('./client', () => ({ apiClient: { post: jest.fn() } }));

function httpError(
  status: number,
  data: unknown,
  headers: Record<string, string> = {}
): AxiosError {
  const config = { headers: {} } as InternalAxiosRequestConfig;
  const response = { status, data, statusText: '', headers, config } as AxiosResponse;
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, {}, response);
}

describe('busyOf', () => {
  it('reads a 429 as busy, with the wait the server asked for', () => {
    const body = {
      message: 'Too many queries are running against demo-pg right now.',
      busy: true,
      retryAfterSeconds: 3,
    };
    expect(busyOf(httpError(429, body))).toEqual({
      message: body.message,
      retryAfterSeconds: 3,
    });
  });

  it('falls back to the Retry-After header and a message of its own', () => {
    expect(busyOf(httpError(429, undefined, { 'retry-after': '7' }))).toEqual({
      message: 'Too many queries are running right now. Try again in a few seconds.',
      retryAfterSeconds: 7,
    });
  });

  it('keeps the wait between one second and a minute', () => {
    expect(busyOf(httpError(429, { retryAfterSeconds: 0 }))?.retryAfterSeconds).toBe(1);
    expect(busyOf(httpError(429, { retryAfterSeconds: 900 }))?.retryAfterSeconds).toBe(60);
  });

  it('does not take a refusal, or anything that is not an HTTP error, for busy', () => {
    expect(busyOf(httpError(403, { message: 'Access to x is denied.' }))).toBeNull();
    expect(busyOf(new Error('Network Error'))).toBeNull();
    expect(busyOf(null)).toBeNull();
  });
});

describe('formatCost', () => {
  it('prints a big estimate whole and a small one to two places', () => {
    expect(formatCost(48_210_555.4)).toBe('48,210,555');
    expect(formatCost(8.314)).toBe('8.31');
  });

  it('has nothing to print for a statement that was not priced', () => {
    expect(formatCost(null)).toBeNull();
    expect(formatCost(undefined)).toBeNull();
    expect(formatCost(Number.NaN)).toBeNull();
  });
});

describe('exportQuery', () => {
  const post = apiClient.post as jest.Mock;
  beforeEach(() => post.mockReset());

  it('asks for the file with no client timeout, as the person asking', async () => {
    const file = new Blob(['id\r\n']);
    post.mockResolvedValue({ data: file });
    const progress = jest.fn();

    await expect(
      exportQuery({ sourceId: 's', sql: 'SELECT 1', purpose: null }, { onProgress: progress })
    ).resolves.toBe(file);

    const [path, body, config] = post.mock.calls[0];
    expect(path).toBe('/v1/query/export');
    expect(body).toEqual({ sourceId: 's', sql: 'SELECT 1', purpose: null });
    expect(body).not.toHaveProperty('asPrincipal');
    expect(config).toMatchObject({ responseType: 'blob', timeout: 0 });
    config.onDownloadProgress({ loaded: 2048 });
    expect(progress).toHaveBeenCalledWith(2048);
  });

  it('reads a refusal back out of the blob it arrived in', async () => {
    const refusal = { message: 'Access to x is denied', fixable: false };
    const blob = new Blob([JSON.stringify(refusal)], { type: 'application/json' });
    // jsdom's Blob may predate text(); the browser's does not.
    if (typeof blob.text !== 'function') {
      Object.defineProperty(blob, 'text', { value: async () => JSON.stringify(refusal) });
    }
    post.mockRejectedValue(httpError(403, blob));

    const error = await exportQuery({ sourceId: 's', sql: 'SELECT 1' }).catch((e) => e);

    expect((error as AxiosError).response?.data).toEqual(refusal);
  });

  it('reads a busy source the same way as for a query', async () => {
    const body = JSON.stringify({ message: 'busy', busy: true, retryAfterSeconds: 4 });
    const blob = new Blob([body]);
    if (typeof blob.text !== 'function') {
      Object.defineProperty(blob, 'text', { value: async () => body });
    }
    post.mockRejectedValue(httpError(429, blob));

    const error = await exportQuery({ sourceId: 's', sql: 'SELECT 1' }).catch((e) => e);

    expect(busyOf(error)).toEqual({ message: 'busy', retryAfterSeconds: 4 });
  });
});
