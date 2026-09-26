package com.mfec.dac.engine;

import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.PolicyDecision;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which one reason answers "why was I refused?".
 *
 * <p>A refused decision carries a reason for every policy that bound, and most
 * of them are not the answer. A matched {@code ALLOW} would have granted had
 * another layer agreed. A {@code DENY} that did not match kept nobody out. A
 * data policy never lets anyone in, so one whose condition did not hold is not
 * why the door is shut. A grant is the thing being asked for, not the thing in
 * the way. And an {@code ALLOW} at a layer the principal got through is beside
 * the point however unmatched it was.
 *
 * <p>What is left, in order: a matched {@code DENY}, which wins outright; an
 * {@code ALLOW} standing in the gate that refused ({@link
 * PolicyEngine#HOLDS_THE_GATE}), preferring one written for this principal that
 * only the clock or the network kept shut ({@link SubjectMatcher#nearMiss});
 * then the engine's own composition line, which says that a layer refused but
 * not which policy to ask about.
 *
 * <p>The query console and the access-request pages both put this sentence in
 * front of people, and they used to choose it separately. A finance analyst
 * after hours was told the procurement policy did not apply to them, which was
 * true and no help.
 */
public final class Refusals {

  private Refusals() {}

  /** The reason to name, or null when the decision gives none. */
  public static DecisionReason blame(PolicyDecision decision) {
    return decision == null ? null : blame(decision.getReasons());
  }

  /** As {@link #blame(PolicyDecision)}, from the reasons alone. */
  public static DecisionReason blame(List<DecisionReason> reasons) {
    if (reasons == null) {
      return null;
    }
    List<DecisionReason> unmatched = new ArrayList<>();
    List<DecisionReason> holding = new ArrayList<>();
    DecisionReason composition = null;
    for (DecisionReason reason : reasons) {
      boolean deny = reason.getEffect() == DecisionReason.Effect.DENY;
      if (Boolean.TRUE.equals(reason.getMatched())) {
        if (deny) {
          return reason;
        }
        continue;
      }
      if (engine(reason)) {
        if (composition == null) {
          composition = reason;
        }
      } else if (deny
          || reason.getPolicyType() == DecisionReason.PolicyType.DATA
          || reason.getPolicyName().startsWith("grant:")) {
        continue;
      } else if (reason.getExplanation() != null
          && reason.getExplanation().startsWith(PolicyEngine.HOLDS_THE_GATE)) {
        holding.add(reason);
      } else {
        unmatched.add(reason);
      }
    }
    Set<String> gate = new HashSet<>();
    for (DecisionReason reason : holding) {
      gate.add(key(reason));
    }
    DecisionReason named = among(unmatched, gate);
    if (named == null) {
      named = among(unmatched, Set.of());
    }
    if (named == null && !holding.isEmpty()) {
      named = holding.get(0);
    }
    return named != null ? named : composition;
  }

  /** True for the engine's own lines, which name no policy a person could ask about. */
  public static boolean engine(DecisionReason reason) {
    return reason.getPolicyName() == null || reason.getPolicyName().startsWith("(");
  }

  /**
   * From among the policies in {@code gate} when it is not empty: a near miss,
   * otherwise the first that says why, otherwise the first.
   */
  private static DecisionReason among(List<DecisionReason> unmatched, Set<String> gate) {
    DecisionReason explained = null;
    DecisionReason first = null;
    for (DecisionReason reason : unmatched) {
      if (!gate.isEmpty() && !gate.contains(key(reason))) {
        continue;
      }
      if (SubjectMatcher.nearMiss(reason.getExplanation())) {
        return reason;
      }
      if (explained == null && reason.getExplanation() != null && !reason.getExplanation().isBlank()) {
        explained = reason;
      }
      if (first == null) {
        first = reason;
      }
    }
    return explained != null ? explained : first;
  }

  /** One policy's reasons share this, whichever of them is being read. */
  private static String key(DecisionReason reason) {
    return reason.getPolicyId() != null ? reason.getPolicyId().toString() : reason.getPolicyName();
  }
}
