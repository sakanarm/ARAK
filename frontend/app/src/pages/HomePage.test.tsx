import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import HomePage from './HomePage';
import { useAuthStore } from '../auth/authStore';
import {
  fetchHomeLayout,
  saveHomeLayout,
  type HomeLayout,
  type HomeLayoutView,
} from '../api/home';
import { search } from '../api/search';
import { fetchPolicies } from '../api/policies';
import { fetchSources } from '../api/sources';
import { fetchVocabulary } from '../api/governance';

/**
 * What the landing page shows, to whom.
 *
 * <p>The point being defended here is the one that is easiest to lose in a
 * refactor: a requester's page holds none of the governance panels, and so it
 * must not ask the server for policies or sources either. A page that filters
 * governance out of its markup but still runs the queries behind it looks right
 * and is wrong -- it spends a request per load on data nobody put on the page,
 * and it does it on the account least likely to be allowed the answer.
 *
 * <p>The filtering itself is a product decision, not a boundary: every endpoint
 * behind those panels answers any signed-in account on purpose, because someone
 * who is refused data has to be able to see which policy refused them. These
 * tests assert what is offered, and claim nothing about what is permitted.
 */

jest.mock('../api/home', () => ({
  ...jest.requireActual('../api/home'),
  fetchHomeLayout: jest.fn(),
  saveHomeLayout: jest.fn(),
  resetHomeLayout: jest.fn(),
}));

jest.mock('../api/search', () => ({
  ...jest.requireActual('../api/search'),
  search: jest.fn(),
}));

jest.mock('../api/policies', () => ({ fetchPolicies: jest.fn() }));
jest.mock('../api/sources', () => ({ fetchSources: jest.fn() }));
jest.mock('../api/governance', () => ({ fetchVocabulary: jest.fn() }));

jest.mock('../api/client', () => ({
  apiClient: { get: jest.fn(), put: jest.fn(), delete: jest.fn() },
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
  fetchCatalogSummary: jest.fn(),
  fetchSystemVersion: jest.fn(),
}));

const layoutCall = fetchHomeLayout as jest.MockedFunction<typeof fetchHomeLayout>;
const saveCall = saveHomeLayout as jest.MockedFunction<typeof saveHomeLayout>;
const searchCall = search as jest.MockedFunction<typeof search>;
const policiesCall = fetchPolicies as jest.MockedFunction<typeof fetchPolicies>;
const sourcesCall = fetchSources as jest.MockedFunction<typeof fetchSources>;
const vocabularyCall = fetchVocabulary as jest.MockedFunction<
  typeof fetchVocabulary
>;

function signedInAs(roles: string[]) {
  useAuthStore.setState({
    token: 'test-token',
    initialising: false,
    user: {
      id: '11111111-1111-1111-1111-111111111111',
      username: 'analyst_a',
      email: 'analyst_a@example.test',
      displayName: 'Analyst A',
      source: 'local',
      roles,
      scopes: [],
    },
  });
}

function stored(layout: HomeLayout): HomeLayoutView {
  return { layout, isDefault: true, updatedAt: null, updatedBy: null };
}

/** The layout a requester gets when they have never saved one. */
const REQUESTER_DEFAULT: HomeLayout = {
  preset: 'SINGLE',
  widgets: [
    { id: 'search-1', type: 'SEARCH', column: 0, title: null, config: {} },
    { id: 'vocab-1', type: 'VOCABULARY', column: 0, title: null, config: {} },
  ],
};

const GOVERNANCE_DEFAULT: HomeLayout = {
  preset: 'WIDE_LEFT',
  widgets: [
    {
      id: 'recent-1',
      type: 'RECENT_POLICIES',
      column: 0,
      title: null,
      config: { limit: 6 },
    },
    { id: 'sources-1', type: 'SOURCES', column: 1, title: null, config: {} },
  ],
};

function show() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <HomePage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  policiesCall.mockResolvedValue([]);
  sourcesCall.mockResolvedValue([]);
  searchCall.mockResolvedValue({ query: '', limit: 24, items: [] });
  vocabularyCall.mockResolvedValue({
    classifications: [],
    glossaries: [],
    domains: [],
    dataProducts: [],
    customProperties: [],
  });
});

describe('the requester page', () => {
  beforeEach(() => {
    signedInAs(['REQUESTER']);
    layoutCall.mockResolvedValue(stored(REQUESTER_DEFAULT));
  });

  it('opens on search and shows no governance panel', async () => {
    show();

    expect(await screen.findByLabelText('Search the catalog')).toBeInTheDocument();
    expect(screen.queryByText('Recent policies')).not.toBeInTheDocument();
    expect(screen.queryByText('Sources')).not.toBeInTheDocument();
  });

  it('asks the server for nothing the page does not show', async () => {
    show();
    await screen.findByLabelText('Search the catalog');

    expect(policiesCall).not.toHaveBeenCalled();
    expect(sourcesCall).not.toHaveBeenCalled();
  });

  it('offers the catalog rather than a policy it could not write', async () => {
    show();
    await screen.findByLabelText('Search the catalog');

    expect(
      screen.queryByRole('button', { name: /New policy/ })
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /Explore the catalog/ })
    ).toBeInTheDocument();
  });

  it('searches once there is enough to search for', async () => {
    searchCall.mockResolvedValue({
      query: 'cust',
      limit: 24,
      items: [
        {
          kind: 'asset',
          facetType: null,
          subtype: 'Regular',
          id: 'a1',
          fqn: 'demo-pg.salesdb.sales.customer',
          name: 'customer',
          displayName: null,
          description: null,
          parentFqn: 'demo-pg.salesdb.sales',
          assets: -1,
        },
      ],
    });
    show();

    fireEvent.change(await screen.findByLabelText('Search the catalog'), {
      target: { value: 'cust' },
    });

    expect(await screen.findByText('customer')).toBeInTheDocument();
    expect(searchCall).toHaveBeenCalledWith('cust', 24);
  });

  it('does not offer a governance panel in the editor', async () => {
    show();
    await screen.findByLabelText('Search the catalog');

    fireEvent.click(screen.getByRole('button', { name: /Edit this page/ }));
    expect(await screen.findByText('Editing your home page')).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole('button', { name: 'Add a panel' })[0]);

    expect(await screen.findByText('Note')).toBeInTheDocument();
    expect(screen.queryByText('Governance coverage')).not.toBeInTheDocument();
    expect(screen.queryByText('Recent policies')).not.toBeInTheDocument();
  });
});

describe('the governance page', () => {
  beforeEach(() => {
    signedInAs(['POLICY_AUTHOR']);
    layoutCall.mockResolvedValue(stored(GOVERNANCE_DEFAULT));
  });

  it('keeps the panels it has always had', async () => {
    show();

    expect(await screen.findByText('Recent policies')).toBeInTheDocument();
    expect(screen.getByText('Sources')).toBeInTheDocument();
    await waitFor(() => expect(policiesCall).toHaveBeenCalledWith({ limit: 6 }));
  });

  it('leads with writing a policy', async () => {
    show();
    await screen.findByText('Recent policies');

    expect(screen.getByRole('button', { name: /New policy/ })).toBeInTheDocument();
  });
});

describe('editing', () => {
  beforeEach(() => {
    signedInAs(['REQUESTER']);
    layoutCall.mockResolvedValue(stored(REQUESTER_DEFAULT));
  });

  it('rearranges the page under the editor and saves what is on screen', async () => {
    saveCall.mockImplementation((layout) => Promise.resolve(stored(layout)));
    show();
    await screen.findByLabelText('Search the catalog');

    fireEvent.click(screen.getByRole('button', { name: /Edit this page/ }));
    await screen.findByText('Editing your home page');

    // Two columns, so the second panel can be sent to the right of the first.
    fireEvent.click(screen.getByRole('button', { name: /Two equal/ }));
    fireEvent.change(screen.getAllByLabelText('Column')[1], {
      target: { value: '1' },
    });

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(saveCall).toHaveBeenCalledTimes(1));
    const posted = saveCall.mock.calls[0][0];
    expect(posted.preset).toBe('HALVES');
    expect(
      posted.widgets.find((widget) => widget.type === 'VOCABULARY')?.column
    ).toBe(1);
    // The editor closes on a saved layout rather than waiting to be dismissed.
    await waitFor(() =>
      expect(screen.queryByText('Editing your home page')).not.toBeInTheDocument()
    );
  });

  it('adds a panel to the page behind the editor as soon as it is chosen', async () => {
    show();
    await screen.findByLabelText('Search the catalog');

    fireEvent.click(screen.getByRole('button', { name: /Edit this page/ }));
    await screen.findByText('Editing your home page');
    fireEvent.click(screen.getAllByRole('button', { name: 'Add a panel' })[0]);
    fireEvent.click(await screen.findByText('Note'));

    expect(
      await screen.findByText('This note is empty. Edit this page to write in it.')
    ).toBeInTheDocument();
  });
});
