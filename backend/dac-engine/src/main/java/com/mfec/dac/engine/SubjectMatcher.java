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
 * an OR of identities, the required-principal list is an AND of them, the
 * attribute list is an AND of conditions about the user, the expression compares
 * the two sides against each other, and the time and context sections bound when
 * and from where any of it counts. Splitting them into separate engines is how
 * systems like this end up with four different answers to the same question.
 *
 * <p>The two identity lists exist because membership questions come in both
 * shapes and one list can only answer one of them. "Anyone in Finance or Risk"
 * is an OR; "and they must also be in the group that has done the privacy
 * training" is an AND, and writing it as a second entry in the OR list would
 * widen the grant to everyone who has done the training. Together they cover
 * (A or B) and C and D, which is as far as a legible form goes; past that the
 * author has {@code expression}.
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

  /**
   * Said when every identity condition held and only the clock did not. A rule
   * is checked in the order identity, attributes, expression, time, context, so
   * reaching this means the policy was written for this principal.
   */
  public static final String OUTSIDE_TIME_WINDOW = "outside the policy's permitted time window";

  /** As {@link #OUTSIDE_TIME_WINDOW}, for the network address or the declared purpose. */
  public static final String OUTSIDE_CONTEXT =
      "request network address or declared purpose is outside the policy's context rule";

  /**
   * True when a policy is for this principal, only not now or not from here.
   * That is the refusal worth naming first: the answer is to wait or to say
   * why, not to go and ask for different attributes.
   */
  public static boolean nearMiss(String explanation) {
    return OUTSIDE_TIME_WINDOW.equals(explanation) || OUTSIDE_CONTEXT.equals(explanation);
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
            || notEmpty(rule.getRequiredPrincipals())
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

    if (notEmpty(rule.getRequiredPrincipals())) {
      for (PrincipalMatch required : rule.getRequiredPrincipals()) {
        if (!principalHolds(required, principal, asset)) {
          return Result.no(
              "principal does not hold every identity the policy requires; missing "
                  + describe(required));
        }
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
      return Result.no(OUTSIDE_TIME_WINDOW);
    }

    if (rule.getContext() != null && !ContextMatcher.matches(rule.getContext(), context)) {
      return Result.no(OUTSIDE_CONTEXT);
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

  /**
   * One identity requirement in the words the audit log uses. An entry naming
   * several fields is reported in full, because it is satisfied only by
   * somebody who holds all of them and a partial message would send the reader
   * looking for the wrong thing.
   */
  private static String describe(PrincipalMatch match) {
    StringBuilder sb = new StringBuilder();
    append(sb, "role", match.getRole());
    append(sb, "team", match.getTeam());
    append(sb, "group", match.getGroup());
    append(sb, "user", match.getUser());
    if (Boolean.TRUE.equals(match.getAssetOwner())) {
      append(sb, "owner of", "this asset");
    }
    return sb.length() == 0 ? "an entry that names nobody" : sb.toString();
  }

  private static void append(StringBuilder sb, String label, String value) {
    if (value == null) {
      return;
    }
    if (sb.length() > 0) {
      sb.append(" and ");
    }
    sb.append(label).append(' ').append(value);
  }

  private static String describe(AttributeCondition condition) {
    Object target = condition.getValue() != null ? condition.getValue() : condition.getValues();
    return condition.getKey() + " " + condition.getOperator() + " " + target;
  }

  private static boolean notEmpty(List<?> list) {
    return list != null && !list.isEmpty();
  }
}
