import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { AlertCircle, Columns01, Rows01, ShieldTick } from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Input } from '@openmetadata/ui-core-components/components/base/input/input';
import { PasswordInput } from '@openmetadata/ui-core-components/components/base/input/password-input';
import { apiErrorMessage, fetchAuthConfig } from '../api/client';
import { useAuthStore } from '../auth/authStore';

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
    <div className="grid min-h-screen grid-cols-1 bg-primary lg:grid-cols-2">
      <main className="flex items-center justify-center px-4 py-12 sm:px-8">
        <div className="w-full max-w-sm">
          <div className="flex size-12 items-center justify-center rounded-lg bg-brand-solid">
            <ShieldTick aria-hidden className="size-6 text-white" />
          </div>

          <h1 className="mt-6 text-display-xs font-semibold text-primary">
            Sign in to ARAK
          </h1>
          <p className="mt-2 text-sm text-tertiary">
            Data access control over your OpenMetadata catalog.
          </p>

          <form className="mt-8 flex flex-col gap-5" onSubmit={onSubmit}>
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
                className="flex items-start gap-2 rounded-lg border border-error_subtle bg-error-primary p-3"
                role="alert">
                <AlertCircle aria-hidden className="mt-0.5 size-4 shrink-0 text-error-primary" />
                <p className="text-sm text-error-primary">{error}</p>
              </div>
            )}

            <Button
              className="w-full"
              isDisabled={localDisabled}
              isLoading={submitting}
              showTextWhileLoading
              size="lg"
              type="submit">
              Sign in
            </Button>
          </form>

          {config?.entraEnabled && (
            <div className="mt-6">
              <div className="flex items-center gap-3">
                <hr className="h-px flex-1 border-none bg-border-secondary" />
                <span className="text-xs text-quaternary">or</span>
                <hr className="h-px flex-1 border-none bg-border-secondary" />
              </div>
              <Button
                className="mt-6 w-full"
                color="secondary"
                isDisabled
                size="lg"
                type="button">
                Continue with Microsoft Entra ID
              </Button>
              <p className="mt-2 text-center text-xs text-quaternary">
                Single sign-on is configured but not yet enabled in this build.
              </p>
            </div>
          )}

          {localDisabled && (
            <p className="mt-6 text-sm text-tertiary">
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
      <aside className="hidden flex-col justify-center gap-8 border-l border-secondary bg-secondary px-16 lg:flex">
        <h2 className="text-display-sm font-semibold text-primary">
          One policy. Every engine.
        </h2>
        <ul className="flex flex-col gap-6">
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
    <li className="flex gap-4">
      <span className="flex size-10 shrink-0 items-center justify-center rounded-lg border border-secondary bg-primary">
        <Icon className="size-5 text-fg-brand-primary" />
      </span>
      <span>
        <p className="text-sm font-medium text-primary">{title}</p>
        <p className="mt-1 text-sm text-tertiary">{text}</p>
      </span>
    </li>
  );
}
