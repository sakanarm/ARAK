package com.mfec.dac.om.crawl;

import com.mfec.dac.common.Fqns;
import com.mfec.dac.om.client.model.Classification;
import com.mfec.dac.om.client.model.CustomProperty;
import com.mfec.dac.om.client.model.DataProduct;
import com.mfec.dac.om.client.model.Domain;
import com.mfec.dac.om.client.model.EntityReference;
import com.mfec.dac.om.client.model.Glossary;
import com.mfec.dac.om.client.model.GlossaryTerm;
import com.mfec.dac.om.client.model.Tag;
import com.mfec.dac.om.client.model.TermRelation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Narrows OpenMetadata's governance entities to the handful of fields policies
 * actually read.
 *
 * <p>Kept free of any network or database call so the rules that are easy to get
 * subtly wrong — which FQN is the parent, what counts as the classification of a
 * nested tag, how an enum property's values are shaped — can be tested against a
 * fixture instead of against a running instance.
 *
 * <p>Parent links are <em>derived from the FQN</em> rather than read from
 * OpenMetadata's {@code parent} reference. The facet table expands ancestors the
 * same way ({@link com.mfec.dac.om.facet.FacetExtractor#ancestorsOf}), and two
 * different notions of "parent" in one system is how a sub-domain ends up
 * visible to a policy that never selected it. Where OpenMetadata disagrees with
 * the derivation, the disagreement is logged rather than silently preferred.
 */
public final class GovernanceMapper {

  private static final Logger LOG = LoggerFactory.getLogger(GovernanceMapper.class);

  private GovernanceMapper() {}

  public static GovernanceSnapshot.ClassificationRow classification(Classification source) {
    if (source == null || blank(source.getFullyQualifiedName())) {
      return null;
    }
    return new GovernanceSnapshot.ClassificationRow(
        source.getId(),
        source.getFullyQualifiedName(),
        source.getName(),
        source.getDescription(),
        Boolean.TRUE.equals(source.getMutuallyExclusive()),
        source.getProvider() == null ? "user" : source.getProvider().getValue(),
        Boolean.TRUE.equals(source.getDisabled()));
  }

  public static GovernanceSnapshot.TagRow tag(Tag source) {
    if (source == null || blank(source.getFullyQualifiedName())) {
      return null;
    }
    String fqn = source.getFullyQualifiedName();
    // A nested tag's classification is the first segment, not its parent tag:
    // PII.Sensitive.Financial belongs to PII, and a selector written as
    // `classifications contains 'PII'` has to keep reaching it.
    String classification = rootOf(fqn);
    String parent = parentWithinRoot(fqn);
    warnIfParentDisagrees(fqn, source.getClassification(), classification, "classification");
    return new GovernanceSnapshot.TagRow(
        source.getId(),
        classification,
        fqn,
        parent,
        source.getName(),
        source.getDescription(),
        Boolean.TRUE.equals(source.getDisabled()));
  }

  public static GovernanceSnapshot.GlossaryRow glossary(Glossary source) {
    if (source == null || blank(source.getFullyQualifiedName())) {
      return null;
    }
    return new GovernanceSnapshot.GlossaryRow(
        source.getId(),
        source.getFullyQualifiedName(),
        source.getName(),
        source.getDescription());
  }

  public static GovernanceSnapshot.GlossaryTermRow glossaryTerm(GlossaryTerm source) {
    if (source == null || blank(source.getFullyQualifiedName())) {
      return null;
    }
    String fqn = source.getFullyQualifiedName();
    String glossaryFqn = rootOf(fqn);
    warnIfParentDisagrees(fqn, source.getGlossary(), glossaryFqn, "glossary");
    return new GovernanceSnapshot.GlossaryTermRow(
        source.getId(),
        glossaryFqn,
        fqn,
        parentWithinRoot(fqn),
        source.getName(),
        source.getDescription(),
        source.getSynonyms(),
        relatedTermNames(source.getRelatedTerms()));
  }

  public static GovernanceSnapshot.DomainRow domain(Domain source) {
    if (source == null || blank(source.getFullyQualifiedName())) {
      return null;
    }
    String fqn = source.getFullyQualifiedName();
    return new GovernanceSnapshot.DomainRow(
        source.getId(),
        fqn,
        parentOf(fqn),
        Math.max(0, Fqns.depth(fqn) - 1),
        source.getName(),
        source.getDescription(),
        source.getDomainType() == null ? null : source.getDomainType().getValue());
  }

  public static GovernanceSnapshot.DataProductRow dataProduct(DataProduct source) {
    if (source == null || blank(source.getFullyQualifiedName())) {
      return null;
    }
    // OpenMetadata 2.0 models domains as a list on every entity, but a data
    // product belongs to exactly one; more than that would make
    // `dataProduct.domain` ambiguous in a selector, so it is worth a warning.
    List<EntityReference> domains = source.getDomains();
    String domainFqn = null;
    if (domains != null && !domains.isEmpty()) {
      domainFqn = fqnOf(domains.get(0));
      if (domains.size() > 1) {
        LOG.warn(
            "Data product {} is in {} domains; using {} for domain inheritance.",
            source.getFullyQualifiedName(), domains.size(), domainFqn);
      }
    }
    return new GovernanceSnapshot.DataProductRow(
        source.getId(),
        source.getFullyQualifiedName(),
        source.getName(),
        source.getDescription(),
        domainFqn);
  }

  /**
   * One custom property definition of an entity type.
   *
   * <p>The declared type arrives as an entity reference to OpenMetadata's own
   * type registry — {@code string}, {@code integer}, {@code date-cp},
   * {@code enum}, {@code entityReference} — and an enum additionally carries its
   * permitted values in a free-form config blob.
   */
  public static GovernanceSnapshot.CustomPropertyRow customProperty(
      String entityType, CustomProperty source) {
    if (source == null || blank(source.getName())) {
      return null;
    }
    String dataType = source.getPropertyType() == null ? null : source.getPropertyType().getName();
    List<String> values = List.of();
    boolean multiSelect = false;
    Object config =
        source.getCustomPropertyConfig() == null
            ? null
            : source.getCustomPropertyConfig().getConfig();
    if (config instanceof Map<?, ?> map) {
      values = stringList(map.get("values"));
      multiSelect = Boolean.TRUE.equals(map.get("multiSelect"));
    } else if (config != null) {
      // A plain list is how some property types express their options.
      values = stringList(config);
    }
    return new GovernanceSnapshot.CustomPropertyRow(
        entityType,
        source.getName(),
        source.getDisplayName(),
        source.getDescription(),
        dataType == null ? "string" : dataType,
        values,
        multiSelect);
  }

  /** The enclosing FQN, or null at the top level. Quote-aware through {@link Fqns}. */
  static String parentOf(String fqn) {
    return parentOf(fqn, 2);
  }

  /**
   * The parent <em>within the same container</em>, or null when there is none.
   *
   * <p>A tag and a glossary term are namespaced by their classification or
   * glossary, so {@code PII.Sensitive} has no parent tag — {@code PII} is the
   * classification, recorded separately. Only {@code PII.Sensitive.Financial}
   * has one. Treating the classification as a parent tag would put a row in the
   * tag table for something that is not a tag.
   */
  static String parentWithinRoot(String fqn) {
    return parentOf(fqn, 3);
  }

  private static String parentOf(String fqn, int minSegments) {
    List<String> segments = Fqns.segments(fqn);
    if (segments.size() < minSegments) {
      return null;
    }
    return Fqns.join(segments.subList(0, segments.size() - 1).toArray(new String[0]));
  }

  /** The outermost segment: a tag's classification, or a term's glossary. */
  static String rootOf(String fqn) {
    List<String> segments = Fqns.segments(fqn);
    return segments.isEmpty() || segments.get(0).isEmpty() ? null : segments.get(0);
  }

  private static List<String> relatedTermNames(List<TermRelation> relations) {
    if (relations == null || relations.isEmpty()) {
      return List.of();
    }
    List<String> names = new ArrayList<>(relations.size());
    for (TermRelation relation : relations) {
      String name = relation == null ? null : fqnOf(relation.getTerm());
      if (name != null) {
        names.add(name);
      }
    }
    return names;
  }

  private static List<String> stringList(Object value) {
    if (!(value instanceof Iterable<?> items)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object item : items) {
      if (item != null) {
        out.add(String.valueOf(item));
      }
    }
    return out;
  }

  private static String fqnOf(EntityReference ref) {
    if (ref == null) {
      return null;
    }
    String fqn = ref.getFullyQualifiedName();
    if (!blank(fqn)) {
      return fqn;
    }
    return blank(ref.getName()) ? null : ref.getName();
  }

  private static void warnIfParentDisagrees(
      String fqn, EntityReference declared, String derived, String what) {
    String declaredFqn = fqnOf(declared);
    if (declaredFqn != null && derived != null && !Fqns.equal(declaredFqn, derived)) {
      LOG.warn(
          "OpenMetadata says {} belongs to {} {}, but its FQN implies {}; using the FQN.",
          fqn, what, declaredFqn, derived);
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
