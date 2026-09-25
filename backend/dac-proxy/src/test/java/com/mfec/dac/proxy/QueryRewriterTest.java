package com.mfec.dac.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.compiler.sql.PostgresDialect;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import java.util.List;
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
