import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import PolicyListPage from './PolicyListPage';
import type { StoredPolicy } from '../../api/policies';

const fetchPolicies = jest.fn();
const countPolicies = jest.fn();
const mockNavigate = jest.fn();

jest.mock('react-router-dom', () => ({
  ...jest.requireActual('react-router-dom'),
  useNavigate: () => mockNavigate,
}));

jest.mock('../../api/policies', () => ({
  ...jest.requireActual('../../api/policies'),
  fetchPolicies: (...args: unknown[]) => fetchPolicies(...args),
  countPolicies: (...args: unknown[]) => countPolicies(...args),
}));

jest.mock('../../api/sources', () => ({
  ...jest.requireActual('../../api/sources'),
  fetchSources: () =>
    Promise.resolve([{ id: 'src-pg', name: 'demo-pg', defaultEnforcementMode: 'PROXY' }]),
  fetchEngines: () => Promise.resolve([]),
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
    expect(screen.getByText(/Policies of the other kind still apply/)).toBeInTheDocument();
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

  it('says where each policy runs, and in which mode', async () => {
    fetchPolicies.mockResolvedValue([
      {
        ...policy(),
        reach: {
          everyConnection: false,
          connections: [
            { service: 'demo-pg', sourceId: 'src-pg', name: 'demo-pg', engine: 'POSTGRES', mode: 'SECURE_VIEW' },
          ],
        },
      },
      {
        ...policy({ name: 'everywhere', displayName: 'Everywhere' }),
        id: 'p-2',
        reach: { everyConnection: true, connections: [] },
      },
      {
        ...policy({ name: 'om-only', displayName: 'Catalogue only' }),
        id: 'p-3',
        reach: {
          everyConnection: false,
          connections: [{ service: 'om-only', sourceId: null, name: 'om-only', engine: null, mode: null }],
        },
      },
    ]);
    countPolicies.mockResolvedValue(3);
    renderPage();

    const confined = await screen.findByRole('link', { name: /PO readers/ });
    expect(within(confined).getByText('demo-pg')).toBeInTheDocument();
    expect(within(confined).getByText('Secure view')).toBeInTheDocument();
    expect(within(confined).getByText('Subscription')).toBeInTheDocument();

    const every = screen.getByRole('link', { name: /Everywhere/ });
    expect(within(every).getByText('Every connection')).toBeInTheDocument();
    expect(within(every).getByText('Each in its own mode')).toBeInTheDocument();

    const unregistered = screen.getByRole('link', { name: /Catalogue only/ });
    expect(within(unregistered).getByText('Not a registered connection')).toBeInTheDocument();
  });

  it('asks the server for one connection and one mode, and clears them', async () => {
    fetchPolicies.mockResolvedValue([]);
    countPolicies.mockResolvedValue(0);
    renderPage('/policies?source=src-pg&mode=PROXY&offset=15');

    await waitFor(() =>
      expect(fetchPolicies).toHaveBeenLastCalledWith(
        expect.objectContaining({ source: 'src-pg', mode: 'PROXY', offset: 15 })
      )
    );
    expect(countPolicies).toHaveBeenLastCalledWith(
      expect.objectContaining({ source: 'src-pg', mode: 'PROXY' })
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Clear' }));

    await waitFor(() =>
      expect(fetchPolicies).toHaveBeenLastCalledWith(
        expect.objectContaining({ source: '', mode: '', offset: 0 })
      )
    );
  });

  it('asks for the policies written for every connection', async () => {
    fetchPolicies.mockResolvedValue([]);
    countPolicies.mockResolvedValue(0);
    renderPage('/policies?source=any');

    await waitFor(() =>
      expect(fetchPolicies).toHaveBeenLastCalledWith(expect.objectContaining({ source: 'any' }))
    );
    expect(await screen.findByText('No policy matches these filters')).toBeInTheDocument();
    // A filter that finds nothing is not an empty platform, and must not say
    // what an empty platform means for enforcement.
    expect(screen.getByText(/policies outside these filters still apply/)).toBeInTheDocument();
    expect(screen.queryByText(/denies by default/)).toBeNull();
  });

  it('explains an empty platform as deny by default only when nothing narrows the list', async () => {
    fetchPolicies.mockResolvedValue([]);
    countPolicies.mockResolvedValue(0);
    renderPage();

    expect(await screen.findByText('No policies yet')).toBeInTheDocument();
    expect(screen.getByText(/denies by default/)).toBeInTheDocument();
  });

  it('asks which kind of policy before the builder opens', async () => {
    fetchPolicies.mockResolvedValue([]);
    countPolicies.mockResolvedValue(0);
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'New policy' }));
    expect(await screen.findByRole('menuitem', { name: 'Subscription policy' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('menuitem', { name: 'Data policy' }));
    expect(mockNavigate).toHaveBeenCalledWith('/policies/new/data');

    fireEvent.click(screen.getByRole('button', { name: 'New policy' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Subscription policy' }));
    expect(mockNavigate).toHaveBeenLastCalledWith('/policies/new/subscription');
  });
});
