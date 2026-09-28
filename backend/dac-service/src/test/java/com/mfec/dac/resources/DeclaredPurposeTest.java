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

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.policy.QueryService;
import com.mfec.dac.purpose.PurposeStore;
import com.mfec.dac.purpose.PurposeStore.Refused;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A purpose declared on a query or asked about in the simulator is one the
 * register lists (FR-21): it reaches the engine as the register's key, and one
 * it does not list is a 400 before anything runs.
 */
@DisplayName("Declared purpose")
class DeclaredPurposeTest {

  private final PurposeStore register = mock(PurposeStore.class);
  private final QueryService queries = mock(QueryService.class);
  private final DecisionService decisions = mock(DecisionService.class);
  private final QueryResource query = new QueryResource(queries, null, 90, register);
  private final DecisionResource decision = new DecisionResource(decisions, register);
  private final HttpServletRequest request = mock(HttpServletRequest.class);

  private final AuthenticatedUser analyst =
      new AuthenticatedUser(
          UUID.randomUUID(), "analyst_a", "analyst_a@example.test", "analyst_a", "local",
          Set.of("REQUESTER"), List.of());

  private final String sourceId = UUID.randomUUID().toString();

  @Test
  @DisplayName("a query runs under the register's key for what was typed")
  void queryUsesTheKey() {
    when(register.declared("Fraud-Analysis")).thenReturn("fraud-analysis");
    when(queries.run(any(), anyString(), anyString(), any(), anyInt(), any(), any(), eq(false)))
        .thenReturn(null);

    try {
      query.run(
          new QueryResource.Ask(sourceId, "SELECT 1", null, null, "Fraud-Analysis", null),
          as(analyst),
          request);
    } catch (RuntimeException ignored) {
      // Only what reached the service matters here.
    }

    verify(queries)
        .run(any(), anyString(), anyString(), any(), anyInt(), any(), eq("fraud-analysis"), eq(false));
  }

  @Test
  @DisplayName("a purpose the register does not list, or has retired, is a 400 and nothing runs")
  void queryRefused() {
    when(register.declared("marketing"))
        .thenThrow(new Refused(Refused.Reason.INVALID, "marketing is not in the register of purposes"));

    assertThatThrownBy(
            () ->
                query.run(
                    new QueryResource.Ask(sourceId, "SELECT 1", null, null, "marketing", null),
                    as(analyst),
                    request))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("not in the register");
    assertThatThrownBy(
            () ->
                query.export(
                    new QueryResource.Ask(sourceId, "SELECT 1", null, null, "marketing", null),
                    as(analyst),
                    request))
        .isInstanceOf(BadRequestException.class);
    verify(queries, never()).run(any(), anyString(), anyString(), any(), anyInt(), any(), any(), eq(false));
    verify(queries, never()).export(any(), anyString(), anyString(), any(), any(), anyInt());
  }

  @Test
  @DisplayName("the simulator asks the engine about the register's key, and refuses what it lacks")
  void simulator() {
    when(register.declared("Reporting")).thenReturn("reporting");
    decision.decide(
        new DecisionResource.Ask(null, "svc.db.sch.t", null, null, "Reporting", null), as(analyst));
    ArgumentCaptor<DecisionService.Ask> asked = ArgumentCaptor.forClass(DecisionService.Ask.class);
    verify(decisions).decide(asked.capture());
    assertThat(asked.getValue().purpose()).isEqualTo("reporting");

    when(register.declared("marketing"))
        .thenThrow(new Refused(Refused.Reason.INVALID, "marketing is not in the register of purposes"));
    assertThatThrownBy(
            () ->
                decision.decide(
                    new DecisionResource.Ask(null, "svc.db.sch.t", null, null, "marketing", null),
                    as(analyst)))
        .isInstanceOf(BadRequestException.class);
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
        return true;
      }

      @Override
      public String getAuthenticationScheme() {
        return "Bearer";
      }
    };
  }
}
