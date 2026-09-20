package com.mfec.dac.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.schema.entity.policy.FacetCondition;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The cross-side comparisons from the plan, and the ways they are allowed to
 * fail.
 *
 * <p>Most of these assert on an expression the requirement document names
 * verbatim, because those are the sentences the feature was asked for in.
 */
class PolicyExpressionEvaluatorTest {

  private final PolicyExpressionEvaluator evaluator = new PolicyExpressionEvaluator();

  private static final RequestContext NOW = RequestContext.at(Instant.parse("2026-09-20T03:00:00Z"));

  private static Principal user(String id) {
    return Principal.withId(id)
        .email(id + "@mfec.co.th")
        .teams("Finance")
        .attribute("department", "FINANCE")
        .attribute("country", "TH")
        .attribute("clearance", "L2")
        .attribute("branch", "BKK-01")
        .build();
  }

  private static AssetContext customer() {
    return AssetContext.of("demo-pg.salesdb.sales.customer")
        .physicalFromFqn()
        .hierarchicalFacet(FacetCondition.FacetType.DOMAINS, "Finance.Risk.Credit")
        .facet(FacetCondition.FacetType.OWNERS, "Finance")
        .facet(FacetCondition.FacetType.TIER, "Tier.Tier1")
        .property("dataResidency", "TH")
        .build();
  }

  private ExpressionEvaluator.Result eval(String expression)
      throws ExpressionUnavailableException {
    return evaluator.evaluate(expression, user("analyst_a"), customer(), NOW);
  }

  @Test
  void comparesAUserAttributeWithACustomPropertyOfTheAsset() throws Exception {
    assertThat(eval("user.country == asset.prop('dataResidency')"))
        .isEqualTo(ExpressionEvaluator.Result.TRUE);
  }

  @Test
  void aUserFromAnotherCountryDoesNotMatchTheResidencyOfTheData() throws Exception {
    Principal offshore =
        Principal.withId("analyst_b").attribute("country", "SG").build();
    assertThat(
            evaluator.evaluate(
                "user.country == asset.prop('dataResidency')", offshore, customer(), NOW))
        .isEqualTo(ExpressionEvaluator.Result.FALSE);
  }

  @Test
  void matchesADomainAtAnyDepthBecauseAncestorsAreMaterialised() throws Exception {
    // FINANCE against Finance: directory sources disagree about casing and a
    // policy must not fail over it, which is why the whole engine compares
    // names case-insensitively.
    assertThat(eval("user.department in asset.domains"))
        .isEqualTo(ExpressionEvaluator.Result.TRUE);
    assertThat(eval("'Finance' in asset.domains")).isEqualTo(ExpressionEvaluator.Result.TRUE);
    assertThat(eval("'Marketing' in asset.domains")).isEqualTo(ExpressionEvaluator.Result.FALSE);
    assertThat(eval("'Finance.Risk.Credit' in asset.domains"))
        .isEqualTo(ExpressionEvaluator.Result.TRUE);
  }

  @Test
  void readsOwnersThroughTheTeamTheUserBelongsTo() throws Exception {
    assertThat(eval("user.teams in asset.owners")).isEqualTo(ExpressionEvaluator.Result.TRUE);
  }

  @Test
  void impliesIsMaterialImplication() throws Exception {
    assertThat(eval("asset.tier == 'Tier.Tier1' implies user.clearance >= 'L2'"))
        .isEqualTo(ExpressionEvaluator.Result.TRUE);
    assertThat(eval("asset.tier == 'Tier.Tier1' implies user.clearance >= 'L3'"))
        .isEqualTo(ExpressionEvaluator.Result.FALSE);
    // The antecedent is false, so the whole thing holds regardless.
    assertThat(eval("asset.tier == 'Tier.Tier9' implies user.clearance >= 'L3'"))
        .isEqualTo(ExpressionEvaluator.Result.TRUE);
  }

  @Test
  void combinesWithAndOrNotAndParentheses() throws Exception {
    assertThat(eval("user.country == 'TH' && user.clearance >= 'L2'"))
        .isEqualTo(ExpressionEvaluator.Result.TRUE);
    assertThat(eval("user.country == 'SG' || user.department == 'FINANCE'"))
        .isEqualTo(ExpressionEvaluator.Result.TRUE);
    assertThat(eval("!(user.country == 'SG')")).isEqualTo(ExpressionEvaluator.Result.TRUE);
    assertThat(eval("not (user.country == 'TH' and user.clearance >= 'L2')"))
        .isEqualTo(ExpressionEvaluator.Result.FALSE);
    assertThat(eval("user.branch in ['BKK-01', 'CNX-01']"))
        .isEqualTo(ExpressionEvaluator.Result.TRUE);
    assertThat(eval("user.branch not in ['SIN-01']")).isEqualTo(ExpressionEvaluator.Result.TRUE);
  }

  @Test
  void anAttributeTheDirectoryDoesNotCarryIsUnknownRatherThanFalse() {
    Principal incomplete = Principal.withId("analyst_z").build();
    assertThatThrownBy(
            () ->
                evaluator.evaluate(
                    "user.country == asset.prop('dataResidency')", incomplete, customer(), NOW))
        .isInstanceOf(ExpressionUnavailableException.class)
        .hasMessageContaining("no value");
  }

  @Test
  void anInequalityOnAMissingAttributeIsAlsoUnknown() {
    // The dangerous direction: read as false, this would stop a DENY policy
    // applying to exactly the user whose record is incomplete.
    Principal incomplete = Principal.withId("analyst_z").build();
    assertThatThrownBy(
            () ->
                evaluator.evaluate(
                    "user.country != asset.prop('dataResidency')", incomplete, customer(), NOW))
        .isInstanceOf(ExpressionUnavailableException.class);
  }

  @Test
  void shortCircuitsPastAnUnreadableOperandWhenTheAnswerIsAlreadySettled() throws Exception {
    Principal incomplete = Principal.withId("analyst_z").attribute("country", "TH").build();
    // False regardless of what the unreadable half says.
    assertThat(
            evaluator.evaluate(
                "user.country == 'SG' && user.clearance >= 'L2'", incomplete, customer(), NOW))
        .isEqualTo(ExpressionEvaluator.Result.FALSE);
    // True regardless.
    assertThat(
            evaluator.evaluate(
                "user.country == 'TH' || user.clearance >= 'L2'", incomplete, customer(), NOW))
        .isEqualTo(ExpressionEvaluator.Result.TRUE);
  }

  @Test
  void anythingMentioningARowIsLeftForTheCompiler() throws Exception {
    assertThat(eval("row.department != user.department"))
        .isEqualTo(ExpressionEvaluator.Result.ROW_DEPENDENT);
    assertThat(eval("user.country == 'TH' && row.branch_code == user.branch"))
        .isEqualTo(ExpressionEvaluator.Result.ROW_DEPENDENT);
    // Settled without the row, so the compiler is not asked to render it.
    assertThat(eval("user.country == 'SG' && row.branch_code == user.branch"))
        .isEqualTo(ExpressionEvaluator.Result.FALSE);
  }

  @Test
  void anUnreadableOperandOutranksRowDependence() {
    Principal incomplete = Principal.withId("analyst_z").build();
    assertThatThrownBy(
            () ->
                evaluator.evaluate(
                    "user.country == 'TH' && row.branch_code == user.branch",
                    incomplete,
                    customer(),
                    NOW))
        .isInstanceOf(ExpressionUnavailableException.class);
  }

  @Test
  void refusesTextItCannotRead() {
    assertThatThrownBy(() -> eval("user.country"))
        .isInstanceOf(ExpressionUnavailableException.class)
        .hasMessageContaining("expected a comparison");
    assertThatThrownBy(() -> eval("principal.country == 'TH'"))
        .isInstanceOf(ExpressionUnavailableException.class)
        .hasMessageContaining("not something a policy can refer to");
    assertThatThrownBy(() -> eval("asset.colour == 'red'"))
        .isInstanceOf(ExpressionUnavailableException.class)
        .hasMessageContaining("no facet called");
    assertThatThrownBy(() -> eval("user.country == 'TH"))
        .isInstanceOf(ExpressionUnavailableException.class)
        .hasMessageContaining("never closed");
    assertThatThrownBy(() -> eval("user.country == 'TH' 'SG'"))
        .isInstanceOf(ExpressionUnavailableException.class)
        .hasMessageContaining("after a complete expression");
  }

  @Test
  void readsTheRequestContext() throws Exception {
    RequestContext audited = NOW.forPurpose("fraud-analysis");
    assertThat(
            evaluator.evaluate(
                "context.purpose == 'fraud-analysis'", user("analyst_a"), customer(), audited))
        .isEqualTo(ExpressionEvaluator.Result.TRUE);
  }
}
