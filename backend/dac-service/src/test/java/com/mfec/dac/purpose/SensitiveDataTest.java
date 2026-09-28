package com.mfec.dac.purpose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.engine.ColumnContext;
import com.mfec.dac.engine.FacetValue;
import com.mfec.dac.purpose.SensitiveData.Kind;
import com.mfec.dac.purpose.SensitiveData.Label;
import com.mfec.dac.purpose.SensitiveData.Mode;
import com.mfec.dac.purpose.SensitiveData.Rule;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What counts as sensitive data (FR-21, M31b), without a database: which labels
 * a rule counts, and the words a person reads when their purpose does not allow it.
 */
@DisplayName("SensitiveData")
class SensitiveDataTest {

  private static Rule rule(boolean builtIn, List<Label> include, List<Label> exclude) {
    return new Rule(builtIn, include, exclude, Mode.WARN, "author_a", null);
  }

  private static Label classification(String fqn) {
    return new Label(Kind.CLASSIFICATION, fqn);
  }

  private static Label tag(String fqn) {
    return new Label(Kind.TAG, fqn);
  }

  private static Label glossary(String fqn) {
    return new Label(Kind.GLOSSARY, fqn);
  }

  private static Label term(String fqn) {
    return new Label(Kind.TERM, fqn);
  }

  @Test
  @DisplayName("the built-in rule counts what the code always counted, and says so for the most specific tag only")
  void builtIn() {
    Rule r = Rule.BUILT_IN;

    assertThat(r.labels(List.of("PII", "PII.Sensitive"), List.of())).containsExactly("PII.Sensitive");
    // The PII beside PII.NonSensitive is that tag's parent, not a second label.
    assertThat(r.labels(List.of("PII", "PII.NonSensitive"), List.of())).isEmpty();
    assertThat(r.labels(List.of("PersonalData", "PersonalData.Personal"), List.of()))
        .containsExactly("PersonalData.Personal");
    assertThat(r.labels(List.of("Security.Confidential"), List.of()))
        .containsExactly("Security.Confidential");
    assertThat(r.labels(List.of("Tier", "Tier.Tier1"), List.of())).isEmpty();
    assertThat(r.labels(List.of("Security.Public"), List.of())).isEmpty();
  }

  @Test
  @DisplayName("the built-in rule never reads a glossary term as sensitive by its name alone")
  void builtInLeavesTermsAlone() {
    assertThat(Rule.BUILT_IN.labels(List.of(), List.of("Finance", "Finance.SecretSauce"))).isEmpty();
  }

  @Test
  @DisplayName("a classification in \"counts\" covers every tag in it, and a glossary every term in it")
  void includeCoversChildren() {
    Rule r = rule(false, List.of(classification("Finance"), glossary("Customer")), List.of());

    assertThat(r.labels(List.of("Finance", "Finance.Salary"), List.of())).containsExactly("Finance.Salary");
    assertThat(r.labels(List.of(), List.of("Customer", "Customer.Identity")))
        .containsExactly("Customer.Identity");
    // With the built-in rule off, PII is only sensitive when a list says so.
    assertThat(r.labels(List.of("PII", "PII.Sensitive"), List.of())).isEmpty();
    // A name that merely starts the same is not beneath it.
    assertThat(r.labels(List.of("FinanceOps.Budget"), List.of())).isEmpty();
  }

  @Test
  @DisplayName("\"never counts\" wins over \"counts\" and over the built-in rule")
  void excludeWins() {
    Rule r = rule(true, List.of(classification("PII")), List.of(tag("PII.Sensitive.Hashed")));

    assertThat(r.labels(List.of("PII", "PII.Sensitive", "PII.Sensitive.Hashed"), List.of())).isEmpty();
    assertThat(r.labels(List.of("PII", "PII.NonSensitive"), List.of()))
        .as("PII in \"counts\" takes in PII.NonSensitive, which the built-in rule alone would not")
        .containsExactly("PII.NonSensitive");

    Rule quiet = rule(true, List.of(), List.of(classification("Security")));
    assertThat(quiet.labels(List.of("Security.Confidential"), List.of())).isEmpty();
  }

  @Test
  @DisplayName("a label names one vocabulary: a tag in the lists says nothing of a term spelt the same")
  void vocabulariesStayApart() {
    Rule r = rule(false, List.of(tag("Finance")), List.of());

    assertThat(r.labels(List.of(), List.of("Finance.Revenue"))).isEmpty();
    assertThat(rule(false, List.of(term("Finance")), List.of()).labels(List.of("Finance.Revenue"), List.of()))
        .isEmpty();
  }

  @Test
  @DisplayName("names match whatever case either was typed in")
  void caseInsensitive() {
    Rule r = rule(false, List.of(classification("finance")), List.of(tag("FINANCE.PUBLIC")));

    assertThat(r.labels(List.of("Finance.Salary", "Finance.Public"), List.of()))
        .containsExactly("Finance.Salary");
    assertThat(SensitiveData.leaves(List.of("PII", "pii.Sensitive", "PII"))).containsExactly("pii.Sensitive");
  }

  @Test
  @DisplayName("a column is read by its confirmed labels of all four kinds, never a suggested one")
  void column() {
    ColumnContext tagged =
        ColumnContext.named("email")
            .facet(FacetType.CLASSIFICATIONS, "PII")
            .facet(FacetType.TAGS, "PII", "PII.Sensitive")
            .build();
    ColumnContext suggested =
        ColumnContext.named("phone").facet(FacetType.TAGS, FacetValue.suggested("PII.Sensitive")).build();
    ColumnContext termed =
        ColumnContext.named("citizen_id")
            .facet(FacetType.GLOSSARIES, "Customer")
            .facet(FacetType.TERMS, "Customer", "Customer.Identity")
            .build();

    assertThat(Rule.BUILT_IN.labels(tagged)).containsExactly("PII.Sensitive");
    assertThat(Rule.BUILT_IN.labels(suggested)).isEmpty();
    assertThat(Rule.BUILT_IN.labels(termed)).isEmpty();
    assertThat(rule(true, List.of(term("Customer.Identity")), List.of()).labels(termed))
        .containsExactly("Customer.Identity");
    assertThat(rule(true, List.of(classification("PII")), List.of()).labels(suggested)).isEmpty();
  }

  @Test
  @DisplayName("the message names the table, at most three labels and the purpose, or that none was named")
  void message() {
    String table = "demo-pg.salesdb.sales.customer";

    assertThat(SensitiveData.message(table, List.of("PII.Sensitive"), "Reporting"))
        .isEqualTo(
            table
                + " holds sensitive data (PII.Sensitive), and Reporting is not a purpose sensitive"
                + " data may be used for. Choose one that is, or ask a policy author to allow it"
                + " under Settings, Purposes");
    assertThat(SensitiveData.message(table, List.of("A.x", "B.x", "C.x", "D.x", "E.x"), null))
        .isEqualTo(
            table
                + " holds sensitive data (A.x, B.x, C.x and 2 more), and no purpose was named."
                + " Name one that sensitive data may be used for");
  }

  @Test
  @DisplayName("only enforce refuses; off checks nothing, and a rule kept nowhere cannot be changed")
  void modes() {
    SensitiveData.Concern warn =
        new SensitiveData.Concern(Mode.WARN, "t", List.of("PII.Sensitive"), null, null, "m");
    assertThat(warn.refuses()).isFalse();
    assertThat(new SensitiveData.Concern(Mode.ENFORCE, "t", List.of(), null, null, "m").refuses()).isTrue();

    SensitiveData off = SensitiveData.off();
    assertThat(off.current().mode()).isEqualTo(Mode.OFF);
    assertThat(off.concern("demo-pg.salesdb.sales.customer", null)).isEmpty();
    assertThat(off.history()).isEmpty();
    assertThatThrownBy(
            () -> off.update(Rule.BUILT_IN.settings(), "because", "author_a"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("the built-in rule is what shipped: counting by name, warning")
  void shipped() {
    assertThat(Rule.BUILT_IN.builtIn()).isTrue();
    assertThat(Rule.BUILT_IN.include()).isEmpty();
    assertThat(Rule.BUILT_IN.exclude()).isEmpty();
    assertThat(Rule.BUILT_IN.mode()).isEqualTo(Mode.WARN);
    assertThat(SensitiveData.covers(classification("PII"), "PII.Sensitive", false)).isTrue();
    assertThat(SensitiveData.covers(classification("PII"), "PIIX.Sensitive", false)).isFalse();
    assertThat(SensitiveData.covers(glossary("PII"), "PII.Sensitive", false)).isFalse();
  }
}
