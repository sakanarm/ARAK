package com.mfec.dac.om.facet;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.om.client.model.AssetCertification;
import com.mfec.dac.om.client.model.Column;
import com.mfec.dac.om.client.model.EntityReference;
import com.mfec.dac.om.client.model.Table;
import com.mfec.dac.om.client.model.TagLabel;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FacetExtractorTest {

  private static TagLabel tag(String fqn) {
    return new TagLabel()
        .tagFQN(fqn)
        .source(TagLabel.SourceEnum.CLASSIFICATION)
        .labelType(TagLabel.LabelTypeEnum.MANUAL)
        .state(TagLabel.StateEnum.CONFIRMED);
  }

  private static TagLabel term(String fqn) {
    return new TagLabel()
        .tagFQN(fqn)
        .source(TagLabel.SourceEnum.GLOSSARY)
        .labelType(TagLabel.LabelTypeEnum.MANUAL)
        .state(TagLabel.StateEnum.CONFIRMED);
  }

  private static EntityReference ref(String fqn) {
    return new EntityReference().fullyQualifiedName(fqn).name(fqn);
  }

  private static List<String> valuesOf(List<ExtractedFacet> facets, FacetType type) {
    return facets.stream().filter(f -> f.facetType() == type).map(ExtractedFacet::facetFqn).toList();
  }

  @Nested
  @DisplayName("classification tags and glossary terms")
  class TagsAndTerms {

    @Test
    @DisplayName("arrive in one array and are split into separate facets")
    void splitsBySource() {
      List<ExtractedFacet> facets =
          FacetExtractor.fromTagLabels(
              "svc.db.dbo.customer", List.of(tag("PII.Sensitive"), term("Finance.CustomerIdentity")));

      assertThat(valuesOf(facets, FacetType.TAGS)).contains("PII.Sensitive");
      assertThat(valuesOf(facets, FacetType.CLASSIFICATIONS)).contains("PII");
      assertThat(valuesOf(facets, FacetType.TERMS)).contains("Finance.CustomerIdentity");
      assertThat(valuesOf(facets, FacetType.GLOSSARIES)).contains("Finance");

      // The distinction is the point: a glossary term must never answer a
      // classification selector, however similarly the two are spelled.
      assertThat(valuesOf(facets, FacetType.TAGS)).doesNotContain("Finance.CustomerIdentity");
      assertThat(valuesOf(facets, FacetType.TERMS)).doesNotContain("PII.Sensitive");
    }

    @Test
    @DisplayName("carry OpenMetadata state so a guess does not enforce on its own")
    void keepsState() {
      TagLabel suggested =
          new TagLabel()
              .tagFQN("PII.Sensitive")
              .source(TagLabel.SourceEnum.CLASSIFICATION)
              .labelType(TagLabel.LabelTypeEnum.AUTOMATED)
              .state(TagLabel.StateEnum.SUGGESTED);

      ExtractedFacet facet =
          FacetExtractor.fromTagLabels("t", List.of(suggested)).stream()
              .filter(f -> f.facetType() == FacetType.TAGS && f.depth() == 0)
              .findFirst()
              .orElseThrow();

      assertThat(facet.confirmed()).isFalse();
      assertThat(facet.omState()).isEqualTo("Suggested");
    }

    @Test
    @DisplayName("expose tier both as a tag and as tier, because policies use both")
    void tierIsReachableTwoWays() {
      List<ExtractedFacet> facets = FacetExtractor.fromTagLabels("t", List.of(tag("Tier.Tier1")));

      assertThat(valuesOf(facets, FacetType.TAGS)).contains("Tier.Tier1");
      assertThat(valuesOf(facets, FacetType.TIER)).containsExactly("Tier1");
    }
  }

  @Nested
  @DisplayName("ancestor expansion")
  class Ancestors {

    @Test
    @DisplayName("writes every level of a sub-domain so `contains` is an index lookup")
    void expandsSubDomains() {
      List<ExtractedFacet> facets =
          FacetExtractor.references(
              "t", FacetType.DOMAINS, List.of(ref("Finance.Risk.Credit")), true);

      assertThat(valuesOf(facets, FacetType.DOMAINS))
          .containsExactly("Finance.Risk.Credit", "Finance.Risk", "Finance");

      assertThat(facets)
          .extracting(ExtractedFacet::facetFqn, ExtractedFacet::depth, ExtractedFacet::direct)
          .containsExactly(
              org.assertj.core.api.Assertions.tuple("Finance.Risk.Credit", 0, true),
              org.assertj.core.api.Assertions.tuple("Finance.Risk", 1, false),
              org.assertj.core.api.Assertions.tuple("Finance", 2, false));
    }

    @Test
    @DisplayName("does not split a name that merely contains a dot")
    void respectsQuotedSegments() {
      List<ExtractedFacet> facets =
          FacetExtractor.references("t", FacetType.DOMAINS, List.of(ref("\"Sales.EU\"")), true);

      // Splitting on the raw dot would invent a parent domain named Sales and
      // hand every Sales policy an asset that was never in it.
      assertThat(valuesOf(facets, FacetType.DOMAINS)).containsExactly("\"Sales.EU\"");
    }

    @Test
    @DisplayName("leaves data products flat, since they are not a hierarchy")
    void productsAreNotExpanded() {
      List<ExtractedFacet> facets =
          FacetExtractor.references(
              "t", FacetType.DATA_PRODUCTS, List.of(ref("Customer.360")), false);

      assertThat(valuesOf(facets, FacetType.DATA_PRODUCTS)).containsExactly("Customer.360");
    }
  }

  @Nested
  @DisplayName("custom properties")
  class CustomProperties {

    @Test
    @DisplayName("become the asset half of an ABAC comparison")
    void readsExtension() {
      List<ExtractedFacet> facets =
          FacetExtractor.customProperties("t", Map.of("dataResidency", "TH"));

      assertThat(facets).singleElement().satisfies(f -> {
        assertThat(f.facetType()).isEqualTo(FacetType.CUSTOM_PROPERTY);
        assertThat(f.property()).isEqualTo("dataResidency");
        assertThat(f.facetFqn()).isEqualTo("TH");
      });
    }

    @Test
    @DisplayName("give a multi-select one row per value, not one rendered list")
    void expandsMultiSelect() {
      List<ExtractedFacet> facets =
          FacetExtractor.customProperties("t", Map.of("regions", List.of("APAC", "EMEA")));

      assertThat(facets).extracting(ExtractedFacet::facetFqn).containsExactly("APAC", "EMEA");
      assertThat(facets).allMatch(f -> "regions".equals(f.property()));
    }

    @Test
    @DisplayName("reduce an entity reference to the FQN a policy can compare")
    void flattensEntityReference() {
      List<ExtractedFacet> facets =
          FacetExtractor.customProperties(
              "t", Map.of("steward", Map.of("id", "abc", "fullyQualifiedName", "team.Finance")));

      assertThat(facets).singleElement().extracting(ExtractedFacet::facetFqn).isEqualTo("team.Finance");
    }
  }

  @Nested
  @DisplayName("a whole table")
  class WholeTable {

    @Test
    @DisplayName("yields every governance facet in one pass")
    void extractsEverything() {
      Table table =
          new Table()
              .fullyQualifiedName("prod-mssql.SalesDB.dbo.customer")
              .tags(List.of(tag("PII.Sensitive"), term("Finance.CustomerIdentity")))
              .domains(List.of(ref("Finance.Risk.Credit")))
              .dataProducts(List.of(ref("Customer 360")))
              .owners(List.of(ref("Finance")))
              .certification(new AssetCertification().tagLabel(tag("Certification.Gold")))
              .extension(Map.of("dataResidency", "TH"));

      List<ExtractedFacet> facets = FacetExtractor.fromTable(table);

      assertThat(valuesOf(facets, FacetType.TAGS)).contains("PII.Sensitive", "PII");
      assertThat(valuesOf(facets, FacetType.CLASSIFICATIONS)).contains("PII");
      assertThat(valuesOf(facets, FacetType.TERMS)).contains("Finance.CustomerIdentity");
      assertThat(valuesOf(facets, FacetType.DOMAINS))
          .contains("Finance.Risk.Credit", "Finance.Risk", "Finance");
      assertThat(valuesOf(facets, FacetType.DATA_PRODUCTS)).containsExactly("Customer 360");
      assertThat(valuesOf(facets, FacetType.OWNERS)).containsExactly("Finance");
      assertThat(valuesOf(facets, FacetType.CERTIFICATION)).containsExactly("Gold");
      assertThat(valuesOf(facets, FacetType.CUSTOM_PROPERTY)).containsExactly("TH");
      assertThat(facets).allMatch(f -> "prod-mssql.SalesDB.dbo.customer".equals(f.targetFqn()));
    }

    @Test
    @DisplayName("does not emit physical facets, which the engine derives itself")
    void leavesPhysicalFacetsAlone() {
      Table table =
          new Table().fullyQualifiedName("prod-mssql.SalesDB.dbo.customer").tags(List.of(tag("PII.Sensitive")));

      assertThat(FacetExtractor.fromTable(table))
          .extracting(ExtractedFacet::facetType)
          .doesNotContain(
              FacetType.SERVICE, FacetType.DATABASE, FacetType.SCHEMA, FacetType.TABLE,
              FacetType.COLUMN_NAME, FacetType.DATA_TYPE);
    }

    @Test
    @DisplayName("keeps the direct row when a value is also reached as an ancestor")
    void prefersTheDirectRow() {
      Table table =
          new Table()
              .fullyQualifiedName("t")
              .domains(List.of(ref("Finance"), ref("Finance.Risk")));

      List<ExtractedFacet> finance =
          FacetExtractor.fromTable(table).stream()
              .filter(f -> f.facetType() == FacetType.DOMAINS && "Finance".equals(f.facetFqn()))
              .toList();

      assertThat(finance).singleElement().satisfies(f -> {
        assertThat(f.direct()).isTrue();
        assertThat(f.depth()).isZero();
      });
    }
  }

  @Test
  @DisplayName("a column carries its own tags")
  void columnTags() {
    Column column =
        new Column()
            .fullyQualifiedName("prod-mssql.SalesDB.dbo.customer.email")
            .tags(List.of(tag("PII.Sensitive")));

    assertThat(valuesOf(FacetExtractor.fromColumn(column), FacetType.TAGS))
        .contains("PII.Sensitive");
  }
}
