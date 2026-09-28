/**
 * The edges of the Query page that the reader can move.
 *
 * <p>Three of them -- explorer | editor, editor | explanation, and editor over
 * rows -- drawn the same way on purpose: one grip that appears where the
 * pointer is, so they read as one idea rather than three. Each answers to the
 * keyboard as well as to a drag; a separator that only answers to a mouse
 * takes the panel away from anybody who cannot use one.
 *
 * <p>Sizes are remembered per browser, because the right split is a property
 * of the screen and of the work, not of the session.
 */

const KEY_STEP = 16;

/** How wide a side-by-side grip is. It is also the gap between the panels. */
export const SPLITTER_WIDTH = 16;

/**
 * A remembered size, or the fallback on a first visit. Wrapped because storage
 * throws in a private window, and a thrown preference must not cost somebody
 * the page.
 */
export function readSize(key: string, fallback: number, min: number, max = Infinity) {
  try {
    const raw = window.localStorage.getItem(key);
    const saved = raw === null ? Number.NaN : Number(raw);
    if (Number.isFinite(saved) && saved >= min) {
      return Math.min(saved, max);
    }
  } catch {
    // No stored preference is not an error; it is the first visit.
  }
  return fallback;
}

function remember(key: string, value: number) {
  try {
    window.localStorage.setItem(key, String(value));
  } catch {
    // The size still applies for this visit; only the memory of it is lost.
  }
}

/** The floor wins over a ceiling that has dropped below it: a panel never inverts. */
function clamp(next: number, min: number, max: number) {
  return Math.round(Math.min(Math.max(next, min), Math.max(max, min)));
}

/**
 * Drag sideways to decide how wide a panel is.
 *
 * <p>{@code panel} says which side of the grip the sized panel is on, because
 * the same movement means opposite things: dragging right widens the explorer
 * on the left and narrows the explanation on the right.
 */
export function WidthSplitter({
  label,
  panel,
  width,
  min,
  max,
  storageKey,
  onChange,
}: {
  label: string;
  panel: 'left' | 'right';
  width: number;
  min: number;
  /** The widest the panel may be, given the row the grip sits in. */
  max: (row: HTMLElement | null) => number;
  storageKey: string;
  onChange: (next: number) => void;
}) {
  const sign = panel === 'left' ? 1 : -1;

  return (
    <div
      aria-label={label}
      aria-orientation="vertical"
      aria-valuemin={min}
      aria-valuenow={Math.round(width)}
      className="tw:group tw:flex tw:shrink-0 tw:cursor-col-resize tw:items-center tw:justify-center"
      onKeyDown={(event) => {
        const step = event.shiftKey ? KEY_STEP * 4 : KEY_STEP;
        const delta =
          event.key === 'ArrowLeft' ? -step : event.key === 'ArrowRight' ? step : 0;
        if (delta === 0) {
          return;
        }
        event.preventDefault();
        const next = clamp(width + sign * delta, min, max(event.currentTarget.parentElement));
        onChange(next);
        remember(storageKey, next);
      }}
      onPointerDown={(event) => {
        event.preventDefault();
        // Measured once, at the start: the row does not change size under a
        // drag, and measuring on every move would force a layout per pixel.
        const ceiling = max(event.currentTarget.parentElement);
        const startX = event.clientX;
        const startWidth = width;
        let settled = startWidth;

        const move = (moved: PointerEvent) => {
          settled = clamp(startWidth + sign * (moved.clientX - startX), min, ceiling);
          onChange(settled);
        };
        const stop = () => {
          window.removeEventListener('pointermove', move);
          window.removeEventListener('pointerup', stop);
          window.removeEventListener('pointercancel', stop);
          remember(storageKey, settled);
        };
        window.addEventListener('pointermove', move);
        window.addEventListener('pointerup', stop);
        window.addEventListener('pointercancel', stop);
      }}
      role="separator"
      style={{ width: SPLITTER_WIDTH }}
      tabIndex={0}>
      <span className="tw:h-10 tw:w-0.5 tw:rounded-full tw:bg-border-secondary tw:transition tw:group-hover:bg-brand-solid tw:group-focus:bg-brand-solid" />
    </div>
  );
}

/**
 * Drag up and down to decide how much of the column sits above the grip.
 *
 * <p>A fixed ratio cannot be right: a one-line statement against a thousand
 * rows and a forty-line statement against three want opposite splits, and both
 * are ordinary.
 */
export function HeightSplitter({
  label,
  height,
  min,
  minBelow,
  storageKey,
  onChange,
}: {
  label: string;
  height: number;
  min: number;
  /** What the panel below keeps, however far the grip is dragged down. */
  minBelow: number;
  storageKey: string;
  onChange: (next: number) => void;
}) {
  // Measured against the column the handle actually sits in, so the panel
  // below keeps its floor no matter how short the window is.
  function ceiling(handle: HTMLElement | null) {
    const column = handle?.parentElement;
    return column ? column.clientHeight - minBelow : Number.MAX_SAFE_INTEGER;
  }

  return (
    <div
      aria-label={label}
      aria-orientation="horizontal"
      aria-valuemin={min}
      aria-valuenow={Math.round(height)}
      className="tw:group tw:-my-1.5 tw:flex tw:h-3 tw:shrink-0 tw:cursor-row-resize tw:items-center tw:justify-center"
      onKeyDown={(event) => {
        const step = event.shiftKey ? KEY_STEP * 4 : KEY_STEP;
        const delta =
          event.key === 'ArrowUp' ? -step : event.key === 'ArrowDown' ? step : 0;
        if (delta === 0) {
          return;
        }
        event.preventDefault();
        const next = clamp(height + delta, min, ceiling(event.currentTarget));
        onChange(next);
        remember(storageKey, next);
      }}
      onPointerDown={(event) => {
        event.preventDefault();
        const top = ceiling(event.currentTarget);
        const startY = event.clientY;
        const startHeight = height;
        let settled = startHeight;

        const move = (moved: PointerEvent) => {
          settled = clamp(startHeight + moved.clientY - startY, min, top);
          onChange(settled);
        };
        const stop = () => {
          window.removeEventListener('pointermove', move);
          window.removeEventListener('pointerup', stop);
          window.removeEventListener('pointercancel', stop);
          remember(storageKey, settled);
        };
        window.addEventListener('pointermove', move);
        window.addEventListener('pointerup', stop);
        window.addEventListener('pointercancel', stop);
      }}
      role="separator"
      tabIndex={0}>
      <span className="tw:h-0.5 tw:w-10 tw:rounded-full tw:bg-border-secondary tw:transition tw:group-hover:bg-brand-solid tw:group-focus:bg-brand-solid" />
    </div>
  );
}
