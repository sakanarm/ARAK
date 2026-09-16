import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CatalogPage from './CatalogPage';
import type { AssetSummary, FacetRow } from '../../api/client';

const fetchAssets = jest.fn();
const fetchFacetValues = jest.fn();

jest.mock('../../api/client', () => ({
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
  fetchAssets: (...args: unknown[]) => fetchAssets(...args),
  fetchFacetValues: (...args: unknown[]) => fetchFacetValues(...args),
  fetchCatalogSummary: () =>
    Promise.resolve({
      assetsByType: { TABLE: 2 },
      columns: 9,
      taggedAssets: 1,
      taggedColumns: 1,
      assetsWithoutOwner: 1,
      facetsByType: { tags: 2 },
    }),
}));

function facet(overrides: Partial<FacetRow>): FacetRow {
  return {
    facetType: 'tags',
    facetFqn: 'PII.Sensitive',
    property: null,
    depth: 0,
    direct: true,
    inheritedFrom: null,
    provenance: 'openmetadata',
    omState: 'Confirmed',
    omLabelType: 'Manual',
    ...overrides,
  };
}

function asset(overrides: Partial<AssetSummary>): AssetSummary {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    fqn: 'prod-pg.SalesDB.dbo.customer',
    name: 'customer',
    displayName: null,
    assetType: 'TABLE',
    parentFqn: 'prod-pg.SalesDB.dbo',
    description: null,
    tier: null,
    certification: null,
    dataSource: 'prod-pg',
    columnCount: 5,
    taggedColumnCount: 2,
    facets: [],
    owners: [],
    ...overrides,
  };
}

function renderPage(path = '/catalog') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <CatalogPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  fetchAssets.mockReset();
  fetchFacetValues.mockReset();
  fetchFacetValues.mockResolvedValue([
    { facetType: 'tags', facetFqn: 'PII.Sensitive', assets: 3 },
    { facetType: 'domains', facetFqn: 'Finance', assets: 4 },
    // Physical facets are not offered as filters even when the crawl records
    // them, so this one must not reach the picker.
    { facetType: 'schema', facetFqn: 'dbo', assets: 9 },
  ]);
  fetchAssets.mockResolvedValue({
    items: [
      asset({
        facets: [facet({}), facet({ facetType: 'domains', facetFqn: 'Finance', direct: false, inheritedFrom: 'prod-pg.SalesDB' })],
        owners: [{ type: 'team', name: 'Finance', direct: true, inheritedFrom: null }],
      }),
    ],
    total: 1,
    limit: 25,
    offset: 0,
  });
});

test('lists what the crawl cached, with its governance', async () => {
  renderPage();

  expect(await screen.findByText('customer')).toBeInTheDocument();
  expect(screen.getByText('prod-pg.SalesDB.dbo.customer')).toBeInTheDocument();
  // Twice over: once as the row's chip, once as a filter in the picker.
  expect(screen.getAllByText('PII.Sensitive')).toHaveLength(2);
  // The count that decides whether this table needs a data policy at all.
  expect(screen.getByText(/2 carrying a tag or term/)).toBeInTheDocument();
});

test('reads its filters from the URL so a filtered list can be shared', async () => {
  renderPage('/catalog?q=customer&type=TABLE&facet=tags:PII.Sensitive&facet=domains:Finance');

  await waitFor(() => expect(fetchAssets).toHaveBeenCalled());
  expect(fetchAssets).toHaveBeenCalledWith(
    expect.objectContaining({
      search: 'customer',
      assetType: 'TABLE',
      facets: ['tags:PII.Sensitive', 'domains:Finance'],
    })
  );
});

test('offers only the governance facets as filters, and AND-s them', async () => {
  renderPage();

  const tag = await screen.findByRole('button', { name: /PII.Sensitive/ });
  expect(screen.queryByRole('button', { name: /dbo/ })).not.toBeInTheDocument();

  fireEvent.click(tag);

  await waitFor(() =>
    expect(fetchAssets).toHaveBeenLastCalledWith(
      expect.objectContaining({ facets: ['tags:PII.Sensitive'] })
    )
  );
  expect(screen.getByText(/must carry all of them/)).toBeInTheDocument();
});

test('a search starts again at the first page', async () => {
  renderPage('/catalog?offset=50');

  await waitFor(() =>
    expect(fetchAssets).toHaveBeenLastCalledWith(expect.objectContaining({ offset: 50 }))
  );

  fireEvent.change(screen.getByLabelText('Search the catalog'), {
    target: { value: 'cust' },
  });
  fireEvent.submit(screen.getByRole('button', { name: 'Search' }));

  await waitFor(() =>
    expect(fetchAssets).toHaveBeenLastCalledWith(
      expect.objectContaining({ search: 'cust', offset: 0 })
    )
  );
});

test('says the cache is empty rather than showing a bare list', async () => {
  fetchAssets.mockResolvedValue({ items: [], total: 0, limit: 25, offset: 0 });
  renderPage();

  expect(await screen.findByText('The cache is empty')).toBeInTheDocument();
});
