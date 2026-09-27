import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import ChangePasswordForm, { passwordProblem } from './ChangePasswordForm';
import { useAuthStore } from './authStore';
import { changePassword } from '../api/client';

jest.mock('../api/client', () => ({
  changePassword: jest.fn(),
  apiErrorMessage: (error: unknown, fallback: string) =>
    error instanceof Error ? error.message : fallback,
}));

const change = changePassword as jest.MockedFunction<typeof changePassword>;

function type(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

function fill(current: string, chosen: string, confirmation = chosen) {
  type('Current password', current);
  type('New password', chosen);
  type('Confirm new password', confirmation);
  fireEvent.click(screen.getByRole('button', { name: 'Change password' }));
}

beforeEach(() => {
  change.mockReset();
  useAuthStore.setState({
    token: 'test-token',
    initialising: false,
    mustChangePassword: true,
    user: {
      id: '11111111-1111-1111-1111-111111111111',
      username: 'analyst_a',
      email: null,
      displayName: 'Analyst A',
      source: 'local',
      roles: [],
      scopes: [],
    },
  });
});

test('says what is wrong with a new password before sending it', () => {
  expect(passwordProblem('', 'long enough password', 'long enough password')).toMatch(
    /current password/
  );
  expect(passwordProblem('old', 'too short', 'too short')).toMatch(/at least 12/);
  expect(passwordProblem('old', 'x'.repeat(201), 'x'.repeat(201))).toMatch(/at most 200/);
  expect(
    passwordProblem('old', 'Analyst_Long_Name', 'Analyst_Long_Name', 'analyst_long_name')
  ).toMatch(/username/);
  expect(
    passwordProblem('the same old password', 'the same old password', 'the same old password')
  ).toMatch(/different/);
  expect(passwordProblem('old', 'long enough password', 'long enough passwrod')).toMatch(
    /do not match/
  );
  expect(passwordProblem('old', 'long enough password', 'long enough password')).toBeNull();
});

test('does not call the backend when the two new passwords differ', async () => {
  render(<ChangePasswordForm />);

  fill('handed-over-by-admin', 'a much longer one of my own', 'a much longer one of mine');

  expect(await screen.findByRole('alert')).toHaveTextContent('do not match');
  expect(change).not.toHaveBeenCalled();
  expect(useAuthStore.getState().mustChangePassword).toBe(true);
});

test('changes the password, says so, and lifts the must-change stop', async () => {
  change.mockResolvedValue(undefined);
  render(<ChangePasswordForm />);

  fill('handed-over-by-admin', 'a much longer one of my own');

  expect(await screen.findByRole('status')).toHaveTextContent('Your password is changed');
  expect(change).toHaveBeenCalledWith('handed-over-by-admin', 'a much longer one of my own');
  expect(useAuthStore.getState().mustChangePassword).toBe(false);
  // Nothing typed stays behind in the fields.
  expect(screen.getByLabelText('Current password')).toHaveValue('');
  expect(screen.getByLabelText('New password')).toHaveValue('');
});

test("shows the backend's reason, keeps the stop, and does not keep the passwords", async () => {
  change.mockRejectedValue(new Error('the current password is not correct'));
  render(<ChangePasswordForm />);

  fill('a wrong guess', 'a much longer one of my own');

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'the current password is not correct'
  );
  expect(useAuthStore.getState().mustChangePassword).toBe(true);
  await waitFor(() => expect(screen.getByLabelText('Current password')).toHaveValue(''));
});
