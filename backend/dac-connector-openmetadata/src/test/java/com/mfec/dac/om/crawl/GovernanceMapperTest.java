package com.mfec.dac.om.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.om.client.model.Classification;
import com.mfec.dac.om.client.model.CustomProperty;
import com.mfec.dac.om.client.model.CustomPropertyConfig;
import com.mfec.dac.om.client.model.DataProduct;
import com.mfec.dac.om.client.model.Domain;
import com.mfec.dac.om.client.model.EntityReference;
import com.mfec.dac.om.client.model.Glossary;
import com.mfec.dac.om.client.model.GlossaryTerm;
import com.mfec.dac.om.client.model.Tag;
import com.mfec.dac.om.client.model.TermRelation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GovernanceMapperTest {

  private static EntityReference ref(String fqn) {
    return new EntityReference().fullyQualifiedName(fqn).name(fqn);
  }

  @Nested
  @DisplayName("classifications and tags")
  class Tags {

    @Test
    @DisplayName("carry the flags the policy builder and the engine both need")
    void keepsGovernanceFlags() {
      GovernanceSnapshot.ClassificationRow row =
          GovernanceMapper.classification(
              new Classification()
                  .name("Tier")
                  .fullyQualifiedName("Tier")
                  .mutuallyExclusive(true)
                  .provider(Classification.ProviderEnum.SYSTEM)
                  .disabled(false));

      assertThat(row.mutuallyExclusive()).isTrue();
      assertThat(row.provider()).isEqualTo("system");
      assertThat(row.disabled()).isFalse();
    }

    @Test
    @DisplayName("default to a user-defined, non-exclusive classification")
    void defaultsAreSafe() {
      GovernanceSnapshot.ClassificationRow row =
          GovernanceMapper.classification(new Classification().name("PII").fullyQualifiedName("PII"));

      // An unset mutuallyExclusive must not turn into "only one tag allowed",
      // which would make inheritance drop tags the asset really carries.
      assertThat(row.mutuallyExclusive()).isFalse();
      assertThat(row.provider()).isEqualTo("user");
    }

    @Test
    @DisplayName("put a nested tag in its classification, not under its parent tag")
    void nestedTagKeepsItsClassification() {
      GovernanceSnapshot.TagRow row =
          GovernanceMapper.tag(
              new Tag()
                  .name("Financial")
                  .fullyQualifiedName("PII.Sensitive.Financial")
                  .classification(ref("PII")));

      // `classifications contains 'PII'` has to keep reaching a tag however
      // deeply someone nests it.
      assertThat(row.classificationFqn()).isEqualTo("PII");
      assertThat(row.parentFqn()).isEqualTo("PII.Sensitive");
    }

    @Test
    @DisplayName("leave a top-level tag without a parent")
    void topLevelTagHasNoParent() {
      GovernanceSnapshot.TagRow row =
          GovernanceMapper.tag(new Tag().name("Sensitive").fullyQualifiedName("PII.Sensitive"));

      assertThat(row.parentFqn()).isNull();
      assertThat(row.classificationFqn()).isEqualTo("PII");
    }
  }

  @Nested
  @DisplayName("glossaries and domains")
  class Hierarchies {

    @Test
    @DisplayName("place a term in its glossary and under its parent term")
    void termKeepsItsPlace() {
      GovernanceSnapshot.GlossaryTermRow row =
          GovernanceMapper.glossaryTerm(
              new GlossaryTerm()
                  .name("Credit")
                  .fullyQualifiedName("Finance.Risk.Credit")
                  .glossary(ref("Finance"))
                  .synonyms(List.of("Lending"))
                  .relatedTerms(List.of(new TermRelation().term(ref("Finance.Risk.Market")))));

      assertThat(row.glossaryFqn()).isEqualTo("Finance");
      assertThat(row.parentFqn()).isEqualTo("Finance.Risk");
      // Stored for display; never matched implicitly, or a policy's reach would
      // change the moment someone adds a synonym (FR-2A.3).
      assertThat(row.synonyms()).containsExactly("Lending");
      assertThat(row.relatedTerms()).containsExactly("Finance.Risk.Market");
    }

    @Test
    @DisplayName("leave a first-level term without a parent term")
    void firstLevelTermHasNoParent() {
      GovernanceSnapshot.GlossaryTermRow row =
          GovernanceMapper.glossaryTerm(
              new GlossaryTerm().name("Risk").fullyQualifiedName("Finance.Risk"));

      // Finance is the glossary, not a term; recording it as parent_fqn would
      // put a row in the term table for something that is not a term.
      assertThat(row.glossaryFqn()).isEqualTo("Finance");
      assertThat(row.parentFqn()).isNull();
    }

    @Test
    @DisplayName("give a sub-domain the depth the facet table expands to")
    void subDomainDepth() {
      GovernanceSnapshot.DomainRow deep =
          GovernanceMapper.domain(
              new Domain()
                  .name("Credit")
                  .fullyQualifiedName("Finance.Risk.Credit")
                  .domainType(Domain.DomainTypeEnum.SOURCE_ALIGNED));

      assertThat(deep.depth()).isEqualTo(2);
      assertThat(deep.parentFqn()).isEqualTo("Finance.Risk");
      assertThat(deep.domainType()).isEqualTo("Source-aligned");

      GovernanceSnapshot.DomainRow top =
          GovernanceMapper.domain(new Domain().name("Finance").fullyQualifiedName("Finance"));
      assertThat(top.depth()).isZero();
      assertThat(top.parentFqn()).isNull();
    }

    @Test
    @DisplayName("do not invent a parent from a dot inside a name")
    void quotedSegmentsStayWhole() {
      GovernanceSnapshot.DomainRow row =
          GovernanceMapper.domain(new Domain().name("Sales.EU").fullyQualifiedName("\"Sales.EU\""));

      // Cutting at the raw dot would invent a domain named Sales and hand it
      // every policy written against the real one.
      assertThat(row.parentFqn()).isNull();
      assertThat(row.depth()).isZero();
    }

    @Test
    @DisplayName("attach a data product to the domain it inherits from")
    void dataProductDomain() {
      GovernanceSnapshot.DataProductRow row =
          GovernanceMapper.dataProduct(
              new DataProduct()
                  .name("Customer 360")
                  .fullyQualifiedName("Customer 360")
                  .domains(List.of(ref("Finance.Risk"))));

      assertThat(row.domainFqn()).isEqualTo("Finance.Risk");
    }

    @Test
    @DisplayName("map a glossary to its own row")
    void glossaryRow() {
      GovernanceSnapshot.GlossaryRow row =
          GovernanceMapper.glossary(
              new Glossary().name("Finance").fullyQualifiedName("Finance").description("Terms"));

      assertThat(row.fqn()).isEqualTo("Finance");
      assertThat(row.description()).isEqualTo("Terms");
    }
  }

  @Nested
  @DisplayName("custom property definitions")
  class CustomProperties {

    @Test
    @DisplayName("bring an enum's permitted values along, so the builder can offer a dropdown")
    void enumValues() {
      GovernanceSnapshot.CustomPropertyRow row =
          GovernanceMapper.customProperty(
              "table",
              new CustomProperty()
                  .name("dataResidency")
                  .propertyType(new EntityReference().name("enum"))
                  .customPropertyConfig(
                      new CustomPropertyConfig()
                          .config(Map.of("values", List.of("TH", "SG"), "multiSelect", false))));

      assertThat(row.dataType()).isEqualTo("enum");
      assertThat(row.enumValues()).containsExactly("TH", "SG");
      assertThat(row.multiSelect()).isFalse();
    }

    @Test
    @DisplayName("record multi-select, which decides whether `contains` is even offered")
    void multiSelect() {
      GovernanceSnapshot.CustomPropertyRow row =
          GovernanceMapper.customProperty(
              "table",
              new CustomProperty()
                  .name("regions")
                  .propertyType(new EntityReference().name("enum"))
                  .customPropertyConfig(
                      new CustomPropertyConfig()
                          .config(Map.of("values", List.of("APAC"), "multiSelect", true))));

      assertThat(row.multiSelect()).isTrue();
    }

    @Test
    @DisplayName("fall back to string rather than to null when the type is missing")
    void defaultsToString() {
      GovernanceSnapshot.CustomPropertyRow row =
          GovernanceMapper.customProperty("table", new CustomProperty().name("note"));

      // data_type is NOT NULL in the cache, and a property with no usable type
      // is still better shown as free text than dropped from the builder.
      assertThat(row.dataType()).isEqualTo("string");
      assertThat(row.enumValues()).isEmpty();
    }
  }

  @Test
  @DisplayName("names the classifications that override on inheritance instead of adding")
  void exposesMutuallyExclusiveRoots() {
    GovernanceSnapshot snapshot =
        new GovernanceSnapshot(
            List.of(
                new GovernanceSnapshot.ClassificationRow(
                    null, "Tier", "Tier", null, true, "system", false),
                new GovernanceSnapshot.ClassificationRow(
                    null, "PII", "PII", null, false, "system", false),
                new GovernanceSnapshot.ClassificationRow(
                    null, "Retired", "Retired", null, true, "user", true)),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    // A disabled classification must not keep overriding inherited tags after
    // it has been switched off (FR-2A.3a).
    assertThat(snapshot.mutuallyExclusiveClassifications()).containsExactly("Tier");
  }

  @Test
  @DisplayName("skips an entity with no fully-qualified name rather than caching a blank key")
  void skipsUnusableRows() {
    assertThat(GovernanceMapper.classification(new Classification().name("PII"))).isNull();
    assertThat(GovernanceMapper.tag(null)).isNull();
    assertThat(GovernanceMapper.domain(new Domain().name("Finance"))).isNull();
  }
}
