package com.mfec.dac.common.engine;

import java.util.Set;

/** Microsoft SQL Server. */
public final class SqlServerEngine implements SourceEngine {

  @Override
  public String id() {
    return "SQLSERVER";
  }

  @Override
  public String displayName() {
    return "SQL Server";
  }

  @Override
  public int defaultPort() {
    return 1433;
  }

  @Override
  public boolean supportsSchemas() {
    return true;
  }

  @Override
  public String driverClassName() {
    return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
  }

  /**
   * {@inheritDoc}
   *
   * <p>{@code encrypt=true} has been the driver default since version 10 and
   * {@code trustServerCertificate=true} is what makes a self-signed
   * certificate usable. Both are stated rather than left to the driver, so
   * that a driver upgrade cannot silently change whether this platform accepts
   * a certificate — the kind of change that turns every source unreachable on
   * a Tuesday for a reason nobody can find in a diff.
   */
  @Override
  public String jdbcUrl(JdbcCoordinates coordinates) {
    String database = coordinates.databaseOr(null);
    return "jdbc:sqlserver://"
        + coordinates.host()
        + ":"
        + coordinates.port()
        + ";encrypt=true;trustServerCertificate=true"
        + (database == null ? "" : ";databaseName=" + database);
  }

  @Override
  public String dialectId() {
    return "SQLSERVER";
  }

  @Override
  public Set<String> proxyCapabilities() {
    return Set.of(
        Capability.ROW_FILTER,
        Capability.COLUMN_MASK,
        Capability.CELL_MASK,
        Capability.COLUMN_HIDE);
  }
}
