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
        <h1 className="text-display-sm font-semibold text-primary">
          {firstName ? `Welcome, ${firstName}` : 'Welcome'}
        </h1>
        <p className="mt-2 text-md text-tertiary">
          Subscription and data policies over your OpenMetadata governance.
        </p>
      </header>

      <section className="mt-8 grid gap-4 md:grid-cols-3">
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

      <section className="mt-10">
        <h2 className="text-lg font-semibold text-primary">Your access</h2>
        <dl className="mt-4 grid grid-cols-[max-content_1fr] gap-x-8 gap-y-3 rounded-xl border border-secondary bg-primary p-6 text-sm">
          <dt className="text-tertiary">Signed in as</dt>
          <dd className="text-primary">
            {user?.username}
            {user?.email ? ` · ${user.email}` : ''}
          </dd>

          <dt className="text-tertiary">Identity source</dt>
          <dd className="text-primary">
            {user?.source === 'local' ? 'Local account' : (user?.source ?? '—')}
          </dd>

          <dt className="text-tertiary">Console roles</dt>
          <dd className="flex flex-wrap gap-1">
            {(user?.roles ?? []).length === 0 ? (
              <span className="text-tertiary">
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
              <dt className="text-tertiary">Scoped to</dt>
              <dd className="text-primary">{user?.scopes.join(', ')}</dd>
            </>
          )}
        </dl>
        <p className="mt-3 text-xs text-quaternary">
          These roles decide what you may do in this console. They do not grant
          access to data in any source — that comes from policy alone.
        </p>
      </section>

      <section className="mt-10">
        <h2 className="text-lg font-semibold text-primary">Sections</h2>
        <ul className="mt-4 grid gap-3 sm:grid-cols-2">
          {NAV_SECTIONS.filter((section) => section.href !== '/').map(
            (section) => (
              <li key={section.href}>
                <Link
                  className="flex h-full items-start gap-3 rounded-xl border border-secondary bg-primary p-4 transition hover:bg-primary_hover"
                  to={section.href}>
                  {section.icon && (
                    <section.icon className="mt-0.5 size-5 shrink-0 text-fg-quaternary" />
                  )}
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-2">
                      <span className="text-sm font-medium text-primary">
                        {section.label}
                      </span>
                      {section.milestone && (
                        <Badge color="gray" size="sm" type="pill-color">
                          {section.milestone}
                        </Badge>
                      )}
                    </span>
                    <span className="mt-1 block text-sm text-tertiary">
                      {section.description}
                    </span>
                  </span>
                  <ArrowRight className="mt-0.5 size-4 shrink-0 text-fg-quaternary" />
                </Link>
              </li>
            )
          )}
        </ul>
      </section>

      {system && (
        <p className="mt-10 text-xs text-quaternary">
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
    <div className="rounded-xl border border-secondary bg-primary p-5">
      <div className="flex items-center justify-between">
        <Icon className="size-5 text-fg-quaternary" />
        <Badge color="gray" size="sm" type="pill-color">
          {milestone}
        </Badge>
      </div>
      <p className="mt-4 text-sm font-medium text-primary">{title}</p>
      <p className="mt-1 text-sm text-tertiary">{what}</p>
    </div>
  );
}
