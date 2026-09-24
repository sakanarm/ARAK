import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import AssetDetailPage from './AssetDetailPage';
import type { FacetRow } from '../../api/client';
import type { AppliedPolicy } from '../../api/policies';

const fetchAsset = jest.fn();
const fetchPoliciesForAsset = jest.fn();

jest.mock('../../api/client', () => ({
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
  fetchAsset: (...args: unknown[]) => fetchAsset(...args),
}));

jest.mock('../../api/policies', () => ({
  fetchPoliciesForAsset: (...args: unknown[]) => fetchPoliciesForAsset(...args),
}));

function applied(
  overrides: Partial<AppliedPolicy['policy']['document']>,
  columns: AppliedPolicy['columns'] = []
): AppliedPolicy {
  return {
    policy: {
      id: `p-${overrides.name}`,
      document: {
        name: 'unnamed',
        policyType: 'SUBSCRIPTION',
        scopeLevel: 'ORG',
        selector: {
          condition: { facet: 'tags', operator: 'contains', value: 'PII' },
        },
        ...overrides,
      },
      lifecycleState: 'ACTIVE',
      environment: 'prod',
      version: 1,
      createdBy: 'author@example.com',
      updatedBy: 'author@example.com',
      updatedAt: '2026-09-20T09:00:00Z',
    },
    matchReason: { facet: 'tags', value: 'PII' },
    columns,
  };
}

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
      facets: [
        facet({
          facetType: 'domains',
          facetFqn: 'Finance',
          direct: false,
          depth: 2,
          inheritedFrom: 'prod-pg.SalesDB.dbo.customer',
        }),
      ],
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
      // What the crawl really stores for one tag: the tag itself, the
      // ancestor row that makes `tags contains 'PII'` an index lookup
      // (FR-2A.2), and the classification derived from the same label.
      // OpenMetadata shows one chip here, and so must this.
      facets: [
        facet({}),
        facet({ facetFqn: 'PII', depth: 1, direct: false }),
        facet({ facetType: 'classifications', facetFqn: 'PII', direct: false }),
      ],
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

/**
 * Renders the page with one tab open.
 *
 * <p>The tab is passed in the URL rather than clicked, because that is how
 * the page is really reached: the open tab is in the query string so a link
 * can point at one. A test that clicked its way there would be asserting
 * about the tab strip in every test that is about something else.
 */
function renderPage(
  fqn = 'prod-pg.SalesDB.dbo.customer',
  tab?: 'access' | 'policies' | 'columns' | 'audit'
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const query = tab ? `?tab=${tab}` : '';
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/catalog/${fqn}${query}`]}>
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
  fetchPoliciesForAsset.mockReset();
  fetchPoliciesForAsset.mockResolvedValue([]);
});

test('the two kinds of policy are answered separately', async () => {
  fetchPoliciesForAsset.mockResolvedValue([
    applied({
      name: 'finance-read',
      displayName: 'Finance may read',
      policyType: 'SUBSCRIPTION',
      effect: 'ALLOW',
    }),
    applied(
      {
        name: 'mask-pii',
        displayName: 'Mask PII columns',
        policyType: 'DATA',
        scopeLevel: 'SCHEMA',
      },
      [
        {
          fqn: 'prod-pg.SalesDB.dbo.customer.email',
          kind: 'COLUMN',
          name: 'email',
          parentFqn: 'prod-pg.SalesDB.dbo.customer',
          dataType: 'VARCHAR',
          matchReason: { action: 'MASK' },
          resolvedAt: null,
        },
      ]
    ),
  ]);
  renderPage(undefined, 'policies');

  expect(await screen.findByText('Finance may read')).toBeInTheDocument();
  expect(screen.getByText('Mask PII columns')).toBeInTheDocument();
  expect(screen.getByText('Allow')).toBeInTheDocument();
  // A data policy says where inside the table it lands, which is the part a
  // data owner came for. By the action rather than by the name, because the
  // chip is the thing that says what happens to the column.
  expect(screen.getByText('mask').parentElement).toHaveTextContent('email');
  expect(screen.getByText('SCHEMA')).toBeInTheDocument();
});

test('a table nothing selects is called out as denied, not left blank', async () => {
  renderPage(undefined, 'policies');

  expect(
    await screen.findByText(/No active policy reaches this asset/)
  ).toBeInTheDocument();
});

test('a subscription policy without a data policy says what that means', async () => {
  fetchPoliciesForAsset.mockResolvedValue([
    applied({ name: 'finance-read', displayName: 'Finance may read' }),
  ]);
  renderPage(undefined, 'policies');

  expect(await screen.findByText('Finance may read')).toBeInTheDocument();
  expect(
    screen.getByText(/rows and columns are returned whole/)
  ).toBeInTheDocument();
});

test('asks for the whole dotted FQN, not the first path segment', async () => {
  renderPage();

  await waitFor(() =>
    expect(fetchAsset).toHaveBeenCalledWith('prod-pg.SalesDB.dbo.customer')
  );
});

test('shows each column with the governance it carries itself', async () => {
  renderPage(undefined, 'columns');

  expect(await screen.findByText('email')).toBeInTheDocument();
  expect(screen.getByText('id')).toBeInTheDocument();
  // Chips print the leaf with its parent, not the whole dotted path.
  expect(screen.getByText('PII / Sensitive')).toBeInTheDocument();
  expect(screen.getByText('2 columns · 1 carrying governance of their own')).toBeInTheDocument();
});

test('a column does not repeat what it inherited from its table', async () => {
  renderPage(undefined, 'columns');

  // `id` inherits the table's domain and nothing else. The table already
  // draws that domain once; printing it again on every column row is how a
  // three-column table came to show more governance than OpenMetadata does,
  // and a three-hundred-column one became unreadable.
  const row = (await screen.findByText('id')).closest('tr');

  expect(row).not.toBeNull();
  expect(within(row as HTMLElement).queryByText('Finance')).toBeNull();
  expect(within(row as HTMLElement).getByText('—')).toBeInTheDocument();
});

test('reads a tagged column exactly as OpenMetadata does', async () => {
  renderPage(undefined, 'columns');

  // Three stored rows, one chip. The other two are ARAK's own index rows --
  // real, needed by the selector, and nothing a reader of this table asked
  // for. Printing them made a column look like it carried governance that
  // OpenMetadata never showed, which is the wrong kind of surprise on a
  // screen people use to decide what to lock down.
  const row = (await screen.findByText('email')).closest('tr');

  expect(row).not.toBeNull();
  expect(within(row as HTMLElement).getByText('PII / Sensitive')).toBeInTheDocument();
  expect(within(row as HTMLElement).queryAllByText('inherited')).toHaveLength(0);
});

test('a materialised ancestor is never called inherited', async () => {
  renderPage(undefined, 'columns');

  // `direct: false` alone does not mean the value came from somewhere else.
  // The tooltip has to say which of the two it is, because the difference is
  // the difference between "your steward tagged this" and "ARAK made it up".
  const chip = await screen.findByTitle(/^Tags/);

  expect(chip).toHaveAttribute('title', expect.stringContaining('applied here'));
  expect(chip.getAttribute('title')).not.toContain('inherited');
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

test('opens the tab the link asked for', async () => {
  renderPage(undefined, 'policies');

  const tab = await screen.findByRole('tab', { name: 'Policies' });
  expect(tab).toHaveAttribute('aria-selected', 'true');
  // And the one it did not ask for is not rendering underneath it.
  expect(screen.queryByText('Custom properties')).not.toBeInTheDocument();
});

test('moving between tabs changes what the page answers', async () => {
  renderPage();

  // Overview is the default, and it is what a link with no tab lands on.
  // By heading rather than by text: the columns table has a 'Governance'
  // header of its own, so a plain text match is true on both tabs and proves
  // nothing about which one is open.
  expect(
    await screen.findByRole('heading', { name: 'Governance' })
  ).toBeInTheDocument();

  fireEvent.click(screen.getByRole('tab', { name: /Columns/ }));
  expect(await screen.findByText('2 columns \u00b7 1 carrying governance of their own')).toBeInTheDocument();
  expect(
    screen.queryByRole('heading', { name: 'Governance' })
  ).not.toBeInTheDocument();
});
