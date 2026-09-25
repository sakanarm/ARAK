package com.mfec.dac.access;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;

/**
 * How often each table is asked for, and how those asks ended (M9 slice 2c).
 *
 * <p>Counted from {@code access_request} itself rather than a counter kept
 * beside it. Every request is already a row that is never deleted -- a
 * withdrawal and a refusal are statuses, not deletions -- so the history a
 * counter would keep is already there, and a counter is one more thing that can
 * drift from the rows it claims to count. The index on
 * {@code (asset_fqn, created_at)} is the one this query walks.
 *
 * <p>The two kinds of "no" are counted apart because they mean different
 * things to the person reading a dashboard. <em>Rejected</em> is an approver
 * saying the person should not have it; <em>declined</em> is the configurer,
 * after approval, saying it cannot be done as asked. A table with many of the
 * second is not over-asked, it is hard to configure.
 */
public class RequestStatistics {

  /** The widest window one call may count over. */
  public static final int MAX_DAYS = 3650;

  private final Jdbi jdbi;

  public RequestStatistics(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /**
   * One table's requests in the window.
   *
   * @param open still waiting on somebody: pending, approved, or being configured
   * @param rejected refused by an approver
   * @param declined approved, then refused by the configurer
   * @param withdrawn taken back by the requester, at any point
   * @param requesters distinct people who asked
   * @param medianHoursToClose median time from asking to a final answer (completed,
   *     rejected or declined); null when none of the window's requests has one
   */
  public record TableStats(
      String assetFqn,
      int asked,
      int open,
      int completed,
      int rejected,
      int declined,
      int withdrawn,
      int requesters,
      Double medianHoursToClose,
      Instant lastAskedAt) {}

  /**
   * Every table asked for since {@code since}, most asked first.
   *
   * @param assetFqn null for every table, or exactly one
   */
  public List<TableStats> perTable(Instant since, String assetFqn) {
    return jdbi.withHandle(
        handle -> {
          Query query =
              handle
                  .createQuery(
                      """
                      SELECT asset_fqn,
                             count(*) AS asked,
                             count(*) FILTER (WHERE status IN ('PENDING', 'APPROVED', 'IN_PROGRESS')) AS open,
                             count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                             count(*) FILTER (WHERE status = 'REJECTED' AND completed_by IS NULL) AS rejected,
                             count(*) FILTER (WHERE status = 'REJECTED' AND completed_by IS NOT NULL) AS declined,
                             count(*) FILTER (WHERE status = 'WITHDRAWN') AS withdrawn,
                             count(DISTINCT lower(requester_username)) AS requesters,
                             percentile_cont(0.5) WITHIN GROUP (
                               ORDER BY extract(epoch FROM coalesce(completed_at, decided_at) - created_at) / 3600.0
                             ) FILTER (WHERE status IN ('COMPLETED', 'REJECTED')) AS median_hours,
                             max(created_at) AS last_asked_at
                      FROM access_request
                      WHERE created_at >= :since
                        AND (CAST(:fqn AS text) IS NULL OR asset_fqn = :fqn)
                      GROUP BY asset_fqn
                      ORDER BY asked DESC, last_asked_at DESC, asset_fqn
                      """)
                  .bind("since", since)
                  .bind("fqn", assetFqn);
          return query.map((rs, ctx) -> map(rs)).list();
        });
  }

  private static TableStats map(ResultSet rs) throws SQLException {
    double median = rs.getDouble("median_hours");
    Double medianHours = rs.wasNull() ? null : Math.round(median * 10.0) / 10.0;
    Timestamp last = rs.getTimestamp("last_asked_at");
    return new TableStats(
        rs.getString("asset_fqn"),
        rs.getInt("asked"),
        rs.getInt("open"),
        rs.getInt("completed"),
        rs.getInt("rejected"),
        rs.getInt("declined"),
        rs.getInt("withdrawn"),
        rs.getInt("requesters"),
        medianHours,
        last == null ? null : last.toInstant());
  }
}
