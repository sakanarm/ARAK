package com.mfec.dac.enforcement;

import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.source.DataSourceStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Whether the query proxy may read a table on a source whose subscription
 * policies are pushed down natively.
 *
 * <p>On a source set to {@code NATIVE_CONFIG} the policy is in force through
 * the role ARAK put on the source, and the proxy reads with the source's own
 * account. Without this gate the proxy would let a person read a table the
 * moment the policy allowed it, whether or not the role is there: before the
 * first apply, after a rollback, or after somebody changed the role by hand.
 * The two ways in would then disagree about the same person and the same
 * table. So the proxy reads only what the role on the source also gives, and
 * refuses -- fail closed -- when it cannot tell.
 *
 * <p>What lets a read through, on an allowed decision: one of the subscription
 * ALLOW policies that matched has a role on this source that
 *
 * <ul>
 *   <li>is {@code APPLIED}, or {@code PENDING} (the policy moved on and a plan
 *       is waiting; the decision already reflects the policy as it is now),
 *   <li>is at Read, not Browse, and
 *   <li>covers this table.
 * </ul>
 *
 * <p>A direct grant (FR-7) has no role of its own, and is let through: it is
 * named, has a reason and an end date, and an owner gave it on purpose. Who is
 * in the role is not checked here: the decision is the person's own, already
 * with every mask and row filter on it, so the proxy never shows more than the
 * policy does.
 *
 * <p>Only PostgreSQL is pushed to; any other engine, and any other mode, is
 * read by the proxy as before. No database, no clock: the roles are looked up
 * through the function passed in, so every rule here has a unit test.
 */
public final class NativeReadGate {

  private static final String GRANT_PREFIX = "grant:";

  private final BiFunction<UUID, UUID, Optional<NativeRoleStore.Role>> roles;

  /** @param roles the role of a policy on a source, by (policy, source) */
  public NativeReadGate(BiFunction<UUID, UUID, Optional<NativeRoleStore.Role>> roles) {
    this.roles = roles;
  }

  public static NativeReadGate of(NativeRoleStore store) {
    return new NativeReadGate(store::find);
  }

  /** True when reads on this source go through the gate at all. */
  public static boolean applies(DataSourceStore.Source source) {
    return source != null
        && source.engine() == DataSourceStore.Engine.POSTGRES
        && source.defaultEnforcementMode() == DataSourceStore.EnforcementMode.NATIVE_CONFIG;
  }

  /**
   * Why the proxy must not read this table for this decision, or empty when it
   * may. A refused decision is not this gate's business and is let through
   * untouched: the proxy refuses it anyway, with the policy's own reason.
   *
   * @param schema the table's schema on the source
   * @param table the table's name on the source
   */
  public Optional<String> refusal(
      DataSourceStore.Source source, String schema, String table, PolicyDecision decision) {
    if (!applies(source) || decision == null || !Boolean.TRUE.equals(decision.getAllowed())) {
      return Optional.empty();
    }
    String wanted = (schema + "." + table).toLowerCase(Locale.ROOT);

    Map<UUID, String> candidates = new LinkedHashMap<>();
    for (DecisionReason reason : decision.getReasons() == null
        ? List.<DecisionReason>of()
        : decision.getReasons()) {
      if (!Boolean.TRUE.equals(reason.getMatched())
          || reason.getEffect() != DecisionReason.Effect.ALLOW
          || reason.getPolicyType() != DecisionReason.PolicyType.SUBSCRIPTION) {
        continue;
      }
      if (reason.getPolicyId() == null) {
        if (reason.getPolicyName() != null && reason.getPolicyName().startsWith(GRANT_PREFIX)) {
          return Optional.empty();
        }
        continue;
      }
      candidates.putIfAbsent(reason.getPolicyId(), reason.getPolicyName());
    }

    if (candidates.isEmpty()) {
      return Optional.of(
          "This source enforces its policies on the database itself, and no subscription"
              + " policy that lets you read "
              + schema
              + "."
              + table
              + " could be matched to what is on the source. Ask a platform admin.");
    }

    List<String> why = new ArrayList<>();
    for (Map.Entry<UUID, String> candidate : candidates.entrySet()) {
      Optional<NativeRoleStore.Role> found = roles.apply(candidate.getKey(), source.id());
      // Not Optional.map: a null problem means "in force", and map would turn it
      // into the same empty as a role that was never there.
      String problem =
          found.isPresent() ? problem(found.get(), wanted) : "has not been pushed yet";
      if (problem == null) {
        return Optional.empty();
      }
      why.add(name(candidate.getValue()) + " " + problem);
    }
    return Optional.of(
        "This source enforces its policies on the database itself, so ARAK reads "
            + schema
            + "."
            + table
            + " only when the policy is in force there too. "
            + String.join("; ", why)
            + ". Ask a platform admin to plan and apply it.");
  }

  /** What stops this role from vouching for the table, or null when nothing does. */
  private static String problem(NativeRoleStore.Role role, String wanted) {
    String status = role.status() == null ? "" : role.status();
    switch (status) {
      case "APPLIED", "PENDING" -> {
        // Checked below.
      }
      case "ROLLED_BACK" -> {
        return "was rolled back on the source";
      }
      case "DRIFTED" -> {
        return "was changed by hand on the source";
      }
      case "FAILED" -> {
        return "failed the last time it was applied";
      }
      default -> {
        return "is not in force on the source";
      }
    }
    if (!"READ".equals(role.accessLevel())) {
      return "is pushed at Browse only, which does not let anyone read rows";
    }
    boolean covers =
        role.tables() != null
            && role.tables().stream()
                .anyMatch(t -> t != null && t.toLowerCase(Locale.ROOT).equals(wanted));
    return covers ? null : "does not cover this table on the source yet";
  }

  private static String name(String policyName) {
    return "Policy " + (policyName == null || policyName.isBlank() ? "(unnamed)" : policyName);
  }
}
