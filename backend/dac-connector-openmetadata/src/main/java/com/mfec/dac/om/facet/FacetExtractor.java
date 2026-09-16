package com.mfec.dac.om.facet;

import com.mfec.dac.common.Fqns;
import com.mfec.dac.om.client.model.AssetCertification;
import com.mfec.dac.om.client.model.Column;
import com.mfec.dac.om.client.model.EntityReference;
import com.mfec.dac.om.client.model.Table;
import com.mfec.dac.om.client.model.TagLabel;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns an OpenMetadata entity into the facet rows a policy selector reads.
 *
 * <p>Two things happen here that are easy to get wrong and expensive to get
 * wrong late.
 *
 * <p>The first is that OpenMetadata delivers classification tags and glossary
 * terms in the same {@code tags[]} array, separated only by a {@code source}
 * field. They mean entirely different things to a policy — one is a sensitivity
 * marking, the other a business definition — so they are split apart here rather
 * than anywhere downstream (FR-1.3a).
 *
 * <p>The second is ancestor expansion. A policy written as
 * {@code domains contains 'Finance'} has to keep covering an asset after someone
 * splits {@code Finance.Risk.Credit} out beneath it, and it has to do so without
 * a recursive query in the hot path. So every hierarchical value is written out
 * with its whole ancestor chain at crawl time (FR-2A.2). Physical facets —
 * service, database, schema, table, column name, data type — are deliberately
 * NOT emitted: the engine derives those from the asset itself, and a second copy
 * here would be one more thing to keep in step.
 */
public final class FacetExtractor {

  /** The OpenMetadata classification that carries tier, as {@code Tier.Tier1}. */
  private static final String TIER_CLASSIFICATION = "Tier";

  private FacetExtractor() {}

  /** Every facet of a table, including the ones its own columns do not carry. */
  public static List<ExtractedFacet> fromTable(Table table) {
    if (table == null || table.getFullyQualifiedName() == null) {
      return List.of();
    }
    return fromParts(
        table.getFullyQualifiedName(),
        table.getTags(),
        table.getDomains(),
        table.getDataProducts(),
        table.getOwners(),
        table.getCertification(),
        table.getExtension());
  }

  /**
   * The same extraction for any entity that carries governance, given its parts.
   *
   * <p>A service, a database and a schema hold exactly the fields a table does —
   * tags, domains, data products, owners, certification, custom properties — but
   * the generated model gives them no common supertype, so they cannot be passed
   * as one type. Taking the parts keeps a single implementation of what a facet
   * means; four copies of this method is how a tag would come to mean one thing
   * on a schema and another on the table inside it.
   */
  public static List<ExtractedFacet> fromParts(
      String fqn,
      List<TagLabel> tags,
      List<EntityReference> domains,
      List<EntityReference> dataProducts,
      List<EntityReference> owners,
      AssetCertification certification,
      Object extension) {
    if (fqn == null || fqn.isBlank()) {
      return List.of();
    }
    List<ExtractedFacet> out = new ArrayList<>(fromTagLabels(fqn, tags));

    out.addAll(references(fqn, FacetType.DOMAINS, domains, true));
    // Data products are flat, but each belongs to a domain. That indirect link
    // is resolved by the crawler, which knows the product catalogue; expanding
    // a product FQN as if it were a hierarchy would invent one.
    out.addAll(references(fqn, FacetType.DATA_PRODUCTS, dataProducts, false));
    out.addAll(owners(fqn, owners));
    out.addAll(certification(fqn, certification));
    out.addAll(customProperties(fqn, extension));
    return dedupe(out);
  }

  /** Every facet of one column of a table. */
  public static List<ExtractedFacet> fromColumn(Column column) {
    if (column == null || column.getFullyQualifiedName() == null) {
      return List.of();
    }
    String fqn = column.getFullyQualifiedName();
    List<ExtractedFacet> out = new ArrayList<>(fromTagLabels(fqn, column.getTags()));
    out.addAll(customProperties(fqn, column.getExtension()));
    return dedupe(out);
  }

  /**
   * Splits {@code tags[]} into its four facets.
   *
   * <p>A classification tag {@code PII.Sensitive} produces a {@code tags} row for
   * itself, a {@code tags} row for each ancestor, and a {@code classifications}
   * row for the classification it lives in. A glossary term does the same into
   * {@code terms} and {@code glossaries}. A tag in the {@code Tier}
   * classification additionally produces a {@code tier} row, because
   * OpenMetadata models tier as a tag and policies are written both ways.
   */
  public static List<ExtractedFacet> fromTagLabels(String targetFqn, List<TagLabel> labels) {
    if (labels == null || labels.isEmpty()) {
      return List.of();
    }
    List<ExtractedFacet> out = new ArrayList<>();
    for (TagLabel label : labels) {
      String value = label.getTagFQN();
      if (value == null || value.isBlank()) {
        continue;
      }
      boolean glossary = label.getSource() == TagLabel.SourceEnum.GLOSSARY;
      FacetType leafType = glossary ? FacetType.TERMS : FacetType.TAGS;
      FacetType rootType = glossary ? FacetType.GLOSSARIES : FacetType.CLASSIFICATIONS;

      String state = label.getState() == null ? null : label.getState().getValue();
      String labelType = label.getLabelType() == null ? null : label.getLabelType().getValue();
      ExtractedFacet leaf =
          new ExtractedFacet(targetFqn, leafType, value, null, 0, true, null, state, labelType);
      out.add(leaf);
      out.addAll(ancestorsOf(leaf));

      // The root segment is the classification or the glossary. Carrying the
      // label's own state forward keeps a suggested tag from silently turning
      // into a confirmed membership of its classification.
      String root = rootSegment(value);
      if (root != null) {
        out.add(
            new ExtractedFacet(targetFqn, rootType, root, null, 0, false, null, state, labelType));
      }
      if (!glossary && TIER_CLASSIFICATION.equals(root)) {
        String tier = Fqns.leaf(value);
        if (tier != null && !tier.equals(value)) {
          out.add(
              new ExtractedFacet(
                  targetFqn, FacetType.TIER, tier, null, 0, true, null, state, labelType));
        }
      }
    }
    return out;
  }

  /**
   * The ancestor rows implied by one hierarchical value.
   *
   * <p>{@code Finance.Risk.Credit} yields {@code Finance.Risk} at depth 1 and
   * {@code Finance} at depth 2. Segments are rebuilt through {@link Fqns} rather
   * than by cutting the string at dots, so a domain genuinely named
   * {@code Sales.EU} stays one level instead of becoming two.
   */
  public static List<ExtractedFacet> ancestorsOf(ExtractedFacet facet) {
    List<String> segments = Fqns.segments(facet.facetFqn());
    if (segments.size() < 2) {
      return List.of();
    }
    List<ExtractedFacet> out = new ArrayList<>(segments.size() - 1);
    for (int keep = segments.size() - 1; keep >= 1; keep--) {
      String ancestor = Fqns.join(segments.subList(0, keep).toArray(new String[0]));
      out.add(facet.asAncestor(ancestor, segments.size() - keep));
    }
    return out;
  }

  /** Entity references as a facet, optionally expanded up their own hierarchy. */
  public static List<ExtractedFacet> references(
      String targetFqn, FacetType type, List<EntityReference> refs, boolean hierarchical) {
    if (refs == null || refs.isEmpty()) {
      return List.of();
    }
    List<ExtractedFacet> out = new ArrayList<>();
    for (EntityReference ref : refs) {
      String value = nameOf(ref);
      if (value == null) {
        continue;
      }
      // OpenMetadata marks a reference it propagated down itself. We compute and
      // explain inheritance ourselves, so such a row is kept but flagged rather
      // than counted as a direct binding (FR-1.3a).
      boolean inherited = Boolean.TRUE.equals(ref.getInherited());
      ExtractedFacet facet =
          new ExtractedFacet(
              targetFqn, type, value, null, 0, !inherited, null, null,
              inherited ? "Propagated" : null);
      out.add(facet);
      if (hierarchical) {
        out.addAll(ancestorsOf(facet));
      }
    }
    return out;
  }

  /** Owners, so a policy can say {@code assetOwner: true} and mean it (FR-3.2a). */
  public static List<ExtractedFacet> owners(String targetFqn, List<EntityReference> owners) {
    return references(targetFqn, FacetType.OWNERS, owners, false);
  }

  /** Certification, which OpenMetadata models as a tag label on the asset. */
  public static List<ExtractedFacet> certification(
      String targetFqn, AssetCertification certification) {
    if (certification == null || certification.getTagLabel() == null) {
      return List.of();
    }
    String value = certification.getTagLabel().getTagFQN();
    if (value == null || value.isBlank()) {
      return List.of();
    }
    String leaf = Fqns.leaf(value);
    return List.of(ExtractedFacet.direct(targetFqn, FacetType.CERTIFICATION, leaf));
  }

  /**
   * Custom property values, read from the entity's {@code extension} object.
   *
   * <p>These are the asset half of ABAC: without them
   * {@code user.country == asset.prop('dataResidency')} has nothing to compare
   * against. Values are stored as text and compared according to the property's
   * declared type, which the crawler syncs separately (FR-1.9).
   */
  public static List<ExtractedFacet> customProperties(String targetFqn, Object extension) {
    if (!(extension instanceof Map<?, ?> map) || map.isEmpty()) {
      return List.of();
    }
    List<ExtractedFacet> out = new ArrayList<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String name = String.valueOf(entry.getKey());
      Object value = entry.getValue();
      if (value == null) {
        continue;
      }
      // A multi-select property arrives as an array. One row per element is what
      // lets `prop('regions') contains 'APAC'` behave like every other
      // multi-valued facet instead of matching against a rendered list.
      if (value instanceof Iterable<?> values) {
        for (Object each : values) {
          if (each != null) {
            out.add(property(targetFqn, name, each));
          }
        }
      } else {
        out.add(property(targetFqn, name, value));
      }
    }
    return out;
  }

  private static ExtractedFacet property(String targetFqn, String name, Object value) {
    return new ExtractedFacet(
        targetFqn, FacetType.CUSTOM_PROPERTY, stringify(value), name, 0, true, null, null, null);
  }

  /**
   * Renders a property value for storage.
   *
   * <p>An entity reference arrives as an object; the FQN is the part a policy
   * can compare, so that is what is kept.
   */
  private static String stringify(Object value) {
    if (value instanceof Map<?, ?> map) {
      Object fqn = map.get("fullyQualifiedName");
      if (fqn == null) {
        fqn = map.get("name");
      }
      if (fqn == null) {
        fqn = map.get("id");
      }
      return fqn == null ? String.valueOf(value) : String.valueOf(fqn);
    }
    return String.valueOf(value);
  }

  /** The first segment: the classification of a tag, or the glossary of a term. */
  public static String rootSegment(String fqn) {
    List<String> segments = Fqns.segments(fqn);
    if (segments.isEmpty() || segments.get(0).isEmpty()) {
      return null;
    }
    return segments.get(0);
  }

  private static String nameOf(EntityReference ref) {
    if (ref == null) {
      return null;
    }
    String fqn = ref.getFullyQualifiedName();
    if (fqn != null && !fqn.isBlank()) {
      return fqn;
    }
    return ref.getName() == null || ref.getName().isBlank() ? null : ref.getName();
  }

  /**
   * Collapses rows that differ only in how they were reached.
   *
   * <p>A value can arrive both directly and as somebody else's ancestor — a
   * table tagged {@code Finance} that also sits in {@code Finance.Risk}. The
   * shallower, more direct row wins, because it is the one that explains the
   * binding truthfully.
   */
  static List<ExtractedFacet> dedupe(List<ExtractedFacet> facets) {
    List<ExtractedFacet> sorted = new ArrayList<>(facets);
    sorted.sort(
        (a, b) -> {
          int byDirect = Boolean.compare(!a.direct(), !b.direct());
          return byDirect != 0 ? byDirect : Integer.compare(a.depth(), b.depth());
        });
    Set<String> seen = new LinkedHashSet<>();
    List<ExtractedFacet> out = new ArrayList<>(sorted.size());
    for (ExtractedFacet facet : sorted) {
      String key =
          facet.targetFqn() + '\0' + facet.facetType() + '\0' + facet.property() + '\0'
              + facet.facetFqn();
      if (seen.add(key)) {
        out.add(facet);
      }
    }
    return out;
  }
}
