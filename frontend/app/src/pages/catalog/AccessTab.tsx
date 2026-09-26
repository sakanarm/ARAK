import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  Clock,
  Edit05,
  Plus,
  SlashCircle01,
  User01,
  Users01,
} from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import {
  amendGrant,
  createGrant,
  fetchAssetAccess,
  revokeGrant,
  type GrantAccess,
  type PersonAccess,
} from '../../api/access';
import { apiErrorMessage } from '../../api/client';
import { Panel } from './panels';
import { GrantDialog } from './GrantDialog';
import { AccessDecision } from './AccessDecision';
import { useAuthStore } from '../../auth/authStore';
import { governs } from '../../auth/stewardship';

/**
 * Who can reach this asset, and where that access comes from (FR-7.3, FR-3.1.5).
 *
 * <p>Two panels, in the order an owner asks them. The first is the list of
 * direct grants — rows somebody typed, with a window and a reason, that the
 * owner can revoke. The second is everyone the engine actually lets in right
 * now, whether a grant put them there or a policy did.
 *
 * <p>The second list is not derived from the first. It is the engine's verdict
 * per person, which is the only version of this page that cannot lie: a grant
 * sitting under a global DENY appears above as a grant and below as nobody, and
 * the number on the grant row says so in as many words.
 */
export function AccessTab({ fqn }: { fqn: string }) {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  // Reading this page is open to everyone; changing it is for whoever governs
  // the table. The server refuses the rest, so the page does not offer it.
  const mayChange = governs(useAuthStore((state) => state.user), fqn);

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

  const live = data.grants.filter((grant) => grant.live);
  const inert = data.grants.filter((grant) => !grant.live);

  return (
    <div className="tw:space-y-6">
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
          <div className="tw:space-y-2">
            {live.map((grant) => (
              <GrantRow
                busy={revoke.isPending || amend.isPending}
                grant={grant}
                key={grant.id}
                onAmend={
                  mayChange
                    ? (change) => amend.mutate({ id: grant.id, change })
                    : undefined
                }
                onRevoke={
                  mayChange ? (reason) => revoke.mutate({ id: grant.id, reason }) : undefined
                }
              />
            ))}
            {inert.length > 0 && (
              <>
                <h3 className="tw:pt-2 tw:text-xs tw:font-semibold tw:tracking-wide tw:text-tertiary tw:uppercase">
                  Not in force
                </h3>
                {inert.map((grant) => (
                  <GrantRow
                    busy={revoke.isPending}
                    grant={grant}
                    key={grant.id}
                    onRevoke={
                      mayChange
                        ? (reason) => revoke.mutate({ id: grant.id, reason })
                        : undefined
                    }
                  />
                ))}
              </>
            )}
          </div>
        )}
      </Panel>

      <Panel
        subtitle="The checks a request for this table goes through, in the order the engine runs them"
        title="How access is decided">
        <AccessDecision fqn={fqn} grants={data.grants} />
      </Panel>

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
          <PeopleTable grants={data.grants} people={data.people} />
        )}

        {data.sampled && data.known && (
          <p className="tw:mt-3 tw:text-xs tw:text-quaternary">
            Capped at {data.principalsEvaluated} principals so the page answers
            while you are looking at it. Someone outside the sample can still
            hold access.
          </p>
        )}
      </Panel>

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
 * One grant, and what it is doing.
 *
 * <p>The row leads with the person, because that is what an owner scans for,
 * and ends with the count of people it currently admits. A live grant admitting
 * nobody is called out rather than shown as a plain zero: it is the single most
 * misleading state this page can be in if left unexplained, since the row looks
 * like access and is not.
 */
function GrantRow({
  grant,
  onRevoke,
  onAmend,
  busy,
}: {
  grant: GrantAccess;
  /** Absent for someone who may not revoke here. */
  onRevoke?: (reason: string) => void;
  /** Absent for someone who may not change grants here. */
  onAmend?: (change: GrantChange) => void;
  busy: boolean;
}) {
  const [mode, setMode] = useState<'idle' | 'revoke' | 'edit'>('idle');
  const [reason, setReason] = useState('');
  const group = grant.principalType === 'GROUP';
  const overruled = grant.live && grant.effectiveFor === 0;

  return (
    <div className="tw:rounded-lg tw:border tw:border-secondary tw:p-3">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        {group ? (
          <Users01 className="tw:size-4 tw:shrink-0 tw:text-tertiary" />
        ) : (
          <User01 className="tw:size-4 tw:shrink-0 tw:text-tertiary" />
        )}
        <span className="tw:text-sm tw:font-medium tw:text-primary">
          {grant.displayName || grant.principal}
        </span>
        {grant.displayName && (
          <span className="tw:font-mono tw:text-xs tw:text-quaternary">
            {grant.principal}
          </span>
        )}
        <Badge color="gray" size="sm" type="modern">
          {group ? 'Group' : 'User'}
        </Badge>
        {grant.principalSource && (
          <Badge color="gray" size="sm" type="modern">
            {grant.principalSource}
          </Badge>
        )}
        {grant.live ? (
          <Badge color="success" size="sm" type="pill-color">
            In force
          </Badge>
        ) : (
          <Badge color="gray" size="sm" type="pill-color">
            {windowState(grant)}
          </Badge>
        )}

        {grant.live && (
          <span className="tw:ml-auto tw:text-xs tw:text-tertiary">
            {grant.effectiveFor === 0
              ? 'lets nobody in'
              : `lets ${grant.effectiveFor} in`}
          </span>
        )}
      </div>

      <div className="tw:mt-2 tw:flex tw:flex-wrap tw:items-center tw:gap-x-4 tw:gap-y-1 tw:text-xs tw:text-tertiary">
        <span className="tw:inline-flex tw:items-center tw:gap-1">
          <Clock className="tw:size-3.5" />
          {windowLabel(grant)}
        </span>
        {grant.grantedBy && (
          <span>
            granted by{' '}
            <span className="tw:text-secondary">{grant.grantedBy}</span>
            {grant.grantedAt ? ` · ${shortDate(grant.grantedAt)}` : ''}
          </span>
        )}
      </div>

      {grant.reason && (
        <p className="tw:mt-1.5 tw:text-pretty tw:text-xs tw:text-secondary">
          “{grant.reason}”
        </p>
      )}

      {overruled && (
        <div className="tw:mt-2 tw:flex tw:items-start tw:gap-2 tw:rounded-md tw:bg-warning-primary tw:p-2">
          <AlertTriangle className="tw:mt-0.5 tw:size-3.5 tw:shrink-0 tw:text-warning-primary" />
          {/* FR-3.1.4 made visible: a grant is a TABLE-layer ALLOW and cannot
              open a table an outer layer has closed. Without this line the row
              reads as access that has been given. */}
          <p className="tw:text-pretty tw:text-xs tw:text-warning-primary">
            This grant is in force and admits nobody. A policy on an outer layer
            is refusing them — a grant can add access where nothing objects, but
            it cannot overrule a layer that says no.
          </p>
        </div>
      )}

      {grant.live && mode === 'revoke' && onRevoke && (
        <div className="tw:mt-3 tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          <input
            aria-label="Why this grant is being revoked"
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

      {grant.live && mode === 'edit' && onAmend && (
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

      {grant.live && mode === 'idle' && (onRevoke || onAmend) && (
        <div className="tw:mt-3 tw:flex tw:gap-2">
          {onAmend && (
            <Button
              color="secondary"
              iconLeading={Edit05}
              onPress={() => setMode('edit')}
              size="sm">
              Edit
            </Button>
          )}
          {onRevoke && (
            <Button
              color="secondary"
              iconLeading={SlashCircle01}
              onPress={() => setMode('revoke')}
              size="sm">
              Revoke
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

/** What an edit sends: the new window and why. */
export interface GrantChange {
  validFrom?: string | null;
  validUntil: string | null;
  reason: string;
}

/**
 * The window of a live grant, changed in place.
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
    <div className="tw:mt-3 tw:space-y-3 tw:rounded-md tw:bg-secondary tw:p-3">
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
 * Everyone the engine lets in, with the grant or policy that did it.
 *
 * <p>Grants are shown by the person they name rather than by id, which needs
 * the grant list to translate; a grant that has since been revoked is still
 * referenced by an evaluation made a moment earlier, so an unknown id falls
 * back to the id rather than to an empty cell.
 */
function PeopleTable({
  people,
  grants,
}: {
  people: PersonAccess[];
  grants: GrantAccess[];
}) {
  const byId = useMemo(() => {
    const map = new Map<string, GrantAccess>();
    for (const grant of grants) {
      map.set(grant.id, grant);
    }
    return map;
  }, [grants]);

  return (
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
          {people.map((person) => (
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
                      grant · {byId.get(id)?.principal ?? id}
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

/** Why a grant is not in force, which is not the same question as when. */
function windowState(grant: GrantAccess): string {
  const now = Date.now();
  if (grant.validFrom && Date.parse(grant.validFrom) > now) {
    return 'Not started';
  }
  if (grant.validUntil && Date.parse(grant.validUntil) <= now) {
    return 'Expired';
  }
  return 'Revoked';
}

function windowLabel(grant: GrantAccess): string {
  const from = grant.validFrom ? shortDate(grant.validFrom) : 'immediately';
  // Said outright rather than shown as a blank: an open-ended grant is the one
  // nobody comes back to, so the page names it every time it draws one.
  const until = grant.validUntil ? shortDate(grant.validUntil) : 'no expiry';
  return `${from} → ${until}`;
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
