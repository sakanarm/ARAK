package com.mfec.dac.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.compiler.sql.PostgresGrantCompiler.AccessLevel;
import com.mfec.dac.engine.PolicyEngine;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Who goes into a policy's role. Each test names one rule from the class
 * comment of {@link NativeMembership} and the person it keeps in or out.
 */
class NativeMembershipTest {

  private static final UUID POLICY = UUID.fromString("5e1f0c2a-0000-4000-8000-000000000001");
  private static final UUID OTHER = UUID.fromString("5e1f0c2a-0000-4000-8000-000000000002");
  private static final String ORDERS = "it_pg.sales.orders";
  private static final String CUSTOMER = "it_pg.sales.customer";
  private static final List<String> BOTH = List.of(ORDERS, CUSTOMER);

  @Test
  void personLetInByThisPolicyOnEveryTableIsAMember() {
    NativeMembership.Result result =
        compute(
            AccessLevel.READ,
            BOTH,
            Map.of("alice", Map.of(ORDERS, allowed("alice", ORDERS), CUSTOMER, allowed("alice", CUSTOMER))),
            Map.of("alice", List.of("alice_db")));

    assertThat(result.logins()).containsExactly("alice_db");
    assertThat(result.qualified()).containsExactly("alice");
    assertThat(result.members().get(0).people()).containsExactly("alice");
    assertThat(result.excluded()).isEmpty();
    assertThat(result.unmapped()).isEmpty();
  }

  @Test
  void denyOnOneTableKeepsThePersonOutOfTheWholeRoleAndListsWhatTheyLose() {
    PolicyDecision denied =
        new PolicyDecision()
            .withPrincipal("bob")
            .withAssetFqn(CUSTOMER)
            .withAllowed(false)
            .withReasons(
                List.of(
                    reason(POLICY, "Sales readers", DecisionReason.Effect.ALLOW, true),
                    reason(OTHER, "No contractors on customer", DecisionReason.Effect.DENY, true)));
    NativeMembership.Result result =
        compute(
            AccessLevel.READ,
            BOTH,
            Map.of("bob", Map.of(ORDERS, allowed("bob", ORDERS), CUSTOMER, denied)),
            Map.of("bob", List.of("bob_db")));

    assertThat(result.members()).isEmpty();
    assertThat(result.excluded()).hasSize(1);
    NativeMembership.Excluded bob = result.excluded().get(0);
    assertThat(bob.person()).isEqualTo("bob");
    assertThat(bob.login()).isEqualTo("bob_db");
    assertThat(bob.reasons()).containsExactly(CUSTOMER + ": denied by No contractors on customer");
    assertThat(bob.lost()).containsExactly(ORDERS);
  }

  @Test
  void exemptPersonIsNotAMemberAndThePlanSaysTheyAreExempt() {
    PolicyDecision exempt =
        new PolicyDecision()
            .withPrincipal("carol")
            .withAssetFqn(ORDERS)
            .withAllowed(false)
            .withReasons(
                List.of(
                    reason(POLICY, "Sales readers", DecisionReason.Effect.ALLOW, false)
                        .withExplanation("carol is exempt from this policy until 2026-12-31")));
    NativeMembership.Result result =
        compute(
            AccessLevel.READ,
            BOTH,
            Map.of("carol", Map.of(ORDERS, exempt, CUSTOMER, allowed("carol", CUSTOMER))),
            Map.of("carol", List.of("carol_db")));

    assertThat(result.members()).isEmpty();
    assertThat(result.excluded()).singleElement()
        .satisfies(e -> assertThat(e.reasons()).singleElement().asString().contains("exempt"));
  }

  @Test
  void personLetInOnlyByAnotherPolicyIsNotThisRolesBusiness() {
    PolicyDecision viaOther =
        new PolicyDecision()
            .withPrincipal("dave")
            .withAssetFqn(ORDERS)
            .withAllowed(true)
            .withReasons(List.of(reason(OTHER, "Finance readers", DecisionReason.Effect.ALLOW, true)));
    NativeMembership.Result result =
        compute(
            AccessLevel.READ,
            List.of(ORDERS),
            Map.of("dave", Map.of(ORDERS, viaOther)),
            Map.of("dave", List.of("dave_db")));

    assertThat(result.members()).isEmpty();
    // Never credited by this policy anywhere, so not even listed as excluded.
    assertThat(result.excluded()).isEmpty();
  }

  @Test
  void creditedOnOneTableButLetInByAnotherPolicyOnTheOtherIsExcluded() {
    PolicyDecision viaOther =
        new PolicyDecision()
            .withPrincipal("erin")
            .withAssetFqn(CUSTOMER)
            .withAllowed(true)
            .withReasons(List.of(reason(OTHER, "Finance readers", DecisionReason.Effect.ALLOW, true)));
    NativeMembership.Result result =
        compute(
            AccessLevel.READ,
            BOTH,
            Map.of("erin", Map.of(ORDERS, allowed("erin", ORDERS), CUSTOMER, viaOther)),
            Map.of("erin", List.of("erin_db")));

    assertThat(result.members()).isEmpty();
    assertThat(result.excluded()).singleElement()
        .satisfies(
            e ->
                assertThat(e.reasons())
                    .containsExactly(CUSTOMER + ": let in by something other than this policy"));
  }

  @Test
  void rowFilterOrMaskKeepsThePersonOutAtReadButNotAtBrowse() {
    PolicyDecision filtered =
        allowed("fay", ORDERS)
            .withRowPredicates(List.of(new ResolvedRowPredicate().withColumn("region")));
    PolicyDecision masked =
        allowed("fay", CUSTOMER)
            .withColumnMasks(List.of(new ResolvedColumnMask().withColumn("email")));
    Map<String, Map<String, PolicyDecision>> decisions =
        Map.of("fay", Map.of(ORDERS, filtered, CUSTOMER, masked));
    Map<String, List<String>> logins = Map.of("fay", List.of("fay_db"));

    NativeMembership.Result read = compute(AccessLevel.READ, BOTH, decisions, logins);
    assertThat(read.members()).isEmpty();
    assertThat(read.excluded()).singleElement()
        .satisfies(
            e ->
                assertThat(e.reasons())
                    .containsExactly(
                        ORDERS + ": a data policy applies a row filter, which a plain SELECT grant would bypass",
                        CUSTOMER + ": a data policy applies a column mask, which a plain SELECT grant would bypass"));

    NativeMembership.Result browse = compute(AccessLevel.BROWSE, BOTH, decisions, logins);
    assertThat(browse.logins()).containsExactly("fay_db");
  }

  @Test
  void hiddenColumnsAndUnenforceableRulesAlsoKeepThePersonOutAtRead() {
    PolicyDecision hidden = allowed("gus", ORDERS).withHiddenColumns(List.of("amount"));
    NativeMembership.Result result =
        compute(
            AccessLevel.READ,
            List.of(ORDERS),
            Map.of("gus", Map.of(ORDERS, hidden)),
            Map.of("gus", List.of("gus_db")));

    assertThat(result.members()).isEmpty();
    assertThat(result.excluded().get(0).reasons().get(0)).contains("hidden columns");
  }

  @Test
  void personWithNoDecisionOnATableIsNotAMember() {
    NativeMembership.Result result =
        compute(
            AccessLevel.READ,
            BOTH,
            Map.of("hal", Map.of(ORDERS, allowed("hal", ORDERS))),
            Map.of("hal", List.of("hal_db")));

    assertThat(result.members()).isEmpty();
    assertThat(result.excluded()).singleElement()
        .satisfies(e -> assertThat(e.reasons()).containsExactly(CUSTOMER + ": no decision"));
  }

  @Test
  void qualifiedPersonWithoutALoginIsReportedAsUnmapped() {
    NativeMembership.Result result =
        compute(
            AccessLevel.READ,
            List.of(ORDERS),
            Map.of("ivy", Map.of(ORDERS, allowed("ivy", ORDERS))),
            Map.of());

    assertThat(result.members()).isEmpty();
    assertThat(result.qualified()).containsExactly("ivy");
    assertThat(result.unmapped()).containsExactly("ivy");
  }

  @Test
  void loginSharedWithSomebodyWhoDoesNotQualifyStaysOut() {
    Map<String, Map<String, PolicyDecision>> decisions = new LinkedHashMap<>();
    decisions.put("jan", Map.of(ORDERS, allowed("jan", ORDERS)));
    decisions.put(
        "kim",
        Map.of(
            ORDERS,
            new PolicyDecision()
                .withPrincipal("kim")
                .withAssetFqn(ORDERS)
                .withAllowed(false)
                .withReasons(List.of(reason(POLICY, "Sales readers", DecisionReason.Effect.ALLOW, false)))));
    NativeMembership.Result result =
        compute(
            AccessLevel.READ,
            List.of(ORDERS),
            decisions,
            Map.of("jan", List.of("team_db"), "kim", List.of("team_db")));

    assertThat(result.members()).isEmpty();
    assertThat(result.sharedRefused()).singleElement().asString()
        .isEqualTo("team_db is shared by jan (who qualifies) and kim (who does not), so it stays"
            + " out of the role.");
  }

  @Test
  void loginSharedOnlyByPeopleWhoQualifyIsAMemberForAllOfThem() {
    NativeMembership.Result result =
        compute(
            AccessLevel.READ,
            List.of(ORDERS),
            Map.of(
                "jan", Map.of(ORDERS, allowed("jan", ORDERS)),
                "lee", Map.of(ORDERS, allowed("lee", ORDERS))),
            Map.of("jan", List.of("team_db"), "lee", List.of("team_db")));

    assertThat(result.members()).singleElement()
        .satisfies(m -> assertThat(m.people()).containsExactly("jan", "lee"));
  }

  @Test
  void policyThatBindsNoTableHasNoMembers() {
    NativeMembership.Result result =
        compute(
            AccessLevel.BROWSE,
            List.of(),
            Map.of("alice", Map.of(ORDERS, allowed("alice", ORDERS))),
            Map.of("alice", List.of("alice_db")));

    assertThat(result.members()).isEmpty();
    assertThat(result.qualified()).isEmpty();
  }

  @Test
  void usernameCaseDoesNotMatterWhenMatchingLogins() {
    NativeMembership.Result result =
        compute(
            AccessLevel.READ,
            List.of(ORDERS),
            Map.of("Alice", Map.of(ORDERS, allowed("Alice", ORDERS))),
            Map.of("alice", List.of("alice_db")));

    assertThat(result.logins()).containsExactly("alice_db");
    assertThat(result.members().get(0).people()).containsExactly("Alice");
    assertThat(result.unmapped()).isEmpty();
  }

  @Test
  void anotherPolicysDenyIsNamedButThisPolicysOwnRowsAreNot() {
    PolicyDecision refused =
        new PolicyDecision()
            .withAllowed(false)
            .withReasons(
                new ArrayList<>(
                    List.of(reason(POLICY, "Sales readers", DecisionReason.Effect.ALLOW, true))));

    assertThat(NativeMembership.failure(refused, POLICY, AccessLevel.READ))
        .isEqualTo("refused by the composition of policies");
    assertThat(NativeMembership.credits(refused, POLICY)).isTrue();
    assertThat(NativeMembership.credits(null, POLICY)).isFalse();
  }

  @Test
  void anotherPolicyHoldingTheGateIsNamed() {
    PolicyDecision refused =
        new PolicyDecision()
            .withAllowed(false)
            .withReasons(
                new ArrayList<>(
                    List.of(
                        reason(POLICY, "Sales readers", DecisionReason.Effect.ALLOW, true),
                        reason(
                                UUID.randomUUID(), "Order desk only",
                                DecisionReason.Effect.ALLOW, false)
                            .withExplanation(
                                PolicyEngine.HOLDS_THE_GATE
                                    + "; it does not allow a lower layer to relax it"),
                        reason(
                                UUID.randomUUID(), "Unrelated", DecisionReason.Effect.ALLOW,
                                false)
                            .withExplanation("did not match"))));

    assertThat(NativeMembership.failure(refused, POLICY, AccessLevel.READ))
        .isEqualTo(
            "not let in by Order desk only, which every reader of this table also has to pass");
  }

  // ------------------------------------------------------------------ helpers

  private static NativeMembership.Result compute(
      AccessLevel level,
      List<String> tables,
      Map<String, Map<String, PolicyDecision>> decisions,
      Map<String, List<String>> logins) {
    return NativeMembership.compute(POLICY, level, tables, decisions, logins);
  }

  private static PolicyDecision allowed(String person, String fqn) {
    return new PolicyDecision()
        .withPrincipal(person)
        .withAssetFqn(fqn)
        .withAllowed(true)
        .withReasons(List.of(reason(POLICY, "Sales readers", DecisionReason.Effect.ALLOW, true)));
  }

  private static DecisionReason reason(
      UUID policyId, String name, DecisionReason.Effect effect, boolean matched) {
    return new DecisionReason()
        .withPolicyId(policyId)
        .withPolicyName(name)
        .withEffect(effect)
        .withPolicyType(DecisionReason.PolicyType.SUBSCRIPTION)
        .withMatched(matched);
  }
}
