import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import type { BadgeColors } from '@openmetadata/ui-core-components/components/base/badges/badge-types';
import type { AssetOwner, FacetRow } from '../../api/client';

/**
 * How governance facets are drawn wherever they appear.
 *
 * <p>One colour per facet type, used identically on the list and the asset page,
 * so a tag looks like a tag whether it came from OpenMetadata's classifications
 * or from a glossary. Guessing a colour per value would make the same tag change
 * colour between screens.
 */
const FACET_COLOURS: Record<string, BadgeColors> = {
  tags: 'error',
  classifications: 'error',
  terms: 'purple',
  glossaries: 'purple',
  domains: 'blue',
  dataProducts: 'indigo',
  tier: 'warning',
  certification: 'success',
  customProperty: 'gray-blue',
};

/** Labels for the filter menus and the facet groups on an asset. */
export const FACET_LABELS: Record<string, string> = {
  tags: 'Tags',
  classifications: 'Classifications',
  terms: 'Glossary terms',
  glossaries: 'Glossaries',
  domains: 'Domains',
  dataProducts: 'Data products',
  tier: 'Tier',
  certification: 'Certification',
  customProperty: 'Custom properties',
};

/** The facet types worth filtering on, in the order a policy author reaches for them. */
export const FILTERABLE_FACETS = [
  'tags',
  'terms',
  'domains',
  'dataProducts',
  'classifications',
  'glossaries',
  'tier',
];

export function facetLabel(facetType: string): string {
  return FACET_LABELS[facetType] ?? facetType;
}

/**
 * One facet, showing how it reached this asset.
 *
 * <p>An inherited facet is drawn faintly and says what it came from, because
 * "why is this column PII?" is the question an owner asks first and the answer
 * is usually a tag on something above it (FR-2A.1). A suggested label is marked
 * too: those are not enforced by default, and a screen that draws them like
 * confirmed ones teaches people the data is protected when it is not (FR-1.3a).
 */
export function FacetChip({ facet }: { facet: FacetRow }) {
  const colour = FACET_COLOURS[facet.facetType] ?? 'gray';
  const suggested = facet.omState === 'Suggested';
  const label =
    facet.facetType === 'customProperty' && facet.property
      ? `${facet.property} = ${facet.facetFqn}`
      : facet.facetFqn;

  const why = [
    facetLabel(facet.facetType),
    facet.direct ? 'applied here' : `inherited${facet.inheritedFrom ? ` from ${facet.inheritedFrom}` : ''}`,
    facet.omLabelType ? `label ${facet.omLabelType}` : null,
    facet.omState ? `state ${facet.omState}` : null,
    facet.provenance !== 'openmetadata' ? `provenance ${facet.provenance}` : null,
  ]
    .filter(Boolean)
    .join(' · ');

  return (
    <span className={facet.direct ? undefined : 'tw:opacity-70'} title={why}>
      <Badge color={suggested ? 'gray' : colour} size="sm" type="pill-color">
        {label}
        {!facet.direct && <span className="tw:ml-1 tw:opacity-70">↑</span>}
        {suggested && <span className="tw:ml-1">?</span>}
      </Badge>
    </span>
  );
}

/** Owners, with the same inherited marker the facets use. */
export function OwnerChip({ owner }: { owner: AssetOwner }) {
  return (
    <span
      className={owner.direct ? undefined : 'tw:opacity-70'}
      title={
        owner.direct
          ? `${owner.type} · named on this asset`
          : `${owner.type} · inherited${owner.inheritedFrom ? ` from ${owner.inheritedFrom}` : ''}`
      }>
      <Badge color="gray" size="sm" type="modern">
        {owner.name}
        {!owner.direct && <span className="tw:ml-1 tw:opacity-70">↑</span>}
      </Badge>
    </span>
  );
}

/** Groups facets by type, keeping the order of {@link FACET_LABELS}. */
export function groupFacets(facets: FacetRow[]): [string, FacetRow[]][] {
  const groups = new Map<string, FacetRow[]>();
  for (const facet of facets) {
    const bucket = groups.get(facet.facetType) ?? [];
    bucket.push(facet);
    groups.set(facet.facetType, bucket);
  }
  const order = Object.keys(FACET_LABELS);
  return [...groups.entries()].sort(
    ([a], [b]) =>
      (order.indexOf(a) === -1 ? 99 : order.indexOf(a)) -
      (order.indexOf(b) === -1 ? 99 : order.indexOf(b))
  );
}

/**
 * The facets shown on a list row.
 *
 * <p>Physical facets (service, database, schema, column name, data type) are
 * dropped: they repeat what the FQN already says, and on a list they crowd out
 * the governance that the row exists to show. The asset page shows everything.
 */
export function listFacets(facets: FacetRow[]): FacetRow[] {
  const shown = facets.filter((facet) => facet.facetType in FACET_LABELS);
  const seen = new Set<string>();
  return shown.filter((facet) => {
    const key = `${facet.facetType}:${facet.facetFqn}:${facet.property ?? ''}`;
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}
