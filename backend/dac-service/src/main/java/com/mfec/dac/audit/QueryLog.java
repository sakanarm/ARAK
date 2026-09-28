package com.mfec.dac.audit;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;

/**
 * The proxied queries on record, read back (FR-8.3, M10).
 *
 * <p>Who may read which rows is decided here, in the SQL, and not by the
 * resource filtering a page it has already fetched: a page cut down after the
 * fact comes back short or empty while there are rows the reader may see
 * further on, and a count taken before the cut tells the reader how many rows
 * they were not shown. {@link Reader} carries what the SQL needs to decide.
 *
 * <p>{@code client_ip} is never selected. The address a query came from is
 * kept for the audit trail and for policies that name networks; it is not
 * something one person reading the log learns about another.
 */
public class QueryLog {

  /** The most rows one page returns. */
  public static final int MAX_LIMIT = 200;

  /** The widest window one call may read over. */
  public static final int MAX_DAYS = 3650;

  private final Jdbi jdbi;

  public QueryLog(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /**
   * Who is reading, as far as row visibility goes.
   *
   * @param everything reads every row: an administrator, policy author or auditor
   * @param username their own rows are always theirs to read, whether they ran
   *     them or had them run as them
   * @param scopes the data-owner scopes whose tables' rows they may also read;
   *     empty for anyone who is not a data owner
   */
  public record Reader(boolean everything, String username, List<String> scopes) {
    public Reader {
      scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
  }

  /**
   * What to narrow to. Every field is optional.
   *
   * @param text matched against the SQL as sent, case-insensitively, and only
   *     on rows whose SQL the reader may see: a search that matched hidden SQL
   *     would tell the reader what it said
   * @param before the id of the last row of the previous page
   */
  public record Filter(
      String outcome,
      String principal,
      String text,
      String assetFqn,
      UUID sourceId,
      Instant since,
      Instant until,
      Long before) {}

  /** One row as stored, before the resource decides what of it a reader sees. */
  public record Entry(
      long id,
      Instant occurredAt,
      String principal,
      String runBy,
      UUID sourceId,
      String sourceName,
      String originalSql,
      String rewrittenSql,
      String outcome,
      String rejectReason,
      Long rowCount,
      Integer durationMs,
      List<String> assets,
      boolean fromCache,
      boolean exported) {}

  /** How many rows in the window, before paging, by outcome. */
  public record Counts(int total, int executed, int rejected, int failed) {}

  /** Refused rows grouped by the reason text, for the dashboard's breakdown. */
  public record ReasonCount(String outcome, String reason, int count) {}

  private static final String VISIBLE =
      """
      (:everything
       OR lower(q.principal_name) = lower(:me)
       OR lower(q.run_by) = lower(:me)
       OR EXISTS (SELECT 1 FROM unnest(q.asset_fqns) f, unnest(CAST(:scopes AS text[])) s
                   WHERE f = s OR starts_with(f, s || '.')))
      """;

  /**
   * The rows whose SQL an owner may read although somebody else ran them:
   * statements that were enforced in full, on tables that are all theirs. A
   * refused statement stops at the first table it cannot use, so the tables
   * after it were never resolved and its text may name tables that are not.
   */
  private static final String SQL_VISIBLE =
      """
      (:everything
       OR lower(q.principal_name) = lower(:me)
       OR lower(q.run_by) = lower(:me)
       OR (q.outcome IN ('EXECUTED', 'FAILED') AND cardinality(q.asset_fqns) > 0
           AND NOT EXISTS (
             SELECT 1 FROM unnest(q.asset_fqns) f
              WHERE NOT EXISTS (SELECT 1 FROM unnest(CAST(:scopes AS text[])) s
                                 WHERE f = s OR starts_with(f, s || '.')))))
      """;

  private static final String WHERE =
      """
      WHERE %s
        AND (CAST(:outcome AS text) IS NULL OR q.outcome = :outcome)
        AND (CAST(:principal AS text) IS NULL OR lower(q.principal_name) = lower(:principal))
        AND (CAST(:asset AS text) IS NULL OR q.asset_fqns @> ARRAY[CAST(:asset AS text)])
        AND (CAST(:sourceId AS uuid) IS NULL OR q.data_source_id = CAST(:sourceId AS uuid))
        AND (CAST(:since AS timestamptz) IS NULL OR q.occurred_at >= CAST(:since AS timestamptz))
        AND (CAST(:until AS timestamptz) IS NULL OR q.occurred_at < CAST(:until AS timestamptz))
        AND (CAST(:pattern AS text) IS NULL OR (%s AND q.original_sql ILIKE :pattern ESCAPE '\\'))
      """
          .formatted(VISIBLE, SQL_VISIBLE);

  /** Newest first, at most {@code limit} rows, starting after {@code filter.before()}. */
  public List<Entry> page(Reader reader, Filter filter, int limit) {
    return jdbi.withHandle(
        handle ->
            bindAll(
                    handle.createQuery(
                        """
                        SELECT q.id, q.occurred_at, q.principal_name, q.run_by, q.data_source_id,
                               d.name AS source_name, q.original_sql, q.rewritten_sql, q.outcome,
                               q.reject_reason, q.row_count, q.duration_ms, q.asset_fqns,
                               q.served_from_cache, q.exported
                          FROM audit_query q
                          LEFT JOIN data_source d ON d.id = q.data_source_id
                        """
                            + WHERE
                            + """
                              AND (CAST(:before AS bigint) IS NULL OR q.id < CAST(:before AS bigint))
                            ORDER BY q.id DESC
                            LIMIT :limit
                            """),
                    reader,
                    filter)
                .bind("before", filter.before())
                .bind("limit", limit)
                .map((rs, ctx) -> map(rs))
                .list());
  }

  /** Every row the filter matches, ignoring the page cursor, by outcome. */
  public Counts counts(Reader reader, Filter filter) {
    return jdbi.withHandle(
        handle ->
            bindAll(
                    handle.createQuery(
                        """
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE q.outcome = 'EXECUTED') AS executed,
                               count(*) FILTER (WHERE q.outcome = 'REJECTED') AS rejected,
                               count(*) FILTER (WHERE q.outcome = 'FAILED') AS failed
                          FROM audit_query q
                        """
                            + WHERE),
                    reader,
                    filter)
                .map(
                    (rs, ctx) ->
                        new Counts(
                            rs.getInt("total"),
                            rs.getInt("executed"),
                            rs.getInt("rejected"),
                            rs.getInt("failed")))
                .one());
  }

  /**
   * Queries per day, by outcome, for every row the filter matches.
   *
   * @return day (UTC, ISO date) to {executed, rejected, failed}, oldest first
   */
  public Map<String, int[]> perDay(Reader reader, Filter filter) {
    List<Map.Entry<String, int[]>> rows =
        jdbi.withHandle(
            handle ->
                bindAll(
                        handle.createQuery(
                            """
                            SELECT to_char(date_trunc('day', q.occurred_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD') AS day,
                                   count(*) FILTER (WHERE q.outcome = 'EXECUTED') AS executed,
                                   count(*) FILTER (WHERE q.outcome = 'REJECTED') AS rejected,
                                   count(*) FILTER (WHERE q.outcome = 'FAILED') AS failed
                              FROM audit_query q
                            """
                                + WHERE
                                + " GROUP BY 1 ORDER BY 1"),
                        reader,
                        filter)
                    .map(
                        (rs, ctx) ->
                            Map.entry(
                                rs.getString("day"),
                                new int[] {
                                  rs.getInt("executed"), rs.getInt("rejected"), rs.getInt("failed")
                                }))
                    .list());
    Map<String, int[]> days = new TreeMap<>();
    rows.forEach(day -> days.put(day.getKey(), day.getValue()));
    return days;
  }

  /** Refused and failed rows grouped by their reason, most frequent first. */
  public List<ReasonCount> reasons(Reader reader, Filter filter, int limit) {
    return jdbi.withHandle(
        handle ->
            bindAll(
                    handle.createQuery(
                        """
                        SELECT q.outcome, q.reject_reason, count(*) AS n
                          FROM audit_query q
                        """
                            + WHERE
                            + """
                              AND q.outcome <> 'EXECUTED'
                            GROUP BY q.outcome, q.reject_reason
                            ORDER BY n DESC, q.reject_reason
                            LIMIT :limit
                            """),
                    reader,
                    filter)
                .bind("limit", limit)
                .map(
                    (rs, ctx) ->
                        new ReasonCount(
                            rs.getString("outcome"), rs.getString("reject_reason"), rs.getInt("n")))
                .list());
  }

  private static org.jdbi.v3.core.statement.Query bindAll(
      org.jdbi.v3.core.statement.Query query, Reader reader, Filter filter) {
    return query
        .bind("everything", reader.everything())
        .bind("me", reader.username() == null ? "" : reader.username())
        .bindArray("scopes", String.class, reader.scopes().toArray(new String[0]))
        .bind("outcome", filter.outcome())
        .bind("principal", filter.principal())
        .bind("asset", filter.assetFqn())
        .bind("sourceId", filter.sourceId())
        .bind("since", filter.since() == null ? null : Timestamp.from(filter.since()))
        .bind("until", filter.until() == null ? null : Timestamp.from(filter.until()))
        .bind("pattern", filter.text() == null ? null : "%" + escapeLike(filter.text()) + "%");
  }

  /** The text as a literal inside an ILIKE pattern: its own % and _ match themselves. */
  static String escapeLike(String text) {
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static Entry map(ResultSet rs) throws SQLException {
    Timestamp at = rs.getTimestamp("occurred_at");
    long rowCount = rs.getLong("row_count");
    Long rows = rs.wasNull() ? null : rowCount;
    int duration = rs.getInt("duration_ms");
    Integer millis = rs.wasNull() ? null : duration;
    String source = rs.getString("data_source_id");
    Array assets = rs.getArray("asset_fqns");
    List<String> fqns =
        assets == null ? List.of() : new ArrayList<>(Arrays.asList((String[]) assets.getArray()));
    return new Entry(
        rs.getLong("id"),
        at == null ? null : at.toInstant(),
        rs.getString("principal_name"),
        rs.getString("run_by"),
        source == null ? null : UUID.fromString(source),
        rs.getString("source_name"),
        rs.getString("original_sql"),
        rs.getString("rewritten_sql"),
        rs.getString("outcome"),
        rs.getString("reject_reason"),
        rows,
        millis,
        List.copyOf(fqns),
        rs.getBoolean("served_from_cache"),
        rs.getBoolean("exported"));
  }
}
