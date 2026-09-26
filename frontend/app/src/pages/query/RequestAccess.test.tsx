import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import RequestAccess from './RequestAccess';
import type { AccessRequest, Refusal, Route } from '../../api/accessRequests';
import type { RequestTemplate } from '../../api/requestTemplates';

const requestAccess = jest.fn();
const fetchEffectiveTemplate = jest.fn();

jest.mock('../../api/requestTemplates', () => {
  const actual = jest.requireActual('../../api/requestTemplates');
  return {
    ...actual,
    fetchEffectiveTemplate: (...args: unknown[]) => fetchEffectiveTemplate(...args),
  };
});

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
    ticket: 'REQ-000001',
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
    stranded: false,
    ...overrides,
  };
}

const BUILT_IN: Route = {
  workflowName: 'Built-in',
  stages: [
    { step: 1, name: 'Owner approval', rule: 'ANY', minApprovals: null, onReject: 'VETO', approvers: ['Owners of the table'] },
  ],
};

const FINANCE: Route = {
  workflowName: 'Finance tables',
  stages: [
    { step: 1, name: 'Owner approval', rule: 'ANY', minApprovals: null, onReject: 'VETO', approvers: ['Owners of the table'] },
    { step: 2, name: 'Security', rule: 'ALL', minApprovals: null, onReject: 'VETO', approvers: ['Team Security'] },
    { step: 2, name: 'Compliance', rule: 'AT_LEAST', minApprovals: 2, onReject: 'QUORUM', approvers: ['ann', 'bob', 'Role Auditor'] },
  ],
};

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

const PII: RequestTemplate = {
  id: 'tpl-1',
  name: 'PII tables',
  description: null,
  scopeFqn: null,
  matchFacets: [],
  enabled: true,
  form: {
    purposes: ['Fraud investigation', 'Regulatory report'],
    purposeRequired: true,
    durations: [7, 14],
    defaultDays: 7,
    maxDays: 30,
    allowUntilRevoked: false,
    referenceLabel: 'DPIA number',
    referenceRequired: true,
    minReasonLength: 20,
    guidance: 'PII: a DPIA number is required.' + String.fromCharCode(10) + '<b>not html</b>',
  },
};

beforeEach(() => {
  requestAccess.mockReset();
  fetchEffectiveTemplate.mockReset();
  // No template configured: the built-in form, as before templates.
  fetchEffectiveTemplate.mockRejectedValue(new Error('none'));
});

describe('RequestAccess on a template', () => {
  async function openTemplated(purpose: string | null = null) {
    fetchEffectiveTemplate.mockResolvedValue(PII);
    renderBox(refusal(), purpose);
    const fields = openForm();
    await screen.findByText('PII tables');
    return fields;
  }

  it('asks what the table’s template asks, and shows its guidance as text', async () => {
    const { days } = await openTemplated();

    expect(fetchEffectiveTemplate).toHaveBeenCalledWith(FQN);
    const guidance = screen.getByRole('note', { name: 'Guidance' });
    // Whatever the template's author typed is text, never markup.
    expect(guidance).toHaveTextContent('<b>not html</b>');
    expect(guidance.querySelector('b')).toBeNull();
    expect(screen.getByLabelText('DPIA number')).toBeInTheDocument();
    expect(screen.getByText('At least 20 characters · 0 so far')).toBeInTheDocument();
    expect(days.value).toBe('7');
    const durations = screen.getByRole('group', { name: 'Durations' });
    expect(within(durations).getByRole('button', { name: '7 days' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(durations).queryByRole('button', { name: 'Until revoked' })).toBeNull();
    expect(screen.getByText(/at most 30/)).toBeInTheDocument();
  });

  it('will not send until every required answer is there', async () => {
    requestAccess.mockResolvedValue(sent());
    const { reason, days, send } = await openTemplated();

    fireEvent.change(reason, { target: { value: 'Investigating case 4411 for fraud' } });
    expect(send).toBeDisabled(); // no purpose, no DPIA number yet
    fireEvent.click(screen.getByRole('button', { name: /Purpose/ }));
    fireEvent.click(await screen.findByRole('option', { name: /Regulatory report/ }));
    expect(send).toBeDisabled();
    fireEvent.change(screen.getByLabelText('DPIA number'), { target: { value: ' DPIA-7 ' } });
    expect(send).toBeEnabled();

    fireEvent.change(days, { target: { value: '31' } });
    expect(send).toBeDisabled();
    expect(screen.getByText('Between 1 and 30 days.')).toBeInTheDocument();
    fireEvent.change(days, { target: { value: '' } });
    expect(send).toBeDisabled(); // until revoked is not offered here
    fireEvent.click(screen.getByRole('button', { name: '14 days' }));
    expect(send).toBeEnabled();

    fireEvent.click(send);
    await screen.findByText(/Request sent/);
    expect(requestAccess.mock.calls[0][0]).toMatchObject({
      purpose: 'Regulatory report',
      days: 14,
      reference: 'DPIA-7',
    });
  });

  it('starts on the Query page’s purpose when the template offers it', async () => {
    requestAccess.mockResolvedValue(sent());
    const { reason } = await openTemplated('fraud INVESTIGATION');
    fireEvent.change(reason, { target: { value: 'Investigating case 4411 for fraud' } });
    fireEvent.change(screen.getByLabelText('DPIA number'), { target: { value: 'D-1' } });

    fireEvent.click(screen.getByRole('button', { name: 'Send request' }));

    await screen.findByText(/Request sent/);
    expect(requestAccess.mock.calls[0][0]).toMatchObject({ purpose: 'Fraud investigation' });
  });

  it('sends no reference when the form asks for none', async () => {
    requestAccess.mockResolvedValue(sent());
    renderBox(refusal());
    const { reason, send } = openForm();
    fireEvent.change(reason, { target: { value: 'Audit' } });
    fireEvent.click(screen.getByRole('button', { name: 'Until revoked' }));

    fireEvent.click(send);

    await screen.findByText(/Request sent/);
    expect(requestAccess.mock.calls[0][0]).not.toHaveProperty('reference');
    expect(requestAccess.mock.calls[0][0]).toMatchObject({ days: null });
  });
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

  it('names the owners while the route is the built-in one', () => {
    renderBox(refusal({ route: BUILT_IN }));

    expect(screen.getByText('Decided by owner_o.')).toBeInTheDocument();
    openForm();
    expect(screen.queryByRole('group', { name: 'Approval route' })).toBeNull();
  });

  it('tells the steps of a configured workflow, and shows each stage in the form', () => {
    renderBox(refusal({ route: FINANCE }));

    // The owners may not be asked at all under a workflow: the steps say who is.
    expect(
      screen.getByText(
        'It goes through the “Finance tables” workflow: Owner approval, then Security and Compliance together.'
      )
    ).toBeInTheDocument();
    expect(screen.queryByText('Decided by owner_o.')).toBeNull();

    openForm();
    const route = screen.getByRole('group', { name: 'Approval route' });
    expect(within(route).getByText('Finance tables')).toBeInTheDocument();
    expect(within(route).getByText('Step 2 · in parallel')).toBeInTheDocument();
    expect(within(route).getByText('Asks Team Security')).toBeInTheDocument();
    expect(within(route).getByText('Asks ann, bob, Role Auditor')).toBeInTheDocument();
    expect(
      within(route).getByText('At least 2 approve · Fails only once 2 approvals are out of reach')
    ).toBeInTheDocument();
    expect(within(route).getAllByRole('listitem')).toHaveLength(2);
  });

  it('says nobody can decide it, whatever the route, when nobody else could', () => {
    renderBox(refusal({ route: FINANCE, stranded: true }));

    expect(screen.getByText(/nobody can decide this yet/)).toBeInTheDocument();
    openForm();
    expect(screen.queryByRole('group', { name: 'Approval route' })).toBeNull();
  });

  it('keeps the route in the note once the request is sent', async () => {
    requestAccess.mockResolvedValue(sent());
    renderBox(refusal({ route: FINANCE }));
    const { reason, send } = openForm();
    fireEvent.change(reason, { target: { value: 'Audit' } });

    fireEvent.click(send);

    expect((await screen.findByText(/Request sent/)).closest('p')).toHaveTextContent(
      /goes through the “Finance tables” workflow: Owner approval, then Security and Compliance together\. You will be let in once it is approved and set up/
    );
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
