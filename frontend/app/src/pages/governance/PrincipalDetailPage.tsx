import { useMemo, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import {
  AlertCircle,
  ArrowLeft,
  Plus,
  ShieldTick,
  Trash01,
  Users01,
} from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Chip as Badge } from '../../components/chips';
import { apiErrorMessage } from '../../api/client';
import { useAuthStore } from '../../auth/authStore';
import {
  addPrincipalAttribute,
  fetchAttributeVocabulary,
  fetchPrincipalDetail,
  removePrincipalAttribute,
  type AttributeOutcome,
  type MemberAttribute,
  type Principal,
  type PrincipalAttribute,
} from '../../api/governance';

/**
 * One principal: what they carry, what they belong to, and who is in them.
 *
 * <p>This is the screen behind the member count in the directory — "who is
 * actually in this group" is the first question anybody asks after writing a
 * rule against a group, and until now the answer lived only in the API
 * response.
 *
 * <p>The page is deliberately three separate lists rather than one merged
 * "effective identity", because the engine keeps them separate too. A group's
 * attributes are the group's own: {@code PrincipalLoader} reads
 * {@code principal_attribute} for the principal being evaluated and takes
 * group membership from a different walk, so an attribute on a group is not
 * carried by its members. Merging them here would show matches the engine will
 * never make, and a directory that disagrees with the engine is worse than no
 * directory — people believe the screen.
 */
export default function PrincipalDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  // Writing an attribute is writing an input to every access decision about
  // this person, so it sits behind the same role as granting one.
  const isAdmin = useAuthStore((state) => state.hasRole('PLATFORM_ADMIN'));

  const { data, isLoading, error } = useQuery({
    queryKey: ['principal', id],
    queryFn: () => fetchPrincipalDetail(id),
    enabled: Boolean(id),
  });

  if (isLoading) {
    return <p className="tw:text-sm tw:text-tertiary">Loading…</p>;
  }

  if (error || !data) {
    return (
      <>
        <BackLink />
        <p className="tw:mt-6 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'That principal is not in the identity cache.')}
        </p>
      </>
    );
  }

  const { principal, attributes, groups, members } = data;
  const memberAttributes = data.memberAttributes ?? [];
  const isGroup = principal.principalType === 'GROUP';

  return (
    <>
      <BackLink />

      <header className="tw:mt-4 tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-4">
        <div className="tw:min-w-0">
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">
              {principal.displayName || principal.username}
            </h1>
            <Badge color="gray" size="sm" type="modern">
              {principal.principalType.toLowerCase()}
            </Badge>
            <Badge color="blue-light" size="sm" type="pill-color">
              {principal.source}
            </Badge>
            {!principal.enabled && (
              <Badge color="warning" size="sm" type="pill-color">
                disabled
              </Badge>
            )}
          </div>
          <p className="tw:mt-2 tw:font-mono tw:text-sm tw:text-tertiary">
            {principal.username}
            {principal.email && ` · ${principal.email}`}
          </p>
        </div>
      </header>

      <div className="tw:mt-6 tw:grid tw:gap-6 tw:lg:grid-cols-3">
        <div className="tw:flex tw:min-w-0 tw:flex-col tw:gap-6 tw:lg:col-span-2">
          {isGroup && (
            <Panel
              subtitle={
                members.length === 0
                  ? 'Nobody is in this group. A rule naming it matches no one.'
                  : `${members.length} ${members.length === 1 ? 'person' : 'people'}, as of the last sync.`
              }
              title="Members">
              {members.length === 0 ? (
                <Note>
                  A subject rule that names this group is a denial for everyone
                  until somebody is added to it — in {principal.source}, not
                  here.
                </Note>
              ) : (
                <PrincipalList rows={members} />
              )}
            </Panel>
          )}

          {isGroup && members.length > 0 && (
            <Panel
              subtitle="The attributes its members carry themselves, and how many carry each value. This is what a rule on those attributes sees of the group."
              title="What its members carry">
              <MemberAttributes members={members.length} rows={memberAttributes} />
            </Panel>
          )}

          <Panel
            subtitle={
              isGroup
                ? "The group's own attributes. Its members do not inherit them."
                : 'What an ABAC condition reads. One row per value — an attribute can hold several.'
            }
            title="Attributes">
            <AttributeList
              canEdit={isAdmin}
              cacheKey={id}
              isGroup={isGroup}
              principalId={principal.id}
              rows={attributes}
            />
            {isGroup && attributes.length > 0 && (
              <div className="tw:mt-4 tw:flex tw:gap-2 tw:rounded-lg tw:bg-secondary tw:p-3">
                <AlertCircle className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-warning-primary" />
                {/* Worth the space: this is the assumption that quietly breaks
                    a policy, and the only place anybody would look for it. */}
                <p className="tw:text-xs tw:text-tertiary">
                  These belong to the group itself. The engine reads each
                  person's own attribute rows, so a rule on{' '}
                  <span className="tw:font-mono">{attributes[0].key}</span> will
                  not match the members listed above unless they carry it
                  themselves. To reach the members, name the group in the
                  subject rule instead.
                </p>
              </div>
            )}
          </Panel>
        </div>

        <aside className="tw:flex tw:flex-col tw:gap-6">
          <Panel title="Identity">
            <dl className="tw:flex tw:flex-col tw:gap-3">
              <Field label="Username" mono value={principal.username} />
              <Field label="Email" value={principal.email ?? '—'} />
              <Field label="Source" value={principal.source} />
              <Field
                label="Status"
                value={principal.enabled ? 'Enabled' : 'Disabled'}
              />
              <Field
                label={isGroup ? 'Members' : 'Groups'}
                value={String(
                  isGroup ? principal.memberCount : principal.groupCount
                )}
              />
            </dl>
          </Panel>

          <Panel
            subtitle={
              groups.length === 0
                ? undefined
                : 'A subject rule naming any of these reaches this principal.'
            }
            title="Member of">
            {groups.length === 0 ? (
              <Note>Not in any group.</Note>
            ) : (
              <ul className="tw:flex tw:flex-col tw:gap-2">
                {groups.map((group) => (
                  <li key={group.id}>
                    <Link
                      className="tw:flex tw:items-center tw:gap-2 tw:rounded-md tw:px-2 tw:py-1.5 tw:text-sm tw:hover:bg-secondary"
                      to={`/principals/${encodeURIComponent(group.id)}`}>
                      <Users01 className="tw:size-4 tw:shrink-0 tw:text-quaternary" />
                      <span className="tw:min-w-0 tw:flex-1 tw:truncate tw:text-secondary">
                        {group.displayName || group.username}
                      </span>
                      <span className="tw:shrink-0 tw:text-xs tw:tabular-nums tw:text-quaternary">
                        {group.memberCount}
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel
            subtitle="What this principal may do to ARAK — not what data they see."
            title="Platform role">
            {principal.appRoles.length === 0 ? (
              <Note>No platform role. Read-only access to ARAK itself.</Note>
            ) : (
              <div className="tw:flex tw:flex-wrap tw:gap-1.5">
                {principal.appRoles.map((role) => (
                  <Badge color="brand" key={role} size="sm" type="pill-color">
                    {role.replace(/_/g, ' ').toLowerCase()}
                  </Badge>
                ))}
              </div>
            )}
            <Link
              className="tw:mt-3 tw:inline-flex tw:items-center tw:gap-1.5 tw:text-xs tw:font-medium tw:text-brand-secondary tw:hover:underline"
              to="/settings/roles">
              <ShieldTick className="tw:size-3.5" />
              What each role can do
            </Link>
          </Panel>
        </aside>
      </div>
    </>
  );
}

/**
 * Each attribute key once, its values beside it with how many members hold
 * them, and how many hold none -- the ones a rule on that key cannot reach.
 */
function MemberAttributes({ rows, members }: { rows: MemberAttribute[]; members: number }) {
  const byKey = useMemo(() => {
    const keys = new Map<string, MemberAttribute[]>();
    for (const row of rows) {
      keys.set(row.key, [...(keys.get(row.key) ?? []), row]);
    }
    return [...keys.entries()];
  }, [rows]);

  if (byKey.length === 0) {
    return <Note>None of its members carries an attribute.</Note>;
  }
  return (
    <dl aria-label="Member attributes" className="tw:flex tw:flex-col tw:divide-y tw:divide-secondary">
      {byKey.map(([key, values]) => {
        const without = Math.max(0, members - values[0].keyHolders);
        return (
          <div className="tw:grid tw:gap-2 tw:py-2.5 tw:first:pt-0 tw:last:pb-0 tw:sm:grid-cols-[10rem_minmax(0,1fr)]" key={key}>
            <dt className="tw:truncate tw:font-mono tw:text-sm tw:text-secondary" title={key}>
              {key}
            </dt>
            <dd className="tw:flex tw:min-w-0 tw:flex-wrap tw:items-center tw:gap-1.5">
              {values.map((v) => (
                <Link
                  className="tw:max-w-full tw:rounded-full tw:hover:opacity-80"
                  key={v.value}
                  title={`${v.members} of ${members} hold ${key} = ${v.value}. Open everyone who does.`}
                  to={`/principals?attr=${encodeURIComponent(`${key}=${v.value}`)}`}>
                  <Badge className="tw:max-w-full" color="gray" size="sm" type="pill-color">
                    <span className="tw:truncate">{v.value}</span>
                    <span className="tw:ml-1.5 tw:shrink-0 tw:tabular-nums tw:text-quaternary">{v.members}</span>
                  </Badge>
                </Link>
              ))}
              {without > 0 && (
                <span className="tw:text-xs tw:text-quaternary">
                  {without} without
                </span>
              )}
            </dd>
          </div>
        );
      })}
    </dl>
  );
}

function BackLink() {
  return (
    <Link
      className="tw:inline-flex tw:items-center tw:gap-1.5 tw:text-sm tw:text-tertiary tw:hover:text-primary"
      to="/principals">
      <ArrowLeft className="tw:size-4" />
      People &amp; attributes
    </Link>
  );
}

function Panel({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: ReactNode;
}) {
  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
      <h2 className="tw:text-sm tw:font-semibold tw:text-primary">{title}</h2>
      {subtitle && (
        <p className="tw:mt-1 tw:text-xs tw:text-tertiary">{subtitle}</p>
      )}
      <div className="tw:mt-3">{children}</div>
    </section>
  );
}

function Field({
  label,
  value,
  mono,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div>
      <dt className="tw:text-xs tw:text-tertiary">{label}</dt>
      <dd
        className={`tw:mt-0.5 tw:break-words tw:text-sm tw:text-secondary ${
          mono ? 'tw:font-mono' : ''
        }`}>
        {value}
      </dd>
    </div>
  );
}

function Note({ children }: { children: ReactNode }) {
  return <p className="tw:text-sm tw:text-tertiary">{children}</p>;
}

const INPUT =
  'tw:w-full tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2.5 tw:py-2 tw:text-sm tw:text-primary tw:placeholder:text-quaternary';

/**
 * The attribute rows, and — for an administrator — the way to change them.
 *
 * <p>Most attributes arrive from a directory, and the ones a data policy is
 * actually written against do not arrive at all. Entra carries a department
 * because HR carries one; it carries no clearance, because nobody in HR
 * decides one, and no directory holds "which branch may this person see rows
 * for". Until this form existed, a rule on such a value could be written but
 * never matched anybody, and the failure was silent: the policy saved,
 * activated, and denied everyone.
 *
 * <p>Only a locally entered row offers the withdraw button. A value from a
 * directory belongs to that directory — closing it here would hold only until
 * the next sync put it straight back, and nothing on this screen would say so.
 * The server refuses it too; this is the same refusal, said before the click.
 */
function AttributeList({
  rows,
  canEdit,
  cacheKey,
  isGroup,
  principalId,
}: {
  rows: PrincipalAttribute[];
  canEdit: boolean;
  /** The id this page's query is cached under, which may be a username. */
  cacheKey: string;
  isGroup: boolean;
  /** The UUID the write endpoints take, which is never a username. */
  principalId: string;
}) {
  const queryClient = useQueryClient();
  const [key, setKey] = useState('');
  const [value, setValue] = useState('');
  const [reason, setReason] = useState('');
  const [failure, setFailure] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);

  // Suggestions, not a closed list. An attribute is matched on the exact
  // string, so FINANCE and Finance are two different departments and only one
  // of them is in anybody's policy — offering what is already in use is the
  // cheapest way to stop that. A key nobody has used yet still types fine.
  const vocabulary = useQuery({
    queryKey: ['attribute-vocabulary'],
    queryFn: fetchAttributeVocabulary,
    enabled: canEdit,
    staleTime: 60 * 1000,
  });

  const known = vocabulary.data?.keys ?? [];
  const values = useMemo(() => {
    const match = known.find((entry) => entry.key === key.trim());
    return match ? match.values.map((each) => each.value) : [];
  }, [known, key]);

  function landed(outcome: AttributeOutcome, idle: string) {
    queryClient.setQueryData(['principal', cacheKey], outcome.detail);
    // Both the directory's value counts and the people list are now stale.
    queryClient.invalidateQueries({ queryKey: ['attribute-vocabulary'] });
    queryClient.invalidateQueries({ queryKey: ['principals'] });
    setFailure(null);
    setNote(outcome.changed ? null : idle);
  }

  function failed(error: unknown, fallback: string) {
    setNote(null);
    setFailure(apiErrorMessage(error, fallback));
  }

  const add = useMutation({
    mutationFn: (input: { key: string; value: string; reason: string }) =>
      addPrincipalAttribute(principalId, input),
    onSuccess: (outcome, input) => {
      landed(outcome, `${input.key} ${input.value} was already there.`);
      // The key stays: adding several values to one key is the common case,
      // and retyping it each time is how a typo gets in.
      setValue('');
      setReason('');
    },
    onError: (error) => failed(error, 'That attribute could not be added.'),
  });

  const remove = useMutation({
    mutationFn: (row: PrincipalAttribute) =>
      removePrincipalAttribute(principalId, {
        key: row.key,
        value: row.value,
      }),
    onSuccess: (outcome, row) =>
      landed(outcome, `${row.key} ${row.value} was not there to take away.`),
    onError: (error) => failed(error, 'That attribute could not be withdrawn.'),
  });

  const pending = remove.isPending ? remove.variables : undefined;

  return (
    <>
      {rows.length === 0 ? (
        <Note>
          None. Any condition on an attribute would exclude{' '}
          {isGroup ? 'this group' : 'this person'}, silently and correctly.
        </Note>
      ) : (
        <AttributeTable
          busy={
            pending ? `${pending.key}=${pending.value}@${pending.source}` : null
          }
          onRemove={canEdit ? (row) => remove.mutate(row) : undefined}
          rows={rows}
        />
      )}

      {canEdit && (
        <form
          className="tw:mt-4 tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-3"
          onSubmit={(event) => {
            event.preventDefault();
            add.mutate({ key: key.trim(), value: value.trim(), reason });
          }}>
          <div className="tw:flex tw:flex-wrap tw:items-end tw:gap-3">
            <div className="tw:w-40">
              <label
                className="tw:text-xs tw:font-medium tw:text-secondary"
                htmlFor="attribute-key">
                Attribute
              </label>
              <input
                className={`${INPUT} tw:mt-1.5 tw:font-mono tw:text-xs`}
                id="attribute-key"
                list="attribute-keys"
                onChange={(event) => setKey(event.target.value)}
                placeholder="clearance"
                value={key}
              />
              <datalist id="attribute-keys">
                {known.map((entry) => (
                  <option key={entry.key} value={entry.key} />
                ))}
              </datalist>
            </div>

            <div className="tw:min-w-40 tw:flex-1">
              <label
                className="tw:text-xs tw:font-medium tw:text-secondary"
                htmlFor="attribute-value">
                Value
              </label>
              <input
                className={`${INPUT} tw:mt-1.5`}
                id="attribute-value"
                list="attribute-values"
                onChange={(event) => setValue(event.target.value)}
                placeholder="L2"
                value={value}
              />
              <datalist id="attribute-values">
                {values.map((each) => (
                  <option key={each} value={each} />
                ))}
              </datalist>
            </div>

            <div className="tw:min-w-40 tw:flex-1">
              <label
                className="tw:text-xs tw:font-medium tw:text-secondary"
                htmlFor="attribute-reason">
                Why <span className="tw:text-quaternary">(optional)</span>
              </label>
              <input
                className={`${INPUT} tw:mt-1.5`}
                id="attribute-reason"
                onChange={(event) => setReason(event.target.value)}
                placeholder="Approved in CAB-114"
                value={reason}
              />
            </div>

            <Button
              iconLeading={Plus}
              isDisabled={
                add.isPending || key.trim() === '' || value.trim() === ''
              }
              size="md"
              type="submit">
              {add.isPending ? 'Adding…' : 'Add'}
            </Button>
          </div>

          {failure && (
            <p className="tw:mt-3 tw:text-xs tw:text-error-primary">
              {failure}
            </p>
          )}
          {note && (
            <p className="tw:mt-3 tw:text-xs tw:text-tertiary">{note}</p>
          )}

          <p className="tw:mt-3 tw:text-xs tw:text-quaternary">
            Stored here as{' '}
            <span className="tw:font-mono">local</span>, alongside whatever{' '}
            {principalType(isGroup)} already carries from a directory. A rule
            reads it as{' '}
            <span className="tw:font-mono">user.{key.trim() || 'clearance'}</span>
            , so the name takes letters, digits and underscores only. Every add
            and withdrawal is written to the identity audit trail with the
            value in full.
          </p>
        </form>
      )}
    </>
  );
}

/** Reads naturally in the sentence above, and nowhere else. */
function principalType(isGroup: boolean) {
  return isGroup ? 'the group' : 'this person';
}

function AttributeTable({
  rows,
  onRemove,
  busy,
}: {
  rows: PrincipalAttribute[];
  /** Absent for everybody who may not write. */
  onRemove?: (row: PrincipalAttribute) => void;
  /** The row being withdrawn right now, so it alone says so. */
  busy?: string | null;
}) {
  return (
    <table className="tw:w-full tw:text-sm">
      <thead>
        <tr className="tw:border-b tw:border-secondary tw:text-left">
          <th className="tw:py-2 tw:pr-3 tw:text-xs tw:font-medium tw:text-tertiary">
            Key
          </th>
          <th className="tw:py-2 tw:pr-3 tw:text-xs tw:font-medium tw:text-tertiary">
            Value
          </th>
          <th className="tw:py-2 tw:text-xs tw:font-medium tw:text-tertiary">
            Synced from
          </th>
          {onRemove && <th className="tw:py-2" />}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => {
          const id = `${row.key}=${row.value}@${row.source}`;
          return (
            <tr
              className="tw:border-b tw:border-secondary tw:last:border-0"
              key={id}>
              <td className="tw:py-2 tw:pr-3 tw:align-top tw:font-mono tw:text-xs tw:text-secondary">
                {row.key}
              </td>
              <td className="tw:py-2 tw:pr-3 tw:align-top tw:text-secondary">
                {row.value}
              </td>
              <td className="tw:py-2 tw:align-top tw:text-tertiary">
                {row.source}
              </td>
              {onRemove && (
                <td className="tw:py-2 tw:pl-3 tw:text-right tw:align-top">
                  {row.source === 'local' ? (
                    <button
                      aria-label={`Withdraw ${row.key} ${row.value}`}
                      className="tw:cursor-pointer tw:inline-flex tw:items-center tw:gap-1.5 tw:rounded-md tw:px-2 tw:py-1 tw:text-xs tw:text-tertiary tw:hover:bg-secondary tw:hover:text-error-primary tw:disabled:opacity-50"
                      disabled={busy === id}
                      onClick={() => onRemove(row)}
                      type="button">
                      <Trash01 className="tw:size-3.5" />
                      {busy === id ? 'Withdrawing…' : 'Withdraw'}
                    </button>
                  ) : (
                    // Said rather than left blank: an empty cell reads as a
                    // missing button, and somebody would go looking for it.
                    <span
                      className="tw:text-xs tw:text-quaternary"
                      title={`${row.source} owns this value. Remove it there.`}>
                      {row.source} owns this
                    </span>
                  )}
                </td>
              )}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function PrincipalList({ rows }: { rows: Principal[] }) {
  return (
    <table className="tw:w-full tw:text-sm">
      <thead>
        <tr className="tw:border-b tw:border-secondary tw:text-left">
          <th className="tw:py-2 tw:pr-3 tw:text-xs tw:font-medium tw:text-tertiary">
            Name
          </th>
          <th className="tw:py-2 tw:pr-3 tw:text-xs tw:font-medium tw:text-tertiary">
            Kind
          </th>
          <th className="tw:py-2 tw:text-right tw:text-xs tw:font-medium tw:text-tertiary">
            Attributes
          </th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr
            className="tw:border-b tw:border-secondary tw:last:border-0"
            key={row.id}>
            <td className="tw:py-2 tw:pr-3 tw:align-top">
              <Link
                className="tw:font-medium tw:text-primary tw:hover:text-brand-secondary"
                to={`/principals/${encodeURIComponent(row.id)}`}>
                {row.displayName || row.username}
              </Link>
              <p className="tw:text-xs tw:text-tertiary">{row.username}</p>
            </td>
            <td className="tw:py-2 tw:pr-3 tw:align-top">
              <Badge color="gray" size="sm" type="modern">
                {row.principalType.toLowerCase()}
              </Badge>
            </td>
            <td className="tw:py-2 tw:text-right tw:align-top tw:tabular-nums tw:text-tertiary">
              {row.attributeCount || '—'}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
