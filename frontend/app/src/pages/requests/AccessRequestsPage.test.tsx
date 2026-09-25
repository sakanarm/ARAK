import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import AccessRequestsPage from './AccessRequestsPage';
import type { AccessRequest, RequestNotices, StageView } from '../../api/accessRequests';

const fetchInbox = jest.fn();
const fetchMine = jest.fn();
const fetchOne = jest.fn();
const fetchNotices = jest.fn();
const approve = jest.fn();
const reject = jest.fn();
const withdraw = jest.fn();
const start = jest.fn();
const complete = jest.fn();
const decline = jest.fn();
const fetchPols = jest.fn();

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
    startRequest: (...args: unknown[]) => start(...args),
    completeRequest: (...args: unknown[]) => complete(...args),
    declineRequest: (...args: unknown[]) => decline(...args),
  };
});

jest.mock('../../api/policies', () => ({
  fetchPolicies: (...args: unknown[]) => fetchPols(...args),
}));

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

function stage(overrides: Partial<StageView> = {}): StageView {
  return {
    idx: 0,
    step: 1,
    name: 'Owners',
    rule: 'ANY',
    minApprovals: null,
    onReject: 'VETO',
    approvers: [{ kind: 'ASSET_OWNERS' }],
    pool: [{ username: 'owner_a', via: 'Owners of the table' }],
    fallback: false,
    status: 'OPEN',
    openedAt: '2026-09-24T03:00:00Z',
    settledAt: null,
    votes: [],
    approvals: 0,
    rejections: 0,
    needed: 1,
    stranded: false,
    mayVote: false,
    ...overrides,
  };
}

/** A configuring-ready request: both steps approved, waiting on `me`. */
function approved(overrides: Partial<AccessRequest> = {}): AccessRequest {
  return request({
    status: 'APPROVED',
    mayDecide: false,
    mayConfigure: true,
    workflowName: 'Finance two-step',
    stages: [stage({ status: 'APPROVED', approvals: 1 })],
    configurerPool: [{ username: 'me', via: 'Data custodian' }],
    ...overrides,
  });
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
  [
    fetchInbox,
    fetchMine,
    fetchOne,
    fetchNotices,
    approve,
    reject,
    withdraw,
    start,
    complete,
    decline,
    fetchPols,
  ].forEach((fn) => fn.mockReset());
  fetchInbox.mockResolvedValue([]);
  fetchMine.mockResolvedValue([]);
  fetchNotices.mockResolvedValue(notices());
  fetchPols.mockResolvedValue([]);
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
    // "Open" is three statuses: the inbox is fetched whole and narrowed here.
    expect(fetchInbox).toHaveBeenCalledWith(null);
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
        'Nothing is waiting for you. Requests you approve or configure appear here.'
      )
    ).toBeInTheDocument();
    expect(location).toBe('/requests?tab=inbox');
  });

  it('keeps every open status under "Open", and leaves the finished ones out', async () => {
    fetchInbox.mockResolvedValue([
      request({ id: 'a', status: 'PENDING', assetFqn: 'pg.db.s.ledger' }),
      approved({ id: 'b', assetFqn: 'pg.db.s.orders' }),
      approved({ id: 'c', status: 'IN_PROGRESS', assetFqn: 'pg.db.s.payments', assignee: 'me' }),
      request({ id: 'd', status: 'COMPLETED', assetFqn: 'pg.db.s.refunds' }),
    ]);
    renderPage('/requests?tab=inbox');

    const list = await screen.findByRole('region', { name: 'Inbox' });
    expect(await within(list).findByRole('button', { name: /ledger, Pending/ })).toBeInTheDocument();
    expect(within(list).getByRole('button', { name: /orders, Approved/ })).toBeInTheDocument();
    expect(within(list).getByRole('button', { name: /payments, Configuring/ })).toBeInTheDocument();
    expect(within(list).queryByRole('button', { name: /refunds/ })).toBeNull();
  });

  it('filters the inbox on the server by one status, and one’s own list here', async () => {
    fetchMine.mockResolvedValue([
      request({ id: 'a', status: 'COMPLETED', assetFqn: 'pg.db.s.orders' }),
      request({ id: 'b', status: 'PENDING', assetFqn: 'pg.db.s.ledger' }),
    ]);
    renderPage('/requests?tab=inbox');
    await waitFor(() => expect(fetchInbox).toHaveBeenCalledWith(null));

    fireEvent.click(screen.getByRole('radio', { name: 'Completed' }));
    await waitFor(() => expect(fetchInbox).toHaveBeenCalledWith('COMPLETED'));

    fireEvent.click(screen.getByRole('tab', { name: /My requests/ }));
    const list = await screen.findByRole('region', { name: 'My requests' });
    expect(await within(list).findByRole('button', { name: /orders, Completed/ })).toBeInTheDocument();
    expect(within(list).getByRole('button', { name: /ledger, Pending/ })).toBeInTheDocument();

    fireEvent.click(within(list).getByRole('radio', { name: 'Open' }));
    await waitFor(() => expect(within(list).queryByRole('button', { name: /orders/ })).toBeNull());
    expect(within(list).getByRole('button', { name: /ledger, Pending/ })).toBeInTheDocument();
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

  it('approves with the note, and sets no length — that is for configuring', async () => {
    fetchInbox.mockResolvedValue([request()]);
    approve.mockResolvedValue(request({ status: 'APPROVED' }));
    renderPage('/requests?tab=inbox');
    await screen.findByRole('button', { name: 'Approve' });
    expect(screen.queryByLabelText('Grant days')).toBeNull();
    expect(screen.getByText(/Approving grants nothing yet/)).toBeInTheDocument();
    expect(screen.getByText(/cannot override a DENY or lift a mask/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Note to the requester'), {
      target: { value: ' Until the audit closes ' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));

    await waitFor(() =>
      expect(approve).toHaveBeenCalledWith('req-1', {
        note: 'Until the audit closes',
        stageIdx: null,
      })
    );
    // The list and the counts are fetched again, so the request moves on.
    await waitFor(() => expect(fetchInbox).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(fetchNotices.mock.calls.length).toBeGreaterThanOrEqual(2));
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
      expect(reject).toHaveBeenCalledWith('req-1', 'Use the aggregated view instead', null)
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
    expect(screen.queryByRole('region', { name: 'Configure the request' })).toBeNull();
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

describe('AccessRequestsPage stages', () => {
  const twoSteps = () =>
    request({
      workflowName: 'Finance two-step',
      currentStep: 2,
      stages: [
        stage({
          idx: 0,
          step: 1,
          status: 'APPROVED',
          approvals: 1,
          votes: [
            {
              voter: 'owner_a',
              decision: 'APPROVE',
              override: false,
              note: 'Fine by me',
              votedAt: '2026-09-24T04:00:00Z',
            },
          ],
        }),
        stage({
          idx: 1,
          step: 2,
          name: 'Stewards',
          rule: 'AT_LEAST',
          minApprovals: 2,
          onReject: 'QUORUM',
          approvers: [{ kind: 'DATA_STEWARD' }, { kind: 'TEAM', name: 'Risk' }],
          pool: [
            { username: 'stew_1', via: 'Data steward' },
            { username: 'stew_2', via: 'Team Risk' },
            { username: 'me', via: 'Team Risk' },
          ],
          needed: 2,
          approvals: 1,
          mayVote: true,
          votes: [
            {
              voter: 'admin',
              decision: 'APPROVE',
              override: true,
              note: null,
              votedAt: '2026-09-24T05:00:00Z',
            },
          ],
        }),
        stage({
          idx: 2,
          step: 2,
          name: 'Custodian',
          rule: 'ALL',
          approvers: [{ kind: 'DATA_CUSTODIAN' }],
          pool: [{ username: 'cust', via: 'Data custodian' }],
        }),
      ],
    });

  it('draws each step with its stages, their rules, who was asked and every answer', async () => {
    fetchInbox.mockResolvedValue([twoSteps()]);
    renderPage('/requests?tab=inbox');

    const card = await detail();
    expect(within(card).getByText('Finance two-step')).toBeInTheDocument();
    expect(within(card).getByText('Step 1')).toBeInTheDocument();
    expect(within(card).getByText('Step 2 · in parallel')).toBeInTheDocument();

    const owners = within(card).getByRole('group', { name: 'Stage Owners' });
    expect(within(owners).getByText('Approved')).toBeInTheDocument();
    expect(within(owners).getByText('approved')).toBeInTheDocument();
    expect(within(owners).getByText('Fine by me')).toBeInTheDocument();

    const stewards = within(card).getByRole('group', { name: 'Stage Stewards' });
    expect(within(stewards).getByText('1 of 2 approvals')).toBeInTheDocument();
    expect(
      within(stewards).getByText(
        /At least 2 of 3 approve · A rejection counts only once the approvals can no longer come/
      )
    ).toBeInTheDocument();
    expect(within(stewards).getByText('Asked stew_1, stew_2, me')).toBeInTheDocument();
    expect(within(stewards).getByText('approved as administrator')).toBeInTheDocument();

    // The steps' list row says how far along it is, and that it is this reader's turn.
    const row = screen.getByRole('button', { name: /customer, Pending/ });
    expect(within(row).getByText('Step 2 of 2')).toBeInTheDocument();
    expect(within(row).getByText('Your turn')).toBeInTheDocument();
  });

  it('names the seats of a stage whose step has not opened yet', async () => {
    fetchInbox.mockResolvedValue([
      request({
        stages: [
          stage(),
          stage({
            idx: 1,
            step: 2,
            name: 'Stewards',
            status: 'WAITING',
            approvers: [{ kind: 'DATA_STEWARD' }, { kind: 'USER', name: 'ann' }],
            pool: [],
          }),
        ],
      }),
    ]);
    renderPage('/requests?tab=inbox');

    const stewards = within(await detail()).getByRole('group', { name: 'Stage Stewards' });
    expect(within(stewards).getByText('Not yet')).toBeInTheDocument();
    expect(within(stewards).getByText('Asks Data steward, ann')).toBeInTheDocument();
    expect(within(stewards).queryByText(/approvals?$/)).toBeNull();
  });

  it('says when a stage fell back to the administrators, or cannot pass', async () => {
    fetchInbox.mockResolvedValue([
      request({
        stranded: true,
        stages: [stage({ fallback: true, stranded: true, pool: [] })],
      }),
    ]);
    renderPage('/requests?tab=inbox');

    const card = await detail();
    expect(within(card).getByText(/so the platform administrators were asked/)).toBeInTheDocument();
    expect(within(card).getByText(/Too few people can answer for this stage/)).toBeInTheDocument();
    expect(within(card).getByText('Nobody can decide this yet')).toBeInTheDocument();
  });

  it('answers the stage this reader was asked on, and lets them pick another', async () => {
    fetchInbox.mockResolvedValue([
      request({
        stages: [
          stage({ idx: 0, name: 'Owners', mayVote: true }),
          stage({
            idx: 1,
            name: 'Stewards',
            pool: [{ username: 'me', via: 'Data steward' }],
            mayVote: true,
          }),
        ],
      }),
    ]);
    approve.mockResolvedValue(request());
    renderPage('/requests?tab=inbox');

    const choice = await screen.findByRole('radiogroup', { name: 'Stage you answer' });
    expect(within(choice).getByRole('radio', { name: 'Stewards' })).toHaveAttribute(
      'aria-checked',
      'true'
    );
    expect(screen.queryByText(/You were not asked on/)).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(approve).toHaveBeenCalledWith('req-1', { note: null, stageIdx: 1 }));

    fireEvent.click(within(choice).getByRole('radio', { name: 'Owners' }));
    expect(screen.getByText(/As a platform administrator you can answer for it/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Note to the requester'), {
      target: { value: 'Owner is away' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Reject' }));
    await waitFor(() => expect(reject).toHaveBeenCalledWith('req-1', 'Owner is away', 0));
  });
});

describe('AccessRequestsPage configuring', () => {
  function region() {
    return screen.findByRole('region', { name: 'Configure the request' });
  }

  it('shows who configures an approved request, and lets one of them take it', async () => {
    fetchInbox.mockResolvedValue([approved()]);
    start.mockResolvedValue(approved({ status: 'IN_PROGRESS', assignee: 'me' }));
    renderPage('/requests?tab=inbox');

    const card = await detail();
    expect(within(card).getByText('Approved — waiting to be configured')).toBeInTheDocument();
    expect(within(card).getByText('Configured by me.')).toBeInTheDocument();
    const panel = await region();
    // Nothing to fill in until it is taken.
    expect(within(panel).queryByLabelText('Grant days')).toBeNull();
    expect(within(panel).queryByRole('button', { name: 'Complete' })).toBeNull();

    fireEvent.click(within(panel).getByRole('button', { name: 'Start configuring' }));
    await waitFor(() => expect(start).toHaveBeenCalledWith('req-1'));
  });

  it('grants for as long as was asked or less, never more', async () => {
    fetchInbox.mockResolvedValue([approved({ status: 'IN_PROGRESS', assignee: 'me' })]);
    complete.mockResolvedValue(approved({ status: 'COMPLETED' }));
    renderPage('/requests?tab=inbox');

    const card = await detail();
    expect(within(card).getByText('is configuring it')).toBeInTheDocument();
    const panel = await region();
    const days = within(panel).getByLabelText('Grant days');
    const done = within(panel).getByRole('button', { name: 'Complete' });
    expect(days).toHaveValue(30);
    expect(done).toBeEnabled();

    fireEvent.change(days, { target: { value: '45' } });
    expect(done).toBeDisabled();
    fireEvent.change(days, { target: { value: '0' } });
    expect(done).toBeDisabled();
    fireEvent.change(days, { target: { value: '' } });
    expect(done).toBeDisabled();

    fireEvent.change(days, { target: { value: '10' } });
    fireEvent.click(done);
    await waitFor(() =>
      expect(complete).toHaveBeenCalledWith('req-1', {
        fulfilment: 'GRANT',
        days: 10,
        policyId: null,
        note: null,
      })
    );
  });

  it('leaves an open-ended ask open-ended, up to a year if a length is set', async () => {
    fetchInbox.mockResolvedValue([
      approved({ status: 'IN_PROGRESS', assignee: 'me', requestedDays: null }),
    ]);
    complete.mockResolvedValue(approved({ status: 'COMPLETED' }));
    renderPage('/requests?tab=inbox');

    const panel = await region();
    const days = within(panel).getByLabelText('Grant days');
    const done = within(panel).getByRole('button', { name: 'Complete' });
    expect(days).toHaveValue(null);
    expect(within(panel).getByText(/They asked until revoked/)).toBeInTheDocument();
    fireEvent.change(days, { target: { value: '366' } });
    expect(done).toBeDisabled();
    fireEvent.change(days, { target: { value: '365' } });
    expect(done).toBeEnabled();
    fireEvent.change(days, { target: { value: '' } });

    fireEvent.click(done);
    await waitFor(() =>
      expect(complete).toHaveBeenCalledWith('req-1', {
        fulfilment: 'GRANT',
        days: null,
        policyId: null,
        note: null,
      })
    );
  });

  it('points at a policy, needs a note for it, and says it activates nothing', async () => {
    fetchInbox.mockResolvedValue([approved({ status: 'IN_PROGRESS', assignee: 'me' })]);
    fetchPols.mockResolvedValue([
      {
        id: 'pol-1',
        document: { name: 'Finance readers' },
        lifecycleState: 'DRAFT',
        environment: 'prod',
        version: 1,
        createdBy: 'me',
        updatedBy: null,
        updatedAt: null,
      },
    ]);
    complete.mockResolvedValue(approved({ status: 'COMPLETED' }));
    renderPage('/requests?tab=inbox');

    const panel = await region();
    fireEvent.click(within(panel).getByRole('radio', { name: /I updated a policy/ }));
    expect(within(panel).queryByLabelText('Grant days')).toBeNull();
    expect(within(panel).getByText(/It does not activate it/)).toBeInTheDocument();
    const done = within(panel).getByRole('button', { name: 'Complete' });
    expect(done).toBeDisabled();

    const select = await within(panel).findByRole('combobox', { name: 'Policy' });
    await within(panel).findByRole('option', { name: 'Finance readers (draft)' });
    fireEvent.change(select, { target: { value: 'pol-1' } });
    expect(done).toBeDisabled();

    fireEvent.change(within(panel).getByLabelText('Configuration note'), {
      target: { value: 'Added analyst_a to the readers' },
    });
    expect(done).toBeEnabled();
    fireEvent.click(done);

    await waitFor(() =>
      expect(complete).toHaveBeenCalledWith('req-1', {
        fulfilment: 'POLICY_UPDATED',
        days: null,
        policyId: 'pol-1',
        note: 'Added analyst_a to the readers',
      })
    );
    expect(fetchPols).toHaveBeenCalledWith({ limit: 200 });
  });

  it('takes a policy id by hand when the policies cannot be listed', async () => {
    fetchInbox.mockResolvedValue([approved({ status: 'IN_PROGRESS', assignee: 'me' })]);
    fetchPols.mockRejectedValue(new Error('Forbidden'));
    complete.mockResolvedValue(approved({ status: 'COMPLETED' }));
    renderPage('/requests?tab=inbox');

    const panel = await region();
    fireEvent.click(within(panel).getByRole('radio', { name: /I created a policy/ }));
    const id = await within(panel).findByLabelText('Policy id');
    fireEvent.change(id, { target: { value: ' pol-9 ' } });
    fireEvent.change(within(panel).getByLabelText('Configuration note'), {
      target: { value: 'New row filter for branch 7' },
    });
    fireEvent.click(within(panel).getByRole('button', { name: 'Complete' }));

    await waitFor(() =>
      expect(complete).toHaveBeenCalledWith('req-1', {
        fulfilment: 'POLICY_CREATED',
        days: null,
        policyId: 'pol-9',
        note: 'New row filter for branch 7',
      })
    );
  });

  it('declines only with a reason for the requester', async () => {
    fetchInbox.mockResolvedValue([approved()]);
    decline.mockResolvedValue(approved({ status: 'REJECTED' }));
    renderPage('/requests?tab=inbox');

    const panel = await region();
    const button = within(panel).getByRole('button', { name: 'Decline' });
    expect(button).toBeDisabled();
    fireEvent.change(within(panel).getByLabelText('Configuration note'), {
      target: { value: 'The table is being retired' },
    });
    fireEvent.click(button);

    await waitFor(() =>
      expect(decline).toHaveBeenCalledWith('req-1', 'The table is being retired')
    );
  });

  it('shows why configuring did not go through', async () => {
    fetchInbox.mockResolvedValue([approved()]);
    start.mockRejectedValue(new Error('owner_b is already configuring this request.'));
    renderPage('/requests?tab=inbox');

    fireEvent.click(within(await region()).getByRole('button', { name: 'Start configuring' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'owner_b is already configuring this request.'
    );
  });

  it('offers no configuring to someone the server does not name', async () => {
    fetchInbox.mockResolvedValue([approved({ mayConfigure: false })]);
    renderPage('/requests?tab=inbox');

    await detail();
    expect(screen.queryByRole('region', { name: 'Configure the request' })).toBeNull();
  });

  it('says how a completed request was configured', async () => {
    fetchInbox.mockResolvedValue([
      approved({
        id: 'g',
        status: 'COMPLETED',
        mayConfigure: false,
        completedBy: 'owner_a',
        completedAt: '2026-09-24T06:00:00Z',
        fulfilment: 'GRANT',
        fulfilmentNote: 'Granted for 10 days',
      }),
      approved({
        id: 'p',
        assetFqn: 'pg.db.s.orders',
        status: 'COMPLETED',
        mayConfigure: false,
        completedBy: 'owner_a',
        fulfilment: 'POLICY_CREATED',
        fulfilmentRef: 'pol-1',
      }),
    ]);
    renderPage('/requests?tab=inbox&status=COMPLETED');

    const card = await detail();
    expect(within(card).getByText('Completed')).toBeInTheDocument();
    expect(within(card).getByText('granted access')).toBeInTheDocument();
    expect(within(card).getByText('Granted for 10 days')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /orders, Completed/ }));
    const other = await screen.findByRole('article', { name: 'Request for pg.db.s.orders' });
    expect(within(other).getByText(/created a policy for it/)).toBeInTheDocument();
    expect(within(other).getByRole('link', { name: 'open the policy' })).toHaveAttribute(
      'href',
      '/policies/pol-1'
    );
  });

  it('says who declined to configure a request, and why', async () => {
    fetchInbox.mockResolvedValue([
      approved({
        status: 'REJECTED',
        mayConfigure: false,
        completedBy: 'owner_a',
        completedAt: '2026-09-24T06:00:00Z',
        fulfilmentNote: 'The table is being retired',
      }),
    ]);
    renderPage('/requests?tab=inbox&status=REJECTED');

    const card = await detail();
    expect(within(card).getByText('declined to configure it')).toBeInTheDocument();
    expect(within(card).getByText('The table is being retired')).toBeInTheDocument();
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

  it('never offers configuring one’s own request', async () => {
    fetchMine.mockResolvedValue([approved({ mayConfigure: true })]);
    renderPage('/requests?tab=mine');

    await detail();
    expect(screen.queryByRole('region', { name: 'Configure the request' })).toBeNull();
  });

  it('says plainly when nobody else can decide an administrator’s own request', async () => {
    fetchMine.mockResolvedValue([request({ mayDecide: false, approvers: [], stranded: true })]);
    renderPage('/requests?tab=mine');

    const card = await detail();
    expect(within(card).getByText('Nobody can decide this yet')).toBeInTheDocument();
    expect(within(card).getByText(/there is no other platform administrator/)).toBeInTheDocument();
    expect(within(card).queryByText('Waiting for a decision')).toBeNull();
  });

  it('still lets the requester withdraw once approved, until it is configured', async () => {
    fetchMine.mockResolvedValue([approved({ mayConfigure: false, requestedDays: 1 })]);
    renderPage('/requests?tab=mine');

    const card = await detail();
    expect(within(card).getByText('1 day')).toBeInTheDocument();
    expect(within(card).getByRole('button', { name: 'Withdraw' })).toBeInTheDocument();
  });

  it('offers no withdraw once a request is configured', async () => {
    fetchMine.mockResolvedValue([
      request({ status: 'COMPLETED', decidedBy: 'owner_o', completedBy: 'owner_o' }),
    ]);
    renderPage('/requests?tab=mine');

    const card = await detail();
    expect(within(card).getByText('approved it')).toBeInTheDocument();
    expect(within(card).getByText('granted access')).toBeInTheDocument();
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
