package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.catalog.LocalVocabularyStore;
import com.mfec.dac.catalog.LocalVocabularyStore.Kind;
import com.mfec.dac.catalog.LocalVocabularyStore.Refused;
import com.mfec.dac.catalog.LocalVocabularyStore.Value;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Classifications and tags made in ARAK: who may make them, and what a name
 * has to look like before it reaches the store.
 */
@DisplayName("LocalVocabularyResource")
class LocalVocabularyResourceTest {

  private final LocalVocabularyStore store = mock(LocalVocabularyStore.class);
  private final LocalVocabularyResource resource = new LocalVocabularyResource(store);

  private final AuthenticatedUser admin = user("admin_a", Set.of("PLATFORM_ADMIN"));
  private final AuthenticatedUser author = user("author_a", Set.of("POLICY_AUTHOR"));
  private final AuthenticatedUser owner = user("owner_a", Set.of("DATA_OWNER"));
  private final AuthenticatedUser analyst = user("analyst_a", Set.of("REQUESTER"));

  private static Value value(Kind kind, String fqn, String classification) {
    return new Value(
        kind, fqn, fqn.substring(fqn.lastIndexOf('.') + 1), classification, null, "Means x",
        false, false, "local", "author_a");
  }

  @Test
  @DisplayName("an admin or a policy author may make vocabulary; a table's owner may not")
  void permission() {
    assertThat(resource.permission(as(admin)).canEdit()).isTrue();
    assertThat(resource.permission(as(author)).canEdit()).isTrue();
    assertThat(resource.permission(as(owner)).canEdit()).isFalse();
    assertThat(resource.permission(as(analyst)).canEdit()).isFalse();
  }

  @Test
  @DisplayName("a classification is made under the caller's name, with its words tidied")
  void createsAClassification() {
    Value made = value(Kind.CLASSIFICATION, "Retention", null);
    when(store.createClassification("Retention", null, "How long we keep it", true, "author_a"))
        .thenReturn(made);

    Response response =
        resource.createClassification(
            new LocalVocabularyResource.NewClassification(
                "  Retention ", " ", " How long we keep it ", true),
            as(author));

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getEntity()).isEqualTo(made);
  }

  @Test
  @DisplayName("a tag is made under a classification, which may be OpenMetadata's")
  void createsATag() {
    Value made = value(Kind.TAG, "PII.Payroll", "PII");
    when(store.createTag("PII", "Payroll", "Payroll", "Pay and bank details", "admin_a"))
        .thenReturn(made);

    Response response =
        resource.createTag(
            new LocalVocabularyResource.NewTag(" PII ", "Payroll", "Payroll", "Pay and bank details"),
            as(admin));

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getEntity()).isEqualTo(made);
  }

  @Test
  @DisplayName("somebody without the right is refused, and nothing is written")
  void refusesSomebodyWithoutTheRight() {
    for (AuthenticatedUser who : List.of(owner, analyst)) {
      assertThatThrownBy(
              () ->
                  resource.createClassification(
                      new LocalVocabularyResource.NewClassification("X", null, "x", false), as(who)))
          .isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(
              () ->
                  resource.createTag(new LocalVocabularyResource.NewTag("PII", "X", null, "x"), as(who)))
          .isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(
              () ->
                  resource.update(
                      new LocalVocabularyResource.Change(Kind.TAG, "PII.X", null, null, true), as(who)))
          .isInstanceOf(ForbiddenException.class);
    }
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("a name is one FQN segment with a letter in it; a description is required")
  void validatesNames() {
    for (String bad : List.of("", "  ", "PII.Extra", "say \"hi\"", "tab\there", "---", "x".repeat(65))) {
      assertThatThrownBy(
              () ->
                  resource.createTag(new LocalVocabularyResource.NewTag("PII", bad, null, "x"), as(author)))
          .as(bad)
          .isInstanceOf(BadRequestException.class);
    }
    assertThatThrownBy(
            () ->
                resource.createClassification(
                    new LocalVocabularyResource.NewClassification("Retention", null, " ", false),
                    as(author)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("description");
    assertThatThrownBy(
            () ->
                resource.createTag(new LocalVocabularyResource.NewTag(" ", "Payroll", null, "x"), as(author)))
        .isInstanceOf(BadRequestException.class);
    assertThat(LocalVocabularyResource.name("ข้อมูลลูกค้า")).isEqualTo("ข้อมูลลูกค้า");
    verify(store, never()).createTag(anyString(), anyString(), any(), anyString(), anyString());
    verify(store, never())
        .createClassification(anyString(), any(), anyString(), anyBoolean(), anyString());
  }

  @Test
  @DisplayName("the store's refusals keep their meaning: missing is 404, taken is 409, not ours is 400")
  void mapsRefusals() {
    when(store.createTag(eq("Nope"), anyString(), any(), anyString(), anyString()))
        .thenThrow(new Refused(Refused.Reason.NOT_FOUND, "No classification Nope"));
    when(store.createClassification(eq("PII"), any(), anyString(), anyBoolean(), anyString()))
        .thenThrow(new Refused(Refused.Reason.CONFLICT, "PII already exists"));
    when(store.update(eq(Kind.TAG), eq("PII.Sensitive"), any(), any(), any(), anyString()))
        .thenThrow(new Refused(Refused.Reason.INVALID, "PII.Sensitive comes from OpenMetadata"));

    assertThatThrownBy(
            () -> resource.createTag(new LocalVocabularyResource.NewTag("Nope", "X", null, "x"), as(author)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                resource.createClassification(
                    new LocalVocabularyResource.NewClassification("PII", null, "x", false), as(author)))
        .isInstanceOf(WebApplicationException.class)
        .satisfies(e -> assertThat(((WebApplicationException) e).getResponse().getStatus()).isEqualTo(409));
    assertThatThrownBy(
            () ->
                resource.update(
                    new LocalVocabularyResource.Change(Kind.TAG, "PII.Sensitive", null, null, true),
                    as(author)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("OpenMetadata");
  }

  @Test
  @DisplayName("a change passes on only what was asked: a blank display name clears it, null leaves it")
  void updates() {
    Value after = value(Kind.TAG, "Retention.Short", "Retention");
    when(store.update(Kind.TAG, "Retention.Short", "", null, true, "author_a")).thenReturn(after);

    assertThat(
            resource.update(
                new LocalVocabularyResource.Change(Kind.TAG, " Retention.Short ", " ", null, true),
                as(author)))
        .isEqualTo(after);
    verify(store).update(eq(Kind.TAG), eq("Retention.Short"), eq(""), isNull(), eq(true), eq("author_a"));

    assertThatThrownBy(
            () -> resource.update(new LocalVocabularyResource.Change(null, "x", null, null, null), as(author)))
        .isInstanceOf(BadRequestException.class);
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
