import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import AssetDetailPage from './AssetDetailPage';
import type { FacetRow } from '../../api/client';

const fetchAsset = jest.fn();

jest.mock('../../api/client', () => ({
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
  fetchAsset: (...args: unknown[]) => fetchAsset(...args),
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

const DETAIL = {
  asset: {
    id: '11111111-1111-1111-1111-111111111111',
    fqn: 'prod-pg.SalesDB.dbo.customer',
    name: 'customer',
    displayName: null,
    assetType: 'TABLE',
    parentFqn: 'prod-pg.SalesDB.dbo',
    description: 'Customer master',
    tier: 'Tier.Tier1',
    certification: null,
    dataSource: 'prod-pg',
    columnCount: 2,
    taggedColumnCount: 1,
    facets: [],
    owners: [],
  },
  customProperties: { dataResidency: 'TH' },
  columns: [
    {
      id: 'c1',
      fqn: 'prod-pg.SalesDB.dbo.customer.id',
      name: 'id',
      ordinal: 1,
      dataType: 'BIGINT',
      dataLength: null,
      nullable: false,
      description: null,
      facets: [],
    },
    {
      id: 'c2',
      fqn: 'prod-pg.SalesDB.dbo.customer.email',
      name: 'email',
      ordinal: 2,
      dataType: 'VARCHAR',
      dataLength: 255,
      nullable: true,
      description: null,
      facets: [facet({})],
    },
  ],
  facets: [
    facet({
      facetType: 'domains',
      facetFqn: 'Finance',
      direct: false,
      depth: 2,
      inheritedFrom: 'Finance.Risk.Credit',
    }),
  ],
  owners: [{ type: 'team', name: 'Finance', direct: true, inheritedFrom: null }],
};

function renderPage(fqn = 'prod-pg.SalesDB.dbo.customer') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/catalog/${fqn}`]}>
        <Routes>
          <Route element={<AssetDetailPage />} path="/catalog/*" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  fetchAsset.mockReset();
  fetchAsset.mockResolvedValue(DETAIL);
});

test('asks for the whole dotted FQN, not the first path segment', async () => {
  renderPage();

  await waitFor(() =>
    expect(fetchAsset).toHaveBeenCalledWith('prod-pg.SalesDB.dbo.customer')
  );
});

test('shows each column with the facets that reach it', async () => {
  renderPage();

  expect(await screen.findByText('email')).toBeInTheDocument();
  expect(screen.getByText('id')).toBeInTheDocument();
  expect(screen.getByText('PII.Sensitive')).toBeInTheDocument();
  expect(screen.getByText('2 columns · 1 carrying a facet')).toBeInTheDocument();
});

test('says where an inherited facet came from', async () => {
  renderPage();

  // By title rather than by text: "Finance" is both the domain and the owning
  // team here, which is exactly the collision the chip's tooltip exists to
  // resolve. The title carries the answer to "why is this asset in Finance?" —
  // the sub-domain three levels down that the crawl expanded into ancestors.
  const domain = await screen.findByTitle(/^Domains/);
  expect(domain).toHaveAttribute(
    'title',
    expect.stringContaining('inherited from Finance.Risk.Credit')
  );
});

test('custom properties are shown as the ABAC inputs they are', async () => {
  renderPage();

  expect(await screen.findByText('dataResidency')).toBeInTheDocument();
  expect(screen.getByText('TH')).toBeInTheDocument();
});

test('an asset missing from the cache says so without blaming OpenMetadata', async () => {
  fetchAsset.mockRejectedValue(new Error('404'));
  renderPage();

  expect(
    await screen.findByText('That asset could not be read.')
  ).toBeInTheDocument();
  expect(screen.getByText(/not the same as not being in/)).toBeInTheDocument();
});
