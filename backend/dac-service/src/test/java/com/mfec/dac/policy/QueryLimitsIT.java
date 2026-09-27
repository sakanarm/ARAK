package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.audit.QueryRefusals;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.QueryExecutor;
import com.mfec.dac.source.jdbc.SourceProbe;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Query API's two guards on the sources against the real catalogue, audit
 * tables and rewrite (FR-6.3): the concurrency limit and the cost guard. Only
 * the source read and the decision are stand-ins; what is under test is that a
 * slot is taken for a read and for nothing else, and that each refusal is
 * logged in a way the refusal breakdown files correctly.
 */
@Testcontainers
class QueryLimitsIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String FQN = "limits-pg.salesdb.sales.customer";
  private static final String SQL = "SELECT id, branch_code FROM sales.customer";

  private static Jdbi jdbi;

  private final DecisionService decisions = mock(DecisionService.class);
  private final QueryExecutor executor = mock(QueryExecutor.class);
  private final List<String> sent = new ArrayList<>();
  private final List<Double> ceilings = new ArrayList<>();
  private QueryAdmission admission;
  private QueryCostGuard costs;
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
          h.execute("DELETE FROM asset_fqn_map");
          h.execute("DELETE FROM asset_column");
          h.execute("DELETE FROM asset");
          h.execute("DELETE FROM data_source");
        });
    source =
        jdbi.withHandle(
            h -> {
              UUID id =
                  h.createQuery(
                          """
                          INSERT INTO data_source (name, engine, host, port, default_database,
                                                   credential_ref, om_service_fqn)
                          VALUES ('limits-pg', 'POSTGRES', 'db.example.test', 5432, 'salesdb',
                                  'env:SRC_LIMITS', 'limits-pg')
                          RETURNING id
                          """)
                      .mapTo(UUID.class)
                      .one();
              UUID asset =
                  h.createQuery(
                          "INSERT INTO asset (data_source_id, fqn, asset_type, name)"
                              + " VALUES (:source, :fqn, 'TABLE', 'customer') RETURNING id")
                      .bindMap(Map.of("source", id, "fqn", FQN))
                      .mapTo(UUID.class)
                      .one();
              h.createUpdate(
                      "INSERT INTO asset_fqn_map (om_fqn, data_source_id, database_name, schema_name,"
                          + " object_name, object_kind, verification_status)"
                          + " VALUES (:fqn, :source, 'salesdb', 'sales', 'customer', 'TABLE', 'MATCHED')")
                  .bind("fqn", FQN)
                  .bind("source", id)
                  .execute();
              int ordinal = 1;
              for (String column : List.of("id", "branch_code")) {
                h.createUpdate(
                        "INSERT INTO asset_column (asset_id, fqn, name, ordinal)"
                            + " VALUES (:asset, :fqn, :name, :ordinal)")
                    .bind("asset", asset)
                    .bind("fqn", FQN + "." + column)
                    .bind("name", column)
                    .bind("ordinal", ordinal++)
                    .execute();
              }
              return id;
            });

    when(decisions.decide(any()))
        .thenAnswer(
            call -> {
              DecisionService.Ask ask = call.getArgument(0);
              return new PolicyDecision()
                  .withPrincipal(ask.principal())
                  .withAssetFqn(ask.assetFqn())
                  .withAllowed(!"outsider".equals(ask.principal()));
            });
    when(executor.run(
            any(SourceProbe.Target.class), anyString(), anyString(), anyInt(), anyInt(), anyDouble()))
        .thenAnswer(
            call -> {
              sent.add(call.getArgument(2));
              ceilings.add(call.getArgument(5));
              return new QueryExecutor.Page(
                  List.of("id", "branch_code"),
                  List.of("int4", "text"),
                  List.of(List.of(sent.size(), "BKK")),
                  false,
                  25,
                  42.5,
                  null);
            });

    // One read at a time per person, and no waiting, so a held slot is felt
    // at once.
    admission = new QueryAdmission(true, 16, 4, 1, Duration.ZERO);
    costs = new QueryCostGuard(true, Map.of("POSTGRES", 1_000d));
    service =
        new QueryService(
            jdbi,
            new ObjectMapper(),
            new DataSourceStore(jdbi),
            decisions,
            executor,
            new QueryResultCache(true, 100, 10_000, Duration.ofSeconds(30)),
            admission,
            costs,
            Clock.systemUTC());
  }

  private record Logged(String outcome, String reason) {}

  private List<Logged> logged() {
    return jdbi.withHandle(
        h ->
            h.createQuery("SELECT outcome, reject_reason FROM audit_query ORDER BY id")
                .map((rs, ctx) -> new Logged(rs.getString(1), rs.getString(2)))
                .list());
  }

  @Test
  @DisplayName("a statement is sent with its engine's ceiling, and the estimate comes back")
  void pricedAndRun() {
    QueryService.Result result =
        service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null);

    assertThat(ceilings).containsExactly(1_000d);
    assertThat(result.estimatedCost()).isEqualTo(42.5);
    assertThat(costs.stats().priced()).isEqualTo(1);
    assertThat(costs.stats().highestAdmitted()).isEqualTo(42.5);
    // The slot was given back.
    assertThat(admission.stats().running()).isZero();
    assertThat(admission.stats().admitted()).isEqualTo(1);
  }

  @Test
  @DisplayName("a statement priced over the ceiling is refused as fixable and logged as too costly")
  void tooCostly() throws Exception {
    when(executor.run(
            any(SourceProbe.Target.class), anyString(), anyString(), anyInt(), anyInt(), anyDouble()))
        .thenThrow(new QueryExecutor.CostExceededException(48_210_555.4, 1_000));

    assertThatThrownBy(
            () -> service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null))
        .isInstanceOfSatisfying(
            QueryService.RejectedException.class,
            e -> {
              assertThat(e.aboutStatement()).isTrue();
              assertThat(e.deniedAsset()).isNull();
              assertThat(e.getMessage())
                  .contains("48,210,555")
                  .contains("1,000")
                  .contains("POSTGRES");
            });

    List<Logged> log = logged();
    assertThat(log).hasSize(1);
    assertThat(log.get(0).outcome()).isEqualTo("REJECTED");
    assertThat(QueryRefusals.categorize(log.get(0).outcome(), log.get(0).reason()))
        .isEqualTo(QueryRefusals.Category.TOO_COSTLY);
    assertThat(costs.stats().refused()).isEqualTo(1);
    assertThat(costs.stats().lastRefusedEstimate()).isEqualTo(48_210_555.4);
    assertThat(admission.stats().running()).isZero();
  }

  @Test
  @DisplayName("with no room for the sender, nothing is sent and the log files it as busy")
  void busy() throws Exception {
    // The same account already has its one read out.
    QueryAdmission.Permit holding = admission.admit(source, "analyst_all");

    assertThatThrownBy(
            () -> service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null))
        .isInstanceOfSatisfying(
            QueryService.BusyException.class,
            e -> {
              assertThat(e.retryAfterSeconds()).isEqualTo(1);
              assertThat(e.getMessage()).startsWith("Too many of your queries are running");
            });
    verify(executor, never())
        .run(
            any(SourceProbe.Target.class), anyString(), anyString(), anyInt(), anyInt(), anyDouble());

    List<Logged> log = logged();
    assertThat(log).hasSize(1);
    assertThat(log.get(0).outcome()).isEqualTo("REJECTED");
    assertThat(QueryRefusals.categorize(log.get(0).outcome(), log.get(0).reason()))
        .isEqualTo(QueryRefusals.Category.BUSY);

    // Somebody else still gets through, and so does the sender once the slot
    // is back.
    service.run(source, SQL, "analyst_two", "analyst_two", 50, null, null, true);
    holding.close();
    service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null, true);
    assertThat(sent).hasSize(2);
  }

  @Test
  @DisplayName("the slot is charged to the account that sent it, not the one it runs as")
  void chargedToSender() {
    QueryAdmission.Permit holding = admission.admit(source, "steward");

    // Run as analyst_all by a steward previewing their view of it.
    assertThatThrownBy(() -> service.run(source, SQL, "analyst_all", "steward", 50, null, null))
        .isInstanceOf(QueryService.BusyException.class);
    holding.close();
  }

  @Test
  @DisplayName("an answer from the result cache takes no slot")
  void cachedTakesNoSlot() {
    service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null);
    QueryAdmission.Permit holding = admission.admit(source, "analyst_all");

    QueryService.Result again =
        service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null);

    assertThat(again.cached()).isTrue();
    assertThat(again.estimatedCost()).isEqualTo(42.5);
    assertThat(sent).hasSize(1);
    holding.close();
  }

  @Test
  @DisplayName("a refusal by policy takes no slot either")
  void deniedTakesNoSlot() {
    QueryAdmission.Permit holding = admission.admit(source, "outsider");

    assertThatThrownBy(() -> service.run(source, SQL, "outsider", "outsider", 50, null, null))
        .isInstanceOf(QueryService.RejectedException.class);
    assertThat(QueryRefusals.categorize("REJECTED", logged().get(0).reason()))
        .isEqualTo(QueryRefusals.Category.POLICY_DENY);
    holding.close();
  }
}
