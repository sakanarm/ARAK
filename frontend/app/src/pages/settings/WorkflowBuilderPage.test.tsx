import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import WorkflowBuilderPage from './WorkflowBuilderPage';
import { workflowDiagram } from './workflowDiagram';
import type {
  AccessWorkflow,
  WorkflowDraft,
  WorkflowExecutions,
  WorkflowListing,
  WorkflowRow,
  WorkflowStage,
} from '../../api/accessWorkflows';

const fetchWorkflows = jest.fn();
const createWorkflow = jest.fn();
const updateWorkflow = jest.fn();
const fetchWorkflowExecutions = jest.fn();
const fetchAssets = jest.fn();
const fetchPrincipals = jest.fn();
let roles: string[] = ['PLATFORM_ADMIN'];

jest.mock('../../api/accessWorkflows', () => {
  const actual = jest.requireActual('../../api/accessWorkflows');
  return {
    ...actual,
    fetchWorkflows: () => fetchWorkflows(),
    createWorkflow: (...args: unknown[]) => createWorkflow(...args),
    updateWorkflow: (...args: unknown[]) => updateWorkflow(...args),
    fetchWorkflowExecutions: (...args: unknown[]) => fetchWorkflowExecutions(...args),
  };
});

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
  fetchAssets: (...args: unknown[]) => fetchAssets(...args),
}));

jest.mock('../../api/governance', () => ({
  fetchPrincipals: (...args: unknown[]) => fetchPrincipals(...args),
}));

jest.mock('../../auth/authStore', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) =>
    selector({
      user: { username: 'admin' },
      hasRole: (...wanted: string[]) => wanted.some((role) => roles.includes(role)),
    }),
}));

const OWNERS_STAGE: WorkflowStage = {
  step: 1,
  name: 'Owner approval',
  rule: 'ANY',
  minApprovals: null,
  onReject: 'VETO',
  approvers: [{ kind: 'ASSET_OWNERS' }],
};

const BUILT_IN: AccessWorkflow = {
  id: null,
  name: 'Built-in',
  description: null,
  scopeFqn: null,
  enabled: true,
  stages: [OWNERS_STAGE],
  configurers: [],
};

const FINANCE: AccessWorkflow = {
  id: 'wf-finance',
  name: 'Finance tables',
  description: 'Money needs two more pairs of eyes',
  scopeFqn: 'demo-pg.salesdb.finance',
  enabled: true,
  stages: [
    OWNERS_STAGE,
    {
      step: 2,
      name: 'Security',
      rule: 'ALL',
      minApprovals: null,
      onReject: 'VETO',
      approvers: [{ kind: 'TEAM', name: 'Security' }],
    },
    {
      step: 2,
      name: 'Compliance',
      rule: 'AT_LEAST',
      minApprovals: 2,
      onReject: 'QUORUM',
      approvers: [
        { kind: 'USER', name: 'ann' },
        { kind: 'USER', name: 'bob' },
        { kind: 'ROLE', name: 'AUDITOR' },
      ],
    },
  ],
  configurers: [{ kind: 'DATA_CUSTODIAN' }],
};

function row(workflow: AccessWorkflow, overrides: Partial<WorkflowRow> = {}): WorkflowRow {
  return {
    workflow,
    createdBy: 'admin',
    createdAt: new Date(Date.now() - 3600_000).toISOString(),
    updatedBy: null,
    updatedAt: null,
    canEdit: true,
    ...overrides,
  };
}

function listing(overrides: Partial<WorkflowListing> = {}): WorkflowListing {
  return { workflows: [], builtIn: BUILT_IN, canCreateDefault: true, ...overrides };
}

function executions(overrides: Partial<WorkflowExecutions> = {}): WorkflowExecutions {
  return { executions: [], counts: {}, total: 0, ...overrides };
}

function Where() {
  return <p>at: {useParams().id ?? 'list'}</p>;
}

function renderAt(path: string) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route element={<Where />} path="/settings/workflows" />
          <Route element={<WorkflowBuilderPage />} path="/settings/workflows/:id" />
          <Route element={<Where />} path="/requests/:id" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

/** The design select: open it by its name, then pick the option. */
async function choose(selectName: string, option: string) {
  fireEvent.click(screen.getByRole('button', { name: new RegExp(selectName) }));
  fireEvent.click(await screen.findByRole('option', { name: new RegExp(option) }));
}

const inspector = () => screen.getByRole('complementary', { name: 'Inspector' });
const node = (name: RegExp) => within(screen.getByRole('figure')).getByRole('button', { name });

beforeEach(() => {
  jest.clearAllMocks();
  roles = ['PLATFORM_ADMIN'];
  fetchAssets.mockResolvedValue({ items: [], total: 0 });
  fetchPrincipals.mockResolvedValue([]);
  fetchWorkflowExecutions.mockResolvedValue(executions());
});

describe('workflowDiagram', () => {
  it('draws one stage with its two ways out', () => {
    const { nodes, edges } = workflowDiagram(BUILT_IN);
    expect(nodes.map((n) => n.id)).toEqual(['start', 'stage-0', 'configure', 'end', 'rejected']);
    expect(edges).toContainEqual(expect.objectContaining({ from: 'stage-0', to: 'configure', label: 'Approve', tone: 'success' }));
    expect(edges).toContainEqual(expect.objectContaining({ from: 'stage-0', to: 'rejected', label: 'Reject', tone: 'error', route: 'down' }));
  });

  it('meets stages that run together at one decision', () => {
    const { nodes, edges } = workflowDiagram(FINANCE);
    const byId = Object.fromEntries(nodes.map((n) => [n.id, n]));
    expect(byId['stage-1'].column).toBe(byId['stage-2'].column);
    expect(byId['stage-1'].eyebrow).toBe('Step 2 · together');
    expect(byId['join-2'].selectable).toBe(false);
    expect(edges.filter((e) => e.to === 'join-2')).toHaveLength(2);
    expect(edges).toContainEqual(expect.objectContaining({ from: 'join-2', to: 'rejected', label: 'Any rejects' }));
    expect(edges).toContainEqual(expect.objectContaining({ from: 'join-2', to: 'configure', label: 'All approve' }));
    // Both stages of step 2 are reached from step 1, the first edge saying so.
    expect(edges.filter((e) => e.from === 'stage-0' && e.to.startsWith('stage-'))).toHaveLength(2);
  });

  it('says so when any one stage of the step is enough', () => {
    const anyOne = {
      ...FINANCE,
      stages: FINANCE.stages.map((stage) => (stage.step === 2 ? { ...stage, join: 'ANY' as const } : stage)),
    };
    const { nodes, edges } = workflowDiagram(anyOne);
    const byId = Object.fromEntries(nodes.map((n) => [n.id, n]));
    expect(byId['stage-1'].eyebrow).toBe('Step 2 · any one');
    expect(byId['join-2'].title).toBe('Any one of step 2 is enough');
    expect(edges).toContainEqual(expect.objectContaining({ from: 'join-2', to: 'rejected', label: 'All reject' }));
    expect(edges).toContainEqual(expect.objectContaining({ from: 'join-2', to: 'configure', label: 'Any one approves' }));
    // A stage alone in its step has nothing to join, whatever it carries.
    const alone = workflowDiagram({ ...BUILT_IN, stages: [{ ...BUILT_IN.stages[0], join: 'ANY' }] });
    expect(alone.edges).toContainEqual(expect.objectContaining({ from: 'stage-0', label: 'Reject' }));
  });

  it('draws no refusal lane for a workflow without stages', () => {
    const { nodes } = workflowDiagram({ ...BUILT_IN, stages: [] });
    expect(nodes.some((n) => n.id === 'rejected')).toBe(false);
  });
});

describe('WorkflowBuilderPage — designing', () => {
  it('sets a default from the built-in stages, with no scope to choose', async () => {
    fetchWorkflows.mockResolvedValue(listing());
    const created = row({ ...BUILT_IN, id: 'wf-new', name: 'Organisation default' });
    createWorkflow.mockImplementation(async () => {
      fetchWorkflows.mockResolvedValue(listing({ workflows: [created] }));
      return created;
    });
    renderAt('/settings/workflows/new?default=1');

    // A new workflow opens on its settings.
    expect(await screen.findByLabelText('Workflow name')).toHaveValue('Organisation default');
    expect(within(inspector()).getByText(/Leave empty for the organisation’s default/)).toBeInTheDocument();
    expect(screen.getByTestId('steps-preview')).toHaveTextContent('Runs as: Owner approval.');
    // Nothing has run yet, so there is no history to read.
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Create workflow' }));
    await waitFor(() => expect(createWorkflow).toHaveBeenCalledTimes(1));
    expect(createWorkflow.mock.calls[0][0]).toEqual({
      name: 'Organisation default',
      description: null,
      scopeFqn: null,
      enabled: true,
      stages: [
        {
          step: 1,
          name: 'Owner approval',
          rule: 'ANY',
          minApprovals: null,
          onReject: 'VETO',
          approvers: [{ kind: 'ASSET_OWNERS' }],
          join: 'ALL',
        },
      ],
      configurers: [],
    });
    // It opens as the saved workflow, with its history beside it.
    expect(await screen.findByRole('tab', { name: /Execution History/ })).toBeInTheDocument();
    expect(screen.getByText('Organisation default', { selector: 'span' })).toBeInTheDocument();
  });

  it('needs a scope when a default already exists, and builds a two-step workflow', async () => {
    fetchWorkflows.mockResolvedValue(
      listing({ workflows: [row({ ...BUILT_IN, id: 'wf-default', name: 'Organisation default' })], canCreateDefault: false })
    );
    fetchAssets.mockResolvedValue({
      items: [{ id: 'a-1', fqn: 'demo-pg.salesdb.finance', name: 'finance', displayName: null, assetType: 'SCHEMA' }],
      total: 1,
    });
    fetchPrincipals.mockResolvedValue([{ id: 'p-9', username: 'Security', displayName: 'Security team', type: 'GROUP' }]);
    createWorkflow.mockResolvedValue(row(FINANCE));
    renderAt('/settings/workflows/new');

    const name = await screen.findByLabelText('Workflow name');
    const create = screen.getByRole('button', { name: 'Create workflow' });
    expect(screen.getByTestId('workflow-problem')).toHaveTextContent('Name the workflow.');
    expect(create).toBeDisabled();
    fireEvent.change(name, { target: { value: '  Finance tables ' } });
    expect(screen.getByTestId('workflow-problem')).toHaveTextContent('Choose the tables it applies to.');

    fireEvent.change(within(inspector()).getByPlaceholderText(/Search the catalog/), { target: { value: 'fin' } });
    fireEvent.click(await within(inspector()).findByRole('button', { name: /demo-pg\.salesdb\.finance/ }));

    // A new step lands after the last one and opens beside the canvas.
    fireEvent.click(screen.getByRole('button', { name: 'Add a step' }));
    const stage2 = within(inspector()).getByRole('group', { name: 'Stage 2' });
    expect(within(stage2).getByLabelText('Stage 2 step')).toHaveValue('2');
    expect(screen.getByTestId('steps-preview')).toHaveTextContent('Runs as: Owner approval, then Stage 2.');
    fireEvent.change(within(stage2).getByLabelText('Stage 2 name'), { target: { value: 'Security' } });
    // The drawing follows the draft.
    expect(node(/Security/)).toHaveAttribute('aria-pressed', 'true');

    // Everyone in the Security team must approve.
    await choose('Stage 2 rule', 'Everyone asked approves');
    await choose('Stage 2 approver 1 kind', 'A team');
    const teamName = within(stage2).getByLabelText('Stage 2 approver 1 name');
    expect(screen.getByTestId('workflow-problem')).toHaveTextContent(/Stage "Security": name the team/);
    fireEvent.change(teamName, { target: { value: 'Secu' } });
    await waitFor(() => expect(fetchPrincipals).toHaveBeenCalledWith({ type: 'GROUP', search: 'Secu', limit: 8 }));
    fireEvent.change(teamName, { target: { value: 'Security' } });

    // A second approver, by role.
    fireEvent.click(within(stage2).getByRole('button', { name: 'Add approver' }));
    await choose('Stage 2 approver 2 kind', 'An app role');
    await choose('Stage 2 approver 2 role', 'Auditor');

    // The custodian configures it.
    fireEvent.click(node(/Configure access/));
    fireEvent.click(within(inspector()).getByRole('button', { name: 'Add configurer' }));
    await choose('Configurer 1 kind', 'Data custodian');

    expect(create).toBeEnabled();
    fireEvent.click(create);
    await waitFor(() => expect(createWorkflow).toHaveBeenCalledTimes(1));
    const draft = createWorkflow.mock.calls[0][0] as WorkflowDraft;
    expect(draft.name).toBe('Finance tables');
    expect(draft.scopeFqn).toBe('demo-pg.salesdb.finance');
    expect(draft.stages).toEqual([
      {
        step: 1,
        name: 'Owner approval',
        rule: 'ANY',
        minApprovals: null,
        onReject: 'VETO',
        approvers: [{ kind: 'ASSET_OWNERS' }],
        join: 'ALL',
      },
      {
        step: 2,
        name: 'Security',
        rule: 'ALL',
        minApprovals: null,
        onReject: 'VETO',
        approvers: [
          { kind: 'TEAM', name: 'Security' },
          { kind: 'ROLE', name: 'AUDITOR' },
        ],
        join: 'ALL',
      },
    ]);
    expect(draft.configurers).toEqual([{ kind: 'DATA_CUSTODIAN' }]);
  });

  it('adds a stage alongside, a step between, and removes one, keeping steps in order', async () => {
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row(FINANCE)] }));
    renderAt('/settings/workflows/wf-finance');

    fireEvent.click(await screen.findByRole('button', { name: /Owner approval/ }));
    fireEvent.click(within(inspector()).getByRole('button', { name: 'Add a stage alongside' }));
    expect(screen.getByTestId('steps-preview')).toHaveTextContent(
      'Owner approval and Stage 4 together, then Security and Compliance together'
    );
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument();

    fireEvent.click(node(/Owner approval/));
    fireEvent.click(within(inspector()).getByRole('button', { name: 'Add a step after' }));
    expect(screen.getByTestId('steps-preview')).toHaveTextContent(
      'Owner approval and Stage 4 together, then Stage 5, then Security and Compliance together'
    );

    // Removing the only stage of step 2 closes the gap.
    fireEvent.click(within(inspector()).getByRole('button', { name: 'Remove stage' }));
    expect(screen.getByTestId('steps-preview')).toHaveTextContent(
      'Owner approval and Stage 4 together, then Security and Compliance together'
    );
    fireEvent.click(node(/Security/));
    expect(within(inspector()).getByLabelText('Stage 2 step')).toHaveValue('2');

    fireEvent.click(screen.getByRole('button', { name: 'Discard changes' }));
    expect(screen.getByTestId('steps-preview')).toHaveTextContent('Owner approval, then Security and Compliance together');
    expect(screen.queryByText('Unsaved changes')).not.toBeInTheDocument();
  });

  it('lets the stages of one step pass together or on their own, and saves one choice for the step', async () => {
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row(FINANCE)] }));
    updateWorkflow.mockResolvedValue(row(FINANCE));
    renderAt('/settings/workflows/wf-finance');

    // A stage alone in its step has no choice to make.
    fireEvent.click(await screen.findByRole('button', { name: /Owner approval/ }));
    expect(within(inspector()).queryByRole('radio', { name: /Any one is enough/ })).not.toBeInTheDocument();

    fireEvent.click(node(/Security/));
    const steps = within(inspector()).getByRole('group', { name: 'The 2 stages of step 2' });
    expect(within(steps).getByRole('radio', { name: /All must approve/ })).toBeChecked();
    fireEvent.click(within(steps).getByRole('radio', { name: /Any one is enough/ }));
    expect(screen.getByTestId('steps-preview')).toHaveTextContent(
      'Owner approval, then Security or Compliance, whichever passes first'
    );
    // The other stage of the step reads the same choice.
    fireEvent.click(node(/Compliance/));
    expect(within(inspector()).getByRole('radio', { name: /Any one is enough/ })).toBeChecked();

    // A stage added beside them joins them the same way.
    fireEvent.click(within(inspector()).getByRole('button', { name: 'Add a stage alongside' }));
    expect(screen.getByTestId('steps-preview')).toHaveTextContent(
      'Security or Compliance or Stage 4, whichever passes first'
    );

    fireEvent.click(screen.getByRole('button', { name: 'Save workflow' }));
    await waitFor(() => expect(updateWorkflow).toHaveBeenCalledTimes(1));
    const draft = updateWorkflow.mock.calls[0][1] as WorkflowDraft;
    expect(draft.stages.map((stage) => `${stage.name} ${stage.join}`)).toEqual([
      'Owner approval ALL',
      'Security ANY',
      'Compliance ANY',
      'Stage 4 ANY',
    ]);
  });

  it('offers only the answers to a rejection that differ under the rule, and says what each does', async () => {
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row(FINANCE)] }));
    renderAt('/settings/workflows/wf-finance');

    // Everyone must approve: one no always fails it, so there is nothing to pick.
    fireEvent.click(await screen.findByRole('button', { name: /Security/ }));
    const security = within(inspector()).getByRole('group', { name: 'Stage 2' });
    expect(within(security).queryByRole('button', { name: /Stage 2 on reject/ })).not.toBeInTheDocument();
    expect(within(security).getByText('The first no fails the stage')).toBeInTheDocument();
    expect(within(security).getByText(/Everyone has to say yes/)).toBeInTheDocument();

    // At least 2 of them, waiting for the rest: said with its number.
    fireEvent.click(node(/Compliance/));
    const compliance = within(inspector()).getByRole('group', { name: 'Stage 3' });
    expect(
      within(compliance).getByText(/3 people asked, 2 needed: the first no waits, the second no fails the stage/)
    ).toBeInTheDocument();
    await choose('Stage 3 on reject', 'The first no fails the stage');
    expect(within(compliance).getByText(/Even if others already said yes/)).toBeInTheDocument();

    // Any one approval: the first answer decides, or a no waits for the others.
    await choose('Stage 3 rule', 'Any one approves');
    await choose('Stage 3 on reject', 'Fails only if everyone says no');
    expect(within(compliance).getByText(/the third says yes: the stage passes/)).toBeInTheDocument();
  });

  it('asks how many for AT_LEAST, and refuses two stages with one name', async () => {
    fetchWorkflows.mockResolvedValue(listing());
    renderAt('/settings/workflows/new?default=1');
    fireEvent.click(await screen.findByRole('button', { name: /Owner approval/ }));
    const stage1 = within(inspector()).getByRole('group', { name: 'Stage 1' });

    expect(within(stage1).queryByLabelText('Stage 1 approvals needed')).not.toBeInTheDocument();
    await choose('Stage 1 rule', 'At least a number approve');
    const needed = within(stage1).getByLabelText('Stage 1 approvals needed');
    expect(needed).toHaveValue('2');
    fireEvent.change(needed, { target: { value: '' } });
    expect(screen.getByTestId('workflow-problem')).toHaveTextContent(/say how many approvals, at least 1/);
    expect(screen.getByRole('button', { name: 'Create workflow' })).toBeDisabled();
    fireEvent.change(needed, { target: { value: '3x' } });
    expect(needed).toHaveValue('3');
    expect(screen.getByRole('button', { name: 'Create workflow' })).toBeEnabled();

    // The last stage cannot go.
    expect(within(inspector()).queryByRole('button', { name: 'Remove stage' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Add a step' }));
    fireEvent.change(within(inspector()).getByLabelText('Stage 2 name'), { target: { value: 'owner APPROVAL' } });
    expect(screen.getByTestId('workflow-problem')).toHaveTextContent(/appears twice/);
    fireEvent.click(within(inspector()).getByRole('button', { name: 'Remove stage' }));
    expect(screen.queryByTestId('workflow-problem')).not.toBeInTheDocument();
  });

  it('keeps the default’s scope fixed, and shows the server’s answer', async () => {
    const byDefault = { ...BUILT_IN, id: 'wf-default', name: 'Organisation default' };
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row(byDefault)], canCreateDefault: false }));
    updateWorkflow.mockRejectedValueOnce(new Error('Stage "Owner approval": name at least one approver'));
    updateWorkflow.mockResolvedValueOnce(row({ ...byDefault, description: 'Owners decide', enabled: false }));
    renderAt('/settings/workflows/wf-default');

    fireEvent.click(await screen.findByRole('button', { name: /Request submitted/ }));
    expect(within(inspector()).getByText(/the organisation's default/)).toBeInTheDocument();
    expect(within(inspector()).queryByPlaceholderText(/Search the catalog/)).not.toBeInTheDocument();
    const save = screen.getByRole('button', { name: 'Save workflow' });
    expect(save).toBeDisabled();

    fireEvent.change(within(inspector()).getByLabelText('Workflow description'), { target: { value: 'Owners decide' } });
    fireEvent.click(within(inspector()).getByRole('checkbox'));
    fireEvent.click(save);
    expect(await screen.findByRole('alert')).toHaveTextContent('name at least one approver');
    expect(updateWorkflow).toHaveBeenCalledWith(
      'wf-default',
      expect.objectContaining({ scopeFqn: null, description: 'Owners decide', enabled: false })
    );

    fireEvent.click(save);
    await waitFor(() => expect(updateWorkflow).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByText('Unsaved changes')).not.toBeInTheDocument());
    expect(screen.getByText('Off')).toBeInTheDocument();
  });

  it('reads the built-in workflow without a way to change it', async () => {
    fetchWorkflows.mockResolvedValue(listing());
    renderAt('/settings/workflows/built-in');
    expect(await screen.findByText('System')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Save|Create/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add a step' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Set a default' })).toHaveAttribute('href', '/settings/workflows/new?default=1');
    fireEvent.click(node(/Owner approval/));
    expect(within(inspector()).queryByRole('group')).not.toBeInTheDocument();
    expect(inspector()).toHaveTextContent('Owners of the table');
    await waitFor(() => expect(fetchWorkflowExecutions).toHaveBeenCalledWith('built-in', { limit: 25, offset: 0 }));
  });

  it('refuses someone who designs nothing, and a workflow that is gone', async () => {
    roles = ['AUDITOR'];
    fetchWorkflows.mockResolvedValue(listing());
    const { unmount } = renderAt('/settings/workflows/new');
    expect(await screen.findByRole('alert')).toHaveTextContent('Only a platform administrator');
    unmount();
    renderAt('/settings/workflows/wf-gone');
    expect(await screen.findByRole('alert')).toHaveTextContent('No workflow has this id');
  });
});

describe('WorkflowBuilderPage — execution history', () => {
  const pending = {
    ticket: 'AR-2026-0042',
    kind: 'ASSET' as const,
    assetFqn: 'demo-pg.salesdb.finance.invoices',
    status: 'PENDING',
    currentStep: 2,
    openStages: ['Security', 'Compliance'],
    steps: 2,
    createdAt: new Date(Date.now() - 7200_000).toISOString(),
    closedAt: null,
  };
  const done = {
    ...pending,
    ticket: 'AR-2026-0040',
    kind: 'PREAUTHORIZATION' as const,
    status: 'COMPLETED',
    currentStep: null,
    openStages: [],
    closedAt: new Date().toISOString(),
  };

  it('lists the requests by ticket, filters by status and pages', async () => {
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row(FINANCE)] }));
    fetchWorkflowExecutions.mockImplementation((_id: string, { status, offset }: { status?: string; offset: number }) =>
      Promise.resolve(
        executions({
          executions: status === 'COMPLETED' ? [done] : offset > 0 ? [done] : [pending, done],
          counts: { PENDING: 1, COMPLETED: 29 },
          total: status === 'COMPLETED' ? 29 : 30,
        })
      )
    );
    renderAt('/settings/workflows/wf-finance');

    const tab = await screen.findByRole('tab', { name: 'Execution History 30' });
    fireEvent.click(tab);
    const panel = await screen.findByRole('region', { name: 'Execution history' });
    const rows = await within(panel).findAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(within(rows[1]).getByRole('link', { name: 'AR-2026-0042' })).toHaveAttribute('href', '/requests/AR-2026-0042');
    expect(rows[1]).toHaveTextContent('Step 2 of 2 · waiting on Security, Compliance');
    expect(rows[1]).toHaveTextContent('Pending');
    expect(rows[2]).toHaveTextContent('Ahead of need');
    expect(rows[2]).toHaveTextContent('Completed');
    expect(panel).toHaveTextContent('1–25 of 30');

    fireEvent.click(within(panel).getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(fetchWorkflowExecutions).toHaveBeenCalledWith('wf-finance', { status: null, limit: 25, offset: 25 }));

    await waitFor(() => expect(screen.getByText('26–30 of 30')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: /Completed/ }));
    await waitFor(() =>
      expect(fetchWorkflowExecutions).toHaveBeenCalledWith('wf-finance', { status: 'COMPLETED', limit: 25, offset: 0 })
    );
  });

  it('says so when nothing has run', async () => {
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row(FINANCE)] }));
    renderAt('/settings/workflows/wf-finance');
    fireEvent.click(await screen.findByRole('tab', { name: /Execution History/ }));
    expect(await screen.findByText('No request has walked this workflow yet.')).toBeInTheDocument();

  });

  it('shows the server’s refusal', async () => {
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row(FINANCE)] }));
    fetchWorkflowExecutions.mockRejectedValue(new Error('Only whoever governs the scope reads its requests'));
    renderAt('/settings/workflows/wf-finance');
    fireEvent.click(await screen.findByRole('tab', { name: /Execution History/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Only whoever governs the scope');
  });
});
