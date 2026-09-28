import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import RequestAccess from './RequestAccess';
import type { AccessRequest, Refusal, Route } from '../../api/accessRequests';
import type { Purpose, PurposeListing } from '../../api/purposes';
import type { RequestTemplate } from '../../api/requestTemplates';
import type { Concern } from '../../api/sensitiveData';

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

function purpose(key: string, name: string, extra: Partial<Purpose> = {}): Purpose {
  return {
    key,
    name,
    description: null,
    legalBasis: null,
    sensitiveAllowed: false,
    owner: null,
    maxDays: null,
    status: 'ACTIVE',
    createdBy: 'system',
    createdAt: '2026-09-28T03:00:00Z',
    updatedBy: 'system',
    updatedAt: '2026-09-28T03:00:00Z',
    ...extra,
  };
}

const REGISTER = [
  purpose('fraud-analysis', 'Fraud analysis', { legalBasis: 'LEGITIMATE_INTEREST', maxDays: 14 }),
  purpose('reporting', 'Reporting'),
  purpose('support', 'Support', { status: 'RETIRED' }),
];

// Without a register the forms ask as they did before there was one.
let register: PurposeListing | undefined;

jest.mock('../../api/purposes', () => ({
  ...jest.requireActual('../../api/purposes'),
  usePurposes: () => ({ data: register, isLoading: false, isError: false }),
}));

// What the sensitive data rule says of the tables and the purpose named (M31b).
let concernsFor: (assets: (string | null | undefined)[], purpose: string | null | undefined) => Concern[] =
  () => [];

jest.mock('../../api/sensitiveData', () => ({
  ...jest.requireActual('../../api/sensitiveData'),
  usePurposeConcerns: (assets: (string | null | undefined)[], purpose: string | null | undefined) =>
    concernsFor(assets, purpose),
}));

function concern(table: string, purposeKey: string | null, mode: 'WARN' | 'ENFORCE'): Concern {
  return {
    table,
    labels: ['PII.Sensitive'],
    purpose: purposeKey,
    purposeName: purposeKey,
    mode,
    message: `${table} holds sensitive data (PII.Sensitive), and ${purposeKey ?? 'nothing'} is not a purpose sensitive data may be used for`,
  };
}

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
  register = undefined;
  concernsFor = () => [];
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

describe('RequestAccess with the register of purposes', () => {
  beforeEach(() => {
    register = { purposes: REGISTER, canEdit: false };
  });

  it('offers the register where the template lists nothing, and sends the key', async () => {
    requestAccess.mockResolvedValue(sent());
    renderBox(refusal());
    const { reason, send } = openForm();
    fireEvent.change(reason, { target: { value: 'Audit' } });

    fireEvent.click(screen.getByRole('button', { name: /Purpose/ }));
    expect(screen.queryByRole('option', { name: /Support/ })).toBeNull();
    fireEvent.click(await screen.findByRole('option', { name: /^Reporting/ }));
    fireEvent.click(send);

    await screen.findByText(/Request sent/);
    expect(requestAccess.mock.calls[0][0]).toMatchObject({ purpose: 'reporting', days: 30 });
  });

  it('holds the days to the purpose’s longest access, and offers no until revoked', async () => {
    requestAccess.mockResolvedValue(sent());
    renderBox(refusal(), 'Fraud-Analysis');
    const { reason, days, send } = openForm();
    fireEvent.change(reason, { target: { value: 'Case 4411' } });

    // The Query page's purpose, by its key; 30 days is past what it allows.
    expect(days.value).toBe('14');
    expect(screen.getByText('Access for this purpose lasts at most 14 days.')).toBeInTheDocument();
    const durations = screen.getByRole('group', { name: 'Durations' });
    expect(within(durations).queryByRole('button', { name: 'Until revoked' })).toBeNull();
    expect(within(durations).queryByRole('button', { name: '30 days' })).toBeNull();

    fireEvent.change(days, { target: { value: '20' } });
    expect(send).toBeDisabled();
    expect(screen.getByText('Between 1 and 14 days.')).toBeInTheDocument();
    fireEvent.click(within(durations).getByRole('button', { name: '7 days' }));
    fireEvent.click(send);

    await screen.findByText(/Request sent/);
    expect(requestAccess.mock.calls[0][0]).toMatchObject({ purpose: 'fraud-analysis', days: 7 });
  });

  it('warns before sending when the rule only warns, and still sends', async () => {
    concernsFor = (assets, key) => (key === 'reporting' ? [concern(assets[0]!, key, 'WARN')] : []);
    requestAccess.mockResolvedValue(sent());
    renderBox(refusal());
    const { reason, send } = openForm();
    fireEvent.change(reason, { target: { value: 'Audit' } });
    expect(screen.queryByRole('note', { name: 'Sensitive data' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /Purpose/ }));
    fireEvent.click(await screen.findByRole('option', { name: /^Reporting/ }));

    const note = screen.getByRole('note', { name: 'Sensitive data' });
    expect(note).toHaveTextContent(`${FQN} holds sensitive data (PII.Sensitive), and reporting`);
    expect(note).toHaveTextContent('You can still ask; whoever decides is told.');
    expect(send).toBeEnabled();
    fireEvent.click(send);

    await screen.findByText(/Request sent/);
    expect(requestAccess.mock.calls[0][0]).toMatchObject({ purpose: 'reporting' });
  });

  it('holds the request back when the rule is enforced, until a purpose it allows is chosen', async () => {
    concernsFor = (assets, key) => (key === 'fraud-analysis' ? [] : [concern(assets[0]!, key ?? null, 'ENFORCE')]);
    requestAccess.mockResolvedValue(sent());
    renderBox(refusal());
    const { reason, send } = openForm();
    fireEvent.change(reason, { target: { value: 'Audit' } });

    const alert = screen.getByRole('alert', { name: 'Refused for this purpose' });
    expect(alert).toHaveTextContent('A request for it would be refused.');
    expect(send).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: /Purpose/ }));
    fireEvent.click(await screen.findByRole('option', { name: /^Fraud analysis/ }));

    expect(screen.queryByRole('alert', { name: 'Refused for this purpose' })).toBeNull();
    await waitFor(() => expect(send).toBeEnabled());
    fireEvent.click(send);
    await screen.findByText(/Request sent/);
    expect(requestAccess.mock.calls[0][0]).toMatchObject({ purpose: 'fraud-analysis' });
  });

  it('gives the days back when a purpose without a limit is chosen instead', async () => {
    renderBox(refusal(), 'fraud-analysis');
    const { days } = openForm();
    expect(days.value).toBe('14');

    fireEvent.click(screen.getByRole('button', { name: /Purpose/ }));
    fireEvent.click(await screen.findByRole('option', { name: /^Reporting/ }));
    expect(screen.getByRole('button', { name: 'Until revoked' })).toBeInTheDocument();
    fireEvent.change(days, { target: { value: '' } });
    expect(screen.getByText(/until revoked/)).toBeInTheDocument();
  });

  it('starts on no purpose when the Query page’s one was retired', async () => {
    requestAccess.mockResolvedValue(sent());
    renderBox(refusal(), 'support');
    const { reason, send } = openForm();
    fireEvent.change(reason, { target: { value: 'Audit' } });
    fireEvent.click(send);

    await screen.findByText(/Request sent/);
    expect(requestAccess.mock.calls[0][0]).toMatchObject({ purpose: null });
  });

  it('names a template’s purposes as the register does, and leaves out the retired ones', async () => {
    fetchEffectiveTemplate.mockResolvedValue({
      ...PII,
      form: { ...PII.form, purposes: ['fraud-analysis', 'support', 'Regulatory report'] },
    });
    renderBox(refusal());
    openForm();
    await screen.findByText('PII tables');

    fireEvent.click(screen.getByRole('button', { name: /Purpose/ }));
    expect(await screen.findByRole('option', { name: /^Fraud analysis/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /^Regulatory report/ })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /Support/ })).toBeNull();
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

  it('still lets a requester a policy would refuse send the request, without a word about the policy', async () => {
    // A DENY, or a stricter layer, outranks any grant -- but the owner may get
    // that policy changed, so the request still goes. The requester is told
    // what kind of rule it is and nothing about which one.
    requestAccess.mockResolvedValue(sent());
    renderBox(refusal({ requestable: false, blockedKind: 'NOT_ADMITTED', blockedBy: null }));

    expect(screen.getByText('You can still ask the owner')).toBeInTheDocument();
    const { reason, send } = openForm();
    // The form is the same as anybody's: what else is in the way is for
    // whoever decides the request.
    expect(screen.queryByText(/policy may also need to change|still refuses/)).toBeNull();
    fireEvent.change(reason, { target: { value: 'Month-end reconciliation' } });
    expect(send).toBeEnabled();
    fireEvent.click(send);

    await waitFor(() => expect(requestAccess).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/Request sent/)).toBeInTheDocument();
  });

  it('does not name the policy in the way in the form, even to somebody who could change it', () => {
    renderBox(
      refusal({
        requestable: false,
        blockedKind: 'NOT_ADMITTED',
        blockedBy: 'no-pii-abroad',
        blockedByPolicy: 'No PII outside Thailand',
        blockedByPolicyId: 'pol-7',
        blockedByReason: 'only staff in Thailand are let in',
      })
    );

    openForm();
    // They see it when they review the request, where it is theirs to act on.
    expect(screen.queryByRole('link', { name: 'No PII outside Thailand' })).toBeNull();
    expect(screen.queryByText(/would not let you in|still refuses/)).toBeNull();
    expect(screen.getByRole('button', { name: 'Send request' })).toBeInTheDocument();
  });

  it('offers nothing to ask for on a table that is not connected', () => {
    renderBox(refusal({ requestable: false, queryable: false }));

    expect(screen.getByText(/is not connected: no data source/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Request access/ })).toBeNull();
  });

  it('offers nothing for a run on somebody else’s behalf', () => {
    // The server says neither whether a grant would do nor what kind of rule
    // is in the way: it is not the caller's refusal.
    const { container } = renderBox(refusal({ requestable: false, blockedBy: null }));
    expect(container).toBeEmptyDOMElement();
  });

  it('offers nothing when the refusal names no table', () => {
    const { container } = renderBox({ message: 'Only SELECT statements are allowed.' });
    expect(container).toBeEmptyDOMElement();
  });
});
