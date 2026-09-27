package com.mfec.dac.source.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Who can read one table at the source without going through the platform
 * (FR-6.3.1).
 *
 * <p>The proxy and the secure view each protect a table by being the only way
 * to it. A policy that masks a column in the proxy means nothing to a login
 * that still holds {@code SELECT} on the base table and connects to the
 * database directly; the view is the same story. This reads the source's own
 * catalogue and names everybody who is left holding a way in, so the page can
 * say "these people are not governed by ARAK" instead of letting a data owner
 * assume they are.
 *
 * <p>What counts, per engine:
 *
 * <ul>
 *   <li><b>PostgreSQL</b> -- the owner; {@code SELECT} granted on the table or on
 *       any of its columns, to a role or to {@code PUBLIC}; superusers; members
 *       of {@code pg_read_all_data} (PG 14+). A grantee without {@code USAGE}
 *       on the schema is left out, since the grant alone does not let it in.
 *   <li><b>SQL Server</b> -- {@code SELECT} or {@code CONTROL} granted on the
 *       object, a column of it, its schema or the database; members of
 *       {@code db_datareader} and {@code db_owner}; the owner; enabled logins in
 *       {@code sysadmin}, as far as the platform's login may see them. A
 *       principal with a direct {@code DENY SELECT} on the object or schema is
 *       left out; a deny that reaches it through a role is not traced.
 * </ul>
 *
 * <p>A role that cannot log in is listed with the logins that inherit it, since
 * a role nobody can log in as is only a risk through its members.
 *
 * <p>Read-only: catalogue views and nothing else, on a read-only connection. It
 * does not look at a single row of the table.
 */
public final class DirectAccessReader {

  /** Past this many members a role's list is cut; the count says there were more. */
  static final int MEMBER_LIMIT = 50;

  /**
   * One principal with a way in.
   *
   * @param login whether it can connect by itself; a role that cannot is only a
   *     way in for its {@code members}
   * @param via how it gets in -- {@code OWNER}, {@code GRANT}, {@code COLUMN},
   *     {@code PUBLIC}, {@code SUPERUSER}, {@code READ_ALL_DATA}, {@code SCHEMA},
   *     {@code DATABASE}, {@code ROLE db_datareader}, {@code SYSADMIN}
   * @param members logins that inherit this role, at most {@link #MEMBER_LIMIT}
   * @param memberCount how many there are in all
   * @param self this is the login the platform itself connects as, which is
   *     expected to hold the table
   */
  public record Holder(
      String name,
      boolean login,
      List<String> via,
      List<String> members,
      int memberCount,
      boolean self) {}

  /**
   * @param found whether the table exists at the source under that name; when
   *     it does not, nothing else here means anything
   * @param connectedAs the login the platform read this as
   */
  public record Report(boolean found, String connectedAs, List<Holder> holders, long millis) {}

  private final CredentialResolver credentials;
  private final int loginTimeoutSeconds;

  public DirectAccessReader(CredentialResolver credentials, int loginTimeoutSeconds) {
    this.credentials = credentials;
    this.loginTimeoutSeconds = loginTimeoutSeconds;
  }

  public Report read(SourceProbe.Target target, String credentialRef, String schema, String table)
      throws SQLException, CredentialResolver.UnresolvableCredentialException {
    long started = System.nanoTime();
    CredentialResolver.Credential credential = credentials.resolve(credentialRef);
    try (Connection connection = JdbcTargets.open(target, credential, loginTimeoutSeconds)) {
      return read(connection, target.engine(), schema, table, started);
    }
  }

  /** The same, on a connection the caller already holds and will close. */
  public static Report read(Connection connection, String engineId, String schema, String table)
      throws SQLException {
    return read(connection, engineId, schema, table, System.nanoTime());
  }

  private static Report read(
      Connection connection, String engineId, String schema, String table, long started)
      throws SQLException {
    Engine engine = Engine.of(engineId);
    String connectedAs = single(connection, engine.selfSql);
    if (!exists(connection, engine, schema, table)) {
      return new Report(false, connectedAs, List.of(), elapsed(started));
    }
    Map<String, Found> found = new LinkedHashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(engine.holdersSql)) {
      bind(statement, engine.holdersParams, schema, table);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          String name = rs.getString("name");
          Found entry = found.computeIfAbsent(name, key -> new Found());
          entry.login |= rs.getBoolean("can_login");
          entry.via.add(rs.getString("via"));
          entry.selfSeen |= rs.getBoolean("is_self");
        }
      }
    }
    List<Holder> holders = new ArrayList<>(found.size());
    for (Map.Entry<String, Found> entry : found.entrySet()) {
      Found at = entry.getValue();
      List<String> members = List.of();
      int count = 0;
      if (!at.login && engine.membersSql != null && !isPublic(entry.getKey())) {
        List<String> all = list(connection, engine.membersSql, entry.getKey());
        count = all.size();
        members = all.size() > MEMBER_LIMIT ? List.copyOf(all.subList(0, MEMBER_LIMIT)) : all;
      }
      boolean self =
          at.selfSeen || (connectedAs != null && entry.getKey().equalsIgnoreCase(connectedAs));
      holders.add(
          new Holder(entry.getKey(), at.login, List.copyOf(at.via), members, count, self));
    }
    holders.sort(
        (a, b) -> {
          if (a.self() != b.self()) {
            return a.self() ? 1 : -1;
          }
          return a.name().compareToIgnoreCase(b.name());
        });
    return new Report(true, connectedAs, List.copyOf(holders), elapsed(started));
  }

  private static boolean isPublic(String name) {
    return "PUBLIC".equalsIgnoreCase(name);
  }

  private static final class Found {
    boolean login;
    boolean selfSeen;
    final Set<String> via = new LinkedHashSet<>();
  }

  private static boolean exists(Connection connection, Engine engine, String schema, String table)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(engine.existsSql)) {
      statement.setString(1, schema);
      statement.setString(2, table);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() && rs.getInt(1) > 0;
      }
    }
  }

  private static String single(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rs = statement.executeQuery()) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private static List<String> list(Connection connection, String sql, String role)
      throws SQLException {
    List<String> out = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, role);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  /** Binds the schema and table wherever the statement asks for them, in order. */
  private static void bind(PreparedStatement statement, String params, String schema, String table)
      throws SQLException {
    for (int i = 0; i < params.length(); i++) {
      statement.setString(i + 1, params.charAt(i) == 's' ? schema : table);
    }
  }

  private static long elapsed(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }

  /** The catalogue queries for one engine. */
  enum Engine {
    POSTGRES(
        "SELECT current_user",
        """
        SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE lower(n.nspname) = lower(?) AND lower(c.relname) = lower(?)
           AND c.relkind IN ('r', 'v', 'm', 'p', 'f')
        """,
        """
        WITH t AS (
          SELECT c.oid, c.relowner, c.relacl, c.relnamespace
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE lower(n.nspname) = lower(?) AND lower(c.relname) = lower(?)
             AND c.relkind IN ('r', 'v', 'm', 'p', 'f')
        ), way AS (
          SELECT t.relowner AS grantee, 'OWNER' AS via, t.relnamespace FROM t
          UNION ALL
          SELECT a.grantee, CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE 'GRANT' END, t.relnamespace
            FROM t, aclexplode(coalesce(t.relacl, acldefault('r', t.relowner))) a
           WHERE a.privilege_type = 'SELECT'
          UNION ALL
          SELECT a.grantee, CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE 'COLUMN' END, t.relnamespace
            FROM t
            JOIN pg_attribute att ON att.attrelid = t.oid AND att.attnum > 0
                                 AND NOT att.attisdropped AND att.attacl IS NOT NULL,
                 aclexplode(att.attacl) a
           WHERE a.privilege_type = 'SELECT'
          UNION ALL
          SELECT r.oid, 'SUPERUSER', NULL FROM pg_roles r WHERE r.rolsuper
          UNION ALL
          SELECT r.oid, 'READ_ALL_DATA', NULL FROM pg_roles r
           WHERE to_regrole('pg_read_all_data') IS NOT NULL
             AND r.oid <> to_regrole('pg_read_all_data') AND NOT r.rolsuper
             AND pg_has_role(r.oid, to_regrole('pg_read_all_data'), 'MEMBER')
        )
        SELECT DISTINCT coalesce(r.rolname, 'PUBLIC') AS name,
               coalesce(r.rolcanlogin, false) AS can_login,
               w.via,
               coalesce(r.rolname = current_user, false) AS is_self
          FROM way w
          LEFT JOIN pg_roles r ON r.oid = w.grantee
         WHERE (w.grantee = 0 AND EXISTS (
                  SELECT 1 FROM pg_namespace n,
                         aclexplode(coalesce(n.nspacl, acldefault('n', n.nspowner))) u
                   WHERE n.oid = w.relnamespace AND u.grantee = 0
                     AND u.privilege_type = 'USAGE'))
            OR w.relnamespace IS NULL
            OR r.rolsuper
            OR has_schema_privilege(w.grantee, w.relnamespace, 'USAGE')
         ORDER BY 1, 3
        """,
        "st",
        """
        WITH RECURSIVE m(oid) AS (
          SELECT am.member FROM pg_auth_members am
           WHERE am.roleid = (SELECT oid FROM pg_roles WHERE rolname = ?)
          UNION
          SELECT am.member FROM pg_auth_members am JOIN m ON am.roleid = m.oid
        )
        SELECT r.rolname FROM m JOIN pg_roles r ON r.oid = m.oid
         WHERE r.rolcanlogin ORDER BY 1
        """),
    SQLSERVER(
        "SELECT USER_NAME()",
        "SELECT CASE WHEN OBJECT_ID(QUOTENAME(?) + N'.' + QUOTENAME(?)) IS NULL THEN 0 ELSE 1 END",
        """
        SELECT p.name AS name,
               CAST(CASE WHEN p.type IN ('S', 'U', 'G', 'E', 'X') THEN 1 ELSE 0 END AS bit)
                   AS can_login,
               w.via,
               CAST(CASE WHEN p.name = USER_NAME() THEN 1 ELSE 0 END AS bit) AS is_self
          FROM (
            SELECT dp.grantee_principal_id AS pid,
                   CASE WHEN dp.class = 0 THEN 'DATABASE'
                        WHEN dp.class = 3 THEN 'SCHEMA'
                        WHEN dp.minor_id > 0 THEN 'COLUMN'
                        ELSE 'GRANT' END AS via
              FROM sys.database_permissions dp
             WHERE dp.state IN ('G', 'W') AND dp.permission_name IN ('SELECT', 'CONTROL')
               AND ((dp.class = 1 AND dp.major_id = OBJECT_ID(QUOTENAME(?) + N'.' + QUOTENAME(?)))
                 OR (dp.class = 3 AND dp.major_id = SCHEMA_ID(?))
                 OR dp.class = 0)
            UNION ALL
            SELECT rm.member_principal_id, 'ROLE ' + r.name
              FROM sys.database_role_members rm
              JOIN sys.database_principals r ON r.principal_id = rm.role_principal_id
             WHERE r.name IN ('db_datareader', 'db_owner')
            UNION ALL
            SELECT COALESCE(o.principal_id, s.principal_id), 'OWNER'
              FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id
             WHERE o.object_id = OBJECT_ID(QUOTENAME(?) + N'.' + QUOTENAME(?))
          ) w
          JOIN sys.database_principals p ON p.principal_id = w.pid
         WHERE p.name NOT IN ('sys', 'INFORMATION_SCHEMA')
           AND NOT EXISTS (
             SELECT 1 FROM sys.database_permissions d
              WHERE d.grantee_principal_id = p.principal_id AND d.state = 'D'
                AND d.permission_name = 'SELECT' AND d.minor_id = 0
                AND ((d.class = 1 AND d.major_id = OBJECT_ID(QUOTENAME(?) + N'.' + QUOTENAME(?)))
                  OR (d.class = 3 AND d.major_id = SCHEMA_ID(?))))
        UNION
        SELECT sp.name, CAST(1 AS bit), 'SYSADMIN',
               CAST(CASE WHEN sp.name = SUSER_SNAME() THEN 1 ELSE 0 END AS bit)
          FROM sys.server_principals sp
         WHERE sp.type IN ('S', 'U', 'G', 'E', 'X') AND sp.is_disabled = 0
           AND sp.name NOT LIKE '##%'
           AND IS_SRVROLEMEMBER('sysadmin', sp.name) = 1
         ORDER BY 1, 3
        """,
        "stsststs",
        """
        WITH m AS (
          SELECT rm.member_principal_id AS pid
            FROM sys.database_role_members rm
            JOIN sys.database_principals r ON r.principal_id = rm.role_principal_id
           WHERE r.name = ?
          UNION ALL
          SELECT rm.member_principal_id
            FROM sys.database_role_members rm JOIN m ON rm.role_principal_id = m.pid
        )
        SELECT DISTINCT p.name FROM m JOIN sys.database_principals p ON p.principal_id = m.pid
         WHERE p.type IN ('S', 'U', 'G', 'E', 'X') ORDER BY 1
        """);

    final String selfSql;
    final String existsSql;
    final String holdersSql;
    /** Which of schema (s) or table (t) each {@code ?} in {@link #holdersSql} takes, in order. */
    final String holdersParams;
    final String membersSql;

    Engine(
        String selfSql,
        String existsSql,
        String holdersSql,
        String holdersParams,
        String membersSql) {
      this.selfSql = selfSql;
      this.existsSql = existsSql;
      this.holdersSql = holdersSql;
      this.holdersParams = holdersParams;
      this.membersSql = membersSql;
    }

    static Engine of(String engine) {
      String id = engine == null ? "" : engine.trim().toUpperCase(Locale.ROOT);
      return switch (id) {
        case "POSTGRES", "POSTGRESQL" -> POSTGRES;
        case "SQLSERVER", "MSSQL" -> SQLSERVER;
        default ->
            throw new IllegalArgumentException("Cannot read who holds a table on " + engine);
      };
    }
  }
}
