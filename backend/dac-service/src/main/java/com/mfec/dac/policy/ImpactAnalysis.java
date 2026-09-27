package com.mfec.dac.policy;

import com.mfec.dac.engine.AssetContext;
import com.mfec.dac.engine.PolicyEngine;
import com.mfec.dac.engine.Principal;
import com.mfec.dac.engine.RequestContext;
import com.mfec.dac.engine.SelectorMatcher;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.entity.policy.Policy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;

/**
 * What changes if this policy is in force (FR-5.3).
 *
 * <h2>Why this is not a count of bindings</h2>
 *
 * <p>The tempting version of impact analysis is "this policy binds 40 tables
 * and 12 people are in the teams it names" — two numbers that are cheap to
 * compute and answer a question nobody asked. A policy that grants what four
 * other policies already grant affects nobody, and a policy that denies one
 * column on one table can end a person's access to it entirely. Neither shows
 * up in a count of what the selector matched.
 *
 * <p>So the question asked here is the counterfactual one: for each person and
 * each table this policy lands on, what does the composed decision say
 * <em>with</em> this policy in the stack, and what does it say
 * <em>without</em> it? The difference is the impact. Everything else on the
 * screen is a roll-up of that difference.
 *
 * <p>Both sides come from {@link PolicyEngine} — the same engine the simulator
 * and the query proxy call. A separate "what would happen" evaluator would be
 * a second implementation of composition, and the first time the two disagreed
 * it would be in front of somebody deciding whether to activate.
 *
 * <h2>Draft and active read the same way</h2>
 *
 * <p>{@link PolicyStore#activeForIncluding} forces the candidate into the
 * stack whatever state it is in, and the "without" list is that same list with
 * the candidate removed. For a draft, "with" is the future. For a policy
 * already active, "with" is today and "without" is the world where it is
 * switched off — which is how you find out what an existing policy is
 * currently doing. {@link Impact#candidateActive} says which reading applies
 * so the screen can word it; the arithmetic is identical.
 *
 * <h2>Sampling is declared, never hidden</h2>
 *
 * <p>An org-wide policy over a classification can bind thousands of tables,
 * and the directory can hold thousands of people; the cross product is not
 * something to evaluate inside a web request. So both sides are capped and the
 * result reports what was measured against what exists. A number that came
 * from a sample is never presented as a total — {@link Impact#sampled} exists
 * so the screen can say "at least" instead of a figure somebody will quote in
 * a change request.
 */
public class ImpactAnalysis {

  /**
   * Tables evaluated at most. Chosen so the worst case stays inside a request:
   * this many tables times {@link #PRINCIPAL_LIMIT} people times two
   * evaluations each.
   */
  public static final int TABLE_LIMIT = 25;

  /** People evaluated at most. */
  public static final int PRINCIPAL_LIMIT = 200;

  /** Affected people listed in full; the rest are counted only. */
  private static final int DETAIL_LIMIT = 50;

  private final Jdbi jdbi;
  private final AssetContextLoader contexts;
  private final PrincipalLoader principals;
  private final PolicyStore policies;
  private final PolicyEngine engine;

  public ImpactAnalysis(
      Jdbi jdbi,
      AssetContextLoader contexts,
      PrincipalLoader principals,
      PolicyStore policies,
      PolicyEngine engine) {
    this.jdbi = jdbi;
    this.contexts = contexts;
    this.principals = principals;
    this.policies = policies;
    this.engine = engine;
  }

  /**
   * How one person's view of one table differs with the policy in force.
   *
   * <p>Ordered most consequential first. A verdict that flips is worse than a
   * projection that narrows, because the first breaks a query outright and the
   * second changes what it returns; and losing access is listed above gaining
   * it because that is the one somebody will be paged about. {@code CHANGED}
   * sits between them for the genuinely mixed case — more masked here, less
   * masked there — which is rare, real, and not honestly describable as either
   * direction.
   */
  public enum Change {
    LOSES_ACCESS,
    GAINS_ACCESS,
    CHANGED,
    SEES_LESS,
    SEES_MORE,
    UNCHANGED
  }

  /** One person against one table. */
  public record TableChange(String assetFqn, Change change, String detail) {}

  /**
   * One person, rolled up across the tables measured.
   *
   * @param change the most consequential of their {@link #tables}
   */
  public record PrincipalChange(
      String principal, Change change, int tablesAffected, List<TableChange> tables) {}

  /**
   * The answer.
   *
   * @param tablesBound how many tables the policy resolves onto in total
   * @param tablesMeasured how many of those were actually evaluated
   * @param principalsKnown how many people the directory holds
   * @param principalsMeasured how many of those were actually evaluated
   * @param sampled true when either side was capped, so every count below is a
   *     floor rather than a total
   * @param byChange how many (person, table) pairs fell into each kind,
   *     including {@code UNCHANGED} — the size of the unaffected majority is
   *     itself the reassurance somebody is looking for
   * @param principals the affected people, worst first, capped at
   *     {@value #DETAIL_LIMIT}
   */
  public record Impact(
      UUID policyId,
      String policyName,
      boolean candidateActive,
      String environment,
      int tablesBound,
      int tablesMeasured,
      int principalsKnown,
      int principalsMeasured,
      boolean sampled,
      int principalsAffected,
      int tablesAffected,
      Map<String, Integer> byChange,
      List<PrincipalChange> principals,
      boolean principalsTruncated,
      Instant measuredAt) {}

  /** Runs the comparison for one policy. */
  public Impact measure(PolicyStore.StoredPolicy candidate) {
    UUID id = candidate.id();
    String environment = candidate.environment();

    return jdbi.withHandle(
        handle ->
            run(
                handle,
                candidate,
                countBoundTables(handle, id),
                boundTables(handle, id, TABLE_LIMIT),
                fqn -> {
                  List<PolicyStore.StoredPolicy> with =
                      policies.activeForIncluding(fqn, environment, id);
                  return new Sides(documents(with, id), documents(with, null));
                }));
  }

  /**
   * What putting an earlier version back would change (FR-9.2, FR-5.3).
   *
   * <p>The same counterfactual as {@link #measure}, between two versions of one
   * policy rather than between having it and not: "before" is the stack with
   * the policy as it reads now, "after" the same stack with the old version in
   * its place. Everything else in the stack is held still, so what differs is
   * the rollback and nothing but.
   *
   * <p>Both versions are evaluated as if in force, whatever the policy's state.
   * For an active policy that is exactly what the rollback does to people
   * today; for any other it is what it would do the day the policy is switched
   * on, and {@link Impact#candidateActive} says which.
   *
   * <p>The tables are the ones either version lands on. The old version's are
   * worked out here, by running its selector over its scope, because its
   * bindings were replaced the moment it stopped being current -- and a
   * rollback that widens the selector reaches tables the current bindings have
   * never heard of, which are the ones a reviewer most needs to see. Tables
   * only one version reaches are measured first, since that is where the
   * change is.
   *
   * @param restored the old version's document; it is changed in place
   */
  public Impact measureRollback(PolicyStore.StoredPolicy current, Policy restored) {
    UUID id = current.id();
    String environment = current.environment();
    Policy now = inForce(current.document(), id);
    Policy then = inForce(restored, id);
    then.setEnvironment(now.getEnvironment());

    return jdbi.withHandle(
        handle -> {
          Set<String> nowTables = new TreeSet<>(boundTables(handle, id, Integer.MAX_VALUE));
          Set<String> thenTables = matching(handle, then);
          Set<String> either = new TreeSet<>(nowTables);
          either.addAll(thenTables);

          List<String> order = new ArrayList<>(either.size());
          List<String> shared = new ArrayList<>();
          for (String fqn : either) {
            if (nowTables.contains(fqn) == thenTables.contains(fqn)) {
              shared.add(fqn);
            } else {
              order.add(fqn);
            }
          }
          order.addAll(shared);
          List<String> fqns = order.subList(0, Math.min(TABLE_LIMIT, order.size()));

          return run(
              handle,
              current,
              either.size(),
              fqns,
              fqn -> {
                List<Policy> others = documents(policies.activeFor(fqn, environment), id);
                List<Policy> before = new ArrayList<>(others);
                if (nowTables.contains(fqn)) {
                  before.add(now);
                }
                List<Policy> after = new ArrayList<>(others);
                if (thenTables.contains(fqn)) {
                  after.add(then);
                }
                return new Sides(before, after);
              });
        });
  }

  /**
   * A document the engine will treat as in force, pointing at its policy.
   *
   * <p>The engine skips a document that says it is a draft, and an old
   * version's copy may say anything about its state; the question here is what
   * the rule does, so the rule is what is evaluated.
   */
  private static Policy inForce(Policy document, UUID id) {
    document.setId(id);
    document.setLifecycleState(Policy.LifecycleState.ACTIVE);
    return document;
  }

  /** The tables a document's selector lands on, as the materializer would find them. */
  private Set<String> matching(org.jdbi.v3.core.Handle handle, Policy document) {
    Set<String> out = new TreeSet<>();
    if (document.getSelector() == null) {
      return out;
    }
    contexts.forEachInScope(
        handle,
        document.getScopeFqn(),
        batch -> {
          for (AssetContext asset : batch) {
            if (SelectorMatcher.matches(document.getSelector(), asset)) {
              out.add(asset.fqn());
            }
          }
        });
    return out;
  }

  /** The stacks one table is evaluated under: as things are, and as they would be. */
  private record Sides(List<Policy> before, List<Policy> after) {}

  /**
   * Evaluates everybody against each table both ways and rolls up the
   * difference. Shared by {@link #measure} and {@link #measureRollback}, which
   * differ only in which tables they ask about and what the two stacks are.
   */
  private Impact run(
      org.jdbi.v3.core.Handle handle,
      PolicyStore.StoredPolicy candidate,
      int tablesBound,
      List<String> fqns,
      java.util.function.Function<String, Sides> stacks) {
    int principalsKnown = principals.countEveryone(handle);
    List<Principal> people = principals.everyone(handle, PRINCIPAL_LIMIT);

    boolean sampled = tablesBound > fqns.size() || principalsKnown > people.size();
    Instant now = Instant.now();

    Map<String, List<TableChange>> byPrincipal = new LinkedHashMap<>();
    Map<String, Integer> counts = new TreeMap<>();
    Set<String> affectedTables = new TreeSet<>();
    int measured = 0;

    for (String fqn : fqns) {
      AssetContext asset = contexts.load(handle, fqn).orElse(null);
      if (asset == null) {
        // Bound to a table the cache no longer holds. Not an error worth
        // failing the whole report for, and counting it as "unchanged"
        // would be a claim we cannot support, so it is simply not
        // measured -- which `tablesMeasured` below already reflects.
        continue;
      }
      measured++;

      Sides sides = stacks.apply(fqn);
      RequestContext context = RequestContext.at(now);

      for (Principal person : people) {
        PolicyDecision before = engine.evaluate(person, asset, context, sides.before());
        PolicyDecision after = engine.evaluate(person, asset, context, sides.after());
        TableChange change = compare(fqn, before, after);
        counts.merge(change.change().name(), 1, Integer::sum);
        if (change.change() != Change.UNCHANGED) {
          byPrincipal
              .computeIfAbsent(person.id(), key -> new ArrayList<>())
              .add(change);
          affectedTables.add(fqn);
        }
      }
    }

    List<PrincipalChange> rolled = new ArrayList<>(byPrincipal.size());
    for (Map.Entry<String, List<TableChange>> entry : byPrincipal.entrySet()) {
      List<TableChange> tables = new ArrayList<>(entry.getValue());
      tables.sort(
          Comparator.comparingInt((TableChange one) -> one.change().ordinal())
              .thenComparing(TableChange::assetFqn));
      Change worst = tables.get(0).change();
      rolled.add(new PrincipalChange(entry.getKey(), worst, tables.size(), tables));
    }
    rolled.sort(
        Comparator.comparingInt((PrincipalChange one) -> one.change().ordinal())
            .thenComparing(
                Comparator.comparingInt(PrincipalChange::tablesAffected).reversed())
            .thenComparing(PrincipalChange::principal));

    boolean truncated = rolled.size() > DETAIL_LIMIT;
    List<PrincipalChange> shown =
        truncated ? List.copyOf(rolled.subList(0, DETAIL_LIMIT)) : List.copyOf(rolled);

    return new Impact(
        candidate.id(),
        candidate.document() == null ? null : candidate.document().getName(),
        "ACTIVE".equals(candidate.lifecycleState()),
        candidate.environment(),
        tablesBound,
        measured,
        principalsKnown,
        people.size(),
        sampled,
        rolled.size(),
        affectedTables.size(),
        Map.copyOf(counts),
        shown,
        truncated,
        now);
  }

  /**
   * The stack as documents, optionally with one policy taken out.
   *
   * <p>Removing by id rather than by re-querying is what guarantees the two
   * sides differ in exactly one policy. A second query could differ in more
   * than that — a policy could expire between the two calls — and an impact
   * report that blames this policy for somebody else's expiry is worse than no
   * report.
   */
  private static List<Policy> documents(List<PolicyStore.StoredPolicy> stack, UUID exclude) {
    List<Policy> out = new ArrayList<>(stack.size());
    for (PolicyStore.StoredPolicy one : stack) {
      if (exclude != null && exclude.equals(one.id())) {
        continue;
      }
      out.add(one.document());
    }
    return out;
  }

  /**
   * The difference between two decisions, in the terms a reviewer thinks in.
   *
   * <p>A decision carries more than a verdict, so "the same" has to mean the
   * same verdict <em>and</em> the same projection. Masks are compared by
   * column and function rather than by object identity: the same column masked
   * by a different policy with the same function is not a change anybody can
   * observe in the data, and reporting it would bury the changes that are.
   */
  static TableChange compare(String fqn, PolicyDecision before, PolicyDecision after) {
    if (Boolean.TRUE.equals(before.getAllowed()) && !Boolean.TRUE.equals(after.getAllowed())) {
      return new TableChange(fqn, Change.LOSES_ACCESS, "cannot read this table at all");
    }
    if (!Boolean.TRUE.equals(before.getAllowed()) && Boolean.TRUE.equals(after.getAllowed())) {
      return new TableChange(fqn, Change.GAINS_ACCESS, "can read this table");
    }
    if (!Boolean.TRUE.equals(after.getAllowed())) {
      // Denied both ways. The reasons may well differ -- a second policy now
      // also denies them -- but nothing about what they can see has changed,
      // and listing them as affected would fill the report with people for
      // whom activating this policy is a no-op.
      return new TableChange(fqn, Change.UNCHANGED, null);
    }

    Set<String> hiddenBefore = hidden(before);
    Set<String> hiddenAfter = hidden(after);
    Set<String> masksBefore = masks(before);
    Set<String> masksAfter = masks(after);
    Set<String> rowsBefore = rows(before);
    Set<String> rowsAfter = rows(after);

    List<String> added = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    describe(added, removed, "hidden", hiddenBefore, hiddenAfter, ImpactAnalysis::columnOf);
    describe(added, removed, "masked", masksBefore, masksAfter, ImpactAnalysis::columnOf);
    describe(added, removed, "row filter", rowsBefore, rowsAfter, one -> one);

    if (added.isEmpty() && removed.isEmpty()) {
      return new TableChange(fqn, Change.UNCHANGED, null);
    }
    Change change =
        added.isEmpty() ? Change.SEES_MORE : removed.isEmpty() ? Change.SEES_LESS : Change.CHANGED;
    List<String> parts = new ArrayList<>();
    parts.addAll(added);
    parts.addAll(removed);
    return new TableChange(fqn, change, String.join("; ", parts));
  }

  /**
   * Turns one kind of restriction appearing or disappearing into a phrase.
   *
   * <p>Named rather than counted wherever the names fit: "email, phone are now
   * masked" tells a reviewer whether to care, "2 more columns are masked" sends
   * them to go and look.
   */
  private static void describe(
      List<String> added,
      List<String> removed,
      String label,
      Set<String> before,
      Set<String> after,
      java.util.function.Function<String, String> render) {
    List<String> gained = difference(after, before, render);
    List<String> lost = difference(before, after, render);
    if (!gained.isEmpty()) {
      added.add(String.join(", ", gained) + " now " + label);
    }
    if (!lost.isEmpty()) {
      removed.add(String.join(", ", lost) + " no longer " + label);
    }
  }

  private static List<String> difference(
      Set<String> from, Set<String> minus, java.util.function.Function<String, String> render) {
    List<String> out = new ArrayList<>();
    for (String one : from) {
      if (!minus.contains(one)) {
        out.add(render.apply(one));
      }
    }
    return out;
  }

  /** {@code column:FUNCTION} back to {@code column}, for the human sentence. */
  private static String columnOf(String key) {
    int cut = key.indexOf(':');
    return cut < 0 ? key : key.substring(0, cut);
  }

  private static Set<String> hidden(PolicyDecision decision) {
    return decision.getHiddenColumns() == null
        ? Set.of()
        : new TreeSet<>(decision.getHiddenColumns());
  }

  private static Set<String> masks(PolicyDecision decision) {
    Set<String> out = new TreeSet<>();
    if (decision.getColumnMasks() == null) {
      return out;
    }
    for (ResolvedColumnMask mask : decision.getColumnMasks()) {
      String function =
          mask.getMasking() == null || mask.getMasking().getFunction() == null
              ? "MASK"
              : mask.getMasking().getFunction().toString();
      // The condition is part of the identity: the same column, the same
      // function, but masked only on some rows is a different thing to see.
      String condition = mask.getCondition() == null ? "" : mask.getCondition();
      out.add(mask.getColumn() + ":" + function + ":" + condition);
    }
    return out;
  }

  private static Set<String> rows(PolicyDecision decision) {
    Set<String> out = new TreeSet<>();
    if (decision.getRowPredicates() == null) {
      return out;
    }
    for (ResolvedRowPredicate predicate : decision.getRowPredicates()) {
      StringBuilder key = new StringBuilder();
      key.append(predicate.getKind()).append(' ').append(predicate.getColumn());
      if (predicate.getOperator() != null) {
        key.append(' ').append(predicate.getOperator());
      }
      if (predicate.getValues() != null && !predicate.getValues().isEmpty()) {
        key.append(' ').append(predicate.getValues());
      }
      if (predicate.getEntitlementKey() != null) {
        key.append(" via ").append(predicate.getEntitlementKey());
      }
      if (predicate.getRawPredicate() != null) {
        key.append(' ').append(predicate.getRawPredicate());
      }
      out.add(key.toString().trim());
    }
    return out;
  }

  /**
   * The tables this policy binds, a stable prefix of them.
   *
   * <p>Only {@code TABLE} rows: the materializer writes one for every table a
   * selector matches and adds {@code COLUMN} rows inside it, so a table whose
   * columns are bound always has a table row too. Reading the column rows as
   * well would mean unioning in their parents to find the same set.
   */
  private static List<String> boundTables(org.jdbi.v3.core.Handle handle, UUID id, int limit) {
    return handle
        .createQuery(
            """
            SELECT target_fqn FROM policy_binding
            WHERE policy_id = :id AND target_kind = 'TABLE'
            ORDER BY target_fqn
            LIMIT :limit
            """)
        .bind("id", id)
        .bind("limit", limit)
        .mapTo(String.class)
        .list();
  }

  private static int countBoundTables(org.jdbi.v3.core.Handle handle, UUID id) {
    return handle
        .createQuery(
            "SELECT count(*) FROM policy_binding WHERE policy_id = :id AND target_kind = 'TABLE'")
        .bind("id", id)
        .mapTo(Integer.class)
        .one();
  }
}
