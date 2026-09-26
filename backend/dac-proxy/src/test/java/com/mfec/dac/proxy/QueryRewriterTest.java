package com.mfec.dac.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.compiler.sql.PostgresDialect;
import com.mfec.dac.engine.AssetContext;
import com.mfec.dac.engine.EngineConfig;
import com.mfec.dac.engine.PolicyEngine;
import com.mfec.dac.engine.Principal;
import com.mfec.dac.engine.RequestContext;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedColumnMask.ScopeLevel;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.api.ResolvedRowPredicate.FacetOperator;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.AttributeCondition;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import com.mfec.dac.schema.entity.policy.TimeRule;
import com.mfec.dac.schema.entity.policy.TimeWindow;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two gates, and the shape of what gets sent when they let a statement
 * through.
 *
 * <p>These are the tests the class's own Javadoc promises. The second gate went
 * to production refusing every query it was given, which is the safe direction
 * and still a failure: nobody could tell an unenforceable statement from a
 * working one.
 */
class QueryRewriterTest {

  private static final List<String> COLUMNS =
      List.of("id", "full_name", "email", "citizen_id", "phone", "salary", "branch_code");

  private final QueryRewriter rewriter = new QueryRewriter(new PostgresDialect(), null);

  // ------------------------------------------------------------- fixtures

  private static PolicyDecision allowed() {
    return new PolicyDecision()
        .withPrincipal("analyst_a")
        .withAssetFqn("demo-pg.salesdb.sales.customer")
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
                    .withValues(List.<Object>of("BKK-01"))))
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
            ? new QueryRewriter.Governed("demo-pg.salesdb.sales.customer", decision, COLUMNS)
            : null;
  }

  // ------------------------------------------------------------- happy path

  @Test
  void wrapsAGovernedTableInADerivedTableAndStillParses() {
    QueryRewriter.Rewritten out =
        rewriter.rewrite("SELECT * FROM sales.customer", governing(restricted()));

    assertThat(out.assets()).containsExactly("demo-pg.salesdb.sales.customer");
    assertThat(out.anyRestriction()).isTrue();
    assertThat(out.sql()).contains("FROM \"sales\".\"customer\"");
    assertThat(out.sql()).contains("WHERE");
  }

  @Test
  void aHiddenColumnDoesNotComeBackThroughTheStar() {
    QueryRewriter.Rewritten out =
        rewriter.rewrite("SELECT * FROM sales.customer", governing(restricted()));
    // The star now expands against the derived table, so the column has to be
    // absent from the projection we built rather than merely unselected.
    assertThat(out.sql()).doesNotContain("salary");
  }

  @Test
  void anUnrestrictedDecisionIsStillRewrittenSoTheShapeNeverDependsOnThePolicy() {
    QueryRewriter.Rewritten out =
        rewriter.rewrite("SELECT id FROM sales.customer", governing(allowed()));
    assertThat(out.assets()).containsExactly("demo-pg.salesdb.sales.customer");
    assertThat(out.anyRestriction()).isFalse();
  }

  @Test
  void keepsTheCallersAliasSoTheRestOfTheStatementStillResolves() {
    QueryRewriter.Rewritten out =
        rewriter.rewrite(
            "SELECT c.branch_code FROM sales.customer c WHERE c.branch_code = 'BKK-01'",
            governing(restricted()));
    assertThat(out.sql()).contains(" c");
  }

  // --------------------------------------------------------------- gate one

  @Test
  void refusesAnythingThatIsNotASelect() {
    assertThatThrownBy(
            () -> rewriter.rewrite("DELETE FROM sales.customer", governing(allowed())))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining("read-only");
  }

  @Test
  void refusesWhatItCannotParse() {
    assertThatThrownBy(() -> rewriter.rewrite("SELEC * FROM x", governing(allowed())))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .isNotInstanceOf(QueryRewriter.DeniedException.class)
        .hasMessageContaining("could not be parsed");
  }

  @Test
  void refusesATableThatResolvesToNoAsset() {
    assertThatThrownBy(
            () -> rewriter.rewrite("SELECT * FROM sales.orders", governing(allowed())))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining("not a governed asset");
  }

  @Test
  void refusesAnUnqualifiedTableRatherThanGuessingWhichOneItIs() {
    assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM customer", governing(allowed())))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining("Qualify");
  }

  @Test
  void refusesAnAssetThePrincipalIsNotSubscribedTo() {
    PolicyDecision denied =
        allowed()
            .withAllowed(false)
            .withReasons(
                List.of(
                    new DecisionReason()
                        .withPolicyName("finance-subscription")
                        .withEffect(DecisionReason.Effect.ALLOW)
                        .withMatched(false)
                        .withExplanation("outside the policy's permitted time window")));

    assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM sales.customer", governing(denied)))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining("finance-subscription did not apply")
        .hasMessageContaining("time window")
        // Names the table, which is what lets the query page offer to ask its
        // owner; a refusal of the statement itself (unparsable, DML) does not.
        .isInstanceOfSatisfying(
            QueryRewriter.DeniedException.class,
            e -> assertThat(e.assetFqn()).isEqualTo("demo-pg.salesdb.sales.customer"));
  }

  @Test
  void doesNotBlameADataPolicyForARefusedSubscription() {
    // A mask whose condition did not hold was listed first and named as the
    // reason, while the table was really shut by an office-hours window.
    PolicyDecision denied =
        allowed()
            .withAllowed(false)
            .withReasons(
                List.of(
                    new DecisionReason()
                        .withPolicyName("mask-contacts")
                        .withPolicyType(DecisionReason.PolicyType.DATA)
                        .withEffect(DecisionReason.Effect.ALLOW)
                        .withMatched(false)
                        .withExplanation("clearance lt L2 is false"),
                    new DecisionReason()
                        .withPolicyName("office-hours")
                        .withPolicyType(DecisionReason.PolicyType.SUBSCRIPTION)
                        .withEffect(DecisionReason.Effect.ALLOW)
                        .withMatched(false)
                        .withExplanation("outside the policy's permitted time window")));

    assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM sales.customer", governing(denied)))
        .isInstanceOf(QueryRewriter.DeniedException.class)
        .hasMessageContaining("office-hours did not apply")
        .hasMessageNotContaining("mask-contacts");
  }

  @Test
  void doesNotReportAMatchedAllowAsTheReasonForARefusal() {
    // The bug this replaced: "access is denied: subject rule satisfied".
    PolicyDecision denied =
        allowed()
            .withAllowed(false)
            .withReasons(
                List.of(
                    new DecisionReason()
                        .withPolicyName("sales-branch-rls")
                        .withEffect(DecisionReason.Effect.ALLOW)
                        .withMatched(true)
                        .withExplanation("policy carries no subject rule"),
                    new DecisionReason()
                        .withPolicyName("(composition)")
                        .withMatched(false)
                        .withExplanation("no policy at layer 0 grants this principal access")));

    assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM sales.customer", governing(denied)))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining("no policy at layer 0")
        .hasMessageNotContaining("no subject rule");
  }

  @Test
  void doesNotBlameADenyThatDidNotMatch() {
    // A DENY that did not match kept nobody out, however early it is listed.
    PolicyDecision denied =
        allowed()
            .withAllowed(false)
            .withReasons(
                List.of(
                    new DecisionReason()
                        .withPolicyName("contractors-deny")
                        .withPolicyType(DecisionReason.PolicyType.SUBSCRIPTION)
                        .withEffect(DecisionReason.Effect.DENY)
                        .withMatched(false)
                        .withExplanation("attribute condition not satisfied: employeeType eq CONTRACTOR"),
                    new DecisionReason()
                        .withPolicyName("finance-subscription")
                        .withPolicyType(DecisionReason.PolicyType.SUBSCRIPTION)
                        .withEffect(DecisionReason.Effect.ALLOW)
                        .withMatched(false)
                        .withExplanation("attribute condition not satisfied: department eq FINANCE")));

    assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM sales.customer", governing(denied)))
        .isInstanceOf(QueryRewriter.DeniedException.class)
        .hasMessageContaining("finance-subscription did not apply")
        .hasMessageNotContaining("contractors-deny");
  }

  // ------------------------------------------- refusals the engine wrote

  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

  /** 2026-09-15 is a Tuesday; 20:00 is after the office closes. */
  private static final RequestContext TUESDAY_EVENING =
      RequestContext.at(LocalDateTime.parse("2026-09-15T20:00").atZone(BANGKOK).toInstant());

  private static Policy subscription(String name, ScopeLevel level) {
    return new Policy()
        .withId(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)))
        .withName(name)
        .withVersion(1)
        .withPolicyType(Policy.PolicyType.SUBSCRIPTION)
        .withScopeLevel(level)
        .withScopeFqn(level == ScopeLevel.ORG ? null : "demo-pg.salesdb.sales.customer")
        .withEffect(Policy.Effect.ALLOW)
        .withSelector(
            new AssetSelector()
                .withCondition(
                    new FacetCondition()
                        .withFacet(FacetType.TABLE)
                        .withOperator(FacetOperator.EQ)
                        .withValue("customer")));
  }

  private static SubjectRule department(String value) {
    return new SubjectRule()
        .withAttributes(
            List.of(
                new AttributeCondition()
                    .withKey("department")
                    .withOperator(FacetOperator.EQ)
                    .withValue(value)));
  }

  private static TimeRule officeHours() {
    return new TimeRule()
        .withWindows(
            List.of(
                new TimeWindow()
                    .withDays(List.of("MON-FRI"))
                    .withFrom("08:00")
                    .withTo("18:00")
                    .withTimezone("Asia/Bangkok")));
  }

  private static PolicyDecision decide(Principal principal, List<Policy> policies) {
    return new PolicyEngine(EngineConfig.defaults().withZone(BANGKOK))
        .evaluate(
            principal,
            AssetContext.of("demo-pg.salesdb.sales.customer").physicalFromFqn().build(),
            TUESDAY_EVENING,
            policies);
  }

  private static Principal financeAnalyst() {
    return Principal.withId("analyst_f").roles("analyst").attribute("department", "FINANCE").build();
  }

  @Test
  void namesThePolicyWrittenForThePrincipalThatOnlyTheClockKeptShut() {
    // Found in UAT: a finance analyst after hours was told the procurement
    // subscription did not apply to them, which was true and no help. The one
    // written for finance, shut only by its office-hours window, is the answer.
    Policy procurement = subscription("procurement-readers", ScopeLevel.ORG);
    procurement.withSubject(department("PROCUREMENT"));
    Policy finance = subscription("finance-office-hours", ScopeLevel.ORG);
    finance.withSubject(department("FINANCE").withTime(officeHours()));

    PolicyDecision denied = decide(financeAnalyst(), List.of(procurement, finance));

    assertThat(denied.getAllowed()).isFalse();
    assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM sales.customer", governing(denied)))
        .isInstanceOf(QueryRewriter.DeniedException.class)
        .hasMessageContaining("finance-office-hours did not apply")
        .hasMessageContaining("time window")
        .hasMessageNotContaining("procurement-readers");
  }

  @Test
  void doesNotBlameAPolicyAtALayerThePrincipalGotThrough() {
    // The org layer lets finance in; the table layer is what refuses. The org
    // policy for procurement did not apply either, but it is not why the door
    // is shut.
    Policy procurement = subscription("procurement-readers", ScopeLevel.ORG);
    procurement.withSubject(department("PROCUREMENT"));
    Policy finance = subscription("finance-readers", ScopeLevel.ORG);
    finance.withSubject(department("FINANCE"));
    Policy tableOwners = subscription("customer-owners-only", ScopeLevel.TABLE);
    tableOwners.withSubject(department("SALES"));

    PolicyDecision denied = decide(financeAnalyst(), List.of(procurement, finance, tableOwners));

    assertThat(denied.getAllowed()).isFalse();
    assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM sales.customer", governing(denied)))
        .isInstanceOf(QueryRewriter.DeniedException.class)
        .hasMessageContaining("customer-owners-only did not apply")
        .hasMessageNotContaining("procurement-readers");
  }

  @Test
  void aMatchedDenyIsTheReason() {
    PolicyDecision denied =
        allowed()
            .withAllowed(false)
            .withReasons(
                List.of(
                    new DecisionReason()
                        .withPolicyName("offshore-deny")
                        .withEffect(DecisionReason.Effect.DENY)
                        .withMatched(true)
                        .withExplanation("reader is outside the data's residency")));

    assertThatThrownBy(() -> rewriter.rewrite("SELECT * FROM sales.customer", governing(denied)))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining("denied by offshore-deny")
        .hasMessageContaining("residency");
  }

  // --------------------------------------------------------------- gate two

  @Test
  void letsARewrittenStatementThroughEvenThoughItStillNamesTheBaseTable() {
    // The derived table necessarily contains the physical name. A gate that
    // looked at the output would refuse this, and did.
    assertThat(rewriter.rewrite("SELECT * FROM sales.customer", governing(restricted())).sql())
        .contains("customer");
  }

  @Test
  void refusesATableReadFromAPositionTheWalkDoesNotReach() {
    // A subquery inside WHERE is not a FROM item, so nothing was placed in
    // front of it. Refused, not sent.
    assertThatThrownBy(
            () ->
                rewriter.rewrite(
                    "SELECT 1 WHERE EXISTS (SELECT 1 FROM sales.customer)", governing(allowed())))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining("does not rewrite");
  }

  @Test
  void governsBothSidesOfAUnion() {
    QueryRewriter.Rewritten out =
        rewriter.rewrite(
            "SELECT id FROM sales.customer UNION ALL SELECT id FROM sales.customer",
            governing(restricted()));
    assertThat(out.assets()).containsExactly("demo-pg.salesdb.sales.customer");
  }

  @Test
  void governsTheBodyOfACteAndDoesNotRewriteTheReferenceTwice() {
    QueryRewriter.Rewritten out =
        rewriter.rewrite(
            "WITH recent AS (SELECT id, branch_code FROM sales.customer) "
                + "SELECT * FROM recent",
            governing(restricted()));
    assertThat(out.assets()).containsExactly("demo-pg.salesdb.sales.customer");
    // One derived table, for the one physical reference inside the CTE body.
    assertThat(out.sql().split("WHERE", -1).length - 1).isEqualTo(1);
  }

  @Test
  void refusesASchemaQualifiedNameThatOnlyLooksLikeACte() {
    assertThatThrownBy(
            () ->
                rewriter.rewrite(
                    "WITH customer AS (SELECT 1 AS id) SELECT * FROM other.customer",
                    governing(allowed())))
        .isInstanceOf(QueryRewriter.RefusedException.class)
        .hasMessageContaining("not a governed asset");
  }

  // ------------------------------------------------------- fixable (M26)

  private QueryRewriter.RefusedException refusalOf(String sql, PolicyDecision decision) {
    try {
      rewriter.rewrite(sql, governing(decision));
    } catch (QueryRewriter.RefusedException e) {
      return e;
    }
    throw new AssertionError("expected a refusal for " + sql);
  }

  @Test
  void aRefusalOfTheStatementIsOneARewordingCouldGetPast() {
    // The four a corrected statement can answer: a typo, a missing schema, a
    // table that is not there, a subquery where the proxy does not look.
    assertThat(refusalOf("SELEC * FROM x", allowed()).aboutStatement()).isTrue();
    assertThat(refusalOf("SELECT * FROM customer", allowed()).aboutStatement()).isTrue();
    assertThat(refusalOf("SELECT * FROM sales.orders", allowed()).aboutStatement()).isTrue();
    assertThat(
            refusalOf("SELECT 1 WHERE EXISTS (SELECT 1 FROM sales.customer)", allowed())
                .aboutStatement())
        .isTrue();
  }

  @Test
  void aRefusalAPolicyMadeIsNeverOfferedForRewording() {
    PolicyDecision denied = allowed().withAllowed(false);
    assertThat(refusalOf("SELECT * FROM sales.customer", denied).aboutStatement()).isFalse();
    // Nor is a write: there is no read-only statement it could be fixed into
    // that means the same thing.
    assertThat(refusalOf("DELETE FROM sales.customer", allowed()).aboutStatement()).isFalse();
    assertThat(refusalOf("", allowed()).aboutStatement()).isFalse();
  }

  @Test
  void theDefaultIsAboutTheStatementAndADenialIsNot() {
    assertThat(new QueryRewriter.RefusedException("x").aboutStatement()).isTrue();
    assertThat(new QueryRewriter.RefusedException("x", false).aboutStatement()).isFalse();
    assertThat(new QueryRewriter.DeniedException("a.b.c.d", "x").aboutStatement()).isFalse();
  }
}
