import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AssetAccessAction, AssetStanding } from './AssetRequestAccess';
import type { Eligibility } from '../../api/accessRequests';
import { useAssistStore } from '../../assist/assistStore';

const fetchEligibility = jest.fn();
const requestAccess = jest.fn();
const fetchSources = jest.fn();
const mockNavigate = jest.fn();

jest.mock('react-router-dom', () => ({
  ...jest.requireActual('react-router-dom'),
  useNavigate: () => mockNavigate,
}));

jest.mock('../../api/sources', () => ({
  fetchSources: (...args: unknown[]) => fetchSources(...args),
}));

jest.mock('../../api/accessRequests', () => {
  const actual = jest.requireActual('../../api/accessRequests');
  return {
    ...actual,
    fetchEligibility: (...args: unknown[]) => fetchEligibility(...args),
    requestAccess: (...args: unknown[]) => requestAccess(...args),
  };
});

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
}));

const FQN = 'demo-pg.salesdb.sales.customer';

function eligibility(overrides: Partial<Eligibility> = {}): Eligibility {
  return {
    assetFqn: FQN,
    readable: false,
    requestable: true,
    blockedBy: null,
    approvers: [{ type: 'user', name: 'owner_o', direct: true, inheritedFrom: null }],
    openRequestId: null,
    ...overrides,
  };
}

function renderBox(assetType = 'TABLE', Component = AssetAccessAction, querySource?: string) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <Component asset={{ fqn: FQN, assetType, querySource }} />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  fetchEligibility.mockReset();
  requestAccess.mockReset();
  fetchSources.mockReset();
  mockNavigate.mockReset();
  useAssistStore.setState({ sql: null });
});

describe('AssetAccessAction', () => {
  it('puts Request access in the header of a table the reader cannot read', async () => {
    fetchEligibility.mockResolvedValue(eligibility());
    renderBox();

    expect(await screen.findByRole('button', { name: 'Request access' })).toBeInTheDocument();
    expect(fetchEligibility).toHaveBeenCalledWith(FQN);
    // The form waits behind the button: the header is not the place for it.
    expect(screen.queryByLabelText('Why you need it')).toBeNull();
  });

  it('shows the workflow a request would walk, step by step, in the dialog', async () => {
    fetchEligibility.mockResolvedValue(
      eligibility({
        route: {
          workflowName: 'Finance tables',
          stages: [
            { step: 1, name: 'Owner approval', rule: 'ANY', minApprovals: null, onReject: 'VETO', approvers: ['Owners of the table'] },
            { step: 2, name: 'Security', rule: 'ALL', minApprovals: null, onReject: 'VETO', approvers: ['Team Security'] },
          ],
        },
      })
    );
    renderBox();

    fireEvent.click(await screen.findByRole('button', { name: 'Request access' }));
    const dialog = await screen.findByRole('dialog');
    const route = within(dialog).getByRole('group', { name: 'Approval route' });
    expect(within(route).getAllByRole('listitem')).toHaveLength(2);
    expect(within(route).getByText('Asks Team Security')).toBeInTheDocument();
    expect(
      within(dialog).getByText(/It goes through the “Finance tables” workflow: Owner approval, then Security\./)
    ).toBeInTheDocument();
  });

  it('says nobody can decide it when the server says the request would be stranded', async () => {
    fetchEligibility.mockResolvedValue(eligibility({ approvers: [], stranded: true }));
    renderBox();

    fireEvent.click(await screen.findByRole('button', { name: 'Request access' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/nobody can decide this yet/)).toBeInTheDocument();
  });

  it('opens the form in a dialog and sends only the reason and the days', async () => {
    fetchEligibility.mockResolvedValue(eligibility());
    requestAccess.mockResolvedValue({
      id: 'req-1',
      assetFqn: FQN,
      status: 'PENDING',
      approvers: eligibility().approvers,
    });
    renderBox();

    fireEvent.click(await screen.findByRole('button', { name: 'Request access' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/Decided by owner_o\./)).toBeInTheDocument();
    expect(within(dialog).getByText(/Say what the data is for/)).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText('Why you need it'), {
      target: { value: ' Quarterly churn review ' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Send request' }));

    await waitFor(() =>
      expect(requestAccess).toHaveBeenCalledWith({
        assetFqn: FQN,
        sourceId: null,
        reason: 'Quarterly churn review',
        purpose: null,
        days: 30,
        attemptedSql: null,
        deniedBy: null,
      })
    );
    // Nothing ran, so there is no statement or refusal to send along -- and
    // the dialog stays up to say the request went.
    expect(await within(dialog).findByText('Request sent')).toBeInTheDocument();
    expect(within(dialog).getByRole('link', { name: 'Access requests' })).toHaveAttribute(
      'href',
      '/requests'
    );
  });

  it('cancel closes the dialog without sending', async () => {
    fetchEligibility.mockResolvedValue(eligibility());
    renderBox();

    fireEvent.click(await screen.findByRole('button', { name: 'Request access' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(requestAccess).not.toHaveBeenCalled();
  });

  it('offers Query to somebody who can already read it, not a second badge', async () => {
    fetchEligibility.mockResolvedValue(eligibility({ readable: true }));
    renderBox();

    expect(await screen.findByRole('button', { name: 'Query' })).toBeInTheDocument();
    expect(screen.queryByText('You can read this')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Request access' })).toBeNull();
  });

  it('opens the console on the table and its source, without running anything', async () => {
    fetchEligibility.mockResolvedValue(eligibility({ readable: true }));
    fetchSources.mockResolvedValue([
      { id: 'src-other', name: 'another-source' },
      { id: 'src-1', name: 'demo-pg' },
    ]);
    renderBox('TABLE', AssetAccessAction, 'demo-pg');

    const query = await screen.findByRole('button', { name: 'Query' });
    // Pressed until the source list has landed, as a person pressing after
    // the page settles would find it.
    await waitFor(() => {
      fireEvent.click(query);
      expect(useAssistStore.getState().sql?.sourceId).toBe('src-1');
    });

    expect(useAssistStore.getState().sql).toMatchObject({
      text: 'SELECT *\nFROM sales.customer',
      sourceId: 'src-1',
    });
    expect(mockNavigate).toHaveBeenCalledWith('/query');
  });

  it('still opens the console when the source is not one it can name', async () => {
    fetchEligibility.mockResolvedValue(eligibility({ readable: true }));
    renderBox();

    fireEvent.click(await screen.findByRole('button', { name: 'Query' }));

    expect(fetchSources).not.toHaveBeenCalled();
    expect(useAssistStore.getState().sql).toMatchObject({
      text: 'SELECT *\nFROM sales.customer',
      sourceId: null,
    });
    expect(mockNavigate).toHaveBeenCalledWith('/query');
  });

  it('links to the open request instead of offering a second', async () => {
    fetchEligibility.mockResolvedValue(eligibility({ openRequestId: 'req-9' }));
    renderBox();

    expect(await screen.findByRole('link', { name: 'Access requested' })).toHaveAttribute(
      'href',
      '/requests'
    );
    expect(screen.queryByRole('button', { name: 'Request access' })).toBeNull();
  });

  it('lets a requester a policy would refuse fill in and send the request, telling them nothing about it', async () => {
    // What else stands in the way is for whoever decides; the form is the
    // same as anybody's, even for somebody who could change the policy.
    fetchEligibility.mockResolvedValue(
      eligibility({
        requestable: false,
        blockedKind: 'DENIED',
        blockedBy: 'PII deny for contractors',
        blockedByPolicy: 'PII deny for contractors',
        blockedByPolicyId: 'pol-1',
      })
    );
    requestAccess.mockResolvedValue({ id: 'req-2', assetFqn: FQN, status: 'PENDING', approvers: [] });
    renderBox();

    fireEvent.click(await screen.findByRole('button', { name: 'Request access' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByText(/PII deny for contractors|would not let you in|policy may also need/)).toBeNull();
    expect(within(dialog).queryByRole('link', { name: /polic/i })).toBeNull();
    fireEvent.change(within(dialog).getByLabelText('Why you need it'), { target: { value: 'Audit' } });
    const send = within(dialog).getByRole('button', { name: 'Send request' });
    expect(send).toBeEnabled();
    fireEvent.click(send);

    await waitFor(() => expect(requestAccess).toHaveBeenCalledTimes(1));
    expect(await within(dialog).findByText('Request sent')).toBeInTheDocument();
  });

  it('says a table no source maps is not connected, and offers no request', async () => {
    fetchEligibility.mockResolvedValue(
      eligibility({ queryable: false, readable: false, requestable: false, approvers: [] })
    );
    renderBox();

    expect(await screen.findByText('Not connected — nothing to query yet')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request access' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Query' })).toBeNull();
  });

  it('draws nothing when refused with no reason it may say', async () => {
    fetchEligibility.mockResolvedValue(eligibility({ requestable: false, blockedBy: null }));
    const { container } = renderBox();

    await waitFor(() => expect(fetchEligibility).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('does not ask about a schema, which has no rows to grant', () => {
    const { container } = renderBox('DATABASE_SCHEMA');

    expect(fetchEligibility).not.toHaveBeenCalled();
    expect(container).toBeEmptyDOMElement();
  });

  it('draws nothing when the answer could not be had', async () => {
    fetchEligibility.mockRejectedValue(new Error('down'));
    const { container } = renderBox();

    await waitFor(() => expect(fetchEligibility).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});

describe('AssetStanding', () => {
  it.each([
    [{ readable: true }, 'You can query', /allowed now/],
    [{ openRequestId: 'req-9' }, 'You requested access', /waiting for an answer/],
    [{}, 'You can request', /an approved request lets you in/],
    [{ requestable: false, blockedKind: 'NOT_ADMITTED' as const }, 'You have no access', /you can still request access/],
    [{ requestable: false, blockedKind: 'DENIED' as const }, 'You have no access', /you can still request access/],
  ])('says where the reader stands, in a badge whose tooltip says what it means: %o', async (overrides, badge, tip) => {
    fetchEligibility.mockResolvedValue(eligibility(overrides));
    renderBox('TABLE', AssetStanding);

    const shown = await screen.findByText(badge);
    expect(shown.parentElement).toHaveAttribute('title', expect.stringMatching(tip));
  });

  it('has no badge for a table that is not connected', async () => {
    fetchEligibility.mockResolvedValue(eligibility({ queryable: false, requestable: false }));
    renderBox('TABLE', AssetStanding);

    await waitFor(() => expect(fetchEligibility).toHaveBeenCalled());
    expect(screen.queryByText(/^You /)).toBeNull();
  });
});
