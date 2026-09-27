package com.mfec.dac.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.audit.QueryRefusals.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * One message per category, copied from where the proxy writes it
 * ({@code QueryRewriter}, {@code ProxyCapabilities}, {@code QueryService}). A
 * reworded message fails here instead of quietly becoming OTHER in the log.
 */
class QueryRefusalsTest {

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      // The cost refusal has an apostrophe in it, which is CsvSource's quote.
      quoteCharacter = '"',
      value = {
        "POLICY_DENY|Access to demo-pg.salesdb.sales.customer is denied. finance-subscription: subject rule not satisfied",
        "POLICY_DENY|Access to demo-pg.salesdb.sales.customer is denied by policy",
        "UNGOVERNED|sales.audit_trail is not a governed asset on this source, so no policy could be applied to it",
        "CANNOT_ENFORCE|Policy on demo-pg.salesdb.sales.customer needs a HASH mask, which the query proxy cannot express on PostgreSQL. Enforce this asset through a secure view instead.",
        "ALL_COLUMNS_HIDDEN|Every column of demo-pg.salesdb.sales.customer is hidden from this principal",
        "UNPARSEABLE|This statement could not be parsed, so it cannot be enforced and will not be run",
        "NOT_READ_ONLY|Only SELECT is allowed through the query proxy; this deployment is read-only (FR-6.3)",
        "UNQUALIFIED|Qualify customer with its schema: the proxy resolves a table to an asset by name, and an unqualified one could be either of two tables",
        "UNSUPPORTED|The query proxy can only read plain tables and subqueries, not generate_series(1, 3)",
        "UNSUPPORTED|This form of SELECT is not supported by the query proxy",
        "UNSUPPORTED|sales.customer is read from a position the proxy does not rewrite — a subquery inside an expression, most likely — so no policy could be placed in front of it. Rewrite it as a join and try again",
        "UNSUPPORTED|The tables this statement reads could not be listed, so it cannot be enforced",
        "SOURCE_UNAVAILABLE|No data source 3f2b1c9e-0000-4000-8000-000000000001",
        "SOURCE_UNAVAILABLE|demo-pg is disabled",
        "EMPTY|No SQL was sent",
        "TOO_COSTLY|The source's planner estimates this statement at a cost of 48,210,555, over the ceiling of 10,000,000 set for POSTGRES sources, so it was not run. A WHERE on an indexed column, fewer joins or less to sort usually brings it under.",
        "BUSY|Too many of your queries are running right now: ARAK runs 2 at a time for one person. Let one finish and run this again.",
        "BUSY|Too many queries are running against demo-pg right now: ARAK sends it at most 4 at a time, so that the source is not overloaded. Try again in a few seconds.",
        "BUSY|Too many queries are running on ARAK right now: it runs at most 16 at a time. Try again in a few seconds.",
        "OTHER|Something nobody has written yet",
      })
  void refusals(Category expected, String reason) {
    assertThat(QueryRefusals.categorize("REJECTED", reason)).isEqualTo(expected);
  }

  @Test
  @DisplayName("a statement that ran has no category, and one the source refused is a source error")
  void outcomes() {
    assertThat(QueryRefusals.categorize("EXECUTED", null)).isNull();
    assertThat(QueryRefusals.categorize("FAILED", "ERROR: relation does not exist"))
        .isEqualTo(Category.SOURCE_ERROR);
  }

  @Test
  @DisplayName("a refusal with no reason recorded is OTHER, not a crash")
  void noReason() {
    assertThat(QueryRefusals.categorize("REJECTED", null)).isEqualTo(Category.OTHER);
  }
}
