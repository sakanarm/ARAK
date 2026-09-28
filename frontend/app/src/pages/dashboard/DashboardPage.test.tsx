import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import DashboardPage, { wordingOf } from './DashboardPage';
import type { Attention, Dashboard, SensitiveTable } from '../../api/dashboard';
import type { GovernanceValue } from '../../api/governance';

const fetchDashboard = jest.fn();

jest.mock('../../api/dashboard', () => {
  const actual = jest.requireActual('../../api/dashboard');
  return { ...actual, fetchDashboard: (...args: unknown[]) => fetchDashboard(...args) };
});

// Asking NokRak about the page has its own tests.
jest.mock('./DashboardExplain', () => () => null);

const fetchVocabulary = jest.fn();

jest.mock('../../api/governance', () => {
  const actual = jest.requireActual('../../api/governance');
  return { ...actual, fetchVocabulary: () => fetchVocabulary() };
});

function value(fqn: string, depth: number, assets: number, children: GovernanceValue[] = [], disabled = false): GovernanceValue {
  return {
    fqn,
    name: fqn.split('.').pop() as string,
    parentFqn: depth === 0 ? null : fqn.slice(0, fqn.lastIndexOf('.')),
    displayName: null,
    description: null,
    depth,
    provenance: 'openmetadata',
    disabled,
    mutuallyExclusive: false,
    assets,
    directAssets: assets,
    policies: 0,
    children,
  };
}

const VOCABULARY = {
  classifications: [
    value('PII', 0, 5, [value('PII.Sensitive', 1, 4)]),
    value('Tier', 0, 2, [value('Tier.Tier1', 1, 1)]),
    value('Retired', 0, 0, [], true),
  ],
  glossaries: [],
  domains: [],
  dataProducts: [],
  customProperties: [],
};

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
}));

const CUSTOMER = 'demo-pg.salesdb.sales.customer';
const ORDERS = 'demo-pg.salesdb.sales.orders';
const SERVER_NOW = '2026-09-25T06:00:00Z';

function table(overrides: Partial<SensitiveTable> = {}): SensitiveTable {
  return {
    fqn: CUSTOMER,
    sensitiveColumns: 2,
    dataPolicies: 1,
    subscriptionPolicies: 1,
    activeGrants: 2,
    readers: 3,
    refused: 1,
    owners: ['owner_a'],
    ...overrides,
  };
}

function dashboard(overrides: Partial<Dashboard> = {}): Dashboard {
  return {
    generatedAt: SERVER_NOW,
    since: '2026-08-26T06:00:00Z',
    days: 30,
    label: 'PII',
    coverage: {
      tables: 40,
      sensitive: 4,
      protectedTables: 3,
      exposed: 1,
      readUnprotected: 1,
      rows: [table({ fqn: ORDERS, dataPolicies: 0, readers: 2, refused: 0, owners: [] }), table()],
    },
    grants: {
      active: 12,
      endingCount: 1,
      endingSoon: [
        {
          principal: 'analyst_a',
          principalType: 'USER',
          assetFqn: CUSTOMER,
          validUntil: '2026-09-25T08:00:00Z',
          source: 'request',
        },
      ],
      openEnded: 3,
      onSensitive: 5,
      unused: 2,
    },
    activity: {
      counts: { total: 20, executed: 15, rejected: 4, failed: 1 },
      perDay: [
        { date: '2026-09-24', executed: 10, rejected: 3, failed: 0 },
        { date: '2026-09-25', executed: 5, rejected: 1, failed: 1 },
      ],
      refusals: [
        { category: 'POLICY_DENY', count: 4 },
        { category: 'SOURCE_ERROR', count: 1 },
      ],
      busiestTables: [{ name: CUSTOMER, queries: 14, refused: 2, people: 3, lastAt: SERVER_NOW }],
      busiestPeople: [{ name: 'analyst_a', queries: 9, refused: 1, people: 2, lastAt: SERVER_NOW }],
      decisions: { total: 120, denied: 10, fromCache: 80, p50Ms: 3, p95Ms: 18 },
    },
    requests: {
      pending: 2,
      approved: 0,
      inProgress: 1,
      oldestOpenAt: '2026-09-20T06:00:00Z',
      asked: 6,
      completed: 2,
      rejected: 1,
      withdrawn: 0,
      medianHoursToClose: 30,
    },
    health: {
      sources: 2,
      sourcesEnabled: 2,
      enforcement: { APPLIED: 3, DRIFTED: 1 },
      policies: { 'SUBSCRIPTION ACTIVE': 4, 'DATA ACTIVE': 2, 'DATA DRAFT': 1 },
      syncStatus: 'IDLE',
      lastCrawlAt: '2026-09-25T05:00:00Z',
      syncFailed: false,
    },
    attention: [
      { kind: 'UNPROTECTED_READ', severity: 'HIGH', count: 1, subject: ORDERS },
      { kind: 'ENFORCEMENT_FAULT', severity: 'HIGH', count: 1, subject: null },
      { kind: 'REQUESTS_WAITING', severity: 'MEDIUM', count: 3, subject: '5' },
      { kind: 'GRANTS_UNUSED', severity: 'LOW', count: 2, subject: null },
    ],
    ...overrides,
  };
}

let location = '';
function Where() {
  location = useLocation().search;
  return null;
}

function renderPage(entry = '/dashboard') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[entry]}>
        <DashboardPage />
        <Where />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function section(name: string) {
  return screen.getByRole('heading', { name: new RegExp(`^${name}`) }).closest('section') as HTMLElement;
}

beforeEach(() => {
  fetchDashboard.mockReset();
  fetchVocabulary.mockReset();
  fetchVocabulary.mockResolvedValue(VOCABULARY);
  location = '';
});

describe('DashboardPage', () => {
  it('asks for PII over thirty days by default and shows the key figures', async () => {
    fetchDashboard.mockResolvedValue(dashboard());
    renderPage();

    const figures = await screen.findByRole('region', { name: 'Key figures' });
    expect(fetchDashboard).toHaveBeenCalledWith(30, 'PII');
    expect(within(figures).getByText('75%')).toBeInTheDocument();
    expect(within(figures).getByText('3 of 4 sensitive tables')).toBeInTheDocument();
    expect(within(figures).getByText('12')).toBeInTheDocument();
    expect(within(figures).getByText('4 refused (20%)')).toBeInTheDocument();
    expect(within(figures).getByRole('link', { name: 'Open requests: 3' })).toHaveAttribute(
      'href',
      '/requests?tab=inbox'
    );
    expect(within(figures).getByText('18 ms')).toBeInTheDocument();
  });

  it('reads the window and the label from the address', async () => {
    fetchDashboard.mockResolvedValue(dashboard({ days: 90, label: 'Finance' }));
    renderPage('/dashboard?days=90&label=Finance');
    await screen.findByRole('region', { name: 'Key figures' });
    expect(fetchDashboard).toHaveBeenCalledWith(90, 'Finance');
    // Not a label the catalogue knows, yet it stays what the picker shows.
    expect(screen.getByRole('button', { name: /Sensitive label/ })).toHaveTextContent('Finance');
  });

  it('puts the label on the card it scopes, not above the whole page', async () => {
    fetchDashboard.mockResolvedValue(dashboard());
    renderPage();
    await screen.findByRole('region', { name: 'Key figures' });
    const picker = screen.getByRole('button', { name: /Sensitive label/ });
    expect(section('Coverage')).toContainElement(picker);
    const header = screen.getByRole('heading', { name: 'Access control dashboard' }).closest('header')!;
    expect(header).not.toContainElement(picker);
    // The window still sits in the header: every card reads it.
    expect(within(header as HTMLElement).getByRole('button', { name: /Window/ })).toBeInTheDocument();
  });

  it('falls back to thirty days when the address asks for a window it does not offer', async () => {
    fetchDashboard.mockResolvedValue(dashboard());
    renderPage('/dashboard?days=12');
    await screen.findByRole('region', { name: 'Key figures' });
    expect(fetchDashboard).toHaveBeenCalledWith(30, 'PII');
  });

  it('offers the labels the catalogue knows, and puts the chosen one in the address', async () => {
    fetchDashboard.mockResolvedValue(dashboard());
    renderPage();
    await screen.findByRole('region', { name: 'Key figures' });
    await waitFor(() => expect(fetchVocabulary).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: /Sensitive label/ }));
    const options = (await screen.findAllByRole('option')).map((o) => o.textContent);
    expect(options).toHaveLength(4);
    expect(options[0]).toContain('PII');
    expect(options[0]).toContain('every tag under it');
    expect(options[1]).toContain('PII.Sensitive');
    expect(options[1]).toContain('4 tables and columns');
    // A disabled classification is not enforced, so it is not offered.
    expect(options.join()).not.toContain('Retired');

    fireEvent.click(screen.getByRole('option', { name: /Tier\.Tier1/ }));
    await waitFor(() => expect(fetchDashboard).toHaveBeenLastCalledWith(30, 'Tier.Tier1'));
    expect(location).toBe('?label=Tier.Tier1');
  });

  it('lists what needs attention, loudest first, each with the way to fix it', async () => {
    fetchDashboard.mockResolvedValue(dashboard());
    renderPage();

    const list = await screen.findByRole('list', { name: 'Needs attention' });
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(4);

    expect(items[0]).toHaveTextContent('1 sensitive table was read with no data policy');
    expect(within(items[0]).getByRole('img', { name: 'High severity' })).toBeInTheDocument();
    expect(within(items[0]).getByRole('link', { name: /Open orders/ })).toHaveAttribute(
      'href',
      `/catalog/${encodeURIComponent(ORDERS)}`
    );
    expect(within(items[1]).getByRole('link', { name: /Open Enforcement/ })).toHaveAttribute(
      'href',
      '/enforcement'
    );
    expect(items[2]).toHaveTextContent('The oldest has waited 5 days for an answer.');
    expect(within(items[2]).getByRole('img', { name: 'Medium severity' })).toBeInTheDocument();
    expect(within(items[3]).getByRole('link', { name: /See grants/ })).toHaveAttribute('href', '#grants');
    expect(section('Needs attention')).toHaveTextContent('2 high');
  });

  it('says so when nothing needs anybody', async () => {
    fetchDashboard.mockResolvedValue(dashboard({ attention: [] }));
    renderPage();
    expect(await screen.findByText('Nothing needs you right now')).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Needs attention' })).not.toBeInTheDocument();
  });

  it('words every kind of item, and never reads a missing subject aloud', () => {
    const kinds: Attention['kind'][] = [
      'UNPROTECTED_READ',
      'UNPROTECTED_REACHABLE',
      'ENFORCEMENT_FAULT',
      'SYNC_FAILED',
      'SYNC_STALE',
      'REQUESTS_WAITING',
      'GRANTS_ENDING',
      'UNPROTECTED_CLOSED',
      'GRANTS_UNUSED',
      'GRANTS_OPEN_ENDED',
    ];
    for (const kind of kinds) {
      const words = wordingOf({ kind, severity: 'LOW', count: 2, subject: null }, 30);
      expect(words.title).not.toMatch(/null|undefined|NaN/);
      expect(words.detail).not.toMatch(/null|undefined|NaN/);
    }
    expect(wordingOf({ kind: 'SYNC_FAILED', severity: 'HIGH', count: 1, subject: null }, 30).action?.to).toBe(
      '/settings'
    );
    expect(
      wordingOf({ kind: 'GRANTS_ENDING', severity: 'MEDIUM', count: 1, subject: 'analyst_a' }, 30)
    ).toMatchObject({ title: '1 grant ends in the next 14 days', action: { to: '#ending' } });
  });

  it('draws coverage as a ring with protected, exposed and closed tables', async () => {
    fetchDashboard.mockResolvedValue(dashboard());
    renderPage();
    await screen.findByRole('region', { name: 'Key figures' });

    const coverage = section('Coverage');
    expect(within(coverage).getByRole('img', { name: '75% of sensitive tables protected' })).toBeInTheDocument();
    expect(within(coverage).getByText('Protected by a data policy').nextSibling).toHaveTextContent('3');
    expect(within(coverage).getByText('Unprotected, and people can get in').nextSibling).toHaveTextContent('1');
    expect(within(coverage).getByText('Unprotected, nobody can get in').nextSibling).toHaveTextContent('0');
    expect(coverage).toHaveTextContent('1 was read unprotected in the last 30 days.');
  });

  it('explains an empty label rather than drawing an empty ring', async () => {
    fetchDashboard.mockResolvedValue(
      dashboard({
        coverage: { tables: 40, sensitive: 0, protectedTables: 0, exposed: 0, readUnprotected: 0, rows: [] },
      })
    );
    renderPage();
    await screen.findByRole('region', { name: 'Key figures' });
    expect(section('Coverage')).toHaveTextContent('No table or column in the catalogue carries PII');
    expect(screen.getByText('No table carries PII')).toBeInTheDocument();
  });

  it('lists the sensitive tables with who can get in and who read them', async () => {
    fetchDashboard.mockResolvedValue(dashboard());
    renderPage();
    await screen.findByRole('region', { name: 'Key figures' });

    const rows = within(section('Tables carrying PII')).getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent('orders');
    expect(rows[1]).toHaveTextContent('Unprotected');
    expect(rows[1]).toHaveTextContent('No owner');
    expect(within(rows[1]).getByRole('link', { name: '2 people' })).toHaveAttribute(
      'href',
      `/audit?table=${encodeURIComponent(ORDERS)}&days=30`
    );
    expect(rows[2]).toHaveTextContent('1 data policy');
    expect(rows[2]).toHaveTextContent('1 policy · 2 grants');
    expect(within(rows[2]).getByRole('link', { name: '1' })).toHaveAttribute(
      'href',
      `/audit?outcome=REJECTED&table=${encodeURIComponent(CUSTOMER)}&days=30`
    );
  });

  it('shows the first tables and the rest on request', async () => {
    const many = Array.from({ length: 11 }, (_, i) => table({ fqn: `demo-pg.salesdb.sales.t${i}` }));
    fetchDashboard.mockResolvedValue(
      dashboard({
        coverage: { tables: 40, sensitive: 11, protectedTables: 11, exposed: 0, readUnprotected: 0, rows: many },
      })
    );
    renderPage();
    await screen.findByRole('region', { name: 'Key figures' });

    const card = section('Tables carrying PII');
    expect(within(card).getAllByRole('row')).toHaveLength(9);
    fireEvent.click(within(card).getByRole('button', { name: 'Show all 11' }));
    expect(within(card).getAllByRole('row')).toHaveLength(12);
  });

  it('counts down the access that is ending from the server clock', async () => {
    // The browser is a day fast; the grant still has two hours by the server.
    jest.useFakeTimers({ now: new Date('2026-09-26T06:00:00Z') });
    try {
      fetchDashboard.mockResolvedValue(dashboard());
      renderPage();

      // Waiting for the page moves the fake clock on a little, so read where
      // the countdown starts and check it is near two hours, not a day off.
      const seconds = (text: string | null) => {
        const [h, m, s] = (text ?? '').split(':').map(Number);
        return h * 3600 + m * 60 + s;
      };
      const start = seconds((await screen.findByRole('timer')).textContent);
      expect(start).toBeGreaterThan(7200 - 5);
      expect(start).toBeLessThanOrEqual(7200);
      act(() => {
        jest.advanceTimersByTime(2_000);
      });
      expect(seconds(screen.getByRole('timer').textContent)).toBe(start - 2);
      const ending = within(screen.getByRole('list', { name: 'Access ending' })).getByRole('link');
      expect(ending).toHaveAttribute('href', `/catalog/${encodeURIComponent(CUSTOMER)}?tab=access`);
    } finally {
      jest.useRealTimers();
    }
  });

  it('links refusals and the busiest tables and people to the query log', async () => {
    fetchDashboard.mockResolvedValue(dashboard());
    renderPage();
    await screen.findByRole('region', { name: 'Key figures' });

    const refusals = section('Why queries did not run');
    expect(within(refusals).getByRole('link', { name: 'Denied by policy' })).toHaveAttribute(
      'href',
      '/audit?outcome=REJECTED&days=30'
    );
    expect(within(refusals).getByRole('link', { name: 'Source returned an error' })).toHaveAttribute(
      'href',
      '/audit?outcome=FAILED&days=30'
    );
    expect(within(section('Most-read tables')).getByRole('link', { name: 'customer' })).toHaveAttribute(
      'href',
      `/audit?table=${encodeURIComponent(CUSTOMER)}&days=30`
    );
    expect(within(section('Most active people')).getByRole('link', { name: 'analyst_a' })).toHaveAttribute(
      'href',
      '/audit?principal=analyst_a&days=30'
    );
  });

  it('charts every day with a spoken summary', async () => {
    fetchDashboard.mockResolvedValue(dashboard());
    renderPage();
    expect(
      await screen.findByRole('img', { name: '15 ran, 4 refused, 1 failed over 2 days' })
    ).toBeInTheDocument();
    expect(section('Queries per day')).toHaveTextContent('Busiest day');
  });

  it('sums up requests, grants and the platform', async () => {
    fetchDashboard.mockResolvedValue(dashboard());
    renderPage();
    await screen.findByRole('region', { name: 'Key figures' });

    expect(section('Access requests')).toHaveTextContent('Half are answered within');
    expect(within(section('Access requests')).getByRole('img', { name: '2 granted, 1 rejected, 0 withdrawn' })).toBeInTheDocument();
    expect(section('Grants in force')).toHaveTextContent('Not used in 90 days');
    const health = section('Platform health');
    expect(health).toHaveTextContent('3 applied');
    expect(health).toHaveTextContent('1 drifted');
    expect(health).toHaveTextContent('Data policies');
    expect(health).toHaveTextContent('2 active · 1 draft');
    expect(health).toHaveTextContent('Subscription policies');
  });

  it('tells a reader the server refused them', async () => {
    fetchDashboard.mockRejectedValue(new Error('Only an administrator, policy author or auditor may read the dashboard'));
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Only an administrator, policy author or auditor may read the dashboard'
    );
    expect(screen.queryByRole('region', { name: 'Key figures' })).not.toBeInTheDocument();
  });
});
