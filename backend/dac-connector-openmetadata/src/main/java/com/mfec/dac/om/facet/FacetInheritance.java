package com.mfec.dac.om.facet;

import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import java.util.ArrayList;
import java.util.EnumSet;
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
 * from — a data owner asking "why does this column count as Finance?"
 * deserves the name of the schema that said so, not a shrug.
 *
 * <p><b>Only the facets OpenMetadata itself inherits are inherited here</b>,
 * and that list is deliberately short: domain, data product, owner and custom
 * property. OpenMetadata really does flow domain and ownership down a service
 * to its databases, schemas and tables, so mirroring it keeps the two catalogs
 * saying the same thing.
 *
 * <p>Classification tags, glossary terms, tier and certification are <b>not</b>
 * inherited, because OpenMetadata does not inherit them either. A column's tags
 * there are exactly the labels somebody put on that column; nothing flows down
 * from the table. Pushing them down anyway had two costs that were not worth
 * paying:
 *
 * <ul>
 *   <li>the catalog stopped agreeing with the catalog it is a cache of — a
 *       table tagged {@code PII.Sensitive} gave every one of its columns a
 *       {@code PII.Sensitive} row, including {@code id} and {@code created_at},
 *       so the screen listed a dozen tags where OpenMetadata showed one;
 *   <li>worse, it broke the selector that column masking is built on. "Mask
 *       every column where {@code tags contains 'PII'}" is meant to find the
 *       columns a steward marked. With downward inheritance it matched every
 *       column of every table carrying the tag, so tagging a table became a way
 *       to null out the whole table by accident.
 * </ul>
 *
 * <p>The schema-wide intent is not lost. A policy that means "everything under
 * this schema" says so with a scope of {@code SCHEMA}, which is what scope
 * levels are for (FR-3.1), rather than by copying a tag onto a thousand
 * columns. Tag <em>ancestors</em> are a different axis and still expand, in
 * {@link FacetExtractor}: a column labelled {@code PII.NonSensitive} does carry
 * a {@code PII} row, because that is the same statement read less precisely.
 *
 * <p>Mutual exclusion no longer needs handling here. It existed so that a
 * table tagged {@code Tier.Tier3} would not also inherit {@code Tier.Tier1}
 * from its schema; with the tag family staying put, the case cannot arise.
 */
public final class FacetInheritance {

  private FacetInheritance() {}

  /**
   * The facet types that cross from one asset to the one below it.
   *
   * <p>Chosen to match OpenMetadata's behaviour rather than to be generous. A
   * column has no domain or owner field of its own, so the only sensible answer
   * to "which domain is this column in" is its table's. Custom properties are
   * on the list for the same reason — OpenMetadata defines them per entity
   * type and a column cannot hold one, so an ABAC rule comparing
   * {@code user.country} against {@code asset.prop('dataResidency')} has to be
   * able to read the table's value from the column.
   *
   * <p>Everything absent from this set stays where it was bound. See the class
   * note for why the tag family is absent.
   */
  private static final Set<FacetType> INHERITABLE =
      EnumSet.of(
          FacetType.DOMAINS,
          FacetType.DATA_PRODUCTS,
          FacetType.OWNERS,
          FacetType.CUSTOM_PROPERTY);

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
   * @param mutuallyExclusiveRoots retained for the call sites; unused while the
   *     tag family does not descend, and the one thing it would govern
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

    if (ancestors != null) {
      for (Level level : ancestors) {
        for (ExtractedFacet facet : level.facets()) {
          if (!INHERITABLE.contains(facet.facetType())) {
            continue;
          }
          ExtractedFacet inherited = facet.inheritedBy(targetFqn, level.fqn());
          // Nearest ancestor first, so the row that survives is the one that
          // can name the closest level that said it.
          if (claimed.add(key(inherited))) {
            out.add(inherited);
          }
        }
      }
    }
    return out;
  }

  private static String key(ExtractedFacet facet) {
    return facet.facetType() + "\0" + facet.property() + "\0" + facet.facetFqn();
  }
}
