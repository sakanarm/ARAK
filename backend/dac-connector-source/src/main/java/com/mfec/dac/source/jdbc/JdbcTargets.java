package com.mfec.dac.source.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;

/**
 * How this platform connects to a source database, in one place.
 *
 * <p>Three components open connections to sources — the registry probe, the
 * introspector and the query proxy — and they must connect the same way. If the
 * probe says a source is reachable because it trusted a self-signed
 * certificate and the proxy then refuses the same server because it did not,
 * the operator is told a lie by one screen and blamed by another.
 *
 * <p>Every connection this class opens names itself {@code arak-dac}. That name
 * is not decoration: the proxy mode's whole safety argument is that a DBA can
 * tell our traffic apart from a user connecting directly, and
 * {@code pg_stat_activity} / {@code sys.dm_exec_sessions} is where they will
 * look (FR-6.3.1).
 */
public final class JdbcTargets {

  private JdbcTargets() {}

  /** The name every connection from this platform announces itself under. */
  public static final String APPLICATION_NAME = "arak-dac";

  public static String url(SourceProbe.Target target) {
    String database =
        target.database() == null || target.database().isBlank() ? null : target.database();
    return switch (String.valueOf(target.engine()).toUpperCase(Locale.ROOT)) {
      case "POSTGRES" ->
          "jdbc:postgresql://"
              + target.host()
              + ":"
              + target.port()
              + "/"
              + (database == null ? "postgres" : database);
      case "SQLSERVER" ->
          "jdbc:sqlserver://"
              + target.host()
              + ":"
              + target.port()
              + ";encrypt=true;trustServerCertificate=true"
              + (database == null ? "" : ";databaseName=" + database);
      default -> throw new IllegalArgumentException(
          "Phase 1 can only connect to POSTGRES and SQLSERVER, not " + target.engine());
    };
  }

  public static Properties properties(CredentialResolver.Credential credential) {
    Properties properties = new Properties();
    properties.setProperty("user", credential.username());
    properties.setProperty("password", credential.password());
    properties.setProperty("ApplicationName", APPLICATION_NAME);
    properties.setProperty("applicationName", APPLICATION_NAME);
    return properties;
  }

  /**
   * Opens a read-only connection, with autocommit left on.
   *
   * <p>Read-only is asked of the driver as well as of ourselves. The proxy
   * refuses anything that is not a {@code SELECT} before it gets this far, but
   * a second lock on the door costs nothing and covers the case where a future
   * caller forgets the first one.
   */
  public static Connection open(
      SourceProbe.Target target, CredentialResolver.Credential credential, int loginTimeoutSeconds)
      throws SQLException {
    int previous = DriverManager.getLoginTimeout();
    DriverManager.setLoginTimeout(loginTimeoutSeconds);
    try {
      Connection connection = DriverManager.getConnection(url(target), properties(credential));
      try {
        connection.setReadOnly(true);
      } catch (SQLException ignored) {
        // Not every driver honours it; the SELECT-only check upstream is the
        // guarantee, this was the belt.
      }
      return connection;
    } finally {
      DriverManager.setLoginTimeout(previous);
    }
  }
}
