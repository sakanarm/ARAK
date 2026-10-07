package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.enforcement.NativeReadGate;
import com.mfec.dac.enforcement.NativeRoleStore;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.QueryExecutor;
import com.mfec.dac.source.jdbc.SourceProbe;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The query proxy on a source whose subscription policies are pushed down
 * natively, against the real catalogue, {@code native_role} and audit tables.
 * The decision and the source read are stand-ins; what is under test is that
 * the proxy reads only what the role on the source also gives, refuses before
 * the source is read otherwise, and audits the refusal like any other.
 */
@Testcontainers
class NativeReadGateIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String ORDERS = "native-pg.salesdb.sales.orders";
  private static final String READ_ORDERS = "SELECT id, total FROM sales.orders";
  private static final UUID POLICY = UUID.fromString("5e1f0c2a-0000-4000-8000-000000000021");

  private static Jdbi jdbi;

  private final DecisionService decisions = mock(DecisionService.class);
  private final QueryExecutor executor = mock(QueryExecutor.class);
  private final List<String> sent = new ArrayList<>();
  private QueryService service;
  private UUID source;

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbi.installPlugins();
  }

  @BeforeEach
  void setUp() throws Exception {
    jdbi.useHandle(
        h -> {
          h.execute("DELETE FROM audit_query");
          h.execute("DELETE FROM audit_decision");
          h.execute("DELETE FROM native_role");
          h.execute("DELETE FROM asset_fqn_map");
          h.execute("DELETE FROM asset_column");
          h.execute("DELETE FROM asset");
          h.execute("DELETE FROM data_source");
        });
    source = jdbi.withHandle(NativeReadGateIT::catalog);

    when(decisions.decide(any()))
        .thenAnswer(
            call -> {
              DecisionService.Ask ask = call.getArgument(0);
              boolean allowed = !"outsider".equals(ask.principal());
              return new PolicyDecision()
                  .withPrincipal(ask.principal())
                  .withAssetFqn(ask.assetFqn())
                  .withAllowed(allowed)
                  .withReasons(
                      List.of(
                          new DecisionReason()
                              .withPolicyId(POLICY)
                              .withPolicyName("Order readers")
                              .withEffect(DecisionReason.Effect.ALLOW)
                              .withPolicyType(DecisionReason.PolicyType.SUBSCRIPTION)
                              .withMatched(allowed)));
            });
    when(executor.run(
            any(SourceProbe.Target.class), anyString(), anyString(), anyInt(), anyInt(), anyDouble()))
        .thenAnswer(
            call -> {
              sent.add(call.getArgument(2));
              return new QueryExecutor.Page(
                  List.of("id", "total"), List.of("int4", "numeric"), List.of(List.of(1, 10)), false, 25);
            });

    service =
        new QueryService(
            jdbi,
            new ObjectMapper(),
            new DataSourceStore(jdbi),
            decisions,
            executor,
            null,
            null,
            null,
            Clock.systemUTC(),
            null,
            NativeReadGate.of(new NativeRoleStore(jdbi)));
  }

  private static UUID catalog(Handle h) {
    UUID id =
        h.createQuery(
                """
                INSERT INTO data_source (name, engine, host, port, default_database,
                                         credential_ref, om_service_fqn, default_enforcement_mode)
                VALUES ('native-pg', 'POSTGRES', 'db.example.test', 5432, 'salesdb',
                        'env:SRC_NATIVE', 'native-pg', 'NATIVE_CONFIG')
                RETURNING id
                """)
            .mapTo(UUID.class)
            .one();
    UUID asset =
        h.createQuery(
                "INSERT INTO asset (data_source_id, fqn, asset_type, name)"
                    + " VALUES (:source, :fqn, 'TABLE', 'orders') RETURNING id")
            .bindMap(Map.of("source", id, "fqn", ORDERS))
            .mapTo(UUID.class)
            .one();
    h.createUpdate(
            "INSERT INTO asset_fqn_map (om_fqn, data_source_id, database_name, schema_name,"
                + " object_name, object_kind, verification_status)"
                + " VALUES (:fqn, :source, 'salesdb', 'sales', 'orders', 'TABLE', 'MATCHED')")
        .bind("fqn", ORDERS)
        .bind("source", id)
        .execute();
    int ordinal = 1;
    for (String column : List.of("id", "total")) {
      h.createUpdate(
              "INSERT INTO asset_column (asset_id, fqn, name, ordinal)"
                  + " VALUES (:asset, :fqn, :name, :ordinal)")
          .bind("asset", asset)
          .bind("fqn", ORDERS + "." + column)
          .bind("name", column)
          .bind("ordinal", ordinal++)
          .execute();
    }
    return id;
  }

  private void role(String status, String level, String tables) {
    jdbi.useHandle(
        h ->
            h.createUpdate(
                    """
                    INSERT INTO native_role (policy_id, data_source_id, role_name, database_name,
                                             access_level, status, applied_fingerprint, tables)
                    VALUES (:p, :s, 'arak_sub_it_role', 'salesdb', :level, :status, 'fp',
                            CAST(:tables AS jsonb))
                    """)
                .bind("p", POLICY)
                .bind("s", source)
                .bind("level", level)
                .bind("status", status)
                .bind("tables", tables)
                .execute());
  }

  private void mode(String mode) {
    jdbi.useHandle(
        h ->
            h.createUpdate("UPDATE data_source SET default_enforcement_mode = :m WHERE id = :id")
                .bind("m", mode)
                .bind("id", source)
                .execute());
  }

  private QueryService.Result run(String principal) {
    return service.run(source, READ_ORDERS, principal, principal, 50, null, null);
  }

  private List<String> outcomes() {
    return jdbi.withHandle(
        h -> h.createQuery("SELECT outcome FROM audit_query ORDER BY id").mapTo(String.class).list());
  }

  private String rejectReason() {
    return jdbi.withHandle(
        h ->
            h.createQuery("SELECT reject_reason FROM audit_query ORDER BY id DESC LIMIT 1")
                .mapTo(String.class)
                .one());
  }

  @Test
  @DisplayName("an applied Read role covering the table lets the proxy read it")
  void appliedRoleReads() {
    role("APPLIED", "READ", "[\"sales.orders\"]");

    run("analyst_a");

    assertThat(sent).hasSize(1);
    assertThat(outcomes()).doesNotContain("REJECTED");
  }

  @Test
  @DisplayName("a policy never pushed is refused before the source is read, and audited")
  void neverPushedIsRefused() {
    assertThatThrownBy(() -> run("analyst_a"))
        .isInstanceOf(QueryService.RejectedException.class)
        .hasMessageContaining("Policy Order readers has not been pushed yet");
    assertThat(sent).isEmpty();
    assertThat(outcomes()).containsExactly("REJECTED");
    assertThat(rejectReason()).contains("has not been pushed yet");
  }

  @Test
  @DisplayName("a rolled-back role is refused")
  void rolledBackIsRefused() {
    role("ROLLED_BACK", "READ", "[\"sales.orders\"]");

    assertThatThrownBy(() -> run("analyst_a"))
        .isInstanceOf(QueryService.RejectedException.class)
        .hasMessageContaining("was rolled back on the source");
    assertThat(sent).isEmpty();
    assertThat(outcomes()).containsExactly("REJECTED");
  }

  @Test
  @DisplayName("a role changed by hand is refused")
  void driftedIsRefused() {
    role("DRIFTED", "READ", "[\"sales.orders\"]");

    assertThatThrownBy(() -> run("analyst_a"))
        .isInstanceOf(QueryService.RejectedException.class)
        .hasMessageContaining("was changed by hand on the source");
    assertThat(sent).isEmpty();
  }

  @Test
  @DisplayName("a Browse role does not let the proxy read rows")
  void browseIsRefused() {
    role("APPLIED", "BROWSE", "[\"sales.orders\"]");

    assertThatThrownBy(() -> run("analyst_a"))
        .isInstanceOf(QueryService.RejectedException.class)
        .hasMessageContaining("pushed at Browse only");
    assertThat(sent).isEmpty();
  }

  @Test
  @DisplayName("the same source in proxy mode reads as before, with no role at all")
  void proxyModeReadsAsBefore() {
    mode("PROXY");

    run("analyst_a");

    assertThat(sent).hasSize(1);
  }

  @Test
  @DisplayName("somebody the policy refuses is refused by the policy, not by the gate")
  void policyRefusalComesFirst() {
    role("APPLIED", "READ", "[\"sales.orders\"]");

    assertThatThrownBy(() -> run("outsider"))
        .isInstanceOf(QueryService.RejectedException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("enforces its policies"));
    assertThat(sent).isEmpty();
  }
}
