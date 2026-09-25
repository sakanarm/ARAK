import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import QueryLogPage from './QueryLogPage';
import type { QueryLogPage as Page, QueryLogRow } from '../../api/audit';

const fetchLog = jest.fn();

jest.mock('../../api/audit', () => {
  const actual = jest.requireActual('../../api/audit');
  return { ...actual, fetchQueryLog: (...args: unknown[]) => fetchLog(...args) };
});

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
}));

const CUSTOMER = 'demo-pg.salesdb.sales.customer';

function row(overrides: Partial<QueryLogRow> = {}): QueryLogRow {
  return {
    id: 10,
    occurredAt: new Date().toISOString(),
    principal: 'analyst_a',
    runBy: null,
    sourceId: 'src-1',
    sourceName: 'demo-pg',
    outcome: 'EXECUTED',
    category: null,
    rejectReason: null,
    originalSql: 'SELECT * FROM sales.customer',
    rewrittenSql: 'SELECT id, NULL AS email FROM sales.customer',
    sqlHidden: false,
    rowCount: 12,
    durationMs: 40,
    assets: [CUSTOMER],
    hiddenAssets: 0,
    own: true,
    ...overrides,
  };
}

function page(overrides: Partial<Page> = {}): Page {
  return {
    since: '2026-08-26T00:00:00Z',
    until: null,
    scope: 'EVERYTHING',
    rows: [row()],
    nextBefore: null,
    counts: { total: 4, executed: 2, rejected: 1, failed: 1 },
    ...overrides,
  };
}

let location = '';
function Where() {
  const here = useLocation();
  location = here.search;
  return null;
}

function renderPage(entry = '/audit') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[entry]}>
        <QueryLogPage />
        <Where />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

/** The first row's toggle: the list's own buttons, not the window picker's. */
async function openable() {
  const list = await screen.findByRole('region', { name: 'Queries' });
  return within(list).findAllByRole('button', { expanded: false }).then((all) => all[0]);
}

beforeEach(() => {
  fetchLog.mockReset();
  location = '';
});

describe('QueryLogPage', () => {
  it('counts the window in the header and lists the statements', async () => {
    fetchLog.mockResolvedValue(page());
    renderPage();

    expect(await screen.findByText('SELECT * FROM sales.customer')).toBeInTheDocument();
    const header = screen.getByRole('banner');
    expect(within(header).getByText('Queries').nextSibling).toHaveTextContent('4');
    expect(within(header).getByText('Refused').nextSibling).toHaveTextContent('1');
    expect(within(header).getByText('Refused share').nextSibling).toHaveTextContent('25%');
    expect(screen.getByText('12 rows · 40 ms')).toBeInTheDocument();
    expect(fetchLog).toHaveBeenCalledWith(
      expect.objectContaining({ days: 30, outcome: null, before: null, limit: 50 })
    );
  });

  it('opens a row to the statement as written and as it ran', async () => {
    fetchLog.mockResolvedValue(page());
    renderPage();

    fireEvent.click(await openable());
    expect(screen.getByText('As written')).toBeInTheDocument();
    expect(screen.getByText('As it ran, with policy compiled in')).toBeInTheDocument();
    expect(screen.getByText('SELECT id, NULL AS email FROM sales.customer')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: CUSTOMER })).toHaveAttribute(
      'href',
      `/catalog/${encodeURIComponent(CUSTOMER)}`
    );
  });

  it('says why a refusal did not run, and marks who ran it as whom', async () => {
    fetchLog.mockResolvedValue(
      page({
        rows: [
          row({
            outcome: 'REJECTED',
            category: 'POLICY_DENY',
            rejectReason: `Access to ${CUSTOMER} is denied by policy`,
            rewrittenSql: null,
            rowCount: null,
            runBy: 'admin',
          }),
        ],
      })
    );
    renderPage();

    expect(await screen.findByText('Denied by policy')).toBeInTheDocument();
    expect(screen.getByText('run by admin')).toBeInTheDocument();
    fireEvent.click(await openable());
    expect(screen.getByText(`Access to ${CUSTOMER} is denied by policy`)).toBeInTheDocument();
    expect(screen.queryByText('As it ran, with policy compiled in')).not.toBeInTheDocument();
  });

  it('tells an owner a statement was withheld, and counts the tables that are not theirs', async () => {
    fetchLog.mockResolvedValue(
      page({
        scope: 'OWNED',
        rows: [
          row({
            own: false,
            sqlHidden: true,
            originalSql: null,
            rewrittenSql: null,
            hiddenAssets: 2,
          }),
        ],
      })
    );
    renderPage();

    expect(
      await screen.findByText('Statement not shown: it also read a table that is not yours')
    ).toBeInTheDocument();
    expect(screen.getByText(/every query that touched a table you own/)).toBeInTheDocument();
    fireEvent.click(await openable());
    expect(screen.getByText(/and 2 more tables that are not yours/)).toBeInTheDocument();
    expect(screen.queryByText('As written')).not.toBeInTheDocument();
  });

  it('keeps the category but not the words when the message names somebody else’s table', async () => {
    fetchLog.mockResolvedValue(
      page({
        scope: 'OWNED',
        rows: [
          row({
            own: false,
            outcome: 'REJECTED',
            category: 'UNGOVERNED',
            rejectReason: null,
            sqlHidden: true,
            originalSql: null,
            rewrittenSql: null,
          }),
        ],
      })
    );
    renderPage();

    fireEvent.click(await openable());
    expect(
      screen.getByText('Table not governed. The full message names a table that is not yours.')
    ).toBeInTheDocument();
  });

  it('filters by outcome through the address', async () => {
    fetchLog.mockResolvedValue(page());
    renderPage();
    await screen.findByText('SELECT * FROM sales.customer');

    fireEvent.click(screen.getByRole('radio', { name: 'Refused' }));
    await waitFor(() =>
      expect(fetchLog).toHaveBeenLastCalledWith(expect.objectContaining({ outcome: 'REJECTED' }))
    );
    expect(location).toBe('?outcome=REJECTED');
    expect(screen.getByRole('radio', { name: 'Refused' })).toHaveAttribute('aria-checked', 'true');
  });

  it('reads its filters from a link, so a number elsewhere can point here', async () => {
    fetchLog.mockResolvedValue(page());
    renderPage(`/audit?outcome=FAILED&table=${encodeURIComponent(CUSTOMER)}&days=7`);

    await screen.findByText('SELECT * FROM sales.customer');
    expect(fetchLog).toHaveBeenCalledWith(
      expect.objectContaining({ outcome: 'FAILED', assetFqn: CUSTOMER, days: 7 })
    );
    expect(screen.getByRole('textbox', { name: 'Table' })).toHaveValue(CUSTOMER);
  });

  it('searches the SQL once typing stops', async () => {
    jest.useFakeTimers();
    try {
      fetchLog.mockResolvedValue(page());
      renderPage();
      await screen.findByText('SELECT * FROM sales.customer');

      fireEvent.change(screen.getByRole('textbox', { name: 'Search the SQL' }), {
        target: { value: 'salary' },
      });
      expect(fetchLog).not.toHaveBeenCalledWith(expect.objectContaining({ q: 'salary' }));
      act(() => {
        jest.advanceTimersByTime(400);
      });
      await waitFor(() =>
        expect(fetchLog).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'salary' }))
      );
    } finally {
      jest.useRealTimers();
    }
  });

  it('hides the principal filter from somebody who only reads their own rows', async () => {
    fetchLog.mockResolvedValue(page({ scope: 'OWN' }));
    renderPage();
    await screen.findByText('SELECT * FROM sales.customer');
    expect(screen.queryByRole('textbox', { name: 'Principal' })).not.toBeInTheDocument();
  });

  it('loads older rows from where the page ended', async () => {
    fetchLog
      .mockResolvedValueOnce(page({ nextBefore: 10 }))
      .mockResolvedValueOnce(
        page({
          rows: [row({ id: 9, originalSql: 'SELECT 1 FROM sales.orders' })],
          nextBefore: null,
          counts: null,
        })
      );
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Load older queries' }));
    expect(await screen.findByText('SELECT 1 FROM sales.orders')).toBeInTheDocument();
    expect(fetchLog).toHaveBeenLastCalledWith(expect.objectContaining({ before: 10 }));
    expect(screen.getByText('SELECT * FROM sales.customer')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Load older queries' })).not.toBeInTheDocument();
    expect(within(screen.getByRole('banner')).getByText('Queries').nextSibling).toHaveTextContent(
      '4'
    );
  });

  it('says so when nothing matches, and when the log cannot be read', async () => {
    fetchLog.mockResolvedValueOnce(page({ rows: [], counts: { total: 0, executed: 0, rejected: 0, failed: 0 } }));
    const { unmount } = renderPage('/audit?outcome=REJECTED');
    expect(
      await screen.findByText('No query in this window matches these filters.')
    ).toBeInTheDocument();
    unmount();

    fetchLog.mockRejectedValueOnce(new Error('days must be between 1 and 3650; got 0'));
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('days must be between 1 and 3650');
  });
});
