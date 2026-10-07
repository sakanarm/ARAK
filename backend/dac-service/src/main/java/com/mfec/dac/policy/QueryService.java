package com.mfec.dac.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.audit.ClientAddress;
import com.mfec.dac.common.engine.SourceEngines;
import com.mfec.dac.compiler.sql.SqlDialect;
import com.mfec.dac.compiler.sql.SqlDialects;
import com.mfec.dac.enforcement.NativeReadGate;
import com.mfec.dac.proxy.ProxyCapabilities;
import com.mfec.dac.proxy.QueryRewriter;
import com.mfec.dac.purpose.SensitiveData;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.api.Unenforceable;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.QueryExecutor;
import com.mfec.dac.source.jdbc.SourceProbe;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
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

  /** How long a download of every row may take when nothing is configured. */
  public static final int DEFAULT_EXPORT_SECONDS = 600;

  /** Rows the source is asked for at a time on a download. */
  static final int EXPORT_FETCH_SIZE = 1_000;

  /**
   * @param assets the governed assets the statement turned out to touch
   * @param explanations what each of those assets was restricted by, in words
   * @param unenforceable restrictions this dialect could not express; they were
   *     tightened rather than dropped, and the caller is told (FR-6.0b)
   * @param millis how long the source took, on the read these rows came from
   * @param cached whether the rows came from {@link QueryResultCache} rather
   *     than a read made for this call
   * @param readAt when the source was read for these rows: now, or when the
   *     cached read was made
   * @param estimatedCost what the source's planner priced the statement at
   *     before it ran (FR-6.3 cost guard); null when it was not priced
   * @param warnings tables holding sensitive data the purpose does not allow,
   *     read anyway because the rule only warns (FR-21); empty when none
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
      List<Unenforceable> unenforceable,
      boolean cached,
      Instant readAt,
      Double estimatedCost,
      List<SensitiveData.Concern> warnings) {}

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
    private final String deniedAsset;
    private final boolean aboutStatement;

    public RejectedException(String message) {
      this(message, null, false);
    }

    /**
     * @param deniedAsset the asset whose decision said no, when that is why;
     *     null for every other kind of refusal
     * @param aboutStatement true when the statement itself is what failed -- it
     *     did not parse, named a table badly, or the source could not run it --
     *     so a corrected statement is worth suggesting. Never true for a
     *     refusal a policy made (M26).
     */
    public RejectedException(String message, String deniedAsset, boolean aboutStatement) {
      super(message);
      this.deniedAsset = deniedAsset;
      this.aboutStatement = aboutStatement && deniedAsset == null;
    }

    public String deniedAsset() {
      return deniedAsset;
    }

    public boolean aboutStatement() {
      return aboutStatement;
    }
  }

  /**
   * There was no room at the source or in the service for one more read
   * (FR-6.3 concurrency limit). Nothing was sent; the same statement sent
   * again in a few seconds will very likely run.
   */
  public static class BusyException extends RuntimeException {
    private final int retryAfterSeconds;

    public BusyException(String message, int retryAfterSeconds) {
      super(message);
      this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
      return retryAfterSeconds;
    }
  }

  private final Jdbi jdbi;
  private final ObjectMapper json;
  private final DataSourceStore sources;
  private final DecisionService decisions;
  private final QueryExecutor executor;
  private final QueryResultCache results;
  private final QueryAdmission admission;
  private final QueryCostGuard costs;
  private final Clock clock;
  private final SensitiveData sensitive;
  private final NativeReadGate nativeGate;
  private final LookupBinder lookups;

  public QueryService(
      Jdbi jdbi,
      ObjectMapper json,
      DataSourceStore sources,
      DecisionService decisions,
      QueryExecutor executor) {
    this(jdbi, json, sources, decisions, executor, QueryResultCache.disabled(), Clock.systemUTC());
  }

  /**
   * @param results where a result may be reused from; {@link
   *     QueryResultCache#disabled()} sends every statement to the source
   */
  public QueryService(
      Jdbi jdbi,
      ObjectMapper json,
      DataSourceStore sources,
      DecisionService decisions,
      QueryExecutor executor,
      QueryResultCache results,
      Clock clock) {
    this(
        jdbi,
        json,
        sources,
        decisions,
        executor,
        results,
        QueryAdmission.unlimited(),
        QueryCostGuard.off(),
        clock);
  }

  /**
   * @param admission how many reads may be out at the sources at once;
   *     {@link QueryAdmission#unlimited()} lets every one through
   * @param costs the planner estimate each engine's statements must stay
   *     under; {@link QueryCostGuard#off()} prices nothing
   */
  public QueryService(
      Jdbi jdbi,
      ObjectMapper json,
      DataSourceStore sources,
      DecisionService decisions,
      QueryExecutor executor,
      QueryResultCache results,
      QueryAdmission admission,
      QueryCostGuard costs,
      Clock clock) {
    this(jdbi, json, sources, decisions, executor, results, admission, costs, clock, null);
  }

  /**
   * @param sensitive what counts as sensitive data, and what happens when a
   *     purpose that does not allow it reads a table holding some; null checks nothing
   */
  public QueryService(
      Jdbi jdbi,
      ObjectMapper json,
      DataSourceStore sources,
      DecisionService decisions,
      QueryExecutor executor,
      QueryResultCache results,
      QueryAdmission admission,
      QueryCostGuard costs,
      Clock clock,
      SensitiveData sensitive) {
    this(jdbi, json, sources, decisions, executor, results, admission, costs, clock, sensitive, null);
  }

  /**
   * @param nativeGate what a source whose policies are pushed down natively
   *     lets the proxy read; null reads every source as the policy decides
   */
  public QueryService(
      Jdbi jdbi,
      ObjectMapper json,
      DataSourceStore sources,
      DecisionService decisions,
      QueryExecutor executor,
      QueryResultCache results,
      QueryAdmission admission,
      QueryCostGuard costs,
      Clock clock,
      SensitiveData sensitive,
      NativeReadGate nativeGate) {
    this.jdbi = jdbi;
    this.json = json;
    this.sources = sources;
    this.decisions = decisions;
    this.executor = executor;
    this.results = results == null ? QueryResultCache.disabled() : results;
    this.admission = admission == null ? QueryAdmission.unlimited() : admission;
    this.costs = costs == null ? QueryCostGuard.off() : costs;
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.sensitive = sensitive == null ? SensitiveData.off() : sensitive;
    this.nativeGate = nativeGate;
    this.lookups = new LookupBinder(jdbi, json, sources, this::readLookup);
  }

  /** As the eight-argument form, with a held result allowed. */
  public Result run(
      UUID sourceId,
      String sql,
      String principal,
      String runBy,
      int maxRows,
      String clientIp,
      String purpose) {
    return run(sourceId, sql, principal, runBy, maxRows, clientIp, purpose, false);
  }

  /**
   * @param principal whose access governs the rows; not necessarily the caller,
   *     because the explorer doubles as the simulator's evidence (FR-5.2)
   * @param runBy the signed-in account that sent it, recorded beside the
   *     principal so a query run as somebody else is never filed as theirs alone
   * @param fresh read the source even when a young enough result is held; the
   *     new result then replaces it
   */
  public Result run(
      UUID sourceId,
      String sql,
      String principal,
      String runBy,
      int maxRows,
      String clientIp,
      String purpose,
      boolean fresh) {

    Enforced enforced = enforce(sourceId, sql, principal, runBy, clientIp, purpose, false);
    DataSourceStore.Source source = enforced.source();
    QueryRewriter.Rewritten rewritten = enforced.rewritten();
    Set<String> touched = enforced.touched();
    int rows = maxRows <= 0 ? DEFAULT_ROWS : Math.min(maxRows, MAX_ROWS);

    // Looked up only now, after every table has been governed and every
    // decision recorded: the key is the enforced statement, so a refusal has
    // already been thrown and a hit is exactly the rows a read would return
    // (see QueryResultCache on why no principal is needed in the key).
    QueryResultCache.Key key =
        new QueryResultCache.Key(
            source.id(),
            source.engine().name(),
            source.host(),
            source.port(),
            source.defaultDatabase(),
            source.credentialRef(),
            source.updatedAt(),
            rewritten.sql(),
            rows);
    long started = System.nanoTime();
    // A mapping joined into the statement is read by the source each time, so
    // a change to it has to count from the next query: holding the rows would
    // keep showing what the mapping no longer allows. A list ARAK read itself
    // is in the statement, so a changed mapping is a different key anyway.
    boolean holdable = !joinsMapping(rewritten.governed());
    if (!fresh && holdable) {
      Optional<QueryResultCache.Hit> hit = results.get(key, clock.instant());
      if (hit.isPresent()) {
        QueryExecutor.Page held = hit.get().page();
        // The duration recorded is what this caller waited, which is the
        // number a latency report is about; the source's own time for the
        // read goes back to the caller with the rows.
        long millis = (System.nanoTime() - started) / 1_000_000;
        audit(
            principal,
            source.id(),
            sql,
            rewritten.sql(),
            "EXECUTED",
            null,
            (long) held.rows().size(),
            (int) millis,
            clientIp,
            touched,
            runBy,
            true,
            false);
        return new Result(
            held.columns(),
            held.columnTypes(),
            held.rows(),
            held.truncated(),
            held.millis(),
            rewritten.sql(),
            rewritten.assets(),
            explain(rewritten.governed()),
            rewritten.unenforceable(),
            true,
            hit.get().storedAt(),
            held.estimatedCost(),
            enforced.warnings());
      }
    }

    // Read before the statement goes out, so a flush while it runs keeps its
    // rows out of the cache.
    long generation = results.generation();
    double ceiling = costs.ceilingFor(source.engine().name());
    Instant readAt;
    QueryExecutor.Page page;
    // A slot is taken only here, for the read itself: a refusal never needed
    // one and a cached answer costs the source nothing. The sender is the one
    // charged for it, since the load is theirs whoever the rows are for.
    try (QueryAdmission.Permit slot =
        admission.admit(source.id(), runBy == null ? principal : runBy)) {
      readAt = clock.instant();
      page =
          executor.run(
              target(source),
              source.credentialRef(),
              rewritten.sql(),
              rows,
              TIMEOUT_SECONDS,
              ceiling);
    } catch (Exception e) {
      throw notRun(e, enforced, sql, principal, runBy, clientIp, started, false);
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
        clientIp,
        touched,
        runBy,
        false,
        false);

    if (ceiling > 0 && costs.admitted(page.estimatedCost(), page.unpricedBecause())) {
      LOG.info(
          "Statements to {} are running without a cost estimate: {}",
          source.name(),
          page.unpricedBecause());
    }

    if (holdable) {
      results.put(key, page, readAt, generation);
    }

    return new Result(
        page.columns(),
        page.columnTypes(),
        page.rows(),
        page.truncated(),
        page.millis(),
        rewritten.sql(),
        rewritten.assets(),
        explain(rewritten.governed()),
        rewritten.unenforceable(),
        false,
        readAt,
        page.estimatedCost(),
        enforced.warnings());
  }

  // -------------------------------------------------------------- download

  /**
   * Starts a download of every row the statement returns, as the caller
   * (FR-6.3; the console's "Download all rows").
   *
   * <p>The on-screen read keeps its cap: a grid of five thousand rows is
   * already more than anybody reads, and every row of it sits in the browser.
   * A download is the other thing people mean by "all of it": the same
   * statement, through the same policy, written out as it is read so neither
   * ARAK nor the browser holds the table. Nothing is cached, and nothing but
   * the caller's own identity may be used: an administrator running as
   * somebody else is checking what they would see, not taking a copy of it.
   *
   * <p>Everything that can refuse happens here, before the first byte: the
   * policy, a slot, the cost of the whole read, and the source's first answer.
   * A refusal is thrown and audited just as {@link #run} does it. What comes
   * back holds a source connection and a slot until it is written or closed.
   *
   * @param maxSeconds how long the whole download may take, from here to the
   *     last row; a read still going then is stopped and audited as failed
   */
  public Download export(
      UUID sourceId, String sql, String principal, String clientIp, String purpose, int maxSeconds) {

    Enforced enforced = enforce(sourceId, sql, principal, principal, clientIp, purpose, true);
    DataSourceStore.Source source = enforced.source();
    long started = System.nanoTime();
    int seconds = maxSeconds <= 0 ? DEFAULT_EXPORT_SECONDS : maxSeconds;

    QueryAdmission.Permit slot = null;
    try {
      slot = admission.admit(source.id(), principal);
      QueryExecutor.Cursor cursor =
          executor.open(
              target(source),
              source.credentialRef(),
              enforced.rewritten().sql(),
              seconds,
              costs.ceilingFor(source.engine().name()),
              EXPORT_FETCH_SIZE);
      return new Download(enforced, sql, principal, clientIp, cursor, slot, started, seconds);
    } catch (Exception e) {
      if (slot != null) {
        slot.close();
      }
      throw notRun(e, enforced, sql, principal, principal, clientIp, started, true);
    }
  }

  /**
   * A download the source has started answering, not yet written anywhere.
   *
   * <p>Written once, by {@link #writeCsv}, which audits the outcome and gives
   * the connection back whatever happens. Closing one that was never written
   * does both too, so a response that failed to start cannot hold a source
   * connection or leave the attempt off the record.
   */
  public final class Download implements AutoCloseable {
    private final Enforced enforced;
    private final String sql;
    private final String principal;
    private final String clientIp;
    private final QueryExecutor.Cursor cursor;
    private final QueryAdmission.Permit slot;
    private final long started;
    private final int maxSeconds;
    private boolean audited;
    private boolean closed;

    private Download(
        Enforced enforced,
        String sql,
        String principal,
        String clientIp,
        QueryExecutor.Cursor cursor,
        QueryAdmission.Permit slot,
        long started,
        int maxSeconds) {
      this.enforced = enforced;
      this.sql = sql;
      this.principal = principal;
      this.clientIp = clientIp;
      this.cursor = cursor;
      this.slot = slot;
      this.started = started;
      this.maxSeconds = maxSeconds;
    }

    public List<String> columns() {
      return cursor.columns();
    }

    /** The governed tables it reads, for the file's name. */
    public List<String> assets() {
      return enforced.rewritten().assets();
    }

    /**
     * Writes the header and then each row as the source sends it.
     *
     * <p>A read that fails part-way throws rather than ending the file: the
     * response is cut off instead of closed cleanly, so the browser reports a
     * failed download rather than saving what arrived as if it were all of it.
     * Either way the audit row says how many rows went out.
     */
    public void writeCsv(OutputStream out) throws IOException {
      long deadline = started + maxSeconds * 1_000_000_000L;
      long written = 0;
      String failure = null;
      try {
        Writer writer =
            new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 16);
        writer.write(QueryCsv.BOM);
        QueryCsv.line(writer, cursor.columns());
        // The header goes now, so the browser has the start of the file while
        // the source is still producing the rest.
        writer.flush();
        List<Object> row;
        while ((row = cursor.next()) != null) {
          if (System.nanoTime() > deadline) {
            failure =
                "The download was stopped after "
                    + written
                    + " rows: it ran past the "
                    + maxSeconds
                    + " seconds a download may take.";
            throw new IOException(failure);
          }
          QueryCsv.line(writer, row);
          written++;
        }
        writer.flush();
      } catch (SQLException e) {
        failure = "The source stopped answering after " + written + " rows: " + e.getMessage();
        LOG.warn("Download from {} failed part-way: {}", enforced.source().name(), e.toString());
        throw new IOException("The source stopped answering part-way through the download", e);
      } catch (IOException | RuntimeException e) {
        if (failure == null) {
          // Most often the person cancelled, or closed the tab.
          failure = "The download was stopped after " + written + " rows: " + e.getMessage();
        }
        throw e;
      } finally {
        record(failure == null ? "EXECUTED" : "FAILED", failure, written);
        close();
      }
    }

    private void record(String outcome, String reason, long rows) {
      if (audited) {
        return;
      }
      audited = true;
      audit(
          principal,
          enforced.source().id(),
          sql,
          enforced.rewritten().sql(),
          outcome,
          reason,
          rows,
          (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000),
          clientIp,
          enforced.touched(),
          principal,
          false,
          true);
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      record("FAILED", "The download was prepared and never sent.", 0);
      cursor.close();
      slot.close();
    }
  }

  // ----------------------------------------------------- the shared steps

  /** A statement the policy has been compiled into, not yet sent anywhere. */
  private record Enforced(
      DataSourceStore.Source source,
      QueryRewriter.Rewritten rewritten,
      Set<String> touched,
      List<SensitiveData.Concern> warnings) {}

  /**
   * Finds the source, governs every table the statement names and rewrites it,
   * auditing and throwing on anything that stops it there.
   *
   * @param exported whether this is a download of every row, as the audit row
   *     records it -- a refused download is still somebody trying to take a copy
   */
  private Enforced enforce(
      UUID sourceId,
      String sql,
      String principal,
      String runBy,
      String clientIp,
      String purpose,
      boolean exported) {

    // Audited before anything else can go right, because a query aimed at a
    // source that is gone or switched off is still someone trying to read data
    // and is exactly the attempt an auditor asks about later. Leaving these two
    // to throw unrecorded meant the log answered "what did people run" with
    // only the runs that got as far as a rewrite.
    Optional<DataSourceStore.Source> found = sources.find(sourceId);
    if (found.isEmpty()) {
      String reason = "No data source " + sourceId;
      audit(principal, null, sql, null, "REJECTED", reason, null, null, clientIp, Set.of(), runBy,
          false, exported);
      throw new RejectedException(reason);
    }
    DataSourceStore.Source source = found.get();
    if (!source.enabled()) {
      String reason = source.name() + " is disabled";
      audit(principal, source.id(), sql, null, "REJECTED", reason, null, null, clientIp, Set.of(),
          runBy, false, exported);
      throw new RejectedException(reason);
    }

    SqlDialect dialect = dialectFor(source);
    QueryRewriter rewriter = new QueryRewriter(dialect, null);

    // Every governed table the rewriter resolved, in the order it met them,
    // including the one a refusal names: the query log files each row under
    // the tables it touched so an owner can be shown the reads of theirs.
    Set<String> touched = new LinkedHashSet<>();
    // One per table, however many times the statement names it.
    Map<String, SensitiveData.Concern> warnings = new LinkedHashMap<>();
    QueryRewriter.Rewritten rewritten;
    try {
      rewritten =
          rewriter.rewrite(
              sql,
              (schema, table) ->
                  govern(
                      source, schema, table, principal, runBy, clientIp, purpose, touched,
                      warnings));
    } catch (QueryRewriter.RefusedException e) {
      if (e instanceof QueryRewriter.DeniedException denied && denied.assetFqn() != null) {
        touched.add(denied.assetFqn());
      }
      audit(principal, source.id(), sql, null, "REJECTED", e.getMessage(), null, null, clientIp,
          touched, runBy, false, exported);
      throw new RejectedException(
          e.getMessage(),
          e instanceof QueryRewriter.DeniedException denied ? denied.assetFqn() : null,
          e.aboutStatement());
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
          clientIp,
          touched,
          runBy,
          false,
          exported);
      throw e;
    }
    return new Enforced(source, rewritten, touched, List.copyOf(warnings.values()));
  }

  /**
   * A read that never started, audited and turned into what the caller is
   * told: no room, too expensive, or the source would not run it.
   */
  private RuntimeException notRun(
      Exception e,
      Enforced enforced,
      String sql,
      String principal,
      String runBy,
      String clientIp,
      long started,
      boolean exported) {
    DataSourceStore.Source source = enforced.source();
    String rewritten = enforced.rewritten().sql();
    Set<String> touched = enforced.touched();
    int millis = (int) ((System.nanoTime() - started) / 1_000_000);

    if (e instanceof QueryAdmission.BusyException full) {
      // REJECTED rather than a new outcome: the statement did not run, which
      // is what the outcome records; QueryRefusals files it as BUSY from the
      // wording, so a throttle is never read as somebody refused access.
      String reason = busy(full, source);
      audit(principal, source.id(), sql, rewritten, "REJECTED", reason, null, millis, clientIp,
          touched, runBy, false, exported);
      return new BusyException(reason, full.retryAfterSeconds());
    }
    if (e instanceof QueryExecutor.CostExceededException over) {
      costs.refused(over.estimate());
      String reason =
          "The source's planner estimates this statement at a cost of "
              + QueryCostGuard.format(over.estimate())
              + ", over the ceiling of "
              + QueryCostGuard.format(over.ceiling())
              + " set for "
              + source.engine().name()
              + " sources, so it was not run. "
              + (exported
                  ? "A download reads every row, so it is priced without the row limit the"
                      + " screen uses. A WHERE that narrows it to the rows you need usually"
                      + " brings it under."
                  : "A WHERE on an indexed column, fewer joins or less to sort usually brings"
                      + " it under.");
      audit(principal, source.id(), sql, rewritten, "REJECTED", reason, null, millis, clientIp,
          touched, runBy, false, exported);
      // About the statement, so the console offers to narrow it.
      return new RejectedException(reason, null, true);
    }
    audit(principal, source.id(), sql, rewritten, "FAILED", e.getMessage(), null, millis, clientIp,
        touched, runBy, false, exported);
    // The source's message can name objects the caller is not entitled to
    // know exist, so it goes to the log and a shorter one goes back.
    LOG.warn("Query against {} failed: {}", source.name(), e.toString());
    return new RejectedException(
        "The source rejected the enforced statement: " + e.getMessage(), null, true);
  }

  /**
   * Reads a lookup's mapping table for {@link LookupBinder}, as any other read:
   * in a slot of its own, charged to whoever sent the statement, and priced
   * against the same ceiling.
   */
  private QueryExecutor.Page readLookup(
      DataSourceStore.Source mapping, String sql, int maxRows, String caller) throws Exception {
    try (QueryAdmission.Permit slot = admission.admit(mapping.id(), caller)) {
      return executor.run(
          target(mapping),
          mapping.credentialRef(),
          sql,
          maxRows,
          LookupBinder.TIMEOUT_SECONDS,
          costs.ceilingFor(mapping.engine().name()));
    } catch (QueryAdmission.BusyException full) {
      throw new BusyException(busy(full, mapping), full.retryAfterSeconds());
    }
  }

  /** Whether any of these decisions joins a mapping table into the statement. */
  private static boolean joinsMapping(List<QueryRewriter.Governed> governed) {
    for (QueryRewriter.Governed asset : governed) {
      if (LookupBinder.hasLookup(asset.decision())) {
        return true;
      }
    }
    return false;
  }

  private static SourceProbe.Target target(DataSourceStore.Source source) {
    return new SourceProbe.Target(
        source.engine().name(), source.host(), source.port(), source.defaultDatabase());
  }

  /** Why there was no room, in the words of whichever ceiling was full. */
  private static String busy(QueryAdmission.BusyException e, DataSourceStore.Source source) {
    return switch (e.scope()) {
      case CALLER -> "Too many of your queries are running right now: ARAK runs "
          + e.limit()
          + " at a time for one person. Let one finish and run this again.";
      case SOURCE -> "Too many queries are running against "
          + source.name()
          + " right now: ARAK sends it at most "
          + e.limit()
          + " at a time, so that the source is not overloaded. Try again in a few seconds.";
      case SERVICE -> "Too many queries are running on ARAK right now: it runs at most "
          + e.limit()
          + " at a time. Try again in a few seconds.";
    };
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
    // Before the values are read: a lookup that ARAK read for itself carries
    // them, and they are said as where they came from, never as a list.
    String lookedUp = LookupBinder.describe(predicate);
    if (lookedUp != null) {
      return lookedUp;
    }
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
      case LOOKUP -> column + " is one of the values of a mapping table";
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
      String runBy,
      String clientIp,
      String purpose,
      Set<String> touched,
      Map<String, SensitiveData.Concern> warnings) {

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
    touched.add(fqn.get());

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
    // Whether the purpose may be used on what this table holds (FR-21), asked
    // only of a table the person may read: a refusal by policy says enough.
    // Kept on the decision row either way, so a report of what sensitive data
    // was used for reads what was true when it was read.
    SensitiveData.Judgement judged =
        Boolean.TRUE.equals(decision.getAllowed())
            ? jdbi.withHandle(handle -> sensitive.judge(handle, fqn.get(), purpose))
            : null;
    SensitiveData.Concern concern = judged == null ? null : judged.concern();
    recordDecision(
        decision,
        clientIp,
        purpose,
        evaluationMs,
        judged == null ? null : judged.sensitive(),
        concern == null ? null : concern.refuses() ? "REFUSED" : "WARNED");
    if (concern != null && concern.refuses()) {
      // Not offered as a request for access: the person may read the table,
      // and what is refused is the purpose they named for it.
      throw new RejectedException(concern.message(), null, false);
    }
    if (concern != null) {
      warnings.putIfAbsent(fqn.get(), concern);
    }

    // On a source whose policies are pushed down, the proxy reads only what the
    // role on the source also gives: recorded as the policy decided, refused
    // here, and audited as refused by the caller like any other refusal.
    if (nativeGate != null) {
      Optional<String> refused = nativeGate.refusal(source, schema, table, decision);
      if (refused.isPresent()) {
        throw new RejectedException(refused.get(), null, false);
      }
    }

    // Recorded first, refused second. A query that is about to be turned away
    // because this engine cannot express the mask is still a decision that was
    // reached, and an auditor asking who tried to read this table deserves the
    // same answer whichever engine it lives on.
    ProxyCapabilities.require(SourceEngines.of(source.engine().name()), fqn.get(), decision);

    // Last, on a copy: what was recorded is the policy's decision, and the
    // values a mapping gave this person belong to this statement alone.
    PolicyDecision bound =
        lookups.bind(source, fqn.get(), decision, runBy == null ? principal : runBy);
    return new QueryRewriter.Governed(fqn.get(), bound, columns);
  }

  /**
   * The dialect to rewrite this source's queries in.
   *
   * <p>Resolved through the registry rather than a switch. The switch compiled
   * happily with an arm missing and failed when somebody ran a query, which is
   * both the latest and the most expensive moment to find out.
   */
  private static SqlDialect dialectFor(DataSourceStore.Source source) {
    return SqlDialects.forEngineId(source.engine().name());
  }

  // ----------------------------------------------------------------- audit

  /**
   * @param sensitive whether the table held sensitive data under the rule;
   *     null when nobody looked -- the rule was off, or the answer was no anyway
   * @param purposeCheck WARNED or REFUSED when the purpose did not allow it
   */
  private void recordDecision(
      PolicyDecision decision,
      String clientIp,
      String purpose,
      int evaluationMs,
      Boolean sensitive,
      String purposeCheck) {
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
                                                  from_cache, purpose, client_ip, sensitive,
                                                  purpose_check)
                      VALUES (:principal, :fqn, :allowed, 'PROXY', CAST(:document AS jsonb),
                              :policyIds, :evaluationMs, :fromCache, :purpose,
                              CAST(:ip AS inet), :sensitive, :purposeCheck)
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
                  .bind("sensitive", sensitive)
                  .bind("purposeCheck", purposeCheck)
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
      String clientIp,
      Set<String> assets,
      String runBy,
      boolean fromCache,
      boolean exported) {

    try {
      jdbi.useHandle(
          handle ->
              handle
                  .createUpdate(
                      """
                      INSERT INTO audit_query (principal_name, data_source_id, original_sql,
                                               rewritten_sql, outcome, reject_reason, row_count,
                                               duration_ms, client_ip, asset_fqns, run_by,
                                               served_from_cache, exported)
                      VALUES (:principal, CAST(:sourceId AS uuid), :original, :rewritten,
                              :outcome, :reason, :rowCount, :millis, CAST(:ip AS inet),
                              :assets, :runBy, :fromCache, :exported)
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
                  .bindArray("assets", String.class, assets.toArray(new String[0]))
                  .bind("runBy", runBy == null || runBy.equalsIgnoreCase(principal) ? null : runBy)
                  .bind("fromCache", fromCache)
                  .bind("exported", exported)
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
