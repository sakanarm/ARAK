package com.mfec.dac.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.source.TableScope.Match;
import com.mfec.dac.source.TableScope.Mode;
import com.mfec.dac.source.TableScope.Rule;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TableScope — which tables an import reads")
class TableScopeTest {

  private static Rule rule(Match match, String value) {
    return new Rule(match, value);
  }

  @Test
  void everythingReadsEveryTable() {
    assertThat(TableScope.EVERYTHING.includes("sales", "customer")).isTrue();
    assertThat(TableScope.EVERYTHING.scansEverything()).isTrue();
    assertThat(TableScope.normalise(null)).isEqualTo(TableScope.EVERYTHING);
  }

  @Test
  void anExclusionLeavesOutWhatItNamesAndIgnoresCase() {
    TableScope scope =
        TableScope.normalise(
            new TableScope(
                Mode.ALL,
                List.of(),
                List.of(rule(Match.STARTS_WITH, "TMP_"), rule(Match.ENDS_WITH, "_backup"))));

    assertThat(scope.includes("sales", "tmp_orders")).isFalse();
    assertThat(scope.includes("sales", "customer_BACKUP")).isFalse();
    assertThat(scope.includes("sales", "customer")).isTrue();
    assertThat(scope.scansEverything()).isFalse();
  }

  @Test
  void onlyReadsTheTablesItNamesLessTheExclusions() {
    TableScope scope =
        TableScope.normalise(
            new TableScope(
                Mode.ONLY,
                List.of(rule(Match.EQUALS, "customer"), rule(Match.CONTAINS, "order")),
                List.of(rule(Match.ENDS_WITH, "_old"))));

    assertThat(scope.includes("sales", "customer")).isTrue();
    assertThat(scope.includes("sales", "orders")).isTrue();
    assertThat(scope.includes("sales", "orders_old")).isFalse();
    assertThat(scope.includes("sales", "invoice")).isFalse();
  }

  @Test
  void aRuleWithADotIsComparedWithTheSchemaAndTable() {
    TableScope scope =
        TableScope.normalise(
            new TableScope(Mode.ALL, List.of(), List.of(rule(Match.STARTS_WITH, "staging."))));

    assertThat(scope.includes("staging", "customer")).isFalse();
    assertThat(scope.includes("sales", "customer")).isTrue();
    // Without the dot the same text is a table name, so a schema is not caught.
    TableScope byName =
        TableScope.normalise(
            new TableScope(Mode.ALL, List.of(), List.of(rule(Match.STARTS_WITH, "staging"))));
    assertThat(byName.includes("staging", "customer")).isTrue();
    assertThat(byName.includes("sales", "staging_customer")).isFalse();
  }

  @Test
  void onlyWithNothingNamedIsRefused() {
    assertThatThrownBy(
            () -> TableScope.normalise(new TableScope(Mode.ONLY, List.of(), List.of())))
        .isInstanceOf(TableScope.InvalidScopeException.class)
        .hasMessageContaining("at least one table");
  }

  @Test
  void includeRulesAreDroppedWhenEveryTableIsScanned() {
    TableScope scope =
        TableScope.normalise(
            new TableScope(Mode.ALL, List.of(rule(Match.EQUALS, "customer")), List.of()));
    assertThat(scope.include()).isEmpty();
    assertThat(scope.scansEverything()).isTrue();
  }

  @Test
  void valuesAreTrimmedAndRepeatsRemoved() {
    TableScope scope =
        TableScope.normalise(
            new TableScope(
                null,
                null,
                List.of(
                    rule(Match.STARTS_WITH, "  tmp_ "),
                    rule(Match.STARTS_WITH, "TMP_"),
                    rule(Match.ENDS_WITH, "tmp_"))));

    assertThat(scope.mode()).isEqualTo(Mode.ALL);
    assertThat(scope.exclude())
        .containsExactly(rule(Match.STARTS_WITH, "tmp_"), rule(Match.ENDS_WITH, "tmp_"));
  }

  @Test
  void aBadRuleIsRefusedWithASentence() {
    assertThatThrownBy(() -> exclude(rule(Match.STARTS_WITH, "   ")))
        .hasMessageContaining("needs some text");
    assertThatThrownBy(() -> exclude(rule(null, "tmp_"))).hasMessageContaining("comparison");
    assertThatThrownBy(() -> exclude(rule(Match.CONTAINS, "a\nb")))
        .hasMessageContaining("control characters");
    assertThatThrownBy(() -> exclude(rule(Match.CONTAINS, "x".repeat(TableScope.MAX_VALUE + 1))))
        .hasMessageContaining("at most");

    List<Rule> many = new ArrayList<>();
    for (int i = 0; i <= TableScope.MAX_RULES; i++) {
      many.add(rule(Match.EQUALS, "t" + i));
    }
    assertThatThrownBy(
            () -> TableScope.normalise(new TableScope(Mode.ALL, List.of(), many)))
        .hasMessageContaining("rules or fewer");
  }

  @Test
  void itRoundTripsAsTheJsonTheFormSends() throws Exception {
    ObjectMapper json = new ObjectMapper();
    TableScope scope =
        json.readValue(
            "{\"mode\":\"ONLY\",\"include\":[{\"match\":\"STARTS_WITH\",\"value\":\"dim_\"}],"
                + "\"exclude\":[{\"match\":\"ENDS_WITH\",\"value\":\"_tmp\"}]}",
            TableScope.class);

    assertThat(scope.includes("dw", "dim_customer")).isTrue();
    assertThat(scope.includes("dw", "dim_customer_tmp")).isFalse();
    String written = json.writeValueAsString(scope);
    // Only the three fields: a derived one would come back and be refused.
    assertThat(json.readTree(written).fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder("mode", "include", "exclude");
    assertThat(json.readValue(written, TableScope.class)).isEqualTo(scope);
  }

  private static TableScope exclude(Rule rule) {
    List<Rule> rules = new ArrayList<>();
    rules.add(rule);
    return TableScope.normalise(new TableScope(Mode.ALL, List.of(), rules));
  }
}
