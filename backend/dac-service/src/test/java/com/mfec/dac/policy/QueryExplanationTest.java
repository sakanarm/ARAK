package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.proxy.QueryRewriter;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The decision rendered for somebody who will not read the generated SQL.
 *
 * <p>The rewritten statement is the authority and these strings are not: they
 * are checked here so that a wrong one is a failing test rather than a plausible
 * sentence on a screen, which is the more dangerous of the two. An explanation
 * that quietly stops matching the enforcement it describes is worse than no
 * explanation, because it is believed.
 */
class QueryExplanationTest {

  private static final String ASSET = "demo-pg.salesdb.sales.customer";

  @Test
  void namesTheColumnsThatWereMaskedAndWhatWasDoneToEachOne() {
    PolicyDecision decision = allowed();
    decision.setColumnMasks(
        List.of(
            mask("citizen_id", MaskingSpec.MaskingFunction.PARTIAL, spec -> spec.setShowLast(4)),
            mask(
                "email",
                MaskingSpec.MaskingFunction.REGEX_REPLACE,
                spec -> spec.setRegex("^[^@]+"))));

    Map<String, String> masked = QueryService.explain(governed(decision)).get(0).maskedColumns();

    assertThat(masked)
        .containsEntry("citizen_id", "hidden except the last 4 characters")
        .containsEntry("email", "rewritten by pattern ^[^@]+");
  }

  @Test
  void spellsOutTheRowFilterWithTheValuesItWasResolvedAgainst() {
    PolicyDecision decision = allowed();
    ResolvedRowPredicate predicate = new ResolvedRowPredicate();
    predicate.setKind(ResolvedRowPredicate.Kind.IN_LIST);
    predicate.setColumn("branch_code");
    predicate.setValues(List.of("BKK-01", "CNX-01"));
    decision.setRowPredicates(List.of(predicate));

    assertThat(QueryService.explain(governed(decision)).get(0).rowFilters())
        .containsExactly("branch_code is one of BKK-01, CNX-01");
  }

  /**
   * The case that reads as a broken screen rather than as a working policy: the
   * grid is empty because the principal has no value for the attribute the
   * filter compares against, so nothing could have matched.
   */
  @Test
  void saysWhyAnEmptyValueListMeansAnEmptyGrid() {
    PolicyDecision decision = allowed();
    ResolvedRowPredicate predicate = new ResolvedRowPredicate();
    predicate.setKind(ResolvedRowPredicate.Kind.IN_LIST);
    predicate.setColumn("branch_code");
    predicate.setValues(List.of());
    decision.setRowPredicates(List.of(predicate));

    assertThat(QueryService.explain(governed(decision)).get(0).rowFilters())
        .containsExactly(
            "branch_code must match one of the principal's values, and they have none");
  }

  @Test
  void keepsTheConditionThatMakesAColumnMaskACellMask() {
    PolicyDecision decision = allowed();
    ResolvedColumnMask mask =
        mask("salary", MaskingSpec.MaskingFunction.NULLIFY, spec -> {});
    mask.setCondition("department <> user.department");
    decision.setColumnMasks(List.of(mask));

    assertThat(QueryService.explain(governed(decision)).get(0).maskedColumns())
        .containsEntry("salary", "replaced with null, where department <> user.department");
  }

  @Test
  void namesEachMatchedPolicyOnceEvenWhenItSuppliedBothAFilterAndAMask() {
    PolicyDecision decision = allowed();
    decision.setReasons(
        List.of(
            reason("sales-branch-rls", ResolvedColumnMask.ScopeLevel.SCHEMA, true),
            reason("sales-branch-rls", ResolvedColumnMask.ScopeLevel.SCHEMA, true),
            reason("pii-masking-below-l2", ResolvedColumnMask.ScopeLevel.ORG, true),
            // Evaluated and did not match, so it explains nothing about this
            // result and naming it would only suggest it had an effect.
            reason("finance-only", ResolvedColumnMask.ScopeLevel.DOMAIN, false)));

    assertThat(QueryService.explain(governed(decision)).get(0).policies())
        .containsExactly("sales-branch-rls (SCHEMA)", "pii-masking-below-l2 (ORG)");
  }

  @Test
  void distinguishesAColumnThatWasDroppedFromOneThatWasBlanked() {
    PolicyDecision decision = allowed();
    decision.setHiddenColumns(List.of("internal_score"));

    QueryService.Explanation explanation = QueryService.explain(governed(decision)).get(0);
    assertThat(explanation.hiddenColumns()).containsExactly("internal_score");
    assertThat(explanation.maskedColumns()).isEmpty();
  }

  // ------------------------------------------------------------- fixtures

  private static PolicyDecision allowed() {
    PolicyDecision decision = new PolicyDecision();
    decision.setPrincipal("analyst_a");
    decision.setAssetFqn(ASSET);
    decision.setAllowed(true);
    return decision;
  }

  private static List<QueryRewriter.Governed> governed(PolicyDecision decision) {
    return List.of(new QueryRewriter.Governed(ASSET, decision, List.of()));
  }

  private static ResolvedColumnMask mask(
      String column, MaskingSpec.MaskingFunction function, java.util.function.Consumer<MaskingSpec> tune) {
    MaskingSpec spec = new MaskingSpec();
    spec.setFunction(function);
    tune.accept(spec);
    ResolvedColumnMask mask = new ResolvedColumnMask();
    mask.setColumn(column);
    mask.setMasking(spec);
    return mask;
  }

  private static DecisionReason reason(
      String name, ResolvedColumnMask.ScopeLevel scope, boolean matched) {
    DecisionReason reason = new DecisionReason();
    reason.setPolicyName(name);
    reason.setScopeLevel(scope);
    reason.setMatched(matched);
    return reason;
  }
}
