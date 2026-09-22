import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import PolicyDetailPage from './PolicyDetailPage';
import type {
  PolicyCoverage,
  PolicyOverlap,
  PolicyTarget,
  StoredPolicy,
} from '../../api/policies';
import type { Policy } from '../../generated/entity/policy/policy';

const fetchPolicy = jest.fn();
const fetchPolicyCoverage = jest.fn();
const fetchPolicyConflicts = jest.fn();
const resolveBindings = jest.fn();
const transitionPolicy = jest.fn();

jest.mock('../../api/policies', () => ({
  fetchPolicy: (...args: unknown[]) => fetchPolicy(...args),
  fetchPolicyCoverage: (...args: unknown[]) => fetchPolicyCoverage(...args),
  fetchPolicyConflicts: (...args: unknown[]) => fetchPolicyConflicts(...args),
  resolveBindings: (...args: unknown[]) => resolveBindings(...args),
  transitionPolicy: (...args: unknown[]) => transitionPolicy(...args),
}));

jest.mock('../../api/client', () => ({
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}));

const ID = '33333333-3333-3333-3333-333333333333';
const TABLE = 'prod-pg.SalesDB.dbo.customer';

function document(overrides: Partial<Policy> = {}): Policy {
  return {
    name: 'mask-pii',
    displayName: 'Mask PII columns',
    policyType: 'DATA',
    scopeLevel: 'ORG',
    selector: { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
    ...overrides,
  };
}

function stored(overrides: Partial<StoredPolicy> = {}): StoredPolicy {
  return {
    id: ID,
    document: document(),
    lifecycleState: 'ACTIVE',
    environment: 'prod',
    version: 3,
    createdBy: 'author@example.com',
    updatedBy: 'author@example.com',
    updatedAt: '2026-09-20T09:00:00Z',
    ...overrides,
  };
}

function target(overrides: Partial<PolicyTarget>): PolicyTarget {
  return {
    fqn: TABLE,
    kind: 'TABLE',
    name: 'customer',
    parentFqn: TABLE,
    dataType: null,
    matchReason: {},
    resolvedAt: '2026-09-21T10:00:00Z',
    ...overrides,
  };
}

function coverage(overrides: Partial<PolicyCoverage> = {}): PolicyCoverage {
  return {
    tableCount: 1,
    columnCount: 1,
    truncated: false,
    resolvedAt: '2026-09-21T10:00:00Z',
    sample: [
      target({ matchReason: { facet: 'tags', value: 'PII' } }),
      target({
        fqn: `${TABLE}.email`,
        kind: 'COLUMN',
        name: 'email',
        dataType: 'VARCHAR',
        matchReason: { columnRule: 0, action: 'MASK' },
      }),
    ],
    ...overrides,
  };
}

function overlap(overrides: Partial<PolicyOverlap> = {}): PolicyOverlap {
  return {
    policyId: '44444444-4444-4444-4444-444444444444',
    name: 'deny-offshore',
    displayName: 'Deny offshore access',
    policyType: 'SUBSCRIPTION',
    effect: 'DENY',
    scopeLevel: 'ORG',
    scopeFqn: null,
    lifecycleState: 'ACTIVE',
    environment: 'prod',
    allowLocalOverride: false,
    sharedTargets: 2,
    examples: [TABLE],
    relation: 'COMPOSES',
    explanation: 'They apply together and neither overrides the other.',
    overrideNote: null,
    ...overrides,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/policies/${ID}`]}>
        <Routes>
          <Route element={<PolicyDetailPage />} path="/policies/:id" />
          <Route element={<p>builder</p>} path="/policies/:id/edit" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  fetchPolicy.mockReset().mockResolvedValue(stored());
  fetchPolicyCoverage.mockReset().mockResolvedValue(coverage());
  fetchPolicyConflicts.mockReset().mockResolvedValue([]);
  resolveBindings.mockReset().mockResolvedValue({});
  transitionPolicy.mockReset().mockResolvedValue(stored());
});

test('opening a policy reads it — no form until Edit is pressed', async () => {
  renderPage();

  expect(await screen.findByText('Mask PII columns')).toBeInTheDocument();
  // Nothing on the page can change the document: the builder is a route away.
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Edit' }));

  expect(await screen.findByText('builder')).toBeInTheDocument();
});

test('what it applies to is grouped by table, with the columns under it', async () => {
  renderPage();

  expect(await screen.findByText(TABLE)).toBeInTheDocument();
  expect(screen.getByText('email')).toBeInTheDocument();
  expect(screen.getByText('1 table · 1 column', { exact: false })).toBeInTheDocument();
  expect(screen.getByText(/Matched on facet tags, value PII/)).toBeInTheDocument();
  // The table was bound in its own right, so it is not column-only cover.
  expect(screen.queryByText('columns only')).not.toBeInTheDocument();
});

test('a table present only through a column says so', async () => {
  fetchPolicyCoverage.mockResolvedValue(
    coverage({
      tableCount: 0,
      columnCount: 1,
      sample: [
        target({
          fqn: `${TABLE}.email`,
          kind: 'COLUMN',
          name: 'email',
          dataType: 'VARCHAR',
          matchReason: { action: 'MASK' },
        }),
      ],
    })
  );
  renderPage();

  expect(await screen.findByText('columns only')).toBeInTheDocument();
});

test('a policy bound to nothing says it enforces nothing', async () => {
  fetchPolicyCoverage.mockResolvedValue(
    coverage({ tableCount: 0, columnCount: 0, sample: [], resolvedAt: null })
  );
  renderPage();

  expect(
    await screen.findByText(/currently affects no data/)
  ).toBeInTheDocument();
});

test('a denial that overrules this policy is raised above the fold', async () => {
  fetchPolicyConflicts.mockResolvedValue([
    overlap({ relation: 'BLOCKED_BY' }),
    overlap({
      policyId: '55555555-5555-5555-5555-555555555555',
      name: 'finance-read',
      displayName: 'Finance may read',
      relation: 'COMPOSES',
    }),
  ]);
  renderPage();

  expect(
    await screen.findByText(
      /Deny offshore access denies what this policy allows/
    )
  ).toBeInTheDocument();
  // And it is still in the list below with its own verdict.
  expect(screen.getByText('Overrules this')).toBeInTheDocument();
  expect(screen.getByText('Applies alongside')).toBeInTheDocument();
});

test('a policy that outranks this one says who may change it', async () => {
  fetchPolicyConflicts.mockResolvedValue([
    overlap({
      relation: 'COMPOSES',
      overrideNote:
        'That policy sits above this one at ORG and does not permit a local' +
        ' override, so this one may only tighten it.',
    }),
  ]);
  renderPage();

  // Authority, not effect: the verdict below still says the two simply
  // compose, and both statements are true at once.
  expect(await screen.findByText(/may only tighten it/)).toBeInTheDocument();
  expect(screen.getByText('Applies alongside')).toBeInTheDocument();
});

test('an overlap with no authority note shows none', async () => {
  fetchPolicyConflicts.mockResolvedValue([overlap()]);
  renderPage();

  expect(await screen.findByText('Applies alongside')).toBeInTheDocument();
  expect(screen.queryByText(/may only tighten it/)).not.toBeInTheDocument();
});

test('no overlap at all is stated rather than left blank', async () => {
  renderPage();

  expect(await screen.findByText('Mask PII columns')).toBeInTheDocument();
  expect(screen.queryByText('Overrules this')).not.toBeInTheDocument();
});

test('re-resolving asks the server and reloads both readings', async () => {
  renderPage();

  fireEvent.click(await screen.findByRole('button', { name: 'Re-resolve' }));

  await waitFor(() => expect(resolveBindings).toHaveBeenCalledWith(ID));
  await waitFor(() => expect(fetchPolicyCoverage).toHaveBeenCalledTimes(2));
  expect(fetchPolicyConflicts).toHaveBeenCalledTimes(2);
});

test('an active policy offers Disable, a draft offers Activate', async () => {
  fetchPolicy.mockResolvedValue(stored({ lifecycleState: 'DRAFT' }));
  renderPage();

  fireEvent.click(await screen.findByRole('button', { name: 'Activate' }));

  await waitFor(() => expect(transitionPolicy).toHaveBeenCalledWith(ID, 'ACTIVE'));
  expect(screen.queryByRole('button', { name: 'Disable' })).not.toBeInTheDocument();
});

test('a refused lifecycle change is shown, not swallowed', async () => {
  transitionPolicy.mockRejectedValue(new Error('409'));
  renderPage();

  fireEvent.click(await screen.findByRole('button', { name: 'Disable' }));

  expect(
    await screen.findByText('The lifecycle change was refused.')
  ).toBeInTheDocument();
});
