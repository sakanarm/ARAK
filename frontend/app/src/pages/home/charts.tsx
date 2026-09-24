/**
 * The two chart shapes the home page offers, drawn by hand.
 *
 * There is no chart library in this app and this is not the place to add one:
 * every chart here is a single breakdown of one categorical field — assets by
 * type, policies by state — which is a list of labels and numbers. A charting
 * dependency would bring an axis engine, a scale engine and a tooltip layer to
 * draw two dozen SVG elements, and would bring its own colours, which is the
 * one thing these must not have: a donut whose "active" slice is a different
 * green from the `Active` chip beside it reads as two different facts.
 *
 * So the palette is the theme's own utility ramp, resolved through CSS
 * variables, which means these redraw correctly in dark mode without this file
 * knowing that dark mode exists.
 */

/** One category of a breakdown. */
export interface Slice {
  label: string;
  value: number;
}

/**
 * The colour ramp, in the order slices take it.
 *
 * Ordered so that adjacent slices are far apart in hue — a ramp that walks
 * blue→blue-light→indigo looks like a gradient rather than like categories,
 * and the reader stops being able to tell where one ends.
 */
const PALETTE = [
  'var(--color-utility-brand-500, #2e90fa)',
  'var(--color-utility-purple-500, #7a5af8)',
  'var(--color-utility-success-500, #17b26a)',
  'var(--color-utility-orange-500, #fb6514)',
  'var(--color-utility-pink-500, #ee46bc)',
  'var(--color-utility-blue-light-500, #0ba5ec)',
  'var(--color-utility-indigo-500, #6172f3)',
  'var(--color-utility-warning-500, #f79009)',
  'var(--color-utility-gray-blue-500, #4e5ba6)',
  'var(--color-utility-error-500, #f04438)',
];

/** The colour of the nth slice, wrapping rather than running out. */
export function colorAt(index: number): string {
  return PALETTE[index % PALETTE.length];
}

/**
 * Sorts biggest first and folds the tail into "Other".
 *
 * A breakdown with thirty categories is not a chart, it is a table drawn
 * badly. Ten is where a legend still fits beside the drawing at the width of
 * one grid column.
 */
export function topSlices(slices: Slice[], max = 8): Slice[] {
  const sorted = [...slices]
    .filter((slice) => slice.value > 0)
    .sort((a, b) => b.value - a.value);
  if (sorted.length <= max) {
    return sorted;
  }
  const head = sorted.slice(0, max - 1);
  const rest = sorted.slice(max - 1);
  const total = rest.reduce((sum, slice) => sum + slice.value, 0);
  return [...head, { label: `Other (${rest.length})`, value: total }];
}

/** Turns a `Record<string, number>` from an API into slices. */
export function slicesOf(counts: Record<string, number> | undefined): Slice[] {
  return Object.entries(counts ?? {}).map(([label, value]) => ({
    label,
    value,
  }));
}

/**
 * A ring, drawn as one circle per slice with a dashed stroke.
 *
 * The trick is the radius: at r = 100 / 2π the circumference is exactly 100, so
 * a slice's `stroke-dasharray` is its percentage and nothing has to be scaled.
 */
function Ring({ slices, total }: { slices: Slice[]; total: number }) {
  let offset = 0;

  return (
    <svg
      className="tw:size-32 tw:shrink-0"
      role="presentation"
      viewBox="0 0 42 42">
      <circle
        cx="21"
        cy="21"
        fill="none"
        r="15.9155"
        stroke="var(--color-bg-secondary, #f5f5f5)"
        strokeWidth="5"
      />
      {slices.map((slice, index) => {
        const percent = (slice.value / total) * 100;
        // Each circle starts where the last one ended. The offset runs
        // backwards because SVG measures dash offset against the direction of
        // the stroke, not with it.
        const dash = `${percent} ${100 - percent}`;
        const element = (
          <circle
            cx="21"
            cy="21"
            fill="none"
            key={slice.label}
            r="15.9155"
            stroke={colorAt(index)}
            strokeDasharray={dash}
            strokeDashoffset={25 - offset}
            strokeWidth="5"
          />
        );
        offset += percent;
        return element;
      })}
    </svg>
  );
}

/** The labelled key beside or under a drawing. */
function Legend({ slices, total }: { slices: Slice[]; total: number }) {
  return (
    <ul className="tw:flex tw:min-w-0 tw:flex-1 tw:flex-col tw:gap-1.5">
      {slices.map((slice, index) => (
        <li
          className="tw:flex tw:items-center tw:gap-2 tw:text-sm"
          key={slice.label}>
          <span
            aria-hidden
            className="tw:size-2.5 tw:shrink-0 tw:rounded-full"
            style={{ backgroundColor: colorAt(index) }}
          />
          <span className="tw:min-w-0 tw:flex-1 tw:truncate tw:text-secondary">
            {slice.label}
          </span>
          <span className="tw:shrink-0 tw:font-medium tw:text-primary tw:tabular-nums">
            {slice.value.toLocaleString()}
          </span>
          <span className="tw:w-10 tw:shrink-0 tw:text-right tw:text-xs tw:text-tertiary tw:tabular-nums">
            {Math.round((slice.value / total) * 100)}%
          </span>
        </li>
      ))}
    </ul>
  );
}

/** Horizontal bars — the shape that stays readable with long labels. */
function BarList({ slices, total }: { slices: Slice[]; total: number }) {
  const largest = Math.max(...slices.map((slice) => slice.value));

  return (
    <ul className="tw:flex tw:flex-col tw:gap-3">
      {slices.map((slice, index) => (
        <li key={slice.label}>
          <div className="tw:flex tw:items-baseline tw:justify-between tw:gap-2">
            <span className="tw:min-w-0 tw:truncate tw:text-sm tw:text-secondary">
              {slice.label}
            </span>
            <span className="tw:shrink-0 tw:text-sm tw:font-medium tw:text-primary tw:tabular-nums">
              {slice.value.toLocaleString()}
              <span className="tw:text-tertiary">
                {' '}
                · {Math.round((slice.value / total) * 100)}%
              </span>
            </span>
          </div>
          {/*
            Scaled against the largest bar rather than the total, so a
            breakdown where one category holds 80% still shows the difference
            between the three that share the rest.
          */}
          <div
            aria-hidden
            className="tw:mt-1.5 tw:h-2 tw:w-full tw:overflow-hidden tw:rounded-full tw:bg-secondary">
            <div
              className="tw:h-full tw:rounded-full tw:transition-all"
              style={{
                width: `${Math.max(2, (slice.value / largest) * 100)}%`,
                backgroundColor: colorAt(index),
              }}
            />
          </div>
        </li>
      ))}
    </ul>
  );
}

/**
 * A breakdown, drawn as the widget's configured shape.
 *
 * The empty case is a sentence rather than an empty ring, for the reason every
 * widget on this page states its own emptiness: a chart of nothing drawn as a
 * grey circle is indistinguishable from a chart that failed to load.
 */
export function Chart({
  slices,
  shape,
  empty,
}: {
  slices: Slice[];
  shape: 'DONUT' | 'BARS';
  empty: string;
}) {
  const shown = topSlices(slices);
  const total = shown.reduce((sum, slice) => sum + slice.value, 0);

  if (total === 0) {
    return <p className="tw:py-6 tw:text-center tw:text-sm tw:text-tertiary">{empty}</p>;
  }

  const summary = shown
    .map((slice) => `${slice.label} ${slice.value}`)
    .join(', ');

  if (shape === 'DONUT') {
    return (
      <div
        aria-label={summary}
        className="tw:flex tw:flex-wrap tw:items-center tw:gap-5"
        role="img">
        <Ring slices={shown} total={total} />
        <Legend slices={shown} total={total} />
      </div>
    );
  }

  return (
    <div aria-label={summary} role="img">
      <BarList slices={shown} total={total} />
    </div>
  );
}
