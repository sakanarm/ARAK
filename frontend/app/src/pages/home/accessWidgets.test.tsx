import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { ExpiringGrant, ExpiringGrants } from '../../api/access';
import type { RequestStats, TableRequestStats } from '../../api/accessRequests';
import type { HomeWidget } from '../../api/home';
import {
  HomeWidgetView,
  countdown,
  hoursLabel,
  segmentsOf,
  specOf,
  urgencyOf,
} from './widgets';

/**
 * The two access cards on the home page (M9 slice 2c): who is about to lose
 * access, counted down, and how often each table is asked for.
 *
 * <p>The case worth having on the countdown is a browser whose clock is wrong.
 * The server sends its own "now" with the list; the card measures from that
 * and only uses the browser to know how long it has been open since. A card
 * that read {@code Date.now()} directly would pass every test run on a laptop
 * with a good clock, and tell somebody on a bad one that they have a day more
 * than they do.
 */

const fetchExpiringGrants = jest.fn();
const fetchRequestStats = jest.fn();

jest.mock('../../api/access', () => ({
  fetchExpiringGrants: (...args: unknown[]) => fetchExpiringGrants(...args),
}));

jest.mock('../../api/accessRequests', () => ({
  fetchRequestStats: (...args: unknown[]) => fetchRequestStats(...args),
}));

jest.mock('../../api/client', () => ({
  fetchCatalogSummary: jest.fn(),
  fetchSystemVersion: jest.fn(),
}));

const HOUR = 3_600_000;
const SERVER_NOW = '2026-09-25T03:00:00Z';
const LEDGER = 'prod-pg.SalesDB.dbo.ledger';
const CUSTOMER = 'prod-pg.SalesDB.dbo.customer';

function grant(overrides: Partial<ExpiringGrant>): ExpiringGrant {
  return {
    id: 'g-1',
    assetFqn: LEDGER,
    principalId: 'p-1',
    username: 'analyst_a',
    displayName: 'Analyst A',
    principalType: 'USER',
    source: 'request',
    requestId: 'r-1',
    validFrom: '2026-09-01T00:00:00Z',
    validUntil: '2026-09-25T05:00:00Z',
    grantedBy: 'owner_a',
    mine: false,
    mayRevoke: false,
    ...overrides,
  };
}

function table(overrides: Partial<TableRequestStats>): TableRequestStats {
  return {
    assetFqn: CUSTOMER,
    asked: 4,
    open: 1,
    completed: 1,
    rejected: 1,
    declined: 1,
    withdrawn: 0,
    requesters: 3,
    medianHoursToClose: 4,
    lastAskedAt: '2026-09-24T03:00:00Z',
    ...overrides,
  };
}

function widget(type: HomeWidget['type'], config: Record<string, unknown> = {}): HomeWidget {
  return { id: 'w', type, column: 0, title: null, config };
}

function renderWidget(w: HomeWidget) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <HomeWidgetView widget={w} />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  fetchExpiringGrants.mockReset();
  fetchRequestStats.mockReset();
});

afterEach(() => {
  jest.useRealTimers();
});

describe('countdown', () => {
  it('shows days only when there is more than one left', () => {
    expect(countdown(2 * 24 * HOUR + 4 * HOUR + 12 * 60_000 + 9_000)).toBe('2d 04:12:09');
    expect(countdown(5 * HOUR + 3_000)).toBe('05:00:03');
  });

  it('rounds down, so it never promises a second that is not there', () => {
    expect(countdown(1_999)).toBe('00:00:01');
  });

  it('says ended rather than counting below zero', () => {
    expect(countdown(0)).toBe('Ended');
    expect(countdown(-5_000)).toBe('Ended');
  });
});

describe('urgencyOf', () => {
  it('draws the line at one day and three', () => {
    expect(urgencyOf(-1)).toBe('ended');
    expect(urgencyOf(23 * HOUR)).toBe('today');
    expect(urgencyOf(24 * HOUR)).toBe('soon');
    expect(urgencyOf(71 * HOUR)).toBe('soon');
    expect(urgencyOf(72 * HOUR)).toBe('later');
  });
});

describe('segmentsOf', () => {
  it('keeps legend order and leaves out outcomes that did not happen', () => {
    expect(
      segmentsOf(table({ open: 2, completed: 0, rejected: 1, declined: 0, withdrawn: 3 })).map(
        (segment) => [segment.key, segment.value]
      )
    ).toEqual([
      ['open', 2],
      ['rejected', 1],
      ['withdrawn', 3],
    ]);
  });
});

describe('hoursLabel', () => {
  it('speaks in minutes, hours or days as fits', () => {
    expect(hoursLabel(0.25)).toBe('15m');
    expect(hoursLabel(0.001)).toBe('1m');
    expect(hoursLabel(4)).toBe('4h');
    expect(hoursLabel(30.5)).toBe('30.5h');
    expect(hoursLabel(72)).toBe('3.0d');
  });
});

describe('specs', () => {
  it('offers the countdown to everyone and the statistics to governance only', () => {
    expect(specOf('EXPIRING_ACCESS').governance).toBe(false);
    expect(specOf('EXPIRING_ACCESS').defaultConfig).toEqual({ withinDays: 14, limit: 8 });
    expect(specOf('ACCESS_REQUEST_STATS').governance).toBe(true);
    expect(specOf('ACCESS_REQUEST_STATS').defaultConfig).toEqual({ days: 90, limit: 8 });
  });
});

describe('Access ending soon', () => {
  it('counts down from the server clock, not the browser clock', async () => {
    // The browser is a whole day fast. Measured from its own clock the grant
    // would already have ended; measured from the server's it has two hours.
    jest.useFakeTimers({ now: new Date('2026-09-26T03:00:00Z') });
    const answer: ExpiringGrants = {
      now: SERVER_NOW,
      withinDays: 14,
      total: 1,
      grants: [grant({})],
    };
    fetchExpiringGrants.mockResolvedValue(answer);

    renderWidget(widget('EXPIRING_ACCESS'));

    expect(await screen.findByRole('timer')).toHaveTextContent('02:00:00');
    act(() => {
      jest.advanceTimersByTime(3_000);
    });
    expect(screen.getByRole('timer')).toHaveTextContent('01:59:57');
    expect(fetchExpiringGrants).toHaveBeenCalledWith(14, 8);
  });

  it('marks the reader’s own grant and a group’s, and links to the table’s access', async () => {
    fetchExpiringGrants.mockResolvedValue({
      now: SERVER_NOW,
      withinDays: 7,
      total: 23,
      grants: [
        grant({ id: 'a', mine: true }),
        grant({
          id: 'b',
          assetFqn: CUSTOMER,
          username: 'finance',
          displayName: 'Finance',
          principalType: 'GROUP',
          validUntil: '2026-09-30T03:00:00Z',
        }),
      ],
    } satisfies ExpiringGrants);

    renderWidget(widget('EXPIRING_ACCESS', { withinDays: 7, limit: 2 }));

    const rows = await screen.findAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText('You')).toBeInTheDocument();
    expect(within(rows[0]).queryByText('Group')).not.toBeInTheDocument();
    expect(within(rows[1]).getByText('Finance')).toBeInTheDocument();
    expect(within(rows[1]).getByText('Group')).toBeInTheDocument();
    expect(within(rows[1]).getByRole('link', { name: CUSTOMER })).toHaveAttribute(
      'href',
      `/catalog/${encodeURIComponent(CUSTOMER)}?tab=access`
    );
    // Two shown of the 23 the reader may see.
    expect(screen.getByText('2 of 23')).toBeInTheDocument();
    expect(fetchExpiringGrants).toHaveBeenCalledWith(7, 2);
  });

  it('says so when nothing ends in the window', async () => {
    fetchExpiringGrants.mockResolvedValue({
      now: SERVER_NOW,
      withinDays: 1,
      total: 0,
      grants: [],
    } satisfies ExpiringGrants);

    renderWidget(widget('EXPIRING_ACCESS', { withinDays: 1 }));

    expect(
      await screen.findByText('No access you can see ends in the next 1 day.')
    ).toBeInTheDocument();
  });
});

describe('Requests per table', () => {
  it('shows totals and one bar per table, busiest first', async () => {
    const answer: RequestStats = {
      since: '2026-06-27T03:00:00Z',
      days: 90,
      total: 2,
      totals: { tables: 2, asked: 6, open: 2, completed: 2, rejected: 1, declined: 1, withdrawn: 0 },
      tables: [
        table({}),
        table({
          assetFqn: LEDGER,
          asked: 2,
          open: 1,
          completed: 1,
          rejected: 0,
          declined: 0,
          requesters: 1,
          medianHoursToClose: null,
        }),
      ],
    };
    fetchRequestStats.mockResolvedValue(answer);

    renderWidget(widget('ACCESS_REQUEST_STATS'));

    expect(await screen.findByRole('link', { name: 'customer' })).toBeInTheDocument();
    expect(fetchRequestStats).toHaveBeenCalledWith(90, 8);
    // Refused adds the approver's no to the configurer's.
    expect(screen.getByText('Refused').previousSibling).toHaveTextContent('2');
    expect(screen.getByText('1 rejected · 1 declined')).toBeInTheDocument();

    const bars = screen.getAllByRole('img');
    expect(bars[0]).toHaveAccessibleName('1 granted, 1 open, 1 rejected, 1 declined');
    expect(bars[1]).toHaveAccessibleName('1 granted, 1 open');
    // The ledger's bar is half the customer's: two asks against four.
    expect((bars[1].firstChild as HTMLElement).style.width).toBe('50%');

    expect(screen.getByText(/3 people · median 4h to answer/)).toBeInTheDocument();
    // Nothing on the ledger has ended, so no median is claimed for it.
    expect(screen.getByText(/^1 person · last/)).toBeInTheDocument();
  });

  it('is empty, not an error, for somebody who oversees no table', async () => {
    fetchRequestStats.mockResolvedValue({
      since: '2026-06-27T03:00:00Z',
      days: 30,
      total: 0,
      totals: { tables: 0, asked: 0, open: 0, completed: 0, rejected: 0, declined: 0, withdrawn: 0 },
      tables: [],
    } satisfies RequestStats);

    renderWidget(widget('ACCESS_REQUEST_STATS', { days: 30 }));

    expect(
      await screen.findByText('No table you oversee has been asked for in the last 30 days.')
    ).toBeInTheDocument();
  });
});
