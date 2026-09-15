package com.mfec.dac.engine;

/**
 * One value of one facet on one asset, as materialised in {@code asset_facet}.
 *
 * <p>The ancestor chain is expanded at materialisation time, so an asset in
 * {@code Finance.Risk.Credit} carries three rows — {@code Finance},
 * {@code Finance.Risk}, {@code Finance.Risk.Credit} — and a hierarchical
 * selector becomes an index lookup instead of a recursive query (FR-2A.2).
 * {@code depth} is the distance from the direct binding and {@code direct} marks
 * the one that was actually attached.
 *
 * @param value      the facet FQN, or the raw value for typed custom properties
 * @param depth      0 for a direct binding, higher for each ancestor level
 * @param direct     false when this row exists only because of ancestor expansion
 * @param suggested  OpenMetadata {@code state = Suggested}: guessed, not confirmed
 * @param propagated OpenMetadata {@code labelType = Propagated | Derived}
 */
public record FacetValue(
    String value, int depth, boolean direct, boolean suggested, boolean propagated) {

  /** A confirmed, directly attached value — the ordinary case. */
  public static FacetValue of(String value) {
    return new FacetValue(value, 0, true, false, false);
  }

  /** An ancestor row produced by expanding a direct binding. */
  public static FacetValue ancestor(String value, int depth) {
    return new FacetValue(value, depth, false, false, false);
  }

  /** A value OpenMetadata has only suggested; not enforced unless opted in. */
  public static FacetValue suggested(String value) {
    return new FacetValue(value, 0, true, true, false);
  }
}
