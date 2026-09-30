package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mfec.dac.compiler.sql.PostgresDialect;
import com.mfec.dac.compiler.sql.SqlServerDialect;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedLookup;
import com.mfec.dac.schema.api.ResolvedLookupKey;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.entity.policy.DataPolicy;
import com.mfec.dac.schema.entity.policy.LookupKey;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.RowFilter;
import com.mfec.dac.schema.entity.policy.RowLookup;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The parts of a lookup row filter that need no database: the statement
 * READ_VALUES sends, the words a person is shown, and what saves.
 */
class LookupBinderTest {

  private static final String MAPPING = "warehouse.sales.ref.department_division";

  private static final LookupBinder.Located WHERE =
      new LookupBinder.Located(MAPPING, UUID.randomUUID(), "sales", "ref", "department_division");

  private static ResolvedLookup lookup(ResolvedLookup.Mode mode, ResolvedLookupKey... keys) {
    return new ResolvedLookup()
        .withTable(MAPPING)
        .withValueColumn("division")
        .withMode(mode)
        .withKeys(List.of(keys));
  }

  private static ResolvedLookupKey key(String column, String... values) {
    return new ResolvedLookupKey()
        .withColumn(column)
        .withUserAttribute(column)
        .withValues(List.of(values));
  }

  private static ResolvedRowPredicate predicate(ResolvedLookup lookup) {
    return new ResolvedRowPredicate()
        .withKind(ResolvedRowPredicate.Kind.LOOKUP)
        .withColumn("division")
        .withOperator(ResolvedRowPredicate.FacetOperator.IN)
        .withLookup(lookup);
  }

  // ---------------------------------------------------------- the statement

  @Test
  void readValuesAsksForTheDistinctValuesOfThePersonsOwnKeysOnly() {
    assertThat(
            LookupBinder.readStatement(
                new PostgresDialect(),
                WHERE,
                lookup(ResolvedLookup.Mode.READ_VALUES, key("department", "AA", "AB"), key("region", "North"))))
        .isEqualTo(
            "SELECT DISTINCT \"division\" FROM \"ref\".\"department_division\""
                + " WHERE \"department\" IN ('AA', 'AB') AND \"region\" IN ('North')"
                + " AND \"division\" IS NOT NULL");
  }

  @Test
  void readValuesQuotesForTheEngineThatHoldsTheMapping() {
    assertThat(
            LookupBinder.readStatement(
                new SqlServerDialect(),
                WHERE,
                lookup(ResolvedLookup.Mode.READ_VALUES, key("department", "O'Neil"))))
        .isEqualTo(
            "SELECT DISTINCT [division] FROM [ref].[department_division]"
                + " WHERE [department] IN (N'O''Neil') AND [division] IS NOT NULL");
  }

  @Test
  void onlyTypesThatMeanTheSameWrittenBackCanBeListed() {
    assertThat(LookupBinder.listable("varchar")).isTrue();
    assertThat(LookupBinder.listable("VARCHAR(20)")).isTrue();
    assertThat(LookupBinder.listable("int4")).isTrue();
    assertThat(LookupBinder.listable("numeric(10,2)")).isTrue();
    assertThat(LookupBinder.listable("\"bpchar\"")).isTrue();
    assertThat(LookupBinder.listable("uniqueidentifier")).isTrue();

    assertThat(LookupBinder.listable("timestamptz")).isFalse();
    assertThat(LookupBinder.listable("datetime2")).isFalse();
    assertThat(LookupBinder.listable("float8")).isFalse();
    assertThat(LookupBinder.listable("bytea")).isFalse();
    assertThat(LookupBinder.listable("varbinary")).isFalse();
    assertThat(LookupBinder.listable(null)).isFalse();
  }

  @Test
  void fixedWidthPaddingIsDroppedAndNothingElse() {
    assertThat(LookupBinder.unpadded("A   ")).isEqualTo("A");
    assertThat(LookupBinder.unpadded("  A ")).isEqualTo("  A");
    assertThat(LookupBinder.unpadded("A\t")).isEqualTo("A\t");
    assertThat(LookupBinder.unpadded("   ")).isEmpty();
  }

  // ----------------------------------------------------------- the decision

  @Test
  void aDecisionWithNoLookupIsNotCopiedOrTouched() {
    LookupBinder binder = new LookupBinder(null, null, null, (s, sql, max, caller) -> null);
    PolicyDecision plain =
        new PolicyDecision()
            .withAllowed(true)
            .withRowPredicates(
                List.of(
                    new ResolvedRowPredicate()
                        .withKind(ResolvedRowPredicate.Kind.IN_LIST)
                        .withColumn("division")
                        .withValues(List.of("A"))));
    PolicyDecision denied =
        new PolicyDecision()
            .withAllowed(false)
            .withRowPredicates(List.of(predicate(lookup(ResolvedLookup.Mode.SUBQUERY))));

    assertThat(binder.bind(null, "warehouse.sales.public.results", plain, "reader_a")).isSameAs(plain);
    assertThat(binder.bind(null, "warehouse.sales.public.results", denied, "reader_a")).isSameAs(denied);
    assertThat(binder.bind(null, "warehouse.sales.public.results", null, "reader_a")).isNull();
    assertThat(LookupBinder.hasLookup(plain)).isFalse();
    assertThat(LookupBinder.hasLookup(denied)).isTrue();
    assertThat(LookupBinder.hasLookup(new PolicyDecision())).isFalse();
  }

  // -------------------------------------------------------------- the words

  @Test
  void aJoinedLookupIsDescribedByWhereItLooksAndWhatItMatches() {
    assertThat(
            LookupBinder.describe(
                predicate(lookup(ResolvedLookup.Mode.SUBQUERY, key("department", "AA"), key("region", "North")))))
        .isEqualTo(
            "division is one of the division values in " + MAPPING
                + " for department AA and region North");
  }

  @Test
  void aReadLookupNeverListsWhatWasRead() {
    ResolvedRowPredicate read =
        predicate(lookup(ResolvedLookup.Mode.READ_VALUES, key("department", "AA")))
            .withKind(ResolvedRowPredicate.Kind.IN_LIST)
            .withValues(List.of("A-secret-division"));

    assertThat(LookupBinder.describe(read))
        .endsWith(", read when the query ran")
        .doesNotContain("A-secret-division");
  }

  @Test
  void aLookupThatGaveNothingSaysSo() {
    ResolvedRowPredicate none =
        predicate(lookup(ResolvedLookup.Mode.READ_VALUES, key("department", "ZZ")))
            .withKind(ResolvedRowPredicate.Kind.ALWAYS_FALSE)
            .withOperator(null);

    assertThat(LookupBinder.describe(none))
        .isEqualTo("no rows at all: " + MAPPING + " gives no division for department ZZ");
  }

  @Test
  void aFilterThatIsNotALookupIsLeftToTheCaller() {
    assertThat(
            LookupBinder.describe(
                new ResolvedRowPredicate()
                    .withKind(ResolvedRowPredicate.Kind.IN_LIST)
                    .withColumn("division")
                    .withValues(List.of("A"))))
        .isNull();
    assertThat(LookupBinder.describe(null)).isNull();
  }

  // -------------------------------------------------------------- what saves

  @Test
  void aLookupOnANamedColumnPassesTheConditionCheck() {
    RowFilter named =
        new RowFilter()
            .withKind(RowFilter.Kind.LOOKUP)
            .withColumn("division")
            .withLookup(
                new RowLookup()
                    .withTable(MAPPING)
                    .withValueColumn("division")
                    .withKeys(
                        List.of(new LookupKey().withColumn("department").withUserAttribute("department"))));
    Policy policy =
        new Policy()
            .withName("divisions-by-department")
            .withPolicyType(Policy.PolicyType.DATA)
            .withData(new DataPolicy().withRowFilters(List.of(named)));

    assertThatCode(() -> ConditionValues.check(policy)).doesNotThrowAnyException();
  }
}
