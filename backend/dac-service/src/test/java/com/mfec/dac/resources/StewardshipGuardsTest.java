package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mfec.dac.access.AccessQuery;
import com.mfec.dac.access.GrantStore;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.enforcement.SecureViewService;
import com.mfec.dac.policy.DecisionService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The scope checks on every resource that acts on a table for somebody else.
 * Each used to check the role and forget the scope.
 */
class StewardshipGuardsTest {

  static final String OWNED = "pg.salesdb.sales.customer";
  static final String ELSEWHERE = "pg.hrdb.hr.salary";

  static AuthenticatedUser user(String name, Set<String> roles, List<String> scopes) {
    return new AuthenticatedUser(
        UUID.randomUUID(), name, name + "@example.test", name, "local", roles, scopes);
  }

  static final AuthenticatedUser SALES_OWNER =
      user("sales_owner", Set.of("DATA_OWNER"), List.of("pg.salesdb"));
  static final AuthenticatedUser ADMIN = user("admin", Set.of("PLATFORM_ADMIN"), List.of());
  static final AuthenticatedUser AUDITOR = user("auditor", Set.of("AUDITOR"), List.of());
  static final AuthenticatedUser REQUESTER = user("analyst", Set.of("REQUESTER"), List.of());

  static SecurityContext as(AuthenticatedUser user) {
    SecurityContext security = mock(SecurityContext.class);
    when(security.getUserPrincipal()).thenReturn(user);
    return security;
  }

  static GrantStore.StoredGrant stored(UUID id, String fqn) {
    return new GrantStore.StoredGrant(
        id, fqn, UUID.randomUUID(), "analyst", "Analyst", "user", "local", "manual", null, null,
        null, "because", "admin", Instant.now(), null, null, null);
  }

  @Nested
  class Grants {
    final GrantStore grants = mock(GrantStore.class);
    final AccessResource resource = new AccessResource(mock(AccessQuery.class), grants);
    final UUID someone = UUID.randomUUID();

    AccessResource.GrantRequest on(String fqn) {
      return new AccessResource.GrantRequest(fqn, someone, null, null, "for the audit");
    }

    @Test
    @DisplayName("a data owner grants on a table they own")
    void inScope() {
      when(grants.grant(any())).thenReturn(stored(UUID.randomUUID(), OWNED));
      assertThat(resource.grant(on(OWNED), as(SALES_OWNER)).getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("a data owner cannot grant on a table they do not own")
    void outOfScope() {
      assertThatThrownBy(() -> resource.grant(on(ELSEWHERE), as(SALES_OWNER)))
          .isInstanceOf(ForbiddenException.class);
      verify(grants, never()).grant(any());
    }

    @Test
    @DisplayName("nobody grants to themselves or a group they are in, an administrator included")
    void notToThemselves() {
      when(grants.reaches("admin", someone)).thenReturn(true);
      assertThatThrownBy(() -> resource.grant(on(OWNED), as(ADMIN)))
          .isInstanceOf(ForbiddenException.class)
          .hasMessageContaining("Nobody grants themselves access");
      verify(grants, never()).grant(any());
    }

    @Test
    @DisplayName("a data owner cannot revoke on a table they do not own")
    void revokeOutOfScope() {
      UUID id = UUID.randomUUID();
      when(grants.find(id)).thenReturn(Optional.of(stored(id, ELSEWHERE)));
      assertThatThrownBy(
              () -> resource.revoke(id, new AccessResource.RevokeRequest("left"), as(SALES_OWNER)))
          .isInstanceOf(ForbiddenException.class);
      verify(grants, never()).revoke(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a data owner revokes on a table they own")
    void revokeInScope() {
      UUID id = UUID.randomUUID();
      when(grants.find(id)).thenReturn(Optional.of(stored(id, OWNED)));
      when(grants.revoke(eq(id), eq("sales_owner"), anyString()))
          .thenReturn(Optional.of(stored(id, OWNED)));
      assertThat(resource.revoke(id, new AccessResource.RevokeRequest("left"), as(SALES_OWNER)))
          .isNotNull();
    }

    @Test
    @DisplayName("revoking a grant that does not exist is a 404, not a 403")
    void revokeMissing() {
      UUID id = UUID.randomUUID();
      when(grants.find(id)).thenReturn(Optional.empty());
      assertThatThrownBy(
              () -> resource.revoke(id, new AccessResource.RevokeRequest("left"), as(SALES_OWNER)))
          .isInstanceOf(NotFoundException.class);
    }

    AccessResource.AmendRequest longer() {
      return new AccessResource.AmendRequest(null, Instant.now().plusSeconds(86_400), "longer");
    }

    @Test
    @DisplayName("a data owner edits a grant on a table they own")
    void amendInScope() {
      UUID id = UUID.randomUUID();
      when(grants.find(id)).thenReturn(Optional.of(stored(id, OWNED)));
      when(grants.amend(eq(id), any(), any(), eq("longer"), eq("sales_owner")))
          .thenReturn(Optional.of(stored(UUID.randomUUID(), OWNED)));
      assertThat(resource.amend(id, longer(), as(SALES_OWNER))).isNotNull();
    }

    @Test
    @DisplayName("a data owner cannot edit a grant on a table they do not own")
    void amendOutOfScope() {
      UUID id = UUID.randomUUID();
      when(grants.find(id)).thenReturn(Optional.of(stored(id, ELSEWHERE)));
      assertThatThrownBy(() -> resource.amend(id, longer(), as(SALES_OWNER)))
          .isInstanceOf(ForbiddenException.class);
      verify(grants, never()).amend(any(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("nobody extends their own grant, an administrator included")
    void amendNotOwnAccess() {
      UUID id = UUID.randomUUID();
      GrantStore.StoredGrant mine = stored(id, OWNED);
      when(grants.find(id)).thenReturn(Optional.of(mine));
      when(grants.reaches("admin", mine.principalId())).thenReturn(true);
      assertThatThrownBy(() -> resource.amend(id, longer(), as(ADMIN)))
          .isInstanceOf(ForbiddenException.class)
          .hasMessageContaining("Nobody changes their own access");
      verify(grants, never()).amend(any(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("an edit needs a reason, and a revoked grant cannot be edited")
    void amendNeedsReasonAndALiveGrant() {
      UUID id = UUID.randomUUID();
      assertThatThrownBy(
              () -> resource.amend(id, new AccessResource.AmendRequest(null, null, " "), as(ADMIN)))
          .isInstanceOf(BadRequestException.class);
      GrantStore.StoredGrant gone =
          new GrantStore.StoredGrant(
              id, OWNED, UUID.randomUUID(), "analyst", "Analyst", "user", "local", "manual", null,
              null, null, "because", "admin", Instant.now(), Instant.now(), "admin", "left");
      when(grants.find(id)).thenReturn(Optional.of(gone));
      assertThatThrownBy(() -> resource.amend(id, longer(), as(ADMIN)))
          .isInstanceOf(NotFoundException.class);
    }
  }

  @Nested
  class Simulation {
    final DecisionService decisions = mock(DecisionService.class);
    final DecisionResource resource = new DecisionResource(decisions);

    DecisionResource.Ask ask(String principal, String fqn) {
      return new DecisionResource.Ask(principal, fqn, null, null, null, null);
    }

    @Test
    @DisplayName("anyone may ask about themselves")
    void self() {
      resource.decide(ask(null, ELSEWHERE), as(REQUESTER));
      verify(decisions).decide(any());
    }

    @Test
    @DisplayName("a data owner simulates somebody else only on a table they own")
    void ownerScope() {
      resource.decide(ask("analyst", OWNED), as(SALES_OWNER));
      assertThatThrownBy(() -> resource.decide(ask("analyst", ELSEWHERE), as(SALES_OWNER)))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("an auditor may simulate anyone anywhere; a requester may not")
    void auditorAndRequester() {
      resource.decide(ask("analyst", ELSEWHERE), as(AUDITOR));
      assertThatThrownBy(() -> resource.decide(ask("someone_else", OWNED), as(REQUESTER)))
          .isInstanceOf(ForbiddenException.class);
    }
  }

  @Nested
  class Enforcement {
    final SecureViewService views = mock(SecureViewService.class);
    final EnforcementResource resource = new EnforcementResource(views);

    SecureViewService.Candidate candidate(String fqn) {
      return new SecureViewService.Candidate(
          fqn, fqn, UUID.randomUUID(), "src", "POSTGRES", "SECURE_VIEW", "s", "t", null,
          "NOT_ENFORCED", null, null, null);
    }

    @Test
    @DisplayName("a data owner's list holds only the tables they own")
    @SuppressWarnings("unchecked")
    void listFiltered() {
      when(views.candidates(any(), anyInt()))
          .thenReturn(List.of(candidate(OWNED), candidate(ELSEWHERE)));
      Map<String, Object> body = resource.list(null, 200, as(SALES_OWNER));
      assertThat((List<SecureViewService.Candidate>) body.get("data"))
          .extracting(SecureViewService.Candidate::assetFqn)
          .containsExactly(OWNED);
      assertThat((List<SecureViewService.Candidate>) resource.list(null, 200, as(ADMIN)).get("data"))
          .hasSize(2);
    }

    @Test
    @DisplayName("a data owner cannot read the state of, or dry-run, a table they do not own")
    void stateAndDryRun() {
      assertThatThrownBy(() -> resource.state(ELSEWHERE, 50, as(SALES_OWNER)))
          .isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(
              () -> resource.dryRun(new EnforcementResource.Target(ELSEWHERE), as(SALES_OWNER), null))
          .isInstanceOf(ForbiddenException.class);
      verify(views, never()).dryRun(anyString(), anyString(), any());
      resource.state(OWNED, 50, as(SALES_OWNER));
      verify(views).state(OWNED);
    }
  }
}
