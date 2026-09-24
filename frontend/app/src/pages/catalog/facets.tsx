import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { badgeWeight } from '../../components/chips';
import type { BadgeColors } from '@openmetadata/ui-core-components/components/base/badges/badge-types';
import type { AssetOwner, FacetRow } from '../../api/client';
import { isAncestor, segments } from '../../lib/fqn';

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

/**
 * The group heading's colour swatch, keyed the same way as the chips under it.
 *
 * <p>A 500-level dot rather than the chip's 100-level fill: at eight pixels a
 * pale wash is invisible, and the dot's whole job is to tie the heading to the
 * row of chips beneath it without repeating their shape.
 */
const FACET_DOTS: Record<string, string> = {
  tags: 'tw:bg-utility-error-500',
  classifications: 'tw:bg-utility-error-500',
  terms: 'tw:bg-utility-purple-500',
  glossaries: 'tw:bg-utility-purple-500',
  domains: 'tw:bg-utility-blue-500',
  dataProducts: 'tw:bg-utility-indigo-500',
  tier: 'tw:bg-utility-warning-500',
  certification: 'tw:bg-utility-success-500',
  customProperty: 'tw:bg-utility-gray-blue-500',
  owners: 'tw:bg-utility-gray-500',
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
  owners: 'Owners',
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
 * The same labels in the singular, for a chip that names one thing.
 *
 * <p>A chip reading "Domains" beside a single domain is a small lie that a
 * reader has to step over every time. The plural forms stay where they belong,
 * on the filter menus and the group headings.
 */
const FACET_ONE: Record<string, string> = {
  tags: 'Tag',
  classifications: 'Classification',
  terms: 'Term',
  glossaries: 'Glossary',
  domains: 'Domain',
  dataProducts: 'Data product',
  tier: 'Tier',
  certification: 'Certification',
  owners: 'Owner',
};

/** What a chip calls its own facet type. */
export function facetOne(facetType: string): string {
  return FACET_ONE[facetType] ?? facetLabel(facetType);
}

/**
 * How much of an FQN a chip prints.
 *
 * <p>Enough for the parent to fit beside a short name and no more. Past that
 * the parent is what gets dropped, never the name: {@code shortFqn} put an
 * ellipsis in front, the chip's own width put one behind, and a long
 * sub-domain arrived on screen elided at both ends -- dots, a fragment, dots.
 */
const NAME_BUDGET = 30;

/**
 * The readable part of a facet's FQN.
 *
 * <p>The parent earns its width by telling two same-named leaves apart --
 * {@code PII.Sensitive} against {@code MFEC-PDPA.Sensitive} are different
 * rules with different owners -- so it is kept while both still fit. The whole
 * FQN is on the chip's tooltip either way, which is where somebody goes when
 * the short form is not enough.
 */
export function facetName(fqn: string): string {
  const parts = segments(fqn);
  if (parts.length <= 1) return fqn;

  const name = parts[parts.length - 1];
  const parent = parts[parts.length - 2];
  return parent.length + name.length <= NAME_BUDGET ? `${parent} / ${name}` : name;
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
export function FacetChip({
  facet,
  showKind = true,
}: {
  facet: FacetRow;
  /**
   * False under a heading that already names the kind.
   *
   * <p>The prefix earns its width on the catalog list, where chips of six
   * kinds sit in one undifferentiated row. Under a heading reading "Tags" it
   * only produces "Tag PII / Sensitive", which spends a third of the chip
   * restating the line above it.
   */
  showKind?: boolean;
}) {
  const colour = FACET_COLOURS[facet.facetType] ?? 'gray';
  const suggested = facet.omState === 'Suggested';

  // A custom property names itself -- the property *is* the kind -- so it
  // stands in for the type label rather than being prefixed by one.
  const custom = facet.facetType === 'customProperty' && facet.property;
  const label = custom ? facet.facetFqn : facetName(facet.facetFqn);

  // "Tier Tier3" says Tier twice. Where the value already opens with the name
  // of its own kind the prefix adds nothing, so it is dropped rather than
  // stuttered.
  const named = custom || facetOne(facet.facetType);
  const kind =
    !showKind || label.toLowerCase().startsWith(named.toLowerCase())
      ? null
      : named;

  // `direct` is false for three different reasons, and only one of them is
  // inheritance. A domain that arrived from the table above carries
  // `inheritedFrom` and really did come from somewhere else. The bare `PII`
  // sitting beside a `PII.NonSensitive` applied right here does not: it is the
  // ancestor row the crawl materialises so that a selector stays an index
  // lookup (FR-2A.2), and the `classifications` row beside it is derived from
  // the same tag. Marking those two "inherited" told the reader ARAK had
  // invented governance OpenMetadata never showed them -- which was the
  // complaint, and it was a fair one.
  const inherited = !facet.direct && Boolean(facet.inheritedFrom);
  const broader = !facet.direct && !facet.inheritedFrom;

  const why = [
    facetLabel(facet.facetType),
    facet.facetFqn,
    inherited
      ? `inherited from ${facet.inheritedFrom}`
      : broader
        ? 'implied by a more specific value applied here'
        : 'applied here',
    facet.omLabelType ? `label ${facet.omLabelType}` : null,
    // Spelled out rather than echoed. "state Suggested" is OpenMetadata's
    // word for it, and it means nothing to somebody who has not read the
    // spec. What the reader needs to know is whether this tag is protecting
    // the column, and for a suggested one the answer is no.
    suggested
      ? 'suggested by OpenMetadata, not confirmed by anyone — no policy enforces on it unless one opts in'
      : facet.omState
        ? 'state ' + facet.omState
        : null,
    facet.provenance !== 'openmetadata' ? `provenance ${facet.provenance}` : null,
  ]
    .filter(Boolean)
    .join(' · ');

  const shade = suggested ? 'gray' : colour;
  const weight = badgeWeight(shade, !facet.direct);

  return (
    <span title={why}>
      <Badge
        bordered={false}
        className={`tw:max-w-80 ${weight}`}
        color={shade}
        size="sm"
        type="pill-color">
        {/* The kind first, at a lighter weight. Six chips in a row are six
            different kinds of thing, and a reader who cannot tell a domain
            from a glossary term is reading a colour code they were never
            given. It never truncates: a half-written "Doma" would be worse
            than none, and it is short enough that it never needs to. */}
        {kind && <span className="tw:mr-1 tw:shrink-0 tw:opacity-65">{kind}</span>}
        {/* Truncated rather than wrapped: a chip that grows to fit a
            hundred-character sub-domain takes the whole row with it. Only the
            end is cut, so the chip always reads from its first letter. */}
        <span className="tw:truncate">{label}</span>
        {/* The word, not an arrow. This used to be a bare ↑, which readers
            reasonably took for a sort control or a link and which said
            nothing at all to anybody who did not already know the
            convention. "inherited" is four characters longer and needs no
            key -- and it is now printed only where something really was
            inherited from another asset, never on a materialised ancestor. */}
        {inherited && (
          <span className="tw:ml-1 tw:shrink-0 tw:text-[10px] tw:opacity-70">
            inherited
          </span>
        )}
        {/* The word, for the same reason the arrow became one. A bare "?"
            was read as a help button -- somebody asked what it meant, which
            is the whole answer about whether it was working. This label says
            what ARAK actually does with the tag: OpenMetadata is only
            guessing at it, nobody has confirmed it, and so no policy is
            enforcing on it unless one opted in (FR-1.3a). */}
        {suggested && (
          <span className="tw:ml-1 tw:shrink-0 tw:text-[10px] tw:opacity-70">
            unconfirmed
          </span>
        )}
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
          <span className="tw:ml-1 tw:shrink-0 tw:text-[10px] tw:opacity-70">
            inherited
          </span>
        )}
      </Badge>
    </span>
  );
}

/**
 * One kind of facet under its own heading.
 *
 * <p>The flat wrap this replaces put eleven chips of five kinds in one block
 * and asked the reader to sort them by colour. A heading per kind is how
 * OpenMetadata's own asset page reads, and it answers the question people
 * actually arrive with -- "what domain is this in?" -- by letting them look in
 * one place rather than scan the lot.
 */
export function FacetGroup({
  facets,
  type,
}: {
  facets: FacetRow[];
  type: string;
}) {
  return (
    <div>
      <div className="tw:mb-2 tw:flex tw:items-center tw:gap-2">
        <span
          aria-hidden="true"
          className={`tw:size-2 tw:shrink-0 tw:rounded-full ${FACET_DOTS[type] ?? 'tw:bg-utility-gray-500'}`}
        />
        <h3 className="tw:text-xs tw:font-semibold tw:uppercase tw:tracking-wide tw:text-tertiary">
          {facetLabel(type)}
        </h3>
        <span className="tw:text-xs tw:tabular-nums tw:text-quaternary">
          {facets.length}
        </span>
      </div>
      <div className="tw:flex tw:flex-wrap tw:gap-1.5">
        {facets.map((facet) => (
          <FacetChip
            facet={facet}
            key={`${facet.facetFqn}:${facet.property ?? ''}:${facet.depth}`}
            showKind={false}
          />
        ))}
      </div>
    </div>
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
/**
 * The facets shown on one column of the columns table.
 *
 * <p>Only what the column itself carries. A column inherits its domain,
 * its owners and its custom properties from the table (FR-2A.1), and those
 * are already drawn once on the table above — repeating them on every
 * row of a three-hundred-column table buries the one chip that differs
 * between rows under five that never do.
 *
 * <p>What remains is the column's own governance, matching what
 * OpenMetadata prints in its own Columns tab, with the materialised
 * ancestors rolled up by {@link listFacets}: a column tagged
 * `PII.NonSensitive` reads as one chip, not as `PII.NonSensitive` beside a
 * bare `PII` that says the same thing less precisely.
 */
export function columnFacets(facets: FacetRow[]): FacetRow[] {
  return listFacets(facets.filter((facet) => !facet.inheritedFrom));
}

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
