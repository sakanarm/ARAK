package com.mfec.dac.resources;

import com.mfec.dac.access.RequestTemplate;
import com.mfec.dac.access.RequestTemplate.Draft;
import com.mfec.dac.access.RequestTemplate.Stored;
import com.mfec.dac.access.RequestTemplate.Template;
import com.mfec.dac.access.RequestTemplateStore;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.auth.Stewardship;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Writing request access templates: what the request form asks, per table.
 *
 * <p>The same rights as workflows. A template for the whole organisation is a
 * platform administrator's; one on a scope belongs to whoever governs that
 * scope ({@link Stewardship#governs}). Reading the list is open to the same
 * people plus auditors. The template a table resolves to is open to anybody
 * signed in, because the form a requester fills in is rendered from it.
 */
@Path("/v1/request-templates")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class RequestTemplateResource {

  private final RequestTemplateStore templates;

  public RequestTemplateResource(RequestTemplateStore templates) {
    this.templates = templates;
  }

  /** One template, and whether the caller may change it. */
  public record Row(
      Template template,
      String createdBy,
      Instant createdAt,
      String updatedBy,
      Instant updatedAt,
      boolean canEdit) {}

  /** Every template, and the built-in one that applies when none does. */
  public record Listing(List<Row> templates, Template builtIn, boolean canCreateDefault) {}

  @GET
  public Listing list(@Context SecurityContext security) {
    AuthenticatedUser caller = reader(security);
    List<Row> rows = templates.list().stream().map(s -> row(s, mayEdit(caller, s.template().scopeFqn()))).toList();
    return new Listing(rows, RequestTemplate.builtIn(), caller.isPlatformAdmin());
  }

  /** The template a request on this table is asked on. Anybody signed in may ask. */
  @GET
  @Path("/effective/{fqn: .+}")
  public Template effective(@PathParam("fqn") String fqn, @Context SecurityContext security) {
    caller(security);
    Template template = templates.effective(fqn);
    // Which scope and labels picked it says something about the table's
    // classification to somebody who may not read the catalog entry; the form
    // needs only what to ask.
    return new Template(
        template.id(), template.name(), template.description(), null, List.of(), true, template.form());
  }

  @GET
  @Path("/{id}")
  public Row one(@PathParam("id") UUID id, @Context SecurityContext security) {
    AuthenticatedUser caller = reader(security);
    Stored s = templates.find(id).orElseThrow(() -> new NotFoundException("No request template " + id));
    return row(s, mayEdit(caller, s.template().scopeFqn()));
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response create(Draft draft, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    Draft clean = validated(draft);
    requireEdit(caller, clean.scopeFqn());
    Stored created = guarded(() -> templates.create(clean, caller.username()));
    return Response.status(Response.Status.CREATED).entity(row(created, true)).build();
  }

  @PUT
  @Path("/{id}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Row update(@PathParam("id") UUID id, Draft draft, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    Stored before = templates.find(id).orElseThrow(() -> new NotFoundException("No request template " + id));
    Draft clean = validated(draft);
    // Both ends: taking a template away from a scope changes that scope's form
    // as much as giving it to the new one does.
    requireEdit(caller, before.template().scopeFqn());
    requireEdit(caller, clean.scopeFqn());
    return row(guarded(() -> templates.update(id, clean, caller.username())), true);
  }

  @DELETE
  @Path("/{id}")
  public Response delete(@PathParam("id") UUID id, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    Stored before = templates.find(id).orElseThrow(() -> new NotFoundException("No request template " + id));
    requireEdit(caller, before.template().scopeFqn());
    guarded(
        () -> {
          templates.delete(id, caller.username());
          return null;
        });
    return Response.noContent().build();
  }

  @GET
  @Path("/{id}/history")
  public List<Map<String, Object>> history(@PathParam("id") UUID id, @Context SecurityContext security) {
    reader(security);
    templates.find(id).orElseThrow(() -> new NotFoundException("No request template " + id));
    return templates.history(id);
  }

  // --------------------------------------------------------------- plumbing

  private static Row row(Stored s, boolean canEdit) {
    return new Row(s.template(), s.createdBy(), s.createdAt(), s.updatedBy(), s.updatedAt(), canEdit);
  }

  static boolean mayEdit(AuthenticatedUser caller, String scope) {
    return scope == null ? caller.isPlatformAdmin() : Stewardship.governs(caller, scope);
  }

  private static void requireEdit(AuthenticatedUser caller, String scope) {
    if (!mayEdit(caller, scope)) {
      throw new ForbiddenException(
          scope == null
              ? "Only a platform administrator sets a template for the whole organisation"
              : "You do not govern " + scope + ", so you cannot set what its requests ask");
    }
  }

  private static Draft validated(Draft draft) {
    try {
      return RequestTemplate.validate(draft);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
  }

  private static <T> T guarded(Supplier<T> call) {
    try {
      return call.get();
    } catch (RequestTemplateStore.NameTakenException e) {
      throw new WebApplicationException(
          Response.status(Response.Status.CONFLICT)
              .entity(Map.of("message", e.getMessage()))
              .type(MediaType.APPLICATION_JSON)
              .build());
    } catch (RequestTemplateStore.NoSuchTemplateException e) {
      throw new NotFoundException(e.getMessage());
    }
  }

  /** Somebody who writes templates for some scope, or audits them. */
  private static AuthenticatedUser reader(SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (!caller.isPlatformAdmin() && !caller.hasAnyRole("POLICY_AUTHOR", "DATA_OWNER", "AUDITOR")) {
      throw new ForbiddenException("Request templates are for administrators, data owners and auditors");
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
