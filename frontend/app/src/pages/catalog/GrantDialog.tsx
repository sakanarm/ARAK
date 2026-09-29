import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Dialog,
  Heading,
  Modal,
  ModalOverlay,
} from 'react-aria-components';
import { SearchLg, User01, Users01 } from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { fetchPrincipals, type Principal } from '../../api/governance';
import type { NewGrant } from '../../api/access';
import { usablePurpose, usePurposes, type Purpose } from '../../api/purposes';
import { fitDays, PurposeSelect } from '../policies/purposePickers';

/**
 * Giving one person or group access to one table (FR-7.1).
 *
 * <p>Four things are asked for and three of them are mandatory: who, why, and
 * when it runs from and to. The window is the part the form is opinionated
 * about. It opens on a countdown, because "30 days" is how people actually
 * think about temporary access, and it is pre-filled, because the open-ended
 * grant is the one that is still there two years later and the default should
 * not be the one nobody reviews.
 *
 * <p>But a countdown cannot say everything. A contractor starting on the first
 * of next month, an audit window agreed with a customer, a migration that runs
 * over one weekend — those are dates, and typing 30 into a box computes the
 * wrong one. So the countdown has a box for any number of days, and beside it
 * is a second mode that takes the two instants directly. Both produce the same
 * two fields the API has always accepted; the form was simply never letting
 * anybody reach them.
 *
 * <p>What the access is for is the fifth thing, and optional: a purpose from
 * the register, kept on the grant and in its trail. A purpose with a longest
 * access bounds the window -- the grant has to end, within that many days of
 * its start -- so the form offers only windows that fit and says why when one
 * does not, instead of letting the server refuse it.
 *
 * <p>Groups come from every directory the platform knows — local, OpenMetadata
 * teams, and Entra when it arrives — for the same reason the engine resolves
 * them all: a grant that could only name a local group would send owners off to
 * rebuild groups that already exist somewhere else.
 */
export function GrantDialog({
  assetFqn,
  isOpen,
  onClose,
  onSubmit,
  submitting,
  error,
}: {
  assetFqn: string;
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (request: NewGrant) => void;
  submitting: boolean;
  error: string | null;
}) {
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<Principal | null>(null);
  const [mode, setMode] = useState<WindowMode>('duration');
  const [days, setDays] = useState<string>('30');
  // Empty means "from now" and "no expiry" respectively, which is why they are
  // strings rather than dates: a half-typed date is a string, and turning it
  // into a Date on every keystroke would make the field fight the person
  // filling it in.
  const [startsAt, setStartsAt] = useState('');
  const [endsAt, setEndsAt] = useState('');
  const [reason, setReason] = useState('');
  // The register's key, or empty for none -- the way every purpose picker
  // keeps it.
  const [purpose, setPurpose] = useState('');
  const { data: register } = usePurposes();
  const chosen = usablePurpose(register?.purposes, purpose);

  // Reopening is a new decision, not a continuation of the last one: leaving
  // the previous principal selected is how somebody grants access to the wrong
  // person while believing they picked them.
  useEffect(() => {
    if (isOpen) {
      setSearch('');
      setSelected(null);
      setMode('duration');
      setDays('30');
      setStartsAt('');
      setEndsAt('');
      setReason('');
      setPurpose('');
    }
  }, [isOpen]);

  const { data: principals, isLoading } = useQuery({
    queryKey: ['grant-principals', search],
    queryFn: () => fetchPrincipals({ search: search || undefined, limit: 50 }),
    enabled: isOpen,
    retry: false,
  });

  // Not memoised: the query is capped at 50 principals, so both passes are
  // cheaper than the dependency array that would guard them -- and `rows` is a
  // fresh array on every render, so a useMemo here would recompute anyway while
  // looking like it did not.
  const rows = principals ?? [];
  const groups = rows.filter((row) => row.principalType === 'GROUP');
  const users = rows.filter((row) => row.principalType !== 'GROUP');

  const grantWindow = windowFrom(mode, days, startsAt, endsAt, chosen);
  const ready =
    selected != null && reason.trim().length > 0 && grantWindow.problem == null;

  // A purpose with a longest access pulls the countdown within it, rather than
  // leaving a 90 the person never typed to be refused. The dates are left as
  // typed: moving somebody's end date is a decision, so it is refused instead.
  const choosePurpose = (value: string) => {
    setPurpose(value);
    if (mode === 'duration') {
      setDays(fitDays(days, usablePurpose(register?.purposes, value)));
    }
  };

  const submit = () => {
    if (!selected || !ready) {
      return;
    }
    onSubmit({
      assetFqn,
      principalId: selected.id,
      validFrom: grantWindow.validFrom,
      validUntil: grantWindow.validUntil,
      reason: reason.trim(),
      purpose: purpose || null,
    });
  };

  return (
    <ModalOverlay
      className="tw:fixed tw:inset-0 tw:z-50 tw:flex tw:items-center tw:justify-center tw:bg-overlay/70 tw:p-4"
      isDismissable
      isOpen={isOpen}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}>
      <Modal className="tw:w-full tw:max-w-lg">
        <Dialog className="tw:max-h-[85vh] tw:overflow-y-auto tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5 tw:outline-none">
          <Heading
            className="tw:text-md tw:font-semibold tw:text-primary"
            slot="title">
            Grant access
          </Heading>
          <p className="tw:mt-1 tw:font-mono tw:text-xs tw:break-all tw:text-quaternary">
            {assetFqn}
          </p>

          {error && (
            <p className="tw:mt-3 tw:rounded-lg tw:bg-error-primary tw:p-3 tw:text-sm tw:text-error-primary">
              {error}
            </p>
          )}

          <div className="tw:mt-4 tw:space-y-4">
            <div>
              <label
                className="tw:text-xs tw:font-medium tw:text-secondary"
                htmlFor="grant-principal-search">
                Who
              </label>
              <div className="tw:relative tw:mt-1">
                <SearchLg className="tw:pointer-events-none tw:absolute tw:top-2.5 tw:left-2.5 tw:size-4 tw:text-quaternary" />
                <input
                  autoComplete="off"
                  className="tw:w-full tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:py-2 tw:pr-2.5 tw:pl-8 tw:text-sm tw:text-primary tw:placeholder:text-quaternary"
                  id="grant-principal-search"
                  onChange={(event) => setSearch(event.target.value)}
                  placeholder="Search people and groups"
                  value={search}
                />
              </div>

              {selected ? (
                <div className="tw:mt-2 tw:flex tw:items-center tw:gap-2 tw:rounded-md tw:border tw:border-brand tw:bg-brand-primary tw:px-2.5 tw:py-2">
                  {selected.principalType === 'GROUP' ? (
                    <Users01 className="tw:size-4 tw:text-brand-secondary" />
                  ) : (
                    <User01 className="tw:size-4 tw:text-brand-secondary" />
                  )}
                  <span className="tw:text-sm tw:font-medium tw:text-primary">
                    {selected.displayName || selected.username}
                  </span>
                  <span className="tw:text-xs tw:text-tertiary">
                    {selected.source}
                    {selected.principalType === 'GROUP'
                      ? ` · ${selected.memberCount} members`
                      : ''}
                  </span>
                  <Button
                    className="tw:ml-auto"
                    color="link-gray"
                    onPress={() => setSelected(null)}
                    size="sm">
                    Change
                  </Button>
                </div>
              ) : (
                <div className="tw:mt-2 tw:max-h-56 tw:overflow-y-auto tw:rounded-md tw:border tw:border-secondary">
                  {isLoading && (
                    <p className="tw:p-3 tw:text-sm tw:text-tertiary">
                      Loading…
                    </p>
                  )}
                  {!isLoading && rows.length === 0 && (
                    <p className="tw:p-3 tw:text-sm tw:text-tertiary">
                      Nobody matches that.
                    </p>
                  )}
                  {/* Groups first: granting to a group is the one that keeps
                      working when somebody joins the team, and putting it above
                      the people is the cheapest way to say so. */}
                  <PrincipalGroup
                    label="Groups"
                    onPick={setSelected}
                    rows={groups}
                  />
                  <PrincipalGroup
                    label="People"
                    onPick={setSelected}
                    rows={users}
                  />
                </div>
              )}
            </div>

            <div>
              <span className="tw:text-xs tw:font-medium tw:text-secondary">
                What for
              </span>
              <PurposeSelect
                ariaLabel="Purpose"
                className="tw:mt-1"
                onChange={choosePurpose}
                value={purpose}
              />
              <p className="tw:mt-1 tw:text-xs tw:text-quaternary">
                {chosen?.maxDays
                  ? `Access for ${chosen.name} lasts at most ${chosen.maxDays} days, so the grant has to end within that.`
                  : 'Optional. From the register of purposes; the grant keeps it, and so does the trail.'}
              </p>
            </div>

            <div>
              <div className="tw:flex tw:items-baseline tw:justify-between tw:gap-3">
                <span className="tw:text-xs tw:font-medium tw:text-secondary">
                  {mode === 'duration' ? 'For how long' : 'Between'}
                </span>
                {/* One link, not a pair of tabs. The two modes describe the
                    same window, so making them look like separate settings
                    would invite somebody to fill in both and wonder which
                    one won. */}
                <button
                  className="tw:cursor-pointer tw:text-xs tw:text-brand-secondary tw:underline"
                  onClick={() => {
                    if (mode === 'dates') {
                      setDays(fitDays(days, chosen));
                    }
                    setMode(mode === 'duration' ? 'dates' : 'duration');
                  }}
                  type="button">
                  {mode === 'duration'
                    ? 'Set start and end dates'
                    : 'Use a duration'}
                </button>
              </div>

              {mode === 'duration' ? (
                <>
                  <div className="tw:mt-1 tw:flex tw:flex-wrap tw:gap-1.5">
                    {DURATIONS.filter((option) => withinPurpose(option.value, chosen)).map(
                      (option) => (
                        <button
                          aria-pressed={days === option.value}
                          className={chip(days === option.value)}
                          key={option.value}
                          onClick={() => setDays(option.value)}
                          type="button">
                          {option.label}
                        </button>
                      )
                    )}
                  </div>
                  <div className="tw:mt-2 tw:flex tw:items-center tw:gap-2">
                    <label
                      className="tw:text-xs tw:text-tertiary"
                      htmlFor="grant-days">
                      or
                    </label>
                    <input
                      aria-label="Number of days"
                      className="tw:w-20 tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2 tw:py-1.5 tw:text-sm tw:text-primary"
                      id="grant-days"
                      inputMode="numeric"
                      min={1}
                      onChange={(event) => setDays(event.target.value)}
                      placeholder="45"
                      type="number"
                      value={days}
                    />
                    <span className="tw:text-xs tw:text-tertiary">days</span>
                  </div>
                </>
              ) : (
                <div className="tw:mt-1 tw:grid tw:grid-cols-1 tw:gap-2 sm:tw:grid-cols-2">
                  <div>
                    <label
                      className="tw:text-xs tw:text-tertiary"
                      htmlFor="grant-starts">
                      Starts
                    </label>
                    <input
                      className="tw:mt-1 tw:w-full tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2 tw:py-1.5 tw:text-sm tw:text-primary"
                      id="grant-starts"
                      onChange={(event) => setStartsAt(event.target.value)}
                      type="datetime-local"
                      value={startsAt}
                    />
                  </div>
                  <div>
                    <label
                      className="tw:text-xs tw:text-tertiary"
                      htmlFor="grant-ends">
                      Ends
                    </label>
                    <input
                      className="tw:mt-1 tw:w-full tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2 tw:py-1.5 tw:text-sm tw:text-primary"
                      id="grant-ends"
                      onChange={(event) => setEndsAt(event.target.value)}
                      type="datetime-local"
                      value={endsAt}
                    />
                  </div>
                </div>
              )}

              <p
                className={`tw:mt-1.5 tw:text-xs ${
                  grantWindow.problem ? 'tw:text-error-primary' : 'tw:text-tertiary'
                }`}>
                {grantWindow.problem ?? grantWindow.summary}
              </p>
            </div>

            <div>
              <label
                className="tw:text-xs tw:font-medium tw:text-secondary"
                htmlFor="grant-reason">
                Why
              </label>
              <textarea
                className="tw:mt-1 tw:w-full tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2.5 tw:py-2 tw:text-sm tw:text-primary tw:placeholder:text-quaternary"
                id="grant-reason"
                onChange={(event) => setReason(event.target.value)}
                placeholder="What is this access for?"
                rows={2}
                value={reason}
              />
              <p className="tw:mt-1 tw:text-xs tw:text-quaternary">
                Required. It is what the audit trail shows an auditor a year
                from now, when nobody remembers.
              </p>
            </div>
          </div>

          <div className="tw:mt-5 tw:flex tw:justify-end tw:gap-2">
            <Button color="secondary" onPress={onClose} size="sm">
              Cancel
            </Button>
            <Button
              isDisabled={!ready || submitting}
              onPress={submit}
              size="sm">
              {submitting ? 'Granting…' : 'Grant access'}
            </Button>
          </div>
        </Dialog>
      </Modal>
    </ModalOverlay>
  );
}

function PrincipalGroup({
  label,
  rows,
  onPick,
}: {
  label: string;
  rows: Principal[];
  onPick: (principal: Principal) => void;
}) {
  if (rows.length === 0) {
    return null;
  }
  return (
    <div>
      <p className="tw:sticky tw:top-0 tw:bg-secondary tw:px-3 tw:py-1 tw:text-xs tw:font-semibold tw:tracking-wide tw:text-tertiary tw:uppercase">
        {label}
      </p>
      <ul>
        {rows.map((row) => (
          <li key={row.id}>
            <button
              className="tw:cursor-pointer tw:flex tw:w-full tw:items-center tw:gap-2 tw:px-3 tw:py-2 tw:text-left tw:hover:bg-secondary"
              onClick={() => onPick(row)}
              type="button">
              {row.principalType === 'GROUP' ? (
                <Users01 className="tw:size-4 tw:shrink-0 tw:text-tertiary" />
              ) : (
                <User01 className="tw:size-4 tw:shrink-0 tw:text-tertiary" />
              )}
              <span className="tw:min-w-0 tw:truncate tw:text-sm tw:text-primary">
                {row.displayName || row.username}
              </span>
              <span className="tw:ml-auto tw:shrink-0 tw:text-xs tw:text-quaternary">
                {row.source}
                {row.principalType === 'GROUP'
                  ? ` · ${row.memberCount}`
                  : ''}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

const DURATIONS = [
  { value: '7', label: '7 days' },
  { value: '30', label: '30 days' },
  { value: '90', label: '90 days' },
  { value: '365', label: '1 year' },
  { value: '', label: 'No expiry' },
];

type WindowMode = 'duration' | 'dates';

const DAY_MS = 24 * 60 * 60 * 1000;

/** Whether a countdown chip is one the purpose allows; No expiry never is under a cap. */
function withinPurpose(days: string, purpose: Purpose | undefined): boolean {
  const most = purpose?.maxDays;
  return !most || (days !== '' && Number(days) <= most);
}

/** The server's words for a window longer than the purpose allows. */
function purposeLimit(purpose: Purpose, most: number): string {
  return `Access for ${purpose.name} lasts at most ${most} days`;
}

function chip(active: boolean) {
  return `tw:cursor-pointer tw:rounded-md tw:border tw:px-2.5 tw:py-1.5 tw:text-sm ${
    active
      ? 'tw:border-brand tw:bg-brand-primary tw:text-brand-secondary'
      : 'tw:border-secondary tw:text-tertiary tw:hover:text-primary'
  }`;
}

/** The window the grant will carry, and what to tell the person about it. */
interface GrantWindow {
  validFrom: string | null;
  validUntil: string | null;
  /** Non-null blocks the submit, and is shown in place of the summary. */
  problem: string | null;
  summary: string;
}

/**
 * Both ways of describing the window, resolved to the two instants the API
 * takes.
 *
 * <p>Absolute instants rather than a duration, because the grant has to mean
 * the same thing after the row is written as it did in the form, and a
 * duration only means something relative to a clock that has already moved on.
 *
 * <p>The refusals here are the server's own rules restated, deliberately: the
 * server checks them again and is the authority, but a person who has just
 * typed an end date before the start date should be told so while their
 * attention is still on the field, not after a round trip. The purpose's
 * longest access is one of them: the grant has to end, within that many days
 * of its start.
 */
function windowFrom(
  mode: WindowMode,
  days: string,
  startsAt: string,
  endsAt: string,
  purpose?: Purpose
): GrantWindow {
  const most = purpose?.maxDays ?? null;
  if (mode === 'duration') {
    if (!days.trim()) {
      if (purpose && most) {
        return {
          validFrom: null,
          validUntil: null,
          problem: `${purposeLimit(purpose, most)}; choose a number of days.`,
          summary: '',
        };
      }
      return {
        validFrom: null,
        validUntil: null,
        problem: null,
        summary: 'No expiry. This grant stays until somebody revokes it.',
      };
    }
    const count = Number(days);
    if (!Number.isFinite(count) || count <= 0) {
      return {
        validFrom: null,
        validUntil: null,
        problem: most
          ? 'Give a number of days above zero.'
          : 'Give a number of days above zero, or pick No expiry.',
        summary: '',
      };
    }
    if (purpose && most && count > most) {
      return {
        validFrom: null,
        validUntil: null,
        problem: `${purposeLimit(purpose, most)}.`,
        summary: '',
      };
    }
    const until = new Date(Date.now() + count * DAY_MS);
    return {
      validFrom: null,
      validUntil: until.toISOString(),
      problem: null,
      summary: `Expires ${until.toLocaleString()} — the engine stops honouring it at that moment, not when a job next runs.`,
    };
  }

  // A datetime-local value carries no zone, so it is read in the browser's own
  // zone, which is the one the person typing it is thinking in.
  const from = startsAt ? new Date(startsAt) : null;
  const until = endsAt ? new Date(endsAt) : null;
  if ((from && Number.isNaN(from.getTime())) || (until && Number.isNaN(until.getTime()))) {
    return { validFrom: null, validUntil: null, problem: 'That is not a date.', summary: '' };
  }
  if (from && until && until <= from) {
    return {
      validFrom: null,
      validUntil: null,
      problem: 'The end has to come after the start.',
      summary: '',
    };
  }
  if (purpose && most) {
    if (!until) {
      return {
        validFrom: null,
        validUntil: null,
        problem: `${purposeLimit(purpose, most)}; give the grant an end.`,
        summary: '',
      };
    }
    // Counted from the start, or from now when it starts now, as the server
    // counts it.
    const start = from ?? new Date();
    if (until.getTime() - start.getTime() > most * DAY_MS) {
      return {
        validFrom: null,
        validUntil: null,
        problem: `${purposeLimit(purpose, most)}; end it by ${new Date(start.getTime() + most * DAY_MS).toLocaleString()}.`,
        summary: '',
      };
    }
  }

  const opens = from
    ? from > new Date()
      ? `Opens ${from.toLocaleString()} — until then the grant exists and grants nothing`
      : `Runs from ${from.toLocaleString()}`
    : 'Starts immediately';
  const closes = until ? `, expires ${until.toLocaleString()}.` : ', with no expiry.';
  return {
    validFrom: from ? from.toISOString() : null,
    validUntil: until ? until.toISOString() : null,
    problem: null,
    summary: opens + closes,
  };
}
