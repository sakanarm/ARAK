import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  Key01,
  Lock01,
  Mail01,
  Users01,
} from '@untitledui/icons';
import { Chip as Badge } from '../components/chips';
import { apiErrorMessage } from '../api/client';
import { fetchPrincipalDetail } from '../api/governance';
import { useAuthStore } from '../auth/authStore';

/**
 * What the platform knows about the person reading it.
 *
 * <p>Not a settings screen — nothing here is editable. It answers the question
 * somebody asks after being refused a table: "what does this system think I
 * am?" Until now the only way to see one's own attributes was the directory,
 * which lists everybody and reads as somebody else's business; people
 * reasonably assumed their own values were not shown anywhere at all.
 *
 * <p>The values matter because they are the left-hand side of every ABAC
 * comparison (FR-3.2). A policy saying {@code clearance >= 'L2'} is deciding
 * against the row on this page, so a person who can read it can tell the
 * difference between "the policy excludes me" and "my department is recorded
 * wrong", which are two very different tickets to open.
 */

/** Where an attribute came from, said in words rather than as a system name. */
const SOURCE_NOTE: Record<string, string> = {
  entra: 'Synced from Entra ID. Changing it means changing it in the directory.',
  openmetadata: 'Synced from OpenMetadata. ARAK does not write it back.',
  local: 'Set in ARAK by an administrator.',
};

export default function ProfilePage() {
  const user = useAuthStore((state) => state.user);

  const { data, isLoading, error } = useQuery({
    // By id: a username is unique only within a directory, and the token
    // carries the id already.
    queryKey: ['principal', user?.id],
    queryFn: () => fetchPrincipalDetail(user!.id),
    enabled: Boolean(user?.id),
  });

  const name = user?.displayName || user?.username || 'You';
  const attributes = data?.attributes ?? [];
  const groups = data?.groups ?? [];
  const roles = user?.roles ?? [];

  return (
    <>
      <header>
        <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">
          Your profile
        </h1>
        <p className="tw:mt-2 tw:max-w-3xl tw:text-md tw:text-tertiary">
          Everything a policy can know about you when it decides what you see.
          Nothing here is editable — it is synced from the directory you signed
          in through.
        </p>
      </header>

      {error && (
        <p className="tw:mt-6 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-4 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(error, 'Could not load your profile.')}
        </p>
      )}

      <section className="tw:mt-8 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-6">
        <h2 className="tw:text-lg tw:font-semibold tw:text-primary">{name}</h2>
        <dl className="tw:mt-4 tw:grid tw:gap-x-8 tw:gap-y-3 tw:sm:grid-cols-2">
          <Field label="Username" value={user?.username} />
          <Field icon={Mail01} label="Email" value={user?.email} />
          <Field
            label="Directory"
            value={data?.principal.source ?? user?.source}
          />
          <Field label="Account type" value={data?.principal.principalType} />
        </dl>
      </section>

      <section className="tw:mt-6 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-6">
        <div className="tw:flex tw:items-center tw:gap-2">
          <Key01 className="tw:size-5 tw:text-tertiary" />
          <h2 className="tw:text-lg tw:font-semibold tw:text-primary">
            Attributes
          </h2>
          <Badge color="gray" size="sm" type="pill-color">
            {attributes.length}
          </Badge>
        </div>
        <p className="tw:mt-1 tw:text-sm tw:text-tertiary">
          These are the values an ABAC policy compares against. A key can carry
          more than one value — <code>clearance</code> holding both{' '}
          <code>L1</code> and <code>L2</code> satisfies a policy asking for
          either.
        </p>

        {isLoading && (
          <p className="tw:mt-4 tw:text-sm tw:text-tertiary">Loading…</p>
        )}

        {!isLoading && attributes.length === 0 && (
          <p className="tw:mt-4 tw:rounded-lg tw:border tw:border-dashed tw:border-secondary tw:p-6 tw:text-sm tw:text-tertiary">
            No attributes are recorded for you. Any policy that tests one will
            refuse — the engine denies by default rather than treating a missing
            value as a pass.
          </p>
        )}

        {attributes.length > 0 && (
          <ul
            aria-label="Your attributes"
            className="tw:mt-4 tw:flex tw:flex-col tw:gap-2">
            {attributes.map((attribute) => (
              <li
                className="tw:flex tw:flex-wrap tw:items-center tw:gap-x-3 tw:gap-y-1 tw:rounded-lg tw:border tw:border-secondary tw:px-4 tw:py-3"
                key={`${attribute.key}:${attribute.value}:${attribute.source}`}>
                <span className="tw:font-mono tw:text-sm tw:text-secondary">
                  {attribute.key}
                </span>
                <span className="tw:text-sm tw:font-semibold tw:text-primary">
                  {attribute.value}
                </span>
                <span
                  className="tw:ml-auto tw:text-xs tw:text-tertiary"
                  title={SOURCE_NOTE[attribute.source] ?? undefined}>
                  {attribute.source}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <div className="tw:mt-6 tw:grid tw:gap-6 tw:lg:grid-cols-2">
        <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-6">
          <div className="tw:flex tw:items-center tw:gap-2">
            <Users01 className="tw:size-5 tw:text-tertiary" />
            <h2 className="tw:text-lg tw:font-semibold tw:text-primary">
              Groups
            </h2>
            <Badge color="gray" size="sm" type="pill-color">
              {groups.length}
            </Badge>
          </div>
          <p className="tw:mt-1 tw:text-sm tw:text-tertiary">
            A policy naming a team reaches you through one of these.
          </p>

          {groups.length === 0 ? (
            <p className="tw:mt-4 tw:text-sm tw:text-tertiary">
              You are not a member of any group.
            </p>
          ) : (
            <ul
              aria-label="Your groups"
              className="tw:mt-4 tw:flex tw:flex-wrap tw:gap-2">
              {groups.map((group) => (
                <li key={group.id}>
                  <Link
                    className="tw:rounded-md tw:border tw:border-secondary tw:px-3 tw:py-1.5 tw:text-sm tw:text-secondary tw:transition tw:hover:border-brand"
                    to={`/principals/${group.id}`}>
                    {group.displayName || group.username}
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-6">
          <div className="tw:flex tw:items-center tw:gap-2">
            <Lock01 className="tw:size-5 tw:text-tertiary" />
            <h2 className="tw:text-lg tw:font-semibold tw:text-primary">
              Platform roles
            </h2>
          </div>
          {/*
            Said plainly because the distinction catches people out: a role
            here decides what you may do *to ARAK* -- write a policy, run a
            sync -- and has nothing to do with which rows of a customer table
            you may read. That is decided by the attributes above.
          */}
          <p className="tw:mt-1 tw:text-sm tw:text-tertiary">
            What you may do in ARAK itself. These do not grant access to any
            data — that is decided by policy.
          </p>

          {roles.length === 0 ? (
            <p className="tw:mt-4 tw:text-sm tw:text-tertiary">
              You hold no platform role, so ARAK is read-only for you.
            </p>
          ) : (
            <ul className="tw:mt-4 tw:flex tw:flex-wrap tw:gap-2">
              {roles.map((role) => (
                <li key={role}>
                  <Badge color="gray" size="sm" type="pill-color">
                    {role.replace(/_/g, ' ').toLowerCase()}
                  </Badge>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </>
  );
}

function Field({
  icon: Icon,
  label,
  value,
}: {
  icon?: React.ComponentType<{ className?: string }>;
  label: string;
  value?: string | null;
}) {
  return (
    <div>
      <dt className="tw:text-xs tw:font-medium tw:uppercase tw:tracking-wide tw:text-tertiary">
        {label}
      </dt>
      <dd className="tw:mt-0.5 tw:flex tw:items-center tw:gap-1.5 tw:text-sm tw:text-primary">
        {Icon && <Icon className="tw:size-4 tw:text-tertiary" />}
        {/* An em dash, not an empty cell: "we hold nothing here" is an answer,
            and a blank line reads as the page having failed to load. */}
        {value || <span className="tw:text-tertiary">—</span>}
      </dd>
    </div>
  );
}
