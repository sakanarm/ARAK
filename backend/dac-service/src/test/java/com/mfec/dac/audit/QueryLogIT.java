package com.mfec.dac.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.audit.QueryLog.Entry;
import com.mfec.dac.audit.QueryLog.Filter;
import com.mfec.dac.audit.QueryLog.Reader;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.policy.QueryService;
import com.mfec.dac.resources.AuditResource;
import com.mfec.dac.source.DataSourceStore;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The query log against real rows (FR-8.3, M10): who reads which rows, that a
 * search cannot read SQL the reader may not, that pages meet without gaps, and
 * that V25 places the refusals written before it.
 */
@Testcontainers
class QueryLogIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String CUSTOMER = "prod-pg.SalesDB.dbo.customer";
  private static final String ORDERS = "prod-pg.SalesDB.dbo.orders";
  private static final String SALARY = "prod-pg.HrDB.dbo.salary";
  private static final String NEIGHBOUR = "prod-pg.SalesDBArchive.dbo.customer";

  private static Jdbi jdbi;
  private static long legacyDenied;
  private static long legacyUnparseable;

  private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
  private QueryLog log;
  private UUID source;

  @BeforeAll
  static void migrate() {
    // Up to V24 first, with two refusals written the way they were before V25,
    // so the backfill is tested on rows that really lacked the column.
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("24"))
        .load()
        .migrate();
    jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbi.installPlugins();
    legacyDenied =
        jdbi.withHandle(
            h ->
                h.createQuery(
                        """
                        INSERT INTO audit_query (principal_name, original_sql, outcome, reject_reason, client_ip)
                        VALUES ('analyst_b', 'SELECT * FROM dbo.customer', 'REJECTED',
                                'Access to prod-pg.SalesDB.dbo.customer is denied. finance-subscription: subject rule not satisfied',
                                CAST('192.0.2.10' AS inet))
                        RETURNING id
                        """)
                    .mapTo(Long.class)
                    .one());
    legacyUnparseable =
        jdbi.withHandle(
            h ->
                h.createQuery(
                        """
                        INSERT INTO audit_query (principal_name, original_sql, outcome, reject_reason)
                        VALUES ('analyst_b', 'SELEC nonsense', 'REJECTED',
                                'This statement could not be parsed, so it cannot be enforced and will not be run')
                        RETURNING id
                        """)
                    .mapTo(Long.class)
                    .one());
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  @BeforeEach
  void clean() {
    jdbi.useHandle(
        h -> {
          h.execute(
              "DELETE FROM audit_query WHERE id NOT IN (" + legacyDenied + ", " + legacyUnparseable + ")");
          h.execute("DELETE FROM data_source");
        });
    source =
        jdbi.withHandle(
            h ->
                h.createQuery(
                        """
                        INSERT INTO data_source (name, engine, host, port, credential_ref, om_service_fqn)
                        VALUES ('prod-pg', 'POSTGRES', 'db.example.test', 5432, 'vault://x', 'prod-pg')
                        RETURNING id
                        """)
                    .mapTo(UUID.class)
                    .one());
    log = new QueryLog(jdbi);
  }

  private long row(
      String principal,
      String runBy,
      String outcome,
      String reason,
      String sql,
      Instant at,
      String... assets) {
    return jdbi.withHandle(
        h ->
            h.createQuery(
                    """
                    INSERT INTO audit_query (occurred_at, principal_name, run_by, data_source_id, original_sql,
                                             rewritten_sql, outcome, reject_reason, row_count, duration_ms,
                                             client_ip, asset_fqns)
                    VALUES (:at, :principal, :runBy, :source, :sql, :rewritten, :outcome, :reason,
                            :rows, 25, CAST('192.0.2.44' AS inet), :assets)
                    RETURNING id
                    """)
                .bind("at", java.sql.Timestamp.from(at))
                .bind("principal", principal)
                .bind("runBy", runBy)
                .bind("source", source)
                .bind("sql", sql)
                .bind("rewritten", "EXECUTED".equals(outcome) ? "/* rewritten */ " + sql : null)
                .bind("outcome", outcome)
                .bind("reason", reason)
                .bind("rows", "EXECUTED".equals(outcome) ? 3L : null)
                .bindArray("assets", String.class, assets)
                .mapTo(Long.class)
                .one());
  }

  private static Filter all() {
    return new Filter(null, null, null, null, null, null, null, null);
  }

  private static Filter search(String text) {
    return new Filter(null, null, text, null, null, null, null, null);
  }

  private static List<Long> ids(List<Entry> entries) {
    return entries.stream().map(Entry::id).toList();
  }

  private static final Reader AUDITOR = new Reader(true, "compliance_a", List.of());
  private static final Reader ANALYST = new Reader(false, "analyst_a", List.of());
  private static final Reader OWNER = new Reader(false, "owner_a", List.of("prod-pg.SalesDB"));

  @Test
  @DisplayName("V25 places a policy refusal written before it on its table, and nothing else")
  void backfill() {
    Entry denied = log.page(AUDITOR, all(), 200).stream().filter(e -> e.id() == legacyDenied).findFirst().orElseThrow();
    Entry unparseable =
        log.page(AUDITOR, all(), 200).stream().filter(e -> e.id() == legacyUnparseable).findFirst().orElseThrow();
    assertThat(denied.assets()).containsExactly(CUSTOMER);
    assertThat(unparseable.assets()).isEmpty();
    assertThat(denied.runBy()).isNull();
    assertThat(ids(log.page(OWNER, all(), 200)))
        .as("the owner of the refused table now sees who was turned away")
        .contains(legacyDenied)
        .doesNotContain(legacyUnparseable);
  }

  @Test
  @DisplayName("each reader reads the rows that are theirs to read")
  void visibility() {
    long mine = row("analyst_a", null, "EXECUTED", null, "SELECT * FROM dbo.orders", now, ORDERS);
    long asMe = row("analyst_a", "admin", "EXECUTED", null, "SELECT id FROM dbo.salary", now, SALARY);
    long byMe = row("owner_a", "analyst_a", "EXECUTED", null, "SELECT 1", now);
    long onOwned = row("analyst_b", null, "EXECUTED", null, "SELECT * FROM dbo.customer", now, CUSTOMER);
    long mixed = row("analyst_b", null, "EXECUTED", null, "SELECT * FROM dbo.customer JOIN hr", now, CUSTOMER, SALARY);
    long elsewhere = row("analyst_b", null, "EXECUTED", null, "SELECT * FROM dbo.salary", now, SALARY);
    long neighbour = row("analyst_b", null, "EXECUTED", null, "SELECT * FROM archive", now, NEIGHBOUR);

    assertThat(ids(log.page(AUDITOR, all(), 200)))
        .contains(mine, asMe, byMe, onOwned, mixed, elsewhere, neighbour);
    assertThat(ids(log.page(ANALYST, all(), 200))).containsExactlyInAnyOrder(mine, asMe, byMe);
    assertThat(ids(log.page(OWNER, all(), 200)))
        .as("own rows, rows on SalesDB, and not SalesDBArchive, which only shares a prefix")
        .containsExactlyInAnyOrder(mine, byMe, onOwned, mixed, legacyDenied);
    assertThat(ids(log.page(new Reader(false, "ANALYST_A", List.of()), all(), 200)))
        .as("usernames compare without case, as sign-in does")
        .containsExactlyInAnyOrder(mine, asMe, byMe);
  }

  @Test
  @DisplayName("a search matches only SQL the reader may read, so it cannot be used to read the rest")
  void searchIsNotAnOracle() {
    long onOwned = row("analyst_b", null, "EXECUTED", null, "SELECT secret_token FROM dbo.customer", now, CUSTOMER);
    long mixed =
        row("analyst_b", null, "EXECUTED", null, "SELECT c.id, s.secret_token FROM dbo.customer c JOIN hr.salary s", now, CUSTOMER, SALARY);
    long refused =
        row("analyst_b", null, "REJECTED", "Access to " + CUSTOMER + " is denied by policy", "SELECT secret_token FROM dbo.customer", now, CUSTOMER);

    assertThat(ids(log.page(OWNER, search("secret_token"), 200))).containsExactly(onOwned);
    assertThat(ids(log.page(OWNER, all(), 200))).contains(onOwned, mixed, refused);
    assertThat(log.counts(OWNER, search("secret_token")).total()).isEqualTo(1);
    assertThat(ids(log.page(AUDITOR, search("SECRET_token"), 200)))
        .as("case-insensitive for those who read it all")
        .containsExactlyInAnyOrder(onOwned, mixed, refused);
  }

  @Test
  @DisplayName("a search for % or _ looks for that character, not for anything")
  void searchIsLiteral() {
    long percent = row("analyst_a", null, "EXECUTED", null, "SELECT '100%' FROM dbo.orders", now, ORDERS);
    row("analyst_a", null, "EXECUTED", null, "SELECT 1 FROM dbo.orders", now, ORDERS);
    assertThat(ids(log.page(ANALYST, search("%"), 200))).containsExactly(percent);
    assertThat(ids(log.page(ANALYST, search("_"), 200))).isEmpty();
  }

  @Test
  @DisplayName("filters narrow by outcome, person, table, source and time")
  void filters() {
    long ran = row("analyst_a", null, "EXECUTED", null, "SELECT 1", now, ORDERS, CUSTOMER);
    long refused = row("analyst_b", null, "REJECTED", "Access to " + CUSTOMER + " is denied by policy", "SELECT 2", now, CUSTOMER);
    long old = row("analyst_a", null, "FAILED", "ERROR: boom", "SELECT 3", now.minus(40, ChronoUnit.DAYS), ORDERS);

    assertThat(ids(log.page(AUDITOR, new Filter("REJECTED", null, null, null, null, null, null, null), 200)))
        .contains(refused)
        .doesNotContain(ran, old);
    assertThat(ids(log.page(AUDITOR, new Filter(null, "ANALYST_A", null, null, null, null, null, null), 200)))
        .containsExactlyInAnyOrder(ran, old);
    assertThat(ids(log.page(AUDITOR, new Filter(null, null, null, ORDERS, null, null, null, null), 200)))
        .containsExactlyInAnyOrder(ran, old);
    assertThat(ids(log.page(AUDITOR, new Filter(null, null, null, null, source, null, null, null), 200)))
        .containsExactlyInAnyOrder(ran, refused, old);
    assertThat(
            ids(log.page(AUDITOR, new Filter(null, null, null, null, null, now.minus(30, ChronoUnit.DAYS), null, null), 200)))
        .contains(ran, refused)
        .doesNotContain(old);
    assertThat(
            ids(log.page(AUDITOR, new Filter(null, null, null, null, null, null, now.minus(30, ChronoUnit.DAYS), null), 200)))
        .contains(old)
        .doesNotContain(ran, refused);
  }

  @Test
  @DisplayName("pages meet without a gap or a repeat, newest first, and name the source")
  void paging() {
    long[] made = new long[7];
    for (int i = 0; i < made.length; i++) {
      made[i] = row("analyst_a", null, "EXECUTED", null, "SELECT " + i, now.minusSeconds(i), ORDERS);
    }
    List<Entry> first = log.page(ANALYST, all(), 3);
    List<Entry> second = log.page(ANALYST, withBefore(first.get(2).id()), 3);
    List<Entry> third = log.page(ANALYST, withBefore(second.get(2).id()), 3);
    assertThat(ids(first)).containsExactly(made[6], made[5], made[4]);
    assertThat(ids(second)).containsExactly(made[3], made[2], made[1]);
    assertThat(ids(third)).containsExactly(made[0]);
    assertThat(first.get(0).sourceName()).isEqualTo("prod-pg");
    assertThat(first.get(0).rowCount()).isEqualTo(3L);
    assertThat(first.get(0).durationMs()).isEqualTo(25);
  }

  private static Filter withBefore(long id) {
    return new Filter(null, null, null, null, null, null, null, id);
  }

  @Test
  @DisplayName("counts, days and reasons cover the reader's rows only")
  void aggregates() {
    row("analyst_a", null, "EXECUTED", null, "SELECT 1", now, ORDERS);
    row("analyst_a", null, "REJECTED", "Access to " + ORDERS + " is denied by policy", "SELECT 2", now, ORDERS);
    row("analyst_a", null, "REJECTED", "Access to " + ORDERS + " is denied by policy", "SELECT 3", now, ORDERS);
    row("analyst_b", null, "FAILED", "ERROR: boom", "SELECT 4", now, SALARY);

    assertThat(log.counts(ANALYST, all())).isEqualTo(new QueryLog.Counts(3, 1, 2, 0));
    QueryLog.Counts everything = log.counts(AUDITOR, all());
    assertThat(everything.failed()).isEqualTo(1);
    assertThat(everything.total()).isEqualTo(everything.executed() + everything.rejected() + everything.failed());

    Map<String, int[]> days = log.perDay(ANALYST, all());
    assertThat(days).hasSize(1);
    assertThat(days.values().iterator().next()).containsExactly(1, 2, 0);

    List<QueryLog.ReasonCount> reasons = log.reasons(ANALYST, all(), 10);
    assertThat(reasons).containsExactly(
        new QueryLog.ReasonCount("REJECTED", "Access to " + ORDERS + " is denied by policy", 2));
  }

  @Test
  @DisplayName("no column the log reads is the address the query came from")
  void neverReadsTheClientAddress() {
    row("analyst_a", null, "EXECUTED", null, "SELECT 1", now, ORDERS);
    AuditResource resource = new AuditResource(log);
    AuditResource.QueryPage page =
        resource.queries(null, null, null, null, null, 30, null, null, null, 50, security(AUDITOR_USER));
    String body;
    try {
      body = new ObjectMapper().findAndRegisterModules().writeValueAsString(page);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    assertThat(body).contains("SELECT 1").doesNotContain("192.0.2").doesNotContain("client");
  }

  @Test
  @DisplayName("the resource pages with a cursor and counts the window on the first page only")
  void resourcePaging() {
    for (int i = 0; i < 5; i++) {
      row("analyst_a", null, "EXECUTED", null, "SELECT " + i, now.minusSeconds(i), ORDERS);
    }
    AuditResource resource = new AuditResource(log);
    AuditResource.QueryPage first =
        resource.queries(null, null, null, null, null, 30, null, null, null, 2, security(ANALYST_USER));
    assertThat(first.rows()).hasSize(2);
    assertThat(first.scope()).isEqualTo("OWN");
    assertThat(first.counts().total()).isEqualTo(5);
    assertThat(first.nextBefore()).isEqualTo(first.rows().get(1).id());
    AuditResource.QueryPage last =
        resource.queries(null, null, null, null, null, 30, null, null, 3L + first.nextBefore(), 50, security(ANALYST_USER));
    assertThat(last.counts()).isNull();
    AuditResource.QueryPage rest =
        resource.queries(null, null, null, null, null, 30, null, null, first.nextBefore(), 50, security(ANALYST_USER));
    assertThat(rest.rows()).hasSize(3);
    assertThat(rest.nextBefore()).isNull();
  }

  @Test
  @DisplayName("a query run as somebody else is written down with who ran it")
  void queryServiceRecordsWhoRanIt() {
    QueryService service = new QueryService(jdbi, new ObjectMapper(), new DataSourceStore(jdbi), null, null);
    UUID missing = UUID.randomUUID();
    assertThatThrownBy(() -> service.run(missing, "SELECT 1", "analyst_a", "admin", 10, "192.0.2.10", null))
        .isInstanceOf(QueryService.RejectedException.class);
    assertThatThrownBy(() -> service.run(missing, "SELECT 2", "analyst_a", "Analyst_A", 10, null, null))
        .isInstanceOf(QueryService.RejectedException.class);
    List<Entry> rows = log.page(ANALYST, all(), 10);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).runBy()).isEqualTo("admin");
    assertThat(rows.get(0).runBy()).as("the same person under another case is not somebody else").isNull();
    assertThat(rows.get(1).assets()).isEmpty();
    assertThat(rows.get(1).rejectReason()).isEqualTo("No data source " + missing);
  }

  private static final AuthenticatedUser AUDITOR_USER =
      new AuthenticatedUser(
          UUID.randomUUID(), "compliance_a", "c@example.test", "C", "local", Set.of("AUDITOR"), List.of());
  private static final AuthenticatedUser ANALYST_USER =
      new AuthenticatedUser(
          UUID.randomUUID(), "analyst_a", "a@example.test", "A", "local", Set.of("REQUESTER"), List.of());

  private static SecurityContext security(Principal principal) {
    return new SecurityContext() {
      @Override
      public Principal getUserPrincipal() {
        return principal;
      }

      @Override
      public boolean isUserInRole(String role) {
        return false;
      }

      @Override
      public boolean isSecure() {
        return true;
      }

      @Override
      public String getAuthenticationScheme() {
        return "Bearer";
      }
    };
  }
}
