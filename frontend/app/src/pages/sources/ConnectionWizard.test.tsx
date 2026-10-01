import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import ConnectionWizard from './ConnectionWizard';
import type { ImportReport, Source, SourceEngineInfo } from '../../api/sources';

const fetchEngines = jest.fn();
const createSource = jest.fn();
const updateSource = jest.fn();
const testSourceTarget = jest.fn();
const previewScope = jest.fn();
const importSourceCatalog = jest.fn();

jest.mock('../../api/sources', () => ({
  ...jest.requireActual('../../api/sources'),
  fetchEngines: () => fetchEngines(),
  createSource: (input: unknown) => createSource(input),
  updateSource: (id: string, input: unknown) => updateSource(id, input),
  testSourceTarget: (input: unknown) => testSourceTarget(input),
  previewScope: (input: unknown) => previewScope(input),
  importSourceCatalog: (id: string) => importSourceCatalog(id),
}));

jest.mock('../../api/client', () => ({
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
  apiClient: { get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn() },
}));

const ENGINES: SourceEngineInfo[] = [
  { id: 'POSTGRES', displayName: 'PostgreSQL', defaultPort: 5432, supportsSchemas: true, supportsSecureViews: true, proxyCapabilities: [] },
  { id: 'MSSQL', displayName: 'SQL Server', defaultPort: 1433, supportsSchemas: true, supportsSecureViews: true, proxyCapabilities: [] },
  { id: 'MYSQL', displayName: 'MySQL', defaultPort: 3306, supportsSchemas: false, supportsSecureViews: false, proxyCapabilities: [] },
] as SourceEngineInfo[];

const STORED: Source = {
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
  tableScope: { mode: 'ALL', include: [], exclude: [{ match: 'STARTS_WITH', value: 'tmp_' }] },
  enabled: true,
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
  assetCount: 0,
} as Source;

const REPORT: ImportReport = {
  source: 'sales-pg',
  tables: 12,
  columns: 140,
  newTables: 12,
  newColumns: [],
  missingTables: [],
  excluded: 3,
  outOfScope: [],
};

const onDone = jest.fn();

function show(source: Source | null = null) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <ConnectionWizard onDone={onDone} source={source} />
    </QueryClientProvider>
  );
}

async function toConnectStep() {
  show();
  fireEvent.click(await screen.findByRole('radio', { name: /PostgreSQL/ }));
  fireEvent.click(screen.getByRole('button', { name: 'Next' }));
}

function type(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

const EXCLUDE = 'Always exclude where the name';

function addExclusion(value: string) {
  type(`${EXCLUDE}: text`, value);
  fireEvent.keyDown(screen.getByLabelText(`${EXCLUDE}: text`), { key: 'Enter' });
}

beforeEach(() => {
  jest.clearAllMocks();
  fetchEngines.mockResolvedValue(ENGINES);
});

describe('ConnectionWizard', () => {
  it('starts on the engine and moves on once one is chosen', async () => {
    show();
    const steps = screen.getByRole('list', { name: 'Steps' });
    expect(within(steps).getByRole('button', { name: /Choose the engine/ })).toHaveAttribute(
      'aria-current',
      'step'
    );
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();

    const postgres = await screen.findByRole('radio', { name: /PostgreSQL/ });
    expect(postgres).toHaveTextContent('Port 5432');
    fireEvent.click(postgres);
    expect(postgres).toHaveAttribute('aria-checked', 'true');
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    expect(screen.getByLabelText('Host')).toBeInTheDocument();
    // The engine's port is offered, not left for somebody to look up.
    expect(screen.getByLabelText('Port')).toHaveValue(5432);
    expect(screen.getByText('1 required')).toBeInTheDocument();

    // Back to the engine is allowed from the stepper.
    fireEvent.click(within(steps).getByRole('button', { name: /Choose the engine/ }));
    expect(screen.getByRole('radio', { name: /PostgreSQL/ })).toHaveAttribute('aria-checked', 'true');
  });

  it('offers MySQL with its own port, and says what a blank database means there', async () => {
    show();
    const mysql = await screen.findByRole('radio', { name: /MySQL/ });
    expect(mysql).toHaveTextContent('Port 3306');
    // No schema level: a MySQL database is where the other two have a schema.
    expect(mysql).toHaveTextContent('database › table');
    fireEvent.click(mysql);
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    expect(screen.getByLabelText('Port')).toHaveValue(3306);
    expect(
      screen.getByText('The database the import reads. Blank reads every database the login can see.')
    ).toBeInTheDocument();
  });

  it('does not carry a secure view over to an engine that has none', async () => {
    updateSource.mockImplementation(async (id, input) => ({ ...STORED, ...input, id }));
    show({ ...STORED, defaultEnforcementMode: 'SECURE_VIEW' });
    expect(screen.getByText(/^Secure view · /)).toBeInTheDocument();

    const steps = screen.getByRole('list', { name: 'Steps' });
    fireEvent.click(within(steps).getByRole('button', { name: /Choose the engine/ }));
    fireEvent.click(await screen.findByRole('radio', { name: /MySQL/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    // The server refuses the mode on MySQL; better said here than at Save.
    expect(screen.getByText(/^Not enforced yet · /)).toBeInTheDocument();
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    });
    expect(updateSource).toHaveBeenCalledWith(
      'src-1',
      expect.objectContaining({ engine: 'MYSQL', defaultEnforcementMode: 'NONE' })
    );
  });

  it('holds Register until a name, a host and a credential are given', async () => {
    await toConnectStep();
    const register = screen.getByRole('button', { name: 'Register' });
    expect(register).toBeDisabled();

    type('Name', 'sales-pg');
    type('Host', 'db.example.test');
    expect(screen.getByText('Complete')).toBeInTheDocument();
    expect(register).toBeDisabled();

    type('Username', 'reader');
    type('Password', 'not-a-real-secret');
    expect(screen.getByText('Given')).toBeInTheDocument();
    expect(register).toBeEnabled();
  });

  it('shows and hides the password on the eye button', async () => {
    await toConnectStep();
    const password = screen.getByLabelText('Password');
    expect(password).toHaveAttribute('type', 'password');
    fireEvent.click(screen.getByRole('button', { name: 'Show password' }));
    expect(password).toHaveAttribute('type', 'text');
    fireEvent.click(screen.getByRole('button', { name: 'Hide password' }));
    expect(password).toHaveAttribute('type', 'password');
  });

  it('adds, refuses a repeat of, and removes an exclusion', async () => {
    await toConnectStep();
    // Folded while it scans everything, with the default said on its face.
    const toggle = screen.getByRole('button', { name: /Scope & options/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(toggle).toHaveTextContent('All tables and views this login can read are in scope.');
    fireEvent.click(toggle);

    addExclusion('tmp_');
    const chips = screen.getByRole('list', { name: EXCLUDE });
    expect(within(chips).getByText('tmp_')).toBeInTheDocument();
    expect(screen.getByTestId('scope-sentence')).toHaveTextContent(
      'All tables and views this login can read, except where the name starts with “tmp_”.'
    );
    expect(screen.getAllByText('1 rule').length).toBeGreaterThan(0);

    addExclusion('TMP_');
    expect(screen.getByText('That rule is already in the list.')).toBeInTheDocument();
    expect(within(chips).getAllByRole('listitem')).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: 'Remove: name starts with “tmp_”' }));
    expect(screen.queryByRole('list', { name: EXCLUDE })).not.toBeInTheDocument();
    expect(screen.getAllByText('Scanning all').length).toBeGreaterThan(0);
  });

  it('refuses to register a scope that names no table', async () => {
    await toConnectStep();
    type('Name', 'sales-pg');
    type('Host', 'db.example.test');
    type('Username', 'reader');
    type('Password', 'not-a-real-secret');
    fireEvent.click(screen.getByRole('button', { name: /Scope & options/ }));

    fireEvent.click(screen.getByRole('button', { name: 'Only specific tables' }));
    expect(
      screen.getByText('Name at least one table to scan, or choose to scan all tables.')
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Check against the database' })).toBeDisabled();

    type('Scan tables where the name: text', 'dim_');
    fireEvent.click(screen.getAllByRole('button', { name: 'Add' })[0]);
    expect(screen.getByRole('button', { name: 'Register' })).toBeEnabled();
    expect(screen.getByTestId('scope-sentence')).toHaveTextContent(
      'Only tables and views whose name starts with “dim_”.'
    );
  });

  it('checks the scope against the database', async () => {
    previewScope.mockResolvedValue({
      total: 20,
      inScope: 17,
      excluded: 3,
      inScopeSample: ['public.orders', 'public.customers'],
      excludedSample: ['public.tmp_load'],
    });
    await toConnectStep();
    type('Host', 'db.example.test');
    fireEvent.click(screen.getByRole('button', { name: /Scope & options/ }));
    addExclusion('tmp_');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Check against the database' }));
    });

    expect(previewScope).toHaveBeenCalledWith(
      expect.objectContaining({
        host: 'db.example.test',
        engine: 'POSTGRES',
        tableScope: { mode: 'ALL', include: [], exclude: [{ match: 'STARTS_WITH', value: 'tmp_' }] },
      })
    );
    expect(
      await screen.findByText('17 of 20 tables and views are in scope · 3 left out')
    ).toBeInTheDocument();
    expect(within(screen.getByRole('list', { name: 'Read' })).getByText('and 15 more')).toBeInTheDocument();
    expect(within(screen.getByRole('list', { name: 'Left out' })).getByText('public.tmp_load')).toBeInTheDocument();

    // A changed scope makes the count a claim about another scope.
    addExclusion('bak_');
    expect(screen.queryByText(/tables and views are in scope ·/)).not.toBeInTheDocument();
  });

  it('reports a test connection', async () => {
    testSourceTarget.mockResolvedValue({
      reachable: true,
      productName: 'PostgreSQL',
      engineVersion: '16.4',
      message: '',
      millis: 42,
    });
    await toConnectStep();
    expect(screen.getByRole('button', { name: 'Test connection' })).toBeDisabled();
    type('Host', 'db.example.test');
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Test connection' }));
    });
    expect(await screen.findByText('PostgreSQL 16.4 answered in 42 ms.')).toBeInTheDocument();

    // Measured against the old host, so it goes when the host does.
    type('Host', 'db2.example.test');
    expect(screen.queryByText(/answered in/)).not.toBeInTheDocument();
  });

  it('registers with the scope, then imports the catalog', async () => {
    createSource.mockImplementation(async (input) => ({ ...STORED, ...input, id: 'src-new' }));
    importSourceCatalog.mockResolvedValue(REPORT);
    await toConnectStep();
    type('Name', 'sales-pg');
    type('Host', 'db.example.test');
    type('Username', 'reader');
    type('Password', 'not-a-real-secret');
    fireEvent.click(screen.getByRole('button', { name: /Scope & options/ }));
    addExclusion(' _bak ');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Register' }));
    });

    expect(createSource).toHaveBeenCalledWith(
      expect.objectContaining({
        name: 'sales-pg',
        engine: 'POSTGRES',
        port: 5432,
        username: 'reader',
        tableScope: { mode: 'ALL', include: [], exclude: [{ match: 'STARTS_WITH', value: '_bak' }] },
      })
    );
    expect(await screen.findByText('sales-pg is registered.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Import tables now' }));
    });
    expect(importSourceCatalog).toHaveBeenCalledWith('src-new');
    expect(await screen.findByText('Left out by the scope')).toBeInTheDocument();
    expect(screen.getByText('140')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    expect(onDone).toHaveBeenCalled();
  });

  it('opens an edit on Connect with what is stored, and saves it', async () => {
    updateSource.mockImplementation(async (id, input) => ({ ...STORED, ...input, id }));
    show({ ...STORED, omServiceFqn: 'sales_service', secureObjectPattern: '{table}_secure' });

    expect(screen.getByRole('region', { name: 'Edit sales-pg' })).toBeInTheDocument();
    expect(screen.getByLabelText('Host')).toHaveValue('db.example.test');
    expect(screen.getByText('Stored')).toBeInTheDocument();
    // Open because it narrows the import, so the rule is in view.
    expect(screen.getByRole('button', { name: /Scope & options/ })).toHaveAttribute(
      'aria-expanded',
      'true'
    );
    expect(within(screen.getByRole('list', { name: EXCLUDE })).getByText('tmp_')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Back' })).not.toBeInTheDocument();
    expect(screen.getByText(/secure objects in sec\.<table>_secure · linked to sales_service$/)).toBeInTheDocument();

    const save = screen.getByRole('button', { name: 'Save changes' });
    expect(save).toBeEnabled();
    await act(async () => {
      fireEvent.click(save);
    });

    expect(updateSource).toHaveBeenCalledWith(
      'src-1',
      expect.objectContaining({
        // Sent back as it came, which the server reads as "keep it".
        credentialRef: 'fernet:stored',
        password: '',
        tableScope: STORED.tableScope,
      })
    );
    expect(createSource).not.toHaveBeenCalled();
    expect(await screen.findByText('Changes to sales-pg are saved.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Skip for now' }));
    expect(onDone).toHaveBeenCalled();
  });
});
