package com.mfec.dac.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.audit.ClientAddress;
import com.mfec.dac.compiler.sql.PostgresDialect;
import com.mfec.dac.compiler.sql.SqlDialect;
import com.mfec.dac.compiler.sql.SqlServerDialect;
import com.mfec.dac.proxy.QueryRewriter;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.api.Unenforceable;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.QueryExecutor;
import com.mfec.dac.source.jdbc.SourceProbe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mode 5.2 end to end: SQL in, policy applied, rows out (FR-6.3).
 *
 * <p>The caller never touches the source. ARAK holds the only connection, as a
 * service account whose grant is {@code SELECT}, and the statement that
 * actually runs is the rewritten one. That is the whole model — the one Denodo
 * made familiar — and its consequence is stated plainly in FR-6.3.1: the source
 * has to be firewalled, because a principal who can open their own connection
 * has bypassed every line of this class.
 *
 * <h2>What is written down</h2>
 *
 * <p>Every attempt is audited, including the refused ones. A rejection is the
 * more interesting record of the two: it is either a policy doing its job or
 * somebody probing for the edge of one, and both are things an investigation
 * will want in order.
 */
public class QueryService {

  private static final Logger LOG = LoggerFactory.getLogger(QueryService.class);

  /** Ceiling on what one call can pull back, whatever it asks for. */
  public static final int MAX_ROWS = 5_000;

  public static final int DEFAULT_ROWS = 200;
  public static final int TIMEOUT_SECONDS = 30;

  /**
   * @param assets the governed assets the statement turned out to touch
   * @param explanations what each of those assets was restricted by, in words
   * @param unenforceable restrictions this dialect could not express; they were
   *     tightened rather than dropped, and the caller is told (FR-6.0b)
   */
  public record Result(
      List<String> columns,
      List<String> columnTypes,
      List<List<Object>> rows,
      boolean truncated,
      long millis,
      String rewrittenSql,
      List<String> assets,
      List<Explanation> explanations,
      List<Unenforceable> unenforceable) {}

  /**
   * Why this result looks the way it does, for one asset.
   *
   * <p>The rewritten statement already tells the whole truth, but only to
   * somebody willing to read generated SQL. Rows that quietly went missing and
   * a column that quietly reads {@code ***} are indistinguishable from a broken
   * pipeline until something names the policy responsible, so the same decision
   * is also rendered in words (FR-5.4).
   *
   * @param maskedColumns column name to a description of what was done to it
   * @param rowFilters one line per row restriction, ANDed together
   * @param policies the policies that matched, named as the author named them
   */
  public record Explanation(
      String asset,
      Map<String, String> maskedColumns,
      List<String> hiddenColumns,
      List<String> rowFilters,
      List<String> policies) {}

  /** The statement was not run, and the message says why in the caller's terms. */
  public static class RejectedException extends RuntimeException {
    public RejectedException(String message) {
      super(message);
    }
  }

  private final Jdbi jdbi;
  private final ObjectMapper json;
  private final DataSourceStore sources;
  private final DecisionService decisions;
  private final QueryExecutor executor;

  public QueryService(
      Jdbi jdbi,
      ObjectMapper json,
      DataSourceStore sources,
      DecisionService decisions,
      QueryExecutor executor) {
    this.jdbi = jdbi;
    this.json = json;
    this.sources = sources;
    this.decisions = decisions;
    this.executor = executor;
  }

  /**
   * @param principal whose access governs the rows; not necessarily the caller,
   *     because the explorer doubles as the simulator's evidence (FR-5.2)
   */
  public Result run(
      UUID sourceId, String sql, String principal, int maxRows, String clientIp, String purpose) {

    // Audited before anything else can go right, because a query aimed at a
    // source that is gone or switched off is still someone trying to read data
    // and is exactly the attempt an auditor asks about later. Leaving these two
    // to throw unrecorded meant the log answered "what did people run" with
    // only the runs that got as far as a rewrite.
    Optional<DataSourceStore.Source> found = sources.find(sourceId);
    if (found.isEmpty()) {
      String reason = "No data source " + sourceId;
      audit(principal, null, sql, null, "REJECTED", reason, null, null, clientIp);
      throw new RejectedException(reason);
    }
    DataSourceStore.Source source = found.get();
    if (!source.enabled()) {
      String reason = source.name() + " is disabled";
      audit(principal, source.id(), sql, null, "REJECTED", reason, null, null, clientIp);
      throw new RejectedException(reason);
    }

    int rows = maxRows <= 0 ? DEFAULT_ROWS : Math.min(maxRows, MAX_ROWS);
    SqlDialect dialect = dialectFor(source);
    QueryRewriter rewriter = new QueryRewriter(dialect, null);

    QueryRewriter.Rewritten rewritten;
    try {
      rewritten = rewriter.rewrite(sql, (schema, table) -> govern(source, schema, table, principal, clientIp, purpose));
    } catch (QueryRewriter.RefusedException e) {
      audit(principal, source.id(), sql, null, "REJECTED", e.getMessage(), null, null, clientIp);
      throw new RejectedException(e.getMessage());
    } catch (RuntimeException e) {
      // Governing a table reference reads the catalog and evaluates policy, so
      // it can fail for reasons the rewriter never names. Whatever the cause,
      // the statement did not run and the attempt is on the record.
      audit(
          principal,
          source.id(),
          sql,
          null,
          "REJECTED",
          String.valueOf(e.getMessage()),
          null,
          null,
          clientIp);
      throw e;
    }

    long started = System.nanoTime();
    QueryExecutor.Page page;
    try {
      page =
          executor.run(
              new SourceProbe.Target(
                  source.engine().name(), source.host(), source.port(), source.defaultDatabase()),
              source.credentialRef(),
              rewritten.sql(),
              rows,
              TIMEOUT_SECONDS);
    } catch (Exception e) {
      long millis = (System.nanoTime() - started) / 1_000_000;
      audit(
          principal,
          source.id(),
          sql,
          rewritten.sql(),
          "FAILED",
          e.getMessage(),
          null,
          (int) millis,
          clientIp);
      // The source's message can name objects the caller is not entitled to
      // know exist, so it goes to the log and a shorter one goes back.
      LOG.warn("Query against {} failed: {}", source.name(), e.toString());
      throw new RejectedException("The source rejected the enforced statement: " + e.getMessage());
    }

    audit(
        principal,
        source.id(),
        sql,
        rewritten.sql(),
        "EXECUTED",
        null,
        (long) page.rows().size(),
        (int) page.millis(),
        clientIp);

    return new Result(
        page.columns(),
        page.columnTypes(),
        page.rows(),
        page.truncated(),
        page.millis(),
        rewritten.sql(),
        rewritten.assets(),
        explain(rewritten.governed()),
        rewritten.unenforceable());
  }

  // -------------------------------------------------------- explainability

  /**
   * Renders each decision in the words a data owner would use.
   *
   * <p>Deliberately derived from the decision rather than from the SQL: the SQL
   * is one of three renderings of the same decision (FR-6.0c), so an
   * explanation read out of it would only be true in proxy mode and would have
   * to be written twice more.
   */
  static List<Explanation> explain(List<QueryRewriter.Governed> governed) {
    List<Explanation> out = new ArrayList<>();
    for (QueryRewriter.Governed asset : governed) {
      PolicyDecision decision = asset.decision();
      if (decision == null) {
        continue;
      }

      Map<String, String> masked = new LinkedHashMap<>();
      if (decision.getColumnMasks() != null) {
        for (ResolvedColumnMask mask : decision.getColumnMasks()) {
          if (mask.getColumn() != null) {
            masked.put(mask.getColumn(), describe(mask));
          }
        }
      }

      List<String> filters = new ArrayList<>();
      if (decision.getRowPredicates() != null) {
        for (ResolvedRowPredicate predicate : decision.getRowPredicates()) {
          filters.add(describe(predicate));
        }
      }

      // A set, because the same policy commonly supplies both a row filter and
      // a mask and naming it twice reads like two policies.
      Set<String> policies = new LinkedHashSet<>();
      if (decision.getReasons() != null) {
        for (DecisionReason reason : decision.getReasons()) {
          if (Boolean.TRUE.equals(reason.getMatched()) && reason.getPolicyName() != null) {
            policies.add(
                reason.getScopeLevel() == null
                    ? reason.getPolicyName()
                    : reason.getPolicyName() + " (" + reason.getScopeLevel() + ")");
          }
        }
      }

      out.add(
          new Explanation(
              asset.fqn(),
              masked,
              decision.getHiddenColumns() == null ? List.of() : decision.getHiddenColumns(),
              filters,
              List.copyOf(policies)));
    }
    return out;
  }

  private static String describe(ResolvedColumnMask mask) {
    MaskingSpec spec = mask.getMasking();
    if (spec == null || spec.getFunction() == null) {
      return "masked";
    }
    String what =
        switch (spec.getFunction()) {
          case NULLIFY -> "replaced with null";
          case CONSTANT -> "replaced with "
              + (spec.getConstant() == null ? "a constant" : spec.getConstant());
          case HASH -> "hashed, so it still joins but no longer reads";
          case PARTIAL -> spec.getShowLast() == null
              ? "partly hidden"
              : "hidden except the last " + spec.getShowLast() + " characters";
          case REGEX_REPLACE -> "rewritten by pattern"
              + (spec.getRegex() == null ? "" : " " + spec.getRegex());
          case ROUNDING -> "rounded"
              + (spec.getRoundTo() == null ? "" : " to " + spec.getRoundTo());
          case CONDITIONAL -> "masked on some rows and not others";
        };
    // The condition is what makes it a cell mask rather than a column mask, and
    // it changes the reading of the value entirely, so it is never dropped.
    return mask.getCondition() == null ? what : what + ", where " + mask.getCondition();
  }

  private static String describe(ResolvedRowPredicate predicate) {
    ResolvedRowPredicate.Kind kind =
        predicate.getKind() == null ? ResolvedRowPredicate.Kind.ALWAYS_FALSE : predicate.getKind();
    String column = predicate.getColumn() == null ? "the row" : predicate.getColumn();
    List<Object> values =
        predicate.getValues() == null ? List.of() : List.copyOf(predicate.getValues());
    return switch (kind) {
      case ALWAYS_FALSE -> "no rows at all; the columns are visible but the contents are not";
      case IN_LIST -> values.isEmpty()
          // An empty list is the interesting case: it means the attribute this
          // filter reads is not set on the principal, so nothing can match, and
          // an empty grid is the correct answer rather than a missing one.
          ? column + " must match one of the principal's values, and they have none"
          : column + " is one of " + join(values);
      case ATTRIBUTE_COMPARE -> column
          + " "
          + (predicate.getOperator() == null ? "matches" : predicate.getOperator().value())
          + " "
          + (values.isEmpty() ? "the principal's value" : join(values));
      case ENTITLEMENT_JOIN -> "the row is listed against this principal in "
          + (predicate.getEntitlementKey() == null ? "the entitlement table"
              : predicate.getEntitlementKey());
      case RAW_PREDICATE -> predicate.getRawPredicate() == null
          ? "a policy-supplied condition"
          : predicate.getRawPredicate();
    };
  }

  private static String join(List<Object> values) {
    StringBuilder out = new StringBuilder();
    for (Object value : values) {
      if (out.length() > 0) {
        out.append(", ");
      }
      out.append(value);
    }
    return out.toString();
  }

  // ------------------------------------------------------------ governance

  /**
   * Turns {@code schema.table} as written into the asset, its decision and the
   * columns the source really has.
   *
   * <p>The column list comes from {@code asset_column} rather than from the
   * decision, because a mask can only be applied to a column somebody knows
   * about: the projection is built from what exists, and anything unmasked in
   * it is unmasked on purpose.
   */
  private QueryRewriter.Governed govern(
      DataSourceStore.Source source,
      String schema,
      String table,
      String principal,
      String clientIp,
      String purpose) {

    Optional<String> fqn =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT om_fqn FROM asset_fqn_map
                         WHERE data_source_id = CAST(:sourceId AS uuid)
                           AND lower(schema_name) = lower(:schema)
                           AND lower(object_name) = lower(:object)
                           AND verification_status <> 'ORPHANED'
                        """)
                    .bind("sourceId", source.id())
                    .bind("schema", schema)
                    .bind("object", table)
                    .mapTo(String.class)
                    .findOne());

    if (fqn.isEmpty()) {
      return null;
    }

    List<String> columns =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT c.name FROM asset_column c
                          JOIN asset a ON a.id = c.asset_id AND a.is_current
                         WHERE a.fqn = :fqn AND c.is_current
                         ORDER BY c.ordinal, c.name
                        """)
                    .bind("fqn", fqn.get())
                    .mapTo(String.class)
                    .list());

    if (columns.isEmpty()) {
      // Nothing is known about its shape, so nothing can be masked in it.
      return null;
    }

    // A null instant means "whenever this runs", which is both what a live
    // query means and the only shape of ask the decision cache may reuse
    // (FR-5.5). Passing Instant.now() here would pin the decision to a
    // microsecond and make every query on the hottest path a cold one.
    long startedAt = System.nanoTime();
    PolicyDecision decision =
        decisions.decide(
            new DecisionService.Ask(principal, fqn.get(), null, clientIp, purpose, null));
    // Rounded up, so a decision that took any time at all never records as
    // having taken none: a column of zeroes and a column of nulls are
    // equally useless for answering whether p95 is under 50ms.
    int evaluationMs = (int) Math.min(Integer.MAX_VALUE,
        (System.nanoTime() - startedAt + 999_999L) / 1_000_000L);
    recordDecision(decision, clientIp, purpose, evaluationMs);
    return new QueryRewriter.Governed(fqn.get(), decision, columns);
  }

  private static SqlDialect dialectFor(DataSourceStore.Source source) {
    return switch (source.engine()) {
      case POSTGRES -> new PostgresDialect();
      case SQLSERVER -> new SqlServerDialect();
    };
  }

  // ----------------------------------------------------------------- audit

  private void recordDecision(
      PolicyDecision decision, String clientIp, String purpose, int evaluationMs) {
    try {
      List<UUID> matched = new ArrayList<>();
      if (decision.getReasons() != null) {
        for (com.mfec.dac.schema.api.DecisionReason reason : decision.getReasons()) {
          if (Boolean.TRUE.equals(reason.getMatched()) && reason.getPolicyId() != null) {
            matched.add(reason.getPolicyId());
          }
        }
      }
      String document = json.writeValueAsString(decision);
      jdbi.useHandle(
          handle ->
              handle
                  .createUpdate(
                      """
                      INSERT INTO audit_decision (principal_name, target_fqn, allowed, mode,
                                                  decision, matched_policy_ids, evaluation_ms,
                                                  from_cache, purpose, client_ip)
                      VALUES (:principal, :fqn, :allowed, 'PROXY', CAST(:document AS jsonb),
                              :policyIds, :evaluationMs, :fromCache, :purpose,
                              CAST(:ip AS inet))
                      """)
                  .bind("principal", decision.getPrincipal())
                  .bind("fqn", decision.getAssetFqn())
                  .bind("allowed", Boolean.TRUE.equals(decision.getAllowed()))
                  .bind("document", document)
                  .bindArray("policyIds", UUID.class, matched.toArray(new UUID[0]))
                  .bind("evaluationMs", evaluationMs)
                  .bind("fromCache", Boolean.TRUE.equals(decision.getFromCache()))
                  .bind("purpose", purpose)
                  .bind("ip", inet(clientIp))
                  .execute());
    } catch (Exception e) {
      // An audit row that cannot be written must not stop a query that policy
      // already allowed — but it is a real problem and says so loudly.
      LOG.error("Could not write the decision audit row for {}", decision.getAssetFqn(), e);
    }
  }

  private void audit(
      String principal,
      UUID sourceId,
      String original,
      String rewritten,
      String outcome,
      String reason,
      Long rowCount,
      Integer millis,
      String clientIp) {

    try {
      jdbi.useHandle(
          handle ->
              handle
                  .createUpdate(
                      """
                      INSERT INTO audit_query (principal_name, data_source_id, original_sql,
                                               rewritten_sql, outcome, reject_reason, row_count,
                                               duration_ms, client_ip)
                      VALUES (:principal, CAST(:sourceId AS uuid), :original, :rewritten,
                              :outcome, :reason, :rowCount, :millis, CAST(:ip AS inet))
                      """)
                  .bind("principal", principal)
                  .bind("sourceId", sourceId)
                  .bind("original", original)
                  .bind("rewritten", rewritten)
                  .bind("outcome", outcome)
                  .bind("reason", reason)
                  .bind("rowCount", rowCount)
                  .bind("millis", millis)
                  .bind("ip", inet(clientIp))
                  .execute());
    } catch (Exception e) {
      LOG.error("Could not write the query audit row for {}", principal, e);
    }
  }

  /**
   * The client address in a form {@code inet} will accept, or null.
   *
   * <p>Kept as a method here because the audit writers above call it on every
   * query; the rule itself lives in {@link ClientAddress}, shared with the
   * identity audit trail so both tables store an address the same way.
   */
  static String inet(String clientIp) {
    return ClientAddress.normalise(clientIp);
  }
}
