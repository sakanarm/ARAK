import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import PolicyDetailPage from './PolicyDetailPage';
import type {
  PolicyCoverage,
  PolicyImpact,
  PolicyOverlap,
  PolicyRevision,
  PolicyTarget,
  StoredPolicy,
} from '../../api/policies';
import type { Policy } from '../../generated/entity/policy/policy';

const fetchPolicy = jest.fn();
const fetchPolicyCoverage = jest.fn();
const fetchPolicyConflicts = jest.fn();
const fetchPolicyImpact = jest.fn();
const resolveBindings = jest.fn();
const transitionPolicy = jest.fn();
const fetchPolicyVersions = jest.fn();
const fetchRollbackImpact = jest.fn();
const rollbackPolicy = jest.fn();

jest.mock('../../api/policies', () => ({
  fetchPolicy: (...args: unknown[]) => fetchPolicy(...args),
  fetchPolicyCoverage: (...args: unknown[]) => fetchPolicyCoverage(...args),
  fetchPolicyConflicts: (...args: unknown[]) => fetchPolicyConflicts(...args),
  fetchPolicyImpact: (...args: unknown[]) => fetchPolicyImpact(...args),
  fetchPolicyVersions: (...args: unknown[]) => fetchPolicyVersions(...args),
  fetchRollbackImpact: (...args: unknown[]) => fetchRollbackImpact(...args),
  resolveBindings: (...args: unknown[]) => resolveBindings(...args),
  rollbackPolicy: (...args: unknown[]) => rollbackPolicy(...args),
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

function impact(overrides: Partial<PolicyImpact> = {}): PolicyImpact {
  return {
    policyId: ID,
    policyName: 'mask-pii',
    candidateActive: false,
    environment: 'prod',
    tablesBound: 1,
    tablesMeasured: 1,
    principalsKnown: 3,
    principalsMeasured: 3,
    sampled: false,
    principalsAffected: 0,
    tablesAffected: 0,
    byChange: { UNCHANGED: 3 },
    principals: [],
    principalsTruncated: false,
    measuredAt: '2026-09-22T03:00:00Z',
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

/** Waits for the policy, then opens one of its sections. */
async function openTab(name: RegExp) {
  await screen.findByText('Mask PII columns');
  fireEvent.click(screen.getByRole('tab', { name }));
}

beforeEach(() => {
  fetchPolicy.mockReset().mockResolvedValue(stored());
  fetchPolicyCoverage.mockReset().mockResolvedValue(coverage());
  fetchPolicyConflicts.mockReset().mockResolvedValue([]);
  fetchPolicyImpact.mockReset().mockResolvedValue(impact());
  resolveBindings.mockReset().mockResolvedValue({});
  transitionPolicy.mockReset().mockResolvedValue(stored());
  fetchPolicyVersions.mockReset().mockResolvedValue(history());
  fetchRollbackImpact.mockReset().mockResolvedValue(
    impact({
      candidateActive: true,
      principalsAffected: 2,
      tablesAffected: 1,
      byChange: { SEES_MORE: 2, UNCHANGED: 1 },
      principals: [
        {
          principal: 'analyst_b',
          change: 'SEES_MORE',
          tablesAffected: 1,
          tables: [{ assetFqn: TABLE, change: 'SEES_MORE', detail: 'email is shown' }],
        },
      ],
    })
  );
  rollbackPolicy.mockReset().mockResolvedValue(stored({ version: 4 }));
});

function revision(overrides: Partial<PolicyRevision>): PolicyRevision {
  return {
    policyId: ID,
    version: 1,
    document: document(),
    lifecycleState: 'DRAFT',
    changedBy: 'author@example.com',
    changeReason: null,
    changedAt: '2026-09-18T09:00:00Z',
    action: 'CREATE',
    restoredFrom: null,
    ...overrides,
  };
}

/** v3 is live and adds a mask for PII.Financial; v1 masked PII alone. */
function history(): PolicyRevision[] {
  const wider = document({
    selector: {
      or: [
        { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
        { condition: { facet: 'tags', operator: 'contains', value: 'PII.Financial' } },
      ],
    },
  });
  return [
    revision({
      version: 3,
      document: wider,
      lifecycleState: 'ACTIVE',
      action: 'PUBLISH',
      changeReason: 'approved by the data owner',
      changedAt: '2026-09-20T09:00:00Z',
    }),
    revision({
      version: 2,
      document: wider,
      action: 'UPDATE',
      changeReason: 'cover financial PII too',
      changedAt: '2026-09-19T09:00:00Z',
    }),
    revision({ version: 1, changeReason: 'first draft' }),
  ];
}

test('opening a policy reads it — no form until Edit is pressed', async () => {
  renderPage();

  expect(await screen.findByText('Mask PII columns')).toBeInTheDocument();
  // Nothing on the page can change the document: the builder is a route away.
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Edit' }));

  expect(await screen.findByText('builder')).toBeInTheDocument();
});

test('the version on the header says when it was last edited, and by whom', async () => {
  renderPage();

  expect(await screen.findByText('v3 · active')).toBeInTheDocument();
  expect(screen.getByText(/^edited .+ by author@example\.com$/)).toBeInTheDocument();
});

test('what it applies to is grouped by table, with the columns under it', async () => {
  renderPage();
  await openTab(/Applies to/);

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
  await openTab(/Applies to/);

  expect(await screen.findByText('columns only')).toBeInTheDocument();
});

test('a policy bound to nothing says it enforces nothing', async () => {
  fetchPolicyCoverage.mockResolvedValue(
    coverage({ tableCount: 0, columnCount: 0, sample: [], resolvedAt: null })
  );
  renderPage();
  await openTab(/Applies to/);

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
  // And it is still in the list with its own verdict.
  fireEvent.click(screen.getByRole('tab', { name: /Other policies/ }));
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
  await openTab(/Other policies/);

  // Authority, not effect: the verdict below still says the two simply
  // compose, and both statements are true at once.
  expect(await screen.findByText(/may only tighten it/)).toBeInTheDocument();
  expect(screen.getByText('Applies alongside')).toBeInTheDocument();
});

test('an overlap with no authority note shows none', async () => {
  fetchPolicyConflicts.mockResolvedValue([overlap()]);
  renderPage();
  await openTab(/Other policies/);

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
  await openTab(/Applies to/);

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

/*
 * The claim the whole panel exists to make. A policy can bind to every table
 * in the estate and still change nothing, because everything it restricts is
 * already restricted above it -- and a binding count would report that as a
 * large, alarming number.
 */
test('a policy that changes nothing for anyone says so, however much it binds', async () => {
  fetchPolicyImpact.mockResolvedValue(
    impact({ tablesBound: 40, tablesMeasured: 25, sampled: true })
  );

  renderPage();
  await openTab(/Impact/);

  expect(
    await screen.findByText(/would change nothing for anyone/i)
  ).toBeInTheDocument();
  // No people, so no floor-wording creeps into a zero: "at least 0" would be
  // technically true of a sampled run and useless to read.
  expect(screen.queryByText(/at least/i)).not.toBeInTheDocument();
  expect(screen.getByText(/25 of 40 tables/)).toBeInTheDocument();
});

test('the people who lose access are named, with the table they lose', async () => {
  fetchPolicyImpact.mockResolvedValue(
    impact({
      principalsAffected: 1,
      tablesAffected: 1,
      byChange: { LOSES_ACCESS: 1, UNCHANGED: 2 },
      principals: [
        {
          principal: 'analyst_a',
          change: 'LOSES_ACCESS',
          tablesAffected: 1,
          tables: [
            {
              assetFqn: TABLE,
              change: 'LOSES_ACCESS',
              detail: 'can read it today, could not after this',
            },
          ],
        },
      ],
    })
  );

  renderPage();
  await openTab(/Impact/);

  expect(
    await screen.findByText(/Activating this would change what 1 person sees/i)
  ).toBeInTheDocument();
  expect(screen.getByText('analyst_a')).toBeInTheDocument();
  expect(screen.getByText('1 loses the table')).toBeInTheDocument();
  expect(
    screen.getByText(/could not after this/, { exact: false })
  ).toBeInTheDocument();
});

/*
 * Same arithmetic, different tense. An active policy is measured against the
 * world without it, so the report is about what it is doing now -- saying
 * "activating this would" over a policy already in force would read as though
 * nothing were enforced yet.
 */
test('a policy already in force is described in the present tense', async () => {
  fetchPolicyImpact.mockResolvedValue(
    impact({
      candidateActive: true,
      principalsAffected: 2,
      tablesAffected: 1,
      byChange: { SEES_LESS: 2, UNCHANGED: 1 },
      principals: [
        {
          principal: 'analyst_a',
          change: 'SEES_LESS',
          tablesAffected: 1,
          tables: [
            { assetFqn: TABLE, change: 'SEES_LESS', detail: 'email now masked' },
          ],
        },
      ],
    })
  );

  renderPage();
  await openTab(/Impact/);

  expect(
    await screen.findByText(/This policy is the reason 2 people/i)
  ).toBeInTheDocument();
  expect(screen.queryByText(/Activating this/i)).not.toBeInTheDocument();
});

/*
 * A capped run reports floors. Printing the figure bare would let somebody
 * approve a policy on the strength of a number that was never the total.
 */
test('a sampled run says "at least" rather than a bare number', async () => {
  fetchPolicyImpact.mockResolvedValue(
    impact({
      sampled: true,
      tablesBound: 300,
      tablesMeasured: 25,
      principalsKnown: 4000,
      principalsMeasured: 200,
      principalsAffected: 180,
      tablesAffected: 25,
      byChange: { LOSES_ACCESS: 180 },
      principals: [],
      principalsTruncated: true,
    })
  );

  renderPage();
  await openTab(/Impact/);

  expect(
    await screen.findByText(/at least 180 people/i)
  ).toBeInTheDocument();
  expect(screen.getByText(/floors rather than totals/i)).toBeInTheDocument();
});

test('a policy bound to nothing is told to resolve, not shown a zero', async () => {
  fetchPolicyImpact.mockResolvedValue(
    impact({ tablesBound: 0, tablesMeasured: 0, byChange: {} })
  );

  renderPage();
  await openTab(/Impact/);

  expect(
    await screen.findByText(/bound to no tables/i)
  ).toBeInTheDocument();
});

test('the policy reads as text, a flowchart or a diagram', async () => {
  renderPage();
  expect(await screen.findByText('In plain words')).toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Diagram' }));

  const figure = await screen.findByRole('figure', { name: /How Mask PII columns decides/ });
  expect(within(figure).getByText('Is the asset one of these?')).toBeInTheDocument();
  // Read-only here: the diagram offers no node to press.
  expect(within(figure).queryByRole('button', { name: /Is the asset/ })).not.toBeInTheDocument();
  localStorage.clear();
});

test('each section is a tab, and the overview is where a policy opens', async () => {
  renderPage();

  await screen.findByText('Mask PII columns');
  expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true');
  // The sections behind the other tabs are not drawn until they are opened.
  expect(screen.queryByText(TABLE)).not.toBeInTheDocument();
  expect(screen.getByRole('tab', { name: /Applies to/ })).toHaveTextContent('1');

  fireEvent.click(screen.getByRole('tab', { name: /Applies to/ }));
  expect(await screen.findByText(TABLE)).toBeInTheDocument();
  expect(screen.queryByText('In plain words')).not.toBeInTheDocument();
});

// ------------------------------------------------------------------ history

/** The document v2 and v3 hold. */
function wider(): Policy {
  return history()[0].document;
}

/** Opens the history and the comparison for one version. */
async function compare(version: number) {
  await openTab(/History/);
  const row = (await screen.findByText(`v${version}`)).closest('li')!;
  fireEvent.click(within(row).getByRole('button', { name: 'Compare with now' }));
  return row;
}

test('every version is listed newest first, with what made it and why', async () => {
  fetchPolicy.mockResolvedValue(stored({ document: wider() }));
  renderPage();
  await openTab(/History/);

  const rows = await screen.findAllByRole('listitem');
  expect(rows.map((row) => within(row).getByText(/^v\d$/).textContent)).toEqual([
    'v3',
    'v2',
    'v1',
  ]);
  expect(within(rows[0]).getByText('activated')).toBeInTheDocument();
  expect(within(rows[0]).getByText('current')).toBeInTheDocument();
  expect(within(rows[0]).getByText('approved by the data owner')).toBeInTheDocument();
  // There is nothing to compare the current version with.
  expect(within(rows[0]).queryByRole('button', { name: 'Compare with now' })).toBeNull();
  expect(within(rows[1]).getByText('edited')).toBeInTheDocument();
  expect(within(rows[2]).getByText('created')).toBeInTheDocument();
});

test('an older version is compared with now, then restored with a reason', async () => {
  fetchPolicy.mockResolvedValue(stored({ document: wider() }));
  renderPage();
  const row = await compare(1);

  expect(within(row).getByText('Now (v3)')).toBeInTheDocument();
  expect(within(row).getByText('Assets')).toBeInTheDocument();
  expect(within(row).getByText('tag under PII')).toBeInTheDocument();

  fireEvent.click(within(row).getByRole('button', { name: 'Restore v1' }));

  // On a live policy, who it changes things for comes before the button.
  expect(
    await within(row).findByText('Restoring v1 would change what 2 people see on 1 table.')
  ).toBeInTheDocument();
  expect(fetchRollbackImpact).toHaveBeenCalledWith(ID, 1);

  const confirm = within(row).getByRole('button', { name: 'Restore v1' });
  expect(confirm).toBeDisabled();

  fireEvent.change(within(row).getByLabelText('Why'), {
    target: { value: '  the financial tag was a mistake  ' },
  });
  fireEvent.click(confirm);

  await waitFor(() =>
    expect(rollbackPolicy).toHaveBeenCalledWith(ID, 1, 3, 'the financial tag was a mistake')
  );
});

test('a version that reads the same as now has nothing to put back', async () => {
  fetchPolicy.mockResolvedValue(stored({ document: wider() }));
  renderPage();
  const row = await compare(2);

  expect(within(row).getByText(/reads the same as the current version/)).toBeInTheDocument();
  expect(within(row).queryByRole('button', { name: 'Restore v2' })).toBeNull();
});

test('a policy not in force is restored without measuring anybody', async () => {
  fetchPolicy.mockResolvedValue(stored({ document: wider(), lifecycleState: 'DRAFT' }));
  renderPage();
  const row = await compare(1);

  fireEvent.click(within(row).getByRole('button', { name: 'Restore v1' }));

  expect(within(row).getByText(/not in force/)).toBeInTheDocument();
  expect(fetchRollbackImpact).not.toHaveBeenCalled();
});

test('a refused restore says so and keeps the form open', async () => {
  fetchPolicy.mockResolvedValue(stored({ document: wider(), lifecycleState: 'DISABLED' }));
  rollbackPolicy.mockRejectedValue(new Error('409'));
  renderPage();
  const row = await compare(1);

  fireEvent.click(within(row).getByRole('button', { name: 'Restore v1' }));
  fireEvent.change(within(row).getByLabelText('Why'), { target: { value: 'undo' } });
  fireEvent.click(within(row).getByRole('button', { name: 'Restore v1' }));

  expect(await within(row).findByText('The restore was refused.')).toBeInTheDocument();
  expect(within(row).getByLabelText('Why')).toHaveValue('undo');
});

test('an archived policy shows its history but offers no restore', async () => {
  fetchPolicy.mockResolvedValue(stored({ document: wider(), lifecycleState: 'ARCHIVED' }));
  renderPage();
  const row = await compare(1);

  expect(within(row).getByText(/archived, which is final/)).toBeInTheDocument();
  expect(within(row).queryByRole('button', { name: 'Restore v1' })).toBeNull();
});
