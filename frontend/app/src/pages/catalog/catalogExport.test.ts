import type { AssetDetail, AssetSummary, FacetRow } from '../../api/client';
import {
  ASSET_COLUMNS,
  assetGrid,
  collectAssets,
  collectDetails,
  columnGrid,
} from './catalogExport';

const fetchAssets = jest.fn();
const fetchAsset = jest.fn();

jest.mock('../../api/client', () => ({
  ...jest.requireActual('../../api/client'),
  fetchAssets: (...args: unknown[]) => fetchAssets(...args),
  fetchAsset: (...args: unknown[]) => fetchAsset(...args),
}));

function facet(facetType: string, facetFqn: string, extra: Partial<FacetRow> = {}): FacetRow {
  return {
    facetType,
    facetFqn,
    property: null,
    depth: 0,
    direct: true,
    inheritedFrom: null,
    provenance: 'openmetadata',
    omState: null,
    omLabelType: null,
    ...extra,
  };
}

function asset(fqn: string, extra: Partial<AssetSummary> = {}): AssetSummary {
  return {
    id: fqn,
    fqn,
    name: fqn.split('.').pop() ?? fqn,
    displayName: null,
    assetType: 'TABLE',
    parentFqn: null,
    description: '<p>Purchase <b>orders</b></p>',
    tier: 'Tier.Tier1',
    certification: null,
    dataSource: null,
    columnCount: 2,
    taggedColumnCount: 1,
    facets: [],
    owners: [],
    childCount: 0,
    provenance: 'openmetadata',
    querySource: 'demo-pg',
    ...extra,
  };
}

const cell = (row: unknown[], name: string) => row[ASSET_COLUMNS.indexOf(name)];

describe('the catalog export', () => {
  beforeEach(() => {
    fetchAssets.mockReset();
    fetchAsset.mockReset();
  });

  it('writes the most specific tag only, and splits the FQN into its levels', () => {
    const grid = assetGrid([
      asset('demo-pg.salesdb.procurement.po', {
        facets: [
          facet('tags', 'PII'),
          facet('tags', 'PII.Sensitive'),
          facet('domains', 'Finance'),
          facet('domains', 'Finance.Procurement'),
          facet('customProperty', 'TH', { property: 'dataResidency' }),
        ],
        owners: [{ type: 'team', name: 'Procurement', direct: true, inheritedFrom: null }],
      }),
    ]);
    const [row] = grid.rows;
    expect(cell(row, 'Service')).toBe('demo-pg');
    expect(cell(row, 'Database')).toBe('salesdb');
    expect(cell(row, 'Schema')).toBe('procurement');
    expect(cell(row, 'Tags')).toBe('PII.Sensitive');
    expect(cell(row, 'Domains')).toBe('Finance.Procurement');
    expect(cell(row, 'Owners')).toBe('Procurement');
    expect(cell(row, 'Custom properties')).toBe('dataResidency=TH');
    expect(cell(row, 'Description')).toBe('Purchase orders');
    expect(cell(row, 'Catalogued by')).toBe('OpenMetadata');
  });

  it('pages through the whole list under the same filters', async () => {
    fetchAssets
      .mockResolvedValueOnce({
        items: Array.from({ length: 500 }, (_, i) => asset(`s.d.x.t${i}`)),
        total: 501,
        limit: 500,
        offset: 0,
      })
      .mockResolvedValueOnce({ items: [asset('s.d.x.last')], total: 501, limit: 500, offset: 500 });

    const { assets, total } = await collectAssets({ search: 'po', reach: 'queryable' });

    expect(total).toBe(501);
    expect(assets).toHaveLength(501);
    expect(fetchAssets).toHaveBeenNthCalledWith(2, {
      search: 'po',
      reach: 'queryable',
      limit: 500,
      offset: 500,
    });
  });

  it('reads columns of tables only, and leaves out a table that fails', async () => {
    const detail = (fqn: string): AssetDetail => ({
      asset: asset(fqn),
      openMetadataUrl: null,
      customProperties: {},
      facets: [],
      owners: [],
      columns: [
        {
          id: 'c2', fqn: `${fqn}.amount`, name: 'amount', ordinal: 2, dataType: 'NUMERIC',
          dataLength: null, nullable: true, description: null, facets: [],
        },
        {
          id: 'c1', fqn: `${fqn}.po_no`, name: 'po_no', ordinal: 1, dataType: 'VARCHAR',
          dataLength: 20, nullable: false, description: null,
          facets: [facet('tags', 'PII'), facet('domains', 'Finance', { inheritedFrom: fqn })],
        },
      ],
    });
    fetchAsset.mockImplementation(async (fqn: string) => {
      if (fqn.endsWith('broken')) throw new Error('gone');
      return detail(fqn);
    });

    const details = await collectDetails([
      asset('s.d.x.po'),
      asset('s.d.x', { assetType: 'SCHEMA' }),
      asset('s.d.x.broken'),
    ]);
    expect(fetchAsset).toHaveBeenCalledTimes(2);
    expect(details).toHaveLength(1);

    const grid = columnGrid(details);
    expect(grid.rows.map((row) => row[1])).toEqual(['po_no', 'amount']);
    expect(grid.rows[0][5]).toBe('No');
    expect(grid.rows[0][8]).toBe('PII');
  });
});
