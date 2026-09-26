package com.mfec.dac.resources;

import com.mfec.dac.access.AccessWorkflow;
import com.mfec.dac.access.AccessWorkflow.Draft;
import com.mfec.dac.access.AccessWorkflow.Workflow;
import com.mfec.dac.access.WorkflowStore;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.auth.Stewardship;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Designing access request workflows (M9 slice 2a).
 *
 * <p>The organisation's default -- a workflow with no scope -- is a platform
 * administrator's. A workflow on a scope belongs to whoever governs that scope
 * ({@link Stewardship#governs}), so a data owner of {@code prod.Sales} designs
 * how requests on their tables are approved and nobody else's. Moving a
 * workflow to another scope needs both.
 *
 * <p>Reading is open to the same people plus auditors: a workflow says who
 * approves what, which is exactly what an auditor asks.
 */
@Path("/v1/access-workflows")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class AccessWorkflowResource {

  private final WorkflowStore workflows;

  public AccessWorkflowResource(WorkflowStore workflows) {
    this.workflows = workflows;
  }

  /** One workflow, and whether the caller may change it. */
  public record Row(
      Workflow workflow,
      String createdBy,
      Instant createdAt,
      String updatedBy,
      Instant updatedAt,
      boolean canEdit) {}

  /** Every workflow, the default first, and the built-in one that applies when none does. */
  public record Listing(List<Row> workflows, Workflow builtIn, boolean canCreateDefault) {}

  @GET
  public Listing list(@Context SecurityContext security) {
    AuthenticatedUser caller = reader(security);
    List<Row> rows =
        workflows.list().stream()
            .map(
                s ->
                    new Row(
                        s.workflow(),
                        s.createdBy(),
                        s.createdAt(),
                        s.updatedBy(),
                        s.updatedAt(),
                        mayEdit(caller, s.workflow().scopeFqn())))
            .toList();
    return new Listing(rows, AccessWorkflow.builtIn(), caller.isPlatformAdmin());
  }

  /** The workflow a request on this table walks. Anybody signed in may ask. */
  @GET
  @Path("/effective/{fqn: .+}")
  public Workflow effective(@PathParam("fqn") String fqn, @Context SecurityContext security) {
    caller(security);
    return workflows.effective(fqn);
  }

  @GET
  @Path("/{id}")
  public Row one(@PathParam("id") UUID id, @Context SecurityContext security) {
    AuthenticatedUser caller = reader(security);
    WorkflowStore.Stored s = workflows.find(id).orElseThrow(() -> new NotFoundException("No access workflow " + id));
    return new Row(
        s.workflow(), s.createdBy(), s.createdAt(), s.updatedBy(), s.updatedAt(),
        mayEdit(caller, s.workflow().scopeFqn()));
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response create(Draft draft, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    Draft clean = validated(draft);
    requireEdit(caller, clean.scopeFqn());
    WorkflowStore.Stored created = guarded(() -> workflows.create(clean, caller.username()));
    return Response.status(Response.Status.CREATED)
        .entity(
            new Row(
                created.workflow(), created.createdBy(), created.createdAt(),
                created.updatedBy(), created.updatedAt(), true))
        .build();
  }

  @PUT
  @Path("/{id}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Row update(@PathParam("id") UUID id, Draft draft, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    WorkflowStore.Stored before =
        workflows.find(id).orElseThrow(() -> new NotFoundException("No access workflow " + id));
    Draft clean = validated(draft);
    // Both ends: taking a workflow away from a scope is changing that scope's
    // approvals as much as giving one to the new scope is.
    requireEdit(caller, before.workflow().scopeFqn());
    requireEdit(caller, clean.scopeFqn());
    WorkflowStore.Stored after = guarded(() -> workflows.update(id, clean, caller.username()));
    return new Row(
        after.workflow(), after.createdBy(), after.createdAt(), after.updatedBy(), after.updatedAt(), true);
  }

  @DELETE
  @Path("/{id}")
  public Response delete(@PathParam("id") UUID id, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    WorkflowStore.Stored before =
        workflows.find(id).orElseThrow(() -> new NotFoundException("No access workflow " + id));
    requireEdit(caller, before.workflow().scopeFqn());
    guarded(
        () -> {
          workflows.delete(id, caller.username());
          return null;
        });
    return Response.noContent().build();
  }

  @GET
  @Path("/{id}/history")
  public List<Map<String, Object>> history(@PathParam("id") UUID id, @Context SecurityContext security) {
    reader(security);
    workflows.find(id).orElseThrow(() -> new NotFoundException("No access workflow " + id));
    return workflows.history(id);
  }

  /**
   * The requests that walked a workflow: its execution history.
   *
   * <p>Narrower than reading the workflow. Whoever governs its scope sees the
   * requests on it, and a platform administrator or an auditor sees any; the
   * organisation's default and the built-in one cover every table, so only
   * those two see theirs. A data owner reading the default to copy it does not
   * thereby see every request in the organisation.
   */
  @GET
  @Path("/{id}/requests")
  public WorkflowStore.Executions executions(
      @PathParam("id") UUID id,
      @QueryParam("status") String status,
      @QueryParam("limit") @DefaultValue("50") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @Context SecurityContext security) {
    AuthenticatedUser caller = reader(security);
    WorkflowStore.Stored s = workflows.find(id).orElseThrow(() -> new NotFoundException("No access workflow " + id));
    requireOversight(caller, s.workflow().scopeFqn());
    return workflows.executions(id, statusOf(status), limit, offset);
  }

  /** The built-in workflow's execution history: requests on tables nothing else covered. */
  @GET
  @Path("/built-in/requests")
  public WorkflowStore.Executions builtInExecutions(
      @QueryParam("status") String status,
      @QueryParam("limit") @DefaultValue("50") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @Context SecurityContext security) {
    requireOversight(reader(security), null);
    return workflows.executions(null, statusOf(status), limit, offset);
  }

  static final Set<String> STATUSES =
      Set.of("PENDING", "APPROVED", "IN_PROGRESS", "COMPLETED", "REJECTED", "WITHDRAWN");

  private static String statusOf(String status) {
    if (status == null || status.isBlank()) return null;
    String upper = status.trim().toUpperCase(java.util.Locale.ROOT);
    if (!STATUSES.contains(upper)) {
      throw new BadRequestException("Unknown request status " + status);
    }
    return upper;
  }

  private static void requireOversight(AuthenticatedUser caller, String scope) {
    if (caller.isPlatformAdmin() || caller.hasAnyRole("AUDITOR")) return;
    if (scope != null && Stewardship.governs(caller, scope)) return;
    throw new ForbiddenException(
        scope == null
            ? "Only a platform administrator or an auditor sees every request this workflow ran"
            : "You do not govern " + scope + ", so its requests are not yours to list");
  }

  // --------------------------------------------------------------- plumbing

  static boolean mayEdit(AuthenticatedUser caller, String scope) {
    return scope == null ? caller.isPlatformAdmin() : Stewardship.governs(caller, scope);
  }

  private static void requireEdit(AuthenticatedUser caller, String scope) {
    if (!mayEdit(caller, scope)) {
      throw new ForbiddenException(
          scope == null
              ? "Only a platform administrator sets the organisation's default workflow"
              : "You do not govern " + scope + ", so you cannot set how its requests are approved");
    }
  }

  private static Draft validated(Draft draft) {
    try {
      return AccessWorkflow.validate(draft);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
  }

  private static <T> T guarded(Supplier<T> call) {
    try {
      return call.get();
    } catch (WorkflowStore.ScopeTakenException e) {
      throw new WebApplicationException(
          Response.status(Response.Status.CONFLICT)
              .entity(Map.of("message", e.getMessage()))
              .type(MediaType.APPLICATION_JSON)
              .build());
    } catch (WorkflowStore.NoSuchWorkflowException e) {
      throw new NotFoundException(e.getMessage());
    }
  }

  /** Somebody who designs workflows for some scope, or audits them. */
  private static AuthenticatedUser reader(SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (!caller.isPlatformAdmin()
        && !caller.hasAnyRole("POLICY_AUTHOR", "DATA_OWNER", "AUDITOR")) {
      throw new ForbiddenException("Access workflows are for administrators, data owners and auditors");
    }
    return caller;
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }
}
