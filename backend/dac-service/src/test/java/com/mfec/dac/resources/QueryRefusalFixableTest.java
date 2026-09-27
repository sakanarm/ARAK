package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.policy.QueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which refusals offer "Fix with AI" (M26).
 *
 * <p>The console offers a corrected statement only when the statement itself
 * failed. A refusal a policy made names the owner instead, and must never be
 * answered with a rewording -- so the flag is checked here, where the refusal
 * becomes the body the console reads.
 */
@DisplayName("Query refusals — fixable")
class QueryRefusalFixableTest {

  private final QueryService queries = mock(QueryService.class);
  private final QueryResource resource = new QueryResource(queries);
  private final HttpServletRequest request = mock(HttpServletRequest.class);

  private final AuthenticatedUser analyst =
      new AuthenticatedUser(
          UUID.randomUUID(),
          "analyst_a",
          "analyst_a@example.test",
          "analyst_a",
          "local",
          Set.of("REQUESTER"),
          List.of());

  @SuppressWarnings("unchecked")
  private Map<String, Object> refusalFor(QueryService.RejectedException rejection) {
    when(queries.run(any(), anyString(), anyString(), anyString(), anyInt(), any(), any(), anyBoolean()))
        .thenThrow(rejection);
    WebApplicationException thrown =
        catchThrowableOfType(
            WebApplicationException.class,
            () ->
                resource.run(
                    new QueryResource.Ask(
                        UUID.randomUUID().toString(), "SELECT 1", null, null, null, null),
                    as(analyst),
                    request));
    assertThat(thrown.getResponse().getStatus()).isEqualTo(403);
    return (Map<String, Object>) thrown.getResponse().getEntity();
  }

  @Test
  @DisplayName("an error from the source is fixable")
  void sourceError() {
    Map<String, Object> body =
        refusalFor(
            new QueryService.RejectedException(
                "The source rejected the enforced statement: column \"emial\" does not exist",
                null,
                true));
    assertThat(body).containsEntry("fixable", true);
  }

  @Test
  @DisplayName("a refusal with no statement to blame is not")
  void plainRefusal() {
    Map<String, Object> body =
        refusalFor(new QueryService.RejectedException("Data source x is disabled"));
    assertThat(body).containsEntry("fixable", false);
  }

  @Test
  @DisplayName("a policy's denial is never fixable, whatever it was built with")
  void denial() {
    Map<String, Object> body =
        refusalFor(
            new QueryService.RejectedException(
                "Access to demo-pg.salesdb.sales.customer is denied",
                "demo-pg.salesdb.sales.customer",
                true));
    assertThat(body)
        .containsEntry("fixable", false)
        .containsEntry("assetFqn", "demo-pg.salesdb.sales.customer");
  }

  private SecurityContext as(Principal who) {
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
        return true;
      }

      @Override
      public String getAuthenticationScheme() {
        return "Bearer";
      }
    };
  }
}
