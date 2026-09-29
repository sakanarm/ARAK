package com.mfec.dac.compiler.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.ColumnGrant;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.Entitlement;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.EntitlementSource;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.Maintenance;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.MismatchedPlanException;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.Rows;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.Subscription;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedLookup;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The rows that finish the sentence the secure view starts.
 *
 * <p>Every test here compiles the view from the same decisions it then
 * maintains the rows from, which is the only arrangement that proves anything.
 * The failure this class exists to prevent is the two halves drifting, and a
 * test that hand-wrote the plan would be asserting that the maintainer agrees
 * with the test author rather than with the compiler.
 *
 * <p>The refusals are the tests worth keeping if the rest were cut. Each covers
 * a state where the view is valid, the rows are valid, the apply succeeds, and
 * reading the database afterwards shows nothing wrong — while the data has gone
 * to somebody the policy did not send it to.
 */
class RowEntitlementMaintainerTest {

  private static final String ASSET = "sales.customer";
  private static final UUID POLICY = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static final MaskingSpec NULLIFY =
      new MaskingSpec().withFunction(MaskingSpec.MaskingFunction.NULLIFY);
  private static final MaskingSpec REDACT =
      new MaskingSpec()
          .withFunction(MaskingSpec.MaskingFunction.REGEX_REPLACE)
          .withRegex("^[^@]+")
          .withReplacement("***");
  private static final MaskingSpec LAST_FOUR =
      new MaskingSpec().withFunction(MaskingSpec.MaskingFunction.PARTIAL).withShowLast(4);
  private static final MaskingSpec REDACTED_CONSTANT =
      new MaskingSpec()
          .withFunction(MaskingSpec.MaskingFunction.CONSTANT)
          .withConstant("***REDACTED***");

  private static final String REDACT_KEY = ViewCompiler.treatmentKey(REDACT, null);
  private static final String LAST_FOUR_KEY = ViewCompiler.treatmentKey(LAST_FOUR, null);

  private static ViewCompiler.Target target() {
    return new ViewCompiler.Target(
        "sales",
        "customer",
        "sec",
        "customer",
        "acl",
        ASSET,
        List.of("id", "email", "citizen_id", "branch_code"),
        ViewCompiler.IdentitySource.DB_PRINCIPAL,
        "dac_reader");
  }

  private static ResolvedColumnMask mask(String column, MaskingSpec spec) {
    return new ResolvedColumnMask().withColumn(column).withMasking(spec).withSourcePolicyId(POLICY);
  }

  private static ResolvedRowPredicate branches(String... values) {
    return new ResolvedRowPredicate()
        .withKind(ResolvedRowPredicate.Kind.IN_LIST)
        .withColumn("branch_code")
        .withEntitlementKey("branch_code")
        .withOperator(ResolvedRowPredicate.FacetOperator.IN)
        .withValues(List.of((Object[]) values));
  }

  private static PolicyDecision allowed(String principal) {
    return new PolicyDecision().withPrincipal(principal).withAllowed(true);
  }

  /**
   * Two readers of one table. {@code analyst_b}'s policy is the stricter one on
   * both masked columns, which makes their treatment the view's {@code ELSE} —
   * so it is {@code analyst_a}, the one who may see more, who needs rows.
   */
  private static List<PolicyDecision> shapes() {
    return List.of(
        allowed("analyst_a")
            .withRowPredicates(List.of(branches("BKK-01", "CNX-01")))
            .withColumnMasks(List.of(mask("email", REDACT), mask("citizen_id", LAST_FOUR))),
        allowed("analyst_b")
            .withRowPredicates(List.of(branches("SGN-01")))
            .withColumnMasks(List.of(mask("email", NULLIFY), mask("citizen_id", NULLIFY))));
  }

  private static ViewCompiler.Plan plan(List<PolicyDecision> shapes) {
    return new ViewCompiler(new PostgresDialect()).compile(shapes, target());
  }

  private static Maintenance maintain(List<PolicyDecision> shapes) {
    return RowEntitlementMaintainer.maintain(
        plan(shapes), ASSET, shapes, EntitlementSource.NONE, Rows.NONE);
  }

  // -------------------------------------------------------- what it writes

  @Test
  void everyAllowedPrincipalGetsExactlyOneSubscription() {
    Maintenance run = maintain(shapes());

    assertThat(run.desired().subscriptions())
        .containsExactlyInAnyOrder(
            new Subscription("analyst_a", ASSET), new Subscription("analyst_b", ASSET));
  }

  @Test
  void theFallbackTreatmentNeedsNoRowAndEveryOtherOneDoes() {
    Maintenance run = maintain(shapes());

    // analyst_b is on the ELSE of both columns, so saying it again in a grant
    // row would double the table to change nothing. analyst_a has to ask for
    // the weaker branches by name.
    assertThat(run.desired().grants())
        .containsExactlyInAnyOrder(
            new ColumnGrant("analyst_a", ASSET, "email", REDACT_KEY),
            new ColumnGrant("analyst_a", ASSET, "citizen_id", LAST_FOUR_KEY));
  }

  @Test
  void theGrantedTreatmentIsTheKeyTheViewBranchesOn() {
    ViewCompiler.Plan shape = plan(shapes());
    Maintenance run =
        RowEntitlementMaintainer.maintain(shape, ASSET, shapes(), EntitlementSource.NONE, Rows.NONE);

    assertThat(run.desired().grants())
        .contains(new ColumnGrant("analyst_a", ASSET, "email", REDACT_KEY));
    // And the view really does have that branch. If these two ever computed the
    // key differently, every reader would quietly get the ELSE and the three
    // tables would still look correct.
    assertThat(shape.viewSql()).contains(REDACT_KEY);
  }

  @Test
  void aColumnNobodyMasksIsGrantedToNobody() {
    Maintenance run = maintain(shapes());

    assertThat(run.desired().grants())
        .noneMatch(grant -> grant.column().equals("branch_code") || grant.column().equals("id"));
  }

  @Test
  void plaintextIsItselfAGrantWhenTheViewMasksByDefault() {
    // Somebody the policy leaves alone, on columns the view masks for everybody
    // else. Reading in the clear is a branch of the CASE like any other, and
    // needs a row like any other.
    List<PolicyDecision> shapes = List.of(shapes().get(1), allowed("analyst_c"));

    Maintenance run = maintain(shapes);

    assertThat(run.desired().grants())
        .containsExactlyInAnyOrder(
            new ColumnGrant("analyst_c", ASSET, "email", ViewCompiler.PLAIN),
            new ColumnGrant("analyst_c", ASSET, "citizen_id", ViewCompiler.PLAIN));
  }

  @Test
  void everyGateValueBecomesOneRow() {
    Maintenance run = maintain(shapes());

    assertThat(run.desired().entitlements())
        .containsExactlyInAnyOrder(
            new Entitlement("analyst_a", ASSET, "branch_code", "BKK-01"),
            new Entitlement("analyst_a", ASSET, "branch_code", "CNX-01"),
            new Entitlement("analyst_b", ASSET, "branch_code", "SGN-01"));
  }

  @Test
  void anEntitlementJoinGoesAndAsksBecauseTheDecisionDoesNotCarryTheValues() {
    List<PolicyDecision> shapes = List.of(allowed("analyst_a").withRowPredicates(List.of(lookup())));

    EntitlementSource source =
        (principal, asset, key) ->
            principal.equals("analyst_a") && asset.equals(ASSET) && key.equals("branch_code")
                ? List.of("BKK-01", "BKK-02")
                : List.of();

    Maintenance run =
        RowEntitlementMaintainer.maintain(plan(shapes), ASSET, shapes, source, Rows.NONE);

    assertThat(run.desired().entitlements())
        .containsExactlyInAnyOrder(
            new Entitlement("analyst_a", ASSET, "branch_code", "BKK-01"),
            new Entitlement("analyst_a", ASSET, "branch_code", "BKK-02"));
  }

  @Test
  void aSourceThatKnowsNothingHidesEveryRowRatherThanNone() {
    List<PolicyDecision> shapes = List.of(allowed("analyst_a").withRowPredicates(List.of(lookup())));

    Maintenance run = maintain(shapes);

    // Subscribed, and entitled to nothing: they read the table's shape and no
    // rows at all. The other reading -- no gate rows meaning no gate -- is how
    // this kind of code leaks, so it is asserted rather than assumed.
    assertThat(run.desired().subscriptions()).containsExactly(new Subscription("analyst_a", ASSET));
    assertThat(run.desired().entitlements()).isEmpty();
    assertThat(run.notes()).anyMatch(note -> note.contains("any value of branch_code"));
  }

  @Test
  void aMappingTableLookupIsNeverTurnedIntoRowsTheMappingDidNotGive() {
    ResolvedRowPredicate mapped =
        new ResolvedRowPredicate()
            .withKind(ResolvedRowPredicate.Kind.LOOKUP)
            .withColumn("branch_code")
            .withOperator(ResolvedRowPredicate.FacetOperator.IN)
            .withLookup(
                new ResolvedLookup()
                    .withTable("warehouse.sales.ref.region_branch")
                    .withValueColumn("branch_code")
                    .withMode(ResolvedLookup.Mode.SUBQUERY));
    List<PolicyDecision> shapes = List.of(allowed("analyst_a").withRowPredicates(List.of(mapped)));
    EntitlementSource everything = (principal, asset, key) -> List.of("BKK-01");

    Maintenance run =
        RowEntitlementMaintainer.maintain(plan(shapes), ASSET, shapes, everything, Rows.NONE);

    // The compiler closed the view; nothing written here may reopen it.
    assertThat(plan(shapes).viewSql()).contains("(1 = 0)");
    assertThat(run.desired().entitlements()).isEmpty();
  }

  private static ResolvedRowPredicate lookup() {
    return new ResolvedRowPredicate()
        .withKind(ResolvedRowPredicate.Kind.ENTITLEMENT_JOIN)
        .withColumn("branch_code")
        .withEntitlementKey("branch_code");
  }

  // ------------------------------------------------------ what it withholds

  @Test
  void alwaysFalseWithholdsTheSubscriptionInsteadOfWritingItIntoTheView() {
    List<PolicyDecision> shapes =
        List.of(
            shapes().get(0),
            allowed("analyst_b")
                .withRowPredicates(
                    List.of(
                        new ResolvedRowPredicate()
                            .withKind(ResolvedRowPredicate.Kind.ALWAYS_FALSE)))
                .withColumnMasks(List.of(mask("email", NULLIFY), mask("citizen_id", NULLIFY))));

    Maintenance run = maintain(shapes);

    assertThat(run.desired().subscriptions()).containsExactly(new Subscription("analyst_a", ASSET));
    assertThat(run.desired().entitlements()).noneMatch(row -> row.principal().equals("analyst_b"));
    assertThat(run.desired().grants()).noneMatch(row -> row.principal().equals("analyst_b"));
  }

  @Test
  void aDeniedPrincipalKeepsNothingAtAll() {
    List<PolicyDecision> shapes =
        List.of(
            shapes().get(0),
            new PolicyDecision()
                .withPrincipal("analyst_b")
                .withAllowed(false)
                .withColumnMasks(List.of(mask("email", NULLIFY), mask("citizen_id", NULLIFY))));

    Maintenance run = maintain(shapes);

    assertThat(run.desired().subscriptions()).containsExactly(new Subscription("analyst_a", ASSET));
    assertThat(run.desired().grants()).noneMatch(row -> row.principal().equals("analyst_b"));
  }

  @Test
  void aColumnHiddenFromOneReaderLeavesThemOnTheStrictestTreatment() {
    // The view as compiled: citizen_id is masked for everybody, nobody hides it.
    ViewCompiler.Plan installed = plan(shapes());

    // And now a policy that hides it from one person. A view cannot drop a
    // column for one reader, so the honest outcome is no grant row -- the ELSE,
    // which is the strictest thing this view does to that column.
    List<PolicyDecision> now =
        List.of(allowed("analyst_c").withHiddenColumns(List.of("citizen_id")));

    Maintenance run =
        RowEntitlementMaintainer.maintain(installed, ASSET, now, EntitlementSource.NONE, Rows.NONE);

    assertThat(run.desired().grants())
        .noneMatch(grant -> grant.column().equals("citizen_id"))
        // ...and emphatically not a plaintext grant, which is what treating the
        // absence of a mask as "no treatment asked for" would have produced.
        .doesNotContain(new ColumnGrant("analyst_c", ASSET, "citizen_id", ViewCompiler.PLAIN));
    assertThat(run.notes())
        .anyMatch(note -> note.contains("citizen_id") && note.contains("analyst_c"));
  }

  // --------------------------------------------------------------- the diff

  @Test
  void whatThePolicyNoLongerSaysIsDeleted() {
    Rows installed =
        new Rows(
            Set.of(new Subscription("analyst_a", ASSET), new Subscription("intern_z", ASSET)),
            Set.of(
                new Entitlement("analyst_a", ASSET, "branch_code", "BKK-01"),
                new Entitlement("analyst_a", ASSET, "branch_code", "HKT-09")),
            Set.of(new ColumnGrant("intern_z", ASSET, "email", ViewCompiler.PLAIN)));

    Maintenance run =
        RowEntitlementMaintainer.maintain(
            plan(shapes()), ASSET, shapes(), EntitlementSource.NONE, installed);

    // The revoked half. A maintainer that only inserted would leave intern_z
    // reading the table in the clear and analyst_a reading a branch they lost.
    assertThat(run.delete().subscriptions()).containsExactly(new Subscription("intern_z", ASSET));
    assertThat(run.delete().entitlements())
        .containsExactly(new Entitlement("analyst_a", ASSET, "branch_code", "HKT-09"));
    assertThat(run.delete().grants())
        .containsExactly(new ColumnGrant("intern_z", ASSET, "email", ViewCompiler.PLAIN));

    // And the row that was already right is not rewritten.
    assertThat(run.insert().entitlements())
        .doesNotContain(new Entitlement("analyst_a", ASSET, "branch_code", "BKK-01"));
  }

  @Test
  void runningItTwiceHasNothingToDoTheSecondTime() {
    Maintenance first = maintain(shapes());
    Maintenance second =
        RowEntitlementMaintainer.maintain(
            plan(shapes()), ASSET, shapes(), EntitlementSource.NONE, first.desired());

    assertThat(second.isSatisfied()).isTrue();
    assertThat(second.desired()).isEqualTo(first.desired());
  }

  // ----------------------------------------------------------- the refusals

  @Test
  void refusesATreatmentTheInstalledViewHasNoBranchFor() {
    // The view as it stands on the database, compiled when the only treatments
    // of email were REGEX_REPLACE and NULLIFY.
    ViewCompiler.Plan installed = plan(shapes());

    // The policy as it stands now. A CONSTANT matches no branch, so the row
    // would be written, the apply would succeed, and analyst_a would read
    // whatever the ELSE happens to be.
    List<PolicyDecision> now =
        List.of(allowed("analyst_a").withColumnMasks(List.of(mask("email", REDACTED_CONSTANT))));

    assertThatThrownBy(
            () ->
                RowEntitlementMaintainer.maintain(
                    installed, ASSET, now, EntitlementSource.NONE, Rows.NONE))
        .isInstanceOf(MismatchedPlanException.class)
        .hasMessageContaining("analyst_a")
        .hasMessageContaining("email")
        .hasMessageContaining("Recompile the view");
  }

  @Test
  void refusesAMaskOnAColumnTheInstalledViewSelectsAsItStands() {
    ViewCompiler.Plan installed = plan(shapes());

    // branch_code is in the view unmasked, because nothing masked it when the
    // view was compiled. No row in any of the three tables can change that, so
    // writing rows and reporting success would be a lie.
    List<PolicyDecision> now =
        List.of(allowed("analyst_a").withColumnMasks(List.of(mask("branch_code", NULLIFY))));

    assertThatThrownBy(
            () ->
                RowEntitlementMaintainer.maintain(
                    installed, ASSET, now, EntitlementSource.NONE, Rows.NONE))
        .isInstanceOf(MismatchedPlanException.class)
        .hasMessageContaining("branch_code")
        .hasMessageContaining("in the clear");
  }

  @Test
  void refusesAGateTheInstalledViewDoesNotJoinOn() {
    ViewCompiler.Plan installed = plan(shapes());

    List<PolicyDecision> now =
        List.of(
            allowed("analyst_a")
                .withRowPredicates(
                    List.of(
                        new ResolvedRowPredicate()
                            .withKind(ResolvedRowPredicate.Kind.IN_LIST)
                            .withColumn("region")
                            .withEntitlementKey("region")
                            .withOperator(ResolvedRowPredicate.FacetOperator.IN)
                            .withValues(List.of("APAC")))));

    // Rows for a key nothing joins on enforce nothing: the filter would simply
    // not happen and every row of the table would come back.
    assertThatThrownBy(
            () ->
                RowEntitlementMaintainer.maintain(
                    installed, ASSET, now, EntitlementSource.NONE, Rows.NONE))
        .isInstanceOf(MismatchedPlanException.class)
        .hasMessageContaining("region")
        .hasMessageContaining("returned to them anyway");
  }

  @Test
  void refusesToRunOverNothingBecauseThatWouldRevokeEverybody() {
    assertThatThrownBy(
            () ->
                RowEntitlementMaintainer.maintain(
                    plan(shapes()), ASSET, null, EntitlementSource.NONE, Rows.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("revoke everybody");
  }
}
