import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import NewRequestPage, { standingOf } from './NewRequestPage';
import type { Eligibility } from '../../api/accessRequests';
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
    expect(within(tray).getByText(/No finance outside HQ still refuses/)).toBeInTheDocument();
    expect(await within(tray).findAllByText('Will be requested')).toHaveLength(2);

    fireEvent.change(screen.getByLabelText('Why you need them'), { target: { value: 'Quarterly review' } });
    const send = screen.getByRole('button', { name: 'Send 2 requests' });
    // The customers table's template wants a DPIA number, so the whole form does.
    expect(send).toBeDisabled();
    expect(screen.getByRole('status')).toHaveTextContent('Give the DPIA no.');
    fireEvent.change(screen.getByLabelText('Reference'), { target: { value: 'DPIA-9' } });
    fireEvent.click(screen.getByRole('button', { name: '30 days' }));
    await waitFor(() => expect(send).toBeEnabled());
    fireEvent.click(send);

    expect(await screen.findByText('1 of 2 requests sent')).toBeInTheDocument();
    expect(requestAccess).toHaveBeenCalledTimes(2);
    // The reference goes only where the table's template asks one.
    expect(requestAccess.mock.calls[0][0]).toEqual({
      assetFqn: fqn('orders'),
      sourceId: null,
      reason: 'Quarterly review',
      purpose: null,
      days: 30,
    });
    expect(requestAccess.mock.calls[1][0]).toMatchObject({ assetFqn: fqn('customers'), reference: 'DPIA-9' });
    expect(screen.getByRole('link', { name: 'AR-101' })).toHaveAttribute('href', '/requests/AR-101');
    expect(screen.getByText('The workflow is switched off')).toBeInTheDocument();
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
    expect(standingOf(e, null).kind).toBe('ready');
  });
});
