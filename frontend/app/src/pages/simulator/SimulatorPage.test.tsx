import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import SimulatorPage, { describePredicate } from './SimulatorPage';
import type { PolicyDecision } from '../../api/decisions';

const simulate = jest.fn();
const fetchAsset = jest.fn();

jest.mock('../../api/decisions', () => ({
  simulate: (...args: unknown[]) => simulate(...args),
}));

jest.mock('../../api/client', () => ({
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
  fetchAsset: (...args: unknown[]) => fetchAsset(...args),
  fetchAssets: () => Promise.resolve({ items: [], total: 0 }),
}));

jest.mock('../../api/governance', () => ({
  fetchPrincipals: () => Promise.resolve([]),
}));

jest.mock('../../api/policies', () => ({
  ENFORCED_ENVIRONMENT: 'prod',
}));

function decision(overrides: Partial<PolicyDecision> = {}): PolicyDecision {
  return {
    principal: 'analyst_a',
    assetFqn: 'prod-pg.sales.public.customer',
    allowed: true,
    reasons: [],
    rowPredicates: [],
    columnMasks: [],
    hiddenColumns: [],
    unenforceable: [],
    ...overrides,
  } as PolicyDecision;
}

function column(name: string) {
  return {
    id: `c-${name}`,
    fqn: `prod-pg.sales.public.customer.${name}`,
    name,
    ordinal: 0,
    dataType: 'VARCHAR',
    dataLength: null,
    nullable: true,
    description: null,
    facets: [],
  };
}

function renderPage(search = '') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/simulator${search}`]}>
        <Routes>
          <Route element={<SimulatorPage />} path="/simulator" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

async function ask(search: string) {
  renderPage(search);
  fireEvent.click(screen.getByRole('button', { name: /see what they see/i }));
}

beforeEach(() => {
  jest.clearAllMocks();
  fetchAsset.mockResolvedValue({
    asset: { fqn: 'prod-pg.sales.public.customer' },
    customProperties: [],
    columns: [column('id'), column('email'), column('citizen_id')],
    facets: [],
    owners: [],
  });
});

const ASKED = '?principal=analyst_a&asset=prod-pg.sales.public.customer';

/*
 * A column with no mask on it is the finding this screen exists to surface --
 * "citizen_id comes back as stored" is what sends somebody to write a policy.
 * It only appears because the projection is rendered against the catalogue's
 * columns rather than against the decision's mask list, so it is asserted
 * rather than left to whoever next edits that component.
 */
test('a sensitive column nobody masked is shown as readable', async () => {
  simulate.mockResolvedValue(
    decision({
      columnMasks: [
        {
          column: 'email',
          masking: { function: 'REGEX_REPLACE', regex: '^[^@]+', replacement: '***' },
        },
      ],
      hiddenColumns: ['id'],
    }) as PolicyDecision
  );

  await ask(ASKED);

  expect(await screen.findByText('as stored')).toBeInTheDocument();
  expect(screen.getByText('citizen_id')).toBeInTheDocument();
  expect(
    screen.getByText('1 as stored · 1 masked · 1 hidden')
  ).toBeInTheDocument();
  expect(screen.getByText(/not in the results/i)).toBeInTheDocument();
});

test('a denial does not pretend there is a projection underneath it', async () => {
  simulate.mockResolvedValue(
    decision({
      allowed: false,
      reasons: [
        {
          policyId: 'p1',
          policyName: 'Residency',
          scopeLevel: 'ORG',
          effect: 'DENY',
          matched: true,
          explanation: 'Their country does not match the asset residency.',
        },
      ],
    })
  );

  await ask(ASKED);

  expect(
    await screen.findByText(/cannot read this table/i)
  ).toBeInTheDocument();
  // No column list at all -- what would have been masked under a denial is not
  // a smaller version of the same answer, it is a different one.
  expect(screen.queryByText('citizen_id')).not.toBeInTheDocument();
  expect(screen.getByText(/Residency/)).toBeInTheDocument();
});

/*
 * The regression this guards: under a denial, `matched` alone put the green
 * ALLOW that happened to match at the top of Why, and hid the two sentences
 * that actually answered the question behind a disclosure triangle.
 */
test('a denial leads with what withheld access, not with what matched', async () => {
  simulate.mockResolvedValue(
    decision({
      allowed: false,
      reasons: [
        {
          policyId: 'p1',
          policyName: 'Finance subscription',
          effect: 'ALLOW',
          matched: false,
          explanation: 'attribute condition not satisfied: clearance lt L2',
        },
        {
          policyId: 'p2',
          policyName: 'Branch rows',
          effect: 'ALLOW',
          matched: true,
          explanation: 'policy carries no subject rule.',
        },
        {
          policyName: '(composition)',
          matched: false,
          explanation: 'no policy at layer 0 grants this principal access.',
        },
      ],
    })
  );

  await ask(ASKED);

  // The engine's own sentence about how the layers added up, said first.
  expect(
    await screen.findByText(/no policy at layer 0 grants/i)
  ).toBeInTheDocument();
  // The policy that withheld access is decisive, with its condition in words.
  expect(screen.getByText('Finance subscription')).toBeInTheDocument();
  expect(screen.getByText(/clearance lt L2/)).toBeInTheDocument();
  // The one that matched anyway did not cause this, so it steps aside.
  expect(
    screen.getByText(/1 more did apply and did not change the outcome/i)
  ).toBeInTheDocument();
});

test('policies that were considered and did not apply are kept out of the way', async () => {
  simulate.mockResolvedValue(
    decision({
      reasons: [
        {
          policyId: 'p1',
          policyName: 'Finance analysts',
          effect: 'ALLOW',
          matched: true,
          explanation: 'They are in team Finance.',
        },
        {
          policyId: 'p2',
          policyName: 'Out of hours block',
          effect: 'DENY',
          matched: false,
          explanation: 'The request is inside the window.',
        },
      ],
    })
  );

  await ask(ASKED);

  expect(await screen.findByText('Finance analysts')).toBeInTheDocument();
  expect(
    screen.getByText(/1 more were considered and did not apply/i)
  ).toBeInTheDocument();
});

test('a restriction the chosen mode cannot express is said out loud', async () => {
  simulate.mockResolvedValue(
    decision({
      unenforceable: [
        {
          policyId: 'p3',
          detail: 'Dynamic Data Masking cannot mask salary per row.',
          suggestedMode: 'SECURE_VIEW',
        },
      ],
    })
  );

  await ask(ASKED);

  expect(
    await screen.findByText(/cannot mask salary per row/i)
  ).toBeInTheDocument();
  expect(screen.getByText(/a secure view can/i)).toBeInTheDocument();
});

test('asking is refused until both a person and a table are named', () => {
  renderPage('?asset=prod-pg.sales.public.customer');

  expect(
    screen.getByRole('button', { name: /see what they see/i })
  ).toBeDisabled();
  expect(simulate).not.toHaveBeenCalled();
});

/*
 * The wording carries this person's resolved values rather than the name of
 * the attribute the policy was written against: "one of BKK, CNX" is an answer
 * about them, "matches their branch" is a restatement of the policy.
 */
describe('describePredicate', () => {
  test('names the values the attribute resolved to', () => {
    expect(
      describePredicate({
        kind: 'IN_LIST',
        column: 'branch_code',
        values: ['BKK', 'CNX'],
      })
    ).toBe('Only rows where branch_code is one of BKK, CNX.');
  });

  test('one value is not phrased as a list of one', () => {
    expect(
      describePredicate({
        kind: 'IN_LIST',
        column: 'branch_code',
        values: ['BKK-01'],
      })
    ).toBe('Only rows where branch_code is BKK-01.');
  });

  test('an attribute this person does not carry is stated as no rows', () => {
    expect(
      describePredicate({
        kind: 'IN_LIST',
        column: 'branch_code',
        values: [],
      })
    ).toMatch(/No rows/);
  });

  test('a lookup names the mapping and this person’s own keys, never what it gives', () => {
    const joined = describePredicate({
      kind: 'LOOKUP',
      column: 'division',
      operator: 'in',
      lookup: {
        table: 'warehouse.sales.ref.department_division',
        valueColumn: 'division',
        mode: 'SUBQUERY',
        keys: [
          { column: 'department', userAttribute: 'department', values: ['AA', 'AB'] },
          { column: 'region', userAttribute: 'region', values: ['North'] },
        ],
      },
    });
    expect(joined).toBe(
      'Only rows where division is one of the division values in' +
        ' warehouse.sales.ref.department_division for department AA, AB and region North;' +
        ' the source reads them as part of the query.'
    );

    const read = describePredicate({
      kind: 'LOOKUP',
      column: 'division',
      lookup: {
        table: 'warehouse.sales.ref.department_division',
        valueColumn: 'division',
        mode: 'READ_VALUES',
        keys: [{ column: 'department', values: ['AA'] }],
      },
    });
    expect(read).toMatch(/ARAK reads them when the query runs\.$/);

    expect(describePredicate({ kind: 'LOOKUP', column: 'division' })).toMatch(/^No rows/);
  });

  test('ALWAYS_FALSE says the shape survives and the contents do not', () => {
    expect(describePredicate({ kind: 'ALWAYS_FALSE' })).toMatch(
      /shape of the table stays visible/
    );
  });
});
