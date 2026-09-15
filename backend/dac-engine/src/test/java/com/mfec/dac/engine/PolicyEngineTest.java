package com.mfec.dac.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.MaskingSpec.MaskingFunction;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask.ScopeLevel;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.api.ResolvedRowPredicate.FacetOperator;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.ColumnRule;
import com.mfec.dac.schema.entity.policy.DataPolicy;
import com.mfec.dac.schema.entity.policy.Exemption;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.PrincipalMatch;
import com.mfec.dac.schema.entity.policy.RowFilter;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import com.mfec.dac.schema.entity.policy.TimeRule;
import com.mfec.dac.schema.entity.policy.TimeWindow;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The composition rules, exercised at the level the compilers see them.
 *
 * <p>Every case here is a rule that the requirement states and that a plausible
 * implementation gets backwards: a layer that grants nothing still gates, a
 * local policy tightens but does not loosen, an undecidable input counts against
 * the principal rather than for them.
 */
class PolicyEngineTest {

  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
  private static final EngineConfig CONFIG = EngineConfig.defaults().withZone(BANGKOK);
  private static final PolicyEngine ENGINE = new PolicyEngine(CONFIG);

  private static Instant bangkok(String dateTime) {
    return LocalDateTime.parse(dateTime).atZone(BANGKOK).toInstant();
  }

  /** 2026-09-15 is a Tuesday, inside office hours. */
  private static final RequestContext NOW = RequestContext.at(bangkok("2026-09-15T09:00"));

  private static Principal analyst() {
    return Principal.withId("analyst_a")
        .email("analyst_a@example.com")
        .roles("analyst")
        .teams("Finance")
        .attribute("department", "FINANCE")
        .attribute("clearance", "L1")
        .build();
  }

  private static AssetContext customer() {
    return AssetContext.of("prod-mssql.SalesDB.dbo.customer")
        .physicalFromFqn()
        .hierarchicalFacet(FacetType.DOMAINS, "Finance.Risk.Credit")
        .column(
            ColumnContext.named("email")
                .fqn("prod-mssql.SalesDB.dbo.customer.email")
                .dataType("VARCHAR")
                .facet(FacetType.TAGS, "PII", "PII.Sensitive"))
        .column(ColumnContext.named("branch_code").dataType("VARCHAR"))
        .build();
  }

  private static AssetSelector table(String name) {
    return new AssetSelector()
        .withCondition(
            new FacetCondition()
                .withFacet(FacetType.TABLE)
                .withOperator(FacetOperator.EQ)
                .withValue(name));
  }

  /**
   * Ids are derived from the name rather than random, so a test that compares
   * two cache keys is comparing the thing it means to compare.
   */
  private static Policy policy(String name, ScopeLevel level, Policy.PolicyType type,
      Policy.Effect effect) {
    return new Policy()
        .withId(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)))
        .withName(name)
        .withVersion(1)
        .withPolicyType(type)
        .withScopeLevel(level)
        .withScopeFqn(level == ScopeLevel.ORG ? null : "prod-mssql.SalesDB.dbo.customer")
        .withEffect(effect)
        .withSelector(table("customer"));
  }

  private static Policy subscription(String name, ScopeLevel level, Policy.Effect effect) {
    return policy(name, level, Policy.PolicyType.SUBSCRIPTION, effect);
  }

  private static Policy dataPolicy(String name, ScopeLevel level, DataPolicy data) {
    return policy(name, level, Policy.PolicyType.DATA, Policy.Effect.ALLOW).withData(data);
  }

  private static SubjectRule onlyRole(String role) {
    return new SubjectRule().withPrincipals(List.of(new PrincipalMatch().withRole(role)));
  }

  private static DataPolicy mask(String column, MaskingFunction function) {
    return new DataPolicy()
        .withColumnRules(
            List.of(
                new ColumnRule()
                    .withAction(ColumnRule.Action.MASK)
                    .withColumns(columnNamed(column))
                    .withMasking(new MaskingSpec().withFunction(function))));
  }

  private static AssetSelector columnNamed(String name) {
    return new AssetSelector()
        .withCondition(
            new FacetCondition()
                .withFacet(FacetType.COLUMN_NAME)
                .withOperator(FacetOperator.EQ)
                .withValue(name));
  }

  private static PolicyDecision decide(List<Policy> policies) {
    return ENGINE.evaluate(analyst(), customer(), NOW, policies);
  }

  private static PolicyDecision decide(Principal principal, List<Policy> policies) {
    return ENGINE.evaluate(principal, customer(), NOW, policies);
  }

  private static String explanations(PolicyDecision decision) {
    StringBuilder sb = new StringBuilder();
    decision.getReasons().forEach(r -> sb.append(r.getExplanation()).append('\n'));
    return sb.toString();
  }

  // ------------------------------------------------------------ subscription

  @Test
  @DisplayName("an asset no policy reaches is closed, not open")
  void noPolicyAtAllIsDenied() {
    PolicyDecision decision = decide(List.of());
    assertThat(decision.getAllowed()).isFalse();
    assertThat(explanations(decision)).contains("denied by default");
  }

  @Test
  @DisplayName("one org-wide allow is enough when nothing else has an opinion")
  void orgAllowGrants() {
    PolicyDecision decision =
        decide(List.of(subscription("org-allow", ScopeLevel.ORG, Policy.Effect.ALLOW)));
    assertThat(decision.getAllowed()).isTrue();
    assertThat(decision.getPrincipal()).isEqualTo("analyst_a");
    assertThat(decision.getAssetFqn()).isEqualTo("prod-mssql.SalesDB.dbo.customer");
    assertThat(decision.getEvaluatedAt()).isEqualTo(NOW.at());
  }

  @Test
  @DisplayName("a matched deny beats any number of allows, at any layer")
  void denyBeatsAllow() {
    PolicyDecision decision =
        decide(
            List.of(
                subscription("org-allow", ScopeLevel.ORG, Policy.Effect.ALLOW),
                subscription("table-deny", ScopeLevel.TABLE, Policy.Effect.DENY)));
    assertThat(decision.getAllowed()).isFalse();
    assertThat(explanations(decision)).contains("a deny always beats an allow");
  }

  @Test
  @DisplayName("every layer that grants to somebody must grant to this principal")
  void everyLayerMustAllow() {
    // The org policy opens the door; the schema policy is a second door, and it
    // only opens for admins. Composition is an intersection, so the principal is
    // out even though a policy allowed them.
    PolicyDecision decision =
        decide(
            List.of(
                subscription("org-allow", ScopeLevel.ORG, Policy.Effect.ALLOW),
                subscription("schema-allow", ScopeLevel.SCHEMA, Policy.Effect.ALLOW)
                    .withSubject(onlyRole("admin"))));
    assertThat(decision.getAllowed()).isFalse();
    assertThat(explanations(decision)).contains("every layer must allow");
  }

  @Test
  @DisplayName("a local allow cannot open a door the global policy kept shut")
  void localCannotRelaxWithoutOverride() {
    PolicyDecision decision =
        decide(
            List.of(
                subscription("org-admins-only", ScopeLevel.ORG, Policy.Effect.ALLOW)
                    .withSubject(onlyRole("admin")),
                subscription("table-allow", ScopeLevel.TABLE, Policy.Effect.ALLOW)));
    assertThat(decision.getAllowed()).isFalse();
  }

  @Test
  @DisplayName("it can when the global policy consented to being overridden")
  void localRelaxesWhenOverrideSet() {
    PolicyDecision decision =
        decide(
            List.of(
                subscription("org-admins-only", ScopeLevel.ORG, Policy.Effect.ALLOW)
                    .withSubject(onlyRole("admin"))
                    .withAllowLocalOverride(true),
                subscription("table-allow", ScopeLevel.TABLE, Policy.Effect.ALLOW)));
    assertThat(decision.getAllowed()).isTrue();
    assertThat(explanations(decision)).contains("allowLocalOverride");
  }

  @Test
  @DisplayName("a deny that allows override yields to a more specific allow")
  void denyYieldsWhenOverrideSet() {
    PolicyDecision decision =
        decide(
            List.of(
                subscription("org-deny", ScopeLevel.ORG, Policy.Effect.DENY)
                    .withAllowLocalOverride(true),
                subscription("table-allow", ScopeLevel.TABLE, Policy.Effect.ALLOW)));
    assertThat(decision.getAllowed()).isTrue();
  }

  @Test
  @DisplayName("a draft policy grants nothing")
  void draftPolicyIsIgnored() {
    PolicyDecision decision =
        decide(
            List.of(
                subscription("org-allow", ScopeLevel.ORG, Policy.Effect.ALLOW)
                    .withLifecycleState(Policy.LifecycleState.DRAFT)));
    assertThat(decision.getAllowed()).isFalse();
    assertThat(explanations(decision)).contains("no subscription policy binds");
  }

  @Test
  @DisplayName("a policy whose validity has run out grants nothing")
  void expiredPolicyIsIgnored() {
    PolicyDecision decision =
        decide(
            List.of(
                subscription("org-allow", ScopeLevel.ORG, Policy.Effect.ALLOW)
                    .withValidUntil(bangkok("2026-01-01T00:00"))));
    assertThat(decision.getAllowed()).isFalse();
  }

  @Test
  @DisplayName("a policy that has not started yet grants nothing")
  void futurePolicyIsIgnored() {
    PolicyDecision decision =
        decide(
            List.of(
                subscription("org-allow", ScopeLevel.ORG, Policy.Effect.ALLOW)
                    .withValidFrom(bangkok("2027-01-01T00:00"))));
    assertThat(decision.getAllowed()).isFalse();
  }

  @Test
  @DisplayName("an exempt principal is outside the deny that names them")
  void exemptPrincipalIsNotDenied() {
    Policy deny =
        subscription("org-deny", ScopeLevel.ORG, Policy.Effect.DENY)
            .withExemptions(
                List.of(
                    new Exemption()
                        .withPrincipal("analyst_a")
                        .withReason("fraud investigation")
                        .withExpiresAt(bangkok("2026-12-31T00:00"))));
    PolicyDecision decision =
        decide(List.of(subscription("org-allow", ScopeLevel.ORG, Policy.Effect.ALLOW), deny));
    assertThat(decision.getAllowed()).isTrue();
    assertThat(explanations(decision)).contains("principal is exempt");
  }

  @Test
  @DisplayName("an exemption with no expiry is an unfinished record, not a permanent hole")
  void exemptionWithoutExpiryIsIgnored() {
    Policy deny =
        subscription("org-deny", ScopeLevel.ORG, Policy.Effect.DENY)
            .withExemptions(
                List.of(new Exemption().withPrincipal("analyst_a").withReason("forever?")));
    PolicyDecision decision =
        decide(List.of(subscription("org-allow", ScopeLevel.ORG, Policy.Effect.ALLOW), deny));
    assertThat(decision.getAllowed()).isFalse();
  }

  @Test
  @DisplayName("a policy whose selector misses this asset is never consulted")
  void selectorThatDoesNotMatchAssetIsSkipped() {
    PolicyDecision decision =
        decide(
            List.of(
                subscription("orders-allow", ScopeLevel.ORG, Policy.Effect.ALLOW)
                    .withSelector(table("orders"))));
    assertThat(decision.getAllowed()).isFalse();
    assertThat(explanations(decision)).contains("no subscription policy binds");
  }

  @Test
  @DisplayName("deny-only policies gate nothing, but they still grant nothing either")
  void denyOnlyBindingIsDenied() {
    PolicyDecision decision =
        decide(
            List.of(
                subscription("org-deny-admins", ScopeLevel.ORG, Policy.Effect.DENY)
                    .withSubject(onlyRole("admin"))));
    assertThat(decision.getAllowed()).isFalse();
    assertThat(explanations(decision)).contains("only deny policies bind");
  }

  // -------------------------------------------------------------- data policy

  private static List<Policy> withAccess(Policy... policies) {
    List<Policy> all = new java.util.ArrayList<>();
    all.add(subscription("org-allow", ScopeLevel.ORG, Policy.Effect.ALLOW));
    all.addAll(List.of(policies));
    return all;
  }

  @Test
  @DisplayName("the strictest mask on a column wins, whichever layer wrote it")
  void strictestMaskWins() {
    PolicyDecision decision =
        decide(
            withAccess(
                dataPolicy("org-partial", ScopeLevel.ORG, mask("email", MaskingFunction.PARTIAL)),
                dataPolicy(
                    "table-nullify", ScopeLevel.TABLE, mask("email", MaskingFunction.NULLIFY))));
    assertThat(decision.getColumnMasks()).hasSize(1);
    assertThat(decision.getColumnMasks().get(0).getColumn()).isEqualTo("email");
    assertThat(decision.getColumnMasks().get(0).getMasking().getFunction())
        .isEqualTo(MaskingFunction.NULLIFY);
  }

  @Test
  @DisplayName("the order the policies arrive in does not change the answer")
  void maskCompositionIsOrderIndependent() {
    Policy partial =
        dataPolicy("org-partial", ScopeLevel.ORG, mask("email", MaskingFunction.PARTIAL));
    Policy nullify =
        dataPolicy("table-nullify", ScopeLevel.TABLE, mask("email", MaskingFunction.NULLIFY));
    PolicyDecision one = decide(withAccess(partial, nullify));
    PolicyDecision other = decide(withAccess(nullify, partial));
    assertThat(one.getColumnMasks().get(0).getMasking().getFunction())
        .isEqualTo(other.getColumnMasks().get(0).getMasking().getFunction());
  }

  @Test
  @DisplayName("hiding a column leaves nothing for a mask to apply to")
  void hideBeatsMask() {
    DataPolicy hide =
        new DataPolicy()
            .withColumnRules(
                List.of(
                    new ColumnRule()
                        .withAction(ColumnRule.Action.HIDE)
                        .withColumns(columnNamed("email"))));
    PolicyDecision decision =
        decide(
            withAccess(
                dataPolicy("org-partial", ScopeLevel.ORG, mask("email", MaskingFunction.PARTIAL)),
                dataPolicy("table-hide", ScopeLevel.TABLE, hide)));
    assertThat(decision.getHiddenColumns()).containsExactly("email");
    assertThat(decision.getColumnMasks()).isEmpty();
  }

  @Test
  @DisplayName("a mask asking for no function is honoured as NULLIFY, not as plaintext")
  void maskWithoutFunctionDegradesToNullify() {
    DataPolicy vague =
        new DataPolicy()
            .withColumnRules(
                List.of(
                    new ColumnRule()
                        .withAction(ColumnRule.Action.MASK)
                        .withColumns(columnNamed("email"))));
    PolicyDecision decision = decide(withAccess(dataPolicy("org-vague", ScopeLevel.ORG, vague)));
    assertThat(decision.getColumnMasks().get(0).getMasking().getFunction())
        .isEqualTo(MaskingFunction.NULLIFY);
    assertThat(explanations(decision)).contains("NULLIFY applied");
  }

  @Test
  @DisplayName("a local ALLOW does not unmask a global policy that did not consent")
  void maskSurvivesAllowWithoutOverride() {
    PolicyDecision decision =
        decide(
            withAccess(
                dataPolicy("org-mask", ScopeLevel.ORG, mask("email", MaskingFunction.HASH)),
                dataPolicy("table-allow", ScopeLevel.TABLE, allowColumn("email"))));
    assertThat(decision.getColumnMasks()).hasSize(1);
  }

  @Test
  @DisplayName("it does unmask one that did, and the release is on the record")
  void allowReleasesWhenOverrideSet() {
    PolicyDecision decision =
        decide(
            withAccess(
                dataPolicy("org-mask", ScopeLevel.ORG, mask("email", MaskingFunction.HASH))
                    .withAllowLocalOverride(true),
                dataPolicy("table-allow", ScopeLevel.TABLE, allowColumn("email"))));
    assertThat(decision.getColumnMasks()).isEmpty();
    assertThat(explanations(decision)).contains("released by a more specific policy");
  }

  @Test
  @DisplayName("an ALLOW column rule that names no columns unmasks nothing")
  void allowWithoutColumnsReleasesNothing() {
    DataPolicy blanketAllow =
        new DataPolicy()
            .withColumnRules(List.of(new ColumnRule().withAction(ColumnRule.Action.ALLOW)));
    PolicyDecision decision =
        decide(
            withAccess(
                dataPolicy("org-mask", ScopeLevel.ORG, mask("email", MaskingFunction.HASH))
                    .withAllowLocalOverride(true),
                dataPolicy("table-allow-all", ScopeLevel.TABLE, blanketAllow)));
    assertThat(decision.getColumnMasks()).hasSize(1);
    assertThat(explanations(decision)).contains("releases nothing");
  }

  @Test
  @DisplayName("a rule naming no columns masks every column, because that is the safe reading")
  void maskWithoutColumnsCoversTheWholeTable() {
    DataPolicy everything =
        new DataPolicy()
            .withColumnRules(
                List.of(
                    new ColumnRule()
                        .withAction(ColumnRule.Action.MASK)
                        .withMasking(new MaskingSpec().withFunction(MaskingFunction.NULLIFY))));
    PolicyDecision decision =
        decide(withAccess(dataPolicy("org-all", ScopeLevel.ORG, everything)));
    assertThat(decision.getColumnMasks())
        .extracting(m -> m.getColumn())
        .containsExactly("branch_code", "email");
  }

  private static DataPolicy allowColumn(String column) {
    return new DataPolicy()
        .withColumnRules(
            List.of(
                new ColumnRule()
                    .withAction(ColumnRule.Action.ALLOW)
                    .withColumns(columnNamed(column))));
  }

  @Test
  @DisplayName("a row filter compares against the attribute the principal actually carries")
  void rowFilterResolvesAttributeValues() {
    Principal branched =
        Principal.withId("analyst_a").roles("analyst").attribute("branch", "BKK-01").build();
    PolicyDecision decision =
        decide(branched, withAccess(dataPolicy("org-rls", ScopeLevel.ORG, branchFilter())));
    assertThat(decision.getRowPredicates()).hasSize(1);
    ResolvedRowPredicate predicate = decision.getRowPredicates().get(0);
    assertThat(predicate.getKind()).isEqualTo(ResolvedRowPredicate.Kind.ATTRIBUTE_COMPARE);
    assertThat(predicate.getColumn()).isEqualTo("branch_code");
    assertThat(predicate.getValues()).containsExactly("BKK-01");
  }

  @Test
  @DisplayName("a row filter whose attribute is missing hides every row rather than none")
  void rowFilterMissingAttributeBecomesAlwaysFalse() {
    PolicyDecision decision =
        decide(withAccess(dataPolicy("org-rls", ScopeLevel.ORG, branchFilter())));
    assertThat(decision.getRowPredicates()).hasSize(1);
    assertThat(decision.getRowPredicates().get(0).getKind())
        .isEqualTo(ResolvedRowPredicate.Kind.ALWAYS_FALSE);
    assertThat(explanations(decision)).contains("no rows match");
  }

  private static DataPolicy branchFilter() {
    return new DataPolicy()
        .withRowFilters(
            List.of(
                new RowFilter()
                    .withKind(RowFilter.Kind.ATTRIBUTE_COMPARE)
                    .withColumn("branch_code")
                    .withOperator(FacetOperator.EQ)
                    .withUserAttribute("branch")));
  }

  @Test
  @DisplayName("a denied decision carries no masks and no filters to compile")
  void deniedDecisionCarriesNothingToEnforce() {
    PolicyDecision decision =
        decide(
            List.of(
                subscription("org-deny", ScopeLevel.ORG, Policy.Effect.DENY),
                dataPolicy("org-mask", ScopeLevel.ORG, mask("email", MaskingFunction.NULLIFY)),
                dataPolicy("org-rls", ScopeLevel.ORG, branchFilter())));
    assertThat(decision.getAllowed()).isFalse();
    assertThat(decision.getColumnMasks()).isEmpty();
    assertThat(decision.getRowPredicates()).isEmpty();
    assertThat(decision.getHiddenColumns()).isEmpty();
    assertThat(decision.getReasons()).isNotEmpty();
  }

  @Test
  @DisplayName("a data policy whose subject excludes this principal restricts nothing")
  void dataPolicyForSomebodyElseDoesNotApply() {
    PolicyDecision decision =
        decide(
            withAccess(
                dataPolicy("org-mask", ScopeLevel.ORG, mask("email", MaskingFunction.NULLIFY))
                    .withSubject(onlyRole("admin"))));
    assertThat(decision.getColumnMasks()).isEmpty();
  }

  // ------------------------------------------------------------- cache key

  @Test
  @DisplayName("editing a policy changes the cache key, so nobody has to remember to invalidate")
  void cacheKeyChangesWithPolicyVersion() {
    Policy v1 = subscription("org-allow", ScopeLevel.ORG, Policy.Effect.ALLOW);
    Policy v2 = subscription("org-allow", ScopeLevel.ORG, Policy.Effect.ALLOW).withVersion(2);
    assertThat(decide(List.of(v1)).getCacheKey()).isNotEqualTo(decide(List.of(v2)).getCacheKey());
  }

  @Test
  @DisplayName("the same inputs produce the same key")
  void cacheKeyIsStable() {
    Policy allow = subscription("org-allow", ScopeLevel.ORG, Policy.Effect.ALLOW);
    assertThat(decide(List.of(allow)).getCacheKey())
        .isEqualTo(decide(List.of(allow)).getCacheKey());
  }

  @Test
  @DisplayName("only a time-dependent policy puts the clock into the key")
  void cacheKeyIncludesMinuteOnlyWhenTimeDependent() {
    Policy plain = subscription("org-allow", ScopeLevel.ORG, Policy.Effect.ALLOW);
    Instant later = bangkok("2026-09-15T09:30");
    assertThat(ENGINE.evaluate(analyst(), customer(), NOW, List.of(plain)).getCacheKey())
        .isEqualTo(
            ENGINE
                .evaluate(analyst(), customer(), RequestContext.at(later), List.of(plain))
                .getCacheKey());

    Policy timed =
        subscription("org-office-hours", ScopeLevel.ORG, Policy.Effect.ALLOW)
            .withSubject(
                new SubjectRule()
                    .withTime(
                        new TimeRule()
                            .withWindows(
                                List.of(
                                    new TimeWindow()
                                        .withDays(List.of("MON-FRI"))
                                        .withFrom("08:00")
                                        .withTo("18:00")
                                        .withTimezone("Asia/Bangkok")))));
    PolicyDecision inHours = ENGINE.evaluate(analyst(), customer(), NOW, List.of(timed));
    assertThat(inHours.getAllowed()).isTrue();
    assertThat(inHours.getCacheKey())
        .isNotEqualTo(
            ENGINE
                .evaluate(analyst(), customer(), RequestContext.at(later), List.of(timed))
                .getCacheKey());
  }

  @Test
  @DisplayName("a time-bounded policy stops granting once the window closes")
  void outsideOfficeHoursIsDenied() {
    Policy timed =
        subscription("org-office-hours", ScopeLevel.ORG, Policy.Effect.ALLOW)
            .withSubject(
                new SubjectRule()
                    .withTime(
                        new TimeRule()
                            .withWindows(
                                List.of(
                                    new TimeWindow()
                                        .withDays(List.of("MON-FRI"))
                                        .withFrom("08:00")
                                        .withTo("18:00")
                                        .withTimezone("Asia/Bangkok")))));
    PolicyDecision evening =
        ENGINE.evaluate(
            analyst(),
            customer(),
            RequestContext.at(bangkok("2026-09-15T20:00")),
            List.of(timed));
    assertThat(evening.getAllowed()).isFalse();
    assertThat(explanations(evening)).contains("time window");
  }

  // ---------------------------------------------------------------- layering

  @Test
  @DisplayName("a deeper sub-domain sits below a shallower one, so it can only tighten")
  void subDomainDepthOrdersTheDomainLayer() {
    Policy broad =
        subscription("finance", ScopeLevel.DOMAIN, Policy.Effect.ALLOW).withScopeFqn("Finance");
    Policy narrow =
        subscription("credit", ScopeLevel.DOMAIN, Policy.Effect.ALLOW)
            .withScopeFqn("Finance.Risk.Credit");
    assertThat(PolicyEngine.layerOf(broad)).isLessThan(PolicyEngine.layerOf(narrow));
    assertThat(PolicyEngine.layerOf(narrow))
        .isLessThan(
            PolicyEngine.layerOf(subscription("svc", ScopeLevel.SERVICE, Policy.Effect.ALLOW)));
  }

  @Test
  @DisplayName("the layers run ORG through COLUMN, in that order")
  void layerOrder() {
    assertThat(
            List.of(
                PolicyEngine.layerOf(subscription("a", ScopeLevel.ORG, Policy.Effect.ALLOW)),
                PolicyEngine.layerOf(subscription("b", ScopeLevel.DOMAIN, Policy.Effect.ALLOW)),
                PolicyEngine.layerOf(subscription("c", ScopeLevel.SERVICE, Policy.Effect.ALLOW)),
                PolicyEngine.layerOf(subscription("d", ScopeLevel.DATABASE, Policy.Effect.ALLOW)),
                PolicyEngine.layerOf(subscription("e", ScopeLevel.SCHEMA, Policy.Effect.ALLOW)),
                PolicyEngine.layerOf(subscription("f", ScopeLevel.TABLE, Policy.Effect.ALLOW)),
                PolicyEngine.layerOf(subscription("g", ScopeLevel.COLUMN, Policy.Effect.ALLOW))))
        .isSorted();
  }
}
