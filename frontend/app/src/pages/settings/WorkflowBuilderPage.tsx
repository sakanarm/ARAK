import { useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { AlertTriangle, ArrowLeft, Plus, Route, Trash01 } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { relativeTime } from '../../components/widgets';
import FlowDiagram from '../../components/diagram/FlowDiagram';
import TabStrip, { panelId, tabId } from '../../components/TabStrip';
import { apiErrorMessage } from '../../api/client';
import { describeOnReject, describeRule, describeSeat, type Seat } from '../../api/accessRequests';
import {
  createWorkflow,
  fetchWorkflowExecutions,
  fetchWorkflows,
  updateWorkflow,
  WORKFLOWS_KEY,
  type AccessWorkflow,
  type WorkflowRow,
  type WorkflowStage,
} from '../../api/accessWorkflows';
import { useAuthStore } from '../../auth/authStore';
import { FIELD, Field } from '../policies/controls';
import { ScopePicker } from './pickers';
import {
  describeSteps,
  draftOf,
  MAX_STAGES,
  newStage,
  problemOf,
  SeatsEditor,
  StageEditor,
} from './workflowParts';
import { CONFIGURE, stageIndexOf, stageNodeId, START, workflowDiagram } from './workflowDiagram';

/**
 * One access workflow, drawn: the builder OpenMetadata's workflow pages taught
 * people to read, for the stages a request walks here.
 *
 * <p>The canvas is the workflow. Choosing a node opens it beside the canvas --
 * the start for the workflow's name and scope, a stage for who it asks and how
 * it passes, the configure node for who sets the access up -- and every change
 * redraws at once, so what is saved is what was seen. Nothing is saved until
 * Save; the server checks the draft again and its refusal is shown as it is.
 *
 * <p>The second tab lists the requests the workflow has run, for whoever may
 * oversee them: by ticket, with the step each one reached, and with nobody's
 * name or address, because designing a route does not make one a reader of
 * everybody who walked it.
 *
 * <p>Routes: {@code /settings/workflows/new} (with {@code ?default=1} for the
 * organisation's default), {@code /settings/workflows/built-in}, and
 * {@code /settings/workflows/<id>}.
 */

type Tab = 'builder' | 'history';

export default function WorkflowBuilderPage() {
  const { id = '' } = useParams();
  const [search] = useSearchParams();
  const mayDesign = useAuthStore((state) => state.hasRole('PLATFORM_ADMIN', 'POLICY_AUTHOR', 'DATA_OWNER'));
  const { data, isLoading, error } = useQuery({ queryKey: WORKFLOWS_KEY, queryFn: fetchWorkflows, retry: false });

  if (error) {
    return (
      <Shell>
        <Alert>{apiErrorMessage(error, 'The workflows could not be loaded.')}</Alert>
      </Shell>
    );
  }
  if (isLoading || !data) {
    return (
      <Shell>
        <p className="tw:text-sm tw:text-tertiary">Loading the workflow…</p>
      </Shell>
    );
  }

  const hasDefault = data.workflows.some((row) => row.workflow.scopeFqn === null);
  if (id === 'new') {
    if (!mayDesign) {
      return (
        <Shell>
          <Alert>Only a platform administrator, a policy author or a data owner designs workflows.</Alert>
        </Shell>
      );
    }
    const asDefault = search.get('default') === '1' && data.canCreateDefault && !hasDefault;
    return (
      <Builder
        canEdit
        initial={{
          ...data.builtIn,
          id: null,
          name: asDefault ? 'Organisation default' : '',
          description: null,
          scopeFqn: null,
          enabled: true,
        }}
        key="new"
        mayBeDefault={data.canCreateDefault && !hasDefault}
      />
    );
  }
  if (id === 'built-in') {
    return <Builder builtIn canEdit={false} initial={data.builtIn} key="built-in" showDefaultLink={data.canCreateDefault && !hasDefault} />;
  }
  const row = data.workflows.find((candidate) => candidate.workflow.id === id);
  if (!row) {
    return (
      <Shell>
        <Alert>No workflow has this id. It may have been deleted.</Alert>
      </Shell>
    );
  }
  return <Builder canEdit={row.canEdit} initial={row.workflow} key={id} row={row} />;
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <div className="tw:flex tw:flex-col tw:gap-4">
      <BackLink />
      {children}
    </div>
  );
}

function BackLink() {
  return (
    <Link
      className="tw:inline-flex tw:w-fit tw:items-center tw:gap-1 tw:text-sm tw:text-tertiary tw:hover:text-primary"
      to="/settings/workflows">
      <ArrowLeft className="tw:size-4" />
      Access workflows
    </Link>
  );
}

function Alert({ children }: { children: React.ReactNode }) {
  return (
    <p
      className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
      role="alert">
      <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
      <span>{children}</span>
    </p>
  );
}

/** Steps renumbered 1, 2, 3 in the order they run, keeping who runs together. */
function compactSteps(stages: WorkflowStage[]): WorkflowStage[] {
  const order = [...new Set(stages.map((stage) => stage.step))].sort((a, b) => a - b);
  return stages.map((stage) => ({ ...stage, step: order.indexOf(stage.step) + 1 }));
}

function freshName(stages: WorkflowStage[]): string {
  const taken = new Set(stages.map((stage) => stage.name.trim().toLowerCase()));
  let n = stages.length + 1;
  while (taken.has(`stage ${n}`)) n++;
  return `Stage ${n}`;
}

function Builder({
  initial,
  row,
  canEdit,
  builtIn = false,
  mayBeDefault = false,
  showDefaultLink = false,
}: {
  initial: AccessWorkflow;
  row?: WorkflowRow;
  canEdit: boolean;
  builtIn?: boolean;
  /** A new workflow whose scope may stay empty, to be the organisation's default. */
  mayBeDefault?: boolean;
  showDefaultLink?: boolean;
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const isNew = !builtIn && initial.id === null;
  const isDefault = !builtIn && !isNew && initial.scopeFqn === null;
  const [saved, setSaved] = useState<AccessWorkflow>(initial);
  const [workflow, setWorkflow] = useState<AccessWorkflow>(initial);
  const [selected, setSelected] = useState<string | null>(isNew ? START : null);
  const [tab, setTab] = useState<Tab>('builder');

  const draft = { ...draftOf(workflow), scopeFqn: isDefault ? null : workflow.scopeFqn };
  const problem = canEdit ? problemOf(draft, !isDefault && !mayBeDefault) : null;
  const dirty = JSON.stringify(draft) !== JSON.stringify({ ...draftOf(saved), scopeFqn: isDefault ? null : saved.scopeFqn });

  const save = useMutation({
    mutationFn: () => (isNew ? createWorkflow(draft) : updateWorkflow(initial.id!, draft)),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: WORKFLOWS_KEY });
      // A route shown before somebody asks comes from the workflow.
      void queryClient.invalidateQueries({ queryKey: ['access-requests'] });
      if (isNew) {
        navigate(result?.workflow?.id ? `/settings/workflows/${result.workflow.id}` : '/settings/workflows', {
          replace: true,
        });
      } else if (result?.workflow) {
        setSaved(result.workflow);
        setWorkflow(result.workflow);
      }
    },
  });

  const set = (next: Partial<AccessWorkflow>) => setWorkflow((current) => ({ ...current, ...next }));
  const setStages = (change: (stages: WorkflowStage[]) => WorkflowStage[]) =>
    setWorkflow((current) => ({ ...current, stages: change(current.stages) }));

  const addStep = (after: number | null) => {
    const index = workflow.stages.length;
    setStages((stages) => {
      const step = after ?? Math.max(0, ...stages.map((stage) => stage.step));
      const shifted = stages.map((stage) => (stage.step > step ? { ...stage, step: stage.step + 1 } : stage));
      return compactSteps([...shifted, newStage(step + 1, freshName(stages))]);
    });
    setSelected(stageNodeId(index));
  };
  const addAlongside = (step: number) => {
    const index = workflow.stages.length;
    setStages((stages) => [...stages, newStage(step, freshName(stages))]);
    setSelected(stageNodeId(index));
  };
  const removeStage = (index: number) => {
    setStages((stages) => compactSteps(stages.filter((_, i) => i !== index)));
    setSelected(null);
  };

  const { nodes, edges } = workflowDiagram(workflow);
  const preview = describeSteps(workflow.stages);
  const historyId = builtIn ? 'built-in' : initial.id;
  const executions = useQuery({
    queryKey: [...WORKFLOWS_KEY, 'executions', historyId, 'ALL', 0],
    queryFn: () => fetchWorkflowExecutions(historyId!, { limit: PAGE, offset: 0 }),
    enabled: historyId !== null,
    retry: false,
  });
  const executionCount = executions.data
    ? Object.values(executions.data.counts).reduce((sum, n) => sum + n, 0)
    : undefined;

  const readOnlyNote = builtIn
    ? 'Built into ARAK; it cannot be changed. An organisation default replaces it.'
    : !canEdit
      ? `You can read this workflow but not change it: that is for whoever governs ${initial.scopeFqn ?? 'the organisation'} or a platform administrator.`
      : null;

  return (
    <div className="tw:flex tw:flex-col tw:gap-4">
      <BackLink />

      <header className="tw:flex tw:flex-wrap tw:items-start tw:gap-4">
        <span className="tw:flex tw:size-11 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-xl tw:bg-utility-brand-50 tw:text-brand-secondary">
          <Route className="tw:size-5" />
        </span>
        <div className="tw:min-w-0 tw:flex-1">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <h1 className="tw:truncate tw:text-xl tw:font-semibold tw:text-primary">
              {workflow.name.trim() || 'New workflow'}
            </h1>
            {builtIn && (
              <Badge color="gray" size="sm" type="pill-color">
                System
              </Badge>
            )}
            {(isDefault || (isNew && mayBeDefault && !workflow.scopeFqn)) && (
              <Badge color="brand" size="sm" type="pill-color">
                Organisation default
              </Badge>
            )}
            {!workflow.enabled && (
              <Badge color="gray" size="sm" type="pill-color">
                Off
              </Badge>
            )}
            {dirty && canEdit && !isNew && (
              <Badge color="warning" size="sm" type="pill-color">
                Unsaved changes
              </Badge>
            )}
          </div>
          <p className="tw:mt-1 tw:text-sm tw:text-tertiary">
            {builtIn ? (
              'Every table without a workflow or a default'
            ) : workflow.scopeFqn ? (
              <>
                Tables under <span className="tw:font-mono tw:text-secondary">{workflow.scopeFqn}</span>
              </>
            ) : isDefault || mayBeDefault ? (
              'Every table without a workflow of its own'
            ) : (
              'Choose the tables it applies to'
            )}
            {preview && <span data-testid="steps-preview"> · Runs as: {preview}.</span>}
          </p>
        </div>
        {canEdit && (
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            {isNew ? (
              <Button color="secondary" onPress={() => navigate('/settings/workflows')} size="sm">
                Cancel
              </Button>
            ) : (
              <Button
                color="secondary"
                isDisabled={!dirty || save.isPending}
                onPress={() => {
                  setWorkflow(saved);
                  setSelected(null);
                  save.reset();
                }}
                size="sm">
                Discard changes
              </Button>
            )}
            <Button
              color="primary"
              isDisabled={!!problem || save.isPending || (!isNew && !dirty)}
              onPress={() => save.mutate()}
              size="sm">
              {save.isPending ? 'Saving…' : isNew ? 'Create workflow' : 'Save workflow'}
            </Button>
          </div>
        )}
      </header>

      {problem && (
        <p className="tw:-mt-2 tw:text-right tw:text-xs tw:text-tertiary" data-testid="workflow-problem">
          {problem}.
        </p>
      )}
      {save.isError && <Alert>{apiErrorMessage(save.error, 'The workflow was not saved.')}</Alert>}
      {readOnlyNote && (
        <p className="tw:rounded-lg tw:bg-secondary tw:px-3 tw:py-2 tw:text-sm tw:text-tertiary">
          {readOnlyNote}
          {builtIn && showDefaultLink && (
            <>
              {' '}
              <Link className="tw:font-semibold tw:text-brand-secondary tw:hover:underline" to="/settings/workflows/new?default=1">
                Set a default
              </Link>
            </>
          )}
        </p>
      )}

      {!isNew && (
        <TabStrip
          idPrefix="workflow"
          label="Workflow views"
          onChange={setTab}
          tabs={[
            { id: 'builder', label: 'Workflow Builder' },
            { id: 'history', label: 'Execution History', count: executionCount },
          ]}
          value={tab}
        />
      )}

      {tab === 'builder' || isNew ? (
        <div
          aria-labelledby={isNew ? undefined : tabId('workflow', 'builder')}
          className="tw:grid tw:items-start tw:gap-4 tw:xl:grid-cols-[minmax(0,1fr)_380px]"
          id={panelId('workflow', 'builder')}
          role={isNew ? undefined : 'tabpanel'}>
          <FlowDiagram
            edges={edges}
            label={`Workflow ${workflow.name.trim() || 'being designed'}`}
            nodes={nodes}
            onSelect={setSelected}
            overlay={
              canEdit ? (
                <Button
                  color="secondary"
                  iconLeading={Plus}
                  isDisabled={workflow.stages.length >= MAX_STAGES}
                  onPress={() => addStep(null)}
                  size="sm">
                  Add a step
                </Button>
              ) : undefined
            }
            selected={selected}
          />
          <Inspector
            addAlongside={addAlongside}
            addStep={addStep}
            canEdit={canEdit}
            isDefault={isDefault || builtIn}
            mayBeDefault={mayBeDefault}
            removeStage={removeStage}
            selected={selected}
            set={set}
            setStages={setStages}
            workflow={workflow}
          />
        </div>
      ) : (
        <div aria-labelledby={tabId('workflow', 'history')} id={panelId('workflow', 'history')} role="tabpanel">
          <ExecutionHistory id={historyId!} />
        </div>
      )}

      {row && (
        <p className="tw:text-xs tw:text-quaternary">
          {row.updatedAt && row.updatedBy
            ? `Changed by ${row.updatedBy} ${relativeTime(row.updatedAt)}`
            : `Created by ${row.createdBy} ${relativeTime(row.createdAt)}`}
        </p>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- inspector

function Inspector({
  workflow,
  selected,
  canEdit,
  isDefault,
  mayBeDefault,
  set,
  setStages,
  addStep,
  addAlongside,
  removeStage,
}: {
  workflow: AccessWorkflow;
  selected: string | null;
  canEdit: boolean;
  isDefault: boolean;
  mayBeDefault: boolean;
  set: (next: Partial<AccessWorkflow>) => void;
  setStages: (change: (stages: WorkflowStage[]) => WorkflowStage[]) => void;
  addStep: (after: number | null) => void;
  addAlongside: (step: number) => void;
  removeStage: (index: number) => void;
}) {
  const index = stageIndexOf(selected);
  const stage = index === null ? undefined : workflow.stages[index];

  let heading = 'Choose a node';
  let body: React.ReactNode = (
    <div className="tw:flex tw:flex-col tw:gap-3 tw:text-sm tw:text-tertiary">
      <p>
        {canEdit
          ? 'Click the start to name the workflow and choose its tables, a stage to change who it asks, or Configure access for who sets it up.'
          : 'Click a node to read it in full.'}
      </p>
      <ul className="tw:flex tw:flex-col tw:gap-1.5 tw:text-xs">
        <li className="tw:flex tw:items-center tw:gap-2">
          <span className="tw:h-0.5 tw:w-5 tw:rounded-full tw:bg-fg-success-primary" /> Goes on when approved
        </li>
        <li className="tw:flex tw:items-center tw:gap-2">
          <span className="tw:h-0.5 tw:w-5 tw:rounded-full tw:bg-fg-error-primary" /> Stops the request when refused
        </li>
        <li>Stages in one column are asked at the same time.</li>
      </ul>
    </div>
  );

  if (selected === START) {
    heading = 'Workflow settings';
    body = canEdit ? (
      <div className="tw:flex tw:flex-col tw:gap-4">
        <Field label="Name">
          <input
            aria-label="Workflow name"
            className={FIELD}
            onChange={(event) => set({ name: event.target.value })}
            value={workflow.name}
          />
        </Field>
        <div className="tw:flex tw:flex-col tw:gap-1.5">
          <span className="tw:text-sm tw:font-medium tw:text-secondary">Applies to</span>
          {isDefault ? (
            <p className="tw:text-sm tw:text-tertiary">
              Every table without a workflow of its own (the organisation's default).
            </p>
          ) : (
            <>
              <ScopePicker onChange={(scopeFqn) => set({ scopeFqn })} value={workflow.scopeFqn} />
              <span className="tw:text-xs tw:text-tertiary">
                {mayBeDefault
                  ? 'Leave empty for the organisation’s default. A scope covers everything under it.'
                  : 'A service, database, schema or table you govern; it covers everything under it.'}
              </span>
            </>
          )}
        </div>
        <Field label="Description">
          <textarea
            aria-label="Workflow description"
            className={`${FIELD} tw:min-h-20 tw:resize-y`}
            onChange={(event) => set({ description: event.target.value })}
            placeholder="When this workflow is the right one, for whoever reads the list"
            value={workflow.description ?? ''}
          />
        </Field>
        <label className="tw:flex tw:items-start tw:gap-2 tw:text-sm tw:text-secondary">
          <input
            checked={workflow.enabled}
            className="tw:mt-0.5"
            onChange={(event) => set({ enabled: event.target.checked })}
            type="checkbox"
          />
          <span>On — while off, requests follow the next workflow up</span>
        </label>
      </div>
    ) : (
      <dl className="tw:flex tw:flex-col tw:gap-3 tw:text-sm">
        <Fact label="Name">{workflow.name}</Fact>
        <Fact label="Applies to">
          {workflow.scopeFqn ? <span className="tw:font-mono">{workflow.scopeFqn}</span> : 'Every table without a workflow of its own'}
        </Fact>
        {workflow.description && <Fact label="Description">{workflow.description}</Fact>}
        <Fact label="State">{workflow.enabled ? 'On' : 'Off — requests follow the next workflow up'}</Fact>
      </dl>
    );
  } else if (stage && index !== null) {
    heading = `Stage · ${stage.name.trim() || 'unnamed'}`;
    body = canEdit ? (
      <div className="tw:flex tw:flex-col tw:gap-3">
        <StageEditor
          index={index}
          onChange={(next) =>
            setStages((stages) => stages.map((current, i) => (i === index ? { ...current, ...next } : current)))
          }
          stage={stage}
        />
        <div className="tw:flex tw:flex-wrap tw:gap-2">
          <Button
            color="secondary"
            iconLeading={Plus}
            isDisabled={workflow.stages.length >= MAX_STAGES}
            onPress={() => addAlongside(stage.step)}
            size="sm">
            Add a stage alongside
          </Button>
          <Button
            color="secondary"
            iconLeading={Plus}
            isDisabled={workflow.stages.length >= MAX_STAGES}
            onPress={() => addStep(stage.step)}
            size="sm">
            Add a step after
          </Button>
          {workflow.stages.length > 1 && (
            <Button color="secondary-destructive" iconLeading={Trash01} onPress={() => removeStage(index)} size="sm">
              Remove stage
            </Button>
          )}
        </div>
      </div>
    ) : (
      <dl className="tw:flex tw:flex-col tw:gap-3 tw:text-sm">
        <Fact label="Step">{stage.step}</Fact>
        <Fact label="Passes when">{describeRule(stage.rule, stage.minApprovals ?? null)}</Fact>
        <Fact label="A rejection">{describeOnReject(stage.onReject)}</Fact>
        <Fact label="Asks">{seats(stage.approvers)}</Fact>
      </dl>
    );
  } else if (selected === CONFIGURE) {
    heading = 'Configure access';
    body = (
      <div className="tw:flex tw:flex-col tw:gap-3">
        <p className="tw:text-sm tw:text-tertiary">
          Who sets up the access once the stages approve it: a grant, or a change to a policy they then name.
          Empty means the owners of the table or its data custodian.
        </p>
        {canEdit ? (
          <SeatsEditor label="Configurer" onChange={(configurers) => set({ configurers })} seats={workflow.configurers} />
        ) : (
          <p className="tw:text-sm tw:text-primary">
            {workflow.configurers.length === 0 ? 'The owners of the table or its data custodian' : seats(workflow.configurers)}
          </p>
        )}
      </div>
    );
  }

  return (
    <aside
      aria-label="Inspector"
      className="tw:flex tw:flex-col tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
      <h2 className="tw:border-b tw:border-secondary tw:px-4 tw:py-3 tw:text-sm tw:font-semibold tw:text-primary">
        {heading}
      </h2>
      <div className="tw:px-4 tw:py-4">{body}</div>
    </aside>
  );
}

function seats(list: Seat[]): string {
  return list.map(describeSeat).join(', ');
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="tw:text-xs tw:font-medium tw:text-tertiary">{label}</dt>
      <dd className="tw:mt-0.5 tw:text-primary">{children}</dd>
    </div>
  );
}

// ---------------------------------------------------------------- history

const PAGE = 25;

type Colour = 'warning' | 'success' | 'error' | 'gray' | 'brand' | 'blue';

const STATUS: Record<string, { label: string; colour: Colour }> = {
  PENDING: { label: 'Pending', colour: 'warning' },
  APPROVED: { label: 'Approved', colour: 'brand' },
  IN_PROGRESS: { label: 'Configuring', colour: 'blue' },
  COMPLETED: { label: 'Completed', colour: 'success' },
  REJECTED: { label: 'Rejected', colour: 'error' },
  WITHDRAWN: { label: 'Withdrawn', colour: 'gray' },
};

const ORDER = ['PENDING', 'APPROVED', 'IN_PROGRESS', 'COMPLETED', 'REJECTED', 'WITHDRAWN'];

function ExecutionHistory({ id }: { id: string }) {
  const [status, setStatus] = useState<string | null>(null);
  const [offset, setOffset] = useState(0);
  const { data, isLoading, error } = useQuery({
    queryKey: [...WORKFLOWS_KEY, 'executions', id, status ?? 'ALL', offset],
    queryFn: () => fetchWorkflowExecutions(id, { status, limit: PAGE, offset }),
    retry: false,
  });

  if (error) return <Alert>{apiErrorMessage(error, 'The requests could not be loaded.')}</Alert>;
  if (isLoading || !data) return <p className="tw:text-sm tw:text-tertiary">Loading the requests…</p>;

  const all = Object.values(data.counts).reduce((sum, n) => sum + n, 0);
  const filters = [
    { value: null as string | null, label: 'All', count: all },
    ...ORDER.filter((value) => data.counts[value]).map((value) => ({
      value: value as string | null,
      label: STATUS[value].label,
      count: data.counts[value],
    })),
  ];

  return (
    <section aria-label="Execution history" className="tw:flex tw:flex-col tw:gap-3">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2" role="group" aria-label="Filter by status">
        {filters.map((filter) => {
          const active = filter.value === status;
          return (
            <button
              aria-pressed={active}
              className={`tw:flex tw:cursor-pointer tw:items-center tw:gap-1.5 tw:rounded-full tw:border tw:px-3 tw:py-1 tw:text-sm tw:font-medium ${
                active
                  ? 'tw:border-brand tw:bg-utility-brand-50 tw:text-brand-secondary'
                  : 'tw:border-secondary tw:bg-primary tw:text-secondary tw:hover:bg-secondary'
              }`}
              key={filter.label}
              onClick={() => {
                setStatus(filter.value);
                setOffset(0);
              }}
              type="button">
              {filter.label}
              <span className="tw:text-xs tw:tabular-nums tw:text-tertiary">{filter.count}</span>
            </button>
          );
        })}
      </div>

      {data.executions.length === 0 ? (
        <p className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-4 tw:py-6 tw:text-center tw:text-sm tw:text-tertiary">
          {all === 0 ? 'No request has walked this workflow yet.' : 'No request with this status.'}
        </p>
      ) : (
        <div className="tw:overflow-x-auto tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
          <table className="tw:w-full tw:text-left tw:text-sm">
            <thead className="tw:bg-secondary tw:text-xs tw:font-medium tw:text-tertiary">
              <tr>
                <th className="tw:px-4 tw:py-2.5" scope="col">Ticket</th>
                <th className="tw:px-4 tw:py-2.5" scope="col">Table</th>
                <th className="tw:px-4 tw:py-2.5" scope="col">Status</th>
                <th className="tw:px-4 tw:py-2.5" scope="col">Where it is</th>
                <th className="tw:px-4 tw:py-2.5" scope="col">Asked</th>
                <th className="tw:px-4 tw:py-2.5" scope="col">Closed</th>
              </tr>
            </thead>
            <tbody className="tw:divide-y tw:divide-secondary">
              {data.executions.map((execution) => {
                const look = STATUS[execution.status] ?? { label: execution.status, colour: 'gray' as Colour };
                return (
                  <tr key={execution.ticket}>
                    <td className="tw:px-4 tw:py-3 tw:whitespace-nowrap">
                      <Link
                        className="tw:font-mono tw:font-medium tw:text-brand-secondary tw:hover:underline"
                        to={`/requests/${encodeURIComponent(execution.ticket)}`}>
                        {execution.ticket}
                      </Link>
                    </td>
                    <td className="tw:max-w-80 tw:px-4 tw:py-3">
                      {execution.kind === 'PREAUTHORIZATION' && (
                        <span className="tw:mr-2 tw:text-xs tw:text-tertiary">Ahead of need</span>
                      )}
                      <span className="tw:block tw:truncate tw:font-mono tw:text-xs tw:text-secondary" title={execution.assetFqn ?? ''}>
                        {execution.assetFqn ?? '—'}
                      </span>
                    </td>
                    <td className="tw:px-4 tw:py-3">
                      <Badge color={look.colour} size="sm" type="pill-color">
                        {look.label}
                      </Badge>
                    </td>
                    <td className="tw:px-4 tw:py-3 tw:text-secondary">{whereItIs(execution)}</td>
                    <td className="tw:px-4 tw:py-3 tw:whitespace-nowrap tw:text-tertiary" title={execution.createdAt}>
                      {relativeTime(execution.createdAt)}
                    </td>
                    <td className="tw:px-4 tw:py-3 tw:whitespace-nowrap tw:text-tertiary" title={execution.closedAt ?? ''}>
                      {execution.closedAt ? relativeTime(execution.closedAt) : '—'}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {data.total > PAGE && (
        <div className="tw:flex tw:items-center tw:justify-between tw:gap-3 tw:text-sm tw:text-tertiary">
          <span>
            {offset + 1}–{Math.min(offset + PAGE, data.total)} of {data.total}
          </span>
          <div className="tw:flex tw:gap-2">
            <Button color="secondary" isDisabled={offset === 0} onPress={() => setOffset(Math.max(0, offset - PAGE))} size="sm">
              Previous
            </Button>
            <Button
              color="secondary"
              isDisabled={offset + PAGE >= data.total}
              onPress={() => setOffset(offset + PAGE)}
              size="sm">
              Next
            </Button>
          </div>
        </div>
      )}
    </section>
  );
}

function whereItIs(execution: {
  status: string;
  currentStep: number | null;
  steps: number;
  openStages: string[];
}): string {
  if (execution.status === 'PENDING') {
    const step = execution.currentStep && execution.steps > 1 ? `Step ${execution.currentStep} of ${execution.steps}` : null;
    const waiting = execution.openStages.length > 0 ? `waiting on ${execution.openStages.join(', ')}` : null;
    return [step, waiting].filter(Boolean).join(' · ') || 'Waiting';
  }
  if (execution.status === 'APPROVED' || execution.status === 'IN_PROGRESS') return 'Being configured';
  return '—';
}
