package com.mfec.dac.om.facet;

import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pushes facets down the asset hierarchy: database to schema to table to column.
 *
 * <p>The effective facets of an asset are what it carries itself plus what its
 * ancestors carry (FR-2A.1). This is computed here rather than read back from
 * OpenMetadata's own propagation, because that behaviour differs between
 * versions and because every inherited row has to be able to say where it came
 * from — a data owner asking "why is this column PII?" deserves the name of the
 * schema that said so, not a shrug.
 *
 * <p>Inheritance is additive, with one exception. Within a mutually exclusive
 * classification an asset may hold only one tag, so a table tagged
 * {@code Tier.Tier3} must not also inherit {@code Tier.Tier1} from its schema:
 * the nearer statement wins outright. That is the override the model promises,
 * and it is the only case where a child removes something rather than adding.
 */
public final class FacetInheritance {

  private FacetInheritance() {}

  /**
   * One level above the asset being computed.
   *
   * @param fqn    the ancestor's fully-qualified name, used as {@code inherited_from}
   * @param facets the facets that ancestor holds, already inherited into itself
   */
  public record Level(String fqn, List<ExtractedFacet> facets) {
    public Level {
      facets = facets == null ? List.of() : List.copyOf(facets);
    }
  }

  /**
   * Merges an asset's own facets with everything it inherits.
   *
   * @param targetFqn             the asset or column being computed
   * @param own                   facets bound directly to it
   * @param ancestors             enclosing levels, nearest first
   * @param mutuallyExclusiveRoots classification FQNs that permit one tag only
   * @return own facets first, then inherited ones, with no duplicates
   */
  public static List<ExtractedFacet> effective(
      String targetFqn,
      List<ExtractedFacet> own,
      List<Level> ancestors,
      Set<String> mutuallyExclusiveRoots) {

    List<ExtractedFacet> out = new ArrayList<>(own == null ? List.of() : own);
    Set<String> claimed = new LinkedHashSet<>();
    for (ExtractedFacet facet : out) {
      claimed.add(key(facet));
    }
    // A classification the asset has already spoken on. Recorded before any
    // inheritance runs, so the nearest statement wins regardless of the order
    // ancestors are visited in.
    Set<String> settled = exclusiveRootsHeldBy(out, mutuallyExclusiveRoots);

    if (ancestors != null) {
      for (Level level : ancestors) {
        for (ExtractedFacet facet : level.facets()) {
          if (blockedByOverride(facet, settled)) {
            continue;
          }
          ExtractedFacet inherited = facet.inheritedBy(targetFqn, level.fqn());
          if (claimed.add(key(inherited))) {
            out.add(inherited);
          }
        }
        // Only the nearest ancestor that says anything about an exclusive
        // classification gets to; levels above it are then settled too.
        settled.addAll(exclusiveRootsHeldBy(level.facets(), mutuallyExclusiveRoots));
      }
    }
    return out;
  }

  /**
   * Whether an inherited tag is shut out by a nearer tag in the same
   * mutually exclusive classification.
   */
  private static boolean blockedByOverride(ExtractedFacet facet, Set<String> settled) {
    if (settled.isEmpty()) {
      return false;
    }
    return switch (facet.facetType()) {
      case TAGS -> settled.contains(FacetExtractor.rootSegment(facet.facetFqn()));
      // The classification row and the tier row are both restatements of the
      // tag, so they have to fall with it or the override leaks through.
      case CLASSIFICATIONS -> settled.contains(facet.facetFqn());
      case TIER -> settled.contains("Tier");
      default -> false;
    };
  }

  private static Set<String> exclusiveRootsHeldBy(
      List<ExtractedFacet> facets, Set<String> mutuallyExclusiveRoots) {
    Set<String> held = new HashSet<>();
    if (mutuallyExclusiveRoots == null || mutuallyExclusiveRoots.isEmpty()) {
      return held;
    }
    for (ExtractedFacet facet : facets) {
      if (facet.facetType() != FacetType.TAGS) {
        continue;
      }
      String root = FacetExtractor.rootSegment(facet.facetFqn());
      if (root != null && mutuallyExclusiveRoots.contains(root)) {
        held.add(root);
      }
    }
    return held;
  }

  private static String key(ExtractedFacet facet) {
    return facet.facetType() + "\0" + facet.property() + "\0" + facet.facetFqn();
  }
}
