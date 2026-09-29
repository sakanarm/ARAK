import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import PolicyBuilderPage from './PolicyBuilderPage';
import { useAssistStore } from '../../assist/assistStore';

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

const fetchSources = jest.fn();
const fetchEngines = jest.fn();

jest.mock('../../api/sources', () => ({
  ...jest.requireActual('../../api/sources'),
  fetchSources: () => fetchSources(),
  fetchEngines: () => fetchEngines(),
}));

const engines = [
  {
    id: 'POSTGRES',
    displayName: 'PostgreSQL',
    defaultPort: 5432,
    supportsSchemas: true,
    proxyCapabilities: ['ROW_FILTER', 'COLUMN_MASK'],
  },
  // An engine the query API has nothing for.
  { id: 'MYSQL', displayName: 'MySQL', defaultPort: 3306, supportsSchemas: false, proxyCapabilities: [] },
];

function source(overrides: Record<string, unknown>) {
  return {
    id: 'src-pg',
    name: 'demo-pg',
    engine: 'POSTGRES',
    engineVersion: '16',
    host: 'db.example.test',
    port: 5432,
    defaultDatabase: null,
    credentialRef: 'env:DEMO_PG',
    defaultEnforcementMode: 'PROXY',
    omServiceFqn: null,
    secureSchema: 'sec',
    secureObjectPattern: '{table}',
    tableScope: { mode: 'ALL', include: [], exclude: [] },
    enabled: true,
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z',
    assetCount: 3,
    ...overrides,
  };
}

const sources = [
  source({}),
  source({
    id: 'src-my',
    name: 'demo-my',
    engine: 'MYSQL',
    defaultEnforcementMode: 'SECURE_VIEW',
    omServiceFqn: 'catalog-my',
    assetCount: 1,
  }),
];

/** A new policy past the page that asks where it runs: every connection, the query API. */
function renderNew(entry = '/policies/new?source=any&mode=PROXY') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[entry]}>
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
  fetchSources.mockResolvedValue(sources);
  fetchEngines.mockResolvedValue(engines);
});

/*
 * A new policy is asked where it runs before the form opens: the kind, the
 * connection and the mode it will be enforced by. The connection narrows the
 * selector; the mode is what the builder checks against, and is not saved --
 * the source's own mode is what enforces it.
 */
describe('where a new policy runs', () => {
  const configure = () => screen.getByRole('button', { name: /Configure the policy/ });

  test('is asked before the form, which waits for a connection and a mode', async () => {
    renderNew('/policies/new');

    expect(await screen.findByRole('region', { name: 'Which connection' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'How it will be enforced' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /environment/i })).not.toBeInTheDocument();
    expect(configure()).toBeDisabled();

    fireEvent.click(await screen.findByRole('button', { name: 'demo-pg' }));
    expect(configure()).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Query API' }));

    expect(configure()).toBeEnabled();
    expect(screen.getByTestId('target-summary')).toHaveTextContent(
      'Subscription policy on demo-pg, enforced by Query API'
    );
  });

  test('covers the chosen connection and saves no mode', async () => {
    renderNew('/policies/new');

    fireEvent.click(await screen.findByRole('button', { name: 'demo-pg' }));
    fireEvent.click(screen.getByRole('button', { name: 'Query API' }));
    fireEvent.click(configure());

    await screen.findByRole('button', { name: /environment/i });
    const target = await screen.findByTestId('policy-target');
    await waitFor(() => expect(target).toHaveTextContent('demo-pg · PostgreSQL'));
    expect(target).toHaveTextContent('Query API');

    fireEvent.change(screen.getByPlaceholderText('mask-pii-outside-clearance'), {
      target: { value: 'pg-readers' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Create draft' }));
    await waitFor(() => expect(createPolicy).toHaveBeenCalledTimes(1));
    const saved = createPolicy.mock.calls[0][0];
    expect(saved.selector).toEqual({
      condition: { facet: 'service', operator: 'eq', value: 'demo-pg' },
    });
    expect(JSON.stringify(saved)).not.toMatch(/PROXY/);
  });

  test('a connection linked to the catalog is selected by its service', async () => {
    renderNew('/policies/new');

    fireEvent.click(await screen.findByRole('button', { name: 'demo-my' }));
    fireEvent.click(screen.getByRole('button', { name: 'Secure view' }));
    fireEvent.click(configure());

    fireEvent.change(await screen.findByPlaceholderText('mask-pii-outside-clearance'), {
      target: { value: 'my-readers' },
    });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Create draft' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Create draft' }));
    await waitFor(() => expect(createPolicy).toHaveBeenCalledTimes(1));
    expect(createPolicy.mock.calls[0][0].selector).toEqual({
      condition: { facet: 'service', operator: 'eq', value: 'catalog-my' },
    });
  });

  test('Change goes back to the question, keeping the answer', async () => {
    renderNew('/policies/new?kind=SUBSCRIPTION&source=src-pg&mode=PROXY');

    const target = await screen.findByTestId('policy-target');
    fireEvent.click(within(target).getByRole('button', { name: 'Change' }));

    expect(await screen.findByRole('button', { name: 'demo-pg' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
    expect(screen.getByRole('button', { name: 'Query API' })).toHaveAttribute(
      'aria-pressed',
      'false'
    );
  });

  test('says when the mode is not the one the connection is set to', async () => {
    renderNew('/policies/new');

    fireEvent.click(await screen.findByRole('button', { name: 'demo-pg' }));
    fireEvent.click(screen.getByRole('button', { name: 'Secure view' }));

    expect(
      screen.getByText(/demo-pg is enforced by query proxy today, and stays that way/)
    ).toBeInTheDocument();
    // Written for another mode is still written: the way on stays open.
    expect(configure()).toBeEnabled();
  });

  test('native config is checked but not applied yet', async () => {
    renderNew('/policies/new');

    fireEvent.click(await screen.findByRole('button', { name: 'Every connection' }));
    fireEvent.click(screen.getByRole('button', { name: 'Native source config' }));

    expect(screen.getByText('Checked, not applied yet')).toBeInTheDocument();
    expect(screen.getByText(/does not push native config to a source yet/)).toBeInTheDocument();
  });

  test('the query API is not offered on an engine it has nothing for', async () => {
    renderNew('/policies/new');

    fireEvent.click(await screen.findByRole('button', { name: 'demo-my' }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'Query API' })).toBeDisabled());
    expect(screen.getByText('Not on MySQL')).toBeInTheDocument();
  });

  test('a policy drafted in the chat opens the form, not the question', async () => {
    // "Load into the builder" hands the document over and then navigates here.
    useAssistStore.getState().deliverPolicy(
      JSON.stringify({
        name: 'drafted-in-chat',
        policyType: 'SUBSCRIPTION',
        scopeLevel: 'ORG',
        selector: { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
      })
    );
    renderNew('/policies/new');

    expect(await screen.findByDisplayValue('drafted-in-chat')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Which connection' })).not.toBeInTheDocument();
    expect(useAssistStore.getState().policy).toBeNull();
  });

  test('a data policy starts on the organisation', async () => {
    renderNew('/policies/new');

    fireEvent.click(await screen.findByRole('button', { name: 'Data policy' }));
    fireEvent.click(screen.getByRole('button', { name: 'Every connection' }));
    fireEvent.click(screen.getByRole('button', { name: 'Secure view' }));
    fireEvent.click(configure());

    expect(await screen.findByTestId('policy-target')).toHaveTextContent('Every connection');
    fireEvent.change(screen.getByPlaceholderText('mask-pii-outside-clearance'), {
      target: { value: 'org-masks' },
    });
    expect(screen.getByRole('button', { name: /level/i })).toHaveTextContent('Organisation');
  });
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

/*
 * A subscription may sit on the whole organisation or on one table. What the
 * organisation layer gates is what step 3 selects, so it is no wider than the
 * selector; the layers in between stay hidden.
 */
test('a subscription is offered the organisation and table levels only', async () => {
  renderNew();

  fireEvent.click(await screen.findByRole('button', { name: /level/i }));

  expect(await screen.findByRole('option', { name: 'Organisation' })).toBeInTheDocument();
  expect(screen.getByRole('option', { name: 'Table' })).toBeInTheDocument();
  expect(screen.queryByRole('option', { name: 'Schema' })).not.toBeInTheDocument();
  expect(
    screen.queryByRole('option', { name: 'Domain or sub-domain' })
  ).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('option', { name: 'Organisation' }));

  expect(await screen.findByText(/Every asset step 3 selects/)).toBeInTheDocument();
});

/*
 * "Is one of" used to be saved with its list typed into one value. The server
 * now refuses that shape, so a policy opened and saved unchanged has to leave
 * with the list where the engine reads it -- or it could never be saved again.
 */
test('saving a policy stored with an old-style list writes the items as values', async () => {
  const id = '55555555-5555-5555-5555-555555555555';
  const stored = {
    id,
    document: {
      name: 'finance-reads-customer',
      policyType: 'SUBSCRIPTION',
      scopeLevel: 'TABLE',
      scopeFqn: 'demo-pg.salesdb.sales.customer',
      selector: {
        condition: { facet: 'table', operator: 'eq', value: 'demo-pg.salesdb.sales.customer' },
      },
      subject: {
        anyOf: [{ team: 'Finance' }],
        attributes: [{ key: 'department', operator: 'in', value: 'FINANCE, RISK' }],
      },
      effect: 'ALLOW',
    },
    lifecycleState: 'DRAFT',
    environment: 'prod',
    version: 2,
  };
  fetchPolicy.mockResolvedValue(stored);
  updatePolicy.mockResolvedValue({ ...stored, version: 3 });
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

  // Shown as the two items it meant, before anybody touches it.
  expect(await screen.findByRole('button', { name: 'Remove RISK' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Save' }));

  await waitFor(() => expect(updatePolicy).toHaveBeenCalled());
  const [sentId, document, version] = updatePolicy.mock.calls[0];
  expect(sentId).toBe(id);
  expect(version).toBe(2);
  expect(JSON.parse(JSON.stringify(document.subject.attributes))).toEqual([
    { key: 'department', operator: 'in', values: ['FINANCE', 'RISK'] },
  ]);
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

/*
 * NokRak on a stored policy. It is sent the form as it stands and answers with
 * it changed; the page shows what changed, with a way back, and the policy is
 * saved only when somebody presses Save -- the same review as a new draft.
 */
describe('NokRak, help me, on a policy that exists', () => {
  const id = '44444444-4444-4444-4444-444444444444';
  const stored = (lifecycleState = 'ACTIVE') => ({
    id,
    document: {
      name: 'finance-reads-customer',
      description: 'Finance reads the customer table',
      policyType: 'SUBSCRIPTION',
      scopeLevel: 'TABLE',
      scopeFqn: 'demo-pg.salesdb.sales.customer',
      selector: {
        condition: { facet: 'table', operator: 'eq', value: 'demo-pg.salesdb.sales.customer' },
      },
      subject: { anyOf: [{ team: 'Finance' }] },
      effect: 'ALLOW',
    },
    lifecycleState,
    environment: 'prod',
    version: 3,
    createdBy: 'author@example.com',
    updatedBy: 'editor@example.com',
    updatedAt: '2026-09-20T09:00:00Z',
  });

  function withAssistant() {
    fetchMyLlmSetting.mockResolvedValue({ available: true });
    fetchOfferedFeatures.mockResolvedValue(['DRAFT_POLICY']);
  }

  function renderEdit() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/policies/${id}/edit`]}>
          <Routes>
            <Route element={<PolicyBuilderPage />} path="/policies/:id/edit" />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    );
  }

  async function askFor(change: string) {
    await screen.findByDisplayValue('Finance reads the customer table');
    fireEvent.click(await screen.findByRole('button', { name: /NokRak, help me/ }));
    const prompt = screen.getByRole('region', { name: 'Tell NokRak what to change' });
    fireEvent.change(within(prompt).getByRole('textbox'), { target: { value: change } });
    fireEvent.click(within(prompt).getByRole('button', { name: 'Suggest it' }));
    return prompt;
  }

  const answer = (document: Record<string, unknown>) => ({
    model: 'test-model',
    personal: false,
    document: JSON.stringify(document),
  });

  test('sends the form as it stands, shows what changed, and saves only on Save', async () => {
    withAssistant();
    fetchPolicy.mockResolvedValue(stored());
    updatePolicy.mockImplementation((_id: string, document: unknown) =>
      Promise.resolve({ ...stored(), document, version: 4 })
    );
    assistPolicy.mockResolvedValue(
      answer({
        ...stored().document,
        effect: 'DENY',
        description: 'Finance may not read the customer table',
        // Neither is the model's to write, and neither reaches the save.
        lifecycleState: 'DRAFT',
        version: 99,
      })
    );
    renderEdit();

    const prompt = await askFor('make it a deny');
    // Said before anybody asks: on an active policy, Save is the moment it applies.
    expect(prompt).toHaveTextContent('this policy is active, so Save puts the change in force');

    await waitFor(() => expect(assistPolicy).toHaveBeenCalledTimes(1));
    const ask = assistPolicy.mock.calls[0][0];
    expect(ask.intent).toBe('make it a deny');
    expect(JSON.parse(ask.current)).toMatchObject({
      name: 'finance-reads-customer',
      effect: 'ALLOW',
    });

    const review = await screen.findByRole('region', { name: "NokRak's suggestion" });
    expect(within(review).getByText('NokRak suggests 2 changes')).toBeInTheDocument();
    expect(within(review).getByText('Effect')).toBeInTheDocument();
    expect(within(review).getByText('DENY')).toBeInTheDocument();
    expect(within(review).getByText('Description')).toBeInTheDocument();
    expect(within(review).getByText(/This policy is active/)).toBeInTheDocument();
    expect(screen.getByDisplayValue('Finance may not read the customer table')).toBeInTheDocument();
    // The prompt closes once the form holds the change; nothing is stored yet.
    expect(
      screen.queryByRole('region', { name: 'Tell NokRak what to change' })
    ).not.toBeInTheDocument();
    expect(updatePolicy).not.toHaveBeenCalled();
    expect(transitionPolicy).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(updatePolicy).toHaveBeenCalledTimes(1));
    const [savedId, saved, expected] = updatePolicy.mock.calls[0];
    expect(savedId).toBe(id);
    expect(saved).toMatchObject({ effect: 'DENY', name: 'finance-reads-customer' });
    expect(saved).not.toHaveProperty('lifecycleState');
    expect(saved).not.toHaveProperty('version');
    // Saved against the version the form was opened on, as any edit is.
    expect(expected).toBe(3);
    expect(transitionPolicy).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: "NokRak's suggestion" })).not.toBeInTheDocument()
    );
  });

  test('Undo puts the form back as it was before the suggestion', async () => {
    withAssistant();
    fetchPolicy.mockResolvedValue(stored('DRAFT'));
    assistPolicy
      .mockResolvedValueOnce(answer({ ...stored().document, description: 'First try' }))
      .mockResolvedValueOnce(answer({ ...stored().document, description: 'Second try' }));
    renderEdit();

    await askFor('reword it');
    expect(await screen.findByDisplayValue('First try')).toBeInTheDocument();
    // Asked again: the second answer is compared with the policy, not the first try.
    fireEvent.click(screen.getByRole('button', { name: /NokRak, help me/ }));
    const again = screen.getByRole('region', { name: 'Tell NokRak what to change' });
    fireEvent.change(within(again).getByRole('textbox'), { target: { value: 'try again' } });
    fireEvent.click(within(again).getByRole('button', { name: 'Suggest it' }));
    expect(await screen.findByDisplayValue('Second try')).toBeInTheDocument();

    const review = screen.getByRole('region', { name: "NokRak's suggestion" });
    expect(within(review).getByText('Finance reads the customer table')).toBeInTheDocument();
    // Not active, so no warning that Save applies it.
    expect(within(review).queryByText(/This policy is active/)).not.toBeInTheDocument();

    fireEvent.click(within(review).getByRole('button', { name: 'Undo the suggestion' }));
    expect(await screen.findByDisplayValue('Finance reads the customer table')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: "NokRak's suggestion" })).not.toBeInTheDocument();
    expect(updatePolicy).not.toHaveBeenCalled();
  });

  test('an answer that changes nothing says so', async () => {
    withAssistant();
    fetchPolicy.mockResolvedValue(stored());
    assistPolicy.mockResolvedValue(answer(stored().document));
    renderEdit();

    await askFor('keep it as it is');

    const review = await screen.findByRole('region', { name: "NokRak's suggestion" });
    expect(within(review).getByText('NokRak’s answer changes nothing')).toBeInTheDocument();
    expect(
      within(review).queryByRole('button', { name: 'Undo the suggestion' })
    ).not.toBeInTheDocument();
    fireEvent.click(within(review).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('region', { name: "NokRak's suggestion" })).not.toBeInTheDocument();
  });

  test('is not offered on an archived policy', async () => {
    withAssistant();
    fetchPolicy.mockResolvedValue(stored('ARCHIVED'));
    renderEdit();

    await screen.findByDisplayValue('Finance reads the customer table');
    await waitFor(() => expect(fetchOfferedFeatures).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: /NokRak/ })).not.toBeInTheDocument();
  });
});
