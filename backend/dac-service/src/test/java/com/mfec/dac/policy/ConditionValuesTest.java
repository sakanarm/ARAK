package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.schema.api.ResolvedRowPredicate.FacetOperator;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.AttributeCondition;
import com.mfec.dac.schema.entity.policy.ColumnRule;
import com.mfec.dac.schema.entity.policy.DataPolicy;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.RowFilter;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConditionValuesTest {

  private static Policy selecting(FacetCondition condition) {
    return new Policy().withSelector(new AssetSelector().withCondition(condition));
  }

  private static FacetCondition tags(FacetOperator operator) {
    return new FacetCondition().withFacet(FacetType.TAGS).withOperator(operator);
  }

  private static Policy withAttribute(AttributeCondition attribute) {
    return selecting(tags(FacetOperator.CONTAINS).withValue("PII"))
        .withSubject(new SubjectRule().withAttributes(List.of(attribute)));
  }

  @Test
  @DisplayName("a list in values, and a single value elsewhere, are accepted")
  void wellFormedConditionsPass() {
    Policy document =
        selecting(tags(FacetOperator.IN).withValues(List.of("PII.Sensitive", "PII.NonSensitive")))
            .withSubject(
                new SubjectRule()
                    .withAttributes(
                        List.of(
                            new AttributeCondition()
                                .withKey("department")
                                .withOperator(FacetOperator.NOT_IN)
                                .withValues(List.of("SALES")),
                            new AttributeCondition()
                                .withKey("clearance")
                                .withOperator(FacetOperator.EXISTS))));
    assertThatCode(() -> ConditionValues.check(document)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("is one of with the list typed into value is refused")
  void listInValueIsRefused() {
    // The shape the editor used to save: one string no real tag equals.
    Policy document = selecting(tags(FacetOperator.IN).withValue("PII.Sensitive, PII.NonSensitive"));
    assertThatThrownBy(() -> ConditionValues.check(document))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tags")
        .hasMessageContaining("\"is one of\" but lists no values");
  }

  @Test
  @DisplayName("is none of with nothing listed is refused, in the subject as in the selector")
  void emptyNotInIsRefused() {
    Policy document =
        withAttribute(
            new AttributeCondition()
                .withKey("department")
                .withOperator(FacetOperator.NOT_IN)
                .withValues(List.of(" ")));
    assertThatThrownBy(() -> ConditionValues.check(document))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("department")
        .hasMessageContaining("\"is none of\"");
  }

  @Test
  @DisplayName("a list with a stray single value beside it is refused")
  void valueBesideValuesIsRefused() {
    Policy document =
        selecting(tags(FacetOperator.IN).withValue("PII").withValues(List.of("PII.Sensitive")));
    assertThatThrownBy(() -> ConditionValues.check(document))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("would be ignored");
  }

  @Test
  @DisplayName("an empty value left beside the list is not a second value")
  void blankValueBesideValuesPasses() {
    Policy document =
        selecting(tags(FacetOperator.IN).withValue("").withValues(List.of("PII.Sensitive")));
    assertThatCode(() -> ConditionValues.check(document)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("is not followed by nothing is refused")
  void blankNotEqualIsRefused() {
    Policy document =
        withAttribute(
            new AttributeCondition()
                .withKey("department")
                .withOperator(FacetOperator.NE)
                .withValue(""));
    assertThatThrownBy(() -> ConditionValues.check(document))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no value to compare against");
  }

  @Test
  @DisplayName("nested selectors and column rules are checked too")
  void nestedAndColumnRulesAreChecked() {
    Policy nested =
        new Policy()
            .withSelector(
                new AssetSelector()
                    .withAnd(
                        List.of(
                            new AssetSelector()
                                .withCondition(tags(FacetOperator.CONTAINS).withValue("PII")),
                            new AssetSelector()
                                .withNot(
                                    new AssetSelector()
                                        .withCondition(tags(FacetOperator.NOT_IN))))));
    assertThatThrownBy(() -> ConditionValues.check(nested))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("The asset selector");

    Policy masking =
        selecting(tags(FacetOperator.CONTAINS).withValue("PII"))
            .withData(
                new DataPolicy()
                    .withColumnRules(
                        List.of(
                            new ColumnRule()
                                .withColumns(
                                    new AssetSelector()
                                        .withCondition(
                                            new FacetCondition()
                                                .withFacet(FacetType.COLUMN_NAME)
                                                .withOperator(FacetOperator.EQ))))));
    assertThatThrownBy(() -> ConditionValues.check(masking))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("A column rule");
  }

  private static Policy filtering(RowFilter filter) {
    return selecting(tags(FacetOperator.CONTAINS).withValue("PII"))
        .withData(new DataPolicy().withRowFilters(List.of(filter)));
  }

  private static RowFilter byTag(String tag) {
    return new RowFilter()
        .withKind(RowFilter.Kind.ATTRIBUTE_COMPARE)
        .withOperator(FacetOperator.EQ)
        .withUserAttribute("branch")
        .withColumns(
            new AssetSelector().withCondition(tags(FacetOperator.CONTAINS).withValue(tag)));
  }

  @Test
  @DisplayName("a row filter that picks its column by tag is accepted")
  void rowFilterColumnSelectorPasses() {
    assertThatCode(() -> ConditionValues.check(filtering(byTag("Org.Branch"))))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a row filter that names its column and also selects one is refused")
  void rowFilterColumnAndSelectorIsRefused() {
    assertThatThrownBy(
            () -> ConditionValues.check(filtering(byTag("Org.Branch").withColumn("branch_code"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("give one of them");
  }

  @Test
  @DisplayName("a row filter's column selector with no tag, or no condition, is refused")
  void rowFilterEmptySelectorIsRefused() {
    assertThatThrownBy(() -> ConditionValues.check(filtering(byTag(""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("A row filter's column selector")
        .hasMessageContaining("no value to compare against");
    assertThatThrownBy(
            () ->
                ConditionValues.check(
                    filtering(byTag("Org.Branch").withColumns(new AssetSelector()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("picks no column");
  }

  @Test
  @DisplayName("a column selector on a row filter that compares no column is refused")
  void rowFilterSelectorOnOtherKindIsRefused() {
    assertThatThrownBy(
            () ->
                ConditionValues.check(
                    filtering(byTag("Org.Branch").withKind(RowFilter.Kind.ALWAYS_FALSE))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("would be ignored");
  }
}
