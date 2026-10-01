package com.mfec.dac.common.engine;

import java.util.List;
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

  /**
   * {@inheritDoc}
   *
   * <p>A backslash in a plain string has been an ordinary character since 9.1,
   * but only by default: a server or a role can still be configured the old
   * way, and then {@code 'a\''} is one string to the server and one and a bit
   * to the parser the proxy checks statements with. Stated here so that what
   * the proxy read is what the server reads, whatever the server's defaults.
   */
  @Override
  public List<String> sessionSetup() {
    return List.of("SET standard_conforming_strings = on");
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
