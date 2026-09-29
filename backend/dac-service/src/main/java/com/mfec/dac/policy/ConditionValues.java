package com.mfec.dac.policy;

import com.mfec.dac.schema.api.ResolvedRowPredicate.FacetOperator;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.AttributeCondition;
import com.mfec.dac.schema.entity.policy.ColumnRule;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.Policy;
import java.util.List;

/**
 * Refuses a condition whose values are in the wrong place, or missing.
 *
 * <p>The engine reads "is one of" and "is none of" from {@code values} and
 * every other comparison from {@code value}. A list put in {@code value} is
 * one string that no real value equals, so "is one of" matched nobody and
 * "is none of" matched everybody: an ALLOW written that way let everyone in,
 * and a selector written that way covered every asset. The policy editor
 * wrote exactly that until it was fixed, and nothing on the way to the store
 * said so. A comparison with nothing to compare against is refused for the
 * same reason -- "department is not" followed by nothing is a half-written
 * rule, not a rule about everybody.
 *
 * <p>Checked on every condition the engine evaluates against a document: the
 * asset selector, each column rule's selector, and the subject's attributes.
 */
final class ConditionValues {

  private ConditionValues() {}

  /** @throws IllegalArgumentException naming the first condition that is wrong */
  static void check(Policy document) {
    selector(document.getSelector(), "The asset selector");
    if (document.getData() != null && document.getData().getColumnRules() != null) {
      for (ColumnRule rule : document.getData().getColumnRules()) {
        if (rule != null) {
          selector(rule.getColumns(), "A column rule");
        }
      }
    }
    if (document.getSubject() != null && document.getSubject().getAttributes() != null) {
      for (AttributeCondition attribute : document.getSubject().getAttributes()) {
        if (attribute != null) {
          compare(
              "The attribute condition on " + attribute.getKey(),
              attribute.getOperator(),
              attribute.getValue(),
              attribute.getValues());
        }
      }
    }
  }

  private static void selector(AssetSelector selector, String where) {
    if (selector == null) {
      return;
    }
    FacetCondition condition = selector.getCondition();
    if (condition != null) {
      compare(
          where + "'s condition on " + facet(condition),
          condition.getOperator(),
          condition.getValue(),
          condition.getValues());
    }
    children(selector.getAnd(), where);
    children(selector.getOr(), where);
    selector(selector.getNot(), where);
  }

  private static void children(List<AssetSelector> branch, String where) {
    if (branch != null) {
      for (AssetSelector child : branch) {
        selector(child, where);
      }
    }
  }

  private static void compare(
      String what, FacetOperator operator, Object value, List<Object> values) {
    if (operator == null) {
      return;
    }
    switch (operator) {
      case EXISTS, NOT_EXISTS -> {}
      case IN, NOT_IN -> {
        String words = operator == FacetOperator.IN ? "\"is one of\"" : "\"is none of\"";
        if (values == null || values.stream().allMatch(ConditionValues::blank)) {
          throw new IllegalArgumentException(
              what + " uses " + words + " but lists no values; give them as values, one per item");
        }
        if (!blank(value)) {
          throw new IllegalArgumentException(
              what + " uses " + words + " and also has a single value, which would be ignored;"
                  + " put every item in values");
        }
      }
      default -> {
        if (blank(value)) {
          throw new IllegalArgumentException(what + " has no value to compare against");
        }
      }
    }
  }

  private static String facet(FacetCondition condition) {
    if (condition.getFacet() == null) {
      return "an unnamed facet";
    }
    String facet = condition.getFacet().value();
    return condition.getProperty() == null ? facet : facet + " " + condition.getProperty();
  }

  private static boolean blank(Object value) {
    return value == null || (value instanceof String text && text.isBlank());
  }
}
