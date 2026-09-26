package com.mfec.dac.resources;

import com.mfec.dac.access.AccessQuery;
import com.mfec.dac.access.GrantStore;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.auth.Stewardship;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * may change who sees data -- and, for a data owner, only on the tables they
 * own ({@link Stewardship}).
 */
@Path("/v1/access")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class AccessResource {

  /** The widest look-ahead one call may ask for: a year. */
  static final int MAX_WITHIN_DAYS = 365;

  /** At most this many grants in one answer, which is several dashboards' worth. */
  static final int MAX_EXPIRING = 500;

  private final AccessQuery access;
  private final GrantStore grants;
  private final Clock clock;

  public AccessResource(AccessQuery access, GrantStore grants) {
    this(access, grants, Clock.systemUTC());
  }

  AccessResource(AccessQuery access, GrantStore grants, Clock clock) {
    this.access = access;
    this.grants = grants;
    this.clock = clock;
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
   * One grant that is about to end, as a dashboard shows it (M9 slice 2c).
   *
   * @param mine the grant reaches the caller, directly or through a group
   * @param mayRevoke the caller governs the table, so the card can offer to end
   *     it now rather than let it run out
   */
  public record ExpiringGrant(
      UUID id,
      String assetFqn,
      UUID principalId,
      String username,
      String displayName,
      String principalType,
      String source,
      UUID requestId,
      Instant validFrom,
      Instant validUntil,
      String grantedBy,
      boolean mine,
      boolean mayRevoke) {}

  /**
   * The grants ending within a window.
   *
   * @param now the instant the window was measured from, so a countdown on the
   *     page starts from the server's clock and not the browser's
   * @param total how many there are before {@code limit} cut the list
   */
  public record Expiring(Instant now, int withinDays, int total, List<ExpiringGrant> grants) {}

  /**
   * Who is about to lose access to which table (M9 slice 2c).
   *
   * <p>Every grant still live now that ends within {@code withinDays}, soonest
   * first. What a caller sees follows who they are: an administrator, policy
   * author or auditor sees every such grant; anyone else sees the grants that
   * reach them -- so a requester's dashboard can warn them before their access
   * lapses -- and the grants on tables they own or oversee, which are the ones
   * they can renew or end. The list of who holds what is open elsewhere to any
   * signed-in person ({@code /assets/{fqn}}), but that is one table a reader
   * chose; a list of every grant across the estate, ordered by when it runs
   * out, is a map of who to ask next week, and is kept to the people whose job
   * it is to read one.
   */
  @GET
  @Path("/grants/expiring")
  public Expiring expiring(
      @QueryParam("withinDays") @DefaultValue("14") int withinDays,
      @QueryParam("limit") @DefaultValue("100") int limit,
      @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (withinDays < 1 || withinDays > MAX_WITHIN_DAYS) {
      throw new BadRequestException(
          "withinDays must be between 1 and " + MAX_WITHIN_DAYS + "; got " + withinDays);
    }
    if (limit < 1 || limit > MAX_EXPIRING) {
      throw new BadRequestException(
          "limit must be between 1 and " + MAX_EXPIRING + "; got " + limit);
    }
    Instant now = clock.instant();
    Instant until = now.plus(Duration.ofDays(withinDays));
    Set<UUID> reachesMe = grants.reachableFrom(caller.username());
    boolean everything = Stewardship.overseesEverything(caller);
    List<ExpiringGrant> visible = new ArrayList<>();
    for (GrantStore.StoredGrant grant : grants.expiring(now, until)) {
      boolean mine = reachesMe.contains(grant.principalId());
      if (!everything && !mine && !Stewardship.oversees(caller, grant.assetFqn())) {
        continue;
      }
      visible.add(
          new ExpiringGrant(
              grant.id(),
              grant.assetFqn(),
              grant.principalId(),
              grant.username(),
              grant.displayName(),
              grant.principalType(),
              grant.source(),
              grant.requestId(),
              grant.validFrom(),
              grant.validUntil(),
              grant.grantedBy(),
              mine,
              Stewardship.governs(caller, grant.assetFqn())));
    }
    return new Expiring(
        now,
        withinDays,
        visible.size(),
        List.copyOf(visible.subList(0, Math.min(limit, visible.size()))));
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
    if (!Stewardship.governs(actor, request.assetFqn())) {
      throw new ForbiddenException(
          "You can grant only on tables you own; " + request.assetFqn() + " is not one of them");
    }
    // The request flow already refuses to let anyone decide their own request.
    // A direct grant is the same act without the paperwork, so the same line
    // holds here -- including a grant to a group the granter is in, which would
    // otherwise be the way round it.
    if (grants.reaches(actor.username(), request.principalId())) {
      throw new ForbiddenException(
          "Nobody grants themselves access; ask another owner or an administrator");
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
    GrantStore.StoredGrant existing =
        grants
            .find(id)
            .orElseThrow(() -> new NotFoundException("No grant " + id + " is outstanding"));
    if (!Stewardship.governs(actor, existing.assetFqn())) {
      throw new ForbiddenException(
          "You can revoke only on tables you own; " + existing.assetFqn() + " is not one of them");
    }
    return grants
        .revoke(id, actor.username(), why)
        .orElseThrow(
            () -> new NotFoundException("No grant " + id + " is outstanding"));
  }

  /** The body of a revocation. */
  public record RevokeRequest(String reason) {}

  /**
   * What a caller sends to change a grant.
   *
   * @param validUntil the new end; null means open-ended, as on creation
   * @param validFrom the new start; ignored once the grant has started
   */
  public record AmendRequest(Instant validFrom, Instant validUntil, String reason) {}

  /**
   * Changes a grant's window or reason.
   *
   * <p>The grant is replaced, not rewritten (see {@link GrantStore#amend}), so
   * the answer is the new grant with a new id. The same lines hold as on
   * creation: only whoever governs the table, never for oneself -- extending
   * one's own grant is granting oneself access for longer -- and a reason.
   */
  @POST
  @Path("/grants/{id}/amend")
  @Consumes(MediaType.APPLICATION_JSON)
  @Secured({"PLATFORM_ADMIN", "POLICY_AUTHOR", "DATA_OWNER"})
  public GrantStore.StoredGrant amend(
      @PathParam("id") UUID id, AmendRequest request, @Context SecurityContext security) {
    AuthenticatedUser actor = caller(security);
    String why = request == null ? null : request.reason();
    if (why == null || why.isBlank()) {
      throw new BadRequestException("Say why this grant is being changed");
    }
    GrantStore.StoredGrant existing =
        grants
            .find(id)
            .filter(grant -> grant.revokedAt() == null)
            .orElseThrow(() -> new NotFoundException("No grant " + id + " is outstanding"));
    if (!Stewardship.governs(actor, existing.assetFqn())) {
      throw new ForbiddenException(
          "You can change grants only on tables you own; "
              + existing.assetFqn()
              + " is not one of them");
    }
    if (grants.reaches(actor.username(), existing.principalId())) {
      throw new ForbiddenException(
          "Nobody changes their own access; ask another owner or an administrator");
    }
    try {
      return grants
          .amend(id, request.validFrom(), request.validUntil(), why, actor.username())
          .orElseThrow(() -> new NotFoundException("No grant " + id + " is outstanding"));
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
