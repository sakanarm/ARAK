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
  // The pure helpers (flatten and the like) are the real ones; only the calls are stubbed.
  ...jest.requireActual('../../api/governance'),
  fetchPrincipals: () => Promise.resolve([]),
  fetchVocabulary: () =>
    Promise.resolve({
      classifications: [],
      glossaries: [],
      domains: [],
      dataProducts: [],
      customProperties: [],
    }),
}));

const assistPolicy = jest.fn();
const fetchMyLlmSetting = jest.fn();
const fetchOfferedFeatures = jest.fn();

jest.mock('../../api/llm', () => ({
  assistPolicy: (...args: unknown[]) => assistPolicy(...args),
  fetchMyLlmSetting: () => fetchMyLlmSetting(),
  fetchOfferedFeatures: () => fetchOfferedFeatures(),
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
  // No assistant unless a test says so, so the form reads as it always has.
  fetchMyLlmSetting.mockRejectedValue(new Error('403'));
  fetchOfferedFeatures.mockResolvedValue([]);
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

test('editing a policy says which version it is, when it was last edited and by whom', async () => {
  const id = '33333333-3333-3333-3333-333333333333';
  fetchPolicy.mockResolvedValue({
    id,
    document: {
      name: 'mask-pii',
      policyType: 'DATA',
      scopeLevel: 'ORG',
      selector: { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
    },
    lifecycleState: 'ACTIVE',
    environment: 'prod',
    version: 3,
    createdBy: 'author@example.com',
    updatedBy: 'editor@example.com',
    updatedAt: '2026-09-20T09:00:00Z',
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/policies/${id}/edit`]}>
        <Routes>
          <Route element={<PolicyBuilderPage />} path="/policies/:id/edit" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );

  expect(await screen.findByText(/^edited .+ by/)).toBeInTheDocument();
  expect(screen.getByText('editor@example.com')).toBeInTheDocument();
  // The versions before this one are a click away.
  expect(screen.getByRole('link', { name: 'History' })).toHaveAttribute(
    'href',
    `/policies/${id}?tab=history`
  );
  expect(fetchPolicy).toHaveBeenCalledWith(id);
});

test('a plain new policy carries no note about a request', async () => {
  renderNew();

  await screen.findByRole('button', { name: /environment/i });
  expect(screen.queryByText(/Drafted from an access request/)).not.toBeInTheDocument();
});

test('the diagram is a third view, and a node in it opens its step over the chart', async () => {
  renderNew();
  await screen.findByRole('button', { name: /environment/i });

  fireEvent.click(screen.getByRole('button', { name: 'Diagram' }));
  const figure = await screen.findByRole('figure');
  expect(screen.queryByRole('button', { name: /environment/i })).not.toBeInTheDocument();

  fireEvent.click(within(figure).getByRole('button', { name: /Is the asset one of these/ }));

  // The step opens in a dialog; the chart stays where it was behind it.
  const dialog = await screen.findByRole('dialog', { name: 'Which assets it covers' });
  expect(within(dialog).getByText('Step 3 of 4')).toBeInTheDocument();
  // Still on the page, only hidden from assistive tech while the modal is up.
  expect(screen.getByRole('figure', { hidden: true })).toBeInTheDocument();

  // Previous walks back through the form's steps without leaving the chart.
  fireEvent.click(within(dialog).getByRole('button', { name: 'Previous' }));
  const second = await screen.findByRole('dialog', { name: 'Where it sits' });
  expect(within(second).getByRole('button', { name: 'Next' })).toBeEnabled();

  fireEvent.click(within(second).getByRole('button', { name: 'Done' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(screen.getByRole('figure')).toBeInTheDocument();
  localStorage.clear();
});

test('an edit made in the dialog is the draft, and saves with it', async () => {
  renderNew();
  await screen.findByRole('button', { name: /environment/i });
  fireEvent.click(screen.getByRole('button', { name: 'Diagram' }));
  const figure = await screen.findByRole('figure');

  // Step 1 has no box of its own, so reach it by walking back from step 3.
  fireEvent.click(within(figure).getByRole('button', { name: /Is the asset one of these/ }));
  fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Previous' }));
  fireEvent.click(within(await screen.findByRole('dialog', { name: 'Where it sits' })).getByRole('button', { name: 'Previous' }));
  const first = await screen.findByRole('dialog', { name: 'What this policy is' });
  expect(within(first).getByRole('button', { name: 'Previous' })).toBeDisabled();
  fireEvent.change(within(first).getByPlaceholderText('mask-pii-outside-clearance'), {
    target: { value: 'from-the-dialog' },
  });
  fireEvent.click(within(first).getByRole('button', { name: 'Close' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

  fireEvent.click(screen.getByRole('button', { name: 'Form' }));
  expect(await screen.findByDisplayValue('from-the-dialog')).toBeInTheDocument();
  localStorage.clear();
});

/*
 * NokRak on the form (the same /v1/llm/assist/policy the dock calls). It fills
 * the form and nothing more: nothing is saved until "Create draft", and a
 * lifecycle state in the answer is not carried into the form or the save.
 */
describe('NokRak, help me', () => {
  function withAssistant(features = ['DRAFT_POLICY']) {
    fetchMyLlmSetting.mockResolvedValue({ available: true });
    fetchOfferedFeatures.mockResolvedValue(features);
  }

  async function openPrompt() {
    fireEvent.click(await screen.findByRole('button', { name: /NokRak, help me/ }));
    return screen.getByRole('region', { name: 'Tell NokRak the rule' });
  }

  test('drafts the rule into the form and saves nothing until asked', async () => {
    withAssistant();
    assistPolicy.mockResolvedValue({
      model: 'test-model',
      personal: false,
      document: JSON.stringify({
        id: 'made-up-id',
        lifecycleState: 'ACTIVE',
        name: 'finance-reads-pii',
        policyType: 'SUBSCRIPTION',
        scopeLevel: 'TABLE',
        scopeFqn: 'demo-pg.salesdb.sales.customer',
        selector: {
          condition: { facet: 'table', operator: 'eq', value: 'demo-pg.salesdb.sales.customer' },
        },
        subject: { principals: [{ group: 'finance' }] },
        effect: 'ALLOW',
      }),
    });
    renderNew();

    const prompt = await openPrompt();
    fireEvent.change(within(prompt).getByRole('textbox'), {
      target: { value: 'Let finance read PII tables' },
    });
    fireEvent.click(within(prompt).getByRole('button', { name: 'Draft it' }));

    await waitFor(() =>
      expect(assistPolicy).toHaveBeenCalledWith({ intent: 'Let finance read PII tables' })
    );
    expect(await screen.findByDisplayValue('finance-reads-pii')).toBeInTheDocument();
    expect(screen.getByText(/Loaded a draft from NokRak/)).toBeInTheDocument();
    // The prompt closes once the form holds the draft.
    expect(screen.queryByRole('region', { name: 'Tell NokRak the rule' })).not.toBeInTheDocument();
    expect(createPolicy).not.toHaveBeenCalled();
    expect(transitionPolicy).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Create draft' }));
    await waitFor(() => expect(createPolicy).toHaveBeenCalledTimes(1));
    const saved = createPolicy.mock.calls[0][0];
    expect(saved).toMatchObject({ name: 'finance-reads-pii' });
    expect(saved).not.toHaveProperty('id');
    expect(saved).not.toHaveProperty('lifecycleState');
    expect(transitionPolicy).not.toHaveBeenCalled();
  });

  test('an answer that is not a policy loads nothing and says so', async () => {
    withAssistant();
    assistPolicy.mockResolvedValue({ model: 'm', personal: false, document: 'Sorry, I cannot.' });
    renderNew();

    const prompt = await openPrompt();
    const box = within(prompt).getByRole('textbox');
    fireEvent.change(box, { target: { value: 'something' } });
    fireEvent.keyDown(box, { key: 'Enter' });

    expect(await screen.findByText(/was not a policy document/)).toBeInTheDocument();
    // Left open, so the author can say it another way.
    expect(screen.getByRole('region', { name: 'Tell NokRak the rule' })).toBeInTheDocument();
  });

  test('a refused draft is said in the prompt', async () => {
    withAssistant();
    assistPolicy.mockRejectedValue(new Error('503'));
    renderNew();

    const prompt = await openPrompt();
    fireEvent.change(within(prompt).getByRole('textbox'), { target: { value: 'x' } });
    fireEvent.click(within(prompt).getByRole('button', { name: 'Draft it' }));

    expect(await within(prompt).findByRole('alert')).toHaveTextContent(
      'NokRak could not draft a policy.'
    );
  });

  test('is not offered to a role without the drafting job', async () => {
    withAssistant(['WRITE_SQL']);
    renderNew();
    await waitFor(() => expect(fetchOfferedFeatures).toHaveBeenCalled());
    await screen.findByRole('button', { name: 'Create draft' });
    expect(screen.queryByRole('button', { name: /NokRak/ })).not.toBeInTheDocument();
  });
});
