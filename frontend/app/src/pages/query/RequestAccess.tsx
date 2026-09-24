import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { AlertTriangle, CheckCircle, Key01, Send01 } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { apiErrorMessage } from '../../api/client';
import {
  describeApprovers,
  requestAccess,
  type AccessRequest,
  type Refusal,
} from '../../api/accessRequests';
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
 */
export default function RequestAccess({
  refusal,
  sourceId,
  sql,
  purpose,
}: {
  refusal: Refusal;
  sourceId: string | null;
  sql: string;
  purpose: string | null;
}) {
  const queryClient = useQueryClient();
  const [asking, setAsking] = useState(false);
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
        attemptedSql: sql,
        deniedBy: refusal.message,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['access-requests'] });
    },
  });

  if (!refusal.assetFqn) {
    return null;
  }

  const sent: AccessRequest | undefined = send.data;
  if (sent || refusal.openRequestId) {
    return (
      <Note icon={CheckCircle} tone="success">
        <span className="tw:font-semibold">{sent ? 'Request sent' : 'Already requested'}</span>{' '}
        for <span className="tw:font-mono">{refusal.assetFqn}</span>.{' '}
        {describeApprovers(sent?.approvers ?? refusal.approvers)} You will be let in once it is
        approved — follow it under{' '}
        <Link className="tw:font-semibold tw:text-brand-secondary tw:hover:underline" to="/requests">
          Access requests
        </Link>
        .
      </Note>
    );
  }

  if (!refusal.requestable) {
    // Nothing to say when the server gave no reason: that is a run on
    // somebody else's behalf, and a request is theirs to make, not ours.
    return refusal.blockedBy ? (
      <Note icon={AlertTriangle} tone="warning">
        Asking the owner would not help: even with their grant,{' '}
        <strong>{refusal.blockedBy}</strong> still refuses. Access to{' '}
        <span className="tw:font-mono">{refusal.assetFqn}</span> has to change in that policy.
      </Note>
    ) : null;
  }

  if (!asking) {
    return (
      <Card>
        <div className="tw:min-w-0 tw:flex-1">
          <p className="tw:text-sm tw:font-semibold tw:text-primary">The owner can let you in</p>
          <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">
            {describeApprovers(refusal.approvers)}
          </p>
        </div>
        <Button color="primary" iconLeading={Send01} onPress={() => setAsking(true)} size="sm">
          Request access from the owner
        </Button>
      </Card>
    );
  }

  const parsedDays = Number.parseInt(days, 10);
  const daysValid = days.trim() === '' || (parsedDays >= 1 && parsedDays <= 365);
  const ready = reason.trim().length > 0 && daysValid && !send.isPending;

  return (
    <form
      aria-label="Request access"
      className="tw:mt-3 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs"
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
        <p className="tw:text-sm tw:text-tertiary">
          {describeApprovers(refusal.approvers)} The statement you ran and the refusal go with
          the request, so they can see what you were trying to do.
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
        <Button color="secondary" onPress={() => setAsking(false)} size="sm">
          Cancel
        </Button>
        <Button color="primary" iconLeading={Send01} isDisabled={!ready} size="sm" type="submit">
          {send.isPending ? 'Sending…' : 'Send request'}
        </Button>
      </div>
    </form>
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

function Note({
  children,
  icon: Icon,
  tone,
}: {
  children: ReactNode;
  icon: typeof CheckCircle;
  tone: 'success' | 'warning';
}) {
  return (
    <p
      className="tw:mt-3 tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2.5 tw:text-sm tw:text-secondary tw:shadow-xs">
      <Icon
        className={`tw:mt-0.5 tw:size-4 tw:shrink-0 ${
          tone === 'success' ? 'tw:text-fg-success-primary' : 'tw:text-fg-warning-primary'
        }`}
      />
      <span>{children}</span>
    </p>
  );
}
