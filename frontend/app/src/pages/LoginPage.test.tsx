import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import LoginPage from './LoginPage';
import { useAuthStore } from '../auth/authStore';

jest.mock('../api/client', () => ({
  fetchAuthConfig: jest.fn().mockResolvedValue({
    localLoginEnabled: true,
    entraEnabled: false,
    entraTenantId: null,
    entraClientId: null,
  }),
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}));

function renderLogin() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function type(field: HTMLElement, value: string) {
  fireEvent.change(field, { target: { value } });
}

beforeEach(() => {
  useAuthStore.setState({ token: null, user: null, initialising: false });
});

test('signs in with the username and password that were typed', async () => {
  const signIn = jest.fn().mockResolvedValue(undefined);
  useAuthStore.setState({ signIn });

  renderLogin();

  type(screen.getByLabelText('Username'), 'admin');
  type(screen.getByLabelText('Password'), 'correct horse battery');
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

  await waitFor(() =>
    expect(signIn).toHaveBeenCalledWith('admin', 'correct horse battery')
  );
});

test('shows the reason the backend refused, and does not keep the password', async () => {
  const signIn = jest.fn().mockRejectedValue(new Error('nope'));
  useAuthStore.setState({ signIn });

  renderLogin();

  type(screen.getByLabelText('Username'), 'admin');
  const password = screen.getByLabelText('Password');
  type(password, 'wrong');
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

  expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in failed.');
  await waitFor(() => expect(password).toHaveValue(''));
});

test('does not call the backend for a username that is only whitespace', async () => {
  // An empty field is stopped by the browser's own required-field validation, so
  // the guard in the page exists for what that check lets through.
  const signIn = jest.fn();
  useAuthStore.setState({ signIn });

  renderLogin();

  type(screen.getByLabelText('Username'), '   ');
  type(screen.getByLabelText('Password'), 'something');
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Enter your username and password.'
  );
  expect(signIn).not.toHaveBeenCalled();
});
