package com.mfec.dac.engine;

import com.mfec.dac.schema.entity.policy.AssetSelector;
import java.util.List;

/**
 * Evaluates an asset selector - the boolean tree of facet conditions that
 * decides which assets a policy binds to, and which columns a column rule
 * covers.
 *
 * <p>An empty selector matches nothing. A policy whose selector says nothing has
 * not been written yet, and reading that as "everything" would silently bind a
 * half-finished policy to the entire estate.
 *
 * <p>The three branch fields are ANDed with each other and with the condition,
 * because that is how they read: a selector carrying both a condition and an
 * "or" list means "this condition, and one of these". Within "or" the children
 * are alternatives; within "and" they are all required.
 */
public final class SelectorMatcher {

  private SelectorMatcher() {}

  public static boolean matches(AssetSelector selector, FacetSource source) {
    if (selector == null) {
      return false;
    }
    boolean any = false;
    boolean result = true;

    if (selector.getCondition() != null) {
      any = true;
      result = FacetMatcher.matches(selector.getCondition(), source);
    }
    if (result && notEmpty(selector.getAnd())) {
      any = true;
      for (AssetSelector child : selector.getAnd()) {
        if (!matches(child, source)) {
          result = false;
          break;
        }
      }
    }
    if (result && notEmpty(selector.getOr())) {
      any = true;
      boolean matched = false;
      for (AssetSelector child : selector.getOr()) {
        if (matches(child, source)) {
          matched = true;
          break;
        }
      }
      result = matched;
    }
    if (result && selector.getNot() != null) {
      any = true;
      result = !matches(selector.getNot(), source);
    }
    return any && result;
  }

  private static boolean notEmpty(List<AssetSelector> list) {
    return list != null && !list.isEmpty();
  }
}
