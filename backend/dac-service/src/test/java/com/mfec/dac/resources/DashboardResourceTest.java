package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.audit.QueryLog;
import com.mfec.dac.audit.QueryRefusals;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.dashboard.DashboardQuery;
import com.mfec.dac.dashboard.DashboardQuery.SensitiveTable;
import com.mfec.dac.resources.DashboardResource.Attention;
import com.mfec.dac.resources.DashboardResource.Severity;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How the dashboard reads its numbers into coverage, days and what wants a person (M10). */
class DashboardResourceTest {

  private static final Instant NOW = Instant.parse("2026-09-25T06:00:00Z");

  private static AuthenticatedUser user(String name, Set<String> roles, List<String> scopes) {
    return new AuthenticatedUser(
        UUID.randomUUID(), name, name + "@example.test", name, "local", roles, scopes);
  }

  private static SensitiveTable table(
      String fqn, int dataPolicies, int subscriptions, int grants, int readers) {
    return new SensitiveTable(fqn, 2, dataPolicies, subscriptions, grants, readers, 0, List.of());
  }

  private static DashboardQuery.GrantPicture noGrants() {
    return new DashboardQuery.GrantPicture(0, 0, List.of(), 0, 0, 0);
  }

  private static DashboardQuery.Requests noRequests() {
    return new DashboardQuery.Requests(0, 0, 0, null, 0, 0, 0, 0, null);
  }

  private static DashboardQuery.Health healthy() {
    return new DashboardQuery.Health(1, 1, Map.of("APPLIED", 3), Map.of(), "IDLE", NOW.minusSeconds(3600), false);
  }

  private static List<String> kinds(List<Attention> attention) {
    return attention.stream().map(Attention::kind).toList();
  }

  @Test
  @DisplayName("only the roles that oversee everything may read it")
  void forbidden() {
    DashboardResource resource = new DashboardResource(null, null);
    for (AuthenticatedUser caller :
        List.of(
            user("owner_a", Set.of("DATA_OWNER"), List.of("pg.salesdb")),
            user("analyst_a", Set.of("REQUESTER"), List.of()))) {
      assertThatThrownBy(() -> resource.dashboard(30, "PII", security(caller)))
          .as(caller.getName())
          .isInstanceOf(ForbiddenException.class);
    }
    assertThatThrownBy(() -> resource.dashboard(30, "PII", security(null)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  @DisplayName("the window and the label are checked before anything is read")
  void validation() {
    DashboardResource resource = new DashboardResource(null, null);
    SecurityContext auditor = security(user("compliance_a", Set.of("AUDITOR"), List.of()));
    assertThatThrownBy(() -> resource.dashboard(0, "PII", auditor)).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> resource.dashboard(366, "PII", auditor)).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> resource.dashboard(30, "  ", auditor)).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> resource.dashboard(30, "x".repeat(201), auditor))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  @DisplayName("coverage counts what a data policy protects, and what is open and unprotected")
  void coverage() {
    DashboardResource.Coverage coverage =
        DashboardResource.coverage(
            40,
            List.of(
                table("a", 0, 1, 0, 3), // unprotected, reachable, read
                table("b", 0, 0, 2, 0), // unprotected, a grant lets somebody in
                table("c", 0, 0, 0, 0), // unprotected, nobody can reach it
                table("d", 1, 1, 0, 5))); // masked
    assertThat(coverage.tables()).isEqualTo(40);
    assertThat(coverage.sensitive()).isEqualTo(4);
    assertThat(coverage.protectedTables()).isEqualTo(1);
    assertThat(coverage.exposed()).isEqualTo(2);
    assertThat(coverage.readUnprotected()).isEqualTo(1);
    assertThat(coverage.rows()).hasSize(4);
  }

  @Test
  @DisplayName("the table list is capped, the counts are not")
  void coverageCap() {
    List<SensitiveTable> many = new ArrayList<>();
    for (int i = 0; i < DashboardResource.TABLE_ROWS + 20; i++) {
      many.add(table("t" + i, 0, 0, 0, 0));
    }
    DashboardResource.Coverage coverage = DashboardResource.coverage(500, many);
    assertThat(coverage.sensitive()).isEqualTo(DashboardResource.TABLE_ROWS + 20);
    assertThat(coverage.rows()).hasSize(DashboardResource.TABLE_ROWS);
  }

  @Test
  @DisplayName("every day of the window is on the chart, the quiet ones as zeros")
  void days() {
    List<DashboardResource.Day> days =
        DashboardResource.days(
            LocalDate.parse("2026-09-22"),
            LocalDate.parse("2026-09-25"),
            Map.of("2026-09-23", new int[] {4, 1, 0}, "2026-09-25", new int[] {0, 0, 2}));
    assertThat(days)
        .containsExactly(
            new DashboardResource.Day("2026-09-22", 0, 0, 0),
            new DashboardResource.Day("2026-09-23", 4, 1, 0),
            new DashboardResource.Day("2026-09-24", 0, 0, 0),
            new DashboardResource.Day("2026-09-25", 0, 0, 2));
  }

  @Test
  @DisplayName("refusals are gathered into the categories a person reads, most frequent first")
  void refusals() {
    List<DashboardResource.Refusal> refusals =
        DashboardResource.refusals(
            List.of(
                new QueryLog.ReasonCount(
                    "REJECTED", "Access to pg.s.x.customer is denied. finance: subject rule not satisfied", 3),
                new QueryLog.ReasonCount(
                    "REJECTED", "Access to pg.s.x.orders is denied. finance: subject rule not satisfied", 2),
                new QueryLog.ReasonCount(
                    "REJECTED",
                    "This statement could not be parsed, so it cannot be enforced and will not be run",
                    4)));
    assertThat(refusals)
        .containsExactly(
            new DashboardResource.Refusal(QueryRefusals.Category.POLICY_DENY, 5),
            new DashboardResource.Refusal(QueryRefusals.Category.UNPARSEABLE, 4));
  }

  @Test
  @DisplayName("an unprotected sensitive table somebody read is the loudest thing on the page")
  void attentionOrder() {
    List<Attention> attention =
        DashboardResource.attention(
            NOW,
            List.of(
                table("pg.s.x.closed", 0, 0, 0, 0),
                table("pg.s.x.open", 0, 1, 0, 0),
                table("pg.s.x.read", 0, 1, 0, 2),
                table("pg.s.x.masked", 1, 1, 0, 9)),
            new DashboardQuery.GrantPicture(
                5,
                2,
                List.of(
                    new DashboardQuery.EndingGrant(
                        "analyst_a", "USER", "pg.s.x.read", NOW.plus(Duration.ofDays(1)), "request")),
                1,
                3,
                4),
            new DashboardQuery.Requests(2, 0, 1, NOW.minus(Duration.ofDays(5)), 3, 1, 0, 0, 4.5),
            new DashboardQuery.Health(
                1, 1, Map.of("APPLIED", 2, "DRIFTED", 1, "FAILED", 1), Map.of(), "IDLE", NOW, false));

    assertThat(kinds(attention))
        .containsExactly(
            "UNPROTECTED_READ",
            "UNPROTECTED_REACHABLE",
            "ENFORCEMENT_FAULT",
            "REQUESTS_WAITING",
            "GRANTS_ENDING",
            "UNPROTECTED_CLOSED",
            "GRANTS_UNUSED",
            "GRANTS_OPEN_ENDED");
    assertThat(attention.get(0)).isEqualTo(new Attention("UNPROTECTED_READ", Severity.HIGH, 1, "pg.s.x.read"));
    assertThat(attention.get(1).subject()).isEqualTo("pg.s.x.open");
    assertThat(attention.get(2).count()).as("drifted and failed").isEqualTo(2);
    assertThat(attention.get(3)).isEqualTo(new Attention("REQUESTS_WAITING", Severity.MEDIUM, 3, "5"));
    assertThat(attention.get(4)).isEqualTo(new Attention("GRANTS_ENDING", Severity.MEDIUM, 2, "analyst_a"));
    assertThat(attention.get(5)).isEqualTo(new Attention("UNPROTECTED_CLOSED", Severity.LOW, 1, "pg.s.x.closed"));
  }

  @Test
  @DisplayName("a quiet, healthy estate asks for nothing")
  void nothingToDo() {
    assertThat(
            DashboardResource.attention(
                NOW, List.of(table("pg.s.x.masked", 1, 1, 1, 4)), noGrants(), noRequests(), healthy()))
        .isEmpty();
  }

  @Test
  @DisplayName("requests answered within three days are not waiting too long")
  void recentRequests() {
    assertThat(
            DashboardResource.attention(
                NOW,
                List.of(),
                noGrants(),
                new DashboardQuery.Requests(1, 0, 0, NOW.minus(Duration.ofDays(2)), 1, 0, 0, 0, null),
                healthy()))
        .isEmpty();
  }

  @Test
  @DisplayName("a failed sync outranks a stale one, and no catalogue at all is not a fault")
  void sync() {
    DashboardQuery.Health failed =
        new DashboardQuery.Health(1, 1, Map.of(), Map.of(), "FAILED", NOW.minus(Duration.ofDays(9)), true);
    assertThat(DashboardResource.attention(NOW, List.of(), noGrants(), noRequests(), failed))
        .containsExactly(new Attention("SYNC_FAILED", Severity.HIGH, 1, null));

    DashboardQuery.Health stale =
        new DashboardQuery.Health(1, 1, Map.of(), Map.of(), "IDLE", NOW.minus(Duration.ofDays(3)), false);
    assertThat(kinds(DashboardResource.attention(NOW, List.of(), noGrants(), noRequests(), stale)))
        .containsExactly("SYNC_STALE");

    DashboardQuery.Health never = new DashboardQuery.Health(1, 1, Map.of(), Map.of(), null, null, false);
    assertThat(DashboardResource.attention(NOW, List.of(), noGrants(), noRequests(), never)).isEmpty();
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
