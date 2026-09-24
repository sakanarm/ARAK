package com.mfec.dac.common.engine;

import java.util.Set;

/**
 * Everything that is true of one kind of source database, in one place.
 *
 * <p>Before this existed the same knowledge was spelled out in three switch
 * statements and an enum, and the switches were the dangerous part: adding an
 * engine and forgetting one of them compiles perfectly and fails at runtime,
 * with a message that says {@code "Phase 1 can only connect to POSTGRES and
 * SQLSERVER"} when the truth is that we simply did not finish. A lie in an
 * error message costs more than the outage, because it sends whoever reads it
 * to look at the source instead of at us.
 *
 * <p>So the rule this interface exists to enforce is: <b>adding an engine is
 * adding one registry entry.</b> Anything an engine can differ in belongs here
 * or is named from here, and {@code SourceEngines.all()} makes it possible for
 * a test to walk every engine and prove nothing was left half-added.
 *
 * <h2>Why there is no {@code dialect()} method</h2>
 *
 * <p>An engine names its dialect ({@link #dialectId()}) rather than holding
 * one. Building the connection string cannot require the SQL compiler on the
 * classpath — the module that opens sockets has no business depending on the
 * module that writes {@code CASE} expressions, and one day a target may have
 * no SQL dialect at all (a document store, a REST source), at which point
 * {@code dialect()} would have been a method every implementation had to
 * refuse. The binding from id to dialect is checked by a conformance test
 * instead, which turns "forgot to register the dialect" from a production
 * problem into a red build.
 *
 * <h2>Why capabilities are split</h2>
 *
 * <p>{@link #proxyCapabilities()} is deliberately <i>not</i> read from the
 * {@code engine_capability} table. That table describes what the database
 * engine itself can do, which is the right home for native and secure-view
 * limits: whether Postgres can mask a column without an extension is a fact
 * about Postgres. But in proxy mode nothing is asked of the engine — we
 * rewrite the query before it is sent — so the only question is whether
 * <i>our</i> rewriter can express the treatment. That is a fact about this
 * codebase, it changes when this codebase changes, and data in a table that
 * describes code is data that goes stale silently.
 */
public interface SourceEngine {

  /**
   * The stable identifier, uppercase, as it is stored in {@code data_source.engine}.
   *
   * <p>This value reaches the database, the REST API and the browser, so it is
   * a name that cannot be changed without a migration — not a display string.
   */
  String id();

  /** What to call this engine on screen. */
  String displayName();

  /** The port to offer when the operator has not typed one. */
  int defaultPort();

  /**
   * Whether this engine has a schema level between the database and the table.
   *
   * <p>Not cosmetic and not a formatting question: MySQL's "database" and
   * "schema" are the same object, so an FQN built for it has one fewer level
   * than one built for Postgres. Anything that parses or assembles an FQN has
   * to ask rather than assume, which is why this is on the interface before an
   * engine that answers {@code false} exists.
   */
  boolean supportsSchemas();

  /** The JDBC driver class, named so a missing driver fails with our sentence rather than the driver's. */
  String driverClassName();

  /** The JDBC URL for these coordinates. */
  String jdbcUrl(JdbcCoordinates coordinates);

  /**
   * The id of the SQL dialect this engine is written in.
   *
   * <p>Several engines can share one — anything that speaks T-SQL answers
   * {@code SQLSERVER} — which is the other reason this is an id and not an
   * object.
   */
  String dialectId();

  /**
   * The treatments the proxy can express when rewriting a query for this engine.
   *
   * <p>Read fail-closed: a treatment that is not in this set is a query the
   * proxy must refuse, not a query it may run unmasked. The names match the
   * {@code capability} column of {@code engine_capability} so the two can be
   * compared, but this set is the one the proxy obeys.
   */
  Set<String> proxyCapabilities();

  /** Coordinates of one database on one host; the credential is deliberately not here. */
  record JdbcCoordinates(String host, int port, String database) {

    public JdbcCoordinates {
      if (host == null || host.isBlank()) {
        throw new IllegalArgumentException("A source needs a host.");
      }
      if (port <= 0 || port > 65535) {
        throw new IllegalArgumentException("Port " + port + " is not a port.");
      }
      database = database == null || database.isBlank() ? null : database.trim();
    }

    /** The database name, or {@code fallback} when the operator named none. */
    public String databaseOr(String fallback) {
      return database == null ? fallback : database;
    }
  }

  /** The treatment names used by {@link #proxyCapabilities()}. */
  final class Capability {
    private Capability() {}

    public static final String ROW_FILTER = "ROW_FILTER";
    public static final String COLUMN_MASK = "COLUMN_MASK";
    public static final String CELL_MASK = "CELL_MASK";
    public static final String COLUMN_HIDE = "COLUMN_HIDE";
  }
}
