package com.mfec.dac.compiler.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.schema.api.PolicyDecision;
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
}
