package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.audit.QueryLog;
import com.mfec.dac.audit.QueryRefusals;
import com.mfec.dac.auth.AuthenticatedUser;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What of a stored query-log row each reader is shown (FR-8.3, M10). */
class AuditResourceTest {

  private static final String CUSTOMER = "pg.salesdb.sales.customer";
  private static final String SALARY = "pg.hrdb.hr.salary";

  private static AuthenticatedUser user(String name, Set<String> roles, List<String> scopes) {
    return new AuthenticatedUser(
        UUID.randomUUID(), name, name + "@example.test", name, "local", roles, scopes);
  }

  private static final AuthenticatedUser OWNER =
      user("owner_a", Set.of("DATA_OWNER"), List.of("pg.salesdb"));
  private static final AuthenticatedUser ANALYST = user("analyst_a", Set.of("REQUESTER"), List.of());

  private static QueryLog.Entry entry(
      String principal, String runBy, String outcome, String reason, List<String> assets) {
    return new QueryLog.Entry(
        7,
        Instant.parse("2026-09-20T03:00:00Z"),
        principal,
        runBy,
        UUID.randomUUID(),
        "demo-pg",
        "SELECT * FROM sales.customer",
        "EXECUTED".equals(outcome) ? "SELECT id FROM (SELECT id FROM sales.customer) c" : null,
        outcome,
        reason,
        "EXECUTED".equals(outcome) ? 12L : null,
        40,
        assets,
        false);
  }

  @Test
  @DisplayName("the person who ran it reads all of it")
  void ownRow() {
    AuditResource.QueryRow row =
        AuditResource.present(ANALYST, false, entry("analyst_a", null, "EXECUTED", null, List.of(CUSTOMER, SALARY)));
    assertThat(row.own()).isTrue();
    assertThat(row.sqlHidden()).isFalse();
    assertThat(row.originalSql()).isNotNull();
    assertThat(row.assets()).containsExactly(CUSTOMER, SALARY);
    assertThat(row.hiddenAssets()).isZero();
  }

  @Test
  @DisplayName("a row an administrator ran as somebody is that person's to read, marked with who ran it")
  void ranAsSomebody() {
    AuditResource.QueryRow asThem =
        AuditResource.present(ANALYST, false, entry("analyst_a", "admin", "EXECUTED", null, List.of(CUSTOMER)));
    assertThat(asThem.own()).isTrue();
    assertThat(asThem.runBy()).isEqualTo("admin");
    AuthenticatedUser admin = user("ADMIN", Set.of("REQUESTER"), List.of());
    assertThat(
            AuditResource.present(admin, false, entry("analyst_a", "admin", "EXECUTED", null, List.of(CUSTOMER)))
                .own())
        .as("matched case-insensitively, as the SQL does")
        .isTrue();
  }

  @Test
  @DisplayName("an owner reads the statement when every table in it is theirs")
  void ownerAllTables() {
    AuditResource.QueryRow row =
        AuditResource.present(OWNER, false, entry("analyst_a", null, "EXECUTED", null, List.of(CUSTOMER)));
    assertThat(row.own()).isFalse();
    assertThat(row.sqlHidden()).isFalse();
    assertThat(row.originalSql()).isEqualTo("SELECT * FROM sales.customer");
    assertThat(row.rowCount()).isEqualTo(12L);
  }

  @Test
  @DisplayName("an owner does not read a statement that also touched a table that is not theirs")
  void ownerMixedTables() {
    AuditResource.QueryRow row =
        AuditResource.present(OWNER, false, entry("analyst_a", null, "EXECUTED", null, List.of(CUSTOMER, SALARY)));
    assertThat(row.sqlHidden()).isTrue();
    assertThat(row.originalSql()).isNull();
    assertThat(row.rewrittenSql()).isNull();
    assertThat(row.assets()).containsExactly(CUSTOMER);
    assertThat(row.hiddenAssets()).isEqualTo(1);
  }

  @Test
  @DisplayName("an owner sees a refusal on their table and why, but not the refused statement")
  void ownerRefusal() {
    String reason = "Access to " + CUSTOMER + " is denied. finance-subscription: subject rule not satisfied";
    AuditResource.QueryRow row =
        AuditResource.present(OWNER, false, entry("analyst_a", null, "REJECTED", reason, List.of(CUSTOMER)));
    assertThat(row.category()).isEqualTo(QueryRefusals.Category.POLICY_DENY);
    assertThat(row.rejectReason()).isEqualTo(reason);
    assertThat(row.sqlHidden())
        .as("a refused statement stops at its first refusal; the rest of its text was never resolved")
        .isTrue();
  }

  @Test
  @DisplayName("a refusal whose message names a table outside the owner's is kept to its category")
  void ownerRefusalNamingAnother() {
    AuditResource.QueryRow row =
        AuditResource.present(
            OWNER,
            false,
            entry(
                "analyst_a",
                null,
                "REJECTED",
                "sales.audit_trail is not a governed asset on this source, so no policy could be applied to it",
                List.of(CUSTOMER)));
    assertThat(row.category()).isEqualTo(QueryRefusals.Category.UNGOVERNED);
    assertThat(row.rejectReason()).isNull();
  }

  @Test
  @DisplayName("an owner reads nothing of a row that touched none of their tables")
  void ownerElsewhere() {
    assertThat(AuditResource.present(OWNER, false, entry("analyst_a", null, "EXECUTED", null, List.of(SALARY))))
        .isNull();
    assertThat(AuditResource.present(OWNER, false, entry("analyst_a", null, "REJECTED", "No SQL was sent", List.of())))
        .isNull();
  }

  @Test
  @DisplayName("owning pg.salesdb is not owning pg.salesdb2")
  void segmentBoundary() {
    assertThat(
            AuditResource.present(
                OWNER, false, entry("analyst_a", null, "EXECUTED", null, List.of("pg.salesdb2.sales.customer"))))
        .isNull();
  }

  @Test
  @DisplayName("the roles that oversee everything read every row whole")
  void everything() {
    AuthenticatedUser auditor = user("compliance_a", Set.of("AUDITOR"), List.of());
    AuditResource.QueryRow row =
        AuditResource.present(auditor, true, entry("analyst_a", null, "EXECUTED", null, List.of(CUSTOMER, SALARY)));
    assertThat(row.sqlHidden()).isFalse();
    assertThat(row.assets()).containsExactly(CUSTOMER, SALARY);
    assertThat(row.own()).isFalse();
  }

  @Test
  @DisplayName("no field of a row is the address the query came from")
  void noClientIp() {
    for (var component : AuditResource.QueryRow.class.getRecordComponents()) {
      assertThat(component.getName().toLowerCase())
          .isNotEqualTo("ip")
          .doesNotContain("clientip")
          .doesNotContain("address");
    }
    for (var component : QueryLog.Entry.class.getRecordComponents()) {
      assertThat(component.getName().toLowerCase())
          .isNotEqualTo("ip")
          .doesNotContain("clientip")
          .doesNotContain("address");
    }
  }

  @Test
  @DisplayName("bad parameters are refused before the log is read")
  void validation() {
    AuditResource resource = new AuditResource(null);
    SecurityContext security = security(ANALYST);
    assertThatThrownBy(() -> call(resource, security, "SKIPPED", null, 30, null, null, 50))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> call(resource, security, null, "not-a-uuid", 30, null, null, 50))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> call(resource, security, null, null, 0, null, null, 50))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> call(resource, security, null, null, 30, "yesterday", null, 50))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () -> call(resource, security, null, null, 30, "2026-09-02T00:00:00Z", "2026-09-01T00:00:00Z", 50))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> call(resource, security, null, null, 30, null, null, 201))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> call(resource, security(null), null, null, 30, null, null, 50))
        .isInstanceOf(ForbiddenException.class);
  }

  private static AuditResource.QueryPage call(
      AuditResource resource,
      SecurityContext security,
      String outcome,
      String sourceId,
      int days,
      String from,
      String to,
      int limit) {
    return resource.queries(outcome, null, null, null, sourceId, days, from, to, null, limit, security);
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
