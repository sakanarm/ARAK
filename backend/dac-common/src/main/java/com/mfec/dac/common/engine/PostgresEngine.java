package com.mfec.dac.common.engine;

import java.util.Set;

/** PostgreSQL. */
public final class PostgresEngine implements SourceEngine {

  @Override
  public String id() {
    return "POSTGRES";
  }

  @Override
  public String displayName() {
    return "PostgreSQL";
  }

  @Override
  public int defaultPort() {
    return 5432;
  }

  @Override
  public boolean supportsSchemas() {
    return true;
  }

  @Override
  public String driverClassName() {
    return "org.postgresql.Driver";
  }

  /**
   * {@inheritDoc}
   *
   * <p>Falls back to the {@code postgres} database because a Postgres URL
   * without one is not a connection to "the server" — it is a connection to a
   * database named after the user, which usually does not exist. The fallback
   * only matters when probing a host before anyone has said which database to
   * use.
   */
  @Override
  public String jdbcUrl(JdbcCoordinates coordinates) {
    return "jdbc:postgresql://"
        + coordinates.host()
        + ":"
        + coordinates.port()
        + "/"
        + coordinates.databaseOr("postgres");
  }

  @Override
  public String dialectId() {
    return "POSTGRES";
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
