package com.mfec.dac.source.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Opens one connection to a source database and reports what it found.
 *
 * <p>This exists for the moment someone registers a source, and it answers two
 * questions that are worth answering then rather than during an apply.
 *
 * <p>The first is whether the credential works at all. A source that is
 * unreachable looks identical to a working one on the registry screen until
 * something tries to use it, and by then the thing trying to use it is a
 * {@code CREATE VIEW} halfway through protecting a table.
 *
 * <p>The second is the engine version, which is not cosmetic. Column-level
 * {@code GRANT UNMASK} needs SQL Server 2022 or newer; on anything older
 * {@code UNMASK} is database-wide, which means the native-config mode cannot
 * mask a column for one person and not another. The capability matrix has to
 * warn about that before a policy is applied, and it can only do so if the
 * version was read from the server rather than typed in by hand.
 *
 * <p>Read-only: it connects, asks the driver for metadata, and closes. It
 * creates nothing and changes nothing.
 */
public final class SourceProbe {

  /** Where to connect. Mirrors the registry row, without the credential. */
  public record Target(String engine, String host, int port, String database) {}

  /**
   * @param reachable    whether a connection was opened and metadata read
   * @param engineVersion the server's own version string, or null when unreachable
   * @param productName  what the driver calls the product, for the audit trail
   * @param message      a sentence for the operator; the failure reason when not reachable
   * @param millis       how long the attempt took, so a slow source is visible as slow
   */
  public record Result(
      boolean reachable, String engineVersion, String productName, String message, long millis) {}

  /** Seconds to wait before calling a source unreachable. */
  private final int timeoutSeconds;

  private final CredentialResolver credentials;

  public SourceProbe() {
    this(new CredentialResolver(), 5);
  }

  public SourceProbe(CredentialResolver credentials, int timeoutSeconds) {
    this.credentials = credentials;
    this.timeoutSeconds = timeoutSeconds;
  }

  public Result probe(Target target, String credentialRef) {
    long started = System.nanoTime();
    CredentialResolver.Credential credential;
    try {
      credential = credentials.resolve(credentialRef);
    } catch (CredentialResolver.UnresolvableCredentialException e) {
      return new Result(false, null, null, e.getMessage(), elapsed(started));
    }

    String url;
    try {
      url = jdbcUrl(target);
    } catch (IllegalArgumentException e) {
      return new Result(false, null, null, e.getMessage(), elapsed(started));
    }

    Properties properties = new Properties();
    properties.setProperty("user", credential.username());
    properties.setProperty("password", credential.password());
    // Named so a DBA reading sys.dm_exec_sessions or pg_stat_activity can tell
    // who opened this, which matters because the proxy mode's identity rules
    // depend on being able to distinguish our connections from a user's.
    properties.setProperty("ApplicationName", "arak-dac");
    properties.setProperty("applicationName", "arak-dac");

    int previousTimeout = DriverManager.getLoginTimeout();
    DriverManager.setLoginTimeout(timeoutSeconds);
    try (Connection connection = DriverManager.getConnection(url, properties)) {
      DatabaseMetaData metadata = connection.getMetaData();
      String version = metadata.getDatabaseProductVersion();
      String product = metadata.getDatabaseProductName();
      return new Result(
          true,
          version,
          product,
          "Connected to " + product + " " + version + " as " + credential.username(),
          elapsed(started));
    } catch (SQLException e) {
      // The driver's message is kept because it is what distinguishes a wrong
      // password from a closed port from a database that does not exist, and an
      // operator cannot fix the right one without knowing which it was.
      return new Result(false, null, null, "Could not connect: " + e.getMessage(), elapsed(started));
    } finally {
      DriverManager.setLoginTimeout(previousTimeout);
    }
  }

  private static String jdbcUrl(Target target) {
    String database = target.database() == null || target.database().isBlank() ? null : target.database();
    return switch (String.valueOf(target.engine()).toUpperCase(java.util.Locale.ROOT)) {
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
              // encrypt=true is the driver 10+ default and trustServerCertificate
              // is what makes a self-signed certificate usable; stating both makes
              // the choice visible rather than leaving it to a driver upgrade.
              + ";encrypt=true;trustServerCertificate=true"
              + (database == null ? "" : ";databaseName=" + database);
      default -> throw new IllegalArgumentException(
          "Phase 1 can only connect to POSTGRES and SQLSERVER, not " + target.engine());
    };
  }

  private static long elapsed(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }
}
