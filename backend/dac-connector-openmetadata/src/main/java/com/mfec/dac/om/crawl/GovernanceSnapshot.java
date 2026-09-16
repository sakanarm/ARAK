package com.mfec.dac.om.crawl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the governance crawl read from OpenMetadata in one pass.
 *
 * <p>Plain records rather than the generated entities. The generated {@code Tag}
 * carries ninety fields, of which this platform cares about eight; narrowing at
 * the edge means a change to OpenMetadata's spec breaks compilation here, in one
 * mapper, instead of surfacing three layers down as a null.
 *
 * <p>This is a cache, not a second catalog. Nothing here is edited by us —
 * writes go back through OpenMetadata (FR-1.7) — and the rows exist so that a
 * policy decision can still be made and explained when OpenMetadata is down
 * (NFR-3).
 */
public record GovernanceSnapshot(
    List<ClassificationRow> classifications,
    List<TagRow> tags,
    List<GlossaryRow> glossaries,
    List<GlossaryTermRow> terms,
    List<DomainRow> domains,
    List<DataProductRow> dataProducts,
    List<CustomPropertyRow> customProperties) {

  public GovernanceSnapshot {
    classifications = copy(classifications);
    tags = copy(tags);
    glossaries = copy(glossaries);
    terms = copy(terms);
    domains = copy(domains);
    dataProducts = copy(dataProducts);
    customProperties = copy(customProperties);
  }

  public static GovernanceSnapshot empty() {
    return new GovernanceSnapshot(
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  private static <T> List<T> copy(List<T> rows) {
    return rows == null ? List.of() : List.copyOf(rows);
  }

  /**
   * Classifications that permit one tag per asset.
   *
   * <p>Needed by {@link com.mfec.dac.om.facet.FacetInheritance}: within one of
   * these a nearer tag replaces an inherited one instead of adding to it, so a
   * table marked {@code Tier.Tier3} does not also count as {@code Tier.Tier1}
   * because its schema said so (FR-2A.3a).
   */
  public Set<String> mutuallyExclusiveClassifications() {
    Set<String> roots = new HashSet<>();
    for (ClassificationRow row : classifications) {
      if (row.mutuallyExclusive() && !row.disabled()) {
        roots.add(row.fqn());
      }
    }
    return roots;
  }

  /**
   * A classification, which is the namespace a tag lives in.
   *
   * @param provider {@code system} for the classifications OpenMetadata ships
   *                 (Tier, Certification, PII) and {@code user} for the ones the
   *                 organisation defined. A system classification must not be
   *                 treated as editable.
   * @param disabled a disabled classification is still cached, so history reads
   *                 correctly, but must not be enforced (FR-2A.3a).
   */
  public record ClassificationRow(
      UUID omId,
      String fqn,
      String name,
      String description,
      boolean mutuallyExclusive,
      String provider,
      boolean disabled) {}

  /** One tag inside a classification; tags nest, so {@code parentFqn} may be set. */
  public record TagRow(
      UUID omId,
      String classificationFqn,
      String fqn,
      String parentFqn,
      String name,
      String description,
      boolean disabled) {}

  public record GlossaryRow(UUID omId, String fqn, String name, String description) {}

  /**
   * A glossary term.
   *
   * @param synonyms     kept for display only
   * @param relatedTerms likewise — neither is ever matched implicitly, because a
   *                     policy whose reach changes when somebody adds a synonym
   *                     is a policy nobody can predict (FR-2A.3)
   */
  public record GlossaryTermRow(
      UUID omId,
      String glossaryFqn,
      String fqn,
      String parentFqn,
      String name,
      String description,
      List<String> synonyms,
      List<String> relatedTerms) {

    public GlossaryTermRow {
      synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
      relatedTerms = relatedTerms == null ? List.of() : List.copyOf(relatedTerms);
    }
  }

  /**
   * A domain or sub-domain.
   *
   * @param depth 0 for a top-level domain, 2 for {@code Finance.Risk.Credit}
   */
  public record DomainRow(
      UUID omId,
      String fqn,
      String parentFqn,
      int depth,
      String name,
      String description,
      String domainType) {}

  /** A data product, which is flat but belongs to a domain (FR-2A.2a). */
  public record DataProductRow(
      UUID omId, String fqn, String name, String description, String domainFqn) {}

  /**
   * The definition of a custom property — not its value.
   *
   * <p>The policy builder needs to know that {@code dataResidency} is an enum of
   * three countries before it can offer a dropdown rather than a free-text box,
   * and the engine needs the declared type before it can decide whether
   * {@code >=} means a number or a string (FR-1.9).
   */
  public record CustomPropertyRow(
      String entityType,
      String name,
      String displayName,
      String description,
      String dataType,
      List<String> enumValues,
      boolean multiSelect) {

    public CustomPropertyRow {
      enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }
  }
}
