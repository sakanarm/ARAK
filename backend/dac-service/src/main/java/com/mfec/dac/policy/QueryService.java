package com.mfec.dac.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.compiler.sql.PostgresDialect;
import com.mfec.dac.compiler.sql.SqlDialect;
import com.mfec.dac.compiler.sql.SqlServerDialect;
import com.mfec.dac.proxy.QueryRewriter;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.Unenforceable;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.QueryExecutor;
import com.mfec.dac.source.jdbc.SourceProbe;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
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
      List<Unenforceable> unenforceable) {}

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

    DataSourceStore.Source source =
        sources
            .find(sourceId)
            .orElseThrow(() -> new RejectedException("No data source " + sourceId));
    if (!source.enabled()) {
      throw new RejectedException(source.name() + " is disabled");
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
        rewritten.unenforceable());
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

    PolicyDecision decision =
        decisions.decide(
            new DecisionService.Ask(principal, fqn.get(), Instant.now(), clientIp, purpose, null));
    recordDecision(decision, clientIp, purpose);
    return new QueryRewriter.Governed(fqn.get(), decision, columns);
  }

  private static SqlDialect dialectFor(DataSourceStore.Source source) {
    return switch (source.engine()) {
      case POSTGRES -> new PostgresDialect();
      case SQLSERVER -> new SqlServerDialect();
    };
  }

  // ----------------------------------------------------------------- audit

  private void recordDecision(PolicyDecision decision, String clientIp, String purpose) {
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
                                                  decision, matched_policy_ids, from_cache,
                                                  purpose, client_ip)
                      VALUES (:principal, :fqn, :allowed, 'PROXY', CAST(:document AS jsonb),
                              :policyIds, :fromCache, :purpose, CAST(:ip AS inet))
                      """)
                  .bind("principal", decision.getPrincipal())
                  .bind("fqn", decision.getAssetFqn())
                  .bind("allowed", Boolean.TRUE.equals(decision.getAllowed()))
                  .bind("document", document)
                  .bindArray("policyIds", UUID.class, matched.toArray(new UUID[0]))
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

  /** IPv4 dotted-quad or IPv6 hex-and-colons. Deliberately not a hostname. */
  private static final Pattern LITERAL_ADDRESS =
      Pattern.compile("^(?:[0-9.]+|[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*)$");

  /**
   * The client address in a form {@code inet} will accept, or null.
   *
   * <p>Jetty hands back an IPv6 loopback as {@code [0:0:0:0:0:0:0:1]}, brackets
   * and all, and Postgres rejects that — which meant the insert threw, the catch
   * below logged it, and the audit row was lost while the query itself went
   * through. Losing the address is acceptable; losing the row is not, because
   * the row is the compliance record (FR-8.2, FR-8.3).
   */
  static String inet(String clientIp) {
    if (clientIp == null || clientIp.isBlank()) {
      return null;
    }
    String candidate = clientIp.trim();
    if (candidate.startsWith("[") && candidate.endsWith("]")) {
      candidate = candidate.substring(1, candidate.length() - 1);
    }
    // A zone index (fe80::1%eth0) is part of a scoped IPv6 address and is not
    // part of what inet stores.
    int zone = candidate.indexOf('%');
    if (zone > 0) {
      candidate = candidate.substring(0, zone);
    }
    // Only a literal address goes any further. getByName would resolve a
    // hostname against DNS, and an audit write is the last place that should
    // reach the network — so the shape is checked here first.
    if (!LITERAL_ADDRESS.matcher(candidate).matches()) {
      LOG.warn("Dropping a client address that is not an IP literal from the audit row");
      return null;
    }
    try {
      return InetAddress.getByName(candidate).getHostAddress();
    } catch (UnknownHostException e) {
      LOG.warn("Dropping an unparseable client address from the audit row: {}", clientIp);
      return null;
    }
  }
}
