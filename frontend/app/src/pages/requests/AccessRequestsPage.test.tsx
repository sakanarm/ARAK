import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import AccessRequestsPage from './AccessRequestsPage';
import type { AccessRequest, RequestNotices } from '../../api/accessRequests';

const fetchInbox = jest.fn();
const fetchMine = jest.fn();
const fetchOne = jest.fn();
const fetchNotices = jest.fn();
const approve = jest.fn();
const reject = jest.fn();
const withdraw = jest.fn();

jest.mock('../../api/accessRequests', () => {
  const actual = jest.requireActual('../../api/accessRequests');
  return {
    ...actual,
    fetchRequestInbox: (...args: unknown[]) => fetchInbox(...args),
    fetchMyRequests: (...args: unknown[]) => fetchMine(...args),
    fetchRequest: (...args: unknown[]) => fetchOne(...args),
    fetchRequestNotices: (...args: unknown[]) => fetchNotices(...args),
    approveRequest: (...args: unknown[]) => approve(...args),
    rejectRequest: (...args: unknown[]) => reject(...args),
    withdrawRequest: (...args: unknown[]) => withdraw(...args),
  };
});

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
}));

jest.mock('../../auth/authStore', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) =>
    selector({ user: { username: 'me' }, hasRole: () => false }),
}));

const FQN = 'demo-pg.salesdb.sales.customer';

function request(overrides: Partial<AccessRequest> = {}): AccessRequest {
  return {
    id: 'req-1',
    assetFqn: FQN,
    requesterId: 'p-1',
    requesterUsername: 'analyst_a',
    dataSourceId: 'src-1',
    reason: 'Month-end reconciliation',
    purpose: 'fraud-analysis',
    requestedDays: 30,
    attemptedSql: 'SELECT * FROM sales.customer',
    deniedBy: `Access to ${FQN} is denied: no policy allows it.`,
    status: 'PENDING',
    createdAt: '2026-09-24T03:00:00Z',
    decidedBy: null,
    decidedAt: null,
    decisionNote: null,
    grantId: null,
    approvers: [{ type: 'team', name: 'Finance', direct: true, inheritedFrom: null }],
    mayDecide: true,
    stranded: false,
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

function renderPage(url = '/requests') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[url]}>
        <AccessRequestsPage />
        <Where />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function detail() {
  return screen.findByRole('article', { name: `Request for ${FQN}` });
}

beforeEach(() => {
  [fetchInbox, fetchMine, fetchOne, fetchNotices, approve, reject, withdraw].forEach((fn) =>
    fn.mockReset()
  );
  fetchInbox.mockResolvedValue([]);
  fetchMine.mockResolvedValue([]);
  fetchNotices.mockResolvedValue(notices());
  location = '';
});

describe('AccessRequestsPage tabs', () => {
  it('opens the inbox when something waits, with both counts on the tabs', async () => {
    fetchNotices.mockResolvedValue(notices({ inboxPending: 3, minePending: 1 }));
    renderPage();

    const inbox = await screen.findByRole('tab', { name: /Inbox/ });
    await waitFor(() => expect(inbox).toHaveAttribute('aria-selected', 'true'));
    expect(within(inbox).getByLabelText('3 open')).toBeInTheDocument();
    expect(
      within(screen.getByRole('tab', { name: /My requests/ })).getByLabelText('1 open')
    ).toBeInTheDocument();
    expect(fetchInbox).toHaveBeenCalledWith('PENDING');
    expect(fetchMine).not.toHaveBeenCalled();
  });

  it('opens one’s own requests when nothing waits for a decision', async () => {
    renderPage();

    expect(await screen.findByText(/You have not asked for anything/)).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /My requests/ })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    expect(fetchInbox).not.toHaveBeenCalled();
  });

  it('opens one’s own requests when the counts cannot be read', async () => {
    fetchNotices.mockRejectedValue(new Error('down'));
    renderPage();

    expect(await screen.findByText(/You have not asked for anything/)).toBeInTheDocument();
  });

  it('keeps the tab in the address, so a link can open it', async () => {
    fetchNotices.mockResolvedValue(notices({ inboxPending: 1 }));
    renderPage('/requests?tab=mine');

    expect(await screen.findByText(/You have not asked for anything/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: /Inbox/ }));

    expect(
      await screen.findByText(
        'Nothing is waiting for your decision. Requests for tables you own appear here.'
      )
    ).toBeInTheDocument();
    expect(location).toBe('/requests?tab=inbox');
  });

  it('filters the inbox on the server, and one’s own list here', async () => {
    fetchMine.mockResolvedValue([
      request({ id: 'a', status: 'APPROVED', assetFqn: 'pg.db.s.orders' }),
      request({ id: 'b', status: 'PENDING', assetFqn: 'pg.db.s.ledger' }),
    ]);
    renderPage('/requests?tab=inbox');
    await waitFor(() => expect(fetchInbox).toHaveBeenCalledWith('PENDING'));

    fireEvent.click(screen.getByRole('radio', { name: 'All' }));
    await waitFor(() => expect(fetchInbox).toHaveBeenCalledWith(null));

    fireEvent.click(screen.getByRole('tab', { name: /My requests/ }));
    const list = await screen.findByRole('region', { name: 'My requests' });
    expect(await within(list).findByRole('button', { name: /orders, Approved/ })).toBeInTheDocument();
    expect(within(list).getByRole('button', { name: /ledger, Pending/ })).toBeInTheDocument();

    fireEvent.click(within(list).getByRole('radio', { name: 'Approved' }));
    await waitFor(() => expect(within(list).queryByRole('button', { name: /ledger/ })).toBeNull());
    expect(within(list).getByRole('button', { name: /orders, Approved/ })).toBeInTheDocument();
    expect(fetchMine).toHaveBeenCalledTimes(1);
  });
});

describe('AccessRequestsPage inbox', () => {
  it('opens the first request with what the owner needs to judge it', async () => {
    fetchInbox.mockResolvedValue([request()]);
    renderPage('/requests?tab=inbox');

    const card = await detail();
    expect(within(card).getAllByText('analyst_a').length).toBeGreaterThan(0);
    expect(within(card).getByText('Month-end reconciliation')).toBeInTheDocument();
    expect(within(card).getByText('30 days')).toBeInTheDocument();
    expect(within(card).getByText('fraud-analysis')).toBeInTheDocument();
    expect(within(card).getByText('SELECT * FROM sales.customer')).toBeInTheDocument();
    expect(within(card).getByText('Decided by team Finance.')).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: FQN })).toHaveAttribute(
      'href',
      `/catalog/${encodeURIComponent(FQN)}`
    );
  });

  it('opens the request that was picked, and marks it in the list', async () => {
    fetchInbox.mockResolvedValue([
      request(),
      request({ id: 'req-2', assetFqn: 'pg.db.s.orders', reason: 'Order audit' }),
    ]);
    renderPage('/requests?tab=inbox');
    await detail();

    const row = screen.getByRole('button', { name: /orders, Pending, asked by analyst_a/ });
    fireEvent.click(row);

    expect(
      await screen.findByRole('article', { name: 'Request for pg.db.s.orders' })
    ).toHaveTextContent('Order audit');
    expect(row).toHaveAttribute('aria-current', 'true');
    expect(location).toBe('/requests?tab=inbox&id=req-2');
  });

  it('fetches a linked request the list does not hold', async () => {
    fetchInbox.mockResolvedValue([]);
    fetchOne.mockResolvedValue(request({ id: 'gone', status: 'WITHDRAWN', decidedBy: 'analyst_a' }));
    renderPage('/requests?tab=inbox&id=gone');

    const card = await detail();
    expect(fetchOne).toHaveBeenCalledWith('gone');
    expect(within(card).getByText('withdrew the request')).toBeInTheDocument();
  });

  it('says so when a linked request cannot be opened', async () => {
    fetchOne.mockRejectedValue(new Error('Not found'));
    renderPage('/requests?tab=inbox&id=nope');

    expect(
      await screen.findByText('That request is not one you can see, or it no longer exists.')
    ).toBeInTheDocument();
  });

  it('approves for as long as was asked, with the note', async () => {
    fetchInbox.mockResolvedValue([request()]);
    approve.mockResolvedValue(request({ status: 'APPROVED' }));
    renderPage('/requests?tab=inbox');
    await screen.findByRole('button', { name: 'Approve' });
    expect(screen.queryByLabelText('Grant days')).toBeNull();

    fireEvent.change(screen.getByLabelText('Note to the requester'), {
      target: { value: ' Until the audit closes ' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));

    await waitFor(() =>
      expect(approve).toHaveBeenCalledWith('req-1', { days: 30, note: 'Until the audit closes' })
    );
    // The list and the counts are fetched again, so the request leaves "pending".
    await waitFor(() => expect(fetchInbox).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(fetchNotices.mock.calls.length).toBeGreaterThanOrEqual(2));
  });

  it('leaves an open-ended ask open-ended', async () => {
    fetchInbox.mockResolvedValue([request({ requestedDays: null })]);
    approve.mockResolvedValue(request({ status: 'APPROVED' }));
    renderPage('/requests?tab=inbox');

    fireEvent.click(await screen.findByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(approve).toHaveBeenCalledWith('req-1', { days: null, note: null }));
  });

  it('will not reject without a note to the requester', async () => {
    fetchInbox.mockResolvedValue([request()]);
    reject.mockResolvedValue(request({ status: 'REJECTED' }));
    renderPage('/requests?tab=inbox');
    const rejectButton = await screen.findByRole('button', { name: 'Reject' });
    expect(rejectButton).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Note to the requester'), {
      target: { value: '   ' },
    });
    expect(rejectButton).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Note to the requester'), {
      target: { value: 'Use the aggregated view instead' },
    });
    fireEvent.click(rejectButton);

    await waitFor(() =>
      expect(reject).toHaveBeenCalledWith('req-1', 'Use the aggregated view instead')
    );
  });

  it('shows why a decision was not recorded', async () => {
    fetchInbox.mockResolvedValue([request()]);
    approve.mockRejectedValue(new Error('Only the owner of this table can decide.'));
    renderPage('/requests?tab=inbox');

    fireEvent.click(await screen.findByRole('button', { name: 'Approve' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Only the owner of this table can decide.'
    );
  });

  it('draws no decision for a request the server says this person may not decide', async () => {
    fetchInbox.mockResolvedValue([request({ mayDecide: false })]);
    renderPage('/requests?tab=inbox');

    await detail();
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
    expect(screen.queryByLabelText('Grant days')).toBeNull();
  });

  it('draws no decision for a request already decided, and shows who decided it', async () => {
    fetchInbox.mockResolvedValue([
      request({
        status: 'REJECTED',
        decidedBy: 'owner_o',
        decidedAt: '2026-09-24T05:00:00Z',
        decisionNote: 'Use the aggregated view',
      }),
    ]);
    renderPage('/requests?tab=inbox&status=REJECTED');

    const card = await detail();
    expect(within(card).getByText('Rejected')).toBeInTheDocument();
    expect(within(card).getByText('owner_o')).toBeInTheDocument();
    expect(within(card).getByText('rejected it')).toBeInTheDocument();
    expect(within(card).getByText('Use the aggregated view')).toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: 'Approve' })).toBeNull();
    expect(fetchInbox).toHaveBeenCalledWith('REJECTED');
  });

  it('says when a list could not be loaded', async () => {
    fetchInbox.mockRejectedValue(new Error('The server is not answering.'));
    renderPage('/requests?tab=inbox');

    expect(await screen.findByRole('alert')).toHaveTextContent('The server is not answering.');
  });
});

describe('AccessRequestsPage my requests', () => {
  it('lets the requester withdraw a pending request and see who decides it', async () => {
    fetchMine.mockResolvedValue([request({ mayDecide: false })]);
    withdraw.mockResolvedValue(request({ status: 'WITHDRAWN' }));
    renderPage('/requests?tab=mine');

    const card = await detail();
    expect(within(card).getByText('Decided by team Finance.')).toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: 'Approve' })).toBeNull();

    fireEvent.click(within(card).getByRole('button', { name: 'Withdraw' }));

    await waitFor(() => expect(withdraw).toHaveBeenCalledWith('req-1'));
  });

  it('never offers a decision on one’s own request, even to someone who may decide', async () => {
    fetchMine.mockResolvedValue([request({ mayDecide: true })]);
    renderPage('/requests?tab=mine');

    await detail();
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Withdraw' })).toBeInTheDocument();
  });

  it('says plainly when nobody else can decide an administrator’s own request', async () => {
    fetchMine.mockResolvedValue([request({ mayDecide: false, approvers: [], stranded: true })]);
    renderPage('/requests?tab=mine');

    const card = await detail();
    expect(within(card).getByText('Nobody can decide this yet')).toBeInTheDocument();
    expect(within(card).getByText(/there is no other platform administrator/)).toBeInTheDocument();
    expect(within(card).queryByText('Waiting for a decision')).toBeNull();
  });

  it('offers no withdraw once a request is decided', async () => {
    fetchMine.mockResolvedValue([
      request({ status: 'APPROVED', decidedBy: 'owner_o', requestedDays: 1 }),
    ]);
    renderPage('/requests?tab=mine');

    const card = await detail();
    expect(within(card).getByText('1 day')).toBeInTheDocument();
    expect(within(card).getByText('approved it')).toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: 'Withdraw' })).toBeNull();
  });

  it('says "until revoked" for an open-ended ask', async () => {
    fetchMine.mockResolvedValue([request({ requestedDays: null })]);
    renderPage('/requests?tab=mine');

    const card = await detail();
    expect(within(card).getByText('Until revoked')).toBeInTheDocument();
  });

  it('says why a withdraw did not go through', async () => {
    fetchMine.mockResolvedValue([request()]);
    withdraw.mockRejectedValue(new Error('Already decided.'));
    renderPage('/requests?tab=mine');

    fireEvent.click(await screen.findByRole('button', { name: 'Withdraw' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Already decided.');
  });
});
