package com.mfec.dac.om.facet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FacetInheritanceTest {

  private static ExtractedFacet tagOn(String target, String value) {
    return ExtractedFacet.direct(target, FacetType.TAGS, value);
  }

  @Test
  @DisplayName("a column picks up what its table was tagged with")
  void inheritsDownwards() {
    List<ExtractedFacet> effective =
        FacetInheritance.effective(
            "svc.db.dbo.customer.email",
            List.of(),
            List.of(
                new FacetInheritance.Level(
                    "svc.db.dbo.customer", List.of(tagOn("svc.db.dbo.customer", "PII.Sensitive")))),
            Set.of());

    assertThat(effective)
        .extracting(
            ExtractedFacet::targetFqn,
            ExtractedFacet::facetFqn,
            ExtractedFacet::direct,
            ExtractedFacet::inheritedFrom)
        .containsExactly(
            tuple("svc.db.dbo.customer.email", "PII.Sensitive", false, "svc.db.dbo.customer"));
  }

  @Test
  @DisplayName("records which level a facet came from, so the UI can explain it")
  void namesTheSource() {
    List<ExtractedFacet> effective =
        FacetInheritance.effective(
            "svc.db.dbo.customer",
            List.of(),
            List.of(
                new FacetInheritance.Level("svc.db.dbo", List.of(tagOn("svc.db.dbo", "PII.Sensitive"))),
                new FacetInheritance.Level("svc.db", List.of(tagOn("svc.db", "Sensitivity.High")))),
            Set.of());

    assertThat(effective)
        .extracting(ExtractedFacet::facetFqn, ExtractedFacet::inheritedFrom)
        .containsExactly(
            tuple("PII.Sensitive", "svc.db.dbo"), tuple("Sensitivity.High", "svc.db"));
  }

  @Test
  @DisplayName("keeps the asset's own row rather than the inherited copy")
  void ownRowWins() {
    List<ExtractedFacet> effective =
        FacetInheritance.effective(
            "svc.db.dbo.customer",
            List.of(tagOn("svc.db.dbo.customer", "PII.Sensitive")),
            List.of(
                new FacetInheritance.Level("svc.db.dbo", List.of(tagOn("svc.db.dbo", "PII.Sensitive")))),
            Set.of());

    assertThat(effective).singleElement().satisfies(f -> {
      assertThat(f.direct()).isTrue();
      assertThat(f.inheritedFrom()).isNull();
    });
  }

  @Test
  @DisplayName("a nearer tag replaces the inherited one inside an exclusive classification")
  void exclusiveClassificationOverrides() {
    List<ExtractedFacet> effective =
        FacetInheritance.effective(
            "svc.db.dbo.customer",
            List.of(tagOn("svc.db.dbo.customer", "Tier.Tier3")),
            List.of(new FacetInheritance.Level("svc.db.dbo", List.of(tagOn("svc.db.dbo", "Tier.Tier1")))),
            Set.of("Tier"));

    // Inheriting both would leave the table in two tiers at once, which is
    // precisely what mutuallyExclusive says cannot happen.
    assertThat(effective)
        .extracting(ExtractedFacet::facetFqn)
        .containsExactly("Tier.Tier3")
        .doesNotContain("Tier.Tier1");
  }

  @Test
  @DisplayName("an override in one classification does not block another")
  void overrideIsScopedToItsClassification() {
    List<ExtractedFacet> effective =
        FacetInheritance.effective(
            "svc.db.dbo.customer",
            List.of(tagOn("svc.db.dbo.customer", "Tier.Tier3")),
            List.of(
                new FacetInheritance.Level(
                    "svc.db.dbo",
                    List.of(tagOn("svc.db.dbo", "Tier.Tier1"), tagOn("svc.db.dbo", "PII.Sensitive")))),
            Set.of("Tier"));

    assertThat(effective)
        .extracting(ExtractedFacet::facetFqn)
        .containsExactlyInAnyOrder("Tier.Tier3", "PII.Sensitive");
  }

  @Test
  @DisplayName("the nearest ancestor that speaks settles an exclusive classification")
  void nearestAncestorWins() {
    List<ExtractedFacet> effective =
        FacetInheritance.effective(
            "svc.db.dbo.customer",
            List.of(),
            List.of(
                new FacetInheritance.Level("svc.db.dbo", List.of(tagOn("svc.db.dbo", "Tier.Tier2"))),
                new FacetInheritance.Level("svc.db", List.of(tagOn("svc.db", "Tier.Tier1")))),
            Set.of("Tier"));

    assertThat(effective).extracting(ExtractedFacet::facetFqn).containsExactly("Tier.Tier2");
  }

  @Test
  @DisplayName("an overridden tag takes its classification and tier rows with it")
  void overrideCoversRestatements() {
    ExtractedFacet inheritedTier =
        new ExtractedFacet("svc.db.dbo", FacetType.TIER, "Tier1", null, 0, true, null, null, null);
    ExtractedFacet inheritedClassification =
        new ExtractedFacet(
            "svc.db.dbo", FacetType.CLASSIFICATIONS, "Tier", null, 0, false, null, null, null);

    List<ExtractedFacet> effective =
        FacetInheritance.effective(
            "svc.db.dbo.customer",
            List.of(tagOn("svc.db.dbo.customer", "Tier.Tier3")),
            List.of(
                new FacetInheritance.Level(
                    "svc.db.dbo",
                    List.of(tagOn("svc.db.dbo", "Tier.Tier1"), inheritedTier, inheritedClassification))),
            Set.of("Tier"));

    // Leaving the tier row behind would let `tier == 'Tier1'` match a table
    // that the tag override has already moved to Tier3.
    assertThat(effective).extracting(ExtractedFacet::facetFqn).containsExactly("Tier.Tier3");
  }
}
