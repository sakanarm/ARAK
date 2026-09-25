package com.mfec.dac.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.engine.ColumnContext;
import com.mfec.dac.engine.FacetValue;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import com.mfec.dac.schema.entity.policy.Policy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The pure part of the review: reading a decision column by column, and judging it. */
class AccessReviewTest {

  static final String FQN = "demo-pg.salesdb.sales.customer";
  static final UUID MASKER = UUID.randomUUID();
  static final UUID DENIER = UUID.randomUUID();

  static final List<ColumnContext> COLUMNS =
      List.of(
          ColumnContext.named("id").dataType("INT").build(),
          ColumnContext.named("email")
              .dataType("VARCHAR")
              .facet(FacetType.TAGS, "PII", "PII.Sensitive")
              .build(),
          ColumnContext.named("citizen_id")
              .dataType("VARCHAR")
              .facet(FacetType.TAGS, "PII", "PII.Sensitive")
              .build(),
          ColumnContext.named("country")
              .dataType("VARCHAR")
              .facet(FacetType.TAGS, "PII", "PII.NonSensitive")
              .build());

  static PolicyDecision allowed() {
    return new PolicyDecision().withAllowed(true);
  }

  static PolicyDecision maskingEmail() {
    return allowed()
        .withColumnMasks(
            List.of(
                new ResolvedColumnMask()
                    .withColumn("EMAIL")
                    .withMasking(
                        new MaskingSpec().withFunction(MaskingSpec.MaskingFunction.PARTIAL))
                    .withSourcePolicyId(MASKER)))
        .withHiddenColumns(List.of("citizen_id"))
        .withReasons(
            List.of(
                new DecisionReason()
                    .withPolicyId(MASKER)
                    .withPolicyName("pii-mask")
                    .withEffect(DecisionReason.Effect.ALLOW)
                    .withMatched(true)));
  }

  static PolicyDecision deniedBy(String name) {
    return new PolicyDecision()
        .withAllowed(false)
        .withReasons(
            List.of(
                new DecisionReason()
                    .withPolicyId(DENIER)
                    .withPolicyName(name)
                    .withEffect(DecisionReason.Effect.DENY)
                    .withMatched(true)
                    .withExplanation("country is not TH")));
  }

  static AccessReview.Requester requester(boolean known, boolean enabled) {
    return new AccessReview.Requester(
        "analyst_a", "Analyst A", null, known, enabled, "local", List.of(), List.of(), List.of(),
        List.of(), 0, List.of(), 0, 0);
  }

  static AccessReview.TableFacts table(List<String> tiers) {
    return new AccessReview.TableFacts(FQN, true, tiers, List.of(), List.of(), 4, 2);
  }

  static AccessReview.Access none() {
    return AccessReview.access(deniedBy("finance-subscription"), COLUMNS);
  }

  static List<String> codes(AccessReview.Judgement judged) {
    return judged.conflicts().stream().map(AccessReview.Conflict::code).toList();
  }

  static List<String> factors(AccessReview.Judgement judged) {
    return judged.risk().factors().stream().map(AccessReview.Factor::code).toList();
  }

  @Nested
  class Sensitivity {

    @Test
    void onlyTheMostSpecificTagCounts() {
      // PII sits beside PII.NonSensitive only as its ancestor.
      assertThat(AccessReview.sensitiveTags(COLUMNS.get(3))).isEmpty();
      assertThat(AccessReview.sensitiveTags(COLUMNS.get(1))).containsExactly("PII.Sensitive");
    }

    @Test
    void wordsAndClassificationsThatMeanSensitive() {
      assertThat(AccessReview.sensitive("PersonalData.Personal")).isTrue();
      assertThat(AccessReview.sensitive("Finance.Confidential")).isTrue();
      assertThat(AccessReview.sensitive("Security.Restricted")).isTrue();
      assertThat(AccessReview.sensitive("pii")).isTrue();
      assertThat(AccessReview.sensitive("PII.NonSensitive")).isFalse();
      assertThat(AccessReview.sensitive("Tier.Tier1")).isFalse();
      assertThat(AccessReview.sensitive("PIIX.Thing")).isFalse();
    }

    @Test
    void aSuggestedTagDoesNotCount() {
      ColumnContext column =
          ColumnContext.named("phone")
              .facet(FacetType.TAGS, FacetValue.suggested("PII.Sensitive"))
              .build();
      assertThat(AccessReview.sensitiveTags(column)).isEmpty();
    }
  }

  @Nested
  class ReadingADecision {

    @Test
    void everyColumnGetsAFateAndMasksAreNamedByPolicy() {
      AccessReview.Access access = AccessReview.access(maskingEmail(), COLUMNS);

      assertThat(access.allowed()).isTrue();
      assertThat(access.columns())
          .extracting(AccessReview.ColumnFate::name, AccessReview.ColumnFate::fate)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple("id", "VISIBLE"),
              org.assertj.core.groups.Tuple.tuple("email", "MASKED"),
              org.assertj.core.groups.Tuple.tuple("citizen_id", "HIDDEN"),
              org.assertj.core.groups.Tuple.tuple("country", "VISIBLE"));
      AccessReview.ColumnFate email = access.columns().get(1);
      assertThat(email.masking()).isEqualTo("PARTIAL");
      assertThat(email.policy()).isEqualTo("pii-mask");
      assertThat(email.sensitive()).isTrue();
      assertThat(access.sensitiveInClear()).isEmpty();
      assertThat(access.blockedBy()).isNull();
    }

    @Test
    void aRefusalNamesThePolicyAndItsId() {
      AccessReview.Access access = none();
      assertThat(access.allowed()).isFalse();
      assertThat(access.blockedBy()).isEqualTo("finance-subscription: country is not TH");
      assertThat(access.blockedByPolicyId()).isEqualTo(DENIER);
    }

    @Test
    void rowFiltersInAReviewersWords() {
      assertThat(
              AccessReview.describe(
                  new ResolvedRowPredicate()
                      .withKind(ResolvedRowPredicate.Kind.ATTRIBUTE_COMPARE)
                      .withColumn("branch_code")
                      .withOperator(ResolvedRowPredicate.FacetOperator.EQ)
                      .withValues(List.of("BKK"))))
          .isEqualTo("branch_code eq BKK");
      assertThat(
              AccessReview.describe(
                  new ResolvedRowPredicate().withKind(ResolvedRowPredicate.Kind.ALWAYS_FALSE)))
          .isEqualTo("no rows at all");
      assertThat(
              AccessReview.describe(
                  new ResolvedRowPredicate()
                      .withKind(ResolvedRowPredicate.Kind.ENTITLEMENT_JOIN)
                      .withEntitlementKey("branch")))
          .contains("\"branch\"");
    }
  }

  @Nested
  class Judging {

    @Test
    void aGrantADenyDefeatsIsABlockerAndTheSuggestionIsToChangeThePolicy() {
      AccessReview.Judgement judged =
          AccessReview.judge(
              5, "month end", requester(true, true), table(List.of()), none(), none(), null, null,
              List.of());

      assertThat(judged.conflicts().get(0).severity()).isEqualTo("BLOCKER");
      assertThat(judged.conflicts().get(0).code()).isEqualTo("GRANT_BLOCKED");
      assertThat(judged.conflicts().get(0).policyId()).isEqualTo(DENIER);
      assertThat(judged.suggestions())
          .extracting(AccessReview.Suggestion::kind)
          .containsExactly("UPDATE_POLICY");
      assertThat(judged.suggestions().get(0).policyId()).isEqualTo(DENIER);
    }

    @Test
    void sensitiveColumnsInClearAreHighRiskAndAShorterGrantIsOfferedFirst() {
      AccessReview.Access open = AccessReview.access(allowed(), COLUMNS);
      AccessReview.Judgement judged =
          AccessReview.judge(
              30, "month end", requester(true, true), table(List.of()), none(), open, null, null,
              List.of());

      assertThat(judged.risk().level()).isEqualTo("HIGH");
      assertThat(factors(judged)).containsExactly("SENSITIVE_IN_CLEAR");
      assertThat(judged.risk().factors().get(0).detail()).contains("email", "citizen_id");
      assertThat(judged.suggestions())
          .extracting(AccessReview.Suggestion::kind, AccessReview.Suggestion::days)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple("GRANT", AccessReview.SHORTER_DAYS),
              org.assertj.core.groups.Tuple.tuple("GRANT", 30));
    }

    @Test
    void masksThatStayAreInformationNotAnObstacle() {
      AccessReview.Access masked = AccessReview.access(maskingEmail(), COLUMNS);
      AccessReview.Judgement judged =
          AccessReview.judge(
              5, "month end", requester(true, true), table(List.of()), none(), masked, null,
              null, List.of());

      assertThat(codes(judged)).containsExactly("MASKS_REMAIN");
      assertThat(judged.risk().level()).isEqualTo("LOW");
      assertThat(factors(judged)).containsExactly("SENSITIVE_PROTECTED");
      assertThat(judged.suggestions()).hasSize(1);
      assertThat(judged.suggestions().get(0).days()).isEqualTo(5);
    }

    @Test
    void lengthTierHistoryAndIdentityRaiseTheRisk() {
      AccessReview.Access plain =
          AccessReview.access(allowed(), List.of(ColumnContext.named("id").build()));
      AccessReview.Requester rejectedBefore =
          new AccessReview.Requester(
              "analyst_a", null, null, true, false, "local", List.of(), List.of(), List.of(),
              List.of(), 0,
              List.of(
                  new AccessReview.PastRequest(
                      UUID.randomUUID(), "REJECTED", 5, Instant.now(), "owner_a", "no", null)),
              1, 1);

      assertThat(
              factors(
                  AccessReview.judge(
                      45, "x", requester(true, true), table(List.of()), none(), plain, null,
                      null, List.of())))
          .containsExactly("LONG");
      assertThat(
              factors(
                  AccessReview.judge(
                      null, " ", rejectedBefore, table(List.of("Tier.Tier1")), none(), plain,
                      null, null, List.of())))
          .containsExactly(
              "REQUESTER_DISABLED", "OPEN_ENDED", "TIER1", "REJECTED_BEFORE", "NO_PURPOSE");
      assertThat(
              AccessReview.judge(
                      120, "x", requester(false, false), table(List.of()), none(), plain, null,
                      null, List.of())
                  .risk()
                  .factors())
          .extracting(AccessReview.Factor::code)
          .containsExactly("REQUESTER_UNKNOWN", "VERY_LONG");
    }

    @Test
    void readingItAlreadyIsAWarningAndTheSuggestionIsToDecline() {
      AccessReview.Access plain =
          AccessReview.access(allowed(), List.of(ColumnContext.named("id").build()));
      AccessReview.Judgement judged =
          AccessReview.judge(
              5, "x", requester(true, true), table(List.of()), plain, plain, null, null,
              List.of(new AccessReview.Peers("finance", "group", 3)));

      assertThat(codes(judged)).containsExactly("ALREADY_READS");
      assertThat(judged.suggestions())
          .extracting(AccessReview.Suggestion::kind)
          .containsExactly("DECLINE");
    }

    @Test
    void peersHoldingGrantsSuggestADraftPolicyThatIsNeverActive() {
      AccessReview.Access plain =
          AccessReview.access(allowed(), List.of(ColumnContext.named("id").build()));
      AccessReview.Judgement judged =
          AccessReview.judge(
              5, "x", requester(true, true), table(List.of()), none(), plain, null, null,
              List.of(new AccessReview.Peers("Finance Team", "team", 3)));

      AccessReview.Suggestion draft = judged.suggestions().get(judged.suggestions().size() - 1);
      assertThat(draft.kind()).isEqualTo("CREATE_POLICY_DRAFT");
      Policy policy = draft.draft();
      assertThat(policy.getName()).isEqualTo("finance-team-reads-customer");
      assertThat(policy.getScopeLevel()).isEqualTo(ResolvedColumnMask.ScopeLevel.TABLE);
      assertThat(policy.getScopeFqn()).isEqualTo(FQN);
      assertThat(policy.getSelector().getCondition().getValue()).isEqualTo(FQN);
      assertThat(policy.getSubject().getPrincipals().get(0).getTeam()).isEqualTo("Finance Team");
      assertThat(policy.getEffect()).isEqualTo(Policy.Effect.ALLOW);
      // Not saved, so not even a DRAFT yet; never ACTIVE.
      assertThat(policy.getLifecycleState()).isNotEqualTo(Policy.LifecycleState.ACTIVE);
      assertThat(policy.getId()).isNull();
    }

    @Test
    void aChosenPolicyThatDoesNotReachTheTableIsABlocker() {
      UUID id = UUID.randomUUID();
      AccessReview.PolicyCheck elsewhere =
          new AccessReview.PolicyCheck(id, "other", null, "ACTIVE", "prod", 1, false);
      AccessReview.Judgement judged =
          AccessReview.judge(
              5, "x", requester(true, true), table(List.of()), none(), none(), none(), elsewhere,
              List.of());

      assertThat(codes(judged)).containsExactly("GRANT_BLOCKED", "POLICY_NOT_BOUND");
    }

    @Test
    void aDraftPolicyThatWouldOpenItIsSaidToAndIsNotActivated() {
      UUID id = UUID.randomUUID();
      AccessReview.PolicyCheck draft =
          new AccessReview.PolicyCheck(id, "fin-read", "Finance read", "DRAFT", "prod", 1, true);
      AccessReview.Access open =
          AccessReview.access(allowed(), List.of(ColumnContext.named("id").build()));
      AccessReview.Judgement judged =
          AccessReview.judge(
              5, "x", requester(true, true), table(List.of()), none(), none(), open, draft,
              List.of());

      assertThat(codes(judged)).containsExactly("GRANT_BLOCKED", "POLICY_NOT_ACTIVE", "POLICY_OPENS");
      assertThat(judged.conflicts().get(1).detail()).contains("will not activate it");
    }

    @Test
    void aPolicyThatStillRefusesIsAWarning() {
      UUID id = UUID.randomUUID();
      AccessReview.PolicyCheck active =
          new AccessReview.PolicyCheck(id, "fin-read", null, "ACTIVE", "prod", 2, true);
      AccessReview.Judgement judged =
          AccessReview.judge(
              5, "x", requester(true, true), table(List.of()), none(), none(), none(), active,
              List.of());

      assertThat(codes(judged)).containsExactly("GRANT_BLOCKED", "POLICY_STILL_REFUSES");
    }

    @Test
    void anUnknownPolicyIsABlocker() {
      AccessReview.PolicyCheck missing =
          new AccessReview.PolicyCheck(UUID.randomUUID(), null, null, null, null, 0, false);
      AccessReview.Judgement judged =
          AccessReview.judge(
              5, "x", requester(true, true), table(List.of()), none(), none(), null, missing,
              List.of());

      assertThat(codes(judged)).containsExactly("GRANT_BLOCKED", "POLICY_NOT_FOUND");
    }
  }

  @Nested
  class Recommending {

    AccessReview.Requester finance() {
      return new AccessReview.Requester(
          "analyst_a", "Analyst A", null, true, true, "local", List.of(), List.of(),
          List.of(new AccessReview.Attribute("department", "FINANCE", "local")),
          List.of(), 0, List.of(), 0, 0);
    }

    AccessReview.TableFacts financeTable() {
      return new AccessReview.TableFacts(
          FQN, true, List.of(), List.of("Finance.Risk"), List.of(), 4, 2);
    }

    List<String> signals(AccessReview.Recommendation r) {
      return r.signals().stream().map(AccessReview.Signal::code).toList();
    }

    @Test
    void aShortMaskedRequestFromTheRightPeopleLeansToApprove() {
      AccessReview.Access masked = AccessReview.access(maskingEmail(), COLUMNS);
      AccessReview.Recommendation r =
          AccessReview.judge(
                  5, "month end", finance(), financeTable(), none(), masked, null, null,
                  List.of(new AccessReview.Peers("finance", "group", 3)))
              .recommendation();

      assertThat(r.verdict()).isEqualTo("APPROVE");
      assertThat(signals(r))
          .containsExactly("PURPOSE", "SENSITIVE_PROTECTED", "SHORT", "PEERS_HOLD", "DOMAIN_MATCH");
      // 50 + 10 + 10 + 10 + 15 + 10, capped at 100.
      assertThat(r.score()).isEqualTo(100);
      assertThat(r.suggestedDays()).isNull();
      assertThat(r.signals().get(4).detail()).contains("department", "FINANCE", "Finance.Risk");
    }

    @Test
    void theScoreIsNeutralPlusTheSignals() {
      AccessReview.Access plain =
          AccessReview.access(allowed(), List.of(ColumnContext.named("id").build()));
      AccessReview.Recommendation r =
          AccessReview.judge(
                  45, "x", requester(true, true), table(List.of("Tier.Tier1")), none(), plain,
                  null, null, List.of())
              .recommendation();

      int sum = r.signals().stream().mapToInt(AccessReview.Signal::points).sum();
      assertThat(r.score()).isEqualTo(AccessReview.NEUTRAL + sum);
      assertThat(signals(r)).containsExactly("PURPOSE", "NOTHING_SENSITIVE", "LONG", "TIER1");
      assertThat(r.score()).isEqualTo(55);
      assertThat(r.verdict()).isEqualTo("REVIEW");
    }

    @Test
    void sensitiveColumnsInClearForLongLeanToRejectAndOfferAShorterGrant() {
      AccessReview.Access open = AccessReview.access(allowed(), COLUMNS);
      AccessReview.Recommendation r =
          AccessReview.judge(
                  null, " ", requester(true, true), table(List.of()), none(), open, null, null,
                  List.of())
              .recommendation();

      assertThat(signals(r)).containsExactly("NO_PURPOSE", "SENSITIVE_IN_CLEAR", "OPEN_ENDED");
      assertThat(r.score()).isZero();
      assertThat(r.verdict()).isEqualTo("REJECT");
      assertThat(r.suggestedDays()).isEqualTo(AccessReview.SHORTER_DAYS);
    }

    @Test
    void aGrantAPolicyWouldDefeatNeverLeansToApprove() {
      AccessReview.Recommendation r =
          AccessReview.judge(
                  3, "month end", finance(), financeTable(), none(), none(), null, null,
                  List.of(new AccessReview.Peers("finance", "group", 4)))
              .recommendation();

      assertThat(signals(r)).startsWith("GRANT_BLOCKED");
      // 50 - 30 + 10 + 10 + 15 + 10: high, and still only REVIEW.
      assertThat(r.score()).isEqualTo(65);
      assertThat(r.verdict()).isEqualTo("REVIEW");
      assertThat(r.summary()).contains("policy in the way");
    }

    @Test
    void historyCounts() {
      AccessReview.Access plain =
          AccessReview.access(allowed(), List.of(ColumnContext.named("id").build()));
      AccessReview.Requester turnedDown =
          new AccessReview.Requester(
              "analyst_a", null, null, true, true, "local", List.of(), List.of(), List.of(),
              List.of(), 0,
              List.of(
                  new AccessReview.PastRequest(
                      UUID.randomUUID(), "REJECTED", 5, Instant.now(), "owner_a", "no", null)),
              4, 3);
      AccessReview.Requester grantedBefore =
          new AccessReview.Requester(
              "analyst_a", null, null, true, true, "local", List.of(), List.of(), List.of(),
              List.of(), 0,
              List.of(
                  new AccessReview.PastRequest(
                      UUID.randomUUID(), "COMPLETED", 5, Instant.now(), "owner_a", null, "GRANT")),
              1, 0);

      assertThat(
              signals(
                  AccessReview.judge(
                          14, "x", turnedDown, table(List.of()), none(), plain, null, null,
                          List.of())
                      .recommendation()))
          .containsExactly("PURPOSE", "NOTHING_SENSITIVE", "REJECTED_BEFORE", "OFTEN_REJECTED");
      assertThat(
              signals(
                  AccessReview.judge(
                          14, "x", grantedBefore, table(List.of()), none(), plain, null, null,
                          List.of())
                      .recommendation()))
          .containsExactly("PURPOSE", "NOTHING_SENSITIVE", "APPROVED_BEFORE");
    }

    @Test
    void nobodyToGiveItToAndNothingToGiveAreNotWeighed() {
      AccessReview.Access plain =
          AccessReview.access(allowed(), List.of(ColumnContext.named("id").build()));

      AccessReview.Recommendation gone =
          AccessReview.judge(
                  5, "x", requester(false, false), table(List.of()), none(), plain, null, null,
                  List.of())
              .recommendation();
      assertThat(gone.verdict()).isEqualTo("REJECT");
      assertThat(gone.score()).isZero();
      assertThat(signals(gone)).containsExactly("REQUESTER_UNKNOWN");

      AccessReview.Recommendation disabled =
          AccessReview.judge(
                  5, "x", requester(true, false), table(List.of()), none(), plain, null, null,
                  List.of())
              .recommendation();
      assertThat(signals(disabled)).containsExactly("REQUESTER_DISABLED");

      AccessReview.Recommendation already =
          AccessReview.judge(
                  5, "x", requester(true, true), table(List.of()), plain, plain, null, null,
                  List.of())
              .recommendation();
      assertThat(already.verdict()).isEqualTo("DECLINE");
      assertThat(already.score()).isZero();
    }

    @Test
    void aDomainMatchesSegmentForSegmentNotByPrefix() {
      assertThat(AccessReview.domainMatch(finance(), List.of("Finance Ops"))).isNull();
      assertThat(AccessReview.domainMatch(finance(), List.of("Sales.Finance")))
          .contains("Sales.Finance");
      assertThat(AccessReview.domainMatch(finance(), List.of())).isNull();
      AccessReview.Requester inGroup =
          new AccessReview.Requester(
              "analyst_a", null, null, true, true, "local", List.of(),
              List.of(new AccessReview.Membership("g1", "finance", "Finance", "group", "local")),
              List.of(), List.of(), 0, List.of(), 0, 0);
      assertThat(AccessReview.domainMatch(inGroup, List.of("Finance")))
          .contains("belong to Finance");
    }
  }

  @Test
  void slugsAreSafePolicyNames() {
    assertThat(AccessReview.slug("Finance Team-reads-kb_likes")).isEqualTo("finance-team-reads-kb-likes");
    assertThat(AccessReview.slug("--X--")).isEqualTo("x");
  }
}
