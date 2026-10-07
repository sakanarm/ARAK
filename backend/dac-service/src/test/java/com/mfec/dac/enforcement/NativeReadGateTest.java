package com.mfec.dac.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.engine.PolicyEngine;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.DataSourceStore.EnforcementMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the query proxy may read on a source whose subscription policies are
 * pushed down natively. Each test names one rule from the class comment of
 * {@link NativeReadGate}.
 */
class NativeReadGateTest {

  private static final UUID POLICY = UUID.fromString("5e1f0c2a-0000-4000-8000-000000000011");
  private static final UUID OTHER = UUID.fromString("5e1f0c2a-0000-4000-8000-000000000012");
  private static final UUID SOURCE = UUID.fromString("5e1f0c2a-0000-4000-8000-0000000000aa");

  private final Map<UUID, NativeRoleStore.Role> roles = new HashMap<>();
  private final List<UUID> asked = new ArrayList<>();
  private final NativeReadGate gate =
      new NativeReadGate(
          (policyId, sourceId) -> {
            asked.add(policyId);
            assertThat(sourceId).isEqualTo(SOURCE);
            return Optional.ofNullable(roles.get(policyId));
          });

  @Test
  void appliedReadRoleCoveringTheTableLetsTheProxyRead() {
    roles.put(POLICY, role(POLICY, "APPLIED", "READ", "sales.orders", "sales.customer"));

    assertThat(gate.refusal(nativePg(), "sales", "orders", allowedBy(POLICY, "Sales readers")))
        .isEmpty();
  }

  @Test
  void tableIsMatchedWhateverCaseTheStatementWroteItIn() {
    roles.put(POLICY, role(POLICY, "APPLIED", "READ", "sales.orders"));

    assertThat(gate.refusal(nativePg(), "SALES", "Orders", allowedBy(POLICY, "Sales readers")))
        .isEmpty();
  }

  @Test
  void pendingRoleStillLetsTheProxyRead() {
    roles.put(POLICY, role(POLICY, "PENDING", "READ", "sales.orders"));

    assertThat(gate.refusal(nativePg(), "sales", "orders", allowedBy(POLICY, "Sales readers")))
        .isEmpty();
  }

  @Test
  void policyNeverPushedIsRefused() {
    Optional<String> refused =
        gate.refusal(nativePg(), "sales", "orders", allowedBy(POLICY, "Sales readers"));

    assertThat(refused).isPresent();
    assertThat(refused.get())
        .contains("Policy Sales readers has not been pushed yet")
        .contains("sales.orders");
  }

  @Test
  void rolledBackRoleIsRefused() {
    roles.put(POLICY, role(POLICY, "ROLLED_BACK", "READ", "sales.orders"));

    assertThat(gate.refusal(nativePg(), "sales", "orders", allowedBy(POLICY, "Sales readers")))
        .hasValueSatisfying(m -> assertThat(m).contains("was rolled back on the source"));
  }

  @Test
  void roleChangedByHandIsRefused() {
    roles.put(POLICY, role(POLICY, "DRIFTED", "READ", "sales.orders"));

    assertThat(gate.refusal(nativePg(), "sales", "orders", allowedBy(POLICY, "Sales readers")))
        .hasValueSatisfying(m -> assertThat(m).contains("was changed by hand on the source"));
  }

  @Test
  void roleWhoseLastApplyFailedIsRefused() {
    roles.put(POLICY, role(POLICY, "FAILED", "READ", "sales.orders"));

    assertThat(gate.refusal(nativePg(), "sales", "orders", allowedBy(POLICY, "Sales readers")))
        .hasValueSatisfying(m -> assertThat(m).contains("failed the last time it was applied"));
  }

  @Test
  void browseRoleDoesNotVouchForReadingRows() {
    roles.put(POLICY, role(POLICY, "APPLIED", "BROWSE", "sales.orders"));

    assertThat(gate.refusal(nativePg(), "sales", "orders", allowedBy(POLICY, "Sales readers")))
        .hasValueSatisfying(m -> assertThat(m).contains("pushed at Browse only"));
  }

  @Test
  void roleWithoutThisTableIsRefused() {
    roles.put(POLICY, role(POLICY, "APPLIED", "READ", "sales.customer"));

    assertThat(gate.refusal(nativePg(), "sales", "orders", allowedBy(POLICY, "Sales readers")))
        .hasValueSatisfying(m -> assertThat(m).contains("does not cover this table"));
  }

  @Test
  void oneGoodRoleAmongSeveralPoliciesIsEnough() {
    roles.put(POLICY, role(POLICY, "ROLLED_BACK", "READ", "sales.orders"));
    roles.put(OTHER, role(OTHER, "APPLIED", "READ", "sales.orders"));
    PolicyDecision decision =
        allowed(
            reason(POLICY, "Sales readers", DecisionReason.Effect.ALLOW, true),
            reason(OTHER, "Order desk", DecisionReason.Effect.ALLOW, true));

    assertThat(gate.refusal(nativePg(), "sales", "orders", decision)).isEmpty();
  }

  @Test
  void everyPolicyIsNamedWhenNoneIsInForce() {
    roles.put(OTHER, role(OTHER, "APPLIED", "BROWSE", "sales.orders"));
    PolicyDecision decision =
        allowed(
            reason(POLICY, "Sales readers", DecisionReason.Effect.ALLOW, true),
            reason(OTHER, "Order desk", DecisionReason.Effect.ALLOW, true));

    assertThat(gate.refusal(nativePg(), "sales", "orders", decision))
        .hasValueSatisfying(
            m ->
                assertThat(m)
                    .contains("Policy Sales readers has not been pushed yet")
                    .contains("Policy Order desk is pushed at Browse only"));
  }

  @Test
  void unmatchedAndDataPoliciesAreNotAskedAbout() {
    roles.put(POLICY, role(POLICY, "APPLIED", "READ", "sales.orders"));
    PolicyDecision decision =
        allowed(
            reason(OTHER, "Not for this person", DecisionReason.Effect.ALLOW, false),
            reason(OTHER, "Mask emails", DecisionReason.Effect.ALLOW, true)
                .withPolicyType(DecisionReason.PolicyType.DATA),
            reason(POLICY, "Sales readers", DecisionReason.Effect.ALLOW, true));

    assertThat(gate.refusal(nativePg(), "sales", "orders", decision)).isEmpty();
    assertThat(asked).containsExactly(POLICY);
  }

  @Test
  void directGrantHasNoRoleAndIsLetThrough() {
    PolicyDecision decision =
        allowed(
            new DecisionReason()
                .withPolicyName("grant:" + UUID.randomUUID())
                .withEffect(DecisionReason.Effect.ALLOW)
                .withPolicyType(DecisionReason.PolicyType.SUBSCRIPTION)
                .withMatched(true));

    assertThat(gate.refusal(nativePg(), "sales", "orders", decision)).isEmpty();
    assertThat(asked).isEmpty();
  }

  @Test
  void allowedWithNothingToMatchIsRefusedRatherThanGuessed() {
    PolicyDecision decision =
        allowed(
            new DecisionReason()
                .withPolicyName(PolicyEngine.COMPOSITION)
                .withMatched(true)
                .withExplanation("allowed"));

    assertThat(gate.refusal(nativePg(), "sales", "orders", decision))
        .hasValueSatisfying(m -> assertThat(m).contains("could be matched"));
  }

  @Test
  void refusedDecisionIsLeftToThePolicy() {
    PolicyDecision denied =
        new PolicyDecision()
            .withAllowed(false)
            .withReasons(List.of(reason(POLICY, "Sales readers", DecisionReason.Effect.DENY, true)));

    assertThat(gate.refusal(nativePg(), "sales", "orders", denied)).isEmpty();
    assertThat(asked).isEmpty();
  }

  @Test
  void proxyModeSourceIsReadAsBefore() {
    assertThat(
            gate.refusal(
                source(DataSourceStore.Engine.POSTGRES, EnforcementMode.PROXY),
                "sales",
                "orders",
                allowedBy(POLICY, "Sales readers")))
        .isEmpty();
    assertThat(asked).isEmpty();
  }

  @Test
  void nativeModeOnAnEngineThatIsNotPushedToIsReadAsBefore() {
    assertThat(
            gate.refusal(
                source(DataSourceStore.Engine.SQLSERVER, EnforcementMode.NATIVE_CONFIG),
                "sales",
                "orders",
                allowedBy(POLICY, "Sales readers")))
        .isEmpty();
    assertThat(asked).isEmpty();
  }

  @Test
  void refusalCarriesNoRoleNameOrStatement() {
    NativeRoleStore.Role rolled = role(POLICY, "ROLLED_BACK", "READ", "sales.orders");
    roles.put(POLICY, rolled);

    assertThat(gate.refusal(nativePg(), "sales", "orders", allowedBy(POLICY, "Sales readers")))
        .hasValueSatisfying(
            m ->
                assertThat(m)
                    .doesNotContain(rolled.roleName())
                    .doesNotContainIgnoringCase("grant ")
                    .doesNotContainIgnoringCase("select "));
  }

  // ------------------------------------------------------------------ helpers

  private static DataSourceStore.Source nativePg() {
    return source(DataSourceStore.Engine.POSTGRES, EnforcementMode.NATIVE_CONFIG);
  }

  private static DataSourceStore.Source source(DataSourceStore.Engine engine, EnforcementMode mode) {
    return new DataSourceStore.Source(
        SOURCE, "salesdb", engine, "16", "db.example.test", 5432, "salesdb", "env:FAKE",
        mode, null, null, null, null, true, Instant.EPOCH, Instant.EPOCH, 0);
  }

  private static NativeRoleStore.Role role(
      UUID policyId, String status, String level, String... tables) {
    return new NativeRoleStore.Role(
        UUID.randomUUID(), policyId, SOURCE, "arak_sub_test_role", "salesdb", level, status,
        "-- script", "fingerprint", List.of("alice_db"), List.of(tables), Instant.EPOCH,
        "admin", Instant.EPOCH, null, null, Instant.EPOCH);
  }

  private static PolicyDecision allowedBy(UUID policyId, String name) {
    return allowed(reason(policyId, name, DecisionReason.Effect.ALLOW, true));
  }

  private static PolicyDecision allowed(DecisionReason... reasons) {
    return new PolicyDecision().withAllowed(true).withReasons(List.of(reasons));
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
