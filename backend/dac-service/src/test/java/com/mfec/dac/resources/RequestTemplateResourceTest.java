package com.mfec.dac.resources;

import static com.mfec.dac.resources.StewardshipGuardsTest.ADMIN;
import static com.mfec.dac.resources.StewardshipGuardsTest.AUDITOR;
import static com.mfec.dac.resources.StewardshipGuardsTest.REQUESTER;
import static com.mfec.dac.resources.StewardshipGuardsTest.SALES_OWNER;
import static com.mfec.dac.resources.StewardshipGuardsTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mfec.dac.access.RequestTemplate;
import com.mfec.dac.access.RequestTemplate.Draft;
import com.mfec.dac.access.RequestTemplate.Form;
import com.mfec.dac.access.RequestTemplate.Stored;
import com.mfec.dac.access.RequestTemplate.Template;
import com.mfec.dac.access.RequestTemplateStore;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.purpose.PurposeStore;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Who may read and write request templates, and what a requester learns from one. */
class RequestTemplateResourceTest {

  static final String SALES = "pg.salesdb";
  static final String HR = "pg.hrdb";

  final RequestTemplateStore store = mock(RequestTemplateStore.class);
  final RequestTemplateResource resource = new RequestTemplateResource(store);

  static final Form FORM =
      new Form(List.of("Audit"), true, List.of(7), 7, 30, false, "Ticket", true, 10, "Say which audit.");

  static Draft draft(String scope) {
    return new Draft("PII", null, scope, List.of("PII"), true, FORM);
  }

  static Stored stored(UUID id, String scope) {
    return new Stored(
        new Template(id, "PII", null, scope, List.of("PII.Sensitive"), true, FORM),
        "admin",
        Instant.now(),
        "admin",
        Instant.now());
  }

  @Test
  @DisplayName("administrators, data owners and auditors read the list; a requester does not")
  void readers() {
    when(store.list()).thenReturn(List.of(stored(UUID.randomUUID(), SALES)));
    for (AuthenticatedUser reader : List.of(ADMIN, SALES_OWNER, AUDITOR)) {
      assertThat(resource.list(as(reader)).templates()).as(reader.username()).hasSize(1);
    }
    assertThatThrownBy(() -> resource.list(as(REQUESTER))).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> resource.history(UUID.randomUUID(), as(REQUESTER)))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  @DisplayName("a requester gets the form for a table, but not which labels or scope picked it")
  void effective() {
    UUID id = UUID.randomUUID();
    when(store.effective("pg.salesdb.s.customer")).thenReturn(stored(id, SALES).template());
    Template seen = resource.effective("pg.salesdb.s.customer", as(REQUESTER));
    assertThat(seen.form()).isEqualTo(FORM);
    assertThat(seen.name()).isEqualTo("PII");
    assertThat(seen.scopeFqn()).isNull();
    assertThat(seen.matchFacets()).isEmpty();
  }

  @Test
  @DisplayName("the organisation-wide template is an administrator's; a scoped one is its steward's")
  void writers() {
    when(store.create(any(), anyString())).thenReturn(stored(UUID.randomUUID(), SALES));
    assertThat(resource.create(draft(SALES), as(SALES_OWNER)).getStatus()).isEqualTo(201);
    assertThatThrownBy(() -> resource.create(draft(null), as(SALES_OWNER)))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> resource.create(draft(HR), as(SALES_OWNER)))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> resource.create(draft(SALES), as(AUDITOR)))
        .isInstanceOf(ForbiddenException.class);
    assertThat(resource.create(draft(null), as(ADMIN)).getStatus()).isEqualTo(201);
  }

  @Test
  @DisplayName("moving a template off a scope the caller does not govern is refused")
  void moving() {
    UUID id = UUID.randomUUID();
    when(store.find(id)).thenReturn(Optional.of(stored(id, HR)));
    assertThatThrownBy(() -> resource.update(id, draft(SALES), as(SALES_OWNER)))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> resource.delete(id, as(SALES_OWNER)))
        .isInstanceOf(ForbiddenException.class);
    verify(store, never()).update(eq(id), any(), anyString());
    verify(store, never()).delete(eq(id), anyString());
  }

  @Test
  @DisplayName("an invalid template is a 400 and a taken name a 409")
  void errors() {
    Draft bad =
        new Draft(
            "x", null, null, List.of(),
            true, new Form(List.of(), false, List.of(), 7, 30, true, null, false, 1, null));
    assertThatThrownBy(() -> resource.create(bad, as(ADMIN))).isInstanceOf(BadRequestException.class);
    when(store.create(any(), anyString())).thenThrow(new RequestTemplateStore.NameTakenException("PII"));
    assertThatThrownBy(() -> resource.create(draft(null), as(ADMIN)))
        .isInstanceOf(WebApplicationException.class)
        .satisfies(e -> assertThat(((WebApplicationException) e).getResponse().getStatus()).isEqualTo(409));
    assertThat(RequestTemplate.builtIn().form().allowUntilRevoked()).isTrue();
  }

  @Test
  @DisplayName("a template offers listed purposes; the ones it already offered stay whatever the register says")
  void purposes() {
    PurposeStore register = mock(PurposeStore.class);
    RequestTemplateResource checked = new RequestTemplateResource(store, register);
    doThrow(new PurposeStore.Refused(PurposeStore.Refused.Reason.INVALID, "\"Audit\" is not in the register"))
        .when(register)
        .requireListed(eq(List.of("Audit")), eq(List.of()), anyString());
    assertThatThrownBy(() -> checked.create(draft(null), as(ADMIN)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("not in the register");
    verify(store, never()).create(any(), anyString());

    UUID id = UUID.randomUUID();
    when(store.find(id)).thenReturn(Optional.of(stored(id, null)));
    when(store.update(eq(id), any(), anyString())).thenReturn(stored(id, null));
    checked.update(id, draft(null), as(ADMIN));
    verify(register).requireListed(List.of("Audit"), List.of("Audit"), "a request template");
  }
}
