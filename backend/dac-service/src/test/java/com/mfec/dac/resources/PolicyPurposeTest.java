package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.policy.ImpactAnalysis;
import com.mfec.dac.policy.PolicyBindingMaterializer;
import com.mfec.dac.policy.PolicyOverview;
import com.mfec.dac.policy.PolicyStore;
import com.mfec.dac.purpose.PurposeStore;
import com.mfec.dac.purpose.PurposeStore.Refused;
import com.mfec.dac.schema.entity.policy.Policy;
import io.dropwizard.jackson.Jackson;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A policy names only purposes the register lists (FR-21), except the ones it
 * already named, which an edit keeps whatever the register now says.
 */
@DisplayName("PolicyResource — purposes")
class PolicyPurposeTest {

  private final PolicyStore policies = mock(PolicyStore.class);
  private final PurposeStore register = mock(PurposeStore.class);
  private final PolicyResource resource =
      new PolicyResource(
          policies,
          mock(PolicyBindingMaterializer.class),
          mock(PolicyOverview.class),
          mock(ImpactAnalysis.class),
          register);
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final ObjectMapper json = Jackson.newObjectMapper();

  private final AuthenticatedUser author =
      new AuthenticatedUser(
          UUID.randomUUID(), "author_a", "author_a@example.test", "author_a", "local",
          Set.of("POLICY_AUTHOR"), List.of());

  private Policy document(String... purposes) throws Exception {
    return json.readValue(
        "{\"name\":\"fraud-desk\",\"displayName\":\"Fraud desk\",\"policyType\":\"SUBSCRIPTION\","
            + "\"scopeLevel\":\"ORG\",\"effect\":\"ALLOW\","
            + "\"selector\":{\"condition\":{\"facet\":\"classifications\",\"operator\":\"contains\","
            + "\"value\":\"PII\"}},"
            + "\"subject\":{\"context\":{\"purpose\":"
            + json.writeValueAsString(List.of(purposes))
            + "}}}",
        Policy.class);
  }

  @Test
  @DisplayName("a new policy naming a purpose the register lacks is a 400, and nothing is saved")
  void create() throws Exception {
    doThrow(new Refused(Refused.Reason.INVALID, "\"marketing\" is not in the register of purposes"))
        .when(register)
        .requireListed(eq(List.of("marketing")), eq(List.of()), anyString());

    assertThatThrownBy(() -> resource.create(document("marketing"), as(author), request))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("not in the register");
    verify(policies, never()).create(any(), anyString(), any());
  }

  @Test
  @DisplayName("an edit checks the new purposes against the ones the policy already had")
  void update() throws Exception {
    UUID id = UUID.randomUUID();
    when(policies.find(id))
        .thenReturn(
            Optional.of(
                new PolicyStore.StoredPolicy(
                    id, document("legacy-audit"), "DRAFT", "prod", 3, "author_a", "author_a",
                    Instant.now())));
    doThrow(new Refused(Refused.Reason.INVALID, "\"marketing\" is not in the register of purposes"))
        .when(register)
        .requireListed(any(), any(), anyString());

    assertThatThrownBy(
            () ->
                resource.update(
                    id, document("legacy-audit", "marketing"), 3, "widen", as(author), request))
        .isInstanceOf(BadRequestException.class);
    verify(register)
        .requireListed(List.of("legacy-audit", "marketing"), List.of("legacy-audit"), "a policy");
    verify(policies, never()).update(any(), any(), anyInt(), anyString(), any(), any());
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
