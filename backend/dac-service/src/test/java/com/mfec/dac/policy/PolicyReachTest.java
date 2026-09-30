package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.source.DataSourceStore;
import io.dropwizard.jackson.Jackson;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which connection a policy is for. The rule under test is the one the list
 * shows its readers: a connection is named only when the policy cannot reach
 * past it, and everything else reads as every connection.
 */
@DisplayName("PolicyReach")
class PolicyReachTest {

  private static final ObjectMapper JSON = Jackson.newObjectMapper();

  private static final DataSourceStore.Source PG =
      source("demo-pg", null, DataSourceStore.Engine.POSTGRES, DataSourceStore.EnforcementMode.PROXY);
  private static final DataSourceStore.Source LINKED =
      source(
          "mssql-conn",
          "Sales Service",
          DataSourceStore.Engine.SQLSERVER,
          DataSourceStore.EnforcementMode.SECURE_VIEW);

  private final PolicyReach reach = new PolicyReach(() -> List.of(PG, LINKED));

  private static DataSourceStore.Source source(
      String name, String omService, DataSourceStore.Engine engine,
      DataSourceStore.EnforcementMode mode) {
    return new DataSourceStore.Source(
        UUID.randomUUID(), name, engine, "16", "db.example.test", 5432, "sales", "env:FAKE",
        mode, omService, null, null, null, true, Instant.EPOCH, Instant.EPOCH, 0);
  }

  private static Policy policy(String level, String anchor, String selector) throws Exception {
    return JSON.readValue(
        "{\"name\":\"p\",\"policyType\":\"SUBSCRIPTION\",\"scopeLevel\":\"" + level + "\","
            + (anchor == null ? "" : "\"scopeFqn\":\"" + anchor + "\",")
            + "\"effect\":\"ALLOW\""
            + (selector == null ? "" : ",\"selector\":" + selector)
            + "}",
        Policy.class);
  }

  private static String service(String operator, String value) {
    return "{\"condition\":{\"facet\":\"service\",\"operator\":\"" + operator
        + "\",\"value\":\"" + value + "\"}}";
  }

  private static final String TAGGED =
      "{\"condition\":{\"facet\":\"tags\",\"operator\":\"contains\",\"value\":\"PII\"}}";

  private PolicyReach.Reach of(Policy policy) {
    return reach.of(policy, reach.sources());
  }

  @Nested
  @DisplayName("names one connection")
  class Confined {

    @Test
    @DisplayName("the service condition the new-policy page writes")
    void serviceEq() throws Exception {
      PolicyReach.Reach where = of(policy("ORG", null, service("eq", "demo-pg")));

      assertThat(where.everyConnection()).isFalse();
      assertThat(where.connections())
          .singleElement()
          .satisfies(
              c -> {
                assertThat(c.sourceId()).isEqualTo(PG.id());
                assertThat(c.name()).isEqualTo("demo-pg");
                assertThat(c.engine()).isEqualTo("POSTGRES");
                assertThat(c.mode()).isEqualTo("PROXY");
              });
    }

    @Test
    @DisplayName("a source linked to OpenMetadata is found by its service name")
    void linkedService() throws Exception {
      PolicyReach.Reach where = of(policy("ORG", null, service("eq", "sales service")));

      assertThat(where.connections()).singleElement()
          .satisfies(c -> {
            assertThat(c.sourceId()).isEqualTo(LINKED.id());
            assertThat(c.mode()).isEqualTo("SECURE_VIEW");
          });
    }

    @Test
    @DisplayName("an anchor at the service layer or below")
    void anchor() throws Exception {
      assertThat(of(policy("SCHEMA", "demo-pg.sales.public", TAGGED)).connections())
          .extracting(PolicyReach.Connection::sourceId)
          .containsExactly(PG.id());
      assertThat(of(policy("SERVICE", "demo-pg", TAGGED)).connections()).hasSize(1);
    }

    @Test
    @DisplayName("a service condition inside an and, beside other conditions")
    void insideAnd() throws Exception {
      String selector = "{\"and\":[" + TAGGED + "," + service("eq", "demo-pg") + "]}";

      assertThat(of(policy("ORG", null, selector)).connections()).hasSize(1);
    }

    @Test
    @DisplayName("a qualified database or schema name")
    void qualifiedPhysical() throws Exception {
      String schema =
          "{\"condition\":{\"facet\":\"schema\",\"operator\":\"eq\","
              + "\"value\":\"demo-pg.sales.public\"}}";

      assertThat(of(policy("ORG", null, schema)).connections())
          .extracting(PolicyReach.Connection::name)
          .containsExactly("demo-pg");
    }

    @Test
    @DisplayName("is one of two services names both")
    void inList() throws Exception {
      String selector =
          "{\"condition\":{\"facet\":\"service\",\"operator\":\"in\","
              + "\"values\":[\"demo-pg\",\"Sales Service\"]}}";

      assertThat(of(policy("ORG", null, selector)).connections())
          .extracting(PolicyReach.Connection::sourceId)
          .containsExactly(PG.id(), LINKED.id());
    }

    @Test
    @DisplayName("a service no source is registered for is still named, with no mode")
    void unregistered() throws Exception {
      PolicyReach.Reach where = of(policy("ORG", null, service("eq", "om-only")));

      assertThat(where.everyConnection()).isFalse();
      assertThat(where.connections()).singleElement()
          .satisfies(c -> {
            assertThat(c.sourceId()).isNull();
            assertThat(c.name()).isEqualTo("om-only");
            assertThat(c.mode()).isNull();
          });
    }
  }

  @Nested
  @DisplayName("reads as every connection")
  class Every {

    @Test
    @DisplayName("a selector that says nothing about where")
    void noService() throws Exception {
      assertThat(of(policy("ORG", null, TAGGED)).everyConnection()).isTrue();
      assertThat(of(policy("DOMAIN", "Finance", TAGGED)).everyConnection()).isTrue();
    }

    @Test
    @DisplayName("an or with one branch open")
    void openOr() throws Exception {
      String selector = "{\"or\":[" + service("eq", "demo-pg") + "," + TAGGED + "]}";

      assertThat(of(policy("ORG", null, selector)).everyConnection()).isTrue();
    }

    @Test
    @DisplayName("a not, which leaves every other service")
    void not() throws Exception {
      String selector = "{\"not\":" + service("eq", "demo-pg") + "}";

      assertThat(of(policy("ORG", null, selector)).everyConnection()).isTrue();
    }

    @Test
    @DisplayName("a bare schema name, which every service may have")
    void bareLeaf() throws Exception {
      String selector =
          "{\"condition\":{\"facet\":\"schema\",\"operator\":\"eq\",\"value\":\"dbo\"}}";

      assertThat(of(policy("ORG", null, selector)).everyConnection()).isTrue();
    }

    @Test
    @DisplayName("is not, which reaches everything else")
    void notEqual() throws Exception {
      assertThat(of(policy("ORG", null, service("ne", "demo-pg"))).everyConnection()).isTrue();
    }
  }

  @Test
  @DisplayName("an anchor on one service and a selector on another names neither")
  void contradiction() throws Exception {
    PolicyReach.Reach where =
        of(policy("SERVICE", "demo-pg", service("eq", "Sales Service")));

    assertThat(where.everyConnection()).isFalse();
    assertThat(where.connections()).isEmpty();
  }
}
