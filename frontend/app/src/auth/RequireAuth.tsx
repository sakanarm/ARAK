import { useEffect, useMemo, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import PasswordChangeGate from './PasswordChangeGate';
import { useAuthStore } from './authStore';

/**
 * Guards everything behind the login screen.
 *
 * This is convenience, not security. Every route it protects is rendered from
 * data the backend only returns to an authenticated caller, and the filter on
 * the Dropwizard side is what actually decides. Removing this component would
 * show empty screens, not somebody else's data.
 */
export default function RequireAuth({ children }: { children: ReactNode }) {
  const token = useAuthStore((state) => state.token);
  const initialising = useAuthStore((state) => state.initialising);
  const refresh = useAuthStore((state) => state.refresh);
  const mustChangePassword = useAuthStore((state) => state.mustChangePassword);
  const location = useLocation();

  useEffect(() => {
    if (initialising) {
      void refresh();
    }
  }, [initialising, refresh]);

  // Remember where they were headed so the login screen can put them back
  // there rather than dropping everyone on the home page.
  //
  // Memoised, and built from the parts of the location rather than from the
  // location itself, because <Navigate> lists `state` in the dependency array
  // of the effect that calls navigate(). An object literal written inline is a
  // new dependency on every render, so the effect fires again, navigates
  // again, and renders again. React Router's own example writes
  // `state={{ from: location }}` and gets away with it only because the
  // redirect normally unmounts this component before the second render
  // arrives -- a race, not a guarantee. Lose it twenty-five times over and
  // React stops warning and throws, which unmounts the whole tree and leaves a
  // blank page at /login with no error anywhere the user can see.
  const from = useMemo(
    () => ({
      from: {
        pathname: location.pathname,
        search: location.search,
        hash: location.hash,
      },
    }),
    [location.pathname, location.search, location.hash]
  );

  if (!token) {
    return <Navigate replace state={from} to="/login" />;
  }

  if (initialising) {
    return (
      <div className="tw:flex tw:min-h-screen tw:items-center tw:justify-center">
        <p className="tw:text-sm tw:text-tertiary">Restoring your session…</p>
      </div>
    );
  }

  // In place of the page, not as a redirect to one: the address stays, and the
  // page asked for is what appears once the new password is saved.
  if (mustChangePassword) {
    return <PasswordChangeGate />;
  }

  return <>{children}</>;
}
