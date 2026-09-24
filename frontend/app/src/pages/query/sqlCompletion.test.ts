import {
  accept,
  asCompletionTable,
  contextAt,
  insideLiteral,
  referencedTables,
  suggest,
  type CompletionTable,
} from './sqlCompletion';

const CUSTOMER: CompletionTable = {
  fqn: 'demo-pg.salesdb.sales.customer',
  schema: 'sales',
  name: 'customer',
};
const ORDERS: CompletionTable = {
  fqn: 'demo-pg.salesdb.sales.orders',
  schema: 'sales',
  name: 'orders',
};
const LEDGER: CompletionTable = {
  fqn: 'demo-pg.salesdb.finance.ledger',
  schema: 'finance',
  name: 'ledger',
};
const TABLES = [CUSTOMER, ORDERS, LEDGER];
const COLUMNS = {
  [CUSTOMER.fqn]: ['id', 'email', 'citizen_id', 'branch_code'],
  [ORDERS.fqn]: ['id', 'customer_id', 'total'],
};

/** The suggestions with the caret at the end of the text. */
function at(sql: string, columns: Record<string, string[] | undefined> = COLUMNS) {
  const context = contextAt(sql, sql.length);
  return context ? suggest(sql, context, TABLES, columns) : [];
}

describe('contextAt', () => {
  it('offers tables after FROM and JOIN, however the keyword is written', () => {
    expect(contextAt('SELECT * FROM ', 14)?.mode).toBe('tables');
    expect(contextAt('select * from cu', 16)).toEqual({
      from: 14,
      prefix: 'cu',
      mode: 'tables',
    });
    expect(contextAt('SELECT * FROM a JOIN\n  sa', 25)?.mode).toBe('tables');
  });

  it('reads what comes before a dot as the qualifier', () => {
    expect(contextAt('SELECT c.em', 11)).toEqual({
      from: 9,
      prefix: 'em',
      mode: 'qualified',
      qualifier: 'c',
    });
    expect(contextAt('SELECT * FROM sales.', 20)).toMatchObject({
      mode: 'qualified',
      qualifier: 'sales',
      prefix: '',
    });
  });

  it('strips quoting from identifiers', () => {
    expect(contextAt('SELECT * FROM "sales"."cu', 25)).toMatchObject({
      mode: 'qualified',
      qualifier: 'sales',
      prefix: 'cu',
    });
  });

  it('suggests nothing inside a string or a comment', () => {
    expect(contextAt("SELECT * FROM t WHERE name = 'FROM ", 35)).toBeNull();
    expect(contextAt('-- FROM ', 8)).toBeNull();
    expect(contextAt('/* FROM ', 8)).toBeNull();
  });

  it('suggests again once the string or comment has closed', () => {
    expect(contextAt("SELECT 'it''s' FROM ", 20)?.mode).toBe('tables');
    expect(contextAt('/* x */ SELECT * FROM ', 22)?.mode).toBe('tables');
    expect(contextAt('-- note\nSELECT * FROM ', 22)?.mode).toBe('tables');
  });

  it('refuses a caret outside the text', () => {
    expect(contextAt('SELECT', -1)).toBeNull();
    expect(contextAt('SELECT', 7)).toBeNull();
  });
});

describe('insideLiteral', () => {
  it('treats a doubled quote as an escape, not a terminator', () => {
    expect(insideLiteral("'it''s", 6)).toBe(true);
    expect(insideLiteral("'it''s' ", 8)).toBe(false);
  });
});

describe('referencedTables', () => {
  it('knows a table by schema.table, by bare name, and by its alias', () => {
    const refs = referencedTables(
      'SELECT * FROM sales.customer c JOIN sales.orders AS o ON o.customer_id = c.id',
      TABLES
    );
    expect(refs.get('c')).toBe(CUSTOMER);
    expect(refs.get('customer')).toBe(CUSTOMER);
    expect(refs.get('sales.customer')).toBe(CUSTOMER);
    expect(refs.get('o')).toBe(ORDERS);
  });

  it('does not mistake the next clause for an alias', () => {
    const refs = referencedTables('SELECT * FROM sales.customer WHERE id = 1', TABLES);
    expect(refs.has('where')).toBe(false);
    expect(refs.get('customer')).toBe(CUSTOMER);
  });

  it('resolves a fully qualified name by its last two parts', () => {
    const refs = referencedTables('SELECT * FROM salesdb.sales.customer x', TABLES);
    expect(refs.get('x')).toBe(CUSTOMER);
  });

  it('ignores tables the catalog does not hold', () => {
    const refs = referencedTables('SELECT * FROM hr.salary s', TABLES);
    expect(refs.size).toBe(0);
  });
});

describe('suggest', () => {
  it('offers tables as schema.table, because the proxy refuses a bare name', () => {
    const tables = at('SELECT * FROM ');
    expect(tables.map((s) => s.insert)).toEqual([
      'finance.ledger',
      'sales.customer',
      'sales.orders',
    ]);
    expect(tables[0]).toMatchObject({ kind: 'table', fqn: LEDGER.fqn });
  });

  it('matches a table by its own name or by its schema', () => {
    expect(at('SELECT * FROM cu').map((s) => s.insert)).toEqual(['sales.customer']);
    expect(at('SELECT * FROM sa').map((s) => s.insert)).toEqual([
      'sales.customer',
      'sales.orders',
    ]);
    expect(at('SELECT * FROM CU').map((s) => s.insert)).toEqual(['sales.customer']);
  });

  it('after a schema and a dot, offers only that schema’s tables, by name', () => {
    const tables = at('SELECT * FROM sales.');
    expect(tables.map((s) => s.insert)).toEqual(['customer', 'orders']);
    expect(tables[0].label).toBe('sales.customer');
  });

  it('after an alias and a dot, offers that table’s columns', () => {
    // An alias nobody has declared is neither a table nor a schema.
    expect(at('SELECT c.')).toEqual([]);

    const sql = 'SELECT * FROM sales.customer c WHERE c.em';
    expect(at(sql)).toEqual([
      { kind: 'column', label: 'email', insert: 'email', detail: 'customer' },
    ]);
  });

  it('reads an alias declared after the caret', () => {
    const sql = 'SELECT c. FROM sales.customer c';
    const context = contextAt(sql, 9)!;
    expect(suggest(sql, context, TABLES, COLUMNS).map((s) => s.label)).toEqual([
      'branch_code',
      'citizen_id',
      'email',
      'id',
    ]);
  });

  it('offers nothing after a dot on a table whose columns are still loading', () => {
    expect(at('SELECT * FROM finance.ledger l WHERE l.', {})).toEqual([]);
  });

  it('offers columns in scope before keywords, and nothing without a prefix', () => {
    expect(at('SELECT * FROM sales.customer WHERE ')).toEqual([]);
    const words = at('SELECT * FROM sales.customer WHERE e');
    expect(words[0]).toMatchObject({ kind: 'column', label: 'email' });
    expect(words.slice(1).map((s) => s.label)).toEqual(['EXISTS', 'ELSE', 'END']);
  });

  it('offers a column once when two tables in scope share its name', () => {
    const sql = 'SELECT * FROM sales.customer c JOIN sales.orders o ON i';
    expect(at(sql).filter((s) => s.label === 'id')).toHaveLength(1);
  });

  it('does not offer the word already typed in full', () => {
    expect(at('SELECT * FROM sales.customer WHERE id AND').map((s) => s.label)).not.toContain(
      'AND'
    );
  });

  it('offers keywords where no table is in scope', () => {
    expect(at('SEL').map((s) => s.label)).toEqual(['SELECT']);
    expect(at('SELECT * FROM sales.customer ORD').map((s) => s.label)).toEqual(['ORDER BY']);
  });
});

describe('accept', () => {
  it('replaces the word being typed and puts a space after a table', () => {
    const sql = 'SELECT * FROM cu';
    const context = contextAt(sql, sql.length)!;
    const [table] = suggest(sql, context, TABLES, COLUMNS);
    expect(accept(sql, sql.length, context, table)).toEqual({
      text: 'SELECT * FROM sales.customer ',
      caret: 29,
    });
  });

  it('replaces the rest of a word the caret sits inside', () => {
    const sql = 'SELECT c.emxx FROM sales.customer c';
    const context = contextAt(sql, 11)!;
    const [email] = suggest(sql, context, TABLES, COLUMNS);
    expect(accept(sql, 11, context, email)).toEqual({
      text: 'SELECT c.email FROM sales.customer c',
      caret: 14,
    });
  });

  it('does not double the space when one already follows', () => {
    const sql = 'SELECT * FROM sa WHERE 1 = 1';
    const context = contextAt(sql, 16)!;
    const [table] = suggest(sql, context, TABLES, COLUMNS);
    expect(accept(sql, 16, context, table).text).toBe(
      'SELECT * FROM sales.customer WHERE 1 = 1'
    );
  });
});

describe('asCompletionTable', () => {
  it('splits an FQN into the schema and table the proxy resolves', () => {
    expect(asCompletionTable('svc.db.sales.customer')).toEqual({
      fqn: 'svc.db.sales.customer',
      schema: 'sales',
      name: 'customer',
    });
    expect(asCompletionTable('lonely')).toBeNull();
  });
});
