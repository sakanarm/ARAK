package com.mfec.dac.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.PolicyDecision;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which policy the refusal names as the one still in the way after a grant. */
class AccessEligibilityTest {

  private static DecisionReason reason(
      String name, DecisionReason.Effect effect, boolean matched, String why) {
    return new DecisionReason()
        .withPolicyName(name)
        .withEffect(effect)
        .withMatched(matched)
        .withExplanation(why);
  }

  private static PolicyDecision refused(DecisionReason... reasons) {
    return new PolicyDecision().withAllowed(false).withReasons(List.of(reasons));
  }

  @Test
  void aMatchedDenyIsNamedEvenWhenItComesLast() {
    String blocker =
        AccessEligibility.blocker(
            refused(
                reason("l2-only", DecisionReason.Effect.ALLOW, false, "clearance is not L2"),
                reason("grant:1", DecisionReason.Effect.ALLOW, true, "granted"),
                reason("no-contractors", DecisionReason.Effect.DENY, true, "employeeType is contractor")));

    assertThat(blocker).isEqualTo("no-contractors: employeeType is contractor");
  }

  @Test
  void otherwiseTheLayerTheGrantCouldNotPass() {
    String blocker =
        AccessEligibility.blocker(
            refused(
                reason("grant:1", DecisionReason.Effect.ALLOW, false, "outside its window"),
                reason("(composition)", null, false, "no policy at layer 0 grants this principal access"),
                reason("l2-only", DecisionReason.Effect.ALLOW, false, "clearance is not L2")));

    // Not the grant -- that is the thing being asked for -- and not the
    // engine's composition line while a policy with a name is available.
    assertThat(blocker).isEqualTo("l2-only: clearance is not L2");
  }

  @Test
  void theCompositionLineWhenNothingElseSpeaks() {
    assertThat(
            AccessEligibility.blocker(
                refused(reason("(composition)", null, false, "no policy at layer 0 grants this principal access"))))
        .isEqualTo("(composition): no policy at layer 0 grants this principal access");
  }

  @Test
  void aNameWithoutExplanationAndNoReasonsAtAll() {
    assertThat(AccessEligibility.blocker(refused(reason("l2-only", DecisionReason.Effect.ALLOW, false, " "))))
        .isEqualTo("l2-only");
    assertThat(AccessEligibility.blocker(new PolicyDecision().withAllowed(false)))
        .isEqualTo("the policy decision");
  }
}
