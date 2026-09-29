package com.mfec.dac.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask.ScopeLevel;
import com.mfec.dac.schema.api.ResolvedLookup;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.api.ResolvedRowPredicate.FacetOperator;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.DataPolicy;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import com.mfec.dac.schema.entity.policy.LookupKey;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.RowFilter;
import com.mfec.dac.schema.entity.policy.RowLookup;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A row filter whose allowed values live in a mapping table: a person in
 * department AA sees the divisions the mapping gives AA, and nothing else.
 *
 * <p>The engine cannot read the mapping; it resolves everything it can without
 * a connection and leaves the rest to the query proxy. Every case here that
 * cannot be resolved must come out as no rows, never as no filter.
 */
class LookupRowFilterTest {

  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
  private static final RequestContext NOW =
      RequestContext.at(LocalDateTime.parse("2026-09-15T09:00").atZone(BANGKOK).toInstant());
  private static final PolicyEngine ENGINE =
      new PolicyEngine(EngineConfig.defaults().withZone(BANGKOK));

  private static final String TABLE = "warehouse.sales.public.results";
  private static final String MAPPING = "warehouse.sales.ref.department_division";

  private static Principal inDepartment(String... departments) {
    Principal.Builder builder = Principal.withId("reader_a").roles("analyst");
    for (String department : departments) {
      builder.attribute("department", department);
    }
    return builder.attribute("region", "North").build();
  }

  private static AssetContext results() {
    return AssetContext.of(TABLE)
        .physicalFromFqn()
        .column(ColumnContext.named("division").dataType("VARCHAR").facet(FacetType.TAGS, "Org", "Org.Division"))
        .column(ColumnContext.named("value").dataType("INTEGER"))
        .build();
  }

  private static Policy policy(String name, Policy.PolicyType type) {
    return new Policy()
        .withId(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)))
        .withName(name)
        .withVersion(1)
        .withPolicyType(type)
        .withScopeLevel(ScopeLevel.ORG)
        .withEffect(Policy.Effect.ALLOW)
        .withSelector(
            new AssetSelector()
                .withCondition(
                    new FacetCondition()
                        .withFacet(FacetType.TABLE)
                        .withOperator(FacetOperator.EQ)
                        .withValue("results")));
  }

  private static RowLookup divisionsByDepartment() {
    return new RowLookup()
        .withTable(MAPPING)
        .withKeys(
            new ArrayList<>(
                List.of(new LookupKey().withColumn("department").withUserAttribute("department"))))
        .withValueColumn("division");
  }

  private static PolicyDecision decide(Principal principal, RowFilter... filters) {
    List<Policy> policies = new ArrayList<>();
    policies.add(policy("door", Policy.PolicyType.SUBSCRIPTION));
    policies.add(
        policy("lookup", Policy.PolicyType.DATA)
            .withData(new DataPolicy().withRowFilters(List.of(filters))));
    return ENGINE.evaluate(principal, results(), NOW, policies);
  }

  private static RowFilter lookupOn(String column, RowLookup lookup) {
    return new RowFilter().withKind(RowFilter.Kind.LOOKUP).withColumn(column).withLookup(lookup);
  }

  private static String reasons(PolicyDecision decision) {
    StringBuilder out = new StringBuilder();
    decision.getReasons().forEach(r -> out.append(r.getExplanation()).append('\n'));
    return out.toString();
  }

  @Test
  @DisplayName("a lookup keeps the mapping, the key and the person's own values, joined by default")
  void resolvesTheLookup() {
    PolicyDecision decision = decide(inDepartment("AA"), lookupOn("division", divisionsByDepartment()));

    assertThat(decision.getAllowed()).isTrue();
    assertThat(decision.getRowPredicates()).hasSize(1);
    ResolvedRowPredicate predicate = decision.getRowPredicates().get(0);
    assertThat(predicate.getKind()).isEqualTo(ResolvedRowPredicate.Kind.LOOKUP);
    assertThat(predicate.getColumn()).isEqualTo("division");
    assertThat(predicate.getOperator()).isEqualTo(FacetOperator.IN);
    assertThat(predicate.getValues()).isNullOrEmpty();
    ResolvedLookup lookup = predicate.getLookup();
    assertThat(lookup.getTable()).isEqualTo(MAPPING);
    assertThat(lookup.getValueColumn()).isEqualTo("division");
    assertThat(lookup.getMode()).isEqualTo(ResolvedLookup.Mode.SUBQUERY);
    assertThat(lookup.getSchemaName()).isNull();
    assertThat(lookup.getTableName()).isNull();
    assertThat(lookup.getKeys()).hasSize(1);
    assertThat(lookup.getKeys().get(0).getColumn()).isEqualTo("department");
    assertThat(lookup.getKeys().get(0).getUserAttribute()).isEqualTo("department");
    assertThat(lookup.getKeys().get(0).getValues()).containsExactly("AA");
  }

  @Test
  @DisplayName("every value of a multi-valued attribute is looked up, and READ_VALUES is carried")
  void readValuesWithSeveralValues() {
    PolicyDecision decision =
        decide(
            inDepartment("AA", "AB"),
            lookupOn("division", divisionsByDepartment().withMode(RowLookup.Mode.READ_VALUES)));

    ResolvedLookup lookup = decision.getRowPredicates().get(0).getLookup();
    assertThat(lookup.getMode()).isEqualTo(ResolvedLookup.Mode.READ_VALUES);
    assertThat(lookup.getKeys().get(0).getValues()).containsExactly("AA", "AB");
  }

  @Test
  @DisplayName("keys are ANDed: each is resolved from its own attribute")
  void severalKeys() {
    RowLookup lookup = divisionsByDepartment();
    lookup.getKeys().add(new LookupKey().withColumn("region").withUserAttribute("region"));

    PolicyDecision decision = decide(inDepartment("AA"), lookupOn("division", lookup));

    ResolvedLookup resolved = decision.getRowPredicates().get(0).getLookup();
    assertThat(resolved.getKeys()).hasSize(2);
    assertThat(resolved.getKeys().get(1).getColumn()).isEqualTo("region");
    assertThat(resolved.getKeys().get(1).getValues()).containsExactly("North");
  }

  @Test
  @DisplayName("a person without the key's attribute sees no rows, and is told why")
  void missingAttributeIsNoRows() {
    PolicyDecision decision = decide(inDepartment(), lookupOn("division", divisionsByDepartment()));

    assertThat(decision.getRowPredicates()).hasSize(1);
    assertThat(decision.getRowPredicates().get(0).getKind())
        .isEqualTo(ResolvedRowPredicate.Kind.ALWAYS_FALSE);
    assertThat(reasons(decision)).contains("department").contains("no rows match");
  }

  @Test
  @DisplayName("a lookup that is not finished is no rows, never no filter")
  void incompleteLookupIsNoRows() {
    List<RowLookup> broken =
        List.of(
            divisionsByDepartment().withTable(null),
            divisionsByDepartment().withTable("sales.ref.department_division"),
            divisionsByDepartment().withValueColumn(" "),
            divisionsByDepartment().withKeys(new ArrayList<>()),
            divisionsByDepartment()
                .withKeys(List.of(new LookupKey().withColumn("department").withUserAttribute(""))));

    for (RowLookup lookup : broken) {
      PolicyDecision decision = decide(inDepartment("AA"), lookupOn("division", lookup));
      assertThat(decision.getRowPredicates())
          .extracting(ResolvedRowPredicate::getKind)
          .containsExactly(ResolvedRowPredicate.Kind.ALWAYS_FALSE);
    }
    PolicyDecision none =
        decide(inDepartment("AA"), new RowFilter().withKind(RowFilter.Kind.LOOKUP).withColumn("division"));
    assertThat(none.getRowPredicates())
        .extracting(ResolvedRowPredicate::getKind)
        .containsExactly(ResolvedRowPredicate.Kind.ALWAYS_FALSE);
  }

  @Test
  @DisplayName("the filtered column can be picked by tag, and each gets a lookup of its own")
  void columnByTag() {
    AssetContext twoDivisions =
        AssetContext.of(TABLE)
            .physicalFromFqn()
            .column(ColumnContext.named("division").dataType("VARCHAR").facet(FacetType.TAGS, "Org", "Org.Division"))
            .column(ColumnContext.named("owner_division").dataType("VARCHAR").facet(FacetType.TAGS, "Org", "Org.Division"))
            .column(ColumnContext.named("value").dataType("INTEGER"))
            .build();
    RowFilter tagged =
        new RowFilter()
            .withKind(RowFilter.Kind.LOOKUP)
            .withColumns(
                new AssetSelector()
                    .withCondition(
                        new FacetCondition()
                            .withFacet(FacetType.TAGS)
                            .withOperator(FacetOperator.CONTAINS)
                            .withValue("Org.Division")))
            .withLookup(divisionsByDepartment());
    List<Policy> policies =
        List.of(
            policy("door", Policy.PolicyType.SUBSCRIPTION),
            policy("lookup", Policy.PolicyType.DATA)
                .withData(new DataPolicy().withRowFilters(List.of(tagged))));

    PolicyDecision decision = ENGINE.evaluate(inDepartment("AA"), twoDivisions, NOW, policies);

    assertThat(decision.getRowPredicates())
        .extracting(ResolvedRowPredicate::getColumn)
        .containsExactlyInAnyOrder("division", "owner_division");
    ResolvedLookup first = decision.getRowPredicates().get(0).getLookup();
    ResolvedLookup second = decision.getRowPredicates().get(1).getLookup();
    // Bound in place by the proxy, so sharing one would bind both to the same answer.
    assertThat(first).isNotSameAs(second);
    assertThat(first.getKeys()).isNotSameAs(second.getKeys());
    assertThat(first.getKeys().get(0)).isNotSameAs(second.getKeys().get(0));
  }

  @Test
  @DisplayName("two lookups that differ only in their mapping stay in the same order every time")
  void orderIsStable() {
    RowFilter one = lookupOn("division", divisionsByDepartment());
    RowFilter two = lookupOn("division", divisionsByDepartment().withTable("warehouse.sales.ref.other_map"));

    List<String> first = tables(decide(inDepartment("AA"), one, two));
    for (int i = 0; i < 5; i++) {
      assertThat(tables(decide(inDepartment("AA"), two, one))).isEqualTo(first);
    }
    assertThat(first).hasSize(2);
  }

  private static List<String> tables(PolicyDecision decision) {
    List<String> out = new ArrayList<>();
    decision.getRowPredicates().forEach(p -> out.add(p.getLookup().getTable()));
    return out;
  }
}
