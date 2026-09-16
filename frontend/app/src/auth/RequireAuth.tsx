import { useEffect, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
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
  const location = useLocation();

  useEffect(() => {
    if (initialising) {
      void refresh();
    }
  }, [initialising, refresh]);

  if (!token) {
    // Remember where they were headed so the login screen can put them back
    // there rather than dropping everyone on the home page.
    return <Navigate replace state={{ from: location }} to="/login" />;
  }

  if (initialising) {
    return (
      <div className="tw:flex tw:min-h-screen tw:items-center tw:justify-center">
        <p className="tw:text-sm tw:text-tertiary">Restoring your session…</p>
      </div>
    );
  }

  return <>{children}</>;
}
