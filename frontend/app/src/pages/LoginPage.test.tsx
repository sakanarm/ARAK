import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import LoginPage from './LoginPage';
import { useAuthStore } from '../auth/authStore';

const mockConfig = {
  localLoginEnabled: true,
  entraEnabled: false,
  entraButtonLabel: null as string | null,
};

jest.mock('../api/client', () => ({
  fetchAuthConfig: jest.fn(() => Promise.resolve(mockConfig)),
  entraStartUrl: (to: string) => `/api/v1/auth/entra/start?to=${encodeURIComponent(to)}`,
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}));

function renderLogin(path = '/login') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <LoginPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function type(field: HTMLElement, value: string) {
  fireEvent.change(field, { target: { value } });
}

beforeEach(() => {
  mockConfig.localLoginEnabled = true;
  mockConfig.entraEnabled = false;
  mockConfig.entraButtonLabel = null;
  useAuthStore.setState({ token: null, user: null, initialising: false });
});

test('signs in with the username and password that were typed', async () => {
  const signIn = jest.fn().mockResolvedValue(undefined);
  useAuthStore.setState({ signIn });

  renderLogin();

  type(await screen.findByLabelText('Email or username'), 'admin');
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

  type(await screen.findByLabelText('Email or username'), 'admin');
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

  type(await screen.findByLabelText('Email or username'), '   ');
  type(screen.getByLabelText('Password'), 'something');
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Enter your email or username, and your password.'
  );
  expect(signIn).not.toHaveBeenCalled();
});

test('offers Microsoft first and keeps email sign-in one click away', async () => {
  mockConfig.entraEnabled = true;
  mockConfig.entraButtonLabel = 'Sign in with Example';
  const signIn = jest.fn().mockResolvedValue(undefined);
  useAuthStore.setState({ signIn });

  renderLogin();

  const microsoft = await screen.findByRole('link', { name: 'Sign in with Example' });
  expect(microsoft).toHaveAttribute('href', '/api/v1/auth/entra/start?to=%2F');
  expect(screen.queryByLabelText('Email or username')).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Sign in with email' }));
  type(screen.getByLabelText('Email or username'), 'someone@example.com');
  type(screen.getByLabelText('Password'), 'correct horse battery');
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

  await waitFor(() =>
    expect(signIn).toHaveBeenCalledWith('someone@example.com', 'correct horse battery')
  );
});

test('says an administrator has to add somebody Microsoft knows but ARAK does not', async () => {
  mockConfig.entraEnabled = true;

  renderLogin('/login?sso_error=not_registered');

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Ask an ARAK administrator to add your account.'
  );
});

test('trades the one-time code for a session exactly once', async () => {
  mockConfig.entraEnabled = true;
  const signInWithSso = jest.fn().mockResolvedValue(undefined);
  useAuthStore.setState({ signInWithSso });

  renderLogin('/login?sso=one-time-code');

  await waitFor(() => expect(signInWithSso).toHaveBeenCalledWith('one-time-code'));
  expect(signInWithSso).toHaveBeenCalledTimes(1);
});
