package com.mfec.dac.dashboard;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jdbi.v3.core.Jdbi;

/**
 * The numbers behind the access-control dashboard (M10, FR-8.5).
 *
 * <p>Every question here is one a reviewer asks of the whole estate -- which
 * sensitive tables nothing protects, who is about to lose access, which grants
 * nobody uses, what the proxy refused -- so it reads every row and is only for
 * the roles that oversee everything. Nothing selected here is a client
 * address, and no statement text leaves this class: the dashboard counts
 * queries, it does not quote them.
 *
 * <p>"Sensitive" is a classification or tag named by the caller, matched by
 * segment ({@code PII} covers {@code PII.Sensitive}, not {@code PIIX}), on the
 * table or any of its columns. A label OpenMetadata only suggested does not
 * count, for the reason it is not enforced either (FR-1.3a): nobody confirmed
 * it.
 */
public class DashboardQuery {

  private final Jdbi jdbi;

  public DashboardQuery(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /**
   * One sensitive table and what stands in front of it.
   *
   * @param sensitiveColumns columns carrying the label; zero when only the table does
   * @param dataPolicies active data policies (masks, row filters) bound to it or its columns
   * @param subscriptionPolicies active ALLOW subscription policies that let people in
   * @param activeGrants grants in force on it
   * @param readers distinct people whose queries on it ran in the window
   * @param refused queries on it refused in the window
   */
  public record SensitiveTable(
      String fqn,
      int sensitiveColumns,
      int dataPolicies,
      int subscriptionPolicies,
      int activeGrants,
      int readers,
      int refused,
      List<String> owners) {

    /** A data policy shapes what comes back; without one a reader sees it raw. */
    public boolean protectedByPolicy() {
      return dataPolicies > 0;
    }

    /** Somebody can get in: a policy allows it or a grant names them. */
    public boolean reachable() {
      return subscriptionPolicies > 0 || activeGrants > 0;
    }
  }

  private static final String SENSITIVE =
      """
      WITH tables AS (
        SELECT a.fqn FROM asset a
         WHERE a.is_current AND a.asset_type IN ('TABLE', 'VIEW')
      ),
      sensitive AS (
        SELECT t.fqn,
               count(DISTINCT f.target_fqn) FILTER (WHERE f.target_fqn <> t.fqn) AS columns
          FROM tables t
          JOIN asset_facet f
            ON f.target_fqn = t.fqn OR starts_with(f.target_fqn, t.fqn || '.')
         WHERE f.facet_type IN ('classifications', 'tags')
           AND (f.facet_fqn = CAST(:label AS text) OR starts_with(f.facet_fqn, CAST(:label AS text) || '.'))
           AND f.om_state IS DISTINCT FROM 'Suggested'
         GROUP BY t.fqn
      )
      """;

  // Joined after an AND whose trailing space a text block would drop.
  private static final String LIVE_POLICY =
      " "
          + """
      p.lifecycle_state = 'ACTIVE'
      AND (p.valid_from IS NULL OR p.valid_from <= now())
      AND (p.valid_until IS NULL OR p.valid_until > now())
      """;

  // Joined after an AND whose trailing space a text block would drop.
  private static final String LIVE_GRANT =
      " "
          + """
      g.revoked_at IS NULL
      AND g.valid_from <= now()
      AND (g.valid_until IS NULL OR g.valid_until > now())
      """;

  /** Every table carrying {@code label}, the unprotected ones first. */
  public List<SensitiveTable> sensitiveTables(String label, Instant since) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    SENSITIVE
                        + """
                        SELECT * FROM (
                        SELECT s.fqn, s.columns,
                               (SELECT count(DISTINCT p.id) FROM policy_binding b JOIN policy p ON p.id = b.policy_id
                                 WHERE (b.target_fqn = s.fqn OR starts_with(b.target_fqn, s.fqn || '.'))
                                   AND p.policy_type = 'DATA' AND """
                        + LIVE_POLICY
                        + """
                                ) AS data_policies,
                               (SELECT count(DISTINCT p.id) FROM policy_binding b JOIN policy p ON p.id = b.policy_id
                                 WHERE b.target_fqn = s.fqn
                                   AND p.policy_type = 'SUBSCRIPTION' AND p.effect = 'ALLOW' AND """
                        + LIVE_POLICY
                        + """
                                ) AS subscription_policies,
                               (SELECT count(*) FROM access_grant g
                                 WHERE g.asset_fqn = s.fqn AND """
                        + LIVE_GRANT
                        + """
                                ) AS active_grants,
                               (SELECT count(DISTINCT lower(q.principal_name)) FROM audit_query q
                                 WHERE q.outcome = 'EXECUTED' AND q.occurred_at >= :since
                                   AND q.asset_fqns @> ARRAY[s.fqn]) AS readers,
                               (SELECT count(*) FROM audit_query q
                                 WHERE q.outcome = 'REJECTED' AND q.occurred_at >= :since
                                   AND q.asset_fqns @> ARRAY[s.fqn]) AS refused,
                               ARRAY(SELECT DISTINCT o.owner_name FROM asset_owner o
                                      WHERE o.target_fqn = s.fqn ORDER BY o.owner_name) AS owners
                          FROM sensitive s
                        ) x
                        ORDER BY data_policies > 0, readers DESC,
                                 subscription_policies + active_grants > 0 DESC, fqn
                        """)
                .bind("label", label)
                .bind("since", Timestamp.from(since))
                .map(
                    (rs, ctx) ->
                        new SensitiveTable(
                            rs.getString("fqn"),
                            rs.getInt("columns"),
                            rs.getInt("data_policies"),
                            rs.getInt("subscription_policies"),
                            rs.getInt("active_grants"),
                            rs.getInt("readers"),
                            rs.getInt("refused"),
                            strings(rs, "owners")))
                .list());
  }

  /** How many tables and views there are at all, for the coverage ring's denominator. */
  public int tableCount() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT count(*) FROM asset WHERE is_current AND asset_type IN ('TABLE', 'VIEW')")
                .mapTo(Integer.class)
                .one());
  }

  /** A grant in force that ends soon. */
  public record EndingGrant(
      String principal, String principalType, String assetFqn, Instant validUntil, String source) {}

  /**
   * The grants in force, in the shapes a reviewer asks about.
   *
   * @param endingSoon ending within the horizon, soonest first, at most ten
   * @param endingCount how many end within the horizon
   * @param openEnded with no end date at all
   * @param onSensitive on a table carrying the label
   * @param unused held by a person for longer than the idle window, with no query
   *     through the proxy on that table inside it
   */
  public record GrantPicture(
      int active,
      int endingCount,
      List<EndingGrant> endingSoon,
      int openEnded,
      int onSensitive,
      int unused) {}

  public GrantPicture grants(String label, Instant endingBy, Instant idleSince) {
    return jdbi.withHandle(
        handle -> {
          int[] counts =
              handle
                  .createQuery(
                      SENSITIVE
                          + """
                          SELECT count(*) AS active,
                                 count(*) FILTER (WHERE g.valid_until IS NOT NULL AND g.valid_until <= :endingBy) AS ending,
                                 count(*) FILTER (WHERE g.valid_until IS NULL) AS open_ended,
                                 count(*) FILTER (WHERE g.asset_fqn IN (SELECT fqn FROM sensitive)) AS on_sensitive,
                                 count(*) FILTER (
                                   WHERE p.principal_type = 'USER' AND g.granted_at < :idleSince
                                     AND NOT EXISTS (
                                       SELECT 1 FROM audit_query q
                                        WHERE q.outcome = 'EXECUTED' AND q.occurred_at >= :idleSince
                                          AND lower(q.principal_name) = lower(p.username)
                                          AND q.asset_fqns @> ARRAY[g.asset_fqn])) AS unused
                            FROM access_grant g
                            JOIN principal p ON p.id = g.principal_id
                           WHERE """
                          + LIVE_GRANT)
                  .bind("label", label)
                  .bind("endingBy", Timestamp.from(endingBy))
                  .bind("idleSince", Timestamp.from(idleSince))
                  .map(
                      (rs, ctx) ->
                          new int[] {
                            rs.getInt("active"),
                            rs.getInt("ending"),
                            rs.getInt("open_ended"),
                            rs.getInt("on_sensitive"),
                            rs.getInt("unused")
                          })
                  .one();
          List<EndingGrant> ending =
              handle
                  .createQuery(
                      """
                      SELECT p.username, p.principal_type, g.asset_fqn, g.valid_until, g.source
                        FROM access_grant g
                        JOIN principal p ON p.id = g.principal_id
                       WHERE g.valid_until IS NOT NULL AND g.valid_until <= :endingBy AND """
                          + LIVE_GRANT
                          + " ORDER BY g.valid_until, p.username LIMIT 10")
                  .bind("endingBy", Timestamp.from(endingBy))
                  .map(
                      (rs, ctx) ->
                          new EndingGrant(
                              rs.getString("username"),
                              rs.getString("principal_type"),
                              rs.getString("asset_fqn"),
                              rs.getTimestamp("valid_until").toInstant(),
                              rs.getString("source")))
                  .list();
          return new GrantPicture(counts[0], counts[1], ending, counts[2], counts[3], counts[4]);
        });
  }

  /** One table or one person, by how often the proxy was asked on their behalf. */
  public record Busiest(String name, int queries, int refused, int people, Instant lastAt) {}

  /** The tables asked of most in the window. {@code people} is distinct principals. */
  public List<Busiest> busiestTables(Instant since, int limit) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT f AS name, count(*) AS queries,
                           count(*) FILTER (WHERE q.outcome = 'REJECTED') AS refused,
                           count(DISTINCT lower(q.principal_name)) AS people,
                           max(q.occurred_at) AS last_at
                      FROM audit_query q, unnest(q.asset_fqns) f
                     WHERE q.occurred_at >= :since
                     GROUP BY f
                     ORDER BY queries DESC, f
                     LIMIT :limit
                    """)
                .bind("since", Timestamp.from(since))
                .bind("limit", limit)
                .map((rs, ctx) -> busiest(rs))
                .list());
  }

  /**
   * The people who asked most in the window. {@code people} is how many
   * distinct tables they touched.
   */
  public List<Busiest> busiestPeople(Instant since, int limit) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT q.principal_name AS name, count(*) AS queries,
                           count(*) FILTER (WHERE q.outcome = 'REJECTED') AS refused,
                           (SELECT count(DISTINCT f) FROM audit_query q2, unnest(q2.asset_fqns) f
                             WHERE lower(q2.principal_name) = lower(q.principal_name)
                               AND q2.occurred_at >= :since) AS people,
                           max(q.occurred_at) AS last_at
                      FROM audit_query q
                     WHERE q.occurred_at >= :since
                     GROUP BY q.principal_name
                     ORDER BY queries DESC, q.principal_name
                     LIMIT :limit
                    """)
                .bind("since", Timestamp.from(since))
                .bind("limit", limit)
                .map((rs, ctx) -> busiest(rs))
                .list());
  }

  /**
   * The policy engine's decisions in the window.
   *
   * @param p50Ms null when no decision recorded how long it took
   */
  public record Decisions(int total, int denied, int fromCache, Integer p50Ms, Integer p95Ms) {}

  public Decisions decisions(Instant since) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT count(*) AS total,
                           count(*) FILTER (WHERE NOT allowed) AS denied,
                           count(*) FILTER (WHERE from_cache) AS cached,
                           percentile_cont(0.5) WITHIN GROUP (ORDER BY evaluation_ms) AS p50,
                           percentile_cont(0.95) WITHIN GROUP (ORDER BY evaluation_ms) AS p95
                      FROM audit_decision
                     WHERE occurred_at >= :since
                    """)
                .bind("since", Timestamp.from(since))
                .map(
                    (rs, ctx) ->
                        new Decisions(
                            rs.getInt("total"),
                            rs.getInt("denied"),
                            rs.getInt("cached"),
                            roundedOrNull(rs, "p50"),
                            roundedOrNull(rs, "p95")))
                .one());
  }

  /**
   * Access requests: what is waiting now, and how the window's were answered.
   *
   * @param oldestOpenAt when the longest-waiting open request was made; null when none is open
   * @param medianHoursToClose from asking to a final answer, over the window's closed requests
   */
  public record Requests(
      int pending,
      int approved,
      int inProgress,
      Instant oldestOpenAt,
      int asked,
      int completed,
      int rejected,
      int withdrawn,
      Double medianHoursToClose) {}

  public Requests requests(Instant since) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                           count(*) FILTER (WHERE status = 'APPROVED') AS approved,
                           count(*) FILTER (WHERE status = 'IN_PROGRESS') AS in_progress,
                           min(created_at) FILTER (WHERE status IN ('PENDING', 'APPROVED', 'IN_PROGRESS')) AS oldest_open,
                           count(*) FILTER (WHERE created_at >= :since) AS asked,
                           count(*) FILTER (WHERE created_at >= :since AND status = 'COMPLETED') AS completed,
                           count(*) FILTER (WHERE created_at >= :since AND status = 'REJECTED') AS rejected,
                           count(*) FILTER (WHERE created_at >= :since AND status = 'WITHDRAWN') AS withdrawn,
                           percentile_cont(0.5) WITHIN GROUP (
                             ORDER BY extract(epoch FROM coalesce(completed_at, decided_at) - created_at) / 3600.0
                           ) FILTER (WHERE created_at >= :since AND status IN ('COMPLETED', 'REJECTED')) AS median_hours
                      FROM access_request
                    """)
                .bind("since", Timestamp.from(since))
                .map(
                    (rs, ctx) -> {
                      Timestamp oldest = rs.getTimestamp("oldest_open");
                      double median = rs.getDouble("median_hours");
                      Double hours = rs.wasNull() ? null : Math.round(median * 10.0) / 10.0;
                      return new Requests(
                          rs.getInt("pending"),
                          rs.getInt("approved"),
                          rs.getInt("in_progress"),
                          oldest == null ? null : oldest.toInstant(),
                          rs.getInt("asked"),
                          rs.getInt("completed"),
                          rs.getInt("rejected"),
                          rs.getInt("withdrawn"),
                          hours);
                    })
                .one());
  }

  /**
   * The platform's own state.
   *
   * @param enforcement secure-view and native objects by status
   * @param policies "SUBSCRIPTION ACTIVE" and the like, to a count
   * @param syncStatus the last OpenMetadata crawl's state; null before the first
   */
  public record Health(
      int sources,
      int sourcesEnabled,
      Map<String, Integer> enforcement,
      Map<String, Integer> policies,
      String syncStatus,
      Instant lastCrawlAt,
      boolean syncFailed) {}

  public Health health() {
    return jdbi.withHandle(
        handle -> {
          int[] sources =
              handle
                  .createQuery(
                      "SELECT count(*) AS total, count(*) FILTER (WHERE enabled) AS enabled FROM data_source")
                  .map((rs, ctx) -> new int[] {rs.getInt("total"), rs.getInt("enabled")})
                  .one();
          Map<String, Integer> enforcement = new TreeMap<>();
          handle
              .createQuery("SELECT status, count(*) AS n FROM enforcement_state GROUP BY status")
              .map((rs, ctx) -> Map.entry(rs.getString("status"), rs.getInt("n")))
              .list()
              .forEach(e -> enforcement.put(e.getKey(), e.getValue()));
          Map<String, Integer> policies = new TreeMap<>();
          handle
              .createQuery(
                  "SELECT policy_type || ' ' || lifecycle_state AS k, count(*) AS n FROM policy GROUP BY 1")
              .map((rs, ctx) -> Map.entry(rs.getString("k"), rs.getInt("n")))
              .list()
              .forEach(e -> policies.put(e.getKey(), e.getValue()));
          // The error text is left behind: it can carry the catalogue's address,
          // and "it failed" is what this card needs to say.
          var sync =
              handle
                  .createQuery(
                      """
                      SELECT status, last_full_crawl_at, last_error IS NOT NULL AS failed
                        FROM sync_state WHERE source = 'openmetadata'
                      """)
                  .map(
                      (rs, ctx) -> {
                        Timestamp at = rs.getTimestamp("last_full_crawl_at");
                        return new Object[] {
                          rs.getString("status"), at == null ? null : at.toInstant(), rs.getBoolean("failed")
                        };
                      })
                  .findOne()
                  .orElse(new Object[] {null, null, false});
          return new Health(
              sources[0],
              sources[1],
              enforcement,
              policies,
              (String) sync[0],
              (Instant) sync[1],
              (Boolean) sync[2]);
        });
  }

  private static Busiest busiest(ResultSet rs) throws SQLException {
    Timestamp last = rs.getTimestamp("last_at");
    return new Busiest(
        rs.getString("name"),
        rs.getInt("queries"),
        rs.getInt("refused"),
        rs.getInt("people"),
        last == null ? null : last.toInstant());
  }

  private static Integer roundedOrNull(ResultSet rs, String column) throws SQLException {
    double value = rs.getDouble(column);
    return rs.wasNull() ? null : (int) Math.round(value);
  }

  private static List<String> strings(ResultSet rs, String column) throws SQLException {
    java.sql.Array array = rs.getArray(column);
    return array == null ? List.of() : List.of((String[]) array.getArray());
  }
}
