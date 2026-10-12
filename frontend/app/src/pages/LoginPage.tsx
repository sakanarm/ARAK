import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { AlertCircle, Columns01, Mail01, Rows01, ShieldTick } from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Input } from '@openmetadata/ui-core-components/components/base/input/input';
import { PasswordInput } from '@openmetadata/ui-core-components/components/base/input/password-input';
import { apiErrorMessage, entraStartUrl, fetchAuthConfig } from '../api/client';
import { AUTH_SPLASH_MS, useAuthSplash } from '../auth/AuthSplash';
import { useAuthStore } from '../auth/authStore';
import logo from '../assets/arak-logo.png';

interface FromState {
  from?: { pathname?: string; search?: string; hash?: string };
}

/**
 * What the service means by each reason it sends back after a Microsoft
 * sign-in that did not end in a session. Signing in with Microsoft proves who
 * somebody is; whether they may use ARAK is ARAK's own accounts and roles, and
 * a first sign-in usually ends at "not_registered" until an administrator adds
 * the person.
 */
const SSO_ERRORS: Record<string, string> = {
  not_registered:
    'You are signed in with Microsoft, but you do not have access to ARAK yet. Ask an ARAK administrator to add your account.',
  ambiguous:
    'More than one ARAK account uses your email address. Ask an ARAK administrator to sort it out.',
  not_allowed: 'Your account is not set up to use ARAK. Ask your administrator.',
  unavailable: 'The sign-in service is not available at the moment. Try again in a few minutes.',
  cancelled: 'Sign-in with Microsoft was cancelled.',
};

const SSO_FAILED = 'Sign-in with Microsoft did not finish. Try again.';

/** A console path, never another site: the value came back through the address bar. */
function safePath(value: string | null): string | null {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return null;
  }
  return value.startsWith('/login') ? null : value;
}

/**
 * Sign-in.
 *
 * Two ways in when Microsoft sign-in is set up: the Microsoft button, and
 * email (or username) with a password, which every local account keeps. When
 * it is not set up, the password form is the whole page, as before.
 *
 * A Microsoft sign-in leaves this page for Microsoft and comes back to it
 * with ?sso=<code>, a one-time code the service trades for a session, or with
 * ?sso_error=<reason>.
 */
export default function LoginPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const token = useAuthStore((state) => state.token);
  const signIn = useAuthStore((state) => state.signIn);
  const signInWithSso = useAuthStore((state) => state.signInWithSso);
  const splash = useAuthSplash();

  const params = new URLSearchParams(location.search);
  const ssoCode = params.get('sso');
  const ssoError = params.get('sso_error');
  const ssoTarget = safePath(params.get('to'));

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(
    ssoError ? (SSO_ERRORS[ssoError] ?? SSO_FAILED) : null
  );
  const [submitting, setSubmitting] = useState(false);
  const [emailChosen, setEmailChosen] = useState(false);
  const exchanged = useRef<string | null>(null);

  const { data: config, isPending } = useQuery({
    queryKey: ['auth-config'],
    queryFn: fetchAuthConfig,
    retry: false,
    staleTime: Infinity,
  });

  // The query string and fragment travel with the path: somebody sent here
  // from /query?sql=... was reading a particular thing, and dropping the part
  // that says which one returns them to a blank version of the right screen.
  //
  // /login is refused as a destination even though nothing should ever offer
  // it. If it ever were, this component would redirect to itself for as long
  // as React allowed, and the page it left behind would be blank.
  const from = (location.state as FromState | null)?.from;
  const target =
    ssoTarget ??
    (from?.pathname && from.pathname !== '/login'
      ? `${from.pathname}${from.search ?? ''}${from.hash ?? ''}`
      : '/');

  useEffect(() => {
    // Once per code. The code works a single time, and React runs an effect
    // twice in development, so a second exchange would replace a session that
    // worked with an error that is not true.
    if (!ssoCode || exchanged.current === ssoCode) {
      return;
    }
    exchanged.current = ssoCode;
    splash.show('signing-in');
    signInWithSso(ssoCode)
      .then(() => splash.hideAfter(AUTH_SPLASH_MS))
      .catch((caught) => {
        splash.hide();
        setError(apiErrorMessage(caught, SSO_FAILED));
        // Off the address bar, so a reload does not spend a used code again.
        navigate('/login', { replace: true });
      });
  }, [ssoCode, signInWithSso, splash, navigate]);

  if (token) {
    return <Navigate replace to={target} />;
  }

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!username.trim() || !password) {
      setError('Enter your email or username, and your password.');
      return;
    }
    setSubmitting(true);
    setError(null);

    // Up before the request, not after it. Raising it on success left a frame
    // in which the token existed but the curtain did not, and the guarded
    // layout painted through the gap — the flash of a half-built home page
    // between clicking and the loading screen.
    splash.show('signing-in');

    try {
      await signIn(username.trim(), password);

      // The redirect above fires as soon as the token lands and unmounts this
      // component, so the curtain cannot be lowered from here -- it lowers
      // itself. The navigate that used to sit in a timer alongside was that
      // same redirect a second time, 900ms later, against a tree that had
      // already moved on.
      splash.hideAfter(AUTH_SPLASH_MS);
    } catch (caught) {
      // A rejected password drops the curtain at once: the wait is over, and
      // the answer belongs on the form that asked the question.
      splash.hide();
      setError(apiErrorMessage(caught, 'Sign-in failed.'));
      setSubmitting(false);
    } finally {
      // The password never stays in state longer than the attempt that used it.
      setPassword('');
    }
  };

  const localDisabled = config?.localLoginEnabled === false;
  const entra = config?.entraEnabled === true;
  // With Microsoft sign-in on, the form waits behind "Sign in with email" so
  // that the button most people want is the first thing on the page.
  const showForm = !localDisabled && (!entra || emailChosen);

  const alert = error && (
    <div
      className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:border tw:border-error_subtle tw:bg-error-primary tw:p-3"
      role="alert">
      <AlertCircle aria-hidden className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-error-primary" />
      <p className="tw:text-sm tw:text-error-primary">{error}</p>
    </div>
  );

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

          {/* Nothing until the service says which ways in it offers, so the
              page does not show the form and then swap it for buttons. */}
          {!isPending && (
            <div className="tw:mt-8 tw:flex tw:flex-col tw:gap-4">
              {entra && (
                <>
                  <MicrosoftButton
                    href={entraStartUrl(target)}
                    label={config?.entraButtonLabel || 'Sign in with Microsoft'}
                  />
                  {!showForm && !localDisabled && (
                    <Button
                      className="tw:w-full"
                      color="secondary"
                      iconLeading={Mail01}
                      onClick={() => {
                        setEmailChosen(true);
                        setError(null);
                      }}
                      size="lg"
                      type="button">
                      Sign in with email
                    </Button>
                  )}
                  {!showForm && alert}
                </>
              )}

              {showForm && (
                <>
                  {entra && (
                    <div className="tw:flex tw:items-center tw:gap-3">
                      <hr className="tw:h-px tw:flex-1 tw:border-none tw:bg-border-secondary" />
                      <span className="tw:text-xs tw:text-quaternary">or sign in with email</span>
                      <hr className="tw:h-px tw:flex-1 tw:border-none tw:bg-border-secondary" />
                    </div>
                  )}
                  <form className="tw:flex tw:flex-col tw:gap-5" onSubmit={onSubmit}>
                    <Input
                      // The visible label carries a required asterisk, and PasswordInput
                      // renders its label outside the field so the input ends up with no
                      // accessible name at all. Naming both explicitly fixes the second
                      // and keeps the two fields consistent.
                      aria-label="Email or username"
                      autoComplete="username"
                      autoFocus
                      isDisabled={submitting}
                      isRequired
                      label="Email or username"
                      name="username"
                      onChange={setUsername}
                      placeholder="you@example.com"
                      value={username}
                    />

                    <PasswordInput
                      aria-label="Password"
                      autoComplete="current-password"
                      isDisabled={submitting}
                      isRequired
                      label="Password"
                      name="password"
                      onChange={setPassword}
                      placeholder="Enter your password"
                      value={password}
                    />

                    {alert}

                    <Button
                      className="tw:w-full"
                      isLoading={submitting}
                      showTextWhileLoading
                      size="lg"
                      type="submit">
                      Sign in
                    </Button>
                  </form>
                </>
              )}

              {localDisabled && (
                <>
                  {alert}
                  <p className="tw:text-sm tw:text-tertiary">
                    Local sign-in is disabled on this deployment. Use single sign-on.
                  </p>
                </>
              )}
            </div>
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

/**
 * The single sign-on button: a plain link, because the service answers with
 * a redirect to Microsoft and the whole page has to follow it.
 */
function MicrosoftButton({ href, label }: { href: string; label: string }) {
  return (
    <a
      className="tw:flex tw:h-11 tw:w-full tw:items-center tw:justify-center tw:gap-3 tw:rounded-lg tw:bg-linear-to-r tw:from-violet-700 tw:via-brand-600 tw:via-30% tw:to-cyan-500 tw:px-4 tw:text-md tw:font-semibold tw:text-white tw:shadow-[0_4px_16px_-4px_rgb(21_112_239/0.55)] tw:transition tw:duration-150 tw:hover:from-violet-800 tw:hover:via-brand-700 tw:hover:to-cyan-600 tw:hover:shadow-[0_6px_22px_-4px_rgb(21_112_239/0.7)] tw:focus-visible:outline-2 tw:focus-visible:outline-offset-2 tw:focus-visible:outline-brand-600"
      href={href}>
      <span className="tw:flex tw:size-7 tw:items-center tw:justify-center tw:rounded-md tw:bg-white">
        <MicrosoftLogo />
      </span>
      {label}
    </a>
  );
}

/** Microsoft's four squares, drawn rather than fetched. */
function MicrosoftLogo() {
  return (
    <svg aria-hidden className="tw:size-4" viewBox="0 0 21 21">
      <rect fill="#f25022" height="10" width="10" x="0" y="0" />
      <rect fill="#7fba00" height="10" width="10" x="11" y="0" />
      <rect fill="#00a4ef" height="10" width="10" x="0" y="11" />
      <rect fill="#ffb900" height="10" width="10" x="11" y="11" />
    </svg>
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
