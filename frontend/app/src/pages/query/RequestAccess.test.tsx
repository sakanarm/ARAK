import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import RequestAccess from './RequestAccess';
import type { AccessRequest, Refusal } from '../../api/accessRequests';

const requestAccess = jest.fn();

jest.mock('../../api/accessRequests', () => {
  const actual = jest.requireActual('../../api/accessRequests');
  return {
    ...actual,
    requestAccess: (...args: unknown[]) => requestAccess(...args),
  };
});

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
}));

const FQN = 'demo-pg.salesdb.sales.customer';
const OWNER = { type: 'user', name: 'owner_o', direct: true, inheritedFrom: null };

function refusal(overrides: Partial<Refusal> = {}): Refusal {
  return {
    message: `Access to ${FQN} is denied: no policy allows it.`,
    assetFqn: FQN,
    requestable: true,
    blockedBy: null,
    approvers: [OWNER],
    openRequestId: null,
    ...overrides,
  };
}

function sent(overrides: Partial<AccessRequest> = {}): AccessRequest {
  return {
    id: 'req-1',
    assetFqn: FQN,
    requesterId: 'p-1',
    requesterUsername: 'analyst_a',
    dataSourceId: 'src-1',
    reason: 'Month-end reconciliation',
    purpose: null,
    requestedDays: 30,
    attemptedSql: 'SELECT * FROM sales.customer',
    deniedBy: 'denied',
    status: 'PENDING',
    createdAt: '2026-09-24T03:00:00Z',
    decidedBy: null,
    decidedAt: null,
    decisionNote: null,
    grantId: null,
    approvers: [OWNER],
    mayDecide: false,
    ...overrides,
  };
}

function renderBox(r: Refusal, purpose: string | null = null) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <RequestAccess
          purpose={purpose}
          refusal={r}
          sourceId="src-1"
          sql="SELECT * FROM sales.customer"
        />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function openForm() {
  fireEvent.click(screen.getByRole('button', { name: 'Request access from the owner' }));
  return {
    reason: screen.getByLabelText('Why you need it'),
    days: screen.getByLabelText('Days') as HTMLInputElement,
    send: screen.getByRole('button', { name: 'Send request' }),
  };
}

beforeEach(() => {
  requestAccess.mockReset();
});

describe('RequestAccess', () => {
  it('offers the request, and says who will decide it', () => {
    renderBox(refusal());

    expect(screen.getByRole('button', { name: 'Request access from the owner' })).toBeInTheDocument();
    expect(screen.getByText('Decided by owner_o.')).toBeInTheDocument();
  });

  it('says an administrator decides when the catalog names no owner', () => {
    renderBox(refusal({ approvers: [] }));

    expect(
      screen.getByText(
        'No owner is recorded for this table, so a platform administrator decides.'
      )
    ).toBeInTheDocument();
  });

  it('sends the statement that ran and the refusal along with the reason', async () => {
    requestAccess.mockResolvedValue(sent());
    renderBox(refusal(), 'fraud-analysis');
    const { reason, days, send } = openForm();

    fireEvent.change(reason, { target: { value: '  Month-end reconciliation ' } });
    fireEvent.change(days, { target: { value: '14' } });
    fireEvent.click(send);

    await screen.findByText(/Request sent/);
    expect(requestAccess).toHaveBeenCalledWith({
      assetFqn: FQN,
      sourceId: 'src-1',
      reason: 'Month-end reconciliation',
      purpose: 'fraud-analysis',
      days: 14,
      attemptedSql: 'SELECT * FROM sales.customer',
      deniedBy: `Access to ${FQN} is denied: no policy allows it.`,
    });
    expect(screen.getByRole('link', { name: 'Access requests' })).toHaveAttribute(
      'href',
      '/requests'
    );
    expect(screen.queryByRole('form', { name: 'Request access' })).toBeNull();
  });

  it('asks for thirty days unless told otherwise, and blank means until revoked', async () => {
    requestAccess.mockResolvedValue(sent({ requestedDays: null }));
    renderBox(refusal());
    const { reason, days, send } = openForm();
    expect(days.value).toBe('30');

    fireEvent.change(reason, { target: { value: 'Audit' } });
    fireEvent.change(days, { target: { value: '' } });
    expect(screen.getByText(/until revoked/)).toBeInTheDocument();
    fireEvent.click(send);

    await waitFor(() => expect(requestAccess).toHaveBeenCalled());
    expect(requestAccess.mock.calls[0][0]).toMatchObject({ days: null, purpose: null });
  });

  it('will not send without a reason', () => {
    renderBox(refusal());
    const { reason, send } = openForm();

    expect(send).toBeDisabled();
    fireEvent.change(reason, { target: { value: '   ' } });
    expect(send).toBeDisabled();
    fireEvent.submit(screen.getByRole('form', { name: 'Request access' }));

    expect(requestAccess).not.toHaveBeenCalled();
  });

  it.each([
    ['0', false],
    ['366', false],
    ['1', true],
    ['365', true],
  ])('treats %s days as valid: %s', (value, valid) => {
    renderBox(refusal());
    const { reason, days, send } = openForm();
    fireEvent.change(reason, { target: { value: 'Audit' } });

    fireEvent.change(days, { target: { value } });

    if (valid) {
      expect(send).toBeEnabled();
      expect(screen.queryByText('Between 1 and 365 days, or blank.')).toBeNull();
    } else {
      expect(send).toBeDisabled();
      expect(screen.getByText('Between 1 and 365 days, or blank.')).toBeInTheDocument();
    }
  });

  it('keeps only the digits of what is typed as days', () => {
    renderBox(refusal());
    const { days } = openForm();

    fireEvent.change(days, { target: { value: '1e2-' } });

    expect(days.value).toBe('12');
  });

  it('shows why a request failed, and keeps the form to try again', async () => {
    requestAccess.mockRejectedValue(new Error('This table is already readable for you.'));
    renderBox(refusal());
    const { reason, send } = openForm();
    fireEvent.change(reason, { target: { value: 'Audit' } });

    fireEvent.click(send);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This table is already readable for you.'
    );
    expect(screen.getByRole('button', { name: 'Send request' })).toBeEnabled();
  });

  it('can be cancelled back to the offer', () => {
    renderBox(refusal());
    openForm();

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.getByRole('button', { name: 'Request access from the owner' })).toBeInTheDocument();
  });

  it('does not offer a second request while one is open', () => {
    renderBox(refusal({ openRequestId: 'req-9' }));

    expect(screen.getByText(/Already requested/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request access from the owner' })).toBeNull();
    expect(screen.getByRole('link', { name: 'Access requests' })).toBeInTheDocument();
  });

  it('names the policy in the way when a grant from the owner would not help', () => {
    // A DENY, or a stricter layer, outranks any grant: sending this to the
    // owner would ask them for something they cannot give.
    renderBox(refusal({ requestable: false, blockedBy: 'No PII outside Thailand' }));

    expect(screen.getByText(/Asking the owner would not help/)).toBeInTheDocument();
    expect(screen.getByText('No PII outside Thailand')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request access from the owner' })).toBeNull();
  });

  it('offers nothing for a run on somebody else’s behalf', () => {
    const { container } = renderBox(refusal({ requestable: false, blockedBy: null }));
    expect(container).toBeEmptyDOMElement();
  });

  it('offers nothing when the refusal names no table', () => {
    const { container } = renderBox({ message: 'Only SELECT statements are allowed.' });
    expect(container).toBeEmptyDOMElement();
  });
});
