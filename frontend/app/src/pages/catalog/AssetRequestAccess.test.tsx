import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AssetAccessAction } from './AssetRequestAccess';
import type { Eligibility } from '../../api/accessRequests';

const fetchEligibility = jest.fn();
const requestAccess = jest.fn();

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

function renderBox(assetType = 'TABLE') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AssetAccessAction asset={{ fqn: FQN, assetType }} />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  fetchEligibility.mockReset();
  requestAccess.mockReset();
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

  it('says so quietly for somebody who can already read it', async () => {
    fetchEligibility.mockResolvedValue(eligibility({ readable: true }));
    renderBox();

    expect(await screen.findByText('You can read this')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request access' })).toBeNull();
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

  it('names the policy in the way when a grant would not help, and offers no form', async () => {
    fetchEligibility.mockResolvedValue(
      eligibility({ requestable: false, blockedBy: 'PII deny for contractors' })
    );
    renderBox();

    fireEvent.click(await screen.findByRole('button', { name: 'Request access' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('PII deny for contractors')).toBeInTheDocument();
    expect(within(dialog).queryByLabelText('Why you need it')).toBeNull();
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
