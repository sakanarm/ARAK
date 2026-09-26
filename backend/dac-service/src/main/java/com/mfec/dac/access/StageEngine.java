package com.mfec.dac.access;

import com.mfec.dac.access.AccessWorkflow.Join;
import com.mfec.dac.access.AccessWorkflow.OnReject;
import com.mfec.dac.access.AccessWorkflow.Rule;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Counts one stage's answers against its rule (M9 slice 2a).
 *
 * <p>The pool is the people who were asked, fixed when the stage's step opened.
 * Answers are replayed in the order they came and the stage settles at the
 * first answer that decides it, so a later answer never reverses an outcome:
 *
 * <table>
 *   <caption>When a stage passes and when it fails</caption>
 *   <tr><th>Rule</th><th>Passes</th><th>VETO / FIRST_RESPONSE</th><th>QUORUM</th></tr>
 *   <tr><td>ALL</td><td>everyone asked approved</td><td>any rejection fails</td><td>any rejection fails</td></tr>
 *   <tr><td>ANY</td><td>one approval</td><td>a rejection before any approval fails</td>
 *       <td>fails once everyone asked has rejected</td></tr>
 *   <tr><td>AT_LEAST n</td><td>n approvals</td><td>any rejection fails</td>
 *       <td>fails once n approvals can no longer come</td></tr>
 * </table>
 *
 * <p>An administrator answering for a stage they were not asked in overrides
 * it: their answer decides the stage outright. A pool that cannot reach the
 * rule -- nobody asked, or fewer people than approvals needed -- does not fail
 * by itself: the stage is stranded and waits for an administrator.
 *
 * <p>Pure, and so exhaustively tested.
 */
public final class StageEngine {

  private StageEngine() {}

  /** Where a stage stands. */
  public enum Outcome {
    OPEN,
    APPROVED,
    REJECTED
  }

  /** One answer, in the order it came. */
  public record Vote(String voter, boolean approve, boolean override) {}

  /**
   * The count.
   *
   * @param needed how many approvals pass the stage
   * @param open how many who were asked have yet to answer
   * @param stranded nobody, or too few, were asked for the rule ever to pass
   * @param decidedBy the answer that settled it; -1 while it is open
   */
  public record Tally(
      Outcome outcome, int approvals, int rejections, int open, int needed, boolean stranded, int decidedBy) {}

  /** How many approvals a stage needs from a pool of this size. */
  public static int needed(Rule rule, Integer minApprovals, int poolSize) {
    return switch (rule) {
      case ALL -> poolSize;
      case ANY -> 1;
      case AT_LEAST -> minApprovals == null ? 1 : minApprovals;
    };
  }

  /**
   * Counts the answers.
   *
   * @param pool the usernames who were asked; compared without case
   * @param votes the answers in the order they came
   */
  public static Tally tally(
      Rule rule, Integer minApprovals, OnReject onReject, Collection<String> pool, List<Vote> votes) {
    Set<String> asked = new HashSet<>();
    for (String person : pool) {
      if (person != null) {
        asked.add(person.toLowerCase(Locale.ROOT));
      }
    }
    int needed = needed(rule, minApprovals, asked.size());
    boolean stranded = asked.isEmpty() || asked.size() < needed;

    Set<String> answered = new HashSet<>();
    int approvals = 0;
    int rejections = 0;
    for (int i = 0; i < votes.size(); i++) {
      Vote vote = votes.get(i);
      String who = vote.voter() == null ? "" : vote.voter().toLowerCase(Locale.ROOT);
      boolean inPool = asked.contains(who);
      if (vote.override() && !inPool) {
        // An administrator from outside the pool decides the stage outright.
        if (vote.approve()) {
          approvals++;
        } else {
          rejections++;
        }
        int open = asked.size() - answered.size();
        return new Tally(
            vote.approve() ? Outcome.APPROVED : Outcome.REJECTED,
            approvals,
            rejections,
            open,
            needed,
            stranded,
            i);
      }
      if (!inPool || !answered.add(who)) {
        // Not asked, or asked and already answered: it does not count.
        continue;
      }
      if (vote.approve()) {
        approvals++;
      } else {
        rejections++;
      }
      int open = asked.size() - answered.size();
      Outcome settled = settle(rule, onReject, needed, approvals, rejections, open);
      if (settled != Outcome.OPEN) {
        return new Tally(settled, approvals, rejections, open, needed, stranded, i);
      }
    }
    return new Tally(Outcome.OPEN, approvals, rejections, asked.size() - answered.size(), needed, stranded, -1);
  }

  private static Outcome settle(
      Rule rule, OnReject onReject, int needed, int approvals, int rejections, int open) {
    if (needed > 0 && approvals >= needed) {
      return Outcome.APPROVED;
    }
    if (rejections == 0) {
      return Outcome.OPEN;
    }
    if (rule == Rule.ALL) {
      return Outcome.REJECTED;
    }
    return switch (onReject) {
      case VETO, FIRST_RESPONSE -> Outcome.REJECTED;
      case QUORUM -> approvals + open < needed ? Outcome.REJECTED : Outcome.OPEN;
    };
  }

  /** What a request does once one of its stages settles. */
  public enum Next {
    /** Some stage of the current step is still waiting. */
    WAIT,
    /** A stage failed: the request is rejected, and every open stage closes. */
    REJECT,
    /** The step passed and another follows: open it. */
    ADVANCE,
    /** The last step passed: the request is approved and waits to be configured. */
    APPROVE
  }

  /** Where the request goes from here, when every stage of the step must pass. */
  public static Next next(List<Outcome> currentStep, boolean moreSteps) {
    return next(currentStep, moreSteps, Join.ALL);
  }

  /**
   * Where the request goes from here.
   *
   * @param currentStep the outcomes of the stages in the step now open
   * @param moreSteps whether a later step follows
   * @param join whether the step needs all its stages or any one of them
   */
  public static Next next(List<Outcome> currentStep, boolean moreSteps, Join join) {
    boolean passed =
        join == Join.ANY
            ? currentStep.contains(Outcome.APPROVED)
            : !currentStep.contains(Outcome.OPEN);
    boolean failed =
        join == Join.ANY
            ? !currentStep.isEmpty() && currentStep.stream().allMatch(o -> o == Outcome.REJECTED)
            : currentStep.contains(Outcome.REJECTED);
    if (failed) {
      return Next.REJECT;
    }
    if (!passed) {
      return Next.WAIT;
    }
    return moreSteps ? Next.ADVANCE : Next.APPROVE;
  }
}
