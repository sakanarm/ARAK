import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import NewRequestPage, { standingOf } from './NewRequestPage';
import type { Eligibility } from '../../api/accessRequests';
import type { Purpose, PurposeListing } from '../../api/purposes';
import type { RequestTemplate } from '../../api/requestTemplates';

const requestAccess = jest.fn();
const fetchEligibility = jest.fn();
const fetchAssets = jest.fn();
const fetchTemplate = jest.fn();

jest.mock('../../api/requestTemplates', () => {
  const actual = jest.requireActual('../../api/requestTemplates');
  return { ...actual, fetchEffectiveTemplate: (...args: unknown[]) => fetchTemplate(...args) };
});

jest.mock('../../api/accessRequests', () => {
  const actual = jest.requireActual('../../api/accessRequests');
  return {
    ...actual,
    requestAccess: (...args: unknown[]) => requestAccess(...args),
    fetchEligibility: (...args: unknown[]) => fetchEligibility(...args),
  };
});

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
  fetchAssets: (...args: unknown[]) => fetchAssets(...args),
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

const BUILT_IN: RequestTemplate = {
  id: null,
  name: 'Built-in',
  description: null,
  scopeFqn: null,
  matchFacets: [],
  enabled: true,
  form: {
    purposes: [],
    purposeRequired: false,
    durations: [7, 30, 90],
    defaultDays: 30,
    maxDays: null,
    allowUntilRevoked: true,
    referenceLabel: null,
    referenceRequired: false,
    minReasonLength: 1,
    guidance: null,
  },
};

const DPIA: RequestTemplate = {
  ...BUILT_IN,
  id: 'tpl-1',
  name: 'PII under sales',
  form: { ...BUILT_IN.form, maxDays: 60, referenceLabel: 'DPIA no.', referenceRequired: true },
};

const fqn = (name: string) => `demo-pg.salesdb.sales.${name}`;

function table(name: string) {
  return {
    id: `id-${name}`,
    fqn: fqn(name),
    name,
    displayName: null,
    assetType: 'TABLE',
    parentFqn: 'demo-pg.salesdb.sales',
    description: null,
    tier: null,
    certification: null,
    dataSource: 'demo-pg',
    columnCount: 3,
    taggedColumnCount: 0,
    facets: [],
    owners: [],
    childCount: 0,
  };
}

function eligible(assetFqn: string, overrides: Partial<Eligibility> = {}): Eligibility {
  return {
    assetFqn,
    readable: false,
    requestable: true,
    blockedBy: null,
    approvers: [],
    openRequestId: null,
    ...overrides,
  };
}

const TABLES = ['orders', 'customers', 'refunds', 'stock', 'invoices'].map(table);

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <NewRequestPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  register = undefined;
  fetchAssets.mockResolvedValue({ items: TABLES, total: TABLES.length, limit: 50, offset: 0 });
  fetchTemplate.mockImplementation((f: string) => Promise.resolve(f === fqn('customers') ? DPIA : BUILT_IN));
  fetchEligibility.mockImplementation((f: string) =>
    Promise.resolve(
      f === fqn('refunds')
        ? eligible(f, { readable: true })
        : f === fqn('stock')
          ? eligible(f, { openRequestId: 'req-7' })
          : f === fqn('invoices')
            ? eligible(f, { requestable: false, blockedBy: 'No finance outside HQ' })
            : eligible(f)
    )
  );
});

async function choose(...names: string[]) {
  for (const name of names) {
    fireEvent.click(await screen.findByLabelText(`Choose ${fqn(name)}`));
  }
}

describe('NewRequestPage', () => {
  it('sends one request per table it can ask for, and leaves the rest out saying why', async () => {
    // invoices: a policy would still refuse after a grant. It is sent all the
    // same; this caller may change policies, so they are told which one.
    requestAccess.mockImplementation((ask: { assetFqn: string }) =>
      ask.assetFqn === fqn('orders')
        ? Promise.resolve({ ticket: 'AR-101' })
        : Promise.reject(new Error('The workflow is switched off'))
    );
    renderPage();
    await choose('orders', 'customers', 'refunds', 'stock', 'invoices');

    const tray = screen.getByRole('list', { name: 'Chosen tables' });
    expect(await within(tray).findByText(/already read it/)).toBeInTheDocument();
    expect(within(tray).getByRole('link', { name: 'See the request' })).toHaveAttribute(
      'href',
      '/requests?tab=mine&status=&id=req-7'
    );
    // invoices is asked for like the others; the policy is for whoever decides.
    expect(within(tray).queryByText(/No finance outside HQ|would not let you in/)).toBeNull();
    expect(await within(tray).findAllByText('Will be requested')).toHaveLength(3);

    fireEvent.change(screen.getByLabelText('Why you need them'), { target: { value: 'Quarterly review' } });
    const send = screen.getByRole('button', { name: 'Send 3 requests' });
    // The customers table's template wants a DPIA number, so the whole form does.
    expect(send).toBeDisabled();
    expect(screen.getByRole('status')).toHaveTextContent('Give the DPIA no.');
    fireEvent.change(screen.getByLabelText('Reference'), { target: { value: 'DPIA-9' } });
    fireEvent.click(screen.getByRole('button', { name: '30 days' }));
    await waitFor(() => expect(send).toBeEnabled());
    fireEvent.click(send);

    expect(await screen.findByText('1 of 3 requests sent')).toBeInTheDocument();
    expect(requestAccess).toHaveBeenCalledTimes(3);
    // The reference goes only where the table's template asks one.
    expect(requestAccess.mock.calls[0][0]).toEqual({
      assetFqn: fqn('orders'),
      sourceId: null,
      reason: 'Quarterly review',
      purpose: null,
      days: 30,
    });
    expect(requestAccess.mock.calls[1][0]).toMatchObject({ assetFqn: fqn('customers'), reference: 'DPIA-9' });
    expect(requestAccess.mock.calls[2][0]).toMatchObject({ assetFqn: fqn('invoices') });
    expect(screen.getByRole('link', { name: 'AR-101' })).toHaveAttribute('href', '/requests/AR-101');
    expect(screen.getAllByText('The workflow is switched off')).toHaveLength(2);
  });

  it('sends a blocked table like any other, and leaves out one that is not connected', async () => {
    // What a plain requester is told: no policy name, only that one may need to change.
    fetchEligibility.mockImplementation((f: string) =>
      Promise.resolve(
        f === fqn('orders')
          ? eligible(f, { requestable: false, blockedKind: 'DENIED', blockedBy: null })
          : eligible(f, { queryable: false, requestable: false, approvers: [] })
      )
    );
    requestAccess.mockResolvedValue({ ticket: 'AR-102' });
    renderPage();
    await choose('orders', 'stock');

    const tray = screen.getByRole('list', { name: 'Chosen tables' });
    expect(await within(tray).findByText('Will be requested')).toBeInTheDocument();
    expect(within(tray).queryByText(/policy may also need to change|still refuses/)).toBeNull();
    expect(within(tray).getByText(/Not connected: no data source in ARAK maps it/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Why you need them'), { target: { value: 'Quarterly review' } });
    const send = screen.getByRole('button', { name: 'Send request' });
    await waitFor(() => expect(send).toBeEnabled());
    fireEvent.click(send);

    await waitFor(() => expect(requestAccess).toHaveBeenCalledTimes(1));
    expect(requestAccess.mock.calls[0][0]).toMatchObject({ assetFqn: fqn('orders') });
  });

  it('holds the whole form to the shortest ceiling of the tables chosen', async () => {
    renderPage();
    await choose('orders', 'customers');
    expect(await screen.findByText(/at most 60/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Until revoked' })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Why you need them'), { target: { value: 'Review' } });
    fireEvent.change(screen.getByLabelText('Reference'), { target: { value: 'D-1' } });
    // 90 is past the customers table's ceiling, so it is not offered.
    expect(screen.queryByRole('button', { name: '90 days' })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Days'), { target: { value: '90' } });
    expect(screen.getByRole('status')).toHaveTextContent('between 1 and 60 days');
    expect(screen.getByRole('button', { name: 'Send 2 requests' })).toBeDisabled();
  });

  it('offers the register, and holds the days to the purpose chosen', async () => {
    register = { purposes: REGISTER, canEdit: false };
    requestAccess.mockResolvedValue({ ticket: 'AR-103' });
    renderPage();
    await choose('orders');
    fireEvent.change(screen.getByLabelText('Why you need them'), { target: { value: 'Case 4411' } });
    expect(screen.getByRole('button', { name: 'Until revoked' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Purpose/ }));
    expect(screen.queryByRole('option', { name: /Support/ })).toBeNull();
    fireEvent.click(await screen.findByRole('option', { name: /^Fraud analysis/ }));

    expect(screen.getByLabelText('Days')).toHaveValue('14');
    expect(screen.queryByRole('button', { name: 'Until revoked' })).toBeNull();
    expect(screen.queryByRole('button', { name: '30 days' })).toBeNull();
    fireEvent.change(screen.getByLabelText('Days'), { target: { value: '20' } });
    const send = screen.getByRole('button', { name: 'Send request' });
    expect(send).toBeDisabled();
    expect(screen.getByRole('status')).toHaveTextContent('14 days');

    fireEvent.click(screen.getByRole('button', { name: '7 days' }));
    await waitFor(() => expect(send).toBeEnabled());
    fireEvent.click(send);
    await waitFor(() => expect(requestAccess).toHaveBeenCalledTimes(1));
    expect(requestAccess.mock.calls[0][0]).toMatchObject({ purpose: 'fraud-analysis', days: 7 });
  });

  it('removes a table from the tray and sends nothing when none can be asked for', async () => {
    renderPage();
    await choose('refunds', 'orders');
    fireEvent.click(screen.getByRole('button', { name: `Remove ${fqn('orders')}` }));
    expect(screen.getByLabelText(`Choose ${fqn('orders')}`)).not.toBeChecked();
    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent('None of the chosen tables can be asked for')
    );
    expect(screen.getByRole('button', { name: 'Send request' })).toBeDisabled();
    expect(requestAccess).not.toHaveBeenCalled();
  });
});

describe('standingOf', () => {
  it('orders the reasons a table is left out', () => {
    const e = eligible('t');
    expect(standingOf(undefined, null).kind).toBe('checking');
    expect(standingOf(undefined, new Error('down'))).toEqual({ kind: 'failed', message: 'down' });
    expect(standingOf({ ...e, readable: true, openRequestId: 'r' }, null).kind).toBe('readable');
    expect(standingOf({ ...e, openRequestId: 'r', requestable: false }, null).kind).toBe('requested');
    expect(standingOf({ ...e, requestable: false, blockedBy: 'P' }, null)).toEqual({ kind: 'blocked', by: 'P' });
    // The policy's title, when the server gave one, over its engine words.
    expect(
      standingOf({ ...e, requestable: false, blockedBy: 'p-1', blockedByPolicy: 'Finance at HQ' }, null)
    ).toEqual({ kind: 'blocked', by: 'Finance at HQ' });
    expect(standingOf({ ...e, requestable: false, blockedKind: 'DENIED' }, null)).toEqual({ kind: 'blocked', by: null });
    expect(standingOf({ ...e, queryable: false, requestable: false, openRequestId: 'r' }, null).kind).toBe('unconnected');
    expect(standingOf(e, null).kind).toBe('ready');
  });
});
