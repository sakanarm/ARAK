package com.mfec.dac.source.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Who can read a SQL Server table without the platform, asked of SQL Server.
 *
 * <p>The users are created {@code WITHOUT LOGIN}: what is being read is the
 * database's own permissions, and those do not care how the user connects.
 */
@Testcontainers
class DirectAccessReaderSqlServerIT {

  @Container
  private static final MSSQLServerContainer<?> MSSQL =
      new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

  @BeforeAll
  static void seed() throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(MSSQL.getJdbcUrl(), MSSQL.getUsername(), MSSQL.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE itdb");
    }
    run(
        "CREATE SCHEMA sales",
        "CREATE TABLE sales.customer (id int PRIMARY KEY, email nvarchar(100))",
        "CREATE TABLE sales.other (id int)",
        // The object granted.
        "CREATE USER reader WITHOUT LOGIN",
        "GRANT SELECT ON sales.customer TO reader",
        // The schema granted to a role, and the user in it.
        "CREATE ROLE analysts",
        "GRANT SELECT ON SCHEMA::sales TO analysts",
        "CREATE USER ann WITHOUT LOGIN",
        "ALTER ROLE analysts ADD MEMBER ann",
        // One column.
        "CREATE USER colpeek WITHOUT LOGIN",
        "GRANT SELECT ON sales.customer (email) TO colpeek",
        // The fixed reader role, and one member of it denied this table.
        "CREATE USER bulkread WITHOUT LOGIN",
        "ALTER ROLE db_datareader ADD MEMBER bulkread",
        "CREATE USER denied WITHOUT LOGIN",
        "ALTER ROLE db_datareader ADD MEMBER denied",
        "DENY SELECT ON sales.customer TO denied",
        // Granted something else only.
        "CREATE USER outsider WITHOUT LOGIN",
        "GRANT SELECT ON sales.other TO outsider");
  }

  @Test
  @DisplayName("names every way in to a table, and leaves out whoever is denied it")
  void namesEveryWayIn() throws SQLException {
    DirectAccessReader.Report report = read("sales", "customer");

    assertThat(report.found()).isTrue();
    Map<String, DirectAccessReader.Holder> by = byName(report);
    assertThat(by).containsKeys("reader", "analysts", "colpeek", "bulkread", "dbo", "sa");
    assertThat(by).doesNotContainKeys("denied", "outsider", "sys", "INFORMATION_SCHEMA");
    assertThat(by.get("reader").via()).containsExactly("GRANT");
    assertThat(by.get("analysts").via()).containsExactly("SCHEMA");
    assertThat(by.get("analysts").login()).isFalse();
    assertThat(by.get("analysts").members()).containsExactly("ann");
    assertThat(by.get("colpeek").via()).containsExactly("COLUMN");
    assertThat(by.get("bulkread").via()).containsExactly("ROLE db_datareader");
    assertThat(by.get("sa").via()).containsExactly("SYSADMIN");
  }

  @Test
  @DisplayName("the platform's own login and database user are marked as itself")
  void marksItself() throws SQLException {
    DirectAccessReader.Report report = read("sales", "customer");

    Map<String, DirectAccessReader.Holder> by = byName(report);
    assertThat(report.connectedAs()).isEqualTo("dbo");
    assertThat(by.get("dbo").self()).isTrue();
    assertThat(by.get("sa").self()).isTrue();
    assertThat(by.get("reader").self()).isFalse();
  }

  @Test
  @DisplayName("a table that is not there is said to be not there")
  void missingTable() throws SQLException {
    assertThat(read("sales", "nope").found()).isFalse();
  }

  private static DirectAccessReader.Report read(String schema, String table) throws SQLException {
    try (Connection connection = connect()) {
      return DirectAccessReader.read(connection, "SQLSERVER", schema, table);
    }
  }

  private static Map<String, DirectAccessReader.Holder> byName(DirectAccessReader.Report report) {
    return report.holders().stream()
        .collect(Collectors.toMap(DirectAccessReader.Holder::name, holder -> holder));
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        MSSQL.getJdbcUrl() + ";databaseName=itdb", MSSQL.getUsername(), MSSQL.getPassword());
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
