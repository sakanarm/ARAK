import { PasscodeLock } from '@untitledui/icons';
import ChangePasswordForm from './ChangePasswordForm';
import { useAuthStore } from './authStore';

/**
 * What somebody sees in place of the console while the password they signed
 * in with is one an administrator chose -- the bootstrap password, a new
 * account's first one, or a reset.
 *
 * <p>That password has been through somebody else's hands and usually a chat
 * message, so it should not last beyond the first sign-in. The screen offers
 * one way on, choosing a new one, and one way out, signing out; it is drawn
 * instead of the page asked for, so the address stays and the page appears in
 * its place once the password is changed.
 */
export default function PasswordChangeGate() {
  const username = useAuthStore((state) => state.user?.username);
  const signOut = useAuthStore((state) => state.signOut);

  return (
    <main className="tw:flex tw:min-h-screen tw:items-center tw:justify-center tw:bg-secondary tw:px-4 tw:py-12">
      <div className="tw:w-full tw:max-w-md tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-8 tw:shadow-sm">
        <span
          aria-hidden="true"
          className="tw:flex tw:size-12 tw:items-center tw:justify-center tw:rounded-xl tw:bg-utility-brand-50 tw:text-utility-brand-700">
          <PasscodeLock className="tw:size-6" />
        </span>
        <h1 className="tw:mt-5 tw:text-display-xs tw:font-semibold tw:text-primary">
          Choose a new password
        </h1>
        <p className="tw:mt-2 tw:text-sm tw:text-tertiary">
          The password you signed in with
          {username ? (
            <>
              {' '}
              as <strong className="tw:font-semibold tw:text-secondary">{username}</strong>
            </>
          ) : null}{' '}
          was set by an administrator. Choose one only you know before you go on.
        </p>

        <div className="tw:mt-6">
          <ChangePasswordForm submitLabel="Save and continue" />
        </div>

        <button
          className="tw:mt-6 tw:text-sm tw:font-semibold tw:text-tertiary tw:hover:text-secondary"
          onClick={signOut}
          type="button">
          Sign out instead
        </button>
      </div>
    </main>
  );
}
