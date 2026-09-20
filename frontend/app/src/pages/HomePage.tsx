import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import {
  AlertTriangle,
  BookOpen01,
  Database01,
  Plus,
  Server01,
  ShieldTick,
  Tag01,
  User03,
} from '@untitledui/icons';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { fetchCatalogSummary, fetchSystemVersion } from '../api/client';
import { fetchVocabulary } from '../api/governance';
import { fetchPolicies } from '../api/policies';
import { fetchSources } from '../api/sources';
import { useAuthStore } from '../auth/authStore';
import { humaniseRole } from '../layout/AppShell';
import { Meter, Stat, Widget, WidgetEmpty, relativeTime } from '../components/widgets';
import mark from '../assets/arak-mark.png';

/**
 * Landing view, laid out as OpenMetadata lays out its own.
 *
 * A greeting banner, then a grid of widgets: the wide one carries the stream of
 * recent work, the narrow column carries the counts. The shape is deliberate —
 * somebody who administers the catalog should not have to learn a second
 * dashboard to administer access to it.
 *
 * Every number here is read from an endpoint. Widgets whose milestone has not
 * landed are not shown as zeroes, because a zero is a claim; they say what they
 * are waiting on instead. A console that displays counts it cannot stand behind
 * teaches people to stop reading them.
 */
export default function HomePage() {
  const user = useAuthStore((state) => state.user);

  const { data: system } = useQuery({
    queryKey: ['system-version'],
    queryFn: fetchSystemVersion,
    retry: false,
  });
  const { data: catalog } = useQuery({
    queryKey: ['catalog-summary'],
    queryFn: fetchCatalogSummary,
    retry: false,
  });
  const { data: vocabulary } = useQuery({
    queryKey: ['vocabulary'],
    queryFn: fetchVocabulary,
    retry: false,
  });
  const { data: policies } = useQuery({
    queryKey: ['policies', 'recent'],
    queryFn: () => fetchPolicies({ limit: 6 }),
    retry: false,
  });
  const { data: sources } = useQuery({
    queryKey: ['sources'],
    queryFn: fetchSources,
    retry: false,
  });

  const byType = catalog?.assetsByType ?? {};
  const tables = (byType.TABLE ?? 0) + (byType.VIEW ?? 0);
  const containers =
    (byType.SERVICE ?? 0) + (byType.DATABASE ?? 0) + (byType.SCHEMA ?? 0);

  const active = (policies ?? []).filter(
    (policy) => policy.lifecycleState === 'ACTIVE'
  ).length;

  return (
    <div className="tw:flex tw:flex-col tw:gap-5">
      <Greeting
        name={user?.displayName || user?.username || ''}
        roles={user?.roles ?? []}
      />

      <div className="tw:grid tw:gap-5 tw:xl:grid-cols-3">
        {/* ---------------------------------------------------- wide column */}
        <div className="tw:flex tw:flex-col tw:gap-5 tw:xl:col-span-2">
          <Widget
            action={{ label: 'All policies', to: '/policies' }}
            count={policies?.length ?? 0}
            title="Recent policies">
            {policies === undefined ? (
              <Loading />
            ) : policies.length === 0 ? (
              <WidgetEmpty
                action={{ label: 'Write the first one', to: '/policies/new' }}
                icon={ShieldTick}
                line="No policy has been written yet. Until one is, nothing is granted and nothing is masked — the platform denies by default."
              />
            ) : (
              <ul className="tw:divide-y tw:divide-secondary">
                {policies.map((policy) => (
                  <li key={policy.id}>
                    <Link
                      className="tw:flex tw:items-start tw:gap-3 tw:-mx-2 tw:rounded-lg tw:px-2 tw:py-3 tw:transition tw:hover:bg-secondary"
                      to={`/policies/${policy.id}`}>
                      <span
                        aria-hidden
                        className="tw:mt-0.5 tw:flex tw:size-8 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-utility-brand-50">
                        <ShieldTick className="tw:size-4 tw:text-fg-brand-primary" />
                      </span>
                      <span className="tw:min-w-0 tw:flex-1">
                        <span className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
                          <span className="tw:truncate tw:text-sm tw:font-medium tw:text-primary">
                            {policy.document.displayName || policy.document.name}
                          </span>
                          <Badge
                            color={stateColor(policy.lifecycleState)}
                            size="sm"
                            type="pill-color">
                            {humanise(policy.lifecycleState)}
                          </Badge>
                          <Badge color="gray" size="sm" type="pill-color">
                            {policy.document.policyType === 'DATA'
                              ? 'Data'
                              : 'Subscription'}
                          </Badge>
                        </span>
                        <span className="tw:mt-0.5 tw:block tw:truncate tw:text-xs tw:text-tertiary">
                          {policy.document.scopeLevel}
                          {policy.document.scopeFqn
                            ? ` · ${policy.document.scopeFqn}`
                            : ''}{' '}
                          · v{policy.version} · {policy.updatedBy}{' '}
                          {relativeTime(policy.updatedAt)}
                        </span>
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Widget>

          <Widget
            action={{ label: 'Open catalog', to: '/catalog' }}
            title="Governance coverage">
            {catalog === undefined ? (
              <Loading />
            ) : tables === 0 ? (
              <WidgetEmpty
                action={{ label: 'Check the sync', to: '/system' }}
                icon={Database01}
                line="Nothing has been crawled from OpenMetadata yet, so no policy can bind to anything."
              />
            ) : (
              <>
                <div className="tw:grid tw:grid-cols-2 tw:gap-6 tw:sm:grid-cols-4">
                  <Stat label="Tables and views" value={tables.toLocaleString()} />
                  <Stat
                    label="Columns"
                    value={catalog.columns.toLocaleString()}
                  />
                  <Stat
                    hint="service, database, schema"
                    label="Containers"
                    value={containers.toLocaleString()}
                  />
                  <Stat label="Policies active" value={active} />
                </div>
                <div className="tw:mt-5 tw:flex tw:flex-col tw:gap-4">
                  <Meter
                    label="Assets carrying at least one tag or term"
                    total={tables}
                    value={catalog.taggedAssets}
                  />
                  <Meter
                    label="Columns carrying at least one tag or term"
                    total={catalog.columns}
                    value={catalog.taggedColumns}
                  />
                  <Meter
                    label="Assets with no owner in OpenMetadata"
                    tone="warning"
                    total={tables}
                    value={catalog.assetsWithoutOwner}
                  />
                </div>
                {catalog.assetsWithoutOwner > 0 && (
                  <p className="tw:mt-4 tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:bg-utility-warning-50 tw:px-3 tw:py-2 tw:text-xs tw:text-secondary">
                    <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-utility-warning-600" />
                    <span>
                      An asset with no owner has nobody who may write a local
                      policy over it, and nobody to route a request to later. It
                      is governed only by whatever reaches it from above.
                    </span>
                  </p>
                )}
              </>
            )}
          </Widget>
        </div>

        {/* -------------------------------------------------- narrow column */}
        <div className="tw:flex tw:flex-col tw:gap-5">
          <Widget
            action={{ label: 'Manage', to: '/sources' }}
            count={sources?.length ?? 0}
            title="Sources">
            {sources === undefined ? (
              <Loading />
            ) : sources.length === 0 ? (
              <WidgetEmpty
                action={{ label: 'Register a source', to: '/sources' }}
                icon={Server01}
                line="No database is registered yet. A source is where enforcement lands, so nothing can be applied until one exists."
              />
            ) : (
              <ul className="tw:flex tw:flex-col tw:gap-3">
                {sources.slice(0, 5).map((source) => (
                  <li
                    className="tw:flex tw:items-start tw:gap-3"
                    key={source.id}>
                    <span
                      aria-hidden
                      className="tw:mt-0.5 tw:flex tw:size-8 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-secondary">
                      <Server01 className="tw:size-4 tw:text-fg-quaternary" />
                    </span>
                    <span className="tw:min-w-0 tw:flex-1">
                      <span className="tw:flex tw:items-center tw:gap-2">
                        <span className="tw:truncate tw:text-sm tw:font-medium tw:text-primary">
                          {source.name}
                        </span>
                        {!source.enabled && (
                          <Badge color="gray" size="sm" type="pill-color">
                            Disabled
                          </Badge>
                        )}
                      </span>
                      <span className="tw:mt-0.5 tw:block tw:truncate tw:text-xs tw:text-tertiary">
                        {source.engine === 'SQLSERVER'
                          ? 'SQL Server'
                          : 'PostgreSQL'}{' '}
                        · {humanise(source.defaultEnforcementMode)} ·{' '}
                        {source.assetCount.toLocaleString()} assets
                      </span>
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </Widget>

          <Widget
            action={{ label: 'Browse', to: '/governance' }}
            title="Governance vocabulary">
            {vocabulary === undefined ? (
              <Loading />
            ) : (
              <dl className="tw:flex tw:flex-col tw:gap-2.5 tw:text-sm">
                <VocabRow
                  icon={Tag01}
                  label="Classifications"
                  value={vocabulary.classifications?.length ?? 0}
                />
                <VocabRow
                  icon={BookOpen01}
                  label="Glossaries"
                  value={vocabulary.glossaries?.length ?? 0}
                />
                <VocabRow
                  icon={Database01}
                  label="Domains"
                  value={vocabulary.domains?.length ?? 0}
                />
                <VocabRow
                  icon={User03}
                  label="Custom properties"
                  value={vocabulary.customProperties?.length ?? 0}
                />
              </dl>
            )}
          </Widget>

          <Widget action={{ label: 'Details', to: '/system' }} title="Platform">
            <dl className="tw:flex tw:flex-col tw:gap-2.5 tw:text-sm">
              <SystemRow label="Service" value={system?.version ?? '—'} />
              <SystemRow
                label="OpenMetadata"
                value={system?.openMetadataExpectedVersion ?? '—'}
              />
              <SystemRow
                label="Instance"
                value={system?.openMetadataBaseUrl ?? '—'}
              />
            </dl>
            <p className="tw:mt-3 tw:text-xs tw:text-quaternary">
              The catalog is read-only here. Tags, terms and domains are owned by
              OpenMetadata; policy and enforcement are owned by this platform.
            </p>
          </Widget>
        </div>
      </div>
    </div>
  );
}

/**
 * The banner OpenMetadata opens with, in our colours.
 *
 * It carries the two actions somebody signing in actually wants, because a
 * greeting that only greets is a row of pixels charged to the top of every
 * session.
 */
function Greeting({ name, roles }: { name: string; roles: string[] }) {
  const firstName = name.split(' ')[0];
  // `onPress` + navigate, never `href`: the library's Button renders a real
  // anchor when given one, and a real anchor reloads the whole app — which on
  // this page means throwing away every query the dashboard just ran.
  const navigate = useNavigate();

  return (
    <section
      className="tw:relative tw:overflow-hidden tw:rounded-2xl tw:px-6 tw:py-7 tw:sm:px-8"
      style={{
        backgroundImage:
          'linear-gradient(120deg, var(--color-brand-700, #175cd3) 0%,' +
          ' var(--color-brand-600, #1570ef) 45%,' +
          ' var(--color-brand-500, #2e90fa) 100%)',
      }}>
      <img
        aria-hidden
        alt=""
        className="tw:pointer-events-none tw:absolute tw:-right-6 tw:-bottom-10 tw:hidden tw:size-56 tw:opacity-15 tw:sm:block"
        src={mark}
      />

      <div className="tw:relative tw:max-w-2xl">
        <p className="tw:text-sm tw:font-medium tw:text-white/80">
          Data access control
        </p>
        <h1 className="tw:mt-1 tw:text-display-sm tw:font-semibold tw:text-white">
          {firstName ? `Welcome back, ${firstName}` : 'Welcome'}
        </h1>
        <p className="tw:mt-2 tw:text-md tw:text-white/85">
          Subscription and data policies over your OpenMetadata governance —
          composed from organisation down to a single column, and enforced in the
          database itself.
        </p>

        {roles.length > 0 && (
          <div className="tw:mt-4 tw:flex tw:flex-wrap tw:gap-1.5">
            {roles.map((role) => (
              <span
                className="tw:rounded-full tw:bg-white/15 tw:px-2.5 tw:py-1 tw:text-xs tw:font-medium tw:text-white"
                key={role}>
                {humaniseRole(role)}
              </span>
            ))}
          </div>
        )}

        {/*
          items-center, or the link button stretches to the filled button's
          height and its label rides the top of that box instead of sitting on
          the same line as "New policy".
        */}
        <div className="tw:mt-6 tw:flex tw:flex-wrap tw:items-center tw:gap-3">
          <Button
            color="secondary"
            iconLeading={Plus}
            onPress={() => navigate('/policies/new')}
            size="md">
            New policy
          </Button>
          <Button
            className="tw:self-center"
            color="link-gray"
            onPress={() => navigate('/catalog')}
            size="md">
            <span className="tw:text-white">Explore the catalog</span>
          </Button>
        </div>
      </div>
    </section>
  );
}

function VocabRow({
  icon: Icon,
  label,
  value,
}: {
  icon: React.FC<{ className?: string }>;
  label: string;
  value: number;
}) {
  return (
    <div className="tw:flex tw:items-center tw:justify-between tw:gap-3">
      <dt className="tw:flex tw:items-center tw:gap-2 tw:text-secondary">
        <Icon className="tw:size-4 tw:text-fg-quaternary" />
        {label}
      </dt>
      <dd className="tw:font-medium tw:text-primary tw:tabular-nums">
        {value.toLocaleString()}
      </dd>
    </div>
  );
}

function SystemRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="tw:flex tw:items-baseline tw:justify-between tw:gap-3">
      <dt className="tw:shrink-0 tw:text-secondary">{label}</dt>
      <dd className="tw:truncate tw:text-right tw:font-medium tw:text-primary">
        {value}
      </dd>
    </div>
  );
}

function Loading() {
  return (
    <div className="tw:flex tw:flex-col tw:gap-3 tw:py-2">
      {[0, 1, 2].map((row) => (
        <div
          className="tw:h-9 tw:animate-pulse tw:rounded-lg tw:bg-secondary"
          key={row}
        />
      ))}
    </div>
  );
}

function stateColor(
  state: string
): 'success' | 'warning' | 'gray' | 'brand' {
  switch (state) {
    case 'ACTIVE':
      return 'success';
    case 'PENDING_APPROVAL':
      return 'warning';
    case 'DRAFT':
      return 'brand';
    default:
      return 'gray';
  }
}

/** ENUM_CASE as a sentence, which is how every label on this page reads. */
function humanise(value: string): string {
  const words = value.toLowerCase().split('_').join(' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}
