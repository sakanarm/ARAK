import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { AlertTriangle, Check, Inbox01, InfoCircle, Key01, XClose } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { apiErrorMessage } from '../../api/client';
import {
  approveRequest,
  describeApprovers,
  fetchMyRequests,
  fetchRequestInbox,
  rejectRequest,
  withdrawRequest,
  type AccessRequest,
  type RequestStatus,
} from '../../api/accessRequests';
import { FIELD, Select, TextField } from '../policies/controls';

/**
 * Asking for a table, and answering (FR-7, the first slice of the Phase 2
 * workflow).
 *
 * <p>One page for both sides, because most people are on both: an analyst who
 * owns one table asks for another. "Waiting for you" is whatever the server
 * says this person may decide — tables they own, directly or through a team,
 * or everything for an administrator — and it is empty rather than hidden for
 * everybody else, so an owner who expected requests and has none can tell the
 * difference between "none yet" and "not shown".
 *
 * <p>Approving writes an ordinary grant, the same one an owner could issue by
 * hand from the table's Access tab. It composes with every other policy like
 * any grant: it cannot beat a DENY or unmask a column, and the page says so,
 * because an owner who thinks "approve" means "unrestricted" is going to be
 * asked why the requester still sees asterisks.
 *
 * <p>Laid out as the Enforcement page lays out its tables — a card per item
 * with the icon tile, the name, a status pill and the FQN underneath — so the
 * two pages that act on a table read as one product.
 */
export default function AccessRequestsPage() {
  const [status, setStatus] = useState<RequestStatus | ''>('PENDING');

  const inbox = useQuery({
    queryKey: ['access-requests', 'inbox', status],
    queryFn: () => fetchRequestInbox(status || null),
  });
  const mine = useQuery({
    queryKey: ['access-requests', 'mine'],
    queryFn: () => fetchMyRequests(),
  });

  return (
    <div className="tw:flex tw:flex-col tw:gap-6">
      <header>
        <h1 className="tw:text-display-xs tw:font-semibold tw:text-primary">Access requests</h1>
        <p className="tw:mt-1 tw:max-w-3xl tw:text-pretty tw:text-sm tw:text-tertiary">
          Ask a table&apos;s owner for access from the refusal on the Query page. The owner
          recorded in OpenMetadata decides — or a platform administrator when the table has
          none.
        </p>
      </header>

      <p className="tw:flex tw:max-w-3xl tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-blue-50 tw:px-3 tw:py-2 tw:text-sm tw:text-secondary">
        <InfoCircle className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-brand-primary" />
        <span>
          Approving issues a grant that composes with every policy like any other: it cannot
          override a DENY or lift a mask, so an approved requester may still see masked columns
          and filtered rows.
        </span>
      </p>

      <section aria-labelledby="inbox-heading" className="tw:flex tw:flex-col tw:gap-3">
        <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-between tw:gap-3">
          <SectionHeading count={inbox.data?.length} id="inbox-heading">
            Waiting for you
          </SectionHeading>
          <Select
            ariaLabel="Status"
            className="tw:w-44"
            onChange={(value) => setStatus(value as RequestStatus | '')}
            options={[
              { value: 'PENDING', label: 'Pending' },
              { value: 'APPROVED', label: 'Approved' },
              { value: 'REJECTED', label: 'Rejected' },
              { value: 'WITHDRAWN', label: 'Withdrawn' },
              { value: '', label: 'All' },
            ]}
            value={status}
          />
        </div>
        <RequestList
          empty={
            status === 'PENDING'
              ? 'Nothing is waiting for your decision. Requests for tables you own appear here.'
              : 'No requests with this status.'
          }
          error={inbox.error}
          isLoading={inbox.isLoading}
          requests={inbox.data}
          side="owner"
        />
      </section>

      <section aria-labelledby="mine-heading" className="tw:flex tw:flex-col tw:gap-3">
        <SectionHeading count={mine.data?.length} id="mine-heading">
          Your requests
        </SectionHeading>
        <RequestList
          empty="You have not asked for anything. A refused query offers to send a request when the owner could let you in."
          error={mine.error}
          isLoading={mine.isLoading}
          requests={mine.data}
          side="requester"
        />
      </section>
    </div>
  );
}

/** A section title with the count beside it, as the catalog's facet headings carry theirs. */
function SectionHeading({
  id,
  count,
  children,
}: {
  id: string;
  count: number | undefined;
  children: string;
}) {
  return (
    <div className="tw:flex tw:items-center tw:gap-2">
      <h2 className="tw:text-lg tw:font-semibold tw:text-primary" id={id}>
        {children}
      </h2>
      {count !== undefined && count > 0 && (
        <span
          aria-hidden
          className="tw:rounded-full tw:bg-brand-primary tw:px-2 tw:py-0.5 tw:text-xs tw:font-medium tw:text-brand-secondary">
          {count}
        </span>
      )}
    </div>
  );
}

function RequestList({
  requests,
  isLoading,
  error,
  empty,
  side,
}: {
  requests: AccessRequest[] | undefined;
  isLoading: boolean;
  error: unknown;
  empty: string;
  side: 'owner' | 'requester';
}) {
  if (isLoading) {
    return <p className="tw:text-sm tw:text-tertiary">Loading…</p>;
  }
  if (error) {
    return (
      <p
        className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
        role="alert">
        <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
        <span>{apiErrorMessage(error, 'The requests could not be loaded.')}</span>
      </p>
    );
  }
  if (!requests || requests.length === 0) {
    return (
      <div className="tw:flex tw:flex-col tw:items-center tw:gap-3 tw:rounded-xl tw:border tw:border-dashed tw:border-secondary tw:bg-primary tw:px-6 tw:py-10 tw:text-center">
        <Inbox01 className="tw:size-7 tw:text-fg-quaternary" />
        <p className="tw:max-w-md tw:text-sm tw:text-tertiary">{empty}</p>
      </div>
    );
  }
  return (
    <ul className="tw:flex tw:flex-col tw:gap-3">
      {requests.map((request) => (
        <li key={request.id}>
          <RequestCard request={request} side={side} />
        </li>
      ))}
    </ul>
  );
}

const STATUS: Record<
  RequestStatus,
  { label: string; colour: 'warning' | 'success' | 'error' | 'gray' }
> = {
  PENDING: { label: 'Pending', colour: 'warning' },
  APPROVED: { label: 'Approved', colour: 'success' },
  REJECTED: { label: 'Rejected', colour: 'error' },
  WITHDRAWN: { label: 'Withdrawn', colour: 'gray' },
};

function RequestCard({
  request,
  side,
}: {
  request: AccessRequest;
  side: 'owner' | 'requester';
}) {
  const pending = request.status === 'PENDING';
  const status = STATUS[request.status] ?? { label: request.status, colour: 'gray' as const };
  const table = request.assetFqn.split('.').pop() || request.assetFqn;
  return (
    <article
      aria-label={`Request for ${request.assetFqn}`}
      className="tw:overflow-hidden tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
      <div className="tw:flex tw:items-start tw:gap-4 tw:px-5 tw:py-4">
        <span
          aria-hidden
          className="tw:flex tw:size-10 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-utility-brand-50">
          <Key01 className="tw:size-5 tw:text-fg-brand-primary" />
        </span>

        <div className="tw:min-w-0 tw:flex-1">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <h3 className="tw:truncate tw:text-md tw:font-semibold tw:text-primary">{table}</h3>
            <Badge color={status.colour} size="sm" type="pill-color">
              {status.label}
            </Badge>
            {request.purpose && (
              <Badge color="gray" size="sm" type="pill-color">
                {request.purpose}
              </Badge>
            )}
            <span className="tw:ml-auto tw:text-xs tw:text-quaternary">
              {when(request.createdAt)}
            </span>
          </div>
          <Link
            className="tw:mt-1 tw:block tw:truncate tw:font-mono tw:text-xs tw:text-tertiary tw:hover:text-brand-secondary tw:hover:underline"
            to={`/catalog/${encodeURIComponent(request.assetFqn)}`}>
            {request.assetFqn}
          </Link>

          <p className="tw:mt-3 tw:whitespace-pre-wrap tw:border-l-2 tw:border-secondary tw:pl-3 tw:text-sm tw:text-secondary">
            {request.reason}
          </p>

          <dl className="tw:mt-3 tw:grid tw:gap-x-6 tw:gap-y-1 tw:text-xs tw:sm:grid-cols-2">
            {side === 'owner' && <Pair label="Asked by" value={request.requesterUsername} />}
            <Pair label="For" value={duration(request.requestedDays)} />
            {side === 'requester' && pending && (
              <Pair label="Decides" value={describeApprovers(request.approvers)} />
            )}
            {request.decidedBy && (
              <Pair
                label={request.status === 'WITHDRAWN' ? 'Withdrawn' : 'Decided by'}
                value={`${request.decidedBy}${
                  request.decidedAt ? ` · ${when(request.decidedAt)}` : ''
                }${request.decisionNote ? ` — ${request.decisionNote}` : ''}`}
              />
            )}
          </dl>

          {(request.attemptedSql || request.deniedBy) && (
            <details className="tw:mt-3 tw:text-xs">
              <summary className="tw:cursor-pointer tw:font-medium tw:text-secondary tw:hover:text-primary">
                What was refused
              </summary>
              {request.deniedBy && <p className="tw:mt-2 tw:text-tertiary">{request.deniedBy}</p>}
              {request.attemptedSql && (
                <pre className="tw:mt-2 tw:overflow-auto tw:whitespace-pre-wrap tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-3 tw:font-mono tw:text-xs tw:text-primary">
                  {request.attemptedSql}
                </pre>
              )}
            </details>
          )}
        </div>
      </div>

      {pending && side === 'owner' && request.mayDecide && <Decide request={request} />}
      {pending && side === 'requester' && <Withdraw request={request} />}
    </article>
  );
}

function Pair({ label, value }: { label: string; value: string }) {
  return (
    <div className="tw:flex tw:gap-2">
      <dt className="tw:shrink-0 tw:text-quaternary">{label}</dt>
      <dd className="tw:min-w-0 tw:text-tertiary">{value}</dd>
    </div>
  );
}

function Decide({ request }: { request: AccessRequest }) {
  const queryClient = useQueryClient();
  const [days, setDays] = useState(
    request.requestedDays === null ? '' : String(request.requestedDays)
  );
  const [note, setNote] = useState('');
  const done = () => queryClient.invalidateQueries({ queryKey: ['access-requests'] });

  const approve = useMutation({
    mutationFn: () =>
      approveRequest(request.id, {
        days: days.trim() === '' ? null : Number.parseInt(days, 10),
        note: note.trim() || null,
      }),
    onSuccess: done,
  });
  const reject = useMutation({
    mutationFn: () => rejectRequest(request.id, note.trim()),
    onSuccess: done,
  });

  // Shorten only: an owner can give less time than was asked for, never more,
  // and cannot turn a bounded ask into an open-ended grant. The server holds
  // the same line; this only keeps the button from offering what it refuses.
  const parsed = Number.parseInt(days, 10);
  const daysValid =
    days.trim() === ''
      ? request.requestedDays === null
      : parsed >= 1 && parsed <= Math.min(365, request.requestedDays ?? 365);
  const busy = approve.isPending || reject.isPending;
  const failure = approve.error ?? reject.error;

  return (
    <div className="tw:flex tw:flex-col tw:gap-3 tw:border-t tw:border-secondary tw:bg-secondary tw:px-5 tw:py-4">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <span className="tw:text-sm tw:font-medium tw:text-secondary">Grant for</span>
        <TextField
          ariaLabel="Grant days"
          className="tw:w-20"
          onChange={(value) => setDays(value.replace(/[^0-9]/g, ''))}
          placeholder="—"
          value={days}
        />
        <span className="tw:text-sm tw:text-tertiary">
          days{days.trim() === '' ? ' (until revoked)' : ''}
        </span>
        {!daysValid && (
          <span className="tw:text-xs tw:text-error-primary">
            {request.requestedDays === null
              ? 'Between 1 and 365 days, or blank for until revoked.'
              : `Between 1 and ${request.requestedDays} days — no longer than was asked.`}
          </span>
        )}
      </div>
      <textarea
        aria-label="Note to the requester"
        className={`${FIELD} tw:min-h-16 tw:resize-y`}
        onChange={(event) => setNote(event.target.value)}
        placeholder="A note to the requester — required to reject"
        value={note}
      />
      {failure && (
        <p
          className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
          role="alert">
          <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
          <span>{apiErrorMessage(failure, 'The decision was not recorded.')}</span>
        </p>
      )}
      <div className="tw:flex tw:justify-end tw:gap-2">
        <Button
          color="secondary-destructive"
          iconLeading={XClose}
          isDisabled={busy || note.trim().length === 0}
          onPress={() => reject.mutate()}
          size="sm">
          {reject.isPending ? 'Rejecting…' : 'Reject'}
        </Button>
        <Button
          color="primary"
          iconLeading={Check}
          isDisabled={busy || !daysValid}
          onPress={() => approve.mutate()}
          size="sm">
          {approve.isPending ? 'Approving…' : 'Approve'}
        </Button>
      </div>
    </div>
  );
}

function Withdraw({ request }: { request: AccessRequest }) {
  const queryClient = useQueryClient();
  const withdraw = useMutation({
    mutationFn: () => withdrawRequest(request.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['access-requests'] }),
  });
  return (
    <div className="tw:flex tw:items-center tw:justify-end tw:gap-3 tw:border-t tw:border-secondary tw:bg-secondary tw:px-5 tw:py-3">
      {withdraw.isError && (
        <span className="tw:mr-auto tw:text-sm tw:text-error-primary" role="alert">
          {apiErrorMessage(withdraw.error, 'The request was not withdrawn.')}
        </span>
      )}
      <Button
        color="secondary"
        isDisabled={withdraw.isPending}
        onPress={() => withdraw.mutate()}
        size="sm">
        {withdraw.isPending ? 'Withdrawing…' : 'Withdraw'}
      </Button>
    </div>
  );
}

function duration(days: number | null): string {
  if (days === null) return 'Until revoked';
  return days === 1 ? '1 day' : `${days} days`;
}

function when(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}
