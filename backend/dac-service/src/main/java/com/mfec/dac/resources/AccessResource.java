package com.mfec.dac.resources;

import com.mfec.dac.access.AccessQuery;
import com.mfec.dac.access.GrantStore;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Direct grants, and the access picture of one asset (FR-7).
 *
 * <p>Separate from {@code /v1/policies} because the two answer different
 * questions for different people. A policy author asks "what does this rule
 * do across the estate"; a data owner standing on one table asks "who can read
 * this, and can I stop them". This resource is the second question.
 *
 * <p>Reading is open to any authenticated caller: an access list is not a
 * secret from the people governed by it, and hiding it is how organisations end
 * up with access nobody reviews. Writing is restricted to the three roles that
 * may change who sees data.
 */
@Path("/v1/access")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class AccessResource {

  private final AccessQuery access;
  private final GrantStore grants;

  public AccessResource(AccessQuery access, GrantStore grants) {
    this.access = access;
    this.grants = grants;
  }

  /**
   * Everyone who can reach one asset, and every grant on it.
   *
   * <p>The FQN is the last path segment and may contain dots and slashes, hence
   * the greedy template — the same one the catalog and policy resources use.
   */
  @GET
  @Path("/assets/{fqn: .+}")
  public AccessQuery.AssetAccess onAsset(@PathParam("fqn") String fqn) {
    return access.onAsset(fqn);
  }

  /**
   * What has been granted and revoked on one asset over time.
   *
   * <p>Covers grants only. Policy changes have their own version history on the
   * policy, and decision-level audit (FR-8.2) is M8 -- saying so on the page is
   * better than a tab that looks complete and is not.
   *
   * <p>Under its own prefix rather than {@code /assets/{fqn}/history}: the FQN
   * template is greedy, so a suffix after it would be ambiguous with the FQN
   * itself and would depend on Jersey's sort order to resolve.
   */
  @GET
  @Path("/history/{fqn: .+}")
  public List<GrantStore.HistoryEntry> history(
      @PathParam("fqn") String fqn, @QueryParam("limit") @DefaultValue("100") int limit) {
    return grants.historyFor(fqn, limit);
  }

  /** What the caller themselves holds, directly or through a group (FR-7.3). */
  @GET
  @Path("/mine")
  public List<GrantStore.StoredGrant> mine(@Context SecurityContext security) {
    return grants.heldBy(caller(security).username());
  }

  /** What one named person holds. Same shape as {@code /mine}. */
  @GET
  @Path("/principals/{username}")
  public List<GrantStore.StoredGrant> held(@PathParam("username") String username) {
    return grants.heldBy(username);
  }

  /**
   * What a caller sends to create a grant.
   *
   * @param validUntil null means open-ended, which is allowed and is the choice
   *     the UI has to make deliberate rather than easy
   */
  public record GrantRequest(
      String assetFqn,
      UUID principalId,
      Instant validFrom,
      Instant validUntil,
      String reason) {}

  /** Creates a grant. */
  @POST
  @Path("/grants")
  @Consumes(MediaType.APPLICATION_JSON)
  @Secured({"PLATFORM_ADMIN", "POLICY_AUTHOR", "DATA_OWNER"})
  public Response grant(GrantRequest request, @Context SecurityContext security) {
    AuthenticatedUser actor = caller(security);
    if (request == null || request.assetFqn() == null || request.assetFqn().isBlank()) {
      throw new BadRequestException("Name the asset this grant is for");
    }
    if (request.principalId() == null) {
      throw new BadRequestException("Name the person or group this grant is for");
    }
    GrantStore.StoredGrant created;
    try {
      created =
          grants.grant(
              new GrantStore.NewGrant(
                  request.assetFqn(),
                  request.principalId(),
                  request.validFrom(),
                  request.validUntil(),
                  request.reason(),
                  actor.username()));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
    return Response.status(Response.Status.CREATED).entity(created).build();
  }

  /**
   * Revokes a grant.
   *
   * <p>A reason is required here as it is on creation. A revocation without one
   * is the row that, six months on, nobody can tell apart from a mistake.
   *
   * <p>A POST rather than a DELETE because nothing is deleted: the row stays as
   * a tombstone so that "who had access last March" remains answerable, and a
   * DELETE that leaves the resource in place would be describing the wrong
   * thing. It also carries a body, which DELETE does not do reliably across
   * clients, and the reason is not optional.
   */
  @POST
  @Path("/grants/{id}/revoke")
  @Consumes(MediaType.APPLICATION_JSON)
  @Secured({"PLATFORM_ADMIN", "POLICY_AUTHOR", "DATA_OWNER"})
  public GrantStore.StoredGrant revoke(
      @PathParam("id") UUID id, RevokeRequest request, @Context SecurityContext security) {
    AuthenticatedUser actor = caller(security);
    String why = request == null ? null : request.reason();
    if (why == null || why.isBlank()) {
      throw new BadRequestException("Say why this grant is being revoked");
    }
    return grants
        .revoke(id, actor.username(), why)
        .orElseThrow(
            () -> new NotFoundException("No grant " + id + " is outstanding"));
  }

  /** The body of a revocation. */
  public record RevokeRequest(String reason) {}

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }
}
