import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { describeApprovers, fetchEligibilities, refusalOf } from './accessRequests';

const mockPost = jest.fn();

jest.mock('./client', () => ({ apiClient: { post: (...args: unknown[]) => mockPost(...args) } }));

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

describe('fetchEligibilities', () => {
  beforeEach(() => mockPost.mockReset());

  it('asks about the whole page in one call, for the caller only', async () => {
    const briefs = [
      {
        assetFqn: 'demo-pg.salesdb.sales.customer',
        queryable: true,
        readable: false,
        requestable: true,
        openRequestId: null,
        blockedKind: null,
      },
    ];
    mockPost.mockResolvedValue({ data: briefs });

    await expect(fetchEligibilities(['demo-pg.salesdb.sales.customer', 'demo-pg.salesdb.sales.orders'], 'Audit')).resolves.toEqual(briefs);
    expect(mockPost).toHaveBeenCalledWith('/v1/access-requests/eligibility', {
      assetFqns: ['demo-pg.salesdb.sales.customer', 'demo-pg.salesdb.sales.orders'],
      purpose: 'Audit',
    });
  });

  it('sends a blank purpose as none, and asks nothing about no tables', async () => {
    mockPost.mockResolvedValue({ data: [] });
    await fetchEligibilities(['demo-pg.salesdb.sales.customer'], '');
    expect(mockPost).toHaveBeenCalledWith('/v1/access-requests/eligibility', {
      assetFqns: ['demo-pg.salesdb.sales.customer'],
      purpose: null,
    });

    mockPost.mockClear();
    await expect(fetchEligibilities([])).resolves.toEqual([]);
    expect(mockPost).not.toHaveBeenCalled();
  });
});
