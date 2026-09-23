import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { AlertCircle, ArrowLeft, ShieldTick, Users01 } from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { apiErrorMessage } from '../../api/client';
import {
  fetchPrincipalDetail,
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

          <Panel
            subtitle={
              isGroup
                ? "The group's own attributes. Its members do not inherit them."
                : 'What an ABAC condition reads. One row per value — an attribute can hold several.'
            }
            title="Attributes">
            {attributes.length === 0 ? (
              <Note>
                None. Any condition on an attribute would exclude{' '}
                {isGroup ? 'this group' : 'this person'}, silently and
                correctly.
              </Note>
            ) : (
              <AttributeTable rows={attributes} />
            )}
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

function AttributeTable({ rows }: { rows: PrincipalAttribute[] }) {
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
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr
            className="tw:border-b tw:border-secondary tw:last:border-0"
            key={`${row.key}=${row.value}@${row.source}`}>
            <td className="tw:py-2 tw:pr-3 tw:align-top tw:font-mono tw:text-xs tw:text-secondary">
              {row.key}
            </td>
            <td className="tw:py-2 tw:pr-3 tw:align-top tw:text-secondary">
              {row.value}
            </td>
            <td className="tw:py-2 tw:align-top tw:text-tertiary">
              {row.source}
            </td>
          </tr>
        ))}
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
