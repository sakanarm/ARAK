package com.mfec.dac.source.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The planner estimate the cost guard reads, asked of SQL Server (FR-6.3):
 * the same shape as {@link CostEstimateIT}, plus what only this engine can get
 * wrong -- showplan mode and the row count left switched on for the statement
 * that follows.
 */
@Testcontainers
class CostEstimateSqlServerIT {

  private static final String CROSS =
      "SELECT a.id, b.id AS other FROM sales.orders a CROSS JOIN sales.orders b"
          + " ORDER BY a.note, b.note";

  @Container
  private static final MSSQLServerContainer<?> MSSQL =
      new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

  @BeforeAll
  static void seed() throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                MSSQL.getJdbcUrl(), MSSQL.getUsername(), MSSQL.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE itdb");
    }
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA sales");
      statement.execute(
          "CREATE TABLE sales.orders (id int PRIMARY KEY, note nvarchar(40))");
      statement.execute(
          "WITH n AS (SELECT TOP (50000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS g"
              + " FROM sys.all_objects a CROSS JOIN sys.all_objects b)"
              + " INSERT INTO sales.orders SELECT g, CONVERT(nvarchar(40), NEWID()) FROM n");
      statement.execute("UPDATE STATISTICS sales.orders");
      // Somebody allowed to read but not to see plans: the common service
      // account.
      statement.execute("CREATE USER noplan WITHOUT LOGIN");
      statement.execute("GRANT SELECT ON SCHEMA::sales TO noplan");
    }
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        MSSQL.getJdbcUrl() + ";databaseName=itdb", MSSQL.getUsername(), MSSQL.getPassword());
  }

  @Test
  @DisplayName("a lookup is priced low and a cross join sorted over both sides far higher")
  void pricesByWork() throws SQLException {
    try (Connection connection = connect()) {
      CostEstimate.Price lookup =
          CostEstimate.price(
              connection, "SQLSERVER", "SELECT id, note FROM sales.orders WHERE id = 7", 201, 30);
      CostEstimate.Price cross = CostEstimate.price(connection, "SQLSERVER", CROSS, 201, 30);

      assertThat(lookup.cost()).isNotNull().isPositive().isLessThan(1);
      assertThat(cross.cost()).isNotNull().isGreaterThan(lookup.cost() * 1_000);
    }
  }

  @Test
  @DisplayName("the row cap is part of the price, so reading the first rows of a big table is cheap")
  void rowCapCounts() throws SQLException {
    try (Connection connection = connect()) {
      CostEstimate.Price capped =
          CostEstimate.price(connection, "SQLSERVER", "SELECT * FROM sales.orders", 201, 30);
      CostEstimate.Price whole =
          CostEstimate.price(connection, "SQLSERVER", "SELECT * FROM sales.orders", 50_000, 30);

      assertThat(capped.cost()).isNotNull().isLessThan(whole.cost());
    }
  }

  @Test
  @DisplayName("afterwards the connection runs statements again rather than returning plans")
  void showplanSwitchedOff() throws SQLException {
    try (Connection connection = connect()) {
      CostEstimate.price(connection, "SQLSERVER", CROSS, 201, 30);
      CostEstimate.price(connection, "SQLSERVER", "SELECT * FROM sales.missing", 201, 30);

      try (Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery("SELECT count(*) FROM sales.orders")) {
        assertThat(rs.next()).isTrue();
        // ROWCOUNT was put back too: a capped count would still say 50000,
        // so read the rows themselves.
        assertThat(rs.getLong(1)).isEqualTo(50_000);
      }
      try (Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery("SELECT id FROM sales.orders")) {
        int rows = 0;
        while (rs.next()) {
          rows++;
        }
        assertThat(rows).isEqualTo(50_000);
      }
    }
  }

  @Test
  @DisplayName("an account without SHOWPLAN gets no figure, and can still read")
  void withoutShowplan() throws SQLException {
    try (Connection connection = connect()) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("EXECUTE AS USER = 'noplan'");
      }
      CostEstimate.Price price =
          CostEstimate.price(connection, "SQLSERVER", "SELECT id FROM sales.orders", 201, 30);

      assertThat(price.cost()).isNull();
      assertThat(price.unpricedBecause()).containsIgnoringCase("showplan");
      try (Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery("SELECT TOP (3) id FROM sales.orders")) {
        assertThat(rs.next()).isTrue();
      }
      try (Statement statement = connection.createStatement()) {
        statement.execute("REVERT");
      }
    }
  }
}
