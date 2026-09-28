import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import {
  AlertTriangle,
  Check,
  ChevronLeft,
  Clock,
  Copy01,
  Database01,
  Expand06,
  Inbox01,
  InfoCircle,
  Key01,
  PlayCircle,
  Plus,
  SearchLg,
  Send01,
  Settings01,
  ShieldTick,
  Table,
  XClose,
} from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { relativeTime } from '../../components/widgets';
import { apiErrorMessage } from '../../api/client';
import {
  approveRequest,
  completeRequest,
  declineRequest,
  describeApprovers,
  describeOnReject,
  describeRule,
  describeSeat,
  describePeople,
  describeTables,
  fetchMyRequests,
  fetchRequest,
  fetchRequestByTicket,
  fetchRequestInbox,
  isPreauthorization,
  matchesSearch,
  OPEN_STATUSES,
  ticketNumber,
  rejectRequest,
  startRequest,
  withdrawRequest,
  type AccessRequest,
  type Fulfilment,
  type RequestStatus,
  type StageStatus,
  type StageView,
} from '../../api/accessRequests';
import { joinOf, stepsOf } from '../../api/accessWorkflows';
import { fetchPolicies } from '../../api/policies';
import { useAuthStore } from '../../auth/authStore';
import { FIELD } from '../policies/controls';
import { PurposeLabel, PurposeName } from '../policies/purposePickers';
import { countLabel, tableName, useRequestNotices } from './useRequestNotices';
import { AccessColumns, ConflictList, RequestReview, useAccessReview } from './RequestReview';

/**
 * Asking for a table, and moving the ask through its workflow (FR-7).
 *
 * <p>Two tabs, because most people are on both sides and the two lists answer
 * different questions: <b>Inbox</b> is what waits for this person -- a stage
 * that asks them, or an approved request they configure -- and <b>My
 * requests</b> is what they asked for. Each carries its count of what is
 * still open, the same numbers the bell and the rail show.
 *
 * <p>Laid out as OpenMetadata lays out its tasks: the list on the left, the
 * request that is open on the right, so an owner working through five asks
 * never loses their place. The selection lives in the URL, which is what lets
 * a notification open one request directly.
 *
 * <p>A request walks its workflow's stages -- those of one step side by side,
 * steps one after another -- and the timeline draws each with who was asked
 * and what they answered. Approving grants nothing by itself: once every stage
 * has said yes, whoever configures it writes the grant, or points at the
 * policy they changed, and says how long. That grant composes with every
 * other policy like any grant: it cannot beat a DENY or unmask a column, and
 * the panels say so, because an owner who thinks "approve" means
 * "unrestricted" is going to be asked why the requester still sees asterisks.
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
                page. The table&apos;s access workflow says who approves, step by step, and who
                sets up the access once they have.
              </p>
            </div>
          </div>
          <div className="tw:flex tw:flex-wrap tw:gap-2">
            <Link
              className="tw:inline-flex tw:items-center tw:gap-1.5 tw:rounded-lg tw:bg-brand-solid tw:px-3 tw:py-2 tw:text-sm tw:font-semibold tw:text-white tw:shadow-xs tw:transition tw:hover:bg-brand-solid_hover"
              title="Ask for one or several tables with one reason"
              to="/requests/new">
              <Plus className="tw:size-4" />
              New request
            </Link>
            <Link
              className="tw:inline-flex tw:items-center tw:gap-1.5 tw:rounded-lg tw:border tw:border-primary tw:bg-primary tw:px-3 tw:py-2 tw:text-sm tw:font-semibold tw:text-secondary tw:shadow-xs tw:transition tw:hover:bg-primary_hover"
              title="Ask ahead of need for a class of tables, for a group"
              to="/requests/preauthorize">
              <ShieldTick className="tw:size-4 tw:text-fg-quaternary" />
              Pre-authorize
            </Link>
            <Link
              className="tw:inline-flex tw:items-center tw:gap-1.5 tw:rounded-lg tw:border tw:border-primary tw:bg-primary tw:px-3 tw:py-2 tw:text-sm tw:font-semibold tw:text-secondary tw:shadow-xs tw:transition tw:hover:bg-primary_hover"
              to="/catalog">
              <Database01 className="tw:size-4 tw:text-fg-quaternary" />
              Find a table to request
            </Link>
          </div>
        </div>

        <dl className="tw:mt-5 tw:flex tw:flex-wrap tw:gap-y-4">
          <HeaderStat first label="Waiting for you" value={inboxPending} />
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

/**
 * One request on a page of its own, at /requests/REQ-000042: the address to
 * paste into an email or a change ticket. Found by ticket number with the
 * same visibility as by id -- somebody who could not open it from the list
 * reads "not found" here too -- and drawn by the same panel as the list's.
 */
export function RequestPage() {
  const { ticket = '' } = useParams();
  const me = useAuthStore((state) => state.user?.username);
  const found = useQuery({
    // Under 'access-requests', so deciding or configuring it refreshes it.
    queryKey: ['access-requests', 'page', ticket],
    queryFn: () => fetchRequestByTicket(ticket),
    retry: false,
  });
  const request = found.data;
  const side: Side =
    request && me && request.requesterUsername.toLowerCase() === me.toLowerCase()
      ? 'mine'
      : 'inbox';

  return (
    <div className="tw:flex tw:flex-col tw:gap-4">
      <nav aria-label="Breadcrumb" className="tw:flex tw:items-center tw:gap-1.5 tw:text-sm">
        <Link
          className="tw:inline-flex tw:items-center tw:gap-1 tw:font-semibold tw:text-tertiary tw:hover:text-brand-secondary"
          to={request ? `/requests?tab=${side}&status=&id=${request.id}` : '/requests'}>
          <ChevronLeft aria-hidden className="tw:size-4" />
          Access requests
        </Link>
        <span aria-hidden className="tw:text-quaternary">/</span>
        <span aria-current="page" className="tw:font-mono tw:font-semibold tw:text-secondary">
          {request?.ticket ?? ticket}
        </span>
      </nav>
      {request ? (
        <RequestDetail page request={request} side={side} />
      ) : found.isError ? (
        <Placeholder>
          {ticketNumber(ticket) === null
            ? `“${ticket}” is not a ticket number. They look like REQ-000042.`
            : `There is no request ${ticket} that you can see.`}
        </Placeholder>
      ) : (
        <p className="tw:text-sm tw:text-tertiary">Loading…</p>
      )}
    </div>
  );
}

type Side = 'inbox' | 'mine';

/** A status, every open one at once, or everything. */
type Filter = RequestStatus | 'OPEN' | '';

const FILTERS: { value: Filter; label: string }[] = [
  { value: 'OPEN', label: 'Open' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'WITHDRAWN', label: 'Withdrawn' },
  { value: '', label: 'All' },
];

function matches(filter: Filter, status: RequestStatus): boolean {
  if (filter === '') return true;
  if (filter === 'OPEN') return OPEN_STATUSES.includes(status);
  return filter === status;
}

/** One side's list and the request open beside it. */
function RequestsTab({ side }: { side: Side }) {
  const [params, setParams] = useSearchParams();
  const raw = params.get('status');
  const status: Filter = raw === null ? (side === 'inbox' ? 'OPEN' : '') : (raw as Filter);

  // "Open" is three statuses, which the server filters one at a time; the
  // inbox is fetched whole for it and narrowed here.
  const serverStatus = status === 'OPEN' || status === '' ? null : status;
  const inbox = useQuery({
    queryKey: ['access-requests', 'inbox', serverStatus],
    queryFn: () => fetchRequestInbox(serverStatus),
    enabled: side === 'inbox',
  });
  const mine = useQuery({
    queryKey: ['access-requests', 'mine'],
    queryFn: () => fetchMyRequests(),
    enabled: side === 'mine',
  });
  const source = side === 'inbox' ? inbox : mine;
  const all = source.data;
  const search = params.get('q') ?? '';
  // What is typed is only a draft until Enter: a search per key would jump the
  // list, and the selection with it, under the reader's hands.
  const [draft, setDraft] = useState(search);
  useEffect(() => setDraft(search), [search]);
  const filtered = all?.filter((r) => matches(status, r.status) && matchesSearch(r, search));
  // A ticket number is often quoted from an email, for a request outside this
  // filter or older than the list goes back. Asked for on its own; the server
  // answers only what the reader could open anyway.
  const typedTicket = ticketNumber(search);
  const byTicket = useQuery({
    queryKey: ['access-requests', 'ticket', typedTicket],
    queryFn: () => fetchRequestByTicket(String(typedTicket)),
    enabled: typedTicket !== null && filtered !== undefined && filtered.length === 0,
    retry: false,
  });
  const listed =
    filtered && filtered.length === 0 && typedTicket !== null && byTicket.data
      ? [byTicket.data]
      : filtered;

  const wanted = params.get('id');
  const selectedId = wanted ?? listed?.[0]?.id ?? null;
  const inList = listed?.find((r) => r.id === selectedId);
  // A notification can point at a request the current filter hides -- an ask
  // withdrawn a minute ago is no longer open. Fetch that one on its own.
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

  function setSearch(value: string) {
    const merged = new URLSearchParams(params);
    merged.set('tab', side);
    merged.delete('id');
    if (value.trim()) {
      merged.set('q', value);
    } else {
      merged.delete('q');
    }
    // Searching again is not navigation: one history entry, not one per search.
    setParams(merged, { replace: true });
  }

  return (
    <div className="tw:grid tw:gap-4 tw:lg:grid-cols-[minmax(0,24rem)_minmax(0,1fr)]">
      <section
        aria-label={side === 'inbox' ? 'Inbox' : 'My requests'}
        className="tw:flex tw:flex-col tw:self-start tw:overflow-hidden tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs tw:lg:sticky tw:lg:top-20">
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
        <form
          className="tw:flex tw:items-center tw:gap-2 tw:border-b tw:border-secondary tw:px-3 tw:py-2"
          onSubmit={(event) => {
            event.preventDefault();
            setSearch(draft);
          }}
          role="search">
          <SearchLg aria-hidden className="tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
          <input
            aria-label="Search requests"
            className="tw:min-w-0 tw:flex-1 tw:bg-transparent tw:text-sm tw:text-primary tw:outline-none tw:placeholder:text-placeholder"
            onChange={(event) => {
              setDraft(event.target.value);
              // Emptying the box (or its clear button) is the one change that
              // needs no Enter: the whole list comes back.
              if (!event.target.value.trim() && search) setSearch('');
            }}
            placeholder="Ticket, table or person — Enter to search"
            type="search"
            value={draft}
          />
        </form>
        <RequestList
          empty={
            search.trim()
              ? `Nothing here matches “${search.trim()}”.`
              : emptyText(side, status)
          }
          error={source.error}
          isLoading={source.isLoading || byTicket.isLoading}
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
                ? 'When a request waits for you, it opens here with everything you need to decide.'
                : 'Your requests open here, with who decides them and what they said.'}
          </Placeholder>
        )}
      </div>
    </div>
  );
}

function emptyText(side: Side, status: Filter): string {
  if (side === 'inbox') {
    return status === 'OPEN'
      ? 'Nothing is waiting for you. Requests you approve or configure appear here.'
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

/** The ticket number, and a button that copies it for an email or a change ticket. */
function TicketNumber({ ticket }: { ticket: string }) {
  const [copied, setCopied] = useState(false);
  if (!ticket) return null;
  return (
    <span className="tw:inline-flex tw:items-center tw:gap-1 tw:rounded-md tw:bg-secondary tw:px-1.5 tw:py-0.5">
      <Link
        className="tw:font-mono tw:text-xs tw:font-semibold tw:text-secondary tw:hover:text-brand-secondary tw:hover:underline"
        title="Ticket number — opens it on its own page"
        to={`/requests/${ticket}`}>
        {ticket}
      </Link>
      <button
        aria-label={copied ? `Copied ${ticket}` : `Copy ${ticket}`}
        className="tw:cursor-pointer tw:rounded tw:p-0.5 tw:text-fg-quaternary tw:hover:text-fg-secondary"
        onClick={() => {
          // Clipboard is missing on plain http and in some browsers; the
          // number is on screen to select by hand either way.
          void navigator.clipboard
            ?.writeText(ticket)
            .then(() => setCopied(true))
            .catch(() => undefined);
        }}
        type="button">
        {copied ? <Check aria-hidden className="tw:size-3.5" /> : <Copy01 aria-hidden className="tw:size-3.5" />}
      </button>
    </span>
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
        const status = statusOf(request.status);
        const progress = stepProgress(request);
        const yours = side === 'inbox' && (request.mayDecide || request.mayConfigure);
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
                  <span className="tw:font-mono">{request.ticket}</span>
                  {' · '}
                  {side === 'inbox'
                    ? `${request.requesterUsername} · ${duration(request.requestedDays)}`
                    : request.assetFqn}
                </span>
                <span className="tw:mt-1.5 tw:flex tw:items-center tw:gap-1.5">
                  <Badge color={status.colour} size="sm" type="pill-color">
                    {status.label}
                  </Badge>
                  {isPreauthorization(request) && (
                    <Badge color="brand" size="sm" type="pill-color">
                      Pre-authorization
                    </Badge>
                  )}
                  {progress && (
                    <span className="tw:shrink-0 tw:text-xs tw:text-quaternary">{progress}</span>
                  )}
                  {yours && (
                    <span className="tw:shrink-0 tw:text-xs tw:font-semibold tw:text-brand-secondary">
                      {request.mayConfigure ? 'You configure' : 'Your turn'}
                    </span>
                  )}
                  {!progress && !yours && request.purpose && (
                    <span className="tw:truncate tw:text-xs tw:text-quaternary">
                      <PurposeName value={request.purpose} />
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

type Colour = 'warning' | 'success' | 'error' | 'gray' | 'brand' | 'blue';

const STATUS: Record<RequestStatus, { label: string; colour: Colour }> = {
  PENDING: { label: 'Pending', colour: 'warning' },
  APPROVED: { label: 'Approved', colour: 'brand' },
  IN_PROGRESS: { label: 'Configuring', colour: 'blue' },
  COMPLETED: { label: 'Completed', colour: 'success' },
  REJECTED: { label: 'Rejected', colour: 'error' },
  WITHDRAWN: { label: 'Withdrawn', colour: 'gray' },
};

function statusOf(status: RequestStatus): { label: string; colour: Colour } {
  return STATUS[status] ?? { label: status, colour: 'gray' };
}

const STAGE_STATUS: Record<StageStatus, { label: string; colour: Colour }> = {
  WAITING: { label: 'Not yet', colour: 'gray' },
  OPEN: { label: 'Waiting', colour: 'warning' },
  APPROVED: { label: 'Approved', colour: 'success' },
  REJECTED: { label: 'Rejected', colour: 'error' },
  CLOSED: { label: 'Closed', colour: 'gray' },
};

/** "Step 1 of 2", while a request with more than one step is waiting on one. */
function stepProgress(request: AccessRequest): string | null {
  const stages = request.stages ?? [];
  if (request.status !== 'PENDING' || stages.length === 0) return null;
  const steps = new Set(stages.map((s) => s.step)).size;
  if (steps < 2 || !request.currentStep) return null;
  return `Step ${request.currentStep} of ${steps}`;
}

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
function RequestDetail({
  request,
  side,
  page = false,
}: {
  request: AccessRequest;
  side: Side;
  /** Drawn on its own page, where "open on its own page" would lead nowhere. */
  page?: boolean;
}) {
  const navigate = useNavigate();
  const status = statusOf(request.status);
  const table = tableName(request.assetFqn);
  const own = side === 'mine';
  const open = OPEN_STATUSES.includes(request.status);
  const configuring = request.status === 'APPROVED' || request.status === 'IN_PROGRESS';
  const preauth = isPreauthorization(request);

  return (
    <article
      aria-label={`Request for ${request.assetFqn}`}
      className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs">
      <div
        className="tw:sticky tw:top-16 tw:z-20 tw:flex tw:flex-wrap tw:items-start tw:gap-4 tw:rounded-t-xl tw:border-b tw:border-secondary tw:bg-primary tw:px-6 tw:py-4"
        data-testid="request-header">
        <span
          aria-hidden
          className="tw:flex tw:size-11 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-utility-brand-50">
          {preauth ? (
            <ShieldTick className="tw:size-5 tw:text-fg-brand-primary" />
          ) : (
            <Key01 className="tw:size-5 tw:text-fg-brand-primary" />
          )}
        </span>
        <div className="tw:min-w-0 tw:flex-1">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <h2 className="tw:truncate tw:text-lg tw:font-semibold tw:text-primary">{table}</h2>
            <Badge color={status.colour} size="sm" type="pill-color">
              {status.label}
            </Badge>
            {preauth && (
              <Badge color="brand" size="sm" type="pill-color">
                Pre-authorization
              </Badge>
            )}
            <TicketNumber ticket={request.ticket} />
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
          {preauth ? 'Open in catalog' : 'Open table'}
        </Button>
        {!page && request.ticket && (
          <Link
            aria-label={`Open ${request.ticket} on its own page`}
            className="tw:inline-flex tw:items-center tw:gap-1.5 tw:rounded-lg tw:border tw:border-primary tw:bg-primary tw:px-3 tw:py-2 tw:text-sm tw:font-semibold tw:text-secondary tw:shadow-xs tw:transition tw:hover:bg-primary_hover"
            title="Open on its own page — a link to share"
            to={`/requests/${request.ticket}`}>
            <Expand06 aria-hidden className="tw:size-4 tw:text-fg-quaternary" />
            Full page
          </Link>
        )}
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
          <Fact label="Purpose">
            {request.purpose ? (
              <PurposeLabel value={request.purpose} />
            ) : (
              <span className="tw:text-quaternary">—</span>
            )}
          </Fact>
          <Fact label="Asked">
            <span title={when(request.createdAt)}>{relativeTime(request.createdAt)}</span>
          </Fact>
          {request.reference && (
            <Fact label="Reference">
              <span className="tw:font-mono tw:break-all">{request.reference}</span>
            </Fact>
          )}
        </dl>

        {preauth && request.target && (
          <dl
            aria-label="What it asks for"
            className="tw:grid tw:grid-cols-[7rem_minmax(0,1fr)] tw:gap-x-3 tw:gap-y-2 tw:rounded-lg tw:bg-secondary tw:px-4 tw:py-3 tw:text-sm">
            <dt className="tw:text-tertiary">Tables where</dt>
            <dd className="tw:min-w-0 tw:break-words tw:text-primary">{describeTables(request.target)}</dd>
            <dt className="tw:text-tertiary">For</dt>
            <dd className="tw:min-w-0 tw:break-words tw:text-primary">{describePeople(request.target)}</dd>
          </dl>
        )}

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

        {!own && open && <RequestReview requestId={request.id} />}

        <div>
          <div className="tw:flex tw:flex-wrap tw:items-baseline tw:justify-between tw:gap-2">
            <h3 className="tw:text-xs tw:font-semibold tw:tracking-wide tw:text-quaternary tw:uppercase">
              Activity
            </h3>
            {(request.workflowName || request.templateName) && (
              <span className="tw:text-xs tw:text-tertiary">
                {request.templateName && (
                  <>
                    Form <b className="tw:font-semibold tw:text-secondary">{request.templateName}</b>
                  </>
                )}
                {request.templateName && request.workflowName && ' · '}
                {request.workflowName && (
                  <>
                    Workflow <b className="tw:font-semibold tw:text-secondary">{request.workflowName}</b>
                  </>
                )}
              </span>
            )}
          </div>
          <Timeline request={request} />
        </div>
      </div>

      {request.status === 'PENDING' && !own && request.mayDecide && <Decide request={request} />}
      {configuring && !own && request.mayConfigure && <Configure request={request} />}
      {open && own && <Withdraw request={request} />}
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

/**
 * What happened, in order: the ask, each step of the workflow with its
 * stages side by side, then the configuring.
 *
 * A request from before workflows has no stages; it drew one decision, and
 * still does.
 */
function Timeline({ request }: { request: AccessRequest }) {
  const stages = request.stages ?? [];
  const steps = stepsOf(stages);
  const legacy = stages.length === 0;
  const rows: ReactNode[] = [];

  rows.push(
    <Step icon={Send01} key="asked" tone="brand" when={request.createdAt}>
      <b className="tw:text-primary">{request.requesterUsername}</b> asked for access
    </Step>
  );

  steps.forEach((group, i) => {
    const state = stepState(group);
    rows.push(
      <Step
        icon={STEP_ICON[state]}
        key={`step-${group[0].step}`}
        tone={STEP_TONE[state]}
        when={null}>
        <b className="tw:text-primary">
          {steps.length > 1 ? `Step ${i + 1}` : 'Approval'}
          {group.length > 1 ? ` · in parallel${joinOf(group) === 'ANY' ? ', any one is enough' : ''}` : ''}
        </b>
        <span className="tw:mt-2 tw:flex tw:flex-col tw:gap-2">
          {group.map((stage) => (
            <StageCard key={stage.idx} stage={stage} />
          ))}
        </span>
      </Step>
    );
  });

  switch (request.status) {
    case 'PENDING':
      if (legacy) {
        rows.push(
          <Step icon={Clock} key="waiting" tone={request.stranded ? 'error' : 'warning'} when={null}>
            <b className="tw:text-primary">
              {request.stranded ? 'Nobody can decide this yet' : 'Waiting for a decision'}
            </b>
            <span className="tw:mt-0.5 tw:block tw:text-xs tw:text-tertiary">
              {describeApprovers(request.approvers, request.stranded)}
            </span>
          </Step>
        );
      } else if (request.stranded) {
        rows.push(
          <Step icon={AlertTriangle} key="stranded" tone="error" when={null}>
            <b className="tw:text-primary">Nobody can decide this yet</b>
            <span className="tw:mt-0.5 tw:block tw:text-xs tw:text-tertiary">
              Nobody but the requester could answer the stage that is open. Change the table&apos;s
              workflow, record an owner, or ask another platform administrator.
            </span>
          </Step>
        );
      }
      break;
    case 'APPROVED':
    case 'IN_PROGRESS':
    case 'COMPLETED':
      if (legacy && request.decidedBy) {
        rows.push(
          <Step icon={Check} key="approved" note={request.decisionNote} tone="success" when={request.decidedAt}>
            <b className="tw:text-primary">{request.decidedBy}</b> approved it
          </Step>
        );
      }
      rows.push(<Configuring key="configuring" request={request} />);
      break;
    case 'REJECTED':
      rows.push(
        request.completedBy ? (
          <Step
            icon={XClose}
            key="declined"
            note={request.fulfilmentNote}
            tone="error"
            when={request.completedAt ?? null}>
            <b className="tw:text-primary">{request.completedBy}</b> declined to configure it
          </Step>
        ) : (
          <Step
            icon={XClose}
            key="rejected"
            note={legacy ? request.decisionNote : null}
            tone="error"
            when={request.decidedAt}>
            <b className="tw:text-primary">{request.decidedBy ?? 'Someone'}</b> rejected it
          </Step>
        )
      );
      break;
    case 'WITHDRAWN':
      rows.push(
        <Step
          icon={Send01}
          key="withdrawn"
          tone="gray"
          when={request.completedAt ?? request.decidedAt}>
          <b className="tw:text-primary">{request.requesterUsername}</b> withdrew the request
        </Step>
      );
      break;
  }

  return (
    <ol className="tw:mt-3 tw:flex tw:flex-col">
      {rows.map((row, i) => (
        <LastContext.Provider key={i} value={i === rows.length - 1}>
          {row}
        </LastContext.Provider>
      ))}
    </ol>
  );
}

/** Whether a timeline row is the last, so it draws no line down to the next. */
const LastContext = createContext(false);

type StepState = 'passed' | 'failed' | 'stuck' | 'open' | 'waiting';

/**
 * Where one step stands, by its join. A step nobody can answer is stuck, not
 * refused: it keeps a warning, and the cross is kept for a real rejection.
 */
function stepState(group: StageView[]): StepState {
  const anyOne = joinOf(group) === 'ANY';
  const passed = anyOne
    ? group.some((s) => s.status === 'APPROVED')
    : group.every((s) => s.status === 'APPROVED');
  const failed = anyOne
    ? group.every((s) => s.status === 'REJECTED')
    : group.some((s) => s.status === 'REJECTED');
  if (passed) return 'passed';
  if (failed) return 'failed';
  const open = group.filter((s) => s.status === 'OPEN');
  if (open.length === 0) return 'waiting';
  // All must pass: one stranded stage holds it. Any one: all of them must be.
  const stuck = anyOne ? open.every((s) => s.stranded) : open.some((s) => s.stranded);
  return stuck ? 'stuck' : 'open';
}

const STEP_ICON: Record<StepState, typeof Check> = {
  passed: Check,
  failed: XClose,
  stuck: AlertTriangle,
  open: Clock,
  waiting: Clock,
};

const STEP_TONE: Record<StepState, keyof typeof TONE> = {
  passed: 'success',
  failed: 'error',
  stuck: 'warning',
  open: 'warning',
  waiting: 'gray',
};

/** One stage: its rule, who was asked, and every answer. */
function StageCard({ stage }: { stage: StageView }) {
  const status = STAGE_STATUS[stage.status] ?? { label: stage.status, colour: 'gray' as const };
  const asked = stage.pool.length;
  return (
    <span
      aria-label={`Stage ${stage.name}`}
      className="tw:block tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2.5"
      role="group">
      <span className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <span className="tw:text-sm tw:font-semibold tw:text-primary">{stage.name}</span>
        <Badge color={status.colour} size="sm" type="pill-color">
          {status.label}
        </Badge>
        {stage.status !== 'WAITING' && (
          <span className="tw:ml-auto tw:text-xs tw:tabular-nums tw:text-tertiary">
            {stage.approvals} of {stage.needed} approval{stage.needed === 1 ? '' : 's'}
          </span>
        )}
      </span>
      <span className="tw:mt-0.5 tw:block tw:text-xs tw:text-tertiary">
        {describeRule(stage.rule, stage.minApprovals, asked || undefined)} ·{' '}
        {describeOnReject(stage.onReject, stage.rule, stage.minApprovals)}
      </span>
      <span className="tw:mt-1 tw:block tw:text-xs tw:text-secondary">
        {stage.status === 'WAITING' || asked === 0 ? (
          <>Asks {stage.approvers.map(describeSeat).join(', ')}</>
        ) : (
          <>Asked {stage.pool.map((m) => m.username).join(', ')}</>
        )}
      </span>
      {stage.fallback && (
        <span className="tw:mt-1 tw:block tw:text-xs tw:text-tertiary">
          Nobody the stage names could answer it, so the platform administrators were asked.
        </span>
      )}
      {stage.status === 'CLOSED' && stage.join === 'ANY' && (
        <span className="tw:mt-1 tw:block tw:text-xs tw:text-tertiary">
          Not needed any more: another stage of this step passed first.
        </span>
      )}
      {stage.stranded && stage.status === 'OPEN' && (
        <span className="tw:mt-1 tw:flex tw:items-start tw:gap-1.5 tw:text-xs tw:text-error-primary">
          <AlertTriangle className="tw:mt-0.5 tw:size-3.5 tw:shrink-0" />
          Too few people can answer for this stage ever to pass; a platform administrator can answer
          for it.
        </span>
      )}
      {stage.votes.length > 0 && (
        <span className="tw:mt-2 tw:flex tw:flex-col tw:gap-1.5 tw:border-t tw:border-secondary tw:pt-2">
          {stage.votes.map((vote) => {
            const approved = vote.decision === 'APPROVE';
            return (
              <span className="tw:flex tw:items-start tw:gap-2 tw:text-xs" key={`${vote.voter}-${vote.votedAt}`}>
                <span
                  className={`tw:mt-0.5 tw:flex tw:size-4 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-full ${
                    approved ? TONE.success : TONE.error
                  }`}>
                  {approved ? <Check className="tw:size-3" /> : <XClose className="tw:size-3" />}
                </span>
                <span className="tw:min-w-0">
                  <b className="tw:text-primary">{vote.voter}</b>{' '}
                  {approved ? 'approved' : 'rejected'}
                  {vote.override ? ' as administrator' : ''}{' '}
                  <span className="tw:text-quaternary" title={when(vote.votedAt)}>
                    {relativeTime(vote.votedAt)}
                  </span>
                  {vote.note && (
                    <span className="tw:mt-0.5 tw:block tw:whitespace-pre-wrap tw:text-secondary">
                      {vote.note}
                    </span>
                  )}
                </span>
              </span>
            );
          })}
        </span>
      )}
    </span>
  );
}

/** After the approvers: who configures it, who took it, and how it was done. */
function Configuring({ request }: { request: AccessRequest }) {
  if (request.status === 'APPROVED') {
    const pool = request.configurerPool ?? [];
    const seats = request.configurers ?? [];
    return (
      <Step icon={Settings01} tone="brand" when={null}>
        <b className="tw:text-primary">Approved — waiting to be configured</b>
        <span className="tw:mt-0.5 tw:block tw:text-xs tw:text-tertiary">
          {pool.length > 0
            ? `Configured by ${pool.map((m) => m.username).join(', ')}.`
            : seats.length > 0
              ? `Configured by ${seats.map(describeSeat).join(', ')}.`
              : 'Configured by the owners of the table or its data custodian.'}
          {request.configurersFallback &&
            ' Nobody the workflow names could, so the platform administrators do.'}
        </span>
      </Step>
    );
  }
  if (request.status === 'IN_PROGRESS') {
    return (
      <Step icon={Settings01} tone="blue" when={request.assignedAt ?? null}>
        <b className="tw:text-primary">{request.assignee ?? 'Someone'}</b> is configuring it
      </Step>
    );
  }
  return (
    <Step
      icon={Key01}
      note={request.fulfilmentNote}
      tone="success"
      when={request.completedAt ?? request.decidedAt}>
      <b className="tw:text-primary">{request.completedBy ?? request.decidedBy ?? 'Someone'}</b>{' '}
      {request.fulfilment === 'POLICY_UPDATED' || request.fulfilment === 'POLICY_CREATED' ? (
        <>
          {request.fulfilment === 'POLICY_UPDATED' ? 'updated' : 'created'} a policy for it
          {request.fulfilmentRef && (
            <>
              {' '}
              —{' '}
              <Link
                className="tw:font-medium tw:text-brand-secondary tw:hover:underline"
                to={`/policies/${encodeURIComponent(request.fulfilmentRef)}`}>
                open the policy
              </Link>
            </>
          )}
        </>
      ) : (
        'granted access'
      )}
    </Step>
  );
}

const TONE = {
  brand: 'tw:bg-utility-brand-50 tw:text-fg-brand-primary',
  blue: 'tw:bg-utility-blue-50 tw:text-utility-blue-600',
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
  children,
}: {
  icon: typeof Check;
  tone: keyof typeof TONE;
  when: string | null;
  note?: string | null;
  children: ReactNode;
}) {
  const last = useContext(LastContext);
  return (
    <li className="tw:relative tw:flex tw:gap-3 tw:pb-4 tw:last:pb-0" data-tone={tone}>
      {!last && (
        <span aria-hidden className="tw:absolute tw:top-8 tw:bottom-0 tw:left-3.75 tw:w-px tw:bg-border-secondary" />
      )}
      <span className={`tw:flex tw:size-8 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-full ${TONE[tone]}`}>
        <Icon className="tw:size-4" />
      </span>
      <div className="tw:min-w-0 tw:flex-1 tw:pt-1.5 tw:text-sm tw:text-secondary">
        <div>{children}</div>
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

function Failure({ error, fallback }: { error: unknown; fallback: string }) {
  return (
    <p
      className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-error-primary"
      role="alert">
      <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
      <span>{apiErrorMessage(error, fallback)}</span>
    </p>
  );
}

function Hint({ children }: { children: ReactNode }) {
  return (
    <p className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-blue-50 tw:px-3 tw:py-2 tw:text-xs tw:text-secondary">
      <InfoCircle className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-brand-primary" />
      <span>{children}</span>
    </p>
  );
}

/**
 * Answering one stage.
 *
 * Somebody asked on two stages that are open at once -- the owner who is also
 * the data steward -- picks which one they answer; an administrator may answer
 * any open stage, and is told it is recorded as answering for its approvers.
 */
function Decide({ request }: { request: AccessRequest }) {
  const queryClient = useQueryClient();
  const me = useAuthStore((state) => state.user?.username ?? '');
  const [note, setNote] = useState('');
  const votable = (request.stages ?? []).filter((s) => s.mayVote);
  const asked = (s: StageView) => s.pool.some((m) => m.username.toLowerCase() === me.toLowerCase());
  const [picked, setPicked] = useState<number | null>(
    () => (votable.find(asked) ?? votable[0])?.idx ?? null
  );
  const stage = votable.find((s) => s.idx === picked) ?? null;
  const done = () => queryClient.invalidateQueries({ queryKey: ['access-requests'] });

  const approve = useMutation({
    mutationFn: () => approveRequest(request.id, { note: note.trim() || null, stageIdx: picked }),
    onSuccess: done,
  });
  const reject = useMutation({
    mutationFn: () => rejectRequest(request.id, note.trim(), picked),
    onSuccess: done,
  });

  const busy = approve.isPending || reject.isPending;
  const failure = approve.error ?? reject.error;

  return (
    <div className="tw:flex tw:flex-col tw:gap-4 tw:border-t tw:border-secondary tw:bg-secondary tw:px-6 tw:py-5">
      <h3 className="tw:text-sm tw:font-semibold tw:text-primary">Your decision</h3>

      {votable.length > 1 && (
        <div aria-label="Stage you answer" className="tw:flex tw:flex-wrap tw:gap-2" role="radiogroup">
          {votable.map((s) => (
            <button
              aria-checked={s.idx === picked}
              className={`tw:cursor-pointer tw:rounded-lg tw:border tw:px-3 tw:py-1.5 tw:text-sm tw:font-medium tw:transition-colors ${
                s.idx === picked
                  ? 'tw:border-brand tw:bg-primary tw:text-brand-secondary'
                  : 'tw:border-primary tw:bg-primary tw:text-secondary tw:hover:bg-primary_hover'
              }`}
              key={s.idx}
              onClick={() => setPicked(s.idx)}
              role="radio"
              type="button">
              {s.name}
            </button>
          ))}
        </div>
      )}

      {stage && !asked(stage) && (
        <p className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-warning-50 tw:px-3 tw:py-2 tw:text-xs tw:text-secondary">
          <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-warning-primary" />
          <span>
            You were not asked on <b>{stage.name}</b>. As a platform administrator you can answer
            for it, and the answer is recorded as made for its approvers.
          </span>
        </p>
      )}

      <Hint>
        Approving grants nothing yet. Once every stage has approved, whoever configures the request
        sets it up and decides how long it lasts. The access composes with every policy like any
        grant: it cannot override a DENY or lift a mask.
      </Hint>

      <textarea
        aria-label="Note to the requester"
        className={`${FIELD} tw:min-h-20 tw:resize-y tw:bg-primary`}
        onChange={(event) => setNote(event.target.value)}
        placeholder="A note to the requester — required to reject"
        value={note}
      />
      {failure && <Failure error={failure} fallback="The decision was not recorded." />}
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

const FULFILMENTS: { value: Fulfilment; label: string; detail: string }[] = [
  {
    value: 'GRANT',
    label: 'Grant access',
    detail: 'ARAK writes a grant for the requester on this table.',
  },
  {
    value: 'POLICY_UPDATED',
    label: 'I updated a policy',
    detail: 'You changed an existing policy so it lets them in.',
  },
  {
    value: 'POLICY_CREATED',
    label: 'I created a policy',
    detail: 'You wrote a new policy for this.',
  },
];

/**
 * Setting up an approved request: take it, then say how it was done, or
 * decline it with a reason.
 *
 * A grant is the one thing written here. A policy is only pointed at -- it was
 * changed on the policy pages, where it is reviewed and activated like any
 * other -- because configuring a request must never switch a policy on.
 */
function Configure({ request }: { request: AccessRequest }) {
  const queryClient = useQueryClient();
  const preauth = isPreauthorization(request);
  const [fulfilment, setFulfilment] = useState<Fulfilment>(preauth ? 'POLICY_CREATED' : 'GRANT');
  const offered = preauth ? FULFILMENTS.filter((option) => option.value !== 'GRANT') : FULFILMENTS;
  const [days, setDays] = useState(request.requestedDays === null ? '' : String(request.requestedDays));
  const [policyId, setPolicyId] = useState('');
  const [note, setNote] = useState('');
  const done = () => queryClient.invalidateQueries({ queryKey: ['access-requests'] });
  const taken = request.status === 'IN_PROGRESS';
  const byPolicy = fulfilment !== 'GRANT';

  const policies = useQuery({
    queryKey: ['policies', 'for-configure'],
    queryFn: () => fetchPolicies({ limit: 200 }),
    enabled: taken && byPolicy,
    retry: false,
  });

  // The same review the panel above reads: when a grant cannot open the table
  // the server refuses one, so the button says so first. A policy being
  // considered is checked against the table as it is chosen -- read, never
  // activated.
  const review = useAccessReview(request.id, null, taken);
  const grantBlocker = review.data?.conflicts.find((c) => c.code === 'GRANT_BLOCKED') ?? null;
  const chosen = policyId.trim();
  const policyCheck = useAccessReview(
    request.id,
    chosen || null,
    taken && byPolicy && UUID.test(chosen)
  );
  const policyConflicts = (policyCheck.data?.conflicts ?? []).filter((c) =>
    c.code.startsWith('POLICY_')
  );

  const start = useMutation({ mutationFn: () => startRequest(request.id), onSuccess: done });
  const complete = useMutation({
    mutationFn: () =>
      completeRequest(request.id, {
        fulfilment,
        days: byPolicy || days.trim() === '' ? null : Number(days),
        policyId: byPolicy ? policyId.trim() : null,
        note: note.trim() || null,
      }),
    onSuccess: done,
  });
  const decline = useMutation({
    mutationFn: () => declineRequest(request.id, note.trim()),
    onSuccess: done,
  });

  const asked = request.requestedDays;
  const dayCount = days.trim() === '' ? null : Number(days);
  const daysWrong =
    !byPolicy &&
    (dayCount === null
      ? asked !== null
      : !Number.isInteger(dayCount) || dayCount < 1 || dayCount > (asked ?? 365));
  const ready = byPolicy
    ? policyId.trim() !== '' && note.trim() !== ''
    : !daysWrong && !grantBlocker;
  const busy = start.isPending || complete.isPending || decline.isPending;
  const failure = start.error ?? complete.error ?? decline.error;

  return (
    <div
      aria-label="Configure the request"
      className="tw:flex tw:flex-col tw:gap-4 tw:border-t tw:border-secondary tw:bg-secondary tw:px-6 tw:py-5"
      role="region">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-between tw:gap-3">
        <h3 className="tw:text-sm tw:font-semibold tw:text-primary">
          {taken ? 'Configure it' : 'Approved — set it up'}
        </h3>
        {!taken && (
          <Button
            color="primary"
            iconLeading={PlayCircle}
            isDisabled={busy}
            onPress={() => start.mutate()}
            size="sm">
            {start.isPending ? 'Taking…' : 'Start configuring'}
          </Button>
        )}
      </div>

      {!taken ? (
        <p className="tw:text-xs tw:text-tertiary">
          Take it first, so the others who configure requests for this table see you have it.
        </p>
      ) : (
        <>
          <div
            aria-label="How it was configured"
            className={`tw:grid tw:gap-2 ${preauth ? 'tw:sm:grid-cols-2' : 'tw:sm:grid-cols-3'}`}
            role="radiogroup">
            {offered.map((option) => {
              const active = option.value === fulfilment;
              return (
                <button
                  aria-checked={active}
                  className={`tw:cursor-pointer tw:rounded-lg tw:border tw:bg-primary tw:px-3 tw:py-2.5 tw:text-left tw:transition-colors ${
                    active ? 'tw:border-brand tw:ring-1 tw:ring-brand' : 'tw:border-primary tw:hover:bg-primary_hover'
                  }`}
                  key={option.value}
                  onClick={() => setFulfilment(option.value)}
                  role="radio"
                  type="button">
                  <span className={`tw:block tw:text-sm tw:font-semibold ${active ? 'tw:text-brand-secondary' : 'tw:text-primary'}`}>
                    {option.label}
                  </span>
                  <span className="tw:mt-0.5 tw:block tw:text-xs tw:text-tertiary">{option.detail}</span>
                </button>
              );
            })}
          </div>

          {!byPolicy ? (
            <div className="tw:flex tw:flex-col tw:gap-2">
              <label className="tw:flex tw:flex-col tw:gap-1.5 tw:text-sm tw:font-medium tw:text-secondary">
                Grant for (days)
                <input
                  aria-invalid={daysWrong || undefined}
                  aria-label="Grant days"
                  className={`${FIELD} tw:w-40 tw:bg-primary`}
                  max={asked ?? 365}
                  min={1}
                  onChange={(event) => setDays(event.target.value)}
                  placeholder={asked === null ? 'Until revoked' : undefined}
                  type="number"
                  value={days}
                />
                <span className="tw:text-xs tw:font-normal tw:text-tertiary">
                  {asked === null
                    ? 'They asked until revoked. Leave it empty for that, or set up to 365 days.'
                    : `They asked for ${duration(asked)}. You can shorten it, not extend it.`}
                </span>
              </label>
              {grantBlocker && <ConflictList conflicts={[grantBlocker]} />}
            </div>
          ) : (
            <div className="tw:flex tw:flex-col tw:gap-1.5">
              <label className="tw:flex tw:flex-col tw:gap-1.5 tw:text-sm tw:font-medium tw:text-secondary">
                Policy
                {policies.isError ? (
                  <input
                    aria-label="Policy id"
                    className={`${FIELD} tw:bg-primary tw:font-mono`}
                    onChange={(event) => setPolicyId(event.target.value)}
                    placeholder="The policy's id, from its page"
                    value={policyId}
                  />
                ) : (
                  <select
                    aria-label="Policy"
                    className={`${FIELD} tw:bg-primary`}
                    onChange={(event) => setPolicyId(event.target.value)}
                    value={policyId}>
                    <option value="">{policies.isLoading ? 'Loading policies…' : 'Choose the policy'}</option>
                    {(policies.data ?? []).map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.document.name} ({p.lifecycleState.toLowerCase().replace('_', ' ')})
                      </option>
                    ))}
                  </select>
                )}
              </label>
              {policyCheck.isFetching && (
                <p className="tw:text-xs tw:text-tertiary">Checking the policy against this table…</p>
              )}
              {policyConflicts.length > 0 && <ConflictList conflicts={policyConflicts} />}
              {policyCheck.data?.ifPolicy && policyCheck.data.policy?.bound && (
                <details className="tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:text-sm">
                  <summary className="tw:cursor-pointer tw:px-3 tw:py-2 tw:font-medium tw:text-secondary tw:hover:text-primary">
                    What they would see with it active
                  </summary>
                  <div className="tw:border-t tw:border-secondary tw:px-3 tw:pb-3">
                    <AccessColumns access={policyCheck.data.ifPolicy} />
                  </div>
                </details>
              )}
              <Hint>
                ARAK only records which policy you changed. It does not activate it: the policy is
                reviewed and switched on from its own page, like any other.
              </Hint>
            </div>
          )}
        </>
      )}

      <textarea
        aria-label="Configuration note"
        className={`${FIELD} tw:min-h-20 tw:resize-y tw:bg-primary`}
        onChange={(event) => setNote(event.target.value)}
        placeholder={
          taken && byPolicy
            ? 'What you changed in the policy — required'
            : 'A note to the requester — required to decline'
        }
        value={note}
      />
      {failure && <Failure error={failure} fallback="That was not recorded." />}
      <div className="tw:flex tw:justify-end tw:gap-2">
        <Button
          color="secondary-destructive"
          iconLeading={XClose}
          isDisabled={busy || note.trim().length === 0}
          onPress={() => decline.mutate()}
          size="sm">
          {decline.isPending ? 'Declining…' : 'Decline'}
        </Button>
        {taken && (
          <Button
            color="primary"
            iconLeading={Check}
            isDisabled={busy || !ready}
            onPress={() => complete.mutate()}
            size="sm">
            {complete.isPending ? 'Completing…' : 'Complete'}
          </Button>
        )}
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
          Changed your mind? Withdrawing takes it out of everyone&apos;s inbox.
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

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function duration(days: number | null): string {
  if (days === null) return 'Until revoked';
  return days === 1 ? '1 day' : `${days} days`;
}

function when(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}
