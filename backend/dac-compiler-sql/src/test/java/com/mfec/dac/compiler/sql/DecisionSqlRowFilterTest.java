package com.mfec.dac.compiler.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedLookup;
import com.mfec.dac.schema.api.ResolvedLookupKey;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.api.Unenforceable;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a row filter compiles to — in particular, that every value the
 * principal holds reaches the WHERE clause.
 *
 * <p>The bug these cover: a principal with two {@code branch} values saw the
 * rows of the first one only, because the compiler defaulted to {@code EQ} and
 * {@code EQ} reads a single value. It looked correct in the generated SQL and
 * was wrong in the row count, which is the worst shape a defect can take here.
 */
class DecisionSqlRowFilterTest {

  private final DecisionSql compiler = new DecisionSql(new PostgresDialect());
  private final List<Unenforceable> unenforceable = new ArrayList<>();

  private String where(ResolvedRowPredicate predicate) {
    return compiler.where(
        new PolicyDecision().withRowPredicates(List.of(predicate)), "t", unenforceable);
  }

  private static ResolvedRowPredicate filter(ResolvedRowPredicate.Kind kind, Object... values) {
    return new ResolvedRowPredicate()
        .withKind(kind)
        .withColumn("branch_code")
        .withValues(List.of(values));
  }

  @Test
  void anInListWithNoOperatorBecomesAnInListAndNotAnEquals() {
    // The policy document that caused this: {"kind":"IN_LIST",
    // "column":"branch_code","userAttribute":"branch"} — no operator at all.
    assertThat(where(filter(ResolvedRowPredicate.Kind.IN_LIST, "BKK-01", "CNX-01")))
        .isEqualTo("(\"t\".\"branch_code\" IN ('BKK-01', 'CNX-01'))");
  }

  @Test
  void aSingleValuedInListStillReadsAsAnInList() {
    assertThat(where(filter(ResolvedRowPredicate.Kind.IN_LIST, "BKK-01")))
        .isEqualTo("(\"t\".\"branch_code\" IN ('BKK-01'))");
  }

  @Test
  void aNumberReadFromANumericColumnIsWrittenInFullAndNeverAsAnExponent() {
    assertThat(
            where(
                filter(
                    ResolvedRowPredicate.Kind.IN_LIST,
                    new java.math.BigDecimal("1E-7"),
                    new java.math.BigDecimal("1E+3"),
                    7L)))
        .isEqualTo("(\"t\".\"branch_code\" IN (0.0000001, 1000, 7))");
  }

  @Test
  void anAttributeCompareWithOneValueIsStillAnEquals() {
    assertThat(where(filter(ResolvedRowPredicate.Kind.ATTRIBUTE_COMPARE, "BKK-01")))
        .isEqualTo("(\"t\".\"branch_code\" = 'BKK-01')");
  }

  @Test
  void anEqualsHandedSeveralValuesWidensRatherThanDroppingAllButTheFirst() {
    assertThat(
            where(
                filter(ResolvedRowPredicate.Kind.ATTRIBUTE_COMPARE, "BKK-01", "CNX-01")
                    .withOperator(ResolvedRowPredicate.FacetOperator.EQ)))
        .isEqualTo("(\"t\".\"branch_code\" IN ('BKK-01', 'CNX-01'))");
  }

  @Test
  void aNotEqualsHandedSeveralValuesExcludesAllOfThem() {
    assertThat(
            where(
                filter(ResolvedRowPredicate.Kind.ATTRIBUTE_COMPARE, "BKK-01", "CNX-01")
                    .withOperator(ResolvedRowPredicate.FacetOperator.NE)))
        .isEqualTo("(\"t\".\"branch_code\" NOT IN ('BKK-01', 'CNX-01'))");
  }

  @Test
  void anExplicitOperatorThatIsNotAComparisonIsLeftAlone() {
    assertThat(
            where(
                filter(ResolvedRowPredicate.Kind.ATTRIBUTE_COMPARE, "BKK")
                    .withOperator(ResolvedRowPredicate.FacetOperator.STARTS_WITH)))
        .contains("LIKE");
  }

  @Test
  void aPrincipalWithNoValueForTheAttributeSeesNothingAndIsToldWhy() {
    String sql =
        compiler.where(
            new PolicyDecision()
                .withRowPredicates(
                    List.of(
                        new ResolvedRowPredicate()
                            .withKind(ResolvedRowPredicate.Kind.IN_LIST)
                            .withColumn("branch_code")
                            .withValues(List.of()))),
            "t",
            unenforceable);

    assertThat(sql).isEqualTo("(1 = 0)");
    assertThat(unenforceable).hasSize(1);
  }

  private static ResolvedRowPredicate lookup(ResolvedLookup lookup) {
    return new ResolvedRowPredicate()
        .withKind(ResolvedRowPredicate.Kind.LOOKUP)
        .withColumn("division")
        .withOperator(ResolvedRowPredicate.FacetOperator.IN)
        .withLookup(lookup);
  }

  private static ResolvedLookup bound(ResolvedLookupKey... keys) {
    return new ResolvedLookup()
        .withTable("warehouse.sales.ref.department_division")
        .withSchemaName("ref")
        .withTableName("department_division")
        .withValueColumn("division")
        .withMode(ResolvedLookup.Mode.SUBQUERY)
        .withKeys(List.of(keys));
  }

  private static ResolvedLookupKey key(String column, String... values) {
    return new ResolvedLookupKey()
        .withColumn(column)
        .withUserAttribute(column)
        .withValues(List.of(values));
  }

  @Test
  void aBoundLookupReadsTheMappingForThePrincipalsOwnValues() {
    assertThat(where(lookup(bound(key("department", "AA")))))
        .isEqualTo(
            "(\"t\".\"division\" IN (SELECT \"arak_lookup\".\"division\""
                + " FROM \"ref\".\"department_division\" \"arak_lookup\""
                + " WHERE \"arak_lookup\".\"department\" IN ('AA')"
                + " AND \"arak_lookup\".\"division\" IS NOT NULL))");
    assertThat(unenforceable).isEmpty();
  }

  @Test
  void everyKeyMustMatchAndEveryValueOfAKeyIsLookedUp() {
    assertThat(where(lookup(bound(key("department", "AA", "AB"), key("region", "North")))))
        .contains("\"arak_lookup\".\"department\" IN ('AA', 'AB')")
        .contains(" AND \"arak_lookup\".\"region\" IN ('North')")
        .endsWith("\"arak_lookup\".\"division\" IS NOT NULL))");
  }

  @Test
  void aValueFromTheDirectoryIsALiteralAndNeverSql() {
    assertThat(where(lookup(bound(key("department", "AA') OR (1 = 1")))))
        .contains("IN ('AA'') OR (1 = 1')");
  }

  @Test
  void onSqlServerTheLookupUsesItsQuoting() {
    String sql =
        new DecisionSql(new SqlServerDialect())
            .where(
                new PolicyDecision().withRowPredicates(List.of(lookup(bound(key("department", "AA"))))),
                "t",
                unenforceable);

    assertThat(sql)
        .isEqualTo(
            "([t].[division] IN (SELECT [arak_lookup].[division]"
                + " FROM [ref].[department_division] [arak_lookup]"
                + " WHERE [arak_lookup].[department] IN (N'AA')"
                + " AND [arak_lookup].[division] IS NOT NULL))");
  }

  @Test
  void anOuterAliasThatClashesWithTheLookupsGivesTheLookupAnother() {
    String sql =
        compiler.where(
            new PolicyDecision().withRowPredicates(List.of(lookup(bound(key("department", "AA"))))),
            "arak_lookup",
            unenforceable);

    assertThat(sql)
        .startsWith("(\"arak_lookup\".\"division\" IN (SELECT \"arak_lookup2\".\"division\"")
        .contains("\"department_division\" \"arak_lookup2\" WHERE \"arak_lookup2\".\"department\"");
  }

  @Test
  void aLookupTheProxyDidNotBindIsNoRowsAndSaysSo() {
    List<ResolvedLookup> unbound =
        List.of(
            bound(key("department", "AA")).withSchemaName(null),
            bound(key("department", "AA")).withTableName(" "),
            bound(key("department", "AA")).withMode(ResolvedLookup.Mode.READ_VALUES));

    for (ResolvedLookup lookup : unbound) {
      unenforceable.clear();
      assertThat(where(lookup(lookup))).isEqualTo("(1 = 0)");
      assertThat(unenforceable).hasSize(1);
      assertThat(unenforceable.get(0).getDetail()).contains("department_division");
    }
    unenforceable.clear();
    assertThat(where(lookup(null))).isEqualTo("(1 = 0)");
    assertThat(unenforceable).hasSize(1);
  }

  @Test
  void aLookupWithNothingToMatchIsNoRows() {
    assertThat(where(lookup(bound()))).isEqualTo("(1 = 0)");
    assertThat(where(lookup(bound(key("department"))))).isEqualTo("(1 = 0)");
    assertThat(where(lookup(bound(key("department", "AA"), key("region"))))).isEqualTo("(1 = 0)");
    assertThat(unenforceable).hasSize(3);
  }
}
