import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, RefreshCcw01, Server01 } from '@untitledui/icons';
import { fetchSystemVersion } from '../api/client';
import { fetchSyncStatus, type SyncState } from '../api/system';
import { useAuthStore } from '../auth/authStore';
import { Chip as Badge } from '../components/chips';
import { relativeTime } from '../components/widgets';

/**
 * Settings -> Service & build: what is running, and the OpenMetadata instance
 * it is pinned to.
 *
 * It used to be a section of its own in the rail. It is a page somebody opens
 * when something looks wrong, not daily work, so it lives with the rest of the
 * platform's setup and the rail keeps to the places people go every day.
 */
export default function SystemStatusPage() {
  const isAdmin = useAuthStore((state) => state.hasRole('PLATFORM_ADMIN'));
  const { data, isLoading, error } = useQuery({
    queryKey: ['system-version'],
    queryFn: fetchSystemVersion,
  });

  return (
    <>
      <header>
        <p className="tw:text-sm tw:text-tertiary">
          <Link className="tw:hover:text-brand-secondary tw:hover:underline" to="/settings">
            Settings
          </Link>
        </p>
        <h1 className="tw:mt-1 tw:text-display-sm tw:font-semibold tw:text-primary">Service &amp; build</h1>
        <p className="tw:mt-2 tw:max-w-3xl tw:text-pretty tw:text-md tw:text-tertiary">
          What is running, which OpenMetadata instance it is pinned to, and whether the crawl that
          fills the catalogue is keeping up.
        </p>
      </header>

      <div className="tw:mt-8 tw:grid tw:gap-4 tw:lg:grid-cols-2">
        <Card icon={<Server01 className="tw:size-5 tw:text-fg-brand-primary" />} title="Service">
          {isLoading && <p className="tw:text-sm tw:text-tertiary">Checking…</p>}
          {error && (
            <p className="tw:flex tw:items-start tw:gap-2 tw:text-sm tw:text-error-primary" role="alert">
              <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
              <span>
                Backend unreachable. Start it with: java -jar backend/dac-service/target/dac-service.jar server
                conf/dac.yml
              </span>
            </p>
          )}
          {data && (
            <dl className={FACTS}>
              <dt className="tw:text-tertiary">Version</dt>
              <dd className="tw:min-w-0 tw:break-all tw:text-primary">{data.version}</dd>
              <dt className="tw:text-tertiary">OpenMetadata</dt>
              <dd className="tw:min-w-0 tw:break-all tw:text-primary">{data.openMetadataBaseUrl}</dd>
              <dt className="tw:text-tertiary">Expected OM version</dt>
              <dd className="tw:min-w-0 tw:text-primary">{data.openMetadataExpectedVersion}</dd>
            </dl>
          )}
        </Card>

        {/* The crawl state is administrators' only; asking for it otherwise draws a 403. */}
        {isAdmin && <CrawlCard />}
      </div>
    </>
  );
}

const FACTS =
  'tw:grid tw:grid-cols-[max-content_minmax(0,1fr)] tw:gap-x-6 tw:gap-y-2 tw:text-sm';

const STATUS: Record<string, { label: string; colour: 'success' | 'error' | 'warning' | 'gray' | 'brand' }> = {
  SUCCEEDED: { label: 'Succeeded', colour: 'success' },
  IDLE: { label: 'Idle', colour: 'gray' },
  RUNNING: { label: 'Running', colour: 'brand' },
  FAILED: { label: 'Failed', colour: 'error' },
  NEVER_RUN: { label: 'Never run', colour: 'warning' },
};

function CrawlCard() {
  const { data, isLoading, error } = useQuery<SyncState>({
    queryKey: ['sync-status'],
    queryFn: fetchSyncStatus,
  });
  const status = data ? (STATUS[data.status] ?? { label: data.status, colour: 'gray' as const }) : null;

  return (
    <Card
      action={
        <Link
          className="tw:text-sm tw:font-semibold tw:text-brand-secondary tw:hover:underline"
          to="/settings/openmetadata">
          Connection &amp; sync
        </Link>
      }
      icon={<RefreshCcw01 className="tw:size-5 tw:text-fg-brand-primary" />}
      title="OpenMetadata crawl">
      {isLoading && <p className="tw:text-sm tw:text-tertiary">Checking…</p>}
      {error && <p className="tw:text-sm tw:text-error-primary">The crawl state could not be read.</p>}
      {data && status && (
        <dl className={FACTS}>
          <dt className="tw:text-tertiary">Status</dt>
          <dd>
            <Badge color={status.colour} size="sm" type="pill-color">
              {status.label}
            </Badge>
          </dd>
          <dt className="tw:text-tertiary">Last full crawl</dt>
          <dd className="tw:text-primary">{data.lastFullCrawlAt ? relativeTime(data.lastFullCrawlAt) : '—'}</dd>
          <dt className="tw:text-tertiary">Last reconcile</dt>
          <dd className="tw:text-primary">{data.lastReconcileAt ? relativeTime(data.lastReconcileAt) : '—'}</dd>
          {data.lastError && (
            <>
              <dt className="tw:text-tertiary">Last error</dt>
              <dd className="tw:min-w-0 tw:break-words tw:text-error-primary">{data.lastError}</dd>
            </>
          )}
        </dl>
      )}
    </Card>
  );
}

function Card({
  icon,
  title,
  action,
  children,
}: {
  icon: ReactNode;
  title: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section
      aria-label={title}
      className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5 tw:shadow-xs">
      <div className="tw:mb-4 tw:flex tw:items-center tw:gap-3">
        <span className="tw:flex tw:size-10 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-secondary">
          {icon}
        </span>
        <h2 className="tw:min-w-0 tw:flex-1 tw:text-lg tw:font-semibold tw:text-primary">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}
