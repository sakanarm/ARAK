package com.mfec.dac.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask.ScopeLevel;
import com.mfec.dac.schema.api.ResolvedRowPredicate.FacetOperator;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.AttributeCondition;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.PrincipalMatch;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import com.mfec.dac.schema.entity.policy.TimeRule;
import com.mfec.dac.schema.entity.policy.TimeWindow;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The one reason a refusal is explained by.
 *
 * <p>Half of these are written by hand, to pin each rule on its own; the rest
 * come from the engine, because the gate rules only mean something against the
 * reasons the engine actually writes.
 */
class RefusalsTest {

  private static DecisionReason reason(
      String name, DecisionReason.Effect effect, boolean matched, String why) {
    return new DecisionReason()
        .withPolicyName(name)
        .withPolicyType(DecisionReason.PolicyType.SUBSCRIPTION)
        .withEffect(effect)
        .withMatched(matched)
        .withExplanation(why);
  }

  private static String blamed(DecisionReason... reasons) {
    DecisionReason reason = Refusals.blame(List.of(reasons));
    return reason == null ? null : reason.getPolicyName();
  }

  @Nested
  @DisplayName("by hand")
  class ByHand {

    @Test
    @DisplayName("a matched DENY wins wherever it is listed")
    void matchedDeny() {
      assertThat(
              blamed(
                  reason("l2-only", DecisionReason.Effect.ALLOW, false, "clearance is not L2"),
                  reason("no-contractors", DecisionReason.Effect.DENY, true, "contractor")))
          .isEqualTo("no-contractors");
    }

    @Test
    @DisplayName("an unmatched DENY, a data policy and a grant are never the reason")
    void notTheReason() {
      assertThat(
              blamed(
                  reason("no-contractors", DecisionReason.Effect.DENY, false, "not a contractor"),
                  reason("mask-contacts", DecisionReason.Effect.ALLOW, false, "clearance lt L2 is false")
                      .withPolicyType(DecisionReason.PolicyType.DATA),
                  reason("grant:1", DecisionReason.Effect.ALLOW, false, "outside its window"),
                  reason("l2-only", DecisionReason.Effect.ALLOW, false, "clearance is not L2")))
          .isEqualTo("l2-only");
    }

    @Test
    @DisplayName("a policy that says why goes before one that does not")
    void explainedFirst() {
      assertThat(
              blamed(
                  reason("silent", DecisionReason.Effect.ALLOW, false, " "),
                  reason("l2-only", DecisionReason.Effect.ALLOW, false, "clearance is not L2")))
          .isEqualTo("l2-only");
      assertThat(blamed(reason("silent", DecisionReason.Effect.ALLOW, false, null)))
          .isEqualTo("silent");
    }

    @Test
    @DisplayName("a near miss goes before the first that did not apply")
    void nearMissFirst() {
      assertThat(
              blamed(
                  reason("procurement", DecisionReason.Effect.ALLOW, false, "attribute condition not satisfied"),
                  reason("finance-hours", DecisionReason.Effect.ALLOW, false, SubjectMatcher.OUTSIDE_TIME_WINDOW)))
          .isEqualTo("finance-hours");
      assertThat(
              blamed(
                  reason("procurement", DecisionReason.Effect.ALLOW, false, "attribute condition not satisfied"),
                  reason("office-network", DecisionReason.Effect.ALLOW, false, SubjectMatcher.OUTSIDE_CONTEXT)))
          .isEqualTo("office-network");
    }

    @Test
    @DisplayName("the engine's composition line only when no policy speaks")
    void compositionLast() {
      DecisionReason composition =
          new DecisionReason()
              .withPolicyName(PolicyEngine.COMPOSITION)
              .withMatched(false)
              .withExplanation("no policy at layer 0 grants this principal access");
      assertThat(
              blamed(
                  composition,
                  reason("l2-only", DecisionReason.Effect.ALLOW, false, "clearance is not L2")))
          .isEqualTo("l2-only");
      assertThat(
              blamed(
                  reason("sales-rls", DecisionReason.Effect.ALLOW, true, "policy carries no subject rule"),
                  composition))
          .isEqualTo(PolicyEngine.COMPOSITION);
      assertThat(Refusals.blame(List.of())).isNull();
      assertThat(Refusals.blame((PolicyDecision) null)).isNull();
    }
  }

  // ------------------------------------------------------------- engine

  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
  private static final String FQN = "demo-pg.salesdb.sales.customer";

  /** 2026-09-15 is a Tuesday. */
  private static RequestContext at(String time) {
    return RequestContext.at(LocalDateTime.parse("2026-09-15T" + time).atZone(BANGKOK).toInstant());
  }

  private static Policy subscription(String name, ScopeLevel level, SubjectRule subject) {
    return new Policy()
        .withId(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)))
        .withName(name)
        .withVersion(1)
        .withPolicyType(Policy.PolicyType.SUBSCRIPTION)
        .withScopeLevel(level)
        .withScopeFqn(level == ScopeLevel.ORG ? null : FQN)
        .withEffect(Policy.Effect.ALLOW)
        .withSelector(
            new AssetSelector()
                .withCondition(
                    new FacetCondition()
                        .withFacet(FacetType.TABLE)
                        .withOperator(FacetOperator.EQ)
                        .withValue("customer")))
        .withSubject(subject);
  }

  private static SubjectRule department(String value) {
    return new SubjectRule()
        .withAttributes(
            List.of(
                new AttributeCondition()
                    .withKey("department")
                    .withOperator(FacetOperator.EQ)
                    .withValue(value)));
  }

  private static TimeRule officeHours() {
    return new TimeRule()
        .withWindows(
            List.of(
                new TimeWindow()
                    .withDays(List.of("MON-FRI"))
                    .withFrom("08:00")
                    .withTo("18:00")
                    .withTimezone("Asia/Bangkok")));
  }

  private static PolicyDecision decide(RequestContext when, Policy... policies) {
    return new PolicyEngine(EngineConfig.defaults().withZone(BANGKOK))
        .evaluate(
            Principal.withId("analyst_f").roles("analyst").attribute("department", "FINANCE").build(),
            AssetContext.of(FQN).physicalFromFqn().build(),
            when,
            List.of(policies));
  }

  private static String blamed(PolicyDecision decision) {
    assertThat(decision.getAllowed()).isFalse();
    DecisionReason reason = Refusals.blame(decision);
    return reason == null ? null : reason.getPolicyName();
  }

  @Nested
  @DisplayName("from the engine")
  class FromTheEngine {

    @Test
    @DisplayName("after hours, the policy written for the principal, not the first listed")
    void afterHours() {
      assertThat(
              blamed(
                  decide(
                      at("20:00"),
                      subscription("procurement-readers", ScopeLevel.ORG, department("PROCUREMENT")),
                      subscription(
                          "finance-office-hours",
                          ScopeLevel.ORG,
                          department("FINANCE").withTime(officeHours())))))
          .isEqualTo("finance-office-hours");
    }

    @Test
    @DisplayName("the layer that refused, not one the principal got through")
    void theRefusingLayer() {
      assertThat(
              blamed(
                  decide(
                      at("09:00"),
                      subscription("procurement-readers", ScopeLevel.ORG, department("PROCUREMENT")),
                      subscription("finance-readers", ScopeLevel.ORG, department("FINANCE")),
                      subscription("sales-only", ScopeLevel.TABLE, department("SALES")))))
          .isEqualTo("sales-only");
    }

    @Test
    @DisplayName("with a grant in the stack, the gate it could not pass")
    void theGateAGrantCouldNotPass() {
      // What the request pages ask: had this been granted, what would still
      // say no? The grant sits at the table; the org policy for procurement
      // holds its gate shut and never agreed to a lower layer relaxing it.
      Policy grant =
          subscription(
                  "grant:1",
                  ScopeLevel.TABLE,
                  new SubjectRule()
                      .withPrincipals(List.of(new PrincipalMatch().withUser("analyst_f"))))
              .withSelector(null);
      assertThat(
              blamed(
                  decide(
                      at("09:00"),
                      subscription("procurement-readers", ScopeLevel.ORG, department("PROCUREMENT")),
                      grant)))
          .isEqualTo("procurement-readers");
    }
  }
}
