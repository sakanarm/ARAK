package com.mfec.dac.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.access.AccessWorkflow.Join;
import com.mfec.dac.access.AccessWorkflow.OnReject;
import com.mfec.dac.access.AccessWorkflow.Rule;
import com.mfec.dac.access.StageEngine.Next;
import com.mfec.dac.access.StageEngine.Outcome;
import com.mfec.dac.access.StageEngine.Tally;
import com.mfec.dac.access.StageEngine.Vote;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** How one stage's answers count against its rule, and where the request goes next. */
class StageEngineTest {

  private static final List<String> THREE = List.of("ann", "bob", "cat");

  private static Vote yes(String who) {
    return new Vote(who, true, false);
  }

  private static Vote no(String who) {
    return new Vote(who, false, false);
  }

  private static Tally tally(Rule rule, Integer min, OnReject onReject, List<String> pool, Vote... votes) {
    return StageEngine.tally(rule, min, onReject, pool, List.of(votes));
  }

  @Nested
  @DisplayName("ALL")
  class All {

    @Test
    void passesOnlyWhenEveryoneApproved() {
      Tally two = tally(Rule.ALL, null, OnReject.VETO, THREE, yes("ann"), yes("bob"));
      assertThat(two.outcome()).isEqualTo(Outcome.OPEN);
      assertThat(two.open()).isEqualTo(1);
      assertThat(two.needed()).isEqualTo(3);

      Tally three = tally(Rule.ALL, null, OnReject.VETO, THREE, yes("ann"), yes("bob"), yes("cat"));
      assertThat(three.outcome()).isEqualTo(Outcome.APPROVED);
      assertThat(three.decidedBy()).isEqualTo(2);
    }

    @Test
    void oneRejectionFailsItWhateverTheSetting() {
      for (OnReject onReject : List.of(OnReject.VETO, OnReject.QUORUM)) {
        Tally t = tally(Rule.ALL, null, onReject, THREE, yes("ann"), no("bob"));
        assertThat(t.outcome()).as(onReject.name()).isEqualTo(Outcome.REJECTED);
        assertThat(t.decidedBy()).isEqualTo(1);
      }
    }
  }

  @Nested
  @DisplayName("ANY")
  class Any {

    @Test
    void oneApprovalPasses() {
      Tally t = tally(Rule.ANY, null, OnReject.VETO, THREE, yes("bob"));
      assertThat(t.outcome()).isEqualTo(Outcome.APPROVED);
      assertThat(t.open()).isEqualTo(2);
    }

    @Test
    void vetoFailsOnARejectionBeforeAnyApproval() {
      assertThat(tally(Rule.ANY, null, OnReject.VETO, THREE, no("ann")).outcome())
          .isEqualTo(Outcome.REJECTED);
    }

    @Test
    void quorumFailsOnlyOnceEveryoneRejected() {
      assertThat(tally(Rule.ANY, null, OnReject.QUORUM, THREE, no("ann"), no("bob")).outcome())
          .isEqualTo(Outcome.OPEN);
      // The last one still carries it.
      assertThat(tally(Rule.ANY, null, OnReject.QUORUM, THREE, no("ann"), no("bob"), yes("cat")).outcome())
          .isEqualTo(Outcome.APPROVED);
      Tally all = tally(Rule.ANY, null, OnReject.QUORUM, THREE, no("ann"), no("bob"), no("cat"));
      assertThat(all.outcome()).isEqualTo(Outcome.REJECTED);
      assertThat(all.rejections()).isEqualTo(3);
    }

    @Test
    void theFirstResponseDecidesEitherWay() {
      assertThat(tally(Rule.ANY, null, OnReject.FIRST_RESPONSE, THREE, no("cat"), yes("ann")).outcome())
          .isEqualTo(Outcome.REJECTED);
      assertThat(tally(Rule.ANY, null, OnReject.FIRST_RESPONSE, THREE, yes("cat"), no("ann")).outcome())
          .isEqualTo(Outcome.APPROVED);
    }
  }

  @Nested
  @DisplayName("AT_LEAST n")
  class AtLeast {

    @Test
    void passesAtN() {
      Tally one = tally(Rule.AT_LEAST, 2, OnReject.QUORUM, THREE, yes("ann"));
      assertThat(one.outcome()).isEqualTo(Outcome.OPEN);
      assertThat(one.needed()).isEqualTo(2);
      assertThat(tally(Rule.AT_LEAST, 2, OnReject.QUORUM, THREE, yes("ann"), yes("cat")).outcome())
          .isEqualTo(Outcome.APPROVED);
    }

    @Test
    void quorumFailsOnceNCanNoLongerCome() {
      // 2 of 3: one rejection leaves two who could still approve.
      assertThat(tally(Rule.AT_LEAST, 2, OnReject.QUORUM, THREE, no("ann")).outcome())
          .isEqualTo(Outcome.OPEN);
      Tally t = tally(Rule.AT_LEAST, 2, OnReject.QUORUM, THREE, no("ann"), yes("bob"), no("cat"));
      assertThat(t.outcome()).isEqualTo(Outcome.REJECTED);
      assertThat(t.decidedBy()).isEqualTo(2);
      assertThat(tally(Rule.AT_LEAST, 2, OnReject.QUORUM, THREE, no("ann"), no("bob")).outcome())
          .isEqualTo(Outcome.REJECTED);
    }

    @Test
    void vetoFailsOnTheFirstRejection() {
      assertThat(tally(Rule.AT_LEAST, 2, OnReject.VETO, THREE, yes("ann"), no("bob")).outcome())
          .isEqualTo(Outcome.REJECTED);
    }

    @Test
    void noMinimumCountsAsOne() {
      assertThat(StageEngine.needed(Rule.AT_LEAST, null, 3)).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("who counts")
  class WhoCounts {

    @Test
    void somebodyNotAskedDoesNotCount() {
      Tally t = tally(Rule.ANY, null, OnReject.VETO, THREE, yes("mallory"), no("mallory"));
      assertThat(t.outcome()).isEqualTo(Outcome.OPEN);
      assertThat(t.approvals()).isZero();
      assertThat(t.rejections()).isZero();
    }

    @Test
    void aSecondAnswerFromTheSamePersonDoesNotCount() {
      Tally t = tally(Rule.ALL, null, OnReject.VETO, THREE, yes("ann"), yes("ANN"), yes("bob"));
      assertThat(t.outcome()).isEqualTo(Outcome.OPEN);
      assertThat(t.approvals()).isEqualTo(2);
    }

    @Test
    void namesAreComparedWithoutCase() {
      assertThat(tally(Rule.ANY, null, OnReject.VETO, List.of("Ann"), yes("aNN")).outcome())
          .isEqualTo(Outcome.APPROVED);
    }

    @Test
    void anAnswerAfterTheStageSettledChangesNothing() {
      Tally t = tally(Rule.ANY, null, OnReject.VETO, THREE, yes("ann"), no("bob"));
      assertThat(t.outcome()).isEqualTo(Outcome.APPROVED);
      assertThat(t.decidedBy()).isZero();
      assertThat(t.rejections()).isZero();
    }
  }

  @Nested
  @DisplayName("an administrator from outside the pool")
  class Override {

    @Test
    void decidesTheStageOutright() {
      Tally approve = tally(Rule.ALL, null, OnReject.VETO, THREE, yes("ann"), new Vote("root", true, true));
      assertThat(approve.outcome()).isEqualTo(Outcome.APPROVED);
      assertThat(approve.decidedBy()).isEqualTo(1);
      assertThat(approve.open()).isEqualTo(2);

      Tally reject = tally(Rule.ANY, null, OnReject.QUORUM, THREE, new Vote("root", false, true));
      assertThat(reject.outcome()).isEqualTo(Outcome.REJECTED);
    }

    @Test
    void anAdministratorWhoWasAskedIsCountedLikeAnyoneElse() {
      Tally t = tally(Rule.ALL, null, OnReject.VETO, THREE, new Vote("ann", true, true));
      assertThat(t.outcome()).isEqualTo(Outcome.OPEN);
      assertThat(t.approvals()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("a pool that cannot reach its rule")
  class Stranded {

    @Test
    void nobodyAskedWaitsForAnAdministrator() {
      for (Rule rule : Rule.values()) {
        Tally t = tally(rule, rule == Rule.AT_LEAST ? 1 : null, OnReject.VETO, List.of());
        assertThat(t.stranded()).as(rule.name()).isTrue();
        assertThat(t.outcome()).as(rule.name()).isEqualTo(Outcome.OPEN);
      }
      // ALL of nobody is not "everybody approved".
      assertThat(StageEngine.needed(Rule.ALL, null, 0)).isZero();
      assertThat(tally(Rule.ALL, null, OnReject.VETO, List.of(), yes("ann")).outcome())
          .isEqualTo(Outcome.OPEN);
      assertThat(tally(Rule.ALL, null, OnReject.VETO, List.of(), new Vote("root", true, true)).outcome())
          .isEqualTo(Outcome.APPROVED);
    }

    @Test
    void fewerAskedThanNeededIsStrandedToo() {
      Tally t = tally(Rule.AT_LEAST, 3, OnReject.VETO, List.of("ann", "bob"), yes("ann"), yes("bob"));
      assertThat(t.stranded()).isTrue();
      assertThat(t.outcome()).isEqualTo(Outcome.OPEN);
      assertThat(tally(Rule.AT_LEAST, 2, OnReject.VETO, List.of("ann", "bob")).stranded()).isFalse();
    }

    @Test
    void nullsInThePoolAreIgnored() {
      List<String> pool = new ArrayList<>();
      pool.add(null);
      pool.add("ann");
      Tally t = StageEngine.tally(Rule.ALL, null, OnReject.VETO, pool, List.of(yes("ann")));
      assertThat(t.outcome()).isEqualTo(Outcome.APPROVED);
    }
  }

  /**
   * Every sequence of up to four answers from a pool of three, under every
   * rule and every rejection setting, checked against the table in
   * {@link StageEngine}'s documentation written out a second way.
   */
  @Test
  @DisplayName("every short sequence of answers agrees with the table")
  void exhaustive() {
    List<List<Vote>> sequences = new ArrayList<>();
    sequences(new ArrayList<>(), 4, sequences);
    int checked = 0;
    for (Rule rule : Rule.values()) {
      for (OnReject onReject : OnReject.values()) {
        if (onReject == OnReject.FIRST_RESPONSE && rule != Rule.ANY) {
          continue;
        }
        for (Integer min : rule == Rule.AT_LEAST ? List.of(1, 2, 3) : java.util.Collections.<Integer>singletonList(null)) {
          for (List<Vote> votes : sequences) {
            Tally got = StageEngine.tally(rule, min, onReject, THREE, votes);
            Outcome want = oracle(rule, min == null ? 0 : min, onReject, votes);
            assertThat(got.outcome()).as("%s %s %s %s", rule, min, onReject, votes).isEqualTo(want);
            assertThat(got.approvals() + got.rejections() + got.open())
                .as("every one asked is counted once")
                .isEqualTo(3);
            if (got.outcome() != Outcome.OPEN) {
              // Settled at decidedBy: the answers up to it give the same outcome,
              // and nothing before it had settled it.
              Tally prefix =
                  StageEngine.tally(rule, min, onReject, THREE, votes.subList(0, got.decidedBy() + 1));
              assertThat(prefix.outcome()).isEqualTo(got.outcome());
              Tally before =
                  StageEngine.tally(rule, min, onReject, THREE, votes.subList(0, got.decidedBy()));
              assertThat(before.outcome()).isEqualTo(Outcome.OPEN);
            } else {
              assertThat(got.decidedBy()).isEqualTo(-1);
            }
            checked++;
          }
        }
      }
    }
    assertThat(checked).isGreaterThan(10_000);
  }

  private static void sequences(List<Vote> prefix, int left, List<List<Vote>> out) {
    out.add(List.copyOf(prefix));
    if (left == 0) {
      return;
    }
    for (String who : List.of("ann", "bob", "cat", "eve")) {
      for (boolean approve : new boolean[] {true, false}) {
        prefix.add(new Vote(who, approve, false));
        sequences(prefix, left - 1, out);
        prefix.remove(prefix.size() - 1);
      }
    }
  }

  /** The same rules, written from the table rather than from the code. */
  private static Outcome oracle(Rule rule, int min, OnReject onReject, List<Vote> votes) {
    java.util.Map<String, Boolean> first = new java.util.LinkedHashMap<>();
    for (Vote vote : votes) {
      if (!THREE.contains(vote.voter()) || first.containsKey(vote.voter())) {
        continue;
      }
      first.put(vote.voter(), vote.approve());
      long yes = first.values().stream().filter(b -> b).count();
      long no = first.size() - yes;
      long waiting = THREE.size() - first.size();
      boolean passed =
          switch (rule) {
            case ALL -> yes == THREE.size();
            case ANY -> yes >= 1;
            case AT_LEAST -> yes >= min;
          };
      if (passed) {
        return Outcome.APPROVED;
      }
      if (no > 0) {
        boolean failed =
            switch (rule) {
              case ALL -> true;
              case ANY -> onReject == OnReject.QUORUM ? waiting == 0 : true;
              case AT_LEAST -> onReject == OnReject.QUORUM ? yes + waiting < min : true;
            };
        if (failed) {
          return Outcome.REJECTED;
        }
      }
    }
    return Outcome.OPEN;
  }

  @Nested
  @DisplayName("where the request goes next")
  class Where {

    @Test
    void aFailedStageRejectsEvenWhileOthersWait() {
      assertThat(StageEngine.next(List.of(Outcome.OPEN, Outcome.REJECTED), true)).isEqualTo(Next.REJECT);
      assertThat(StageEngine.next(List.of(Outcome.APPROVED, Outcome.REJECTED), false)).isEqualTo(Next.REJECT);
    }

    @Test
    void anOpenStageHoldsTheStep() {
      assertThat(StageEngine.next(List.of(Outcome.APPROVED, Outcome.OPEN), true)).isEqualTo(Next.WAIT);
    }

    @Test
    void aPassedStepAdvancesOrApproves() {
      assertThat(StageEngine.next(List.of(Outcome.APPROVED, Outcome.APPROVED), true)).isEqualTo(Next.ADVANCE);
      assertThat(StageEngine.next(List.of(Outcome.APPROVED), false)).isEqualTo(Next.APPROVE);
    }

    @Test
    @DisplayName("any one of the step: one passing is enough, even beside a stage still open")
    void anyOnePassesTheStep() {
      Join any = Join.ANY;
      assertThat(StageEngine.next(List.of(Outcome.OPEN, Outcome.APPROVED), true, any)).isEqualTo(Next.ADVANCE);
      assertThat(StageEngine.next(List.of(Outcome.APPROVED, Outcome.OPEN), false, any)).isEqualTo(Next.APPROVE);
      // A failed stage beside one that passed does not undo the pass.
      assertThat(StageEngine.next(List.of(Outcome.REJECTED, Outcome.APPROVED), false, any)).isEqualTo(Next.APPROVE);
    }

    @Test
    @DisplayName("any one of the step: a rejection waits for the others, and fails only when all failed")
    void anyOneFailsOnlyWhenEveryStageFailed() {
      Join any = Join.ANY;
      assertThat(StageEngine.next(List.of(Outcome.REJECTED, Outcome.OPEN), true, any)).isEqualTo(Next.WAIT);
      assertThat(StageEngine.next(List.of(Outcome.OPEN, Outcome.OPEN), true, any)).isEqualTo(Next.WAIT);
      assertThat(StageEngine.next(List.of(Outcome.REJECTED, Outcome.REJECTED), true, any)).isEqualTo(Next.REJECT);
    }

    @Test
    void theTwoArgumentFormIsAllOfTheStep() {
      assertThat(StageEngine.next(List.of(Outcome.OPEN, Outcome.APPROVED), true, Join.ALL)).isEqualTo(Next.WAIT);
      assertThat(StageEngine.next(List.of(Outcome.OPEN, Outcome.REJECTED), true, Join.ALL)).isEqualTo(Next.REJECT);
    }
  }
}
