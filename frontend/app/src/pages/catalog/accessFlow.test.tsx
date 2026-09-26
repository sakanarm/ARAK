import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { GrantAccess } from '../../api/access';
import type { AppliedPolicy, StoredPolicy } from '../../api/policies';
import { AccessDecision } from './AccessDecision';
import { accessFlow } from './accessFlow';

const fetchPoliciesForAsset = jest.fn();

jest.mock('../../api/policies', () => ({
  ...jest.requireActual('../../api/policies'),
  fetchPoliciesForAsset: (...args: unknown[]) => fetchPoliciesForAsset(...args),
}));

const FQN = 'demo-pg.salesdb.procurement.po';

function applied(
  id: string,
  document: Record<string, unknown>,
  columns: string[] = []
): AppliedPolicy {
  return {
    policy: {
      id,
      lifecycleState: 'ACTIVE',
      environment: 'prod',
      version: 1,
      createdBy: 'author_a',
      updatedBy: 'author_a',
      updatedAt: '2026-09-01T00:00:00Z',
      document: {
        name: id,
        selector: {},
        scopeLevel: 'ORG',
        ...document,
      } as unknown as StoredPolicy['document'],
    },
    matchReason: {},
    columns: columns.map((name) => ({
      fqn: `${FQN}.${name}`,
      kind: 'COLUMN' as const,
      name,
      parentFqn: FQN,
      dataType: 'NUMERIC',
      matchReason: { action: 'MASK' },
      resolvedAt: null,
    })),
  };
}

function grant(extra: Partial<GrantAccess> = {}): GrantAccess {
  return {
    id: 'g-1',
    principal: 'buyer_a',
    displayName: null,
    principalType: 'user',
    principalSource: 'local',
    validFrom: null,
    validUntil: null,
    reason: 'UAT',
    grantedBy: 'owner_a',
    grantedAt: '2026-09-01T00:00:00Z',
    live: true,
    effectiveFor: 1,
    ...extra,
  } as GrantAccess;
}

const DENY_CONTRACTORS = applied('deny-contractors', {
  displayName: 'No contractors',
  policyType: 'SUBSCRIPTION',
  effect: 'DENY',
  subject: { attributes: [{ key: 'employeeType', operator: 'eq', value: 'contractor' }] },
});
const ALLOW_FINANCE = applied('allow-finance', {
  displayName: 'Finance reads finance',
  policyType: 'SUBSCRIPTION',
  effect: 'ALLOW',
  subject: { principals: [{ team: 'Finance' }] },
});
const ALLOW_BUYERS = applied('allow-buyers', {
  displayName: 'Buyers read PO',
  policyType: 'SUBSCRIPTION',
  effect: 'ALLOW',
  scopeLevel: 'TABLE',
  scopeFqn: FQN,
  allowLocalOverride: true,
  subject: { principals: [{ group: 'Buyers' }] },
});
const MASK_AMOUNT = applied(
  'mask-amount',
  {
    displayName: 'Mask PO amounts',
    policyType: 'DATA',
    scopeLevel: 'SCHEMA',
    scopeFqn: 'demo-pg.salesdb.procurement',
    subject: { principals: [{ team: 'Procurement' }] },
    data: {
      rowFilters: [{ kind: 'ATTRIBUTE_COMPARE', column: 'branch', userAttribute: 'branch' }],
      columnRules: [
        { columns: { condition: { facet: 'column', operator: 'eq', value: 'amount' } }, action: 'MASK', masking: { function: 'NULLIFY' } },
      ],
    },
  },
  ['amount']
);

describe('the access decision for one table', () => {
  it('puts denies first, then one gate per layer outermost first, then data policies', () => {
    // Handed over out of order on purpose: the chart sorts by the engine's layers.
    const flow = accessFlow([MASK_AMOUNT, ALLOW_BUYERS, ALLOW_FINANCE, DENY_CONTRACTORS], [grant()]);

    expect(flow.steps.map((step) => step.id)).toEqual([
      'deny-deny-contractors',
      'gate-ORG',
      'gate-TABLE',
      'data-mask-amount',
    ]);
    const [deny, org, table, data] = flow.steps;
    expect(deny.yesTone).toBe('deny');
    expect(deny.title).toMatch(/employeeType/);
    // The organisation gate did not consent to relaxing, so a grant cannot pass it.
    expect(org.no).toMatch(/does not let a grant past/);
    // The table gate did, and names who a grant would let through.
    expect(table.no).toMatch(/buyer_a/);
    expect(data.items.map((item) => item.text).join(' ')).toMatch(/amount/);
    expect(data.no).toMatch(/skipped/);
    expect(flow.exitTone).toBe('restrict');
  });

  it('lets a grant decide on its own when nothing gates the table', () => {
    const flow = accessFlow([], [grant({ principal: 'Buyers', principalType: 'group' })]);
    expect(flow.steps).toHaveLength(1);
    expect(flow.steps[0].title).toBe('Is the caller named in a direct grant?');
    expect(flow.steps[0].items[0].text).toBe('anyone in the group Buyers');
    expect(flow.exitTone).toBe('allow');
  });

  it('says nobody gets in when there is no policy and no live grant', () => {
    const flow = accessFlow([], [grant({ live: false })]);
    expect(flow.steps[0].title).toBe('Nothing opens this table');
    expect(flow.exitTone).toBe('deny');
    expect(flow.layers).toEqual([]);
  });

  it('draws the layers as a diagram, and each policy links to its page', async () => {
    fetchPoliciesForAsset.mockResolvedValue([ALLOW_FINANCE, MASK_AMOUNT]);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <AccessDecision fqn={FQN} grants={[grant()]} />
        </MemoryRouter>
      </QueryClientProvider>
    );

    expect(await screen.findByText('Someone asks to read this table')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Finance reads finance' })).toHaveAttribute(
      'href',
      '/policies/allow-finance'
    );

    fireEvent.click(screen.getByRole('button', { name: 'Diagram' }));
    expect(screen.getByRole('button', { name: 'Diagram' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('1. Organisation')).toBeInTheDocument();
    expect(screen.getByText(/^3\. Table/)).toBeInTheDocument();
    expect(screen.getByText('restricts amount')).toBeInTheDocument();
    expect(fetchPoliciesForAsset).toHaveBeenCalledWith(FQN);
  });
});
