import type { GrantAccess, PersonAccess } from '../../api/access';
import {
  DEFAULT_GRANT_STATUSES,
  countGrants,
  countOrigins,
  filterGrants,
  filterPeople,
  grantStatus,
  page,
  range,
} from './accessLists';

const NOW = Date.parse('2026-09-27T00:00:00Z');

function grant(id: string, over: Partial<GrantAccess> = {}): GrantAccess {
  return {
    id,
    principal: id,
    displayName: null,
    principalType: 'USER',
    principalSource: 'local',
    validFrom: null,
    validUntil: null,
    reason: null,
    grantedBy: 'owner_o',
    grantedAt: '2026-09-01T00:00:00Z',
    live: true,
    effectiveFor: 1,
    ...over,
  };
}

function person(principal: string, over: Partial<PersonAccess> = {}): PersonAccess {
  return {
    principal,
    origin: 'POLICY',
    viaPolicies: [],
    viaGrants: [],
    restricted: false,
    maskedColumns: 0,
    hiddenColumns: 0,
    rowFilters: 0,
    ...over,
  };
}

const future = { live: false, validFrom: '2026-12-01T00:00:00Z' };
const past = { live: false, validFrom: '2026-01-01T00:00:00Z', validUntil: '2026-06-01T00:00:00Z' };

describe('grantStatus', () => {
  it('tells an overruled grant from one in force, but only for an asset that was evaluated', () => {
    expect(grantStatus(grant('a'), true, NOW)).toBe('IN_FORCE');
    expect(grantStatus(grant('a', { effectiveFor: 0 }), true, NOW)).toBe('OVERRULED');
    // Nobody was evaluated, so a zero says nothing about a policy refusing them.
    expect(grantStatus(grant('a', { effectiveFor: 0 }), false, NOW)).toBe('IN_FORCE');
  });

  it('calls a grant not live yet not started, and the rest expired', () => {
    expect(grantStatus(grant('a', future), true, NOW)).toBe('NOT_STARTED');
    expect(grantStatus(grant('a', past), true, NOW)).toBe('EXPIRED');
  });
});

describe('the grant list', () => {
  const grants = [
    grant('ended', { ...past, reason: 'Old audit' }),
    grant('later', future),
    grant('live-1', { reason: 'Quarter end close' }),
    grant('ignored', { effectiveFor: 0 }),
    grant('live-2', { displayName: 'Finance Team', principalType: 'GROUP' }),
  ];

  it('counts every grant once, so the chips add up to the list', () => {
    const counts = countGrants(grants, true, NOW);
    expect(counts).toEqual({ IN_FORCE: 2, OVERRULED: 1, NOT_STARTED: 1, EXPIRED: 1 });
  });

  it('leaves expired grants out by default and puts overruled ones first', () => {
    const shown = filterGrants(grants, {
      statuses: DEFAULT_GRANT_STATUSES,
      search: '',
      known: true,
      now: NOW,
    });
    expect(shown.map((g) => g.id)).toEqual(['ignored', 'live-1', 'live-2', 'later']);
  });

  it('searches name, display name, reason and who granted it, ignoring case', () => {
    const all = new Set(['IN_FORCE', 'OVERRULED', 'NOT_STARTED', 'EXPIRED'] as const);
    const find = (search: string) =>
      filterGrants(grants, { statuses: all, search, known: true, now: NOW }).map((g) => g.id);

    expect(find('  QUARTER ')).toEqual(['live-1']);
    expect(find('finance')).toEqual(['live-2']);
    expect(find('audit')).toEqual(['ended']);
    expect(find('owner_o')).toHaveLength(5);
    expect(find('nobody-by-this-name')).toEqual([]);
  });
});

describe('the people list', () => {
  const people = [
    person('p-policy'),
    person('p-grant', { origin: 'GRANT', viaGrants: ['g-1'] }),
    person('p-both', { origin: 'BOTH', viaGrants: ['g-1'], viaPolicies: ['Finance reads'] }),
    person('p-masked', { restricted: true, maskedColumns: 2, viaPolicies: ['Mask PII'] }),
  ];
  const grantNames = new Map([['g-1', 'finance_team']]);

  it('counts a person let in both ways under either way', () => {
    expect(countOrigins(people)).toEqual({ ALL: 4, GRANT: 2, POLICY: 3, BOTH: 1 });
  });

  it('narrows by origin and by who sees less than all', () => {
    const find = (origin: 'ALL' | 'GRANT' | 'POLICY' | 'BOTH', restrictedOnly = false) =>
      filterPeople(people, { origin, restrictedOnly, search: '', grantNames }).map(
        (p) => p.principal
      );

    expect(find('GRANT')).toEqual(['p-grant', 'p-both']);
    expect(find('BOTH')).toEqual(['p-both']);
    expect(find('POLICY', true)).toEqual(['p-masked']);
  });

  it('finds people by the policy or the grant that let them in', () => {
    const find = (search: string) =>
      filterPeople(people, { origin: 'ALL', restrictedOnly: false, search, grantNames }).map(
        (p) => p.principal
      );

    expect(find('mask pii')).toEqual(['p-masked']);
    // A grant id is not what anybody types; the principal it names is.
    expect(find('finance_team')).toEqual(['p-grant', 'p-both']);
    expect(find('g-1')).toEqual([]);
  });
});

describe('paging', () => {
  const rows = Array.from({ length: 23 }, (_, i) => i);

  it('cuts a page and says where it is', () => {
    expect(page(rows, 10, 10)).toEqual({ rows: [10, 11, 12, 13, 14, 15, 16, 17, 18, 19], offset: 10 });
    expect(range(10, 10, 23)).toBe('11–20 of 23');
    expect(range(0, 0, 0)).toBe('');
  });

  it('pulls an offset past the end back to the last page, so a narrowed list is never blank', () => {
    expect(page(rows, 40, 10)).toEqual({ rows: [20, 21, 22], offset: 20 });
    expect(page(rows, -5, 10).offset).toBe(0);
    expect(page([], 30, 10)).toEqual({ rows: [], offset: 0 });
  });
});
