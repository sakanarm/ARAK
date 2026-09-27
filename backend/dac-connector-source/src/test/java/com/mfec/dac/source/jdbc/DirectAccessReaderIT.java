package com.mfec.dac.source.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Who can read a Postgres table without the platform, asked of Postgres.
 *
 * <p>Each role below is set up to hold, or to look as though it holds, a way
 * in by exactly one route, so that a missing or extra holder names the query
 * that is wrong.
 */
@Testcontainers
class DirectAccessReaderIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @BeforeAll
  static void seed() throws SQLException {
    run(
        "CREATE SCHEMA sales",
        "CREATE TABLE sales.customer (id int PRIMARY KEY, email text, branch_code text)",
        "CREATE TABLE sales.hidden (id int)",
        "CREATE TABLE public.open_t (id int)",
        // A login with the table granted, and the schema.
        "CREATE ROLE reader LOGIN PASSWORD 'it-only-reader'",
        "GRANT USAGE ON SCHEMA sales TO reader",
        "GRANT SELECT ON sales.customer TO reader",
        // A group role nobody logs in as, and the login that inherits it.
        "CREATE ROLE analysts NOLOGIN",
        "GRANT USAGE ON SCHEMA sales TO analysts",
        "GRANT SELECT ON sales.customer TO analysts",
        "CREATE ROLE ann LOGIN PASSWORD 'it-only-ann' IN ROLE analysts",
        // One column is still a way in.
        "CREATE ROLE colpeek LOGIN PASSWORD 'it-only-colpeek'",
        "GRANT USAGE ON SCHEMA sales TO colpeek",
        "GRANT SELECT (email) ON sales.customer TO colpeek",
        // The grant without the schema opens nothing.
        "CREATE ROLE noschema LOGIN PASSWORD 'it-only-noschema'",
        "GRANT SELECT ON sales.customer TO noschema",
        // Reads everything by role, PG 14+.
        "CREATE ROLE bulk LOGIN PASSWORD 'it-only-bulk' IN ROLE pg_read_all_data",
        "CREATE ROLE outsider LOGIN PASSWORD 'it-only-outsider'",
        // PUBLIC: open where PUBLIC has the schema, shut where it has not.
        "GRANT SELECT ON public.open_t TO PUBLIC",
        "GRANT SELECT ON sales.hidden TO PUBLIC");
  }

  @Test
  @DisplayName("names every way in to a table, and only those")
  void namesEveryWayIn() throws SQLException {
    DirectAccessReader.Report report = read("sales", "customer");

    assertThat(report.found()).isTrue();
    Map<String, DirectAccessReader.Holder> by = byName(report);
    assertThat(by.keySet())
        .containsExactlyInAnyOrder("analysts", "bulk", "colpeek", "reader", POSTGRES.getUsername());
    assertThat(by.get("reader").via()).containsExactly("GRANT");
    assertThat(by.get("reader").login()).isTrue();
    assertThat(by.get("colpeek").via()).containsExactly("COLUMN");
    assertThat(by.get("bulk").via()).containsExactly("READ_ALL_DATA");
    assertThat(by.get("analysts").login()).isFalse();
    assertThat(by.get("analysts").members()).containsExactly("ann");
    assertThat(by.get("analysts").memberCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("the login the platform connects as is marked, and listed last")
  void marksItself() throws SQLException {
    DirectAccessReader.Report report = read("sales", "customer");

    assertThat(report.connectedAs()).isEqualTo(POSTGRES.getUsername());
    DirectAccessReader.Holder last = report.holders().get(report.holders().size() - 1);
    assertThat(last.name()).isEqualTo(POSTGRES.getUsername());
    assertThat(last.self()).isTrue();
    assertThat(last.via()).contains("OWNER", "SUPERUSER");
    assertThat(report.holders().stream().filter(DirectAccessReader.Holder::self)).hasSize(1);
  }

  @Test
  @DisplayName("PUBLIC counts only where PUBLIC also has the schema")
  void publicNeedsTheSchema() throws SQLException {
    assertThat(byName(read("public", "open_t"))).containsKey("PUBLIC");
    assertThat(byName(read("sales", "hidden"))).doesNotContainKey("PUBLIC");
  }

  @Test
  @DisplayName("names match the way the proxy matches them, ignoring case")
  void ignoresCase() throws SQLException {
    assertThat(byName(read("SALES", "Customer"))).containsKey("reader");
  }

  @Test
  @DisplayName("a table that is not there is said to be not there")
  void missingTable() throws SQLException {
    DirectAccessReader.Report report = read("sales", "nope");

    assertThat(report.found()).isFalse();
    assertThat(report.holders()).isEmpty();
  }

  @Test
  @DisplayName("through a resolved credential, on a read-only connection")
  void throughTheTarget() throws Exception {
    SourceProbe.Target target =
        new SourceProbe.Target(
            "POSTGRES",
            POSTGRES.getHost(),
            POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
            POSTGRES.getDatabaseName());
    Function<String, String> env =
        name ->
            "IT_SRC".equals(name) ? POSTGRES.getUsername() + ":" + POSTGRES.getPassword() : null;
    DirectAccessReader reader = new DirectAccessReader(new CredentialResolver(env), 10);

    DirectAccessReader.Report report = reader.read(target, "env:IT_SRC", "sales", "customer");

    assertThat(byName(report)).containsKey("reader");
  }

  private static DirectAccessReader.Report read(String schema, String table) throws SQLException {
    try (Connection connection = connect()) {
      return DirectAccessReader.read(connection, "POSTGRES", schema, table);
    }
  }

  private static Map<String, DirectAccessReader.Holder> byName(DirectAccessReader.Report report) {
    return report.holders().stream()
        .collect(Collectors.toMap(DirectAccessReader.Holder::name, holder -> holder));
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void run(String... statements) throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      for (String sql : List.of(statements)) {
        statement.execute(sql);
      }
    }
  }
}
