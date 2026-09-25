package com.mfec.dac.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.llm.AssistPrompts.Column;
import com.mfec.dac.llm.AssistPrompts.Table;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two promises the assistant rests on, tested without a gateway.
 *
 * <p>This is why the prompt layer is a pile of pure functions. The promises —
 * <em>metadata only, never rows</em> and <em>a suggestion, never an act</em> —
 * are the sort of thing that is easy to state in a design document and easy to
 * lose in a refactor, and neither is worth anything if checking it needs a key,
 * a network and somebody's credit card.
 *
 * <p>The read-only cases are written as attempts rather than as examples. A
 * statement arriving beside a Run button is a suggestion the console is making,
 * and the interesting question is not whether an obvious {@code DROP TABLE}
 * gets through but whether one dressed up as a query does.
 */
@DisplayName("AssistPrompts")
class AssistPromptsTest {

  private static Table customer() {
    return new Table(
        "prod-pg.SalesDB.dbo.customer",
        "One row per customer",
        List.of(
            new Column("id", "bigint", null, List.of()),
            new Column("email", "varchar", "Contact address", List.of("PII.Sensitive")),
            new Column("branch_code", "varchar", null, List.of())));
  }

  private static Table order() {
    return new Table(
        "prod-pg.SalesDB.dbo.sales_order",
        "Orders placed",
        List.of(
            new Column("id", "bigint", null, List.of()),
            new Column("customer_id", "bigint", null, List.of()),
            new Column("total", "numeric", null, List.of())));
  }

  @Nested
  @DisplayName("the schema brief")
  class Brief {

    @Test
    @DisplayName("names tables, columns, types and tags")
    void carriesTheShape() {
      String brief = AssistPrompts.schemaBrief(List.of(customer()));

      assertThat(brief)
          .contains("prod-pg.SalesDB.dbo.customer")
          .contains("email varchar")
          .contains("[PII.Sensitive]")
          .contains("One row per customer");
    }

    @Test
    @DisplayName("is built from metadata only, so a row cannot reach the gateway")
    void carriesNoValues() {
      // The strongest form this can take without a database on the other end:
      // the function's whole input is the catalogue record, and there is no
      // parameter a value could arrive through. What is asserted here is the
      // consequence — nothing in the output that was not a name, a type, a tag
      // or a description. If somebody ever adds a sample-values field to the
      // brief, this is the test that should stop them.
      String brief = AssistPrompts.schemaBrief(List.of(customer(), order()));

      for (String line : brief.split("\n")) {
        assertThat(line).doesNotContain("@").doesNotContain("'");
      }
      assertThat(brief).doesNotContain("=");
    }

    @Test
    @DisplayName("flattens a description that spans lines")
    void flattensDescriptions() {
      Table wordy =
          new Table(
              "s.d.t",
              "First line.\n\n  Second line.",
              List.of(new Column("c", "int", "a\nb", List.of())));

      assertThat(AssistPrompts.schemaBrief(List.of(wordy)))
          .contains("-- First line. Second line.")
          .contains("-- a b");
    }
  }

  @Nested
  @DisplayName("the SQL instruction")
  class SqlSystem {

    @Test
    @DisplayName("names the engine it is writing for")
    void namesTheEngine() {
      assertThat(AssistPrompts.sqlSystem("PostgreSQL")).contains("PostgreSQL");
      assertThat(AssistPrompts.sqlSystem(null)).contains("SQL database");
    }

    @Test
    @DisplayName("tells the model not to mask anything itself")
    void leavesMaskingToTheProxy() {
      // Without this the model does the polite thing and hashes the column it
      // can see is tagged, and the proxy then masks the hash -- twice-masked
      // output that looks like a platform bug.
      assertThat(AssistPrompts.sqlSystem("PostgreSQL"))
          .contains("Do not try to mask, hash or redact anything yourself");
    }

    @Test
    @DisplayName("gives the model a way to say no")
    void offersARefusal() {
      assertThat(AssistPrompts.sqlSystem("PostgreSQL")).contains("UNANSWERABLE");
    }
  }

  @Nested
  @DisplayName("the policy instruction")
  class PolicySystem {

    @Test
    @DisplayName("hands over the schema verbatim")
    void carriesTheSchema() {
      String schema = "{\"title\":\"Policy\",\"required\":[\"name\"]}";

      assertThat(AssistPrompts.policySystem(schema)).contains(schema);
    }

    @Test
    @DisplayName("forces the draft state, because the assistant never activates")
    void draftsOnly() {
      // FR-2.6, separation of duty. The endpoint forces this again on the way
      // out; this is the first of the two, and the one that keeps the model
      // from confidently handing back something marked ACTIVE.
      assertThat(AssistPrompts.policySystem("{}"))
          .contains("\"lifecycleState\": \"DRAFT\"")
          .contains("you never activate anything");
    }

    @Test
    @DisplayName("omits the table brief when there is none")
    void briefIsOptional() {
      assertThat(AssistPrompts.policyUser("mask PII for everyone below L2", ""))
          .isEqualTo("Draft a policy for: mask PII for everyone below L2");
    }
  }

  @Nested
  @DisplayName("reading the answer back")
  class Extract {

    @Test
    @DisplayName("takes the statement out of a fence")
    void stripsAFence() {
      String answer = "```sql\nSELECT id FROM customer\n```";

      assertThat(AssistPrompts.extractSql(answer)).isEqualTo("SELECT id FROM customer");
    }

    @Test
    @DisplayName("drops a sentence the model opened with")
    void dropsPreamble() {
      String answer = "Sure! Here is the query you asked for:\nSELECT id FROM customer";

      assertThat(AssistPrompts.extractSql(answer)).isEqualTo("SELECT id FROM customer");
    }

    @Test
    @DisplayName("keeps a bare statement as it is")
    void keepsABareStatement() {
      assertThat(AssistPrompts.extractSql("WITH x AS (SELECT 1) SELECT * FROM x"))
          .isEqualTo("WITH x AS (SELECT 1) SELECT * FROM x");
    }

    @Test
    @DisplayName("survives an empty or absent answer")
    void survivesNothing() {
      assertThat(AssistPrompts.extractSql(null)).isEmpty();
      assertThat(AssistPrompts.extractSql("   ")).isEmpty();
      assertThat(AssistPrompts.extractJson(null)).isEmpty();
      assertThat(AssistPrompts.extractJson("I could not do that")).isEmpty();
    }

    @Test
    @DisplayName("takes the object out of a fenced answer")
    void stripsAJsonFence() {
      String answer = "```json\n{\"name\": \"mask-pii\"}\n```";

      assertThat(AssistPrompts.extractJson(answer)).isEqualTo("{\"name\": \"mask-pii\"}");
    }

    @Test
    @DisplayName("takes the outermost object, nested braces and all")
    void takesTheOutermostObject() {
      String answer = "Here: {\"a\": {\"b\": 1}} -- hope that helps";

      assertThat(AssistPrompts.extractJson(answer)).isEqualTo("{\"a\": {\"b\": 1}}");
    }

    @Test
    @DisplayName("recognises a refusal")
    void recognisesARefusal() {
      assertThat(AssistPrompts.isRefusal("UNANSWERABLE")).isTrue();
      assertThat(AssistPrompts.isRefusal("unanswerable.")).isTrue();
      assertThat(AssistPrompts.isRefusal("SELECT 1")).isFalse();
    }
  }

  @Nested
  @DisplayName("the read-only check")
  class ReadOnly {

    @Test
    @DisplayName("accepts an ordinary query")
    void acceptsAQuery() {
      assertThat(AssistPrompts.looksReadOnly("SELECT id, email FROM customer WHERE id = 1"))
          .isTrue();
    }

    @Test
    @DisplayName("accepts a CTE and a leading parenthesis")
    void acceptsACte() {
      assertThat(AssistPrompts.looksReadOnly("WITH recent AS (SELECT 1) SELECT * FROM recent"))
          .isTrue();
      assertThat(AssistPrompts.looksReadOnly("(SELECT 1) UNION ALL (SELECT 2)")).isTrue();
    }

    @Test
    @DisplayName("accepts one trailing semicolon, which every SQL tool writes")
    void acceptsOneSemicolon() {
      assertThat(AssistPrompts.looksReadOnly("SELECT 1;")).isTrue();
      assertThat(AssistPrompts.looksReadOnly("SELECT 1;\n")).isTrue();
    }

    @Test
    @DisplayName("refuses a second statement hiding behind the first")
    void refusesASecondStatement() {
      assertThat(AssistPrompts.looksReadOnly("SELECT 1; DROP TABLE customer")).isFalse();
      assertThat(AssistPrompts.looksReadOnly("SELECT 1;\nDELETE FROM customer")).isFalse();
    }

    @Test
    @DisplayName("refuses anything that writes, however it opens")
    void refusesWrites() {
      assertThat(AssistPrompts.looksReadOnly("UPDATE customer SET email = 'x'")).isFalse();
      assertThat(AssistPrompts.looksReadOnly("DELETE FROM customer")).isFalse();
      assertThat(AssistPrompts.looksReadOnly("CREATE VIEW v AS SELECT 1")).isFalse();
      assertThat(AssistPrompts.looksReadOnly("GRANT SELECT ON customer TO analyst")).isFalse();
      assertThat(AssistPrompts.looksReadOnly("SELECT * INTO copy FROM customer")).isFalse();
      assertThat(AssistPrompts.looksReadOnly("")).isFalse();
      assertThat(AssistPrompts.looksReadOnly("   ")).isFalse();
    }

    @Test
    @DisplayName("is not fooled by a keyword inside a string literal")
    void ignoresLiterals() {
      // The point of blanking literals first. Refusing this would be a
      // perfectly safe query the console silently declined to offer, which is
      // the failure mode nobody reports and everybody works around.
      assertThat(AssistPrompts.looksReadOnly("SELECT id FROM note WHERE body = 'please update'"))
          .isTrue();
      assertThat(AssistPrompts.looksReadOnly("SELECT 'drop table customer' AS warning")).isTrue();
    }

    @Test
    @DisplayName("is not fooled by a keyword inside a comment")
    void ignoresComments() {
      assertThat(AssistPrompts.looksReadOnly("SELECT id FROM customer -- drop this later"))
          .isTrue();
      assertThat(AssistPrompts.looksReadOnly("/* delete me */ SELECT id FROM customer")).isTrue();
    }

    @Test
    @DisplayName("is not fooled by a keyword that is only part of an identifier")
    void ignoresIdentifiers() {
      assertThat(AssistPrompts.looksReadOnly("SELECT created_at, updated_by FROM customer"))
          .isTrue();
      assertThat(AssistPrompts.looksReadOnly("SELECT id FROM customer ORDER BY created_at"))
          .isTrue();
    }

    @Test
    @DisplayName("refuses a write hidden behind a comment that never closes")
    void refusesAnUnclosedComment() {
      // /* with no */ swallows the rest of the statement, so the check must not
      // read what follows as SQL it has cleared.
      assertThat(AssistPrompts.looksReadOnly("/* SELECT 1 DROP TABLE customer")).isFalse();
    }
  }

  @Nested
  @DisplayName("picking tables for the prompt")
  class Relevance {

    @Test
    @DisplayName("puts the table the question names first")
    void namesWin() {
      List<Table> picked =
          AssistPrompts.mostRelevant(List.of(order(), customer()), "how many customers?", 2);

      assertThat(picked.get(0).fqn()).isEqualTo("prod-pg.SalesDB.dbo.customer");
    }

    @Test
    @DisplayName("finds the singular table from a plural question")
    void singularises() {
      List<Table> picked =
          AssistPrompts.mostRelevant(List.of(customer(), order()), "list the orders", 1);

      assertThat(picked).singleElement().extracting(Table::fqn).isEqualTo(
          "prod-pg.SalesDB.dbo.sales_order");
    }

    @Test
    @DisplayName("counts a column the question names")
    void columnsCount() {
      List<Table> picked =
          AssistPrompts.mostRelevant(List.of(order(), customer()), "who has an email", 2);

      assertThat(picked.get(0).fqn()).isEqualTo("prod-pg.SalesDB.dbo.customer");
    }

    @Test
    @DisplayName("keeps catalogue order when nothing matches, and honours the limit")
    void stableOnTies() {
      List<Table> picked =
          AssistPrompts.mostRelevant(List.of(order(), customer()), "xyzzy", 5);

      assertThat(picked).extracting(Table::fqn)
          .containsExactly("prod-pg.SalesDB.dbo.sales_order", "prod-pg.SalesDB.dbo.customer");
      assertThat(AssistPrompts.mostRelevant(List.of(order(), customer()), "xyzzy", 1)).hasSize(1);
    }
  }

  @Nested
  @DisplayName("fix and explain (M26)")
  class FixAndExplain {

    private static final String SQL =
        "SELECT id, emial FROM prod-pg.SalesDB.dbo.customer WHERE branch_code = 'BKK-01'";

    private String brief() {
      return AssistPrompts.schemaBrief(List.of(customer()));
    }

    @Test
    @DisplayName("redacts a value PostgreSQL quotes back")
    void redactsPostgresValue() {
      String out =
          AssistPrompts.redactError(
              "ERROR: invalid input syntax for type integer: \"4111-1111\"", SQL, brief());
      assertThat(out).isEqualTo("ERROR: invalid input syntax for type integer: \"…\"");
    }

    @Test
    @DisplayName("redacts a value SQL Server quotes back")
    void redactsSqlServerValue() {
      String out =
          AssistPrompts.redactError(
              "Conversion failed when converting the varchar value 'A-9921' to data type int.",
              SQL,
              brief());
      assertThat(out)
          .isEqualTo("Conversion failed when converting the varchar value '…' to data type int.");
    }

    @Test
    @DisplayName("keeps names and literals that are already in the statement or the catalogue")
    void keepsKnownTokens() {
      String out =
          AssistPrompts.redactError(
              "column \"emial\" does not exist; did you mean \"email\"? near 'BKK-01'",
              SQL,
              brief());
      assertThat(out)
          .contains("\"emial\"")
          .contains("\"email\"")
          .contains("'BKK-01'");
    }

    @Test
    @DisplayName("drops the detail lines that echo a row, and a key's value")
    void dropsDetail() {
      String out =
          AssistPrompts.redactError(
              "ERROR: something failed\n  Detail: Failing row contains (7, x@example.test)\n"
                  + "  Where: SQL function \"f\"\n  Position: 9\nKey (id)=(42) is odd",
              SQL,
              brief());
      assertThat(out)
          .startsWith("ERROR: something failed")
          .doesNotContain("Failing row")
          .doesNotContain("x@example.test")
          .doesNotContain("Position")
          .doesNotContain("42")
          .contains("Key (id)=(…)");
    }

    @Test
    @DisplayName("flattens and caps what is left")
    void caps() {
      String out = AssistPrompts.redactError("a\n\n b " + "x".repeat(2000), SQL, brief());
      assertThat(out).doesNotContain("\n").startsWith("a b ");
      assertThat(out.length()).isLessThanOrEqualTo(AssistPrompts.MAX_ERROR);
      assertThat(AssistPrompts.redactError(null, SQL, brief())).isEmpty();
    }

    @Test
    @DisplayName("the fix prompt carries statement, catalogue and error, and forbids working around access")
    void fixPrompt() {
      String user = AssistPrompts.fixUser(SQL, "column \"emial\" does not exist", brief());
      assertThat(user)
          .contains(SQL)
          .contains("prod-pg.SalesDB.dbo.customer")
          .contains("email varchar")
          .contains("column \"emial\" does not exist");
      assertThat(AssistPrompts.fixSystem("PostgreSQL"))
          .contains("PostgreSQL")
          .contains("Change only what the error is about")
          .contains("do not swap a table for another")
          .contains("UNANSWERABLE");
    }

    @Test
    @DisplayName("the explain prompt carries the statement, and says no data was seen")
    void explainPrompt() {
      assertThat(AssistPrompts.explainUser(SQL, brief()))
          .contains(SQL)
          .contains("prod-pg.SalesDB.dbo.customer");
      assertThat(AssistPrompts.explainUser(SQL, "")).startsWith("Statement:");
      assertThat(AssistPrompts.explainSystem(null))
          .contains("SQL database")
          .contains("You have not seen any data");
    }

    @Test
    @DisplayName("an explanation is trimmed and capped")
    void explanation() {
      assertThat(AssistPrompts.extractExplanation("  Reads customers.  ")).isEqualTo("Reads customers.");
      assertThat(AssistPrompts.extractExplanation(null)).isEmpty();
      assertThat(AssistPrompts.extractExplanation("y".repeat(10_000)).length())
          .isLessThanOrEqualTo(AssistPrompts.MAX_EXPLANATION);
    }

    @Test
    @DisplayName("the same statement is recognised through spacing, case and a semicolon")
    void sameStatement() {
      assertThat(AssistPrompts.sameStatement("SELECT  id\nFROM t;", "select id from t")).isTrue();
      assertThat(AssistPrompts.sameStatement("SELECT id FROM t", "SELECT id FROM u")).isFalse();
    }
  }
}
