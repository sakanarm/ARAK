import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import AccessWorkflowsPage, { describeSteps, problemOf } from './AccessWorkflowsPage';
import type {
  AccessWorkflow,
  WorkflowDraft,
  WorkflowListing,
  WorkflowRow,
  WorkflowStage,
} from '../../api/accessWorkflows';

const fetchWorkflows = jest.fn();
const deleteWorkflow = jest.fn();
const fetchWorkflowHistory = jest.fn();
let roles: string[] = ['PLATFORM_ADMIN'];

jest.mock('../../api/accessWorkflows', () => {
  const actual = jest.requireActual('../../api/accessWorkflows');
  return {
    ...actual,
    fetchWorkflows: () => fetchWorkflows(),
    deleteWorkflow: (...args: unknown[]) => deleteWorkflow(...args),
    fetchWorkflowHistory: (...args: unknown[]) => fetchWorkflowHistory(...args),
  };
});

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
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
      <MemoryRouter initialEntries={['/settings/workflows']}>
        <Routes>
          <Route element={<AccessWorkflowsPage />} path="/settings/workflows" />
          <Route element={<BuilderStandIn />} path="/settings/workflows/:id" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function BuilderStandIn() {
  return <p>builder: {useParams().id}</p>;
}

beforeEach(() => {
  jest.clearAllMocks();
  roles = ['PLATFORM_ADMIN'];
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
    expect(within(builtIn).queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
  });

  it('says so when there is no default and nothing scoped', async () => {
    fetchWorkflows.mockResolvedValue(listing());
    renderPage();
    expect(await screen.findByText(/No default is set/)).toBeInTheDocument();
    expect(screen.getByText('No scope has a workflow of its own.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Set a default' })).toBeInTheDocument();
  });

  it('marks a workflow that is off', async () => {
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row({ ...FINANCE, enabled: false })] }));
    renderPage();
    const card = await screen.findByRole('article', { name: 'Workflow Finance tables' });
    expect(within(card).getByText('Off')).toBeInTheDocument();
    expect(within(card).getByText(/the next workflow up applies instead/)).toBeInTheDocument();
  });

  it('hides Delete where the reader may not change it, but keeps the history', async () => {
    roles = ['AUDITOR'];
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row(FINANCE, { canEdit: false })], canCreateDefault: false }));
    renderPage();
    const card = await screen.findByRole('article', { name: 'Workflow Finance tables' });
    expect(within(card).getByRole('link', { name: 'View diagram' })).toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
    expect(within(card).getByRole('button', { name: 'Changes' })).toBeInTheDocument();
    // An auditor designs nothing.
    expect(screen.queryByRole('button', { name: 'New workflow' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Set a default' })).not.toBeInTheDocument();
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
    fireEvent.click(within(card).getByRole('button', { name: 'Changes' }));
    const history = await within(card).findByRole('region', { name: 'History' });
    await waitFor(() => expect(within(history).getAllByRole('listitem')).toHaveLength(2));
    expect(history).toHaveTextContent('owner_a changed it');
    expect(history).toHaveTextContent('admin created it');
    expect(fetchWorkflowHistory).toHaveBeenCalledWith('wf-finance');
    expect(within(card).getByRole('button', { name: 'Hide changes' })).toBeInTheDocument();
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

describe('AccessWorkflowsPage — to the builder', () => {
  it('opens each workflow in the builder, the built-in one read-only', async () => {
    fetchWorkflows.mockResolvedValue(listing({ workflows: [row(FINANCE), row({ ...FINANCE, id: 'wf-ro', name: 'Read only', scopeFqn: 'x.y' }, { canEdit: false })] }));
    renderPage();
    const finance = await screen.findByRole('article', { name: 'Workflow Finance tables' });
    expect(within(finance).getByRole('link', { name: 'Open in builder' })).toHaveAttribute('href', '/settings/workflows/wf-finance');
    const readOnly = screen.getByRole('article', { name: 'Workflow Read only' });
    expect(within(readOnly).getByRole('link', { name: 'View diagram' })).toHaveAttribute('href', '/settings/workflows/wf-ro');
    const builtIn = screen.getByRole('article', { name: 'Workflow Built-in' });
    expect(within(builtIn).getByRole('link', { name: 'View diagram' })).toHaveAttribute('href', '/settings/workflows/built-in');
    expect(screen.getByRole('link', { name: 'Set a default' })).toHaveAttribute('href', '/settings/workflows/new?default=1');
  });

  it('starts a new workflow in the builder', async () => {
    fetchWorkflows.mockResolvedValue(listing());
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'New workflow' }));
    expect(await screen.findByText('builder: new')).toBeInTheDocument();
  });
});

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
