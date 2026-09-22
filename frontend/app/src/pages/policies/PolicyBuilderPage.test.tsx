import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import PolicyBuilderPage from './PolicyBuilderPage';

const createPolicy = jest.fn();
const fetchPolicy = jest.fn();
const resolveBindings = jest.fn();
const transitionPolicy = jest.fn();
const updatePolicy = jest.fn();

jest.mock('../../api/policies', () => ({
  ENFORCED_ENVIRONMENT: 'prod',
  createPolicy: (...args: unknown[]) => createPolicy(...args),
  fetchPolicy: (...args: unknown[]) => fetchPolicy(...args),
  resolveBindings: (...args: unknown[]) => resolveBindings(...args),
  transitionPolicy: (...args: unknown[]) => transitionPolicy(...args),
  updatePolicy: (...args: unknown[]) => updatePolicy(...args),
}));

jest.mock('../../api/governance', () => ({
  fetchPrincipals: () => Promise.resolve([]),
  fetchVocabulary: () =>
    Promise.resolve({ tags: [], terms: [], domains: [], dataProducts: [] }),
}));

jest.mock('../../api/client', () => ({
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}));

function renderNew() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/policies/new']}>
        <Routes>
          <Route element={<PolicyBuilderPage />} path="/policies/new" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  createPolicy.mockResolvedValue({ id: 'new-id' });
});

/*
 * The environment field is the one control on this form whose wrong value
 * produces no error and no empty screen -- the policy saves, activates, lists
 * and simply never runs. So the default is asserted rather than assumed.
 *
 * The assertion is on the control because the control renders `draft`, and
 * `draft` is the object posted verbatim on save: there is no second copy of
 * this value that could disagree with what is shown.
 */
test('a new policy is authored into the environment the engine enforces', async () => {
  renderNew();

  const field = await screen.findByRole('button', { name: /environment/i });

  expect(field).toHaveTextContent('prod');
});

test('choosing an environment the engine does not read says so', async () => {
  renderNew();

  // Nothing is said while the policy sits where it will be enforced: a note on
  // every policy is a note nobody reads by the time it matters.
  expect(screen.queryByText(/never enforced/i)).not.toBeInTheDocument();

  fireEvent.click(await screen.findByRole('button', { name: /environment/i }));
  fireEvent.click(await screen.findByRole('option', { name: /dev/i }));

  expect(await screen.findByText(/never enforced/i)).toBeInTheDocument();
});
