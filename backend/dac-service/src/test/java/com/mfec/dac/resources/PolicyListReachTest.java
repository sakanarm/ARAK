package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.policy.ImpactAnalysis;
import com.mfec.dac.policy.PolicyBindingMaterializer;
import com.mfec.dac.policy.PolicyOverview;
import com.mfec.dac.policy.PolicyReach;
import com.mfec.dac.policy.PolicyStore;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.source.DataSourceStore;
import io.dropwizard.jackson.Jackson;
import jakarta.ws.rs.BadRequestException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The policy list's connection and mode filters. Neither is a column, so with
 * either set the page is cut from the filtered list; the count must agree with
 * the pages, or the pager shows an empty last page.
 */
@DisplayName("PolicyResource — connection and mode filters")
class PolicyListReachTest {

  private static final ObjectMapper JSON = Jackson.newObjectMapper();

  private static final DataSourceStore.Source PG = source("demo-pg", DataSourceStore.EnforcementMode.PROXY);
  private static final DataSourceStore.Source MSSQL =
      source("demo-mssql", DataSourceStore.EnforcementMode.SECURE_VIEW);

  private final PolicyStore store = mock(PolicyStore.class);
  private final PolicyResource resource =
      new PolicyResource(
          store,
          mock(PolicyBindingMaterializer.class),
          mock(PolicyOverview.class),
          mock(ImpactAnalysis.class),
          null,
          new PolicyReach(() -> List.of(PG, MSSQL)));

  private static DataSourceStore.Source source(String name, DataSourceStore.EnforcementMode mode) {
    return new DataSourceStore.Source(
        UUID.randomUUID(), name, DataSourceStore.Engine.POSTGRES, "16", "db.example.test", 5432,
        "sales", "env:FAKE", mode, null, null, null, null, true, Instant.EPOCH, Instant.EPOCH, 0);
  }

  private static PolicyStore.StoredPolicy stored(String name, String service) throws Exception {
    String selector =
        service == null
            ? "{\"condition\":{\"facet\":\"tags\",\"operator\":\"contains\",\"value\":\"PII\"}}"
            : "{\"condition\":{\"facet\":\"service\",\"operator\":\"eq\",\"value\":\""
                + service + "\"}}";
    Policy document =
        JSON.readValue(
            "{\"name\":\"" + name + "\",\"policyType\":\"SUBSCRIPTION\",\"scopeLevel\":\"ORG\","
                + "\"effect\":\"ALLOW\",\"selector\":" + selector + "}",
            Policy.class);
    return new PolicyStore.StoredPolicy(
        UUID.randomUUID(), document, "DRAFT", "prod", 1, "author_a", "author_a", Instant.EPOCH);
  }

  private List<PolicyStore.StoredPolicy> estate() throws Exception {
    return List.of(
        stored("on-pg-1", "demo-pg"),
        stored("everywhere", null),
        stored("on-mssql", "demo-mssql"),
        stored("on-pg-2", "demo-pg"));
  }

  private static List<String> names(List<PolicyResource.ListedPolicy> page) {
    return page.stream().map(p -> p.policy().document().getName()).toList();
  }

  @Test
  @DisplayName("with no connection or mode, the database pages as before")
  void unfiltered() throws Exception {
    when(store.list(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(estate());

    List<PolicyResource.ListedPolicy> page =
        resource.list(null, null, null, null, null, null, 50, 0);

    assertThat(page).hasSize(4);
    verify(store, never()).listAll(any(), any(), any(), any());
    assertThat(page.get(1).reach().everyConnection()).isTrue();
    assertThat(page.get(0).reach().connections()).singleElement()
        .satisfies(c -> assertThat(c.mode()).isEqualTo("PROXY"));
  }

  @Test
  @DisplayName("one connection keeps the policies written for it, and pages them")
  void byConnection() throws Exception {
    when(store.listAll(any(), any(), any(), any())).thenReturn(estate());
    String pg = PG.id().toString();

    assertThat(names(resource.list(null, null, null, null, pg, null, 50, 0)))
        .containsExactly("on-pg-1", "on-pg-2");
    assertThat(names(resource.list(null, null, null, null, pg, null, 1, 1)))
        .containsExactly("on-pg-2");
    assertThat(resource.count(null, null, null, null, pg, null).total()).isEqualTo(2);
  }

  @Test
  @DisplayName("any keeps the policies written for every connection")
  void everyConnection() throws Exception {
    when(store.listAll(any(), any(), any(), any())).thenReturn(estate());

    assertThat(names(resource.list(null, null, null, null, "any", null, 50, 0)))
        .containsExactly("everywhere");
    assertThat(resource.count(null, null, null, null, "any", null).total()).isEqualTo(1);
  }

  @Test
  @DisplayName("a mode keeps the policies on a connection it enforces today")
  void byMode() throws Exception {
    when(store.listAll(any(), any(), any(), any())).thenReturn(estate());

    assertThat(names(resource.list(null, null, null, null, null, "SECURE_VIEW", 50, 0)))
        .containsExactly("on-mssql");
    assertThat(names(resource.list(null, null, null, null, PG.id().toString(), "SECURE_VIEW", 50, 0)))
        .isEmpty();
    assertThat(resource.count(null, null, null, null, null, "NATIVE_CONFIG").total())
        .isZero();
  }

  @Test
  @DisplayName("a connection or mode that is not one is refused, not ignored")
  void refused() {
    assertThatThrownBy(() -> resource.list(null, null, null, null, "demo-pg", null, 50, 0))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> resource.count(null, null, null, null, null, "FAST"))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  @DisplayName("the list keeps its shape: the policy's fields, plus where it applies")
  void shape() throws Exception {
    when(store.list(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(estate());

    JsonNode first =
        JSON.valueToTree(resource.list(null, null, null, null, null, null, 50, 0)).get(0);

    assertThat(first.has("id")).isTrue();
    assertThat(first.path("document").path("name").asText()).isEqualTo("on-pg-1");
    assertThat(first.has("policy")).isFalse();
    assertThat(first.path("reach").path("connections").get(0).path("name").asText())
        .isEqualTo("demo-pg");
    assertThat(first.path("reach").path("connections").get(0).has("host")).isFalse();
  }
}
