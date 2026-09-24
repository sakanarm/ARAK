package com.mfec.dac.om.facet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What crosses from one asset to the one below it, and what does not.
 *
 * <p>Split in two on purpose. The first half is the promise that inheritance
 * still works for the facets OpenMetadata itself inherits; the second is the
 * promise that the tag family stays exactly where a steward put it, which is
 * the bug these tests were rewritten for.
 */
class FacetInheritanceTest {

  private static ExtractedFacet tagOn(String target, String value) {
    return ExtractedFacet.direct(target, FacetType.TAGS, value);
  }

  private static ExtractedFacet domainOn(String target, String value) {
    return ExtractedFacet.direct(target, FacetType.DOMAINS, value);
  }

  @Nested
  @DisplayName("what OpenMetadata inherits, we inherit")
  class Inherited {

    @Test
    @DisplayName("a column picks up the domain its table is in")
    void inheritsDownwards() {
      List<ExtractedFacet> effective =
          FacetInheritance.effective(
              "svc.db.dbo.customer.email",
              List.of(),
              List.of(
                  new FacetInheritance.Level(
                      "svc.db.dbo.customer",
                      List.of(domainOn("svc.db.dbo.customer", "Finance.Risk")))),
              Set.of());

      assertThat(effective)
          .extracting(
              ExtractedFacet::targetFqn,
              ExtractedFacet::facetFqn,
              ExtractedFacet::direct,
              ExtractedFacet::inheritedFrom)
          .containsExactly(
              tuple("svc.db.dbo.customer.email", "Finance.Risk", false, "svc.db.dbo.customer"));
    }

    @Test
    @DisplayName("records which level a facet came from, so the UI can explain it")
    void namesTheSource() {
      List<ExtractedFacet> effective =
          FacetInheritance.effective(
              "svc.db.dbo.customer",
              List.of(),
              List.of(
                  new FacetInheritance.Level(
                      "svc.db.dbo", List.of(domainOn("svc.db.dbo", "Finance.Risk"))),
                  new FacetInheritance.Level(
                      "svc.db",
                      List.of(
                          ExtractedFacet.direct("svc.db", FacetType.OWNERS, "pitchayuk")))),
              Set.of());

      assertThat(effective)
          .extracting(ExtractedFacet::facetFqn, ExtractedFacet::inheritedFrom)
          .containsExactly(tuple("Finance.Risk", "svc.db.dbo"), tuple("pitchayuk", "svc.db"));
    }

    @Test
    @DisplayName("the nearest level that says it is the one named")
    void nearestAncestorWins() {
      List<ExtractedFacet> effective =
          FacetInheritance.effective(
              "svc.db.dbo.customer",
              List.of(),
              List.of(
                  new FacetInheritance.Level(
                      "svc.db.dbo", List.of(domainOn("svc.db.dbo", "Finance"))),
                  new FacetInheritance.Level("svc.db", List.of(domainOn("svc.db", "Finance")))),
              Set.of());

      // One row, and it blames the schema rather than the database: the point
      // of inheritance here is the explanation, and the useful explanation is
      // the closest one.
      assertThat(effective)
          .singleElement()
          .satisfies(f -> assertThat(f.inheritedFrom()).isEqualTo("svc.db.dbo"));
    }

    @Test
    @DisplayName("keeps the asset's own row rather than the inherited copy")
    void ownRowWins() {
      List<ExtractedFacet> effective =
          FacetInheritance.effective(
              "svc.db.dbo.customer",
              List.of(domainOn("svc.db.dbo.customer", "Finance.Risk")),
              List.of(
                  new FacetInheritance.Level(
                      "svc.db.dbo", List.of(domainOn("svc.db.dbo", "Finance.Risk")))),
              Set.of());

      assertThat(effective)
          .singleElement()
          .satisfies(
              f -> {
                assertThat(f.direct()).isTrue();
                assertThat(f.inheritedFrom()).isNull();
              });
    }

    @Test
    @DisplayName("a custom property reaches the column, which cannot hold one itself")
    void customPropertiesReachColumns() {
      ExtractedFacet residency =
          new ExtractedFacet(
              "svc.db.dbo.customer",
              FacetType.CUSTOM_PROPERTY,
              "TH",
              "dataResidency",
              0,
              true,
              null,
              null,
              null);

      List<ExtractedFacet> effective =
          FacetInheritance.effective(
              "svc.db.dbo.customer.email",
              List.of(),
              List.of(new FacetInheritance.Level("svc.db.dbo.customer", List.of(residency))),
              Set.of());

      // Without this, `user.country == asset.prop('dataResidency')` could never
      // be written against a column, because OpenMetadata has nowhere to put a
      // custom property on one.
      assertThat(effective)
          .extracting(ExtractedFacet::property, ExtractedFacet::facetFqn)
          .containsExactly(tuple("dataResidency", "TH"));
    }
  }

  @Nested
  @DisplayName("what OpenMetadata does not inherit, we do not invent")
  class NotInherited {

    @Test
    @DisplayName("a table's tag does not land on its columns")
    void tagsDoNotDescend() {
      List<ExtractedFacet> effective =
          FacetInheritance.effective(
              "svc.db.dbo.customer.created_at",
              List.of(),
              List.of(
                  new FacetInheritance.Level(
                      "svc.db.dbo.customer",
                      List.of(tagOn("svc.db.dbo.customer", "PII.Sensitive")))),
              Set.of());

      // The bug this replaced: every column of a table tagged PII.Sensitive
      // came back tagged PII.Sensitive, so "mask every column where tags
      // contains PII" nulled out created_at and id along with the real ones.
      assertThat(effective).isEmpty();
    }

    @Test
    @DisplayName("a column keeps its own tag and only its own")
    void ownColumnTagSurvivesAlone() {
      List<ExtractedFacet> effective =
          FacetInheritance.effective(
              "svc.db.dbo.customer.id",
              List.of(tagOn("svc.db.dbo.customer.id", "PII.NonSensitive")),
              List.of(
                  new FacetInheritance.Level(
                      "svc.db.dbo.customer",
                      List.of(
                          tagOn("svc.db.dbo.customer", "PII.Sensitive"),
                          tagOn("svc.db.dbo.customer", "Tier.Tier2")))),
              Set.of());

      assertThat(effective)
          .extracting(ExtractedFacet::facetFqn)
          .containsExactly("PII.NonSensitive");
    }

    @Test
    @DisplayName("tier, classification and glossary term stay where they were bound")
    void theRestOfTheTagFamilyStaysPut() {
      ExtractedFacet tier =
          new ExtractedFacet(
              "svc.db.dbo.customer", FacetType.TIER, "Tier2", null, 0, true, null, null, null);
      ExtractedFacet classification =
          new ExtractedFacet(
              "svc.db.dbo.customer",
              FacetType.CLASSIFICATIONS,
              "PII",
              null,
              0,
              false,
              null,
              null,
              null);
      ExtractedFacet term =
          ExtractedFacet.direct(
              "svc.db.dbo.customer", FacetType.TERMS, "Finance.CustomerIdentity");

      List<ExtractedFacet> effective =
          FacetInheritance.effective(
              "svc.db.dbo.customer.email",
              List.of(),
              List.of(
                  new FacetInheritance.Level(
                      "svc.db.dbo.customer", List.of(tier, classification, term))),
              Set.of());

      assertThat(effective).isEmpty();
    }

    @Test
    @DisplayName("a schema's tag does not land on its tables either")
    void tagsDoNotDescendBetweenAssets() {
      List<ExtractedFacet> effective =
          FacetInheritance.effective(
              "svc.db.dbo.customer",
              List.of(),
              List.of(
                  new FacetInheritance.Level("svc.db.dbo", List.of(tagOn("svc.db.dbo", "PII"))),
                  new FacetInheritance.Level(
                      "svc.db", List.of(domainOn("svc.db", "Finance")))),
              Set.of());

      // The rule is about the facet type, not about how far down the chain the
      // asset sits: the schema's tag is refused at the table, while the
      // database's domain still arrives.
      assertThat(effective)
          .extracting(ExtractedFacet::facetType, ExtractedFacet::facetFqn)
          .containsExactly(tuple(FacetType.DOMAINS, "Finance"));
    }
  }
}
