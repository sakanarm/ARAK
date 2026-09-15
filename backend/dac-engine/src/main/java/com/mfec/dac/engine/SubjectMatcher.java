package com.mfec.dac.engine;

import com.mfec.dac.schema.entity.policy.AttributeCondition;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.PrincipalMatch;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import java.util.List;

/**
 * Decides whether a subject rule matches this principal, on this asset, now.
 *
 * <p>RBAC, ABAC, rule-based and time-based access are not four engines here.
 * They are four sections of one predicate, ANDed together: the principal list is
 * an OR of identities, the attribute list is an AND of conditions about the
 * user, the expression compares the two sides against each other, and the time
 * and context sections bound when and from where any of it counts. Splitting
 * them into separate engines is how systems like this end up with four different
 * answers to the same question.
 *
 * <p>An empty rule matches nobody. The schema says so in as many words, and the
 * reason is that the alternative - reading silence as "everyone" - turns an
 * unfinished policy into a grant. An absent rule is different: a policy with no
 * subject at all is about the asset, not about a person, which is how "mask
 * every PII column" is written.
 */
public final class SubjectMatcher {

  /**
   * @param matched     whether the rule holds
   * @param explanation why, in the words that reach the audit log and the UI
   */
  public record Result(boolean matched, String explanation) {

    static Result yes(String explanation) {
      return new Result(true, explanation);
    }

    static Result no(String explanation) {
      return new Result(false, explanation);
    }
  }

  private SubjectMatcher() {}

  public static Result matches(
      SubjectRule rule,
      Principal principal,
      AssetContext asset,
      RequestContext context,
      EngineConfig config)
      throws ExpressionUnavailableException {

    if (rule == null) {
      return Result.yes("policy carries no subject rule, so it applies to every principal");
    }
    boolean hasAnything =
        notEmpty(rule.getPrincipals())
            || notEmpty(rule.getAttributes())
            || (rule.getExpression() != null && !rule.getExpression().isBlank())
            || rule.getTime() != null
            || rule.getContext() != null;
    if (!hasAnything) {
      return Result.no("subject rule is present but states no condition; an empty rule matches "
          + "nobody, never everybody");
    }

    if (notEmpty(rule.getPrincipals())) {
      PrincipalMatch hit = firstPrincipalMatch(rule.getPrincipals(), principal, asset);
      if (hit == null) {
        return Result.no("principal is none of the roles, teams, groups or users the policy names");
      }
    }

    if (notEmpty(rule.getAttributes())) {
      for (AttributeCondition condition : rule.getAttributes()) {
        if (!attributeHolds(condition, principal)) {
          return Result.no("attribute condition not satisfied: " + describe(condition));
        }
      }
    }

    if (rule.getExpression() != null && !rule.getExpression().isBlank()) {
      ExpressionEvaluator.Result result =
          config.expressions().evaluate(rule.getExpression(), principal, asset, context);
      if (result == ExpressionEvaluator.Result.ROW_DEPENDENT) {
        // A subject rule decides who reaches the table at all, before any row
        // exists. An expression that needs a row cannot answer that question,
        // and pretending otherwise would let it through untested.
        throw new ExpressionUnavailableException(
            rule.getExpression(), "a subject expression cannot depend on row data");
      }
      if (result == ExpressionEvaluator.Result.FALSE) {
        return Result.no("expression is false for this principal: " + rule.getExpression());
      }
    }

    if (rule.getTime() != null
        && !TimeMatcher.matches(rule.getTime(), context.at(), config.defaultZone())) {
      return Result.no("outside the policy's permitted time window");
    }

    if (rule.getContext() != null && !ContextMatcher.matches(rule.getContext(), context)) {
      return Result.no("request network address or declared purpose is outside the policy's context rule");
    }

    return Result.yes("subject rule satisfied");
  }

  /**
   * The principals list is an OR of identities; within one entry the fields are
   * ANDed, so an entry naming both a team and a role means someone who is both.
   */
  private static PrincipalMatch firstPrincipalMatch(
      List<PrincipalMatch> matches, Principal principal, AssetContext asset) {
    for (PrincipalMatch match : matches) {
      if (principalHolds(match, principal, asset)) {
        return match;
      }
    }
    return null;
  }

  private static boolean principalHolds(
      PrincipalMatch match, Principal principal, AssetContext asset) {
    boolean stated = false;
    if (match.getRole() != null) {
      stated = true;
      if (!principal.hasRole(match.getRole())) {
        return false;
      }
    }
    if (match.getTeam() != null) {
      stated = true;
      if (!principal.hasTeam(match.getTeam())) {
        return false;
      }
    }
    if (match.getGroup() != null) {
      stated = true;
      if (!principal.hasGroup(match.getGroup())) {
        return false;
      }
    }
    if (match.getUser() != null) {
      stated = true;
      if (!principal.is(match.getUser())) {
        return false;
      }
    }
    if (Boolean.TRUE.equals(match.getAssetOwner())) {
      stated = true;
      if (!isOwner(principal, asset)) {
        return false;
      }
    }
    // An entry that names nobody grants to nobody, for the same reason an empty
    // rule does.
    return stated;
  }

  /**
   * Owner matching accepts the user's id, their email, or any team they belong
   * to, because OpenMetadata records owners as either users or teams and refers
   * to users by whichever of the two the catalog was populated with.
   */
  private static boolean isOwner(Principal principal, AssetContext asset) {
    List<FacetValue> owners = asset.facetValues(FacetCondition.FacetType.OWNERS, null);
    for (FacetValue owner : owners) {
      String value = owner.value();
      if (value == null) {
        continue;
      }
      if (principal.is(value) || principal.hasTeam(value)) {
        return true;
      }
    }
    return false;
  }

  private static boolean attributeHolds(AttributeCondition condition, Principal principal) {
    if (condition == null || condition.getKey() == null || condition.getOperator() == null) {
      return false;
    }
    List<String> values = principal.attributeValues(condition.getKey(), condition.getSource());
    return Operators.evaluate(
        condition.getOperator(), values, condition.getValue(), condition.getValues());
  }

  private static String describe(AttributeCondition condition) {
    Object target = condition.getValue() != null ? condition.getValue() : condition.getValues();
    return condition.getKey() + " " + condition.getOperator() + " " + target;
  }

  private static boolean notEmpty(List<?> list) {
    return list != null && !list.isEmpty();
  }
}
