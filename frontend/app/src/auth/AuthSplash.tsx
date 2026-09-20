import { create } from 'zustand';
import mark from '../assets/arak-mark.png';

export type AuthPhase = 'idle' | 'signing-in' | 'signing-out';

interface AuthSplashState {
  phase: AuthPhase;
  show: (phase: Exclude<AuthPhase, 'idle'>) => void;
  hide: () => void;
}

/**
 * Whether the sign-in or sign-out curtain is up.
 *
 * A store rather than props because the two ends of this are far apart: the
 * login form raises it, the account menu in the header raises it, and the
 * thing that draws it sits above the router so it survives the route change
 * that signing in or out causes.
 */
export const useAuthSplash = create<AuthSplashState>((set) => ({
  phase: 'idle',
  show: (phase) => set({ phase }),
  hide: () => set({ phase: 'idle' }),
}));

/** How long the curtain stays up. Long enough to read, short enough to forgive. */
export const AUTH_SPLASH_MS = 900;

const COPY: Record<Exclude<AuthPhase, 'idle'>, { title: string; detail: string }> =
  {
    'signing-in': {
      title: 'Signing you in',
      detail: 'Loading your policies and catalog…',
    },
    'signing-out': {
      title: 'Signing you out',
      detail: 'Clearing this session from the browser…',
    },
  };

/**
 * The full-screen curtain shown while a session starts or ends.
 *
 * Signing in and signing out both replace the entire screen, and without a
 * curtain that swap is a white flash followed by a different page — which
 * looks like a crash more than a transition. Naming what is happening also
 * makes the wait honest: sign-in really is fetching the session and the
 * catalog behind this, and sign-out really is discarding the token.
 */
export default function AuthSplash() {
  const phase = useAuthSplash((state) => state.phase);

  if (phase === 'idle') {
    return null;
  }

  const copy = COPY[phase];

  return (
    <div
      aria-live="polite"
      className="arak-splash-enter tw:fixed tw:inset-0 tw:z-200 tw:flex tw:flex-col tw:items-center tw:justify-center tw:gap-6 tw:bg-primary"
      role="status">
      <img
        alt=""
        aria-hidden
        className="arak-splash-mark tw:size-16"
        src={mark}
      />

      <div className="tw:flex tw:flex-col tw:items-center tw:gap-1.5">
        <p className="tw:text-lg tw:font-semibold tw:text-primary">{copy.title}</p>
        <p className="tw:text-sm tw:text-tertiary">{copy.detail}</p>
      </div>

      {/*
        An indeterminate sweep rather than a percentage: nothing here knows how
        far along it is, and a bar that invents a number is a bar that lies.
      */}
      <div className="tw:h-1 tw:w-56 tw:overflow-hidden tw:rounded-full tw:bg-secondary">
        <div className="arak-splash-sweep tw:h-full tw:w-1/3 tw:rounded-full tw:bg-brand-solid" />
      </div>
    </div>
  );
}
