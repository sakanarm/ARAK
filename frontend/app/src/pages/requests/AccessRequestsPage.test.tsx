import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import AccessRequestsPage from './AccessRequestsPage';
import type { AccessRequest } from '../../api/accessRequests';

const fetchInbox = jest.fn();
const fetchMine = jest.fn();
const approve = jest.fn();
const reject = jest.fn();
const withdraw = jest.fn();

jest.mock('../../api/accessRequests', () => {
  const actual = jest.requireActual('../../api/accessRequests');
  return {
    ...actual,
    fetchRequestInbox: (...args: unknown[]) => fetchInbox(...args),
    fetchMyRequests: (...args: unknown[]) => fetchMine(...args),
    approveRequest: (...args: unknown[]) => approve(...args),
    rejectRequest: (...args: unknown[]) => reject(...args),
    withdrawRequest: (...args: unknown[]) => withdraw(...args),
  };
});

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
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
    ...overrides,
  };
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AccessRequestsPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function section(name: string) {
  return screen.getByRole('region', { name });
}

beforeEach(() => {
  [fetchInbox, fetchMine, approve, reject, withdraw].forEach((fn) => fn.mockReset());
  fetchInbox.mockResolvedValue([]);
  fetchMine.mockResolvedValue([]);
});

describe('AccessRequestsPage', () => {
  it('asks for what is pending by default, and says so when there is nothing', async () => {
    renderPage();

    expect(
      await screen.findByText(
        'Nothing is waiting for your decision. Requests for tables you own appear here.'
      )
    ).toBeInTheDocument();
    expect(await screen.findByText(/You have not asked for anything/)).toBeInTheDocument();
    expect(fetchInbox).toHaveBeenCalledWith('PENDING');
  });

  it('shows the request as the owner needs to judge it', async () => {
    fetchInbox.mockResolvedValue([request()]);
    renderPage();

    const card = await within(section('Waiting for you')).findByRole('article', {
      name: `Request for ${FQN}`,
    });
    expect(within(card).getByText('analyst_a')).toBeInTheDocument();
    expect(within(card).getByText('Month-end reconciliation')).toBeInTheDocument();
    expect(within(card).getByText('30 days')).toBeInTheDocument();
    expect(within(card).getByText('fraud-analysis')).toBeInTheDocument();
    expect(within(card).getByText('SELECT * FROM sales.customer')).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: FQN })).toHaveAttribute(
      'href',
      `/catalog/${encodeURIComponent(FQN)}`
    );
  });

  it('approves for as long as was asked, with the note', async () => {
    fetchInbox.mockResolvedValue([request()]);
    approve.mockResolvedValue(request({ status: 'APPROVED' }));
    renderPage();
    const days = (await screen.findByLabelText('Grant days')) as HTMLInputElement;
    expect(days.value).toBe('30');

    fireEvent.change(screen.getByLabelText('Note to the requester'), {
      target: { value: ' Until the audit closes ' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));

    await waitFor(() =>
      expect(approve).toHaveBeenCalledWith('req-1', { days: 30, note: 'Until the audit closes' })
    );
    // The lists are fetched again, so the request leaves "pending".
    await waitFor(() => expect(fetchInbox).toHaveBeenCalledTimes(2));
  });

  it('lets the owner give less time than asked, never more', async () => {
    fetchInbox.mockResolvedValue([request({ requestedDays: 30 })]);
    approve.mockResolvedValue(request({ status: 'APPROVED' }));
    renderPage();
    const days = await screen.findByLabelText('Grant days');
    const approveButton = screen.getByRole('button', { name: 'Approve' });

    fireEvent.change(days, { target: { value: '31' } });
    expect(approveButton).toBeDisabled();
    expect(
      screen.getByText('Between 1 and 30 days — no longer than was asked.')
    ).toBeInTheDocument();

    // A bounded ask cannot be turned into a grant until revoked.
    fireEvent.change(days, { target: { value: '' } });
    expect(approveButton).toBeDisabled();

    fireEvent.change(days, { target: { value: '0' } });
    expect(approveButton).toBeDisabled();

    fireEvent.change(days, { target: { value: '7' } });
    expect(approveButton).toBeEnabled();
    fireEvent.click(approveButton);

    await waitFor(() => expect(approve).toHaveBeenCalledWith('req-1', { days: 7, note: null }));
  });

  it('may leave an open-ended ask open-ended, or bound it', async () => {
    fetchInbox.mockResolvedValue([request({ requestedDays: null })]);
    approve.mockResolvedValue(request({ status: 'APPROVED' }));
    renderPage();
    const days = (await screen.findByLabelText('Grant days')) as HTMLInputElement;
    const approveButton = screen.getByRole('button', { name: 'Approve' });
    expect(days.value).toBe('');
    expect(approveButton).toBeEnabled();

    fireEvent.change(days, { target: { value: '366' } });
    expect(approveButton).toBeDisabled();
    expect(
      screen.getByText('Between 1 and 365 days, or blank for until revoked.')
    ).toBeInTheDocument();

    fireEvent.change(days, { target: { value: '' } });
    fireEvent.click(approveButton);
    await waitFor(() => expect(approve).toHaveBeenCalledWith('req-1', { days: null, note: null }));
  });

  it('will not reject without a note to the requester', async () => {
    fetchInbox.mockResolvedValue([request()]);
    reject.mockResolvedValue(request({ status: 'REJECTED' }));
    renderPage();
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
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Approve' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Only the owner of this table can decide.'
    );
  });

  it('draws no decision for a request the server says this person may not decide', async () => {
    fetchInbox.mockResolvedValue([request({ mayDecide: false })]);
    renderPage();

    await screen.findByRole('article', { name: `Request for ${FQN}` });
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
    expect(screen.queryByLabelText('Grant days')).toBeNull();
  });

  it('draws no decision for a request already decided, and shows who decided it', async () => {
    fetchInbox.mockResolvedValue([
      request({
        status: 'REJECTED',
        decidedBy: 'owner_o',
        decidedAt: 'not a date',
        decisionNote: 'Use the aggregated view',
      }),
    ]);
    renderPage();

    const card = await screen.findByRole('article', { name: `Request for ${FQN}` });
    expect(within(card).getByText('Rejected')).toBeInTheDocument();
    expect(within(card).getByText('Decided by')).toBeInTheDocument();
    expect(within(card).getByText('owner_o · not a date — Use the aggregated view')).toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: 'Approve' })).toBeNull();
  });

  it('lets the requester withdraw a pending request and see who decides it', async () => {
    fetchMine.mockResolvedValue([request({ mayDecide: false })]);
    withdraw.mockResolvedValue(request({ status: 'WITHDRAWN' }));
    renderPage();

    const mine = section('Your requests');
    const card = await within(mine).findByRole('article', { name: `Request for ${FQN}` });
    expect(within(card).getByText('Decided by team Finance.')).toBeInTheDocument();
    // The requester side does not name the requester back to them.
    expect(within(card).queryByText('Asked by')).toBeNull();

    fireEvent.click(within(card).getByRole('button', { name: 'Withdraw' }));

    await waitFor(() => expect(withdraw).toHaveBeenCalledWith('req-1'));
  });

  it('offers no withdraw once a request is decided', async () => {
    fetchMine.mockResolvedValue([
      request({ status: 'APPROVED', decidedBy: 'owner_o', requestedDays: 1 }),
    ]);
    renderPage();

    const card = await within(section('Your requests')).findByRole('article');
    expect(within(card).getByText('1 day')).toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: 'Withdraw' })).toBeNull();
  });

  it('says "until revoked" for an open-ended ask', async () => {
    fetchMine.mockResolvedValue([request({ requestedDays: null })]);
    renderPage();

    const card = await within(section('Your requests')).findByRole('article');
    expect(within(card).getByText('Until revoked')).toBeInTheDocument();
  });

  it('says when a list could not be loaded', async () => {
    fetchInbox.mockRejectedValue(new Error('The server is not answering.'));
    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('The server is not answering.');
  });
});
