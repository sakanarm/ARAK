package com.mfec.dac.enforcement;

import com.mfec.dac.audit.ClientAddress;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * What has been applied to which asset, and the trail of everybody who tried.
 *
 * <p>Two tables with deliberately different lifetimes. {@code enforcement_state}
 * is one row per asset and mode, overwritten on every apply: it answers "what
 * is on the source right now, as far as the platform knows". {@code
 * audit_enforcement} is append-only and answers "how did it get that way". A
 * failed apply changes the first only to say it failed, and is in the second
 * forever.
 *
 * <h2>Keyed by FQN, not by asset id</h2>
 *
 * <p>{@code asset} is versioned (SCD2), so the id of "the customer table" changes
 * every time the catalogue sees it change. The state row keeps whichever id
 * was current at the last write, for the foreign key, but is found by
 * {@code target_fqn}: an apply made last month is still the apply on this
 * table after a re-sync gave the table a new version.
 */
public final class EnforcementStateStore {

  public static final String SECURE_VIEW = "SECURE_VIEW";

  /** One asset's enforcement in one mode. */
  public record State(
      UUID id,
      UUID assetId,
      String targetFqn,
      UUID dataSourceId,
      String mode,
      String status,
      String appliedDdl,
      String rollbackDdl,
      String appliedFingerprint,
      Instant lastAppliedAt,
      String lastAppliedBy,
      String lastError,
      String secureSchema,
      String secureView,
      Instant updatedAt) {

    /** True when there is an object on the source that a rollback would remove. */
    public boolean isInstalled() {
      return ("APPLIED".equals(status) || "DRIFTED".equals(status))
          && secureSchema != null
          && secureView != null;
    }
  }

  /** One line of the trail. */
  public record AuditEntry(
      long id,
      Instant occurredAt,
      String actor,
      String targetFqn,
      UUID dataSourceId,
      String mode,
      String action,
      String outcome,
      UUID reviewId,
      String signature,
      Integer statements,
      Integer rowsInserted,
      Integer rowsDeleted,
      String detail) {}

  /** What to write to the trail; the id and time are the database's. */
  public record Audit(
      String actor,
      String targetFqn,
      UUID dataSourceId,
      String mode,
      String action,
      String outcome,
      UUID reviewId,
      String signature,
      Integer statements,
      Integer rowsInserted,
      Integer rowsDeleted,
      String detail,
      String clientIp) {}

  private static final String COLUMNS =
      """
      id, asset_id, target_fqn, data_source_id, mode, status, applied_ddl, rollback_ddl,
      applied_fingerprint, last_applied_at, last_applied_by, last_error,
      secure_object_schema, secure_object_name, updated_at
      """;

  private final Jdbi jdbi;

  public EnforcementStateStore(Jdbi jdbi) {
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
  }

  // ------------------------------------------------------------------ reads

  public Optional<State> find(String targetFqn, String mode) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT " + COLUMNS + " FROM enforcement_state"
                        + " WHERE target_fqn = :fqn AND mode = :mode"
                        + " ORDER BY updated_at DESC LIMIT 1")
                .bind("fqn", targetFqn)
                .bind("mode", mode)
                .map((rs, ctx) -> state(rs))
                .findOne());
  }

  public List<State> list(String mode) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT " + COLUMNS + " FROM enforcement_state WHERE mode = :mode"
                        + " ORDER BY target_fqn")
                .bind("mode", mode)
                .map((rs, ctx) -> state(rs))
                .list());
  }

  /** The most recent entries for one asset, newest first. */
  public List<AuditEntry> history(String targetFqn, int limit) {
    int bounded = Math.max(1, Math.min(limit, 200));
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT id, occurred_at, actor, target_fqn, data_source_id, mode, action,
                           outcome, review_id, signature, statements, rows_inserted,
                           rows_deleted, detail
                      FROM audit_enforcement
                     WHERE target_fqn = :fqn
                     ORDER BY occurred_at DESC, id DESC
                     LIMIT :limit
                    """)
                .bind("fqn", targetFqn)
                .bind("limit", bounded)
                .map(
                    (rs, ctx) ->
                        new AuditEntry(
                            rs.getLong("id"),
                            instant(rs.getTimestamp("occurred_at")),
                            rs.getString("actor"),
                            rs.getString("target_fqn"),
                            rs.getObject("data_source_id", UUID.class),
                            rs.getString("mode"),
                            rs.getString("action"),
                            rs.getString("outcome"),
                            rs.getObject("review_id", UUID.class),
                            rs.getString("signature"),
                            integer(rs, "statements"),
                            integer(rs, "rows_inserted"),
                            integer(rs, "rows_deleted"),
                            rs.getString("detail")))
                .list());
  }

  // ----------------------------------------------------------------- writes

  /** A successful apply: the object is on the source and this is how to take it off. */
  public State recordApplied(
      UUID assetId,
      String targetFqn,
      UUID dataSourceId,
      String mode,
      String appliedDdl,
      String rollbackDdl,
      String fingerprint,
      String actor,
      String secureSchema,
      String secureView) {
    return jdbi.inTransaction(
        handle -> {
          upsert(handle, assetId, targetFqn, dataSourceId, mode);
          handle
              .createUpdate(
                  """
                  UPDATE enforcement_state
                     SET status = 'APPLIED', applied_ddl = :applied, rollback_ddl = :rollback,
                         applied_fingerprint = :fingerprint, last_applied_at = now(),
                         last_applied_by = :actor, last_error = NULL, drift_detail = NULL,
                         secure_object_schema = :schema, secure_object_name = :view,
                         updated_at = now()
                   WHERE target_fqn = :fqn AND mode = :mode
                  """)
              .bind("applied", appliedDdl)
              .bind("rollback", rollbackDdl)
              .bind("fingerprint", fingerprint)
              .bind("actor", actor)
              .bind("schema", secureSchema)
              .bind("view", secureView)
              .bind("fqn", targetFqn)
              .bind("mode", mode)
              .execute();
          return read(handle, targetFqn, mode);
        });
  }

  /**
   * An apply or rollback that did not happen.
   *
   * <p>The status becomes {@code FAILED} only when nothing was installed
   * before. An asset whose last good apply is still on the source stays
   * {@code APPLIED} with the error beside it: the transaction rolled back, so
   * the source still holds exactly what it held, and saying otherwise would
   * send somebody to fix an object that is fine.
   */
  public State recordFailure(
      UUID assetId, String targetFqn, UUID dataSourceId, String mode, String error) {
    return jdbi.inTransaction(
        handle -> {
          upsert(handle, assetId, targetFqn, dataSourceId, mode);
          handle
              .createUpdate(
                  """
                  UPDATE enforcement_state
                     SET status = CASE WHEN status IN ('APPLIED', 'DRIFTED') THEN status
                                       ELSE 'FAILED' END,
                         last_error = :error, updated_at = now()
                   WHERE target_fqn = :fqn AND mode = :mode
                  """)
              .bind("error", error)
              .bind("fqn", targetFqn)
              .bind("mode", mode)
              .execute();
          return read(handle, targetFqn, mode);
        });
  }

  /**
   * The object is gone from the source. The scripts are kept: what was applied
   * last is still worth reading after it has been taken off.
   */
  public State recordRolledBack(String targetFqn, String mode, String actor) {
    return jdbi.inTransaction(
        handle -> {
          handle
              .createUpdate(
                  """
                  UPDATE enforcement_state
                     SET status = 'NOT_ENFORCED', last_error = NULL,
                         secure_object_schema = NULL, secure_object_name = NULL,
                         last_applied_by = :actor, updated_at = now()
                   WHERE target_fqn = :fqn AND mode = :mode
                  """)
              .bind("actor", actor)
              .bind("fqn", targetFqn)
              .bind("mode", mode)
              .execute();
          return read(handle, targetFqn, mode);
        });
  }

  public void audit(Audit entry) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO audit_enforcement
                        (actor, target_fqn, data_source_id, mode, action, outcome, review_id,
                         signature, statements, rows_inserted, rows_deleted, detail, client_ip)
                    VALUES (:actor, :fqn, :sourceId, :mode, :action, :outcome, :reviewId,
                            :signature, :statements, :inserted, :deleted, :detail,
                            CAST(:ip AS inet))
                    """)
                .bind("actor", entry.actor())
                .bind("fqn", entry.targetFqn())
                .bind("sourceId", entry.dataSourceId())
                .bind("mode", entry.mode())
                .bind("action", entry.action())
                .bind("outcome", entry.outcome())
                .bind("reviewId", entry.reviewId())
                .bind("signature", entry.signature())
                .bind("statements", entry.statements())
                .bind("inserted", entry.rowsInserted())
                .bind("deleted", entry.rowsDeleted())
                .bind("detail", entry.detail())
                .bind("ip", ClientAddress.normalise(entry.clientIp()))
                .execute());
  }

  // ---------------------------------------------------------------- helpers

  /**
   * Makes sure there is exactly one row for this asset and mode, pointing at
   * the asset's current version.
   */
  private static void upsert(
      Handle handle, UUID assetId, String targetFqn, UUID dataSourceId, String mode) {
    int moved =
        handle
            .createUpdate(
                """
                UPDATE enforcement_state
                   SET asset_id = :assetId, data_source_id = :sourceId
                 WHERE target_fqn = :fqn AND mode = :mode
                """)
            .bind("assetId", assetId)
            .bind("sourceId", dataSourceId)
            .bind("fqn", targetFqn)
            .bind("mode", mode)
            .execute();
    if (moved == 0) {
      handle
          .createUpdate(
              """
              INSERT INTO enforcement_state (asset_id, target_fqn, data_source_id, mode)
              VALUES (:assetId, :fqn, :sourceId, :mode)
              """)
          .bind("assetId", assetId)
          .bind("sourceId", dataSourceId)
          .bind("fqn", targetFqn)
          .bind("mode", mode)
          .execute();
    }
  }

  private static State read(Handle handle, String targetFqn, String mode) {
    return handle
        .createQuery(
            "SELECT " + COLUMNS + " FROM enforcement_state"
                + " WHERE target_fqn = :fqn AND mode = :mode")
        .bind("fqn", targetFqn)
        .bind("mode", mode)
        .map((rs, ctx) -> state(rs))
        .findOne()
        .orElse(null);
  }

  private static State state(ResultSet rs) throws SQLException {
    return new State(
        rs.getObject("id", UUID.class),
        rs.getObject("asset_id", UUID.class),
        rs.getString("target_fqn"),
        rs.getObject("data_source_id", UUID.class),
        rs.getString("mode"),
        rs.getString("status"),
        rs.getString("applied_ddl"),
        rs.getString("rollback_ddl"),
        rs.getString("applied_fingerprint"),
        instant(rs.getTimestamp("last_applied_at")),
        rs.getString("last_applied_by"),
        rs.getString("last_error"),
        rs.getString("secure_object_schema"),
        rs.getString("secure_object_name"),
        instant(rs.getTimestamp("updated_at")));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static Integer integer(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }
}
