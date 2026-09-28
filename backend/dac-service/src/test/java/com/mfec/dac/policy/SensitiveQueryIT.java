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
import com.mfec.dac.purpose.PurposeStore;
import com.mfec.dac.purpose.SensitiveData;
import com.mfec.dac.purpose.SensitiveData.Mode;
import com.mfec.dac.purpose.SensitiveData.Settings;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.QueryExecutor;
import com.mfec.dac.source.jdbc.SourceProbe;
import io.dropwizard.jackson.Jackson;
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
 * A purpose named on a query that reads sensitive data (FR-21, M31b), against
 * the real catalogue, rule and audit tables. The decision and the source read
 * are stand-ins; what is under test is that warn lets the query run and says
 * so, enforce turns it away naming the purpose, and the decision row keeps
 * what was true either way.
 */
@Testcontainers
class SensitiveQueryIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String CUSTOMER = "purpose-pg.salesdb.sales.customer";
  private static final String NOTES = "purpose-pg.salesdb.sales.notes";
  private static final String READ_CUSTOMER = "SELECT id, email FROM sales.customer";
  private static final String READ_NOTES = "SELECT id, body FROM sales.notes";

  private static Jdbi jdbi;

  private final DecisionService decisions = mock(DecisionService.class);
  private final QueryExecutor executor = mock(QueryExecutor.class);
  private final List<String> sent = new ArrayList<>();
  private SensitiveData sensitive;
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
          h.execute(
              "UPDATE sensitive_data_rule SET built_in = true, include = '[]', exclude = '[]',"
                  + " mode = 'WARN', updated_by = 'system'");
          h.execute("DELETE FROM audit_sensitive_data_rule WHERE actor <> 'system'");
          h.execute("DELETE FROM purpose WHERE created_by <> 'system'");
        });
    source = jdbi.withHandle(SensitiveQueryIT::catalog);

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
              return new QueryExecutor.Page(
                  List.of("id", "value"), List.of("int4", "text"), List.of(List.of(1, "x")), false, 25);
            });

    sensitive = new SensitiveData(jdbi, Jackson.newObjectMapper());
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
            sensitive);
  }

  /** customer: email confirmed PII.Sensitive; notes: nothing sensitive. */
  private static UUID catalog(Handle h) {
    UUID id =
        h.createQuery(
                """
                INSERT INTO data_source (name, engine, host, port, default_database,
                                         credential_ref, om_service_fqn)
                VALUES ('purpose-pg', 'POSTGRES', 'db.example.test', 5432, 'salesdb',
                        'env:SRC_PURPOSE', 'purpose-pg')
                RETURNING id
                """)
            .mapTo(UUID.class)
            .one();
    UUID customer = table(h, id, CUSTOMER, "customer", List.of("id", "email"));
    UUID email =
        h.createQuery("SELECT id FROM asset_column WHERE asset_id = :a AND name = 'email'")
            .bind("a", customer)
            .mapTo(UUID.class)
            .one();
    for (String tag : List.of("PII", "PII.Sensitive")) {
      h.createUpdate(
              "INSERT INTO asset_facet (column_id, target_fqn, facet_type, facet_fqn, om_state)"
                  + " VALUES (:c, :t, 'tags', :f, 'Confirmed')")
          .bind("c", email)
          .bind("t", CUSTOMER + ".email")
          .bind("f", tag)
          .execute();
    }
    table(h, id, NOTES, "notes", List.of("id", "body"));
    return id;
  }

  private static UUID table(Handle h, UUID source, String fqn, String name, List<String> columns) {
    UUID asset =
        h.createQuery(
                "INSERT INTO asset (data_source_id, fqn, asset_type, name)"
                    + " VALUES (:source, :fqn, 'TABLE', :name) RETURNING id")
            .bindMap(Map.of("source", source, "fqn", fqn, "name", name))
            .mapTo(UUID.class)
            .one();
    h.createUpdate(
            "INSERT INTO asset_fqn_map (om_fqn, data_source_id, database_name, schema_name,"
                + " object_name, object_kind, verification_status)"
                + " VALUES (:fqn, :source, 'salesdb', 'sales', :name, 'TABLE', 'MATCHED')")
        .bind("fqn", fqn)
        .bind("source", source)
        .bind("name", name)
        .execute();
    int ordinal = 1;
    for (String column : columns) {
      h.createUpdate(
              "INSERT INTO asset_column (asset_id, fqn, name, ordinal)"
                  + " VALUES (:asset, :fqn, :name, :ordinal)")
          .bind("asset", asset)
          .bind("fqn", fqn + "." + column)
          .bind("name", column)
          .bind("ordinal", ordinal++)
          .execute();
    }
    return asset;
  }

  private void mode(Mode mode) {
    sensitive.update(new Settings(true, List.of(), List.of(), mode), "Test " + mode, "author_a");
  }

  private QueryService.Result run(String sql, String principal, String purpose) {
    return service.run(source, sql, principal, principal, 50, null, purpose);
  }

  /** What the last decision row kept: whether the table was sensitive, and the purpose check. */
  private List<Object> decided() {
    return jdbi.withHandle(
        h ->
            h.createQuery(
                    "SELECT sensitive, purpose_check FROM audit_decision ORDER BY id DESC LIMIT 1")
                .map((rs, ctx) -> java.util.Arrays.asList(rs.getObject(1), rs.getObject(2)))
                .one());
  }

  private List<String> outcomes() {
    return jdbi.withHandle(
        h -> h.createQuery("SELECT outcome FROM audit_query ORDER BY id").mapTo(String.class).list());
  }

  @Test
  @DisplayName("warn runs the query, returns one warning naming the purpose, and marks the decision")
  void warns() {
    QueryService.Result result = run(READ_CUSTOMER, "analyst_a", "reporting");

    assertThat(sent).hasSize(1);
    assertThat(result.warnings()).hasSize(1);
    SensitiveData.Concern concern = result.warnings().get(0);
    assertThat(concern.mode()).isEqualTo(Mode.WARN);
    assertThat(concern.table()).isEqualTo(CUSTOMER);
    assertThat(concern.labels()).containsExactly("PII.Sensitive");
    assertThat(concern.purpose()).isEqualTo("reporting");
    assertThat(concern.message())
        .startsWith(CUSTOMER + " holds sensitive data (PII.Sensitive)")
        .contains("Reporting is not a purpose sensitive data may be used for");
    assertThat(decided()).containsExactly(true, "WARNED");
    assertThat(outcomes()).doesNotContain("REJECTED");
  }

  @Test
  @DisplayName("warn with no purpose at all says none was named")
  void warnsWithoutAPurpose() {
    QueryService.Result result = run(READ_CUSTOMER, "analyst_a", null);

    assertThat(result.warnings()).singleElement().satisfies(c -> {
      assertThat(c.purpose()).isNull();
      assertThat(c.message()).contains("no purpose was named");
    });
    assertThat(decided()).containsExactly(true, "WARNED");
  }

  @Test
  @DisplayName("enforce turns the query away before the source is read, names the purpose, and offers no request")
  void enforces() {
    mode(Mode.ENFORCE);

    assertThatThrownBy(() -> run(READ_CUSTOMER, "analyst_a", "reporting"))
        .isInstanceOf(QueryService.RejectedException.class)
        .hasMessageContaining(CUSTOMER)
        .hasMessageContaining("Reporting is not a purpose sensitive data may be used for")
        .satisfies(e -> assertThat(((QueryService.RejectedException) e).deniedAsset()).isNull());
    assertThat(sent).isEmpty();
    assertThat(decided()).containsExactly(true, "REFUSED");
    assertThat(outcomes()).containsExactly("REJECTED");
    String reason =
        jdbi.withHandle(
            h -> h.createQuery("SELECT reject_reason FROM audit_query").mapTo(String.class).one());
    assertThat(reason).contains("is not a purpose sensitive data may be used for");
  }

  @Test
  @DisplayName("a purpose that allows sensitive data runs quietly, even under enforce, and the row still says sensitive")
  void allowedPurpose() {
    new PurposeStore(jdbi)
        .create(
            "fraud-review",
            new PurposeStore.Details("Fraud review", null, null, true, null, null),
            "author_a");
    mode(Mode.ENFORCE);

    QueryService.Result result = run(READ_CUSTOMER, "analyst_a", "fraud-review");

    assertThat(sent).hasSize(1);
    assertThat(result.warnings()).isEmpty();
    assertThat(decided()).containsExactly(true, null);
  }

  @Test
  @DisplayName("a table with nothing sensitive on it is neither warned of nor refused")
  void notSensitive() {
    mode(Mode.ENFORCE);

    QueryService.Result result = run(READ_NOTES, "analyst_a", "reporting");

    assertThat(result.warnings()).isEmpty();
    assertThat(decided()).containsExactly(false, null);
  }

  @Test
  @DisplayName("off checks nothing and records nothing about sensitivity")
  void off() {
    mode(Mode.OFF);

    QueryService.Result result = run(READ_CUSTOMER, "analyst_a", "reporting");

    assertThat(sent).hasSize(1);
    assertThat(result.warnings()).isEmpty();
    assertThat(decided()).containsExactly(null, null);
  }

  @Test
  @DisplayName("somebody refused by policy is refused by policy; the purpose is not asked about")
  void deniedByPolicyFirst() {
    mode(Mode.ENFORCE);

    assertThatThrownBy(() -> run(READ_CUSTOMER, "outsider", "reporting"))
        .isInstanceOf(QueryService.RejectedException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("is not a purpose"));
    assertThat(decided()).containsExactly(null, null);
  }
}
