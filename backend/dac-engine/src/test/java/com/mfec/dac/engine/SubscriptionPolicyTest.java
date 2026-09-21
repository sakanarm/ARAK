package com.mfec.dac.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask.ScopeLevel;
import com.mfec.dac.schema.api.ResolvedRowPredicate.FacetOperator;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.ColumnRule;
import com.mfec.dac.schema.entity.policy.DataPolicy;
import com.mfec.dac.schema.entity.policy.Exemption;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.PrincipalMatch;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Who gets through the door.
 *
 * <p>{@link PolicyEngineTest} covers the shape of the composition. This covers
 * the boundaries around it -- the instant a validity window closes, an
 * exemption that has run out, a layer that grants to nobody, a policy pinned to
 * another environment -- because those are the places where a rule that reads
 * correctly is implemented off by one, and where being off by one means either
 * an outage or an unauthorised read.
 *
 * <p>Nothing here asserts a default that is merely convenient. Every expected
 * value is the one the requirement names, and where the requirement is silent
 * the expectation is the closed one.
 */
class SubscriptionPolicyTest {

  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

  private static Instant bangkok(String dateTime) {
    return LocalDateTime.parse(dateTime).atZone(BANGKOK).toInstant();
  }

  /** 2026-09-15 is a Tuesday, inside office hours. */
  private static final Instant AT = bangkok("2026-09-15T09:00");

  private static final RequestContext NOW = RequestContext.at(AT);

  private static PolicyEngine engine() {
    return new PolicyEngine(EngineConfig.defaults().withZone(BANGKOK));
  }

  private static Principal analyst() {
    return Principal.withId("analyst_a")
        .email("analyst_a@example.com")
        .roles("analyst")
        .teams("Finance")
        .groups("credit-analysts", "privacy-trained")
        .attribute("department", "FINANCE")
        .attribute("clearance", "L1")
        .build();
  }

  private static AssetContext customer() {
    return AssetContext.of("prod-mssql.SalesDB.dbo.customer")
        .physicalFromFqn()
        .hierarchicalFacet(FacetType.DOMAINS, "Finance.Risk.Credit")
        .column(ColumnContext.named("email").dataType("VARCHAR"))
        .build();
  }

  // ------------------------------------------------------------- fixtures

  private static AssetSelector table(String name) {
    return new AssetSelector()
        .withCondition(
            new FacetCondition()
                .withFacet(FacetType.TABLE)
                .withOperator(FacetOperator.EQ)
                .withValue(name));
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
        .withSelector(table("customer"));
  }

  private static Policy subscription(String name, ScopeLevel level, Policy.Effect effect) {
    return policy(name, level, Policy.PolicyType.SUBSCRIPTION).withEffect(effect);
  }

  private static Policy allow(String name, ScopeLevel level) {
    return subscription(name, level, Policy.Effect.ALLOW);
  }

  private static Policy deny(String name, ScopeLevel level) {
    return subscription(name, level, Policy.Effect.DENY);
  }

  private static SubjectRule onlyRole(String role) {
    return new SubjectRule().withPrincipals(List.of(new PrincipalMatch().withRole(role)));
  }

  private static PrincipalMatch group(String name) {
    return new PrincipalMatch().withGroup(name);
  }

  /** In any one of these. */
  private static SubjectRule anyGroup(String... names) {
    return new SubjectRule().withPrincipals(Stream.of(names).map(SubscriptionPolicyTest::group).toList());
  }

  /** In all of these. */
  private static SubjectRule everyGroup(String... names) {
    return new SubjectRule()
        .withRequiredPrincipals(Stream.of(names).map(SubscriptionPolicyTest::group).toList());
  }

  private static Exemption exemption(String principal, Instant expiresAt) {
    return new Exemption()
        .withPrincipal(principal)
        .withReason("fraud investigation")
        .withExpiresAt(expiresAt);
  }

  private static boolean allowed(PolicyEngine engine, RequestContext context, Policy... policies) {
    return engine.evaluate(analyst(), customer(), context, List.of(policies)).getAllowed();
  }

  private static boolean allowed(Policy... policies) {
    return allowed(engine(), NOW, policies);
  }

  private static String explanations(PolicyDecision decision) {
    StringBuilder sb = new StringBuilder();
    decision.getReasons().forEach(r -> sb.append(r.getExplanation()).append('\n'));
    return sb.toString();
  }

  // ------------------------------------------------------------ exemptions

  @Nested
  @DisplayName("exemptions")
  class Exemptions {

    @Test
    @DisplayName("an exemption naming a team covers everyone on it")
    void teamExemptionCoversItsMembers() {
      Policy blocked =
          deny("org-deny", ScopeLevel.ORG)
              .withExemptions(List.of(exemption("Finance", bangkok("2026-12-31T00:00"))));

      assertThat(allowed(allow("org-allow", ScopeLevel.ORG), blocked)).isTrue();
    }

    @Test
    @DisplayName("an exemption naming somebody else leaves this principal inside the deny")
    void otherPrincipalsExemptionDoesNothing() {
      Policy blocked =
          deny("org-deny", ScopeLevel.ORG)
              .withExemptions(List.of(exemption("analyst_b", bangkok("2026-12-31T00:00"))));

      assertThat(allowed(allow("org-allow", ScopeLevel.ORG), blocked)).isFalse();
    }

    /**
     * An exemption expiring at this instant has expired. The alternative reading
     * leaves a hole open for one more evaluation, which for a time-based grant
     * is the one evaluation somebody was waiting for.
     */
    @Test
    @DisplayName("an exemption expiring at this very instant is already over")
    void exemptionExpiringNowIsExpired() {
      Policy blocked =
          deny("org-deny", ScopeLevel.ORG).withExemptions(List.of(exemption("analyst_a", AT)));

      assertThat(allowed(allow("org-allow", ScopeLevel.ORG), blocked)).isFalse();
    }

    @Test
    @DisplayName("one that expires a minute from now still holds")
    void exemptionExpiringLaterStillHolds() {
      Policy blocked =
          deny("org-deny", ScopeLevel.ORG)
              .withExemptions(List.of(exemption("analyst_a", bangkok("2026-09-15T09:01"))));

      assertThat(allowed(allow("org-allow", ScopeLevel.ORG), blocked)).isTrue();
    }

    /**
     * Exemption is written and read as relief from a restriction, and on a DENY
     * that is what it is. On an ALLOW the same field means the opposite: the
     * principal is outside the policy, so the grant does not reach them, and
     * because the layer still gates they lose the access the policy was there
     * to give. Pinned here so that the day this is changed, it is changed on
     * purpose.
     */
    @Test
    @DisplayName("an exemption from an ALLOW takes the grant away rather than widening it")
    void exemptionFromAnAllowRemovesTheGrant() {
      Policy grant =
          allow("org-allow", ScopeLevel.ORG)
              .withExemptions(List.of(exemption("analyst_a", bangkok("2026-12-31T00:00"))));

      assertThat(allowed(grant)).isFalse();
    }
  }

  // ------------------------------------------------------------- lifecycle

  @Nested
  @DisplayName("lifecycle and validity")
  class Lifecycle {

    @Test
    @DisplayName("a policy with no lifecycle state is read as active, not as unfinished")
    void nullLifecycleIsActive() {
      assertThat(allowed(allow("org-allow", ScopeLevel.ORG).withLifecycleState(null))).isTrue();
    }

    @Test
    @DisplayName("a disabled policy grants nothing")
    void disabledGrantsNothing() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG)
                      .withLifecycleState(Policy.LifecycleState.DISABLED)))
          .isFalse();
    }

    @Test
    @DisplayName("an archived policy grants nothing")
    void archivedGrantsNothing() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG)
                      .withLifecycleState(Policy.LifecycleState.ARCHIVED)))
          .isFalse();
    }

    @Test
    @DisplayName("a policy still awaiting approval grants nothing")
    void pendingApprovalGrantsNothing() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG)
                      .withLifecycleState(Policy.LifecycleState.PENDING_APPROVAL)))
          .isFalse();
    }

    /**
     * A disabled DENY stops denying. That is the point of disabling one, but it
     * is also the failure mode nobody expects, so it is written down.
     */
    @Test
    @DisplayName("disabling a DENY lets the access it was blocking through")
    void disablingADenyOpensTheDoor() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG),
                  deny("org-deny", ScopeLevel.ORG)
                      .withLifecycleState(Policy.LifecycleState.DISABLED)))
          .isTrue();
    }

    @Test
    @DisplayName("a policy whose window opens at this instant is already in force")
    void validFromAtThisInstantIsInForce() {
      assertThat(allowed(allow("org-allow", ScopeLevel.ORG).withValidFrom(AT))).isTrue();
    }

    @Test
    @DisplayName("one whose window closes at this instant is already out of force")
    void validUntilAtThisInstantHasExpired() {
      assertThat(allowed(allow("org-allow", ScopeLevel.ORG).withValidUntil(AT))).isFalse();
    }

    @Test
    @DisplayName("a DENY that has expired stops denying, exactly as an ALLOW stops granting")
    void expiredDenyStopsDenying() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG),
                  deny("org-deny", ScopeLevel.ORG).withValidUntil(bangkok("2026-01-01T00:00"))))
          .isTrue();
    }
  }

  // ----------------------------------------------------------- environment

  @Nested
  @DisplayName("environment pinning")
  class Environments {

    private PolicyEngine prodEngine() {
      return new PolicyEngine(
          EngineConfig.defaults().withZone(BANGKOK).withEnvironment(Policy.Environment.PROD));
    }

    @Test
    @DisplayName("an engine serving prod ignores a policy pinned to dev")
    void otherEnvironmentIsIgnored() {
      assertThat(
              allowed(
                  prodEngine(),
                  NOW,
                  allow("dev-allow", ScopeLevel.ORG).withEnvironment(Policy.Environment.DEV)))
          .isFalse();
    }

    @Test
    @DisplayName("and enforces one pinned to its own")
    void ownEnvironmentApplies() {
      assertThat(
              allowed(
                  prodEngine(),
                  NOW,
                  allow("prod-allow", ScopeLevel.ORG).withEnvironment(Policy.Environment.PROD)))
          .isTrue();
    }

    @Test
    @DisplayName("a policy pinned to nothing applies everywhere")
    void unpinnedPolicyApplies() {
      assertThat(allowed(prodEngine(), NOW, allow("any-allow", ScopeLevel.ORG))).isTrue();
    }

    @Test
    @DisplayName("an engine pinned to nothing enforces every policy, whatever it is pinned to")
    void agnosticEngineEnforcesEverything() {
      assertThat(
              allowed(allow("dev-allow", ScopeLevel.ORG).withEnvironment(Policy.Environment.DEV)))
          .isTrue();
    }

    /**
     * The dangerous direction of the same rule: a DENY authored for prod is not
     * enforced by an engine serving dev, so the environment field can widen
     * access as easily as narrow it.
     */
    @Test
    @DisplayName("a DENY pinned to another environment does not deny here")
    void denyPinnedElsewhereDoesNotApply() {
      assertThat(
              allowed(
                  prodEngine(),
                  NOW,
                  allow("org-allow", ScopeLevel.ORG),
                  deny("dev-deny", ScopeLevel.ORG).withEnvironment(Policy.Environment.DEV)))
          .isTrue();
    }
  }

  // ----------------------------------------------------------------- gates

  @Nested
  @DisplayName("layers as gates")
  class Gates {

    @Test
    @DisplayName("a layer holding only an unmatched DENY is not a gate, so it blocks nobody")
    void unmatchedDenyIsNoGate() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG),
                  deny("schema-deny-admins", ScopeLevel.SCHEMA).withSubject(onlyRole("admin"))))
          .isTrue();
    }

    @Test
    @DisplayName("a layer whose only ALLOW misses this principal shuts the door")
    void unmatchedAllowClosesItsLayer() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG),
                  allow("schema-admins", ScopeLevel.SCHEMA).withSubject(onlyRole("admin"))))
          .isFalse();
    }

    @Test
    @DisplayName("one matching ALLOW is enough to open a layer holding several")
    void oneMatchingAllowOpensTheLayer() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG),
                  allow("schema-admins", ScopeLevel.SCHEMA).withSubject(onlyRole("admin")),
                  allow("schema-analysts", ScopeLevel.SCHEMA).withSubject(onlyRole("analyst"))))
          .isTrue();
    }

    /**
     * Override is a property of a layer, not of a policy. One ALLOW at the layer
     * that did not consent is enough to keep the whole layer shut, because the
     * author who withheld consent did so about this asset and a colleague's
     * policy cannot answer for them.
     */
    @Test
    @DisplayName("a layer is overridable only when every ALLOW in it consented")
    void oneUnconsentingAllowKeepsTheLayerShut() {
      assertThat(
              allowed(
                  allow("org-consenting", ScopeLevel.ORG)
                      .withSubject(onlyRole("admin"))
                      .withAllowLocalOverride(true),
                  allow("org-withholding", ScopeLevel.ORG).withSubject(onlyRole("admin")),
                  allow("table-allow", ScopeLevel.TABLE)))
          .isFalse();
    }

    @Test
    @DisplayName("and is overridable when they all did")
    void allConsentingAllowsOpenTheLayer() {
      assertThat(
              allowed(
                  allow("org-consenting", ScopeLevel.ORG)
                      .withSubject(onlyRole("admin"))
                      .withAllowLocalOverride(true),
                  allow("org-also-consenting", ScopeLevel.ORG)
                      .withSubject(onlyRole("auditor"))
                      .withAllowLocalOverride(true),
                  allow("table-allow", ScopeLevel.TABLE)))
          .isTrue();
    }

    @Test
    @DisplayName("an override needs somewhere deeper to override from")
    void consentWithoutADeeperAllowGrantsNothing() {
      assertThat(
              allowed(
                  allow("org-admins", ScopeLevel.ORG)
                      .withSubject(onlyRole("admin"))
                      .withAllowLocalOverride(true)))
          .isFalse();
    }

    @Test
    @DisplayName("a DENY that consented still denies when nothing deeper allows")
    void consentingDenyWithoutADeeperAllowStillDenies() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG),
                  deny("org-deny", ScopeLevel.ORG).withAllowLocalOverride(true)))
          .isFalse();
    }

    @Test
    @DisplayName("a deeper DENY is not something a shallower ALLOW can override")
    void allowAboveDoesNotBeatDenyBelow() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG),
                  deny("table-deny", ScopeLevel.TABLE).withAllowLocalOverride(true)))
          .isFalse();
    }

    /**
     * FR-3.1.3: sub-domains nest inside the DOMAIN layer by FQN depth, so a
     * policy on {@code Finance.Risk.Credit} sits below one on {@code Finance}
     * and can override it on the same terms any deeper layer could.
     */
    @Test
    @DisplayName("a deeper sub-domain overrides a shallower one that consented")
    void deeperSubDomainOverridesShallower() {
      Policy broad =
          deny("finance-deny", ScopeLevel.DOMAIN)
              .withScopeFqn("Finance")
              .withAllowLocalOverride(true);
      Policy narrow = allow("credit-allow", ScopeLevel.DOMAIN).withScopeFqn("Finance.Risk.Credit");

      assertThat(allowed(allow("org-allow", ScopeLevel.ORG), broad, narrow)).isTrue();
    }

    @Test
    @DisplayName("and the shallower one does not override the deeper")
    void shallowerSubDomainDoesNotOverrideDeeper() {
      Policy narrow =
          deny("credit-deny", ScopeLevel.DOMAIN)
              .withScopeFqn("Finance.Risk.Credit")
              .withAllowLocalOverride(true);
      Policy broad = allow("finance-allow", ScopeLevel.DOMAIN).withScopeFqn("Finance");

      assertThat(allowed(allow("org-allow", ScopeLevel.ORG), narrow, broad)).isFalse();
    }
  }

  // ------------------------------------------------------------ undecidable

  @Nested
  @DisplayName("an expression that cannot be decided")
  class Undecidable {

    private Policy withExpression(Policy policy) {
      return policy.withSubject(new SubjectRule().withExpression("user.nonsense ?? asset"));
    }

    @Test
    @DisplayName("counts against an ALLOW, which therefore grants nothing")
    void undecidableAllowGrantsNothing() {
      assertThat(allowed(withExpression(allow("org-allow", ScopeLevel.ORG)))).isFalse();
    }

    @Test
    @DisplayName("counts for a DENY, which therefore denies")
    void undecidableDenyDenies() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG),
                  withExpression(deny("org-deny", ScopeLevel.ORG))))
          .isFalse();
    }

    @Test
    @DisplayName("and says so, rather than failing silently in both directions")
    void undecidableIsExplained() {
      PolicyDecision decision =
          engine()
              .evaluate(
                  analyst(),
                  customer(),
                  NOW,
                  List.of(withExpression(allow("org-allow", ScopeLevel.ORG))));

      assertThat(explanations(decision)).contains("failing closed");
    }
  }

  // ------------------------------------------------------------ group logic

  @Nested
  @DisplayName("naming several groups")
  class Groups {

    @Test
    @DisplayName("the OR list is satisfied by membership of any one of them")
    void anyGroupIsEnough() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG)
                      .withSubject(anyGroup("risk-analysts", "credit-analysts"))))
          .isTrue();
    }

    @Test
    @DisplayName("and not by membership of none of them")
    void noGroupIsNotEnough() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG)
                      .withSubject(anyGroup("risk-analysts", "treasury"))))
          .isFalse();
    }

    @Test
    @DisplayName("the AND list needs membership of every one of them")
    void everyGroupIsRequired() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG)
                      .withSubject(everyGroup("credit-analysts", "privacy-trained"))))
          .isTrue();
    }

    @Test
    @DisplayName("and one missing membership is enough to fail it")
    void oneMissingGroupFailsTheAndList() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG)
                      .withSubject(everyGroup("credit-analysts", "treasury"))))
          .isFalse();
    }

    /**
     * The shape the two lists exist for: a grant to either of two teams, which
     * everyone taking it must additionally have qualified for. Written as three
     * entries in one OR list it would grant to anyone who had merely
     * qualified -- the opposite of what the author wrote down.
     */
    @Test
    @DisplayName("the two lists compose as (any of these) and (all of those)")
    void orListAndAndListCompose() {
      SubjectRule rule =
          new SubjectRule()
              .withPrincipals(List.of(group("risk-analysts"), group("credit-analysts")))
              .withRequiredPrincipals(List.of(group("privacy-trained")));

      assertThat(allowed(allow("org-allow", ScopeLevel.ORG).withSubject(rule))).isTrue();
    }

    @Test
    @DisplayName("and the AND part still binds when the OR part matched")
    void andPartBindsAfterTheOrPartMatches() {
      SubjectRule rule =
          new SubjectRule()
              .withPrincipals(List.of(group("risk-analysts"), group("credit-analysts")))
              .withRequiredPrincipals(List.of(group("board-approved")));

      assertThat(allowed(allow("org-allow", ScopeLevel.ORG).withSubject(rule))).isFalse();
    }

    @Test
    @DisplayName("an AND list on its own is a condition, not an empty rule")
    void andListAloneIsAStatedCondition() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG).withSubject(everyGroup("credit-analysts"))))
          .isTrue();
    }

    @Test
    @DisplayName("a required entry mixing a team and a group means somebody who is both")
    void entryFieldsAreAndedWithinTheEntry() {
      SubjectRule both =
          new SubjectRule()
              .withRequiredPrincipals(
                  List.of(new PrincipalMatch().withTeam("Finance").withGroup("treasury")));

      assertThat(allowed(allow("org-allow", ScopeLevel.ORG).withSubject(both))).isFalse();
    }

    @Test
    @DisplayName("the refusal names the membership that was missing")
    void refusalNamesTheMissingGroup() {
      PolicyDecision decision =
          engine()
              .evaluate(
                  analyst(),
                  customer(),
                  NOW,
                  List.of(
                      allow("org-allow", ScopeLevel.ORG)
                          .withSubject(everyGroup("credit-analysts", "treasury"))));

      assertThat(explanations(decision)).contains("group treasury");
    }

    @Test
    @DisplayName("a DENY reads the same lists, so requiring every group narrows who it catches")
    void denyUsesTheSameLists() {
      assertThat(
              allowed(
                  allow("org-allow", ScopeLevel.ORG),
                  deny("org-deny", ScopeLevel.ORG)
                      .withSubject(everyGroup("credit-analysts", "treasury"))))
          .isTrue();
    }
  }

  // ------------------------------------------------------------- no binding

  @Nested
  @DisplayName("nothing binds")
  class NoBinding {

    @Test
    @DisplayName("data policies alone never open a door; they only furnish one already open")
    void dataPoliciesAloneDenyAccess() {
      Policy maskOnly =
          policy("org-mask", ScopeLevel.ORG, Policy.PolicyType.DATA)
              .withData(
                  new DataPolicy()
                      .withColumnRules(
                          List.of(new ColumnRule().withAction(ColumnRule.Action.MASK))));

      PolicyDecision decision =
          engine().evaluate(analyst(), customer(), NOW, List.of(maskOnly));

      assertThat(decision.getAllowed()).isFalse();
      assertThat(explanations(decision)).contains("no subscription policy binds");
      assertThat(decision.getColumnMasks()).isEmpty();
    }

    @Test
    @DisplayName("a null entry in the policy list is skipped rather than thrown over")
    void nullPolicyIsSkipped() {
      List<Policy> policies = new ArrayList<>();
      policies.add(null);
      policies.add(allow("org-allow", ScopeLevel.ORG));

      assertThat(engine().evaluate(analyst(), customer(), NOW, policies).getAllowed()).isTrue();
    }

    @Test
    @DisplayName("a null policy list is a closed door, not a crash")
    void nullPolicyListIsDenied() {
      assertThat(engine().evaluate(analyst(), customer(), NOW, null).getAllowed()).isFalse();
    }

    @Test
    @DisplayName("a policy with no effect is read as ALLOW, the schema's own default")
    void nullEffectIsAllow() {
      assertThat(allowed(allow("org-allow", ScopeLevel.ORG).withEffect(null))).isTrue();
    }
  }
}
