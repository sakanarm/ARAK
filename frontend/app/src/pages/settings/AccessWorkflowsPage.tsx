import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { AlertTriangle, Dataflow03, Plus, Trash01 } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { relativeTime } from '../../components/widgets';
import { apiErrorMessage } from '../../api/client';
import { describeOnReject, describeRule, describeSeat } from '../../api/accessRequests';
import {
  deleteWorkflow,
  fetchWorkflowHistory,
  fetchWorkflows,
  stepsOf,
  WORKFLOWS_KEY,
  type AccessWorkflow,
  type WorkflowRow,
  type WorkflowStage,
} from '../../api/accessWorkflows';
import { useAuthStore } from '../../auth/authStore';

export { describeSteps, problemOf } from './workflowParts';

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
 * <p>Each card reads a workflow in words; designing one happens in the
 * workflow builder ({@code WorkflowBuilderPage}), where the stages are drawn
 * as the request will walk them and each is edited beside the drawing.
 */

export default function AccessWorkflowsPage() {
  const navigate = useNavigate();
  const mayDesign = useAuthStore((state) =>
    state.hasRole('PLATFORM_ADMIN', 'POLICY_AUTHOR', 'DATA_OWNER')
  );
  const { data, isLoading, error } = useQuery({
    queryKey: WORKFLOWS_KEY,
    queryFn: fetchWorkflows,
    retry: false,
  });

  const rows = [...(data?.workflows ?? [])].sort((a, b) =>
    (a.workflow.scopeFqn ?? '').localeCompare(b.workflow.scopeFqn ?? '')
  );
  const byDefault = rows.find((row) => row.workflow.scopeFqn === null) ?? null;
  const scoped = rows.filter((row) => row.workflow.scopeFqn !== null);
  const canCreateDefault = data?.canCreateDefault ?? false;

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
        {data && mayDesign && (
          <Button
            color="primary"
            iconLeading={Plus}
            onPress={() => navigate('/settings/workflows/new')}
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

      {data && (
        <>
          <Section
            blurb="Every table that no workflow below covers."
            heading="Organisation default">
            {byDefault ? (
              <WorkflowCard row={byDefault} workflow={byDefault.workflow} />
            ) : (
              <p className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-4 tw:py-3 tw:text-sm tw:text-tertiary">
                No default is set, so the built-in workflow below applies to every table without a
                workflow of its own.
                {canCreateDefault && mayDesign && (
                  <>
                    {' '}
                    <Link
                      className="tw:font-semibold tw:text-brand-secondary tw:hover:underline"
                      to="/settings/workflows/new?default=1">
                      Set a default
                    </Link>
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
              scoped.map((row) => <WorkflowCard key={row.workflow.id} row={row} workflow={row.workflow} />)
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

// ------------------------------------------------------------------ reading

function WorkflowCard({
  workflow,
  row,
  builtIn = false,
}: {
  workflow: AccessWorkflow;
  row?: WorkflowRow;
  builtIn?: boolean;
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
        <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          <Link
            className="tw:inline-flex tw:items-center tw:gap-1.5 tw:rounded-lg tw:border tw:border-primary tw:bg-primary tw:px-3 tw:py-2 tw:text-sm tw:font-semibold tw:text-secondary tw:shadow-xs tw:hover:bg-primary_hover"
            to={`/settings/workflows/${builtIn ? 'built-in' : workflow.id}`}>
            <Dataflow03 className="tw:size-4" />
            {row?.canEdit ? 'Open in builder' : 'View diagram'}
          </Link>
          {row && (
            <Button color="secondary" onPress={() => setHistory((open) => !open)} size="sm">
              {history ? 'Hide changes' : 'Changes'}
            </Button>
          )}
          {row?.canEdit && (
            <Button
              color="secondary-destructive"
              iconLeading={Trash01}
              onPress={() => setConfirming(true)}
              size="sm">
              Delete
            </Button>
          )}
        </div>
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
