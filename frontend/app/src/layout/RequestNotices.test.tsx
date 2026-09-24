import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { Inbox01 } from '@untitledui/icons';
import { Notifications } from './TopNav';
import { NavItem } from './AppShell';
import type { RequestNotice, RequestNotices } from '../api/accessRequests';
import type { NavSection } from './navigation';

const fetchNotices = jest.fn();
const markSeen = jest.fn();
const fetchSync = jest.fn();
let isAdmin = false;

jest.mock('../api/accessRequests', () => {
  const actual = jest.requireActual('../api/accessRequests');
  return {
    ...actual,
    fetchRequestNotices: (...args: unknown[]) => fetchNotices(...args),
    markRequestNoticesSeen: (...args: unknown[]) => markSeen(...args),
  };
});

jest.mock('../api/system', () => ({
  fetchSyncStatus: (...args: unknown[]) => fetchSync(...args),
}));

jest.mock('../auth/authStore', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) =>
    selector({ user: { username: 'owner_o' }, hasRole: () => isAdmin }),
}));

function notice(overrides: Partial<RequestNotice> = {}): RequestNotice {
  return {
    id: 1,
    kind: 'REQUESTED',
    side: 'INBOX',
    requestId: 'req-1',
    assetFqn: 'demo-pg.salesdb.sales.customer',
    actor: 'analyst_a',
    requesterUsername: 'analyst_a',
    note: null,
    occurredAt: '2026-09-24T03:00:00Z',
    unseen: true,
    ...overrides,
  };
}

function notices(overrides: Partial<RequestNotices> = {}): RequestNotices {
  return { unseen: 0, inboxPending: 0, minePending: 0, seenAt: null, items: [], ...overrides };
}

let location = '';
function Where() {
  const here = useLocation();
  location = here.pathname + here.search;
  return null;
}

function renderWith(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/']}>
        {ui}
        <Where />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  [fetchNotices, markSeen, fetchSync].forEach((fn) => fn.mockReset());
  fetchNotices.mockResolvedValue(notices());
  markSeen.mockResolvedValue(undefined);
  fetchSync.mockResolvedValue({ status: 'SUCCEEDED', lastFullCrawlAt: null, updatedAt: null });
  isAdmin = false;
  location = '';
});

describe('the bell', () => {
  it('shows no number when nothing is new', async () => {
    renderWith(<Notifications />);

    await waitFor(() => expect(fetchNotices).toHaveBeenCalled());
    expect(screen.getByRole('button', { name: 'Notifications' })).toBeInTheDocument();
  });

  it('counts what is new, and reads it all when opened', async () => {
    fetchNotices
      .mockResolvedValueOnce(
        notices({
          unseen: 2,
          inboxPending: 1,
          items: [
            notice(),
            notice({
              id: 2,
              kind: 'REJECTED',
              side: 'MINE',
              requestId: 'req-9',
              assetFqn: 'pg.db.s.ledger',
              actor: 'finance_lead',
              note: 'Use the summary',
            }),
          ],
        })
      )
      .mockResolvedValue(notices({ unseen: 0, inboxPending: 1 }));
    renderWith(<Notifications />);

    const bell = await screen.findByRole('button', { name: 'Notifications, 2 new' });
    fireEvent.click(bell);

    const panel = await screen.findByRole('dialog', { name: 'Notifications' });
    expect(within(panel).getByText('1 request waiting for your decision')).toBeInTheDocument();
    expect(within(panel).getByText('asked for access to')).toBeInTheDocument();
    expect(within(panel).getByText('rejected your request for')).toBeInTheDocument();
    expect(within(panel).getByText('“Use the summary”')).toBeInTheDocument();
    expect(within(panel).getAllByRole('img', { name: 'New' })).toHaveLength(2);
    await waitFor(() => expect(markSeen).toHaveBeenCalledTimes(1));
  });

  it('opens the request a notice is about, on the right tab', async () => {
    fetchNotices.mockResolvedValue(
      notices({
        items: [notice({ kind: 'APPROVED', side: 'MINE', requestId: 'req-7', unseen: false })],
      })
    );
    renderWith(<Notifications />);

    fireEvent.click(await screen.findByRole('button', { name: 'Notifications' }));
    fireEvent.click(await screen.findByText('approved your request for'));

    expect(location).toBe('/requests?tab=mine&id=req-7');
    // Nothing was new, so nothing is marked.
    expect(markSeen).not.toHaveBeenCalled();
  });

  it('takes a decider to their inbox', async () => {
    fetchNotices.mockResolvedValue(notices({ inboxPending: 3 }));
    renderWith(<Notifications />);

    fireEvent.click(await screen.findByRole('button', { name: 'Notifications' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Review' }));

    expect(location).toBe('/requests?tab=inbox');
  });

  it('says so when there is nothing at all', async () => {
    renderWith(<Notifications />);

    fireEvent.click(await screen.findByRole('button', { name: 'Notifications' }));

    expect(await screen.findByText("You're all caught up")).toBeInTheDocument();
  });

  it('adds a failed crawl for an administrator', async () => {
    isAdmin = true;
    fetchSync.mockResolvedValue({ status: 'FAILED', lastError: 'OpenMetadata refused the token' });
    renderWith(<Notifications />);

    fireEvent.click(await screen.findByRole('button', { name: 'Notifications, 1 new' }));

    expect(await screen.findByText('The last OpenMetadata crawl failed')).toBeInTheDocument();
    expect(screen.getByText('OpenMetadata refused the token')).toBeInTheDocument();
  });

  it('never asks a non-administrator’s bell about the crawl', async () => {
    renderWith(<Notifications />);

    await waitFor(() => expect(fetchNotices).toHaveBeenCalled());
    expect(fetchSync).not.toHaveBeenCalled();
  });
});

describe('the rail’s requests link', () => {
  const SECTION = {
    label: 'Access requests',
    href: '/requests',
    icon: Inbox01,
    milestone: null,
    description: '',
    visibleTo: 'everyone',
  } as NavSection;

  it('carries the number waiting', () => {
    renderWith(<NavItem collapsed={false} count={12} current={false} section={SECTION} />);

    expect(screen.getByLabelText('12 waiting')).toHaveTextContent('9+');
  });

  it('says the number when collapsed, where only a dot is drawn', () => {
    renderWith(<NavItem collapsed count={2} current={false} section={SECTION} />);

    expect(screen.getByRole('link', { name: 'Access requests, 2 waiting' })).toBeInTheDocument();
  });

  it('draws nothing when nothing waits', () => {
    renderWith(<NavItem collapsed={false} count={0} current={false} section={SECTION} />);

    expect(screen.queryByLabelText(/waiting/)).toBeNull();
  });
});
