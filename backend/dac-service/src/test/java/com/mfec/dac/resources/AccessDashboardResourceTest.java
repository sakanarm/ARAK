package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mfec.dac.access.AccessEligibility;
import com.mfec.dac.access.AccessQuery;
import com.mfec.dac.access.AccessRequestStore;
import com.mfec.dac.access.GrantStore;
import com.mfec.dac.access.RequestStatistics;
import com.mfec.dac.auth.AuthenticatedUser;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two dashboard reads of M9 slice 2c: who is about to lose access, and how
 * often each table is asked for -- and, for both, who is shown what.
 */
class AccessDashboardResourceTest {

  static final Instant NOW = Instant.parse("2026-09-25T03:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  static final String SALES = "pg.salesdb.sales.customer";
  static final String HR = "pg.hrdb.hr.salary";

  static AuthenticatedUser user(String name, Set<String> roles, List<String> scopes) {
    return new AuthenticatedUser(
        UUID.randomUUID(), name, name + "@example.test", name, "local", roles, scopes);
  }

  static final AuthenticatedUser ADMIN = user("admin", Set.of("PLATFORM_ADMIN"), List.of());
  static final AuthenticatedUser AUDITOR = user("auditor", Set.of("AUDITOR"), List.of());
  static final AuthenticatedUser SALES_OWNER =
      user("sales_owner", Set.of("DATA_OWNER"), List.of("pg.salesdb"));
  static final AuthenticatedUser ANALYST = user("analyst", Set.of("REQUESTER"), List.of());

  static SecurityContext as(AuthenticatedUser user) {
    SecurityContext security = mock(SecurityContext.class);
    when(security.getUserPrincipal()).thenReturn(user);
    return security;
  }

  static GrantStore.StoredGrant grant(String fqn, UUID principal, String username, Duration left) {
    return new GrantStore.StoredGrant(
        UUID.randomUUID(), fqn, principal, username, username, "user", "local", "request",
        UUID.randomUUID(), NOW.minus(Duration.ofDays(1)), NOW.plus(left), "because", "admin",
        NOW.minus(Duration.ofDays(1)), null, null, null, null);
  }

  @Nested
  @DisplayName("grants ending soon")
  class Expiring {

    final GrantStore grants = mock(GrantStore.class);
    final AccessResource resource = new AccessResource(mock(AccessQuery.class), grants, CLOCK);

    final UUID analystId = UUID.randomUUID();
    final UUID financeGroup = UUID.randomUUID();
    final UUID someoneElse = UUID.randomUUID();

    GrantStore.StoredGrant analystOnHr;
    GrantStore.StoredGrant groupOnHr;
    GrantStore.StoredGrant otherOnSales;
    GrantStore.StoredGrant otherOnHr;

    @BeforeEach
    void world() {
      analystOnHr = grant(HR, analystId, "analyst", Duration.ofHours(5));
      groupOnHr = grant(HR, financeGroup, "finance", Duration.ofDays(2));
      otherOnSales = grant(SALES, someoneElse, "someone", Duration.ofDays(3));
      otherOnHr = grant(HR, someoneElse, "someone", Duration.ofDays(4));
      when(grants.expiring(NOW, NOW.plus(Duration.ofDays(14))))
          .thenReturn(List.of(analystOnHr, groupOnHr, otherOnSales, otherOnHr));
      when(grants.reachableFrom("analyst")).thenReturn(Set.of(analystId, financeGroup));
      when(grants.reachableFrom("admin")).thenReturn(Set.of());
      when(grants.reachableFrom("auditor")).thenReturn(Set.of());
      when(grants.reachableFrom("sales_owner")).thenReturn(Set.of());
    }

    @Test
    @DisplayName("measures the window from the server's clock and says so")
    void window() {
      AccessResource.Expiring answer = resource.expiring(14, 100, as(ADMIN));
      assertThat(answer.now()).isEqualTo(NOW);
      assertThat(answer.withinDays()).isEqualTo(14);
      verify(grants).expiring(NOW, NOW.plus(Duration.ofDays(14)));
    }

    @Test
    @DisplayName("shows an administrator every grant, soonest first, and lets them end each")
    void adminSeesAll() {
      AccessResource.Expiring answer = resource.expiring(14, 100, as(ADMIN));
      assertThat(answer.total()).isEqualTo(4);
      assertThat(answer.grants())
          .extracting(AccessResource.ExpiringGrant::id)
          .containsExactly(analystOnHr.id(), groupOnHr.id(), otherOnSales.id(), otherOnHr.id());
      assertThat(answer.grants()).allMatch(AccessResource.ExpiringGrant::mayRevoke);
      assertThat(answer.grants()).noneMatch(AccessResource.ExpiringGrant::mine);
    }

    @Test
    @DisplayName("shows an auditor everything but offers them nothing to revoke")
    void auditorReadsOnly() {
      AccessResource.Expiring answer = resource.expiring(14, 100, as(AUDITOR));
      assertThat(answer.grants()).hasSize(4);
      assertThat(answer.grants()).noneMatch(AccessResource.ExpiringGrant::mayRevoke);
    }

    @Test
    @DisplayName("shows a requester only the grants that reach them, through a group too")
    void requesterSeesOwn() {
      AccessResource.Expiring answer = resource.expiring(14, 100, as(ANALYST));
      assertThat(answer.grants())
          .extracting(AccessResource.ExpiringGrant::id)
          .containsExactly(analystOnHr.id(), groupOnHr.id());
      assertThat(answer.grants()).allMatch(AccessResource.ExpiringGrant::mine);
      assertThat(answer.grants()).noneMatch(AccessResource.ExpiringGrant::mayRevoke);
      assertThat(answer.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("shows a data owner the grants on their own tables, and no others")
    void ownerSeesTheirTables() {
      AccessResource.Expiring answer = resource.expiring(14, 100, as(SALES_OWNER));
      assertThat(answer.grants())
          .singleElement()
          .satisfies(
              only -> {
                assertThat(only.id()).isEqualTo(otherOnSales.id());
                assertThat(only.mayRevoke()).isTrue();
                assertThat(only.mine()).isFalse();
              });
    }

    @Test
    @DisplayName("cuts the list at the limit but still counts what it cut")
    void limits() {
      AccessResource.Expiring answer = resource.expiring(14, 1, as(ADMIN));
      assertThat(answer.grants()).hasSize(1);
      assertThat(answer.total()).isEqualTo(4);
    }

    @Test
    @DisplayName("refuses a window or a limit outside what it will count")
    void refusesBadNumbers() {
      assertThatThrownBy(() -> resource.expiring(0, 10, as(ADMIN)))
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining("withinDays");
      assertThatThrownBy(() -> resource.expiring(366, 10, as(ADMIN)))
          .isInstanceOf(BadRequestException.class);
      assertThatThrownBy(() -> resource.expiring(14, 0, as(ADMIN)))
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining("limit");
      assertThatThrownBy(() -> resource.expiring(14, 501, as(ADMIN)))
          .isInstanceOf(BadRequestException.class);
      verify(grants, never()).expiring(any(), any());
    }
  }

  @Nested
  @DisplayName("requests per table")
  class Stats {

    final RequestStatistics statistics = mock(RequestStatistics.class);
    final AccessRequestResource resource =
        new AccessRequestResource(
            mock(AccessRequestStore.class), mock(AccessEligibility.class), null, statistics, CLOCK);

    final Instant since = NOW.minus(Duration.ofDays(90));

    final RequestStatistics.TableStats sales =
        new RequestStatistics.TableStats(SALES, 7, 2, 3, 1, 0, 1, 4, 6.5, NOW);
    final RequestStatistics.TableStats hr =
        new RequestStatistics.TableStats(HR, 3, 0, 1, 0, 2, 0, 2, null, NOW);

    @BeforeEach
    void world() {
      when(statistics.perTable(eq(since), isNull())).thenReturn(List.of(sales, hr));
      when(statistics.perTable(eq(since), eq(SALES))).thenReturn(List.of(sales));
    }

    @Test
    @DisplayName("counts every table for an administrator, with totals across them")
    void adminCountsAll() {
      AccessRequestResource.Stats answer = resource.stats(90, null, 50, as(ADMIN));
      assertThat(answer.since()).isEqualTo(since);
      assertThat(answer.days()).isEqualTo(90);
      assertThat(answer.tables()).containsExactly(sales, hr);
      assertThat(answer.totals())
          .isEqualTo(new AccessRequestResource.StatsTotals(2, 10, 2, 4, 1, 2, 1));
    }

    @Test
    @DisplayName("counts every table for an auditor too")
    void auditorCountsAll() {
      assertThat(resource.stats(90, null, 50, as(AUDITOR)).tables()).containsExactly(sales, hr);
    }

    @Test
    @DisplayName("counts only a data owner's own tables, totals included")
    void ownerCountsTheirs() {
      AccessRequestResource.Stats answer = resource.stats(90, null, 50, as(SALES_OWNER));
      assertThat(answer.tables()).containsExactly(sales);
      assertThat(answer.totals().asked()).isEqualTo(7);
      assertThat(answer.totals().tables()).isEqualTo(1);
    }

    @Test
    @DisplayName("gives a requester an empty answer, not a refusal")
    void requesterGetsNothing() {
      AccessRequestResource.Stats answer = resource.stats(90, null, 50, as(ANALYST));
      assertThat(answer.tables()).isEmpty();
      assertThat(answer.total()).isZero();
      assertThat(answer.totals()).isEqualTo(new AccessRequestResource.StatsTotals(0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("narrows to one table when asked, trimming the name")
    void oneTable() {
      assertThat(resource.stats(90, "  " + SALES + " ", 50, as(ADMIN)).tables())
          .containsExactly(sales);
      assertThat(resource.stats(90, "   ", 50, as(ADMIN)).tables()).containsExactly(sales, hr);
    }

    @Test
    @DisplayName("cuts at the limit but totals every visible table")
    void limits() {
      AccessRequestResource.Stats answer = resource.stats(90, null, 1, as(ADMIN));
      assertThat(answer.tables()).containsExactly(sales);
      assertThat(answer.total()).isEqualTo(2);
      assertThat(answer.totals().asked()).isEqualTo(10);
    }

    @Test
    @DisplayName("refuses a window or a limit outside what it will count")
    void refusesBadNumbers() {
      assertThatThrownBy(() -> resource.stats(0, null, 50, as(ADMIN)))
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining("days");
      assertThatThrownBy(() -> resource.stats(RequestStatistics.MAX_DAYS + 1, null, 50, as(ADMIN)))
          .isInstanceOf(BadRequestException.class);
      assertThatThrownBy(() -> resource.stats(90, null, 0, as(ADMIN)))
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining("limit");
      verify(statistics, never()).perTable(any(), anyString());
    }

    @Test
    @DisplayName("says the counts are not here when the resource was built without them")
    void unavailable() {
      AccessRequestResource bare =
          new AccessRequestResource(mock(AccessRequestStore.class), mock(AccessEligibility.class));
      assertThatThrownBy(() -> bare.stats(90, null, 50, as(ADMIN)))
          .isInstanceOf(NotFoundException.class);
    }
  }
}
