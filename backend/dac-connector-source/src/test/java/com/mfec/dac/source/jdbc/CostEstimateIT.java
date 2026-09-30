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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The planner estimate the cost guard reads, asked of PostgreSQL (FR-6.3).
 *
 * <p>The figures are the engine's and move between versions, so none is
 * pinned. What is pinned is the shape the guard relies on: an ordinary lookup
 * is priced low, a join with nothing to join on far higher, the row cap brings
 * a plain {@code SELECT *} down with it, and a planner that cannot be asked
 * leaves the connection as usable as it found it.
 */
@Testcontainers
class CostEstimateIT {

  private static final String CROSS =
      "SELECT a.id, b.id AS other FROM sales.orders a CROSS JOIN sales.orders b"
          + " ORDER BY a.note, b.note";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @BeforeAll
  static void seed() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA sales");
      statement.execute(
          "CREATE TABLE sales.orders AS"
              + " SELECT g AS id, md5(g::text) AS note FROM generate_series(1, 50000) g");
      statement.execute("ALTER TABLE sales.orders ADD PRIMARY KEY (id)");
      statement.execute("ANALYZE sales.orders");
    }
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @Test
  @DisplayName("a lookup is priced low and a cross join sorted over both sides far higher")
  void pricesByWork() throws SQLException {
    try (Connection connection = connect()) {
      CostEstimate.Price lookup =
          CostEstimate.price(
              connection, "POSTGRES", "SELECT id, note FROM sales.orders WHERE id = 7", 201, 30);
      CostEstimate.Price cross = CostEstimate.price(connection, "POSTGRES", CROSS, 201, 30);

      assertThat(lookup.cost()).isNotNull().isPositive().isLessThan(100);
      assertThat(lookup.unpricedBecause()).isNull();
      assertThat(cross.cost()).isNotNull().isGreaterThan(lookup.cost() * 10_000);
    }
  }

  @Test
  @DisplayName("the row cap is part of the price, so reading the first rows of a big table is cheap")
  void rowCapCounts() throws SQLException {
    try (Connection connection = connect()) {
      CostEstimate.Price capped =
          CostEstimate.price(connection, "POSTGRES", "SELECT * FROM sales.orders;", 201, 30);
      CostEstimate.Price whole =
          CostEstimate.price(connection, "POSTGRES", "SELECT * FROM sales.orders", 50_000, 30);

      assertThat(capped.cost()).isNotNull().isLessThan(whole.cost() / 20);
    }
  }

  @Test
  @DisplayName("a statement with its own LIMIT and a trailing comment still price")
  void wrapsAnyStatement() throws SQLException {
    try (Connection connection = connect()) {
      CostEstimate.Price price =
          CostEstimate.price(
              connection,
              "POSTGRES",
              "WITH o AS (SELECT id FROM sales.orders) SELECT id FROM o ORDER BY id LIMIT 5 -- five",
              201,
              30);

      assertThat(price.cost()).isNotNull().isPositive();
    }
  }

  @Test
  @DisplayName("a planner that refuses leaves no figure, and the connection still runs statements")
  void unpricedLeavesConnectionUsable() throws SQLException {
    try (Connection connection = connect()) {
      CostEstimate.Price price =
          CostEstimate.price(connection, "POSTGRES", "SELECT * FROM sales.missing", 201, 30);

      assertThat(price.cost()).isNull();
      assertThat(price.unpricedBecause()).contains("missing");
      try (Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery("SELECT count(*) FROM sales.orders")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isEqualTo(50_000);
      }
    }
  }

  @Test
  @DisplayName("an engine without a known estimate is not priced, and says so")
  void otherEngine() throws SQLException {
    CostEstimate.Price price = CostEstimate.price(null, "ORACLE", "SELECT 1", 201, 30);

    assertThat(price.cost()).isNull();
    assertThat(price.unpricedBecause()).contains("ORACLE");
  }
}
