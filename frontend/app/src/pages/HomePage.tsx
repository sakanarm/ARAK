import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ArrowRight, Database01, ShieldTick, Users01 } from '@untitledui/icons';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { fetchSystemVersion } from '../api/client';
import { useAuthStore } from '../auth/authStore';
import { humaniseRole } from '../layout/AppShell';
import { NAV_SECTIONS } from '../layout/navigation';

/**
 * Landing view.
 *
 * It shows what the platform can actually answer today — who you are signed in
 * as, what the console will let you do, and whether the service is up — and it
 * says plainly which milestone each empty panel is waiting on. Inventing a
 * dashboard of counts before the catalog sync exists would make the console
 * look finished and teach people to distrust the numbers on it later.
 */
export default function HomePage() {
  const user = useAuthStore((state) => state.user);
  const { data: system } = useQuery({
    queryKey: ['system-version'],
    queryFn: fetchSystemVersion,
    retry: false,
  });

  const name = user?.displayName || user?.username || '';
  const firstName = name.split(' ')[0];

  return (
    <>
      <header>
        <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">
          {firstName ? `Welcome, ${firstName}` : 'Welcome'}
        </h1>
        <p className="tw:mt-2 tw:text-md tw:text-tertiary">
          Subscription and data policies over your OpenMetadata governance.
        </p>
      </header>

      <section className="tw:mt-8 tw:grid tw:gap-4 tw:md:grid-cols-3">
        <PendingCard
          icon={Database01}
          milestone="M1"
          title="Assets you own"
          what="Tables where OpenMetadata lists you or one of your teams as an owner."
        />
        <PendingCard
          icon={ShieldTick}
          milestone="M3"
          title="Policies in effect"
          what="Every policy that reaches an asset you own, and the scope it came from."
        />
        <PendingCard
          icon={Users01}
          milestone="M8"
          title="Grants expiring"
          what="Access you granted that runs out in the next thirty days."
        />
      </section>

      <section className="tw:mt-10">
        <h2 className="tw:text-lg tw:font-semibold tw:text-primary">Your access</h2>
        <dl className="tw:mt-4 tw:grid tw:grid-cols-[max-content_1fr] tw:gap-x-8 tw:gap-y-3 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-6 tw:text-sm">
          <dt className="tw:text-tertiary">Signed in as</dt>
          <dd className="tw:text-primary">
            {user?.username}
            {user?.email ? ` · ${user.email}` : ''}
          </dd>

          <dt className="tw:text-tertiary">Identity source</dt>
          <dd className="tw:text-primary">
            {user?.source === 'local' ? 'Local account' : (user?.source ?? '—')}
          </dd>

          <dt className="tw:text-tertiary">Console roles</dt>
          <dd className="tw:flex tw:flex-wrap tw:gap-1">
            {(user?.roles ?? []).length === 0 ? (
              <span className="tw:text-tertiary">
                None. You can sign in, but nothing is yours to change yet.
              </span>
            ) : (
              (user?.roles ?? []).map((role) => (
                <Badge color="brand" key={role} size="sm" type="pill-color">
                  {humaniseRole(role)}
                </Badge>
              ))
            )}
          </dd>

          {(user?.scopes ?? []).length > 0 && (
            <>
              <dt className="tw:text-tertiary">Scoped to</dt>
              <dd className="tw:text-primary">{user?.scopes.join(', ')}</dd>
            </>
          )}
        </dl>
        <p className="tw:mt-3 tw:text-xs tw:text-quaternary">
          These roles decide what you may do in this console. They do not grant
          access to data in any source — that comes from policy alone.
        </p>
      </section>

      <section className="tw:mt-10">
        <h2 className="tw:text-lg tw:font-semibold tw:text-primary">Sections</h2>
        <ul className="tw:mt-4 tw:grid tw:gap-3 tw:sm:grid-cols-2">
          {NAV_SECTIONS.filter((section) => section.href !== '/').map(
            (section) => (
              <li key={section.href}>
                <Link
                  className="tw:flex tw:h-full tw:items-start tw:gap-3 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4 tw:transition tw:hover:bg-primary_hover"
                  to={section.href}>
                  {section.icon && (
                    <section.icon className="tw:mt-0.5 tw:size-5 tw:shrink-0 tw:text-fg-quaternary" />
                  )}
                  <span className="tw:min-w-0 tw:flex-1">
                    <span className="tw:flex tw:items-center tw:gap-2">
                      <span className="tw:text-sm tw:font-medium tw:text-primary">
                        {section.label}
                      </span>
                      {section.milestone && (
                        <Badge color="gray" size="sm" type="pill-color">
                          {section.milestone}
                        </Badge>
                      )}
                    </span>
                    <span className="tw:mt-1 tw:block tw:text-sm tw:text-tertiary">
                      {section.description}
                    </span>
                  </span>
                  <ArrowRight className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
                </Link>
              </li>
            )
          )}
        </ul>
      </section>

      {system && (
        <p className="tw:mt-10 tw:text-xs tw:text-quaternary">
          Service {system.version} · OpenMetadata {system.openMetadataBaseUrl} (expects{' '}
          {system.openMetadataExpectedVersion})
        </p>
      )}
    </>
  );
}

function PendingCard({
  icon: Icon,
  title,
  what,
  milestone,
}: {
  icon: React.FC<{ className?: string }>;
  title: string;
  what: string;
  milestone: string;
}) {
  return (
    <div className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
      <div className="tw:flex tw:items-center tw:justify-between">
        <Icon className="tw:size-5 tw:text-fg-quaternary" />
        <Badge color="gray" size="sm" type="pill-color">
          {milestone}
        </Badge>
      </div>
      <p className="tw:mt-4 tw:text-sm tw:font-medium tw:text-primary">{title}</p>
      <p className="tw:mt-1 tw:text-sm tw:text-tertiary">{what}</p>
    </div>
  );
}
