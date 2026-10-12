package com.mfec.dac.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;

/**
 * Personal access tokens: what a tool such as DBeaver or Power BI sends as its
 * password to the gateway (M29a).
 *
 * <p>A token is 256 random bits, shown once when it is made and kept only as a
 * SHA-256 hash. It always ends, at most {@code maxDays} after it was made, and
 * it is ended rather than deleted, so the audit rows that name it keep meaning
 * something. A token stops working the moment it is revoked, expires, or its
 * owner is disabled; nothing about it is cached.
 */
public class AccessTokenStore {

  /** What every token starts with, so a leaked one is recognisable as ours. */
  public static final String PREFIX = "arak_pat_";

  private static final SecureRandom RANDOM = new SecureRandom();

  /** One token as its owner and an administrator see it: never the token itself. */
  public record Token(
      UUID id,
      UUID principalId,
      String principalName,
      String name,
      String tokenPrefix,
      String createdBy,
      Instant createdAt,
      Instant expiresAt,
      Instant lastUsedAt,
      Instant revokedAt,
      String revokedBy,
      String revokeReason) {

    public String status(Instant now) {
      if (revokedAt != null) {
        return "REVOKED";
      }
      return expiresAt.isAfter(now) ? "ACTIVE" : "EXPIRED";
    }
  }

  /** A token just made: the only time {@code secret} exists outside the client. */
  public record Created(Token token, String secret) {}

  /** The owner of a token that signed in. */
  public record Holder(UUID tokenId, UUID principalId, String principalName) {}

  public static class InvalidTokenRequest extends RuntimeException {
    public InvalidTokenRequest(String message) {
      super(message);
    }
  }

  private final Jdbi jdbi;
  private final Clock clock;
  private final int maxDays;

  public AccessTokenStore(Jdbi jdbi, Clock clock, int maxDays) {
    this.jdbi = jdbi;
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.maxDays = maxDays;
  }

  public int maxDays() {
    return maxDays;
  }

  /**
   * Makes a token for {@code principalId}, ending after {@code days}.
   *
   * @throws InvalidTokenRequest when the name is empty or too long, or the
   *     length is outside 1 to {@code maxDays}
   */
  public Created create(UUID principalId, String name, int days, String actor) {
    String label = name == null ? "" : name.strip();
    if (label.isEmpty() || label.length() > 100) {
      throw new InvalidTokenRequest("Give the token a name of 1 to 100 characters, such as the tool it is for");
    }
    if (days < 1 || days > maxDays) {
      throw new InvalidTokenRequest(
          "A token must end within " + maxDays + " days; choose from 1 to " + maxDays);
    }
    byte[] bits = new byte[32];
    RANDOM.nextBytes(bits);
    String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bits);
    String prefix = secret.substring(0, PREFIX.length() + 4);
    Instant now = clock.instant();
    Instant ends = now.plus(Duration.ofDays(days));

    UUID id =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        INSERT INTO access_token (principal_id, name, token_prefix, token_hash,
                                                  created_by, created_at, expires_at)
                        VALUES (:principal, :name, :prefix, :hash, :actor, :now, :ends)
                        RETURNING id
                        """)
                    .bind("principal", principalId)
                    .bind("name", label)
                    .bind("prefix", prefix)
                    .bind("hash", hash(secret))
                    .bind("actor", actor)
                    .bind("now", now)
                    .bind("ends", ends)
                    .mapTo(UUID.class)
                    .one());
    return new Created(find(id).orElseThrow(), secret);
  }

  /**
   * The owner of {@code secret}, if it is a token that may sign in now: not
   * revoked, not expired, and its owner enabled. Marks it used.
   */
  public Optional<Holder> authenticate(String secret) {
    if (secret == null || !secret.startsWith(PREFIX) || secret.length() > 200) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    return jdbi.withHandle(
        handle -> {
          Optional<Holder> holder =
              handle
                  .createQuery(
                      """
                      SELECT t.id, t.principal_id, p.username
                        FROM access_token t
                        JOIN principal p ON p.id = t.principal_id
                       WHERE t.token_hash = :hash
                         AND t.revoked_at IS NULL
                         AND t.expires_at > :now
                         AND p.enabled
                      """)
                  .bind("hash", hash(secret))
                  .bind("now", now)
                  .map(
                      (rs, ctx) ->
                          new Holder(
                              rs.getObject("id", UUID.class),
                              rs.getObject("principal_id", UUID.class),
                              rs.getString("username")))
                  .findOne();
          holder.ifPresent(
              h ->
                  handle
                      .createUpdate("UPDATE access_token SET last_used_at = :now WHERE id = :id")
                      .bind("now", now)
                      .bind("id", h.tokenId())
                      .execute());
          return holder;
        });
  }

  /** Whether the token is still one that may be used: checked again before each statement. */
  public boolean stillValid(UUID tokenId) {
    Instant now = clock.instant();
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT count(*) FROM access_token t JOIN principal p ON p.id = t.principal_id
                     WHERE t.id = :id AND t.revoked_at IS NULL AND t.expires_at > :now
                       AND p.enabled
                    """)
                .bind("id", tokenId)
                .bind("now", now)
                .mapTo(Integer.class)
                .one()
            == 1);
  }

  public List<Token> forPrincipal(UUID principalId) {
    return select("WHERE t.principal_id = :principal", principalId);
  }

  /** Every token, newest first, for an administrator. */
  public List<Token> all() {
    return select("", null);
  }

  public Optional<Token> find(UUID id) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(SELECT + " WHERE t.id = :id")
                .bind("id", id)
                .map(AccessTokenStore::token)
                .findOne());
  }

  /**
   * Ends a token. Ending one that has already ended changes nothing.
   *
   * @return the token as it now stands
   */
  public Token revoke(UUID id, String actor, String reason) {
    String why = reason == null ? null : reason.strip();
    if (why == null || why.isEmpty()) {
      throw new InvalidTokenRequest("Say why the token is being revoked");
    }
    Instant now = clock.instant();
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    UPDATE access_token
                       SET revoked_at = :now, revoked_by = :actor, revoke_reason = :reason
                     WHERE id = :id AND revoked_at IS NULL
                    """)
                .bind("now", now)
                .bind("actor", actor)
                .bind("reason", why.length() > 500 ? why.substring(0, 500) : why)
                .bind("id", id)
                .execute());
    return find(id).orElseThrow();
  }

  private static final String SELECT =
      """
      SELECT t.id, t.principal_id, p.username, t.name, t.token_prefix, t.created_by,
             t.created_at, t.expires_at, t.last_used_at, t.revoked_at, t.revoked_by,
             t.revoke_reason
        FROM access_token t
        JOIN principal p ON p.id = t.principal_id
      """;

  private List<Token> select(String where, UUID principalId) {
    return jdbi.withHandle(
        handle -> {
          var query = handle.createQuery(SELECT + where + " ORDER BY t.created_at DESC LIMIT 500");
          if (principalId != null) {
            query.bind("principal", principalId);
          }
          return query.map(AccessTokenStore::token).list();
        });
  }

  private static Token token(java.sql.ResultSet rs, org.jdbi.v3.core.statement.StatementContext ctx)
      throws java.sql.SQLException {
    return new Token(
        rs.getObject("id", UUID.class),
        rs.getObject("principal_id", UUID.class),
        rs.getString("username"),
        rs.getString("name"),
        rs.getString("token_prefix"),
        rs.getString("created_by"),
        instant(rs, "created_at"),
        instant(rs, "expires_at"),
        instant(rs, "last_used_at"),
        instant(rs, "revoked_at"),
        rs.getString("revoked_by"),
        rs.getString("revoke_reason"));
  }

  private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
    java.sql.Timestamp at = rs.getTimestamp(column);
    return at == null ? null : at.toInstant();
  }

  static String hash(String secret) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing from this JVM", e);
    }
  }
}
