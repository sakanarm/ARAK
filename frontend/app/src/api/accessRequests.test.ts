import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { describeApprovers, refusalOf } from './accessRequests';

jest.mock('./client', () => ({ apiClient: {} }));

function httpError(status: number, data: unknown): AxiosError {
  const config = { headers: {} } as InternalAxiosRequestConfig;
  const response = { status, data, statusText: '', headers: {}, config } as AxiosResponse;
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, {}, response);
}

describe('refusalOf', () => {
  it('reads a 403 with a message as a refusal, with what it names', () => {
    const body = {
      message: 'Access to demo-pg.salesdb.sales.customer is denied.',
      assetFqn: 'demo-pg.salesdb.sales.customer',
      requestable: true,
    };
    expect(refusalOf(httpError(403, body))).toEqual(body);
  });

  it('does not read a statement that would not parse as a refusal', () => {
    // A 400 carries a message too; offering to ask an owner about a typo is
    // the wrong advice.
    expect(refusalOf(httpError(400, { message: 'Could not parse the statement.' }))).toBeNull();
  });

  it('ignores a 403 without a message, and anything that is not an HTTP error', () => {
    expect(refusalOf(httpError(403, undefined))).toBeNull();
    expect(refusalOf(httpError(403, { message: 42 }))).toBeNull();
    expect(refusalOf(new Error('Network Error'))).toBeNull();
    expect(refusalOf(null)).toBeNull();
  });
});

describe('describeApprovers', () => {
  it('names users and teams the catalog records as owners', () => {
    expect(
      describeApprovers([
        { type: 'user', name: 'owner_o', direct: true, inheritedFrom: null },
        { type: 'team', name: 'Finance', direct: false, inheritedFrom: 'demo-pg.salesdb' },
      ])
    ).toBe('Decided by owner_o, team Finance.');
  });

  it('says an administrator decides when nobody is recorded', () => {
    const fallback = 'No owner is recorded for this table, so a platform administrator decides.';
    expect(describeApprovers([])).toBe(fallback);
    expect(describeApprovers(undefined)).toBe(fallback);
  });

  it('promises nobody when only the requester could decide', () => {
    expect(describeApprovers([], true)).toMatch(/^No owner is recorded .* nobody can decide this yet/);
    expect(
      describeApprovers([{ type: 'user', name: 'admin', direct: true, inheritedFrom: null }], true)
    ).toMatch(/^Only the requester owns this table/);
  });
});
