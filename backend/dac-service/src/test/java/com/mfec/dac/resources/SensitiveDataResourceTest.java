package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.purpose.SensitiveData;
import com.mfec.dac.purpose.SensitiveData.Concern;
import com.mfec.dac.purpose.SensitiveData.Coverage;
import com.mfec.dac.purpose.SensitiveData.Kind;
import com.mfec.dac.purpose.SensitiveData.Label;
import com.mfec.dac.purpose.SensitiveData.Mode;
import com.mfec.dac.purpose.SensitiveData.Rule;
import com.mfec.dac.purpose.SensitiveData.Settings;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * What counts as sensitive data (FR-21, M31b): who may read the rule, who may
 * change it, and what a change has to carry before it reaches the store.
 */
@DisplayName("SensitiveDataResource")
class SensitiveDataResourceTest {

  private final SensitiveData sensitive = mock(SensitiveData.class);
  private final SensitiveDataResource resource = new SensitiveDataResource(sensitive);

  private final AuthenticatedUser admin = user("admin_a", Set.of("PLATFORM_ADMIN"));
  private final AuthenticatedUser author = user("author_a", Set.of("POLICY_AUTHOR"));
  private final AuthenticatedUser owner = user("owner_a", Set.of("DATA_OWNER"));
  private final AuthenticatedUser auditor = user("auditor_a", Set.of("AUDITOR"));
  private final AuthenticatedUser analyst = user("analyst_a", Set.of("REQUESTER"));

  private static final Label PII = new Label(Kind.CLASSIFICATION, "PII");

  private static SensitiveDataResource.Edit edit(Mode mode, String reason) {
    return new SensitiveDataResource.Edit(null, List.of(PII), List.of(), mode, reason);
  }

  @Test
  @DisplayName("everybody reads the rule, because the request form tells them of it; only an editor is told they may change it")
  void reads() {
    when(sensitive.current()).thenReturn(Rule.BUILT_IN);

    assertThat(resource.current(as(analyst)).rule()).isEqualTo(Rule.BUILT_IN);
    assertThat(resource.current(as(analyst)).canEdit()).isFalse();
    assertThat(resource.current(as(owner)).canEdit()).isFalse();
    assertThat(resource.current(as(author)).canEdit()).isTrue();
    assertThat(resource.current(as(admin)).canEdit()).isTrue();
  }

  @Test
  @DisplayName("an admin or a policy author changes it, with the built-in rule kept unless turned off and the reason trimmed")
  void changes() {
    when(sensitive.update(any(), anyString(), anyString())).thenReturn(Rule.BUILT_IN);

    assertThat(resource.update(edit(Mode.ENFORCE, "  Audit finding 12  "), as(author)).canEdit()).isTrue();

    ArgumentCaptor<Settings> wanted = ArgumentCaptor.forClass(Settings.class);
    verify(sensitive).update(wanted.capture(), eq("Audit finding 12"), eq("author_a"));
    assertThat(wanted.getValue()).isEqualTo(new Settings(true, List.of(PII), List.of(), Mode.ENFORCE));

    resource.update(
        new SensitiveDataResource.Edit(false, List.of(), List.of(), Mode.OFF, "Pause it"), as(admin));
    verify(sensitive).update(new Settings(false, List.of(), List.of(), Mode.OFF), "Pause it", "admin_a");
  }

  @Test
  @DisplayName("a data owner, an auditor or a requester may not change it")
  void editorsOnly() {
    for (AuthenticatedUser who : List.of(owner, auditor, analyst)) {
      assertThatThrownBy(() -> resource.update(edit(Mode.WARN, "r"), as(who)))
          .isInstanceOf(ForbiddenException.class);
    }
    verify(sensitive, never()).update(any(), anyString(), anyString());
  }

  @Test
  @DisplayName("a change names what happens and says why, within the length a reason may be")
  void validates() {
    assertThatThrownBy(() -> resource.update(null, as(admin))).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> resource.update(edit(null, "r"), as(admin)))
        .hasMessageContaining("off, warn or enforce");
    assertThatThrownBy(() -> resource.update(edit(Mode.WARN, "  "), as(admin)))
        .hasMessageContaining("Say why");
    assertThatThrownBy(
            () -> resource.update(edit(Mode.WARN, "x".repeat(PurposeResource.MAX_REASON + 1)), as(admin)))
        .hasMessageContaining("Keep the reason");
    verify(sensitive, never()).update(any(), anyString(), anyString());
  }

  @Test
  @DisplayName("what the store refuses -- a label in both lists, or no change at all -- comes back as a 400")
  void storeRefusals() {
    when(sensitive.update(any(), anyString(), anyString()))
        .thenThrow(new IllegalArgumentException("That is the rule already; nothing to change"));

    assertThatThrownBy(() -> resource.update(edit(Mode.WARN, "again"), as(admin)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("nothing to change");
  }

  @Test
  @DisplayName("how far a rule reaches, and what it was, is for those who govern")
  void reviewersOnly() {
    Coverage none = new Coverage(0, 0, 3, List.of(), List.of());
    when(sensitive.coverage(any())).thenReturn(none);
    when(sensitive.history()).thenReturn(List.of());

    for (AuthenticatedUser who : List.of(admin, author, owner, auditor)) {
      assertThat(resource.preview(new SensitiveDataResource.Draft(null, List.of(PII), null), as(who)))
          .isEqualTo(none);
      assertThat(resource.history(as(who))).isEmpty();
    }
    assertThatThrownBy(() -> resource.preview(null, as(analyst))).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> resource.history(as(analyst))).isInstanceOf(ForbiddenException.class);
  }

  @Test
  @DisplayName("a preview measures the draft, not the rule in force, and refuses a label without a kind or a name")
  void preview() {
    when(sensitive.coverage(any())).thenReturn(new Coverage(0, 0, 0, List.of(), List.of()));

    resource.preview(new SensitiveDataResource.Draft(false, List.of(PII), List.of()), as(author));
    ArgumentCaptor<Rule> measured = ArgumentCaptor.forClass(Rule.class);
    verify(sensitive).coverage(measured.capture());
    assertThat(measured.getValue().builtIn()).isFalse();
    assertThat(measured.getValue().include()).containsExactly(PII);

    assertThatThrownBy(
            () ->
                resource.preview(
                    new SensitiveDataResource.Draft(null, List.of(new Label(null, "PII")), null),
                    as(author)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                resource.preview(
                    new SensitiveDataResource.Draft(null, List.of(new Label(Kind.TAG, " ")), null),
                    as(author)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  @DisplayName("anybody asks whether a purpose meets sensitive data on a table, and a table has to be named")
  void check() {
    Concern concern =
        new Concern(
            Mode.WARN, "demo-pg.salesdb.sales.customer", List.of("PII.Sensitive"), "reporting",
            "Reporting", "m");
    when(sensitive.concern("demo-pg.salesdb.sales.customer", "reporting")).thenReturn(Optional.of(concern));
    when(sensitive.concern("demo-pg.salesdb.sales.orders", "reporting")).thenReturn(Optional.empty());

    assertThat(resource.check(" demo-pg.salesdb.sales.customer ", "reporting", as(analyst)).concern())
        .isEqualTo(concern);
    assertThat(resource.check("demo-pg.salesdb.sales.orders", "reporting", as(analyst)).concern()).isNull();
    assertThatThrownBy(() -> resource.check(" ", "reporting", as(analyst)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> resource.check("t", "reporting", null)).isInstanceOf(ForbiddenException.class);
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
