package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.query.SavedQueryStore;
import com.mfec.dac.query.SavedQueryStore.Clean;
import com.mfec.dac.query.SavedQueryStore.Draft;
import com.mfec.dac.query.SavedQueryStore.Saved;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Saved queries in the console: open to anybody signed in, each person to their
 * own and to what others chose to share.
 *
 * <p>Nothing here runs a statement. Opening a saved query puts its text in the
 * editor; running it is {@code POST /v1/query}, as the person pressing Run.
 */
@Path("/v1/saved-queries")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class SavedQueryResource {

  private final SavedQueryStore queries;

  public SavedQueryResource(SavedQueryStore queries) {
    this.queries = queries;
  }

  /** A saved query, and whether it is the caller's to change. */
  public record Row(
      UUID id,
      String owner,
      String name,
      String description,
      UUID sourceId,
      String sql,
      boolean shared,
      boolean mine,
      Instant createdAt,
      Instant updatedAt) {}

  @GET
  public List<Row> list(@Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    return queries.list(caller.username()).stream().map(s -> row(s, caller)).toList();
  }

  @GET
  @Path("/{id}")
  public Row one(@PathParam("id") UUID id, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    return row(
        queries.find(id, caller.username()).orElseThrow(() -> new NotFoundException("No saved query " + id)),
        caller);
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response create(Draft draft, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    Clean clean = validated(draft);
    Saved saved = guarded(() -> queries.create(clean, caller.username()));
    return Response.status(Response.Status.CREATED).entity(row(saved, caller)).build();
  }

  @PUT
  @Path("/{id}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Row update(@PathParam("id") UUID id, Draft draft, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    Clean clean = validated(draft);
    return row(guarded(() -> queries.update(id, clean, caller.username())), caller);
  }

  @DELETE
  @Path("/{id}")
  public Response delete(@PathParam("id") UUID id, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    guarded(
        () -> {
          queries.delete(id, caller.username());
          return null;
        });
    return Response.noContent().build();
  }

  // --------------------------------------------------------------- plumbing

  private static Row row(Saved s, AuthenticatedUser caller) {
    return new Row(
        s.id(),
        s.owner(),
        s.name(),
        s.description(),
        s.sourceId(),
        s.sql(),
        s.shared(),
        s.owner().equalsIgnoreCase(caller.username()),
        s.createdAt(),
        s.updatedAt());
  }

  private static Clean validated(Draft draft) {
    try {
      return SavedQueryStore.validate(draft);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
  }

  private static <T> T guarded(Supplier<T> call) {
    try {
      return call.get();
    } catch (SavedQueryStore.NameTakenException e) {
      // With the query that has the name, so the console can offer to replace
      // that one rather than leave the caller guessing which it was.
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("message", e.getMessage());
      body.put("existingId", e.existingId());
      throw new WebApplicationException(
          Response.status(Response.Status.CONFLICT).entity(body).type(MediaType.APPLICATION_JSON).build());
    } catch (SavedQueryStore.NoSuchQueryException e) {
      throw new NotFoundException(e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }
}
