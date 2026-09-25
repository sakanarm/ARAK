import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import AccessWorkflowsPage, { describeSteps, problemOf } from './AccessWorkflowsPage';
import type {
  AccessWorkflow,
  WorkflowDraft,
  WorkflowListing,
  WorkflowRow,
  WorkflowStage,
} from '../../api/accessWorkflows';

const fetchWorkflows = jest.fn();
const createWorkflow = jest.fn();
const updateWorkflow = jest.fn();
const deleteWorkflow = jest.fn();
const fetchWorkflowHistory = jest.fn();
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
    deleteWorkflow: (...args: unknown[]) => deleteWorkflow(...args),
    fetchWorkflowHistory: (...args: unknown[]) => fetchWorkflowHistory(...args),
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

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <AccessWorkflowsPage />
    </QueryClientProvider>
  );
}

/** The design select: open it by its name, then pick the option. */
async function choose(selectName: string, option: string) {
  fireEvent.click(screen.getByRole('button', { name: new RegExp(selectName) }));
  fireEvent.click(await screen.findByRole('option', { name: new RegExp(option) }));
}

beforeEach(() => {
  jest.clearAllMocks();
  roles = ['PLATFORM_ADMIN'];
  fetchAssets.mockResolvedValue({ items: [], total: 0 });
  fetchPrincipals.mockResolvedValue([]);
});

describe('AccessWorkflowsPage — reading', () => {
  it('lists the default, the scoped workflows, and ends with the built-in one', async () => {
    fetchWorkflows.mockResolvedValue(
      listing({
        workflows: [
          row(FINANCE),
          row({ ...BUILT_IN, id: 'wf-default', name: 'Organisation default' }, { updatedBy: 'owner_a', updatedAt: new Date().toISOString() }),
        ],
        canCreateDefault: false,
      })
    );
    renderPage();

    const byDefault = await screen.findByRole('region', { name: 'Organisation default' });
    expect(within(byDefault).getByRole('article', { name: 'Workflow Organisation default' })).toBeInTheDocument();
    expect(within(byDefault).getByText(/Changed by owner_a/)).toBeInTheDocument();

    const scoped = screen.getByRole('region', { name: 'Scoped workflows' });
    const finance = within(scoped).getByRole('article', { name: 'Workflow Finance tables' });
    expect(within(finance).getByText('demo-pg.salesdb.finance')).toBeInTheDocument();
    expect(within(finance).getByText('Money needs two more pairs of eyes')).toBeInTheDocument();
    // Two steps: the owners, then Security and Compliance side by side.
    expect(within(finance).getAllByRole('listitem')).toHaveLength(2);
    expect(within(finance).getByText('Asks Team Security')).toBeInTheDocument();
    expect(within(finance).getByText('Asks ann, bob, Role Auditor')).toBeInTheDocument();
    expect(within(finance).getByText(/Configured by/)).toHaveTextContent('Data custodian');

    const last = screen.getByRole('region', { name: 'When nothing else applies' });
    const builtIn = within(last).getByRole('article', { name: 'Workflow Built-in' });
    // Its name and the badge.
    expect(within(builtIn).getAllByText('Built-in')).toHaveLength(2);
    expect(within(builtIn).getByText(/the owners of the table or its data custodian/)).toBeInTheDocument();
    expect(within(builtIn).queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
  });

  it('says so when there is no default and nothing scoped', async () => {
    fetchWorkflows.mockResolvedValue(listing());
    renderPage();
    expect(await screen.findByText(/No default is set/)).toBeInTheDocument();
    expect(screen.getByText('No scope has a workflow of its own.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Set a default' })).toBeInTheDocument();
  });

  it('marks a workflow that is off', async () => {
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row({ ...FINANCE, enabled: false })] }));
    renderPage();
    const card = await screen.findByRole('article', { name: 'Workflow Finance tables' });
    expect(within(card).getByText('Off')).toBeInTheDocument();
    expect(within(card).getByText(/the next workflow up applies instead/)).toBeInTheDocument();
  });

  it('hides Edit and Delete where the reader may not change it, but keeps the history', async () => {
    roles = ['AUDITOR'];
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row(FINANCE, { canEdit: false })], canCreateDefault: false }));
    renderPage();
    const card = await screen.findByRole('article', { name: 'Workflow Finance tables' });
    expect(within(card).queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
    expect(within(card).getByRole('button', { name: 'History' })).toBeInTheDocument();
    // An auditor designs nothing.
    expect(screen.queryByRole('button', { name: 'New workflow' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Set a default' })).not.toBeInTheDocument();
  });

  it('shows the server’s refusal when the list cannot be read', async () => {
    fetchWorkflows.mockRejectedValue(new Error('Access workflows are for administrators, data owners and auditors'));
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Access workflows are for administrators');
  });

  it('opens the history of a workflow', async () => {
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row(FINANCE)] }));
    fetchWorkflowHistory.mockResolvedValue([
      { id: 2, occurredAt: new Date().toISOString(), actor: 'owner_a', action: 'UPDATE', scopeFqn: FINANCE.scopeFqn },
      { id: 1, occurredAt: new Date().toISOString(), actor: 'admin', action: 'CREATE', scopeFqn: FINANCE.scopeFqn },
    ]);
    renderPage();
    const card = await screen.findByRole('article', { name: 'Workflow Finance tables' });
    fireEvent.click(within(card).getByRole('button', { name: 'History' }));
    const history = await within(card).findByRole('region', { name: 'History' });
    await waitFor(() => expect(within(history).getAllByRole('listitem')).toHaveLength(2));
    expect(history).toHaveTextContent('owner_a changed it');
    expect(history).toHaveTextContent('admin created it');
    expect(fetchWorkflowHistory).toHaveBeenCalledWith('wf-finance');
    expect(within(card).getByRole('button', { name: 'Hide history' })).toBeInTheDocument();
  });
});

describe('AccessWorkflowsPage — deleting', () => {
  it('asks first, keeps it on "Keep it", deletes on confirm', async () => {
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row(FINANCE)] }));
    deleteWorkflow.mockResolvedValue(undefined);
    renderPage();
    const card = await screen.findByRole('article', { name: 'Workflow Finance tables' });

    fireEvent.click(within(card).getByRole('button', { name: 'Delete' }));
    expect(within(card).getByRole('alertdialog', { name: 'Delete the workflow' })).toHaveTextContent(
      'Requests already asked keep the stages'
    );
    fireEvent.click(within(card).getByRole('button', { name: 'Keep it' }));
    expect(within(card).queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(deleteWorkflow).not.toHaveBeenCalled();

    fireEvent.click(within(card).getByRole('button', { name: 'Delete' }));
    fireEvent.click(within(card).getByRole('button', { name: 'Delete workflow' }));
    await waitFor(() => expect(deleteWorkflow).toHaveBeenCalledWith('wf-finance'));
    await waitFor(() => expect(fetchWorkflows).toHaveBeenCalledTimes(2));
  });

  it('says why the server refused to delete', async () => {
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row(FINANCE)] }));
    deleteWorkflow.mockRejectedValue(new Error('You do not govern demo-pg.salesdb.finance'));
    renderPage();
    const card = await screen.findByRole('article', { name: 'Workflow Finance tables' });
    fireEvent.click(within(card).getByRole('button', { name: 'Delete' }));
    fireEvent.click(within(card).getByRole('button', { name: 'Delete workflow' }));
    expect(await within(card).findByRole('alert')).toHaveTextContent('You do not govern');
  });
});

describe('AccessWorkflowsPage — designing', () => {
  it('sets a default from the built-in stages, with no scope to choose', async () => {
    fetchWorkflows.mockResolvedValue(listing());
    createWorkflow.mockResolvedValue(row({ ...BUILT_IN, id: 'wf-new', name: 'Organisation default' }));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Set a default' }));

    const form = screen.getByRole('form', { name: 'New workflow' });
    expect(within(form).getByLabelText('Workflow name')).toHaveValue('Organisation default');
    expect(within(form).getByText(/Leave empty for the organisation’s default/)).toBeInTheDocument();
    expect(within(form).getByTestId('steps-preview')).toHaveTextContent('Runs as: Owner approval.');
    // Nothing else to design while one is open.
    expect(screen.queryByRole('button', { name: 'New workflow' })).not.toBeInTheDocument();

    fireEvent.click(within(form).getByRole('button', { name: 'Create workflow' }));
    await waitFor(() => expect(createWorkflow).toHaveBeenCalledTimes(1));
    expect(createWorkflow.mock.calls[0][0]).toEqual({
      name: 'Organisation default',
      description: null,
      scopeFqn: null,
      enabled: true,
      stages: [
        { step: 1, name: 'Owner approval', rule: 'ANY', minApprovals: null, onReject: 'VETO', approvers: [{ kind: 'ASSET_OWNERS' }] },
      ],
      configurers: [],
    });
    await waitFor(() => expect(screen.queryByRole('form')).not.toBeInTheDocument());
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
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'New workflow' }));
    const form = screen.getByRole('form', { name: 'New workflow' });
    const create = within(form).getByRole('button', { name: 'Create workflow' });

    expect(within(form).getByText('Name the workflow.')).toBeInTheDocument();
    expect(create).toBeDisabled();
    fireEvent.change(within(form).getByLabelText('Workflow name'), { target: { value: '  Finance tables ' } });
    expect(within(form).getByText('Choose the tables it applies to.')).toBeInTheDocument();

    fireEvent.change(within(form).getByPlaceholderText(/Search the catalog/), { target: { value: 'fin' } });
    fireEvent.click(await within(form).findByRole('button', { name: /demo-pg\.salesdb\.finance/ }));

    // A second stage lands on the next step; put it beside the first instead, then back.
    fireEvent.click(within(form).getByRole('button', { name: 'Add stage' }));
    const stage2 = within(form).getByRole('group', { name: 'Stage 2' });
    expect(within(stage2).getByLabelText('Stage 2 step')).toHaveValue('2');
    expect(within(form).getByTestId('steps-preview')).toHaveTextContent('Runs as: Owner approval, then Stage 2.');
    fireEvent.change(within(stage2).getByLabelText('Stage 2 name'), { target: { value: 'Security' } });
    fireEvent.change(within(stage2).getByLabelText('Stage 2 step'), { target: { value: '1' } });
    expect(within(form).getByTestId('steps-preview')).toHaveTextContent('Runs as: Owner approval and Security together.');
    fireEvent.change(within(stage2).getByLabelText('Stage 2 step'), { target: { value: '2' } });

    // Everyone in the Security team must approve.
    await choose('Stage 2 rule', 'Everyone asked approves');
    await choose('Stage 2 approver 1 kind', 'A team');
    const teamName = within(stage2).getByLabelText('Stage 2 approver 1 name');
    expect(within(form).getByText(/Stage "Security": name the team/)).toBeInTheDocument();
    fireEvent.change(teamName, { target: { value: 'Secu' } });
    await waitFor(() =>
      expect(fetchPrincipals).toHaveBeenCalledWith({ type: 'GROUP', search: 'Secu', limit: 8 })
    );
    fireEvent.change(teamName, { target: { value: 'Security' } });

    // A second approver, by role.
    fireEvent.click(within(stage2).getByRole('button', { name: 'Add approver' }));
    await choose('Stage 2 approver 2 kind', 'An app role');
    await choose('Stage 2 approver 2 role', 'Auditor');

    // The custodian configures it.
    fireEvent.click(within(form).getByRole('button', { name: 'Add configurer' }));
    await choose('Configurer 1 kind', 'Data custodian');

    expect(create).toBeEnabled();
    fireEvent.click(create);
    await waitFor(() => expect(createWorkflow).toHaveBeenCalledTimes(1));
    const draft = createWorkflow.mock.calls[0][0] as WorkflowDraft;
    expect(draft.name).toBe('Finance tables');
    expect(draft.scopeFqn).toBe('demo-pg.salesdb.finance');
    expect(draft.stages).toEqual([
      { step: 1, name: 'Owner approval', rule: 'ANY', minApprovals: null, onReject: 'VETO', approvers: [{ kind: 'ASSET_OWNERS' }] },
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
      },
    ]);
    expect(draft.configurers).toEqual([{ kind: 'DATA_CUSTODIAN' }]);
  });

  it('asks how many for AT_LEAST, and a first response only decides an ANY stage', async () => {
    fetchWorkflows.mockResolvedValue(listing());
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Set a default' }));
    const stage1 = screen.getByRole('group', { name: 'Stage 1' });

    await choose('Stage 1 on reject', describeOnRejectLabel('FIRST_RESPONSE'));
    expect(screen.getByRole('button', { name: /Stage 1 on reject/ })).toHaveTextContent(
      describeOnRejectLabel('FIRST_RESPONSE')
    );

    expect(within(stage1).queryByLabelText('Stage 1 approvals needed')).not.toBeInTheDocument();
    await choose('Stage 1 rule', 'At least a number approve');
    const needed = within(stage1).getByLabelText('Stage 1 approvals needed');
    expect(needed).toHaveValue('2');
    // The first answer cannot decide a stage that needs two.
    expect(screen.getByRole('button', { name: /Stage 1 on reject/ })).toHaveTextContent(describeOnRejectLabel('VETO'));
    fireEvent.click(screen.getByRole('button', { name: /Stage 1 on reject/ }));
    expect(await screen.findByRole('option', { name: new RegExp(describeOnRejectLabel('FIRST_RESPONSE')) })).toHaveAttribute(
      'aria-disabled',
      'true'
    );
    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' });

    fireEvent.change(needed, { target: { value: '' } });
    expect(screen.getByText(/say how many approvals, at least 1/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create workflow' })).toBeDisabled();
    fireEvent.change(needed, { target: { value: '3x' } });
    expect(needed).toHaveValue('3');
    expect(screen.getByRole('button', { name: 'Create workflow' })).toBeEnabled();
  });

  it('refuses two stages with one name, and removes a stage', async () => {
    fetchWorkflows.mockResolvedValue(listing());
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Set a default' }));
    const form = screen.getByRole('form', { name: 'New workflow' });
    expect(within(form).queryByRole('button', { name: 'Remove stage' })).not.toBeInTheDocument();
    fireEvent.click(within(form).getByRole('button', { name: 'Add stage' }));
    fireEvent.change(within(form).getByLabelText('Stage 2 name'), { target: { value: 'owner APPROVAL' } });
    expect(within(form).getByText(/appears twice/)).toBeInTheDocument();
    fireEvent.click(within(within(form).getByRole('group', { name: 'Stage 2' })).getByRole('button', { name: 'Remove stage' }));
    expect(within(form).queryByRole('group', { name: 'Stage 2' })).not.toBeInTheDocument();
    expect(within(form).queryByText(/appears twice/)).not.toBeInTheDocument();
  });

  it('edits in place, keeps the default’s scope fixed, and shows the server’s answer', async () => {
    const byDefault = { ...BUILT_IN, id: 'wf-default', name: 'Organisation default' };
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row(byDefault), row(FINANCE)], canCreateDefault: false }));
    updateWorkflow.mockRejectedValueOnce(new Error('Stage "Owner approval": name at least one approver'));
    updateWorkflow.mockResolvedValueOnce(row(byDefault));
    renderPage();

    const card = await screen.findByRole('article', { name: 'Workflow Organisation default' });
    fireEvent.click(within(card).getByRole('button', { name: 'Edit' }));
    const form = screen.getByRole('form', { name: 'Edit Organisation default' });
    expect(within(form).getByText(/the organisation's default/)).toBeInTheDocument();
    expect(within(form).queryByPlaceholderText(/Search the catalog/)).not.toBeInTheDocument();
    // The other card cannot be opened while one is being edited.
    const finance = screen.getByRole('article', { name: 'Workflow Finance tables' });
    expect(within(finance).queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();

    fireEvent.change(within(form).getByLabelText('Workflow description'), { target: { value: 'Owners decide' } });
    fireEvent.click(within(form).getByRole('checkbox'));
    fireEvent.click(within(form).getByRole('button', { name: 'Save workflow' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('name at least one approver');
    expect(updateWorkflow).toHaveBeenCalledWith('wf-default', expect.objectContaining({
      scopeFqn: null,
      description: 'Owners decide',
      enabled: false,
    }));

    fireEvent.click(within(form).getByRole('button', { name: 'Save workflow' }));
    await waitFor(() => expect(screen.queryByRole('form')).not.toBeInTheDocument());
    expect(updateWorkflow).toHaveBeenCalledTimes(2);
  });

  it('cancel drops the draft', async () => {
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row(FINANCE)] }));
    renderPage();
    const card = await screen.findByRole('article', { name: 'Workflow Finance tables' });
    fireEvent.click(within(card).getByRole('button', { name: 'Edit' }));
    const form = screen.getByRole('form', { name: 'Edit Finance tables' });
    expect(within(form).getByText('demo-pg.salesdb.finance')).toBeInTheDocument();
    expect(within(form).getByLabelText('Stage 3 approvals needed')).toHaveValue('2');
    expect(within(form).getByLabelText('Stage 3 approver 1 name')).toHaveValue('ann');
    fireEvent.click(within(form).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('form')).not.toBeInTheDocument();
    expect(updateWorkflow).not.toHaveBeenCalled();
  });
});

function describeOnRejectLabel(value: 'VETO' | 'QUORUM' | 'FIRST_RESPONSE'): string {
  const { describeOnReject } = jest.requireActual('../../api/accessRequests');
  return describeOnReject(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

describe('problemOf', () => {
  const draft = (overrides: Partial<WorkflowDraft> = {}): WorkflowDraft => ({
    name: 'Finance',
    scopeFqn: 'demo-pg.salesdb.finance',
    stages: [OWNERS_STAGE],
    configurers: [],
    ...overrides,
  });

  it('passes a good draft', () => {
    expect(problemOf(draft(), true)).toBeNull();
    expect(problemOf(draft({ scopeFqn: null }), false)).toBeNull();
  });

  it('names the first thing wrong', () => {
    expect(problemOf(draft({ name: ' ' }), true)).toBe('Name the workflow');
    expect(problemOf(draft({ scopeFqn: null }), true)).toBe('Choose the tables it applies to');
    expect(problemOf(draft({ stages: [] }), true)).toBe('A workflow needs at least one stage');
    expect(problemOf(draft({ stages: [{ ...OWNERS_STAGE, name: '' }] }), true)).toBe('Name every stage');
    expect(problemOf(draft({ stages: [OWNERS_STAGE, { ...OWNERS_STAGE, step: 2 }] }), true)).toMatch(/appears twice/);
    expect(problemOf(draft({ stages: [{ ...OWNERS_STAGE, step: 0 }] }), true)).toMatch(/steps count from 1/);
    expect(problemOf(draft({ stages: [{ ...OWNERS_STAGE, approvers: [] }] }), true)).toMatch(/at least one approver/);
    expect(problemOf(draft({ stages: [{ ...OWNERS_STAGE, approvers: [{ kind: 'USER', name: ' ' }] }] }), true)).toMatch(/name the person/);
    expect(problemOf(draft({ stages: [{ ...OWNERS_STAGE, approvers: [{ kind: 'ROLE', name: '' }] }] }), true)).toMatch(/name the role/);
    expect(problemOf(draft({ stages: [{ ...OWNERS_STAGE, rule: 'AT_LEAST', minApprovals: 0 }] }), true)).toMatch(/how many approvals/);
    expect(problemOf(draft({ configurers: [{ kind: 'TEAM', name: '' }] }), true)).toMatch(/Configured by/);
  });
});

describe('describeSteps', () => {
  it('reads steps in order, parallel stages together', () => {
    expect(describeSteps(FINANCE.stages)).toBe('Owner approval, then Security and Compliance together');
    expect(describeSteps([{ ...OWNERS_STAGE, step: 3 }, { ...OWNERS_STAGE, name: 'First', step: 1 }])).toBe(
      'First, then Owner approval'
    );
    expect(describeSteps([{ ...OWNERS_STAGE, name: '  ' }])).toBe('');
  });
});
