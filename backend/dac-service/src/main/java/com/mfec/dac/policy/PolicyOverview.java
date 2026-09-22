package com.mfec.dac.policy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.schema.entity.policy.ColumnRule;
import com.mfec.dac.schema.entity.policy.Policy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;

/**
 * Reads a policy back the way somebody asks about it, rather than the way it was written.
 *
 * <p>Three questions, and they are the same question from different ends. "What does this policy
 * touch" is answered by {@link #coverage}, straight out of {@code policy_binding} — the rows the
 * materializer already writes, which until now nothing read. "What else touches the same things,
 * and does it fight with me" is answered by {@link #overlaps}. "What governs this table" is
 * {@link #applied}, which is the same rows read from the asset's side.
 *
 * <p>Nothing here evaluates a policy. A decision needs a principal and a moment; this is what can
 * be said about a policy while it sits still, which is what somebody reviewing one before pressing
 * Activate actually has.
 */
public class PolicyOverview {

  private final Jdbi jdbi;
  private final ObjectMapper json;

  public PolicyOverview(Jdbi jdbi, ObjectMapper json) {
    this.jdbi = jdbi;
    this.json = json;
  }

  /** One table or column a selector resolved onto. */
  public record Target(
      String fqn,
      String kind,
      String name,
      String parentFqn,
      String dataType,
      Map<String, Object> matchReason,
      Instant resolvedAt) {}

  /**
   * What a policy currently lands on.
   *
   * @param truncated whether {@code sample} is shorter than the counts — a policy over a whole
   *     classification can bind thousands of columns, and a screen that tries to print all of them
   *     is a screen nobody scrolls to the bottom of.
   */
  public record Coverage(
      int tableCount, int columnCount, List<Target> sample, boolean truncated, Instant resolvedAt) {}

  /**
   * Another policy that lands on at least one of the same targets.
   *
   * @param relation what actually happens where they meet — see {@link #relate}
   * @param overrideNote set when that policy has authority over this one and forbids relaxing it,
   *     null otherwise -- see {@link #overrideNote}
   */
  public record Overlap(
      UUID policyId,
      String name,
      String displayName,
      String policyType,
      String effect,
      String scopeLevel,
      String scopeFqn,
      String lifecycleState,
      String environment,
      boolean allowLocalOverride,
      int sharedTargets,
      List<String> examples,
      String relation,
      String explanation,
      String overrideNote) {}

  /**
   * One policy reaching an asset, and where it lands inside it.
   *
   * @param matchReason why the selector caught this table, from the table-level binding
   * @param columns the columns of this table the policy also binds -- empty for a subscription
   *     policy, and for a data policy that only filters rows
   */
  public record Applied(
      PolicyStore.StoredPolicy policy, Map<String, Object> matchReason, List<Target> columns) {}

  private static final int SAMPLE_LIMIT = 200;

  /**
   * The policies governing one asset, in the order the engine composes them (FR-3.1.5).
   *
   * <p>The list comes in already ordered and filtered by {@link PolicyStore#activeFor}; all this
   * adds is the part a data owner actually came for -- which columns each rule reaches, and why
   * the table was selected at all. Column bindings carry no {@code asset_id}, so they are found
   * through the column's own parent rather than the binding.
   */
  public List<Applied> applied(String assetFqn, List<PolicyStore.StoredPolicy> active) {
    if (active.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = active.stream().map(PolicyStore.StoredPolicy::id).toList();

    List<PolicyRow> rows =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT b.policy_id, b.target_fqn, b.target_kind,
                               b.match_reason::text AS reason, b.resolved_at,
                               c.name, c.data_type
                        FROM policy_binding b
                        LEFT JOIN asset_column c ON c.id = b.column_id
                        LEFT JOIN asset a        ON a.id = c.asset_id
                        WHERE b.policy_id IN (<ids>)
                          AND (b.target_fqn = :fqn OR a.fqn = :fqn)
                        ORDER BY b.target_fqn
                        """)
                    .bindList("ids", ids)
                    .bind("fqn", assetFqn)
                    .map(
                        (rs, ctx) ->
                            new PolicyRow(
                                UUID.fromString(rs.getString("policy_id")),
                                new Target(
                                    rs.getString("target_fqn"),
                                    rs.getString("target_kind"),
                                    rs.getString("name"),
                                    assetFqn,
                                    rs.getString("data_type"),
                                    reason(rs.getString("reason")),
                                    rs.getTimestamp("resolved_at") == null
                                        ? null
                                        : rs.getTimestamp("resolved_at").toInstant())))
                    .list());

    Map<UUID, Map<String, Object>> reasons = new java.util.HashMap<>();
    Map<UUID, List<Target>> columns = new java.util.HashMap<>();
    for (PolicyRow row : rows) {
      if ("COLUMN".equals(row.target.kind())) {
        columns.computeIfAbsent(row.policyId, key -> new ArrayList<>()).add(row.target);
      } else {
        reasons.put(row.policyId, row.target.matchReason());
      }
    }

    List<Applied> out = new ArrayList<>(active.size());
    for (PolicyStore.StoredPolicy policy : active) {
      out.add(
          new Applied(
              policy,
              reasons.getOrDefault(policy.id(), Map.of()),
              List.copyOf(columns.getOrDefault(policy.id(), List.of()))));
    }
    return List.copyOf(out);
  }

  private record PolicyRow(UUID policyId, Target target) {}

  /** Every table and column this policy is bound to, counted in full and sampled for display. */
  public Coverage coverage(UUID policyId) {
    return jdbi.withHandle(
        handle -> {
          Map<String, Integer> counts =
              handle
                  .createQuery(
                      """
                      SELECT target_kind, count(*) AS n
                      FROM policy_binding WHERE policy_id = :id
                      GROUP BY target_kind
                      """)
                  .bind("id", policyId)
                  .reduceRows(
                      new java.util.HashMap<String, Integer>(),
                      (map, row) -> {
                        map.put(row.getColumn("target_kind", String.class),
                            row.getColumn("n", Integer.class));
                        return map;
                      });

          List<Target> sample =
              handle
                  .createQuery(
                      """
                      SELECT b.target_fqn, b.target_kind, b.match_reason::text AS reason,
                             b.resolved_at,
                             COALESCE(c.name, a.name) AS name,
                             COALESCE(a.fqn, ca.fqn, b.target_fqn) AS parent_fqn,
                             c.data_type
                      FROM policy_binding b
                      LEFT JOIN asset a ON a.id = b.asset_id
                      LEFT JOIN asset_column c ON c.id = b.column_id
                      LEFT JOIN asset ca ON ca.id = c.asset_id
                      WHERE b.policy_id = :id
                      ORDER BY CASE b.target_kind WHEN 'TABLE' THEN 0 ELSE 1 END, b.target_fqn
                      LIMIT :limit
                      """)
                  .bind("id", policyId)
                  .bind("limit", SAMPLE_LIMIT)
                  .map(
                      (rs, ctx) ->
                          new Target(
                              rs.getString("target_fqn"),
                              rs.getString("target_kind"),
                              rs.getString("name"),
                              rs.getString("parent_fqn"),
                              rs.getString("data_type"),
                              reason(rs.getString("reason")),
                              rs.getTimestamp("resolved_at") == null
                                  ? null
                                  : rs.getTimestamp("resolved_at").toInstant()))
                  .list();

          Instant resolvedAt =
              handle
                  .createQuery(
                      "SELECT max(resolved_at) FROM policy_binding WHERE policy_id = :id")
                  .bind("id", policyId)
                  .mapTo(Instant.class)
                  .findOne()
                  .orElse(null);

          int tables = counts.getOrDefault("TABLE", 0);
          int columns = counts.getOrDefault("COLUMN", 0);
          return new Coverage(
              tables, columns, sample, tables + columns > sample.size(), resolvedAt);
        });
  }

  /**
   * The other policies bound to the same targets, strongest interaction first.
   *
   * <p>Archived policies are left out because they cannot affect anything; drafts are kept in,
   * because the point of this screen is to see the collision before activating, not after.
   *
   * <p>{@code environment} is the stored row's, not the document's. The two can disagree -- a
   * document with no environment is stored as {@code dev} -- and the row is the one the engine
   * filters on, so comparing against anything else would pair a policy with strangers.
   */
  public List<Overlap> overlaps(UUID policyId, Policy subject, String environment) {
    List<Row> rows =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT p.id, p.name, p.display_name, p.policy_type, p.effect,
                               p.scope_level, p.scope_fqn, p.lifecycle_state, p.environment,
                               p.allow_local_override, p.document::text AS document,
                               count(*) AS shared,
                               array_agg(b2.target_fqn ORDER BY b2.target_fqn) AS examples
                        FROM policy_binding b1
                        JOIN policy_binding b2
                          ON b2.target_fqn = b1.target_fqn AND b2.policy_id <> b1.policy_id
                        JOIN policy p ON p.id = b2.policy_id
                        WHERE b1.policy_id = :id
                          AND p.lifecycle_state <> 'ARCHIVED'
                          AND p.environment = :environment
                        GROUP BY p.id, p.name, p.display_name, p.policy_type, p.effect,
                                 p.scope_level, p.scope_fqn, p.lifecycle_state, p.environment,
                                 p.allow_local_override, p.document
                        ORDER BY shared DESC, p.name
                        """)
                    .bind("id", policyId)
                    .bind("environment", environment)
                    .map(
                        (rs, ctx) ->
                            new Row(
                                UUID.fromString(rs.getString("id")),
                                rs.getString("name"),
                                rs.getString("display_name"),
                                rs.getString("policy_type"),
                                rs.getString("effect"),
                                rs.getString("scope_level"),
                                rs.getString("scope_fqn"),
                                rs.getString("lifecycle_state"),
                                rs.getString("environment"),
                                rs.getBoolean("allow_local_override"),
                                rs.getInt("shared"),
                                examples(rs.getArray("examples"), EXAMPLE_LIMIT)))
                    .list());

    List<Overlap> out = new ArrayList<>();
    for (Row row : rows) {
      Verdict verdict = relate(subject, row);
      out.add(
          new Overlap(
              row.id,
              row.name,
              row.displayName,
              row.policyType,
              row.effect,
              row.scopeLevel,
              row.scopeFqn,
              row.lifecycleState,
              row.environment,
              row.allowLocalOverride,
              row.shared,
              row.examples,
              verdict.relation,
              verdict.explanation,
              overrideNote(subject, row.scopeLevel, row.allowLocalOverride)));
    }
    out.sort(
        java.util.Comparator.comparingInt((Overlap o) -> RELATION_ORDER.indexOf(o.relation()))
            .thenComparing(java.util.Comparator.comparingInt(Overlap::sharedTargets).reversed())
            .thenComparing(Overlap::name));
    return List.copyOf(out);
  }

  // ------------------------------------------------------------------ verdicts

  /** Worst first: this is the order the screen reads in, so the real conflict is at the top. */
  private static final List<String> RELATION_ORDER =
      List.of("BLOCKED_BY", "BLOCKS", "MASK_OVERLAP", "NARROWS", "COMPOSES");

  private record Verdict(String relation, String explanation) {}

  /**
   * What happens where the two policies meet.
   *
   * <p>Everything here is FR-5.1 stated from one policy's point of view. It says what the engine
   * will do, not what looks suspicious: "these two overlap" is not worth a screen, "this one is
   * dead wherever that one applies" is.
   */
  private static Verdict relate(Policy subject, Row other) {
    String mine = typeOf(subject);
    boolean sameType = mine.equals(other.policyType);

    if (!sameType) {
      return new Verdict(
          "COMPOSES",
          "Different questions: one decides whether the table may be read at all, the other what"
              + " is visible once it is. They apply together and neither overrides the other.");
    }

    if ("SUBSCRIPTION".equals(mine)) {
      String myEffect = subject.getEffect() == null ? "ALLOW" : subject.getEffect().value();
      boolean iDeny = "DENY".equals(myEffect);
      boolean theyDeny = "DENY".equals(other.effect);

      if (theyDeny && !iDeny) {
        return new Verdict(
            "BLOCKED_BY",
            "A denial wins over an allowance, so on the targets they share this policy grants"
                + " nothing. Whatever it allows here is already refused.");
      }
      if (iDeny && !theyDeny) {
        return new Verdict(
            "BLOCKS",
            "This policy denies what that one allows. On the targets they share, that policy"
                + " grants nothing.");
      }
      if (iDeny) {
        return new Verdict(
            "COMPOSES", "Both deny. Either one alone is enough to refuse access here.");
      }
      return new Verdict(
          "NARROWS",
          "Both allow, and access needs every layer to allow it. A caller must satisfy this"
              + " policy's subject rule and that one's, not either.");
    }

    // Both DATA. The clash worth showing is two rules reaching the same column.
    if (touchesColumns(subject)) {
      return new Verdict(
          "MASK_OVERLAP",
          "Both mask or hide columns here. Where they land on the same column the stricter rule"
              + " wins, so the weaker one has no visible effect.");
    }
    return new Verdict(
        "NARROWS",
        "Row filters from every data policy are combined with AND, so the rows left are the ones"
            + " both policies allow.");
  }

  /**
   * Whether a lower policy would be trying to loosen a higher one that forbids it.
   *
   * <p>Kept separate from {@link #relate} because it is about authority rather than effect: the
   * two policies may agree perfectly and this still matters when somebody edits one of them.
   */
  public static String overrideNote(
      Policy subject, String otherScopeLevel, boolean otherAllowsLocalOverride) {
    int mine = depth(scopeOf(subject));
    int theirs = depth(otherScopeLevel);
    if (theirs < mine && !otherAllowsLocalOverride) {
      return "That policy sits above this one at "
          + otherScopeLevel
          + " and does not permit a local override, so this one may only tighten it.";
    }
    return null;
  }

  private static final List<String> SCOPES =
      List.of("ORG", "DOMAIN", "SERVICE", "DATABASE", "SCHEMA", "TABLE", "COLUMN");

  private static int depth(String scopeLevel) {
    int i = SCOPES.indexOf(scopeLevel);
    return i < 0 ? SCOPES.size() : i;
  }

  private static boolean touchesColumns(Policy policy) {
    if (policy.getData() == null || policy.getData().getColumnRules() == null) {
      return false;
    }
    for (ColumnRule rule : policy.getData().getColumnRules()) {
      if (rule.getAction() != ColumnRule.Action.ALLOW) {
        return true;
      }
    }
    return false;
  }

  private static String typeOf(Policy policy) {
    return policy.getPolicyType() == null ? "SUBSCRIPTION" : policy.getPolicyType().value();
  }

  private static String scopeOf(Policy policy) {
    return policy.getScopeLevel() == null ? "ORG" : policy.getScopeLevel().value();
  }

  private record Row(
      UUID id,
      String name,
      String displayName,
      String policyType,
      String effect,
      String scopeLevel,
      String scopeFqn,
      String lifecycleState,
      String environment,
      boolean allowLocalOverride,
      int shared,
      List<String> examples) {}

  /**
   * How many of the shared targets a row names before the screen says "and more".
   *
   * <p>Only the names are capped. {@code sharedTargets} is counted in SQL over every row, so the
   * number stays exact however long the list gets -- the mistake to avoid is a screen that reports
   * three collisions when there are three hundred.
   */
  private static final int EXAMPLE_LIMIT = 3;

  private static List<String> examples(java.sql.Array array, int limit) {
    if (array == null) {
      return List.of();
    }
    try {
      Object raw = array.getArray();
      if (raw instanceof Object[] values) {
        List<String> out = new ArrayList<>(Math.min(values.length, limit));
        for (Object value : values) {
          if (out.size() == limit) {
            break;
          }
          if (value != null) {
            out.add(value.toString());
          }
        }
        return List.copyOf(out);
      }
      return List.of();
    } catch (java.sql.SQLException e) {
      return List.of();
    }
  }

  /**
   * Reads the stored match reason.
   *
   * <p>An unreadable reason costs the row its explanation, not the screen its listing: knowing a
   * policy binds a column is worth more than knowing why, and losing both to a bad cell would be
   * the worse trade.
   */
  private Map<String, Object> reason(String raw) {
    if (raw == null || raw.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, Object> parsed = json.readValue(raw, new TypeReference<>() {});
      return parsed == null ? Map.of() : parsed;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return Map.of();
    }
  }
}
