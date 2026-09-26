package com.mfec.dac.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.access.AccessWorkflow.Draft;
import com.mfec.dac.access.AccessWorkflow.Join;
import com.mfec.dac.access.AccessWorkflow.Kind;
import com.mfec.dac.access.AccessWorkflow.OnReject;
import com.mfec.dac.access.AccessWorkflow.Rule;
import com.mfec.dac.access.AccessWorkflow.Seat;
import com.mfec.dac.access.AccessWorkflow.Stage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** What an administrator may save as a workflow, and how it is cleaned on the way in. */
class AccessWorkflowTest {

  private static final Seat OWNERS = Seat.of(Kind.ASSET_OWNERS);

  private static Stage stage(int step, String name, Rule rule, Integer min, OnReject onReject, Seat... seats) {
    return new Stage(step, name, rule, min, onReject, Arrays.asList(seats));
  }

  private static Stage any(int step, String name) {
    return stage(step, name, Rule.ANY, null, OnReject.VETO, OWNERS);
  }

  private static Stage anyJoin(int step, String name) {
    return new Stage(step, name, Rule.ANY, null, OnReject.VETO, List.of(OWNERS), Join.ANY);
  }

  private static Draft draft(Stage... stages) {
    return new Draft("Finance review", null, null, null, Arrays.asList(stages), null);
  }

  private static String refusal(Draft draft) {
    try {
      AccessWorkflow.validate(draft);
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    }
    throw new AssertionError("expected a refusal");
  }

  @Nested
  @DisplayName("cleaning")
  class Cleaning {

    @Test
    @DisplayName("stages that run together keep their join; a stage alone has nothing to join")
    void joins() {
      Draft clean =
          AccessWorkflow.validate(
              draft(
                  anyJoin(1, "Owner"),
                  anyJoin(1, "Steward"),
                  anyJoin(2, "Security")));
      assertThat(clean.stages())
          .extracting(s -> s.name() + " " + s.join())
          .containsExactly("Owner ANY", "Steward ANY", "Security ALL");
      // Written before joins existed: the stored stage reads as ALL.
      assertThat(any(1, "Owner").join()).isEqualTo(Join.ALL);
    }

    @Test
    void stagesThatRunTogetherAgreeOnTheJoin() {
      assertThat(refusal(draft(anyJoin(1, "Owner"), any(1, "Steward"))))
          .contains("Step 1")
          .contains("any one is enough");
    }

    @Test
    void stepsAreRenumberedDenselyAndSortedStably() {
      Draft clean =
          AccessWorkflow.validate(
              draft(any(7, "Security"), any(3, "Owner"), any(7, "Privacy"), any(3, "Steward")));
      assertThat(clean.stages())
          .extracting(s -> s.step() + " " + s.name())
          .containsExactly("1 Owner", "1 Steward", "2 Security", "2 Privacy");
    }

    @Test
    void namesAreTrimmedAndDefaultsFilled() {
      Draft clean =
          AccessWorkflow.validate(
              new Draft("  Finance  ", "   ", "  prod.Sales ", null, List.of(any(1, " Owner ")), null));
      assertThat(clean.name()).isEqualTo("Finance");
      assertThat(clean.description()).isNull();
      assertThat(clean.scopeFqn()).isEqualTo("prod.Sales");
      assertThat(clean.enabled()).isTrue();
      assertThat(clean.stages().get(0).name()).isEqualTo("Owner");
      assertThat(clean.configurers()).isEmpty();
      assertThat(AccessWorkflow.validate(new Draft("x", null, "  ", false, List.of(any(1, "a")), null)).scopeFqn())
          .isNull();
    }

    @Test
    void seatsAreDeduplicatedAndRolesUppercased() {
      Draft clean =
          AccessWorkflow.validate(
              draft(
                  stage(
                      1,
                      "Review",
                      Rule.ALL,
                      null,
                      OnReject.VETO,
                      new Seat(Kind.USER, "Ann"),
                      new Seat(Kind.USER, " ann "),
                      new Seat(Kind.ROLE, "data_owner"),
                      new Seat(Kind.ASSET_OWNERS, "ignored"),
                      OWNERS)));
      assertThat(clean.stages().get(0).approvers())
          .containsExactly(new Seat(Kind.USER, "Ann"), new Seat(Kind.ROLE, "DATA_OWNER"), OWNERS);
    }

    @Test
    void aMinimumIsKeptOnlyForAtLeast() {
      Draft clean =
          AccessWorkflow.validate(
              draft(
                  stage(1, "A", Rule.ANY, 3, OnReject.VETO, OWNERS),
                  stage(1, "B", Rule.AT_LEAST, 2, OnReject.QUORUM, OWNERS)));
      assertThat(clean.stages()).extracting(Stage::minApprovals).containsExactly(null, 2);
    }
  }

  @Nested
  @DisplayName("refusals")
  class Refusals {

    @Test
    void theWorkflowItself() {
      assertThat(refusal(null)).isEqualTo("Send a workflow");
      assertThat(refusal(new Draft(" ", null, null, null, List.of(any(1, "a")), null)))
          .isEqualTo("Name the workflow");
      assertThat(refusal(new Draft("x".repeat(201), null, null, null, List.of(any(1, "a")), null)))
          .isEqualTo("Keep the name under 200 characters");
      assertThat(refusal(draft())).isEqualTo("A workflow needs at least one stage");
      assertThat(refusal(new Draft("x", null, null, null, null, null)))
          .isEqualTo("A workflow needs at least one stage");
      List<Stage> many = new ArrayList<>();
      for (int i = 0; i <= AccessWorkflow.MAX_STAGES; i++) {
        many.add(any(1, "s" + i));
      }
      assertThat(refusal(new Draft("x", null, null, null, many, null))).isEqualTo("At most 12 stages");
    }

    @Test
    void eachStage() {
      assertThat(refusal(draft(any(1, "a"), null))).isEqualTo("Stage 2 is empty");
      assertThat(refusal(draft(any(0, "a")))).isEqualTo("Stage 1: steps count from 1");
      assertThat(refusal(draft(any(1, " ")))).isEqualTo("Name every stage");
      assertThat(refusal(draft(any(1, "Owner"), any(2, "owner "))))
          .isEqualTo("Stage \"owner\" appears twice; give each stage its own name");
      assertThat(refusal(draft(stage(1, "a", null, null, OnReject.VETO, OWNERS))))
          .isEqualTo("Stage \"a\": choose how many approvals it needs");
      assertThat(refusal(draft(stage(1, "a", Rule.ANY, null, null, OWNERS))))
          .isEqualTo("Stage \"a\": choose what a rejection does");
      assertThat(refusal(draft(stage(1, "a", Rule.ANY, null, OnReject.VETO))))
          .isEqualTo("Stage \"a\": name at least one approver");
    }

    @Test
    void atLeastNeedsASensibleNumber() {
      assertThat(refusal(draft(stage(1, "a", Rule.AT_LEAST, null, OnReject.VETO, OWNERS))))
          .isEqualTo("Stage \"a\": say how many approvals, at least 1");
      assertThat(refusal(draft(stage(1, "a", Rule.AT_LEAST, 0, OnReject.VETO, OWNERS))))
          .isEqualTo("Stage \"a\": say how many approvals, at least 1");
      assertThat(refusal(draft(stage(1, "a", Rule.AT_LEAST, 101, OnReject.VETO, OWNERS))))
          .isEqualTo("Stage \"a\": at most 100 approvals");
    }

    @Test
    void theFirstAnswerDecidesOnlyWhereOneApprovalIsEnough() {
      assertThat(refusal(draft(stage(1, "a", Rule.ALL, null, OnReject.FIRST_RESPONSE, OWNERS))))
          .startsWith("Stage \"a\": \"the first answer decides\" needs a stage that one approval passes");
      assertThat(refusal(draft(stage(1, "a", Rule.AT_LEAST, 1, OnReject.FIRST_RESPONSE, OWNERS))))
          .contains("the first answer decides");
      assertThat(
              AccessWorkflow.validate(draft(stage(1, "a", Rule.ANY, null, OnReject.FIRST_RESPONSE, OWNERS)))
                  .stages())
          .hasSize(1);
    }

    @Test
    void eachSeat() {
      assertThat(refusal(draft(stage(1, "a", Rule.ANY, null, OnReject.VETO, new Seat(null, "x")))))
          .isEqualTo("Stage \"a\": choose what kind of approver each one is");
      assertThat(refusal(draft(stage(1, "a", Rule.ANY, null, OnReject.VETO, (Seat) null))))
          .isEqualTo("Stage \"a\": choose what kind of approver each one is");
      assertThat(refusal(draft(stage(1, "a", Rule.ANY, null, OnReject.VETO, new Seat(Kind.TEAM, " ")))))
          .isEqualTo("Stage \"a\": name the team");
      assertThat(refusal(draft(stage(1, "a", Rule.ANY, null, OnReject.VETO, new Seat(Kind.ROLE, "wizard")))))
          .isEqualTo("Stage \"a\": there is no app role wizard");
      Seat[] many = new Seat[AccessWorkflow.MAX_SEATS + 1];
      for (int i = 0; i < many.length; i++) {
        many[i] = new Seat(Kind.USER, "u" + i);
      }
      assertThat(refusal(draft(stage(1, "a", Rule.ANY, null, OnReject.VETO, many))))
          .isEqualTo("Stage \"a\": at most 25 approvers");
    }

    @Test
    void theConfigurersToo() {
      assertThat(
              refusal(
                  new Draft(
                      "x", null, null, null, List.of(any(1, "a")), List.of(new Seat(Kind.USER, "")))))
          .isEqualTo("Configured by: name the user");
    }
  }

  @Test
  @DisplayName("the built-in workflow passes its own checks")
  void builtInIsValid() {
    AccessWorkflow.Workflow builtIn = AccessWorkflow.builtIn();
    assertThat(builtIn.builtIn()).isTrue();
    Draft again =
        AccessWorkflow.validate(
            new Draft(
                builtIn.name(),
                builtIn.description(),
                builtIn.scopeFqn(),
                builtIn.enabled(),
                builtIn.stages(),
                builtIn.configurers()));
    assertThat(again.stages()).isEqualTo(builtIn.stages());
    assertThat(again.configurers()).isEqualTo(AccessWorkflow.defaultConfigurers());
  }

  @Test
  @DisplayName("seats name themselves for the page")
  void describe() {
    assertThat(new Seat(Kind.USER, "ann").describe()).isEqualTo("ann");
    assertThat(new Seat(Kind.TEAM, "Finance").describe()).isEqualTo("Team Finance");
    assertThat(new Seat(Kind.ROLE, "AUDITOR").describe()).isEqualTo("Role Auditor");
    assertThat(OWNERS.describe()).isEqualTo("Owners of the table");
    assertThat(Seat.of(Kind.DATA_STEWARD).describe()).isEqualTo("Data steward");
    assertThat(Seat.of(Kind.DATA_CUSTODIAN).describe()).isEqualTo("Data custodian");
    assertThat(new Seat(null, null).describe()).isEqualTo("?");
  }
}
