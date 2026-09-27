package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.audit.QueryLog;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.QueryExecutor;
import com.mfec.dac.source.jdbc.SourceProbe;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
 * The Query API's result cache against the real catalogue, audit tables and
 * rewrite (FR-6.3). Only the source read and the decision are stand-ins, so
 * what is under test is the part that has to be right: that a held result is
 * only ever handed back for the enforced statement it was read with, and that
 * the log says which rows the source never saw.
 */
@Testcontainers
class QueryResultCacheIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String FQN = "cache-pg.salesdb.sales.customer";
  private static final String SQL = "SELECT id, branch_code FROM sales.customer";

  private static Jdbi jdbi;

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-09-27T08:00:00Z"));
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  private final DecisionService decisions = mock(DecisionService.class);
  private final QueryExecutor executor = mock(QueryExecutor.class);
  private final List<String> sent = new ArrayList<>();
  private QueryResultCache cache;
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
                          VALUES ('cache-pg', 'POSTGRES', 'db.example.test', 5432, 'salesdb',
                                  'env:SRC_CACHE', 'cache-pg')
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
              PolicyDecision decision =
                  new PolicyDecision()
                      .withPrincipal(ask.principal())
                      .withAssetFqn(ask.assetFqn())
                      .withAllowed(true);
              // Two principals whose decisions differ: one sees every branch,
              // the other only their own.
              if ("analyst_bkk".equals(ask.principal())) {
                decision.withRowPredicates(
                    List.of(
                        new ResolvedRowPredicate()
                            .withKind(ResolvedRowPredicate.Kind.IN_LIST)
                            .withColumn("branch_code")
                            .withValues(List.of("BKK"))));
              }
              if ("outsider".equals(ask.principal())) {
                decision.withAllowed(false);
              }
              return decision;
            });
    when(executor.run(any(SourceProbe.Target.class), anyString(), anyString(), anyInt(), anyInt()))
        .thenAnswer(
            call -> {
              sent.add(call.getArgument(2));
              return new QueryExecutor.Page(
                  List.of("id", "branch_code"),
                  List.of("int4", "text"),
                  List.of(List.of(sent.size(), "BKK")),
                  false,
                  25);
            });

    cache = new QueryResultCache(true, 100, 10_000, Duration.ofSeconds(30));
    service =
        new QueryService(
            jdbi, new ObjectMapper(), new DataSourceStore(jdbi), decisions, executor, cache, clock);
  }

  private List<Boolean> servedFromCache() {
    return jdbi.withHandle(
        h ->
            h.createQuery("SELECT served_from_cache FROM audit_query ORDER BY id")
                .mapTo(Boolean.class)
                .list());
  }

  @Test
  @DisplayName("the same statement again is answered without the source, and the log says so")
  void secondRunIsCached() throws Exception {
    QueryService.Result first = service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null);
    now.set(now.get().plusSeconds(10));
    QueryService.Result second = service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null);

    assertThat(sent).hasSize(1);
    assertThat(first.cached()).isFalse();
    assertThat(second.cached()).isTrue();
    assertThat(second.readAt()).isEqualTo(first.readAt());
    assertThat(second.rows()).isEqualTo(first.rows());
    assertThat(second.rewrittenSql()).isEqualTo(first.rewrittenSql());
    assertThat(servedFromCache()).containsExactly(false, true);
    // The decision is still made and recorded on a hit.
    verify(decisions, times(2)).decide(any());
    int decided =
        jdbi.withHandle(
            h -> h.createQuery("SELECT count(*) FROM audit_decision").mapTo(Integer.class).one());
    assertThat(decided).isEqualTo(2);

    List<QueryLog.Entry> log =
        new QueryLog(jdbi)
            .page(
                new QueryLog.Reader(true, "auditor", List.of()),
                new QueryLog.Filter(null, null, null, null, null, null, null, null),
                10);
    assertThat(log).extracting(QueryLog.Entry::fromCache).containsExactly(true, false);
  }

  @Test
  @DisplayName("asking for a fresh read goes to the source and replaces what was held")
  void freshReadsTheSource() {
    service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null);
    QueryService.Result fresh =
        service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null, true);
    QueryService.Result after = service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null);

    assertThat(sent).hasSize(2);
    assertThat(fresh.cached()).isFalse();
    assertThat(after.cached()).isTrue();
    assertThat(after.rows()).isEqualTo(fresh.rows());
    assertThat(servedFromCache()).containsExactly(false, false, true);
  }

  @Test
  @DisplayName("somebody whose decision differs never gets the rows somebody else read")
  void differentDecisionDifferentRead() {
    service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null);
    QueryService.Result restricted =
        service.run(source, SQL, "analyst_bkk", "analyst_bkk", 50, null, null);

    assertThat(sent).hasSize(2);
    assertThat(restricted.cached()).isFalse();
    assertThat(sent.get(1)).contains("BKK").isNotEqualTo(sent.get(0));
  }

  @Test
  @DisplayName("somebody whose decision is the same is served the same enforced read")
  void sameDecisionSharesTheRead() {
    service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null);
    QueryService.Result other = service.run(source, SQL, "analyst_two", "analyst_two", 50, null, null);

    assertThat(sent).hasSize(1);
    assertThat(other.cached()).isTrue();
  }

  @Test
  @DisplayName("a denial is still a denial after the table was read for somebody else")
  void denialIsNeverServed() {
    service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null);

    assertThatThrownBy(() -> service.run(source, SQL, "outsider", "outsider", 50, null, null))
        .isInstanceOf(QueryService.RejectedException.class);
    assertThat(sent).hasSize(1);
    assertThat(servedFromCache()).containsExactly(false, false);
  }

  @Test
  @DisplayName("a different row cap, an expired entry or a flush each read the source again")
  void boundaries() {
    service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null);
    service.run(source, SQL, "analyst_all", "analyst_all", 60, null, null);
    assertThat(sent).hasSize(2);

    now.set(now.get().plusSeconds(31));
    assertThat(service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null).cached()).isFalse();
    assertThat(sent).hasSize(3);

    cache.invalidateAll("policy edited");
    assertThat(service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null).cached()).isFalse();
    assertThat(sent).hasSize(4);
  }

  @Test
  @DisplayName("editing the source's registration stops its old results being served")
  void sourceEditMissesTheCache() {
    service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null);
    jdbi.useHandle(
        h ->
            h.createUpdate(
                    "UPDATE data_source SET credential_ref = 'env:SRC_CACHE_2', updated_at = now()"
                        + " WHERE id = :id")
                .bind("id", source)
                .execute());

    assertThat(service.run(source, SQL, "analyst_all", "analyst_all", 50, null, null).cached()).isFalse();
    assertThat(sent).hasSize(2);
  }
}
