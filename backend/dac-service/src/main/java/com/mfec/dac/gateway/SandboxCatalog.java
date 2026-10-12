package com.mfec.dac.gateway;

import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.source.DataSourceStore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jdbi.v3.core.Jdbi;
import org.postgresql.core.BaseConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers a client's questions about the server -- which schemas, tables and
 * columns there are, what types exist -- from an empty PostgreSQL database
 * that is shaped like what the asking person may see.
 *
 * <p>A BI tool learns a database from {@code pg_catalog} and {@code
 * information_schema} with queries no rewriter could follow, and sending them
 * to the source would show every table there whoever asked. So they never go
 * there. They go to a separate server holding one database per shape: a shape
 * is a set of tables with the columns someone may see, created empty, and
 * everyone who may see exactly that set shares it. The question runs as a role
 * that can do nothing but read those tables, in a transaction that is rolled
 * back. Nothing in a shape is data: the worst a question can learn is a table
 * name its asker was already allowed to see.
 *
 * <p>A shape is kept, not rebuilt for each question, because tools ask in
 * steps: psql's {@code \d} and DBeaver's navigator find a table's OID in one
 * question and look its columns up by that OID in the next. A shape nobody has
 * used for a while is dropped when a new one for the same source is made.
 *
 * <p>Each source also has an empty database named after it, because a tool
 * looks the database it connected to up by that name in {@code pg_database}.
 * {@code current_database()} names the shape.
 */
final class SandboxCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(SandboxCatalog.class);

  private static final Pattern URL = Pattern.compile("^(jdbc:postgresql://[^/?]+)/?([^?]*)(\\?.*)?$");

  /** Names a sandbox database cannot take: they are the server's own. */
  private static final Set<String> RESERVED = Set.of("postgres", "template0", "template1");

  /** The comment on a shape database once it is complete; also marks it as the gateway's own. */
  static final String SHAPE_MARK = "ARAK gateway catalog shape";

  /** Hex digits of the shape's hash in its database name. */
  private static final int SHAPE_HASH = 16;

  /** A shape used here more recently than this is never dropped. */
  private static final long SHAPE_IDLE_NANOS = 10L * 60 * 1_000_000_000L;

  /**
   * Functions that do something other than read: change a setting (the role
   * among them), signal another session, take a lock, touch a file or a large
   * object, or run a query given as a string. Nothing a tool needs to learn a
   * catalog, and refused so that a question stays a question.
   */
  private static final Pattern ACTIONS =
      Pattern.compile(
          "(?i)\\b(set_config|pg_terminate_backend|pg_cancel_backend|pg_reload_conf"
              + "|pg_rotate_logfile|pg_promote|pg_switch_wal|pg_create_\\w+|pg_drop_\\w+"
              + "|pg_replication_\\w+|pg_logical_\\w+|pg_advisory\\w*|pg_try_advisory\\w*"
              + "|pg_notify|pg_sleep\\w*|pg_read_\\w+|pg_ls_\\w+|pg_stat_file|pg_file_\\w+"
              + "|lo_\\w+|dblink\\w*|\\w+_to_xml\\w*|query_to_json\\w*|pg_import_system_collations"
              + "|txid_current|pg_current_xact_id|nextval|setval)\\s*\\(");

  /** Unicode-escaped names could spell any of the above in a way the pattern cannot see. */
  private static final Pattern UNICODE_ESCAPE = Pattern.compile("(?i)\\bU&[\"']");

  /** What the catalog says a column is, as the sandbox can create it. */
  private static final Map<String, String> TYPES = new LinkedHashMap<>();

  static {
    TYPES.put("VARCHAR", "varchar");
    TYPES.put("STRING", "varchar");
    TYPES.put("CHAR", "varchar");
    TYPES.put("TEXT", "text");
    TYPES.put("INT", "integer");
    TYPES.put("BIGINT", "bigint");
    TYPES.put("SMALLINT", "smallint");
    TYPES.put("TINYINT", "smallint");
    TYPES.put("BOOLEAN", "boolean");
    TYPES.put("UUID", "uuid");
    TYPES.put("TIMESTAMPZ", "timestamptz");
    TYPES.put("TIMESTAMPTZ", "timestamptz");
    TYPES.put("TIMESTAMP", "timestamp");
    TYPES.put("DATETIME", "timestamp");
    TYPES.put("DATE", "date");
    TYPES.put("TIME", "time");
    TYPES.put("NUMERIC", "numeric");
    TYPES.put("DECIMAL", "numeric");
    TYPES.put("NUMBER", "numeric");
    TYPES.put("DOUBLE", "double precision");
    TYPES.put("FLOAT", "real");
    TYPES.put("JSON", "json");
    TYPES.put("JSONB", "jsonb");
    TYPES.put("INET", "inet");
    TYPES.put("BYTEA", "bytea");
    TYPES.put("BINARY", "bytea");
    TYPES.put("VARBINARY", "bytea");
    TYPES.put("BYTES", "bytea");
  }

  /** A result from the sandbox, read in full; a catalog answer is small. */
  record Result(List<PgWire.Column> columns, List<String[]> rows, boolean capped) {}

  /** A table as the asking person may see it. */
  private record Visible(String schema, String table, List<String[]> columns) {}

  private final Jdbi app;
  private final DecisionService decisions;
  private final String url;
  private final String username;
  private final String password;
  private final String readerRole;
  private final int timeoutSeconds;
  private final int maxRows;
  private final Set<String> created = ConcurrentHashMap.newKeySet();
  /** Shape databases known to be complete. */
  private final Set<String> ready = ConcurrentHashMap.newKeySet();
  /** When this process last used each shape, so a shape in use here is not dropped. */
  private final Map<String, Long> lastUsed = new ConcurrentHashMap<>();

  SandboxCatalog(
      Jdbi app,
      DecisionService decisions,
      String url,
      String username,
      String password,
      String readerRole,
      int timeoutSeconds,
      int maxRows) {
    Matcher m = URL.matcher(Objects.requireNonNull(url, "gateway.sandboxJdbcUrl"));
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "gateway.sandboxJdbcUrl must look like jdbc:postgresql://host:port/database");
    }
    this.app = app;
    this.decisions = decisions;
    this.url = url;
    this.username = username;
    this.password = password;
    this.readerRole = readerRole;
    this.timeoutSeconds = timeoutSeconds;
    this.maxRows = maxRows;
  }

  /** A session's sandbox: one connection, opened when the first question comes. */
  Session open(DataSourceStore.Source source, String principal, String clientIp) {
    return new Session(source, principal, clientIp);
  }

  final class Session implements AutoCloseable {
    private final DataSourceStore.Source source;
    private final String principal;
    private final String clientIp;
    private Connection connection;
    private String connectedTo;
    private String shapedFor;
    private long shapedAt;
    private List<Visible> visible;

    private Session(DataSourceStore.Source source, String principal, String clientIp) {
      this.source = source;
      this.principal = principal;
      this.clientIp = clientIp;
    }

    /**
     * Runs one catalog question.
     *
     * @param sql the question with its parameters already put in
     * @param purpose the purpose declared for the session, which can change
     *     what the person may see
     * @param schema where unqualified names are looked for
     * @param limit at most this many rows; 0 for the sandbox's own cap
     */
    Result query(String sql, String purpose, String schema, int limit) throws PgError {
      refuseActions(sql);
      Connection c = connection(shape(visibleTables(purpose)));
      int cap = limit > 0 ? Math.min(limit, maxRows) : maxRows;
      try {
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
          String path = schema == null || schema.isBlank() ? "public" : schema;
          s.execute("SET LOCAL search_path = " + ident(path));
          s.execute("SET LOCAL ROLE " + ident(readerRole));
          s.execute("SET LOCAL statement_timeout = " + (timeoutSeconds * 1000L));
          s.setMaxRows(cap + 1);
          try (ResultSet rs = s.executeQuery(sql)) {
            return read(c, rs, cap);
          }
        } finally {
          c.rollback();
        }
      } catch (SQLException e) {
        throw fromSandbox(e);
      }
    }

    @Override
    public void close() {
      if (connection != null) {
        try {
          connection.close();
        } catch (SQLException e) {
          LOG.debug("Closing a sandbox connection: {}", e.toString());
        }
        connection = null;
        connectedTo = null;
      }
    }

    /** A connection to the database of this shape, made the first time it is asked for. */
    private Connection connection(String shape) throws PgError {
      try {
        String name = shapeDatabase(source.name(), shape);
        if (connection != null && !connection.isClosed() && name.equals(connectedTo)) {
          return connection;
        }
        close();
        try {
          connection = connect(name);
        } catch (SQLException e) {
          if (!PgError.INVALID_CATALOG.equals(e.getSQLState())) {
            throw e;
          }
          // Dropped as unused by another ARAK node since this one made it.
          ready.remove(name);
          name = shapeDatabase(source.name(), shape);
          connection = connect(name);
        }
        connectedTo = name;
        return connection;
      } catch (SQLException e) {
        LOG.warn("The gateway's catalog sandbox could not be reached: {}", e.toString());
        throw new PgError(
            PgError.CONNECTION_FAILURE, "The catalog is not available right now; try again shortly");
      }
    }

    /** The tables this person may read on the source, with the columns they may see. */
    private List<Visible> visibleTables(String purpose) {
      String key = purpose == null ? "" : purpose;
      long now = System.nanoTime();
      if (visible != null && key.equals(shapedFor) && now - shapedAt < 30_000_000_000L) {
        return visible;
      }
      List<String[]> assets =
          app.withHandle(
              h ->
                  h.createQuery(
                          """
                          SELECT om_fqn, schema_name, object_name FROM asset_fqn_map
                           WHERE data_source_id = CAST(:sourceId AS uuid)
                             AND verification_status <> 'ORPHANED'
                           ORDER BY schema_name, object_name
                          """)
                      .bind("sourceId", source.id())
                      .map((rs, ctx) ->
                          new String[] {
                            rs.getString("om_fqn"), rs.getString("schema_name"), rs.getString("object_name")
                          })
                      .list());
      List<Visible> out = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (String[] asset : assets) {
        String schema = asset[1];
        String lower = schema.toLowerCase(Locale.ROOT);
        if (lower.startsWith("pg_") || lower.equals("information_schema")) {
          continue;
        }
        if (!seen.add(lower + "." + asset[2].toLowerCase(Locale.ROOT))) {
          continue;
        }
        PolicyDecision decision;
        try {
          decision =
              decisions.decide(new DecisionService.Ask(principal, asset[0], null, clientIp, key.isEmpty() ? null : key, null));
        } catch (RuntimeException e) {
          // A table nobody could decide about is not shown: the catalog fails closed too.
          LOG.warn("No decision for {} while listing the gateway catalog: {}", asset[0], e.toString());
          continue;
        }
        if (!Boolean.TRUE.equals(decision.getAllowed())) {
          continue;
        }
        Set<String> hidden = new HashSet<>();
        if (decision.getHiddenColumns() != null) {
          for (String h : decision.getHiddenColumns()) {
            hidden.add(h.toLowerCase(Locale.ROOT));
          }
        }
        List<String[]> columns =
            app.withHandle(
                h ->
                    h.createQuery(
                            """
                            SELECT c.name, c.data_type FROM asset_column c
                              JOIN asset a ON a.id = c.asset_id AND a.is_current
                             WHERE a.fqn = :fqn AND c.is_current
                             ORDER BY c.ordinal, c.name
                            """)
                        .bind("fqn", asset[0])
                        .map((rs, ctx) -> new String[] {rs.getString("name"), rs.getString("data_type")})
                        .list());
        columns.removeIf(c -> hidden.contains(c[0].toLowerCase(Locale.ROOT)));
        if (!columns.isEmpty()) {
          out.add(new Visible(schema, asset[2], columns));
        }
      }
      visible = out;
      shapedFor = key;
      shapedAt = now;
      return out;
    }
  }

  /** The DDL that makes a shape: the tables, with the columns, the reader may see. */
  private String shape(List<Visible> tables) {
    StringBuilder sql = new StringBuilder();
    Set<String> schemas = new HashSet<>();
    for (Visible t : tables) {
      if (schemas.add(t.schema())) {
        sql.append("CREATE SCHEMA IF NOT EXISTS ").append(ident(t.schema())).append(";\n");
        sql.append("GRANT USAGE ON SCHEMA ").append(ident(t.schema()))
            .append(" TO ").append(ident(readerRole)).append(";\n");
      }
      sql.append("CREATE TABLE ").append(ident(t.schema())).append('.').append(ident(t.table())).append(" (");
      for (int i = 0; i < t.columns().size(); i++) {
        String[] c = t.columns().get(i);
        if (i > 0) {
          sql.append(", ");
        }
        sql.append(ident(c[0])).append(' ').append(pgType(c[1]));
      }
      sql.append(");\n");
      sql.append("GRANT SELECT ON ").append(ident(t.schema())).append('.').append(ident(t.table()))
          .append(" TO ").append(ident(readerRole)).append(";\n");
    }
    return sql.toString();
  }

  /**
   * The database of a shape, created and committed the first time anyone with
   * that shape asks. Its name is the source's with a hash of the shape, so two
   * people who may see the same tables and columns share one.
   */
  private String shapeDatabase(String sourceName, String shape) throws SQLException {
    // Created only so a tool can find the database it connected to by name.
    ensureDatabase(sourceName);
    String name = shapeName(sourceName, shape);
    if (ready.contains(name)) {
      lastUsed.put(name, System.nanoTime());
      return name;
    }
    boolean made = false;
    try (Connection admin = connect(null)) {
      try (var lock = admin.prepareStatement("SELECT pg_advisory_lock(hashtext(?))")) {
        lock.setString(1, name);
        lock.execute();
      }
      try {
        boolean exists = false;
        boolean complete = false;
        try (var check =
            admin.prepareStatement(
                "SELECT pg_catalog.shobj_description(oid, 'pg_database') FROM pg_catalog.pg_database"
                    + " WHERE datname = ?")) {
          check.setString(1, name);
          try (ResultSet rs = check.executeQuery()) {
            if (rs.next()) {
              exists = true;
              complete = SHAPE_MARK.equals(rs.getString(1));
            }
          }
        }
        if (!exists) {
          try (Statement s = admin.createStatement()) {
            s.execute("CREATE DATABASE " + ident(name));
          }
        }
        if (!complete) {
          // One transaction: a shape is either whole and marked, or absent and made again.
          try (Connection c = connect(name)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
              if (!shape.isEmpty()) {
                s.execute(shape);
              }
              s.execute("COMMENT ON DATABASE " + ident(name) + " IS '" + SHAPE_MARK + "'");
            }
            c.commit();
          }
          made = true;
          LOG.info("Made the gateway catalog shape {} for source {}", name, sourceName);
        }
      } finally {
        try (var unlock = admin.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
          unlock.setString(1, name);
          unlock.execute();
        }
      }
    }
    ready.add(name);
    lastUsed.put(name, System.nanoTime());
    if (made) {
      dropUnused(sourceName, name);
    }
    return name;
  }

  /**
   * Drops this source's other shapes that nobody is connected to and this
   * process has not used lately. One that is in use refuses to be dropped.
   */
  private void dropUnused(String sourceName, String keep) {
    String prefix = shapePrefix(sourceName);
    long now = System.nanoTime();
    try (Connection admin = connect(null)) {
      List<String> names = new ArrayList<>();
      try (var list =
          admin.prepareStatement(
              "SELECT datname FROM pg_catalog.pg_database"
                  + " WHERE pg_catalog.shobj_description(oid, 'pg_database') = ?")) {
        list.setString(1, SHAPE_MARK);
        try (ResultSet rs = list.executeQuery()) {
          while (rs.next()) {
            names.add(rs.getString(1));
          }
        }
      }
      for (String name : names) {
        if (name.equals(keep) || !isShapeOf(name, prefix)) {
          continue;
        }
        Long used = lastUsed.get(name);
        if (used != null && now - used < SHAPE_IDLE_NANOS) {
          continue;
        }
        try (Statement s = admin.createStatement()) {
          s.execute("DROP DATABASE " + ident(name));
          ready.remove(name);
          lastUsed.remove(name);
          LOG.info("Dropped the unused gateway catalog shape {}", name);
        } catch (SQLException e) {
          LOG.debug("Kept the gateway catalog shape {}: {}", name, e.getMessage());
        }
      }
    } catch (SQLException e) {
      LOG.warn("Could not tidy the gateway catalog shapes of {}: {}", sourceName, e.toString());
    }
  }

  /** The source's name cut short enough that a hash still fits PostgreSQL's 63 bytes. */
  static String shapePrefix(String sourceName) {
    String prefix = sourceName;
    while (prefix.getBytes(StandardCharsets.UTF_8).length > 63 - 1 - SHAPE_HASH) {
      prefix = prefix.substring(0, prefix.offsetByCodePoints(prefix.length(), -1));
    }
    return prefix + "~";
  }

  static String shapeName(String sourceName, String shape) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(shape.getBytes(StandardCharsets.UTF_8));
      return shapePrefix(sourceName) + HexFormat.of().formatHex(hash).substring(0, SHAPE_HASH);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  static boolean isShapeOf(String name, String prefix) {
    return name.length() == prefix.length() + SHAPE_HASH
        && name.startsWith(prefix)
        && name.substring(prefix.length()).chars().allMatch(c -> Character.digit(c, 16) >= 0);
  }

  /** A connection to a sandbox database as the builder; {@code null} for the one in the URL. */
  private Connection connect(String database) throws SQLException {
    Properties props = new Properties();
    props.setProperty("user", username);
    props.setProperty("password", password);
    // Every value read back as the server wrote it, so it can be sent on unchanged.
    props.setProperty("binaryTransfer", "false");
    props.setProperty("ApplicationName", "ARAK gateway catalog");
    return DriverManager.getConnection(database == null ? url : urlFor(database), props);
  }

  private static Result read(Connection c, ResultSet rs, int cap) throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    BaseConnection base = c.unwrap(BaseConnection.class);
    int n = md.getColumnCount();
    List<PgWire.Column> columns = new ArrayList<>(n);
    for (int i = 1; i <= n; i++) {
      int oid = base.getTypeInfo().getPGType(md.getColumnTypeName(i));
      if (oid <= 0) {
        oid = PgTypes.TEXT;
      }
      columns.add(new PgWire.Column(md.getColumnLabel(i), oid, PgTypes.typeSize(oid), -1, (short) 0));
    }
    List<String[]> rows = new ArrayList<>();
    boolean capped = false;
    while (rs.next()) {
      if (rows.size() >= cap) {
        capped = true;
        break;
      }
      String[] row = new String[n];
      for (int i = 1; i <= n; i++) {
        row[i - 1] = rs.getString(i);
      }
      rows.add(row);
    }
    return new Result(columns, rows, capped);
  }

  private static void refuseActions(String sql) throws PgError {
    String plain = SqlText.stripQuoted(sql);
    if (UNICODE_ESCAPE.matcher(sql).find()) {
      throw new PgError(
          PgError.FEATURE_NOT_SUPPORTED, "Unicode-escaped names are not accepted by the ARAK gateway");
    }
    Matcher m = ACTIONS.matcher(plain);
    if (m.find()) {
      throw new PgError(
          PgError.INSUFFICIENT_PRIVILEGE,
          "The ARAK gateway is read-only; " + m.group(1) + "() is not available");
    }
  }

  /** The sandbox database for a source, created the first time it is asked for. */
  private String ensureDatabase(String sourceName) throws SQLException {
    String name = sourceName;
    if (RESERVED.contains(name.toLowerCase(Locale.ROOT)) || name.length() > 63) {
      throw new SQLException("A source named " + name + " cannot have a catalog sandbox");
    }
    if (created.contains(name)) {
      return name;
    }
    Properties props = new Properties();
    props.setProperty("user", username);
    props.setProperty("password", password);
    props.setProperty("ApplicationName", "ARAK gateway catalog");
    try (Connection admin = DriverManager.getConnection(url, props);
        var check = admin.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
      check.setString(1, name);
      boolean exists;
      try (ResultSet rs = check.executeQuery()) {
        exists = rs.next();
      }
      if (!exists) {
        try (Statement s = admin.createStatement()) {
          s.execute("CREATE DATABASE " + ident(name));
          LOG.info("Created the gateway catalog sandbox for source {}", name);
        } catch (SQLException e) {
          // Another session may have created it first.
          if (!"42P04".equals(e.getSQLState())) {
            throw e;
          }
        }
      }
    }
    created.add(name);
    return name;
  }

  private String urlFor(String database) {
    Matcher m = URL.matcher(url);
    if (!m.matches()) {
      throw new IllegalStateException("gateway.sandboxJdbcUrl");
    }
    String query = m.group(3) == null ? "" : m.group(3);
    return m.group(1) + "/" + URLEncoder.encode(database, StandardCharsets.UTF_8).replace("+", "%20") + query;
  }

  static String pgType(String catalogType) {
    if (catalogType == null) {
      return "text";
    }
    String t = catalogType.strip().toUpperCase(Locale.ROOT);
    String mapped = TYPES.get(t);
    if (mapped != null) {
      return mapped;
    }
    // A PostgreSQL source's own type names, as the crawl may carry them.
    return switch (t) {
      case "INTEGER", "INT4", "SERIAL" -> "integer";
      case "INT8", "BIGSERIAL" -> "bigint";
      case "INT2", "SMALLSERIAL" -> "smallint";
      case "BOOL" -> "boolean";
      case "FLOAT8", "DOUBLE PRECISION" -> "double precision";
      case "FLOAT4", "REAL" -> "real";
      case "CHARACTER VARYING" -> "varchar";
      default -> "text";
    };
  }

  /** A name as a quoted PostgreSQL identifier. */
  static String ident(String name) {
    return "\"" + name.replace("\u0000", "").replace("\"", "\"\"") + "\"";
  }

  private static PgError fromSandbox(SQLException e) {
    String state = e.getSQLState();
    String message = e.getMessage() == null ? "The catalog question failed" : e.getMessage();
    // pgjdbc prefixes "ERROR: " and may add a position line; the client wants the message only.
    message = message.replaceFirst("^ERROR:\\s*", "");
    int nl = message.indexOf('\n');
    if (nl > 0) {
      message = message.substring(0, nl);
    }
    if (state == null || state.length() != 5 || state.startsWith("08")) {
      return new PgError(PgError.INTERNAL, "The catalog question failed");
    }
    if ("57014".equals(state)) {
      return new PgError(PgError.QUERY_CANCELED, "The catalog question took too long");
    }
    return new PgError(state, message);
  }
}
