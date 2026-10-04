package com.mfec.dac.enforcement;

import com.mfec.dac.compiler.sql.PostgresGrantCompiler.AccessLevel;
import com.mfec.dac.engine.PolicyEngine;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.PolicyDecision;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Who goes into a subscription policy's role on a source, worked out from the
 * engine's decisions. No database, no clock: everything it needs is passed in,
 * so every rule below has a unit test.
 *
 * <h2>The rule</h2>
 *
 * A role grants the same thing on every table in it, so a person is a member
 * only when, on <em>every</em> table the policy binds on that source:
 *
 * <ol>
 *   <li>the engine lets them in ({@code allowed}), with every other policy
 *       composed -- a DENY anywhere keeps them out of the whole role, which is
 *       decision B: fail closed, and say which tables they lose;
 *   <li>this policy is one of the reasons -- a direct grant or another
 *       policy letting them in is not this role's business, and an exemption
 *       from this policy is a reason that did not match;
 *   <li>at Read, the decision carries no row filter, mask, hidden column or
 *       unenforceable rule. The role grants plain {@code SELECT}; a data policy
 *       that narrows what this person sees cannot be expressed by it, and
 *       granting it anyway would show them what the data policy hides.
 * </ol>
 *
 * <p>A policy that binds no table on the source has no members: "every table"
 * of nothing is true, and a role that let everybody connect because of it
 * would be the vacuous truth nobody meant.
 *
 * <p>A login is a member when every person mapped to it is. One person who
 * qualifies sharing a login with one who does not keeps the login out, and the
 * plan says why.
 */
public final class NativeMembership {

  private NativeMembership() {}

  /** A login that joins the role, and the people it stands for. */
  public record Member(String login, List<String> people) {}

  /**
   * A person the policy speaks to who will not be in the role.
   *
   * @param login their mapped login, or null when they have none
   * @param reasons one line per table they failed on
   * @param lost the tables the policy lets them into, which the role would
   *     have given them and now will not
   */
  public record Excluded(String person, String login, List<String> reasons, List<String> lost) {}

  /**
   * @param members logins, sorted, that should hold the role
   * @param qualified people who pass every table, mapped or not
   * @param unmapped people who qualify but have no login on this source
   * @param sharedRefused logins left out because someone else on them does not
   *     qualify
   */
  public record Result(
      List<Member> members,
      List<String> qualified,
      List<Excluded> excluded,
      List<String> unmapped,
      List<String> sharedRefused) {

    public SortedSet<String> logins() {
      SortedSet<String> out = new TreeSet<>();
      for (Member member : members) {
        out.add(member.login());
      }
      return out;
    }
  }

  /**
   * @param policyId the policy whose role this is
   * @param tables the bound tables, by FQN, in the order to report them
   * @param decisions each person's decision on each table, by username then
   *     FQN; a person missing a table counts as refused on it
   * @param logins lower-cased username to the logins that person uses here
   */
  public static Result compute(
      UUID policyId,
      AccessLevel level,
      List<String> tables,
      Map<String, Map<String, PolicyDecision>> decisions,
      Map<String, ? extends Collection<String>> logins) {
    Objects.requireNonNull(policyId, "policyId");
    Objects.requireNonNull(level, "level");
    List<String> scope = tables == null ? List.of() : List.copyOf(new LinkedHashSet<>(tables));
    Map<String, Map<String, PolicyDecision>> byPerson =
        decisions == null ? Map.of() : decisions;
    Map<String, ? extends Collection<String>> loginMap = logins == null ? Map.of() : logins;

    Set<String> qualified = new TreeSet<>();
    List<Excluded> excluded = new ArrayList<>();
    Map<String, String> personKey = new TreeMap<>();

    if (!scope.isEmpty()) {
      for (Map.Entry<String, Map<String, PolicyDecision>> entry : byPerson.entrySet()) {
        String person = entry.getKey();
        Map<String, PolicyDecision> perTable = entry.getValue() == null ? Map.of() : entry.getValue();
        List<String> reasons = new ArrayList<>();
        List<String> lost = new ArrayList<>();
        boolean creditedSomewhere = false;
        for (String table : scope) {
          PolicyDecision decision = perTable.get(table);
          boolean credited = credits(decision, policyId);
          creditedSomewhere |= credited;
          String failure = failure(decision, policyId, level);
          if (failure != null) {
            reasons.add(table + ": " + failure);
          } else {
            lost.add(table);
          }
        }
        if (reasons.isEmpty()) {
          qualified.add(person);
          personKey.put(NativeLoginMap.key(person), person);
        } else if (creditedSomewhere) {
          excluded.add(new Excluded(person, firstLogin(loginMap, person), reasons, lost));
        }
      }
    }

    // Login -> everybody mapped to it, qualified or not.
    Map<String, SortedSet<String>> peopleByLogin = new TreeMap<>();
    for (Map.Entry<String, ? extends Collection<String>> entry : loginMap.entrySet()) {
      for (String login : entry.getValue()) {
        peopleByLogin.computeIfAbsent(login, k -> new TreeSet<>()).add(entry.getKey());
      }
    }
    Set<String> qualifiedKeys = new TreeSet<>(personKey.keySet());

    List<Member> members = new ArrayList<>();
    List<String> sharedRefused = new ArrayList<>();
    for (Map.Entry<String, SortedSet<String>> entry : peopleByLogin.entrySet()) {
      SortedSet<String> on = entry.getValue();
      List<String> passing = new ArrayList<>();
      List<String> failing = new ArrayList<>();
      for (String key : on) {
        (qualifiedKeys.contains(key) ? passing : failing).add(key);
      }
      if (passing.isEmpty()) {
        continue;
      }
      List<String> names = passing.stream().map(personKey::get).toList();
      if (failing.isEmpty()) {
        members.add(new Member(entry.getKey(), names));
      } else {
        sharedRefused.add(
            entry.getKey() + " is shared by " + String.join(", ", names)
                + (names.size() == 1 ? " (who qualifies) and " : " (who qualify) and ")
                + String.join(", ", failing)
                + (failing.size() == 1 ? " (who does not)" : " (who do not)")
                + ", so it stays out of the role.");
      }
    }

    List<String> unmapped = new ArrayList<>();
    for (String person : qualified) {
      Collection<String> mapped = loginMap.get(NativeLoginMap.key(person));
      if (mapped == null || mapped.isEmpty()) {
        unmapped.add(person);
      }
    }

    return new Result(
        List.copyOf(members),
        List.copyOf(qualified),
        List.copyOf(excluded),
        List.copyOf(unmapped),
        List.copyOf(sharedRefused));
  }

  /** True when the decision lets the person in and this policy is why. */
  static boolean credits(PolicyDecision decision, UUID policyId) {
    if (decision == null || decision.getReasons() == null) {
      return false;
    }
    for (DecisionReason reason : decision.getReasons()) {
      if (policyId.equals(reason.getPolicyId())
          && reason.getEffect() == DecisionReason.Effect.ALLOW
          && Boolean.TRUE.equals(reason.getMatched())) {
        return true;
      }
    }
    return false;
  }

  /** Why this decision keeps the person out of the role, or null when it does not. */
  static String failure(PolicyDecision decision, UUID policyId, AccessLevel level) {
    if (decision == null) {
      return "no decision";
    }
    boolean credited = credits(decision, policyId);
    if (!Boolean.TRUE.equals(decision.getAllowed())) {
      if (!credited) {
        return exemption(decision, policyId)
            .orElse("the policy does not let them in");
      }
      String denied = deniedBy(decision, policyId);
      if (denied != null) {
        return "denied by " + denied;
      }
      String gated = gatedBy(decision, policyId);
      return gated == null
          ? "refused by the composition of policies"
          : "not let in by " + gated + ", which every reader of this table also has to pass";
    }
    if (!credited) {
      return exemption(decision, policyId)
          .orElse("let in by something other than this policy");
    }
    if (level == AccessLevel.READ) {
      List<String> narrowed = new ArrayList<>();
      if (notEmpty(decision.getRowPredicates())) {
        narrowed.add("a row filter");
      }
      if (notEmpty(decision.getColumnMasks())) {
        narrowed.add("a column mask");
      }
      if (notEmpty(decision.getHiddenColumns())) {
        narrowed.add("hidden columns");
      }
      if (notEmpty(decision.getUnenforceable())) {
        narrowed.add("a rule that cannot be enforced");
      }
      if (!narrowed.isEmpty()) {
        return "a data policy applies "
            + String.join(" and ", narrowed)
            + ", which a plain SELECT grant would bypass";
      }
    }
    return null;
  }

  private static java.util.Optional<String> exemption(PolicyDecision decision, UUID policyId) {
    if (decision.getReasons() == null) {
      return java.util.Optional.empty();
    }
    for (DecisionReason reason : decision.getReasons()) {
      if (policyId.equals(reason.getPolicyId())
          && reason.getExplanation() != null
          && reason.getExplanation().contains("exempt")) {
        return java.util.Optional.of(reason.getExplanation());
      }
    }
    return java.util.Optional.empty();
  }

  private static String deniedBy(PolicyDecision decision, UUID policyId) {
    Map<String, Boolean> names = new LinkedHashMap<>();
    for (DecisionReason reason : decision.getReasons()) {
      if (reason.getEffect() == DecisionReason.Effect.DENY
          && Boolean.TRUE.equals(reason.getMatched())
          && !policyId.equals(reason.getPolicyId())
          && reason.getPolicyName() != null) {
        names.put(reason.getPolicyName(), true);
      }
    }
    return names.isEmpty() ? null : String.join(", ", names.keySet());
  }

  /** The other ALLOW policies that hold a layer this person did not pass. */
  private static String gatedBy(PolicyDecision decision, UUID policyId) {
    Map<String, Boolean> names = new LinkedHashMap<>();
    for (DecisionReason reason : decision.getReasons()) {
      if (reason.getEffect() == DecisionReason.Effect.ALLOW
          && !Boolean.TRUE.equals(reason.getMatched())
          && !policyId.equals(reason.getPolicyId())
          && reason.getPolicyName() != null
          && reason.getExplanation() != null
          && reason.getExplanation().startsWith(PolicyEngine.HOLDS_THE_GATE)) {
        names.put(reason.getPolicyName(), true);
      }
    }
    return names.isEmpty() ? null : String.join(", ", names.keySet());
  }

  private static boolean notEmpty(Collection<?> values) {
    return values != null && !values.isEmpty();
  }

  private static String firstLogin(Map<String, ? extends Collection<String>> logins, String person) {
    Collection<String> mapped = logins.get(NativeLoginMap.key(person));
    return mapped == null || mapped.isEmpty() ? null : mapped.iterator().next();
  }
}
