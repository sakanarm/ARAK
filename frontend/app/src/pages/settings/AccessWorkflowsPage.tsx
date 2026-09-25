import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { AlertTriangle, Edit03, Plus, Trash01, XClose } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { relativeTime } from '../../components/widgets';
import { apiErrorMessage } from '../../api/client';
import { fetchPrincipals } from '../../api/governance';
import {
  describeOnReject,
  describeRule,
  describeSeat,
  type OnReject,
  type Seat,
  type SeatKind,
  type StageRule,
} from '../../api/accessRequests';
import {
  createWorkflow,
  deleteWorkflow,
  fetchWorkflowHistory,
  fetchWorkflows,
  stepsOf,
  updateWorkflow,
  WORKFLOWS_KEY,
  type AccessWorkflow,
  type WorkflowDraft,
  type WorkflowRow,
  type WorkflowStage,
} from '../../api/accessWorkflows';
import { useAuthStore } from '../../auth/authStore';
import { FIELD, Field, Select, type SelectOption } from '../policies/controls';
import { ScopePicker } from './pickers';

/**
 * Access workflows (M9 slice 2a): who approves a request, in what order, and
 * who then configures it.
 *
 * <p>One workflow per scope. The deepest enabled scope covering a table wins,
 * the organisation's default covers the rest, and the built-in one -- any one
 * owner approves -- applies when there is no default either, so the page
 * always ends with it: somebody reading the list should never have to guess
 * what happens to a table no workflow names.
 *
 * <p>The editor writes stages as the server keeps them: a step number and a
 * rule each. Stages that share a step are asked at the same time; the preview
 * under the stages says how they will run, in the words the requester and the
 * approvers will later read, because a number typed into a box is easy to get
 * wrong and a sentence is not. The server checks the draft again and its
 * answer is shown as it is; nothing here is the last word on who may save.
 */

const RULES: SelectOption[] = [
  { value: 'ANY', label: 'Any one approves' },
  { value: 'ALL', label: 'Everyone asked approves' },
  { value: 'AT_LEAST', label: 'At least a number approve' },
];

const ON_REJECT: OnReject[] = ['VETO', 'QUORUM', 'FIRST_RESPONSE'];

const SEAT_KINDS: SelectOption[] = [
  { value: 'ASSET_OWNERS', label: 'Owners of the table', hint: 'As OpenMetadata records them' },
  { value: 'DATA_STEWARD', label: 'Data steward', hint: "The table's dataSteward property" },
  { value: 'DATA_CUSTODIAN', label: 'Data custodian', hint: "The table's dataCustodian property" },
  { value: 'USER', label: 'A person', hint: 'By username or email' },
  { value: 'TEAM', label: 'A team', hint: 'Everyone in it, nested teams too' },
  { value: 'ROLE', label: 'An app role', hint: 'Whoever holds it over the table' },
];

/** Not REQUESTER: everybody holds it, and a stage anybody may pass is no stage. */
const ROLES: SelectOption[] = [
  { value: 'DATA_OWNER', label: 'Data owner' },
  { value: 'POLICY_AUTHOR', label: 'Policy author' },
  { value: 'AUDITOR', label: 'Auditor' },
  { value: 'PLATFORM_ADMIN', label: 'Platform administrator' },
];

const NAMED: ReadonlySet<SeatKind> = new Set<SeatKind>(['USER', 'TEAM', 'ROLE']);

const MAX_STAGES = 12;
const MAX_SEATS = 25;

/** What is being edited: a new workflow (no id) or an existing one. */
interface Editing {
  id: string | null;
  initial: AccessWorkflow;
}

export default function AccessWorkflowsPage() {
  const mayDesign = useAuthStore((state) =>
    state.hasRole('PLATFORM_ADMIN', 'POLICY_AUTHOR', 'DATA_OWNER')
  );
  const { data, isLoading, error } = useQuery({
    queryKey: WORKFLOWS_KEY,
    queryFn: fetchWorkflows,
    retry: false,
  });
  const [editing, setEditing] = useState<Editing | null>(null);

  const rows = [...(data?.workflows ?? [])].sort((a, b) =>
    (a.workflow.scopeFqn ?? '').localeCompare(b.workflow.scopeFqn ?? '')
  );
  const byDefault = rows.find((row) => row.workflow.scopeFqn === null) ?? null;
  const scoped = rows.filter((row) => row.workflow.scopeFqn !== null);
  const canCreateDefault = data?.canCreateDefault ?? false;

  const startNew = (from: AccessWorkflow, name: string) =>
    setEditing({ id: null, initial: { ...from, id: null, name, scopeFqn: null, enabled: true } });

  return (
    <div className="tw:flex tw:flex-col tw:gap-5">
      <header className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-4">
        <div>
          <h1 className="tw:text-xl tw:font-semibold tw:text-primary">Access workflows</h1>
          <p className="tw:mt-1 tw:max-w-3xl tw:text-sm tw:text-tertiary">
            Who approves a request for a table, in what order, and who configures the access once
            it is approved. The deepest workflow covering a table applies; stages in one step are
            asked at the same time, steps one after another. A request keeps the stages it was
            asked with, so a change here reaches only requests made after it.
          </p>
        </div>
        {data && mayDesign && !editing && (
          <Button
            color="primary"
            iconLeading={Plus}
            onPress={() => startNew(data.builtIn, '')}
            size="sm">
            New workflow
          </Button>
        )}
      </header>

      {error && (
        <p
          className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
          role="alert">
          <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
          {apiErrorMessage(error, 'The workflows could not be loaded.')}
        </p>
      )}
      {isLoading && <p className="tw:text-sm tw:text-tertiary">Loading workflows…</p>}

      {editing && editing.id === null && data && (
        <WorkflowEditor
          canCreateDefault={canCreateDefault && !byDefault}
          editing={editing}
          onDone={() => setEditing(null)}
        />
      )}

      {data && (
        <>
          <Section
            blurb="Every table that no workflow below covers."
            heading="Organisation default">
            {byDefault ? (
              <RowOrEditor editing={editing} onEdit={setEditing} row={byDefault} />
            ) : (
              <p className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-4 tw:py-3 tw:text-sm tw:text-tertiary">
                No default is set, so the built-in workflow below applies to every table without a
                workflow of its own.
                {canCreateDefault && !editing && (
                  <>
                    {' '}
                    <button
                      className="tw:cursor-pointer tw:font-semibold tw:text-brand-secondary tw:hover:underline"
                      onClick={() => startNew(data.builtIn, 'Organisation default')}
                      type="button">
                      Set a default
                    </button>
                  </>
                )}
              </p>
            )}
          </Section>

          <Section
            blurb="A workflow on a service, database, schema or table, for everything under it."
            heading="Scoped workflows">
            {scoped.length === 0 ? (
              <p className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-4 tw:py-3 tw:text-sm tw:text-tertiary">
                No scope has a workflow of its own.
              </p>
            ) : (
              scoped.map((row) => (
                <RowOrEditor
                  editing={editing}
                  key={row.workflow.id}
                  onEdit={setEditing}
                  row={row}
                />
              ))
            )}
          </Section>

          <Section
            blurb="Built into ARAK; it cannot be changed. A default replaces it."
            heading="When nothing else applies">
            <WorkflowCard builtIn workflow={data.builtIn} />
          </Section>
        </>
      )}
    </div>
  );
}

function Section({
  heading,
  blurb,
  children,
}: {
  heading: string;
  blurb: string;
  children: React.ReactNode;
}) {
  return (
    <section aria-label={heading} className="tw:flex tw:flex-col tw:gap-3">
      <div>
        <h2 className="tw:text-md tw:font-semibold tw:text-primary">{heading}</h2>
        <p className="tw:text-sm tw:text-tertiary">{blurb}</p>
      </div>
      {children}
    </section>
  );
}

function RowOrEditor({
  row,
  editing,
  onEdit,
}: {
  row: WorkflowRow;
  editing: Editing | null;
  onEdit: (editing: Editing | null) => void;
}) {
  if (editing && editing.id === row.workflow.id) {
    return <WorkflowEditor canCreateDefault={false} editing={editing} onDone={() => onEdit(null)} />;
  }
  return (
    <WorkflowCard
      onEdit={editing ? undefined : () => onEdit({ id: row.workflow.id, initial: row.workflow })}
      row={row}
      workflow={row.workflow}
    />
  );
}

// ------------------------------------------------------------------ reading

function WorkflowCard({
  workflow,
  row,
  builtIn = false,
  onEdit,
}: {
  workflow: AccessWorkflow;
  row?: WorkflowRow;
  builtIn?: boolean;
  onEdit?: () => void;
}) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [history, setHistory] = useState(false);
  const remove = useMutation({
    mutationFn: () => deleteWorkflow(workflow.id!),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: WORKFLOWS_KEY });
      void queryClient.invalidateQueries({ queryKey: ['access-requests'] });
    },
  });

  return (
    <article
      aria-label={`Workflow ${workflow.name}`}
      className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
      <div className="tw:flex tw:flex-wrap tw:items-start tw:gap-3 tw:px-4 tw:py-3">
        <div className="tw:min-w-0 tw:flex-1">
          <p className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <span className="tw:text-sm tw:font-semibold tw:text-primary">{workflow.name}</span>
            {builtIn && (
              <Badge color="gray" size="sm" type="pill-color">
                Built-in
              </Badge>
            )}
            {!workflow.enabled && (
              <Badge color="gray" size="sm" type="pill-color">
                Off
              </Badge>
            )}
          </p>
          <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">
            {builtIn ? (
              'Every table without a workflow or a default'
            ) : workflow.scopeFqn ? (
              <>
                Tables under <span className="tw:font-mono">{workflow.scopeFqn}</span>
              </>
            ) : (
              'Every table without a workflow of its own'
            )}
            {!workflow.enabled && ' · off, so the next workflow up applies instead'}
          </p>
          {workflow.description && (
            <p className="tw:mt-1 tw:text-sm tw:text-secondary">{workflow.description}</p>
          )}
        </div>
        {row && (
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <Button color="secondary" onPress={() => setHistory((open) => !open)} size="sm">
              {history ? 'Hide history' : 'History'}
            </Button>
            {row.canEdit && onEdit && (
              <>
                <Button color="secondary" iconLeading={Edit03} onPress={onEdit} size="sm">
                  Edit
                </Button>
                <Button
                  color="secondary-destructive"
                  iconLeading={Trash01}
                  onPress={() => setConfirming(true)}
                  size="sm">
                  Delete
                </Button>
              </>
            )}
          </div>
        )}
      </div>

      <div className="tw:border-t tw:border-secondary tw:px-4 tw:py-3">
        <StepsView stages={workflow.stages} />
        <p className="tw:mt-3 tw:text-xs tw:text-tertiary">
          Configured by{' '}
          <span className="tw:text-secondary">
            {workflow.configurers.length === 0
              ? 'the owners of the table or its data custodian'
              : workflow.configurers.map(describeSeat).join(', ')}
          </span>
        </p>
      </div>

      {confirming && (
        <div
          className="tw:flex tw:flex-wrap tw:items-center tw:gap-3 tw:border-t tw:border-secondary tw:bg-utility-error-50 tw:px-4 tw:py-3"
          role="alertdialog"
          aria-label="Delete the workflow">
          <p className="tw:min-w-0 tw:flex-1 tw:text-sm tw:text-secondary">
            Delete “{workflow.name}”? Requests already asked keep the stages they were asked with;
            new ones follow the next workflow up.
          </p>
          <Button color="secondary" onPress={() => setConfirming(false)} size="sm">
            Keep it
          </Button>
          <Button
            color="secondary-destructive"
            isDisabled={remove.isPending}
            onPress={() => remove.mutate()}
            size="sm">
            Delete workflow
          </Button>
          {remove.isError && (
            <p className="tw:w-full tw:text-sm tw:text-error-primary" role="alert">
              {apiErrorMessage(remove.error, 'The workflow was not deleted.')}
            </p>
          )}
        </div>
      )}

      {row && history && <History id={workflow.id!} />}

      {row && (
        <p className="tw:border-t tw:border-secondary tw:px-4 tw:py-2 tw:text-xs tw:text-quaternary">
          {row.updatedAt && row.updatedBy
            ? `Changed by ${row.updatedBy} ${relativeTime(row.updatedAt)}`
            : `Created by ${row.createdBy} ${relativeTime(row.createdAt)}`}
        </p>
      )}
    </article>
  );
}

/** The stages, step by step: what is asked together, then what follows. */
function StepsView({ stages }: { stages: WorkflowStage[] }) {
  return (
    <ol className="tw:flex tw:flex-col tw:gap-2">
      {stepsOf(stages).map((group, index) => (
        <li className="tw:flex tw:items-start tw:gap-2.5" key={group[0].step}>
          <span
            aria-hidden
            className="tw:flex tw:size-5 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-full tw:bg-utility-brand-50 tw:text-xs tw:font-semibold tw:text-brand-secondary">
            {index + 1}
          </span>
          <span className="tw:flex tw:min-w-0 tw:flex-1 tw:flex-wrap tw:gap-2">
            {group.map((stage) => (
              <span
                className="tw:min-w-48 tw:flex-1 tw:rounded-lg tw:border tw:border-secondary tw:px-3 tw:py-2"
                key={stage.name}>
                <span className="tw:block tw:text-sm tw:font-medium tw:text-primary">{stage.name}</span>
                <span className="tw:block tw:text-xs tw:text-tertiary">
                  {describeRule(stage.rule, stage.minApprovals)} · {describeOnReject(stage.onReject)}
                </span>
                <span className="tw:block tw:text-xs tw:text-secondary">
                  Asks {stage.approvers.map(describeSeat).join(', ')}
                </span>
              </span>
            ))}
          </span>
        </li>
      ))}
    </ol>
  );
}

const ACTIONS: Record<string, string> = {
  CREATE: 'created it',
  UPDATE: 'changed it',
  DELETE: 'deleted it',
};

function History({ id }: { id: string }) {
  const { data, isLoading, error } = useQuery({
    queryKey: [...WORKFLOWS_KEY, 'history', id],
    queryFn: () => fetchWorkflowHistory(id),
  });
  return (
    <div aria-label="History" className="tw:border-t tw:border-secondary tw:px-4 tw:py-3" role="region">
      {isLoading && <p className="tw:text-sm tw:text-tertiary">Loading…</p>}
      {error && (
        <p className="tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'The history could not be loaded.')}
        </p>
      )}
      {data && data.length === 0 && <p className="tw:text-sm tw:text-tertiary">No changes recorded.</p>}
      {data && data.length > 0 && (
        <ul className="tw:flex tw:flex-col tw:gap-1">
          {data.map((change) => (
            <li className="tw:text-sm tw:text-secondary" key={change.id}>
              <span className="tw:font-medium tw:text-primary">{change.actor}</span>{' '}
              {ACTIONS[change.action] ?? change.action.toLowerCase()}
              {change.scopeFqn && (
                <>
                  {' '}
                  on <span className="tw:font-mono tw:text-xs">{change.scopeFqn}</span>
                </>
              )}{' '}
              <span className="tw:text-xs tw:text-tertiary">{relativeTime(change.occurredAt)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ------------------------------------------------------------------ editing

/** The first thing the server would refuse, said the same way, or null. */
export function problemOf(draft: WorkflowDraft, scopeRequired: boolean): string | null {
  if (!draft.name.trim()) return 'Name the workflow';
  if (scopeRequired && !draft.scopeFqn) return 'Choose the tables it applies to';
  if (draft.stages.length === 0) return 'A workflow needs at least one stage';
  const names = new Set<string>();
  for (const stage of draft.stages) {
    const label = stage.name.trim();
    if (!label) return 'Name every stage';
    const where = `Stage "${label}"`;
    if (names.has(label.toLowerCase())) return `${where} appears twice; give each stage its own name`;
    names.add(label.toLowerCase());
    if (!Number.isInteger(stage.step) || stage.step < 1) return `${where}: steps count from 1`;
    if (stage.approvers.length === 0) return `${where}: name at least one approver`;
    const unnamed = stage.approvers.find((seat) => NAMED.has(seat.kind) && !seat.name?.trim());
    if (unnamed) return `${where}: name the ${unnamed.kind === 'TEAM' ? 'team' : unnamed.kind === 'ROLE' ? 'role' : 'person'}`;
    if (stage.rule === 'AT_LEAST' && !(stage.minApprovals && stage.minApprovals >= 1)) {
      return `${where}: say how many approvals, at least 1`;
    }
  }
  const unnamed = (draft.configurers ?? []).find((seat) => NAMED.has(seat.kind) && !seat.name?.trim());
  if (unnamed) return 'Configured by: name every person, team and role';
  return null;
}

/** "Owner approval, then Security and Compliance together": how the stages will run. */
export function describeSteps(stages: WorkflowStage[]): string {
  const named = stages.filter((stage) => stage.name.trim());
  if (named.length === 0) return '';
  return stepsOf(named)
    .map((group) =>
      group.length === 1
        ? group[0].name.trim()
        : `${group.map((stage) => stage.name.trim()).join(' and ')} together`
    )
    .join(', then ');
}

function cleanSeats(seats: Seat[]): Seat[] {
  return seats.map((seat) =>
    NAMED.has(seat.kind) ? { kind: seat.kind, name: seat.name?.trim() ?? '' } : { kind: seat.kind }
  );
}

function WorkflowEditor({
  editing,
  canCreateDefault,
  onDone,
}: {
  editing: Editing;
  /** A new workflow may be the default: its scope may stay empty. */
  canCreateDefault: boolean;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const isNew = editing.id === null;
  const isDefault = !isNew && editing.initial.scopeFqn === null;
  const [name, setName] = useState(editing.initial.name);
  const [description, setDescription] = useState(editing.initial.description ?? '');
  const [scopeFqn, setScopeFqn] = useState<string | null>(editing.initial.scopeFqn || null);
  const [enabled, setEnabled] = useState(editing.initial.enabled);
  const [stages, setStages] = useState<WorkflowStage[]>(editing.initial.stages.map((stage) => ({ ...stage })));
  const [configurers, setConfigurers] = useState<Seat[]>(editing.initial.configurers);

  const draft: WorkflowDraft = {
    name: name.trim(),
    description: description.trim() || null,
    scopeFqn: isDefault ? null : scopeFqn,
    enabled,
    stages: stages.map((stage) => ({
      step: stage.step,
      name: stage.name.trim(),
      rule: stage.rule,
      minApprovals: stage.rule === 'AT_LEAST' ? (stage.minApprovals ?? null) : null,
      onReject: stage.onReject,
      approvers: cleanSeats(stage.approvers),
    })),
    configurers: cleanSeats(configurers),
  };
  const problem = problemOf(draft, !isDefault && !(isNew && canCreateDefault));

  const save = useMutation({
    mutationFn: () => (isNew ? createWorkflow(draft) : updateWorkflow(editing.id!, draft)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: WORKFLOWS_KEY });
      // A route shown before somebody asks comes from the workflow.
      void queryClient.invalidateQueries({ queryKey: ['access-requests'] });
      onDone();
    },
  });

  const change = (index: number, next: Partial<WorkflowStage>) =>
    setStages((current) => current.map((stage, i) => (i === index ? { ...stage, ...next } : stage)));

  const addStage = () =>
    setStages((current) => [
      ...current,
      {
        step: Math.max(0, ...current.map((stage) => stage.step)) + 1,
        name: `Stage ${current.length + 1}`,
        rule: 'ANY',
        minApprovals: null,
        onReject: 'VETO',
        approvers: [{ kind: 'ASSET_OWNERS' }],
      },
    ]);

  const preview = describeSteps(stages);

  return (
    <form
      aria-label={isNew ? 'New workflow' : `Edit ${editing.initial.name}`}
      className="tw:rounded-xl tw:border tw:border-brand tw:bg-primary tw:shadow-md"
      onSubmit={(event) => {
        event.preventDefault();
        if (!problem && !save.isPending) save.mutate();
      }}>
      <div className="tw:border-b tw:border-secondary tw:px-4 tw:py-3">
        <p className="tw:text-sm tw:font-semibold tw:text-primary">
          {isNew ? 'New workflow' : `Edit ${editing.initial.name}`}
        </p>
      </div>

      <div className="tw:flex tw:flex-col tw:gap-4 tw:px-4 tw:py-4">
        <div className="tw:grid tw:gap-4 tw:md:grid-cols-2">
          <Field label="Name">
            <input
              aria-label="Workflow name"
              className={FIELD}
              onChange={(event) => setName(event.target.value)}
              value={name}
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
                <ScopePicker onChange={setScopeFqn} value={scopeFqn} />
                <span className="tw:text-xs tw:text-tertiary">
                  {isNew && canCreateDefault
                    ? 'Leave empty for the organisation’s default. A scope covers everything under it.'
                    : 'A service, database, schema or table you govern; it covers everything under it.'}
                </span>
              </>
            )}
          </div>
        </div>
        <Field label="Description">
          <textarea
            aria-label="Workflow description"
            className={`${FIELD} tw:min-h-16 tw:resize-y`}
            onChange={(event) => setDescription(event.target.value)}
            placeholder="When this workflow is the right one, for whoever reads the list"
            value={description}
          />
        </Field>
        <label className="tw:flex tw:items-center tw:gap-2 tw:text-sm tw:text-secondary">
          <input checked={enabled} onChange={(event) => setEnabled(event.target.checked)} type="checkbox" />
          On — while off, requests follow the next workflow up
        </label>

        <fieldset className="tw:flex tw:flex-col tw:gap-3">
          <legend className="tw:text-sm tw:font-medium tw:text-secondary">Stages</legend>
          {stages.map((stage, index) => (
            <StageEditor
              index={index}
              key={index}
              onChange={(next) => change(index, next)}
              onRemove={
                stages.length > 1 ? () => setStages((current) => current.filter((_, i) => i !== index)) : undefined
              }
              stage={stage}
            />
          ))}
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-3">
            <Button
              color="secondary"
              iconLeading={Plus}
              isDisabled={stages.length >= MAX_STAGES}
              onPress={addStage}
              size="sm">
              Add stage
            </Button>
            {preview && (
              <p className="tw:text-sm tw:text-tertiary" data-testid="steps-preview">
                Runs as: {preview}.
              </p>
            )}
          </div>
        </fieldset>

        <fieldset className="tw:flex tw:flex-col tw:gap-2">
          <legend className="tw:text-sm tw:font-medium tw:text-secondary">Configured by</legend>
          <p className="tw:text-xs tw:text-tertiary">
            Who sets up the access once the stages approve it: a grant, or a change to a policy they
            then name. Empty means the owners of the table or its data custodian.
          </p>
          <SeatsEditor label="Configurer" onChange={setConfigurers} seats={configurers} />
        </fieldset>

        {save.isError && (
          <p
            className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
            role="alert">
            <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
            <span>{apiErrorMessage(save.error, 'The workflow was not saved.')}</span>
          </p>
        )}
      </div>

      <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-end tw:gap-2 tw:border-t tw:border-secondary tw:px-4 tw:py-3">
        {problem && <span className="tw:mr-auto tw:text-xs tw:text-tertiary">{problem}.</span>}
        <Button color="secondary" onPress={onDone} size="sm">
          Cancel
        </Button>
        <Button color="primary" isDisabled={!!problem || save.isPending} size="sm" type="submit">
          {save.isPending ? 'Saving…' : isNew ? 'Create workflow' : 'Save workflow'}
        </Button>
      </div>
    </form>
  );
}

function StageEditor({
  stage,
  index,
  onChange,
  onRemove,
}: {
  stage: WorkflowStage;
  index: number;
  onChange: (next: Partial<WorkflowStage>) => void;
  onRemove?: () => void;
}) {
  const n = index + 1;
  const onRejectOptions: SelectOption[] = ON_REJECT.map((value) => ({
    value,
    label: describeOnReject(value),
    isDisabled: value === 'FIRST_RESPONSE' && stage.rule !== 'ANY',
    hint: value === 'FIRST_RESPONSE' && stage.rule !== 'ANY' ? 'Only when any one approval passes it' : undefined,
  }));
  return (
    <div
      aria-label={`Stage ${n}`}
      className="tw:flex tw:flex-col tw:gap-3 tw:rounded-lg tw:border tw:border-secondary tw:px-3 tw:py-3"
      role="group">
      <div className="tw:flex tw:flex-wrap tw:items-end tw:gap-3">
        <label className="tw:flex tw:w-20 tw:flex-col tw:gap-1.5">
          <span className="tw:text-xs tw:font-medium tw:text-secondary">Step</span>
          <input
            aria-label={`Stage ${n} step`}
            className={FIELD}
            inputMode="numeric"
            onChange={(event) => {
              const digits = event.target.value.replace(/[^0-9]/g, '');
              onChange({ step: digits === '' ? 0 : Number.parseInt(digits, 10) });
            }}
            value={stage.step === 0 ? '' : String(stage.step)}
          />
        </label>
        <label className="tw:flex tw:min-w-48 tw:flex-1 tw:flex-col tw:gap-1.5">
          <span className="tw:text-xs tw:font-medium tw:text-secondary">Stage name</span>
          <input
            aria-label={`Stage ${n} name`}
            className={FIELD}
            onChange={(event) => onChange({ name: event.target.value })}
            value={stage.name}
          />
        </label>
        {onRemove && (
          <Button color="secondary" iconLeading={Trash01} onPress={onRemove} size="sm">
            Remove stage
          </Button>
        )}
      </div>
      <div className="tw:flex tw:flex-wrap tw:items-end tw:gap-3">
        <div className="tw:flex tw:min-w-56 tw:flex-col tw:gap-1.5">
          <span className="tw:text-xs tw:font-medium tw:text-secondary">Passes when</span>
          <Select
            ariaLabel={`Stage ${n} rule`}
            onChange={(value) => {
              const rule = value as StageRule;
              onChange({
                rule,
                minApprovals: rule === 'AT_LEAST' ? (stage.minApprovals ?? 2) : null,
                // The first answer cannot decide a stage that needs more than one.
                onReject: rule !== 'ANY' && stage.onReject === 'FIRST_RESPONSE' ? 'VETO' : stage.onReject,
              });
            }}
            options={RULES}
            value={stage.rule}
          />
        </div>
        {stage.rule === 'AT_LEAST' && (
          <label className="tw:flex tw:w-24 tw:flex-col tw:gap-1.5">
            <span className="tw:text-xs tw:font-medium tw:text-secondary">How many</span>
            <input
              aria-label={`Stage ${n} approvals needed`}
              className={FIELD}
              inputMode="numeric"
              onChange={(event) => {
                const digits = event.target.value.replace(/[^0-9]/g, '');
                onChange({ minApprovals: digits === '' ? null : Number.parseInt(digits, 10) });
              }}
              value={stage.minApprovals == null ? '' : String(stage.minApprovals)}
            />
          </label>
        )}
        <div className="tw:flex tw:min-w-72 tw:flex-1 tw:flex-col tw:gap-1.5">
          <span className="tw:text-xs tw:font-medium tw:text-secondary">A rejection</span>
          <Select
            ariaLabel={`Stage ${n} on reject`}
            onChange={(value) => onChange({ onReject: value as OnReject })}
            options={onRejectOptions}
            value={stage.onReject}
          />
        </div>
      </div>
      <div className="tw:flex tw:flex-col tw:gap-1.5">
        <span className="tw:text-xs tw:font-medium tw:text-secondary">Asks</span>
        <SeatsEditor
          label={`Stage ${n} approver`}
          onChange={(approvers) => onChange({ approvers })}
          seats={stage.approvers}
        />
      </div>
    </div>
  );
}

function SeatsEditor({
  seats,
  onChange,
  label,
}: {
  seats: Seat[];
  onChange: (seats: Seat[]) => void;
  /** Names each control: "Stage 1 approver 2 kind". */
  label: string;
}) {
  const set = (index: number, seat: Seat) => onChange(seats.map((s, i) => (i === index ? seat : s)));
  return (
    <div className="tw:flex tw:flex-col tw:gap-2">
      {seats.map((seat, index) => {
        const where = `${label} ${index + 1}`;
        return (
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2" key={index}>
            <Select
              ariaLabel={`${where} kind`}
              className="tw:w-56"
              onChange={(value) => {
                const kind = value as SeatKind;
                set(index, { kind, name: kind === 'ROLE' ? 'DATA_OWNER' : NAMED.has(kind) ? '' : null });
              }}
              options={SEAT_KINDS}
              value={seat.kind}
            />
            {seat.kind === 'ROLE' && (
              <Select
                ariaLabel={`${where} role`}
                className="tw:w-56"
                onChange={(value) => set(index, { kind: 'ROLE', name: value })}
                options={ROLES}
                value={seat.name ?? 'DATA_OWNER'}
              />
            )}
            {(seat.kind === 'USER' || seat.kind === 'TEAM') && (
              <NameField
                ariaLabel={`${where} name`}
                kind={seat.kind}
                onChange={(name) => set(index, { kind: seat.kind, name })}
                value={seat.name ?? ''}
              />
            )}
            <button
              aria-label={`Remove ${where}`}
              className="tw:flex tw:size-8 tw:cursor-pointer tw:items-center tw:justify-center tw:rounded-md tw:text-quaternary tw:hover:bg-secondary tw:hover:text-secondary"
              onClick={() => onChange(seats.filter((_, i) => i !== index))}
              type="button">
              <XClose className="tw:size-4" />
            </button>
          </div>
        );
      })}
      <div>
        <Button
          color="secondary"
          iconLeading={Plus}
          isDisabled={seats.length >= MAX_SEATS}
          onPress={() => onChange([...seats, { kind: 'USER', name: '' }])}
          size="sm">
          {`Add ${label.toLowerCase().replace(/^stage \d+ /, '')}`}
        </Button>
      </div>
    </div>
  );
}

/**
 * A username or a team, typed, with the directory's matches offered.
 *
 * <p>Free text because a seat is matched by name when its step opens -- a
 * person who joins after the workflow is saved is still found -- but the
 * suggestions come from the directory, so a typo is the exception.
 */
function NameField({
  kind,
  value,
  onChange,
  ariaLabel,
}: {
  kind: 'USER' | 'TEAM';
  value: string;
  onChange: (value: string) => void;
  ariaLabel: string;
}) {
  const search = value.trim();
  const { data } = useQuery({
    queryKey: ['workflow-seat-names', kind, search],
    queryFn: () => fetchPrincipals({ type: kind === 'USER' ? 'USER' : 'GROUP', search, limit: 8 }),
    enabled: search.length > 0,
    staleTime: 30 * 1000,
  });
  const listId = `${ariaLabel.replace(/\s+/g, '-').toLowerCase()}-options`;
  return (
    <>
      <input
        aria-label={ariaLabel}
        className={`${FIELD} tw:w-56`}
        list={listId}
        onChange={(event) => onChange(event.target.value)}
        placeholder={kind === 'USER' ? 'Username or email' : 'Team name'}
        value={value}
      />
      <datalist id={listId}>
        {(data ?? []).map((principal) => (
          <option key={principal.id} value={principal.username}>
            {principal.displayName ?? principal.username}
          </option>
        ))}
      </datalist>
    </>
  );
}
