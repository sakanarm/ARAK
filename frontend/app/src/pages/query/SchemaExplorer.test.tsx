import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import SchemaExplorer, { columnHint } from './SchemaExplorer';
import type { AssetDetail, AssetSummary, ColumnDetail, FacetRow } from '../../api/client';

const fetchAssets = jest.fn();
const fetchAsset = jest.fn();

jest.mock('../../api/client', () => ({
  ...jest.requireActual('../../api/client'),
  fetchAssets: (...args: unknown[]) => fetchAssets(...args),
  fetchAsset: (...args: unknown[]) => fetchAsset(...args),
}));

const TABLE = 'demo-pg.salesdb.sales.customer';

function facet(facetType: string, facetFqn: string): FacetRow {
  return {
    facetType,
    facetFqn,
    property: null,
    depth: facetFqn.split('.').length - 1,
    direct: true,
    inheritedFrom: null,
    provenance: 'openmetadata',
    omState: 'Confirmed',
    omLabelType: 'Manual',
  };
}

function column(overrides: Partial<ColumnDetail> = {}): ColumnDetail {
  return {
    id: 'c1',
    fqn: `${TABLE}.email`,
    name: 'email',
    ordinal: 1,
    dataType: 'VARCHAR',
    dataLength: 255,
    nullable: true,
    description: '<p>Where the customer is written to</p>',
    descriptionSource: 'openmetadata',
    facets: [],
    ...overrides,
  };
}

function renderExplorer(onInsert = jest.fn()) {
  fetchAssets.mockResolvedValue({
    items: [{ fqn: TABLE, name: 'customer', facets: [], owners: [] } as unknown as AssetSummary],
    total: 1,
    limit: 500,
    offset: 0,
  });
  fetchAsset.mockResolvedValue({
    asset: { fqn: TABLE },
    columns: [column(), column({ id: 'c2', fqn: `${TABLE}.id`, name: 'id', description: null })],
  } as unknown as AssetDetail);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter basename="/Arak" initialEntries={['/Arak/query']}>
        <SchemaExplorer onInsert={onInsert} sourceId="src-1" />
      </MemoryRouter>
    </QueryClientProvider>
  );
  return onInsert;
}

/** Down to the table, which is three levels in and closed by default. */
async function openTable() {
  fireEvent.click(await screen.findByRole('button', { name: 'Expand sales' }));
  return screen.getByRole('button', { name: /^customer/ });
}

describe('SchemaExplorer', () => {
  beforeEach(() => {
    fetchAssets.mockReset();
    fetchAsset.mockReset();
  });

  it('offers a table’s catalog page on right-click, in a new tab', async () => {
    renderExplorer();
    const table = await openTable();

    fireEvent.contextMenu(table, { clientX: 40, clientY: 80 });

    const menu = screen.getByRole('menu', { name: 'Table sales.customer' });
    const link = within(menu).getByRole('menuitem', { name: 'Open in the Data Catalog' });
    // Under the base path the app is served from, or the new tab is a 404.
    expect(link).toHaveAttribute('href', `/Arak/catalog/${TABLE}`);
    expect(link).toHaveAttribute('target', '_blank');
    // The first item has the focus, so the keyboard can use the menu at once.
    expect(link).toHaveFocus();

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('opens a column’s table at its columns, and inserts the column', async () => {
    const onInsert = renderExplorer();
    fireEvent.click(await screen.findByRole('button', { name: 'Expand sales' }));
    fireEvent.click(screen.getByRole('button', { name: 'Expand customer' }));

    const email = await screen.findByRole('button', { name: /^email/ });
    fireEvent.contextMenu(email, { clientX: 40, clientY: 120 });

    const menu = screen.getByRole('menu', { name: 'Column email' });
    expect(
      within(menu).getByRole('menuitem', { name: 'Open its table in the Data Catalog' })
    ).toHaveAttribute('href', `/Arak/catalog/${TABLE}?tab=columns`);

    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Insert into the editor' }));
    expect(onInsert).toHaveBeenCalledWith('email');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('copies the full name, and closes on a click anywhere else', async () => {
    const writeText = jest.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    renderExplorer();
    const table = await openTable();

    fireEvent.contextMenu(table, { clientX: 40, clientY: 80 });
    fireEvent.click(screen.getByRole('menuitem', { name: 'Copy the full name' }));
    expect(writeText).toHaveBeenCalledWith(TABLE);

    fireEvent.contextMenu(table, { clientX: 40, clientY: 80 });
    expect(screen.getByRole('menu')).toBeInTheDocument();
    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('moves between the items with the arrow keys', async () => {
    renderExplorer();
    fireEvent.contextMenu(await openTable(), { clientX: 40, clientY: 80 });
    const items = screen.getAllByRole('menuitem');

    fireEvent.keyDown(screen.getByRole('menu'), { key: 'ArrowDown' });
    expect(items[1]).toHaveFocus();
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'ArrowUp' });
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'ArrowUp' });
    expect(items[2]).toHaveFocus();
  });

  it('still inserts a column on click', async () => {
    const onInsert = renderExplorer();
    fireEvent.click(await screen.findByRole('button', { name: 'Expand sales' }));
    fireEvent.click(screen.getByRole('button', { name: 'Expand customer' }));

    fireEvent.click(await screen.findByRole('button', { name: /^id/ }));
    expect(onInsert).toHaveBeenCalledWith('id');
  });

  it('shows what a column means when it has the focus', async () => {
    renderExplorer();
    fireEvent.click(await screen.findByRole('button', { name: 'Expand sales' }));
    fireEvent.click(screen.getByRole('button', { name: 'Expand customer' }));
    const email = await screen.findByRole('button', { name: /^email/ });

    // A tooltip opens at once for the keyboard; the pointer waits a moment.
    fireEvent.keyDown(document.body, { key: 'Tab' });
    act(() => email.focus());

    const tooltip = await screen.findByRole('tooltip');
    expect(tooltip).toHaveTextContent('email · VARCHAR');
    expect(tooltip).toHaveTextContent('Where the customer is written to');
  });
});

describe('columnHint', () => {
  it('reads the description as text', () => {
    expect(columnHint(column()).description).toBe('Where the customer is written to');
  });

  it('says when there is no description, and where to add one', () => {
    expect(columnHint(column({ description: null })).description).toMatch(
      /^No description yet\. .*Columns tab/
    );
  });

  it('cuts a long description short', () => {
    const hint = columnHint(column({ description: 'x'.repeat(1_000) }));
    expect(hint.description).toHaveLength(400);
    expect(hint.description.endsWith('…')).toBe(true);
  });

  it('names only the most specific tag and term', () => {
    const facets = [
      facet('tags', 'PII'),
      facet('tags', 'PII.Sensitive'),
      facet('terms', 'Finance'),
      facet('terms', 'Finance.CustomerIdentity'),
      facet('domains', 'Finance'),
    ];
    expect(columnHint(column({ facets })).labels).toEqual([
      'Finance.CustomerIdentity',
      'PII.Sensitive',
    ]);
  });
});
