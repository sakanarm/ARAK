import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

/*
 * A request's review can suggest a policy for a whole group. It arrives here
 * as navigation state and fills the form -- nothing more. Saving it is the
 * author's press of "Create draft", and it lands as a draft like any other.
 */
test('a policy suggested by an access request fills the form and is saved as a new draft', async () => {
  const fqn = 'demo-pg.salesdb.sales.customer';
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter
        initialEntries={[
          {
            pathname: '/policies/new',
            state: {
              draft: {
                // Neither may survive: the form would pass for an existing, active policy.
                id: 'stale-id',
                lifecycleState: 'ACTIVE',
                name: 'analysts-reads-customer',
                displayName: 'analysts reads customer',
                policyType: 'SUBSCRIPTION',
                scopeLevel: 'TABLE',
                scopeFqn: fqn,
                selector: { condition: { facet: 'table', operator: 'eq', value: fqn } },
                subject: { principals: [{ group: 'analysts' }] },
                effect: 'ALLOW',
                environment: 'prod',
              },
              from: { requestId: 'req-1', assetFqn: fqn },
            },
          },
        ]}>
        <Routes>
          <Route element={<PolicyBuilderPage />} path="/policies/new" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );

  expect(
    await screen.findByText(new RegExp(`Drafted from an access request for ${fqn}`))
  ).toBeInTheDocument();
  expect(screen.getByDisplayValue('analysts-reads-customer')).toBeInTheDocument();
  // Opening it saved nothing.
  expect(createPolicy).not.toHaveBeenCalled();
  expect(transitionPolicy).not.toHaveBeenCalled();

  fireEvent.click(screen.getByRole('button', { name: 'Create draft' }));
  await waitFor(() => expect(createPolicy).toHaveBeenCalledTimes(1));
  const saved = createPolicy.mock.calls[0][0];
  expect(saved).toMatchObject({
    name: 'analysts-reads-customer',
    scopeFqn: fqn,
    subject: { principals: [{ group: 'analysts' }] },
  });
  expect(saved).not.toHaveProperty('id');
  expect(saved).not.toHaveProperty('lifecycleState');
  expect(transitionPolicy).not.toHaveBeenCalled();
});

test('a plain new policy carries no note about a request', async () => {
  renderNew();

  await screen.findByRole('button', { name: /environment/i });
  expect(screen.queryByText(/Drafted from an access request/)).not.toBeInTheDocument();
});

test('the diagram is a third view, and a node in it opens the step that writes it', async () => {
  // jsdom lays nothing out, so it has no scrolling to offer.
  const scroll = jest.fn();
  Element.prototype.scrollIntoView = scroll;
  renderNew();
  await screen.findByRole('button', { name: /environment/i });

  fireEvent.click(screen.getByRole('button', { name: 'Diagram' }));
  const figure = await screen.findByRole('figure');
  expect(screen.queryByRole('button', { name: /environment/i })).not.toBeInTheDocument();

  fireEvent.click(within(figure).getByRole('button', { name: /Is the asset one of these/ }));

  // Back on the form, where the step lives.
  expect(await screen.findByRole('button', { name: /environment/i })).toBeInTheDocument();
  expect(screen.queryByRole('figure')).not.toBeInTheDocument();
  await waitFor(() => expect(scroll).toHaveBeenCalled());
  localStorage.clear();
});
