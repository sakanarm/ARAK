package com.mfec.dac.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the documentation.
 *
 * <p>Every example on the syntax page carries the principal, the asset and the
 * answer it claims, and this puts all of them through the real evaluator. The
 * point is not to test the evaluator — {@link PolicyExpressionEvaluatorTest}
 * does that — but to stop the page being wrong. A policy author who follows a
 * worked example and gets a different answer stops trusting the whole page, and
 * has no way to tell which half lied. So the build is what finds out first.
 *
 * <p>Each case is its own test so a failure names the example and the row it is
 * in rather than reporting that "the reference" is broken.
 */
class ExpressionReferenceTest {

  private static final PolicyExpressionEvaluator ENGINE = new PolicyExpressionEvaluator();

  @Nested
  @DisplayName("every worked example")
  class Examples {

    @TestFactory
    @DisplayName("gives the answer it says it does")
    List<DynamicTest> cases() {
      List<DynamicTest> tests = new ArrayList<>();
      for (JsonNode example : ExpressionReference.document().get("examples")) {
        String expression = example.get("expression").asText();
        for (JsonNode one : example.get("cases")) {
          String name =
              example.get("id").asText() + " — " + one.get("given").asText();
          tests.add(
              DynamicTest.dynamicTest(
                  name,
                  () ->
                      assertThat(outcome(expression, one))
                          .as("%s%n  %s", name, expression)
                          .isEqualTo(one.get("expect").asText())));
        }
      }
      return tests;
    }

    @TestFactory
    @DisplayName("is something the builder would accept")
    List<DynamicTest> accepted() {
      List<DynamicTest> tests = new ArrayList<>();
      for (JsonNode example : ExpressionReference.document().get("examples")) {
        String expression = example.get("expression").asText();
        tests.add(
            DynamicTest.dynamicTest(
                example.get("id").asText(),
                () -> {
                  PolicyExpressionEvaluator.Validation result =
                      PolicyExpressionEvaluator.validate(expression);
                  assertThat(result.valid())
                      .as("%s rejected: %s", expression, result.message())
                      .isTrue();
                }));
      }
      return tests;
    }

    @Test
    @DisplayName("there is more than a token few of them")
    void enough() {
      // A guard against the reference quietly shrinking to the one example
      // somebody was debugging. The page has to cover the language.
      assertThat(ExpressionReference.document().get("examples")).hasSizeGreaterThanOrEqualTo(10);
    }
  }

  @Nested
  @DisplayName("every expression the page calls wrong")
  class Rejected {

    @TestFactory
    @DisplayName("really is rejected")
    List<DynamicTest> cases() {
      List<DynamicTest> tests = new ArrayList<>();
      for (JsonNode bad : ExpressionReference.document().get("rejected")) {
        String expression = bad.get("expression").asText();
        tests.add(
            DynamicTest.dynamicTest(
                expression,
                () ->
                    assertThat(PolicyExpressionEvaluator.validate(expression).valid())
                        .as("the page says this cannot be saved: %s", bad.get("why").asText())
                        .isFalse()));
      }
      return tests;
    }
  }

  @Nested
  @DisplayName("every name the page lists")
  class Members {

    /**
     * The page names {@code asset.tier} and {@code context.purpose} as things a
     * policy can refer to. Those two roots are closed lists in the parser, so a
     * name the page invents is a name the parser throws on — which is exactly
     * the sort of drift that only shows up when somebody tries to save.
     */
    @TestFactory
    @DisplayName("is a name the parser resolves")
    List<DynamicTest> cases() {
      List<DynamicTest> tests = new ArrayList<>();
      for (JsonNode root : ExpressionReference.document().get("roots")) {
        if (root.get("open").asBoolean()) {
          // user. and row. take anything by design, so there is nothing here
          // the parser could contradict.
          continue;
        }
        for (JsonNode member : root.get("members")) {
          for (String name : withAliases(member)) {
            if (name.contains("<")) {
              continue;
            }
            tests.add(
                DynamicTest.dynamicTest(
                    name,
                    () -> {
                      PolicyExpressionEvaluator.Validation result =
                          PolicyExpressionEvaluator.validate(name + " == 'probe'");
                      assertThat(result.valid())
                          .as("the page lists %s but the parser says: %s", name, result.message())
                          .isTrue();
                    }));
          }
        }
      }
      return tests;
    }

    private List<String> withAliases(JsonNode member) {
      List<String> names = new ArrayList<>();
      names.add(member.get("name").asText());
      for (JsonNode alias : member.get("aliases")) {
        names.add(alias.asText());
      }
      return names;
    }
  }

  // ------------------------------------------------------------- fixtures

  /** The documented answer, with the undecidable case named rather than thrown. */
  private static String outcome(String expression, JsonNode one) {
    try {
      return ENGINE
          .evaluate(expression, principal(one.get("principal")), asset(one.get("asset")), context(one.get("context")))
          .name();
    } catch (ExpressionUnavailableException e) {
      // Which is how the page describes it: not false, not true, undecidable.
      return "UNKNOWN";
    }
  }

  private static Principal principal(JsonNode node) {
    Principal.Builder builder = Principal.withId(text(node, "id", ""));
    if (node.hasNonNull("email")) {
      builder.email(node.get("email").asText());
    }
    builder.roles(strings(node, "roles"));
    builder.teams(strings(node, "teams"));
    builder.groups(strings(node, "groups"));
    JsonNode attributes = node.get("attributes");
    if (attributes != null) {
      for (Iterator<Map.Entry<String, JsonNode>> it = attributes.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> attribute = it.next();
        for (JsonNode value : attribute.getValue()) {
          builder.attribute(attribute.getKey(), value.asText());
        }
      }
    }
    return builder.build();
  }

  private static AssetContext asset(JsonNode node) {
    AssetContext.Builder builder = AssetContext.of(text(node, "fqn", "probe.db.schema.table"));
    JsonNode facets = node.get("facets");
    if (facets != null) {
      for (Iterator<Map.Entry<String, JsonNode>> it = facets.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> facet = it.next();
        builder.facet(facetType(facet.getKey()), values(facet.getValue()));
      }
    }
    JsonNode properties = node.get("properties");
    if (properties != null) {
      for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> property = it.next();
        builder.property(property.getKey(), property.getValue().asText());
      }
    }
    return builder.build();
  }

  private static RequestContext context(JsonNode node) {
    if (node == null) {
      return new RequestContext(Instant.EPOCH, null, null);
    }
    return new RequestContext(
        Instant.EPOCH, text(node, "ip", null), text(node, "purpose", null));
  }

  private static FacetCondition.FacetType facetType(String value) {
    for (FacetCondition.FacetType type : FacetCondition.FacetType.values()) {
      if (type.value().equalsIgnoreCase(value)) {
        return type;
      }
    }
    throw new IllegalArgumentException(
        "the reference uses a facet called '" + value + "', which is not one the schema has");
  }

  private static String[] values(JsonNode array) {
    List<String> out = new ArrayList<>();
    for (JsonNode value : array) {
      out.add(value.asText());
    }
    return out.toArray(new String[0]);
  }

  private static String[] strings(JsonNode node, String field) {
    JsonNode array = node.get(field);
    return array == null ? new String[0] : values(array);
  }

  private static String text(JsonNode node, String field, String fallback) {
    return node != null && node.hasNonNull(field) ? node.get(field).asText() : fallback;
  }
}
