import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { badgeWeight } from '../../components/chips';
import type { BadgeColors } from '@openmetadata/ui-core-components/components/base/badges/badge-types';
import type { AssetOwner, FacetRow } from '../../api/client';
import { isAncestor, shortFqn } from '../../lib/fqn';

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
 * <p>An inherited facet is drawn a shade lighter and says what it came from,
 * because "why is this column PII?" is the question an owner asks first and the
 * answer is usually a tag on something above it (FR-2A.1). A suggested label is
 * marked too: those are not enforced by default, and a screen that draws them
 * like confirmed ones teaches people the data is protected when it is not
 * (FR-1.3a).
 */
export function FacetChip({ facet }: { facet: FacetRow }) {
  const colour = FACET_COLOURS[facet.facetType] ?? 'gray';
  const suggested = facet.omState === 'Suggested';
  const label =
    facet.facetType === 'customProperty' && facet.property
      ? `${facet.property} = ${facet.facetFqn}`
      : shortFqn(facet.facetFqn);

  const why = [
    facetLabel(facet.facetType),
    facet.facetFqn,
    facet.direct ? 'applied here' : `inherited${facet.inheritedFrom ? ` from ${facet.inheritedFrom}` : ''}`,
    facet.omLabelType ? `label ${facet.omLabelType}` : null,
    facet.omState ? `state ${facet.omState}` : null,
    facet.provenance !== 'openmetadata' ? `provenance ${facet.provenance}` : null,
  ]
    .filter(Boolean)
    .join(' · ');

  const shade = suggested ? 'gray' : colour;
  const weight = badgeWeight(shade, !facet.direct);

  return (
    <span title={why}>
      <Badge
        className={`tw:max-w-72 ${weight}`}
        color={shade}
        size="sm"
        type="pill-color">
        {/* Truncated rather than wrapped: a chip that grows to fit a
            hundred-character sub-domain takes the whole row with it. */}
        <span className="tw:truncate">{label}</span>
        {!facet.direct && (
          <span aria-hidden className="tw:ml-1 tw:text-[10px] tw:opacity-80">
            ↑
          </span>
        )}
        {suggested && <span className="tw:ml-1">?</span>}
      </Badge>
    </span>
  );
}

/** Owners, with the same inherited marker the facets use. */
export function OwnerChip({ owner }: { owner: AssetOwner }) {
  return (
    <span
      title={
        owner.direct
          ? `${owner.type} · named on this asset`
          : `${owner.type} · inherited${owner.inheritedFrom ? ` from ${owner.inheritedFrom}` : ''}`
      }>
      {/* An owner is a person, not a classification, so it stays the one white
          chip in the row: colouring it would put it in competition with the
          governance facets it sits beside. */}
      <Badge
        className={`tw:font-medium ${owner.direct ? '' : 'tw:text-tertiary'}`}
        color="gray"
        size="sm"
        type="modern">
        {owner.name}
        {!owner.direct && (
          <span aria-hidden className="tw:ml-1 tw:text-[10px] tw:opacity-80">
            ↑
          </span>
        )}
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
 * Facet types that say nothing new on a list row.
 *
 * <p>`classifications` is the head of the tag FQN that is already on the row —
 * an asset tagged `PII.Sensitive` drew a second chip reading `PII`. `tier` is
 * drawn as its own badge in the row header, so keeping the facet drew `Tier2`
 * twice. Both are still shown in full on the asset page, where completeness is
 * the point.
 */
const OFF_THE_LIST = ['classifications', 'tier'];

/**
 * The facets shown on a list row.
 *
 * <p>Physical facets (service, database, schema, column name, data type) are
 * dropped: they repeat what the FQN already says, and on a list they crowd out
 * the governance that the row exists to show. The asset page shows everything.
 *
 * <p>Ancestors are dropped too. `asset_facet` deliberately materialises the
 * whole chain (FR-2A.2) so that a selector is an index lookup, but a row that
 * prints every link of it is a row of eleven chips where four carry the
 * meaning: a three-deep sub-domain arrived as three chips, each repeating the
 * one before it. The deepest one implies its ancestors, and the tooltip on it
 * spells them out.
 */
export function listFacets(facets: FacetRow[]): FacetRow[] {
  const shown = facets.filter(
    (facet) =>
      facet.facetType in FACET_LABELS && !OFF_THE_LIST.includes(facet.facetType)
  );

  const seen = new Set<string>();
  const unique = shown.filter((facet) => {
    const key = `${facet.facetType}:${facet.facetFqn}:${facet.property ?? ''}`;
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });

  return unique.filter(
    (facet) =>
      !unique.some(
        (other) =>
          other.facetType === facet.facetType &&
          isAncestor(facet.facetFqn, other.facetFqn)
      )
  );
}
