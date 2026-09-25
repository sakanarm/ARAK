import { useEffect, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  AlertCircle,
  AlertTriangle,
  ArrowRight,
  BarChartSquare02,
  CheckCircle,
  Hourglass01,
  InfoCircle,
  ShieldTick,
} from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { Widget, WidgetEmpty, relativeTime } from '../../components/widgets';
import { apiErrorMessage } from '../../api/client';
import { CATEGORY_LABELS, type RefusalCategory } from '../../api/audit';
import {
  fetchDashboard,
  type Attention,
  type Busiest,
  type Dashboard,
  type DashboardDay,
  type EndingGrant,
  type SensitiveTable,
  type Severity,
} from '../../api/dashboard';
import { countdown, hoursLabel, humanise, Loading, urgencyOf, type Urgency } from '../home/widgets';
import { Select, TextField } from '../policies/controls';

/**
 * The access-control dashboard (M10, FR-8.5).
 *
 * One page for the questions a reviewer brings to the whole estate, in the
 * order they are asked: what wants me, how much of the sensitive data a policy
 * actually protects, what the proxy ran and refused, who holds access and who
 * is about to lose it, and whether the platform itself is well. Every number
 * links to the page where the thing behind it is fixed or read in full, so the
 * dashboard is a way in rather than a report to screenshot.
 *
 * The window and the label live in the address, like the query log's filters,
 * so a link to "PII over the last 90 days" can be sent to somebody.
 */

const WINDOWS = [
  { value: '7', label: 'Last 7 days' },
  { value: '30', label: 'Last 30 days' },
  { value: '90', label: 'Last 90 days' },
  { value: '365', label: 'Last year' },
];

const COLOURS = {
  success: 'var(--color-utility-success-500, #17b26a)',
  error: 'var(--color-utility-error-500, #f04438)',
  warning: 'var(--color-utility-warning-500, #f79009)',
  gray: 'var(--color-utility-gray-300, #d0d5dd)',
  brand: 'var(--color-utility-brand-500, #2e90fa)',
};

/** The sensitive tables listed before "Show all". */
const TABLES_SHOWN = 8;

export default function DashboardPage() {
  const [params, setParams] = useSearchParams();
  const days = WINDOWS.some((w) => w.value === params.get('days')) ? params.get('days')! : '30';
  const label = (params.get('label') ?? '').trim() || 'PII';

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params);
    for (const [key, value] of Object.entries(next)) {
      if (value === '') {
        merged.delete(key);
      } else {
        merged.set(key, value);
      }
    }
    setParams(merged, { replace: true });
  }

  const dashboard = useQuery({
    queryKey: ['dashboard', days, label],
    queryFn: () => fetchDashboard(Number(days), label),
    retry: false,
    // Heavy enough not to poll every second; the countdowns tick on their own.
    refetchInterval: 120_000,
  });
  const data = dashboard.data;

  return (
    <div className="tw:flex tw:flex-col tw:gap-4">
      <header className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-6 tw:py-5 tw:shadow-xs">
        <div className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-4">
          <div className="tw:flex tw:min-w-0 tw:items-start tw:gap-4">
            <span
              aria-hidden
              className="tw:flex tw:size-12 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-xl tw:bg-utility-brand-50">
              <BarChartSquare02 className="tw:size-6 tw:text-fg-brand-primary" />
            </span>
            <div className="tw:min-w-0">
              <h1 className="tw:text-display-xs tw:font-semibold tw:text-primary">
                Access control dashboard
              </h1>
              <p className="tw:mt-1 tw:max-w-2xl tw:text-pretty tw:text-sm tw:text-tertiary">
                How much of the data labelled <span className="tw:font-medium tw:text-secondary">{label}</span>{' '}
                a policy protects, who can reach it, what the query proxy ran and refused, and what
                wants a decision.
                {data && (
                  <span className="tw:text-quaternary"> Updated {relativeTime(data.generatedAt)}.</span>
                )}
              </p>
            </div>
          </div>
          <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
            <LabelField onCommit={(value) => update({ label: value === 'PII' ? '' : value })} value={label} />
            <Select
              ariaLabel="Window"
              className="tw:w-44"
              onChange={(value) => update({ days: value === '30' ? '' : value })}
              options={WINDOWS}
              value={days}
            />
          </div>
        </div>
      </header>

      {dashboard.isError ? (
        <p
          className="tw:flex tw:items-start tw:gap-2 tw:rounded-xl tw:border tw:border-secondary tw:bg-utility-error-50 tw:px-4 tw:py-3 tw:text-sm tw:text-error-primary"
          role="alert">
          <AlertTriangle className="tw:mt-0.5 tw:size-4 tw:shrink-0" />
          <span>{apiErrorMessage(dashboard.error, 'The dashboard could not be read.')}</span>
        </p>
      ) : data === undefined ? (
        <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5 tw:shadow-xs">
          <Loading />
        </section>
      ) : (
        <Body data={data} fetchedAt={dashboard.dataUpdatedAt} />
      )}
    </div>
  );
}

function Body({ data, fetchedAt }: { data: Dashboard; fetchedAt: number }) {
  const days = data.days;
  return (
    <>
      <Kpis data={data} />

      <div className="tw:grid tw:gap-4 tw:lg:grid-cols-3">
        <AttentionCard className="tw:lg:col-span-2" data={data} />
        <CoverageCard data={data} />
      </div>

      <div className="tw:grid tw:gap-4 tw:lg:grid-cols-3">
        <ActivityCard className="tw:lg:col-span-2" data={data} />
        <RefusalsCard data={data} />
      </div>

      <SensitiveTablesCard data={data} />

      <div className="tw:grid tw:gap-4 tw:lg:grid-cols-3">
        <EndingCard data={data} fetchedAt={fetchedAt} />
        <BusiestCard
          empty="No table was queried through the proxy in this window."
          people={(row) => `${row.people} ${row.people === 1 ? 'person' : 'people'}`}
          rows={data.activity.busiestTables}
          title="Most-read tables"
          to={(row) => `/audit?table=${encodeURIComponent(row.name)}&days=${days}`}
        />
        <BusiestCard
          empty="Nobody sent a query through the proxy in this window."
          people={(row) => `${row.people} ${row.people === 1 ? 'table' : 'tables'}`}
          rows={data.activity.busiestPeople}
          title="Most active people"
          to={(row) => `/audit?principal=${encodeURIComponent(row.name)}&days=${days}`}
        />
      </div>

      <div className="tw:grid tw:gap-4 tw:lg:grid-cols-3">
        <GrantsCard data={data} />
        <RequestsCard data={data} />
        <HealthCard data={data} />
      </div>
    </>
  );
}

// -------------------------------------------------------------------- KPIs

function Kpis({ data }: { data: Dashboard }) {
  const { coverage, grants, activity, requests } = data;
  const covered = percent(coverage.protectedTables, coverage.sensitive);
  const open = requests.pending + requests.approved + requests.inProgress;
  const refusedShare = percent(activity.counts.rejected, activity.counts.total);

  return (
    <section
      aria-label="Key figures"
      className="tw:grid tw:grid-cols-2 tw:gap-4 tw:md:grid-cols-3 tw:xl:grid-cols-5">
      <Kpi
        hint={
          coverage.sensitive === 0
            ? `No table carries ${data.label}`
            : `${coverage.protectedTables} of ${coverage.sensitive} sensitive tables`
        }
        label="Protected by policy"
        tone={coverage.sensitive === 0 ? 'neutral' : covered === 100 ? 'good' : covered < 50 ? 'bad' : 'warn'}
        value={coverage.sensitive === 0 ? '–' : `${covered}%`}
      />
      <Kpi
        hint={`${grants.onSensitive} on sensitive tables · ${grants.endingCount} ending soon`}
        label="Grants in force"
        value={grants.active.toLocaleString()}
      />
      <Kpi
        hint={
          activity.counts.total === 0
            ? `None in ${data.days} days`
            : `${activity.counts.rejected.toLocaleString()} refused (${refusedShare}%)`
        }
        label="Proxy queries"
        to={`/audit?days=${data.days}`}
        value={activity.counts.total.toLocaleString()}
      />
      <Kpi
        hint={
          requests.oldestOpenAt ? `Oldest asked ${relativeTime(requests.oldestOpenAt)}` : 'Nothing waiting'
        }
        label="Open requests"
        tone={open > 0 ? 'warn' : 'neutral'}
        to="/requests?tab=inbox"
        value={open.toLocaleString()}
      />
      <Kpi
        hint={
          activity.decisions.total === 0
            ? 'No decisions in this window'
            : `p50 ${activity.decisions.p50Ms ?? '–'} ms · ${activity.decisions.total.toLocaleString()} decisions`
        }
        label="Decision time, p95"
        tone={activity.decisions.p95Ms !== null && activity.decisions.p95Ms > 50 ? 'warn' : 'neutral'}
        value={activity.decisions.p95Ms === null ? '–' : `${activity.decisions.p95Ms} ms`}
      />
    </section>
  );
}

type Tone = 'good' | 'warn' | 'bad' | 'neutral';

const TONE_TEXT: Record<Tone, string> = {
  good: 'tw:text-success-primary',
  warn: 'tw:text-warning-primary',
  bad: 'tw:text-error-primary',
  neutral: 'tw:text-primary',
};

function Kpi({
  label,
  value,
  hint,
  tone = 'neutral',
  to,
}: {
  label: string;
  value: string;
  hint: string;
  tone?: Tone;
  to?: string;
}) {
  const body = (
    <>
      <p className="tw:text-sm tw:font-medium tw:text-tertiary">{label}</p>
      <p className={`tw:mt-2 tw:text-display-sm tw:font-semibold tw:tabular-nums ${TONE_TEXT[tone]}`}>
        {value}
      </p>
      <p className="tw:mt-1 tw:text-xs tw:text-quaternary">
        {hint}
      </p>
    </>
  );
  const shell =
    'tw:block tw:min-w-0 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-5 tw:py-4 tw:shadow-xs';
  return to ? (
    <Link aria-label={`${label}: ${value}`} className={`${shell} tw:transition tw:hover:border-brand`} to={to}>
      {body}
    </Link>
  ) : (
    <div className={shell}>{body}</div>
  );
}

// ---------------------------------------------------------------- attention

const SEVERITY_LOOK: Record<Severity, { icon: typeof AlertCircle; ring: string; fg: string; label: string }> = {
  HIGH: {
    icon: AlertCircle,
    ring: 'tw:bg-utility-error-50',
    fg: 'tw:text-fg-error-primary',
    label: 'High',
  },
  MEDIUM: {
    icon: AlertTriangle,
    ring: 'tw:bg-utility-warning-50',
    fg: 'tw:text-fg-warning-primary',
    label: 'Medium',
  },
  LOW: {
    icon: InfoCircle,
    ring: 'tw:bg-secondary',
    fg: 'tw:text-fg-quaternary',
    label: 'Low',
  },
};

function plural(count: number, one: string, many: string): string {
  return `${count.toLocaleString()} ${count === 1 ? one : many}`;
}

function shortName(fqn: string): string {
  return fqn.split('.').pop() ?? fqn;
}

/** What one item says, and where it is dealt with. */
export function wordingOf(
  item: Attention,
  days: number
): { title: string; detail: string; action?: { label: string; to: string } } {
  const subject = item.subject;
  const table = subject
    ? { label: `Open ${shortName(subject)}`, to: `/catalog/${encodeURIComponent(subject)}` }
    : undefined;
  switch (item.kind) {
    case 'UNPROTECTED_READ':
      return {
        title: `${plural(item.count, 'sensitive table was', 'sensitive tables were')} read with no data policy`,
        detail: `Somebody queried ${item.count === 1 ? 'it' : 'them'} in the last ${days} days and nothing masked or filtered what they saw${
          subject ? `, starting with ${subject}` : ''
        }.`,
        action: table,
      };
    case 'UNPROTECTED_REACHABLE':
      return {
        title: `${plural(item.count, 'sensitive table is', 'sensitive tables are')} open with no data policy`,
        detail: `A subscription policy or a grant lets people in, and nothing would mask what they see. Nobody has read ${
          item.count === 1 ? 'it' : 'them'
        } yet${subject ? `; the first is ${subject}` : ''}.`,
        action: table,
      };
    case 'ENFORCEMENT_FAULT':
      return {
        title: `${plural(item.count, 'enforced object has', 'enforced objects have')} drifted or failed`,
        detail:
          'A secure view or native object no longer matches what was applied, or could not be applied. Until it is re-applied the source may not enforce the policy.',
        action: { label: 'Open Enforcement', to: '/enforcement' },
      };
    case 'SYNC_FAILED':
      return {
        title: 'The last OpenMetadata sync failed',
        detail:
          'Tags, owners and new tables stop arriving until it succeeds, so a newly labelled column may not be covered yet.',
        action: { label: 'Open settings', to: '/settings' },
      };
    case 'SYNC_STALE':
      return {
        title: 'The catalogue has not been synced for over two days',
        detail: 'A label or owner changed in OpenMetadata since then is not reflected in policy yet.',
        action: { label: 'Open settings', to: '/settings' },
      };
    case 'REQUESTS_WAITING':
      return {
        title: `${plural(item.count, 'access request is', 'access requests are')} still open`,
        detail: subject
          ? `The oldest has waited ${plural(Number(subject), 'day', 'days')} for an answer.`
          : 'Some have waited more than three days for an answer.',
        action: { label: 'Open requests', to: '/requests?tab=inbox' },
      };
    case 'GRANTS_ENDING':
      return {
        title: `${plural(item.count, 'grant ends', 'grants end')} in the next 14 days`,
        detail: subject
          ? `Starting with ${subject}. Renew what is still needed before it lapses.`
          : 'Renew what is still needed before it lapses.',
        action: { label: 'See who', to: '#ending' },
      };
    case 'UNPROTECTED_CLOSED':
      return {
        title: `${plural(item.count, 'sensitive table has', 'sensitive tables have')} no data policy`,
        detail: `Nobody can get in today, so nothing is exposed; the first grant would open ${
          item.count === 1 ? 'it' : 'them'
        } unmasked${subject ? `, starting with ${subject}` : ''}.`,
        action: table,
      };
    case 'GRANTS_UNUSED':
      return {
        title: `${plural(item.count, 'grant has', 'grants have')} not been used in 90 days`,
        detail: 'Access nobody uses is access nobody would miss. Consider revoking it at the next review.',
        action: { label: 'See grants', to: '#grants' },
      };
    case 'GRANTS_OPEN_ENDED':
      return {
        title: `${plural(item.count, 'grant never ends', 'grants never end')}`,
        detail: 'A grant without an end date outlives the reason it was given. Give it one.',
        action: { label: 'See grants', to: '#grants' },
      };
    default:
      return { title: humanise(String(item.kind)), detail: `${item.count}` };
  }
}

function AttentionCard({ data, className }: { data: Dashboard; className?: string }) {
  const items = data.attention;
  const high = items.filter((item) => item.severity === 'HIGH').length;
  return (
    <Widget
      className={className}
      count={items.length === 0 ? undefined : high > 0 ? `${high} high` : items.length}
      title="Needs attention">
      {items.length === 0 ? (
        <div className="tw:flex tw:flex-col tw:items-center tw:gap-2 tw:py-8 tw:text-center">
          <span className="tw:flex tw:size-10 tw:items-center tw:justify-center tw:rounded-full tw:bg-utility-success-50">
            <CheckCircle className="tw:size-5 tw:text-fg-success-primary" />
          </span>
          <p className="tw:text-sm tw:font-medium tw:text-primary">Nothing needs you right now</p>
          <p className="tw:max-w-sm tw:text-sm tw:text-tertiary">
            Every sensitive table has a data policy, the platform is healthy and no request has
            waited too long.
          </p>
        </div>
      ) : (
        <ul aria-label="Needs attention" className="tw:divide-y tw:divide-secondary">
          {items.map((item) => (
            <AttentionRow days={data.days} item={item} key={item.kind} />
          ))}
        </ul>
      )}
    </Widget>
  );
}

function AttentionRow({ item, days }: { item: Attention; days: number }) {
  const look = SEVERITY_LOOK[item.severity] ?? SEVERITY_LOOK.LOW;
  const Icon = look.icon;
  const words = wordingOf(item, days);
  return (
    <li className="tw:flex tw:items-start tw:gap-3 tw:py-3 tw:first:pt-0 tw:last:pb-0">
      <span
        aria-label={`${look.label} severity`}
        className={`tw:flex tw:size-8 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-full ${look.ring}`}
        role="img">
        <Icon aria-hidden className={`tw:size-4 ${look.fg}`} />
      </span>
      <div className="tw:min-w-0 tw:flex-1">
        <p className="tw:text-sm tw:font-medium tw:text-primary">{words.title}</p>
        <p className="tw:mt-0.5 tw:text-pretty tw:text-sm tw:text-tertiary">{words.detail}</p>
      </div>
      {words.action && <ActionLink action={words.action} />}
    </li>
  );
}

function ActionLink({ action }: { action: { label: string; to: string } }) {
  const className =
    'tw:flex tw:shrink-0 tw:items-center tw:gap-1 tw:self-center tw:text-xs tw:font-semibold tw:text-brand-secondary tw:hover:underline';
  // An in-page anchor is a plain link: the router would treat it as a route.
  return action.to.startsWith('#') ? (
    <a className={className} href={action.to}>
      {action.label}
      <ArrowRight className="tw:size-3.5" />
    </a>
  ) : (
    <Link className={className} to={action.to}>
      {action.label}
      <ArrowRight className="tw:size-3.5" />
    </Link>
  );
}

// ----------------------------------------------------------------- coverage

function CoverageCard({ data }: { data: Dashboard }) {
  const { coverage } = data;
  const unprotected = coverage.sensitive - coverage.protectedTables;
  const closed = unprotected - coverage.exposed;
  const slices = [
    { key: 'protected', label: 'Protected by a data policy', value: coverage.protectedTables, colour: COLOURS.success },
    { key: 'exposed', label: 'Unprotected, and people can get in', value: coverage.exposed, colour: COLOURS.error },
    { key: 'closed', label: 'Unprotected, nobody can get in', value: closed, colour: COLOURS.gray },
  ];
  const covered = percent(coverage.protectedTables, coverage.sensitive);

  return (
    <Widget count={coverage.sensitive} title={`Coverage of ${data.label}`}>
      {coverage.sensitive === 0 ? (
        <WidgetEmpty
          icon={ShieldTick}
          line={`No table or column in the catalogue carries ${data.label}, confirmed. A suggested label does not count until somebody confirms it.`}
        />
      ) : (
        <div className="tw:flex tw:flex-col tw:items-center tw:gap-5">
          <Ring label={`${covered}% of sensitive tables protected`} slices={slices} total={coverage.sensitive}>
            <span className="tw:text-display-xs tw:font-semibold tw:text-primary tw:tabular-nums">{covered}%</span>
            <span className="tw:text-xs tw:text-tertiary">protected</span>
          </Ring>
          <ul className="tw:flex tw:w-full tw:flex-col tw:gap-2">
            {slices.map((slice) => (
              <li className="tw:flex tw:items-center tw:gap-2 tw:text-sm" key={slice.key}>
                <span aria-hidden className="tw:size-2.5 tw:shrink-0 tw:rounded-full" style={{ backgroundColor: slice.colour }} />
                <span className="tw:min-w-0 tw:flex-1 tw:truncate tw:text-secondary">{slice.label}</span>
                <span className="tw:font-medium tw:text-primary tw:tabular-nums">{slice.value}</span>
              </li>
            ))}
          </ul>
          <p className="tw:w-full tw:border-t tw:border-secondary tw:pt-3 tw:text-xs tw:text-tertiary">
            {plural(coverage.sensitive, 'table', 'tables')} of {coverage.tables.toLocaleString()} in the
            catalogue carry {data.label}.
            {coverage.readUnprotected > 0 && (
              <span className="tw:font-medium tw:text-error-primary">
                {' '}
                {plural(coverage.readUnprotected, 'was', 'were')} read unprotected in the last {data.days} days.
              </span>
            )}
          </p>
        </div>
      )}
    </Widget>
  );
}

/**
 * A ring of up to a handful of slices, with whatever the caller puts in the
 * middle. At r = 100 / 2π the circumference is 100, so a slice's dash is its
 * percentage -- the same trick the home page's donut uses.
 */
function Ring({
  slices,
  total,
  label,
  children,
}: {
  slices: { key: string; value: number; colour: string }[];
  total: number;
  label: string;
  children: ReactNode;
}) {
  const radius = 100 / (2 * Math.PI);
  let offset = 0;
  return (
    <div aria-label={label} className="tw:relative tw:size-40" role="img">
      <svg className="tw:size-full tw:-rotate-90" viewBox="0 0 36 36">
        <circle
          cx="18"
          cy="18"
          fill="none"
          r={radius}
          stroke="var(--color-bg-secondary, #f2f4f7)"
          strokeWidth="3.6"
        />
        {slices
          .filter((slice) => slice.value > 0)
          .map((slice) => {
            const share = (slice.value / total) * 100;
            const circle = (
              <circle
                cx="18"
                cy="18"
                fill="none"
                key={slice.key}
                r={radius}
                stroke={slice.colour}
                strokeDasharray={`${share} ${100 - share}`}
                strokeDashoffset={-offset}
                strokeWidth="3.6"
              />
            );
            offset += share;
            return circle;
          })}
      </svg>
      <div className="tw:absolute tw:inset-0 tw:flex tw:flex-col tw:items-center tw:justify-center">
        {children}
      </div>
    </div>
  );
}

// ----------------------------------------------------------------- activity

const ACTIVITY_SERIES: { key: 'executed' | 'rejected' | 'failed'; label: string; colour: string }[] = [
  { key: 'executed', label: 'Ran', colour: COLOURS.success },
  { key: 'rejected', label: 'Refused', colour: COLOURS.error },
  { key: 'failed', label: 'Failed', colour: COLOURS.warning },
];

function dayTotal(day: DashboardDay): number {
  return day.executed + day.rejected + day.failed;
}

function ActivityCard({ data, className }: { data: Dashboard; className?: string }) {
  const perDay = data.activity.perDay;
  const counts = data.activity.counts;
  const tallest = Math.max(1, ...perDay.map(dayTotal));
  const busiest = perDay.reduce<DashboardDay | null>(
    (best, day) => (best === null || dayTotal(day) > dayTotal(best) ? day : best),
    null
  );

  return (
    <Widget
      action={{ label: 'Query log', to: `/audit?days=${data.days}` }}
      className={className}
      count={counts.total.toLocaleString()}
      title="Queries per day">
      {counts.total === 0 ? (
        <WidgetEmpty
          icon={BarChartSquare02}
          line={`Nothing was sent through the query proxy in the last ${data.days} days.`}
        />
      ) : (
        <>
          <div className="tw:flex tw:items-stretch tw:gap-3">
            <div className="tw:flex tw:flex-col tw:justify-between tw:pb-5 tw:text-right tw:text-xs tw:text-quaternary tw:tabular-nums">
              <span>{tallest.toLocaleString()}</span>
              <span>0</span>
            </div>
            <div className="tw:min-w-0 tw:flex-1">
              <div
                aria-label={`${counts.executed} ran, ${counts.rejected} refused, ${counts.failed} failed over ${perDay.length} days`}
                className={`tw:flex tw:h-44 tw:items-end tw:border-b tw:border-secondary ${
                  perDay.length > 90 ? '' : 'tw:gap-px'
                }`}
                role="img">
                {perDay.map((day) => (
                  <DayBar day={day} key={day.date} tallest={tallest} />
                ))}
              </div>
              <div className="tw:mt-1.5 tw:flex tw:justify-between tw:text-xs tw:text-quaternary">
                <span>{dayLabel(perDay[0]?.date)}</span>
                <span>{dayLabel(perDay[perDay.length - 1]?.date)}</span>
              </div>
            </div>
          </div>
          <div className="tw:mt-3 tw:flex tw:flex-wrap tw:items-center tw:justify-between tw:gap-3">
            <ul
              aria-label="Legend"
              className="tw:flex tw:flex-wrap tw:gap-x-4 tw:gap-y-1 tw:text-xs tw:text-tertiary">
              {ACTIVITY_SERIES.map((series) => (
                <li className="tw:flex tw:items-center tw:gap-1.5" key={series.key}>
                  <span aria-hidden className="tw:size-2 tw:rounded-full" style={{ backgroundColor: series.colour }} />
                  {series.label}
                  <span className="tw:font-medium tw:text-secondary tw:tabular-nums">
                    {counts[series.key].toLocaleString()}
                  </span>
                </li>
              ))}
            </ul>
            {busiest && dayTotal(busiest) > 0 && (
              <p className="tw:text-xs tw:text-quaternary">
                Busiest day {dayLabel(busiest.date)} · {dayTotal(busiest).toLocaleString()} queries
              </p>
            )}
          </div>
        </>
      )}
    </Widget>
  );
}

function DayBar({ day, tallest }: { day: DashboardDay; tallest: number }) {
  const total = dayTotal(day);
  return (
    <div
      className="tw:flex tw:h-full tw:min-w-0 tw:flex-1 tw:flex-col-reverse"
      title={`${dayLabel(day.date)}: ${day.executed} ran, ${day.rejected} refused, ${day.failed} failed`}>
      <div
        className="tw:flex tw:flex-col-reverse tw:overflow-hidden tw:rounded-t-sm"
        style={{ height: `${(total / tallest) * 100}%` }}>
        {ACTIVITY_SERIES.map((series) =>
          day[series.key] > 0 ? (
            <div
              key={series.key}
              style={{ height: `${(day[series.key] / total) * 100}%`, backgroundColor: series.colour }}
            />
          ) : null
        )}
      </div>
    </div>
  );
}

function dayLabel(date: string | undefined): string {
  if (!date) return '';
  // A UTC calendar day: read as noon so no timezone moves it to its neighbour.
  return new Date(`${date}T12:00:00Z`).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

// ----------------------------------------------------------------- refusals

/** The outcome a category's rows carry in the log. */
function outcomeOf(category: RefusalCategory): 'REJECTED' | 'FAILED' {
  return category === 'SOURCE_ERROR' || category === 'SOURCE_UNAVAILABLE' ? 'FAILED' : 'REJECTED';
}

function RefusalsCard({ data }: { data: Dashboard }) {
  const refusals = data.activity.refusals;
  const total = refusals.reduce((sum, refusal) => sum + refusal.count, 0);
  const widest = Math.max(1, ...refusals.map((refusal) => refusal.count));
  return (
    <Widget
      action={{ label: 'Refused', to: `/audit?outcome=REJECTED&days=${data.days}` }}
      count={total.toLocaleString()}
      title="Why queries did not run">
      {refusals.length === 0 ? (
        <WidgetEmpty icon={CheckCircle} line={`No query was refused or failed in the last ${data.days} days.`} />
      ) : (
        <ul className="tw:flex tw:flex-col tw:gap-3.5">
          {refusals.map((refusal) => (
            <li key={refusal.category}>
              <div className="tw:flex tw:items-baseline tw:justify-between tw:gap-3">
                <Link
                  className="tw:min-w-0 tw:truncate tw:text-sm tw:font-medium tw:text-primary tw:hover:underline"
                  to={`/audit?outcome=${outcomeOf(refusal.category)}&days=${data.days}`}>
                  {CATEGORY_LABELS[refusal.category] ?? refusal.category}
                </Link>
                <span className="tw:shrink-0 tw:text-sm tw:font-medium tw:text-primary tw:tabular-nums">
                  {refusal.count.toLocaleString()}
                  <span className="tw:ml-1 tw:text-xs tw:font-normal tw:text-quaternary">
                    {percent(refusal.count, total)}%
                  </span>
                </span>
              </div>
              <div aria-hidden className="tw:mt-1.5 tw:h-1.5 tw:w-full tw:overflow-hidden tw:rounded-full tw:bg-secondary">
                <div
                  className="tw:h-full tw:rounded-full"
                  style={{
                    width: `${(refusal.count / widest) * 100}%`,
                    backgroundColor: outcomeOf(refusal.category) === 'FAILED' ? COLOURS.warning : COLOURS.error,
                  }}
                />
              </div>
            </li>
          ))}
        </ul>
      )}
    </Widget>
  );
}

// ---------------------------------------------------------- sensitive tables

function SensitiveTablesCard({ data }: { data: Dashboard }) {
  const [all, setAll] = useState(false);
  const rows = data.coverage.rows;
  const shown = all ? rows : rows.slice(0, TABLES_SHOWN);
  const unprotected = data.coverage.sensitive - data.coverage.protectedTables;

  return (
    <Widget
      count={
        unprotected > 0 ? `${unprotected} unprotected` : data.coverage.sensitive > 0 ? 'all protected' : undefined
      }
      title={`Tables carrying ${data.label}`}>
      {rows.length === 0 ? (
        <WidgetEmpty icon={ShieldTick} line={`No table carries ${data.label}.`} />
      ) : (
        <>
          <div className="tw:-mx-5 tw:overflow-x-auto">
            <table className="tw:w-full tw:min-w-[720px] tw:text-sm">
              <thead>
                <tr className="tw:border-b tw:border-secondary tw:text-left tw:text-xs tw:font-medium tw:text-tertiary">
                  <th className="tw:px-5 tw:pb-2 tw:font-medium">Table</th>
                  <th className="tw:px-3 tw:pb-2 tw:font-medium">Protection</th>
                  <th className="tw:px-3 tw:pb-2 tw:font-medium">Who can get in</th>
                  <th className="tw:px-3 tw:pb-2 tw:text-right tw:font-medium">Read by</th>
                  <th className="tw:px-3 tw:pb-2 tw:text-right tw:font-medium">Refused</th>
                  <th className="tw:px-5 tw:pb-2 tw:font-medium">Owners</th>
                </tr>
              </thead>
              <tbody className="tw:divide-y tw:divide-secondary">
                {shown.map((table) => (
                  <SensitiveRow days={data.days} key={table.fqn} table={table} />
                ))}
              </tbody>
            </table>
          </div>
          {rows.length > TABLES_SHOWN && (
            <button
              className="tw:mt-3 tw:cursor-pointer tw:text-xs tw:font-semibold tw:text-brand-secondary tw:hover:underline"
              onClick={() => setAll((was) => !was)}
              type="button">
              {all ? 'Show fewer' : `Show all ${rows.length}`}
            </button>
          )}
          {data.coverage.sensitive > rows.length && (
            <p className="tw:mt-2 tw:text-xs tw:text-quaternary">
              The first {rows.length} of {data.coverage.sensitive}, unprotected first; the counts above cover all
              of them.
            </p>
          )}
        </>
      )}
    </Widget>
  );
}

function SensitiveRow({ table, days }: { table: SensitiveTable; days: number }) {
  const covered = table.dataPolicies > 0;
  const reachable = table.subscriptionPolicies > 0 || table.activeGrants > 0;
  const ways = [
    table.subscriptionPolicies > 0 ? plural(table.subscriptionPolicies, 'policy', 'policies') : null,
    table.activeGrants > 0 ? plural(table.activeGrants, 'grant', 'grants') : null,
  ].filter(Boolean);

  return (
    <tr>
      <td className="tw:max-w-72 tw:px-5 tw:py-2.5">
        <Link
          className="tw:block tw:truncate tw:font-medium tw:text-primary tw:hover:underline"
          title={table.fqn}
          to={`/catalog/${encodeURIComponent(table.fqn)}`}>
          {shortName(table.fqn)}
        </Link>
        <span className="tw:block tw:truncate tw:text-xs tw:text-quaternary" title={table.fqn}>
          {table.sensitiveColumns > 0
            ? `${plural(table.sensitiveColumns, 'column', 'columns')} labelled`
            : 'Labelled on the table'}
        </span>
      </td>
      <td className="tw:px-3 tw:py-2.5">
        {covered ? (
          <Badge color="success" size="sm" type="pill-color">
            {plural(table.dataPolicies, 'data policy', 'data policies')}
          </Badge>
        ) : (
          <Badge color={reachable ? 'error' : 'gray'} size="sm" type="pill-color">
            Unprotected
          </Badge>
        )}
      </td>
      <td className="tw:px-3 tw:py-2.5 tw:text-secondary">
        {ways.length > 0 ? ways.join(' · ') : <span className="tw:text-quaternary">Nobody</span>}
      </td>
      <td className="tw:px-3 tw:py-2.5 tw:text-right tw:tabular-nums">
        {table.readers > 0 ? (
          <Link
            className={`tw:hover:underline ${covered ? 'tw:text-primary' : 'tw:font-semibold tw:text-error-primary'}`}
            to={`/audit?table=${encodeURIComponent(table.fqn)}&days=${days}`}>
            {plural(table.readers, 'person', 'people')}
          </Link>
        ) : (
          <span className="tw:text-quaternary">–</span>
        )}
      </td>
      <td className="tw:px-3 tw:py-2.5 tw:text-right tw:tabular-nums">
        {table.refused > 0 ? (
          <Link
            className="tw:text-primary tw:hover:underline"
            to={`/audit?outcome=REJECTED&table=${encodeURIComponent(table.fqn)}&days=${days}`}>
            {table.refused.toLocaleString()}
          </Link>
        ) : (
          <span className="tw:text-quaternary">–</span>
        )}
      </td>
      <td className="tw:max-w-48 tw:truncate tw:px-5 tw:py-2.5 tw:text-secondary" title={table.owners.join(', ')}>
        {table.owners.length > 0 ? table.owners.join(', ') : <span className="tw:text-quaternary">No owner</span>}
      </td>
    </tr>
  );
}

// ------------------------------------------------------------ ending access

/** The server's "now", carried forward by the browser's elapsed time only. */
function useServerNow(serverNow: string, fetchedAt: number): number {
  const [tick, setTick] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setTick(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const base = new Date(serverNow).getTime();
  return Number.isNaN(base) ? tick : base + (tick - fetchedAt);
}

const URGENCY_CLASS: Record<Urgency, string> = {
  ended: 'tw:bg-secondary tw:text-tertiary',
  today: 'tw:bg-utility-error-50 tw:text-utility-error-700',
  soon: 'tw:bg-utility-warning-50 tw:text-utility-warning-700',
  later: 'tw:bg-secondary tw:text-secondary',
};

function EndingCard({ data, fetchedAt }: { data: Dashboard; fetchedAt: number }) {
  const now = useServerNow(data.generatedAt, fetchedAt);
  const { endingSoon, endingCount } = data.grants;
  return (
    <div className="tw:scroll-mt-4" id="ending">
      <Widget
        className="tw:h-full"
        count={endingCount > endingSoon.length ? `${endingSoon.length} of ${endingCount}` : endingCount}
        title="Access ending in 14 days">
        {endingSoon.length === 0 ? (
          <WidgetEmpty icon={Hourglass01} line="No grant ends in the next 14 days." />
        ) : (
          <ul aria-label="Access ending" className="tw:divide-y tw:divide-secondary">
            {endingSoon.map((grant) => (
              <EndingRow grant={grant} key={`${grant.principal}|${grant.assetFqn}|${grant.validUntil}`} now={now} />
            ))}
          </ul>
        )}
      </Widget>
    </div>
  );
}

function EndingRow({ grant, now }: { grant: EndingGrant; now: number }) {
  const left = new Date(grant.validUntil).getTime() - now;
  return (
    <li className="tw:flex tw:items-start tw:gap-3 tw:py-2.5 tw:first:pt-0 tw:last:pb-0">
      <span className="tw:min-w-0 tw:flex-1">
        <span className="tw:flex tw:items-center tw:gap-2">
          <span className="tw:truncate tw:text-sm tw:font-medium tw:text-primary">{grant.principal}</span>
          {grant.principalType === 'GROUP' && (
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
      <span
        aria-label={`Ends in ${countdown(left)}`}
        className={`tw:shrink-0 tw:rounded-md tw:px-2 tw:py-0.5 tw:font-mono tw:text-xs tw:font-medium tw:tabular-nums ${
          URGENCY_CLASS[urgencyOf(left)]
        }`}
        role="timer"
        title={new Date(grant.validUntil).toLocaleString()}>
        {countdown(left)}
      </span>
    </li>
  );
}

// ------------------------------------------------------------------ busiest

function BusiestCard({
  title,
  rows,
  empty,
  to,
  people,
}: {
  title: string;
  rows: Busiest[];
  empty: string;
  to: (row: Busiest) => string;
  people: (row: Busiest) => string;
}) {
  const widest = Math.max(1, ...rows.map((row) => row.queries));
  return (
    <Widget title={title}>
      {rows.length === 0 ? (
        <WidgetEmpty icon={BarChartSquare02} line={empty} />
      ) : (
        <ol aria-label={title} className="tw:flex tw:flex-col tw:gap-3">
          {rows.map((row) => (
            <li key={row.name}>
              <div className="tw:flex tw:items-baseline tw:justify-between tw:gap-3">
                <Link
                  className="tw:min-w-0 tw:truncate tw:text-sm tw:font-medium tw:text-primary tw:hover:underline"
                  title={row.name}
                  to={to(row)}>
                  {shortName(row.name)}
                </Link>
                <span className="tw:shrink-0 tw:text-sm tw:font-medium tw:text-primary tw:tabular-nums">
                  {row.queries.toLocaleString()}
                </span>
              </div>
              <div aria-hidden className="tw:mt-1 tw:flex tw:h-1.5 tw:w-full tw:overflow-hidden tw:rounded-full tw:bg-secondary">
                <div className="tw:flex tw:h-full" style={{ width: `${(row.queries / widest) * 100}%` }}>
                  <div style={{ width: `${((row.queries - row.refused) / row.queries) * 100}%`, backgroundColor: COLOURS.brand }} />
                  <div style={{ width: `${(row.refused / row.queries) * 100}%`, backgroundColor: COLOURS.error }} />
                </div>
              </div>
              <p className="tw:mt-1 tw:text-xs tw:text-quaternary">
                {people(row)}
                {row.refused > 0 && ` · ${row.refused.toLocaleString()} refused`}
                {row.lastAt && ` · last ${relativeTime(row.lastAt)}`}
              </p>
            </li>
          ))}
        </ol>
      )}
    </Widget>
  );
}

// ------------------------------------------------------------------- grants

function GrantsCard({ data }: { data: Dashboard }) {
  const grants = data.grants;
  return (
    <div className="tw:scroll-mt-4" id="grants">
      <Widget className="tw:h-full" count={grants.active} title="Grants in force">
        {grants.active === 0 ? (
          <WidgetEmpty
            icon={ShieldTick}
            line="Nobody holds a grant. Access comes from subscription policies alone."
          />
        ) : (
          <div className="tw:flex tw:flex-col tw:gap-3.5">
            <Share colour={COLOURS.brand} label={`On tables carrying ${data.label}`} total={grants.active} value={grants.onSensitive} />
            <Share colour={COLOURS.warning} label="Ending in 14 days" total={grants.active} value={grants.endingCount} />
            <Share colour={COLOURS.gray} label="No end date" total={grants.active} value={grants.openEnded} />
            <Share colour={COLOURS.error} label="Not used in 90 days" total={grants.active} value={grants.unused} />
          </div>
        )}
      </Widget>
    </div>
  );
}

function Share({ label, value, total, colour }: { label: string; value: number; total: number; colour: string }) {
  return (
    <div>
      <div className="tw:flex tw:items-baseline tw:justify-between tw:gap-2">
        <span className="tw:text-sm tw:text-secondary">{label}</span>
        <span className="tw:text-sm tw:font-medium tw:text-primary tw:tabular-nums">
          {value.toLocaleString()}
          <span className="tw:ml-1 tw:text-xs tw:font-normal tw:text-quaternary">{percent(value, total)}%</span>
        </span>
      </div>
      <div aria-hidden className="tw:mt-1.5 tw:h-1.5 tw:w-full tw:overflow-hidden tw:rounded-full tw:bg-secondary">
        <div className="tw:h-full tw:rounded-full" style={{ width: `${percent(value, total)}%`, backgroundColor: colour }} />
      </div>
    </div>
  );
}

// ----------------------------------------------------------------- requests

function RequestsCard({ data }: { data: Dashboard }) {
  const r = data.requests;
  const open = r.pending + r.approved + r.inProgress;
  const closed = r.completed + r.rejected + r.withdrawn;
  const outcomes = [
    { key: 'completed', label: 'Granted', value: r.completed, colour: COLOURS.success },
    { key: 'rejected', label: 'Rejected', value: r.rejected, colour: COLOURS.error },
    { key: 'withdrawn', label: 'Withdrawn', value: r.withdrawn, colour: COLOURS.gray },
  ];
  return (
    <Widget action={{ label: 'Requests', to: '/requests?tab=inbox' }} count={open} title="Access requests">
      <dl className="tw:grid tw:grid-cols-3 tw:gap-3">
        <Figure label="Waiting" value={r.pending} />
        <Figure label="Approved" value={r.approved} />
        <Figure label="Being set up" value={r.inProgress} />
      </dl>
      <div className="tw:mt-4 tw:border-t tw:border-secondary tw:pt-4">
        <p className="tw:text-xs tw:font-medium tw:text-tertiary">
          Asked in the last {data.days} days: <span className="tw:text-primary tw:tabular-nums">{r.asked}</span>
        </p>
        {closed > 0 ? (
          <>
            <div
              aria-label={outcomes.map((o) => `${o.value} ${o.label.toLowerCase()}`).join(', ')}
              className="tw:mt-2 tw:flex tw:h-2 tw:w-full tw:overflow-hidden tw:rounded-full tw:bg-secondary"
              role="img">
              {outcomes.map((o) =>
                o.value > 0 ? (
                  <div key={o.key} style={{ width: `${(o.value / closed) * 100}%`, backgroundColor: o.colour }} />
                ) : null
              )}
            </div>
            <ul className="tw:mt-2 tw:flex tw:flex-wrap tw:gap-x-4 tw:gap-y-1 tw:text-xs tw:text-tertiary">
              {outcomes.map((o) => (
                <li className="tw:flex tw:items-center tw:gap-1.5" key={o.key}>
                  <span aria-hidden className="tw:size-2 tw:rounded-full" style={{ backgroundColor: o.colour }} />
                  {o.label} <span className="tw:font-medium tw:text-secondary tw:tabular-nums">{o.value}</span>
                </li>
              ))}
            </ul>
          </>
        ) : (
          <p className="tw:mt-2 tw:text-xs tw:text-quaternary">None of them has been closed yet.</p>
        )}
        <p className="tw:mt-3 tw:text-sm tw:text-secondary">
          {r.medianHoursToClose === null
            ? 'No request in this window has had its final answer yet.'
            : `Half are answered within ${hoursLabel(r.medianHoursToClose)}.`}
        </p>
      </div>
    </Widget>
  );
}

function Figure({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <dt className="tw:text-xs tw:text-tertiary">{label}</dt>
      <dd className="tw:mt-0.5 tw:text-lg tw:font-semibold tw:text-primary tw:tabular-nums">{value.toLocaleString()}</dd>
    </div>
  );
}

// ------------------------------------------------------------------- health

const ENFORCEMENT_COLOUR: Record<string, 'success' | 'warning' | 'error' | 'gray' | 'brand'> = {
  APPLIED: 'success',
  PENDING: 'brand',
  DRIFTED: 'warning',
  FAILED: 'error',
  NOT_ENFORCED: 'gray',
};

const ENFORCEMENT_ORDER = ['APPLIED', 'PENDING', 'DRIFTED', 'FAILED', 'NOT_ENFORCED'];

function HealthCard({ data }: { data: Dashboard }) {
  const h = data.health;
  const enforcement = Object.entries(h.enforcement)
    .filter(([, n]) => n > 0)
    .sort(([a], [b]) => rank(ENFORCEMENT_ORDER, a) - rank(ENFORCEMENT_ORDER, b));

  // "SUBSCRIPTION ACTIVE" -> { SUBSCRIPTION: { ACTIVE: n } }
  const byType = new Map<string, Record<string, number>>();
  for (const [key, n] of Object.entries(h.policies)) {
    const [type, state = ''] = key.split(' ');
    byType.set(type, { ...(byType.get(type) ?? {}), [state]: n });
  }

  const sync = h.syncFailed
    ? { text: 'Last sync failed', colour: 'error' as const }
    : h.syncStatus === null
      ? { text: 'Never synced', colour: 'gray' as const }
      : { text: humanise(h.syncStatus), colour: 'success' as const };

  return (
    <Widget action={{ label: 'Enforcement', to: '/enforcement' }} title="Platform health">
      <dl className="tw:flex tw:flex-col tw:gap-3.5 tw:text-sm">
        <HealthRow label="Data sources">
          <span className="tw:tabular-nums">
            {h.sourcesEnabled} enabled
            {h.sources > h.sourcesEnabled && <span className="tw:text-quaternary"> of {h.sources}</span>}
          </span>
        </HealthRow>
        <HealthRow label="OpenMetadata sync">
          <span className="tw:flex tw:items-center tw:gap-2">
            <Badge color={sync.colour} size="sm" type="pill-color">
              {sync.text}
            </Badge>
            {h.lastCrawlAt && <span className="tw:text-xs tw:text-quaternary">{relativeTime(h.lastCrawlAt)}</span>}
          </span>
        </HealthRow>
        <HealthRow label="Enforced objects">
          {enforcement.length === 0 ? (
            <span className="tw:text-quaternary">None applied</span>
          ) : (
            <span className="tw:flex tw:flex-wrap tw:justify-end tw:gap-1">
              {enforcement.map(([status, n]) => (
                <Badge color={ENFORCEMENT_COLOUR[status] ?? 'gray'} key={status} size="sm" type="pill-color">
                  {n} {humanise(status).toLowerCase()}
                </Badge>
              ))}
            </span>
          )}
        </HealthRow>
        {[...byType.entries()]
          .sort(([a], [b]) => a.localeCompare(b))
          .map(([type, states]) => (
            <HealthRow key={type} label={`${humanise(type)} policies`}>
              <span className="tw:tabular-nums">
                {states.ACTIVE ?? 0} active
                {Object.entries(states)
                  .filter(([state, n]) => state !== 'ACTIVE' && n > 0)
                  .sort(([a], [b]) => a.localeCompare(b))
                  .map(([state, n]) => (
                    <span className="tw:text-quaternary" key={state}>
                      {' '}
                      · {n} {humanise(state).toLowerCase()}
                    </span>
                  ))}
              </span>
            </HealthRow>
          ))}
      </dl>
    </Widget>
  );
}

function HealthRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="tw:flex tw:items-center tw:justify-between tw:gap-3">
      <dt className="tw:shrink-0 tw:text-tertiary">{label}</dt>
      <dd className="tw:min-w-0 tw:text-right tw:text-primary">{children}</dd>
    </div>
  );
}

// ------------------------------------------------------------------ helpers

function percent(part: number, whole: number): number {
  return whole > 0 ? Math.round((part / whole) * 100) : 0;
}

function rank(order: string[], value: string): number {
  const at = order.indexOf(value);
  return at === -1 ? order.length : at;
}

/** The label reaches the address a moment after typing stops. */
function LabelField({ value, onCommit }: { value: string; onCommit: (value: string) => void }) {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);
  useEffect(() => {
    const next = draft.trim();
    if (next === '' || next === value) return undefined;
    const timer = setTimeout(() => onCommit(next), 450);
    return () => clearTimeout(timer);
    // onCommit is a fresh closure every render; the draft is what matters.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft, value]);
  return (
    <TextField
      ariaLabel="Sensitive label"
      className="tw:w-44"
      onChange={setDraft}
      placeholder="Label, e.g. PII"
      value={draft}
    />
  );
}
