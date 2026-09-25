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
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
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

  @Test
  @DisplayName("a request with no caller is refused")
  void noCaller() {
    assertThatThrownBy(() -> resource.start(id, null)).isInstanceOf(ForbiddenException.class);
  }
}
