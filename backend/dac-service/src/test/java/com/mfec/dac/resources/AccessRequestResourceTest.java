package com.mfec.dac.resources;

import static com.mfec.dac.resources.StewardshipGuardsTest.ADMIN;
import static com.mfec.dac.resources.StewardshipGuardsTest.SALES_OWNER;
import static com.mfec.dac.resources.StewardshipGuardsTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mfec.dac.access.AccessEligibility;
import com.mfec.dac.access.AccessRequestStore;
import com.mfec.dac.access.AccessRequestStore.RequestException;
import com.mfec.dac.access.AccessReview;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the request endpoints refuse before the store is asked, and how its refusals reach HTTP. */
class AccessRequestResourceTest {

  final AccessRequestStore store = mock(AccessRequestStore.class);
  final AccessRequestResource resource = new AccessRequestResource(store, mock(AccessEligibility.class));
  final UUID id = UUID.randomUUID();

  @Test
  @DisplayName("approving does not set how long: that is for whoever configures it")
  void approveRefusesDays() {
    assertThatThrownBy(
            () -> resource.approve(id, new AccessRequestResource.Decision(30, "ok", null), as(SALES_OWNER)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("Approving does not set how long; set it when configuring the request");
    verify(store, never()).approve(any(), any(), any(), any());
  }

  @Test
  @DisplayName("an approval passes its note and stage on, and a missing body is no note")
  void approvePassesThrough() {
    resource.approve(id, new AccessRequestResource.Decision(null, "Month-end", 2), as(SALES_OWNER));
    verify(store).approve(eq(id), eq(new AccessRequestStore.Actor("sales_owner", false)), eq("Month-end"), eq(2));
    resource.approve(id, null, as(ADMIN));
    verify(store).approve(eq(id), eq(new AccessRequestStore.Actor("admin", true)), isNull(), isNull());
  }

  @Test
  @DisplayName("configuring needs to be told how")
  void completeNeedsABody() {
    assertThatThrownBy(() -> resource.complete(id, null, as(SALES_OWNER)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("Say how it was configured");
    verify(store, never()).complete(any(), any(), any());
  }

  @Test
  @DisplayName("the store's refusals become 400, 403, 404 and 409")
  void refusals() {
    when(store.start(eq(id), any())).thenThrow(new RequestException(RequestException.Kind.INVALID, "bad"));
    assertThatThrownBy(() -> resource.start(id, as(SALES_OWNER))).isInstanceOf(BadRequestException.class);

    when(store.withdraw(eq(id), any())).thenThrow(new RequestException(RequestException.Kind.FORBIDDEN, "not yours"));
    assertThatThrownBy(() -> resource.withdraw(id, as(SALES_OWNER)))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("not yours");

    when(store.reject(eq(id), any(), any(), any()))
        .thenThrow(new RequestException(RequestException.Kind.NOT_FOUND, "No request"));
    assertThatThrownBy(() -> resource.reject(id, null, as(SALES_OWNER))).isInstanceOf(NotFoundException.class);

    when(store.decline(eq(id), any(), anyString()))
        .thenThrow(new RequestException(RequestException.Kind.CONFLICT, "already configured"));
    assertThatThrownBy(
            () -> resource.decline(id, new AccessRequestResource.Decision(null, "no", null), as(SALES_OWNER)))
        .isInstanceOfSatisfying(
            WebApplicationException.class, e -> assertThat(e.getResponse().getStatus()).isEqualTo(409));
  }

  AccessRequestStore.StoredRequest request(String status, boolean mayConfigure) {
    AccessRequestStore.StoredRequest request = mock(AccessRequestStore.StoredRequest.class);
    when(request.status()).thenReturn(status);
    when(request.mayConfigure()).thenReturn(mayConfigure);
    when(request.requesterUsername()).thenReturn("analyst_a");
    return request;
  }

  final AccessReview review = mock(AccessReview.class);
  final AccessRequestResource reviewing =
      new AccessRequestResource(store, mock(AccessEligibility.class), review);

  @Test
  @DisplayName("a grant that a policy would still defeat is refused with 409, and nothing is written")
  void grantThatWouldNotOpen() {
    AccessRequestStore.StoredRequest request = request("APPROVED", true);
    when(store.find(eq(id), any())).thenReturn(request);
    when(review.grantWouldNotOpen(request)).thenReturn(Optional.of("country-deny: country is not TH"));

    assertThatThrownBy(
            () ->
                reviewing.complete(
                    id, new AccessRequestResource.Configure("GRANT", 3, null, null), as(SALES_OWNER)))
        .isInstanceOfSatisfying(
            WebApplicationException.class,
            e -> {
              assertThat(e.getResponse().getStatus()).isEqualTo(409);
              assertThat(e.getResponse().getEntity().toString())
                  .contains("Still refusing: country-deny: country is not TH", "Policy updated");
            });
    verify(store, never()).complete(any(), any(), any());
  }

  @Test
  @DisplayName("a grant that would open the table goes through; policy answers are not checked here")
  void grantThatOpens() {
    AccessRequestStore.StoredRequest request = request("IN_PROGRESS", true);
    when(store.find(eq(id), any())).thenReturn(request);
    when(review.grantWouldNotOpen(request)).thenReturn(Optional.empty());

    reviewing.complete(id, new AccessRequestResource.Configure("GRANT", 3, null, null), as(SALES_OWNER));
    verify(store).complete(eq(id), any(), any());

    reviewing.complete(
        id,
        new AccessRequestResource.Configure("POLICY_UPDATED", null, UUID.randomUUID().toString(), null),
        as(SALES_OWNER));
    verify(review).grantWouldNotOpen(request);
  }

  @Test
  @DisplayName("somebody who may not configure it now gets the store's answer, not the grant check")
  void grantCheckOnlyForTheConfigurer() {
    AccessRequestStore.StoredRequest request = request("IN_PROGRESS", false);
    when(store.find(eq(id), any())).thenReturn(request);
    when(store.complete(eq(id), any(), any()))
        .thenThrow(new RequestException(RequestException.Kind.CONFLICT, "owner_o is configuring this request"));

    assertThatThrownBy(
            () ->
                reviewing.complete(
                    id, new AccessRequestResource.Configure("GRANT", 3, null, null), as(SALES_OWNER)))
        .isInstanceOfSatisfying(
            WebApplicationException.class,
            e -> assertThat(e.getResponse().getEntity().toString()).contains("owner_o is configuring"));
    verify(review, never()).grantWouldNotOpen(any());
  }

  @Test
  @DisplayName("a review names its policy by id; anything else is 400")
  void reviewPolicyId() {
    assertThatThrownBy(() -> reviewing.review(id, "not-a-uuid", as(SALES_OWNER)))
        .isInstanceOf(BadRequestException.class);
    UUID policy = UUID.randomUUID();
    reviewing.review(id, policy.toString(), as(SALES_OWNER));
    verify(review).review(eq(id), eq(new AccessRequestStore.Actor("sales_owner", false)), eq(policy));
    reviewing.review(id, " ", as(SALES_OWNER));
    verify(review).review(eq(id), any(), isNull());

    when(review.review(eq(id), any(), isNull()))
        .thenThrow(new RequestException(RequestException.Kind.FORBIDDEN, "not for the requester"));
    assertThatThrownBy(() -> reviewing.review(id, null, as(SALES_OWNER)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  @DisplayName("a request with no caller is refused")
  void noCaller() {
    assertThatThrownBy(() -> resource.start(id, null)).isInstanceOf(ForbiddenException.class);
  }
}
