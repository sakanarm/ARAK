package com.mfec.dac.engine;

import com.mfec.dac.common.Fqns;
import com.mfec.dac.schema.api.ResolvedRowPredicate.FacetOperator;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The one implementation of the comparison operators.
 *
 * <p>The same operator set appears on both sides of a policy - on facets of the
 * asset and on attributes of the user - and cross-side comparison only means
 * anything if both sides read them identically. Two implementations would drift,
 * and the drift would show up as a policy that matches an asset but not the user
 * it was written for.
 *
 * <p>Every operator is existential over the actual values: a multi-valued
 * attribute or facet satisfies EQ when any one of its values does. NE and NOT_IN
 * are the negation of that, so they hold only when no value matches -- and
 * neither holds when the condition gives nothing to compare against. "Is not"
 * followed by nothing is a half-written condition, and reading it as true
 * would let it admit, or select, everything.
 */
final class Operators {

  private Operators() {}

  static boolean evaluate(FacetOperator op, List<String> actual, Object target, List<Object> targets) {
    String targetText = Comparisons.asString(target);
    switch (op) {
      case EXISTS:
        return !actual.isEmpty();
      case NOT_EXISTS:
        return actual.isEmpty();
      case EQ:
        return any(actual, v -> Fqns.equal(v, targetText) || Comparisons.equal(v, target));
      case NE:
        return targetText != null
            && !targetText.isBlank()
            && !any(actual, v -> Fqns.equal(v, targetText) || Comparisons.equal(v, target));
      case CONTAINS:
        return any(actual, v -> Fqns.isDescendantOrSelf(v, targetText));
      case STARTS_WITH:
        return any(actual, v -> startsWith(v, targetText));
      case MATCHES:
        return matchesRegex(actual, targetText);
      case IN: {
        List<Object> list = listOf(target, targets);
        return any(actual, v -> inList(v, list));
      }
      case NOT_IN: {
        List<Object> list = listOf(target, targets);
        return !list.isEmpty() && !any(actual, v -> inList(v, list));
      }
      case GT:
        return compares(actual, target, c -> c > 0);
      case GTE:
        return compares(actual, target, c -> c >= 0);
      case LT:
        return compares(actual, target, c -> c < 0);
      case LTE:
        return compares(actual, target, c -> c <= 0);
      default:
        return false;
    }
  }

  private interface ValuePredicate {
    boolean test(String value);
  }

  private interface IntCheck {
    boolean test(int comparison);
  }

  private static boolean any(List<String> values, ValuePredicate predicate) {
    for (String v : values) {
      if (predicate.test(v)) {
        return true;
      }
    }
    return false;
  }

  private static boolean startsWith(String value, String prefix) {
    return value != null && prefix != null
        && value.regionMatches(true, 0, prefix, 0, prefix.length());
  }

  /**
   * The list an IN or NOT_IN compares against.
   *
   * <p>The list belongs in {@code values}. The policy editor used to put what
   * was typed into {@code value} instead, as one string, so "is one of" matched
   * nobody and "is none of" matched everybody -- in an ALLOW, everybody was let
   * in. Policies saved then are still stored that way. They are read here as
   * the editor showed them to their author, the text split at commas. Saving
   * now refuses that shape, so only an older document reaches this branch.
   */
  static List<Object> listOf(Object target, List<Object> targets) {
    if (targets != null && !targets.isEmpty()) {
      return targets;
    }
    if (target instanceof String text) {
      List<Object> out = new ArrayList<>();
      for (String part : text.split(",")) {
        if (!part.isBlank()) {
          out.add(part.strip());
        }
      }
      return out;
    }
    return target == null ? List.of() : List.of(target);
  }

  private static boolean inList(String value, List<Object> candidates) {
    for (Object candidate : candidates) {
      if (Fqns.equal(value, Comparisons.asString(candidate)) || Comparisons.equal(value, candidate)) {
        return true;
      }
    }
    return false;
  }

  private static boolean compares(List<String> values, Object target, IntCheck check) {
    for (String v : values) {
      Integer c = Comparisons.compare(v, target);
      if (c != null && check.test(c)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesRegex(List<String> values, String regex) {
    if (regex == null) {
      return false;
    }
    Pattern pattern;
    try {
      pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    } catch (PatternSyntaxException e) {
      // One policy that will not compile must not take evaluation down for
      // every asset, so it matches nothing.
      return false;
    }
    return any(values, v -> v != null && pattern.matcher(v).matches());
  }
}
