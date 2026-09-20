import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useIsFetching } from '@tanstack/react-query';
import { useLocation, type Location } from 'react-router-dom';

/**
 * How long the outgoing page is held before the incoming one takes its place.
 *
 * A single-page app swaps the DOM in a few milliseconds, which reads as a
 * flicker rather than as navigation: people are left unsure whether their
 * click registered, and click again. Holding the page you came from for a beat
 * while the bar runs is the difference between "something happened" and
 * "something flashed".
 */
const HOLD_MS = 420;

/** And an upper bound, so a query that never settles cannot hang the bar. */
const CEILING_MS = 4000;

/**
 * The lagged location that the router should actually render.
 *
 * Must be called *outside* the `<Routes location={…}>` it feeds, because
 * overriding the location installs a new LocationContext and everything under
 * it — this hook included — would then read back its own output and never
 * move.
 *
 * Returning a location rather than holding on to the rendered children is the
 * whole trick. `<Outlet />` resolves against the router context at render
 * time, so keeping the previous element around keeps nothing: the same element
 * simply renders the new page. The only way to hold a page is to hold the
 * location it was rendered from.
 */
export function useRouteLag(): { display: Location; loading: boolean } {
  const location = useLocation();
  const id = `${location.pathname}${location.search}`;

  const [display, setDisplay] = useState<Location>(location);
  const [loading, setLoading] = useState(false);
  const displayId = `${display.pathname}${display.search}`;

  // Whatever the address bar says right now. Read at the end of the hold, so a
  // second click during the hold lands on the page that was asked for last
  // rather than on the one that started the hold.
  const latest = useRef(location);
  latest.current = location;

  const fetching = useIsFetching();
  const held = useRef(true);
  const startedAt = useRef(0);

  useEffect(() => {
    if (id === displayId) {
      return;
    }

    setLoading(true);
    held.current = false;
    startedAt.current = Date.now();

    const timer = window.setTimeout(() => {
      setDisplay(latest.current);
      held.current = true;
    }, HOLD_MS);

    return () => window.clearTimeout(timer);
  }, [id, displayId]);

  // The bar outlives the swap: it comes down once the page that arrived has
  // finished fetching, so a screen whose data takes two seconds keeps the bar
  // up for two seconds instead of declaring victory over an empty table.
  useEffect(() => {
    if (!loading) {
      return;
    }
    const timer = window.setInterval(() => {
      const expired = Date.now() - startedAt.current > CEILING_MS;
      if (expired || (held.current && fetching === 0)) {
        setLoading(false);
      }
    }, 60);
    return () => window.clearInterval(timer);
  }, [loading, fetching]);

  return { display, loading };
}

/**
 * The fade that plays when the held page is finally replaced.
 *
 * Keyed by the location the router is rendering — the lagged one, because this
 * sits under the overridden LocationContext — so React remounts exactly when
 * the swap happens and the animation runs with it rather than after it.
 */
export default function RouteTransition({ children }: { children: ReactNode }) {
  const { pathname, search } = useLocation();

  return (
    <div className="arak-page-enter" key={`${pathname}${search}`}>
      {children}
    </div>
  );
}

/**
 * The bar across the top.
 *
 * It creeps to nearly full while the page loads and then completes, rather
 * than animating at a constant rate to an unknown finish. A bar that reaches
 * the end and sits there is a bar that says "done" while nothing is.
 */
export function RouteProgress({ active }: { active: boolean }) {
  // Kept mounted for the length of the fade-out, so the finished bar is seen
  // reaching the right-hand edge instead of vanishing at ninety per cent.
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (active) {
      setVisible(true);
      return;
    }
    const timer = window.setTimeout(() => setVisible(false), 320);
    return () => window.clearTimeout(timer);
  }, [active]);

  if (!visible) {
    return null;
  }

  return (
    <div
      aria-hidden
      className="tw:pointer-events-none tw:fixed tw:inset-x-0 tw:top-0 tw:z-100 tw:h-0.5">
      <div
        className={`tw:h-full tw:rounded-r-full tw:bg-brand-solid tw:shadow-[0_0_8px_var(--color-bg-brand-solid)] ${
          active ? 'arak-progress-creep' : 'arak-progress-finish'
        }`}
      />
    </div>
  );
}
