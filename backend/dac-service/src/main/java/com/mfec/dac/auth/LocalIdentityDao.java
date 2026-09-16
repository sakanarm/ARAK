package com.mfec.dac.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;

/**
 * Reads and updates local credentials (FR-2.2).
 *
 * <p>Hand-written SQL through JDBI rather than an ORM, which is the same choice
 * the rest of the platform makes: most of the queries here and nearly all of
 * the ones in the compilers are shapes an entity mapper would fight.
 */
public class LocalIdentityDao {

  /** What a login attempt needs to know about an account, in one round trip. */
  public record LocalAccount(
      UUID id,
      String username,
      String email,
      String displayName,
      boolean enabled,
      String passwordHash,
      boolean mustChange,
      int failedAttempts,
      Instant lockedUntil) {

    public boolean hasCredential() {
      return passwordHash != null && !passwordHash.isBlank();
    }

    public boolean isLocked(Instant now) {
      return lockedUntil != null && lockedUntil.isAfter(now);
    }
  }

  private final Jdbi jdbi;

  public LocalIdentityDao(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  public Optional<LocalAccount> findLocalAccount(String username) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT p.id, p.username, p.email, p.display_name, p.enabled,
                           c.password_hash, c.must_change, c.failed_attempts, c.locked_until
                    FROM principal p
                    LEFT JOIN local_credential c ON c.principal_id = p.id
                    WHERE p.source = 'local'
                      AND p.principal_type <> 'GROUP'
                      AND lower(p.username) = lower(:username)
                    """)
                .bind("username", username)
                .map(
                    (rs, ctx) ->
                        new LocalAccount(
                            rs.getObject("id", UUID.class),
                            rs.getString("username"),
                            rs.getString("email"),
                            rs.getString("display_name"),
                            rs.getBoolean("enabled"),
                            rs.getString("password_hash"),
                            rs.getBoolean("must_change"),
                            rs.getInt("failed_attempts"),
                            rs.getTimestamp("locked_until") == null
                                ? null
                                : rs.getTimestamp("locked_until").toInstant()))
                .findOne());
  }

  /**
   * Rebuilds a user from the database rather than from token claims.
   *
   * <p>Used by {@code /auth/me} so that a role granted or withdrawn during a
   * session is visible on the next page load, even though the token itself
   * still carries the old claims until it expires.
   */
  public Optional<AuthenticatedUser> loadUser(UUID principalId) {
    return jdbi.withHandle(
        handle -> {
          Optional<AuthenticatedUser> base =
              handle
                  .createQuery(
                      """
                      SELECT id, username, email, display_name, source
                      FROM principal
                      WHERE id = :id AND enabled = true
                      """)
                  .bind("id", principalId)
                  .map(
                      (rs, ctx) ->
                          new AuthenticatedUser(
                              rs.getObject("id", UUID.class),
                              rs.getString("username"),
                              rs.getString("email"),
                              rs.getString("display_name"),
                              rs.getString("source"),
                              Set.of(),
                              List.of()))
                  .findOne();
          if (base.isEmpty()) {
            return Optional.empty();
          }
          AuthenticatedUser user = base.get();
          Set<String> roles = new LinkedHashSet<>();
          List<String> scopes = new ArrayList<>();
          handle
              .createQuery(
                  """
                  SELECT app_role, scope_fqn
                  FROM app_role_assignment
                  WHERE principal_id = :id
                  ORDER BY app_role
                  """)
              .bind("id", principalId)
              .map(
                  (rs, ctx) -> {
                    roles.add(rs.getString("app_role"));
                    String scope = rs.getString("scope_fqn");
                    if (scope != null && !scopes.contains(scope)) {
                      scopes.add(scope);
                    }
                    return 1;
                  })
              .list();
          return Optional.of(
              new AuthenticatedUser(
                  user.id(),
                  user.username(),
                  user.email(),
                  user.displayName(),
                  user.source(),
                  roles,
                  scopes));
        });
  }

  public void recordSuccess(UUID principalId) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    UPDATE local_credential
                    SET failed_attempts = 0, locked_until = NULL, last_login_at = now()
                    WHERE principal_id = :id
                    """)
                .bind("id", principalId)
                .execute());
  }

  /**
   * Counts a failed attempt and locks the account once the threshold is reached.
   *
   * <p>The lock is on the account rather than on the source address, because
   * the attack this defends against is an offline-quality guessing rate against
   * one known username, and an address-based limit is trivially spread across
   * hosts.
   */
  public void recordFailure(UUID principalId, int maxAttempts, java.time.Duration lockFor) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    UPDATE local_credential
                    SET failed_attempts = failed_attempts + 1,
                        locked_until = CASE
                            WHEN failed_attempts + 1 >= :maxAttempts
                            THEN now() + make_interval(secs => :lockSeconds)
                            ELSE locked_until
                        END
                    WHERE principal_id = :id
                    """)
                .bind("id", principalId)
                .bind("maxAttempts", maxAttempts)
                .bind("lockSeconds", (double) lockFor.toSeconds())
                .execute());
  }

  /** Upserts a password; used by the bootstrap and by an administrator reset. */
  public void setPassword(UUID principalId, String passwordHash, boolean mustChange) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO local_credential
                        (principal_id, password_hash, must_change, password_changed_at)
                    VALUES (:id, :hash, :mustChange, now())
                    ON CONFLICT (principal_id) DO UPDATE
                    SET password_hash = EXCLUDED.password_hash,
                        must_change = EXCLUDED.must_change,
                        password_changed_at = now(),
                        failed_attempts = 0,
                        locked_until = NULL
                    """)
                .bind("id", principalId)
                .bind("hash", passwordHash)
                .bind("mustChange", mustChange)
                .execute());
  }

  /** Re-hashes in place after a successful login, without touching the lockout state. */
  public void refreshHash(UUID principalId, String passwordHash) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    "UPDATE local_credential SET password_hash = :hash WHERE principal_id = :id")
                .bind("id", principalId)
                .bind("hash", passwordHash)
                .execute());
  }

  public boolean hasAnyCredential() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT count(*) FROM local_credential")
                .mapTo(Long.class)
                .one()
        > 0);
  }
}
