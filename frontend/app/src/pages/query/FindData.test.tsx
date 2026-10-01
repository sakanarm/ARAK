import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { draftFor, findLanguage, FindWithNokRak } from './FindData';
import type { FoundData, FoundTable } from '../../api/llm';

const assistFindData = jest.fn();

jest.mock('../../api/llm', () => ({
  assistFindData: (...args: unknown[]) => assistFindData(...args),
}));

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
}));

// The request form has its own tests; here it is enough that the right table reaches it.
jest.mock('../catalog/AssetRequestAccess', () => ({
  AssetAccessAction: ({ asset }: { asset: { fqn: string } }) => (
    <button type="button">Request access to {asset.fqn}</button>
  ),
}));

const CUSTOMER = 'demo-pg.salesdb.sales.customer';
const SALARY = 'demo-pg.hrdb.hr.salary';

function table(overrides: Partial<FoundTable> = {}): FoundTable {
  return {
    fqn: CUSTOMER,
    access: 'READABLE',
    description: 'One row per customer',
    why: 'Holds each customer and their email.',
    columns: ['id', 'email'],
    sourceId: 'src-pg',
    engine: 'POSTGRES',
    ...overrides,
  };
}

function answer(tables: FoundTable[], keywords = ['customer', 'email']): FoundData {
  return { tables, keywords, model: 'demo-model', personal: false };
}

function renderFind(onUse = jest.fn(), onClose = jest.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <FindWithNokRak onClose={onClose} onUse={onUse} />
      </MemoryRouter>
    </QueryClientProvider>
  );
  return { onUse, onClose };
}

function ask(text: string) {
  fireEvent.change(screen.getByRole('textbox'), { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: 'Find it' }));
}

beforeEach(() => {
  assistFindData.mockReset();
});

describe('Find data', () => {
  it('asks with the sentence, in the language it was written in', async () => {
    assistFindData.mockResolvedValue(answer([]));
    renderFind();

    ask('ลูกค้าที่ยังค้างชำระ');

    await waitFor(() =>
      expect(assistFindData).toHaveBeenCalledWith({ want: 'ลูกค้าที่ยังค้างชำระ', language: 'Thai' })
    );
  });

  it('shows each table with why it fits, the columns and the access the check gave', async () => {
    assistFindData.mockResolvedValue(
      answer([table(), table({ fqn: SALARY, access: 'REQUESTABLE', why: 'Pay by month.', columns: [] })])
    );
    renderFind();

    ask('customer emails');

    const list = await screen.findByRole('list', { name: 'Tables NokRak found' });
    const customer = within(list).getByRole('region', { name: CUSTOMER });
    expect(within(customer).getByText('Holds each customer and their email.')).toBeInTheDocument();
    expect(within(customer).getByText('You can query')).toBeInTheDocument();
    expect(within(customer).getByText('email')).toBeInTheDocument();
    expect(within(customer).getByRole('link', { name: CUSTOMER })).toHaveAttribute(
      'href',
      `/catalog/${encodeURIComponent(CUSTOMER)}`
    );
    const salary = within(list).getByRole('region', { name: SALARY });
    expect(within(salary).getByText('You can request')).toBeInTheDocument();
    expect(screen.getByText(/Searched the catalogue for/)).toHaveTextContent('customer, email');
  });

  it('puts a statement for a readable table in the editor, on its source', async () => {
    assistFindData.mockResolvedValue(answer([table()]));
    const { onUse } = renderFind();

    ask('customer emails');
    fireEvent.click(await screen.findByRole('button', { name: 'Put in the editor' }));

    expect(onUse).toHaveBeenCalledWith('SELECT id, email\nFROM sales.customer', 'src-pg');
  });

  it('offers the request form, not the editor, for a table the person may only ask for', async () => {
    assistFindData.mockResolvedValue(answer([table({ fqn: SALARY, access: 'REQUESTABLE' })]));
    renderFind();

    ask('salaries');

    expect(await screen.findByRole('button', { name: `Request access to ${SALARY}` })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Put in the editor' })).not.toBeInTheDocument();
  });

  it('says so when nothing was found', async () => {
    assistFindData.mockResolvedValue(answer([], ['warehouse']));
    renderFind();

    ask('where the forklifts are');

    expect(await screen.findByText(/found no table you can query or request/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'browse the catalogue' })).toHaveAttribute('href', '/catalog');
    expect(screen.queryByText(/you cannot query or request/)).not.toBeInTheDocument();
  });

  it('says a table matched that is out of reach, rather than that nothing did', async () => {
    assistFindData.mockResolvedValue({ ...answer([], ['orders']), outOfReach: 1 });
    renderFind();

    ask('purchase orders');

    const said = await screen.findByText(/found no table you can query or request/);
    expect(said).toHaveTextContent(/One table matched, but you cannot query or request it now/);
    expect(said).toHaveTextContent(/The catalogue says why on each table's page/);
  });

  it('counts the ones out of reach under those it found', async () => {
    assistFindData.mockResolvedValue({ ...answer([table()]), outOfReach: 2 });
    renderFind();

    ask('customer emails');

    await screen.findByRole('list', { name: 'Tables NokRak found' });
    expect(screen.getByText(/2 more tables matched, but you cannot query or request them now/)).toBeInTheDocument();
  });

  it('shows what the server said when it could not answer', async () => {
    assistFindData.mockRejectedValue(new Error('The assistant did not answer with a list of tables.'));
    renderFind();

    ask('customer emails');

    expect(await screen.findByRole('alert')).toHaveTextContent('did not answer with a list of tables');
  });
});

describe('the statement a found table offers', () => {
  it('names the columns when each can be written bare', () => {
    expect(draftFor({ fqn: CUSTOMER, columns: ['id', 'email'], engine: 'POSTGRES' })).toBe(
      'SELECT id, email\nFROM sales.customer'
    );
  });

  it('falls back to * for a name that would need quotes, or none named', () => {
    expect(draftFor({ fqn: CUSTOMER, columns: ['Email'], engine: 'POSTGRES' })).toBe(
      'SELECT *\nFROM sales.customer'
    );
    expect(draftFor({ fqn: CUSTOMER, columns: ['id', 'order'], engine: 'POSTGRES' })).toBe(
      'SELECT *\nFROM sales.customer'
    );
    expect(draftFor({ fqn: CUSTOMER, columns: ['first name'], engine: 'SQLSERVER' })).toBe(
      'SELECT *\nFROM sales.customer'
    );
    expect(draftFor({ fqn: CUSTOMER, columns: [], engine: 'POSTGRES' })).toBe('SELECT *\nFROM sales.customer');
  });

  it('keeps a mixed-case name on SQL Server, which does not fold it', () => {
    expect(draftFor({ fqn: 'demo-ms.SalesDB.dbo.Customer', columns: ['CustomerID'], engine: 'SQLSERVER' })).toBe(
      'SELECT CustomerID\nFROM dbo.Customer'
    );
  });

  it('keeps a mixed-case name on MySQL too, and qualifies the table with its database', () => {
    expect(draftFor({ fqn: 'demo-my.default.sales.Customer', columns: ['CustomerID'], engine: 'MYSQL' })).toBe(
      'SELECT CustomerID\nFROM sales.Customer'
    );
  });

  it('reads the language from the sentence', () => {
    expect(findLanguage('customer emails')).toBe('English');
    expect(findLanguage('อีเมลลูกค้า')).toBe('Thai');
  });
});
