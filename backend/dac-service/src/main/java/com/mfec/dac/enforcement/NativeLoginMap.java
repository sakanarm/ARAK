package com.mfec.dac.enforcement;

import com.mfec.dac.compiler.sql.PostgresGrantCompiler;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;

/**
 * Which existing database login each person uses on a source
 * ({@code db_principal_map}, kind {@code LOGIN}).
 *
 * <p>ARAK never creates a login or sets a password: the DBA owns both. All it
 * needs to know is that the person the platform calls {@code alice} connects to
 * this source as {@code alice_ro}, so that when the policy lets alice in, the
 * policy's role is granted to {@code alice_ro}. A person with no row here is
 * left out of the role and named in the plan.
 *
 * <p>Several people may share one login (a team account). That login then
 * joins the role only when every one of them qualifies -- anything else would
 * let the people the policy refuses in through a colleague's account.
 */
public final class NativeLoginMap {

  /** PostgreSQL's identifier limit, in bytes; logins are ASCII in practice. */
  static final int MAX_LOGIN = 63;

  /** One person's login on one source. */
  public record Login(
      UUID principalId, String username, String displayName, String principalType, String login) {}

  /** The request was not one that can be stored. */
  public static class InvalidLoginException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidLoginException(String message) {
      super(message);
    }
  }

  private final Jdbi jdbi;

  public NativeLoginMap(Jdbi jdbi) {
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
  }

  public List<Login> list(UUID sourceId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT p.id, p.username, p.display_name, p.principal_type,
                           m.db_principal_name
                      FROM db_principal_map m
                      JOIN principal p ON p.id = m.principal_id
                     WHERE m.data_source_id = :s AND m.db_principal_kind = 'LOGIN'
                     ORDER BY lower(p.username)
                    """)
                .bind("s", sourceId)
                .map(
                    (rs, ctx) ->
                        new Login(
                            rs.getObject("id", UUID.class),
                            rs.getString("username"),
                            rs.getString("display_name"),
                            rs.getString("principal_type"),
                            rs.getString("db_principal_name")))
                .list());
  }

  public int count(UUID sourceId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT count(*) FROM db_principal_map
                     WHERE data_source_id = :s AND db_principal_kind = 'LOGIN'
                    """)
                .bind("s", sourceId)
                .mapTo(Integer.class)
                .one());
  }

  /**
   * Usernames, lower-cased as the engine compares them, to the logins they
   * use. Usually one each; a username held by two principals (one from Entra,
   * one local) can reach two.
   */
  public Map<String, SortedSet<String>> loginsByPerson(UUID sourceId) {
    Map<String, SortedSet<String>> out = new TreeMap<>();
    for (Login one : list(sourceId)) {
      out.computeIfAbsent(key(one.username()), k -> new TreeSet<>()).add(one.login());
    }
    return out;
  }

  /**
   * Maps one person to one login, replacing whatever they had.
   *
   * @return the principal that was mapped
   */
  public Login put(UUID sourceId, String username, String login) {
    String clean = validate(login);
    if (username == null || username.isBlank()) {
      throw new InvalidLoginException("Say whose login this is.");
    }
    List<UUID> ids =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT id FROM principal
                         WHERE lower(username) = lower(:u) AND principal_type IN ('USER', 'SERVICE')
                         ORDER BY CASE source WHEN 'entra' THEN 0 WHEN 'openmetadata' THEN 1
                                  ELSE 2 END
                        """)
                    .bind("u", username.trim())
                    .mapTo(UUID.class)
                    .list());
    if (ids.isEmpty()) {
      throw new InvalidLoginException("No person named " + username.trim() + " is known.");
    }
    UUID principalId = ids.get(0);
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO db_principal_map
                        (data_source_id, principal_id, db_principal_name, db_principal_kind)
                    VALUES (:s, :p, :login, 'LOGIN')
                    ON CONFLICT (data_source_id, principal_id) DO UPDATE SET
                        db_principal_name = EXCLUDED.db_principal_name,
                        db_principal_kind = 'LOGIN',
                        last_error = NULL
                    """)
                .bind("s", sourceId)
                .bind("p", principalId)
                .bind("login", clean)
                .execute());
    return list(sourceId).stream()
        .filter(one -> one.principalId().equals(principalId))
        .findFirst()
        .orElseThrow();
  }

  /** @return the logins that were unmapped, empty when there was nothing to remove */
  public List<String> remove(UUID sourceId, String username) {
    List<String> removed = new ArrayList<>();
    jdbi.useHandle(
        handle ->
            removed.addAll(
                handle
                    .createQuery(
                        """
                        DELETE FROM db_principal_map m
                         USING principal p
                         WHERE p.id = m.principal_id AND m.data_source_id = :s
                           AND m.db_principal_kind = 'LOGIN'
                           AND lower(p.username) = lower(:u)
                        RETURNING m.db_principal_name
                        """)
                    .bind("s", sourceId)
                    .bind("u", username == null ? "" : username.trim())
                    .mapTo(String.class)
                    .list()));
    return removed;
  }

  /** The login as it will be stored, or the reason it cannot be. */
  static String validate(String login) {
    if (login == null || login.isBlank()) {
      throw new InvalidLoginException("Give the database login this person connects as.");
    }
    String clean = login.strip();
    if (clean.length() > MAX_LOGIN) {
      throw new InvalidLoginException(
          "A PostgreSQL login is at most " + MAX_LOGIN + " characters long.");
    }
    for (int i = 0; i < clean.length(); i++) {
      if (Character.isISOControl(clean.charAt(i))) {
        throw new InvalidLoginException("A login may not contain control characters.");
      }
    }
    String lower = clean.toLowerCase(Locale.ROOT);
    if (lower.startsWith(PostgresGrantCompiler.ROLE_PREFIX)) {
      throw new InvalidLoginException(
          clean + " is a name ARAK uses for policy roles; map the person's own login.");
    }
    if (lower.startsWith("pg_")) {
      throw new InvalidLoginException(
          clean + " is a PostgreSQL system role name, not a login.");
    }
    if (lower.equals("public")) {
      throw new InvalidLoginException("PUBLIC is everybody, not a login.");
    }
    return clean;
  }

  static String key(String username) {
    return username == null ? "" : username.toLowerCase(Locale.ROOT);
  }
}
