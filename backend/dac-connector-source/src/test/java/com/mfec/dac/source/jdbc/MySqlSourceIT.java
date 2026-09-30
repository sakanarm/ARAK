package com.mfec.dac.source.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Everything this module does to a source, done to a real MySQL.
 *
 * <p>MySQL is the engine whose database is its schema and whose connection
 * sees every database at once, so what is pinned here is mostly that: which
 * databases an import reads, that the server's own are never among them, and
 * that every connection the platform opens reads a backslash in a string the
 * way the proxy's parser does.
 */
@Testcontainers
class MySqlSourceIT {

  private static final String READER = "arak_reader";
  private static final String READER_PASSWORD = "reader-it-only";

  @Container private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

  @BeforeAll
  static void seed() throws SQLException {
    try (Connection connection = asRoot();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE sales_db");
      statement.execute(
          "CREATE TABLE sales_db.orders ("
              + " id INT PRIMARY KEY, note VARCHAR(64) NOT NULL, placed DATE NULL)");
      statement.execute("SET SESSION cte_max_recursion_depth = 100000");
      statement.execute(
          "INSERT INTO sales_db.orders (id, note, placed)"
              + " WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 5000)"
              + " SELECT n, MD5(n), '2024-01-01' FROM seq");
      statement.execute("ANALYZE TABLE sales_db.orders");

      // Written by a session seven hours ahead of UTC, as a server in Bangkok
      // would write it: the TIMESTAMP is midnight UTC, the DATETIME is the
      // clock on the wall and has no zone.
      statement.execute(
          "CREATE TABLE sales_db.moment (id INT PRIMARY KEY, at_ts TIMESTAMP NULL, at_dt DATETIME NULL)");
      statement.execute("SET SESSION time_zone = '+07:00'");
      statement.execute(
          "INSERT INTO sales_db.moment VALUES (1, '2024-01-01 07:00:00', '2024-01-01 07:00:00')");
      statement.execute(
          "CREATE VIEW sales_db.recent AS SELECT id, note FROM sales_db.orders WHERE id > 4000");

      // One character away from sales_db when the underscore is read as a
      // wildcard, which is how the driver reads a schema filter.
      statement.execute("CREATE DATABASE salesXdb");
      statement.execute("CREATE TABLE salesXdb.decoy (id INT PRIMARY KEY)");
      statement.execute("CREATE DATABASE hr");
      statement.execute("CREATE TABLE hr.staff (id INT PRIMARY KEY, full_name VARCHAR(80))");

      statement.execute(
          "CREATE USER '" + READER + "'@'%' IDENTIFIED BY '" + READER_PASSWORD + "'");
      statement.execute("GRANT SELECT ON sales_db.* TO '" + READER + "'@'%'");
      statement.execute("GRANT SELECT ON salesXdb.* TO '" + READER + "'@'%'");
      statement.execute("GRANT SELECT ON hr.* TO '" + READER + "'@'%'");
    }
  }

  private static Connection asRoot() throws SQLException {
    return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
  }

  private static SourceProbe.Target target(String database) {
    return new SourceProbe.Target(
        "MYSQL", MYSQL.getHost(), MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT), database);
  }

  private static final CredentialResolver.Credential READER_LOGIN =
      new CredentialResolver.Credential(READER, READER_PASSWORD);

  private static CredentialResolver resolver() {
    Function<String, String> env =
        name ->
            switch (name) {
              case "IT_MYSQL" -> READER + ":" + READER_PASSWORD;
              case "IT_MYSQL_WRONG" -> READER + ":not-the-password";
              default -> null;
            };
    return new CredentialResolver(env);
  }

  private static String one(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  // ------------------------------------------------------------- the session

  @Test
  @DisplayName("every connection reads a backslash in a string as a backslash, and keeps the server's other modes")
  void backslashIsOrdinary() throws SQLException {
    String before;
    try (Connection plain =
        DriverManager.getConnection(
            "jdbc:mysql://"
                + MYSQL.getHost()
                + ":"
                + MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT)
                + "/sales_db",
            READER,
            READER_PASSWORD)) {
      before = one(plain, "SELECT @@SESSION.sql_mode");
      // What the server does left alone: \n is one character.
      assertThat(one(plain, "SELECT CHAR_LENGTH('\\n')")).isEqualTo("1");
    }
    assertThat(before).isNotBlank().doesNotContain("NO_BACKSLASH_ESCAPES");

    try (Connection connection = JdbcTargets.open(target("sales_db"), READER_LOGIN, 10)) {
      String mode = one(connection, "SELECT @@SESSION.sql_mode");
      assertThat(mode).contains("NO_BACKSLASH_ESCAPES");
      for (String kept : before.split(",")) {
        assertThat(mode).contains(kept);
      }
      assertThat(one(connection, "SELECT CHAR_LENGTH('\\n')")).isEqualTo("2");
    }
  }

  @Test
  @DisplayName("a server that runs with no SQL mode at all still gets the one the proxy needs")
  void emptyServerMode() throws SQLException {
    try (Connection root = asRoot();
        Statement statement = root.createStatement()) {
      String global = one(root, "SELECT @@GLOBAL.sql_mode");
      statement.execute("SET GLOBAL sql_mode = ''");
      try (Connection connection = JdbcTargets.open(target("sales_db"), READER_LOGIN, 10)) {
        // Contains, not equals: the driver adds a mode of its own to a session.
        assertThat(one(connection, "SELECT @@SESSION.sql_mode")).contains("NO_BACKSLASH_ESCAPES");
        assertThat(one(connection, "SELECT CHAR_LENGTH('\\n')")).isEqualTo("2");
      } finally {
        statement.execute("SET GLOBAL sql_mode = '" + global + "'");
      }
    }
  }

  @Test
  @DisplayName("the connection is read-only at the server, whatever the login may do")
  void readOnly() throws SQLException {
    CredentialResolver.Credential root =
        new CredentialResolver.Credential("root", MYSQL.getPassword());
    try (Connection connection = JdbcTargets.open(target("sales_db"), root, 10);
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(
              () -> statement.execute("INSERT INTO sales_db.orders VALUES (9001, 'x', NULL)"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  @DisplayName("a DBA can tell the platform's connections by name")
  void announcesItself() throws SQLException {
    CredentialResolver.Credential root =
        new CredentialResolver.Credential("root", MYSQL.getPassword());
    try (Connection connection = JdbcTargets.open(target("sales_db"), root, 10)) {
      assertThat(
              one(
                  connection,
                  "SELECT ATTR_VALUE FROM performance_schema.session_connect_attrs"
                      + " WHERE PROCESSLIST_ID = CONNECTION_ID() AND ATTR_NAME = 'program_name'"))
          .isEqualTo(JdbcTargets.APPLICATION_NAME);
    }
  }

  // --------------------------------------------------------------- the probe

  @Test
  @DisplayName("the probe says what answered")
  void probeReachable() {
    SourceProbe.Result result =
        new SourceProbe(resolver(), 10).probe(target("sales_db"), "env:IT_MYSQL");

    assertThat(result.reachable()).as(result.message()).isTrue();
    assertThat(result.productName()).isEqualTo("MySQL");
    assertThat(result.engineVersion()).startsWith("8.4");
    assertThat(result.message()).contains(READER);
  }

  @Test
  @DisplayName("the probe connects with no database named")
  void probeWithoutDatabase() {
    assertThat(new SourceProbe(resolver(), 10).probe(target(null), "env:IT_MYSQL").reachable())
        .isTrue();
  }

  @Test
  @DisplayName("a wrong password is reported as unreachable, with the server's reason")
  void probeRefused() {
    SourceProbe.Result result =
        new SourceProbe(resolver(), 10).probe(target("sales_db"), "env:IT_MYSQL_WRONG");

    assertThat(result.reachable()).isFalse();
    assertThat(result.message()).startsWith("Could not connect").contains("Access denied");
  }

  // ------------------------------------------------------------ the catalog

  @Test
  @DisplayName("a source that names a database reads that database, as the schema of its tables")
  void readsTheNamedDatabase() throws Exception {
    List<JdbcIntrospector.Table> tables =
        new JdbcIntrospector(resolver(), 10).tables(target("sales_db"), "env:IT_MYSQL", null);

    assertThat(tables)
        .extracting(JdbcIntrospector.Table::qualified)
        .containsExactlyInAnyOrder("sales_db.orders", "sales_db.recent", "sales_db.moment");

    JdbcIntrospector.Table orders =
        tables.stream().filter(table -> table.name().equals("orders")).findFirst().orElseThrow();
    assertThat(orders.kind()).isEqualTo("TABLE");
    assertThat(orders.columns())
        .extracting(JdbcIntrospector.Column::name)
        .containsExactly("id", "note", "placed");
    assertThat(orders.columns())
        .extracting(JdbcIntrospector.Column::nullable)
        .containsExactly(false, false, true);
    assertThat(orders.columns().get(1).dataType()).isEqualToIgnoringCase("VARCHAR");
    assertThat(orders.columns().get(1).length()).isEqualTo(64);

    assertThat(tables.stream().filter(table -> table.name().equals("recent")).findFirst())
        .get()
        .extracting(JdbcIntrospector.Table::kind)
        .isEqualTo("VIEW");
  }

  @Test
  @DisplayName("a source that names none reads every database but the server's own")
  void readsEveryDatabase() throws Exception {
    List<JdbcIntrospector.Name> names =
        new JdbcIntrospector(resolver(), 10).names(target(null), "env:IT_MYSQL", null);

    assertThat(names)
        .extracting(JdbcIntrospector.Name::qualified)
        .contains("sales_db.orders", "sales_db.recent", "salesXdb.decoy", "hr.staff");
    assertThat(names)
        .extracting(name -> name.schema().toLowerCase())
        .doesNotContain("mysql", "information_schema", "performance_schema", "sys");
  }

  @Test
  @DisplayName("asking for one database reads one, though the driver takes its name as a pattern")
  void oneSchemaIsOneSchema() throws Exception {
    List<JdbcIntrospector.Name> names =
        new JdbcIntrospector(resolver(), 10).names(target(null), "env:IT_MYSQL", "sales_db");

    assertThat(names).extracting(JdbcIntrospector.Name::schema).containsOnly("sales_db");
  }

  @Test
  @DisplayName("a table left out by the caller has none of its columns read")
  void keepsOnlyWhatIsAsked() throws Exception {
    List<JdbcIntrospector.Table> tables =
        new JdbcIntrospector(resolver(), 10)
            .tables(target(null), "env:IT_MYSQL", null, (schema, name) -> schema.equals("hr"));

    assertThat(tables).extracting(JdbcIntrospector.Table::qualified).containsExactly("hr.staff");
    assertThat(tables.get(0).columns())
        .extracting(JdbcIntrospector.Column::name)
        .containsExactly("id", "full_name");
  }

  @Test
  @DisplayName("what a read covers is what the source names, unless a schema is asked for")
  void schemaToRead() {
    assertThat(JdbcIntrospector.schemaToRead(target("sales_db"), null)).isEqualTo("sales_db");
    assertThat(JdbcIntrospector.schemaToRead(target("sales_db"), "hr")).isEqualTo("hr");
    assertThat(JdbcIntrospector.schemaToRead(target(null), null)).isNull();
    // Where a database holds schemas, naming the database narrows nothing.
    assertThat(
            JdbcIntrospector.schemaToRead(
                new SourceProbe.Target("POSTGRES", "db.example.test", 5432, "salesdb"), null))
        .isNull();
  }

  // -------------------------------------------------------------- the reads

  @Test
  @DisplayName("a page stops at the row cap and says it did")
  void pageIsCapped() throws Exception {
    QueryExecutor.Page page =
        new QueryExecutor(resolver(), 10)
            .run(target("sales_db"), "env:IT_MYSQL", "SELECT id, note FROM sales_db.orders", 200, 30);

    assertThat(page.columns()).containsExactly("id", "note");
    assertThat(page.rows()).hasSize(200);
    assertThat(page.truncated()).isTrue();
  }

  @Test
  @DisplayName("a TIMESTAMP comes back as the moment it records, whatever zone this process is in")
  void readsAMomentAsTheMomentItIs() throws Exception {
    java.util.TimeZone before = java.util.TimeZone.getDefault();
    // The zone that used to decide the answer: the driver took the server's
    // clock to be this process's.
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Bangkok"));
    try {
      QueryExecutor.Page page =
          new QueryExecutor(resolver(), 10)
              .run(
                  target("sales_db"),
                  "env:IT_MYSQL",
                  "SELECT at_ts, at_dt, @@SESSION.time_zone FROM sales_db.moment",
                  10,
                  30);

      assertThat(page.rows()).hasSize(1);
      assertThat(page.rows().get(0).get(0)).isEqualTo("2024-01-01T00:00:00Z");
      // A DATETIME has no zone to convert from: it is shown as it was written,
      // and without a Z, since it names no moment.
      assertThat(page.rows().get(0).get(1)).isEqualTo("2024-01-01T07:00");
      assertThat(page.rows().get(0).get(2)).isEqualTo("+00:00");
    } finally {
      java.util.TimeZone.setDefault(before);
    }
  }

  @Test
  @DisplayName("a download reads every row in batches")
  void downloadStreams() throws Exception {
    int rows = 0;
    try (QueryExecutor.Cursor cursor =
        new QueryExecutor(resolver(), 10)
            .open(
                target("sales_db"),
                "env:IT_MYSQL",
                "SELECT id, note FROM sales_db.orders",
                30,
                0,
                500)) {
      while (cursor.next() != null) {
        rows++;
      }
    }
    assertThat(rows).isEqualTo(5000);
  }

  // --------------------------------------------------------------- the price

  @Test
  @DisplayName("a read with a row cap is not priced, because MySQL's figure ignores the cap")
  void cappedReadIsUnpriced() throws SQLException {
    try (Connection connection = JdbcTargets.open(target("sales_db"), READER_LOGIN, 10)) {
      CostEstimate.Price capped =
          CostEstimate.price(connection, "MYSQL", "SELECT * FROM sales_db.orders", 201, 30);

      assertThat(capped.cost()).isNull();
      assertThat(capped.unpricedBecause()).contains("row limit");
    }
  }

  @Test
  @DisplayName("a download is priced by the work it is: a lookup low, a cross join far higher")
  void downloadIsPriced() throws SQLException {
    try (Connection connection = JdbcTargets.open(target("sales_db"), READER_LOGIN, 10)) {
      CostEstimate.Price lookup =
          CostEstimate.price(
              connection, "MYSQL", "SELECT id, note FROM sales_db.orders WHERE id = 7;", 0, 30);
      CostEstimate.Price scan =
          CostEstimate.price(connection, "MYSQL", "SELECT * FROM sales_db.orders", 0, 30);
      CostEstimate.Price cross =
          CostEstimate.price(
              connection,
              "MYSQL",
              "SELECT a.id, b.id AS other FROM sales_db.orders a CROSS JOIN sales_db.orders b",
              0,
              30);

      assertThat(lookup.cost()).isNotNull().isPositive().isLessThan(10);
      assertThat(scan.cost()).isNotNull().isGreaterThan(lookup.cost() * 10);
      assertThat(cross.cost()).isNotNull().isGreaterThan(scan.cost() * 1000);
      // Asking the planner ran nothing and left the connection usable.
      assertThat(one(connection, "SELECT COUNT(*) FROM sales_db.orders")).isEqualTo("5000");
    }
  }

  @Test
  @DisplayName("a statement the planner cannot price is left unpriced with its reason")
  void unpriceable() throws SQLException {
    try (Connection connection = JdbcTargets.open(target("sales_db"), READER_LOGIN, 10)) {
      CostEstimate.Price price =
          CostEstimate.price(connection, "MYSQL", "SELECT * FROM sales_db.no_such_table", 0, 30);

      assertThat(price.cost()).isNull();
      assertThat(price.unpricedBecause()).contains("no_such_table");
    }
  }
}
