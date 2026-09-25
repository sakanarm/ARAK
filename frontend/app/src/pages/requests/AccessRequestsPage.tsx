import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import {
  AlertTriangle,
  Check,
  Clock,
  Database01,
  Inbox01,
  InfoCircle,
  Key01,
  Send01,
  Table,
  XClose,
} from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { relativeTime } from '../../components/widgets';
import { apiErrorMessage } from '../../api/client';
import {
  approveRequest,
  describeApprovers,
  fetchMyRequests,
  fetchRequest,
  fetchRequestInbox,
  rejectRequest,
  withdrawRequest,
  type AccessRequest,
  type RequestStatus,
} from '../../api/accessRequests';
import { FIELD } from '../policies/controls';
import { countLabel, tableName, useRequestNotices } from './useRequestNotices';

/**
 * Asking for a table, and answering (FR-7, the first slice of the Phase 2
 * workflow).
 *
 * <p>Two tabs, because most people are on both sides and the two lists answer
 * different questions: <b>Inbox</b> is what waits for this person's decision --
 * tables they own, directly or through a team, or everything for an
 * administrator -- and <b>My requests</b> is what they asked for. Each carries
 * its count of what is still open, the same numbers the bell and the rail show.
 *
 * <p>Laid out as OpenMetadata lays out its tasks: the list on the left, the
 * request that is open on the right, so an owner working through five asks
 * never loses their place. The selection lives in the URL, which is what lets
 * a notification open one request directly.
 *
 * <p>Approving writes an ordinary grant, the same one an owner could issue by
 * hand from the table's Access tab. It composes with every other policy like
 * any grant: it cannot beat a DENY or unmask a column, and the decision panel
 * says so, because an owner who thinks "approve" means "unrestricted" is going
 * to be asked why the requester still sees asterisks.
 */
export default function AccessRequestsPage() {
  const [params, setParams] = useSearchParams();
  const notices = useRequestNotices();

  const inboxPending = notices.data?.inboxPending;
  const minePending = notices.data?.minePending;

  // No tab in the URL: open the one with something to do. Until the counts
  // arrive nothing is drawn, so the page does not flick from one tab to the
  // other under somebody's cursor.
  const asked = params.get('tab');
  const tab: Side | null =
    asked === 'inbox' || asked === 'mine'
      ? asked
      : notices.isLoading
        ? null
        : (inboxPending ?? 0) > 0
          ? 'inbox'
          : 'mine';

  function open(next: Side) {
    setParams({ tab: next }, { replace: false });
  }

  return (
    <div className="tw:flex tw:flex-col">
      <header className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-6 tw:py-5 tw:shadow-xs">
        <div className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-4">
          <div className="tw:flex tw:min-w-0 tw:items-start tw:gap-4">
            <span
              aria-hidden
              className="tw:flex tw:size-12 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-xl tw:bg-utility-brand-50">
              <Inbox01 className="tw:size-6 tw:text-fg-brand-primary" />
            </span>
            <div className="tw:min-w-0">
              <h1 className="tw:text-display-xs tw:font-semibold tw:text-primary">
                Access requests
              </h1>
              <p className="tw:mt-1 tw:max-w-2xl tw:text-pretty tw:text-sm tw:text-tertiary">
                Ask for a table from its page in the Catalog, or from a refusal on the Query
                page. The owner recorded in OpenMetadata decides — or a platform administrator
                when the table has none.
              </p>
            </div>
          </div>
          <Link
            className="tw:inline-flex tw:items-center tw:gap-1.5 tw:rounded-lg tw:border tw:border-primary tw:bg-primary tw:px-3 tw:py-2 tw:text-sm tw:font-semibold tw:text-secondary tw:shadow-xs tw:transition tw:hover:bg-primary_hover"
            to="/catalog">
            <Database01 className="tw:size-4 tw:text-fg-quaternary" />
            Find a table to request
          </Link>
        </div>

        <dl className="tw:mt-5 tw:flex tw:flex-wrap tw:gap-y-4">
          <HeaderStat first label="Waiting for your decision" value={inboxPending} />
          <HeaderStat label="Your open requests" value={minePending} />
        </dl>
      </header>

      <div className="tw:mt-4 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:shadow-xs">
        <div
          aria-label="Access requests"
          className="tw:flex tw:gap-2 tw:overflow-x-auto tw:overflow-y-hidden"
          role="tablist">
          <TabButton
            active={tab === 'inbox'}
            count={inboxPending}
            icon={Inbox01}
            label="Inbox"
            onPress={() => open('inbox')}
          />
          <TabButton
            active={tab === 'mine'}
            count={minePending}
            icon={Send01}
            label="My requests"
            onPress={() => open('mine')}
          />
        </div>
      </div>

      <div className="tw:mt-4">
        {tab === null ? (
          <p className="tw:text-sm tw:text-tertiary">Loading…</p>
        ) : (
          <RequestsTab key={tab} side={tab} />
        )}
      </div>
    </div>
  );
}

type Side = 'inbox' | 'mine';

const FILTERS: { value: RequestStatus | ''; label: string }[] = [
  { value: 'PENDING', label: 'Pending' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'WITHDRAWN', label: 'Withdrawn' },
  { value: '', label: 'All' },
];

/** One side's list and the request open beside it. */
function RequestsTab({ side }: { side: Side }) {
  const [params, setParams] = useSearchParams();
  const raw = params.get('status');
  const status: RequestStatus | '' =
    raw === null ? (side === 'inbox' ? 'PENDING' : '') : (raw as RequestStatus | '');

  const inbox = useQuery({
    queryKey: ['access-requests', 'inbox', status],
    queryFn: () => fetchRequestInbox(status || null),
    enabled: side === 'inbox',
  });
  const mine = useQuery({
    queryKey: ['access-requests', 'mine'],
    queryFn: () => fetchMyRequests(),
    enabled: side === 'mine',
  });
  const source = side === 'inbox' ? inbox : mine;
  const all = source.data;
  // The inbox is filtered by the server; one's own list is short, so here.
  const listed =
    side === 'mine' && all && status ? all.filter((r) => r.status === status) : all;

  const wanted = params.get('id');
  const selectedId = wanted ?? listed?.[0]?.id ?? null;
  const inList = listed?.find((r) => r.id === selectedId);
  // A notification can point at a request the current filter hides -- an ask
  // withdrawn a minute ago is no longer pending. Fetch that one on its own.
  const single = useQuery({
    queryKey: ['access-requests', 'one', selectedId],
    queryFn: () => fetchRequest(selectedId as string),
    enabled: selectedId !== null && listed !== undefined && inList === undefined,
    retry: false,
  });
  const selected = inList ?? single.data;

  function update(next: Record<string, string | null>) {
    const merged = new URLSearchParams(params);
    merged.set('tab', side);
    for (const [key, value] of Object.entries(next)) {
      if (value === null) {
        merged.delete(key);
      } else {
        merged.set(key, value);
      }
    }
    setParams(merged);
  }

  return (
    <div className="tw:grid tw:gap-4 tw:lg:grid-cols-[minmax(0,24rem)_minmax(0,1fr)]">
      <section
        aria-label={side === 'inbox' ? 'Inbox' : 'My requests'}
        className="tw:flex tw:flex-col tw:self-start tw:overflow-hidden tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
        <div
          aria-label="Status"
          className="tw:flex tw:gap-0.5 tw:overflow-x-auto tw:border-b tw:border-secondary tw:p-2"
          role="radiogroup">
          {FILTERS.map((filter) => {
            const active = filter.value === status;
            return (
              <button
                aria-checked={active}
                className={`tw:shrink-0 tw:cursor-pointer tw:rounded-md tw:px-2.5 tw:py-1 tw:text-xs tw:font-semibold tw:transition-colors ${
                  active
                    ? 'tw:bg-utility-brand-50 tw:text-brand-secondary'
                    : 'tw:text-tertiary tw:hover:bg-primary_hover tw:hover:text-secondary'
                }`}
                key={filter.label}
                onClick={() => update({ status: filter.value, id: null })}
                role="radio"
                type="button">
                {filter.label}
              </button>
            );
          })}
        </div>
        <RequestList
          empty={emptyText(side, status)}
          error={source.error}
          isLoading={source.isLoading}
          onSelect={(id) => update({ id })}
          requests={listed}
          selectedId={selectedId}
          side={side}
        />
      </section>

      <div className="tw:min-w-0">
        {selected ? (
          <RequestDetail request={selected} side={side} />
        ) : single.isError ? (
          <Placeholder>That request is not one you can see, or it no longer exists.</Placeholder>
        ) : source.isLoading || single.isLoading ? null : (
          <Placeholder>
            {listed && listed.length > 0
              ? 'Pick a request to read it.'
              : side === 'inbox'
                ? 'When someone asks for a table you own, it opens here with everything you need to decide.'
                : 'Your requests open here, with who decides them and what they said.'}
          </Placeholder>
        )}
      </div>
    </div>
  );
}

function emptyText(side: Side, status: RequestStatus | ''): string {
  if (side === 'inbox') {
    return status === 'PENDING'
      ? 'Nothing is waiting for your decision. Requests for tables you own appear here.'
      : 'No requests with this status.';
  }
  return status
    ? 'None of your requests has this status.'
    : 'You have not asked for anything. Open a table in the Catalog and choose Request access.';
}

/** A number under a label, divided from the one before by a thin rule. */
function HeaderStat({
  label,
  value,
  first = false,
}: {
  label: string;
  value: number | undefined;
  first?: boolean;
}) {
  return (
    <div className="tw:flex tw:items-stretch">
      {!first && (
        <span aria-hidden="true" className="tw:mx-6 tw:w-px tw:self-stretch tw:bg-border-secondary" />
      )}
      <div>
        <dt className="tw:text-sm tw:text-tertiary">{label}</dt>
        <dd className="tw:mt-1 tw:text-display-xs tw:font-semibold tw:tabular-nums tw:text-primary">
          {value ?? '–'}
        </dd>
      </div>
    </div>
  );
}

function TabButton({
  label,
  icon: Icon,
  count,
  active,
  onPress,
}: {
  label: string;
  icon: typeof Inbox01;
  count: number | undefined;
  active: boolean;
  onPress: () => void;
}) {
  return (
    <button
      aria-selected={active}
      className={`tw:relative tw:flex tw:shrink-0 tw:cursor-pointer tw:items-center tw:gap-2 tw:px-3 tw:py-3.5 tw:text-sm tw:font-semibold tw:transition-colors tw:focus-visible:outline-2 tw:focus-visible:-outline-offset-2 tw:focus-visible:outline-brand ${
        active ? 'tw:text-brand-secondary' : 'tw:text-tertiary tw:hover:text-primary'
      }`}
      onClick={onPress}
      role="tab"
      type="button">
      <Icon aria-hidden className="tw:size-4" />
      {label}
      {count !== undefined && count > 0 && (
        <span
          aria-label={`${count} open`}
          className={`tw:rounded-full tw:px-1.5 tw:py-0.5 tw:text-xs tw:tabular-nums ${
            active ? 'tw:bg-brand-solid tw:text-white' : 'tw:bg-secondary tw:text-tertiary'
          }`}>
          {countLabel(count)}
        </span>
      )}
      {active && (
        <span
          aria-hidden="true"
          className="tw:absolute tw:inset-x-3 tw:bottom-0 tw:h-0.5 tw:rounded-full tw:bg-brand-solid"
        />
      )}
    </button>
  );
}

function RequestList({
  requests,
  isLoading,
  error,
  empty,
  side,
  selectedId,
  onSelect,
}: {
  requests: AccessRequest[] | undefined;
  isLoading: boolean;
  error: unknown;
  empty: string;
  side: Side;
  selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  if (isLoading) {
    return <p className="tw:px-4 tw:py-6 tw:text-sm tw:text-tertiary">Loading…</p>;
  }
  if (error) {
    return (
      <p
        className="tw:m-3 tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
        role="alert">
        <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
        <span>{apiErrorMessage(error, 'The requests could not be loaded.')}</span>
      </p>
    );
  }
  if (!requests || requests.length === 0) {
    return (
      <div className="tw:flex tw:flex-col tw:items-center tw:gap-3 tw:px-6 tw:py-10 tw:text-center">
        <span className="tw:flex tw:size-12 tw:items-center tw:justify-center tw:rounded-full tw:bg-secondary">
          <Inbox01 className="tw:size-6 tw:text-fg-quaternary" />
        </span>
        <p className="tw:max-w-xs tw:text-sm tw:text-tertiary">{empty}</p>
      </div>
    );
  }
  return (
    <ul className="tw:max-h-[70vh] tw:overflow-y-auto">
      {requests.map((request) => {
        const active = request.id === selectedId;
        const status = STATUS[request.status] ?? { label: request.status, colour: 'gray' as const };
        return (
          <li className="tw:border-b tw:border-secondary tw:last:border-b-0" key={request.id}>
            <button
              aria-current={active ? 'true' : undefined}
              aria-label={`${tableName(request.assetFqn)}, ${status.label}${
                side === 'inbox' ? `, asked by ${request.requesterUsername}` : ''
              }`}
              className={`tw:flex tw:w-full tw:cursor-pointer tw:items-start tw:gap-3 tw:px-4 tw:py-3 tw:text-left tw:transition-colors ${
                active ? 'tw:bg-utility-brand-50' : 'tw:hover:bg-primary_hover'
              }`}
              onClick={() => onSelect(request.id)}
              type="button">
              {side === 'inbox' ? (
                <Initial name={request.requesterUsername} />
              ) : (
                <span className="tw:flex tw:size-8 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-secondary">
                  <Table className="tw:size-4 tw:text-fg-quaternary" />
                </span>
              )}
              <span className="tw:min-w-0 tw:flex-1">
                <span className="tw:flex tw:items-center tw:gap-2">
                  <span className="tw:truncate tw:text-sm tw:font-semibold tw:text-primary">
                    {tableName(request.assetFqn)}
                  </span>
                  <span className="tw:ml-auto tw:shrink-0 tw:text-xs tw:text-quaternary">
                    {relativeTime(request.createdAt)}
                  </span>
                </span>
                <span className="tw:mt-0.5 tw:block tw:truncate tw:text-xs tw:text-tertiary">
                  {side === 'inbox'
                    ? `${request.requesterUsername} · ${duration(request.requestedDays)}`
                    : request.assetFqn}
                </span>
                <span className="tw:mt-1.5 tw:flex tw:items-center tw:gap-1.5">
                  <Badge color={status.colour} size="sm" type="pill-color">
                    {status.label}
                  </Badge>
                  {request.purpose && (
                    <span className="tw:truncate tw:text-xs tw:text-quaternary">
                      {request.purpose}
                    </span>
                  )}
                </span>
              </span>
            </button>
          </li>
        );
      })}
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

/** The first letter of a name in a circle, as the asset page draws owners. */
function Initial({ name, size = 'sm' }: { name: string; size?: 'sm' | 'md' }) {
  return (
    <span
      aria-hidden
      className={`tw:flex tw:shrink-0 tw:items-center tw:justify-center tw:rounded-full tw:bg-utility-brand-50 tw:font-semibold tw:text-brand-secondary tw:uppercase ${
        size === 'md' ? 'tw:size-10 tw:text-md' : 'tw:size-8 tw:text-xs'
      }`}>
      {name.slice(0, 1)}
    </span>
  );
}

function Placeholder({ children }: { children: ReactNode }) {
  return (
    <div className="tw:flex tw:min-h-64 tw:flex-col tw:items-center tw:justify-center tw:gap-3 tw:rounded-xl tw:border tw:border-dashed tw:border-secondary tw:bg-primary tw:px-6 tw:py-10 tw:text-center">
      <Key01 className="tw:size-7 tw:text-fg-quaternary" />
      <p className="tw:max-w-sm tw:text-sm tw:text-tertiary">{children}</p>
    </div>
  );
}

/** Everything about one request, and what this reader can do with it. */
function RequestDetail({ request, side }: { request: AccessRequest; side: Side }) {
  const navigate = useNavigate();
  const pending = request.status === 'PENDING';
  const status = STATUS[request.status] ?? { label: request.status, colour: 'gray' as const };
  const table = tableName(request.assetFqn);
  const own = side === 'mine';

  return (
    <article
      aria-label={`Request for ${request.assetFqn}`}
      className="tw:overflow-hidden tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
      <div className="tw:flex tw:flex-wrap tw:items-start tw:gap-4 tw:border-b tw:border-secondary tw:px-6 tw:py-5">
        <span
          aria-hidden
          className="tw:flex tw:size-11 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-utility-brand-50">
          <Key01 className="tw:size-5 tw:text-fg-brand-primary" />
        </span>
        <div className="tw:min-w-0 tw:flex-1">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <h2 className="tw:truncate tw:text-lg tw:font-semibold tw:text-primary">{table}</h2>
            <Badge color={status.colour} size="sm" type="pill-color">
              {status.label}
            </Badge>
          </div>
          <Link
            className="tw:mt-0.5 tw:block tw:truncate tw:font-mono tw:text-xs tw:text-tertiary tw:hover:text-brand-secondary tw:hover:underline"
            to={`/catalog/${encodeURIComponent(request.assetFqn)}`}>
            {request.assetFqn}
          </Link>
        </div>
        <Button
          color="secondary"
          iconLeading={Table}
          onPress={() => navigate(`/catalog/${encodeURIComponent(request.assetFqn)}`)}
          size="sm">
          Open table
        </Button>
      </div>

      <div className="tw:flex tw:flex-col tw:gap-5 tw:px-6 tw:py-5">
        <dl className="tw:grid tw:gap-x-6 tw:gap-y-4 tw:sm:grid-cols-2 tw:xl:grid-cols-4">
          <Fact label="Requested by">
            <span className="tw:flex tw:items-center tw:gap-2">
              <Initial name={request.requesterUsername} />
              <span className="tw:truncate">{request.requesterUsername}</span>
            </span>
          </Fact>
          <Fact label="For">{duration(request.requestedDays)}</Fact>
          <Fact label="Purpose">{request.purpose ?? <span className="tw:text-quaternary">—</span>}</Fact>
          <Fact label="Asked">
            <span title={when(request.createdAt)}>{relativeTime(request.createdAt)}</span>
          </Fact>
        </dl>

        <div>
          <h3 className="tw:text-xs tw:font-semibold tw:tracking-wide tw:text-quaternary tw:uppercase">
            Reason
          </h3>
          <p className="tw:mt-1.5 tw:text-sm tw:whitespace-pre-wrap tw:text-primary">
            {request.reason}
          </p>
        </div>

        {(request.attemptedSql || request.deniedBy) && (
          <details className="tw:group tw:rounded-lg tw:border tw:border-secondary tw:text-sm">
            <summary className="tw:cursor-pointer tw:px-4 tw:py-2.5 tw:font-medium tw:text-secondary tw:hover:text-primary">
              What was refused
            </summary>
            <div className="tw:border-t tw:border-secondary tw:px-4 tw:py-3">
              {request.deniedBy && <p className="tw:text-xs tw:text-tertiary">{request.deniedBy}</p>}
              {request.attemptedSql && (
                <pre className="tw:mt-2 tw:overflow-auto tw:rounded-lg tw:bg-secondary tw:p-3 tw:font-mono tw:text-xs tw:whitespace-pre-wrap tw:text-primary">
                  {request.attemptedSql}
                </pre>
              )}
            </div>
          </details>
        )}

        <div>
          <h3 className="tw:text-xs tw:font-semibold tw:tracking-wide tw:text-quaternary tw:uppercase">
            Activity
          </h3>
          <ol className="tw:mt-3 tw:flex tw:flex-col">
            <Step
              icon={Send01}
              last={false}
              tone="brand"
              when={request.createdAt}>
              <b className="tw:text-primary">{request.requesterUsername}</b> asked for access
            </Step>
            {pending ? (
              <Step icon={Clock} last tone={request.stranded ? 'error' : 'warning'} when={null}>
                <b className="tw:text-primary">
                  {request.stranded ? 'Nobody can decide this yet' : 'Waiting for a decision'}
                </b>
                <span className="tw:mt-0.5 tw:block tw:text-xs tw:text-tertiary">
                  {describeApprovers(request.approvers, request.stranded)}
                </span>
              </Step>
            ) : (
              <Step
                icon={
                  request.status === 'APPROVED'
                    ? Check
                    : request.status === 'REJECTED'
                      ? XClose
                      : Send01
                }
                last
                note={request.decisionNote}
                tone={
                  request.status === 'APPROVED'
                    ? 'success'
                    : request.status === 'REJECTED'
                      ? 'error'
                      : 'gray'
                }
                when={request.decidedAt}>
                <b className="tw:text-primary">{request.decidedBy ?? 'Someone'}</b>{' '}
                {request.status === 'WITHDRAWN'
                  ? 'withdrew the request'
                  : request.status === 'APPROVED'
                    ? 'approved it'
                    : 'rejected it'}
              </Step>
            )}
          </ol>
        </div>
      </div>

      {pending && !own && request.mayDecide && <Decide request={request} />}
      {pending && own && <Withdraw request={request} />}
    </article>
  );
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="tw:min-w-0">
      <dt className="tw:text-xs tw:text-tertiary">{label}</dt>
      <dd className="tw:mt-1 tw:flex tw:min-h-8 tw:items-center tw:text-sm tw:font-medium tw:text-primary">
        {children}
      </dd>
    </div>
  );
}

const TONE = {
  brand: 'tw:bg-utility-brand-50 tw:text-fg-brand-primary',
  warning: 'tw:bg-utility-warning-50 tw:text-fg-warning-primary',
  success: 'tw:bg-utility-success-50 tw:text-fg-success-primary',
  error: 'tw:bg-utility-error-50 tw:text-fg-error-primary',
  gray: 'tw:bg-secondary tw:text-fg-quaternary',
} as const;

/** One event on the request's timeline, with a line down to the next. */
function Step({
  icon: Icon,
  tone,
  when: at,
  note,
  last,
  children,
}: {
  icon: typeof Check;
  tone: keyof typeof TONE;
  when: string | null;
  note?: string | null;
  last: boolean;
  children: ReactNode;
}) {
  return (
    <li className="tw:relative tw:flex tw:gap-3 tw:pb-4 tw:last:pb-0">
      {!last && (
        <span aria-hidden className="tw:absolute tw:top-8 tw:bottom-0 tw:left-3.75 tw:w-px tw:bg-border-secondary" />
      )}
      <span className={`tw:flex tw:size-8 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-full ${TONE[tone]}`}>
        <Icon className="tw:size-4" />
      </span>
      <div className="tw:min-w-0 tw:pt-1.5 tw:text-sm tw:text-secondary">
        <p>{children}</p>
        {at && (
          <p className="tw:mt-0.5 tw:text-xs tw:text-quaternary" title={when(at)}>
            {relativeTime(at)}
          </p>
        )}
        {note && (
          <p className="tw:mt-2 tw:rounded-lg tw:bg-secondary tw:px-3 tw:py-2 tw:text-sm tw:whitespace-pre-wrap tw:text-secondary">
            {note}
          </p>
        )}
      </div>
    </li>
  );
}

function Decide({ request }: { request: AccessRequest }) {
  const queryClient = useQueryClient();
  const [note, setNote] = useState('');
  const done = () => queryClient.invalidateQueries({ queryKey: ['access-requests'] });

  const approve = useMutation({
    mutationFn: () =>
      approveRequest(request.id, {
        // The grant runs as long as was asked -- the "For" line above says how
        // long -- so approving is one decision, not a second form to fill in.
        days: request.requestedDays,
        note: note.trim() || null,
      }),
    onSuccess: done,
  });
  const reject = useMutation({
    mutationFn: () => rejectRequest(request.id, note.trim()),
    onSuccess: done,
  });

  const busy = approve.isPending || reject.isPending;
  const failure = approve.error ?? reject.error;

  return (
    <div className="tw:flex tw:flex-col tw:gap-4 tw:border-t tw:border-secondary tw:bg-secondary tw:px-6 tw:py-5">
      <div className="tw:flex tw:items-center tw:justify-between tw:gap-3">
        <h3 className="tw:text-sm tw:font-semibold tw:text-primary">Your decision</h3>
      </div>

      <p className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-blue-50 tw:px-3 tw:py-2 tw:text-xs tw:text-secondary">
        <InfoCircle className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-brand-primary" />
        <span>
          Approving issues a grant that composes with every policy like any other: it cannot
          override a DENY or lift a mask, so an approved requester may still see masked columns
          and filtered rows.
        </span>
      </p>

      <textarea
        aria-label="Note to the requester"
        className={`${FIELD} tw:min-h-20 tw:resize-y tw:bg-primary`}
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
          isDisabled={busy}
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
    <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-end tw:gap-3 tw:border-t tw:border-secondary tw:bg-secondary tw:px-6 tw:py-4">
      {withdraw.isError ? (
        <span className="tw:mr-auto tw:text-sm tw:text-error-primary" role="alert">
          {apiErrorMessage(withdraw.error, 'The request was not withdrawn.')}
        </span>
      ) : (
        <span className="tw:mr-auto tw:text-xs tw:text-tertiary">
          Changed your mind? Withdrawing takes it out of the owner&apos;s inbox.
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
