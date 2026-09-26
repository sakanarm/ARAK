import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { SavedQueriesPanel, SaveQueryButton } from './SavedQueries';
import { NameTakenError, sameStatement, type SavedQuery } from '../../api/savedQueries';

const fetchSavedQueries = jest.fn();
const createSavedQuery = jest.fn();
const updateSavedQuery = jest.fn();
const deleteSavedQuery = jest.fn();

jest.mock('../../api/savedQueries', () => {
  const actual = jest.requireActual('../../api/savedQueries');
  return {
    ...actual,
    fetchSavedQueries: (...args: unknown[]) => fetchSavedQueries(...args),
    createSavedQuery: (...args: unknown[]) => createSavedQuery(...args),
    updateSavedQuery: (...args: unknown[]) => updateSavedQuery(...args),
    deleteSavedQuery: (...args: unknown[]) => deleteSavedQuery(...args),
  };
});

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
}));

function saved(overrides: Partial<SavedQuery> = {}): SavedQuery {
  return {
    id: 'q-1',
    owner: 'analyst_a',
    name: 'Monthly sales',
    description: null,
    sourceId: 'src-1',
    sql: 'SELECT * FROM sales.orders',
    shared: false,
    mine: true,
    createdAt: '2026-09-26T03:00:00Z',
    updatedAt: '2026-09-26T03:00:00Z',
    ...overrides,
  };
}

function wrap(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}>{children}</QueryClientProvider>);
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe('SavedQueriesPanel', () => {
  it('lists yours and the shared ones apart, and opening one only hands it to the editor', async () => {
    const mine = saved();
    const theirs = saved({ id: 'q-2', owner: 'analyst_b', name: 'Branch totals', shared: true, mine: false });
    fetchSavedQueries.mockResolvedValue([mine, theirs]);
    const onOpen = jest.fn();
    wrap(<SavedQueriesPanel onOpen={onOpen} openedId={null} />);

    expect(await screen.findByText('Mine')).toBeInTheDocument();
    expect(screen.getByText('Shared with me')).toBeInTheDocument();
    expect(screen.getByText('by analyst_b')).toBeInTheDocument();
    // Somebody else's query cannot be deleted from here.
    expect(screen.queryByRole('button', { name: 'Delete Branch totals' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByText('Branch totals'));
    expect(onOpen).toHaveBeenCalledWith(theirs);
    expect(createSavedQuery).not.toHaveBeenCalled();
    expect(updateSavedQuery).not.toHaveBeenCalled();
  });

  it('searches names and statements, and asks before deleting', async () => {
    fetchSavedQueries.mockResolvedValue([saved(), saved({ id: 'q-3', name: 'Stock', sql: 'SELECT * FROM inv.stock' })]);
    deleteSavedQuery.mockResolvedValue(undefined);
    wrap(<SavedQueriesPanel onOpen={jest.fn()} openedId={null} />);

    await screen.findByText('Stock');
    fireEvent.change(screen.getByLabelText('Search saved queries'), { target: { value: 'inv.stock' } });
    expect(screen.queryByText('Monthly sales')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Delete Stock' }));
    expect(deleteSavedQuery).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(deleteSavedQuery).toHaveBeenCalled());
    expect(deleteSavedQuery.mock.calls[0][0]).toBe('q-3');
  });

  it('says so when nothing is saved', async () => {
    fetchSavedQueries.mockResolvedValue([]);
    wrap(<SavedQueriesPanel onOpen={jest.fn()} openedId={null} />);
    expect(await screen.findByText(/Nothing saved yet/)).toBeInTheDocument();
  });
});

describe('SaveQueryButton', () => {
  async function openDialog() {
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    return screen.findByRole('dialog', { name: 'Save query' });
  }

  it('saves a new query privately unless sharing is ticked', async () => {
    fetchSavedQueries.mockResolvedValue([]);
    const made = saved({ id: 'q-9', name: 'Top customers' });
    createSavedQuery.mockResolvedValue(made);
    const onSaved = jest.fn();
    wrap(<SaveQueryButton onSaved={onSaved} opened={null} sourceId="src-1" sql="SELECT 1" />);

    await openDialog();
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: '  Top customers ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(made));
    expect(createSavedQuery).toHaveBeenCalledWith({
      name: 'Top customers',
      description: null,
      sourceId: 'src-1',
      sql: 'SELECT 1',
      shared: false,
    });
  });

  it('says when the same statement is already saved, whatever its spacing', async () => {
    fetchSavedQueries.mockResolvedValue([saved({ sql: 'SELECT *\n  FROM sales.orders;' })]);
    wrap(<SaveQueryButton onSaved={jest.fn()} opened={null} sourceId="src-1" sql="SELECT * FROM sales.orders" />);

    await openDialog();
    expect(await screen.findByText(/already saved as/)).toHaveTextContent('Monthly sales');
  });

  it('offers to replace a query of the same name instead of saving a second one', async () => {
    fetchSavedQueries.mockResolvedValue([saved()]);
    updateSavedQuery.mockResolvedValue(saved({ sql: 'SELECT 2' }));
    wrap(<SaveQueryButton onSaved={jest.fn()} opened={null} sourceId="src-1" sql="SELECT 2" />);

    await openDialog();
    await waitFor(() => expect(fetchSavedQueries).toHaveBeenCalled());
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'MONTHLY SALES' } });
    expect(await screen.findByRole('alert')).toHaveTextContent('You already have a query called');
    fireEvent.click(screen.getByRole('button', { name: 'Replace it' }));

    await waitFor(() => expect(updateSavedQuery).toHaveBeenCalled());
    expect(updateSavedQuery.mock.calls[0][0]).toBe('q-1');
    expect(createSavedQuery).not.toHaveBeenCalled();
  });

  it('turns a clash the server found into the same offer', async () => {
    fetchSavedQueries.mockResolvedValue([]);
    createSavedQuery.mockRejectedValue(new NameTakenError('You already have a query called "Old"', 'q-7'));
    updateSavedQuery.mockResolvedValue(saved({ id: 'q-7' }));
    wrap(<SaveQueryButton onSaved={jest.fn()} opened={null} sourceId="" sql="SELECT 3" />);

    await openDialog();
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Old' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Replace it' }));

    await waitFor(() => expect(updateSavedQuery).toHaveBeenCalled());
    expect(updateSavedQuery.mock.calls[0][0]).toBe('q-7');
    expect(updateSavedQuery.mock.calls[0][1]).toMatchObject({ name: 'Old', sourceId: null });
  });

  it('opened from one of yours, updates it by default and can keep it and save a copy', async () => {
    const opened = saved();
    fetchSavedQueries.mockResolvedValue([opened]);
    updateSavedQuery.mockResolvedValue(opened);
    createSavedQuery.mockResolvedValue(saved({ id: 'q-2', name: 'Monthly sales (copy)' }));
    wrap(<SaveQueryButton onSaved={jest.fn()} opened={opened} sourceId="src-1" sql="SELECT 5" />);

    await openDialog();
    expect(screen.getByLabelText('Name')).toHaveValue('Monthly sales');
    fireEvent.click(screen.getByRole('button', { name: 'Update' }));
    await waitFor(() => expect(updateSavedQuery).toHaveBeenCalled());
    expect(updateSavedQuery.mock.calls[0][0]).toBe('q-1');

    await openDialog();
    fireEvent.click(screen.getByLabelText(/save this as a new query/));
    expect(screen.getByLabelText('Name')).toHaveValue('Monthly sales (copy)');
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(createSavedQuery).toHaveBeenCalled());
    expect(createSavedQuery.mock.calls[0][0]).toMatchObject({ name: 'Monthly sales (copy)', sql: 'SELECT 5' });
  });

  it('does not offer to update a shared query that is not yours', async () => {
    fetchSavedQueries.mockResolvedValue([]);
    wrap(
      <SaveQueryButton
        onSaved={jest.fn()}
        opened={saved({ mine: false, owner: 'analyst_b', shared: true })}
        sourceId="src-1"
        sql="SELECT 6"
      />
    );
    await openDialog();
    expect(screen.queryByText(/Update/)).not.toBeInTheDocument();
    expect(screen.getByLabelText('Name')).toHaveValue('');
  });
});

describe('sameStatement', () => {
  it('ignores spacing and a trailing semicolon, and nothing else', () => {
    expect(sameStatement('SELECT 1', '  SELECT\n1 ;')).toBe(true);
    expect(sameStatement("SELECT 'a  b'", "SELECT 'a b'")).toBe(true);
    expect(sameStatement('SELECT 1', 'SELECT 2')).toBe(false);
  });
});
