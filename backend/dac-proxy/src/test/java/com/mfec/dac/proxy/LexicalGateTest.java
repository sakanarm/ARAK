package com.mfec.dac.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.compiler.sql.MySqlDialect;
import com.mfec.dac.compiler.sql.PostgresDialect;
import com.mfec.dac.compiler.sql.SqlDialect;
import com.mfec.dac.compiler.sql.SqlServerDialect;
import com.mfec.dac.schema.api.PolicyDecision;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * That a statement the source would read differently from the parser is not
 * sent.
 *
 * <p>Each statement in the first half reads {@code sales.secret}, a table no
 * policy was put in front of, once the source's own lexer has had its say. The
 * parser sees that table only as the inside of a string or of a name, so the
 * first two gates find nothing to refuse. Two of them were run by hand against
 * PostgreSQL 16 as the rewriter used to send them, and returned the row.
 */
class LexicalGateTest {

  private static final List<String> COLUMNS = List.of("id", "full_name");

  private static final QueryRewriter.Governance ONLY_CUSTOMER =
      (schema, table) ->
          "sales".equalsIgnoreCase(schema) && "customer".equalsIgnoreCase(table)
              ? new QueryRewriter.Governed(
                  "demo.salesdb.sales.customer",
                  new PolicyDecision()
                      .withPrincipal("analyst_a")
                      .withAssetFqn("demo.salesdb.sales.customer")
                      .withAllowed(true)
                      .withRowPredicates(List.of())
                      .withColumnMasks(List.of())
                      .withHiddenColumns(List.of())
                      .withReasons(List.of()),
                  COLUMNS)
              : null;

  private static QueryRewriter on(SqlDialect dialect) {
    return new QueryRewriter(dialect, null);
  }

  private static void refused(SqlDialect dialect, String sql, String saying) {
    assertThatThrownBy(() -> on(dialect).rewrite(sql, ONLY_CUSTOMER))
        .as(dialect.name() + ": " + sql)
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining(saying);
  }

  // ------------------------------------------ a table carried past the parser

  @Test
  void refusesAnEscapeStringWhoseBackslashThePostgresServerWouldHonour() {
    // To the server E'a\'' is the string a'. To the parser it is 'a\' and the
    // start of a second string, which swallows the secret table.
    refused(
        new PostgresDialect(),
        "SELECT E'a\\'' , token FROM sales.secret -- ' FROM sales.customer",
        "prefix");
  }

  @Test
  void refusesAHintBecauseTheServerNestsCommentsAndTheParserDoesNot() {
    // PostgreSQL and SQL Server close this comment at the second */, which is
    // inside what the parser took for a string; what follows it is theirs to run.
    String sql =
        "SELECT /*+ /* */ id, ' */ 1, token FROM sales.secret -- ' FROM sales.customer";
    refused(new PostgresDialect(), sql, "hints and comments");
    refused(new SqlServerDialect(), sql, "hints and comments");
    refused(new MySqlDialect(), sql, "hints and comments");
  }

  @Test
  void refusesAHashOnMySqlWhereItHidesTheRestOfTheLine() {
    // The parser reads #a as a column alias. MySQL reads it as a comment to the
    // end of the line, and the string on the next line as SQL.
    refused(
        new MySqlDialect(),
        "SELECT 1 #a, '\n , token FROM sales.secret -- ' FROM sales.customer",
        "# starts a comment");
    refused(new MySqlDialect(), "SELECT id #> 'x' FROM sales.customer", "# starts a comment");
  }

  @Test
  void refusesAHintThatCouldChangeTheSessionOnMySql() {
    refused(
        new MySqlDialect(),
        "SELECT /*+ SET_VAR(sql_mode = '') */ id FROM sales.customer",
        "hints and comments");
  }

  @Test
  void refusesABacktickWhereItIsNotAQuote() {
    // A backtick quotes a name for the parser whatever the engine. PostgreSQL
    // and SQL Server do not take it that way, so the quote inside it is theirs.
    String sql = "SELECT 1 AS `x'` , ' , token FROM sales.secret -- ' FROM sales.customer";
    refused(new PostgresDialect(), sql, "backtick");
    refused(new SqlServerDialect(), sql, "backtick");
  }

  @Test
  void refusesADollarOnPostgresWhereItCanOpenAString() {
    refused(new PostgresDialect(), "SELECT $tag$ FROM sales.customer", "dollar-quoted");
    refused(new PostgresDialect(), "SELECT id AS total$ FROM sales.customer", "dollar-quoted");
  }

  @Test
  void refusesACharacterSetIntroducerAndEveryOtherPrefix() {
    refused(new PostgresDialect(), "SELECT e'x' FROM sales.customer", "prefix");
    refused(new MySqlDialect(), "SELECT id FROM sales.customer WHERE full_name = E'x'", "prefix");
  }

  @Test
  void refusesAVariableWhereverItIsWritten() {
    // The first gate catches one in the select list. One in a WHERE, or one
    // being assigned, used to get past it.
    for (SqlDialect dialect : List.of(new MySqlDialect(), new SqlServerDialect())) {
      refused(dialect, "SELECT id FROM sales.customer WHERE @x = 1", "Session variables");
      refused(dialect, "SELECT @x := 1 FROM sales.customer", "Session variables");
    }
  }

  @Test
  void refusesAJdbcEscapeTheDriverWouldRewriteAfterTheCheck() {
    refused(new PostgresDialect(), "SELECT {d '2020-01-01'} FROM sales.customer", "JDBC escapes");
  }

  // ------------------------------------------------ what still goes through

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT id FROM sales.customer WHERE full_name = 'O''Brien'",
        "SELECT id AS \"a\"\"b\" FROM sales.customer",
        "SELECT id FROM sales.customer WHERE full_name = N'กรุงเทพ'",
        "SELECT id FROM sales.customer WHERE full_name LIKE 'a\\_b'",
        "SELECT id, 1 - -1, 2 / 3, -(-id) FROM sales.customer",
        "SELECT id FROM sales.customer WHERE full_name = '-- /* # $ @ { ; \\ `'",
        "SELECT id FROM sales.customer -- a comment the parser drops\n WHERE id = 1",
        "SELECT id /* and this one */ FROM sales.customer"
      })
  void anOrdinaryStatementPassesOnEveryEngine(String sql) {
    for (SqlDialect dialect :
        List.of(new PostgresDialect(), new SqlServerDialect(), new MySqlDialect())) {
      assertThatCode(() -> on(dialect).rewrite(sql, ONLY_CUSTOMER))
          .as(dialect.name() + ": " + sql)
          .doesNotThrowAnyException();
    }
  }

  @Test
  void aCommentTheCallerWroteNeverReachesTheSource() {
    String sent =
        on(new PostgresDialect())
            .rewrite("SELECT id /* note */ FROM sales.customer -- trailing", ONLY_CUSTOMER)
            .sql();
    assertThat(sent).doesNotContain("note").doesNotContain("trailing").doesNotContain("--");
  }

  @Test
  void eachEngineKeepsItsOwnWayOfQuotingAName() {
    assertThatCode(
            () -> on(new MySqlDialect()).rewrite("SELECT `id` FROM `sales`.`customer`", ONLY_CUSTOMER))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                on(new SqlServerDialect())
                    .rewrite("SELECT [id] FROM [sales].[customer] WHERE [id] = 1", ONLY_CUSTOMER))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                on(new PostgresDialect())
                    .rewrite("SELECT \"id\" FROM \"sales\".\"customer\"", ONLY_CUSTOMER))
        .doesNotThrowAnyException();
  }

  @Test
  void postgresKeepsItsOperatorsThatAreVariablesElsewhere() {
    // @> is containment on PostgreSQL and #> a JSON path; neither starts
    // anything there.
    assertThatCode(() -> LexicalGate.check("POSTGRES", "SELECT a @> b, c #> '{x}' FROM t"))
        .doesNotThrowAnyException();
  }

  // ----------------------------------------------------- the reading itself

  @Test
  void readsADoubledQuoteAsTheQuoteItself() {
    assertThatCode(() -> LexicalGate.check("POSTGRES", "SELECT 'it''s -- fine' FROM t"))
        .doesNotThrowAnyException();
    assertThatCode(() -> LexicalGate.check("SQLSERVER", "SELECT [a]]--b] FROM t"))
        .doesNotThrowAnyException();
    assertThatCode(() -> LexicalGate.check("MYSQL", "SELECT `a``#b` FROM t"))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesTextThatEndsInsideAQuote() {
    assertThatThrownBy(() -> LexicalGate.check("POSTGRES", "SELECT 'never closed FROM t"))
        .isInstanceOf(QueryRewriter.RefusedException.class);
    assertThatThrownBy(() -> LexicalGate.check("MYSQL", "SELECT `never closed FROM t"))
        .isInstanceOf(QueryRewriter.RefusedException.class);
  }

  @Test
  void refusesASeparatorAndAStrayBackslash() {
    assertThatThrownBy(() -> LexicalGate.check("POSTGRES", "SELECT 1; SELECT 2"))
        .isInstanceOf(QueryRewriter.RefusedException.class);
    assertThatThrownBy(() -> LexicalGate.check("SQLSERVER", "SELECT 1 \\ 2"))
        .isInstanceOf(QueryRewriter.RefusedException.class);
  }

  @Test
  void holdsAnEngineItHasNotBeenToldAboutToEveryRule() {
    // The strictest reading, so an engine added without a line here is refused
    // more than it should be rather than less.
    for (String sql :
        List.of("SELECT a #b FROM t", "SELECT $x FROM t", "SELECT @x FROM t", "SELECT `a` FROM t")) {
      assertThatThrownBy(() -> LexicalGate.check("ORACLE", sql))
          .as(sql)
          .isInstanceOf(QueryRewriter.RefusedException.class);
    }
  }
}
