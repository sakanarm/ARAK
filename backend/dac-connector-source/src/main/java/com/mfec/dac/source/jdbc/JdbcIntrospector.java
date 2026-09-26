package com.mfec.dac.source.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Reads the real shape of a source database (FR-1.6).
 *
 * <p>The catalog is a copy of what OpenMetadata knows, and OpenMetadata knows
 * what it knew at its last ingestion. That is fine for browsing and wrong for
 * enforcing: a column added an hour ago is a column no policy covers, and
 * generating SQL from a stale column list either fails loudly or — far worse —
 * succeeds while leaving the new column unmasked. So before anything is
 * enforced, the column list is confirmed against the server itself.
 *
 * <p>It is also how a source that OpenMetadata has never ingested becomes
 * usable here at all: introspection produces assets with provenance
 * {@code discovered}, which the OpenMetadata sync is not allowed to overwrite.
 *
 * <p>Strictly read-only. It opens a connection, reads {@link DatabaseMetaData},
 * and closes.
 */
public final class JdbcIntrospector {

  /** A table or view as the server describes it right now. */
  public record Table(
      String database, String schema, String name, String kind, List<Column> columns) {

    public String qualified() {
      return schema + "." + name;
    }
  }

  /**
   * @param ordinal 1-based position, kept because a projection has to be
   *     rebuilt in the server's order for {@code SELECT *} to mean the same
   *     thing before and after a rewrite
   */
  public record Column(
      String name, int ordinal, String dataType, Integer length, boolean nullable) {}

  /** A table or view by name only, for questions that do not need its columns. */
  public record Name(String schema, String name, String kind) {

    public String qualified() {
      return schema + "." + name;
    }
  }

  /** Schemas that belong to the engine, never to the business. */
  private static final Set<String> SYSTEM_SCHEMAS =
      Set.of(
          "information_schema",
          "pg_catalog",
          "pg_toast",
          "sys",
          "db_accessadmin",
          "db_backupoperator",
          "db_datareader",
          "db_datawriter",
          "db_ddladmin",
          "db_denydatareader",
          "db_denydatawriter",
          "db_owner",
          "db_securityadmin",
          "guest");

  private final CredentialResolver credentials;
  private final int timeoutSeconds;

  public JdbcIntrospector() {
    this(new CredentialResolver(), 15);
  }

  public JdbcIntrospector(CredentialResolver credentials, int timeoutSeconds) {
    this.credentials = credentials;
    this.timeoutSeconds = timeoutSeconds;
  }

  /**
   * Every non-system table and view in the target database, with its columns.
   *
   * @param schemaFilter one schema to restrict to, or null for all of them
   */
  public List<Table> tables(SourceProbe.Target target, String credentialRef, String schemaFilter)
      throws SQLException, CredentialResolver.UnresolvableCredentialException {
    return tables(target, credentialRef, schemaFilter, (schema, name) -> true);
  }

  /**
   * The tables {@code keep} accepts, with their columns.
   *
   * <p>The test is applied to the list of names, before any column is read, so
   * a scope that leaves out ten thousand scratch tables also leaves out their
   * columns rather than reading them and throwing them away.
   *
   * @param keep given a schema and a table name, whether to read the table
   */
  public List<Table> tables(
      SourceProbe.Target target,
      String credentialRef,
      String schemaFilter,
      BiPredicate<String, String> keep)
      throws SQLException, CredentialResolver.UnresolvableCredentialException {

    CredentialResolver.Credential credential = credentials.resolve(credentialRef);
    try (Connection connection = JdbcTargets.open(target, credential, timeoutSeconds)) {
      String database = database(connection, target);
      DatabaseMetaData metadata = connection.getMetaData();

      // Keyed by schema.name so the column pass can find its table again
      // without a nested query per table, which on a wide catalog is the
      // difference between one round trip and a thousand.
      Map<String, Draft> drafts = new LinkedHashMap<>();
      for (Name found : list(metadata, database, schemaFilter)) {
        if (keep.test(found.schema(), found.name())) {
          drafts.put(found.qualified(), new Draft(found.schema(), found.name(), found.kind()));
        }
      }
      if (drafts.isEmpty()) {
        return List.of();
      }

      try (ResultSet rs = metadata.getColumns(database, schemaFilter, "%", "%")) {
        while (rs.next()) {
          String schema = rs.getString("TABLE_SCHEM");
          String table = rs.getString("TABLE_NAME");
          Draft draft = drafts.get(schema + "." + table);
          if (draft == null) {
            continue;
          }
          int size = rs.getInt("COLUMN_SIZE");
          draft.columns.add(
              new Column(
                  rs.getString("COLUMN_NAME"),
                  rs.getInt("ORDINAL_POSITION"),
                  rs.getString("TYPE_NAME"),
                  rs.wasNull() || size <= 0 ? null : size,
                  "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"))));
        }
      }

      List<Table> out = new ArrayList<>(drafts.size());
      for (Draft draft : drafts.values()) {
        draft.columns.sort((a, b) -> Integer.compare(a.ordinal(), b.ordinal()));
        out.add(new Table(database, draft.schema, draft.name, draft.kind, List.copyOf(draft.columns)));
      }
      return out;
    }
  }

  /**
   * Every non-system table and view by name, without reading a column.
   *
   * <p>What a scope is checked against before it is saved: one catalog call,
   * so trying a rule out against a large database costs about as much as
   * testing the connection.
   */
  public List<Name> names(SourceProbe.Target target, String credentialRef, String schemaFilter)
      throws SQLException, CredentialResolver.UnresolvableCredentialException {

    CredentialResolver.Credential credential = credentials.resolve(credentialRef);
    try (Connection connection = JdbcTargets.open(target, credential, timeoutSeconds)) {
      return list(connection.getMetaData(), database(connection, target), schemaFilter);
    }
  }

  private static String database(Connection connection, SourceProbe.Target target)
      throws SQLException {
    String database = connection.getCatalog();
    return database == null || database.isBlank() ? target.database() : database;
  }

  private static List<Name> list(DatabaseMetaData metadata, String database, String schemaFilter)
      throws SQLException {
    List<Name> out = new ArrayList<>();
    try (ResultSet rs =
        metadata.getTables(database, schemaFilter, "%", new String[] {"TABLE", "VIEW"})) {
      while (rs.next()) {
        String schema = rs.getString("TABLE_SCHEM");
        if (schema == null || SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
          continue;
        }
        String name = rs.getString("TABLE_NAME");
        String kind = "VIEW".equalsIgnoreCase(rs.getString("TABLE_TYPE")) ? "VIEW" : "TABLE";
        out.add(new Name(schema, name, kind));
      }
    }
    return out;
  }

  private static final class Draft {
    final String schema;
    final String name;
    final String kind;
    final List<Column> columns = new ArrayList<>();

    Draft(String schema, String name, String kind) {
      this.schema = schema;
      this.name = name;
      this.kind = kind;
    }
  }
}
