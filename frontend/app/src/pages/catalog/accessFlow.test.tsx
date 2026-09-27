import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { GrantAccess } from '../../api/access';
import type { AppliedPolicy, StoredPolicy } from '../../api/policies';
import { AccessDecision } from './AccessDecision';
import { accessFlow } from './accessFlow';
import { DATA, DENIED, DENIES, EXIT, accessDiagram } from './accessDiagram';

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
  });

  it('draws denies as one node, a node per allow layer, and data policies as one', () => {
    const { nodes, edges, steps } = accessDiagram(
      accessFlow([MASK_AMOUNT, ALLOW_BUYERS, DENY_CONTRACTORS, ALLOW_FINANCE], [grant()])
    );
    const tasks = nodes.filter((node) => node.kind === 'task').map((node) => node.id);
    expect(tasks).toEqual([DENIES, 'gate-ORG', 'gate-TABLE', DATA]);

    // Every way to be refused runs down to the one Denied end, past the last check.
    const refused = edges.filter((edge) => edge.to === DENIED);
    expect(refused.map((edge) => [edge.from, edge.label])).toEqual([
      [DENIES, 'Yes'],
      ['gate-ORG', 'No'],
      ['gate-TABLE', 'No'],
    ]);
    const denied = nodes.find((node) => node.id === DENIED)!;
    expect(denied.column).toBe(nodes.find((node) => node.id === DATA)!.column);
    expect(edges.find((edge) => edge.from === DENIES && edge.to === 'gate-ORG')?.label).toBe('No');

    // A gate a grant can get past says so, first.
    const table = nodes.find((node) => node.id === 'gate-TABLE')!;
    expect(table.lines?.[0]).toEqual({
      text: 'A direct grant or a narrower layer can let them past',
      caution: true,
    });
    expect(nodes.find((node) => node.id === DATA)?.lines?.[0].text).toBe(
      'Mask PO amounts · in the team Procurement'
    );
    expect(nodes.find((node) => node.id === EXIT)?.tone).toBe('warning');
    expect(steps[DATA].map((step) => step.id)).toEqual(['data-mask-amount']);
  });

  it('ends without a Denied lane when nothing opens the table', () => {
    const { nodes, edges } = accessDiagram(accessFlow([], []));
    expect(nodes.some((node) => node.id === DENIED)).toBe(false);
    expect(edges.at(-1)).toEqual({ from: 'grants', to: EXIT });
    expect(nodes.find((node) => node.id === EXIT)?.tone).toBe('error');
  });

  it('draws the decision on a canvas, a click opens a step in full, and the flowchart is a toggle away', async () => {
    fetchPoliciesForAsset.mockResolvedValue([ALLOW_FINANCE, MASK_AMOUNT]);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <AccessDecision fqn={FQN} grants={[grant()]} />
        </MemoryRouter>
      </QueryClientProvider>
    );

    expect(
      await screen.findByRole('figure', { name: 'How access to this table is decided' })
    ).toBeInTheDocument();
    expect(screen.getByText('Read requested')).toBeInTheDocument();
    expect(screen.getByText('What they see')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'The step, in full' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /What they see/ }));
    const detail = screen.getByRole('region', { name: 'The step, in full' });
    expect(within(detail).getByRole('link', { name: 'Mask PO amounts' })).toHaveAttribute(
      'href',
      '/policies/mask-amount'
    );
    expect(within(detail).getByText('Apply these restrictions, then carry on')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Flowchart' }));
    expect(screen.getByText('Someone asks to read this table')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Finance reads finance' })).toHaveAttribute(
      'href',
      '/policies/allow-finance'
    );
    expect(fetchPoliciesForAsset).toHaveBeenCalledWith(FQN);
  });
});
