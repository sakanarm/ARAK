import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
    childCount: 0,
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
  // Once as the row's chip, which keeps the parent so that `PII.Sensitive`
  // cannot be mistaken for `MFEC-PDPA.Sentitive`...
  expect(screen.getAllByText('PII / Sensitive')).toHaveLength(1);
  // ...and once in the filter rail, which drops the parent because the rail
  // puts it back as an indent under the `PII` row above.
  expect(screen.getByRole('checkbox', { name: /^PII\.Sensitive/ })).toBeInTheDocument();
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

  const tag = await screen.findByRole('checkbox', { name: /^PII\.Sensitive/ });
  expect(screen.queryByRole('checkbox', { name: /dbo/ })).not.toBeInTheDocument();

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

test('pages by number, and keeps the page size the reader picked', async () => {
  // Eighty assets at twenty-five to a page: four pages, which is few enough
  // that every number is drawn and none of them is an ellipsis.
  fetchAssets.mockResolvedValue({ items: [asset({})], total: 80, limit: 25, offset: 0 });
  renderPage();

  await screen.findByText('customer');
  expect(screen.getByRole('button', { current: 'page' })).toHaveTextContent('1');

  fireEvent.click(screen.getByRole('button', { name: '3' }));

  await waitFor(() =>
    expect(fetchAssets).toHaveBeenLastCalledWith(expect.objectContaining({ offset: 50 }))
  );
});

test('asks for as many assets per page as the URL says', async () => {
  fetchAssets.mockResolvedValue({ items: [asset({})], total: 80, limit: 50, offset: 0 });
  renderPage('/catalog?size=50');

  await waitFor(() =>
    expect(fetchAssets).toHaveBeenLastCalledWith(expect.objectContaining({ limit: 50 }))
  );
});

describe('hierarchy', () => {
  const page = (items: AssetSummary[]) => ({ items, total: items.length, limit: 500, offset: 0 });
  const service = asset({ id: 's', fqn: 'prod-pg', name: 'prod-pg', assetType: 'SERVICE', parentFqn: null, columnCount: 0, childCount: 2 });
  const sales = asset({ id: 'd1', fqn: 'prod-pg.SalesDB', name: 'SalesDB', assetType: 'DATABASE', parentFqn: 'prod-pg', columnCount: 0, childCount: 1 });
  const hr = asset({ id: 'd2', fqn: 'prod-pg.HrDB', name: 'HrDB', assetType: 'DATABASE', parentFqn: 'prod-pg', columnCount: 0, childCount: 0 });
  const dbo = asset({ id: 'sc', fqn: 'prod-pg.SalesDB.dbo', name: 'dbo', assetType: 'SCHEMA', parentFqn: 'prod-pg.SalesDB', columnCount: 0, childCount: 1 });

  beforeEach(() => {
    fetchAssets.mockImplementation((query: { assetType?: string; parent?: string }) => {
      if (query.assetType === 'SERVICE') return Promise.resolve(page([service]));
      if (query.parent === 'prod-pg') return Promise.resolve(page([sales, hr]));
      if (query.parent === 'prod-pg.SalesDB') return Promise.resolve(page([dbo]));
      if (query.parent === 'prod-pg.SalesDB.dbo') return Promise.resolve(page([asset({})]));
      return Promise.resolve(page([asset({})]));
    });
  });

  test('walks service, database, schema and table one level at a time', async () => {
    renderPage('/catalog?view=tree');

    const tree = await screen.findByRole('tree', { name: 'Catalog hierarchy' });
    // The only service opens by itself; its two databases wait to be asked.
    expect(await within(tree).findByRole('treeitem', { name: 'SalesDB' })).toHaveAttribute('aria-expanded', 'false');
    expect(within(tree).getByRole('treeitem', { name: 'prod-pg' })).toHaveAttribute('aria-expanded', 'true');
    expect(within(tree).getByText('2 databases')).toBeInTheDocument();
    // An empty database has nothing to open.
    expect(within(tree).getByRole('treeitem', { name: 'HrDB' })).not.toHaveAttribute('aria-expanded');
    expect(fetchAssets).not.toHaveBeenCalledWith(expect.objectContaining({ parent: 'prod-pg.SalesDB' }));

    fireEvent.click(within(tree).getByRole('button', { name: 'Expand SalesDB' }));

    // The lone schema opens in turn and shows its table, which links to its page.
    const table = await within(tree).findByRole('link', { name: 'customer' });
    expect(table).toHaveAttribute('href', `/catalog/${encodeURIComponent('prod-pg.SalesDB.dbo.customer')}`);
    expect(fetchAssets).toHaveBeenCalledWith({ parent: 'prod-pg.SalesDB', limit: 500 });
    expect(within(tree).getByRole('treeitem', { name: 'customer' })).toHaveAttribute('aria-level', '4');
    // The filtered list is not asked for while the tree is shown.
    expect(fetchAssets).not.toHaveBeenCalledWith(expect.objectContaining({ offset: 0 }));
  });

  test('switches between the list and the tree, and a search goes back to the list', async () => {
    renderPage();
    await screen.findByText('customer');

    fireEvent.click(screen.getByRole('button', { name: 'Hierarchy' }));
    expect(await screen.findByRole('tree', { name: 'Catalog hierarchy' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hierarchy' })).toHaveAttribute('aria-pressed', 'true');

    fireEvent.change(screen.getByLabelText('Search the catalog'), { target: { value: 'cust' } });
    fireEvent.submit(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(screen.queryByRole('tree')).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'List' })).toHaveAttribute('aria-pressed', 'true');
    await waitFor(() =>
      expect(fetchAssets).toHaveBeenLastCalledWith(expect.objectContaining({ search: 'cust' }))
    );
  });
});
