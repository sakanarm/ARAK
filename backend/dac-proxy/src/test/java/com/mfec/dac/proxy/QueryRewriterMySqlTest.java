package com.mfec.dac.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.compiler.sql.MySqlDialect;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rewriter on SQL as MySQL writes it.
 *
 * <p>The gates are the same ones {@code QueryRewriterTest} holds to account on
 * PostgreSQL. What is checked here is what differs: backticks, a database where
 * the other engines have a schema, MySQL's own functions, and the statements
 * only MySQL has that must not get through.
 */
class QueryRewriterMySqlTest {

  private static final String FQN = "demo-mysql.default.sales.customer";

  private static final List<String> COLUMNS =
      List.of("id", "full_name", "email", "citizen_id", "salary", "branch_code");

  private final QueryRewriter rewriter = new QueryRewriter(new MySqlDialect(), null);

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

  private static PolicyDecision restricted() {
    return allowed()
        .withRowPredicates(
            List.of(
                new ResolvedRowPredicate()
                    .withKind(ResolvedRowPredicate.Kind.IN_LIST)
                    .withColumn("branch_code")
                    .withValues(List.<Object>of("BKK-01"))))
        .withColumnMasks(
            List.of(
                new ResolvedColumnMask()
                    .withColumn("citizen_id")
                    .withMasking(
                        new MaskingSpec()
                            .withFunction(MaskingSpec.MaskingFunction.PARTIAL)
                            .withShowLast(4))))
        .withHiddenColumns(List.of("salary"));
  }

  /** The MySQL database {@code sales} is what arrives as the schema. */
  private static QueryRewriter.Governance governing(PolicyDecision decision) {
    return (schema, table) ->
        "sales".equalsIgnoreCase(schema) && "customer".equalsIgnoreCase(table)
            ? new QueryRewriter.Governed(FQN, decision, COLUMNS)
            : null;
  }

  @Test
  void readsBackticksAndWritesTheEnforcedFormInThem() {
    QueryRewriter.Rewritten out =
        rewriter.rewrite("SELECT * FROM `sales`.`customer`", governing(restricted()));

    assertThat(out.assets()).containsExactly(FQN);
    assertThat(out.anyRestriction()).isTrue();
    assertThat(out.sql())
        .contains("FROM `sales`.`customer` `t`")
        .contains("WHERE (`t`.`branch_code` IN ('BKK-01'))")
        .contains("RIGHT(CAST(`t`.`citizen_id` AS CHAR), 4)")
        .doesNotContain("salary")
        // The star has to expand against a table called what the caller called it.
        .endsWith(") `customer`");
  }

  @Test
  void resolvesDatabaseDotTableTheWayTheOtherEnginesResolveSchemaDotTable() {
    QueryRewriter.Rewritten out =
        rewriter.rewrite(
            "SELECT c.id, c.full_name FROM sales.customer c WHERE c.id > 10 LIMIT 5",
            governing(allowed()));
    assertThat(out.assets()).containsExactly(FQN);
    assertThat(out.sql()).endsWith(") c WHERE c.id > 10 LIMIT 5");
  }

  @Test
  void refusesAnUnqualifiedTableBecauseAConnectionSeesEveryDatabase() {
    assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM customer", governing(allowed())))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining("Qualify");
  }

  @Test
  void refusesTheServersOwnTablesLikeAnyOtherTableNobodyGoverns() {
    for (String table :
        List.of("mysql.user", "information_schema.tables", "performance_schema.threads")) {
      assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM " + table, governing(allowed())))
          .as(table)
          .isInstanceOf(QueryRewriter.RefusedException.class)
          .hasMessageContaining("not a governed asset");
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT IFNULL(full_name, ''), IF(id > 1, 'a', 'b') FROM sales.customer",
        "SELECT DATE_FORMAT(NOW(), '%Y-%m'), DATEDIFF(CURDATE(), '2020-01-01') FROM sales.customer",
        "SELECT branch_code, GROUP_CONCAT(full_name SEPARATOR ', ') FROM sales.customer"
            + " GROUP BY branch_code",
        "SELECT SUBSTRING_INDEX(email, '@', -1), CHAR_LENGTH(full_name) FROM sales.customer",
        "SELECT id FROM sales.customer WHERE full_name REGEXP '^A' OR full_name = \"Anan\"",
        "SELECT JSON_EXTRACT('{\"a\": 1}', '$.a') FROM sales.customer"
      })
  void allowsTheFunctionsThatOnlyComputeOverTheirArguments(String sql) {
    assertThatCode(() -> rewriter.rewrite(sql, governing(allowed()))).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SLEEP(5)",
        "BENCHMARK(1000000, MD5('x'))",
        "LOAD_FILE('/etc/passwd')",
        "GET_LOCK('x', 10)",
        "USER()",
        "CURRENT_USER()",
        "DATABASE()",
        "VERSION()",
        "CONNECTION_ID()",
        "sys.version_major()"
      })
  void refusesAFunctionThatWaitsReadsAFileOrReportsOnTheServer(String call) {
    assertThatThrownBy(
            () -> rewriter.rewrite("SELECT " + call + " FROM sales.customer", governing(allowed())))
        .as(call)
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining("query proxy allows");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT * FROM sales.customer INTO OUTFILE '/tmp/x'",
        "SELECT * INTO OUTFILE '/tmp/x' FROM sales.customer",
        "SELECT * INTO DUMPFILE '/tmp/x' FROM sales.customer",
        "SELECT id FROM sales.customer INTO @a",
        "SELECT * FROM sales.customer LOCK IN SHARE MODE",
        "SELECT * FROM sales.customer FOR UPDATE",
        "TABLE sales.customer",
        "HANDLER sales.customer OPEN",
        "SET SESSION sql_mode = ''",
        "SELECT id FROM sales.customer; SET SESSION sql_mode = ''",
        "SELECT @@sql_mode",
        "SELECT id FROM sales.customer WHERE @@version LIKE '8%'"
      })
  void refusesTheStatementsThatWriteLockOrTouchTheSession(String sql) {
    assertThatThrownBy(() -> rewriter.rewrite(sql, governing(allowed())))
        .as(sql)
        .isInstanceOf(QueryRewriter.RefusedException.class);
  }

  @Test
  void dropsAVersionedCommentInsteadOfLettingMySqlRunIt() {
    // MySQL executes what is inside /*! ... */. The parser reads it as a
    // comment, and a comment is not part of what is sent.
    QueryRewriter.Rewritten out =
        rewriter.rewrite(
            "SELECT id /*!50000 , (SELECT token FROM sales.secret LIMIT 1) */ FROM sales.customer",
            governing(allowed()));
    assertThat(out.sql()).doesNotContain("secret").doesNotContain("/*");
  }

  @Test
  void aStringWithABackslashInItGoesThroughAsWritten() {
    // Safe only because the session it runs on reads a backslash as a
    // backslash; MySqlProxyIT runs this very shape against a server to show
    // the table inside the second string is not read.
    QueryRewriter.Rewritten out =
        rewriter.rewrite(
            "SELECT 'a\\'' UNION SELECT token FROM sales.secret -- ' FROM sales.customer",
            governing(allowed()));
    assertThat(out.assets()).containsExactly(FQN);
  }
}
