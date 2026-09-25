import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { AlertTriangle, CheckCircle, Key01, Send01 } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { apiErrorMessage } from '../../api/client';
import {
  describeApprovers,
  describeOnReject,
  describeRule,
  requestAccess,
  type AccessRequest,
  type Refusal,
  type Route,
} from '../../api/accessRequests';
import { stepsOf } from '../../api/accessWorkflows';
import { FIELD, TextField } from '../policies/controls';

/**
 * What to do about a refusal, under the refusal itself.
 *
 * <p>Only what the server said is offered. Whether a request could help is the
 * engine's answer — the same decision re-run with a grant from the owner in the
 * stack — so the button is drawn only when a "yes" from the owner would open
 * the table. When a DENY or a stricter layer would still refuse, the box says
 * which policy that is instead, because asking the owner would put a request in
 * front of somebody who cannot give the answer being asked for.
 *
 * <p>The catalog asks the same question before anybody has run anything
 * (`from="catalog"`): there is no statement and no refusal to send along, so
 * the request carries only the reason, and the box says "you cannot read this
 * yet" rather than pointing at a query that never ran.
 */
export default function RequestAccess({
  refusal,
  sourceId,
  sql,
  purpose,
  from = 'query',
}: {
  refusal: Refusal;
  sourceId: string | null;
  sql: string;
  purpose: string | null;
  from?: 'query' | 'catalog';
}) {
  const catalog = from === 'catalog';
  const [asking, setAsking] = useState(false);
  const [sent, setSent] = useState<AccessRequest | null>(null);

  if (!refusal.assetFqn) {
    return null;
  }

  if (sent || refusal.openRequestId) {
    return <RequestedNote refusal={refusal} sent={sent} />;
  }

  if (!refusal.requestable) {
    return <BlockedNote refusal={refusal} />;
  }

  if (!asking) {
    return (
      <Card>
        <div className="tw:min-w-0 tw:flex-1">
          <p className="tw:text-sm tw:font-semibold tw:text-primary">
            {catalog ? 'You cannot read this table yet' : 'The owner can let you in'}
          </p>
          <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">
            {catalog && 'The owner can let you in. '}
            {whoDecides(refusal)}
          </p>
        </div>
        <Button color="primary" iconLeading={Send01} onPress={() => setAsking(true)} size="sm">
          {catalog ? 'Request access' : 'Request access from the owner'}
        </Button>
      </Card>
    );
  }

  return (
    <RequestAccessForm
      className="tw:mt-3 tw:shadow-xs"
      from={from}
      onCancel={() => setAsking(false)}
      onSent={setSent}
      purpose={purpose}
      refusal={refusal}
      sourceId={sourceId}
      sql={sql}
    />
  );
}

/**
 * The request itself: why, and for how long.
 *
 * <p>Its own component so the catalog can open it in a dialog from the page
 * header, where OpenMetadata puts an asset's actions, while the Query page
 * keeps it inline under the refusal it answers. Both send the same request.
 */
export function RequestAccessForm({
  refusal,
  sourceId,
  sql,
  purpose,
  from = 'query',
  onCancel,
  onSent,
  className = '',
}: {
  refusal: Refusal;
  sourceId: string | null;
  sql: string;
  purpose: string | null;
  from?: 'query' | 'catalog';
  onCancel: () => void;
  onSent: (request: AccessRequest) => void;
  /** Added to the frame: a margin inline, a heavier shadow in a dialog. */
  className?: string;
}) {
  const catalog = from === 'catalog';
  const queryClient = useQueryClient();
  const [reason, setReason] = useState('');
  const [days, setDays] = useState('30');

  const send = useMutation({
    mutationFn: () =>
      requestAccess({
        assetFqn: refusal.assetFqn!,
        sourceId,
        reason: reason.trim(),
        purpose,
        days: days.trim() === '' ? null : Number.parseInt(days, 10),
        attemptedSql: catalog ? null : sql,
        deniedBy: catalog ? null : refusal.message,
      }),
    onSuccess: (request) => {
      void queryClient.invalidateQueries({ queryKey: ['access-requests'] });
      onSent(request);
    },
  });

  const parsedDays = Number.parseInt(days, 10);
  const daysValid = days.trim() === '' || (parsedDays >= 1 && parsedDays <= 365);
  const ready = reason.trim().length > 0 && daysValid && !send.isPending;

  return (
    <form
      aria-label="Request access"
      className={`tw:rounded-xl tw:border tw:border-secondary tw:bg-primary ${className}`}
      onSubmit={(event) => {
        event.preventDefault();
        if (ready) send.mutate();
      }}>
      <div className="tw:flex tw:items-start tw:gap-3 tw:border-b tw:border-secondary tw:px-4 tw:py-3">
        <Tile />
        <div className="tw:min-w-0">
          <p className="tw:text-sm tw:font-semibold tw:text-primary">Request access</p>
          <p className="tw:truncate tw:font-mono tw:text-xs tw:text-tertiary">
            {refusal.assetFqn}
          </p>
        </div>
      </div>

      <div className="tw:flex tw:flex-col tw:gap-4 tw:px-4 tw:py-4">
        {refusal.route && !refusal.stranded && !ownersOnly(refusal.route) && (
          <RouteSteps route={refusal.route} />
        )}
        <p className="tw:text-sm tw:text-tertiary">
          {whoDecides(refusal)}{' '}
          {catalog
            ? 'Say what the data is for; that is what they decide on.'
            : 'The statement you ran and the refusal go with the request, so they can see what you were trying to do.'}
        </p>
        <label className="tw:flex tw:flex-col tw:gap-1.5">
          <span className="tw:text-sm tw:font-medium tw:text-secondary">
            Why you need it <span className="tw:text-error-primary">*</span>
          </span>
          <textarea
            aria-label="Why you need it"
            className={`${FIELD} tw:min-h-20 tw:resize-y`}
            onChange={(event) => setReason(event.target.value)}
            placeholder="What the data is for, and for how long"
            value={reason}
          />
        </label>
        <div className="tw:flex tw:flex-col tw:gap-1.5">
          <span className="tw:text-sm tw:font-medium tw:text-secondary">For how long</span>
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <TextField
              ariaLabel="Days"
              className="tw:w-20"
              onChange={(value) => setDays(value.replace(/[^0-9]/g, ''))}
              placeholder="—"
              value={days}
            />
            <span className="tw:text-sm tw:text-tertiary">
              days{days.trim() === '' ? ' (until revoked)' : ''}
            </span>
            {purpose && (
              <Badge color="gray" size="sm" type="pill-color">
                Purpose: {purpose}
              </Badge>
            )}
          </div>
          {!daysValid && (
            <span className="tw:text-xs tw:text-error-primary">Between 1 and 365 days, or blank.</span>
          )}
        </div>
        {send.isError && (
          <p
            className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
            role="alert">
            <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
            <span>{apiErrorMessage(send.error, 'The request was not sent.')}</span>
          </p>
        )}
      </div>

      <div className="tw:flex tw:justify-end tw:gap-2 tw:border-t tw:border-secondary tw:px-4 tw:py-3">
        <Button color="secondary" onPress={onCancel} size="sm">
          Cancel
        </Button>
        <Button color="primary" iconLeading={Send01} isDisabled={!ready} size="sm" type="submit">
          {send.isPending ? 'Sending…' : 'Send request'}
        </Button>
      </div>
    </form>
  );
}

/**
 * The route is the built-in one: any one owner of the table approves.
 *
 * <p>Then the owners' names say it better than the route does, so the page
 * keeps saying "Decided by ann" rather than "Owners of the table".
 */
function ownersOnly(route: Route): boolean {
  const [only, ...rest] = route.stages;
  return (
    rest.length === 0 &&
    only !== undefined &&
    only.rule === 'ANY' &&
    only.approvers.length === 1 &&
    only.approvers[0] === 'Owners of the table'
  );
}

/**
 * Who a request will wait for, in a sentence.
 *
 * <p>The owners by name while the route is the built-in one, the workflow's
 * steps once somebody configured one -- the owners might not be asked at all
 * then. A request that would wait for nobody says so whatever the route.
 */
export function whoDecides(refusal: Refusal): string {
  const route = refusal.route;
  if (refusal.stranded || !route || route.stages.length === 0 || ownersOnly(route)) {
    return describeApprovers(refusal.approvers, refusal.stranded);
  }
  const steps = stepsOf(route.stages).map((group) =>
    group.length === 1 ? group[0].name : `${group.map((stage) => stage.name).join(' and ')} together`
  );
  return `It goes through the “${route.workflowName}” workflow: ${steps.join(', then ')}.`;
}

/**
 * The steps of the route, one under the other, with who each stage asks.
 *
 * <p>Shown in the form, before the request is sent, so the requester knows
 * how many answers it takes and from whom; stages that share a step are
 * asked at the same time.
 */
export function RouteSteps({ route }: { route: Route }) {
  const steps = stepsOf(route.stages);
  return (
    <div
      aria-label="Approval route"
      className="tw:rounded-lg tw:border tw:border-secondary tw:px-3 tw:py-2.5"
      role="group">
      <p className="tw:text-xs tw:text-tertiary">
        Workflow <span className="tw:font-semibold tw:text-secondary">{route.workflowName}</span>
      </p>
      <ol className="tw:mt-2 tw:flex tw:flex-col tw:gap-2">
        {steps.map((group, index) => (
          <li className="tw:flex tw:items-start tw:gap-2.5" key={group[0].step}>
            <span
              aria-hidden
              className="tw:flex tw:size-5 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-full tw:bg-utility-brand-50 tw:text-xs tw:font-semibold tw:text-brand-secondary">
              {index + 1}
            </span>
            <span className="tw:flex tw:min-w-0 tw:flex-col tw:gap-1">
              {group.length > 1 && (
                <span className="tw:text-xs tw:text-tertiary">
                  Step {index + 1} · in parallel
                </span>
              )}
              {group.map((stage) => (
                <span className="tw:block" key={`${stage.step}-${stage.name}`}>
                  <span className="tw:text-sm tw:font-medium tw:text-primary">{stage.name}</span>
                  <span className="tw:block tw:text-xs tw:text-tertiary">
                    {describeRule(stage.rule, stage.minApprovals)} · {describeOnReject(stage.onReject)}
                  </span>
                  <span className="tw:block tw:text-xs tw:text-secondary">
                    Asks {stage.approvers.join(', ') || 'nobody'}
                  </span>
                </span>
              ))}
            </span>
          </li>
        ))}
      </ol>
    </div>
  );
}

/** The key in a brand tile, as the cards elsewhere carry their icon. */
function Tile() {
  return (
    <span
      aria-hidden
      className="tw:flex tw:size-9 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-utility-brand-50">
      <Key01 className="tw:size-4 tw:text-fg-brand-primary" />
    </span>
  );
}

function Card({ children }: { children: ReactNode }) {
  return (
    <div className="tw:mt-3 tw:flex tw:flex-wrap tw:items-center tw:gap-3 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-4 tw:py-3 tw:shadow-xs">
      <Tile />
      {children}
    </div>
  );
}

/** A request is already on its way to the people who decide it. */
export function RequestedNote({
  refusal,
  sent,
  className,
}: {
  refusal: Refusal;
  sent: AccessRequest | null;
  className?: string;
}) {
  return (
    <Note className={className} icon={CheckCircle} tone="success">
      <span className="tw:font-semibold">{sent ? 'Request sent' : 'Already requested'}</span>{' '}
      for <span className="tw:font-mono">{refusal.assetFqn}</span>.{' '}
      {whoDecides(sent ? { ...refusal, approvers: sent.approvers, stranded: sent.stranded } : refusal)}{' '}
      You will be let in once it is approved and set up — follow it under{' '}
      <Link className="tw:font-semibold tw:text-brand-secondary tw:hover:underline" to="/requests">
        Access requests
      </Link>
      .
    </Note>
  );
}

/**
 * A grant would not help, and which policy is in the way.
 *
 * <p>Nothing to say when the server gave no reason: that is a run on
 * somebody else's behalf, and a request is theirs to make, not ours.
 */
export function BlockedNote({ refusal, className }: { refusal: Refusal; className?: string }) {
  return refusal.blockedBy ? (
    <Note className={className} icon={AlertTriangle} tone="warning">
      Asking the owner would not help: even with their grant,{' '}
      <strong>{refusal.blockedBy}</strong> still refuses. Access to{' '}
      <span className="tw:font-mono">{refusal.assetFqn}</span> has to change in that policy.
    </Note>
  ) : null;
}

function Note({
  children,
  icon: Icon,
  tone,
  className = 'tw:mt-3 tw:shadow-xs',
}: {
  children: ReactNode;
  icon: typeof CheckCircle;
  tone: 'success' | 'warning';
  className?: string;
}) {
  return (
    <p
      className={`${className} tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2.5 tw:text-sm tw:text-secondary`}>
      <Icon
        className={`tw:mt-0.5 tw:size-4 tw:shrink-0 ${
          tone === 'success' ? 'tw:text-fg-success-primary' : 'tw:text-fg-warning-primary'
        }`}
      />
      <span>{children}</span>
    </p>
  );
}
