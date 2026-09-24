import type { Policy } from '../../generated/entity/policy/policy';
import { capabilities } from './enforcement';
import { describePolicy, describeSelector, describeSubject } from './policyLanguage';

/**
 * The readback is the only thing most approvers will actually read, so the two
 * mistranslations that would matter most are pinned here: calling an empty
 * selector "everything", and calling an empty subject rule "nobody".
 */

test('an empty selector reads as nothing, never as everything', () => {
  expect(describeSelector(undefined)).toBe('nothing');
  expect(describeSelector({})).toBe('nothing');
});

test('an absent subject reads as everyone', () => {
  expect(describeSubject(undefined)).toBe('everyone');
});

test('a subject reads its role, attribute and hours in one sentence', () => {
  const sentence = describeSubject({
    principals: [{ role: 'analyst' }, { assetOwner: true }],
    attributes: [{ key: 'clearance', operator: 'gte', value: 'L2' }],
    time: {
      windows: [
        { days: ['MON-FRI'], from: '08:00', to: '18:00', timezone: 'Asia/Bangkok' },
      ],
    },
  });

  expect(sentence).toContain('anyone with the role analyst');
  expect(sentence).toContain('whoever owns the asset');
  expect(sentence).toContain('whose clearance is at least L2');
  expect(sentence).toContain('Asia/Bangkok');
});

test('a data policy says what it restricts, and says when it restricts nothing', () => {
  const empty: Policy = {
    name: 'p',
    policyType: 'DATA',
    scopeLevel: 'ORG',
    selector: { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
  };
  expect(describePolicy(empty).join(' ')).toContain('nothing is restricted yet');

  const masked: Policy = {
    ...empty,
    data: {
      columnRules: [
        {
          columns: {
            condition: { facet: 'tags', operator: 'contains', value: 'PII.Sensitive' },
          },
          action: 'MASK',
          masking: { function: 'PARTIAL', showLast: 4 },
        },
      ],
    },
  };
  expect(describePolicy(masked).join(' ')).toContain('keeping the last 4');
});

/**
 * The capability matrix exists to stop a mask being dropped silently by a mode
 * that cannot express it, so the gaps are asserted per engine rather than just
 * counted.
 */
test('native source config cannot carry a cell mask on either engine', () => {
  const policy: Policy = {
    name: 'cell',
    policyType: 'DATA',
    scopeLevel: 'ORG',
    selector: { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
    data: {
      columnRules: [
        {
          columns: { condition: { facet: 'columnName', operator: 'eq', value: 'salary' } },
          action: 'MASK',
          masking: { function: 'NULLIFY' },
          condition: 'dept != user.department',
        },
      ],
    },
  };

  for (const engine of ['POSTGRES', 'SQLSERVER'] as const) {
    const notes = capabilities(policy, engine);
    const native = notes.find((note) => note.mode === 'NATIVE_CONFIG')!;
    expect(native.support).toBe('none');
    expect(native.gaps.join(' ')).toContain('Cell masking');

    // The other two carry it whole; if that ever stops being true the builder
    // must say so rather than let an apply succeed with the rule missing.
    expect(notes.find((note) => note.mode === 'PROXY')!.support).toBe('full');
    expect(notes.find((note) => note.mode === 'SECURE_VIEW')!.support).toBe('full');
  }
});

test('an engine nobody wrote notes for is reported as unverified, not as SQL Server', () => {
  // The notes used to be a ternary between the two engines we ship, so a third
  // one silently inherited the SQL Server sentence -- a confident, specific
  // claim about a product nobody had checked. A gap with no note is a gap.
  const policy: Policy = {
    name: 'mask',
    policyType: 'DATA',
    scopeLevel: 'ORG',
    selector: { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
    data: {
      columnRules: [
        {
          columns: { condition: { facet: 'columnName', operator: 'eq', value: 'email' } },
          action: 'MASK',
          masking: { function: 'NULLIFY' },
        },
      ],
    },
  };

  const native = capabilities(policy, 'MYSQL').find(
    (note) => note.mode === 'NATIVE_CONFIG'
  )!;
  expect(native.support).not.toBe('full');
  expect(native.gaps.join(' ')).toContain('has not been verified');
  expect(native.gaps.join(' ')).not.toContain('SQL Server');
});

test('a row-filter-only policy is carried whole by all three modes', () => {
  const policy: Policy = {
    name: 'rls',
    policyType: 'DATA',
    scopeLevel: 'SCHEMA',
    scopeFqn: 'prod-mssql.SalesDB.dbo',
    selector: { condition: { facet: 'schema', operator: 'eq', value: 'dbo' } },
    data: {
      rowFilters: [
        {
          kind: 'ATTRIBUTE_COMPARE',
          column: 'branch_code',
          operator: 'eq',
          userAttribute: 'branch',
        },
      ],
    },
  };

  for (const note of capabilities(policy, 'POSTGRES')) {
    expect(note.support).toBe('full');
  }
});
