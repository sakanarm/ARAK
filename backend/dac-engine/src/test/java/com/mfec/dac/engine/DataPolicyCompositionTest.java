package com.mfec.dac.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.MaskingSpec.MaskingFunction;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedColumnMask.ScopeLevel;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.api.ResolvedRowPredicate.FacetOperator;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.ColumnRule;
import com.mfec.dac.schema.entity.policy.DataPolicy;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.RowFilter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a principal who is allowed in actually sees.
 *
 * <p>{@link PolicyEngineTest} settles who gets through the door. This settles
 * what is left of the table once they are inside: which rows survive, which
 * columns are masked and with what, and -- the part with the most ways to be
 * quietly wrong -- when one policy is permitted to undo another's restriction.
 *
 * <p>Each case is written so that failing it means exposure rather than
 * refusal. A masking bug that conceals too much is a support ticket; one that
 * conceals too little is the incident this platform exists to prevent, and it
 * leaves no trace on any screen because the query succeeds.
 */
class DataPolicyCompositionTest {

  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
  private static final RequestContext NOW =
      RequestContext.at(LocalDateTime.parse("2026-09-15T09:00").atZone(BANGKOK).toInstant());

  private static PolicyEngine engine() {
    return new PolicyEngine(EngineConfig.defaults().withZone(BANGKOK));
  }

  /** An engine whose expressions all settle the same way. */
  private static PolicyEngine engineWhere(ExpressionEvaluator.Result result) {
    return new PolicyEngine(
        EngineConfig.defaults()
            .withZone(BANGKOK)
            .withExpressions((expression, principal, asset, context) -> result));
  }

  private static Principal analyst() {
    return Principal.withId("analyst_a")
        .email("analyst_a@example.com")
        .roles("analyst")
        .teams("Finance")
        .attribute("department", "FINANCE")
        .attribute("clearance", "L1")
        .attribute("branch", "BKK-01")
        .attribute("branch", "CNX-01")
        .build();
  }

  private static AssetContext customer() {
    return AssetContext.of("prod-mssql.SalesDB.dbo.customer")
        .physicalFromFqn()
        .hierarchicalFacet(FacetType.DOMAINS, "Finance.Risk.Credit")
        .column(
            ColumnContext.named("citizen_id")
                .fqn("prod-mssql.SalesDB.dbo.customer.citizen_id")
                .dataType("VARCHAR")
                .facet(FacetType.TAGS, "PII", "PII.Sensitive"))
        .column(
            ColumnContext.named("email")
                .fqn("prod-mssql.SalesDB.dbo.customer.email")
                .dataType("VARCHAR")
                .facet(FacetType.TAGS, "PII", "PII.Sensitive"))
        .column(ColumnContext.named("salary").dataType("DECIMAL"))
        .column(ColumnContext.named("branch_code").dataType("VARCHAR"))
        .build();
  }

  // ------------------------------------------------------------- fixtures

  private static AssetSelector facet(FacetType type, FacetOperator operator, String value) {
    return new AssetSelector()
        .withCondition(
            new FacetCondition().withFacet(type).withOperator(operator).withValue(value));
  }

  private static AssetSelector columnNamed(String name) {
    return facet(FacetType.COLUMN_NAME, FacetOperator.EQ, name);
  }

  private static Policy policy(String name, ScopeLevel level, Policy.PolicyType type) {
    return new Policy()
        .withId(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)))
        .withName(name)
        .withVersion(1)
        .withPolicyType(type)
        .withScopeLevel(level)
        .withScopeFqn(level == ScopeLevel.ORG ? null : "prod-mssql.SalesDB.dbo.customer")
        .withEffect(Policy.Effect.ALLOW)
        .withSelector(facet(FacetType.TABLE, FacetOperator.EQ, "customer"));
  }

  /** The one subscription that lets the principal in, so the data rules are reached at all. */
  private static Policy doorIsOpen() {
    return policy("door", ScopeLevel.ORG, Policy.PolicyType.SUBSCRIPTION);
  }

  private static Policy data(String name, ScopeLevel level, ColumnRule... rules) {
    return policy(name, level, Policy.PolicyType.DATA)
        .withData(new DataPolicy().withColumnRules(List.of(rules)));
  }

  private static Policy rows(String name, ScopeLevel level, RowFilter... filters) {
    return policy(name, level, Policy.PolicyType.DATA)
        .withData(new DataPolicy().withRowFilters(List.of(filters)));
  }

  private static ColumnRule maskRule(String column, MaskingFunction function) {
    return new ColumnRule()
        .withAction(ColumnRule.Action.MASK)
        .withColumns(columnNamed(column))
        .withMasking(new MaskingSpec().withFunction(function));
  }

  private static ColumnRule allowRule(String column) {
    return new ColumnRule().withAction(ColumnRule.Action.ALLOW).withColumns(columnNamed(column));
  }

  private static ColumnRule hideRule(String column) {
    return new ColumnRule().withAction(ColumnRule.Action.HIDE).withColumns(columnNamed(column));
  }

  private static PolicyDecision decide(PolicyEngine engine, Policy... policies) {
    List<Policy> all = new ArrayList<>();
    all.add(doorIsOpen());
    all.addAll(List.of(policies));
    return engine.evaluate(analyst(), customer(), NOW, all);
  }

  private static PolicyDecision decide(Policy... policies) {
    return decide(engine(), policies);
  }

  private static List<String> maskedColumns(PolicyDecision decision) {
    List<String> out = new ArrayList<>();
    for (ResolvedColumnMask mask : decision.getColumnMasks()) {
      out.add(mask.getColumn());
    }
    return out;
  }

  private static ResolvedColumnMask maskOn(PolicyDecision decision, String column) {
    for (ResolvedColumnMask mask : decision.getColumnMasks()) {
      if (column.equals(mask.getColumn())) {
        return mask;
      }
    }
    return null;
  }

  // ------------------------------------------------------------ row filters

  @Nested
  @DisplayName("row filters")
  class RowFilters {

    @Test
    @DisplayName("every layer's filter survives, because they intersect rather than replace")
    void filtersFromSeveralLayersAllSurvive() {
      PolicyDecision decision =
          decide(
              rows(
                  "org-branch",
                  ScopeLevel.ORG,
                  new RowFilter()
                      .withKind(RowFilter.Kind.IN_LIST)
                      .withColumn("branch_code")
                      .withUserAttribute("branch")),
              rows(
                  "schema-live",
                  ScopeLevel.SCHEMA,
                  new RowFilter()
                      .withKind(RowFilter.Kind.RAW_PREDICATE)
                      .withRawPredicate("deleted_at IS NULL")),
              rows(
                  "table-entitlement",
                  ScopeLevel.TABLE,
                  new RowFilter()
                      .withKind(RowFilter.Kind.ENTITLEMENT_JOIN)
                      .withColumn("branch_code")
                      .withEntitlementKey("sales.customer")));

      assertThat(decision.getRowPredicates())
          .extracting(ResolvedRowPredicate::getKind)
          .containsExactlyInAnyOrder(
              ResolvedRowPredicate.Kind.IN_LIST,
              ResolvedRowPredicate.Kind.RAW_PREDICATE,
              ResolvedRowPredicate.Kind.ENTITLEMENT_JOIN);
    }

    @Test
    @DisplayName("a multi-valued attribute becomes the whole list, not its first element")
    void inListCarriesEveryValue() {
      PolicyDecision decision =
          decide(
              rows(
                  "branch",
                  ScopeLevel.ORG,
                  new RowFilter()
                      .withKind(RowFilter.Kind.IN_LIST)
                      .withColumn("branch_code")
                      .withUserAttribute("branch")));

      assertThat(decision.getRowPredicates().get(0).getValues())
          .containsExactly("BKK-01", "CNX-01");
    }

    @Test
    @DisplayName("a filter with no kind hides every row, which is the safe reading of a blank")
    void missingKindIsAlwaysFalse() {
      PolicyDecision decision =
          decide(rows("blank", ScopeLevel.ORG, new RowFilter().withColumn("branch_code")));

      assertThat(decision.getRowPredicates().get(0).getKind())
          .isEqualTo(ResolvedRowPredicate.Kind.ALWAYS_FALSE);
    }

    @Test
    @DisplayName("an entitlement join keeps the key the generated view will join on")
    void entitlementJoinKeepsItsKey() {
      PolicyDecision decision =
          decide(
              rows(
                  "ent",
                  ScopeLevel.ORG,
                  new RowFilter()
                      .withKind(RowFilter.Kind.ENTITLEMENT_JOIN)
                      .withColumn("branch_code")
                      .withEntitlementKey("sales.customer")));

      ResolvedRowPredicate predicate = decision.getRowPredicates().get(0);
      assertThat(predicate.getEntitlementKey()).isEqualTo("sales.customer");
      assertThat(predicate.getColumn()).isEqualTo("branch_code");
    }

    @Test
    @DisplayName("every predicate names the policy it came from, or nobody can explain the result")
    void everyPredicateNamesItsSource() {
      PolicyDecision decision =
          decide(
              rows(
                  "branch",
                  ScopeLevel.ORG,
                  new RowFilter()
                      .withKind(RowFilter.Kind.IN_LIST)
                      .withColumn("branch_code")
                      .withUserAttribute("branch")));

      assertThat(decision.getRowPredicates().get(0).getSourcePolicyId()).isNotNull();
    }

    /**
     * The three compilers render the same decision and their output is compared
     * byte for byte in CI (FR-6.0c). A list whose order follows whatever order
     * the policies happened to be loaded in cannot survive that comparison, and
     * the way it fails is a diff nobody can reproduce on demand.
     */
    @Test
    @DisplayName("the order the policies arrive in does not change the order of the predicates")
    void rowPredicateOrderIsDeterministic() {
      Policy branch =
          rows(
              "org-branch",
              ScopeLevel.ORG,
              new RowFilter()
                  .withKind(RowFilter.Kind.IN_LIST)
                  .withColumn("branch_code")
                  .withUserAttribute("branch"));
      Policy live =
          rows(
              "schema-live",
              ScopeLevel.SCHEMA,
              new RowFilter()
                  .withKind(RowFilter.Kind.RAW_PREDICATE)
                  .withRawPredicate("deleted_at IS NULL"));
      Policy entitlement =
          rows(
              "table-entitlement",
              ScopeLevel.TABLE,
              new RowFilter()
                  .withKind(RowFilter.Kind.ENTITLEMENT_JOIN)
                  .withColumn("branch_code")
                  .withEntitlementKey("sales.customer"));

      assertThat(render(decide(branch, live, entitlement)))
          .isEqualTo(render(decide(entitlement, branch, live)))
          .isEqualTo(render(decide(live, entitlement, branch)));
    }

    private List<String> render(PolicyDecision decision) {
      List<String> out = new ArrayList<>();
      for (ResolvedRowPredicate predicate : decision.getRowPredicates()) {
        out.add(
            predicate.getKind()
                + "|"
                + predicate.getColumn()
                + "|"
                + predicate.getValues()
                + "|"
                + predicate.getEntitlementKey()
                + "|"
                + predicate.getRawPredicate());
      }
      return out;
    }
  }

  // ---------------------------------------------------------- column masks

  @Nested
  @DisplayName("column masks")
  class ColumnMasks {

    @Test
    @DisplayName("a rule selecting by tag covers every column carrying it, not just a named one")
    void tagSelectorCoversEveryTaggedColumn() {
      PolicyDecision decision =
          decide(
              data(
                  "pii",
                  ScopeLevel.ORG,
                  new ColumnRule()
                      .withAction(ColumnRule.Action.MASK)
                      .withColumns(facet(FacetType.TAGS, FacetOperator.CONTAINS, "PII"))
                      .withMasking(new MaskingSpec().withFunction(MaskingFunction.HASH))));

      assertThat(maskedColumns(decision)).containsExactly("citizen_id", "email");
    }

    @Test
    @DisplayName("an unconditional mask outranks a conditional one of the same function")
    void unconditionalBeatsConditionalOfEqualStrength() {
      PolicyDecision decision =
          decide(
              data(
                  "cell",
                  ScopeLevel.ORG,
                  maskRule("salary", MaskingFunction.NULLIFY)
                      .withCondition("department <> user.department")),
              data("always", ScopeLevel.TABLE, maskRule("salary", MaskingFunction.NULLIFY)));

      assertThat(maskOn(decision, "salary").getCondition()).isNull();
    }

    @Test
    @DisplayName("a conditional mask keeps its condition when nothing stronger displaces it")
    void conditionSurvivesToTheCompiler() {
      PolicyDecision decision =
          decide(
              engineWhere(ExpressionEvaluator.Result.ROW_DEPENDENT),
              data(
                  "cell",
                  ScopeLevel.ORG,
                  maskRule("salary", MaskingFunction.NULLIFY)
                      .withCondition("department <> user.department")));

      assertThat(maskOn(decision, "salary").getCondition())
          .isEqualTo("department <> user.department");
    }

    @Test
    @DisplayName("a condition already known to be true is settled here, not re-tested per row")
    void settledConditionIsDropped() {
      PolicyDecision decision =
          decide(
              engineWhere(ExpressionEvaluator.Result.TRUE),
              data(
                  "cell",
                  ScopeLevel.ORG,
                  maskRule("salary", MaskingFunction.NULLIFY).withCondition("1 = 1")));

      assertThat(maskOn(decision, "salary")).isNotNull();
      assertThat(maskOn(decision, "salary").getCondition()).isNull();
    }

    @Test
    @DisplayName("a condition known to be false withdraws the mask entirely")
    void falseConditionWithdrawsTheMask() {
      PolicyDecision decision =
          decide(
              engineWhere(ExpressionEvaluator.Result.FALSE),
              data(
                  "cell",
                  ScopeLevel.ORG,
                  maskRule("salary", MaskingFunction.NULLIFY).withCondition("1 = 0")));

      assertThat(decision.getColumnMasks()).isEmpty();
    }

    @Test
    @DisplayName("every mask records the layer that imposed it, for the explanation screen")
    void maskNamesItsLayer() {
      PolicyDecision decision =
          decide(data("schema-rule", ScopeLevel.SCHEMA, maskRule("email", MaskingFunction.HASH)));

      assertThat(maskOn(decision, "email").getSourceScopeLevel()).isEqualTo(ScopeLevel.SCHEMA);
      assertThat(maskOn(decision, "email").getSourcePolicyId()).isNotNull();
    }

    @Test
    @DisplayName("masks come out in column order whatever order the policies arrived in")
    void masksAreSorted() {
      PolicyDecision decision =
          decide(
              data("s", ScopeLevel.ORG, maskRule("salary", MaskingFunction.NULLIFY)),
              data("e", ScopeLevel.ORG, maskRule("email", MaskingFunction.HASH)),
              data("c", ScopeLevel.ORG, maskRule("citizen_id", MaskingFunction.PARTIAL)));

      assertThat(maskedColumns(decision)).containsExactly("citizen_id", "email", "salary");
    }

    @Test
    @DisplayName("a data policy carrying effect DENY still masks; the effect is not a switch")
    void denyEffectOnADataPolicyStillRestricts() {
      PolicyDecision decision =
          decide(
              data("deny-flavoured", ScopeLevel.ORG, maskRule("email", MaskingFunction.HASH))
                  .withEffect(Policy.Effect.DENY));

      assertThat(maskedColumns(decision)).containsExactly("email");
    }
  }

  // --------------------------------------------------------------- release

  @Nested
  @DisplayName("releasing a restriction")
  class Release {

    private Policy globalMask(boolean overridable) {
      return data("global-pii", ScopeLevel.ORG, maskRule("email", MaskingFunction.HASH))
          .withAllowLocalOverride(overridable);
    }

    private Policy localRelease(String condition) {
      return data("local-release", ScopeLevel.TABLE, allowRule("email").withCondition(condition));
    }

    @Test
    @DisplayName("a release from a deeper layer unmasks a policy that consented")
    void consentedMaskIsReleased() {
      assertThat(decide(globalMask(true), localRelease(null)).getColumnMasks()).isEmpty();
    }

    @Test
    @DisplayName("and does not unmask one that did not")
    void unconsentedMaskSurvives() {
      assertThat(maskedColumns(decide(globalMask(false), localRelease(null))))
          .containsExactly("email");
    }

    @Test
    @DisplayName("a release from the same layer is no more specific, so it releases nothing")
    void sameLayerReleasesNothing() {
      Policy sameLayer = data("same-layer", ScopeLevel.ORG, allowRule("email"));

      assertThat(maskedColumns(decide(globalMask(true), sameLayer))).containsExactly("email");
    }

    @Test
    @DisplayName("a release whose condition is false releases nothing")
    void falseConditionReleasesNothing() {
      PolicyDecision decision =
          decide(
              engineWhere(ExpressionEvaluator.Result.FALSE),
              globalMask(true),
              localRelease("user.clearance >= 'L2'"));

      assertThat(maskedColumns(decision)).containsExactly("email");
    }

    @Test
    @DisplayName("a release whose condition is true releases the column")
    void trueConditionReleases() {
      PolicyDecision decision =
          decide(
              engineWhere(ExpressionEvaluator.Result.TRUE),
              globalMask(true),
              localRelease("user.clearance >= 'L2'"));

      assertThat(decision.getColumnMasks()).isEmpty();
    }

    /**
     * The case the rest of the engine is careful about and this path was not.
     *
     * <p>A release is a conditional grant of plaintext, and a restriction has
     * nowhere to record "released only for some rows" -- it is either kept or
     * dropped. When the condition cannot be settled, dropping it hands the
     * column to exactly the principals the condition existed to exclude. Every
     * other undecidable input in this engine counts against the principal; this
     * one has to as well.
     */
    @Test
    @DisplayName("a release whose condition cannot be decided releases nothing")
    void undecidableConditionReleasesNothing() {
      PolicyDecision decision =
          decide(
              engineWhere(ExpressionEvaluator.Result.ROW_DEPENDENT),
              globalMask(true),
              localRelease("user.clearance >= 'L2'"));

      assertThat(maskedColumns(decision)).containsExactly("email");
    }

    /**
     * The same case with no evaluator configured at all, which is the engine's
     * own default. {@link PolicyExpressionEvaluator} is wired in by the service,
     * so the realistic route to this path is the first test above: a condition
     * mentioning {@code row.} is row-dependent by design, and an unreadable one
     * arrives here too. Both are ordinary things for a policy author to write.
     */
    @Test
    @DisplayName("and neither does one on an engine with no expression evaluator at all")
    void unavailableEvaluatorReleasesNothing() {
      PolicyDecision decision =
          decide(engine(), globalMask(true), localRelease("user.clearance >= 'L2'"));

      assertThat(maskedColumns(decision)).containsExactly("email");
    }

    @Test
    @DisplayName("a refused release is on the record, so the author can see why it did nothing")
    void refusedReleaseIsExplained() {
      PolicyDecision decision =
          decide(engine(), globalMask(true), localRelease("user.clearance >= 'L2'"));

      StringBuilder sb = new StringBuilder();
      decision.getReasons().forEach(r -> sb.append(r.getExplanation()).append('\n'));
      assertThat(sb.toString()).contains("cannot be decided");
    }
  }

  // ------------------------------------------------------------------ hide

  @Nested
  @DisplayName("hiding a column")
  class Hide {

    @Test
    @DisplayName("a hidden column is absent from the projection, not blanked in it")
    void hiddenIsNotMasked() {
      PolicyDecision decision = decide(data("hide", ScopeLevel.ORG, hideRule("salary")));

      assertThat(decision.getHiddenColumns()).containsExactly("salary");
      assertThat(decision.getColumnMasks()).isEmpty();
    }

    @Test
    @DisplayName("a hide that consented to override is released by a deeper allow")
    void consentedHideIsReleased() {
      Policy hide = data("hide", ScopeLevel.ORG, hideRule("salary")).withAllowLocalOverride(true);
      Policy release = data("release", ScopeLevel.TABLE, allowRule("salary"));

      assertThat(decide(hide, release).getHiddenColumns()).isEmpty();
    }

    @Test
    @DisplayName("one that did not consent stays hidden")
    void unconsentedHideSurvives() {
      Policy hide = data("hide", ScopeLevel.ORG, hideRule("salary"));
      Policy release = data("release", ScopeLevel.TABLE, allowRule("salary"));

      assertThat(decide(hide, release).getHiddenColumns()).containsExactly("salary");
    }

    @Test
    @DisplayName("hidden columns come out sorted, like the masks")
    void hiddenColumnsAreSorted() {
      PolicyDecision decision =
          decide(
              data("h1", ScopeLevel.ORG, hideRule("salary")),
              data("h2", ScopeLevel.ORG, hideRule("email")),
              data("h3", ScopeLevel.ORG, hideRule("citizen_id")));

      assertThat(decision.getHiddenColumns()).containsExactly("citizen_id", "email", "salary");
    }
  }
}
