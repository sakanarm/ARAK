package com.mfec.dac.engine;

import com.mfec.dac.schema.entity.policy.Exemption;
import com.mfec.dac.schema.entity.policy.Policy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * How long a decision stays true on its own (FR-5.5).
 *
 * <p>A cache in front of an access decision is only safe if something says when
 * the decision stops being the answer. Two different things can end it. One is
 * an edit — a policy changed, a person joined a group, a tag moved — and that is
 * not this class's problem: edits are announced, and the cache is emptied.
 *
 * <p>The other is the clock, and nobody announces the clock. A policy that
 * grants access between 08:00 and 18:00 stops granting it at 18:00 with no
 * write anywhere, and a cache that did not know that would keep answering
 * "allowed" all evening. This class reads a policy stack and says when the
 * earliest such moment is.
 *
 * <h2>Why minute granularity for windows</h2>
 *
 * <p>{@link PolicyEngine} already stamps every decision with a cache key that
 * folds in the current minute whenever a bound policy carries a time rule. That
 * is the engine's own declaration of how finely a time-windowed decision may be
 * reused, and this class deliberately repeats it rather than computing the exact
 * next window edge. A second, more precise rule living here would be a second
 * opinion about the same question, and the two would eventually disagree — at
 * which point the cache would be serving decisions the engine considered stale.
 *
 * <p>Fixed instants are different: {@code validUntil} on a policy and
 * {@code expiresAt} on an exemption are exact, and a grant that was written to
 * end at 17:00:00 should end at 17:00:00, not at 17:00:59. Those are taken
 * exactly.
 *
 * <p>What is not covered here is covered by the cache's own time-to-live. Should
 * a future schema add a time-dependent term that this class does not know to
 * look for, the result is bounded staleness rather than an entry that never
 * expires.
 */
public final class DecisionValidity {

  private DecisionValidity() {}

  /**
   * The earliest instant after {@code at} at which the clock alone could change
   * the answer, or empty when nothing in the stack depends on the clock.
   *
   * @param policies the composed stack the decision was made from — the same
   *     list handed to {@link PolicyEngine#evaluate}, not only the ones that
   *     matched, because a policy that does not bind today may bind tomorrow
   */
  public static Optional<Instant> until(List<Policy> policies, Instant at) {
    if (policies == null || policies.isEmpty() || at == null) {
      return Optional.empty();
    }
    Instant earliest = null;

    for (Policy policy : policies) {
      if (policy == null) {
        continue;
      }
      if (policy.getSubject() != null && policy.getSubject().getTime() != null) {
        // The engine's minute bucket, repeated verbatim. See the class note.
        earliest = earlier(earliest, at.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES));
      }
      earliest = earlier(earliest, future(policy.getValidFrom(), at));
      earliest = earlier(earliest, future(policy.getValidUntil(), at));

      List<Exemption> exemptions = policy.getExemptions();
      if (exemptions != null) {
        for (Exemption exemption : exemptions) {
          if (exemption != null) {
            earliest = earlier(earliest, future(exemption.getExpiresAt(), at));
          }
        }
      }
    }
    return Optional.ofNullable(earliest);
  }

  /** A boundary already in the past cannot change anything again. */
  private static Instant future(Instant candidate, Instant at) {
    return candidate != null && candidate.isAfter(at) ? candidate : null;
  }

  private static Instant earlier(Instant current, Instant candidate) {
    if (candidate == null) {
      return current;
    }
    return current == null || candidate.isBefore(current) ? candidate : current;
  }
}
