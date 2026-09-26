package com.mfec.dac.resources;

import static com.mfec.dac.resources.StewardshipGuardsTest.ADMIN;
import static com.mfec.dac.resources.StewardshipGuardsTest.AUDITOR;
import static com.mfec.dac.resources.StewardshipGuardsTest.REQUESTER;
import static com.mfec.dac.resources.StewardshipGuardsTest.SALES_OWNER;
import static com.mfec.dac.resources.StewardshipGuardsTest.as;
import static com.mfec.dac.resources.StewardshipGuardsTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mfec.dac.access.AccessWorkflow;
import com.mfec.dac.access.AccessWorkflow.Draft;
import com.mfec.dac.access.AccessWorkflow.Kind;
import com.mfec.dac.access.AccessWorkflow.OnReject;
import com.mfec.dac.access.AccessWorkflow.Rule;
import com.mfec.dac.access.AccessWorkflow.Seat;
import com.mfec.dac.access.AccessWorkflow.Stage;
import com.mfec.dac.access.AccessWorkflow.Workflow;
import com.mfec.dac.access.WorkflowStore;
import com.mfec.dac.auth.AuthenticatedUser;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Who may read, design and move access request workflows. */
class AccessWorkflowResourceTest {

  static final String SALES = "pg.salesdb";
  static final String HR = "pg.hrdb";
  static final AuthenticatedUser AUTHOR = user("author", Set.of("POLICY_AUTHOR"), List.of());

  final WorkflowStore store = mock(WorkflowStore.class);
  final AccessWorkflowResource resource = new AccessWorkflowResource(store);

  static Draft draft(String scope) {
    return new Draft(
        "Sales review",
        null,
        scope,
        true,
        List.of(new Stage(1, "Owner", Rule.ANY, null, OnReject.VETO, List.of(Seat.of(Kind.ASSET_OWNERS)))),
        List.of());
  }

  static WorkflowStore.Stored stored(UUID id, String scope) {
    Draft d = AccessWorkflow.validate(draft(scope));
    return new WorkflowStore.Stored(
        new Workflow(id, d.name(), null, scope, true, d.stages(), d.configurers()),
        "admin",
        Instant.now(),
        "admin",
        Instant.now());
  }

  @Nested
  @DisplayName("reading")
  class Reading {

    @Test
    @DisplayName("administrators, authors, data owners and auditors read; a requester does not")
    void readers() {
      when(store.list()).thenReturn(List.of(stored(UUID.randomUUID(), SALES), stored(UUID.randomUUID(), null)));
      for (AuthenticatedUser reader : List.of(ADMIN, AUTHOR, SALES_OWNER, AUDITOR)) {
        assertThat(resource.list(as(reader)).workflows()).as(reader.username()).hasSize(2);
      }
      assertThatThrownBy(() -> resource.list(as(REQUESTER)))
          .isInstanceOf(ForbiddenException.class)
          .hasMessage("Access workflows are for administrators, data owners and auditors");
      assertThatThrownBy(() -> resource.one(UUID.randomUUID(), as(REQUESTER)))
          .isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(() -> resource.history(UUID.randomUUID(), as(REQUESTER)))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("each row says whether this caller may change it")
    void canEdit() {
      when(store.list()).thenReturn(List.of(stored(UUID.randomUUID(), null), stored(UUID.randomUUID(), SALES), stored(UUID.randomUUID(), HR)));
      assertThat(resource.list(as(SALES_OWNER)).workflows())
          .extracting(AccessWorkflowResource.Row::canEdit)
          .containsExactly(false, true, false);
      assertThat(resource.list(as(SALES_OWNER)).canCreateDefault()).isFalse();
      assertThat(resource.list(as(AUDITOR)).workflows())
          .extracting(AccessWorkflowResource.Row::canEdit)
          .containsOnly(false);
      assertThat(resource.list(as(ADMIN)).workflows())
          .extracting(AccessWorkflowResource.Row::canEdit)
          .containsOnly(true);
      assertThat(resource.list(as(ADMIN)).canCreateDefault()).isTrue();
      // A policy author governs every scope, but the organisation's default is an administrator's.
      assertThat(resource.list(as(AUTHOR)).workflows())
          .extracting(AccessWorkflowResource.Row::canEdit)
          .containsExactly(false, true, true);
    }

    @Test
    @DisplayName("anybody signed in may ask which workflow a table walks")
    void effective() {
      when(store.effective("pg.salesdb.sales.customer")).thenReturn(AccessWorkflow.builtIn());
      assertThat(resource.effective("pg.salesdb.sales.customer", as(REQUESTER)).name()).isEqualTo("Built-in");
    }

    @Test
    @DisplayName("an unknown workflow is a 404")
    void missing() {
      UUID id = UUID.randomUUID();
      when(store.find(id)).thenReturn(Optional.empty());
      assertThatThrownBy(() -> resource.one(id, as(ADMIN))).isInstanceOf(NotFoundException.class);
      assertThatThrownBy(() -> resource.history(id, as(ADMIN))).isInstanceOf(NotFoundException.class);
      assertThatThrownBy(() -> resource.update(id, draft(SALES), as(ADMIN))).isInstanceOf(NotFoundException.class);
      assertThatThrownBy(() -> resource.delete(id, as(ADMIN))).isInstanceOf(NotFoundException.class);
    }
  }

  @Nested
  @DisplayName("creating")
  class Creating {

    @Test
    @DisplayName("the organisation's default is an administrator's alone")
    void defaultIsAdminOnly() {
      assertThatThrownBy(() -> resource.create(draft(null), as(SALES_OWNER)))
          .isInstanceOf(ForbiddenException.class)
          .hasMessage("Only a platform administrator sets the organisation's default workflow");
      assertThatThrownBy(() -> resource.create(draft(null), as(AUTHOR)))
          .isInstanceOf(ForbiddenException.class);
      verify(store, never()).create(any(), anyString());

      when(store.create(any(), eq("admin"))).thenReturn(stored(UUID.randomUUID(), null));
      assertThat(resource.create(draft(null), as(ADMIN)).getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("a data owner designs a workflow for what they govern, and nothing else")
    void scoped() {
      when(store.create(any(), eq("sales_owner"))).thenReturn(stored(UUID.randomUUID(), SALES + ".sales"));
      assertThat(resource.create(draft(SALES + ".sales"), as(SALES_OWNER)).getStatus()).isEqualTo(201);

      assertThatThrownBy(() -> resource.create(draft(HR), as(SALES_OWNER)))
          .isInstanceOf(ForbiddenException.class)
          .hasMessage("You do not govern pg.hrdb, so you cannot set how its requests are approved");
      // Above what they own is not theirs either.
      assertThatThrownBy(() -> resource.create(draft("pg"), as(SALES_OWNER)))
          .isInstanceOf(ForbiddenException.class);
      // Nor is a name that only starts the same way.
      assertThatThrownBy(() -> resource.create(draft("pg.salesdb2"), as(SALES_OWNER)))
          .isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(() -> resource.create(draft(SALES), as(AUDITOR)))
          .isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(() -> resource.create(draft(SALES), as(REQUESTER)))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("a workflow that does not validate is a 400, before any scope check")
    void invalid() {
      Draft noStages = new Draft("x", null, SALES, true, List.of(), List.of());
      assertThatThrownBy(() -> resource.create(noStages, as(SALES_OWNER)))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("A workflow needs at least one stage");
      verify(store, never()).create(any(), anyString());
    }

    @Test
    @DisplayName("a second workflow on a scope is a 409")
    void scopeTaken() {
      when(store.create(any(), anyString())).thenThrow(new WorkflowStore.ScopeTakenException(SALES));
      assertThatThrownBy(() -> resource.create(draft(SALES), as(SALES_OWNER)))
          .isInstanceOfSatisfying(
              WebApplicationException.class, e -> assertThat(e.getResponse().getStatus()).isEqualTo(409));
    }

    @Test
    @DisplayName("what is stored is the cleaned draft")
    void cleaned() {
      when(store.create(any(), anyString())).thenReturn(stored(UUID.randomUUID(), SALES));
      resource.create(
          new Draft(
              "  Sales review ",
              null,
              " " + SALES + " ",
              null,
              List.of(new Stage(4, "Owner", Rule.ANY, null, OnReject.VETO, List.of(Seat.of(Kind.ASSET_OWNERS)))),
              null),
          as(SALES_OWNER));
      verify(store).create(eq(AccessWorkflow.validate(draft(SALES))), eq("sales_owner"));
    }
  }

  @Nested
  @DisplayName("changing")
  class Changing {

    final UUID id = UUID.randomUUID();

    @Test
    @DisplayName("moving a workflow needs both the scope it leaves and the one it goes to")
    void bothEnds() {
      when(store.find(id)).thenReturn(Optional.of(stored(id, SALES)));
      assertThatThrownBy(() -> resource.update(id, draft(HR), as(SALES_OWNER)))
          .isInstanceOf(ForbiddenException.class)
          .hasMessageContaining("pg.hrdb");

      when(store.find(id)).thenReturn(Optional.of(stored(id, HR)));
      assertThatThrownBy(() -> resource.update(id, draft(SALES), as(SALES_OWNER)))
          .isInstanceOf(ForbiddenException.class)
          .hasMessageContaining("pg.hrdb");
      verify(store, never()).update(any(), any(), anyString());

      when(store.find(id)).thenReturn(Optional.of(stored(id, SALES)));
      when(store.update(eq(id), any(), eq("sales_owner"))).thenReturn(stored(id, SALES + ".sales"));
      assertThat(resource.update(id, draft(SALES + ".sales"), as(SALES_OWNER)).canEdit()).isTrue();
    }

    @Test
    @DisplayName("a data owner cannot turn a scoped workflow into the default")
    void notIntoTheDefault() {
      when(store.find(id)).thenReturn(Optional.of(stored(id, SALES)));
      assertThatThrownBy(() -> resource.update(id, draft(null), as(SALES_OWNER)))
          .isInstanceOf(ForbiddenException.class);
      verify(store, never()).update(any(), any(), anyString());
    }

    @Test
    @DisplayName("deleting follows the scope the workflow is on")
    void delete() {
      when(store.find(id)).thenReturn(Optional.of(stored(id, HR)));
      assertThatThrownBy(() -> resource.delete(id, as(SALES_OWNER))).isInstanceOf(ForbiddenException.class);
      verify(store, never()).delete(any(), anyString());

      when(store.find(id)).thenReturn(Optional.of(stored(id, SALES)));
      assertThat(resource.delete(id, as(SALES_OWNER)).getStatus()).isEqualTo(204);
      verify(store).delete(id, "sales_owner");

      when(store.find(id)).thenReturn(Optional.of(stored(id, null)));
      assertThatThrownBy(() -> resource.delete(id, as(AUTHOR))).isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("a change that collides with another workflow's scope is a 409")
    void collides() {
      when(store.find(id)).thenReturn(Optional.of(stored(id, SALES)));
      when(store.update(eq(id), any(), anyString())).thenThrow(new WorkflowStore.ScopeTakenException(SALES + ".sales"));
      assertThatThrownBy(() -> resource.update(id, draft(SALES + ".sales"), as(ADMIN)))
          .isInstanceOfSatisfying(
              WebApplicationException.class, e -> assertThat(e.getResponse().getStatus()).isEqualTo(409));
    }
  }

  @Nested
  @DisplayName("execution history")
  class Executions {

    final WorkflowStore.Executions none = new WorkflowStore.Executions(List.of(), java.util.Map.of(), 0);

    @Test
    @DisplayName("a scope's requests are its governors', an administrator's and an auditor's")
    void scoped() {
      UUID id = UUID.randomUUID();
      when(store.find(id)).thenReturn(Optional.of(stored(id, SALES)));
      when(store.executions(eq(id), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
          .thenReturn(none);
      for (AuthenticatedUser reader : List.of(ADMIN, AUTHOR, SALES_OWNER, AUDITOR)) {
        assertThat(resource.executions(id, null, 50, 0, as(reader))).as(reader.username()).isSameAs(none);
      }
      UUID hr = UUID.randomUUID();
      when(store.find(hr)).thenReturn(Optional.of(stored(hr, HR)));
      assertThatThrownBy(() -> resource.executions(hr, null, 50, 0, as(SALES_OWNER)))
          .isInstanceOf(ForbiddenException.class)
          .hasMessage("You do not govern " + HR + ", so its requests are not yours to list");
      assertThatThrownBy(() -> resource.executions(id, null, 50, 0, as(REQUESTER)))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("the default's and the built-in one's cover everything, so only oversight sees them")
    void everything() {
      UUID id = UUID.randomUUID();
      when(store.find(id)).thenReturn(Optional.of(stored(id, null)));
      when(store.executions(any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
          .thenReturn(none);
      assertThat(resource.executions(id, null, 50, 0, as(AUDITOR))).isSameAs(none);
      assertThat(resource.builtInExecutions(null, 50, 0, as(ADMIN))).isSameAs(none);
      verify(store).executions(null, null, 50, 0);
      for (AuthenticatedUser other : List.of(AUTHOR, SALES_OWNER)) {
        assertThatThrownBy(() -> resource.executions(id, null, 50, 0, as(other)))
            .as(other.username())
            .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> resource.builtInExecutions(null, 50, 0, as(other)))
            .as(other.username())
            .isInstanceOf(ForbiddenException.class);
      }
    }

    @Test
    @DisplayName("a status filter is one of the request statuses, in any case")
    void status() {
      UUID id = UUID.randomUUID();
      when(store.find(id)).thenReturn(Optional.of(stored(id, SALES)));
      resource.executions(id, " pending ", 20, 40, as(ADMIN));
      verify(store).executions(id, "PENDING", 20, 40);
      assertThatThrownBy(() -> resource.executions(id, "LOST", 50, 0, as(ADMIN)))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("Unknown request status LOST");
      assertThatThrownBy(() -> resource.executions(UUID.randomUUID(), null, 50, 0, as(ADMIN)))
          .isInstanceOf(NotFoundException.class);
    }
  }

  @Test
  @DisplayName("no caller at all is refused")
  void noCaller() {
    assertThatThrownBy(() -> resource.effective("x", null)).isInstanceOf(ForbiddenException.class);
  }
}
