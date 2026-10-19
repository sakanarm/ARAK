package com.mfec.dac.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.compiler.sql.DatabricksDialect;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rewriter on SQL as Databricks writes it.
 *
 * <p>The gates are the same ones {@code QueryRewriterMySqlTest} holds to
 * account. What is checked here is what differs on Databricks: a catalog in
 * front of the schema, a double quote that is a string, and a backslash that
 * is an escape the source reads and the parser does not.
 */
class QueryRewriterDatabricksTest {

  private static final String FQN = "demo-dbx.main.sales.customer";

  private static final List<String> COLUMNS =
      List.of("id", "full_name", "email", "citizen_id", "salary", "branch_code");

  private final QueryRewriter rewriter = new QueryRewriter(new DatabricksDialect(), null);

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
                    .withValues(List.<Object>of("BKK-01", "O'Hare"))))
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
        .contains("`t`.`branch_code` IN ('BKK-01', concat('O', chr(39), 'Hare'))")
        .contains("right(CAST(`t`.`citizen_id` AS STRING), 4)")
        .doesNotContain("salary")
        .endsWith(") `customer`");
    // What is sent holds nothing that Databricks and the parser would read
    // differently.
    assertThat(out.sql()).doesNotContain("\\").doesNotContain("''").doesNotContain("\"");
  }

  @Test
  void resolvesSchemaDotTable() {
    QueryRewriter.Rewritten out =
        rewriter.rewrite(
            "SELECT c.id, c.full_name FROM sales.customer c WHERE c.id > 10 LIMIT 5",
            governing(allowed()));
    assertThat(out.assets()).containsExactly(FQN);
    assertThat(out.sql()).endsWith(") c WHERE c.id > 10 LIMIT 5");
  }

  @Test
  void refusesAnUnqualifiedTable() {
    assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM customer", governing(allowed())))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining("Qualify");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT * FROM main.sales.customer",
        "SELECT * FROM `other_catalog`.`sales`.`customer`",
        "SELECT * FROM hive_metastore.sales.customer",
        "SELECT c.id FROM main.sales.customer c"
      })
  void refusesAnyNameWithACatalogInFrontOfIt(String sql) {
    // Resolved by schema and table alone, a catalog the caller named would be
    // dropped and the policy of the source's own catalog applied to another's.
    assertThatThrownBy(() -> rewriter.rewrite(sql, governing(allowed())))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining("without a catalog");
  }

  @Test
  void refusesTheWorkspacesOwnTablesLikeAnyOtherTableNobodyGoverns() {
    for (String table : List.of("information_schema.tables", "system.access.audit")) {
      assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM " + table, governing(allowed())))
          .as(table)
          .isInstanceOf(QueryRewriter.RefusedException.class);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT * FROM sales.customer WHERE full_name = \"x\"",
        "SELECT \"id\" FROM sales.customer"
      })
  void refusesADoubleQuoteBecauseItIsAStringToDatabricksAndANameToTheParser(String sql) {
    assertThatThrownBy(() -> rewriter.rewrite(sql, governing(allowed())))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining("double quote");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // Databricks ends the first string at the last quote; the parser ends
        // it at the second, and reads what follows as SQL it may not be.
        "SELECT * FROM sales.customer WHERE full_name = '\\' OR id = 1 OR full_name = '\\''",
        "SELECT * FROM sales.customer WHERE full_name = 'a\\'",
        "SELECT * FROM sales.customer WHERE full_name = '\\u0027'",
        "SELECT * FROM sales.customer WHERE full_name RLIKE '\\\\d+'"
      })
  void refusesABackslashAnywhereEvenInsideAString(String sql) {
    // Refused either way: by the parser where it cannot read the statement,
    // and by the gate, which is shown to say why on its own just below.
    assertThatThrownBy(() -> rewriter.rewrite(sql, governing(allowed())))
        .isInstanceOf(QueryRewriter.RefusedException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT * FROM sales.customer WHERE full_name = '\\' OR id = 1 OR full_name = '\\''",
        "SELECT * FROM sales.customer WHERE full_name = 'a\\\\'",
        "SELECT * FROM sales.customer WHERE full_name RLIKE '\\d+'"
      })
  void theGateNamesTheBackslashWhateverTheParserMakesOfTheRest(String sql) {
    assertThatThrownBy(() -> LexicalGate.check("DATABRICKS", sql))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining("backslash");
    // The other engines are opened so that a backslash is an ordinary
    // character, and are not held to this.
    assertThatCode(() -> LexicalGate.check("POSTGRES", "SELECT 'a\\d' FROM t"))
        .doesNotThrowAnyException();
  }

  @Test
  void theGateRefusesADoubleQuoteOnlyOnDatabricks() {
    assertThatThrownBy(() -> LexicalGate.check("DATABRICKS", "SELECT \"id\" FROM t"))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining("double quote");
    assertThatCode(() -> LexicalGate.check("POSTGRES", "SELECT \"id\" FROM t"))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT /*+ BROADCAST(c) */ * FROM sales.customer c",
        "SELECT * FROM sales.customer; SELECT 1",
        "SELECT * FROM sales.customer WHERE full_name = r'a'",
        "SELECT * FROM sales.customer WHERE id = ${x}",
        "SELECT @x"
      })
  void refusesWhatTheSourceCouldReadAsSomethingElse(String sql) {
    assertThatThrownBy(() -> rewriter.rewrite(sql, governing(allowed())))
        .isInstanceOf(QueryRewriter.RefusedException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT ifnull(full_name, ''), if(id > 1, 'a', 'b') FROM sales.customer",
        "SELECT date_format(now(), 'yyyy-MM'), datediff(current_date(), DATE '2020-01-01')"
            + " FROM sales.customer",
        "SELECT branch_code, collect_list(full_name) FROM sales.customer GROUP BY branch_code",
        "SELECT split(email, '@')[1], char_length(full_name) FROM sales.customer",
        "SELECT id FROM sales.customer WHERE full_name RLIKE '^A' OR full_name = 'Anan'",
        "SELECT id FROM sales.customer WHERE full_name = 'O''Hare'"
      })
  void allowsStatementsThatOnlyComputeOverTheirArguments(String sql) {
    assertThatCode(() -> rewriter.rewrite(sql, governing(allowed()))).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "reflect('java.lang.Runtime', 'getRuntime')",
        "java_method('java.lang.System', 'getenv', 'HOME')",
        "current_user()",
        "is_member('admins')",
        "current_catalog()",
        "input_file_name()",
        "ai_query('endpoint', full_name)",
        "http_request(conn => 'c', method => 'GET', path => '/')",
        "read_files('s3://bucket')"
      })
  void refusesFunctionsThatReachBeyondTheirArguments(String call) {
    assertThatThrownBy(
            () ->
                rewriter.rewrite(
                    "SELECT " + call + " FROM sales.customer", governing(allowed())))
        .isInstanceOf(QueryRewriter.RefusedException.class);
  }
}
