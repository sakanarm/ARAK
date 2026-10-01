package com.mfec.dac.common.engine;

import java.util.List;
import java.util.Set;

/**
 * MySQL.
 *
 * <p>The first engine here with no schema level: a MySQL database is what the
 * other two call a schema, and one connection reaches every database the login
 * may see. So the database a source names is where unqualified names resolve
 * and what an import reads, not a boundary the server enforces.
 */
public final class MySqlEngine implements SourceEngine {

  @Override
  public String id() {
    return "MYSQL";
  }

  @Override
  public String displayName() {
    return "MySQL";
  }

  @Override
  public int defaultPort() {
    return 3306;
  }

  @Override
  public boolean supportsSchemas() {
    return false;
  }

  @Override
  public String driverClassName() {
    return "com.mysql.cj.jdbc.Driver";
  }

  /**
   * {@inheritDoc}
   *
   * <p>Every property is one the rest of the platform relies on, stated rather
   * than left to the driver's default for the same reason SQL Server's TLS
   * choice is: a driver upgrade must not be able to change it.
   *
   * <ul>
   *   <li>{@code databaseTerm=SCHEMA} makes the driver report a MySQL database
   *       as a schema, which is the level it sits at in an FQN, so the
   *       introspector reads it the way it reads the other engines.
   *   <li>{@code allowMultiQueries=false} and {@code allowLoadLocalInfile=false}:
   *       one statement per round trip, and the server may never ask this
   *       process for a file.
   *   <li>{@code useCursorFetch=true} lets a download of every row be read in
   *       batches. Without it the driver holds the whole result in heap.
   *   <li>{@code zeroDateTimeBehavior=CONVERT_TO_NULL}: a {@code 0000-00-00}
   *       date is not a date Java has, and the default is to fail the whole
   *       page over it.
   *   <li>{@code connectionTimeZone=UTC} is the zone the driver reads a date
   *       and time in. It is the zone {@link #sessionSetup()} puts the session
   *       in; left to its default the driver assumes the server keeps this
   *       process's zone, and a {@code TIMESTAMP} comes back wrong by the
   *       difference between the two.
   *   <li>{@code sslMode=PREFERRED} encrypts wherever the server offers TLS and
   *       still connects where it does not. It does not verify the certificate.
   *   <li>{@code program_name} is what a DBA sees for this connection in
   *       {@code performance_schema.session_connect_attrs}; it is the name
   *       {@code JdbcTargets} announces on the other engines.
   * </ul>
   *
   * <p>With no database named the path is left empty, which MySQL accepts: the
   * session has no default database and every table has to be qualified.
   */
  @Override
  public String jdbcUrl(JdbcCoordinates coordinates) {
    return "jdbc:mysql://"
        + coordinates.host()
        + ":"
        + coordinates.port()
        + "/"
        + coordinates.databaseOr("")
        + "?databaseTerm=SCHEMA"
        + "&allowMultiQueries=false"
        + "&allowLoadLocalInfile=false"
        + "&useCursorFetch=true"
        + "&zeroDateTimeBehavior=CONVERT_TO_NULL"
        + "&characterEncoding=UTF-8"
        + "&connectionTimeZone=UTC"
        + "&sslMode=PREFERRED"
        + "&connectionAttributes=program_name:arak-dac";
  }

  @Override
  public String dialectId() {
    return "MYSQL";
  }

  @Override
  public Set<String> proxyCapabilities() {
    return Set.of(
        Capability.ROW_FILTER,
        Capability.COLUMN_MASK,
        Capability.CELL_MASK,
        Capability.COLUMN_HIDE);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Left to itself MySQL reads a backslash inside a string as an escape, so
   * {@code 'a\''} is one string to it. The parser the proxy checks a statement
   * with reads a backslash as a backslash, so to it that string ended a quote
   * sooner. From there the two disagree about what is a string and what is
   * SQL, and a statement could carry a table the proxy never saw. This mode
   * makes the server read a string the way the parser does. The modes already
   * set are kept.
   *
   * <p>The session is also put in UTC, as an offset, which a server accepts
   * whether or not its time zone tables were ever loaded. A MySQL session
   * otherwise runs in whatever zone the server was started in, which the
   * driver has no dependable way to learn, and it has to know to read a
   * {@code TIMESTAMP} as the moment it records. So on a MySQL source
   * {@code NOW()} is UTC, whatever the server's own clock says.
   */
  @Override
  public List<String> sessionSetup() {
    return List.of(
        "SET SESSION sql_mode = CONCAT_WS(',', NULLIF(@@SESSION.sql_mode, ''),"
            + " 'NO_BACKSLASH_ESCAPES')",
        "SET SESSION time_zone = '+00:00'");
  }

  @Override
  public Set<String> systemSchemas() {
    // information_schema and sys are already left out for every engine.
    return Set.of("mysql", "performance_schema");
  }

  /**
   * {@inheritDoc}
   *
   * <p>Not yet. The view works out who is reading from the database principal,
   * and inside a MySQL view {@code CURRENT_USER()} is the view's definer, not
   * the reader; the mode needs that settled and tested before it is offered.
   */
  @Override
  public boolean supportsSecureViews() {
    return false;
  }
}
