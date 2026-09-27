import { useState, type FormEvent } from 'react';
import { AlertCircle, CheckCircle } from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { PasswordInput } from '@openmetadata/ui-core-components/components/base/input/password-input';
import { apiErrorMessage, changePassword } from '../api/client';
import { useAuthStore } from './authStore';

/** The same bounds the backend holds a chosen password to. */
export const MIN_PASSWORD = 12;
export const MAX_PASSWORD = 200;

/**
 * Why a new password would be refused, or null when it would not.
 *
 * <p>Checked here before anything is sent, so the common mistakes -- two
 * fields that do not match, a password too short -- are answered at once and
 * do not spend one of the attempts a wrong current password counts against.
 * The backend checks all of it again; this is for the person, not for safety.
 */
export function passwordProblem(
  current: string,
  chosen: string,
  confirmation: string,
  username?: string
): string | null {
  if (!current) {
    return 'Enter your current password.';
  }
  if (chosen.length < MIN_PASSWORD) {
    return `The new password needs at least ${MIN_PASSWORD} characters.`;
  }
  if (chosen.length > MAX_PASSWORD) {
    return `The new password can be at most ${MAX_PASSWORD} characters.`;
  }
  if (username && chosen.toLowerCase() === username.toLowerCase()) {
    return 'The new password cannot be your username.';
  }
  if (chosen === current) {
    return 'The new password must be different from the current one.';
  }
  if (chosen !== confirmation) {
    return 'The two new passwords do not match.';
  }
  return null;
}

/**
 * Current password, a new one, and the new one again (FR-2.2).
 *
 * <p>Used on the profile page and on the screen that stops somebody signed in
 * with a password an administrator chose. Either way a success clears that
 * stop, so the store is told here rather than by each caller.
 */
export default function ChangePasswordForm({
  submitLabel = 'Change password',
}: {
  submitLabel?: string;
}) {
  const username = useAuthStore((state) => state.user?.username);
  const passwordChanged = useAuthStore((state) => state.passwordChanged);

  const [current, setCurrent] = useState('');
  const [chosen, setChosen] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [changed, setChanged] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setChanged(false);
    const problem = passwordProblem(current, chosen, confirmation, username);
    if (problem) {
      setError(problem);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await changePassword(current, chosen);
      setChanged(true);
      passwordChanged();
    } catch (caught) {
      setError(apiErrorMessage(caught, 'The password could not be changed.'));
    } finally {
      // As on the sign-in form: a password stays in state no longer than the
      // attempt that used it.
      setCurrent('');
      setChosen('');
      setConfirmation('');
      setSubmitting(false);
    }
  };

  return (
    <form className="tw:flex tw:flex-col tw:gap-4" noValidate onSubmit={onSubmit}>
      <PasswordInput
        aria-label="Current password"
        autoComplete="current-password"
        isDisabled={submitting}
        isRequired
        label="Current password"
        name="currentPassword"
        onChange={setCurrent}
        value={current}
      />
      <PasswordInput
        aria-label="New password"
        autoComplete="new-password"
        hint={`At least ${MIN_PASSWORD} characters. A sentence you will remember beats a short string of symbols.`}
        isDisabled={submitting}
        isRequired
        label="New password"
        name="newPassword"
        onChange={setChosen}
        value={chosen}
      />
      <PasswordInput
        aria-label="Confirm new password"
        autoComplete="new-password"
        isDisabled={submitting}
        isRequired
        label="Confirm new password"
        name="confirmPassword"
        onChange={setConfirmation}
        value={confirmation}
      />

      {error && (
        <div
          className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:border tw:border-error_subtle tw:bg-error-primary tw:p-3"
          role="alert">
          <AlertCircle aria-hidden className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-error-primary" />
          <p className="tw:text-sm tw:text-error-primary">{error}</p>
        </div>
      )}

      {changed && (
        <div
          className="tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:border tw:border-success_subtle tw:bg-success-primary tw:p-3"
          role="status">
          <CheckCircle aria-hidden className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-success-primary" />
          <p className="tw:text-sm tw:text-success-primary">
            Your password is changed. Use the new one the next time you sign in.
          </p>
        </div>
      )}

      <div>
        <Button isLoading={submitting} showTextWhileLoading type="submit">
          {submitLabel}
        </Button>
      </div>
    </form>
  );
}
