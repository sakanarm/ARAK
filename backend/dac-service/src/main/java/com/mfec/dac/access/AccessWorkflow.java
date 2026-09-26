package com.mfec.dac.access;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Who approves an access request, in what order, by what rule, and who then
 * configures the access (M9 slice 2a).
 *
 * <p>A workflow is a list of stages. Stages that share a step run side by side
 * and, by the step's {@link Join}, all of them must pass or any one of them is
 * enough; steps run one after another. Each stage names its
 * approvers as seats -- a person, a team, an app role, or whoever holds a part
 * on the table itself (its owners, its data steward, its data custodian) -- and
 * the seats are resolved to people when the stage's step opens. A stage passes
 * by its {@link Rule}, and what a rejection does is its {@link OnReject}.
 *
 * <p>Pure: no database, so the rules can be tested exhaustively.
 */
public final class AccessWorkflow {

  private AccessWorkflow() {}

  /** How many stages a workflow may have: a chain longer than this is a design problem. */
  public static final int MAX_STAGES = 12;

  /** How many seats a stage may have. */
  public static final int MAX_SEATS = 25;

  /** Who a seat names. */
  public enum Kind {
    /** One person, by username or email. */
    USER(true),
    /** Every member of a team or group, however deeply nested. */
    TEAM(true),
    /** Whoever holds an app role over the table: global, or scoped to it or above it. */
    ROLE(true),
    /** The table's owners as OpenMetadata records them. */
    ASSET_OWNERS(false),
    /** Whoever the table's {@code dataSteward} custom property names. */
    DATA_STEWARD(false),
    /** Whoever the table's {@code dataCustodian} custom property names. */
    DATA_CUSTODIAN(false);

    private final boolean named;

    Kind(boolean named) {
      this.named = named;
    }

    /** Whether the seat needs a name: a person, a team, a role; not "the owners". */
    public boolean named() {
      return named;
    }
  }

  /** How many approvals a stage needs. */
  public enum Rule {
    /** Everyone who was asked. */
    ALL,
    /** Any one of them. */
    ANY,
    /** At least {@code minApprovals} of them. */
    AT_LEAST
  }

  /** What a rejection does to a stage. */
  public enum OnReject {
    /** One rejection fails the stage, and with it the request, at once. */
    VETO,
    /**
     * A rejection fails the stage only once the approvals it still needs can
     * no longer come: the others who were asked may still carry it.
     */
    QUORUM,
    /**
     * The first answer decides the stage, whichever way it goes. Only with
     * {@link Rule#ANY}: with any other rule the first answer cannot be enough.
     */
    FIRST_RESPONSE
  }

  /**
   * How the stages of one step, run side by side, add up to the step.
   *
   * <p>Held on each stage of the step and always the same across them; a step
   * with one stage is {@link #ALL}, where the two mean the same.
   */
  public enum Join {
    /** Every stage must pass; one failing rejects the request. */
    ALL,
    /**
     * One stage passing passes the step and closes the others; the request is
     * rejected only once every stage of the step has failed.
     */
    ANY
  }

  /**
   * One seat.
   *
   * @param name the person, team or role; null for the table's own parts
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Seat(Kind kind, String name) {

    public static Seat of(Kind kind) {
      return new Seat(kind, null);
    }

    /** How the page names it. */
    public String describe() {
      if (kind == null) {
        return "?";
      }
      return switch (kind) {
        case USER -> name;
        case TEAM -> "Team " + name;
        case ROLE -> "Role " + roleLabel(name);
        case ASSET_OWNERS -> "Owners of the table";
        case DATA_STEWARD -> "Data steward";
        case DATA_CUSTODIAN -> "Data custodian";
      };
    }
  }

  /** One stage. {@code minApprovals} is set for {@link Rule#AT_LEAST} and only then. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Stage(
      int step,
      String name,
      Rule rule,
      Integer minApprovals,
      OnReject onReject,
      List<Seat> approvers,
      Join join) {

    public Stage {
      join = join == null ? Join.ALL : join;
    }

    public Stage(
        int step, String name, Rule rule, Integer minApprovals, OnReject onReject, List<Seat> approvers) {
      this(step, name, rule, minApprovals, onReject, approvers, Join.ALL);
    }
  }

  /**
   * A workflow as stored.
   *
   * @param id null for the built-in one
   * @param scopeFqn null for the organisation's default
   */
  public record Workflow(
      UUID id,
      String name,
      String description,
      String scopeFqn,
      boolean enabled,
      List<Stage> stages,
      List<Seat> configurers) {

    public boolean builtIn() {
      return id == null;
    }
  }

  /** What an administrator sends to create or replace a workflow. */
  public record Draft(
      String name,
      String description,
      String scopeFqn,
      Boolean enabled,
      List<Stage> stages,
      List<Seat> configurers) {}

  /** The workflow that applies when nobody has configured one. */
  public static Workflow builtIn() {
    return new Workflow(
        null,
        "Built-in",
        "Any one owner of the table approves; the owners or the data custodian configure it.",
        null,
        true,
        List.of(
            new Stage(1, "Owner approval", Rule.ANY, null, OnReject.VETO, List.of(Seat.of(Kind.ASSET_OWNERS)))),
        defaultConfigurers());
  }

  /** Who configures when a workflow names nobody. */
  public static List<Seat> defaultConfigurers() {
    return List.of(Seat.of(Kind.ASSET_OWNERS), Seat.of(Kind.DATA_CUSTODIAN));
  }

  /**
   * Checks a draft and returns it cleaned: names trimmed, steps renumbered
   * densely from 1 in their order, stages sorted by step.
   *
   * @throws IllegalArgumentException naming the first thing wrong, in words the
   *     editor can show beside the field
   */
  public static Draft validate(Draft draft) {
    if (draft == null) {
      throw new IllegalArgumentException("Send a workflow");
    }
    String name = trim(draft.name());
    if (name == null) {
      throw new IllegalArgumentException("Name the workflow");
    }
    if (name.length() > 200) {
      throw new IllegalArgumentException("Keep the name under 200 characters");
    }
    List<Stage> stages = draft.stages() == null ? List.of() : draft.stages();
    if (stages.isEmpty()) {
      throw new IllegalArgumentException("A workflow needs at least one stage");
    }
    if (stages.size() > MAX_STAGES) {
      throw new IllegalArgumentException("At most " + MAX_STAGES + " stages");
    }

    List<Stage> sorted = new ArrayList<>(stages);
    for (int i = 0; i < sorted.size(); i++) {
      if (sorted.get(i) == null) {
        throw new IllegalArgumentException("Stage " + (i + 1) + " is empty");
      }
      if (sorted.get(i).step() < 1) {
        throw new IllegalArgumentException("Stage " + (i + 1) + ": steps count from 1");
      }
    }
    // Stable: stages that share a step keep the order they were written in.
    sorted.sort(java.util.Comparator.comparingInt(Stage::step));

    List<Stage> clean = new ArrayList<>(sorted.size());
    int previousStep = -1;
    int dense = 0;
    java.util.Set<String> names = new java.util.HashSet<>();
    for (Stage stage : sorted) {
      if (stage.step() != previousStep) {
        dense++;
        previousStep = stage.step();
      }
      String label = trim(stage.name());
      String where = "Stage \"" + (label == null ? "untitled" : label) + "\"";
      if (label == null) {
        throw new IllegalArgumentException("Name every stage");
      }
      if (!names.add(label.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException(where + " appears twice; give each stage its own name");
      }
      if (stage.rule() == null) {
        throw new IllegalArgumentException(where + ": choose how many approvals it needs");
      }
      if (stage.onReject() == null) {
        throw new IllegalArgumentException(where + ": choose what a rejection does");
      }
      List<Seat> seats = seats(stage.approvers(), where);
      if (seats.isEmpty()) {
        throw new IllegalArgumentException(where + ": name at least one approver");
      }
      Integer min = null;
      if (stage.rule() == Rule.AT_LEAST) {
        if (stage.minApprovals() == null || stage.minApprovals() < 1) {
          throw new IllegalArgumentException(where + ": say how many approvals, at least 1");
        }
        if (stage.minApprovals() > 100) {
          throw new IllegalArgumentException(where + ": at most 100 approvals");
        }
        min = stage.minApprovals();
      }
      if (stage.onReject() == OnReject.FIRST_RESPONSE && stage.rule() != Rule.ANY) {
        throw new IllegalArgumentException(
            where
                + ": \"the first answer decides\" needs a stage that one approval passes;"
                + " set it to \"any one\", or choose another way to handle a rejection");
      }
      clean.add(new Stage(dense, label, stage.rule(), min, stage.onReject(), seats, stage.join()));
    }

    // One join per step: the stages that run together say it together, and a
    // step with one stage has nothing to join.
    java.util.Map<Integer, List<Stage>> bySteps = new java.util.LinkedHashMap<>();
    for (Stage stage : clean) {
      bySteps.computeIfAbsent(stage.step(), k -> new ArrayList<>()).add(stage);
    }
    for (int i = 0; i < clean.size(); i++) {
      Stage stage = clean.get(i);
      List<Stage> together = bySteps.get(stage.step());
      if (together.stream().map(Stage::join).distinct().count() > 1) {
        throw new IllegalArgumentException(
            "Step " + stage.step() + ": the stages that run together need one rule for the step;"
                + " either all of them must approve, or any one is enough");
      }
      if (together.size() == 1 && stage.join() != Join.ALL) {
        clean.set(
            i,
            new Stage(
                stage.step(), stage.name(), stage.rule(), stage.minApprovals(), stage.onReject(),
                stage.approvers(), Join.ALL));
      }
    }

    List<Seat> configurers = seats(draft.configurers(), "Configured by");
    String scope = trim(draft.scopeFqn());
    String description = trim(draft.description());
    return new Draft(
        name,
        description,
        scope,
        draft.enabled() == null ? Boolean.TRUE : draft.enabled(),
        List.copyOf(clean),
        configurers);
  }

  private static List<Seat> seats(List<Seat> seats, String where) {
    if (seats == null) {
      return List.of();
    }
    if (seats.size() > MAX_SEATS) {
      throw new IllegalArgumentException(where + ": at most " + MAX_SEATS + " approvers");
    }
    List<Seat> out = new ArrayList<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (Seat seat : seats) {
      if (seat == null || seat.kind() == null) {
        throw new IllegalArgumentException(where + ": choose what kind of approver each one is");
      }
      String name = seat.kind().named() ? trim(seat.name()) : null;
      if (seat.kind().named() && name == null) {
        throw new IllegalArgumentException(
            where + ": name the " + seat.kind().name().toLowerCase(Locale.ROOT));
      }
      if (seat.kind() == Kind.ROLE) {
        name = name.toUpperCase(Locale.ROOT);
        if (!ROLES.contains(name)) {
          throw new IllegalArgumentException(where + ": there is no app role " + seat.name());
        }
      }
      String key = seat.kind() + "|" + (name == null ? "" : name.toLowerCase(Locale.ROOT));
      if (seen.add(key)) {
        out.add(new Seat(seat.kind(), name));
      }
    }
    return List.copyOf(out);
  }

  /** The app roles a ROLE seat may name, as V2 lists them. */
  /** "Data owner" for DATA_OWNER: how a requester reads the role they wait on. */
  static String roleLabel(String role) {
    if (role == null) {
      return "?";
    }
    return switch (role.toUpperCase(Locale.ROOT)) {
      case "PLATFORM_ADMIN" -> "Platform administrator";
      case "POLICY_AUTHOR" -> "Policy author";
      case "DATA_OWNER" -> "Data owner";
      case "AUDITOR" -> "Auditor";
      case "REQUESTER" -> "Requester";
      default -> role;
    };
  }

  static final List<String> ROLES =
      List.of("PLATFORM_ADMIN", "POLICY_AUTHOR", "DATA_OWNER", "AUDITOR", "REQUESTER");

  private static String trim(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
