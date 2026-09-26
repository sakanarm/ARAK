package com.mfec.dac.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

/**
 * The register of source databases the platform governs (FR-6.0a).
 *
 * <p>A source is where enforcement actually happens, so this table decides more
 * than connection details: {@code default_enforcement_mode} says whether a
 * policy on these assets becomes a security policy on the original table, a
 * {@code _secure} view, or a rewrite in the query proxy, and
 * {@code secure_schema} / {@code secure_object_pattern} decide what the
 * generated objects will be called.
 *
 * <p>Two things are validated harder than the rest, both because of where the
 * value ends up rather than because of what it looks like.
 *
 * <ul>
 *   <li><b>The credential reference is a pointer, never a secret.</b> It has to
 *       carry a scheme this platform knows how to resolve. Refusing anything
 *       else here is what stops the console becoming the place someone pastes a
 *       production password — a field that accepted one would put it in an app
 *       database, a backup and an audit export, and the vault requirement
 *       (NFR-1) would be satisfied only on paper.
 *   <li><b>The secure schema and object pattern are identifiers we will
 *       concatenate into DDL.</b> Nothing downstream can parameterise the name
 *       of an object it is creating, so the only place this can be made safe is
 *       at the point it is stored.
 * </ul>
 */
public class DataSourceStore {

  /** What the platform can generate SQL for today (Phase 1). */
  public enum Engine {
    POSTGRES,
    SQLSERVER
  }

  /**
   * How policy reaches the data on this source.
   *
   * <p>{@code NONE} is not "unprotected by accident" — it is a source that is
   * catalogued and policed on paper but has nothing applied to it yet, which is
   * every source on the day it is added.
   */
  public enum EnforcementMode {
    NATIVE_CONFIG,
    SECURE_VIEW,
    PROXY,
    NONE
  }

  /**
   * One registered source.
   *
   * @param tableScope which tables the catalogue import reads; never null, and
   *     {@link TableScope#EVERYTHING} when nothing has been left out
   * @param assetCount tables the crawl has attributed to this source; it is what
   *     makes the difference between an unused row and one that is load-bearing
   *     visible before anybody edits or removes it
   */
  public record Source(
      UUID id,
      String name,
      Engine engine,
      String engineVersion,
      String host,
      int port,
      String defaultDatabase,
      String credentialRef,
      EnforcementMode defaultEnforcementMode,
      String omServiceFqn,
      String secureSchema,
      String secureObjectPattern,
      TableScope tableScope,
      boolean enabled,
      Instant createdAt,
      Instant updatedAt,
      long assetCount) {}

  /**
   * What a caller may send; the server owns everything else on the row.
   *
   * <p>{@code username} and {@code password} are the typed-in alternative to a
   * pointer, and they are deliberately not columns. The resource seals them
   * into {@code credentialRef} before this store ever sees them, so the rule
   * that holds here is still the original one: this class handles a reference
   * and never a secret. Anything that reaches the row is either a pointer or
   * ciphertext, which is what makes a backup of this table safe to hand to
   * somebody without also handing them the key.
   *
   * <p>{@code tableScope} left out of an update keeps the scope already stored,
   * so a client written before scopes existed cannot widen one by saving.
   */
  public record SourceInput(
      String name,
      String engine,
      String engineVersion,
      String host,
      Integer port,
      String defaultDatabase,
      String credentialRef,
      String defaultEnforcementMode,
      String omServiceFqn,
      String secureSchema,
      String secureObjectPattern,
      Boolean enabled,
      String username,
      String password,
      TableScope tableScope) {

    /** The shape every caller used before a table scope could be set. */
    public SourceInput(
        String name,
        String engine,
        String engineVersion,
        String host,
        Integer port,
        String defaultDatabase,
        String credentialRef,
        String defaultEnforcementMode,
        String omServiceFqn,
        String secureSchema,
        String secureObjectPattern,
        Boolean enabled,
        String username,
        String password) {
      this(
          name,
          engine,
          engineVersion,
          host,
          port,
          defaultDatabase,
          credentialRef,
          defaultEnforcementMode,
          omServiceFqn,
          secureSchema,
          secureObjectPattern,
          enabled,
          username,
          password,
          null);
    }

    /** The shape every caller used before a credential could be typed in. */
    public SourceInput(
        String name,
        String engine,
        String engineVersion,
        String host,
        Integer port,
        String defaultDatabase,
        String credentialRef,
        String defaultEnforcementMode,
        String omServiceFqn,
        String secureSchema,
        String secureObjectPattern,
        Boolean enabled) {
      this(
          name,
          engine,
          engineVersion,
          host,
          port,
          defaultDatabase,
          credentialRef,
          defaultEnforcementMode,
          omServiceFqn,
          secureSchema,
          secureObjectPattern,
          enabled,
          null,
          null,
          null);
    }
  }

  /** A rejected input, with the reason in the words the form should show. */
  public static class InvalidSourceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidSourceException(String message) {
      super(message);
    }
  }

  /** A name that is already taken, or a removal that would take assets with it. */
  public static class SourceConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SourceConflictException(String message) {
      super(message);
    }
  }

  /**
   * Schemes this platform knows how to resolve into an actual credential.
   *
   * <p>{@code env:} is here for local development and is as explicit as the
   * others on purpose: an operator reading the row can see the credential lives
   * in the process environment rather than having to infer it from the absence
   * of a scheme.
   */
  private static final List<String> CREDENTIAL_SCHEMES =
      List.of("vault://", "fernet:", "azurekeyvault://", "env:");

  /** Unquoted identifier: what we are willing to paste into generated DDL. */
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

  /** The pattern after {table} is removed — the literal part of an object name. */
  private static final Pattern PATTERN_LITERAL = Pattern.compile("[A-Za-z0-9_]{0,48}");

  private static final String COLUMNS =
      """
      s.id, s.name, s.engine, s.engine_version, s.host, s.port, s.default_database,
      s.credential_ref, s.default_enforcement_mode, s.om_service_fqn, s.secure_schema,
      s.secure_object_pattern, s.table_scope, s.enabled, s.created_at, s.updated_at,
      (SELECT count(*) FROM asset a
        WHERE a.data_source_id = s.id AND a.is_current
          AND a.asset_type IN ('TABLE', 'VIEW')) AS asset_count
      """;

  /** Writes and reads the scope column; the scope is plain records and enums. */
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Jdbi jdbi;

  public DataSourceStore(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  public List<Source> list() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT " + COLUMNS + " FROM data_source s ORDER BY s.name")
                .map((rs, ctx) -> read(rs))
                .list());
  }

  public Optional<Source> find(UUID id) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT " + COLUMNS + " FROM data_source s WHERE s.id = :id")
                .bind("id", id)
                .map((rs, ctx) -> read(rs))
                .findOne());
  }

  public Source create(SourceInput input) {
    Validated v = validate(input, true);
    UUID id =
        insert(v).orElseThrow(() -> new SourceConflictException("A source named " + v.name
            + " already exists. Names are how policies and crawls refer to a source, so they "
            + "cannot be reused."));
    return find(id).orElseThrow();
  }

  public Source update(UUID id, SourceInput input) {
    find(id).orElseThrow(() -> new NoSuchSourceException(id));
    Validated v = validate(input, true);
    try {
      int rows =
          jdbi.withHandle(
              handle ->
                  handle
                      .createUpdate(
                          """
                          UPDATE data_source
                             SET name = :name, engine = :engine, engine_version = :engineVersion,
                                 host = :host, port = :port, default_database = :defaultDatabase,
                                 credential_ref = :credentialRef,
                                 default_enforcement_mode = :mode,
                                 om_service_fqn = :omServiceFqn,
                                 secure_schema = :secureSchema,
                                 secure_object_pattern = :securePattern,
                                 table_scope = CASE WHEN :keepScope THEN table_scope
                                                    ELSE CAST(:scope AS jsonb) END,
                                 enabled = :enabled,
                                 updated_at = now()
                           WHERE id = :id
                          """)
                      .bind("id", id)
                      .bind("name", v.name)
                      .bind("engine", v.engine.name())
                      .bind("engineVersion", v.engineVersion)
                      .bind("host", v.host)
                      .bind("port", v.port)
                      .bind("defaultDatabase", v.defaultDatabase)
                      .bind("credentialRef", v.credentialRef)
                      .bind("mode", v.mode.name())
                      .bind("omServiceFqn", v.omServiceFqn)
                      .bind("secureSchema", v.secureSchema)
                      .bind("securePattern", v.securePattern)
                      .bind("keepScope", v.scope == null)
                      .bind("scope", stored(v.scope))
                      .bind("enabled", v.enabled)
                      .execute());
      if (rows == 0) {
        throw new NoSuchSourceException(id);
      }
    } catch (UnableToExecuteStatementException e) {
      throw nameTaken(e, v.name);
    }
    return find(id).orElseThrow();
  }

  /** Disabling stops the crawl and every apply without losing what is already known. */
  public Source setEnabled(UUID id, boolean enabled) {
    int rows =
        jdbi.withHandle(
            handle ->
                handle
                    .createUpdate(
                        "UPDATE data_source SET enabled = :enabled, updated_at = now() "
                            + "WHERE id = :id")
                    .bind("id", id)
                    .bind("enabled", enabled)
                    .execute());
    if (rows == 0) {
      throw new NoSuchSourceException(id);
    }
    return find(id).orElseThrow();
  }

  /** Records what a connection test found, so the capability matrix has a version to read. */
  public void recordEngineVersion(UUID id, String version) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    "UPDATE data_source SET engine_version = :version, updated_at = now() "
                        + "WHERE id = :id")
                .bind("id", id)
                .bind("version", version)
                .execute());
  }

  /**
   * Removes a source that governs nothing.
   *
   * <p>The foreign keys cascade, so deleting a source that has assets would also
   * delete their columns, their facets, the policy bindings that reach them and
   * their enforcement state — a mis-click that silently unprotects an estate.
   * A source with assets is disabled instead, and the caller is told the count
   * rather than the rule.
   */
  public void delete(UUID id) {
    find(id).orElseThrow(() -> new NoSuchSourceException(id));
    // Deliberately not Source.assetCount(), which counts tables and views because
    // that is the number a person recognises as "what is on this source". The
    // cascade does not stop at tables: a source holding only the service,
    // database and schema rows from a crawl would pass that check and take them
    // with it, and the next crawl would silently re-create them with new ids,
    // orphaning every policy binding that pointed at the old ones.
    long governed = countGovernedRows(id);
    if (governed > 0) {
      throw new SourceConflictException(
          "This source still holds "
              + governed
              + " catalogued objects. Removing it would delete them along with their policy "
              + "bindings and enforcement state. Disable it instead, or re-point the crawl "
              + "first.");
    }
    jdbi.useHandle(
        handle -> handle.createUpdate("DELETE FROM data_source WHERE id = :id").bind("id", id).execute());
  }

  /** Every current catalogue row attributed to this source, containers included. */
  private long countGovernedRows(UUID id) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT count(*) FROM asset WHERE data_source_id = :id AND is_current")
                .bind("id", id)
                .mapTo(Long.class)
                .one());
  }

  /** Thrown when an id names no source; mapped to 404 by the resource. */
  public static class NoSuchSourceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NoSuchSourceException(UUID id) {
      super("No data source " + id);
    }
  }

  // ------------------------------------------------------------- validation

  private record Validated(
      String name,
      Engine engine,
      String engineVersion,
      String host,
      int port,
      String defaultDatabase,
      String credentialRef,
      EnforcementMode mode,
      String omServiceFqn,
      String secureSchema,
      String securePattern,
      TableScope scope,
      boolean enabled) {}

  private Validated validate(SourceInput in, boolean requireCredential) {
    if (in == null) {
      throw new InvalidSourceException("Send a source");
    }
    String name = trimmed(in.name());
    if (name == null) {
      throw new InvalidSourceException("Give the source a name");
    }
    // The name is joined to OpenMetadata FQNs and to generated object comments,
    // and a dot in it would make `prod.mssql.SalesDB` ambiguous about where the
    // service ends.
    if (!IDENTIFIER.matcher(name.replace("-", "_")).matches()) {
      throw new InvalidSourceException(
          "A source name may contain letters, digits, hyphens and underscores only, and must "
              + "start with a letter: it is used as the first segment of every FQN on this source");
    }

    Engine engine = parseEnum(Engine.class, in.engine(), "engine");
    EnforcementMode mode =
        in.defaultEnforcementMode() == null || in.defaultEnforcementMode().isBlank()
            ? EnforcementMode.NONE
            : parseEnum(EnforcementMode.class, in.defaultEnforcementMode(), "enforcement mode");

    String host = trimmed(in.host());
    if (host == null) {
      throw new InvalidSourceException("Give the host the database answers on");
    }
    int port = in.port() == null ? defaultPort(engine) : in.port();
    if (port < 1 || port > 65_535) {
      throw new InvalidSourceException("Port must be between 1 and 65535");
    }

    String credentialRef = trimmed(in.credentialRef());
    if (credentialRef == null) {
      if (requireCredential) {
        throw new InvalidSourceException(
            "Give a username and password, or a pointer to where the secret is kept ("
                + String.join(", ", CREDENTIAL_SCHEMES)
                + ")");
      }
    } else if (CREDENTIAL_SCHEMES.stream().noneMatch(s -> credentialRef.toLowerCase(Locale.ROOT).startsWith(s))) {
      throw new InvalidSourceException(
          "A credential reference must start with one of "
              + String.join(", ", CREDENTIAL_SCHEMES)
              + ". This field points at where the secret is kept, or holds one sealed with the "
              + "deployment key; a password typed in plain would be copied into every backup "
              + "and audit export of this table.");
    }

    String secureSchema = trimmed(in.secureSchema()) == null ? "sec" : trimmed(in.secureSchema());
    if (!IDENTIFIER.matcher(secureSchema).matches()) {
      throw new InvalidSourceException(
          "The secure schema becomes part of a CREATE VIEW statement, so it must be a plain "
              + "identifier: letters, digits and underscores, starting with a letter");
    }

    String pattern =
        trimmed(in.secureObjectPattern()) == null ? "{table}" : trimmed(in.secureObjectPattern());
    if (!pattern.contains("{table}")) {
      throw new InvalidSourceException(
          "The object pattern must contain {table}; without it every secure object on this "
              + "source would be given the same name");
    }
    if (!PATTERN_LITERAL.matcher(pattern.replace("{table}", "")).matches()) {
      throw new InvalidSourceException(
          "Apart from {table}, the object pattern may contain letters, digits and underscores "
              + "only: the result is used as an object name in generated DDL");
    }

    String omServiceFqn = trimmed(in.omServiceFqn());
    if (omServiceFqn != null && omServiceFqn.contains(".")) {
      throw new InvalidSourceException(
          "The OpenMetadata service name is the first segment of an FQN and cannot contain a dot");
    }

    TableScope scope;
    try {
      scope = in.tableScope() == null ? null : TableScope.normalise(in.tableScope());
    } catch (TableScope.InvalidScopeException e) {
      throw new InvalidSourceException(e.getMessage());
    }

    return new Validated(
        name,
        engine,
        trimmed(in.engineVersion()),
        host,
        port,
        trimmed(in.defaultDatabase()),
        credentialRef,
        mode,
        omServiceFqn,
        secureSchema,
        pattern,
        scope,
        in.enabled() == null || in.enabled());
  }

  private Optional<UUID> insert(Validated v) {
    try {
      return jdbi.withHandle(
          handle ->
              handle
                  .createQuery(
                      """
                      INSERT INTO data_source (name, engine, engine_version, host, port,
                                               default_database, credential_ref,
                                               default_enforcement_mode, om_service_fqn,
                                               secure_schema, secure_object_pattern,
                                               table_scope, enabled)
                      VALUES (:name, :engine, :engineVersion, :host, :port, :defaultDatabase,
                              :credentialRef, :mode, :omServiceFqn, :secureSchema,
                              :securePattern, CAST(:scope AS jsonb), :enabled)
                      RETURNING id
                      """)
                  .bind("name", v.name)
                  .bind("engine", v.engine.name())
                  .bind("engineVersion", v.engineVersion)
                  .bind("host", v.host)
                  .bind("port", v.port)
                  .bind("defaultDatabase", v.defaultDatabase)
                  .bind("credentialRef", v.credentialRef)
                  .bind("mode", v.mode.name())
                  .bind("omServiceFqn", v.omServiceFqn)
                  .bind("secureSchema", v.secureSchema)
                  .bind("securePattern", v.securePattern)
                  .bind("scope", stored(v.scope))
                  .bind("enabled", v.enabled)
                  .mapTo(UUID.class)
                  .findOne());
    } catch (UnableToExecuteStatementException e) {
      throw nameTaken(e, v.name);
    }
  }

  private static SourceConflictException nameTaken(UnableToExecuteStatementException e, String name) {
    String text = String.valueOf(e.getMessage());
    if (text.contains("data_source_name_key") || text.contains("duplicate key")) {
      return new SourceConflictException(
          "A source named " + name + " already exists. Names are how policies and crawls refer "
              + "to a source, so they cannot be reused.");
    }
    throw e;
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String what) {
    if (value == null || value.isBlank()) {
      throw new InvalidSourceException("Choose a " + what);
    }
    for (E candidate : type.getEnumConstants()) {
      if (candidate.name().equalsIgnoreCase(value.trim())) {
        return candidate;
      }
    }
    throw new InvalidSourceException(
        "Unknown " + what + " '" + value + "'. Phase 1 supports " + listOf(type));
  }

  private static <E extends Enum<E>> String listOf(Class<E> type) {
    StringBuilder sb = new StringBuilder();
    for (E candidate : type.getEnumConstants()) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(candidate.name());
    }
    return sb.toString();
  }

  /**
   * The column value for a scope: null when every table is read.
   *
   * <p>Stored as absent rather than as {@code {"mode":"ALL"}} so that "never
   * narrowed" and "narrowed, then widened again" read the same, and a source
   * registered before this column existed needs no backfill.
   */
  private static String stored(TableScope scope) {
    if (scope == null || scope.scansEverything()) {
      return null;
    }
    try {
      return JSON.writeValueAsString(scope);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not write a table scope", e);
    }
  }

  private static TableScope readScope(String json, String source) {
    if (json == null || json.isBlank()) {
      return TableScope.EVERYTHING;
    }
    try {
      return JSON.readValue(json, TableScope.class);
    } catch (JsonProcessingException e) {
      // Only reachable by editing the row by hand: everything written here went
      // through TableScope.normalise. Guessing would import tables somebody
      // chose to leave out, or leave out ones they wanted.
      throw new IllegalStateException(
          "The table scope stored for source " + source + " cannot be read", e);
    }
  }

  private static int defaultPort(Engine engine) {
    return engine == Engine.SQLSERVER ? 1433 : 5432;
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String out = value.trim();
    return out.isEmpty() ? null : out;
  }

  private static Source read(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Source(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        Engine.valueOf(rs.getString("engine")),
        rs.getString("engine_version"),
        rs.getString("host"),
        rs.getInt("port"),
        rs.getString("default_database"),
        rs.getString("credential_ref"),
        EnforcementMode.valueOf(rs.getString("default_enforcement_mode")),
        rs.getString("om_service_fqn"),
        rs.getString("secure_schema"),
        rs.getString("secure_object_pattern"),
        readScope(rs.getString("table_scope"), rs.getString("name")),
        rs.getBoolean("enabled"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getLong("asset_count"));
  }
}
