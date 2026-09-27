import { canonical, diffPolicies } from './policyDiff';
import type { Policy } from '../../generated/entity/policy/policy';

function policy(overrides: Partial<Policy> = {}): Policy {
  return {
    name: 'deny-pii',
    policyType: 'SUBSCRIPTION',
    scopeLevel: 'ORG',
    effect: 'DENY',
    selector: { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
    subject: {
      attributes: [{ key: 'clearance', operator: 'lt', value: 'L2' }],
    },
    ...overrides,
  };
}

describe('diffPolicies', () => {
  test('two copies of the same policy have nothing between them', () => {
    expect(diffPolicies(policy(), policy())).toEqual([]);
  });

  test('key order, a spelled-out false and an empty list are not differences', () => {
    const plain = policy();
    const spelled: Policy = {
      subject: { attributes: [{ value: 'L2', operator: 'lt', key: 'clearance' }], principals: [] },
      selector: {
        condition: {
          value: 'PII',
          operator: 'contains',
          facet: 'tags',
          includeSuggested: false,
        },
      },
      effect: 'DENY',
      scopeLevel: 'ORG',
      policyType: 'SUBSCRIPTION',
      name: 'deny-pii',
      allowLocalOverride: false,
      exemptions: [],
    };

    expect(diffPolicies(plain, spelled)).toEqual([]);
  });

  test('the store’s own fields are never shown as a change', () => {
    const later = policy({
      version: 7,
      lifecycleState: 'ACTIVE',
      updatedAt: '2026-09-27T00:00:00Z',
      updatedBy: 'someone-else',
      id: 'x',
    });

    expect(diffPolicies(policy(), later)).toEqual([]);
  });

  test('a changed rule reads as the sentences either side would say', () => {
    const changes = diffPolicies(
      policy(),
      policy({ subject: { attributes: [{ key: 'clearance', operator: 'eq', value: 'L1' }] } })
    );

    expect(changes).toEqual([
      {
        field: 'Who',
        before: ['whose clearance is below L2'],
        after: ['whose clearance is exactly L1'],
      },
    ]);
  });

  test('parts are listed in the order the page shows them', () => {
    const changes = diffPolicies(
      policy(),
      policy({
        name: 'deny-pii-v2',
        effect: 'ALLOW',
        selector: { condition: { facet: 'domains', operator: 'contains', value: 'Finance' } },
      })
    );

    expect(changes.map((change) => change.field)).toEqual(['Name', 'Assets', 'Effect']);
  });

  test('something added shows nothing on the side it was missing from', () => {
    const changes = diffPolicies(policy(), policy({ description: 'Keeps PII in.' }));

    expect(changes).toEqual([
      { field: 'Description', before: [], after: ['Keeps PII in.'] },
    ]);
  });

  test('data rules are compared one line per rule', () => {
    const data = (showLast: number): Partial<Policy> => ({
      policyType: 'DATA',
      data: {
        columnRules: [
          {
            columns: { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
            action: 'MASK',
            masking: { function: 'PARTIAL', showLast },
          },
        ],
      },
    });

    const changes = diffPolicies(policy(data(4)), policy(data(2)));

    expect(changes).toEqual([
      {
        field: 'Column rules',
        before: ['columns selected by tag under PII are partly hidden, keeping the last 4'],
        after: ['columns selected by tag under PII are partly hidden, keeping the last 2'],
      },
    ]);
  });

  test('a change the sentence cannot tell apart falls back to the raw value', () => {
    // Whether Suggested tags count is not in the sentence, but it changes what
    // the policy binds to, so the difference has to show somehow.
    const suggested = policy({
      selector: {
        condition: { facet: 'tags', operator: 'contains', value: 'PII', includeSuggested: true },
      },
    });

    const [change] = diffPolicies(policy(), suggested);

    expect(change.field).toBe('Assets');
    expect(change.before[0]).not.toContain('includeSuggested');
    expect(change.after[0]).toContain('"includeSuggested":true');
  });
});

describe('canonical', () => {
  test('drops what carries no meaning and sorts what is left', () => {
    expect(canonical({ b: 1, a: null, c: false, d: [], e: '  ', f: { z: 1, y: undefined } })).toEqual({
      b: 1,
      f: { z: 1 },
    });
    expect(JSON.stringify(canonical({ b: 1, a: 2 }))).toBe('{"a":2,"b":1}');
  });
});
