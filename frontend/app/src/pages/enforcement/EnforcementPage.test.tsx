import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import EnforcementPage from './EnforcementPage';
import type { SecureViewCandidate, SecureViewPreview } from '../../api/secureViews';

const fetchCandidates = jest.fn();
const dryRun = jest.fn();
const apply = jest.fn();
const rollback = jest.fn();
const history = jest.fn();
let isAdmin = true;

jest.mock('../../api/secureViews', () => ({
  fetchSecureViewCandidates: (...args: unknown[]) => fetchCandidates(...args),
  fetchSecureViewHistory: (...args: unknown[]) => history(...args),
  dryRunSecureView: (...args: unknown[]) => dryRun(...args),
  applySecureView: (...args: unknown[]) => apply(...args),
  rollbackSecureView: (...args: unknown[]) => rollback(...args),
  rowCount: (rows: { subscriptions: unknown[]; entitlements: unknown[]; grants: unknown[] }) =>
    rows.subscriptions.length + rows.entitlements.length + rows.grants.length,
}));

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
}));

jest.mock('../../auth/authStore', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) =>
    selector({ hasRole: () => isAdmin }),
}));

const EMPTY = { subscriptions: [], entitlements: [], grants: [] };

function candidate(overrides: Partial<SecureViewCandidate> = {}): SecureViewCandidate {
  return {
    assetFqn: 'demo-pg.salesdb.sales.customer',
    displayName: 'customer',
    dataSourceId: 'src-1',
    sourceName: 'demo-pg',
    engine: 'postgres',
    defaultMode: 'SECURE_VIEW',
    schema: 'sales',
    table: 'customer',
    secureObject: 'sec.customer',
    status: 'NOT_ENFORCED',
    lastAppliedAt: null,
    lastAppliedBy: null,
    lastError: null,
    ...overrides,
  };
}

function preview(): SecureViewPreview {
  return {
    reviewId: '6f1d9b8e-0000-4000-8000-000000000001',
    expiresAt: '2026-09-24T04:00:00Z',
    assetFqn: 'demo-pg.salesdb.sales.customer',
    dataSourceId: 'src-1',
    sourceName: 'demo-pg',
    engine: 'postgres',
    secureObject: 'sec.customer',
    liveColumns: ['id', 'email', 'salary'],
    uncataloguedColumns: ['salary'],
    principals: 4,
    allowed: 3,
    dryRun: {
      installed: EMPTY,
      rows: {
        desired: EMPTY,
        insert: {
          subscriptions: [{ principal: 'analyst_a', asset: 'x' }],
          entitlements: [],
          grants: [],
        },
        delete: EMPTY,
        notes: [],
      },
      applyScript: 'CREATE OR REPLACE VIEW "sec"."customer" AS SELECT 1',
      rollbackScript: 'DROP VIEW IF EXISTS "sec"."customer"',
      notes: ['compiled for 4 people'],
      warnings: [],
      signature: 'sig',
    },
    warnings: ['Column salary is not in the catalogue yet.'],
  };
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <EnforcementPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  isAdmin = true;
  fetchCandidates.mockResolvedValue([candidate()]);
  history.mockResolvedValue({ assetFqn: 'x', state: null, history: [] });
});

describe('EnforcementPage', () => {
  it('lists the tables with their enforcement status', async () => {
    fetchCandidates.mockResolvedValue([
      candidate(),
      candidate({
        assetFqn: 'demo-pg.salesdb.sales.orders',
        displayName: 'orders',
        table: 'orders',
        status: 'APPLIED',
        lastAppliedBy: 'admin',
        lastAppliedAt: '2026-09-24T03:00:00Z',
      }),
    ]);
    renderPage();

    expect(await screen.findByText('customer')).toBeInTheDocument();
    expect(screen.getByText('orders')).toBeInTheDocument();
    expect(screen.getByText('Not enforced')).toBeInTheDocument();
    expect(screen.getByText('Applied')).toBeInTheDocument();
  });

  it('shows the review from a dry run and applies it by its id alone', async () => {
    dryRun.mockResolvedValue(preview());
    apply.mockResolvedValue({
      state: { status: 'APPLIED' },
      statements: 3,
      inserted: 1,
      deleted: 0,
      notes: [],
    });
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Dry run' }));

    expect(await screen.findByText(/nothing has been written/)).toBeInTheDocument();
    expect(screen.getByText('Column salary is not in the catalogue yet.')).toBeInTheDocument();
    expect(screen.getByText(/CREATE OR REPLACE VIEW/)).toBeInTheDocument();
    expect(dryRun).toHaveBeenCalledWith('demo-pg.salesdb.sales.customer');

    fireEvent.click(screen.getByRole('button', { name: 'Apply this review' }));

    await waitFor(() =>
      expect(apply).toHaveBeenCalledWith(
        'demo-pg.salesdb.sales.customer',
        '6f1d9b8e-0000-4000-8000-000000000001'
      )
    );
    expect(await screen.findByText(/Applied: 3 statements ran/)).toBeInTheDocument();
    expect(screen.queryByText(/nothing has been written/)).not.toBeInTheDocument();
  });

  it('does not offer apply or roll back to someone who is not an administrator', async () => {
    isAdmin = false;
    fetchCandidates.mockResolvedValue([candidate({ status: 'APPLIED' })]);
    dryRun.mockResolvedValue(preview());
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Dry run' }));

    expect(await screen.findByText(/Only a platform administrator/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Apply this review' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Roll back' })).not.toBeInTheDocument();
  });

  it('clears a spent review when apply is refused, and says why', async () => {
    dryRun.mockResolvedValue(preview());
    apply.mockRejectedValue({ message: 'This review is no longer the one that was reviewed.' });
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Dry run' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Apply this review' }));

    expect(
      await screen.findByText('This review is no longer the one that was reviewed.')
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Apply this review' })).not.toBeInTheDocument();
  });

  it('asks before rolling back, and a cancel sends nothing', async () => {
    fetchCandidates.mockResolvedValue([candidate({ status: 'APPLIED' })]);
    rollback.mockResolvedValue({
      state: { status: 'NOT_ENFORCED' },
      statements: 1,
      inserted: 0,
      deleted: 0,
      notes: [],
    });
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Roll back' }));
    expect(screen.getByText(/Drop sec.customer\?/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(rollback).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Roll back' }));
    fireEvent.click(screen.getByRole('button', { name: 'Drop the view' }));

    await waitFor(() => expect(rollback).toHaveBeenCalledWith('demo-pg.salesdb.sales.customer'));
    expect(await screen.findByText(/Rolled back: 1 statement ran/)).toBeInTheDocument();
  });

  it('offers no roll back for a table with no view installed', async () => {
    renderPage();

    expect(await screen.findByRole('button', { name: 'Dry run' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Roll back' })).not.toBeInTheDocument();
  });

  it('passes the search to the server', async () => {
    renderPage();
    await screen.findByText('customer');

    fireEvent.change(screen.getByLabelText('Search tables'), { target: { value: 'ord_' } });

    await waitFor(() => expect(fetchCandidates).toHaveBeenLastCalledWith('ord_'));
  });
});
