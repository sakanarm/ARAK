import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { AlertCircle, Columns01, Rows01, ShieldTick } from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Input } from '@openmetadata/ui-core-components/components/base/input/input';
import { PasswordInput } from '@openmetadata/ui-core-components/components/base/input/password-input';
import { apiErrorMessage, fetchAuthConfig } from '../api/client';
import { useAuthStore } from '../auth/authStore';
import logo from '../assets/arak-logo.png';

interface FromState {
  from?: { pathname?: string };
}

/**
 * Sign-in.
 *
 * Local username and password is the only method wired up (FR-2.2), which is
 * what the platform needs before a tenant exists and what every test fixture
 * uses. The Entra ID button is rendered from /auth/config rather than from a
 * build flag so that turning OIDC on later is a backend change and a redirect,
 * not a rewrite of this screen.
 */
export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const token = useAuthStore((state) => state.token);
  const signIn = useAuthStore((state) => state.signIn);

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const { data: config } = useQuery({
    queryKey: ['auth-config'],
    queryFn: fetchAuthConfig,
    retry: false,
    staleTime: Infinity,
  });

  const target = (location.state as FromState | null)?.from?.pathname ?? '/';

  if (token) {
    return <Navigate replace to={target} />;
  }

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!username.trim() || !password) {
      setError('Enter your username and password.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await signIn(username.trim(), password);
      navigate(target, { replace: true });
    } catch (caught) {
      setError(apiErrorMessage(caught, 'Sign-in failed.'));
    } finally {
      // The password never stays in state longer than the attempt that used it.
      setPassword('');
      setSubmitting(false);
    }
  };

  const localDisabled = config?.localLoginEnabled === false;

  return (
    <div className="tw:grid tw:min-h-screen tw:grid-cols-1 tw:bg-primary tw:lg:grid-cols-2">
      <main className="tw:flex tw:items-center tw:justify-center tw:px-4 tw:py-12 tw:sm:px-8">
        <div className="tw:w-full tw:max-w-sm">
          {/*
            The lockup already spells out the product and what the initials
            stand for, so the heading below says only what this screen is for.
          */}
          <img
            alt="ARAK — Access Rights and Authorization Keeper"
            // Sized by width, not height: the lockup carries its own subtitle
            // and at the height that suited a plain wordmark that line renders
            // at around ten pixels, which is decoration rather than text.
            className="tw:h-auto tw:w-64"
            src={logo}
          />

          <h1 className="tw:mt-8 tw:text-display-xs tw:font-semibold tw:text-primary">
            Sign in
          </h1>
          <p className="tw:mt-2 tw:text-sm tw:text-tertiary">
            Data access control over your OpenMetadata catalog.
          </p>

          <form className="tw:mt-8 tw:flex tw:flex-col tw:gap-5" onSubmit={onSubmit}>
            <Input
              // The visible label carries a required asterisk, and PasswordInput
              // renders its label outside the field so the input ends up with no
              // accessible name at all. Naming both explicitly fixes the second
              // and keeps the two fields consistent.
              aria-label="Username"
              autoComplete="username"
              autoFocus
              isDisabled={submitting || localDisabled}
              isRequired
              label="Username"
              name="username"
              onChange={setUsername}
              placeholder="admin"
              value={username}
            />

            <PasswordInput
              aria-label="Password"
              autoComplete="current-password"
              isDisabled={submitting || localDisabled}
              isRequired
              label="Password"
              name="password"
              onChange={setPassword}
              placeholder="Enter your password"
              value={password}
            />

            {error && (
              <div
                className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:border tw:border-error_subtle tw:bg-error-primary tw:p-3"
                role="alert">
                <AlertCircle aria-hidden className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-error-primary" />
                <p className="tw:text-sm tw:text-error-primary">{error}</p>
              </div>
            )}

            <Button
              className="tw:w-full"
              isDisabled={localDisabled}
              isLoading={submitting}
              showTextWhileLoading
              size="lg"
              type="submit">
              Sign in
            </Button>
          </form>

          {config?.entraEnabled && (
            <div className="tw:mt-6">
              <div className="tw:flex tw:items-center tw:gap-3">
                <hr className="tw:h-px tw:flex-1 tw:border-none tw:bg-border-secondary" />
                <span className="tw:text-xs tw:text-quaternary">or</span>
                <hr className="tw:h-px tw:flex-1 tw:border-none tw:bg-border-secondary" />
              </div>
              <Button
                className="tw:mt-6 tw:w-full"
                color="secondary"
                isDisabled
                size="lg"
                type="button">
                Continue with Microsoft Entra ID
              </Button>
              <p className="tw:mt-2 tw:text-center tw:text-xs tw:text-quaternary">
                Single sign-on is configured but not yet enabled in this build.
              </p>
            </div>
          )}

          {localDisabled && (
            <p className="tw:mt-6 tw:text-sm tw:text-tertiary">
              Local sign-in is disabled on this deployment. Use single sign-on.
            </p>
          )}
        </div>
      </main>

      {/*
        Decoration on wide screens only. It says what the product does, which is
        the one thing a person standing at a login screen may legitimately not
        know yet.
      */}
      <aside className="tw:hidden tw:flex-col tw:justify-center tw:gap-8 tw:border-l tw:border-secondary tw:bg-secondary tw:px-16 tw:lg:flex">
        <h2 className="tw:text-display-sm tw:font-semibold tw:text-primary">
          One policy. Every engine.
        </h2>
        <ul className="tw:flex tw:flex-col tw:gap-6">
          <Feature
            icon={ShieldTick}
            text="Subscription policies decide who reaches a table; data policies decide what they see in it."
            title="Subscription and data policies"
          />
          <Feature
            icon={Rows01}
            text="Row filters and column masks compose from org down to column, and a local policy can only tighten."
            title="Layered from org to column"
          />
          <Feature
            icon={Columns01}
            text="The same decision compiles to native source config, to a secure view, or to a rewritten query."
            title="Three ways to enforce"
          />
        </ul>
      </aside>
    </div>
  );
}

function Feature({
  icon: Icon,
  title,
  text,
}: {
  icon: React.FC<{ className?: string }>;
  title: string;
  text: string;
}) {
  return (
    <li className="tw:flex tw:gap-4">
      <span className="tw:flex tw:size-10 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:border tw:border-secondary tw:bg-primary">
        <Icon className="tw:size-5 tw:text-fg-brand-primary" />
      </span>
      <span>
        <p className="tw:text-sm tw:font-medium tw:text-primary">{title}</p>
        <p className="tw:mt-1 tw:text-sm tw:text-tertiary">{text}</p>
      </span>
    </li>
  );
}
