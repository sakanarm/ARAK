package com.mfec.dac.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The two dashboard reads of M9 slice 2c against real rows: which grants end
 * soon, and how often each table is asked for.
 *
 * <p>Rows are written with SQL rather than through the stores, because what is
 * under test is how the queries read states the stores reach only over days --
 * a grant that lapsed an hour ago and has not been tombstoned yet, a request
 * declined by its configurer a week after it was approved.
 */
@Testcontainers
class AccessDashboardIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String CUSTOMER = "prod-pg.SalesDB.dbo.customer";
  private static final String LEDGER = "prod-pg.SalesDB.dbo.ledger";
  private static final String ORDERS = "prod-pg.SalesDB.dbo.orders";

  private static Jdbi jdbi;

  /** Truncated to microseconds, which is what timestamptz keeps. */
  private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

  private GrantStore grants;
  private RequestStatistics statistics;
  private UUID analyst;
  private UUID finance;
  private UUID parentGroup;
  private UUID other;
  private UUID disabled;

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
  void clean() {
    jdbi.useHandle(
        handle ->
            handle.execute(
                """
                TRUNCATE access_request, audit_access_request, access_grant, audit_grant_change,
                         group_member, principal CASCADE
                """));
    grants = new GrantStore(jdbi);
    statistics = new RequestStatistics(jdbi);
    analyst = principal("USER", "analyst_a", true);
    finance = principal("GROUP", "finance", true);
    parentGroup = principal("GROUP", "all-staff", true);
    other = principal("USER", "someone", true);
    disabled = principal("USER", "gone_away", false);
    member(finance, analyst);
    member(parentGroup, finance);
  }

  private UUID principal(String type, String username, boolean enabled) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    INSERT INTO principal (principal_type, username, display_name, source, enabled)
                    VALUES (:type, :username, :username, 'local', :enabled)
                    RETURNING id
                    """)
                .bind("type", type)
                .bind("username", username)
                .bind("enabled", enabled)
                .mapTo(UUID.class)
                .one());
  }

  private void member(UUID group, UUID member) {
    jdbi.useHandle(
        handle ->
            handle.execute(
                "INSERT INTO group_member (group_id, member_id, source) VALUES (?, ?, 'local')",
                group,
                member));
  }

  private UUID grant(String fqn, UUID principal, Instant from, Instant until) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    INSERT INTO access_grant
                      (asset_fqn, principal_id, source, valid_from, valid_until, reason, granted_by)
                    VALUES (:fqn, :principal, 'manual', :from, :until, 'for the test', 'admin')
                    RETURNING id
                    """)
                .bind("fqn", fqn)
                .bind("principal", principal)
                .bind("from", from)
                .bind("until", until)
                .mapTo(UUID.class)
                .one());
  }

  // ------------------------------------------------------------ expiring

  @Nested
  @DisplayName("grants ending soon")
  class Expiring {

    @Test
    @DisplayName("lists live grants that end inside the window, soonest first")
    void soonestFirst() {
      Instant week = now.plus(Duration.ofDays(7));
      UUID later = grant(CUSTOMER, analyst, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(5)));
      UUID sooner = grant(LEDGER, other, now.minus(Duration.ofDays(1)), now.plus(Duration.ofHours(3)));
      UUID group = grant(ORDERS, finance, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(2)));

      assertThat(grants.expiring(now, week))
          .extracting(GrantStore.StoredGrant::id)
          .containsExactly(sooner, group, later);
    }

    @Test
    @DisplayName("leaves out what does not end in the window, never ends, or no longer counts")
    void leavesOut() {
      Instant week = now.plus(Duration.ofDays(7));
      UUID inside = grant(CUSTOMER, analyst, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(6)));
      // Ends after the window.
      grant(LEDGER, analyst, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(8)));
      // Open-ended.
      grant(ORDERS, analyst, now.minus(Duration.ofDays(1)), null);
      // Already lapsed, and the expiry job has not got to it yet.
      grant(ORDERS, other, now.minus(Duration.ofDays(3)), now.minus(Duration.ofMinutes(5)));
      // Not started yet, though it would end inside the window.
      grant(LEDGER, other, now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(2)));
      // Held by a principal who has been disabled.
      grant(CUSTOMER, disabled, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)));
      // Revoked.
      UUID revoked =
          grant(LEDGER, finance, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)));
      jdbi.useHandle(
          handle ->
              handle.execute(
                  "UPDATE access_grant SET revoked_at = now(), revoked_by = 'admin' WHERE id = ?",
                  revoked));

      assertThat(grants.expiring(now, week))
          .extracting(GrantStore.StoredGrant::id)
          .containsExactly(inside);
    }

    @Test
    @DisplayName("counts a grant ending exactly at the edge of the window as inside it")
    void edge() {
      Instant week = now.plus(Duration.ofDays(7));
      UUID atEdge = grant(CUSTOMER, analyst, now.minus(Duration.ofDays(1)), week);
      assertThat(grants.expiring(now, week))
          .extracting(GrantStore.StoredGrant::id)
          .containsExactly(atEdge);
    }

    @Test
    @DisplayName("carries who holds it, so the card can name them")
    void namesTheHolder() {
      grant(CUSTOMER, finance, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)));
      assertThat(grants.expiring(now, now.plus(Duration.ofDays(7))))
          .singleElement()
          .satisfies(
              g -> {
                assertThat(g.username()).isEqualTo("finance");
                assertThat(g.isGroup()).isTrue();
                assertThat(g.assetFqn()).isEqualTo(CUSTOMER);
              });
    }
  }

  @Nested
  @DisplayName("who a grant reaches")
  class Reach {

    @Test
    @DisplayName("is the person, their groups, and the groups those are in")
    void walksGroups() {
      assertThat(grants.reachableFrom("analyst_a"))
          .containsExactlyInAnyOrder(analyst, finance, parentGroup);
      assertThat(grants.reachableFrom("ANALYST_A"))
          .containsExactlyInAnyOrder(analyst, finance, parentGroup);
      assertThat(grants.reachableFrom("someone")).containsExactly(other);
    }

    @Test
    @DisplayName("is nobody for a name nobody has")
    void unknown() {
      assertThat(grants.reachableFrom("nobody")).isEmpty();
      assertThat(grants.reachableFrom(" ")).isEmpty();
      assertThat(grants.reachableFrom(null)).isEmpty();
    }
  }

  // ------------------------------------------------------------ statistics

  /**
   * Writes one request in the state named, {@code age} ago, answered {@code
   * took} after it was asked.
   */
  private void request(String fqn, String requester, String state, Duration age, Duration took) {
    Instant asked = now.minus(age);
    Instant answered = took == null ? null : asked.plus(took);
    jdbi.useHandle(
        handle -> {
          String sql =
              switch (state) {
                case "PENDING" ->
                    """
                    INSERT INTO access_request (asset_fqn, requester_username, reason, status, created_at)
                    VALUES (:fqn, :who, 'need it', 'PENDING', :asked)
                    """;
                case "APPROVED" ->
                    """
                    INSERT INTO access_request (asset_fqn, requester_username, reason, status, created_at,
                                                decided_by, decided_at)
                    VALUES (:fqn, :who, 'need it', 'APPROVED', :asked, 'owner_o', :answered)
                    """;
                case "IN_PROGRESS" ->
                    """
                    INSERT INTO access_request (asset_fqn, requester_username, reason, status, created_at,
                                                decided_by, decided_at, assignee, assigned_at)
                    VALUES (:fqn, :who, 'need it', 'IN_PROGRESS', :asked, 'owner_o', :answered,
                            'admin', :answered)
                    """;
                case "COMPLETED" ->
                    """
                    INSERT INTO access_request (asset_fqn, requester_username, reason, status, created_at,
                                                decided_by, decided_at, completed_by, completed_at,
                                                fulfilment)
                    VALUES (:fqn, :who, 'need it', 'COMPLETED', :asked, 'owner_o', :asked,
                            'admin', :answered, 'POLICY_UPDATED')
                    """;
                case "REJECTED" ->
                    """
                    INSERT INTO access_request (asset_fqn, requester_username, reason, status, created_at,
                                                decided_by, decided_at)
                    VALUES (:fqn, :who, 'need it', 'REJECTED', :asked, 'owner_o', :answered)
                    """;
                case "DECLINED" ->
                    """
                    INSERT INTO access_request (asset_fqn, requester_username, reason, status, created_at,
                                                decided_by, decided_at, completed_by, completed_at)
                    VALUES (:fqn, :who, 'need it', 'REJECTED', :asked, 'owner_o', :asked,
                            'admin', :answered)
                    """;
                case "WITHDRAWN" ->
                    """
                    INSERT INTO access_request (asset_fqn, requester_username, reason, status, created_at,
                                                decided_by, decided_at)
                    VALUES (:fqn, :who, 'need it', 'WITHDRAWN', :asked, :who, :answered)
                    """;
                default -> throw new IllegalArgumentException(state);
              };
          handle
              .createUpdate(sql)
              .bind("fqn", fqn)
              .bind("who", requester)
              .bind("asked", asked)
              .bind("answered", answered)
              .execute();
        });
  }

  @Nested
  @DisplayName("requests per table")
  class Statistics {

    @BeforeEach
    void history() {
      Duration day = Duration.ofDays(1);
      // customer: asked five times by three people, every kind of ending.
      request(CUSTOMER, "analyst_a", "COMPLETED", Duration.ofDays(10), Duration.ofHours(2));
      request(CUSTOMER, "analyst_b", "REJECTED", Duration.ofDays(9), Duration.ofHours(4));
      request(CUSTOMER, "Analyst_A", "DECLINED", Duration.ofDays(8), Duration.ofHours(30));
      request(CUSTOMER, "analyst_c", "WITHDRAWN", Duration.ofDays(3), Duration.ofHours(1));
      request(CUSTOMER, "analyst_b", "PENDING", day, null);
      // ledger: twice, both still open.
      request(LEDGER, "analyst_a", "APPROVED", Duration.ofDays(2), Duration.ofHours(1));
      request(LEDGER, "analyst_b", "IN_PROGRESS", Duration.ofHours(5), Duration.ofHours(1));
      // orders: once, long ago.
      request(ORDERS, "analyst_a", "COMPLETED", Duration.ofDays(200), Duration.ofHours(8));
    }

    @Test
    @DisplayName("counts each table's asks and how they ended, most asked first")
    void perTable() {
      List<RequestStatistics.TableStats> stats =
          statistics.perTable(now.minus(Duration.ofDays(90)), null);

      assertThat(stats)
          .extracting(RequestStatistics.TableStats::assetFqn)
          .containsExactly(CUSTOMER, LEDGER);
      RequestStatistics.TableStats customer = stats.get(0);
      assertThat(customer.asked()).isEqualTo(5);
      assertThat(customer.open()).isEqualTo(1);
      assertThat(customer.completed()).isEqualTo(1);
      assertThat(customer.rejected()).isEqualTo(1);
      assertThat(customer.declined()).isEqualTo(1);
      assertThat(customer.withdrawn()).isEqualTo(1);
      // analyst_a and Analyst_A are one person.
      assertThat(customer.requesters()).isEqualTo(3);
      assertThat(customer.lastAskedAt()).isEqualTo(now.minus(Duration.ofDays(1)));

      RequestStatistics.TableStats ledger = stats.get(1);
      assertThat(ledger.asked()).isEqualTo(2);
      assertThat(ledger.open()).isEqualTo(2);
      assertThat(ledger.completed() + ledger.rejected() + ledger.declined()).isZero();
    }

    @Test
    @DisplayName("takes the median time to a final answer, leaving out withdrawals and open asks")
    void median() {
      List<RequestStatistics.TableStats> stats =
          statistics.perTable(now.minus(Duration.ofDays(90)), null);
      // Completed in 2h, rejected in 4h, declined in 30h: the median is 4h.
      // The withdrawal after 1h is not an answer and does not pull it down.
      assertThat(stats.get(0).medianHoursToClose()).isEqualTo(4.0);
      // Nothing on the ledger has ended, so there is no median to give.
      assertThat(stats.get(1).medianHoursToClose()).isNull();
    }

    @Test
    @DisplayName("counts only inside the window")
    void window() {
      assertThat(statistics.perTable(now.minus(Duration.ofDays(365)), null))
          .extracting(RequestStatistics.TableStats::assetFqn)
          .containsExactly(CUSTOMER, LEDGER, ORDERS);
      // Two each in the last four days: the tie goes to the table asked for
      // most recently, the ledger five hours ago over the customer yesterday.
      assertThat(statistics.perTable(now.minus(Duration.ofDays(4)), null))
          .extracting(RequestStatistics.TableStats::assetFqn, RequestStatistics.TableStats::asked)
          .containsExactly(
              org.assertj.core.api.Assertions.tuple(LEDGER, 2),
              org.assertj.core.api.Assertions.tuple(CUSTOMER, 2));
    }

    @Test
    @DisplayName("narrows to one table")
    void oneTable() {
      assertThat(statistics.perTable(now.minus(Duration.ofDays(90)), LEDGER))
          .singleElement()
          .extracting(RequestStatistics.TableStats::assetFqn)
          .isEqualTo(LEDGER);
      assertThat(statistics.perTable(now.minus(Duration.ofDays(90)), "prod-pg.nothing")).isEmpty();
    }
  }
}
