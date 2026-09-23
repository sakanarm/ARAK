import { useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  CheckCircle,
  Key01,
  RefreshCcw01,
  Zap,
} from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../../api/client';
import {
  fetchOpenMetadataSettings,
  startCrawl,
  testOpenMetadata,
  type ConnectionProbe,
  type CrawlResult,
} from '../../api/system';
import { useAuthStore } from '../../auth/authStore';

/**
 * The OpenMetadata connection (FR-1.1).
 *
 * This is the setting the whole product stands on: every asset, tag, glossary
 * term and domain a policy is written against is a copy of what this instance
 * holds, so "is it connected, and how old is the copy" is the first question
 * anybody debugging a policy should be able to answer.
 *
 * The page reads and proves; it does not write. The bot token and the webhook
 * secret come from the process environment and there is no endpoint that sets
 * them — a browser that can rewrite the upstream credential is a browser whose
 * compromise hands over the catalog, and a secret typed into a form is a secret
 * in a request log. So each one is shown as present or absent, with the name of
 * the variable to set, and the two buttons here do the things that are safe to
 * do from a browser: ask the instance who it is, and ask for a crawl.
 */
export default function OpenMetadataSettingsPage() {
  const isAdmin = useAuthStore((state) => state.hasRole('PLATFORM_ADMIN'));
  const queryClient = useQueryClient();
  const [probe, setProbe] = useState<ConnectionProbe | null>(null);
  const [crawl, setCrawl] = useState<CrawlResult | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ['om-settings'],
    queryFn: fetchOpenMetadataSettings,
    enabled: isAdmin,
    retry: false,
  });

  const test = useMutation({
    mutationFn: testOpenMetadata,
    onSuccess: (result) => {
      setProbe(result);
      setCrawl(null);
    },
  });

  const run = useMutation({
    mutationFn: startCrawl,
    onSuccess: (result) => {
      setCrawl(result);
      setProbe(null);
      // The crawl rewrites the facet rows every policy binding is resolved
      // from, so the vocabulary and the catalog on screen are now stale.
      queryClient.invalidateQueries({ queryKey: ['om-settings'] });
      queryClient.invalidateQueries({ queryKey: ['sync-status'] });
      queryClient.invalidateQueries({ queryKey: ['vocabulary'] });
      queryClient.invalidateQueries({ queryKey: ['catalog'] });
    },
  });

  if (!isAdmin) {
    return (
      <div className="tw:space-y-6">
        <Header />
        <Notice tone="warning">
          Only a platform admin can see the connection. It carries the address of
          the catalog and the state of the credential used to read it.
        </Notice>
      </div>
    );
  }

  return (
    <div className="tw:space-y-6">
      <Header />

      {isLoading && (
        <p className="tw:text-sm tw:text-tertiary">Reading the connection…</p>
      )}

      {error && (
        <Notice tone="error">
          {apiErrorMessage(error, 'Could not read the OpenMetadata connection.')}
        </Notice>
      )}

      {data && (
        <>
          <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
            <div className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-4">
              <div className="tw:min-w-0">
                <h2 className="tw:text-md tw:font-semibold tw:text-primary">
                  Instance
                </h2>
                <p className="tw:mt-1 tw:font-mono tw:text-sm tw:break-all tw:text-secondary">
                  {data.baseUrl}
                </p>
              </div>
              <div className="tw:flex tw:shrink-0 tw:gap-2">
                <Button
                  color="secondary"
                  iconLeading={Zap}
                  isDisabled={test.isPending}
                  onPress={() => test.mutate()}
                  size="sm">
                  {test.isPending ? 'Testing…' : 'Test connection'}
                </Button>
                <Button
                  color="primary"
                  iconLeading={RefreshCcw01}
                  isDisabled={run.isPending || data.sync.status === 'RUNNING'}
                  onPress={() => run.mutate()}
                  size="sm">
                  {run.isPending ? 'Crawling…' : 'Run a full crawl'}
                </Button>
              </div>
            </div>

            {test.error && (
              <div className="tw:mt-4">
                <Notice tone="error">
                  {apiErrorMessage(test.error, 'The test could not be run.')}
                </Notice>
              </div>
            )}

            {probe && (
              <div className="tw:mt-4">
                <Notice
                  tone={
                    !probe.reachable
                      ? 'error'
                      : probe.versionMatches
                        ? 'success'
                        : 'warning'
                  }>
                  {probe.message}
                  {probe.reachable && (
                    <>
                      {' '}
                      Reported version {probe.version}, in {probe.tookMs} ms.
                    </>
                  )}
                </Notice>
              </div>
            )}

            {run.error && (
              <div className="tw:mt-4">
                <Notice tone="error">
                  {apiErrorMessage(run.error, 'The crawl could not be started.')}
                </Notice>
              </div>
            )}

            {crawl && (
              <div className="tw:mt-4">
                <Notice tone="success">
                  Crawl finished — {summarise(crawl)}.
                </Notice>
              </div>
            )}

            <dl className="tw:mt-5 tw:grid tw:gap-x-8 tw:gap-y-3 tw:text-sm tw:sm:grid-cols-2">
              <Pair label="Client generated from">
                {data.expectedVersion}
                {data.failOnVersionMismatch ? ' · startup fails on a mismatch' : ''}
              </Pair>
              <Pair label="Timeouts">
                {data.connectTimeoutMs} ms connect · {data.readTimeoutMs} ms read
              </Pair>
            </dl>
          </section>

          <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
            <h2 className="tw:text-md tw:font-semibold tw:text-primary">
              Credentials
            </h2>
            <p className="tw:mt-1 tw:text-sm tw:text-tertiary">
              Set in the environment of the service, never here. This page reports
              whether each one is present; nothing in the platform can read one
              back out.
            </p>

            <div className="tw:mt-4 tw:space-y-3">
              <Secret
                configured={data.tokenConfigured}
                detail="Read-only bot token. A human account's token carries that person's permissions and expires when they leave, which turns an offboarding into a catalog outage."
                name="Bot token"
                variable={data.tokenSource}
              />
              <Secret
                configured={data.webhookSecretConfigured}
                detail={`The whole authentication of ${data.webhookPath} — the one endpoint with no session behind it. Without it every delivery is refused.`}
                name="Webhook secret"
                variable={data.webhookSecretSource}
              />
            </div>

            <dl className="tw:mt-5 tw:grid tw:gap-x-8 tw:gap-y-3 tw:text-sm tw:sm:grid-cols-2">
              <Pair label="Webhook endpoint">
                <span className="tw:font-mono tw:text-xs">{data.webhookPath}</span>
              </Pair>
              <Pair label="Signature header">
                <span className="tw:font-mono tw:text-xs">
                  {data.webhookSignatureHeader}
                </span>
              </Pair>
            </dl>
          </section>

          <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
            <h2 className="tw:text-md tw:font-semibold tw:text-primary">
              Keeping the copy current
            </h2>
            <p className="tw:mt-1 tw:text-sm tw:text-tertiary">
              Three mechanisms, on purpose (FR-1.5). The webhook is one delivery
              attempt to a service that may be restarting; the poller is what
              makes missing one a delay rather than a permanently wrong cache;
              the nightly reconcile is what catches everything both of them lost.
            </p>

            <dl className="tw:mt-4 tw:grid tw:gap-x-8 tw:gap-y-3 tw:text-sm tw:sm:grid-cols-2">
              <Pair label="Change-feed poller">
                {data.pollEnabled
                  ? `Every ${data.pollIntervalSeconds}s, up to ${data.maxEventsPerPoll} events a tick`
                  : 'Off'}
              </Pair>
              <Pair label="Nightly reconcile">
                {data.reconcileEnabled
                  ? `${data.reconcileAt} ${data.reconcileZone}`
                  : 'Off'}
              </Pair>
              <Pair label="Sync status">
                <SyncBadge status={data.sync.status} />
              </Pair>
              <Pair label="Last full crawl">
                {formatWhen(data.sync.lastFullCrawlAt)}
              </Pair>
              <Pair label="Last reconcile">
                {formatWhen(data.sync.lastReconcileAt)}
              </Pair>
              <Pair label="Newest change event">
                {data.sync.lastEventTs
                  ? formatWhen(new Date(data.sync.lastEventTs).toISOString())
                  : '—'}
              </Pair>
            </dl>

            {data.sync.lastError && (
              <div className="tw:mt-4">
                <Notice tone="error">{data.sync.lastError}</Notice>
              </div>
            )}
          </section>

          <p className="tw:text-xs tw:text-quaternary">
            Configuration source: {data.configSource}. Changing any of it is a
            restart of the service, which is why none of it is editable from a
            browser.
          </p>
        </>
      )}
    </div>
  );
}

function Header() {
  return (
    <header>
      <h1 className="tw:text-display-xs tw:font-semibold tw:text-primary">
        OpenMetadata connection
      </h1>
      <p className="tw:mt-1 tw:max-w-3xl tw:text-balance tw:text-sm tw:text-tertiary">
        Where this platform reads its metadata from. Assets, tags, glossary terms
        and domains are a cache of that instance — policies are ours, the
        vocabulary they are written in is theirs.
      </p>
    </header>
  );
}

function Secret({
  name,
  variable,
  configured,
  detail,
}: {
  name: string;
  variable: string;
  configured: boolean;
  detail: string;
}) {
  return (
    <div className="tw:flex tw:items-start tw:gap-3 tw:rounded-lg tw:border tw:border-secondary tw:p-3">
      <Key01 className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
      <div className="tw:min-w-0 tw:flex-1">
        <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          <span className="tw:text-sm tw:font-medium tw:text-primary">{name}</span>
          <span className="tw:font-mono tw:text-xs tw:text-tertiary">
            {variable}
          </span>
          <Badge
            color={configured ? 'success' : 'error'}
            size="sm"
            type="pill-color">
            {configured ? 'Set' : 'Not set'}
          </Badge>
        </div>
        <p className="tw:mt-1 tw:text-xs tw:text-tertiary">{detail}</p>
      </div>
    </div>
  );
}

function SyncBadge({ status }: { status: string }) {
  const color =
    status === 'RUNNING'
      ? 'brand'
      : status === 'FAILED'
        ? 'error'
        : status === 'NEVER_RUN'
          ? 'warning'
          : 'success';

  return (
    <Badge color={color} size="sm" type="pill-color">
      {status.toLowerCase().replace('_', ' ')}
    </Badge>
  );
}

function Pair({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="tw:text-xs tw:text-tertiary">{label}</dt>
      <dd className="tw:mt-0.5 tw:text-sm tw:text-primary">{children}</dd>
    </div>
  );
}

function Notice({
  tone,
  children,
}: {
  tone: 'success' | 'warning' | 'error';
  children: ReactNode;
}) {
  const Icon = tone === 'success' ? CheckCircle : AlertTriangle;
  const classes =
    tone === 'success'
      ? 'tw:border-success tw:bg-success-primary tw:text-success-primary'
      : tone === 'warning'
        ? 'tw:border-warning tw:bg-warning-primary tw:text-warning-primary'
        : 'tw:border-error tw:bg-error-primary tw:text-error-primary';

  return (
    <p
      className={`tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:border tw:p-3 tw:text-sm ${classes}`}>
      <Icon className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
      <span>{children}</span>
    </p>
  );
}

/** What a crawl actually wrote, rather than "done". */
function summarise(result: CrawlResult): string {
  const parts: string[] = [];
  const add = (count: number | undefined, noun: string) => {
    if (count !== undefined) {
      parts.push(`${count} ${noun}`);
    }
  };
  add(result.tables, 'tables');
  add(result.columns, 'columns');
  add(result.tags, 'tags');
  add(result.glossaryTerms, 'glossary terms');
  add(result.domains, 'domains');
  add(result.facets, 'facet rows');
  return parts.length > 0 ? parts.join(', ') : 'nothing changed';
}

function formatWhen(iso: string | null | undefined): string {
  if (!iso) {
    return 'never';
  }
  const when = new Date(iso);
  if (Number.isNaN(when.getTime())) {
    return 'never';
  }
  return when.toLocaleString();
}
