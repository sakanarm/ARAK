import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  Key01,
  Lock01,
  Mail01,
  Users01,
} from '@untitledui/icons';
import type { ComponentType } from 'react';
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

/**
 * A hue per directory, so a row of attributes shows at a glance which of them
 * ARAK can change and which live somewhere else.
 *
 * <p>Taken from the same utility palette the catalog draws facets with rather
 * than picked fresh, so "blue means Entra" holds across the product instead of
 * meaning one thing here and another on an asset page.
 */
const SOURCE_TINT: Record<string, string> = {
  entra: 'tw:bg-utility-blue-50 tw:text-utility-blue-700',
  openmetadata: 'tw:bg-utility-purple-50 tw:text-utility-purple-700',
  local: 'tw:bg-utility-gray-blue-50 tw:text-utility-gray-blue-700',
};

/**
 * What a platform role lets somebody do, and how loudly the page says it.
 *
 * <p>Warning for the administrator, because a page that draws "platform admin"
 * in the same grey as "requester" is muting the one fact on it that changes
 * what the reader is looking at.
 */
const ROLE_TINT: Record<string, string> = {
  PLATFORM_ADMIN: 'tw:bg-utility-warning-50 tw:text-utility-warning-700',
  POLICY_AUTHOR: 'tw:bg-utility-brand-50 tw:text-utility-brand-700',
  DATA_OWNER: 'tw:bg-utility-indigo-50 tw:text-utility-indigo-700',
  AUDITOR: 'tw:bg-utility-purple-50 tw:text-utility-purple-700',
  REQUESTER: 'tw:bg-utility-gray-blue-50 tw:text-utility-gray-blue-700',
};

/** The first letters of a name, for the one place this page can carry a face. */
function initials(name: string): string {
  const words = name.split(' ').filter(Boolean);
  if (words.length === 0) return '?';
  const letters =
    words.length === 1 ? words[0].slice(0, 2) : words[0][0] + words[1][0];
  return letters.toUpperCase();
}

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

{/*
        The page used to open on a white card with a name in it, which read as
        the top of a form rather than as somebody's account. A tinted band and
        a monogram give it a subject -- and the monogram is the only thing on
        the page that is recognisably *you* rather than a value about you.
      */}
      <section className="tw:mt-8 tw:overflow-hidden tw:rounded-xl tw:border tw:border-secondary tw:bg-primary">
        <div className="tw:flex tw:items-center tw:gap-4 tw:border-b tw:border-secondary tw:bg-utility-brand-50 tw:px-6 tw:py-5">
          <span
            aria-hidden="true"
            className="tw:flex tw:size-14 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-full tw:bg-brand-solid tw:text-lg tw:font-semibold tw:text-fg-white">
            {initials(name)}
          </span>
          <div className="tw:min-w-0">
            <h2 className="tw:truncate tw:text-lg tw:font-semibold tw:text-utility-brand-800">
              {name}
            </h2>
            {user?.username && (
              <p className="tw:truncate tw:font-mono tw:text-sm tw:text-utility-brand-700">
                {user.username}
              </p>
            )}
          </div>
        </div>
        <dl className="tw:grid tw:gap-x-8 tw:gap-y-3 tw:px-6 tw:py-5 tw:sm:grid-cols-2">
          <Field icon={Mail01} label="Email" value={user?.email} />
          <Field
            label="Directory"
            value={data?.principal.source ?? user?.source}
          />
          <Field label="Username" value={user?.username} />
          <Field label="Account type" value={data?.principal.principalType} />
        </dl>
      </section>

      <section className="tw:mt-6 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-6">
        <SectionHead
          count={attributes.length}
          icon={Key01}
          tint="tw:bg-utility-brand-50 tw:text-utility-brand-700"
          title="Attributes"
        />
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
                className="tw:flex tw:flex-wrap tw:items-center tw:gap-x-3 tw:gap-y-1 tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary_subtle tw:px-4 tw:py-3"
                key={`${attribute.key}:${attribute.value}:${attribute.source}`}>
                <span className="tw:rounded-md tw:bg-utility-brand-100 tw:px-2 tw:py-0.5 tw:font-mono tw:text-xs tw:text-utility-brand-700">
                  {attribute.key}
                </span>
                <span className="tw:text-sm tw:font-semibold tw:text-primary">
                  {attribute.value}
                </span>
                <span
                  className={`tw:ml-auto tw:rounded-full tw:px-2 tw:py-0.5 tw:text-xs tw:font-medium ${
                    SOURCE_TINT[attribute.source] ??
                    'tw:bg-utility-gray-100 tw:text-utility-gray-700'
                  }`}
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
          <SectionHead
            count={groups.length}
            icon={Users01}
            tint="tw:bg-utility-indigo-50 tw:text-utility-indigo-700"
            title="Groups"
          />
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
                    className="tw:rounded-md tw:bg-utility-indigo-50 tw:px-3 tw:py-1.5 tw:text-sm tw:font-medium tw:text-utility-indigo-700 tw:transition tw:hover:bg-utility-indigo-100"
                    to={`/principals/${group.id}`}>
                    {group.displayName || group.username}
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-6">
          <SectionHead
            count={roles.length}
            icon={Lock01}
            tint="tw:bg-utility-purple-50 tw:text-utility-purple-700"
            title="Platform roles"
          />
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
                  <span
                    className={`tw:rounded-full tw:px-2.5 tw:py-1 tw:text-xs tw:font-semibold tw:capitalize ${
                      ROLE_TINT[role] ??
                      'tw:bg-utility-gray-100 tw:text-utility-gray-700'
                    }`}>
                    {role.replace(/_/g, ' ').toLowerCase()}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </>
  );
}

/**
 * A section's title, its icon in a tinted tile, and how many things are in it.
 *
 * <p>The tile is the point: a bare grey glyph beside a heading is decoration,
 * while the same glyph on its section's colour is what lets somebody find
 * "Groups" again by colour after they have scrolled past it once.
 */
function SectionHead({
  count,
  icon: Icon,
  tint,
  title,
}: {
  count: number;
  icon: ComponentType<{ className?: string }>;
  tint: string;
  title: string;
}) {
  return (
    <div className="tw:flex tw:items-center tw:gap-2.5">
      <span
        className={`tw:flex tw:size-8 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg ${tint}`}>
        <Icon className="tw:size-4.5" />
      </span>
      <h2 className="tw:text-lg tw:font-semibold tw:text-primary">{title}</h2>
      <Badge color="gray" size="sm" type="pill-color">
        {count}
      </Badge>
    </div>
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
