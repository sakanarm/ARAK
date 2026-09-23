package com.mfec.dac.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.MaskingSpec.MaskingFunction;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedColumnMask.ScopeLevel;
import com.mfec.dac.schema.api.ResolvedRowPredicate.FacetOperator;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.AttributeCondition;
import com.mfec.dac.schema.entity.policy.ColumnRule;
import com.mfec.dac.schema.entity.policy.DataPolicy;
import com.mfec.dac.schema.entity.policy.Exemption;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.PrincipalMatch;
import com.mfec.dac.schema.entity.policy.RowFilter;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Composition stated as set algebra, over a whole population at once.
 *
 * <p>The other engine tests ask what happens to one principal under one
 * arrangement of policies. That is the right shape for a rule but the wrong
 * shape for the thing most likely to be wrong, which is not any single rule but
 * how several of them combine. Two policies that each behave correctly alone
 * can still, together, admit somebody neither of them meant to.
 *
 * <p>So every case here runs the same five people and three tables through the
 * engine and asserts the exact set that comes out. A union that has quietly
 * become an intersection admits too few and is noticed in a day; an
 * intersection that has become a union admits too many and is noticed by an
 * auditor, which is the failure this platform exists to prevent. Writing the
 * expected answer as a set rather than a boolean is what makes the difference
 * visible: an off-by-one-person bug has nowhere to hide in
 * {@code containsExactlyInAnyOrder}.
 *
 * <p>The three operations, in the terms FR-5.1 uses:
 *
 * <ul>
 *   <li><b>Union</b> — policies sitting at the same layer. Any one of them that
 *       admits you is enough, so the layer reaches the union of their subjects.
 *   <li><b>Intersection</b> — policies sitting at different layers. Every layer
 *       that has an opinion must admit you, so access is the intersection and
 *       a local policy can only ever narrow a global one.
 *   <li><b>Complement</b> — DENY, and the exemption that carves a hole back out
 *       of a DENY. Complement of a complement is not the identity here, which
 *       is exactly why it is worth a test.
 * </ul>
 */
class PolicyAlgebraTest {

  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
  private static final PolicyEngine ENGINE =
      new PolicyEngine(EngineConfig.defaults().withZone(BANGKOK));

  /** 2026-09-15 is a Tuesday, inside office hours, so time never decides anything below. */
  private static final RequestContext NOW =
      RequestContext.at(LocalDateTime.parse("2026-09-15T09:00").atZone(BANGKOK).toInstant());

  private static final String TABLE = "prod-mssql.SalesDB.dbo.customer";

  // ------------------------------------------------------------ population

  /**
   * Five people chosen so that no two policies below pick out the same set.
   *
   * <p>Every pair of the groupings used in these tests — team, role, clearance,
   * country — cuts the population differently. If they overlapped, a union and
   * an intersection could produce the same answer and the test would pass
   * whichever one the engine had implemented.
   */
  private static Map<String, Principal> population() {
    Map<String, Principal> people = new LinkedHashMap<>();
    people.put("fin_l1", person("fin_l1", "Finance", "analyst", "L1", "TH"));
    people.put("fin_l2", person("fin_l2", "Finance", "analyst", "L2", "TH"));
    people.put("aud_l2", person("aud_l2", "Audit", "auditor", "L2", "TH"));
    people.put("eng_sg", person("eng_sg", "Engineering", "analyst", "L2", "SG"));
    people.put("contractor", person("contractor", "Contractors", "guest", "L1", "TH"));
    return people;
  }

  private static Principal person(
      String id, String team, String role, String clearance, String country) {
    return Principal.withId(id)
        .email(id + "@example.com")
        .roles(role)
        .teams(team)
        .attribute("clearance", clearance)
        .attribute("country", country)
        .attribute("branch", "BKK-01")
        .build();
  }

  private static AssetContext customer() {
    return AssetContext.of(TABLE)
        .physicalFromFqn()
        .hierarchicalFacet(FacetType.DOMAINS, "Finance.Risk.Credit")
        .column(
            ColumnContext.named("citizen_id")
                .fqn(TABLE + ".citizen_id")
                .dataType("VARCHAR")
                .facet(FacetType.TAGS, "PII", "PII.Sensitive"))
        .column(
            ColumnContext.named("email")
                .fqn(TABLE + ".email")
                .dataType("VARCHAR")
                .facet(FacetType.TAGS, "PII", "PII.Sensitive"))
        .column(ColumnContext.named("salary").dataType("DECIMAL"))
        .column(ColumnContext.named("branch_code").dataType("VARCHAR"))
        .build();
  }

  /** Everyone the given arrangement of policies lets through, by id. */
  private static List<String> admitted(Policy... policies) {
    List<String> in = new ArrayList<>();
    population()
        .forEach(
            (id, principal) -> {
              if (Boolean.TRUE.equals(
                  ENGINE.evaluate(principal, customer(), NOW, List.of(policies)).getAllowed())) {
                in.add(id);
              }
            });
    return in;
  }

  // ------------------------------------------------------------- fixtures

  private static AssetSelector cond(FacetType facet, FacetOperator operator, String value) {
    return new AssetSelector()
        .withCondition(
            new FacetCondition().withFacet(facet).withOperator(operator).withValue(value));
  }

  private static Policy policy(String name, ScopeLevel level, Policy.PolicyType type,
      Policy.Effect effect) {
    return new Policy()
        .withId(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)))
        .withName(name)
        .withVersion(1)
        .withPolicyType(type)
        .withScopeLevel(level)
        .withScopeFqn(level == ScopeLevel.ORG ? null : TABLE)
        .withEffect(effect)
        .withSelector(cond(FacetType.TABLE, FacetOperator.EQ, "customer"));
  }

  private static Policy allow(String name, ScopeLevel level, SubjectRule subject) {
    return policy(name, level, Policy.PolicyType.SUBSCRIPTION, Policy.Effect.ALLOW)
        .withSubject(subject);
  }

  private static Policy deny(String name, ScopeLevel level, SubjectRule subject) {
    return policy(name, level, Policy.PolicyType.SUBSCRIPTION, Policy.Effect.DENY)
        .withSubject(subject);
  }

  private static SubjectRule team(String name) {
    return new SubjectRule().withPrincipals(List.of(new PrincipalMatch().withTeam(name)));
  }

  private static SubjectRule role(String name) {
    return new SubjectRule().withPrincipals(List.of(new PrincipalMatch().withRole(name)));
  }

  private static SubjectRule attribute(String key, FacetOperator operator, Object value) {
    return new SubjectRule()
        .withAttributes(
            List.of(
                new AttributeCondition()
                    .withKey(key)
                    .withOperator(operator)
                    .withValue(value)));
  }

  private static Policy maskPolicy(String name, ScopeLevel level, ColumnRule... rules) {
    return policy(name, level, Policy.PolicyType.DATA, Policy.Effect.ALLOW)
        .withData(new DataPolicy().withColumnRules(List.of(rules)));
  }

  private static ColumnRule maskRule(String column, MaskingFunction function) {
    return new ColumnRule()
        .withAction(ColumnRule.Action.MASK)
        .withColumns(cond(FacetType.COLUMN_NAME, FacetOperator.EQ, column))
        .withMasking(new MaskingSpec().withFunction(function));
  }

  private static ColumnRule hideRule(String column) {
    return new ColumnRule()
        .withAction(ColumnRule.Action.HIDE)
        .withColumns(cond(FacetType.COLUMN_NAME, FacetOperator.EQ, column));
  }

  /** The one subscription that opens the door, so data rules are reached at all. */
  private static Policy doorIsOpen() {
    return policy("door", ScopeLevel.ORG, Policy.PolicyType.SUBSCRIPTION, Policy.Effect.ALLOW);
  }

  private static PolicyDecision inside(Policy... policies) {
    List<Policy> all = new ArrayList<>();
    all.add(doorIsOpen());
    all.addAll(List.of(policies));
    return ENGINE.evaluate(population().get("fin_l1"), customer(), NOW, all);
  }

  private static List<String> maskedColumns(PolicyDecision decision) {
    List<String> out = new ArrayList<>();
    for (ResolvedColumnMask mask : decision.getColumnMasks()) {
      out.add(mask.getColumn());
    }
    return out;
  }

  private static MaskingFunction functionOn(PolicyDecision decision, String column) {
    for (ResolvedColumnMask mask : decision.getColumnMasks()) {
      if (column.equals(mask.getColumn())) {
        return mask.getMasking().getFunction();
      }
    }
    return null;
  }

  // ================================================================= union

  @Nested
  @DisplayName("policies sharing a layer (union — OR)")
  class Union {

    @Test
    @DisplayName("admit the union of the people they name, not only those all of them name")
    void reachIsTheUnion() {
      // Two ORG policies, disjoint subjects. A layer is a gate that one
      // matching ALLOW is enough to open, so the layer's reach is the union.
      assertThat(admitted(allow("finance", ScopeLevel.ORG, team("Finance")),
              allow("auditors", ScopeLevel.ORG, role("auditor"))))
          .containsExactlyInAnyOrder("fin_l1", "fin_l2", "aud_l2");
    }

    @Test
    @DisplayName("overlapping policies do not admit somebody twice or drop them")
    void idempotent() {
      // Union is idempotent: A ∪ A = A. Worth pinning because the layer gate is
      // implemented by iterating policies, and a counting bug there would show
      // up first as a duplicate that nobody notices until it becomes a hole.
      List<String> once = admitted(allow("finance", ScopeLevel.ORG, team("Finance")));
      List<String> twice =
          admitted(
              allow("finance", ScopeLevel.ORG, team("Finance")),
              allow("finance-again", ScopeLevel.ORG, team("Finance")));
      assertThat(twice).isEqualTo(once).containsExactlyInAnyOrder("fin_l1", "fin_l2");
    }

    @Test
    @DisplayName("are commutative, so the order they were written in changes nobody's access")
    void commutative() {
      Policy a = allow("finance", ScopeLevel.ORG, team("Finance"));
      Policy b = allow("auditors", ScopeLevel.ORG, role("auditor"));
      // Policies arrive from a query with no ordering guarantee. If this ever
      // stopped holding, access would depend on the database's mood.
      assertThat(admitted(a, b)).containsExactlyInAnyOrderElementsOf(admitted(b, a));
    }

    @Test
    @DisplayName("a policy naming several identities is itself a union")
    void principalListIsOr() {
      // The OR lives in two places -- across policies and inside one policy's
      // principal list -- and both have to mean the same thing, or the same
      // intent written two ways gives two answers.
      SubjectRule either =
          new SubjectRule()
              .withPrincipals(
                  List.of(new PrincipalMatch().withTeam("Finance"),
                      new PrincipalMatch().withRole("auditor")));
      assertThat(admitted(allow("either", ScopeLevel.ORG, either)))
          .containsExactlyInAnyOrder("fin_l1", "fin_l2", "aud_l2")
          .containsExactlyInAnyOrderElementsOf(
              admitted(
                  allow("finance", ScopeLevel.ORG, team("Finance")),
                  allow("auditors", ScopeLevel.ORG, role("auditor"))));
    }

    @Test
    @DisplayName("the empty union is closed, because default-deny is the whole point")
    void emptyUnion() {
      assertThat(admitted()).isEmpty();
    }
  }

  // ========================================================== intersection

  @Nested
  @DisplayName("policies at different layers (intersection — AND)")
  class Intersection {

    @Test
    @DisplayName("admit only the people every layer admits")
    void reachIsTheIntersection() {
      // ORG reaches {fin_l1, fin_l2, aud_l2}; TABLE reaches {fin_l2, aud_l2,
      // eng_sg}. Access is the intersection, {fin_l2, aud_l2} -- and notably
      // NOT eng_sg, who the deeper policy names but the global one does not.
      // This is FR-3.1.4: a local policy tightens, it cannot open.
      assertThat(
              admitted(
                  allow("finance", ScopeLevel.ORG, team("Finance")),
                  allow("auditors", ScopeLevel.ORG, role("auditor")),
                  allow("cleared", ScopeLevel.TABLE, attribute("clearance", FacetOperator.EQ, "L2"))))
          .containsExactlyInAnyOrder("fin_l2", "aud_l2");
    }

    @Test
    @DisplayName("a deeper layer cannot admit somebody the shallower one never did")
    void localCannotWiden() {
      // The mistake FR-5.1 warns about in so many words: a table owner writes a
      // policy for every analyst, and eng_sg -- an analyst -- still does not get
      // in, because ORG only ever admitted Finance. The deeper policy is not
      // ignored; it is intersected, and intersecting with a larger set leaves
      // the smaller one alone.
      //
      // Written with a non-empty answer on purpose. An empty one would also be
      // consistent with the engine having thrown the local policy away, which
      // is a different bug with the same symptom.
      assertThat(
              admitted(
                  allow("finance", ScopeLevel.ORG, team("Finance")),
                  allow("every-analyst", ScopeLevel.TABLE, role("analyst"))))
          .containsExactlyInAnyOrder("fin_l1", "fin_l2")
          .doesNotContain("eng_sg");
    }

    @Test
    @DisplayName("an intersection that is empty admits nobody, rather than falling back to either side")
    void emptyIntersection() {
      // Disjoint layers. A fallback here -- "no overlap, so use the ORG set" --
      // is the single most tempting wrong implementation of composition.
      assertThat(
              admitted(
                  allow("finance", ScopeLevel.ORG, team("Finance")),
                  allow("engineering-only", ScopeLevel.TABLE, team("Engineering"))))
          .isEmpty();
    }

    @Test
    @DisplayName("every layer in the chain binds, not just the first and the last")
    void everyLayerBinds() {
      // ORG ∩ SCHEMA ∩ TABLE. The middle layer is the one an implementation
      // that only compared "global against local" would skip.
      assertThat(
              admitted(
                  allow("org", ScopeLevel.ORG, attribute("country", FacetOperator.EQ, "TH")),
                  allow("schema", ScopeLevel.SCHEMA, role("analyst")),
                  allow("table", ScopeLevel.TABLE, attribute("clearance", FacetOperator.EQ, "L2"))))
          // TH: {fin_l1, fin_l2, aud_l2, contractor} ∩ analyst: {fin_l1,
          // fin_l2, eng_sg} ∩ L2: {fin_l2, aud_l2, eng_sg} = {fin_l2}
          .containsExactlyInAnyOrder("fin_l2");
    }

    @Test
    @DisplayName("a layer nobody wrote a policy for is not a closed door")
    void silentLayersAbstain() {
      // Intersection is over the layers that have an opinion. If an empty layer
      // counted as "admits nobody", one ORG policy could never grant anything,
      // because six of the seven layers are always empty.
      assertThat(admitted(allow("only-org", ScopeLevel.ORG, team("Finance"))))
          .containsExactlyInAnyOrder("fin_l1", "fin_l2");
    }

    @Test
    @DisplayName("conditions inside one rule intersect, exactly as separate layers do")
    void attributesAreAnded() {
      SubjectRule bothConditions =
          new SubjectRule()
              .withAttributes(
                  List.of(
                      new AttributeCondition()
                          .withKey("clearance")
                          .withOperator(FacetOperator.EQ)
                          .withValue("L2"),
                      new AttributeCondition()
                          .withKey("country")
                          .withOperator(FacetOperator.EQ)
                          .withValue("TH")));
      // L2 ∩ TH = {fin_l2, aud_l2}. Same answer as writing the two conditions
      // as two policies at two layers, which is the property that lets an
      // author choose either style without changing who gets in.
      assertThat(admitted(allow("both", ScopeLevel.ORG, bothConditions)))
          .containsExactlyInAnyOrder("fin_l2", "aud_l2")
          .containsExactlyInAnyOrderElementsOf(
              admitted(
                  allow("cleared", ScopeLevel.ORG, attribute("clearance", FacetOperator.EQ, "L2")),
                  allow("local", ScopeLevel.TABLE, attribute("country", FacetOperator.EQ, "TH"))));
    }

    @Test
    @DisplayName("requiring several memberships intersects them within a single entry")
    void requiredPrincipalsAreAnded() {
      SubjectRule financeAnalysts =
          new SubjectRule()
              .withPrincipals(List.of(new PrincipalMatch().withTeam("Finance")))
              .withRequiredPrincipals(List.of(new PrincipalMatch().withRole("analyst")));
      assertThat(admitted(allow("finance-analysts", ScopeLevel.ORG, financeAnalysts)))
          .containsExactlyInAnyOrder("fin_l1", "fin_l2");

      SubjectRule financeAuditors =
          new SubjectRule()
              .withPrincipals(List.of(new PrincipalMatch().withTeam("Finance")))
              .withRequiredPrincipals(List.of(new PrincipalMatch().withRole("auditor")));
      // Nobody is both, and the answer is the empty set rather than either half.
      assertThat(admitted(allow("finance-auditors", ScopeLevel.ORG, financeAuditors))).isEmpty();
    }
  }

  // ============================================================ complement

  @Nested
  @DisplayName("DENY and exemption (complement — NOT)")
  class Complement {

    @Test
    @DisplayName("a DENY subtracts its subjects from whoever was allowed")
    void denySubtracts() {
      // ALLOW {fin_l1, fin_l2} minus DENY {fin_l1, contractor} = {fin_l2}.
      // Subtracting somebody the ALLOW never reached is a no-op, which is why
      // contractor's presence in the DENY changes nothing.
      assertThat(
              admitted(
                  allow("finance", ScopeLevel.ORG, team("Finance")),
                  deny("uncleared", ScopeLevel.ORG, attribute("clearance", FacetOperator.EQ, "L1"))))
          .containsExactlyInAnyOrder("fin_l2");
    }

    @Test
    @DisplayName("a DENY at any layer subtracts from every layer, not only its own")
    void denyIsGlobal() {
      // FR-3.3: DENY wins outright. A DENY written at COLUMN has to reach back
      // up and remove people an ORG policy admitted, or "deny wins" would mean
      // "deny wins among its neighbours".
      assertThat(
              admitted(
                  allow("finance", ScopeLevel.ORG, team("Finance")),
                  allow("analysts", ScopeLevel.TABLE, role("analyst")),
                  deny("no-l1", ScopeLevel.COLUMN, attribute("clearance", FacetOperator.EQ, "L1"))))
          .containsExactlyInAnyOrder("fin_l2");
    }

    @Test
    @DisplayName("an exemption carves a hole back out of the DENY, and only out of it")
    void exemptionIsComplementOfComplement() {
      Policy denyL1 =
          deny("uncleared", ScopeLevel.ORG, attribute("clearance", FacetOperator.EQ, "L1"))
              .withExemptions(
                  List.of(
                      new Exemption()
                          .withPrincipal("fin_l1")
                          .withReason("Signed off for the quarter-end close")
                          .withGrantedBy("owner_o")
                          .withExpiresAt(NOW.at().plusSeconds(3600))));

      // (A \ D) ∪ (A ∩ E) = {fin_l2} ∪ {fin_l1}. Note what did NOT happen:
      // contractor is exempt from nothing and was never allowed, so the
      // exemption cannot let them in. An exemption releases from a DENY; it is
      // not itself a grant.
      assertThat(admitted(allow("finance", ScopeLevel.ORG, team("Finance")), denyL1))
          .containsExactlyInAnyOrder("fin_l1", "fin_l2");

      Policy exemptsAnOutsider =
          deny("uncleared", ScopeLevel.ORG, attribute("clearance", FacetOperator.EQ, "L1"))
              .withExemptions(
                  List.of(
                      new Exemption()
                          .withPrincipal("contractor")
                          .withReason("Vendor support window")
                          .withGrantedBy("owner_o")
                          .withExpiresAt(NOW.at().plusSeconds(3600))));
      assertThat(admitted(allow("finance", ScopeLevel.ORG, team("Finance")), exemptsAnOutsider))
          .containsExactlyInAnyOrder("fin_l2")
          .doesNotContain("contractor");
    }

    @Test
    @DisplayName("two DENYs subtract the union of what they name")
    void deniesUnion() {
      // Complements compose the other way round: A \ (D1 ∪ D2). Everyone the
      // ALLOW reached is L1 or L2, so between them the two DENYs empty it.
      assertThat(
              admitted(
                  allow("everyone-in-finance", ScopeLevel.ORG, team("Finance")),
                  deny("no-l1", ScopeLevel.ORG, attribute("clearance", FacetOperator.EQ, "L1")),
                  deny("no-l2", ScopeLevel.ORG, attribute("clearance", FacetOperator.EQ, "L2"))))
          .isEmpty();
    }

    @Test
    @DisplayName("a DENY on its own leaves the door shut rather than opening it")
    void denyAloneGrantsNothing() {
      // The complement of nothing is nothing, not everything. An implementation
      // that read "no ALLOW matched, but a DENY did not match either" as a pass
      // would open every table that happened to carry only DENY policies.
      assertThat(deny("uncleared", ScopeLevel.ORG, attribute("clearance", FacetOperator.EQ, "L1")))
          .isNotNull();
      assertThat(admitted(deny("no-l1", ScopeLevel.ORG,
              attribute("clearance", FacetOperator.EQ, "L1"))))
          .isEmpty();
    }

    @Test
    @DisplayName("subtraction is not commutative with the union it is applied to")
    void orderOfOperations() {
      // (A1 ∪ A2) \ D is what the engine must compute. The wrong reading --
      // (A1 \ D) ∪ A2, i.e. applying the DENY to whichever policy it was
      // written next to -- would let aud_l2 in here, because the DENY sits
      // beside the Finance policy and aud_l2 arrives through the other one.
      Policy denyTh =
          deny("no-th", ScopeLevel.ORG, attribute("country", FacetOperator.EQ, "TH"));
      assertThat(
              admitted(
                  allow("finance", ScopeLevel.ORG, team("Finance")),
                  denyTh,
                  allow("auditors", ScopeLevel.ORG, role("auditor"))))
          .isEmpty();
    }
  }

  // ============================================== the asset side of the algebra

  @Nested
  @DisplayName("selectors, over the set of assets a policy binds to")
  class Selectors {

    /** Three tables that no single condition separates the same way. */
    private List<AssetContext> tables() {
      return List.of(
          AssetContext.of("prod-mssql.SalesDB.dbo.customer")
              .physicalFromFqn()
              .hierarchicalFacet(FacetType.DOMAINS, "Finance.Risk.Credit")
              .facet(FacetType.TAGS, "PII", "PII.Sensitive")
              .build(),
          AssetContext.of("prod-mssql.SalesDB.dbo.orders")
              .physicalFromFqn()
              .hierarchicalFacet(FacetType.DOMAINS, "Finance")
              .build(),
          AssetContext.of("prod-mssql.SalesDB.audit.access_log")
              .physicalFromFqn()
              .hierarchicalFacet(FacetType.DOMAINS, "Compliance")
              .facet(FacetType.TAGS, "PII")
              .build());
    }

    private List<String> selected(AssetSelector selector) {
      List<String> out = new ArrayList<>();
      for (AssetContext table : tables()) {
        if (SelectorMatcher.matches(selector, table)) {
          String fqn = table.table();
          out.add(fqn.substring(fqn.lastIndexOf('.') + 1));
        }
      }
      return out;
    }

    private AssetSelector pii() {
      return cond(FacetType.TAGS, FacetOperator.CONTAINS, "PII");
    }

    private AssetSelector inDbo() {
      return cond(FacetType.SCHEMA, FacetOperator.EQ, "dbo");
    }

    private AssetSelector inFinance() {
      return cond(FacetType.DOMAINS, FacetOperator.CONTAINS, "Finance");
    }

    @Test
    @DisplayName("or selects the union, and selects the intersection")
    void unionAndIntersection() {
      assertThat(selected(new AssetSelector().withOr(List.of(pii(), inDbo()))))
          .containsExactlyInAnyOrder("customer", "orders", "access_log");
      assertThat(selected(pii().withAnd(List.of(inDbo()))))
          .containsExactlyInAnyOrder("customer");
    }

    @Test
    @DisplayName("not selects the complement")
    void complement() {
      assertThat(selected(new AssetSelector().withNot(pii())))
          .containsExactlyInAnyOrder("orders");
    }

    @Test
    @DisplayName("not distributes over or exactly as De Morgan says it must")
    void deMorgan() {
      // not(A or B) == (not A) and (not B). This is not a test of set theory;
      // it is a test that `not` negates the whole branch it was given rather
      // than only that branch's own condition, which is the natural way to get
      // this wrong and produces a selector that binds to more tables than the
      // author asked for.
      AssetSelector notEither = new AssetSelector().withNot(
          new AssetSelector().withOr(List.of(pii(), inFinance())));
      AssetSelector neither =
          new AssetSelector()
              .withNot(pii())
              .withAnd(List.of(new AssetSelector().withNot(inFinance())));
      assertThat(selected(notEither)).containsExactlyInAnyOrderElementsOf(selected(neither));
      // And both are empty here, because every table carries PII or sits in
      // Finance -- asserted so the equality above cannot pass by both sides
      // being broken in the same direction.
      assertThat(selected(notEither)).isEmpty();
    }

    @Test
    @DisplayName("and distributes over or")
    void distributive() {
      // A and (B or C) == (A and B) or (A and C).
      AssetSelector factored =
          inDbo().withAnd(List.of(new AssetSelector().withOr(List.of(pii(), inFinance()))));
      AssetSelector expanded =
          new AssetSelector()
              .withOr(
                  List.of(
                      inDbo().withAnd(List.of(pii())),
                      inDbo().withAnd(List.of(inFinance()))));
      assertThat(selected(factored))
          .containsExactlyInAnyOrderElementsOf(selected(expanded))
          .containsExactlyInAnyOrder("customer", "orders");
    }

    @Test
    @DisplayName("contains covers the descendants of a domain; eq covers only the level named")
    void hierarchy() {
      // The distinction the plan calls out at 4a: a policy on `Finance` has to
      // reach a table three sub-domains down, and a policy written with `eq`
      // has to not.
      assertThat(selected(cond(FacetType.DOMAINS, FacetOperator.CONTAINS, "Finance")))
          .containsExactlyInAnyOrder("customer", "orders");
      assertThat(selected(cond(FacetType.DOMAINS, FacetOperator.EQ, "Finance")))
          .containsExactlyInAnyOrder("orders");
    }

    @Test
    @DisplayName("a selector matching no asset binds the policy to nothing, harmlessly")
    void emptySelection() {
      assertThat(selected(cond(FacetType.TABLE, FacetOperator.EQ, "no_such_table"))).isEmpty();
    }
  }

  // ====================================================== the data policy side

  @Nested
  @DisplayName("data policies, where the three operations mean different things")
  class DataAlgebra {

    @Test
    @DisplayName("the set of masked columns is the union of what each policy covers")
    void masksUnionAcrossColumns() {
      // Restriction unions where access intersects, and this is not a
      // contradiction: both are "strictest wins" seen from opposite ends. More
      // policies can only ever mean more columns hidden, never fewer.
      assertThat(
              maskedColumns(
                  inside(
                      maskPolicy("pii", ScopeLevel.ORG, maskRule("email", MaskingFunction.HASH)),
                      maskPolicy("pay", ScopeLevel.TABLE,
                          maskRule("salary", MaskingFunction.NULLIFY)))))
          .containsExactlyInAnyOrder("email", "salary");
    }

    @Test
    @DisplayName("two masks on one column join at the stronger, whichever layer wrote it")
    void masksJoinAtTheStrongest() {
      // A lattice join, not a union: one column ends with one function. The
      // deeper layer losing here is the point -- strength decides, not depth,
      // so a table owner cannot weaken a global mask by restating it.
      assertThat(
              functionOn(
                  inside(
                      maskPolicy("weak-but-deep", ScopeLevel.TABLE,
                          maskRule("email", MaskingFunction.PARTIAL)),
                      maskPolicy("strong-and-shallow", ScopeLevel.ORG,
                          maskRule("email", MaskingFunction.NULLIFY))),
                  "email"))
          .isEqualTo(MaskingFunction.NULLIFY);
    }

    @Test
    @DisplayName("the join is commutative and idempotent, so restating a mask changes nothing")
    void joinIsWellBehaved() {
      MaskingFunction oneWay =
          functionOn(
              inside(
                  maskPolicy("a", ScopeLevel.ORG, maskRule("email", MaskingFunction.HASH)),
                  maskPolicy("b", ScopeLevel.TABLE, maskRule("email", MaskingFunction.PARTIAL))),
              "email");
      MaskingFunction otherWay =
          functionOn(
              inside(
                  maskPolicy("b", ScopeLevel.TABLE, maskRule("email", MaskingFunction.PARTIAL)),
                  maskPolicy("a", ScopeLevel.ORG, maskRule("email", MaskingFunction.HASH))),
              "email");
      assertThat(oneWay).isEqualTo(otherWay).isEqualTo(MaskingFunction.HASH);

      assertThat(
              functionOn(
                  inside(
                      maskPolicy("a", ScopeLevel.ORG, maskRule("email", MaskingFunction.HASH)),
                      maskPolicy("a-again", ScopeLevel.SCHEMA,
                          maskRule("email", MaskingFunction.HASH))),
                  "email"))
          .isEqualTo(MaskingFunction.HASH);
    }

    @Test
    @DisplayName("row filters intersect, so each policy's predicate survives alongside the others")
    void rowFiltersIntersect() {
      Policy branch =
          policy("branch", ScopeLevel.SCHEMA, Policy.PolicyType.DATA, Policy.Effect.ALLOW)
              .withData(
                  new DataPolicy()
                      .withRowFilters(
                          List.of(
                              new RowFilter()
                                  .withKind(RowFilter.Kind.ATTRIBUTE_COMPARE)
                                  .withColumn("branch_code")
                                  .withUserAttribute("branch"))));
      Policy country =
          policy("country", ScopeLevel.TABLE, Policy.PolicyType.DATA, Policy.Effect.ALLOW)
              .withData(
                  new DataPolicy()
                      .withRowFilters(
                          List.of(
                              new RowFilter()
                                  .withKind(RowFilter.Kind.ATTRIBUTE_COMPARE)
                                  .withColumn("country_code")
                                  .withUserAttribute("country"))));

      // Both predicates come out, to be ANDed by whichever compiler runs. An
      // implementation that let the deeper filter replace the shallower one
      // would silently widen every row set in the system.
      assertThat(inside(branch, country).getRowPredicates())
          .hasSize(2)
          .extracting(p -> p.getColumn())
          .containsExactlyInAnyOrder("branch_code", "country_code");
    }

    @Test
    @DisplayName("hidden columns union, and a hidden column is not also a masked one")
    void hidesUnion() {
      PolicyDecision decision =
          inside(
              maskPolicy("hide-id", ScopeLevel.ORG, hideRule("citizen_id")),
              maskPolicy("hide-pay", ScopeLevel.TABLE, hideRule("salary")),
              maskPolicy("mask-id", ScopeLevel.SCHEMA,
                  maskRule("citizen_id", MaskingFunction.PARTIAL)));
      assertThat(decision.getHiddenColumns()).containsExactlyInAnyOrder("citizen_id", "salary");
      // Hiding outranks masking: the column is gone from the projection, so a
      // mask on it would be an instruction to blank something that is not
      // there, and emitting one would mean the compiler had to select it.
      assertThat(maskedColumns(decision)).doesNotContain("citizen_id");
    }

    @Test
    @DisplayName("restriction is monotone: adding a policy never reveals more than before")
    void monotone() {
      // The single property that makes the whole composition safe to reason
      // about. Whatever else changes, one more data policy can only take away.
      PolicyDecision fewer =
          inside(maskPolicy("pii", ScopeLevel.ORG, maskRule("email", MaskingFunction.PARTIAL)));
      PolicyDecision more =
          inside(
              maskPolicy("pii", ScopeLevel.ORG, maskRule("email", MaskingFunction.PARTIAL)),
              maskPolicy("pay", ScopeLevel.TABLE, maskRule("salary", MaskingFunction.NULLIFY)),
              maskPolicy("id", ScopeLevel.COLUMN, hideRule("citizen_id")));

      assertThat(maskedColumns(more)).containsAll(
          maskedColumns(fewer).stream().filter(c -> !more.getHiddenColumns().contains(c)).toList());
      assertThat(more.getHiddenColumns()).containsAll(fewer.getHiddenColumns());
    }
  }

  // ============================================ where the two halves meet

  @Nested
  @DisplayName("subscription and data policy together")
  class Mixed {

    @Test
    @DisplayName("a refused subscription leaves nothing for a data policy to say")
    void deniedCarriesNothing() {
      // Not merely tidiness. A denied decision that still carried masks would
      // tell the reader which columns exist and which are sensitive, and the
      // compilers would have a decision they could half-apply.
      PolicyDecision decision =
          ENGINE.evaluate(
              population().get("contractor"),
              customer(),
              NOW,
              List.of(
                  allow("finance", ScopeLevel.ORG, team("Finance")),
                  maskPolicy("pii", ScopeLevel.ORG, maskRule("email", MaskingFunction.NULLIFY))));
      assertThat(decision.getAllowed()).isFalse();
      assertThat(decision.getColumnMasks()).isEmpty();
      assertThat(decision.getRowPredicates()).isEmpty();
      assertThat(decision.getHiddenColumns()).isEmpty();
    }

    @Test
    @DisplayName("a data policy is not a door, however many of them agree")
    void dataPoliciesDoNotGrant() {
      // Three data policies, no subscription. The answer is still closed: the
      // two halves are not interchangeable, and a data policy that accidentally
      // counted as a grant would open every table it was written for.
      assertThat(
              ENGINE
                  .evaluate(
                      population().get("fin_l2"),
                      customer(),
                      NOW,
                      List.of(
                          maskPolicy("a", ScopeLevel.ORG, maskRule("email",
                              MaskingFunction.NULLIFY)),
                          maskPolicy("b", ScopeLevel.TABLE, maskRule("salary",
                              MaskingFunction.NULLIFY)),
                          maskPolicy("c", ScopeLevel.COLUMN, hideRule("citizen_id"))))
                  .getAllowed())
          .isFalse();
    }

    @Test
    @DisplayName("the masks a person gets depend on who they are, not only on the table")
    void restrictionsArePerPrincipal() {
      // A data policy whose subject rule names L1 only. fin_l1 is masked and
      // fin_l2 is not, from one policy, on one table, at one instant.
      Policy maskTheUncleared =
          maskPolicy("uncleared", ScopeLevel.ORG, maskRule("email", MaskingFunction.NULLIFY))
              .withSubject(attribute("clearance", FacetOperator.EQ, "L1"));
      List<Policy> policies =
          List.of(allow("finance", ScopeLevel.ORG, team("Finance")), maskTheUncleared);

      assertThat(
              maskedColumns(
                  ENGINE.evaluate(population().get("fin_l1"), customer(), NOW, policies)))
          .containsExactly("email");
      assertThat(
              maskedColumns(
                  ENGINE.evaluate(population().get("fin_l2"), customer(), NOW, policies)))
          .isEmpty();
    }

    @Test
    @DisplayName("the whole arrangement is stable under shuffling")
    void orderIndependent() {
      // Everything above, at once, evaluated in both directions. Composition is
      // meant to be a fold over a set, and a set has no order; this is the
      // cheapest way to notice if it has quietly become a fold over a list.
      List<Policy> policies =
          new ArrayList<>(
              List.of(
                  allow("finance", ScopeLevel.ORG, team("Finance")),
                  allow("auditors", ScopeLevel.ORG, role("auditor")),
                  allow("cleared", ScopeLevel.TABLE,
                      attribute("clearance", FacetOperator.EQ, "L2")),
                  deny("no-guests", ScopeLevel.SCHEMA, role("guest")),
                  maskPolicy("pii", ScopeLevel.ORG, maskRule("email", MaskingFunction.PARTIAL)),
                  maskPolicy("pii-strong", ScopeLevel.TABLE,
                      maskRule("email", MaskingFunction.NULLIFY)),
                  maskPolicy("hide-id", ScopeLevel.COLUMN, hideRule("citizen_id"))));

      PolicyDecision forwards =
          ENGINE.evaluate(population().get("fin_l2"), customer(), NOW, policies);
      List<Policy> reversed = new ArrayList<>(policies);
      Collections.reverse(reversed);
      PolicyDecision backwards =
          ENGINE.evaluate(population().get("fin_l2"), customer(), NOW, reversed);

      assertThat(backwards.getAllowed()).isEqualTo(forwards.getAllowed()).isTrue();
      assertThat(maskedColumns(backwards)).isEqualTo(maskedColumns(forwards));
      assertThat(functionOn(backwards, "email"))
          .isEqualTo(functionOn(forwards, "email"))
          .isEqualTo(MaskingFunction.NULLIFY);
      assertThat(backwards.getHiddenColumns()).isEqualTo(forwards.getHiddenColumns());
      // And the cache key, because two orderings that decided the same thing
      // but keyed differently would halve the hit rate without failing anything.
      assertThat(backwards.getCacheKey()).isEqualTo(forwards.getCacheKey());
    }
  }
}
