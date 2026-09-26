import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import PolicyListPage from './PolicyListPage';
import type { StoredPolicy } from '../../api/policies';

const fetchPolicies = jest.fn();
const countPolicies = jest.fn();

jest.mock('../../api/policies', () => ({
  ...jest.requireActual('../../api/policies'),
  fetchPolicies: (...args: unknown[]) => fetchPolicies(...args),
  countPolicies: (...args: unknown[]) => countPolicies(...args),
}));

function policy(extra: Partial<StoredPolicy['document']> = {}): StoredPolicy {
  return {
    id: 'p-1',
    lifecycleState: 'ACTIVE',
    environment: 'dev',
    version: 3,
    createdBy: 'author_a',
    updatedBy: 'author_a',
    updatedAt: new Date().toISOString(),
    document: {
      name: 'po-readers',
      displayName: 'PO readers',
      policyType: 'SUBSCRIPTION',
      scopeLevel: 'TABLE',
      scopeFqn: 'demo-pg.salesdb.procurement.po',
      effect: 'ALLOW',
      selector: {},
      subject: { anyOf: [{ team: 'Procurement' }] },
      ...extra,
    } as StoredPolicy['document'],
  };
}

function renderPage(path = '/policies') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <PolicyListPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('the policy list', () => {
  beforeEach(() => {
    fetchPolicies.mockReset();
    countPolicies.mockReset();
  });

  it('reads each policy back with its scope, state and version', async () => {
    fetchPolicies.mockResolvedValue([policy()]);
    countPolicies.mockResolvedValue(1);
    renderPage();

    expect(await screen.findByText('PO readers')).toBeInTheDocument();
    const row = screen.getByRole('link', { name: /PO readers/ });
    expect(row).toHaveAttribute('href', '/policies/p-1');
    expect(within(row).getByText('Table')).toBeInTheDocument();
    expect(within(row).getByText('Active')).toBeInTheDocument();
    expect(within(row).getByText(/^v3 ·/)).toBeInTheDocument();
    expect(screen.getByText('1 policy')).toBeInTheDocument();
  });

  it('switches kind with the tabs and asks the server for that kind', async () => {
    fetchPolicies.mockResolvedValue([]);
    countPolicies.mockResolvedValue(0);
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Data' }));

    await waitFor(() =>
      expect(fetchPolicies).toHaveBeenLastCalledWith(
        expect.objectContaining({ type: 'DATA', offset: 0 })
      )
    );
    expect(screen.getByRole('button', { name: 'Data' })).toHaveAttribute('aria-current', 'page');
    expect(await screen.findByText('No policy matches these filters')).toBeInTheDocument();
  });

  it('marks a deny, and names a data policy for what it restricts', async () => {
    fetchPolicies.mockResolvedValue([
      policy({ effect: 'DENY' }),
      {
        ...policy({
          name: 'po-mask',
          displayName: 'Mask PO amounts',
          policyType: 'DATA',
          effect: undefined,
          data: { rowFilters: [], columnRules: [{}] },
        } as unknown as Partial<StoredPolicy['document']>),
        id: 'p-2',
      },
    ]);
    countPolicies.mockResolvedValue(2);
    renderPage();

    expect(await screen.findByText('Deny')).toBeInTheDocument();
    expect(screen.getByText(/1 column rule/)).toBeInTheDocument();
  });
});
