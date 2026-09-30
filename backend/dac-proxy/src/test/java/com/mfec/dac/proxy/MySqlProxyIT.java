package com.mfec.dac.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.compiler.sql.MySqlDialect;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.MaskingSpec.MaskingFunction;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.source.jdbc.CredentialResolver;
import com.mfec.dac.source.jdbc.JdbcTargets;
import com.mfec.dac.source.jdbc.SourceProbe;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Mode 5.2 against a real MySQL: what the rewriter writes, run the way the
 * platform runs it.
 *
 * <p>Two things only a server can settle. One is that the masks and filters
 * are SQL MySQL accepts and that they return what the policy says. The other
 * is the one the whole mode rests on: that a statement means to MySQL what it
 * meant to the parser. {@code sales.secret} is a table no policy is in front
 * of; the last tests send statements that reach it on a connection opened any
 * other way, and show that they do not on ours.
 */
@Testcontainers
class MySqlProxyIT {

  private static final String FQN = "demo-mysql.default.sales.customer";
  private static final String SECRET = "SECRET-ROW";
  private static final String READER = "arak_reader";
  private static final String READER_PASSWORD = "reader-it-only";

  private static final List<String> COLUMNS =
      List.of("id", "full_name", "email", "citizen_id", "phone", "salary", "branch_code", "born");

  @Container private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

  @BeforeAll
  static void seed() throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE sales");
      statement.execute(
          "CREATE TABLE sales.customer ("
              + " id INT PRIMARY KEY, full_name VARCHAR(80), email VARCHAR(120),"
              + " citizen_id CHAR(13), phone VARCHAR(20), salary DECIMAL(12, 2),"
              + " branch_code VARCHAR(20), born DATE)");
      // The third branch code holds a real backslash: CHAR(92), so that what is
      // stored does not depend on how this session reads one.
      statement.execute(
          "INSERT INTO sales.customer VALUES"
              + " (1, 'Anan', 'anan@example.test', '1100000000011', '0810000001', 50000,"
              + "  'BKK-01', '1990-05-17'),"
              + " (2, 'Bua', 'bua@example.test', '1100000000022', '0810000002', 61000,"
              + "  'CNX-02', '1985-11-02'),"
              + " (3, 'Chai', NULL, NULL, NULL, 72000, CONCAT('BKK', CHAR(92), '01'), NULL)");
      statement.execute("CREATE TABLE sales.secret (token VARCHAR(40))");
      statement.execute("INSERT INTO sales.secret VALUES ('" + SECRET + "')");
      statement.execute(
          "CREATE USER '" + READER + "'@'%' IDENTIFIED BY '" + READER_PASSWORD + "'");
      // The service account can read the secret table. Nothing but the proxy
      // stands between a caller and it, which is the situation being tested.
      statement.execute("GRANT SELECT ON sales.* TO '" + READER + "'@'%'");
    }
  }

  // ------------------------------------------------------------- fixtures

  private static PolicyDecision allowed() {
    return new PolicyDecision()
        .withPrincipal("analyst_a")
        .withAssetFqn(FQN)
        .withAllowed(true)
        .withRowPredicates(List.of())
        .withColumnMasks(List.of())
        .withHiddenColumns(List.of())
        .withReasons(List.of());
  }

  private static ResolvedRowPredicate branchIn(String... branches) {
    return new ResolvedRowPredicate()
        .withKind(ResolvedRowPredicate.Kind.IN_LIST)
        .withColumn("branch_code")
        .withValues(List.<Object>of((Object[]) branches));
  }

  private static ResolvedColumnMask mask(String column, MaskingSpec spec) {
    return new ResolvedColumnMask().withColumn(column).withMasking(spec);
  }

  private static QueryRewriter.Governance governing(PolicyDecision decision) {
    return (schema, table) ->
        "sales".equalsIgnoreCase(schema) && "customer".equalsIgnoreCase(table)
            ? new QueryRewriter.Governed(FQN, decision, COLUMNS)
            : null;
  }

  private static String rewrite(String sql, PolicyDecision decision) {
    return new QueryRewriter(new MySqlDialect(), null).rewrite(sql, governing(decision)).sql();
  }

  /** A connection as the platform opens one to a source. */
  private static Connection ours() throws SQLException {
    return JdbcTargets.open(
        new SourceProbe.Target(
            "MYSQL", MYSQL.getHost(), MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT), "sales"),
        new CredentialResolver.Credential(READER, READER_PASSWORD),
        10);
  }

  /** The same login on a connection nobody set up, which is MySQL as it comes. */
  private static Connection plain() throws SQLException {
    return DriverManager.getConnection(
        "jdbc:mysql://"
            + MYSQL.getHost()
            + ":"
            + MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT)
            + "/sales",
        READER,
        READER_PASSWORD);
  }

  private static List<Map<String, Object>> rows(Connection connection, String sql)
      throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      ResultSetMetaData meta = rs.getMetaData();
      List<Map<String, Object>> out = new ArrayList<>();
      while (rs.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          row.put(meta.getColumnLabel(i), rs.getObject(i));
        }
        out.add(row);
      }
      return out;
    }
  }

  /** Every value the statement returned, as text; nothing when the server refused it. */
  private static List<String> everythingReturned(Connection connection, String sql) {
    try {
      List<String> out = new ArrayList<>();
      for (Map<String, Object> row : rows(connection, sql)) {
        for (Object value : row.values()) {
          out.add(String.valueOf(value));
        }
      }
      return out;
    } catch (SQLException refusedByTheServer) {
      return List.of();
    }
  }

  private static String sha256(String text) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  // ------------------------------------------------------ what comes back

  @Test
  @DisplayName("an unrestricted decision returns the table as it is")
  void unrestricted() throws SQLException {
    try (Connection connection = ours()) {
      List<Map<String, Object>> rows =
          rows(connection, rewrite("SELECT * FROM sales.customer ORDER BY id", allowed()));

      assertThat(rows).hasSize(3);
      assertThat(rows.get(0).keySet()).containsExactlyElementsOf(COLUMNS);
      assertThat(rows.get(0)).containsEntry("full_name", "Anan");
    }
  }

  @Test
  @DisplayName("a row filter, four masks and a hidden column are what MySQL returns")
  void restricted() throws Exception {
    PolicyDecision decision =
        allowed()
            .withRowPredicates(List.of(branchIn("BKK-01")))
            .withColumnMasks(
                List.of(
                    mask(
                        "citizen_id",
                        new MaskingSpec().withFunction(MaskingFunction.PARTIAL).withShowLast(4)),
                    mask(
                        "email",
                        new MaskingSpec().withFunction(MaskingFunction.HASH).withSaltRef("s1")),
                    mask(
                        "phone",
                        new MaskingSpec()
                            .withFunction(MaskingFunction.REGEX_REPLACE)
                            .withRegex("\\d")
                            .withReplacement("#")),
                    mask(
                        "born",
                        new MaskingSpec().withFunction(MaskingFunction.ROUNDING).withRoundTo("MONTH")),
                    mask("full_name", new MaskingSpec().withFunction(MaskingFunction.NULLIFY))))
            .withHiddenColumns(List.of("salary"));

    try (Connection connection = ours()) {
      List<Map<String, Object>> rows =
          rows(connection, rewrite("SELECT * FROM `sales`.`customer`", decision));

      assertThat(rows).hasSize(1);
      Map<String, Object> row = rows.get(0);
      assertThat(row).doesNotContainKey("salary");
      assertThat(row.get("id")).isEqualTo(1);
      assertThat(row.get("full_name")).isNull();
      assertThat(row.get("citizen_id")).isEqualTo("*********0011");
      assertThat(row.get("email")).isEqualTo(sha256("anan@example.test" + "s1"));
      // \d reaches the server as \d: a backslash is a backslash on this session.
      assertThat(row.get("phone")).isEqualTo("##########");
      assertThat(String.valueOf(row.get("born"))).isEqualTo("1990-05-01");
    }
  }

  @Test
  @DisplayName("a mask leaves a NULL as NULL rather than masking the absence of a value")
  void masksOverNull() throws SQLException {
    PolicyDecision decision =
        allowed()
            .withRowPredicates(List.of(branchIn("BKK\\01")))
            .withColumnMasks(
                List.of(
                    mask(
                        "citizen_id",
                        new MaskingSpec()
                            .withFunction(MaskingFunction.PARTIAL)
                            .withShowFirst(1)
                            .withShowLast(4)),
                    mask(
                        "born",
                        new MaskingSpec().withFunction(MaskingFunction.ROUNDING).withRoundTo("YEAR"))));

    try (Connection connection = ours()) {
      List<Map<String, Object>> rows =
          rows(connection, rewrite("SELECT id, citizen_id, born FROM sales.customer", decision));

      // The filter value holds a backslash and matched the one row that has it.
      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).get("id")).isEqualTo(3);
      assertThat(rows.get(0).get("citizen_id")).isNull();
      assertThat(rows.get(0).get("born")).isNull();
    }
  }

  @Test
  @DisplayName("the rest of the statement still runs over the enforced table")
  void joinsAndAggregates() throws SQLException {
    PolicyDecision decision = allowed().withRowPredicates(List.of(branchIn("BKK-01", "CNX-02")));
    try (Connection connection = ours()) {
      List<Map<String, Object>> rows =
          rows(
              connection,
              rewrite(
                  "SELECT c.branch_code, COUNT(*) AS n, GROUP_CONCAT(c.full_name) AS who"
                      + " FROM sales.customer c JOIN sales.customer d ON d.id = c.id"
                      + " WHERE c.full_name <> \"Nobody\""
                      + " GROUP BY c.branch_code ORDER BY c.branch_code",
                  decision));

      assertThat(rows).extracting(row -> row.get("branch_code")).containsExactly("BKK-01", "CNX-02");
      assertThat(rows).extracting(row -> row.get("who")).containsExactly("Anan", "Bua");
    }
  }

  // ------------------------------------ what the source reads, and the parser

  /**
   * Each of these reads {@code sales.secret} when MySQL is left to read a
   * backslash in a string as an escape: the parser ends the string one quote
   * sooner than the server does, so it takes the secret table for the inside of
   * the next string and lets the statement through.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT 'a\\'' UNION SELECT token FROM sales.secret -- ' FROM sales.customer",
        "SELECT token, \"a\\\"\" FROM sales.secret -- \" FROM sales.customer"
      })
  @DisplayName("a table hidden behind a backslash is not read on a connection the platform opened")
  void aBackslashDoesNotCarryATablePastThePolicy(String sql) throws SQLException {
    String sent = rewrite(sql, allowed());

    try (Connection connection = ours()) {
      assertThat(everythingReturned(connection, sent)).as(sent).doesNotContain(SECRET);
    }
    // And the reason the session is set up at all: the same text, the same
    // login, on a connection as MySQL hands one out.
    try (Connection connection = plain()) {
      assertThat(everythingReturned(connection, sent)).as(sent).contains(SECRET);
    }
  }

  @Test
  @DisplayName("what the platform's session reads as one string, the parser read as one string")
  void theTwoReadingsAgree() throws SQLException {
    try (Connection connection = ours()) {
      List<Map<String, Object>> rows =
          rows(
              connection,
              rewrite("SELECT 'a\\' AS kept, id FROM sales.customer WHERE id = 1", allowed()));

      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).get("kept")).isEqualTo("a\\");
    }
  }
}
