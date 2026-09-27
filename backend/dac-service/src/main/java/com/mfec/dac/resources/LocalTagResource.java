package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.auth.Stewardship;
import com.mfec.dac.catalog.LocalTagStore;
import com.mfec.dac.catalog.LocalTagStore.LocalTag;
import com.mfec.dac.catalog.LocalTagStore.Refused;
import com.mfec.dac.policy.PolicyBindingMaterializer;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Tagging a table or its columns in ARAK, for a tag OpenMetadata has but
 * nobody has attached there yet (FR-1.7).
 *
 * <p>Attaching a tag can put a mask on a column, so it takes the same right as
 * granting on the table: whoever governs it ({@link Stewardship#governs}), with
 * a reason, audited. The table's policy bindings are re-resolved before the
 * answer goes back, so the next query already sees the change.
 */
@Path("/v1/local-tags")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class LocalTagResource {

  private final LocalTagStore tags;
  private final PolicyBindingMaterializer materializer;

  public LocalTagResource(LocalTagStore tags, PolicyBindingMaterializer materializer) {
    this.tags = tags;
    this.materializer = materializer;
  }

  /** What to attach, or take off, and why. */
  public record Change(String targetFqn, String tagFqn, String reason) {}

  /** The tags attached in ARAK on a table and its columns, and whether the caller may change them. */
  public record Listing(boolean canEdit, List<LocalTag> tags) {}

  @GET
  public Listing list(@QueryParam("asset") String assetFqn, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (assetFqn == null || assetFqn.isBlank()) {
      throw new BadRequestException("Say which table: ?asset=<fqn>");
    }
    String asset = assetFqn.trim();
    return new Listing(Stewardship.governs(caller, asset), tags.forAsset(asset));
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response add(Change change, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    Change clean = validated(change);
    String asset = requireGoverned(caller, clean.targetFqn());
    LocalTag added = guarded(() -> tags.add(clean.targetFqn(), clean.tagFqn(), clean.reason(), caller.username()));
    materializer.refresh(List.of(asset));
    return Response.status(Response.Status.CREATED).entity(added).build();
  }

  /** A POST rather than a DELETE: taking a tag off needs a reason, and a DELETE has no body to carry one. */
  @POST
  @Path("/remove")
  @Consumes(MediaType.APPLICATION_JSON)
  public LocalTag remove(Change change, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    Change clean = validated(change);
    String asset = requireGoverned(caller, clean.targetFqn());
    LocalTag removed =
        guarded(() -> tags.remove(clean.targetFqn(), clean.tagFqn(), clean.reason(), caller.username()));
    materializer.refresh(List.of(asset));
    return removed;
  }

  // --------------------------------------------------------------- plumbing

  private String requireGoverned(AuthenticatedUser caller, String targetFqn) {
    String asset =
        tags.assetOf(targetFqn)
            .orElseThrow(() -> new NotFoundException("No current table, view or column " + targetFqn));
    if (!Stewardship.governs(caller, asset)) {
      throw new ForbiddenException("You do not govern " + asset + ", so you cannot tag it");
    }
    return asset;
  }

  private static Change validated(Change change) {
    if (change == null) {
      throw new BadRequestException("Say what to tag, with which tag, and why");
    }
    String target = trimmed(change.targetFqn());
    String tag = trimmed(change.tagFqn());
    String reason = trimmed(change.reason());
    if (target == null || tag == null) {
      throw new BadRequestException("targetFqn and tagFqn are required");
    }
    if (reason == null) {
      throw new BadRequestException("A reason is required: it is what the audit trail says");
    }
    if (reason.length() > 1000) {
      throw new BadRequestException("Keep the reason under 1000 characters");
    }
    return new Change(target, tag, reason);
  }

  private static String trimmed(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static <T> T guarded(Supplier<T> call) {
    try {
      return call.get();
    } catch (Refused e) {
      switch (e.kind()) {
        case NOT_FOUND -> throw new NotFoundException(e.getMessage());
        case INVALID -> throw new BadRequestException(e.getMessage());
        default ->
            throw new WebApplicationException(
                Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("message", e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
      }
    } catch (org.jdbi.v3.core.statement.UnableToExecuteStatementException e) {
      // Two people attaching the same tag at once: the unique key decides.
      if (e.getCause() instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
        throw new WebApplicationException(
            Response.status(Response.Status.CONFLICT)
                .entity(Map.of("message", "That tag is already attached there"))
                .type(MediaType.APPLICATION_JSON)
                .build());
      }
      throw e;
    }
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }
}
