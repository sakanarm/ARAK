package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.purpose.PurposeStore;
import com.mfec.dac.purpose.PurposeStore.Details;
import com.mfec.dac.purpose.PurposeStore.LegalBasis;
import com.mfec.dac.purpose.PurposeStore.Purpose;
import com.mfec.dac.purpose.PurposeStore.Refused;
import com.mfec.dac.purpose.PurposeStore.Status;
import com.mfec.dac.purpose.PurposeStore.Usage;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The register of purposes (FR-21): who may read it, who may change it, and
 * what a purpose has to look like before it reaches the store.
 */
@DisplayName("PurposeResource")
class PurposeResourceTest {

  private final PurposeStore store = mock(PurposeStore.class);
  private final PurposeResource resource = new PurposeResource(store);

  private final AuthenticatedUser admin = user("admin_a", Set.of("PLATFORM_ADMIN"));
  private final AuthenticatedUser author = user("author_a", Set.of("POLICY_AUTHOR"));
  private final AuthenticatedUser owner = user("owner_a", Set.of("DATA_OWNER"));
  private final AuthenticatedUser auditor = user("auditor_a", Set.of("AUDITOR"));
  private final AuthenticatedUser analyst = user("analyst_a", Set.of("REQUESTER"));

  private static Purpose purpose(String key, String name, Status status) {
    Instant now = Instant.parse("2026-09-28T03:00:00Z");
    return new Purpose(
        key, name, null, null, false, null, null, status, "system", now, "system", now);
  }

  @Test
  @DisplayName("everybody reads the register, because everybody picks from it; only an editor is told they may change it")
  void list() {
    when(store.list()).thenReturn(List.of(purpose("reporting", "Reporting", Status.ACTIVE)));

    assertThat(resource.list(as(analyst)).purposes()).extracting(Purpose::key).containsExactly("reporting");
    assertThat(resource.list(as(analyst)).canEdit()).isFalse();
    assertThat(resource.list(as(owner)).canEdit()).isFalse();
    assertThat(resource.list(as(author)).canEdit()).isTrue();
    assertThat(resource.list(as(admin)).canEdit()).isTrue();
  }

  @Test
  @DisplayName("an admin or a policy author lists a purpose, with the key lower-cased and the details trimmed")
  void creates() {
    when(store.create(anyString(), any(), anyString()))
        .thenReturn(purpose("credit-review", "Credit review", Status.ACTIVE));

    Response made =
        resource.create(
            new PurposeResource.NewPurpose(
                " Credit-Review ", "  Credit review ", "  ", LegalBasis.CONTRACT, true, " Risk ", 30),
            as(author));

    assertThat(made.getStatus()).isEqualTo(201);
    verify(store)
        .create(
            "credit-review",
            new Details("Credit review", null, LegalBasis.CONTRACT, true, "Risk", 30),
            "author_a");
  }

  @Test
  @DisplayName("a data owner, an auditor or a requester may not change the register")
  void editorsOnly() {
    PurposeResource.NewPurpose ask =
        new PurposeResource.NewPurpose("x", "X", null, null, null, null, null);
    for (AuthenticatedUser who : List.of(owner, auditor, analyst)) {
      assertThatThrownBy(() -> resource.create(ask, as(who))).isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(() -> resource.retire("x", new PurposeResource.Why("r"), as(who)))
          .isInstanceOf(ForbiddenException.class);
    }
    verify(store, never()).create(anyString(), any(), anyString());
  }

  @Test
  @DisplayName("a key is a lower-case slug, and a purpose needs a name people can read")
  void validates() {
    for (String bad : List.of("", "  ", "-starts-with-dash", "has space", "ทดสอบ", "x".repeat(64))) {
      assertThatThrownBy(
              () ->
                  resource.create(
                      new PurposeResource.NewPurpose(bad, "Name", null, null, null, null, null),
                      as(admin)))
          .as(bad)
          .isInstanceOf(BadRequestException.class);
    }
    assertThatThrownBy(
            () ->
                resource.create(
                    new PurposeResource.NewPurpose("ok", " ", null, null, null, null, null), as(admin)))
        .hasMessageContaining("name is required");
    assertThatThrownBy(
            () ->
                resource.create(
                    new PurposeResource.NewPurpose("ok", "a\u0007b", null, null, null, null, null),
                    as(admin)))
        .hasMessageContaining("control characters");
    assertThatThrownBy(
            () ->
                resource.create(
                    new PurposeResource.NewPurpose("ok", "Ok", null, null, null, null, 366), as(admin)))
        .hasMessageContaining("between 1 and 365");
    assertThatThrownBy(() -> resource.create(null, as(admin))).isInstanceOf(BadRequestException.class);
    verify(store, never()).create(anyString(), any(), anyString());
  }

  @Test
  @DisplayName("retiring or reinstating says why, and the reason reaches the store")
  void needsAReason() {
    assertThatThrownBy(() -> resource.retire("support", new PurposeResource.Why("  "), as(author)))
        .hasMessageContaining("Say why");
    assertThatThrownBy(() -> resource.reinstate("support", null, as(author)))
        .hasMessageContaining("Say why");
    assertThatThrownBy(
            () -> resource.retire("support", new PurposeResource.Why("x".repeat(501)), as(author)))
        .hasMessageContaining("500");

    when(store.retire("support", "Merged into reporting", "author_a"))
        .thenReturn(purpose("support", "Support", Status.RETIRED));
    assertThat(resource.retire("support", new PurposeResource.Why(" Merged into reporting "), as(author)).status())
        .isEqualTo(Status.RETIRED);
  }

  @Test
  @DisplayName("what the store refuses comes back as 404, 400 or 409")
  void refusals() {
    when(store.update(eq("nothing"), any(), anyString()))
        .thenThrow(new Refused(Refused.Reason.NOT_FOUND, "No purpose nothing"));
    when(store.update(eq("reporting"), any(), anyString()))
        .thenThrow(new Refused(Refused.Reason.CONFLICT, "The purpose support is already called Support"));
    when(store.retire(eq("support"), anyString(), anyString()))
        .thenThrow(new Refused(Refused.Reason.CONFLICT, "Support is already retired"));

    PurposeResource.Edit edit = new PurposeResource.Edit("Support", null, null, null, null, null);
    assertThatThrownBy(() -> resource.update("nothing", edit, as(admin))).isInstanceOf(NotFoundException.class);
    WebApplicationException taken =
        catchThrowableOfType(
            () -> resource.update("reporting", edit, as(admin)), WebApplicationException.class);
    assertThat(taken.getResponse().getStatus()).isEqualTo(409);
    assertThat(taken.getResponse().getEntity()).isEqualTo(Map.of("message", "The purpose support is already called Support"));
    WebApplicationException twice =
        catchThrowableOfType(
            () -> resource.retire("support", new PurposeResource.Why("r"), as(admin)),
            WebApplicationException.class);
    assertThat(twice.getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  @DisplayName("where purposes are used is for those who govern, and splits the register from what it lacks")
  void usage() {
    when(store.list())
        .thenReturn(
            List.of(
                purpose("fraud-analysis", "Fraud analysis", Status.ACTIVE),
                purpose("support", "Support", Status.RETIRED)));
    Map<String, Usage> all = new LinkedHashMap<>();
    all.put("fraud-analysis", new Usage("Fraud-Analysis", 2, 0, 1, 5));
    all.put("marketing", new Usage("marketing", 1, 1, 0, 0));
    all.put("empty", new Usage("empty", 0, 0, 0, 0));
    when(store.usage()).thenReturn(all);

    PurposeResource.Uses uses = resource.usage(as(auditor));

    assertThat(uses.listed()).containsOnlyKeys("fraud-analysis", "support");
    assertThat(uses.listed().get("fraud-analysis").recentQueries()).isEqualTo(5);
    assertThat(uses.listed().get("support").any()).isFalse();
    assertThat(uses.unlisted()).extracting(Usage::value).containsExactly("marketing");

    assertThatThrownBy(() -> resource.usage(as(analyst))).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> resource.history("support", as(analyst))).isInstanceOf(ForbiddenException.class);
  }

  @Test
  @DisplayName("the history of a purpose that is not there is a 404")
  void history() {
    when(store.find("nothing")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> resource.history("nothing", as(owner))).isInstanceOf(NotFoundException.class);

    when(store.find("support")).thenReturn(Optional.of(purpose("support", "Support", Status.ACTIVE)));
    when(store.history("support")).thenReturn(List.of());
    assertThat(resource.history("support", as(owner))).isEmpty();
  }

  private static AuthenticatedUser user(String name, Set<String> roles) {
    return new AuthenticatedUser(
        UUID.randomUUID(), name, name + "@example.test", name, "local", roles, List.of());
  }

  private static SecurityContext as(Principal who) {
    return new SecurityContext() {
      @Override
      public Principal getUserPrincipal() {
        return who;
      }

      @Override
      public boolean isUserInRole(String role) {
        return false;
      }

      @Override
      public boolean isSecure() {
        return false;
      }

      @Override
      public String getAuthenticationScheme() {
        return "Bearer";
      }
    };
  }
}
