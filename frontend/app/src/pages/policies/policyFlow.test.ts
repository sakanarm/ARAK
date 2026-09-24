import type { Policy } from '../../generated/entity/policy/policy';
import { buildFlow } from './policyFlow';

/**
 * The chart is a second explanation of a document somebody is about to publish,
 * which makes a wrong chart worse than no chart. What is pinned here is the
 * handful of readings that would mislead in the permissive direction: an empty
 * gate that looks authored, a gate whose failure looks like a denial, and an
 * order that lets the asset gate come after the caller gate.
 */

const DATA: Policy = {
  name: 'mask-pii',
  policyType: 'DATA',
  scopeLevel: 'ORG',
  selector: { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
  data: {
    columnRules: [
      {
        columns: { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
        action: 'MASK',
        masking: { function: 'NULLIFY' },
      },
    ],
  },
};

test('the asset gate comes first, because a policy bound to nothing never runs', () => {
  const flow = buildFlow(DATA);

  expect(flow.steps[0].lane).toBe('which');
  expect(flow.steps[1].lane).toBe('who');
  expect(flow.steps.map((step) => step.kind)).toEqual(['gate', 'gate', 'outcome']);
});

test('an empty selector says it binds to nothing, never to everything', () => {
  const flow = buildFlow({ ...DATA, selector: {} });
  const gate = flow.steps[0];

  expect(gate.items).toHaveLength(0);
  expect(gate.tone).toBe('empty');
  expect(gate.note).toContain('no assets at all');
});

test('an absent subject is drawn as open, not as authored', () => {
  const flow = buildFlow({ ...DATA, subject: undefined });
  const gate = flow.steps[1];

  expect(gate.tone).toBe('open');
  expect(gate.title).toContain('Everyone');
  // Nothing to fail, so nothing branches away: an "if not" on a gate everyone
  // passes would invent a path that does not exist.
  expect(gate.otherwise).toBeUndefined();
});

test('failing the caller gate reads as "not this policy", not as a denial', () => {
  const flow = buildFlow({
    ...DATA,
    subject: { attributes: [{ key: 'clearance', operator: 'gte', value: 'L2' }] },
  });

  expect(flow.steps[1].otherwise).toContain('Another policy may still decide');
  expect(flow.steps[1].otherwise).not.toContain('denied');
});

test('each row filter and column rule is its own box', () => {
  const flow = buildFlow({
    ...DATA,
    data: {
      rowFilters: [
        {
          kind: 'ATTRIBUTE_COMPARE',
          column: 'branch_code',
          operator: 'eq',
          userAttribute: 'branch',
        },
      ],
      columnRules: [
        {
          columns: { condition: { facet: 'columnName', operator: 'eq', value: 'email' } },
          action: 'MASK',
          masking: { function: 'NULLIFY' },
        },
        {
          columns: { condition: { facet: 'columnName', operator: 'eq', value: 'phone' } },
          action: 'HIDE',
        },
      ],
    },
  });

  expect(flow.steps.filter((step) => step.kind === 'outcome')).toHaveLength(3);
});

test('a data policy that restricts nothing says so', () => {
  const flow = buildFlow({ ...DATA, data: {} });
  const outcome = flow.steps[2];

  expect(outcome.title).toBe('Nothing is restricted');
  expect(outcome.tone).toBe('empty');
});

test('a denying subscription policy is the one box drawn in the error tone', () => {
  const flow = buildFlow({
    name: 'deny-offshore',
    policyType: 'SUBSCRIPTION',
    scopeLevel: 'ORG',
    effect: 'DENY',
    selector: { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
  });
  const outcome = flow.steps[flow.steps.length - 1];

  expect(outcome.tone).toBe('deny');
  expect(flow.exit).toContain('refused');
});

test('every box names the form step that writes it, so the chart is not a dead end', () => {
  for (const step of buildFlow(DATA).steps) {
    expect(step.step).toBeGreaterThan(0);
  }
});

test('a policy nothing below can relax says so, and one that can says that instead', () => {
  expect(buildFlow(DATA).gateOpen).toBe(false);
  expect(buildFlow(DATA).gate).toContain('Nothing below this layer');
  expect(buildFlow({ ...DATA, allowLocalOverride: true }).gate).toContain('audit log');
});
