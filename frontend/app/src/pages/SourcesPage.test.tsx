import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import SourcesPage from './SourcesPage';
import type { Source, SourceEngineInfo } from '../api/sources';

const fetchSources = jest.fn();
const fetchEngines = jest.fn();
const importSourceCatalog = jest.fn();

jest.mock('../api/sources', () => ({
  ...jest.requireActual('../api/sources'),
  fetchSources: () => fetchSources(),
  fetchEngines: () => fetchEngines(),
  importSourceCatalog: (id: string) => importSourceCatalog(id),
}));

jest.mock('../api/client', () => ({
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
  apiClient: { get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn() },
}));

let admin = true;
jest.mock('../auth/authStore', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) => selector({ hasRole: () => admin }),
}));

const SOURCE: Source = {
  id: 'src-1',
  name: 'sales-pg',
  engine: 'POSTGRES',
  engineVersion: '16.4',
  host: 'db.example.test',
  port: 5432,
  defaultDatabase: 'salesdb',
  credentialRef: 'fernet:stored',
  defaultEnforcementMode: 'NONE',
  omServiceFqn: null,
  secureSchema: 'sec',
  secureObjectPattern: '',
  tableScope: { mode: 'ONLY', include: [{ match: 'STARTS_WITH', value: 'dim_' }], exclude: [] },
  enabled: true,
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
  assetCount: 4,
} as Source;

function show() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <SourcesPage />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  admin = true;
  fetchEngines.mockResolvedValue([
    { id: 'POSTGRES', displayName: 'PostgreSQL', defaultPort: 5432, supportsSchemas: true, proxyCapabilities: [] },
  ] as SourceEngineInfo[]);
  fetchSources.mockResolvedValue([SOURCE]);
});

describe('SourcesPage', () => {
  it('says what each source imports', async () => {
    show();
    expect(
      await screen.findByText('1 rule — Only tables and views whose name starts with “dim_”.')
    ).toBeInTheDocument();
  });

  it('imports a source’s tables from its card', async () => {
    importSourceCatalog.mockResolvedValue({
      source: 'sales-pg',
      tables: 4,
      columns: 31,
      newTables: 0,
      newColumns: ['sales-pg.salesdb.public.dim_customer.email'],
      missingTables: [],
      excluded: 9,
      outOfScope: [],
    });
    show();
    const button = await screen.findByRole('button', { name: 'Import tables' });
    await act(async () => {
      fireEvent.click(button);
    });

    expect(importSourceCatalog).toHaveBeenCalledWith('src-1');
    expect(await screen.findByText('Left out by the scope')).toBeInTheDocument();
    expect(screen.getByText(/One column is new since the last import/)).toBeInTheDocument();
  });

  it('opens the wizard from Register a source', async () => {
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Register a source' }));
    const wizard = screen.getByRole('region', { name: 'Add a new connection' });
    expect(await within(wizard).findByRole('radio', { name: /PostgreSQL/ })).toBeInTheDocument();
    // The list steps aside while the form is open.
    expect(screen.queryByRole('button', { name: 'Import tables' })).not.toBeInTheDocument();

    fireEvent.click(within(wizard).getByRole('button', { name: 'Cancel' }));
    expect(await screen.findByRole('button', { name: 'Import tables' })).toBeInTheDocument();
  });

  it('offers no import to a reader', async () => {
    admin = false;
    show();
    expect(await screen.findByText('sales-pg')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Import tables' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Register a source' })).not.toBeInTheDocument();
  });
});
