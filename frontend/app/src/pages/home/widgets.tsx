import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  Activity,
  AlertTriangle,
  BarChart01,
  BookOpen01,
  Code01,
  CpuChip01,
  Database01,
  Hourglass01,
  Link01,
  MessageTextSquare01,
  PieChart01,
  SearchLg,
  Server01,
  ShieldTick,
  Tag01,
  Ticket01,
  User03,
  VideoRecorder,
} from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { Meter, Stat, Widget, WidgetEmpty, relativeTime } from '../../components/widgets';
import { fetchCatalogSummary, fetchSystemVersion } from '../../api/client';
import { fetchVocabulary } from '../../api/governance';
import { fetchPolicies } from '../../api/policies';
import { fetchSources } from '../../api/sources';
import { fetchExpiringGrants } from '../../api/access';
import type { ExpiringGrant } from '../../api/access';
import { fetchRequestStats } from '../../api/accessRequests';
import type { TableRequestStats } from '../../api/accessRequests';
import { engineLabel, useSourceEngines } from '../../engines';
import { KIND_GROUPS, hitHref, search } from '../../api/search';
import type { HomeLink, HomeWidget, HomeWidgetType } from '../../api/home';
import { Chart, slicesOf } from './charts';

/**
 * Every widget the home page can hold, and how each one draws itself.
 *
 * Two rules shape this file.
 *
 * <p>First, a widget fetches its own data. The page does not run five queries
 * and hand them down, because then a requester's page — which holds none of the
 * governance panels — would still ask the server for policies and sources on
 * every load, and would still show a spinner for data nobody put on the page.
 * TanStack dedupes by query key, so two widgets reading the same endpoint still
 * make one request; the keys here are deliberately the same strings the rest of
 * the app uses.
 *
 * <p>Second, the five panels this page has always had are lifted across
 * unchanged. Their markup is not "close to" what it was — it is what it was.
 * The customisable page is a change to how widgets are arranged, and a person
 * who liked yesterday's home page should not be able to tell that anything was
 * rewritten underneath it.
 */

/** What the editor needs to know about a widget to offer it. */
export interface WidgetSpec {
  type: HomeWidgetType;
  /** The name in the picker, and the heading when no title is set. */
  label: string;
  /** One line on what it is for, shown in the picker. */
  blurb: string;
  icon: React.FC<{ className?: string }>;
  /** Mirrors {@code HomeLayout.WidgetType#governance()} on the server. */
  governance: boolean;
  /** Widgets that need something typed before they show anything. */
  authored: boolean;
  defaultConfig: Record<string, unknown>;
}

export const WIDGET_SPECS: WidgetSpec[] = [
  {
    type: 'SEARCH',
    label: 'Search',
    blurb: 'Find a table, a column, a tag or a term across the whole catalog.',
    icon: SearchLg,
    governance: false,
    authored: false,
    defaultConfig: {},
  },
  {
    type: 'RECENT_POLICIES',
    label: 'Recent policies',
    blurb: 'The policies changed most recently, newest first.',
    icon: ShieldTick,
    governance: true,
    authored: false,
    defaultConfig: { limit: 6 },
  },
  {
    type: 'GOVERNANCE_COVERAGE',
    label: 'Governance coverage',
    blurb: 'How much of the estate carries a tag, a term or an owner.',
    icon: Activity,
    governance: true,
    authored: false,
    defaultConfig: {},
  },
  {
    type: 'SOURCES',
    label: 'Sources',
    blurb: 'The registered databases and the mode each one enforces in.',
    icon: Server01,
    governance: true,
    authored: false,
    defaultConfig: { limit: 5 },
  },
  {
    type: 'VOCABULARY',
    // "Governance vocabulary" on a requester's landing page read as a policy
    // panel to the one audience that is never shown policy, which is the worst
    // place for that word to appear. What the panel counts is the catalog's
    // own vocabulary -- the tags and domains its filters are built from -- and
    // saying so is both shorter and true on the governance page as well.
    label: 'Catalog vocabulary',
    blurb: 'Counts of classifications, glossaries, domains and properties.',
    icon: BookOpen01,
    governance: false,
    authored: false,
    defaultConfig: {},
  },
  {
    type: 'PLATFORM',
    label: 'Platform',
    blurb: 'Service version and the OpenMetadata instance it reads.',
    icon: CpuChip01,
    governance: true,
    authored: false,
    defaultConfig: {},
  },
  {
    type: 'CHART_ASSETS_BY_TYPE',
    label: 'Chart — assets by type',
    blurb: 'The shape of the catalog: tables, views, schemas, databases.',
    icon: BarChart01,
    governance: false,
    authored: false,
    defaultConfig: { shape: 'BARS' },
  },
  {
    type: 'CHART_POLICIES_BY_STATE',
    label: 'Chart — policies by state',
    blurb: 'How much of the policy set is active, and how much is still draft.',
    icon: PieChart01,
    governance: true,
    authored: false,
    defaultConfig: { shape: 'DONUT' },
  },
  {
    type: 'CHART_POLICIES_BY_SCOPE',
    label: 'Chart — policies by scope',
    blurb: 'Where policy is written: organisation, domain, schema, table.',
    icon: PieChart01,
    governance: true,
    authored: false,
    defaultConfig: { shape: 'DONUT' },
  },
  {
    type: 'CHART_SOURCES_BY_MODE',
    label: 'Chart — sources by enforcement mode',
    blurb: 'Secure view, native config or proxy, counted per source.',
    icon: BarChart01,
    governance: true,
    authored: false,
    defaultConfig: { shape: 'BARS' },
  },
  {
    type: 'EXPIRING_ACCESS',
    label: 'Access ending soon',
    blurb: 'Who is about to lose access to which table, with a countdown.',
    icon: Hourglass01,
    // Not governance: a requester's page carries it too, where it lists only
    // the grants that reach them, so nobody finds out on the morning it lapses.
    governance: false,
    authored: false,
    defaultConfig: { withinDays: 14, limit: 8 },
  },
  {
    type: 'ACCESS_REQUEST_STATS',
    label: 'Requests per table',
    blurb: 'How often each table is asked for, and how those asks ended.',
    icon: Ticket01,
    governance: true,
    authored: false,
    defaultConfig: { days: 90, limit: 8 },
  },
  {
    type: 'LINKS',
    label: 'Links',
    blurb: 'A list of addresses your team keeps going back to.',
    icon: Link01,
    governance: false,
    authored: true,
    defaultConfig: { links: [] },
  },
  {
    type: 'NOTE',
    label: 'Note',
    blurb: 'Plain text — a reminder, a contact, a standing instruction.',
    icon: MessageTextSquare01,
    governance: false,
    authored: true,
    defaultConfig: { text: '' },
  },
  {
    type: 'HTML',
    label: 'HTML',
    blurb: 'Formatted content. Scripts and styles are stripped when saved.',
    icon: Code01,
    governance: false,
    authored: true,
    defaultConfig: { html: '' },
  },
  {
    type: 'VIDEO',
    label: 'Video',
    blurb: 'A YouTube or Vimeo video, or a direct .mp4 / .webm / .ogg file.',
    icon: VideoRecorder,
    governance: false,
    authored: true,
    defaultConfig: { url: '' },
  },
];

const BY_TYPE = new Map(WIDGET_SPECS.map((spec) => [spec.type, spec]));

/** The spec for a stored widget, or a placeholder for one we do not know. */
export function specOf(type: HomeWidgetType): WidgetSpec {
  return (
    BY_TYPE.get(type) ?? {
      type,
      label: type,
      blurb: '',
      icon: Database01,
      governance: false,
      authored: false,
      defaultConfig: {},
    }
  );
}

/** The heading a widget shows: the one its owner typed, else its own. */
export function headingOf(widget: HomeWidget): string {
  const title = widget.title?.trim();
  return title ? title : specOf(widget.type).label;
}

// --------------------------------------------------------------- dispatch

/** Draws one stored widget. */
export function HomeWidgetView({ widget }: { widget: HomeWidget }) {
  switch (widget.type) {
    case 'SEARCH':
      return <SearchWidget widget={widget} />;
    case 'RECENT_POLICIES':
      return <RecentPoliciesWidget widget={widget} />;
    case 'GOVERNANCE_COVERAGE':
      return <CoverageWidget widget={widget} />;
    case 'SOURCES':
      return <SourcesWidget widget={widget} />;
    case 'VOCABULARY':
      return <VocabularyWidget widget={widget} />;
    case 'PLATFORM':
      return <PlatformWidget widget={widget} />;
    case 'CHART_ASSETS_BY_TYPE':
    case 'CHART_POLICIES_BY_STATE':
    case 'CHART_POLICIES_BY_SCOPE':
    case 'CHART_SOURCES_BY_MODE':
      return <ChartWidget widget={widget} />;
    case 'EXPIRING_ACCESS':
      return <ExpiringAccessWidget widget={widget} />;
    case 'ACCESS_REQUEST_STATS':
      return <RequestStatsWidget widget={widget} />;
    case 'LINKS':
      return <LinksWidget widget={widget} />;
    case 'NOTE':
      return <NoteWidget widget={widget} />;
    case 'HTML':
      return <HtmlWidget widget={widget} />;
    case 'VIDEO':
      return <VideoWidget widget={widget} />;
    default:
      return (
        <Widget title={headingOf(widget)}>
          <WidgetEmpty
            icon={AlertTriangle}
            line="This panel was saved by a newer version of the platform than the one your browser loaded. Reload the page to see it."
          />
        </Widget>
      );
  }
}

// ----------------------------------------------------------------- search

/**
 * The way in, for somebody who came to find data rather than to govern it.
 *
 * It searches the same endpoint the header's box does, and lands on the same
 * pages, but shows more of each hit — this is a page somebody reads, not a
 * dropdown they are trying to get past.
 */
function SearchWidget({ widget }: { widget: HomeWidget }) {
  const [query, setQuery] = useState('');
  const trimmed = query.trim();
  const ready = trimmed.length >= 2;

  const { data, isFetching } = useQuery({
    queryKey: ['search', trimmed],
    queryFn: () => search(trimmed, 24),
    enabled: ready,
    retry: false,
  });

  const items = data?.items ?? [];

  return (
    <Widget
      action={{ label: 'Browse the catalog', to: '/catalog' }}
      title={headingOf(widget)}>
      <label className="tw:relative tw:block">
        <SearchLg className="tw:pointer-events-none tw:absolute tw:top-1/2 tw:left-3.5 tw:size-5 tw:-translate-y-1/2 tw:text-fg-quaternary" />
        <input
          aria-label="Search the catalog"
          className="tw:w-full tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:py-3 tw:pr-3 tw:pl-11 tw:text-md tw:text-primary tw:placeholder:text-quaternary"
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search tables, columns, tags, terms and domains"
          type="search"
          value={query}
        />
      </label>

      {!ready ? (
        <p className="tw:mt-3 tw:text-xs tw:text-tertiary">
          Two letters is enough to start. A column matches on its own name, so
          searching <span className="tw:font-medium">citizen_id</span> finds the
          tables that hold one.
        </p>
      ) : items.length === 0 ? (
        <p className="tw:mt-4 tw:py-4 tw:text-center tw:text-sm tw:text-tertiary">
          {isFetching
            ? 'Searching…'
            : `Nothing in the catalog matches “${trimmed}”.`}
        </p>
      ) : (
        <div className="tw:mt-4 tw:flex tw:flex-col tw:gap-4">
          {KIND_GROUPS.map((group) => {
            const hits = items.filter((hit) => hit.kind === group.kind);
            if (hits.length === 0) {
              return null;
            }
            return (
              <div key={group.kind}>
                <p className="tw:text-xs tw:font-semibold tw:tracking-wide tw:text-tertiary tw:uppercase">
                  {group.label}
                </p>
                <ul className="tw:mt-1.5 tw:flex tw:flex-col">
                  {hits.map((hit) => (
                    <li key={`${hit.kind}:${hit.fqn ?? hit.id ?? hit.name}`}>
                      <Link
                        className="tw:-mx-2 tw:flex tw:items-start tw:gap-3 tw:rounded-lg tw:px-2 tw:py-2 tw:transition tw:hover:bg-secondary"
                        to={hitHref(hit)}>
                        <span className="tw:min-w-0 tw:flex-1">
                          <span className="tw:block tw:truncate tw:text-sm tw:font-medium tw:text-primary">
                            {hit.displayName || hit.name}
                          </span>
                          <span className="tw:block tw:truncate tw:text-xs tw:text-tertiary">
                            {hit.fqn ?? hit.parentFqn ?? ''}
                          </span>
                        </span>
                        {hit.assets >= 0 && (
                          <span className="tw:shrink-0 tw:text-xs tw:text-quaternary tw:tabular-nums">
                            {hit.assets.toLocaleString()} assets
                          </span>
                        )}
                      </Link>
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
        </div>
      )}
    </Widget>
  );
}

// ------------------------------------------------------- reading widgets

function RecentPoliciesWidget({ widget }: { widget: HomeWidget }) {
  const limit = numberConfig(widget, 'limit', 6);
  const { data: policies } = useQuery({
    queryKey: ['policies', 'recent', limit],
    queryFn: () => fetchPolicies({ limit }),
    retry: false,
  });

  return (
    <Widget
      action={{ label: 'All policies', to: '/policies' }}
      count={policies?.length ?? 0}
      title={headingOf(widget)}>
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
                      {policyState(policy.lifecycleState)}
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
  );
}

function CoverageWidget({ widget }: { widget: HomeWidget }) {
  const { data: catalog } = useQuery({
    queryKey: ['catalog-summary'],
    queryFn: fetchCatalogSummary,
    retry: false,
  });
  const { data: policies } = useQuery({
    queryKey: ['policies', 'recent', 6],
    queryFn: () => fetchPolicies({ limit: 6 }),
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
    <Widget
      action={{ label: 'Open catalog', to: '/catalog' }}
      title={headingOf(widget)}>
      {catalog === undefined ? (
        <Loading />
      ) : tables === 0 ? (
        <WidgetEmpty
          action={{ label: 'Check the sync', to: '/settings/system' }}
          icon={Database01}
          line="Nothing has been crawled from OpenMetadata yet, so no policy can bind to anything."
        />
      ) : (
        <>
          <div className="tw:grid tw:grid-cols-2 tw:gap-6 tw:sm:grid-cols-4">
            <Stat label="Tables and views" value={tables.toLocaleString()} />
            <Stat label="Columns" value={catalog.columns.toLocaleString()} />
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
                An asset with no owner has nobody who may write a local policy
                over it, and nobody to route a request to later. It is governed
                only by whatever reaches it from above.
              </span>
            </p>
          )}
        </>
      )}
    </Widget>
  );
}

function SourcesWidget({ widget }: { widget: HomeWidget }) {
  const limit = numberConfig(widget, 'limit', 5);
  const { data: sources } = useQuery({
    queryKey: ['sources'],
    queryFn: fetchSources,
    retry: false,
  });
  const { data: engines } = useSourceEngines();

  return (
    <Widget
      action={{ label: 'Manage', to: '/sources' }}
      count={sources?.length ?? 0}
      title={headingOf(widget)}>
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
          {sources.slice(0, limit).map((source) => (
            <li className="tw:flex tw:items-start tw:gap-3" key={source.id}>
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
                  {engineLabel(engines, source.engine)} ·{' '}
                  {humanise(source.defaultEnforcementMode)} ·{' '}
                  {source.assetCount.toLocaleString()} assets
                </span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </Widget>
  );
}

function VocabularyWidget({ widget }: { widget: HomeWidget }) {
  const { data: vocabulary } = useQuery({
    queryKey: ['vocabulary'],
    queryFn: fetchVocabulary,
    retry: false,
  });

  return (
    <Widget
      action={{ label: 'Browse', to: '/governance' }}
      title={headingOf(widget)}>
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
  );
}

function PlatformWidget({ widget }: { widget: HomeWidget }) {
  const { data: system } = useQuery({
    queryKey: ['system-version'],
    queryFn: fetchSystemVersion,
    retry: false,
  });

  return (
    <Widget action={{ label: 'Details', to: '/settings/system' }} title={headingOf(widget)}>
      <dl className="tw:flex tw:flex-col tw:gap-2.5 tw:text-sm">
        <SystemRow label="Service" value={system?.version ?? '—'} />
        <SystemRow
          label="OpenMetadata"
          value={system?.openMetadataExpectedVersion ?? '—'}
        />
        <SystemRow label="Instance" value={system?.openMetadataBaseUrl ?? '—'} />
      </dl>
      <p className="tw:mt-3 tw:text-xs tw:text-quaternary">
        The catalog is read-only here. Tags, terms and domains are owned by
        OpenMetadata; policy and enforcement are owned by this platform.
      </p>
    </Widget>
  );
}

// ----------------------------------------------------------------- charts

function ChartWidget({ widget }: { widget: HomeWidget }) {
  const shape = widget.config?.shape === 'DONUT' ? 'DONUT' : 'BARS';

  const catalog = useQuery({
    queryKey: ['catalog-summary'],
    queryFn: fetchCatalogSummary,
    retry: false,
    enabled: widget.type === 'CHART_ASSETS_BY_TYPE',
  });
  const policies = useQuery({
    queryKey: ['policies', 'all-for-charts'],
    queryFn: () => fetchPolicies({ limit: 500 }),
    retry: false,
    enabled:
      widget.type === 'CHART_POLICIES_BY_STATE' ||
      widget.type === 'CHART_POLICIES_BY_SCOPE',
  });
  const sources = useQuery({
    queryKey: ['sources'],
    queryFn: fetchSources,
    retry: false,
    enabled: widget.type === 'CHART_SOURCES_BY_MODE',
  });

  let slices: { label: string; value: number }[] = [];
  let loading = false;
  let empty = 'Nothing to chart yet.';
  let action: { label: string; to: string } | undefined;

  switch (widget.type) {
    case 'CHART_ASSETS_BY_TYPE':
      loading = catalog.data === undefined;
      slices = slicesOf(catalog.data?.assetsByType).map((slice) => ({
        ...slice,
        label: humanise(slice.label),
      }));
      empty = 'Nothing has been crawled from OpenMetadata yet.';
      action = { label: 'Open catalog', to: '/catalog' };
      break;
    case 'CHART_POLICIES_BY_STATE':
      loading = policies.data === undefined;
      slices = countBy(policies.data ?? [], (policy) =>
        policyState(policy.lifecycleState)
      );
      empty = 'No policy has been written yet.';
      action = { label: 'All policies', to: '/policies' };
      break;
    case 'CHART_POLICIES_BY_SCOPE':
      loading = policies.data === undefined;
      slices = countBy(policies.data ?? [], (policy) =>
        humanise(policy.document.scopeLevel)
      );
      empty = 'No policy has been written yet.';
      action = { label: 'All policies', to: '/policies' };
      break;
    case 'CHART_SOURCES_BY_MODE':
      loading = sources.data === undefined;
      slices = countBy(sources.data ?? [], (source) =>
        humanise(source.defaultEnforcementMode)
      );
      empty = 'No database is registered yet.';
      action = { label: 'Manage', to: '/sources' };
      break;
    default:
      break;
  }

  return (
    <Widget action={action} title={headingOf(widget)}>
      {loading ? <Loading /> : <Chart empty={empty} shape={shape} slices={slices} />}
    </Widget>
  );
}

// ----------------------------------------------------------------- access

/** A fetched instant, read against the clock that measured it. */
function useServerClock(serverNow: string | undefined, fetchedAt: number) {
  const [tick, setTick] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setTick(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  // The server said "now" at the moment the answer landed; carry that forward
  // by however long the page has been open since, on the browser's own clock.
  // Only the elapsed time is trusted locally, never the browser's absolute time.
  const base = serverNow ? new Date(serverNow).getTime() : Number.NaN;
  return Number.isNaN(base) ? tick : base + (tick - fetchedAt);
}

function ExpiringAccessWidget({ widget }: { widget: HomeWidget }) {
  const withinDays = numberConfig(widget, 'withinDays', 14);
  const limit = numberConfig(widget, 'limit', 8);
  const { data, dataUpdatedAt } = useQuery({
    queryKey: ['access', 'expiring', withinDays, limit],
    queryFn: () => fetchExpiringGrants(withinDays, limit),
    retry: false,
    // A lapsed grant drops out of the answer; a minute is soon enough for
    // the list to notice, and the countdown itself ticks every second.
    refetchInterval: 60_000,
  });
  const now = useServerClock(data?.now, dataUpdatedAt);

  const grants = data?.grants ?? [];
  const count =
    data === undefined
      ? 0
      : data.total > grants.length
        ? `${grants.length} of ${data.total}`
        : data.total;

  return (
    <Widget
      action={{ label: 'Requests', to: '/requests' }}
      count={count}
      title={headingOf(widget)}>
      {data === undefined ? (
        <Loading />
      ) : grants.length === 0 ? (
        <WidgetEmpty
          icon={Hourglass01}
          line={`No access you can see ends in the next ${withinDays} ${
            withinDays === 1 ? 'day' : 'days'
          }.`}
        />
      ) : (
        <ul className="tw:divide-y tw:divide-secondary">
          {grants.map((grant) => (
            <ExpiringRow grant={grant} key={grant.id} now={now} />
          ))}
        </ul>
      )}
    </Widget>
  );
}

function ExpiringRow({ grant, now }: { grant: ExpiringGrant; now: number }) {
  const left = new Date(grant.validUntil).getTime() - now;
  const tone = urgencyOf(left);
  const holder = grant.displayName || grant.username;
  const group = grant.principalType === 'GROUP';

  return (
    <li className="tw:flex tw:items-start tw:gap-3 tw:py-3">
      <span className="tw:min-w-0 tw:flex-1">
        <span className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
          <span className="tw:truncate tw:text-sm tw:font-medium tw:text-primary">
            {holder}
          </span>
          {grant.mine && (
            <Badge color="brand" size="sm" type="pill-color">
              You
            </Badge>
          )}
          {group && (
            <Badge color="gray" size="sm" type="pill-color">
              Group
            </Badge>
          )}
        </span>
        <Link
          className="tw:mt-0.5 tw:block tw:truncate tw:text-xs tw:text-tertiary tw:hover:text-secondary tw:hover:underline"
          title={grant.assetFqn}
          to={`/catalog/${encodeURIComponent(grant.assetFqn)}?tab=access`}>
          {grant.assetFqn}
        </Link>
      </span>
      <span className="tw:flex tw:shrink-0 tw:flex-col tw:items-end tw:gap-0.5">
        <span
          aria-label={`Ends in ${countdown(left)}`}
          className={`tw:rounded-md tw:px-2 tw:py-0.5 tw:font-mono tw:text-xs tw:font-medium tw:tabular-nums ${URGENCY_CLASS[tone]}`}
          role="timer">
          {countdown(left)}
        </span>
        <span className="tw:text-xs tw:text-quaternary">
          {new Date(grant.validUntil).toLocaleString(undefined, {
            dateStyle: 'medium',
            timeStyle: 'short',
          })}
        </span>
      </span>
    </li>
  );
}

export type Urgency = 'ended' | 'today' | 'soon' | 'later';

/** Under a day is today's problem; under three days is this week's. */
export function urgencyOf(msLeft: number): Urgency {
  if (msLeft <= 0) {
    return 'ended';
  }
  if (msLeft < 24 * 3_600_000) {
    return 'today';
  }
  if (msLeft < 3 * 24 * 3_600_000) {
    return 'soon';
  }
  return 'later';
}

const URGENCY_CLASS: Record<Urgency, string> = {
  ended: 'tw:bg-secondary tw:text-tertiary',
  today: 'tw:bg-utility-error-50 tw:text-utility-error-700',
  soon: 'tw:bg-utility-warning-50 tw:text-utility-warning-700',
  later: 'tw:bg-secondary tw:text-secondary',
};

/**
 * "3d 04:12:09", or "04:12:09" inside the last day.
 *
 * Seconds are shown throughout: the card is the one place a person watches
 * access run out, and a clock that only moves once a minute reads as stuck.
 */
export function countdown(msLeft: number): string {
  if (msLeft <= 0) {
    return 'Ended';
  }
  const total = Math.floor(msLeft / 1000);
  const days = Math.floor(total / 86_400);
  const hours = Math.floor((total % 86_400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const clock = [hours, minutes, seconds]
    .map((part) => String(part).padStart(2, '0'))
    .join(':');
  return days > 0 ? `${days}d ${clock}` : clock;
}

function RequestStatsWidget({ widget }: { widget: HomeWidget }) {
  const days = numberConfig(widget, 'days', 90);
  const limit = numberConfig(widget, 'limit', 8);
  const { data } = useQuery({
    queryKey: ['access-requests', 'stats', days, limit],
    queryFn: () => fetchRequestStats(days, limit),
    retry: false,
  });

  const tables = data?.tables ?? [];
  const widest = Math.max(1, ...tables.map((table) => table.asked));

  return (
    <Widget
      action={{ label: 'Requests', to: '/requests' }}
      count={data?.total ?? 0}
      title={headingOf(widget)}>
      {data === undefined ? (
        <Loading />
      ) : tables.length === 0 ? (
        <WidgetEmpty
          icon={Ticket01}
          line={`No table you oversee has been asked for in the last ${days} days.`}
        />
      ) : (
        <>
          <div className="tw:grid tw:grid-cols-2 tw:gap-6 tw:sm:grid-cols-4">
            <Stat
              hint={`${data.totals.tables.toLocaleString()} tables · ${days} days`}
              label="Asked"
              value={data.totals.asked.toLocaleString()}
            />
            <Stat label="Still open" value={data.totals.open.toLocaleString()} />
            <Stat label="Granted" value={data.totals.completed.toLocaleString()} />
            <Stat
              hint={`${data.totals.rejected} rejected · ${data.totals.declined} declined`}
              label="Refused"
              value={(data.totals.rejected + data.totals.declined).toLocaleString()}
            />
          </div>
          <ul className="tw:mt-5 tw:flex tw:flex-col tw:gap-3.5">
            {tables.map((table) => (
              <StatsRow key={table.assetFqn} table={table} widest={widest} />
            ))}
          </ul>
          <ul
            aria-label="Legend"
            className="tw:mt-4 tw:flex tw:flex-wrap tw:gap-x-4 tw:gap-y-1 tw:text-xs tw:text-tertiary">
            {OUTCOMES.map((outcome) => (
              <li className="tw:flex tw:items-center tw:gap-1.5" key={outcome.key}>
                <span
                  aria-hidden
                  className="tw:size-2 tw:rounded-full"
                  style={{ backgroundColor: outcome.colour }}
                />
                {outcome.label}
              </li>
            ))}
          </ul>
        </>
      )}
    </Widget>
  );
}

function StatsRow({ table, widest }: { table: TableRequestStats; widest: number }) {
  const segments = segmentsOf(table);
  const name = table.assetFqn.split('.').pop() ?? table.assetFqn;
  const facts = [
    `${table.requesters} ${table.requesters === 1 ? 'person' : 'people'}`,
    table.medianHoursToClose === null
      ? null
      : `median ${hoursLabel(table.medianHoursToClose)} to answer`,
    `last ${relativeTime(table.lastAskedAt)}`,
  ].filter(Boolean);

  return (
    <li>
      <div className="tw:flex tw:items-baseline tw:justify-between tw:gap-3">
        <Link
          className="tw:min-w-0 tw:truncate tw:text-sm tw:font-medium tw:text-primary tw:hover:underline"
          title={table.assetFqn}
          to={`/catalog/${encodeURIComponent(table.assetFqn)}`}>
          {name}
        </Link>
        <span className="tw:shrink-0 tw:text-sm tw:font-medium tw:text-primary tw:tabular-nums">
          {table.asked.toLocaleString()}
        </span>
      </div>
      {/*
        The bar's length is the table's share of the busiest one; its colours
        are how those asks ended. Both at once, so a table that is asked for
        often and mostly refused stands out without a second chart.
      */}
      <div
        aria-label={segments
          .map((segment) => `${segment.value} ${segment.label.toLowerCase()}`)
          .join(', ')}
        className="tw:mt-1.5 tw:h-1.5 tw:w-full tw:overflow-hidden tw:rounded-full tw:bg-secondary"
        role="img">
        <div
          className="tw:flex tw:h-full"
          style={{ width: `${Math.round((table.asked / widest) * 100)}%` }}>
          {segments.map((segment) => (
            <div
              key={segment.key}
              style={{
                width: `${(segment.value / table.asked) * 100}%`,
                backgroundColor: segment.colour,
              }}
            />
          ))}
        </div>
      </div>
      <p className="tw:mt-1 tw:truncate tw:text-xs tw:text-tertiary">
        {facts.join(' · ')}
      </p>
    </li>
  );
}

type Outcome = 'completed' | 'open' | 'rejected' | 'declined' | 'withdrawn';

const OUTCOMES: { key: Outcome; label: string; colour: string }[] = [
  { key: 'completed', label: 'Granted', colour: 'var(--color-success-500, #17b26a)' },
  { key: 'open', label: 'Open', colour: 'var(--color-brand-500, #2e90fa)' },
  { key: 'rejected', label: 'Rejected', colour: 'var(--color-error-500, #f04438)' },
  { key: 'declined', label: 'Declined', colour: 'var(--color-warning-500, #f79009)' },
  { key: 'withdrawn', label: 'Withdrawn', colour: 'var(--color-gray-400, #98a2b3)' },
];

/** The outcomes a table's bar is drawn from, in legend order, empty ones left out. */
export function segmentsOf(
  table: TableRequestStats
): { key: Outcome; label: string; colour: string; value: number }[] {
  return OUTCOMES.map((outcome) => ({ ...outcome, value: table[outcome.key] })).filter(
    (segment) => segment.value > 0
  );
}

/** Hours as a person says them: minutes under one, days past two. */
export function hoursLabel(hours: number): string {
  if (hours < 1) {
    return `${Math.max(1, Math.round(hours * 60))}m`;
  }
  if (hours < 48) {
    return `${Number.isInteger(hours) ? hours : hours.toFixed(1)}h`;
  }
  return `${(hours / 24).toFixed(1)}d`;
}

// -------------------------------------------------------- authored widgets

function LinksWidget({ widget }: { widget: HomeWidget }) {
  const links = Array.isArray(widget.config?.links)
    ? (widget.config.links as HomeLink[])
    : [];

  return (
    <Widget count={links.length} title={headingOf(widget)}>
      {links.length === 0 ? (
        <WidgetEmpty
          icon={Link01}
          line="No links yet. Edit this page to add the addresses your team keeps going back to."
        />
      ) : (
        <ul className="tw:flex tw:flex-col tw:gap-1">
          {links.map((link) => (
            <li key={`${link.url}:${link.label}`}>
              {/*
                An external address, so a real anchor with the same rel the
                server enforces on links inside HTML widgets — a page opened
                with target=_blank can otherwise navigate its opener.
              */}
              <a
                className="tw:-mx-2 tw:flex tw:items-start tw:gap-2.5 tw:rounded-lg tw:px-2 tw:py-2 tw:transition tw:hover:bg-secondary"
                href={link.url}
                rel="nofollow noopener noreferrer"
                target="_blank">
                <Link01 className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
                <span className="tw:min-w-0 tw:flex-1">
                  <span className="tw:block tw:truncate tw:text-sm tw:font-medium tw:text-primary">
                    {link.label}
                  </span>
                  <span className="tw:block tw:truncate tw:text-xs tw:text-tertiary">
                    {link.note || link.url}
                  </span>
                </span>
              </a>
            </li>
          ))}
        </ul>
      )}
    </Widget>
  );
}

function NoteWidget({ widget }: { widget: HomeWidget }) {
  const text = typeof widget.config?.text === 'string' ? widget.config.text : '';

  return (
    <Widget title={headingOf(widget)}>
      {text.trim() === '' ? (
        <WidgetEmpty
          icon={MessageTextSquare01}
          line="This note is empty. Edit this page to write in it."
        />
      ) : (
        // Plain text, rendered as text. Line breaks are kept because people
        // write lists in these and expect them to stay lists.
        <p className="tw:text-sm tw:whitespace-pre-wrap tw:text-secondary">
          {text}
        </p>
      )}
    </Widget>
  );
}

function HtmlWidget({ widget }: { widget: HomeWidget }) {
  const html = typeof widget.config?.html === 'string' ? widget.config.html : '';

  return (
    <Widget title={headingOf(widget)}>
      {html.trim() === '' ? (
        <WidgetEmpty
          icon={Code01}
          line="Nothing here yet. Edit this page to paste in formatted content."
        />
      ) : (
        /*
          The markup was rebuilt from an allowlist by the server, on write and
          again on read, so what arrives here holds no script, no style and no
          frame. That is the whole of the safety argument — this component adds
          nothing to it and must not be given content from anywhere else.
        */
        <div
          className="arak-prose tw:text-sm tw:text-secondary"
          dangerouslySetInnerHTML={{ __html: html }}
        />
      )}
    </Widget>
  );
}

function VideoWidget({ widget }: { widget: HomeWidget }) {
  const url = typeof widget.config?.url === 'string' ? widget.config.url : '';
  const kind = widget.config?.kind === 'FILE' ? 'FILE' : 'EMBED';
  const caption =
    typeof widget.config?.caption === 'string' ? widget.config.caption : '';

  return (
    <Widget title={headingOf(widget)}>
      {url === '' ? (
        <WidgetEmpty
          icon={VideoRecorder}
          line="No video yet. Edit this page and paste a YouTube, Vimeo or direct file address."
        />
      ) : (
        <figure className="tw:m-0">
          <div className="tw:aspect-video tw:w-full tw:overflow-hidden tw:rounded-lg tw:bg-secondary">
            {kind === 'FILE' ? (
              <video className="tw:size-full" controls preload="metadata" src={url} />
            ) : (
              <iframe
                allow="accelerometer; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
                allowFullScreen
                className="tw:size-full"
                // The address was rewritten to a player URL on an allowlisted
                // host by the server; nothing here derives it from user input.
                referrerPolicy="strict-origin-when-cross-origin"
                src={url}
                title={caption || headingOf(widget)}
              />
            )}
          </div>
          {caption && (
            <figcaption className="tw:mt-2 tw:text-xs tw:text-tertiary">
              {caption}
            </figcaption>
          )}
        </figure>
      )}
    </Widget>
  );
}

// ---------------------------------------------------------------- helpers

function numberConfig(
  widget: HomeWidget,
  key: string,
  fallback: number
): number {
  const raw = widget.config?.[key];
  return typeof raw === 'number' && Number.isFinite(raw) ? raw : fallback;
}

function countBy<T>(
  items: T[],
  key: (item: T) => string
): { label: string; value: number }[] {
  const counts = new Map<string, number>();
  for (const item of items) {
    const label = key(item);
    counts.set(label, (counts.get(label) ?? 0) + 1);
  }
  return [...counts.entries()].map(([label, value]) => ({ label, value }));
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

export function Loading() {
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

function stateColor(state: string): 'success' | 'warning' | 'gray' | 'brand' {
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

/** A policy's state as it reads; archived says outright that it no longer applies. */
function policyState(state: string): string {
  return state === 'ARCHIVED' ? 'Archived (Not active)' : humanise(state);
}

/** ENUM_CASE as a sentence, which is how every label on this page reads. */
export function humanise(value: string): string {
  const words = value.toLowerCase().split('_').join(' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}
