package com.mfec.dac.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuthFilterTest {

  private static final JwtService TOKENS =
      new JwtService(
          "a-test-signing-key-long-enough-to-be-accepted",
          "data-access-control",
          Duration.ofHours(1));

  private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

  /** Stand-in resource: one ordinary endpoint, one open to a pending change. */
  static class Endpoints {
    @Secured
    public void ordinary() {}

    @Secured
    @PasswordChangeExempt
    public void exempt() {}

    @Secured({"PLATFORM_ADMIN"})
    public void adminOnly() {}
  }

  private static AuthenticatedUser user(String... roles) {
    return new AuthenticatedUser(
        ID, "someone", "someone@example.com", "Someone", "local", Set.of(roles), List.of());
  }

  /** Runs the filter against one endpoint and returns the aborting response, or null. */
  private static Response run(String endpoint, String token, Set<UUID> pending) throws Exception {
    AuthFilter filter = new AuthFilter(TOKENS, pending::contains);
    ResourceInfo info = mock(ResourceInfo.class);
    Method method = Endpoints.class.getMethod(endpoint);
    when(info.getResourceMethod()).thenReturn(method);
    when(info.getResourceClass()).thenAnswer(inv -> Endpoints.class);
    Field field = AuthFilter.class.getDeclaredField("resourceInfo");
    field.setAccessible(true);
    field.set(filter, info);

    ContainerRequestContext request = mock(ContainerRequestContext.class);
    when(request.getHeaderString(HttpHeaders.AUTHORIZATION))
        .thenReturn(token == null ? null : "Bearer " + token);
    UriInfo uri = mock(UriInfo.class);
    when(uri.getRequestUri()).thenReturn(URI.create("https://example.test/api/v1/x"));
    when(request.getUriInfo()).thenReturn(uri);

    filter.filter(request);
    ArgumentCaptor<Response> aborted = ArgumentCaptor.forClass(Response.class);
    try {
      verify(request).abortWith(aborted.capture());
      verify(request, never()).setSecurityContext(any(SecurityContext.class));
      return aborted.getValue();
    } catch (AssertionError notAborted) {
      verify(request).setSecurityContext(any(SecurityContext.class));
      return null;
    }
  }

  @Test
  @DisplayName("an ordinary token reaches an ordinary endpoint")
  void ordinaryTokenPasses() throws Exception {
    assertThat(run("ordinary", TOKENS.issue(user()), Set.of())).isNull();
  }

  @Test
  @DisplayName("no token is a 401")
  void missingToken() throws Exception {
    assertThat(run("ordinary", null, Set.of()).getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("a token with a pending password change is refused with 403 elsewhere")
  void pendingChangeIsRefused() throws Exception {
    Response refused = run("ordinary", TOKENS.issue(user(), true), Set.of(ID));
    assertThat(refused.getStatus()).isEqualTo(403);
    assertThat(refused.getEntity().toString()).contains("choose a new password");
  }

  @Test
  @DisplayName("a pending admin token cannot use its role either")
  void pendingAdminIsRefused() throws Exception {
    assertThat(run("adminOnly", TOKENS.issue(user("PLATFORM_ADMIN"), true), Set.of(ID))
            .getStatus())
        .isEqualTo(403);
  }

  @Test
  @DisplayName("a pending token still reaches the endpoints that let it change the password")
  void exemptEndpointsStayOpen() throws Exception {
    assertThat(run("exempt", TOKENS.issue(user(), true), Set.of(ID))).isNull();
  }

  @Test
  @DisplayName("once the password is changed, the same token works everywhere")
  void sameTokenWorksAfterTheChange() throws Exception {
    String token = TOKENS.issue(user(), true);
    Set<UUID> pending = new HashSet<>(Set.of(ID));
    assertThat(run("ordinary", token, pending).getStatus()).isEqualTo(403);
    pending.clear();
    assertThat(run("ordinary", token, pending)).isNull();
  }

  @Test
  @DisplayName("the database is not asked about tokens that carry no pending mark")
  void unmarkedTokenSkipsTheLookup() throws Exception {
    AuthFilter filter =
        new AuthFilter(
            TOKENS,
            id -> {
              throw new AssertionError("looked up " + id);
            });
    ContainerRequestContext request = mock(ContainerRequestContext.class);
    when(request.getHeaderString(HttpHeaders.AUTHORIZATION))
        .thenReturn("Bearer " + TOKENS.issue(user()));
    UriInfo uri = mock(UriInfo.class);
    when(uri.getRequestUri()).thenReturn(URI.create("https://example.test/api/v1/x"));
    when(request.getUriInfo()).thenReturn(uri);
    filter.filter(request);
    verify(request).setSecurityContext(any(SecurityContext.class));
  }
}
