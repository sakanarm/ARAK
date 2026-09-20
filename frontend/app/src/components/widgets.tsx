import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight } from '@untitledui/icons';

/**
 * The widget shell the landing page is built from.
 *
 * OpenMetadata's home page is a grid of equal-weight cards, each with a title
 * row, an optional link out, and a body that is either a short list or an empty
 * state that says what would be there. Matching that shape matters more than
 * matching any single colour: somebody who administers the catalog reads this
 * page the same way without being taught it.
 *
 * The empty state is a first-class part of the component rather than something
 * each caller improvises, because most of these widgets are empty on the day a
 * deployment is stood up, and an empty card with no explanation is the thing
 * that makes a console feel broken rather than new.
 */
export function Widget({
  title,
  count,
  action,
  children,
  className = '',
}: {
  title: string;
  count?: ReactNode;
  action?: { label: string; to: string };
  children: ReactNode;
  className?: string;
}) {
  return (
    <section
      className={`tw:flex tw:flex-col tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs ${className}`}>
      <header className="tw:flex tw:items-center tw:justify-between tw:gap-3 tw:border-b tw:border-secondary tw:px-5 tw:py-3.5">
        <h2 className="tw:flex tw:items-center tw:gap-2 tw:text-sm tw:font-semibold tw:text-primary">
          {title}
          {count !== undefined && (
            <span className="tw:rounded-full tw:bg-secondary tw:px-2 tw:py-0.5 tw:text-xs tw:font-medium tw:text-tertiary tw:tabular-nums">
              {count}
            </span>
          )}
        </h2>
        {action && (
          <Link
            className="tw:flex tw:shrink-0 tw:items-center tw:gap-1 tw:text-xs tw:font-semibold tw:text-brand-secondary tw:hover:underline"
            to={action.to}>
            {action.label}
            <ArrowRight className="tw:size-3.5" />
          </Link>
        )}
      </header>
      <div className="tw:flex-1 tw:px-5 tw:py-4">{children}</div>
    </section>
  );
}

/** What a widget shows when it has nothing — and why, not just that. */
export function WidgetEmpty({
  icon: Icon,
  line,
  action,
}: {
  icon?: React.FC<{ className?: string }>;
  line: string;
  action?: { label: string; to: string };
}) {
  return (
    <div className="tw:flex tw:flex-col tw:items-center tw:gap-2 tw:py-6 tw:text-center">
      {Icon && <Icon className="tw:size-6 tw:text-fg-quaternary" />}
      <p className="tw:max-w-xs tw:text-sm tw:text-tertiary">{line}</p>
      {action && (
        <Link
          className="tw:text-sm tw:font-semibold tw:text-brand-secondary tw:hover:underline"
          to={action.to}>
          {action.label}
        </Link>
      )}
    </div>
  );
}

/** A count with its label, as the catalog widgets state theirs. */
export function Stat({
  label,
  value,
  hint,
}: {
  label: string;
  value: ReactNode;
  hint?: string;
}) {
  return (
    <div>
      <p className="tw:text-display-xs tw:font-semibold tw:text-primary tw:tabular-nums">
        {value}
      </p>
      <p className="tw:mt-0.5 tw:text-xs tw:font-medium tw:text-tertiary">
        {label}
      </p>
      {hint && <p className="tw:mt-0.5 tw:text-xs tw:text-quaternary">{hint}</p>}
    </div>
  );
}

/**
 * A proportion, shown as a bar.
 *
 * Coverage is the only number on this page a person acts on — an estate that is
 * 30% tagged is a policy that quietly covers a third of what its author
 * thought — so it is drawn rather than left as two numbers to divide in the
 * head.
 */
export function Meter({
  label,
  value,
  total,
  tone = 'brand',
}: {
  label: string;
  value: number;
  total: number;
  tone?: 'brand' | 'warning';
}) {
  const percent = total > 0 ? Math.round((value / total) * 100) : 0;
  const fill =
    tone === 'warning'
      ? 'var(--color-warning-500, #f79009)'
      : 'var(--color-brand-600, #1570ef)';

  return (
    <div>
      <div className="tw:flex tw:items-baseline tw:justify-between tw:gap-2">
        <span className="tw:text-sm tw:text-secondary">{label}</span>
        <span className="tw:text-sm tw:font-medium tw:text-primary tw:tabular-nums">
          {value.toLocaleString()}
          <span className="tw:text-tertiary"> / {total.toLocaleString()}</span>
        </span>
      </div>
      <div
        aria-hidden
        className="tw:mt-1.5 tw:h-1.5 tw:w-full tw:overflow-hidden tw:rounded-full tw:bg-secondary">
        <div
          className="tw:h-full tw:rounded-full tw:transition-all"
          style={{ width: `${percent}%`, backgroundColor: fill }}
        />
      </div>
    </div>
  );
}

/** "3 minutes ago" — the granularity a feed needs and no more. */
export function relativeTime(iso: string | null | undefined): string {
  if (!iso) {
    return '—';
  }
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) {
    return '—';
  }
  const seconds = Math.round((Date.now() - then) / 1000);
  if (seconds < 60) {
    return 'just now';
  }
  const units: [number, Intl.RelativeTimeFormatUnit][] = [
    [60, 'minute'],
    [3600, 'hour'],
    [86_400, 'day'],
    [604_800, 'week'],
    [2_592_000, 'month'],
    [31_536_000, 'year'],
  ];
  let chosen: [number, Intl.RelativeTimeFormatUnit] = units[0];
  for (const unit of units) {
    if (seconds >= unit[0]) {
      chosen = unit;
    }
  }
  const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
  return formatter.format(-Math.round(seconds / chosen[0]), chosen[1]);
}
