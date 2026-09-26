import type { TableScope } from '../../api/sources';
import {
  MAX_RULES,
  describeRule,
  describeScope,
  ruleMatches,
  ruleProblem,
  scansEverything,
  scopeBadge,
  scopeIncludes,
  scopeProblem,
  scopeToSend,
} from './tableScope';

/**
 * The form's copy of the server's scope rules.
 *
 * It only previews — the import applies the server's — but a preview that
 * disagrees with the import is worse than none, so the matching cases here are
 * the ones TableScopeTest pins on the server.
 */

const ALL: TableScope = { mode: 'ALL', include: [], exclude: [] };

describe('ruleMatches', () => {
  it('compares the table name, ignoring case', () => {
    expect(ruleMatches({ match: 'STARTS_WITH', value: 'TMP_' }, 'sales', 'tmp_load')).toBe(true);
    expect(ruleMatches({ match: 'STARTS_WITH', value: 'tmp_' }, 'tmp_', 'orders')).toBe(false);
    expect(ruleMatches({ match: 'ENDS_WITH', value: '_bak' }, 'sales', 'orders_BAK')).toBe(true);
    expect(ruleMatches({ match: 'CONTAINS', value: 'copy' }, 'sales', 'orders_copy_2')).toBe(true);
    expect(ruleMatches({ match: 'EQUALS', value: 'orders' }, 'sales', 'orders_2')).toBe(false);
  });

  it('compares schema.table once the text has a dot in it', () => {
    expect(ruleMatches({ match: 'STARTS_WITH', value: 'staging.' }, 'Staging', 'orders')).toBe(true);
    expect(ruleMatches({ match: 'STARTS_WITH', value: 'staging.' }, 'sales', 'staging')).toBe(false);
    expect(ruleMatches({ match: 'EQUALS', value: 'sales.orders' }, 'sales', 'orders')).toBe(true);
  });

  it('treats the text as text, not a pattern', () => {
    expect(ruleMatches({ match: 'CONTAINS', value: '.*' }, 'sales', 'orders')).toBe(false);
    expect(ruleMatches({ match: 'STARTS_WITH', value: '%' }, 'sales', 'orders')).toBe(false);
  });
});

describe('scopeIncludes', () => {
  it('keeps everything by default', () => {
    expect(scopeIncludes(ALL, 'sales', 'anything')).toBe(true);
  });

  it('lets an exclusion win over a named table', () => {
    const scope: TableScope = {
      mode: 'ONLY',
      include: [{ match: 'STARTS_WITH', value: 'dim_' }],
      exclude: [{ match: 'ENDS_WITH', value: '_old' }],
    };
    expect(scopeIncludes(scope, 'sales', 'dim_customer')).toBe(true);
    expect(scopeIncludes(scope, 'sales', 'dim_customer_old')).toBe(false);
    expect(scopeIncludes(scope, 'sales', 'fact_sales')).toBe(false);
  });

  it('ignores include rules while scanning everything', () => {
    const scope: TableScope = { mode: 'ALL', include: [{ match: 'EQUALS', value: 'x' }], exclude: [] };
    expect(scopeIncludes(scope, 'sales', 'orders')).toBe(true);
    expect(scansEverything(scope)).toBe(true);
  });
});

describe('ruleProblem', () => {
  const rules = [{ match: 'STARTS_WITH' as const, value: 'tmp_' }];

  it('accepts a new rule', () => {
    expect(ruleProblem(rules, { match: 'STARTS_WITH', value: 'bak_' })).toBeNull();
    expect(ruleProblem(rules, { match: 'ENDS_WITH', value: 'tmp_' })).toBeNull();
  });

  it('refuses blank, repeated, multi-line and overlong text', () => {
    expect(ruleProblem(rules, { match: 'STARTS_WITH', value: '   ' })).toMatch(/Type some text/);
    expect(ruleProblem(rules, { match: 'STARTS_WITH', value: ' TMP_ ' })).toMatch(/already/);
    expect(ruleProblem(rules, { match: 'CONTAINS', value: 'a\nb' })).toMatch(/line breaks/);
    expect(ruleProblem(rules, { match: 'CONTAINS', value: 'x'.repeat(257) })).toMatch(/256/);
  });

  it('refuses a rule past the limit', () => {
    const full = Array.from({ length: MAX_RULES }, (_, i) => ({
      match: 'EQUALS' as const,
      value: `t${i}`,
    }));
    expect(ruleProblem(full, { match: 'EQUALS', value: 'one_more' })).toMatch(/50 rules/);
  });
});

describe('what is sent and said', () => {
  it('trims, and drops include rules the server would ignore', () => {
    expect(
      scopeToSend({
        mode: 'ALL',
        include: [{ match: 'EQUALS', value: 'x' }],
        exclude: [{ match: 'STARTS_WITH', value: ' tmp_ ' }],
      })
    ).toEqual({ mode: 'ALL', include: [], exclude: [{ match: 'STARTS_WITH', value: 'tmp_' }] });
  });

  it('refuses to name no table at all', () => {
    expect(scopeProblem({ mode: 'ONLY', include: [], exclude: [] })).toMatch(/at least one/);
    expect(scopeProblem(ALL)).toBeNull();
  });

  it('describes each shape in a sentence', () => {
    expect(describeScope(null)).toBe('All tables and views this login can read are in scope.');
    expect(
      describeScope({ mode: 'ALL', include: [], exclude: [{ match: 'STARTS_WITH', value: 'tmp_' }] })
    ).toBe('All tables and views this login can read, except where the name starts with “tmp_”.');
    expect(
      describeScope({
        mode: 'ONLY',
        include: [
          { match: 'STARTS_WITH', value: 'procurement.' },
          { match: 'EQUALS', value: 'orders' },
        ],
        exclude: [],
      })
    ).toBe(
      'Only tables and views whose schema.table starts with “procurement.” or name is “orders”.'
    );
    expect(describeRule({ match: 'ENDS_WITH', value: '_bak' })).toBe('name ends with “_bak”');
  });

  it('counts only the rules that apply', () => {
    expect(scopeBadge(undefined)).toBe('Scanning all');
    expect(
      scopeBadge({
        mode: 'ALL',
        include: [{ match: 'EQUALS', value: 'x' }],
        exclude: [{ match: 'EQUALS', value: 'y' }],
      })
    ).toBe('1 rule');
    expect(
      scopeBadge({
        mode: 'ONLY',
        include: [{ match: 'EQUALS', value: 'x' }],
        exclude: [{ match: 'EQUALS', value: 'y' }],
      })
    ).toBe('2 rules');
  });
});
