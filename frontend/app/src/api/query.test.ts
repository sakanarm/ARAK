import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { busyOf, formatCost } from './query';

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
