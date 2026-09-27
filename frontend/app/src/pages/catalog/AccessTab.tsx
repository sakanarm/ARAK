import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  ChevronRight,
  Clock,
  DotsHorizontal,
  Edit05,
  Plus,
  SearchLg,
  SlashCircle01,
  User01,
  Users01,
} from '@untitledui/icons';
import { Button as AriaButton } from 'react-aria-components';
import { Chip as Badge } from '../../components/chips';
import { Pager } from '../../components/Pager';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Dropdown } from '@openmetadata/ui-core-components/components/base/dropdown/dropdown';
import {
  amendGrant,
  createGrant,
  fetchAssetAccess,
  revokeGrant,
  type AssetAccess,
  type GrantAccess,
  type PersonAccess,
} from '../../api/access';
import { apiErrorMessage } from '../../api/client';
import { Panel } from './panels';
import { GrantDialog } from './GrantDialog';
import { AccessDecision } from './AccessDecision';
import { DirectAccessPanel } from './DirectAccessPanel';
import { useAuthStore } from '../../auth/authStore';
import { governs } from '../../auth/stewardship';
import {
  DEFAULT_GRANT_STATUSES,
  GRANT_STATUSES,
  ORIGIN_FILTERS,
  countGrants,
  countOrigins,
  filterGrants,
  filterPeople,
  grantStatus,
  page,
  range,
  type GrantStatus,
  type OriginFilter,
} from './accessLists';

/** Short enough to take in at a glance; the pager offers more. */
const GRANT_PAGE_SIZES = [10, 25, 50, 100];
const PEOPLE_PAGE_SIZES = [25, 50, 100];

/** Where the summary strip jumps to. */
const SECTIONS = {
  grants: 'access-grants',
  decided: 'access-decided',
  people: 'access-people',
  outside: 'access-outside',
} as const;

function jump(id: string) {
  // Not every browser this runs in scrolls smoothly, and a test DOM does not
  // scroll at all; a jump that silently does nothing is better than a throw.
  document.getElementById(id)?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
}

/**
 * Who can reach this asset, and where that access comes from (FR-7.3, FR-3.1.5).
 *
 * <p>Two lists, in the order an owner asks them. The first is the direct
 * grants — rows somebody typed, with a window and a reason, that the owner can
 * revoke. The second is everyone the engine actually lets in right now, whether
 * a grant put them there or a policy did.
 *
 * <p>The second list is not derived from the first. It is the engine's verdict
 * per person, which is the only version of this page that cannot lie: a grant
 * sitting under a global DENY appears above as a grant and below as nobody, and
 * the grant row says so in as many words.
 *
 * <p>Either list can run to hundreds on a table used across the organisation,
 * so both are narrowed where the reader is looking — status chips, search and
 * pages — and a strip at the top says how many of each there are before anyone
 * scrolls. It is all done on the one answer already on the page: nothing here
 * asks the server again.
 */
export function AccessTab({ fqn }: { fqn: string }) {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  // Reading this page is open to everyone; changing it is for whoever governs
  // the table. The server refuses the rest, so the page does not offer it.
  const mayChange = governs(useAuthStore((state) => state.user), fqn);

  const [statuses, setStatuses] = useState<ReadonlySet<GrantStatus>>(DEFAULT_GRANT_STATUSES);
  const [origin, setOrigin] = useState<OriginFilter>('ALL');
  const [restrictedOnly, setRestrictedOnly] = useState(false);

  const { data, isLoading, error } = useQuery({
    queryKey: ['asset-access', fqn],
    queryFn: () => fetchAssetAccess(fqn),
    enabled: Boolean(fqn),
    retry: false,
  });

  // One invalidation for both panels and the audit tab: a grant changes what
  // the trail says as surely as it changes who is in the list, and a page that
  // refreshed one of the three would be showing itself disagreeing.
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['asset-access', fqn] });
    queryClient.invalidateQueries({ queryKey: ['asset-grant-history', fqn] });
  };

  const create = useMutation({
    mutationFn: createGrant,
    onSuccess: () => {
      setDialogOpen(false);
      refresh();
    },
  });

  const revoke = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      revokeGrant(id, reason),
    onSuccess: refresh,
  });

  const amend = useMutation({
    mutationFn: ({ id, change }: { id: string; change: GrantChange }) =>
      amendGrant(id, change),
    onSuccess: refresh,
  });

  if (isLoading) {
    return <p className="tw:text-sm tw:text-tertiary">Loading…</p>;
  }

  if (error || !data) {
    return (
      <p className="tw:text-sm tw:text-error-primary">
        {apiErrorMessage(error, 'The access list for this asset could not be read.')}
      </p>
    );
  }

  const showGrants = (only: GrantStatus[]) => {
    setStatuses(new Set(only));
    jump(SECTIONS.grants);
  };
  const showPeople = (restricted: boolean) => {
    setOrigin('ALL');
    setRestrictedOnly(restricted);
    jump(SECTIONS.people);
  };

  return (
    <div className="tw:space-y-6">
      <SummaryStrip data={data} onGrants={showGrants} onPeople={showPeople} />

      <div className="tw:scroll-mt-24" id={SECTIONS.grants}>
        <Panel
          action={
            mayChange ? (
              <Button
                iconLeading={Plus}
                onPress={() => setDialogOpen(true)}
                size="sm">
                Grant access
              </Button>
            ) : undefined
          }
          subtitle="Access given to one person or group on this table, with a window and a reason (FR-7.1)"
          title="Direct grants">
          {revoke.isError && (
            <Notice tone="error">
              {apiErrorMessage(revoke.error, 'That grant could not be revoked.')}
            </Notice>
          )}
          {amend.isError && (
            <Notice tone="error">
              {apiErrorMessage(amend.error, 'That grant could not be changed.')}
            </Notice>
          )}

          {data.grants.length === 0 ? (
            <p className="tw:text-sm tw:text-tertiary">
              Nothing is granted directly here. Everyone below, if anyone, is let
              in by a policy.
            </p>
          ) : (
            <GrantList
              busy={revoke.isPending || amend.isPending}
              grants={data.grants}
              known={data.known}
              onAmend={
                mayChange ? (id, change) => amend.mutate({ id, change }) : undefined
              }
              onRevoke={
                mayChange ? (id, reason) => revoke.mutate({ id, reason }) : undefined
              }
              onStatuses={setStatuses}
              people={data.people}
              sampled={data.sampled}
              statuses={statuses}
            />
          )}
        </Panel>
      </div>

      <div className="tw:scroll-mt-24" id={SECTIONS.decided}>
        <Panel
          subtitle="The checks a request for this table goes through, in the order the engine runs them"
          title="How access is decided">
          <AccessDecision fqn={fqn} grants={data.grants} />
        </Panel>
      </div>

      <div className="tw:scroll-mt-24" id={SECTIONS.people}>
        <Panel
          subtitle={
            data.sampled
              ? `The engine's verdict for ${data.principalsEvaluated} of ${data.principalsKnown} principals`
              : `The engine's verdict for all ${data.principalsKnown} principals`
          }
          title="Who can read this now">
          {!data.known ? (
            <Notice tone="warning">
              This asset is not in the catalog cache, so nothing about it can be
              evaluated. The grants above are shown because they exist; who they
              let in is unanswerable until the asset is crawled again.
            </Notice>
          ) : data.people.length === 0 ? (
            <p className="tw:text-sm tw:text-warning-primary">
              Nobody can read this table. Access is denied by default, so this is
              what a table with no subscription policy and no grant looks like.
            </p>
          ) : (
            <PeopleList
              grants={data.grants}
              onOrigin={setOrigin}
              onRestrictedOnly={setRestrictedOnly}
              origin={origin}
              people={data.people}
              restrictedOnly={restrictedOnly}
            />
          )}

          {data.sampled && data.known && (
            <p className="tw:mt-3 tw:text-xs tw:text-quaternary">
              Capped at {data.principalsEvaluated} principals so the page answers
              while you are looking at it. Someone outside the sample can still
              hold access.
            </p>
          )}
        </Panel>
      </div>

      <div className="tw:scroll-mt-24" id={SECTIONS.outside}>
        <DirectAccessPanel fqn={fqn} />
      </div>

      <GrantDialog
        assetFqn={fqn}
        error={
          create.isError
            ? apiErrorMessage(create.error, 'That grant could not be created.')
            : null
        }
        isOpen={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onSubmit={(request) => create.mutate(request)}
        submitting={create.isPending}
      />
    </div>
  );
}

/**
 * The tab in one line: how many grants are doing what, how many people get
 * in, and links down to each section.
 *
 * <p>Each count is a button that narrows the list it counts, so "1 overruled"
 * is not only news but the way to the row. Counts of zero are left out, except
 * for the two a reader always wants — grants in force and people who can read.
 */
function SummaryStrip({
  data,
  onGrants,
  onPeople,
}: {
  data: AssetAccess;
  onGrants: (only: GrantStatus[]) => void;
  onPeople: (restrictedOnly: boolean) => void;
}) {
  const counts = countGrants(data.grants, data.known);
  const inForce = counts.IN_FORCE + counts.OVERRULED;
  const restricted = data.people.filter((person) => person.restricted).length;

  return (
    <nav
      aria-label="Access at a glance"
      className="tw:flex tw:flex-wrap tw:items-center tw:gap-x-4 tw:gap-y-2 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-4 tw:py-3">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-1.5">
        <Stat
          count={inForce}
          label={inForce === 1 ? 'grant in force' : 'grants in force'}
          onPress={() => onGrants(['IN_FORCE', 'OVERRULED'])}
        />
        {counts.OVERRULED > 0 && (
          <Stat
            count={counts.OVERRULED}
            label="overruled"
            onPress={() => onGrants(['OVERRULED'])}
            warning
          />
        )}
        {counts.NOT_STARTED > 0 && (
          <Stat
            count={counts.NOT_STARTED}
            label="not started"
            onPress={() => onGrants(['NOT_STARTED'])}
          />
        )}
        {counts.EXPIRED > 0 && (
          <Stat
            count={counts.EXPIRED}
            label="expired"
            onPress={() => onGrants(['EXPIRED'])}
          />
        )}
      </div>

      {data.known && (
        <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-1.5">
          <Stat
            count={data.people.length}
            label="can read now"
            onPress={() => onPeople(false)}
            suffix={data.sampled ? ` (of ${data.principalsEvaluated} checked)` : undefined}
          />
          {restricted > 0 && (
            <Stat
              count={restricted}
              label="see less than all"
              onPress={() => onPeople(true)}
            />
          )}
        </div>
      )}

      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-x-3 tw:gap-y-1 tw:text-xs tw:sm:ml-auto">
        <span className="tw:text-quaternary">Jump to</span>
        {(
          [
            [SECTIONS.grants, 'Grants'],
            [SECTIONS.decided, 'How it is decided'],
            [SECTIONS.people, 'Who can read'],
            [SECTIONS.outside, 'Outside ARAK'],
          ] as const
        ).map(([id, label]) => (
          <a
            className="tw:text-brand-secondary tw:hover:underline"
            href={`#${id}`}
            key={id}
            onClick={(event) => {
              event.preventDefault();
              jump(id);
            }}>
            {label}
          </a>
        ))}
      </div>
    </nav>
  );
}

function Stat({
  count,
  label,
  suffix,
  warning,
  onPress,
}: {
  count: number;
  label: string;
  suffix?: string;
  warning?: boolean;
  onPress: () => void;
}) {
  return (
    <button
      className={`tw:cursor-pointer tw:rounded-md tw:px-2 tw:py-1 tw:text-sm tw:hover:bg-secondary ${
        warning ? 'tw:text-warning-primary' : 'tw:text-secondary'
      }`}
      onClick={onPress}
      type="button">
      <span className="tw:font-semibold tw:tabular-nums">{count}</span> {label}
      {suffix && <span className="tw:text-xs tw:text-quaternary">{suffix}</span>}
    </button>
  );
}

/**
 * The direct grants, narrowed by status and search, a page at a time.
 *
 * <p>The page resets to the first whenever the filter changes: staying on page
 * four of a list that now has one page would show an empty list and look like
 * the search found nothing.
 */
function GrantList({
  grants,
  known,
  people,
  sampled,
  statuses,
  onStatuses,
  onRevoke,
  onAmend,
  busy,
}: {
  grants: GrantAccess[];
  known: boolean;
  people: PersonAccess[];
  sampled: boolean;
  statuses: ReadonlySet<GrantStatus>;
  onStatuses: (next: ReadonlySet<GrantStatus>) => void;
  onRevoke?: (id: string, reason: string) => void;
  onAmend?: (id: string, change: GrantChange) => void;
  busy: boolean;
}) {
  const [search, setSearch] = useState('');
  const [offset, setOffset] = useState(0);
  const [size, setSize] = useState(GRANT_PAGE_SIZES[0]);

  // Who each grant is letting in, read off the people list rather than asked
  // for: the engine already said, per person, which grants admitted them.
  const admitted = useMemo(() => {
    const map = new Map<string, string[]>();
    for (const person of people) {
      for (const id of person.viaGrants) {
        map.set(id, [...(map.get(id) ?? []), person.principal]);
      }
    }
    return map;
  }, [people]);

  const counts = countGrants(grants, known);
  const shown = filterGrants(grants, { statuses, search, known });
  const current = page(shown, offset, size);

  const toggle = (status: GrantStatus) => {
    const next = new Set(statuses);
    if (next.has(status)) {
      next.delete(status);
    } else {
      next.add(status);
    }
    onStatuses(next);
    setOffset(0);
  };

  return (
    <>
      <div className="tw:mb-3 tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <SearchBox
          label="Search grants"
          onChange={(next) => {
            setSearch(next);
            setOffset(0);
          }}
          placeholder="Name, reason or who granted it"
          value={search}
        />
        <div aria-label="Grant status" className="tw:flex tw:flex-wrap tw:gap-1.5" role="group">
          {GRANT_STATUSES.map(({ status, label }) => (
            <FilterChip
              count={counts[status]}
              key={status}
              label={label}
              onPress={() => toggle(status)}
              pressed={statuses.has(status)}
              warning={status === 'OVERRULED' && counts[status] > 0}
            />
          ))}
        </div>
      </div>

      {shown.length === 0 ? (
        <p className="tw:rounded-lg tw:border tw:border-dashed tw:border-secondary tw:p-3 tw:text-sm tw:text-tertiary">
          No grant here matches.{' '}
          <button
            className="tw:cursor-pointer tw:text-brand-secondary tw:hover:underline"
            onClick={() => {
              onStatuses(new Set(GRANT_STATUSES.map(({ status }) => status)));
              setSearch('');
              setOffset(0);
            }}
            type="button">
            Show all {grants.length}
          </button>
        </p>
      ) : (
        <ul
          aria-label="Grants"
          className="tw:divide-y tw:divide-secondary tw:rounded-lg tw:border tw:border-secondary">
          {current.rows.map((grant) => (
            <GrantRow
              admits={admitted.get(grant.id) ?? []}
              busy={busy}
              grant={grant}
              key={grant.id}
              known={known}
              onAmend={onAmend && ((change) => onAmend(grant.id, change))}
              onRevoke={onRevoke && ((reason) => onRevoke(grant.id, reason))}
              sampled={sampled}
              status={grantStatus(grant, known)}
            />
          ))}
        </ul>
      )}

      <ListFooter
        label="Grant pages"
        noun="Grants"
        offset={current.offset}
        onOffset={setOffset}
        onSize={(next) => {
          setSize(next);
          setOffset(0);
        }}
        shown={current.rows.length}
        size={size}
        sizes={GRANT_PAGE_SIZES}
        total={shown.length}
      />
    </>
  );
}

/**
 * One grant on one line: who, where it stands, its window, why, and how many
 * it lets in. A long name or reason is cut to fit, so the name and the reason
 * both open the row into its details — every field in full, and the people the
 * grant is letting in, which for a group is the "who is in it" a line of text
 * cannot answer.
 *
 * <p>An overruled grant keeps its explanation under the line. It is the single
 * most misleading state this page can be in if left unexplained, since the row
 * looks like access and is not.
 */
function GrantRow({
  grant,
  status,
  admits,
  known,
  sampled,
  onRevoke,
  onAmend,
  busy,
}: {
  grant: GrantAccess;
  status: GrantStatus;
  /** The evaluated people this grant lets in. */
  admits: string[];
  known: boolean;
  sampled: boolean;
  /** Absent for someone who may not revoke here. */
  onRevoke?: (reason: string) => void;
  /** Absent for someone who may not change grants here. */
  onAmend?: (change: GrantChange) => void;
  busy: boolean;
}) {
  const [mode, setMode] = useState<'idle' | 'revoke' | 'edit'>('idle');
  const [reason, setReason] = useState('');
  const [open, setOpen] = useState(false);
  const group = grant.principalType === 'GROUP';
  const name = grant.displayName || grant.principal;
  const details = `grant-details-${grant.id}`;
  // An expired grant is history; a live one and one still to start can both be
  // changed or ended, and the server takes either.
  const changeable = status !== 'EXPIRED' && (onRevoke || onAmend);

  return (
    <li className="tw:px-3 tw:py-2">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-x-3 tw:gap-y-1 tw:md:flex-nowrap">
        <button
          aria-controls={details}
          aria-expanded={open}
          aria-label={`Details for ${name}`}
          className="tw:flex tw:min-w-0 tw:cursor-pointer tw:items-center tw:gap-2 tw:rounded-md tw:text-left tw:outline-focus-ring tw:focus-visible:outline-2 tw:md:w-72 tw:md:shrink-0"
          onClick={() => setOpen(!open)}
          title={grant.displayName ? `${name} (${grant.principal})` : name}
          type="button">
          <ChevronRight
            className={`tw:size-4 tw:shrink-0 tw:text-quaternary tw:transition-transform ${
              open ? 'tw:rotate-90' : ''
            }`}
          />
          {group ? (
            <Users01 className="tw:size-4 tw:shrink-0 tw:text-tertiary" />
          ) : (
            <User01 className="tw:size-4 tw:shrink-0 tw:text-tertiary" />
          )}
          <span className="tw:min-w-0">
            <span className="tw:block tw:truncate tw:text-sm tw:font-medium tw:text-primary">
              {name}
            </span>
            {grant.displayName && (
              <span className="tw:block tw:truncate tw:font-mono tw:text-xs tw:text-quaternary">
                {grant.principal}
              </span>
            )}
          </span>
        </button>

        <StatusBadge status={status} />

        <span className="tw:inline-flex tw:shrink-0 tw:items-center tw:gap-1 tw:text-xs tw:whitespace-nowrap tw:text-tertiary">
          <Clock className="tw:size-3.5" />
          {windowLabel(grant)}
        </span>

        <button
          aria-controls={details}
          aria-expanded={open}
          className={`tw:min-w-0 tw:flex-1 tw:cursor-pointer tw:truncate tw:text-left tw:text-xs ${
            grant.reason
              ? 'tw:text-secondary tw:hover:text-primary'
              : 'tw:text-quaternary tw:hover:text-secondary'
          }`}
          onClick={() => setOpen(!open)}
          title={grant.reason ?? undefined}
          type="button">
          {grant.reason ? `“${grant.reason}”` : 'no reason given'}
        </button>

        {grant.live && (
          <span className="tw:shrink-0 tw:text-xs tw:whitespace-nowrap tw:text-tertiary">
            {grant.effectiveFor === 0 ? 'lets nobody in' : `lets ${grant.effectiveFor} in`}
          </span>
        )}

        {changeable && (
          <Dropdown.Root>
            <AriaButton
              aria-label={`Actions for ${name}`}
              className="tw:shrink-0 tw:cursor-pointer tw:rounded-md tw:p-1 tw:text-fg-quaternary tw:outline-focus-ring tw:hover:bg-secondary tw:hover:text-fg-quaternary_hover tw:focus-visible:outline-2">
              <DotsHorizontal className="tw:size-4" />
            </AriaButton>
            <Dropdown.Popover className="tw:w-44">
              <Dropdown.Menu selectionMode="none">
                {onAmend ? (
                  <Dropdown.Item icon={Edit05} label="Edit" onAction={() => setMode('edit')} />
                ) : null}
                {onRevoke ? (
                  <Dropdown.Item
                    icon={SlashCircle01}
                    label="Revoke"
                    onAction={() => setMode('revoke')}
                  />
                ) : null}
              </Dropdown.Menu>
            </Dropdown.Popover>
          </Dropdown.Root>
        )}
      </div>

      {open && (
        <GrantDetails
          admits={admits}
          grant={grant}
          id={details}
          known={known}
          sampled={sampled}
          status={status}
        />
      )}

      {status === 'OVERRULED' && (
        <p className="tw:mt-1 tw:flex tw:items-start tw:gap-1.5 tw:pl-6 tw:text-pretty tw:text-xs tw:text-warning-primary">
          <AlertTriangle className="tw:mt-0.5 tw:size-3.5 tw:shrink-0" />
          {/* FR-3.1.4 made visible: a grant is a TABLE-layer ALLOW and cannot
              open a table an outer layer has closed. Without this line the row
              reads as access that has been given. */}
          In force and admits nobody: a policy on an outer layer refuses them. A
          grant can add access where nothing objects, but it cannot overrule a
          layer that says no.
        </p>
      )}

      {mode === 'revoke' && onRevoke && (
        <div className="tw:mt-2 tw:flex tw:flex-wrap tw:items-center tw:gap-2 tw:pl-6">
          <input
            aria-label="Why this grant is being revoked"
            autoFocus
            className="tw:min-w-0 tw:flex-1 tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2.5 tw:py-1.5 tw:text-sm tw:text-primary tw:placeholder:text-quaternary"
            onChange={(event) => setReason(event.target.value)}
            placeholder="Why is this being revoked?"
            value={reason}
          />
          <Button
            color="primary-destructive"
            isDisabled={busy || reason.trim().length === 0}
            onPress={() => onRevoke(reason.trim())}
            size="sm">
            Revoke
          </Button>
          <Button color="link-gray" onPress={() => setMode('idle')} size="sm">
            Cancel
          </Button>
        </div>
      )}

      {mode === 'edit' && onAmend && (
        <GrantEditor
          busy={busy}
          grant={grant}
          onCancel={() => setMode('idle')}
          onSave={(change) => {
            onAmend(change);
            setMode('idle');
          }}
        />
      )}
    </li>
  );
}

/** More than this and the list of who a grant admits is a page of its own. */
const ADMITS_SHOWN = 30;

/**
 * A grant with nothing cut short: the whole name and principal, the window to
 * the minute, the reason as it was typed, who granted it, and who it lets in.
 */
function GrantDetails({
  id,
  grant,
  status,
  admits,
  known,
  sampled,
}: {
  id: string;
  grant: GrantAccess;
  status: GrantStatus;
  admits: string[];
  known: boolean;
  sampled: boolean;
}) {
  const group = grant.principalType === 'GROUP';
  const term = 'tw:text-tertiary';
  const value = 'tw:min-w-0 tw:text-secondary';

  let letsIn: React.ReactNode;
  if (status === 'NOT_STARTED') {
    letsIn = `Nobody yet: it starts ${longDate(grant.validFrom!)}.`;
  } else if (status === 'EXPIRED') {
    letsIn = 'Nobody: it has ended.';
  } else if (!known) {
    letsIn = 'Cannot tell: this asset is not in the catalog cache, so nobody was evaluated.';
  } else if (admits.length === 0) {
    letsIn = 'Nobody: a policy on an outer layer refuses them.';
  } else {
    letsIn = (
      <>
        <span className="tw:flex tw:flex-wrap tw:gap-1.5">
          {admits.slice(0, ADMITS_SHOWN).map((principal) => (
            <span
              className="tw:rounded tw:bg-primary tw:px-1.5 tw:py-0.5 tw:break-all tw:text-secondary"
              key={principal}>
              {principal}
            </span>
          ))}
          {admits.length > ADMITS_SHOWN && (
            <span className="tw:px-1.5 tw:py-0.5 tw:text-tertiary">
              and {admits.length - ADMITS_SHOWN} more — search for them under Who can read this now
            </span>
          )}
        </span>
        {sampled && (
          <span className="tw:mt-1 tw:block tw:text-quaternary">
            Of the principals checked; someone outside the sample may be let in too.
          </span>
        )}
      </>
    );
  }

  return (
    <dl
      className="tw:mt-2 tw:ml-6 tw:grid tw:grid-cols-1 tw:gap-x-4 tw:gap-y-2 tw:rounded-md tw:bg-secondary tw:p-3 tw:text-xs tw:sm:grid-cols-[7rem_1fr]"
      id={id}>
      <dt className={term}>{group ? 'Group' : 'User'}</dt>
      <dd className={value}>
        <span className="tw:text-sm tw:font-medium tw:break-words tw:text-primary">
          {grant.displayName || grant.principal}
        </span>
        {grant.displayName && (
          <span className="tw:block tw:font-mono tw:break-all tw:text-tertiary">
            {grant.principal}
          </span>
        )}
        {grant.principalSource && (
          <span className="tw:block tw:text-tertiary">from {grant.principalSource}</span>
        )}
      </dd>

      <dt className={term}>Window</dt>
      <dd className={value}>
        {grant.validFrom ? `From ${longDate(grant.validFrom)}` : 'From when it was granted'}
        {' · '}
        {grant.validUntil ? `until ${longDate(grant.validUntil)}` : 'no expiry'}
      </dd>

      <dt className={term}>Reason</dt>
      <dd className={`${value} tw:whitespace-pre-wrap tw:break-words`}>
        {grant.reason || 'None given'}
      </dd>

      <dt className={term}>Granted</dt>
      <dd className={value}>
        {grant.grantedBy ? (
          <>
            by <span className="tw:text-primary">{grant.grantedBy}</span>
            {grant.grantedAt ? ` on ${longDate(grant.grantedAt)}` : ''}
          </>
        ) : (
          'Who granted it is not recorded'
        )}
      </dd>

      <dt className={term}>Lets in</dt>
      <dd className={value}>{letsIn}</dd>
    </dl>
  );
}

function StatusBadge({ status }: { status: GrantStatus }) {
  const label = GRANT_STATUSES.find((entry) => entry.status === status)?.label ?? status;
  const color =
    status === 'IN_FORCE' ? 'success' : status === 'OVERRULED' ? 'warning' : 'gray';
  return (
    <span className="tw:shrink-0">
      <Badge color={color} size="sm" type="pill-color">
        {label}
      </Badge>
    </span>
  );
}

/** What an edit sends: the new window and why. */
export interface GrantChange {
  validFrom?: string | null;
  validUntil: string | null;
  reason: string;
}

/**
 * The window of a grant, changed in place.
 *
 * <p>The server keeps the old row as a revoked one and writes the new window
 * beside it, so the audit trail still reads what was there before. A start
 * that has already passed is not offered: moving it would claim the person had
 * no access over a stretch in which they did.
 */
function GrantEditor({
  grant,
  busy,
  onSave,
  onCancel,
}: {
  grant: GrantAccess;
  busy: boolean;
  onSave: (change: GrantChange) => void;
  onCancel: () => void;
}) {
  const started = !grant.validFrom || new Date(grant.validFrom) <= new Date();
  const [startsAt, setStartsAt] = useState(localInput(grant.validFrom));
  const [noExpiry, setNoExpiry] = useState(!grant.validUntil);
  const [endsAt, setEndsAt] = useState(localInput(grant.validUntil));
  const [reason, setReason] = useState('');

  const from = !started && startsAt ? new Date(startsAt) : null;
  const until = !noExpiry && endsAt ? new Date(endsAt) : null;
  const problem =
    !noExpiry && !endsAt
      ? 'Pick when it ends, or tick No expiry.'
      : until && Number.isNaN(until.getTime())
        ? 'That is not a date.'
        : until && until <= new Date()
          ? 'That end has already passed. To end a grant now, revoke it.'
          : until && from && until <= from
            ? 'The end has to come after the start.'
            : null;

  return (
    <div className="tw:mt-2 tw:space-y-3 tw:rounded-md tw:bg-secondary tw:p-3">
      <div className="tw:flex tw:flex-wrap tw:items-end tw:gap-3">
        {!started && (
          <div>
            <label
              className="tw:block tw:text-xs tw:text-tertiary"
              htmlFor={`starts-${grant.id}`}>
              Starts
            </label>
            <input
              className="tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2 tw:py-1 tw:text-sm tw:text-primary"
              id={`starts-${grant.id}`}
              onChange={(event) => setStartsAt(event.target.value)}
              type="datetime-local"
              value={startsAt}
            />
          </div>
        )}
        <div>
          <label
            className="tw:block tw:text-xs tw:text-tertiary"
            htmlFor={`ends-${grant.id}`}>
            Ends
          </label>
          <input
            className="tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2 tw:py-1 tw:text-sm tw:text-primary tw:disabled:opacity-50"
            disabled={noExpiry}
            id={`ends-${grant.id}`}
            onChange={(event) => setEndsAt(event.target.value)}
            type="datetime-local"
            value={endsAt}
          />
        </div>
        <label className="tw:inline-flex tw:items-center tw:gap-1.5 tw:pb-1.5 tw:text-sm tw:text-secondary">
          <input
            checked={noExpiry}
            onChange={(event) => setNoExpiry(event.target.checked)}
            type="checkbox"
          />
          No expiry
        </label>
      </div>
      <input
        aria-label="Why this grant is being changed"
        className="tw:w-full tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2.5 tw:py-1.5 tw:text-sm tw:text-primary tw:placeholder:text-quaternary"
        onChange={(event) => setReason(event.target.value)}
        placeholder="Why is this being changed?"
        value={reason}
      />
      {problem && <p className="tw:text-xs tw:text-error-primary">{problem}</p>}
      <div className="tw:flex tw:gap-2">
        <Button
          isDisabled={busy || problem !== null || reason.trim().length === 0}
          onPress={() =>
            onSave({
              ...(from ? { validFrom: from.toISOString() } : {}),
              validUntil: until ? until.toISOString() : null,
              reason: reason.trim(),
            })
          }
          size="sm">
          Save
        </Button>
        <Button color="link-gray" onPress={onCancel} size="sm">
          Cancel
        </Button>
      </div>
    </div>
  );
}

/** An ISO instant as a datetime-local value, in the browser's own zone. */
function localInput(value: string | null): string {
  if (!value) {
    return '';
  }
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) {
    return '';
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}T${pad(at.getHours())}:${pad(at.getMinutes())}`;
}

/**
 * Everyone the engine lets in, with the grant or policy that did it, narrowed
 * by where the access comes from and whether they see all of it.
 *
 * <p>Grants are shown by the person they name rather than by id, which needs
 * the grant list to translate; a grant that has since been revoked is still
 * referenced by an evaluation made a moment earlier, so an unknown id falls
 * back to the id rather than to an empty cell.
 */
function PeopleList({
  people,
  grants,
  origin,
  onOrigin,
  restrictedOnly,
  onRestrictedOnly,
}: {
  people: PersonAccess[];
  grants: GrantAccess[];
  origin: OriginFilter;
  onOrigin: (next: OriginFilter) => void;
  restrictedOnly: boolean;
  onRestrictedOnly: (next: boolean) => void;
}) {
  const [search, setSearch] = useState('');
  const [offset, setOffset] = useState(0);
  const [size, setSize] = useState(PEOPLE_PAGE_SIZES[0]);

  const grantNames = useMemo(() => {
    const map = new Map<string, string>();
    for (const grant of grants) {
      map.set(grant.id, grant.principal);
    }
    return map;
  }, [grants]);

  const counts = countOrigins(people);
  const shown = filterPeople(people, { origin, restrictedOnly, search, grantNames });
  const current = page(shown, offset, size);

  return (
    <>
      <div className="tw:mb-3 tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <SearchBox
          label="Search people"
          onChange={(next) => {
            setSearch(next);
            setOffset(0);
          }}
          placeholder="Name, policy or grant"
          value={search}
        />
        <div aria-label="Access from" className="tw:flex tw:flex-wrap tw:gap-1.5" role="group">
          {ORIGIN_FILTERS.map((entry) => (
            <FilterChip
              count={counts[entry.origin]}
              key={entry.origin}
              label={entry.label}
              onPress={() => {
                onOrigin(entry.origin);
                setOffset(0);
              }}
              pressed={origin === entry.origin}
            />
          ))}
        </div>
        <label className="tw:inline-flex tw:items-center tw:gap-1.5 tw:text-sm tw:text-secondary">
          <input
            checked={restrictedOnly}
            onChange={(event) => {
              onRestrictedOnly(event.target.checked);
              setOffset(0);
            }}
            type="checkbox"
          />
          Only those who see less than all
        </label>
      </div>

      {shown.length === 0 ? (
        <p className="tw:rounded-lg tw:border tw:border-dashed tw:border-secondary tw:p-3 tw:text-sm tw:text-tertiary">
          Nobody here matches.{' '}
          <button
            className="tw:cursor-pointer tw:text-brand-secondary tw:hover:underline"
            onClick={() => {
              onOrigin('ALL');
              onRestrictedOnly(false);
              setSearch('');
              setOffset(0);
            }}
            type="button">
            Show all {people.length}
          </button>
        </p>
      ) : (
        <div className="tw:overflow-x-auto">
          <table className="tw:w-full tw:text-sm">
            <thead>
              <tr className="tw:border-b tw:border-secondary tw:text-left tw:text-xs tw:text-tertiary">
                <th className="tw:py-2 tw:pr-3 tw:font-medium">Principal</th>
                <th className="tw:py-2 tw:pr-3 tw:font-medium">Access from</th>
                <th className="tw:py-2 tw:pr-3 tw:font-medium">Granted by</th>
                <th className="tw:py-2 tw:font-medium">What they see</th>
              </tr>
            </thead>
            <tbody>
              {current.rows.map((person) => (
                <tr
                  className="tw:border-b tw:border-secondary tw:last:border-0"
                  key={person.principal}>
                  <td className="tw:py-2 tw:pr-3 tw:align-top tw:font-medium tw:text-primary">
                    {person.principal}
                  </td>
                  <td className="tw:py-2 tw:pr-3 tw:align-top">
                    <OriginBadge origin={person.origin} />
                  </td>
                  <td className="tw:py-2 tw:pr-3 tw:align-top">
                    <div className="tw:flex tw:flex-wrap tw:gap-1.5">
                      {person.viaGrants.map((id) => (
                        <span
                          className="tw:rounded tw:bg-secondary tw:px-1.5 tw:py-0.5 tw:text-xs tw:text-secondary"
                          key={id}>
                          grant · {grantNames.get(id) ?? id}
                        </span>
                      ))}
                      {person.viaPolicies.map((name) => (
                        <span
                          className="tw:rounded tw:bg-secondary tw:px-1.5 tw:py-0.5 tw:text-xs tw:text-secondary"
                          key={name}>
                          {name}
                        </span>
                      ))}
                      {person.viaGrants.length === 0 &&
                        person.viaPolicies.length === 0 && (
                          <span className="tw:text-xs tw:text-quaternary">—</span>
                        )}
                    </div>
                  </td>
                  <td className="tw:py-2 tw:align-top">
                    {person.restricted ? (
                      <span className="tw:text-xs tw:text-tertiary">
                        {restriction(person)}
                      </span>
                    ) : (
                      <span className="tw:text-xs tw:text-quaternary">
                        everything
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <ListFooter
        label="People pages"
        noun="People"
        offset={current.offset}
        onOffset={setOffset}
        onSize={(next) => {
          setSize(next);
          setOffset(0);
        }}
        shown={current.rows.length}
        size={size}
        sizes={PEOPLE_PAGE_SIZES}
        total={shown.length}
      />
    </>
  );
}

/**
 * "1–10 of 34" and the pager, under a list. Nothing at all while the list
 * fits on one page at the smallest size: a pager with one page is noise.
 */
function ListFooter({
  offset,
  shown,
  total,
  size,
  sizes,
  label,
  noun,
  onOffset,
  onSize,
}: {
  offset: number;
  shown: number;
  total: number;
  size: number;
  sizes: number[];
  label: string;
  noun: string;
  onOffset: (offset: number) => void;
  onSize: (size: number) => void;
}) {
  if (total <= sizes[0]) {
    return null;
  }
  return (
    <div className="tw:flex tw:flex-wrap tw:items-end tw:justify-between tw:gap-x-4">
      <p aria-live="polite" className="tw:mt-4 tw:text-xs tw:text-tertiary tw:tabular-nums">
        {range(offset, shown, total)}
      </p>
      <div className="tw:min-w-0 tw:flex-1">
        <Pager
          label={label}
          noun={noun}
          offset={offset}
          onOffset={onOffset}
          onPageSize={onSize}
          pageSize={size}
          sizes={sizes}
          total={total}
        />
      </div>
    </div>
  );
}

function SearchBox({
  label,
  placeholder,
  value,
  onChange,
}: {
  label: string;
  placeholder: string;
  value: string;
  onChange: (next: string) => void;
}) {
  return (
    <div className="tw:relative tw:w-full tw:sm:w-64">
      <SearchLg className="tw:pointer-events-none tw:absolute tw:top-2 tw:left-2.5 tw:size-4 tw:text-quaternary" />
      <input
        aria-label={label}
        autoComplete="off"
        className="tw:w-full tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:py-1.5 tw:pr-2.5 tw:pl-8 tw:text-sm tw:text-primary tw:placeholder:text-quaternary"
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        type="search"
        value={value}
      />
    </div>
  );
}

/** A filter that is on or off, with how many rows it stands for. */
function FilterChip({
  label,
  count,
  pressed,
  warning,
  onPress,
}: {
  label: string;
  count: number;
  pressed: boolean;
  warning?: boolean;
  onPress: () => void;
}) {
  return (
    <button
      aria-pressed={pressed}
      className={`tw:inline-flex tw:cursor-pointer tw:items-center tw:gap-1.5 tw:rounded-full tw:border tw:px-2.5 tw:py-1 tw:text-xs tw:font-medium ${
        pressed
          ? 'tw:border-brand tw:bg-brand-primary tw:text-brand-secondary'
          : 'tw:border-secondary tw:bg-primary tw:text-tertiary tw:hover:text-secondary'
      }`}
      onClick={onPress}
      type="button">
      {label}
      <span
        className={`tw:tabular-nums ${warning ? 'tw:text-warning-primary' : 'tw:text-quaternary'}`}>
        {count}
      </span>
    </button>
  );
}

/**
 * Where the access came from.
 *
 * <p>`BOTH` earns its own colour because of what it means operationally:
 * revoking the grant will not remove this person's access, and an owner who
 * does not know that will revoke it and be surprised.
 */
function OriginBadge({ origin }: { origin: PersonAccess['origin'] }) {
  if (origin === 'GRANT') {
    return (
      <Badge color="blue" size="sm" type="pill-color">
        Direct grant
      </Badge>
    );
  }
  if (origin === 'POLICY') {
    return (
      <Badge color="gray" size="sm" type="pill-color">
        Via policy
      </Badge>
    );
  }
  return (
    <Badge color="indigo" size="sm" type="pill-color">
      Grant and policy
    </Badge>
  );
}

function Notice({
  tone,
  children,
}: {
  tone: 'error' | 'warning';
  children: React.ReactNode;
}) {
  const error = tone === 'error';
  return (
    <div
      className={`tw:mb-3 tw:rounded-lg tw:p-3 tw:text-pretty tw:text-sm ${
        error
          ? 'tw:bg-error-primary tw:text-error-primary'
          : 'tw:bg-warning-primary tw:text-warning-primary'
      }`}>
      {children}
    </div>
  );
}

function restriction(person: PersonAccess): string {
  const parts: string[] = [];
  if (person.maskedColumns > 0) {
    parts.push(`${person.maskedColumns} masked`);
  }
  if (person.hiddenColumns > 0) {
    parts.push(`${person.hiddenColumns} hidden`);
  }
  if (person.rowFilters > 0) {
    parts.push(
      `${person.rowFilters} row filter${person.rowFilters === 1 ? '' : 's'}`
    );
  }
  return parts.join(' · ');
}

function windowLabel(grant: GrantAccess): string {
  const from = grant.validFrom ? shortDate(grant.validFrom) : 'immediately';
  // Said outright rather than shown as a blank: an open-ended grant is the one
  // nobody comes back to, so the page names it every time it draws one.
  const until = grant.validUntil ? shortDate(grant.validUntil) : 'no expiry';
  return `${from} → ${until}`;
}

/** To the minute, for the one place a reader wants to know exactly when. */
function longDate(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime())
    ? value
    : parsed.toLocaleString(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
}

function shortDate(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime())
    ? value
    : parsed.toLocaleDateString(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
      });
}
