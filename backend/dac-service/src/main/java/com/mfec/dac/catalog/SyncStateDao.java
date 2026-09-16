package com.mfec.dac.catalog;

import java.time.Instant;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

/**
 * Where a sync left off, and whether it finished (FR-1.5).
 *
 * <p>One row per source. The change-event cursor lives here because the poller
 * that backs up the webhook has to resume from where the last one stopped, and
 * the crawl timestamps because the nightly reconcile needs to know whether a
 * full crawl has happened at all.
 *
 * <p>The status is written at both ends of a run on purpose. A row left at
 * {@code RUNNING} with an old timestamp is the signal that a crawl died without
 * saying so, and that is worth surfacing: the cache is then stale in a way
 * nothing else reveals — every row in it still looks perfectly valid.
 */
public class SyncStateDao {

  /** What the last run of one source did. */
  public record SyncState(
      String source,
      Long lastEventTs,
      Instant lastFullCrawlAt,
      Instant lastReconcileAt,
      String status,
      String lastError,
      Instant updatedAt) {

    public boolean running() {
      return "RUNNING".equals(status);
    }
  }

  public static final String IDLE = "IDLE";
  public static final String RUNNING = "RUNNING";
  public static final String FAILED = "FAILED";

  private final Jdbi jdbi;

  public SyncStateDao(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  public Optional<SyncState> find(String source) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT source, last_event_ts, last_full_crawl_at, last_reconcile_at,
                           status, last_error, updated_at
                    FROM sync_state WHERE source = :source
                    """)
                .bind("source", source)
                .map(
                    (rs, ctx) ->
                        new SyncState(
                            rs.getString("source"),
                            rs.getObject("last_event_ts") == null
                                ? null
                                : rs.getLong("last_event_ts"),
                            instant(rs.getTimestamp("last_full_crawl_at")),
                            instant(rs.getTimestamp("last_reconcile_at")),
                            rs.getString("status"),
                            rs.getString("last_error"),
                            instant(rs.getTimestamp("updated_at"))))
                .findOne());
  }

  /** Marks a run as under way, clearing the error the previous one may have left. */
  public void started(String source) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO sync_state (source, status, last_error, updated_at)
                    VALUES (:source, 'RUNNING', NULL, now())
                    ON CONFLICT (source) DO UPDATE SET
                        status = 'RUNNING', last_error = NULL, updated_at = now()
                    """)
                .bind("source", source)
                .execute());
  }

  public void crawlSucceeded(String source, Instant at) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO sync_state (source, last_full_crawl_at, status, last_error,
                                            updated_at)
                    VALUES (:source, :at, 'IDLE', NULL, now())
                    ON CONFLICT (source) DO UPDATE SET
                        last_full_crawl_at = EXCLUDED.last_full_crawl_at,
                        status = 'IDLE', last_error = NULL, updated_at = now()
                    """)
                .bind("source", source)
                .bind("at", at)
                .execute());
  }

  public void reconciled(String source, Instant at) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO sync_state (source, last_reconcile_at, status, updated_at)
                    VALUES (:source, :at, 'IDLE', now())
                    ON CONFLICT (source) DO UPDATE SET
                        last_reconcile_at = EXCLUDED.last_reconcile_at,
                        status = 'IDLE', updated_at = now()
                    """)
                .bind("source", source)
                .bind("at", at)
                .execute());
  }

  /**
   * Records a failure without moving the crawl timestamps.
   *
   * <p>A failed crawl has not refreshed anything, so {@code last_full_crawl_at}
   * must keep pointing at the last one that did. Advancing it here would let the
   * reconcile job conclude the cache had just been checked.
   */
  public void failed(String source, String error) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO sync_state (source, status, last_error, updated_at)
                    VALUES (:source, 'FAILED', :error, now())
                    ON CONFLICT (source) DO UPDATE SET
                        status = 'FAILED', last_error = EXCLUDED.last_error, updated_at = now()
                    """)
                .bind("source", source)
                .bind("error", truncate(error))
                .execute());
  }

  /** Moves the change-event cursor (FR-1.5). */
  public void eventCursor(String source, long timestampMillis) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO sync_state (source, last_event_ts, updated_at)
                    VALUES (:source, :ts, now())
                    ON CONFLICT (source) DO UPDATE SET
                        last_event_ts = EXCLUDED.last_event_ts, updated_at = now()
                    """)
                .bind("source", source)
                .bind("ts", timestampMillis)
                .execute());
  }

  private static Instant instant(java.sql.Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static String truncate(String error) {
    if (error == null) {
      return null;
    }
    return error.length() <= 2000 ? error : error.substring(0, 2000);
  }
}
