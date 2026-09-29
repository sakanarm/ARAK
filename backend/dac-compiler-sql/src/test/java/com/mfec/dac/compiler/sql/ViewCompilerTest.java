package com.mfec.dac.compiler.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedLookup;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.api.Unenforceable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the secure view compiles to, and what it refuses to compile.
 *
 * <p>The two whole-script assertions are golden files rather than string
 * literals because the thing being checked is a page of DDL that a person will
 * be asked to approve on a production database. A diff of that page is
 * reviewable; a diff of forty concatenated Java literals is not, and a reviewer
 * who cannot see the change will approve it.
 *
 * <p>Run with {@code -Dgolden.write=true} to re-bless them after an
 * intentional change — and read the diff before committing it, because a
 * blessed golden file is the only record that the output was ever looked at.
 */
class ViewCompilerTest {

  private static final Path GOLDEN = Path.of("src", "test", "resources", "golden");

  /** The table the manual E2E in the plan uses, with one column of each awkward kind. */
  private static final List<String> COLUMNS =
      List.of("id", "email", "citizen_id", "branch_code", "salary", "internal_note");

  private static ViewCompiler.Target target(SqlDialect dialect) {
    return new ViewCompiler.Target(
        "sales",
        "customer",
        "sec",
        "customer",
        "acl",
        null,
        COLUMNS,
        ViewCompiler.IdentitySource.DB_PRINCIPAL,
        "dac_reader");
  }

  /**
   * Two principals' decisions on one asset: one may read the email partly
   * redacted, the other not at all. The view has to serve both.
   */
  private static List<PolicyDecision> shapes() {
    return List.of(
        new PolicyDecision()
            .withRowPredicates(
                List.of(
                    new ResolvedRowPredicate()
                        .withKind(ResolvedRowPredicate.Kind.IN_LIST)
                        .withColumn("branch_code")
                        .withEntitlementKey("branch_code")
                        .withValues(List.of("BKK-01", "CNX-01"))))
            .withColumnMasks(
                List.of(
                    mask(
                        "email",
                        new MaskingSpec()
                            .withFunction(MaskingSpec.MaskingFunction.REGEX_REPLACE)
                            .withRegex("^[^@]+")
                            .withReplacement("***"),
                        null),
                    mask(
                        "citizen_id",
                        new MaskingSpec()
                            .withFunction(MaskingSpec.MaskingFunction.PARTIAL)
                            .withShowLast(4),
                        null)))
            .withHiddenColumns(List.of("internal_note")),
        new PolicyDecision()
            .withColumnMasks(
                List.of(
                    // Stricter than the first decision's mask on the same
                    // column: this is the one that has to become the ELSE.
                    mask("email", new MaskingSpec().withFunction(MaskingSpec.MaskingFunction.NULLIFY), null),
                    // A cell mask (FR-4.3) -- masked only on other people's rows.
                    mask(
                        "salary",
                        new MaskingSpec()
                            .withFunction(MaskingSpec.MaskingFunction.CONSTANT)
                            .withConstant("***"),
                        "\"t\".\"branch_code\" <> 'BKK-01'"))));
  }

  private static ResolvedColumnMask mask(String column, MaskingSpec spec, String condition) {
    return new ResolvedColumnMask()
        .withColumn(column)
        .withMasking(spec)
        .withCondition(condition)
        .withSourcePolicyId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
  }

  // ------------------------------------------------------------ the scripts

  @Test
  void compilesTheWholeScriptForPostgres() throws IOException {
    assertGolden("secure-view-postgres.sql", new PostgresDialect());
  }

  @Test
  void compilesTheWholeScriptForSqlServer() throws IOException {
    assertGolden("secure-view-sqlserver.sql", new SqlServerDialect());
  }

  private void assertGolden(String name, SqlDialect dialect) throws IOException {
    ViewCompiler.Plan plan = new ViewCompiler(dialect).compile(shapes(), target(dialect));
    String actual = plan.applyScript() + "\n-- rollback\n\n" + plan.rollbackScript();
    Path file = GOLDEN.resolve(name);
    if (Boolean.getBoolean("golden.write")) {
      Files.createDirectories(GOLDEN);
      Files.writeString(file, actual, StandardCharsets.UTF_8);
    }
    assertThat(Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"))
        .isEqualTo(actual);
  }

  // ------------------------------------------------------- what it must do

  @Test
  void theStrictestMaskIsWhatSomebodyWithNoGrantGets() {
    ViewCompiler.Plan plan =
        new ViewCompiler(new PostgresDialect()).compile(shapes(), target(new PostgresDialect()));

    // NULLIFY outranks REGEX_REPLACE, so it is the ELSE and the redacting
    // branch has to be asked for by name.
    assertThat(plan.viewSql()).contains("ELSE NULL\n  END AS \"email\"");
    assertThat(plan.treatments())
        .filteredOn(t -> t.column().equals("email") && t.fallback())
        .singleElement()
        .satisfies(t -> assertThat(t.key()).startsWith("NULLIFY#"));
  }

  @Test
  void plaintextIsATreatmentSomebodyHasToBeGranted() {
    ViewCompiler.Plan plan =
        new ViewCompiler(new PostgresDialect()).compile(shapes(), target(new PostgresDialect()));

    assertThat(plan.treatments())
        .filteredOn(t -> t.column().equals("email"))
        .extracting(ViewCompiler.Treatment::key)
        .contains(ViewCompiler.PLAIN);
    assertThat(plan.viewSql()).contains("g.\"treatment\" = 'PLAIN'");
  }

  @Test
  void aColumnWithNoMaskIsNotWrappedInAnything() {
    String sql =
        new ViewCompiler(new PostgresDialect())
            .compile(shapes(), target(new PostgresDialect()))
            .viewSql();
    assertThat(sql).contains("  \"t\".\"id\" AS \"id\",");
    assertThat(sql).contains("  \"t\".\"branch_code\" AS \"branch_code\",");
  }

  @Test
  void aHiddenColumnLeavesTheViewForEverybodyAndSaysSo() {
    ViewCompiler.Plan plan =
        new ViewCompiler(new PostgresDialect()).compile(shapes(), target(new PostgresDialect()));

    assertThat(plan.viewSql()).doesNotContain("internal_note");
    // The note is the point: this mode is stricter than the policy here, and
    // the person approving the DDL is the only one who can notice.
    assertThat(plan.notes())
        .anySatisfy(note -> assertThat(note).contains("internal_note").contains("FR-4.5"));
  }

  @Test
  void everyRowGateReadsTheEntitlementTableRatherThanABakedInValue() {
    String sql =
        new ViewCompiler(new PostgresDialect())
            .compile(shapes(), target(new PostgresDialect()))
            .viewSql();

    // The values belong to one principal and this object serves all of them,
    // so none of them may appear in the row gate. (The projection above it may
    // legitimately hold a literal, because a cell mask's condition is authored
    // SQL and is emitted as written.)
    String where = sql.substring(sql.indexOf("\nWHERE "));
    assertThat(where).doesNotContain("BKK-01");
    assertThat(where).doesNotContain("CNX-01");
    assertThat(sql).contains("e.\"entitlement_key\" = 'branch_code'");
    assertThat(sql).contains("e.\"value\" = CAST(\"t\".\"branch_code\" AS text)");
  }

  @Test
  void theMaintainerIsToldWhichKeysItHasToFill() {
    assertThat(
            new ViewCompiler(new PostgresDialect())
                .compile(shapes(), target(new PostgresDialect()))
                .entitlementKeys())
        .containsExactly("branch_code");
  }

  @Test
  void denyingEveryRowIsLeftToTheSubscriptionTableRatherThanWrittenIntoTheView() {
    PolicyDecision denied =
        new PolicyDecision()
            .withRowPredicates(
                List.of(
                    new ResolvedRowPredicate()
                        .withKind(ResolvedRowPredicate.Kind.ALWAYS_FALSE)));

    ViewCompiler.Plan plan =
        new ViewCompiler(new PostgresDialect())
            .compile(List.of(denied), target(new PostgresDialect()));

    // Rendering 1 = 0 here would blank the table for every reader, not for the
    // one the policy denied.
    assertThat(plan.viewSql()).doesNotContain("1 = 0");
    assertThat(plan.notes()).anySatisfy(note -> assertThat(note).contains("asset_subscription"));
  }

  @Test
  void anOperatorTheLookupCannotExpressClosesTheViewRatherThanDroppingTheFilter() {
    PolicyDecision ranged =
        new PolicyDecision()
            .withRowPredicates(
                List.of(
                    new ResolvedRowPredicate()
                        .withKind(ResolvedRowPredicate.Kind.ATTRIBUTE_COMPARE)
                        .withColumn("salary")
                        .withOperator(ResolvedRowPredicate.FacetOperator.LTE)
                        .withValues(List.of(100000))
                        .withSourcePolicyId(UUID.fromString("22222222-2222-2222-2222-222222222222"))));

    ViewCompiler.Plan plan =
        new ViewCompiler(new PostgresDialect())
            .compile(List.of(ranged), target(new PostgresDialect()));

    assertThat(plan.viewSql()).contains("(1 = 0)");
    assertThat(plan.unenforceable())
        .singleElement()
        .satisfies(
            u -> {
              assertThat(u.getSuggestedMode()).isEqualTo(Unenforceable.SuggestedMode.PROXY);
              assertThat(u.getDetail()).contains("salary").contains("proxy");
            });
  }

  @Test
  void aRowFilterThatReadsAMappingTableClosesTheViewAndPointsAtTheProxy() {
    PolicyDecision mapped =
        new PolicyDecision()
            .withRowPredicates(
                List.of(
                    new ResolvedRowPredicate()
                        .withKind(ResolvedRowPredicate.Kind.LOOKUP)
                        .withColumn("branch_code")
                        .withOperator(ResolvedRowPredicate.FacetOperator.IN)
                        .withLookup(
                            new ResolvedLookup()
                                .withTable("warehouse.sales.ref.region_branch")
                                .withValueColumn("branch_code")
                                .withMode(ResolvedLookup.Mode.SUBQUERY))
                        .withSourcePolicyId(UUID.fromString("33333333-3333-3333-3333-333333333333"))));

    ViewCompiler.Plan plan =
        new ViewCompiler(new PostgresDialect())
            .compile(List.of(mapped), target(new PostgresDialect()));

    assertThat(plan.viewSql()).contains("(1 = 0)");
    assertThat(plan.entitlementKeys()).isEmpty();
    assertThat(plan.unenforceable())
        .singleElement()
        .satisfies(
            u -> {
              assertThat(u.getSuggestedMode()).isEqualTo(Unenforceable.SuggestedMode.PROXY);
              assertThat(u.getDetail()).contains("region_branch").contains("proxy");
            });
  }

  @Test
  void aKeyRequiredByOnePolicyAndForbiddenByAnotherIsRefusedRatherThanGuessed() {
    ResolvedRowPredicate required =
        new ResolvedRowPredicate()
            .withKind(ResolvedRowPredicate.Kind.IN_LIST)
            .withColumn("branch_code")
            .withValues(List.of("BKK-01"));
    ResolvedRowPredicate forbidden =
        new ResolvedRowPredicate()
            .withKind(ResolvedRowPredicate.Kind.IN_LIST)
            .withColumn("branch_code")
            .withOperator(ResolvedRowPredicate.FacetOperator.NOT_IN)
            .withValues(List.of("CNX-01"));

    assertThatThrownBy(
            () ->
                new ViewCompiler(new PostgresDialect())
                    .compile(
                        List.of(new PolicyDecision().withRowPredicates(List.of(required, forbidden))),
                        target(new PostgresDialect())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("both required and forbidden");
  }

  @Test
  void theSessionPrincipalIsOptInAndCarriesItsOwnWarning() {
    ViewCompiler.Target proxied =
        new ViewCompiler.Target(
            "sales",
            "customer",
            "sec",
            "customer",
            "acl",
            null,
            COLUMNS,
            ViewCompiler.IdentitySource.SESSION_CONTEXT,
            null);

    ViewCompiler.Plan plan = new ViewCompiler(new PostgresDialect()).compile(shapes(), proxied);

    assertThat(plan.viewSql()).contains("current_setting('app.principal', true)");
    assertThat(plan.viewSql()).doesNotContain("CURRENT_USER");
    assertThat(plan.notes())
        .anySatisfy(note -> assertThat(note).contains("name themselves anybody"));
  }

  // --------------------------------------------------------- what it refuses

  @Test
  void aSecureNameThatWouldNeedQuotingToBeSafeIsRefused() {
    assertThatThrownBy(
            () ->
                new ViewCompiler.Target(
                    "sales",
                    "customer",
                    "sec\"; DROP TABLE customer; --",
                    "customer",
                    "acl",
                    null,
                    COLUMNS,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("plain identifier");
  }

  @Test
  void aTableWeCouldNotIntrospectIsRefusedRatherThanCompiledEmpty() {
    // A view built from an empty column list would be a view of nothing, and
    // would look applied. FR-1.6 is why this is fatal.
    assertThatThrownBy(
            () ->
                new ViewCompiler.Target(
                    "sales", "customer", "sec", "customer", "acl", null, List.of(), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot see the shape of");
  }

  @Test
  void aSourceNameWithAQuoteInItIsGovernedRatherThanRejected() {
    // Not our name to hold to a pattern: refusing it would leave the table
    // ungoverned, which is worse than quoting it.
    ViewCompiler.Target odd =
        new ViewCompiler.Target(
            "we\"ird", "cust\"omer", "sec", "customer", "acl", null, List.of("id"), null, null);
    assertThat(new ViewCompiler(new PostgresDialect()).compile(shapes(), odd).viewSql())
        .contains("FROM \"we\"\"ird\".\"cust\"\"omer\" \"t\"");
  }

  // ------------------------------------------------------------- treatments

  @Test
  void theTreatmentKeyDependsOnEveryParameterOfTheMask() {
    MaskingSpec four =
        new MaskingSpec().withFunction(MaskingSpec.MaskingFunction.PARTIAL).withShowLast(4);
    MaskingSpec six =
        new MaskingSpec().withFunction(MaskingSpec.MaskingFunction.PARTIAL).withShowLast(6);

    assertThat(ViewCompiler.treatmentKey(four, null))
        .isEqualTo(ViewCompiler.treatmentKey(four, null))
        .isNotEqualTo(ViewCompiler.treatmentKey(six, null))
        // The same function under a different condition is a different
        // treatment: granting one must not grant the other.
        .isNotEqualTo(ViewCompiler.treatmentKey(four, "dept = 'FINANCE'"));
    assertThat(ViewCompiler.treatmentKey(null, null)).isEqualTo(ViewCompiler.PLAIN);
  }

  @Test
  void theKeyCarriesNoneOfTheMaskItNames() {
    // It ends up in a column of a table on the customer's database.
    String key =
        ViewCompiler.treatmentKey(
            new MaskingSpec()
                .withFunction(MaskingSpec.MaskingFunction.HASH)
                .withSaltRef("vault://kv/dac/salt/citizen_id"),
            null);
    assertThat(key).doesNotContain("vault").doesNotContain("citizen_id");
  }

  @Test
  void theViewAndTheProxyMaskAColumnWithTheSameCharacters() {
    // FR-6.0c, at the one place the two modes could drift: both call
    // DecisionSql.masked, and this fails the moment one stops.
    SqlDialect dialect = new PostgresDialect();
    MaskingSpec spec =
        new MaskingSpec().withFunction(MaskingSpec.MaskingFunction.PARTIAL).withShowLast(4);

    String proxy = DecisionSql.masked(dialect, "\"t\".\"citizen_id\"", spec, null);
    assertThat(
            new ViewCompiler(dialect)
                .compile(shapes(), target(dialect))
                .viewSql())
        .contains(proxy);
  }
}
