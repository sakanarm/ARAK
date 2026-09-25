package com.mfec.dac.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.audit.QueryLog;
import com.mfec.dac.audit.QueryRefusals;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.resources.DashboardResource;
import com.mfec.dac.resources.DashboardResource.Attention;
import com.mfec.dac.resources.DashboardResource.Dashboard;
import com.mfec.dac.resources.DashboardResource.Severity;
import io.dropwizard.jackson.Jackson;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The dashboard against real rows (M10, FR-8.5): what counts as sensitive, what
 * counts as protected, which grants are ending, open-ended or idle, and that
 * none of it carries a client address.
 */
@Testcontainers
class DashboardIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String CUSTOMER = "prod-pg.SalesDB.dbo.customer";
  private static final String ORDERS = "prod-pg.SalesDB.dbo.orders";
  private static final String ARCHIVE = "prod-pg.SalesDB.dbo.archive";
  private static final String SALARY = "prod-pg.HrDB.dbo.salary";
  private static final String NOTES = "prod-pg.SalesDB.dbo.notes";

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

  private static Jdbi jdbi;
  private static DashboardResource resource;

  @BeforeAll
  static void seed() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbi.installPlugins();
    jdbi.useHandle(DashboardIT::rows);
    resource = new DashboardResource(new DashboardQuery(jdbi), new QueryLog(jdbi));
  }

  private static void rows(Handle h) {
    UUID source =
        h.createQuery(
                """
                INSERT INTO data_source (name, engine, host, port, credential_ref, om_service_fqn)
                VALUES ('prod-pg', 'POSTGRES', 'db.example.test', 5432, 'vault://x', 'prod-pg') RETURNING id
                """)
            .mapTo(UUID.class)
            .one();
    UUID customer = asset(h, CUSTOMER, "TABLE");
    UUID orders = asset(h, ORDERS, "TABLE");
    UUID archive = asset(h, ARCHIVE, "TABLE");
    UUID salary = asset(h, SALARY, "TABLE");
    UUID notes = asset(h, NOTES, "TABLE");
    UUID schema = asset(h, "prod-pg.SalesDB.dbo", "SCHEMA");

    // customer: one confirmed sensitive column. orders: the table itself is
    // classified. archive: a column tagged under PII. salary: only suggested.
    // notes: PIIX, which is not PII. The schema: classified, but not a table.
    columnFacet(h, customer, CUSTOMER + ".email", "tags", "PII.Sensitive", "Confirmed");
    columnFacet(h, customer, CUSTOMER + ".email", "classifications", "PII", "Confirmed");
    tableFacet(h, orders, ORDERS, "classifications", "PII", null);
    columnFacet(h, archive, ARCHIVE + ".note", "tags", "PII.NonSensitive", "Confirmed");
    columnFacet(h, salary, SALARY + ".amount", "tags", "PII.Sensitive", "Suggested");
    tableFacet(h, notes, NOTES, "tags", "PIIX", null);
    tableFacet(h, schema, "prod-pg.SalesDB.dbo", "classifications", "PII", null);

    // A live mask on customer.email; an expired one on orders; a draft one on archive.
    bind(h, policy(h, "mask-email", "DATA", "ACTIVE", null), CUSTOMER + ".email", "COLUMN");
    bind(h, policy(h, "expired-mask", "DATA", "ACTIVE", NOW.minus(Duration.ofDays(1))), ORDERS, "TABLE");
    bind(h, policy(h, "draft-mask", "DATA", "DRAFT", null), ARCHIVE, "TABLE");
    UUID subscription = policy(h, "sales-subscription", "SUBSCRIPTION", "ACTIVE", null);
    bind(h, subscription, CUSTOMER, "TABLE");
    bind(h, subscription, ORDERS, "TABLE");

    UUID analystA = principal(h, "analyst_a", "USER");
    UUID analystB = principal(h, "analyst_b", "USER");
    UUID finance = principal(h, "finance", "GROUP");
    // Ending in three days, used. Open-ended and idle. Too new to be idle. A
    // group, never "unused". Revoked. Expired.
    grant(h, analystA, ORDERS, 100, Duration.ofDays(3), false);
    grant(h, analystB, CUSTOMER, 120, null, false);
    grant(h, analystB, NOTES, 10, Duration.ofDays(30), false);
    grant(h, finance, CUSTOMER, 200, null, false);
    grant(h, analystA, CUSTOMER, 50, null, true);
    h.createUpdate(
            """
            INSERT INTO access_grant (principal_id, asset_fqn, granted_by, reason, valid_from, valid_until, granted_at)
            VALUES (:p, :a, 'admin', 'expired', :from, :until, :from)
            """)
        .bind("p", analystA)
        .bind("a", SALARY)
        .bind("from", ts(NOW.minus(Duration.ofDays(30))))
        .bind("until", ts(NOW.minus(Duration.ofDays(1))))
        .execute();

    query(h, "analyst_a", "EXECUTED", null, 1, ORDERS);
    query(h, "analyst_a", "EXECUTED", null, 2, ORDERS, CUSTOMER);
    query(
        h,
        "analyst_b",
        "REJECTED",
        "Access to " + CUSTOMER + " is denied. sales-subscription: subject rule not satisfied",
        1,
        CUSTOMER);
    query(
        h,
        "analyst_b",
        "REJECTED",
        "This statement could not be parsed, so it cannot be enforced and will not be run",
        3);
    query(h, "analyst_a", "EXECUTED", null, 60, ORDERS);

    int[] millis = {10, 20, 30, 40};
    boolean[] allowed = {true, true, false, true};
    for (int i = 0; i < millis.length; i++) {
      h.createUpdate(
              """
              INSERT INTO audit_decision (principal_name, target_fqn, allowed, decision, evaluation_ms, from_cache, client_ip, occurred_at)
              VALUES ('analyst_a', :t, :allowed, '{}', :ms, :cached, CAST('192.0.2.44' AS inet), :at)
              """)
          .bind("t", CUSTOMER)
          .bind("allowed", allowed[i])
          .bind("ms", millis[i])
          .bind("cached", i == 0)
          .bind("at", ts(NOW.minus(Duration.ofHours(i + 1))))
          .execute();
    }
    h.execute(
        "INSERT INTO audit_decision (principal_name, target_fqn, allowed, decision, evaluation_ms, occurred_at)"
            + " VALUES ('analyst_a', 'x', false, '{}', 900, now() - interval '90 days')");

    request(h, analystA, ORDERS, "PENDING", 5, null, null);
    request(h, analystB, ORDERS, "REJECTED", 2, 24, null);
    request(h, analystB, NOTES, "COMPLETED", 3, 2, 10);
    request(h, analystA, NOTES, "WITHDRAWN", 40, 1, null);

    enforcement(h, customer, source, CUSTOMER, "APPLIED");
    enforcement(h, orders, source, ORDERS, "DRIFTED");

    h.createUpdate(
            """
            INSERT INTO sync_state (source, status, last_full_crawl_at, last_error)
            VALUES ('openmetadata', 'IDLE', :at, NULL)
            ON CONFLICT (source) DO UPDATE SET status = 'IDLE', last_full_crawl_at = :at, last_error = NULL
            """)
        .bind("at", ts(NOW.minus(Duration.ofDays(3))))
        .execute();
  }

  private static Dashboard read(int days, String label) {
    return resource.dashboard(days, label, security(auditor()));
  }

  @Test
  @DisplayName("sensitive means a confirmed label on the table or a column, matched by segment")
  void sensitive() {
    Dashboard d = read(30, "PII");
    assertThat(d.coverage().tables()).as("tables and views only").isEqualTo(5);
    assertThat(d.coverage().rows())
        .extracting(DashboardQuery.SensitiveTable::fqn)
        .as("unprotected first, the one somebody read ahead of the one nobody did")
        .containsExactly(ORDERS, ARCHIVE, CUSTOMER);
    assertThat(d.coverage().sensitive()).isEqualTo(3);

    Dashboard narrow = read(30, "PII.Sensitive");
    assertThat(narrow.coverage().rows())
        .extracting(DashboardQuery.SensitiveTable::fqn)
        .as("a suggested tag does not count")
        .containsExactly(CUSTOMER);
  }

  @Test
  @DisplayName("protected means a live data policy; an expired or draft one does not count")
  void coverage() {
    Dashboard d = read(30, "PII");
    DashboardQuery.SensitiveTable customer = row(d, CUSTOMER);
    assertThat(customer.sensitiveColumns()).isEqualTo(1);
    assertThat(customer.dataPolicies()).isEqualTo(1);
    assertThat(customer.subscriptionPolicies()).isEqualTo(1);
    assertThat(customer.activeGrants()).as("the revoked grant is gone").isEqualTo(2);
    assertThat(customer.readers()).isEqualTo(1);
    assertThat(customer.refused()).isEqualTo(1);

    DashboardQuery.SensitiveTable orders = row(d, ORDERS);
    assertThat(orders.dataPolicies()).as("expired").isZero();
    assertThat(orders.sensitiveColumns()).as("classified at the table").isZero();
    assertThat(orders.readers()).as("the 60-day-old query is outside the window").isEqualTo(1);
    assertThat(row(d, ARCHIVE).dataPolicies()).as("draft").isZero();

    assertThat(d.coverage().protectedTables()).isEqualTo(1);
    assertThat(d.coverage().exposed()).isEqualTo(1);
    assertThat(d.coverage().readUnprotected()).isEqualTo(1);
  }

  @Test
  @DisplayName("grants: live ones, ending soon, open-ended, on sensitive tables, and idle")
  void grants() {
    DashboardQuery.GrantPicture g = read(30, "PII").grants();
    assertThat(g.active()).isEqualTo(4);
    assertThat(g.endingCount()).isEqualTo(1);
    assertThat(g.endingSoon()).hasSize(1);
    assertThat(g.endingSoon().get(0).principal()).isEqualTo("analyst_a");
    assertThat(g.endingSoon().get(0).assetFqn()).isEqualTo(ORDERS);
    assertThat(g.endingSoon().get(0).validUntil()).isAfter(NOW.plus(Duration.ofDays(2)));
    assertThat(g.openEnded()).isEqualTo(2);
    assertThat(g.onSensitive()).isEqualTo(3);
    assertThat(g.unused()).as("analyst_b on customer; a group is never idle").isEqualTo(1);
  }

  @Test
  @DisplayName("activity over the window: per day, by why, the busiest tables and people")
  void activity() {
    Dashboard d = read(30, "PII");
    DashboardResource.Activity a = d.activity();
    assertThat(a.counts()).isEqualTo(new QueryLog.Counts(4, 2, 2, 0));
    assertThat(a.perDay()).hasSize(30);
    assertThat(a.perDay().stream().mapToInt(DashboardResource.Day::executed).sum()).isEqualTo(2);
    assertThat(a.refusals())
        .containsExactly(
            new DashboardResource.Refusal(QueryRefusals.Category.POLICY_DENY, 1),
            new DashboardResource.Refusal(QueryRefusals.Category.UNPARSEABLE, 1));
    assertThat(a.busiestTables())
        .extracting(DashboardQuery.Busiest::name)
        .containsExactly(CUSTOMER, ORDERS);
    DashboardQuery.Busiest customer = a.busiestTables().get(0);
    assertThat(customer.queries()).isEqualTo(2);
    assertThat(customer.refused()).isEqualTo(1);
    assertThat(customer.people()).isEqualTo(2);
    assertThat(a.busiestPeople())
        .extracting(DashboardQuery.Busiest::name)
        .containsExactly("analyst_a", "analyst_b");
    assertThat(a.busiestPeople().get(0).people()).as("tables analyst_a touched").isEqualTo(2);
    assertThat(a.busiestPeople().get(1).refused()).isEqualTo(2);

    assertThat(a.decisions()).isEqualTo(new DashboardQuery.Decisions(4, 1, 1, 25, 39));
    assertThat(read(90, "PII").activity().counts().total()).isEqualTo(5);
  }

  @Test
  @DisplayName("requests: what waits now, and how the window's were answered")
  void requests() {
    DashboardQuery.Requests r = read(30, "PII").requests();
    assertThat(r.pending()).isEqualTo(1);
    assertThat(r.oldestOpenAt()).isBefore(NOW.minus(Duration.ofDays(4)));
    assertThat(r.asked()).isEqualTo(3);
    assertThat(r.completed()).isEqualTo(1);
    assertThat(r.rejected()).isEqualTo(1);
    assertThat(r.withdrawn()).as("outside the window").isZero();
    assertThat(r.medianHoursToClose()).as("24 and 10 hours").isEqualTo(17.0);
  }

  @Test
  @DisplayName("health and what wants a person, most urgent first")
  void attention() {
    Dashboard d = read(30, "PII");
    assertThat(d.health().sources()).isEqualTo(1);
    assertThat(d.health().enforcement()).containsEntry("DRIFTED", 1).containsEntry("APPLIED", 1);
    assertThat(d.health().policies())
        .containsEntry("DATA ACTIVE", 2)
        .containsEntry("DATA DRAFT", 1)
        .containsEntry("SUBSCRIPTION ACTIVE", 1);
    assertThat(d.health().syncStatus()).isEqualTo("IDLE");

    assertThat(d.attention())
        .containsExactly(
            new Attention("UNPROTECTED_READ", Severity.HIGH, 1, ORDERS),
            new Attention("ENFORCEMENT_FAULT", Severity.HIGH, 1, null),
            new Attention("SYNC_STALE", Severity.MEDIUM, 1, null),
            new Attention("REQUESTS_WAITING", Severity.MEDIUM, 1, "5"),
            new Attention("GRANTS_ENDING", Severity.MEDIUM, 1, "analyst_a"),
            new Attention("UNPROTECTED_CLOSED", Severity.LOW, 1, ARCHIVE),
            new Attention("GRANTS_UNUSED", Severity.LOW, 1, null),
            new Attention("GRANTS_OPEN_ENDED", Severity.LOW, 2, null));
  }

  @Test
  @DisplayName("the response carries no client address and no statement text")
  void nothingLeaks() throws Exception {
    ObjectMapper mapper = Jackson.newObjectMapper();
    String json = mapper.writeValueAsString(read(365, "PII"));
    assertThat(json).doesNotContain("192.0.2.").doesNotContain("198.51.100.");
    assertThat(json).doesNotContain("SELECT").doesNotContain("clientIp").doesNotContain("requesterIp");
  }

  private static DashboardQuery.SensitiveTable row(Dashboard d, String fqn) {
    return d.coverage().rows().stream().filter(r -> r.fqn().equals(fqn)).findFirst().orElseThrow();
  }

  private static UUID asset(Handle h, String fqn, String type) {
    return h.createQuery("INSERT INTO asset (fqn, name, asset_type) VALUES (:f, :n, :t) RETURNING id")
        .bind("f", fqn)
        .bind("n", fqn.substring(fqn.lastIndexOf('.') + 1))
        .bind("t", type)
        .mapTo(UUID.class)
        .one();
  }

  private static void tableFacet(Handle h, UUID asset, String fqn, String type, String facet, String state) {
    h.createUpdate(
            "INSERT INTO asset_facet (asset_id, target_fqn, facet_type, facet_fqn, om_state) VALUES (:a, :t, :ty, :f, :s)")
        .bind("a", asset)
        .bind("t", fqn)
        .bind("ty", type)
        .bind("f", facet)
        .bind("s", state)
        .execute();
  }

  private static void columnFacet(Handle h, UUID asset, String fqn, String type, String facet, String state) {
    UUID column =
        h.createQuery(
                """
                INSERT INTO asset_column (asset_id, fqn, name) VALUES (:a, :f, :n)
                ON CONFLICT DO NOTHING RETURNING id
                """)
            .bind("a", asset)
            .bind("f", fqn)
            .bind("n", fqn.substring(fqn.lastIndexOf('.') + 1))
            .mapTo(UUID.class)
            .findOne()
            .orElseGet(
                () ->
                    h.createQuery("SELECT id FROM asset_column WHERE fqn = :f")
                        .bind("f", fqn)
                        .mapTo(UUID.class)
                        .first());
    h.createUpdate(
            "INSERT INTO asset_facet (column_id, target_fqn, facet_type, facet_fqn, om_state) VALUES (:c, :t, :ty, :f, :s)")
        .bind("c", column)
        .bind("t", fqn)
        .bind("ty", type)
        .bind("f", facet)
        .bind("s", state)
        .execute();
  }

  private static UUID policy(Handle h, String name, String type, String state, Instant until) {
    return h.createQuery(
            """
            INSERT INTO policy (name, policy_type, scope_level, document, lifecycle_state, valid_until)
            VALUES (:n, :t, 'TABLE', '{}', :s, :u) RETURNING id
            """)
        .bind("n", name)
        .bind("t", type)
        .bind("s", state)
        .bind("u", until == null ? null : ts(until))
        .mapTo(UUID.class)
        .one();
  }

  private static void bind(Handle h, UUID policy, String target, String kind) {
    h.createUpdate("INSERT INTO policy_binding (policy_id, target_fqn, target_kind) VALUES (:p, :t, :k)")
        .bind("p", policy)
        .bind("t", target)
        .bind("k", kind)
        .execute();
  }

  private static UUID principal(Handle h, String name, String type) {
    return h.createQuery(
            "INSERT INTO principal (username, principal_type, source) VALUES (:n, :t, 'local') RETURNING id")
        .bind("n", name)
        .bind("t", type)
        .mapTo(UUID.class)
        .one();
  }

  private static void grant(
      Handle h, UUID principal, String asset, int daysAgo, Duration remaining, boolean revoked) {
    Instant from = NOW.minus(Duration.ofDays(daysAgo));
    h.createUpdate(
            """
            INSERT INTO access_grant (principal_id, asset_fqn, granted_by, reason, valid_from, valid_until,
                                      granted_at, revoked_at, revoked_by)
            VALUES (:p, :a, 'admin', 'test', :from, :until, :from, :revokedAt, :revokedBy)
            """)
        .bind("p", principal)
        .bind("a", asset)
        .bind("from", ts(from))
        .bind("until", remaining == null ? null : ts(NOW.plus(remaining)))
        .bind("revokedAt", revoked ? ts(NOW.minus(Duration.ofDays(1))) : null)
        .bind("revokedBy", revoked ? "admin" : null)
        .execute();
  }

  private static void query(
      Handle h, String principal, String outcome, String reason, int daysAgo, String... assets) {
    h.createUpdate(
            """
            INSERT INTO audit_query (principal_name, original_sql, outcome, reject_reason, asset_fqns, client_ip, occurred_at)
            VALUES (:p, 'SELECT * FROM somewhere', :o, :r, :a, CAST('198.51.100.7' AS inet), :at)
            """)
        .bind("p", principal)
        .bind("o", outcome)
        .bind("r", reason)
        .bindArray("a", String.class, (Object[]) assets)
        .bind("at", ts(NOW.minus(Duration.ofDays(daysAgo))))
        .execute();
  }

  /**
   * @param decidedAfterHours hours from asking to the decision; null while pending
   * @param completedAfterHours hours from asking to completion, for a completed one
   */
  private static void request(
      Handle h,
      UUID requester,
      String asset,
      String status,
      int daysAgo,
      Integer decidedAfterHours,
      Integer completedAfterHours) {
    Instant created = NOW.minus(Duration.ofDays(daysAgo));
    h.createUpdate(
            """
            INSERT INTO access_request (requester_id, requester_username, asset_fqn, reason, status, created_at,
                                        decided_by, decided_at, fulfilment, completed_by, completed_at, requester_ip)
            VALUES (:rid, 'someone', :a, 'for a report', :s, :c, :db, :da, :f, :cb, :ca, CAST('192.0.2.99' AS inet))
            """)
        .bind("rid", requester)
        .bind("a", asset)
        .bind("s", status)
        .bind("c", ts(created))
        .bind("db", decidedAfterHours == null ? null : "owner_a")
        .bind("da", decidedAfterHours == null ? null : ts(created.plus(Duration.ofHours(decidedAfterHours))))
        .bind("f", completedAfterHours == null ? null : "POLICY_UPDATED")
        .bind("cb", completedAfterHours == null ? null : "owner_a")
        .bind(
            "ca",
            completedAfterHours == null ? null : ts(created.plus(Duration.ofHours(completedAfterHours))))
        .execute();
  }

  private static void enforcement(Handle h, UUID asset, UUID source, String fqn, String status) {
    h.createUpdate(
            """
            INSERT INTO enforcement_state (asset_id, data_source_id, mode, target_fqn, status)
            VALUES (:a, :s, 'SECURE_VIEW', :f, :st)
            """)
        .bind("a", asset)
        .bind("s", source)
        .bind("f", fqn)
        .bind("st", status)
        .execute();
  }

  private static Timestamp ts(Instant at) {
    return Timestamp.from(at);
  }

  private static AuthenticatedUser auditor() {
    return new AuthenticatedUser(
        UUID.randomUUID(), "compliance_a", "compliance_a@example.test", "compliance_a", "local",
        Set.of("AUDITOR"), List.of());
  }

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
