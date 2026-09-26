package com.mfec.dac.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

/**
 * The statements people keep under a name, in the query console.
 *
 * <h2>What is kept, and what sharing shares</h2>
 *
 * <p>The statement, never a row it returned. A query shared with everyone is
 * shared as text: whoever opens it runs it as themselves, through the proxy and
 * the policies that apply to them, so nobody sees anything by way of a shared
 * query that they could not have seen by typing it. The literals in a
 * {@code WHERE} can themselves be data, which is why sharing is chosen per
 * query and off by default.
 *
 * <h2>Who sees what</h2>
 *
 * <p>Your own queries, and the queries others have shared. A private query of
 * somebody else's does not exist as far as you are concerned -- not found, not
 * forbidden, so asking for one by id says nothing about whether it is there.
 * Only the owner changes or deletes a query, administrators included: a query
 * is somebody's working notes, not a governed object.
 *
 * <h2>Names</h2>
 *
 * <p>One owner has one query of each name, ignoring case. Saving under a name
 * already taken is refused with the query that has it, so the console can offer
 * to replace that one instead of quietly keeping two.
 */
public class SavedQueryStore {

  public static final int MAX_NAME = 120;
  public static final int MAX_DESCRIPTION = 500;
  public static final int MAX_SQL = 100_000;
  /** Per owner. Enough for anybody's notes, and a ceiling on a script filling the table. */
  public static final int MAX_PER_OWNER = 500;

  /** What is saved: said by the caller, checked by {@link #validate}. */
  public record Draft(String name, String description, String sourceId, String sql, Boolean shared) {}

  /** A saved query as its readers see it. */
  public record Saved(
      UUID id,
      String owner,
      String name,
      String description,
      UUID sourceId,
      String sql,
      boolean shared,
      Instant createdAt,
      Instant updatedAt) {}

  /** A draft once checked: trimmed, bounded, with a parsed source. */
  public record Clean(String name, String description, UUID sourceId, String sql, boolean shared) {}

  private final Jdbi jdbi;

  public SavedQueryStore(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /** Checks a draft, or says what is wrong with it. */
  public static Clean validate(Draft draft) {
    if (draft == null) {
      throw new IllegalArgumentException("Send {\"name\": \"…\", \"sql\": \"SELECT …\"}");
    }
    String name = draft.name() == null ? "" : draft.name().strip();
    if (name.isEmpty()) {
      throw new IllegalArgumentException("Give the query a name");
    }
    if (name.length() > MAX_NAME) {
      throw new IllegalArgumentException("A name is at most " + MAX_NAME + " characters");
    }
    String description = draft.description() == null ? null : draft.description().strip();
    if (description != null && description.isEmpty()) {
      description = null;
    }
    if (description != null && description.length() > MAX_DESCRIPTION) {
      throw new IllegalArgumentException("A description is at most " + MAX_DESCRIPTION + " characters");
    }
    String sql = draft.sql() == null ? "" : draft.sql();
    if (sql.isBlank()) {
      throw new IllegalArgumentException("There is no statement to save");
    }
    if (sql.length() > MAX_SQL) {
      throw new IllegalArgumentException("A statement is at most " + MAX_SQL + " characters");
    }
    UUID source = null;
    if (draft.sourceId() != null && !draft.sourceId().isBlank()) {
      try {
        source = UUID.fromString(draft.sourceId().strip());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("sourceId must be the UUID of a registered source");
      }
    }
    return new Clean(name, description, source, sql, Boolean.TRUE.equals(draft.shared()));
  }

  /** The caller's own queries, then those others have shared, each by name. */
  public List<Saved> list(String caller) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT * FROM saved_query
                     WHERE lower(owner) = lower(:caller) OR shared
                     ORDER BY lower(owner) = lower(:caller) DESC, lower(name), id
                    """)
                .bind("caller", caller)
                .map((rs, ctx) -> saved(rs))
                .list());
  }

  /** One query, if the caller owns it or it is shared. */
  public Optional<Saved> find(UUID id, String caller) {
    return jdbi.withHandle(handle -> readable(handle, id, caller));
  }

  /** Saves a new query, or refuses a name the caller already uses. */
  public Saved create(Clean clean, String owner) {
    return named(
        clean.name(),
        owner,
        () ->
            jdbi.inTransaction(
                handle -> {
                  int held =
                      handle
                          .createQuery("SELECT count(*) FROM saved_query WHERE lower(owner) = lower(:owner)")
                          .bind("owner", owner)
                          .mapTo(Integer.class)
                          .one();
                  if (held >= MAX_PER_OWNER) {
                    throw new IllegalArgumentException(
                        "You have " + MAX_PER_OWNER + " saved queries; delete some before saving more");
                  }
                  requireSource(handle, clean.sourceId());
                  UUID id =
                      handle
                          .createQuery(
                              """
                              INSERT INTO saved_query (owner, name, description, source_id, sql, shared)
                              VALUES (:owner, :name, :description, :source, :sql, :shared)
                              RETURNING id
                              """)
                          .bind("owner", owner)
                          .bind("name", clean.name())
                          .bind("description", clean.description())
                          .bind("source", clean.sourceId())
                          .bind("sql", clean.sql())
                          .bind("shared", clean.shared())
                          .mapTo(UUID.class)
                          .one();
                  return readable(handle, id, owner).orElseThrow();
                }));
  }

  /** Replaces a query the caller owns. */
  public Saved update(UUID id, Clean clean, String owner) {
    return named(
        clean.name(),
        owner,
        () ->
            jdbi.inTransaction(
                handle -> {
                  owned(handle, id, owner);
                  requireSource(handle, clean.sourceId());
                  handle
                      .createUpdate(
                          """
                          UPDATE saved_query
                             SET name = :name, description = :description, source_id = :source,
                                 sql = :sql, shared = :shared, updated_at = now()
                           WHERE id = :id
                          """)
                      .bind("name", clean.name())
                      .bind("description", clean.description())
                      .bind("source", clean.sourceId())
                      .bind("sql", clean.sql())
                      .bind("shared", clean.shared())
                      .bind("id", id)
                      .execute();
                  return readable(handle, id, owner).orElseThrow();
                }));
  }

  /** Deletes a query the caller owns. */
  public void delete(UUID id, String owner) {
    jdbi.useTransaction(
        handle -> {
          owned(handle, id, owner);
          handle.createUpdate("DELETE FROM saved_query WHERE id = :id").bind("id", id).execute();
        });
  }

  /** A name the owner already uses, with the query that uses it. */
  public static class NameTakenException extends RuntimeException {
    private final UUID existingId;

    public NameTakenException(String name, UUID existingId) {
      super("You already have a query called \"" + name + "\"");
      this.existingId = existingId;
    }

    public UUID existingId() {
      return existingId;
    }
  }

  /** No query by that id that the caller may see, or may change. */
  public static class NoSuchQueryException extends RuntimeException {
    public NoSuchQueryException(UUID id) {
      super("No saved query " + id);
    }
  }

  // --------------------------------------------------------------- plumbing

  private static Optional<Saved> readable(Handle handle, UUID id, String caller) {
    return handle
        .createQuery("SELECT * FROM saved_query WHERE id = :id AND (lower(owner) = lower(:caller) OR shared)")
        .bind("id", id)
        .bind("caller", caller)
        .map((rs, ctx) -> saved(rs))
        .findOne();
  }

  /**
   * The query, locked, if the caller owns it. Somebody else's shared query is
   * "no such query" to a writer, the same answer as one that is not there.
   */
  private static void owned(Handle handle, UUID id, String owner) {
    handle
        .createQuery("SELECT id FROM saved_query WHERE id = :id AND lower(owner) = lower(:owner) FOR UPDATE")
        .bind("id", id)
        .bind("owner", owner)
        .mapTo(UUID.class)
        .findOne()
        .orElseThrow(() -> new NoSuchQueryException(id));
  }

  private static void requireSource(Handle handle, UUID source) {
    if (source == null) {
      return;
    }
    boolean known =
        handle
            .createQuery("SELECT EXISTS (SELECT 1 FROM data_source WHERE id = :id)")
            .bind("id", source)
            .mapTo(Boolean.class)
            .one();
    if (!known) {
      throw new IllegalArgumentException("No registered source " + source);
    }
  }

  private <T> T named(String name, String owner, java.util.function.Supplier<T> call) {
    try {
      return call.get();
    } catch (UnableToExecuteStatementException e) {
      if (String.valueOf(e.getMessage()).contains("saved_query_owner_name_idx")) {
        UUID existing =
            jdbi.withHandle(
                handle ->
                    handle
                        .createQuery(
                            "SELECT id FROM saved_query WHERE lower(owner) = lower(:owner) AND lower(name) = lower(:name)")
                        .bind("owner", owner)
                        .bind("name", name)
                        .mapTo(UUID.class)
                        .findOne()
                        .orElse(null));
        throw new NameTakenException(name, existing);
      }
      throw e;
    }
  }

  private static Saved saved(ResultSet rs) throws SQLException {
    String source = rs.getString("source_id");
    return new Saved(
        UUID.fromString(rs.getString("id")),
        rs.getString("owner"),
        rs.getString("name"),
        rs.getString("description"),
        source == null ? null : UUID.fromString(source),
        rs.getString("sql"),
        rs.getBoolean("shared"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
