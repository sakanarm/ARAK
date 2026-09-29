import type { Policy } from '../../generated/entity/policy/policy';
import {
  listOf,
  normaliseConditionValues,
  splitValues,
  withOperator,
} from './conditionValues';

/**
 * Which field a condition's values live in.
 *
 * The engine reads "is one of" from `values` and everything else from
 * `value`. Written the other way round, "is none of" admitted everybody, so
 * these pin the editor to the engine's reading.
 */

describe('splitValues', () => {
  it('splits at commas, trims, and drops blanks', () => {
    expect(splitValues(' FINANCE, RISK ,, ')).toEqual(['FINANCE', 'RISK']);
  });
});

describe('listOf', () => {
  it('prefers the list', () => {
    expect(listOf({ operator: 'in', value: 'X', values: ['A', 'B'] })).toEqual(['A', 'B']);
  });

  it('reads a list the old editor saved into value as its items', () => {
    expect(listOf({ operator: 'in', value: 'FINANCE, RISK' })).toEqual(['FINANCE', 'RISK']);
  });

  it('is empty when there is nothing to compare against', () => {
    expect(listOf({ operator: 'in' })).toEqual([]);
    expect(listOf({ operator: 'in', value: null })).toEqual([]);
  });
});

describe('withOperator', () => {
  it('moves a typed value into the list when the operator becomes "is one of"', () => {
    const next = withOperator(
      { key: 'department', operator: 'eq' as string, value: 'FINANCE, RISK' },
      'in'
    );

    expect(next).toEqual({ key: 'department', operator: 'in', values: ['FINANCE', 'RISK'] });
    expect(next.value).toBeUndefined();
  });

  it('keeps the first item, not a joined string, when leaving a list', () => {
    const next = withOperator(
      { key: 'department', operator: 'in' as string, values: ['FINANCE', 'RISK'] },
      'ne'
    );

    expect(next).toEqual({ key: 'department', operator: 'ne', value: 'FINANCE' });
    expect(next.values).toBeUndefined();
  });

  it('leaves the value alone between two single-value operators', () => {
    expect(withOperator({ operator: 'eq' as string, value: 'L2' }, 'gte')).toEqual({
      operator: 'gte',
      value: 'L2',
    });
  });
});

describe('normaliseConditionValues', () => {
  it('rewrites every old-style list in the document, and nothing else', () => {
    const legacy: Policy = {
      name: 'finance',
      policyType: 'DATA',
      scopeLevel: 'ORG',
      selector: {
        and: [
          { condition: { facet: 'schema', operator: 'notIn', value: 'hr, payroll' } },
          { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
        ],
      },
      subject: {
        attributes: [{ key: 'department', operator: 'in', value: 'FINANCE,RISK' }],
      },
      data: {
        columnRules: [
          {
            action: 'MASK',
            columns: { condition: { facet: 'columnName', operator: 'in', value: 'email' } },
          },
        ],
      },
    };

    const saved = JSON.parse(JSON.stringify(normaliseConditionValues(legacy)));

    expect(saved.selector.and[0].condition).toEqual({
      facet: 'schema',
      operator: 'notIn',
      values: ['hr', 'payroll'],
    });
    expect(saved.selector.and[1].condition).toEqual({
      facet: 'tags',
      operator: 'contains',
      value: 'PII',
    });
    expect(saved.subject.attributes[0]).toEqual({
      key: 'department',
      operator: 'in',
      values: ['FINANCE', 'RISK'],
    });
    expect(saved.data.columnRules[0].columns.condition).toEqual({
      facet: 'columnName',
      operator: 'in',
      values: ['email'],
    });
  });
});
