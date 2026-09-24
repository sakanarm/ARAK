package com.mfec.dac.access;

import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.PolicyDecision;
import java.util.List;

/**
 * Whether a person can read a table, and if not, whether asking its owner would
 * change that.
 *
 * <p>One answer shared by the query page's refusal and its suggestions, so the
 * two cannot disagree about whether a "Request access" button belongs next to a
 * table.
 *
 * <h2>Three answers, not two</h2>
 *
 * <ul>
 *   <li><b>readable</b> -- the decision already allows it;
 *   <li><b>requestable</b> -- it does not, but the same decision with a grant
 *       from the owner would;
 *   <li><b>neither</b> -- a DENY, or a higher layer that refuses this person,
 *       would still say no after a grant, so there is nothing for the owner to
 *       approve. The answer says which policy is in the way, because that is who
 *       the person has to talk to instead.
 * </ul>
 */
public class AccessEligibility {

  private final DecisionService decisions;
  private final AccessRequestStore requests;

  public AccessEligibility(DecisionService decisions, AccessRequestStore requests) {
    this.decisions = decisions;
    this.requests = requests;
  }

  /**
   * @param readable the decision allows reading now
   * @param requestable not readable, and a grant from the owner would make it so
   * @param blockedBy when neither: what would still refuse after a grant
   * @param approvers who would decide a request, per OpenMetadata; empty means
   *     no owner is recorded and a platform administrator decides
   * @param openRequestId the caller's request that is already waiting, if any
   */
  public record Verdict(
      String assetFqn,
      boolean readable,
      boolean requestable,
      String blockedBy,
      List<AccessRequestStore.Approver> approvers,
      String openRequestId) {}

  /** Just the first half, for listing many tables at once: can this person read it now. */
  public boolean readable(String username, String assetFqn, String ip, String purpose) {
    PolicyDecision decision =
        decisions.decide(new DecisionService.Ask(username, assetFqn, null, ip, purpose, null));
    return Boolean.TRUE.equals(decision.getAllowed());
  }

  /** The full answer for one table. */
  public Verdict check(String username, String assetFqn, String ip, String purpose) {
    DecisionService.Ask ask = new DecisionService.Ask(username, assetFqn, null, ip, purpose, null);
    if (Boolean.TRUE.equals(decisions.decide(ask).getAllowed())) {
      return new Verdict(assetFqn, true, false, null, List.of(), null);
    }
    PolicyDecision ifGranted = decisions.decideAsIfGranted(ask);
    boolean requestable = Boolean.TRUE.equals(ifGranted.getAllowed());
    String open =
        requests.openRequest(assetFqn, username).map(r -> r.id().toString()).orElse(null);
    return new Verdict(
        assetFqn,
        false,
        requestable,
        requestable ? null : blocker(ifGranted),
        requests.approversFor(assetFqn),
        open);
  }

  /**
   * What still says no once the grant is in the stack.
   *
   * <p>A matched DENY first, because it wins outright; otherwise the first
   * policy that did not apply, which is the higher layer the grant could not
   * pass; otherwise whatever the engine said about composing the layers.
   */
  static String blocker(PolicyDecision decision) {
    List<DecisionReason> reasons = decision.getReasons();
    if (reasons == null) {
      return "the policy decision";
    }
    for (DecisionReason reason : reasons) {
      if (Boolean.TRUE.equals(reason.getMatched())
          && reason.getEffect() == DecisionReason.Effect.DENY) {
        return named(reason);
      }
    }
    // A named policy before the engine's own "(composition)" line, which says
    // that a layer refused but not which policy the person should ask about.
    for (boolean namedOnly : new boolean[] {true, false}) {
      for (DecisionReason reason : reasons) {
        String name = reason.getPolicyName();
        if (!Boolean.TRUE.equals(reason.getMatched())
            && name != null
            && !name.startsWith("grant:")
            && (!namedOnly || !name.startsWith("("))) {
          return named(reason);
        }
      }
    }
    return "the policy decision";
  }

  private static String named(DecisionReason reason) {
    String name = reason.getPolicyName() == null ? "a policy" : reason.getPolicyName();
    return reason.getExplanation() == null || reason.getExplanation().isBlank()
        ? name
        : name + ": " + reason.getExplanation();
  }
}
