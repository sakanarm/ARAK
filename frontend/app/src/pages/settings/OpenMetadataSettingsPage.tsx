import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  CheckCircle,
  ClockRefresh,
  Key01,
  RefreshCcw01,
  Zap,
} from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../../api/client';
import {
  fetchOpenMetadataSettings,
  fetchSyncSchedule,
  saveOpenMetadataSettings,
  saveSyncSchedule,
  startCrawl,
  testOpenMetadata,
  type ConnectionProbe,
  type CrawlResult,
  type OpenMetadataEdit,
  type OpenMetadataSettings,
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
 * The page reads it, proves it, and writes it. Both of the things an estate
 * actually changes are changeable here: where the instance lives, and the
 * credentials used to read it. Neither was, until recently, and the symptom of
 * that was an administrator looking at a wrong address with no way on any
 * screen to correct it.
 *
 * Writing a secret and reading one back are not the same permission, and only
 * the first is offered. Each secret is shown as present or absent — never a
 * value, a prefix or a length — and a field left empty keeps the one in force,
 * so moving the URL does not cost a token and tabbing through the form does not
 * clear one. An estate that injects its token through the environment keeps
 * doing exactly that, untouched.
 *
 * The rest of the page is the evidence somebody debugging a policy needs: does
 * the instance answer, what version does it report, how old is the copy, and
 * when does the unattended crawl run.
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
                  onPress={() => test.mutate(undefined)}
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
              Replaceable below, never readable. This page reports whether each
              one is present; nothing in the platform will read one back out,
              here or anywhere else.
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

          <ConnectionEditor data={data} />

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

            <ScheduleEditor />

            {data.sync.lastError && (
              <div className="tw:mt-4">
                <Notice tone="error">{data.sync.lastError}</Notice>
              </div>
            )}
          </section>

          <p className="tw:text-xs tw:text-quaternary">
            Configuration source: {data.configSource}.
            {data.isDefault
              ? ' Nothing here has been changed from what the file asked for.'
              : ` Last changed ${formatWhen(data.updatedAt)}${
                  data.updatedBy ? ` by ${data.updatedBy}` : ''
                }.`}{' '}
            The connection, the credentials and the reconcile schedule are
            stored, so changing any of them is a save rather than a restart. The
            poller's cadence is still the service file.
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
      <p className="tw:mt-1 tw:max-w-3xl tw:text-pretty tw:text-sm tw:text-tertiary">
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

const INPUT =
  'tw:w-full tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2.5 tw:py-2 tw:text-sm tw:text-primary tw:placeholder:text-quaternary';

/**
 * Where the platform reads its metadata from (FR-1.1).
 *
 * This used to be a redeploy. An estate moves its instance, promotes a second
 * environment or renames a host, and under the old design the only visible
 * symptom was an "Open in OpenMetadata" button that quietly stopped appearing,
 * with nothing on any screen to fix it.
 *
 * Two rules shape the form. The first is that a secret field left empty keeps
 * the stored one: it sends {@code undefined}, never {@code ''}, because an
 * empty string reads as "clear it" and that is how an estate loses its bot
 * token by tabbing through a form. The second is that an address can be proved
 * before it is saved — probing what is already in force can only tell you the
 * crawl has already stopped.
 */
function ConnectionEditor({ data }: { data: OpenMetadataSettings }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);

  // Seeded from the server once rather than on every render, and reseeded on a
  // discard: a refetch while somebody is halfway through typing a host would
  // take it back off them.
  const [touched, setTouched] = useState(false);
  const [baseUrl, setBaseUrl] = useState(data.baseUrl);
  const [expectedVersion, setExpectedVersion] = useState(data.expectedVersion);
  const [failOnMismatch, setFailOnMismatch] = useState(
    data.failOnVersionMismatch
  );
  const [connectMs, setConnectMs] = useState(String(data.connectTimeoutMs));
  const [readMs, setReadMs] = useState(String(data.readTimeoutMs));
  const [token, setToken] = useState('');
  const [webhookSecret, setWebhookSecret] = useState('');
  const [reason, setReason] = useState('');
  const [probe, setProbe] = useState<ConnectionProbe | null>(null);

  useEffect(() => {
    if (!touched) {
      setBaseUrl(data.baseUrl);
      setExpectedVersion(data.expectedVersion);
      setFailOnMismatch(data.failOnVersionMismatch);
      setConnectMs(String(data.connectTimeoutMs));
      setReadMs(String(data.readTimeoutMs));
    }
  }, [data, touched]);

  const save = useMutation({
    mutationFn: saveOpenMetadataSettings,
    onSuccess: (next) => {
      queryClient.setQueryData(['om-settings'], next);
      setToken('');
      setWebhookSecret('');
      setReason('');
      setProbe(null);
      setTouched(false);
      // Repointing the catalog changes what every asset, tag and term on screen
      // refers to. Nothing read from the old instance is safe to keep showing.
      queryClient.invalidateQueries({ queryKey: ['sync-status'] });
      queryClient.invalidateQueries({ queryKey: ['vocabulary'] });
      queryClient.invalidateQueries({ queryKey: ['catalog'] });
    },
  });

  const trial = useMutation({
    mutationFn: testOpenMetadata,
    onSuccess: (result) => setProbe(result),
  });

  // A blank or nonsensical box keeps what is in force rather than sending a
  // zero, which the server would have to refuse anyway.
  const positive = (value: string, fallback: number): number => {
    const parsed = Number(value);
    return Number.isFinite(parsed) && parsed > 0 ? Math.trunc(parsed) : fallback;
  };

  const edit: OpenMetadataEdit = {
    baseUrl: baseUrl.trim(),
    expectedVersion: expectedVersion.trim(),
    failOnVersionMismatch: failOnMismatch,
    connectTimeoutMs: positive(connectMs, data.connectTimeoutMs),
    readTimeoutMs: positive(readMs, data.readTimeoutMs),
    jwtToken: token === '' ? undefined : token,
    webhookSecret: webhookSecret === '' ? undefined : webhookSecret,
    reason: reason.trim() === '' ? undefined : reason.trim(),
  };

  const dirty =
    edit.baseUrl !== data.baseUrl ||
    edit.expectedVersion !== data.expectedVersion ||
    failOnMismatch !== data.failOnVersionMismatch ||
    edit.connectTimeoutMs !== data.connectTimeoutMs ||
    edit.readTimeoutMs !== data.readTimeoutMs ||
    token !== '' ||
    webhookSecret !== '';

  const change = (apply: () => void) => {
    setTouched(true);
    setProbe(null);
    apply();
  };

  const discard = () => {
    setToken('');
    setWebhookSecret('');
    setReason('');
    setProbe(null);
    setTouched(false);
  };

  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
      <div className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-3">
        <div className="tw:min-w-0">
          <h2 className="tw:text-md tw:font-semibold tw:text-primary">
            Change the connection
          </h2>
          <p className="tw:mt-1 tw:max-w-2xl tw:text-sm tw:text-tertiary">
            Pointing the platform at another instance, or replacing a credential
            that was rotated upstream. A save takes effect at once — the client
            is repointed in the same call, so there is no restart in which a
            wrong address still looks like a working one.
          </p>
        </div>
        <Button
          color="secondary"
          isDisabled={save.isPending}
          onPress={() => {
            if (open) {
              discard();
            }
            setOpen(!open);
          }}
          size="sm">
          {open ? 'Cancel' : 'Edit'}
        </Button>
      </div>

      {open && (
        <div className="tw:mt-5 tw:space-y-4 tw:border-t tw:border-secondary tw:pt-5">
          <Field
            hint="The root of the instance. Not the /api path — the client appends that itself."
            htmlFor="om-base-url"
            label="Base URL">
            <input
              className={INPUT}
              id="om-base-url"
              onChange={(event) => change(() => setBaseUrl(event.target.value))}
              placeholder="https://openmetadata.example.com"
              value={baseUrl}
            />
          </Field>

          <div className="tw:grid tw:gap-4 tw:sm:grid-cols-3">
            <Field
              hint="The spec this client was generated from."
              htmlFor="om-expected-version"
              label="Expected version">
              <input
                className={INPUT}
                id="om-expected-version"
                onChange={(event) =>
                  change(() => setExpectedVersion(event.target.value))
                }
                placeholder="2.0.1"
                value={expectedVersion}
              />
            </Field>
            <Field htmlFor="om-connect-ms" label="Connect timeout (ms)">
              <input
                className={INPUT}
                id="om-connect-ms"
                inputMode="numeric"
                onChange={(event) =>
                  change(() => setConnectMs(event.target.value))
                }
                value={connectMs}
              />
            </Field>
            <Field htmlFor="om-read-ms" label="Read timeout (ms)">
              <input
                className={INPUT}
                id="om-read-ms"
                inputMode="numeric"
                onChange={(event) => change(() => setReadMs(event.target.value))}
                value={readMs}
              />
            </Field>
          </div>

          <div className="tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-3">
            <Toggle
              checked={failOnMismatch}
              label={
                failOnMismatch
                  ? 'Refuse to start on a version mismatch'
                  : 'Start anyway on a version mismatch'
              }
              onChange={(next) => change(() => setFailOnMismatch(next))}
            />
            <p className="tw:mt-2 tw:text-xs tw:text-tertiary">
              A mismatch means fields the crawler reads may have been renamed or
              removed. Refusing to start turns that into an outage somebody
              notices; starting anyway turns it into a cache that is quietly
              missing things.
            </p>
          </div>

          <div className="tw:grid tw:gap-4 tw:sm:grid-cols-2">
            <Field
              hint={
                data.tokenConfigured
                  ? 'One is stored. Leave this empty to keep it.'
                  : 'None is stored. The crawl cannot run until one is.'
              }
              htmlFor="om-token"
              label="Bot token">
              <input
                autoComplete="new-password"
                className={INPUT}
                id="om-token"
                onChange={(event) => change(() => setToken(event.target.value))}
                placeholder={data.tokenConfigured ? 'unchanged' : 'eyJ…'}
                type="password"
                value={token}
              />
            </Field>
            <Field
              hint={
                data.webhookSecretConfigured
                  ? 'One is stored. Leave this empty to keep it.'
                  : 'None is stored. Every webhook delivery is being refused.'
              }
              htmlFor="om-webhook-secret"
              label="Webhook secret">
              <input
                autoComplete="new-password"
                className={INPUT}
                id="om-webhook-secret"
                onChange={(event) =>
                  change(() => setWebhookSecret(event.target.value))
                }
                placeholder={
                  data.webhookSecretConfigured ? 'unchanged' : 'not set'
                }
                type="password"
                value={webhookSecret}
              />
            </Field>
          </div>

          <Field
            hint="Written to the audit trail beside the address. The credential never is."
            htmlFor="om-reason"
            label="Reason (optional)">
            <input
              className={INPUT}
              id="om-reason"
              onChange={(event) => setReason(event.target.value)}
              placeholder="Moved to the new host after the migration"
              value={reason}
            />
          </Field>

          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <Button
              color="secondary"
              iconLeading={Zap}
              isDisabled={trial.isPending || baseUrl.trim() === ''}
              onPress={() => trial.mutate(edit)}
              size="sm">
              {trial.isPending ? 'Testing…' : 'Test before saving'}
            </Button>
            <Button
              color="primary"
              isDisabled={!dirty || save.isPending || baseUrl.trim() === ''}
              onPress={() => save.mutate(edit)}
              size="sm">
              {save.isPending ? 'Saving…' : 'Save the connection'}
            </Button>
            {dirty && (
              <Button
                color="tertiary"
                isDisabled={save.isPending}
                onPress={discard}
                size="sm">
                Discard
              </Button>
            )}
          </div>

          {trial.error && (
            <Notice tone="error">
              {apiErrorMessage(trial.error, 'The test could not be run.')}
            </Notice>
          )}

          {probe && (
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
                  Reported version {probe.version}, in {probe.tookMs} ms. Nothing
                  has been saved yet.
                </>
              )}
            </Notice>
          )}

          {save.error && (
            <Notice tone="error">
              {apiErrorMessage(save.error, 'The connection could not be saved.')}
            </Notice>
          )}
        </div>
      )}
    </section>
  );
}

/** A labelled box, with the sentence that stops somebody guessing at it. */
function Field({
  label,
  htmlFor,
  hint,
  children,
}: {
  label: string;
  htmlFor: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <div>
      <label
        className="tw:text-xs tw:font-medium tw:text-secondary"
        htmlFor={htmlFor}>
        {label}
      </label>
      <div className="tw:mt-1.5">{children}</div>
      {hint && <p className="tw:mt-1 tw:text-xs tw:text-tertiary">{hint}</p>}
    </div>
  );
}

/**
 * When the nightly crawl runs (FR-1.5).
 *
 * The hour used to be an environment variable, which made "move it an hour
 * later, the warehouse load shifted" a deploy and an operator. Nobody ever did
 * it. It lives in the database now and the running service re-reads it before
 * every booking, so a save moves tonight's crawl rather than next week's.
 *
 * Three different times are printed here on purpose, because they answer three
 * questions people have been caught out by: what is stored, what the service
 * file asked for before anybody touched it, and what is actually booked. A
 * schedule can be switched on in a process that is not running the backstop at
 * all, and the only honest way to say so is to print the booking.
 */
function ScheduleEditor() {
  const queryClient = useQueryClient();
  const { data, error, isLoading } = useQuery({
    queryKey: ['om-sync-schedule'],
    queryFn: fetchSyncSchedule,
    retry: false,
  });

  // Seeded from the server once rather than on every render: a refetch while
  // somebody is halfway through typing an hour would take it back off them.
  const [at, setAt] = useState('');
  const [zone, setZone] = useState('');
  const [touched, setTouched] = useState(false);

  useEffect(() => {
    if (data && !touched) {
      setAt(data.at.slice(0, 5));
      setZone(data.zone);
    }
  }, [data, touched]);

  const save = useMutation({
    mutationFn: saveSyncSchedule,
    onSuccess: (next) => {
      queryClient.setQueryData(['om-sync-schedule'], next);
      setTouched(false);
    },
  });

  // Offered, not required. The server validates the zone regardless, and a
  // browser too old to list them should still let somebody type one in.
  const zones = useMemo(() => {
    try {
      const supported = (
        Intl as unknown as { supportedValuesOf?: (key: string) => string[] }
      ).supportedValuesOf;
      return supported ? supported('timeZone') : [];
    } catch {
      return [];
    }
  }, []);

  if (isLoading) {
    return (
      <p className="tw:mt-5 tw:text-sm tw:text-tertiary">
        Reading the schedule…
      </p>
    );
  }

  if (error || !data) {
    return (
      <div className="tw:mt-5">
        <Notice tone="error">
          {apiErrorMessage(error, 'Could not read the reconcile schedule.')}
        </Notice>
      </div>
    );
  }

  const stored = data.at.slice(0, 5);
  const fileAt = data.configuredAt.slice(0, 5);
  const dirty = at !== stored || zone !== data.zone;
  const restorable =
    !data.isDefault && (stored !== fileAt || data.zone !== data.configuredZone);

  return (
    <div className="tw:mt-5 tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary tw:p-4">
      <div className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-3">
        <div className="tw:min-w-0">
          <h3 className="tw:flex tw:items-center tw:gap-2 tw:text-sm tw:font-semibold tw:text-primary">
            <ClockRefresh className="tw:size-4 tw:shrink-0 tw:text-tertiary" />
            Nightly reconcile
          </h3>
          <p className="tw:mt-1 tw:text-xs tw:text-tertiary">
            {data.enabled
              ? `Runs at ${stored}, local time in ${data.zone}.`
              : 'Off. Until somebody runs a crawl, nothing will retire an asset that vanished without an event.'}
          </p>
        </div>
        <Toggle
          checked={data.enabled}
          label={data.enabled ? 'On' : 'Off'}
          onChange={(enabled) => save.mutate({ enabled })}
        />
      </div>

      <div className="tw:mt-4 tw:flex tw:flex-wrap tw:items-end tw:gap-3">
        <div>
          <label
            className="tw:text-xs tw:font-medium tw:text-secondary"
            htmlFor="reconcile-at">
            Time of day
          </label>
          <input
            className={`${INPUT} tw:mt-1.5 tw:w-32`}
            id="reconcile-at"
            onChange={(event) => {
              setTouched(true);
              setAt(event.target.value);
            }}
            type="time"
            value={at}
          />
        </div>

        <div className="tw:min-w-56 tw:flex-1">
          <label
            className="tw:text-xs tw:font-medium tw:text-secondary"
            htmlFor="reconcile-zone">
            Time zone
          </label>
          <input
            className={`${INPUT} tw:mt-1.5`}
            id="reconcile-zone"
            list="reconcile-zones"
            onChange={(event) => {
              setTouched(true);
              setZone(event.target.value);
            }}
            placeholder="Asia/Bangkok"
            value={zone}
          />
          <datalist id="reconcile-zones">
            {zones.map((id) => (
              <option key={id} value={id} />
            ))}
          </datalist>
        </div>

        <Button
          color="primary"
          isDisabled={!dirty || save.isPending}
          onPress={() => save.mutate({ at, zone })}
          size="sm">
          {save.isPending ? 'Saving…' : 'Save the time'}
        </Button>

        {restorable && (
          <Button
            color="tertiary"
            isDisabled={save.isPending}
            onPress={() =>
              save.mutate({
                at: data.configuredAt,
                zone: data.configuredZone,
              })
            }
            size="sm">
            Back to {fileAt} {data.configuredZone}
          </Button>
        )}
      </div>

      {save.error && (
        <div className="tw:mt-3">
          <Notice tone="error">
            {apiErrorMessage(save.error, 'The schedule could not be saved.')}
          </Notice>
        </div>
      )}

      <dl className="tw:mt-4 tw:grid tw:gap-x-8 tw:gap-y-3 tw:text-sm tw:sm:grid-cols-2">
        <Pair label="Next run">
          {data.managed
            ? formatWhen(data.nextRunAt)
            : 'Not booked here'}
        </Pair>
        <Pair label="Schedule last changed">
          {data.isDefault
            ? `Never — still what the service file asked for (${fileAt} ${data.configuredZone})`
            : `${formatWhen(data.updatedAt)}${data.updatedBy ? ` by ${data.updatedBy}` : ''}`}
        </Pair>
      </dl>

      {data.enabled && !data.managed && (
        <div className="tw:mt-3">
          <Notice tone="warning">
            The schedule is on, but nothing in this service is running it. The
            time is stored and an instance that does run the backstop will
            honour it; this one will not crawl on its own.
          </Notice>
        </div>
      )}

      <p className="tw:mt-3 tw:text-xs tw:text-quaternary">
        Local time in the zone named, never UTC, so it stays half past two in
        the morning across a daylight-saving change. A save takes effect at
        once: the pending crawl is rebooked without a restart.
      </p>
    </div>
  );
}

/**
 * A switch.
 *
 * A button with {@code role="switch"} rather than a checkbox, because pressing
 * it saves — there is no form to submit — and a switch is what tells a screen
 * reader that.
 */
function Toggle({
  checked,
  label,
  onChange,
}: {
  checked: boolean;
  label: string;
  onChange: (value: boolean) => void;
}) {
  return (
    <button
      aria-checked={checked}
      className="tw:flex tw:shrink-0 tw:cursor-pointer tw:items-center tw:gap-2.5"
      onClick={() => onChange(!checked)}
      role="switch"
      type="button">
      <span
        className={`tw:relative tw:h-5 tw:w-9 tw:shrink-0 tw:rounded-full tw:transition ${
          checked ? 'tw:bg-brand-solid' : 'tw:bg-quaternary'
        }`}>
        <span
          className={`tw:absolute tw:top-0.5 tw:size-4 tw:rounded-full tw:bg-primary tw:transition ${
            checked ? 'tw:left-4.5' : 'tw:left-0.5'
          }`}
        />
      </span>
      <span className="tw:text-sm tw:text-secondary">{label}</span>
    </button>
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
