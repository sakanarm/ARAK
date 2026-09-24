package com.mfec.dac.resources;

import com.mfec.dac.access.AccessEligibility;
import com.mfec.dac.access.AccessRequestStore;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import jakarta.servlet.http.HttpServletRequest;
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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Asking a table's owner for access, and answering (FR-7, first slice of the
 * Phase 2 workflow).
 *
 * <p>Open to every signed-in person, because anybody can be refused and
 * anybody may ask. Who may <em>decide</em> is not a console role: it is the
 * table's owner as OpenMetadata records it, or a platform administrator, and
 * {@link AccessRequestStore} checks it on every approval rather than trusting
 * the inbox that showed the button.
 */
@Path("/v1/access-requests")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class AccessRequestResource {

  /** At most this many tables in one readability check, which is a suggestion list's worth. */
  static final int MAX_CHECK = 500;

  private final AccessRequestStore requests;
  private final AccessEligibility eligibility;

  public AccessRequestResource(AccessRequestStore requests, AccessEligibility eligibility) {
    this.requests = requests;
    this.eligibility = eligibility;
  }

  /** What a requester sends. {@code days} null means "until revoked". */
  public record Ask(
      String assetFqn,
      String sourceId,
      String reason,
      String purpose,
      Integer days,
      String attemptedSql,
      String deniedBy) {}

  /** An owner's answer. {@code days} may shorten what was asked, never extend it. */
  public record Decision(Integer days, String note) {}

  /** Which tables to check, for the caller only. */
  public record Check(List<String> assetFqns, String purpose) {}

  /**
   * Opens a request.
   *
   * <p>The engine is asked first whether a grant would open this table for this
   * person. If it would not -- a DENY, or a higher layer that refuses them -- the
   * request is refused here with the policy that is in the way, instead of
   * sitting in an owner's inbox waiting for a "yes" that changes nothing.
   */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response create(
      Ask ask, @Context SecurityContext security, @Context HttpServletRequest http) {
    AuthenticatedUser caller = caller(security);
    if (ask == null || ask.assetFqn() == null || ask.assetFqn().isBlank()) {
      throw new BadRequestException("Name the table you are asking for");
    }
    String fqn = ask.assetFqn().trim();
    UUID sourceId = null;
    if (ask.sourceId() != null && !ask.sourceId().isBlank()) {
      try {
        sourceId = UUID.fromString(ask.sourceId().trim());
      } catch (IllegalArgumentException e) {
        throw new BadRequestException("sourceId must be the UUID of a registered source");
      }
    }

    AccessEligibility.Verdict verdict =
        eligibility.check(caller.username(), fqn, clientIp(http), ask.purpose());
    if (verdict.readable()) {
      throw conflict("You can already read " + fqn + "; there is nothing to ask for");
    }
    if (!verdict.requestable()) {
      throw conflict(
          "A grant from the owner would not open "
              + fqn
              + " for you, so the request was not sent. Still refusing: "
              + verdict.blockedBy());
    }

    UUID source = sourceId;
    AccessRequestStore.StoredRequest created =
        guarded(
            () ->
                requests.create(
                    new AccessRequestStore.NewRequest(
                        fqn,
                        caller.id(),
                        caller.username(),
                        source,
                        ask.reason(),
                        ask.purpose(),
                        ask.days(),
                        ask.attemptedSql(),
                        ask.deniedBy()),
                    actor(caller)));
    return Response.status(Response.Status.CREATED).entity(created).build();
  }

  /** What the caller has asked for, newest first. */
  @GET
  @Path("/mine")
  public List<AccessRequestStore.StoredRequest> mine(
      @QueryParam("limit") @DefaultValue("100") int limit, @Context SecurityContext security) {
    return requests.madeBy(actor(caller(security)), limit);
  }

  /** What the caller may decide: tables they own, or everything for an administrator. */
  @GET
  @Path("/inbox")
  public List<AccessRequestStore.StoredRequest> inbox(
      @QueryParam("status") String status,
      @QueryParam("limit") @DefaultValue("100") int limit,
      @Context SecurityContext security) {
    return guarded(() -> requests.decidableBy(actor(caller(security)), status, limit));
  }

  /**
   * Whether the caller can read each of these tables now.
   *
   * <p>The caller's own decisions only -- there is no {@code asPrincipal} here,
   * because a suggestion list that could be asked on somebody else's behalf
   * would be a map of what they can read. The tables themselves are already on
   * the Explorer beside it, so naming them reveals nothing new.
   */
  @POST
  @Path("/check")
  @Consumes(MediaType.APPLICATION_JSON)
  public Map<String, Boolean> check(
      Check check, @Context SecurityContext security, @Context HttpServletRequest http) {
    AuthenticatedUser caller = caller(security);
    if (check == null || check.assetFqns() == null) {
      throw new BadRequestException("Send {\"assetFqns\": [\"…\"]}");
    }
    if (check.assetFqns().size() > MAX_CHECK) {
      throw new BadRequestException("Check at most " + MAX_CHECK + " tables at a time");
    }
    Map<String, Boolean> out = new LinkedHashMap<>();
    for (String fqn : check.assetFqns()) {
      if (fqn != null && !fqn.isBlank() && !out.containsKey(fqn)) {
        out.put(fqn, eligibility.readable(caller.username(), fqn, clientIp(http), check.purpose()));
      }
    }
    return out;
  }

  /** The full answer for one table: readable, requestable, or what is in the way. */
  @GET
  @Path("/eligibility/{fqn: .+}")
  public AccessEligibility.Verdict eligibility(
      @PathParam("fqn") String fqn,
      @QueryParam("purpose") String purpose,
      @Context SecurityContext security,
      @Context HttpServletRequest http) {
    return eligibility.check(caller(security).username(), fqn, clientIp(http), purpose);
  }

  /**
   * The header bell: requests for tables the caller decides, answers to the
   * caller's own, how many are new, and the counts the requests page tabs show.
   */
  @GET
  @Path("/notifications")
  public AccessRequestStore.Notices notifications(
      @QueryParam("limit") @DefaultValue("20") int limit, @Context SecurityContext security) {
    return requests.notices(actor(caller(security)), limit);
  }

  /** Marks everything so far as read, for the caller only. */
  @POST
  @Path("/notifications/seen")
  public Response notificationsSeen(@Context SecurityContext security) {
    requests.markNoticesSeen(actor(caller(security)));
    return Response.noContent().build();
  }

  /** One request, for the requester or someone who may decide it. */
  @GET
  @Path("/{id}")
  public AccessRequestStore.StoredRequest one(
      @PathParam("id") UUID id, @Context SecurityContext security) {
    return guarded(() -> requests.find(id, actor(caller(security))));
  }

  /** Approves, which writes the grant. */
  @POST
  @Path("/{id}/approve")
  @Consumes(MediaType.APPLICATION_JSON)
  public AccessRequestStore.StoredRequest approve(
      @PathParam("id") UUID id, Decision decision, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    return guarded(
        () ->
            requests.approve(
                id,
                actor(caller),
                decision == null ? null : decision.days(),
                decision == null ? null : decision.note()));
  }

  /** Rejects, with a reason the requester reads. */
  @POST
  @Path("/{id}/reject")
  @Consumes(MediaType.APPLICATION_JSON)
  public AccessRequestStore.StoredRequest reject(
      @PathParam("id") UUID id, Decision decision, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    return guarded(
        () -> requests.reject(id, actor(caller), decision == null ? null : decision.note()));
  }

  /** Takes one's own open request back. */
  @POST
  @Path("/{id}/withdraw")
  public AccessRequestStore.StoredRequest withdraw(
      @PathParam("id") UUID id, @Context SecurityContext security) {
    return guarded(() -> requests.withdraw(id, actor(caller(security))));
  }

  // --------------------------------------------------------------- plumbing

  static AccessRequestStore.Actor actor(AuthenticatedUser user) {
    return new AccessRequestStore.Actor(user.username(), user.isPlatformAdmin());
  }

  private static <T> T guarded(Supplier<T> call) {
    try {
      return call.get();
    } catch (AccessRequestStore.RequestException e) {
      throw switch (e.kind()) {
        case INVALID -> new BadRequestException(e.getMessage());
        case NOT_FOUND -> new NotFoundException(e.getMessage());
        case FORBIDDEN -> new ForbiddenException(e.getMessage());
        case CONFLICT -> conflict(e.getMessage());
      };
    }
  }

  private static WebApplicationException conflict(String message) {
    return new WebApplicationException(
        Response.status(Response.Status.CONFLICT)
            .entity(Map.of("message", message))
            .type(MediaType.APPLICATION_JSON)
            .build());
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }

  /** The connection's own address; see {@code QueryResource#clientIp} for why not X-Forwarded-For. */
  private static String clientIp(HttpServletRequest request) {
    return request == null ? null : request.getRemoteAddr();
  }
}
