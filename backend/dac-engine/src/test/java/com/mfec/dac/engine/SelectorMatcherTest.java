package com.mfec.dac.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.schema.api.ResolvedRowPredicate.FacetOperator;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SelectorMatcherTest {

  private static AssetSelector cond(FacetType facet, FacetOperator op, Object value) {
    return new AssetSelector()
        .withCondition(
            new FacetCondition().withFacet(facet).withOperator(op).withValue(value));
  }

  /** A customer table in the third level of the Finance domain, tagged PII.Sensitive. */
  private static AssetContext customer() {
    return AssetContext.of("prod-mssql.SalesDB.dbo.customer")
        .physicalFromFqn()
        .hierarchicalFacet(FacetType.DOMAINS, "Finance.Risk.Credit")
        .hierarchicalFacet(FacetType.TAGS, "PII.Sensitive")
        .property("dataResidency", "TH")
        .column(
            ColumnContext.named("email")
                .fqn("prod-mssql.SalesDB.dbo.customer.email")
                .dataType("VARCHAR")
                .facet(FacetType.TAGS, "PII", "PII.Sensitive"))
        .column(ColumnContext.named("branch_code").dataType("VARCHAR"))
        .build();
  }

  @Test
  @DisplayName("an empty selector matches nothing, because a half-written policy binds to nothing")
  void emptySelectorMatchesNothing() {
    assertThat(SelectorMatcher.matches(null, customer())).isFalse();
    assertThat(SelectorMatcher.matches(new AssetSelector(), customer())).isFalse();
    assertThat(SelectorMatcher.matches(new AssetSelector().withOr(List.of()), customer()))
        .isFalse();
  }

  @Test
  @DisplayName("contains covers the sub-domain; eq covers only the level named")
  void containsVersusEq() {
    assertThat(SelectorMatcher.matches(cond(FacetType.DOMAINS, FacetOperator.CONTAINS, "Finance"),
            customer()))
        .isTrue();
    assertThat(
            SelectorMatcher.matches(
                cond(FacetType.DOMAINS, FacetOperator.EQ, "Finance.Risk.Credit"), customer()))
        .isTrue();
    // The test the requirement calls out by name: a policy scoped with eq to the
    // top level must not reach an asset that lives three levels down.
    assertThat(SelectorMatcher.matches(cond(FacetType.DOMAINS, FacetOperator.EQ, "Finance"),
            customer()))
        .isFalse();
  }

  @Test
  @DisplayName("eq still matches an asset bound directly to that level")
  void eqMatchesADirectBinding() {
    // The counterpart to the case above: eq excludes the ancestor rows that
    // expansion generated, not the binding the steward actually made.
    AssetContext topLevel =
        AssetContext.of("prod-mssql.SalesDB.dbo.customer")
            .hierarchicalFacet(FacetType.DOMAINS, "Finance")
            .build();
    assertThat(SelectorMatcher.matches(cond(FacetType.DOMAINS, FacetOperator.EQ, "Finance"),
            topLevel))
        .isTrue();
  }

  @Test
  @DisplayName("a physical facet accepts either its leaf or its full FQN")
  void physicalFacetsAcceptEitherSpelling() {
    assertThat(SelectorMatcher.matches(cond(FacetType.SCHEMA, FacetOperator.EQ, "dbo"), customer()))
        .isTrue();
    assertThat(
            SelectorMatcher.matches(
                cond(FacetType.SCHEMA, FacetOperator.EQ, "prod-mssql.SalesDB.dbo"), customer()))
        .isTrue();
  }

  @Test
  @DisplayName("condition and branches are ANDed; or children are alternatives")
  void booleanComposition() {
    AssetSelector schemaAndTag =
        cond(FacetType.SCHEMA, FacetOperator.EQ, "dbo")
            .withAnd(List.of(cond(FacetType.TAGS, FacetOperator.CONTAINS, "PII")));
    assertThat(SelectorMatcher.matches(schemaAndTag, customer())).isTrue();

    AssetSelector schemaAndMissingTag =
        cond(FacetType.SCHEMA, FacetOperator.EQ, "dbo")
            .withAnd(List.of(cond(FacetType.TAGS, FacetOperator.CONTAINS, "Confidential")));
    assertThat(SelectorMatcher.matches(schemaAndMissingTag, customer())).isFalse();

    AssetSelector eitherTag =
        new AssetSelector()
            .withOr(
                List.of(
                    cond(FacetType.TAGS, FacetOperator.CONTAINS, "Confidential"),
                    cond(FacetType.TAGS, FacetOperator.CONTAINS, "PII")));
    assertThat(SelectorMatcher.matches(eitherTag, customer())).isTrue();
  }

  @Test
  @DisplayName("not inverts, and composes with a sibling condition")
  void negation() {
    AssetSelector piiButNotPublic =
        cond(FacetType.TAGS, FacetOperator.CONTAINS, "PII")
            .withNot(cond(FacetType.TERMS, FacetOperator.CONTAINS, "Public.Approved"));
    assertThat(SelectorMatcher.matches(piiButNotPublic, customer())).isTrue();

    AssetSelector notPii = new AssetSelector().withNot(cond(FacetType.TAGS,
        FacetOperator.CONTAINS, "PII"));
    assertThat(SelectorMatcher.matches(notPii, customer())).isFalse();
  }

  @Test
  @DisplayName("a suggested label does not enforce unless the policy opts in")
  void suggestedLabelsAreExcludedByDefault() {
    AssetContext guessed =
        AssetContext.of("prod-mssql.SalesDB.dbo.customer")
            .facet(FacetType.TAGS, FacetValue.suggested("PII.Sensitive"))
            .build();

    FacetCondition strict =
        new FacetCondition()
            .withFacet(FacetType.TAGS)
            .withOperator(FacetOperator.CONTAINS)
            .withValue("PII");
    assertThat(SelectorMatcher.matches(new AssetSelector().withCondition(strict), guessed))
        .isFalse();

    assertThat(
            SelectorMatcher.matches(
                new AssetSelector()
                    .withCondition(
                        new FacetCondition()
                            .withFacet(FacetType.TAGS)
                            .withOperator(FacetOperator.CONTAINS)
                            .withValue("PII")
                            .withIncludeSuggested(true)),
                guessed))
        .isTrue();
  }

  @Test
  @DisplayName("the same matcher selects columns, by name, type or tag")
  void columnSelection() {
    ColumnContext email =
        ColumnContext.named("email")
            .fqn("prod-mssql.SalesDB.dbo.customer.email")
            .dataType("VARCHAR")
            .facet(FacetType.TAGS, "PII.Sensitive")
            .build();
    ColumnContext branch = ColumnContext.named("branch_code").dataType("VARCHAR").build();

    AssetSelector byTag = cond(FacetType.TAGS, FacetOperator.CONTAINS, "PII");
    assertThat(SelectorMatcher.matches(byTag, email)).isTrue();
    assertThat(SelectorMatcher.matches(byTag, branch)).isFalse();

    AssetSelector byName = cond(FacetType.COLUMN_NAME, FacetOperator.EQ, "email");
    assertThat(SelectorMatcher.matches(byName, email)).isTrue();
    assertThat(SelectorMatcher.matches(byName, branch)).isFalse();

    AssetSelector byType = cond(FacetType.DATA_TYPE, FacetOperator.EQ, "VARCHAR");
    assertThat(SelectorMatcher.matches(byType, branch)).isTrue();
  }

  @Test
  @DisplayName("a custom property compares as a number when both sides are numeric")
  void customProperty() {
    ColumnContext scored = ColumnContext.named("salary").property("sensitivityScore", 9).build();
    AssetSelector highScore =
        new AssetSelector()
            .withCondition(
                new FacetCondition()
                    .withFacet(FacetType.CUSTOM_PROPERTY)
                    .withProperty("sensitivityScore")
                    .withOperator(FacetOperator.GTE)
                    .withValue(8));
    assertThat(SelectorMatcher.matches(highScore, scored)).isTrue();

    ColumnContext low = ColumnContext.named("nickname").property("sensitivityScore", 2).build();
    assertThat(SelectorMatcher.matches(highScore, low)).isFalse();
  }

  @Test
  @DisplayName("a condition missing its facet or operator selects nothing")
  void malformedConditionSelectsNothing() {
    AssetSelector noOperator =
        new AssetSelector().withCondition(new FacetCondition().withFacet(FacetType.TAGS));
    assertThat(SelectorMatcher.matches(noOperator, customer())).isFalse();
  }
}
