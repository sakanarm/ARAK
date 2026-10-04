package com.mfec.dac.enforcement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;

/**
 * The roles ARAK has put on PostgreSQL sources for subscription policies, and
 * the account it puts them there with (V47).
 *
 * <p>{@code native_role} is one row per policy and source, overwritten by
 * every apply, sweep and check: it answers "what did ARAK leave on the source
 * last time, and is it still there". How it got that way is in
 * {@code audit_enforcement}, under the role's name.
 *
 * <p>{@code native_credential} holds a reference, never a secret: a sealed
 * {@code fernet:} value or a pointer. It is read only to connect, and nothing
 * here hands it back to a caller -- {@link #credential} says whether one is
 * configured and by which scheme, and that is all.
 */
public final class NativeRoleStore {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

  /** One policy's role on one source, as ARAK last left it. */
  public record Role(
      UUID id,
      UUID policyId,
      UUID dataSourceId,
      String roleName,
      String databaseName,
      String accessLevel,
      String status,
      String appliedScript,
      String appliedFingerprint,
      List<String> members,
      List<String> tables,
      Instant lastAppliedAt,
      String lastAppliedBy,
      Instant lastCheckedAt,
      String lastError,
      String detail,
      Instant updatedAt) {

    /** True when ARAK has put something on the source that is still its to take off. */
    public boolean isInstalled() {
      return appliedFingerprint != null && !"ROLLED_BACK".equals(status);
    }
  }

  /** Whether a source has an account to push with. The reference itself never leaves. */
  public record CredentialInfo(
      boolean configured, String scheme, Instant updatedAt, String updatedBy) {

    static final CredentialInfo NONE = new CredentialInfo(false, null, null, null);
  }

  private final Jdbi jdbi;

  public NativeRoleStore(Jdbi jdbi) {
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
  }

  // ------------------------------------------------------------------ roles

  public Optional<Role> find(UUID policyId, UUID sourceId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT * FROM native_role WHERE policy_id = :p AND data_source_id = :s")
                .bind("p", policyId)
                .bind("s", sourceId)
                .map((rs, ctx) -> role(rs))
                .findOne());
  }

  public List<Role> listForPolicy(UUID policyId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT * FROM native_role WHERE policy_id = :p ORDER BY role_name")
                .bind("p", policyId)
                .map((rs, ctx) -> role(rs))
                .list());
  }

  /** Every role still on a source, for the sweep. */
  public List<Role> listInstalled() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT * FROM native_role
                     WHERE status <> 'ROLLED_BACK' AND applied_fingerprint IS NOT NULL
                     ORDER BY role_name
                    """)
                .map((rs, ctx) -> role(rs))
                .list());
  }

  /** What an apply or a sweep left on the source. */
  public Role recordApplied(
      UUID policyId,
      UUID sourceId,
      String roleName,
      String database,
      String level,
      String status,
      String script,
      String fingerprint,
      Collection<String> members,
      Collection<String> tables,
      String actor,
      String detail) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    INSERT INTO native_role
                        (policy_id, data_source_id, role_name, database_name, access_level,
                         status, applied_script, applied_fingerprint, members, tables,
                         last_applied_at, last_applied_by, last_checked_at, last_error, detail)
                    VALUES (:p, :s, :role, :db, :level, :status, :script, :fp,
                            CAST(:members AS jsonb), CAST(:tables AS jsonb),
                            now(), :actor, now(), NULL, :detail)
                    ON CONFLICT (policy_id, data_source_id) DO UPDATE SET
                        role_name = EXCLUDED.role_name,
                        database_name = EXCLUDED.database_name,
                        access_level = EXCLUDED.access_level,
                        status = EXCLUDED.status,
                        applied_script = EXCLUDED.applied_script,
                        applied_fingerprint = EXCLUDED.applied_fingerprint,
                        members = EXCLUDED.members,
                        tables = EXCLUDED.tables,
                        last_applied_at = now(),
                        last_applied_by = EXCLUDED.last_applied_by,
                        last_checked_at = now(),
                        last_error = NULL,
                        detail = EXCLUDED.detail,
                        updated_at = now()
                    RETURNING *
                    """)
                .bind("p", policyId)
                .bind("s", sourceId)
                .bind("role", roleName)
                .bind("db", database)
                .bind("level", level)
                .bind("status", status)
                .bind("script", script)
                .bind("fp", fingerprint)
                .bind("members", json(members))
                .bind("tables", json(tables))
                .bind("actor", actor)
                .bind("detail", detail)
                .map((rs, ctx) -> role(rs))
                .one());
  }

  /**
   * An apply that did not happen. The transaction on the source was rolled
   * back, so whatever was applied before is still there: the fingerprint and
   * members are kept, and only the status and the error change.
   */
  public void recordFailure(
      UUID policyId, UUID sourceId, String roleName, String database, String level, String error) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO native_role
                        (policy_id, data_source_id, role_name, database_name, access_level,
                         status, last_error)
                    VALUES (:p, :s, :role, :db, :level, 'FAILED', :error)
                    ON CONFLICT (policy_id, data_source_id) DO UPDATE SET
                        status = 'FAILED', last_error = EXCLUDED.last_error, updated_at = now()
                    """)
                .bind("p", policyId)
                .bind("s", sourceId)
                .bind("role", roleName)
                .bind("db", database)
                .bind("level", level)
                .bind("error", clip(error))
                .execute());
  }

  /** What a check or a sweep found, without anything having been applied. */
  public void recordStatus(UUID id, String status, String error, String detail) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    UPDATE native_role
                       SET status = :status, last_checked_at = now(), last_error = :error,
                           detail = coalesce(:detail, detail), updated_at = now()
                     WHERE id = :id
                    """)
                .bind("id", id)
                .bind("status", status)
                .bind("error", clip(error))
                .bind("detail", detail)
                .execute());
  }

  public void recordRolledBack(UUID id, String actor, String detail) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    UPDATE native_role
                       SET status = 'ROLLED_BACK', applied_fingerprint = NULL,
                           members = '[]'::jsonb, last_applied_at = now(),
                           last_applied_by = :actor, last_checked_at = now(),
                           last_error = NULL, detail = :detail, updated_at = now()
                     WHERE id = :id
                    """)
                .bind("id", id)
                .bind("actor", actor)
                .bind("detail", detail)
                .execute());
  }

  // ------------------------------------------------------------- credential

  /** The reference to connect with, for the service only. Never served. */
  public Optional<String> credentialRef(UUID sourceId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT credential_ref FROM native_credential WHERE data_source_id = :s")
                .bind("s", sourceId)
                .mapTo(String.class)
                .findOne());
  }

  public CredentialInfo credential(UUID sourceId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT credential_ref, updated_at, updated_by
                      FROM native_credential WHERE data_source_id = :s
                    """)
                .bind("s", sourceId)
                .map(
                    (rs, ctx) ->
                        new CredentialInfo(
                            true,
                            scheme(rs.getString("credential_ref")),
                            instant(rs, "updated_at"),
                            rs.getString("updated_by")))
                .findOne()
                .orElse(CredentialInfo.NONE));
  }

  public void setCredential(UUID sourceId, String ref, String actor) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO native_credential (data_source_id, credential_ref, updated_by)
                    VALUES (:s, :ref, :actor)
                    ON CONFLICT (data_source_id) DO UPDATE SET
                        credential_ref = EXCLUDED.credential_ref,
                        updated_by = EXCLUDED.updated_by,
                        updated_at = now()
                    """)
                .bind("s", sourceId)
                .bind("ref", ref)
                .bind("actor", actor)
                .execute());
  }

  public boolean deleteCredential(UUID sourceId) {
    return jdbi.withHandle(
            handle ->
                handle
                    .createUpdate("DELETE FROM native_credential WHERE data_source_id = :s")
                    .bind("s", sourceId)
                    .execute())
        > 0;
  }

  /** The scheme of a reference, e.g. {@code fernet} or {@code vault}; never the rest of it. */
  static String scheme(String ref) {
    if (ref == null) {
      return null;
    }
    String lower = ref.trim().toLowerCase(Locale.ROOT);
    int colon = lower.indexOf(':');
    return colon < 1 ? "unknown" : lower.substring(0, colon);
  }

  // ---------------------------------------------------------------- helpers

  private static Role role(ResultSet rs) throws SQLException {
    return new Role(
        rs.getObject("id", UUID.class),
        rs.getObject("policy_id", UUID.class),
        rs.getObject("data_source_id", UUID.class),
        rs.getString("role_name"),
        rs.getString("database_name"),
        rs.getString("access_level"),
        rs.getString("status"),
        rs.getString("applied_script"),
        rs.getString("applied_fingerprint"),
        strings(rs.getString("members")),
        strings(rs.getString("tables")),
        instant(rs, "last_applied_at"),
        rs.getString("last_applied_by"),
        instant(rs, "last_checked_at"),
        rs.getString("last_error"),
        rs.getString("detail"),
        instant(rs, "updated_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp at = rs.getTimestamp(column);
    return at == null ? null : at.toInstant();
  }

  private static List<String> strings(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return List.copyOf(JSON.readValue(json, STRINGS));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("native_role holds a list that is not JSON", e);
    }
  }

  private static String json(Collection<String> values) {
    try {
      return JSON.writeValueAsString(values == null ? List.of() : List.copyOf(values));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String clip(String text) {
    if (text == null) {
      return null;
    }
    return text.length() <= 2000 ? text : text.substring(0, 2000) + "…";
  }
}
