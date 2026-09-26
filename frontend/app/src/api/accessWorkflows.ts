import { apiClient } from './client';
import type { OnReject, Seat, StageRule } from './accessRequests';

/**
 * Access workflows: which stages a request for a table walks, and who
 * configures it once approved.
 *
 * One workflow per scope. The most specific scope covering a table wins; the
 * organisation's default (no scope) covers the rest, and the built-in one --
 * any one owner approves -- applies when there is no default either. The
 * server decides who may change which: the default is for platform
 * administrators, a scope for whoever governs it. `canEdit` draws the
 * buttons and nothing more.
 */

export interface WorkflowStage {
  /** Stages sharing a step run side by side; steps run one after another. */
  step: number;
  name: string;
  rule: StageRule;
  /** For AT_LEAST, and only then. */
  minApprovals?: number | null;
  onReject: OnReject;
  approvers: Seat[];
}

export interface AccessWorkflow {
  /** Null for the built-in workflow. */
  id: string | null;
  name: string;
  description: string | null;
  /** Null for the organisation's default. */
  scopeFqn: string | null;
  enabled: boolean;
  stages: WorkflowStage[];
  /** Who configures an approved request; empty means the owners and the data custodian. */
  configurers: Seat[];
}

export interface WorkflowRow {
  workflow: AccessWorkflow;
  createdBy: string;
  createdAt: string;
  updatedBy: string | null;
  updatedAt: string | null;
  canEdit: boolean;
}

export interface WorkflowListing {
  workflows: WorkflowRow[];
  builtIn: AccessWorkflow;
  canCreateDefault: boolean;
}

export interface WorkflowDraft {
  name: string;
  description?: string | null;
  scopeFqn?: string | null;
  enabled?: boolean;
  stages: WorkflowStage[];
  configurers?: Seat[];
}

export interface WorkflowChange {
  id: number;
  occurredAt: string;
  actor: string;
  action: string;
  scopeFqn: string | null;
}

export const WORKFLOWS_KEY = ['access-workflows'] as const;

export async function fetchWorkflows(): Promise<WorkflowListing> {
  const { data } = await apiClient.get<WorkflowListing>('/v1/access-workflows');
  return data;
}

/** The workflow a request on this table would walk. Open to anyone signed in. */
export async function fetchEffectiveWorkflow(assetFqn: string): Promise<AccessWorkflow> {
  const { data } = await apiClient.get<AccessWorkflow>(
    `/v1/access-workflows/effective/${encodeURIComponent(assetFqn)}`
  );
  return data;
}

export async function createWorkflow(draft: WorkflowDraft): Promise<WorkflowRow> {
  const { data } = await apiClient.post<WorkflowRow>('/v1/access-workflows', draft);
  return data;
}

export async function updateWorkflow(id: string, draft: WorkflowDraft): Promise<WorkflowRow> {
  const { data } = await apiClient.put<WorkflowRow>(
    `/v1/access-workflows/${encodeURIComponent(id)}`,
    draft
  );
  return data;
}

export async function deleteWorkflow(id: string): Promise<void> {
  await apiClient.delete(`/v1/access-workflows/${encodeURIComponent(id)}`);
}

export async function fetchWorkflowHistory(id: string): Promise<WorkflowChange[]> {
  const { data } = await apiClient.get<WorkflowChange[]>(
    `/v1/access-workflows/${encodeURIComponent(id)}/history`
  );
  return data;
}

/** One request a workflow ran: its ticket, never its id, and nobody's name. */
export interface WorkflowExecution {
  ticket: string;
  kind: 'ASSET' | 'PREAUTHORIZATION';
  assetFqn: string | null;
  status: string;
  /** The step it has reached; null once it is decided. */
  currentStep: number | null;
  /** The stages waiting on an answer now. */
  openStages: string[];
  /** How many steps it was asked with. */
  steps: number;
  createdAt: string;
  closedAt: string | null;
}

export interface WorkflowExecutions {
  executions: WorkflowExecution[];
  /** Requests by status, over every page. */
  counts: Record<string, number>;
  /** How many match the chosen status, over every page. */
  total: number;
}

/**
 * The requests a workflow has run, newest first. Only an administrator, an
 * auditor or whoever governs the workflow's scope may list them.
 */
export async function fetchWorkflowExecutions(
  id: string | 'built-in',
  params: { status?: string | null; limit?: number; offset?: number } = {}
): Promise<WorkflowExecutions> {
  const { data } = await apiClient.get<WorkflowExecutions>(
    `/v1/access-workflows/${id === 'built-in' ? 'built-in' : encodeURIComponent(id)}/requests`,
    {
      params: {
        ...(params.status ? { status: params.status } : {}),
        limit: params.limit ?? 50,
        offset: params.offset ?? 0,
      },
    }
  );
  return data;
}

/** Stages grouped by step, in order: what runs together, then what follows. */
export function stepsOf<T extends { step: number }>(stages: T[]): T[][] {
  const byStep = new Map<number, T[]>();
  for (const stage of stages) {
    const list = byStep.get(stage.step) ?? [];
    list.push(stage);
    byStep.set(stage.step, list);
  }
  return [...byStep.entries()].sort((a, b) => a[0] - b[0]).map(([, list]) => list);
}
