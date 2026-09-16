package com.mfec.dac.om.facet;

import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;

/**
 * One row destined for {@code asset_facet}.
 *
 * <p>This is the whole output of reading an OpenMetadata entity: the crawler
 * turns tables and columns into these, and the writer turns these into rows.
 * Nothing in between touches the database, so the rules that decide what a
 * facet means — which ancestors it implies, whether a suggested tag counts,
 * where an inherited one came from — are testable without one.
 *
 * @param targetFqn     the asset or column the facet applies to
 * @param facetType     which facet; the same vocabulary the policy engine uses
 * @param facetFqn      the facet value, or the raw value for a custom property
 * @param property      custom-property name; null for every other facet type
 * @param depth         0 when bound at this exact value, 1 per ancestor level
 * @param direct        false when the row exists only through expansion or
 *                      inheritance
 * @param inheritedFrom the FQN this came down from, so the UI can answer "why
 *                      does this column count as PII?" (FR-2A.1); null when the
 *                      facet was attached here
 * @param omState       OpenMetadata {@code state}: Suggested or Confirmed
 * @param omLabelType   OpenMetadata {@code labelType}: Manual, Automated,
 *                      Propagated or Derived
 */
public record ExtractedFacet(
    String targetFqn,
    FacetType facetType,
    String facetFqn,
    String property,
    int depth,
    boolean direct,
    String inheritedFrom,
    String omState,
    String omLabelType) {

  /** A value attached directly to this asset, with no OpenMetadata label metadata. */
  public static ExtractedFacet direct(String targetFqn, FacetType type, String value) {
    return new ExtractedFacet(targetFqn, type, value, null, 0, true, null, null, null);
  }

  /** The same facet as seen one level further up its own hierarchy. */
  public ExtractedFacet asAncestor(String ancestorFqn, int ancestorDepth) {
    return new ExtractedFacet(
        targetFqn, facetType, ancestorFqn, property, ancestorDepth, false, inheritedFrom, omState,
        omLabelType);
  }

  /** The same facet, as it appears on a descendant that inherited it. */
  public ExtractedFacet inheritedBy(String childFqn, String from) {
    return new ExtractedFacet(
        childFqn, facetType, facetFqn, property, depth, false, from, omState, omLabelType);
  }

  /**
   * Whether OpenMetadata has confirmed this label.
   *
   * <p>Default enforcement covers confirmed labels only. A tag a classifier
   * guessed at should not lock a column on its own — that decision belongs to a
   * person, and a policy opts into suggestions explicitly (FR-1.3a).
   */
  public boolean confirmed() {
    return omState == null || "Confirmed".equals(omState);
  }

  /**
   * Whether OpenMetadata produced this label by its own propagation.
   *
   * <p>We compute inheritance ourselves and have to explain it, so a propagated
   * label is recorded but is not counted a second time as if it had been
   * attached by hand (FR-1.3a).
   */
  public boolean propagated() {
    return "Propagated".equals(omLabelType) || "Derived".equals(omLabelType);
  }
}
