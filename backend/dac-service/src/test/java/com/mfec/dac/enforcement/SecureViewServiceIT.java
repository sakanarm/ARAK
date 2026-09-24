package com.mfec.dac.enforcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.CredentialResolver;
import com.mfec.dac.source.jdbc.SecureViewApplier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * Review, apply and roll back a secure view, through the service the endpoint
 * calls, against a real database.
 *
 * <p>One container plays both parts: its {@code public} schema is the
 * platform's own database, migrated by Flyway, and its {@code sales} schema is
 * the customer's. That is not how it is deployed, but nothing here reads one
 * through the other, and it keeps the test to one container.
 *
 * <p>The population is a stub so that each test can say who is allowed without
 * writing a policy. Everything after it -- the live column read, the compiler,
 * the maintainer, the applier, the state row, the audit trail -- is the real
 * thing.
 */
@Testcontainers
class SecureViewServiceIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String FQN = "it_pg.salesdb.sales.customer";

  private static final MaskingSpec REDACT_LOCAL_PART =
      new MaskingSpec()
          .withFunction(MaskingSpec.MaskingFunction.REGEX_REPLACE)
          .withRegex("^[^@]+")
          .withReplacement("***");

  private static Jdbi jdbi;

  private DataSourceStore sources;
  private EnforcementStateStore states;
  private ReviewedPlans reviews;
  private DataSourceStore.Source source;
  /** Which credential the source uses; switched by the test for a read-only login. */
  private final AtomicReference<String> sourceLogin = new AtomicReference<>();
  private final AtomicReference<List<PolicyDecision>> population = new AtomicReference<>();
  private SecureViewService service;

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
  void seed() throws SQLException {
    run(
        "DROP SCHEMA IF EXISTS sales CASCADE",
        "DROP SCHEMA IF EXISTS sec CASCADE",
        "DROP SCHEMA IF EXISTS acl CASCADE",
        "CREATE SCHEMA sales",
        "CREATE TABLE sales.customer (id int PRIMARY KEY, email text, branch_code text,"
            + " salary numeric)",
        "INSERT INTO sales.customer VALUES (1, 'anan@example.com', 'BKK-01', 50000),"
            + " (2, 'bee@example.com', 'CNX-01', 60000)");
    run("DROP OWNED BY analyst_a CASCADE", "DROP ROLE IF EXISTS analyst_a");
    run("CREATE ROLE analyst_a LOGIN PASSWORD 'it-only-analyst_a'");

    jdbi.useHandle(
        handle -> {
          handle.execute("TRUNCATE data_source CASCADE");
          handle.execute("TRUNCATE audit_enforcement");
        });

    sources = new DataSourceStore(jdbi);
    states = new EnforcementStateStore(jdbi);
    reviews = new ReviewedPlans();
    sourceLogin.set(POSTGRES.getUsername() + ":" + POSTGRES.getPassword());
    population.set(List.of(allowedWithMaskedEmail("analyst_a"), denied("analyst_b")));

    source =
        sources.create(
            new DataSourceStore.SourceInput(
                "it_pg", "POSTGRES", null, POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                POSTGRES.getDatabaseName(), "env:IT_SOURCE_LOGIN", "SECURE_VIEW", "it_pg",
                "sec", "{table}", true));
    catalogue(source.id(), "id", "email", "branch_code");

    CredentialResolver credentials =
        new CredentialResolver(name -> "IT_SOURCE_LOGIN".equals(name) ? sourceLogin.get() : null);
    service =
        new SecureViewService(
            jdbi, sources, credentials, new SecureViewApplier(), fqn -> population.get(),
            new AppDbEntitlementSource(jdbi), states, reviews);
  }

  // ------------------------------------------------------------------ list

  @Test
  @DisplayName("a catalogued table on an enabled source is a candidate, not yet enforced")
  void listsCandidates() {
    List<SecureViewService.Candidate> candidates = service.candidates(null, 50);

    assertThat(candidates).hasSize(1);
    SecureViewService.Candidate only = candidates.get(0);
    assertThat(only.assetFqn()).isEqualTo(FQN);
    assertThat(only.status()).isEqualTo("NOT_ENFORCED");
    assertThat(only.secureObject()).isEqualTo("sec.customer");
    assertThat(service.candidates("no-such", 50)).isEmpty();
    // An underscore is a literal here, not LIKE's one-character wildcard.
    assertThat(service.candidates("it_pg", 50)).hasSize(1);
    assertThat(service.candidates("itXpg", 50)).isEmpty();
  }

  // --------------------------------------------------------------- dry run

  @Test
  @DisplayName("a dry run writes nothing, reads the live columns, and says which are uncatalogued")
  void dryRunWritesNothing() throws SQLException {
    SecureViewService.Preview preview = service.dryRun(FQN, "admin", "192.0.2.10");

    assertThat(preview.reviewId()).isNotNull();
    assertThat(preview.secureObject()).isEqualTo("sec.customer");
    assertThat(preview.liveColumns()).containsExactly("id", "email", "branch_code", "salary");
    assertThat(preview.uncataloguedColumns()).containsExactly("salary");
    assertThat(preview.warnings()).anyMatch(w -> w.contains("salary"));
    assertThat(preview.warnings()).anyMatch(w -> w.contains("cutover"));
    assertThat(preview.principals()).isEqualTo(2);
    assertThat(preview.allowed()).isEqualTo(1);
    assertThat(preview.dryRun().applyScript()).contains("CREATE OR REPLACE VIEW");
    assertThat(preview.dryRun().rows().insert().subscriptions()).hasSize(1);

    assertThat(count("SELECT count(*) FROM information_schema.views WHERE table_schema = 'sec'"))
        .isZero();
    assertThat(service.state(FQN)).isEmpty();
    assertThat(service.history(FQN, 10))
        .extracting(EnforcementStateStore.AuditEntry::action, EnforcementStateStore.AuditEntry::outcome)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("DRY_RUN", "REVIEWED"));
  }

  // ------------------------------------------------------------------ apply

  @Test
  @DisplayName("applying the reviewed plan installs the view, and the reader sees what the policy says")
  void applyInstallsTheReviewedView() throws SQLException {
    SecureViewService.Preview preview = service.dryRun(FQN, "admin", null);

    SecureViewService.Outcome outcome = service.apply(FQN, preview.reviewId(), "admin", null);

    assertThat(outcome.state().status()).isEqualTo("APPLIED");
    assertThat(outcome.state().secureSchema()).isEqualTo("sec");
    assertThat(outcome.state().secureView()).isEqualTo("customer");
    assertThat(outcome.state().lastAppliedBy()).isEqualTo("admin");
    assertThat(outcome.inserted()).isPositive();

    grantReadOn("analyst_a");
    assertThat(rowsAs("analyst_a", "SELECT id, email FROM sec.customer ORDER BY id"))
        .containsExactly("1|***@example.com", "2|***@example.com");

    assertThat(service.candidates(null, 10).get(0).status()).isEqualTo("APPLIED");
    assertThat(service.history(FQN, 10).get(0).outcome()).isEqualTo("APPLIED");
  }

  @Test
  @DisplayName("a review can be spent once; the second apply is refused and audited as stale")
  void aReviewIsSingleUse() {
    SecureViewService.Preview preview = service.dryRun(FQN, "admin", null);
    service.apply(FQN, preview.reviewId(), "admin", null);

    assertThatThrownBy(() -> service.apply(FQN, preview.reviewId(), "admin", null))
        .isInstanceOf(SecureViewService.ConflictException.class);
    assertThat(service.history(FQN, 10).get(0).outcome()).isEqualTo("STALE");
  }

  @Test
  @DisplayName("an id nobody issued is refused")
  void anUnknownReviewIsRefused() {
    assertThatThrownBy(() -> service.apply(FQN, UUID.randomUUID(), "admin", null))
        .isInstanceOf(SecureViewService.ConflictException.class);
  }

  @Test
  @DisplayName("a column added after the review makes the review stale, and nothing is applied")
  void aNewColumnInvalidatesTheReview() throws SQLException {
    SecureViewService.Preview preview = service.dryRun(FQN, "admin", null);
    run("ALTER TABLE sales.customer ADD COLUMN phone text");

    assertThatThrownBy(() -> service.apply(FQN, preview.reviewId(), "admin", null))
        .isInstanceOf(SecureViewService.ConflictException.class)
        .hasMessageContaining("no longer the one that was reviewed");
    assertThat(count("SELECT count(*) FROM information_schema.views WHERE table_schema = 'sec'"))
        .isZero();
    assertThat(service.state(FQN)).isEmpty();
  }

  @Test
  @DisplayName("a policy change after the review makes it stale too")
  void aChangedDecisionInvalidatesTheReview() {
    SecureViewService.Preview preview = service.dryRun(FQN, "admin", null);
    population.set(List.of(allowedWithMaskedEmail("analyst_a"), allowedWithMaskedEmail("analyst_b")));

    assertThatThrownBy(() -> service.apply(FQN, preview.reviewId(), "admin", null))
        .isInstanceOf(SecureViewService.ConflictException.class);
  }

  @Test
  @DisplayName("a login that may not create objects fails the apply, which is recorded as FAILED")
  void aReadOnlyLoginFailsTheApply() throws SQLException {
    run("DROP OWNED BY it_reader CASCADE", "DROP ROLE IF EXISTS it_reader");
    run(
        "CREATE ROLE it_reader LOGIN PASSWORD 'it-only-reader'",
        "REVOKE CREATE ON DATABASE " + POSTGRES.getDatabaseName() + " FROM PUBLIC",
        "GRANT USAGE ON SCHEMA sales TO it_reader",
        "GRANT SELECT ON sales.customer TO it_reader");
    sourceLogin.set("it_reader:it-only-reader");
    try {
      SecureViewService.Preview preview = service.dryRun(FQN, "admin", null);

      assertThatThrownBy(() -> service.apply(FQN, preview.reviewId(), "admin", null))
          .isInstanceOf(SecureViewService.SourceFailureException.class);
      assertThat(service.state(FQN)).get().extracting(EnforcementStateStore.State::status)
          .isEqualTo("FAILED");
      assertThat(service.history(FQN, 10).get(0).outcome()).isEqualTo("FAILED");
    } finally {
      run("GRANT CREATE ON DATABASE " + POSTGRES.getDatabaseName() + " TO PUBLIC");
    }
  }

  // --------------------------------------------------------------- rollback

  @Test
  @DisplayName("rollback drops the view it applied and nothing else")
  void rollbackDropsTheView() throws SQLException {
    SecureViewService.Preview preview = service.dryRun(FQN, "admin", null);
    service.apply(FQN, preview.reviewId(), "admin", null);

    SecureViewService.Outcome outcome = service.rollback(FQN, "admin", "192.0.2.10");

    assertThat(outcome.state().status()).isEqualTo("NOT_ENFORCED");
    assertThat(outcome.state().secureView()).isNull();
    // The scripts are kept for whoever reads what was applied last.
    assertThat(outcome.state().appliedDdl()).contains("CREATE OR REPLACE VIEW");
    assertThat(count("SELECT count(*) FROM information_schema.views WHERE table_schema = 'sec'"))
        .isZero();
    assertThat(count("SELECT count(*) FROM sales.customer")).isEqualTo(2);
    assertThat(service.history(FQN, 10).get(0).outcome()).isEqualTo("ROLLED_BACK");

    assertThatThrownBy(() -> service.rollback(FQN, "admin", null))
        .isInstanceOf(SecureViewService.ConflictException.class);
  }

  @Test
  @DisplayName("rollback drops the view by the name it was applied under, not today's pattern")
  void rollbackUsesTheStoredName() throws SQLException {
    SecureViewService.Preview preview = service.dryRun(FQN, "admin", null);
    service.apply(FQN, preview.reviewId(), "admin", null);
    jdbi.useHandle(
        handle ->
            handle.execute(
                "UPDATE data_source SET secure_object_pattern = '{table}_secure' WHERE id = ?",
                source.id()));

    service.rollback(FQN, "admin", null);

    assertThat(count("SELECT count(*) FROM information_schema.views WHERE table_schema = 'sec'"))
        .isZero();
  }

  // --------------------------------------------------------------- refusals

  @Test
  @DisplayName("a table the catalogue cannot place is not found, and the attempt is audited")
  void unknownTableIsNotFound() {
    assertThatThrownBy(() -> service.dryRun("it_pg.salesdb.sales.nothing", "admin", null))
        .isInstanceOf(SecureViewService.NotFoundException.class);
    assertThat(service.history("it_pg.salesdb.sales.nothing", 10).get(0).outcome())
        .isEqualTo("REFUSED");
  }

  @Test
  @DisplayName("a disabled source is refused")
  void disabledSourceIsRefused() {
    jdbi.useHandle(
        handle -> handle.execute("UPDATE data_source SET enabled = false WHERE id = ?", source.id()));

    assertThatThrownBy(() -> service.dryRun(FQN, "admin", null))
        .isInstanceOf(SecureViewService.RefusedException.class);
  }

  @Test
  @DisplayName("an empty population is refused rather than revoking everybody")
  void emptyPopulationIsRefused() {
    population.set(List.of());

    assertThatThrownBy(() -> service.dryRun(FQN, "admin", null))
        .isInstanceOf(SecureViewService.RefusedException.class);
  }

  @Test
  @DisplayName("a table dropped at the source since the crawl is refused, not built blind")
  void droppedTableIsRefused() throws SQLException {
    run("DROP TABLE sales.customer");

    assertThatThrownBy(() -> service.dryRun(FQN, "admin", null))
        .isInstanceOf(SecureViewService.RefusedException.class)
        .hasMessageContaining("Re-sync");
  }

  @Test
  @DisplayName("an unresolvable credential is refused with the resolver's reason")
  void unresolvableCredentialIsRefused() {
    sourceLogin.set(null);

    assertThatThrownBy(() -> service.dryRun(FQN, "admin", null))
        .isInstanceOf(SecureViewService.RefusedException.class);
  }

  // ---------------------------------------------------------------- helpers

  private static PolicyDecision allowedWithMaskedEmail(String principal) {
    return new PolicyDecision()
        .withPrincipal(principal)
        .withAssetFqn(FQN)
        .withAllowed(true)
        .withColumnMasks(
            List.of(new ResolvedColumnMask().withColumn("email").withMasking(REDACT_LOCAL_PART)));
  }

  private static PolicyDecision denied(String principal) {
    return new PolicyDecision().withPrincipal(principal).withAssetFqn(FQN).withAllowed(false);
  }

  /** What a crawl would have written: the asset, its mapping, and some of its columns. */
  private static void catalogue(UUID sourceId, String... columns) {
    jdbi.useHandle(
        handle -> {
          UUID assetId =
              handle
                  .createQuery(
                      "INSERT INTO asset (data_source_id, fqn, asset_type, name)"
                          + " VALUES (:source, :fqn, 'TABLE', 'customer') RETURNING id")
                  .bindMap(Map.of("source", sourceId, "fqn", FQN))
                  .mapTo(UUID.class)
                  .one();
          handle
              .createUpdate(
                  "INSERT INTO asset_fqn_map (om_fqn, data_source_id, database_name, schema_name,"
                      + " object_name, object_kind, verification_status)"
                      + " VALUES (:fqn, :source, :db, 'sales', 'customer', 'TABLE', 'MATCHED')")
              .bind("fqn", FQN)
              .bind("source", sourceId)
              .bind("db", POSTGRES.getDatabaseName())
              .execute();
          int ordinal = 1;
          for (String column : columns) {
            handle
                .createUpdate(
                    "INSERT INTO asset_column (asset_id, fqn, name, ordinal)"
                        + " VALUES (:asset, :fqn, :name, :ordinal)")
                .bind("asset", assetId)
                .bind("fqn", FQN + "." + column)
                .bind("name", column)
                .bind("ordinal", ordinal++)
                .execute();
          }
        });
  }

  private static void run(String... statements) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        try {
          statement.execute(sql);
        } catch (SQLException failure) {
          if (!sql.startsWith("DROP")) {
            throw failure;
          }
        }
      }
    }
  }

  /** Cutover is not automated yet, so the test does it by hand. */
  private static void grantReadOn(String login) throws SQLException {
    run(
        "GRANT USAGE ON SCHEMA sec TO " + login,
        "GRANT USAGE ON SCHEMA acl TO " + login,
        "GRANT SELECT ON sec.customer TO " + login,
        "GRANT SELECT ON acl.asset_subscription TO " + login,
        "GRANT SELECT ON acl.row_entitlement TO " + login,
        "GRANT SELECT ON acl.column_grant TO " + login);
  }

  private static List<String> rowsAs(String login, String sql) throws SQLException {
    String url =
        "jdbc:postgresql://" + POSTGRES.getHost() + ":"
            + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/"
            + POSTGRES.getDatabaseName();
    List<String> out = new ArrayList<>();
    try (Connection connection = DriverManager.getConnection(url, login, "it-only-" + login);
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      int columns = rows.getMetaData().getColumnCount();
      while (rows.next()) {
        StringBuilder line = new StringBuilder();
        for (int i = 1; i <= columns; i++) {
          if (i > 1) {
            line.append('|');
          }
          line.append(rows.getString(i));
        }
        out.add(line.toString());
      }
    }
    return out;
  }

  private static long count(String sql) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
