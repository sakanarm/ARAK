package com.mfec.dac.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.schema.api.ResolvedRowPredicate.FacetOperator;
import com.mfec.dac.schema.entity.policy.AttributeCondition;
import com.mfec.dac.schema.entity.policy.ContextRule;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import com.mfec.dac.schema.entity.policy.PrincipalMatch;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import com.mfec.dac.schema.entity.policy.TimeRule;
import com.mfec.dac.schema.entity.policy.TimeWindow;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubjectMatcherTest {

  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
  private static final EngineConfig CONFIG = EngineConfig.defaults().withZone(BANGKOK);

  private static Instant bangkok(String dateTime) {
    return LocalDateTime.parse(dateTime).atZone(BANGKOK).toInstant();
  }

  private static final RequestContext TUESDAY_MORNING =
      RequestContext.at(bangkok("2026-09-15T09:00"));

  private static Principal analyst() {
    return Principal.withId("analyst_a")
        .email("analyst_a@example.com")
        .roles("analyst")
        .teams("Finance")
        .attribute("department", "FINANCE")
        .attribute("clearance", "L1")
        .build();
  }

  private static AssetContext assetOwnedBy(String owner) {
    return AssetContext.of("prod-mssql.SalesDB.dbo.customer")
        .physicalFromFqn()
        .facet(FacetType.OWNERS, owner)
        .build();
  }

  private static SubjectMatcher.Result match(SubjectRule rule, Principal principal)
      throws ExpressionUnavailableException {
    return SubjectMatcher.matches(rule, principal, assetOwnedBy("data-team"), TUESDAY_MORNING,
        CONFIG);
  }

  @Test
  @DisplayName("no subject rule means the policy applies to everyone")
  void absentRuleAppliesToEveryone() throws Exception {
    assertThat(match(null, analyst()).matched()).isTrue();
  }

  @Test
  @DisplayName("a rule that is present but states nothing matches nobody")
  void emptyRuleMatchesNobody() throws Exception {
    SubjectMatcher.Result result = match(new SubjectRule(), analyst());
    assertThat(result.matched()).isFalse();
    assertThat(result.explanation()).contains("matches nobody");
  }

  @Test
  @DisplayName("principals are an OR of identities")
  void principalsAreAlternatives() throws Exception {
    SubjectRule rule =
        new SubjectRule()
            .withPrincipals(
                List.of(
                    new PrincipalMatch().withRole("auditor"),
                    new PrincipalMatch().withTeam("Finance")));
    assertThat(match(rule, analyst()).matched()).isTrue();

    SubjectRule neither =
        new SubjectRule()
            .withPrincipals(List.of(new PrincipalMatch().withRole("auditor")));
    assertThat(match(neither, analyst()).matched()).isFalse();
  }

  @Test
  @DisplayName("fields inside one principal entry are ANDed")
  void fieldsWithinAnEntryAreRequiredTogether() throws Exception {
    SubjectRule bothNeeded =
        new SubjectRule()
            .withPrincipals(
                List.of(new PrincipalMatch().withRole("analyst").withTeam("Marketing")));
    assertThat(match(bothNeeded, analyst()).matched()).isFalse();

    SubjectRule bothHeld =
        new SubjectRule()
            .withPrincipals(List.of(new PrincipalMatch().withRole("analyst").withTeam("Finance")));
    assertThat(match(bothHeld, analyst()).matched()).isTrue();
  }

  @Test
  @DisplayName("a principal entry that names nobody grants to nobody")
  void emptyPrincipalEntryGrantsNothing() throws Exception {
    SubjectRule rule = new SubjectRule().withPrincipals(List.of(new PrincipalMatch()));
    assertThat(match(rule, analyst()).matched()).isFalse();
  }

  @Test
  @DisplayName("assetOwner matches by user id, by email, or through a team")
  void assetOwner() throws Exception {
    SubjectRule rule =
        new SubjectRule().withPrincipals(List.of(new PrincipalMatch().withAssetOwner(true)));

    assertThat(
            SubjectMatcher.matches(
                    rule, analyst(), assetOwnedBy("analyst_a"), TUESDAY_MORNING, CONFIG)
                .matched())
        .isTrue();
    assertThat(
            SubjectMatcher.matches(
                    rule, analyst(), assetOwnedBy("analyst_a@example.com"), TUESDAY_MORNING, CONFIG)
                .matched())
        .isTrue();
    assertThat(
            SubjectMatcher.matches(
                    rule, analyst(), assetOwnedBy("Finance"), TUESDAY_MORNING, CONFIG)
                .matched())
        .isTrue();
    assertThat(
            SubjectMatcher.matches(
                    rule, analyst(), assetOwnedBy("someone-else"), TUESDAY_MORNING, CONFIG)
                .matched())
        .isFalse();
  }

  @Test
  @DisplayName("attribute conditions are ANDed with each other")
  void attributesAreAllRequired() throws Exception {
    SubjectRule rule =
        new SubjectRule()
            .withAttributes(
                List.of(
                    new AttributeCondition()
                        .withKey("department")
                        .withOperator(FacetOperator.EQ)
                        .withValue("FINANCE"),
                    new AttributeCondition()
                        .withKey("clearance")
                        .withOperator(FacetOperator.GTE)
                        .withValue("L2")));
    SubjectMatcher.Result result = match(rule, analyst());
    assertThat(result.matched()).isFalse();
    assertThat(result.explanation()).contains("clearance");

    Principal cleared =
        Principal.withId("analyst_b")
            .attribute("department", "FINANCE")
            .attribute("clearance", "L2")
            .build();
    assertThat(match(rule, cleared).matched()).isTrue();
  }

  @Test
  @DisplayName("an attribute the principal does not have fails the condition")
  void missingAttributeFailsClosed() throws Exception {
    SubjectRule rule =
        new SubjectRule()
            .withAttributes(
                List.of(
                    new AttributeCondition()
                        .withKey("country")
                        .withOperator(FacetOperator.EQ)
                        .withValue("TH")));
    assertThat(match(rule, analyst()).matched()).isFalse();
  }

  @Test
  @DisplayName("time is checked against the request instant, in the window's own zone")
  void timeWindow() throws Exception {
    SubjectRule rule =
        new SubjectRule()
            .withPrincipals(List.of(new PrincipalMatch().withTeam("Finance")))
            .withTime(
                new TimeRule()
                    .withWindows(
                        List.of(
                            new TimeWindow()
                                .withDays(List.of("MON-FRI"))
                                .withFrom("08:00")
                                .withTo("18:00")
                                .withTimezone("Asia/Bangkok"))));
    assertThat(match(rule, analyst()).matched()).isTrue();

    RequestContext evening = RequestContext.at(bangkok("2026-09-15T20:00"));
    SubjectMatcher.Result result =
        SubjectMatcher.matches(rule, analyst(), assetOwnedBy("data-team"), evening, CONFIG);
    assertThat(result.matched()).isFalse();
    assertThat(result.explanation()).contains("time window");
  }

  @Test
  @DisplayName("a context rule fails closed when the request does not carry the value")
  void contextFailsClosedOnMissingValues() throws Exception {
    SubjectRule rule =
        new SubjectRule()
            .withPrincipals(List.of(new PrincipalMatch().withTeam("Finance")))
            .withContext(new ContextRule().withIpCidr(List.of("10.0.0.0/8")));

    // No IP on the request: the rule cannot be satisfied, so it is not.
    assertThat(match(rule, analyst()).matched()).isFalse();

    RequestContext inside = TUESDAY_MORNING.fromIp("10.20.30.40");
    assertThat(
            SubjectMatcher.matches(rule, analyst(), assetOwnedBy("data-team"), inside, CONFIG)
                .matched())
        .isTrue();

    RequestContext outside = TUESDAY_MORNING.fromIp("192.168.1.4");
    assertThat(
            SubjectMatcher.matches(rule, analyst(), assetOwnedBy("data-team"), outside, CONFIG)
                .matched())
        .isFalse();
  }

  @Test
  @DisplayName("an undecidable expression is raised, not guessed at")
  void unavailableExpressionThrows() {
    SubjectRule rule =
        new SubjectRule().withExpression("user.country == asset.prop('dataResidency')");
    assertThatThrownBy(() -> match(rule, analyst()))
        .isInstanceOf(ExpressionUnavailableException.class);
  }

  @Test
  @DisplayName("a subject expression that needs a row is rejected outright")
  void rowDependentSubjectExpressionIsRejected() {
    EngineConfig rowDependent =
        CONFIG.withExpressions((e, p, a, c) -> ExpressionEvaluator.Result.ROW_DEPENDENT);
    SubjectRule rule = new SubjectRule().withExpression("row.dept != user.department");
    assertThatThrownBy(
            () ->
                SubjectMatcher.matches(
                    rule, analyst(), assetOwnedBy("data-team"), TUESDAY_MORNING, rowDependent))
        .isInstanceOf(ExpressionUnavailableException.class)
        .hasMessageContaining("row data");
  }

  @Test
  @DisplayName("an expression that evaluates false does not match")
  void falseExpressionDoesNotMatch() throws Exception {
    EngineConfig alwaysFalse =
        CONFIG.withExpressions((e, p, a, c) -> ExpressionEvaluator.Result.FALSE);
    SubjectRule rule = new SubjectRule().withExpression("1 == 2");
    assertThat(
            SubjectMatcher.matches(
                    rule, analyst(), assetOwnedBy("data-team"), TUESDAY_MORNING, alwaysFalse)
                .matched())
        .isFalse();
  }
}
