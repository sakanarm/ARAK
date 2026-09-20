import type { FacetRow } from '../../api/client';
import { listFacets } from './facets';

function row(facetType: string, facetFqn: string): FacetRow {
  return {
    facetType,
    facetFqn,
    depth: 0,
    direct: true,
    inheritedFrom: null,
    provenance: 'openmetadata',
    omState: 'Confirmed',
    omLabelType: 'Manual',
    property: null,
  };
}

test('a list row keeps the deepest facet and drops the chain above it', () => {
  const shown = listFacets([
    row('domains', 'Premium'),
    row('domains', 'Premium.IOS-Data'),
    row('domains', 'Premium.IOS-Data.DTP'),
    row('tags', 'PII'),
    row('tags', 'PII.Sensitive'),
    // A second, unrelated branch is not an ancestor of the first and stays.
    row('tags', 'MFEC-PDPA.Non PII'),
  ]);

  expect(shown.map((facet) => facet.facetFqn)).toEqual([
    'Premium.IOS-Data.DTP',
    'PII.Sensitive',
    'MFEC-PDPA.Non PII',
  ]);
});

test('a list row drops what the row already says another way', () => {
  // `classifications` repeats the head of the tag FQN, and tier has its own
  // badge in the row header.
  const shown = listFacets([
    row('tags', 'Tier.Tier2'),
    row('classifications', 'Tier'),
    row('tier', 'Tier2'),
  ]);

  expect(shown.map((facet) => facet.facetFqn)).toEqual(['Tier.Tier2']);
});
