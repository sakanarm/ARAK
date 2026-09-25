package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.auth.Stewardship;
import com.mfec.dac.enforcement.SecureViewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
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

/**
 * Enforcement mode 5.1.2 over HTTP: review, apply and roll back a secure view
 * (FR-6.1, FR-6.4).
 *
 * <h2>Who may do what</h2>
 *
 * <p>Reading the state and running a dry run is open to the people who write
 * and own policy, because a dry run is how they find out what their policy
 * does to a real table -- a data owner on the tables they own, and only those.
 * Applying and rolling back are for platform
 * administrators only: they run DDL on a customer's database under the
 * platform's own credential, and the person who wrote a policy is not the
 * person who should be able to put it in front of production by themselves
 * (FR-2.6, separation of duty).
 *
 * <h2>What an apply carries</h2>
 *
 * <p>The id of a dry run, nothing more. The server kept what it showed; a
 * caller cannot agree to a change it described itself.
 */
@Path("/v1/enforcement/secure-views")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured({"PLATFORM_ADMIN", "POLICY_AUTHOR", "DATA_OWNER"})
public class EnforcementResource {

  public record Target(String assetFqn) {}

  public record Approval(String assetFqn, String reviewId) {}

  private final SecureViewService views;

  public EnforcementResource(SecureViewService views) {
    this.views = views;
  }

  @GET
  public Map<String, Object> list(
      @QueryParam("q") String search,
      @DefaultValue("200") @QueryParam("limit") int limit,
      @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    List<SecureViewService.Candidate> candidates =
        views.candidates(search, limit).stream()
            .filter(candidate -> Stewardship.governs(caller, candidate.assetFqn()))
            .toList();
    return Map.of("data", candidates, "populationLimit", SecureViewService.POPULATION_LIMIT);
  }

  @GET
  @Path("/state")
  public Map<String, Object> state(
      @QueryParam("fqn") String fqn,
      @DefaultValue("50") @QueryParam("limit") int limit,
      @Context SecurityContext security) {
    if (fqn == null || fqn.isBlank()) {
      throw new BadRequestException("Say which table: ?fqn=…");
    }
    requireGoverns(caller(security), fqn);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("assetFqn", fqn.trim());
    body.put("state", views.state(fqn.trim()).orElse(null));
    body.put("history", views.history(fqn.trim(), limit));
    return body;
  }

  @POST
  @Path("/dry-run")
  public SecureViewService.Preview dryRun(
      Target target, @Context SecurityContext security, @Context HttpServletRequest request) {
    AuthenticatedUser caller = caller(security);
    if (target == null || target.assetFqn() == null || target.assetFqn().isBlank()) {
      throw new BadRequestException("Send {\"assetFqn\": \"service.database.schema.table\"}");
    }
    requireGoverns(caller, target.assetFqn());
    try {
      return views.dryRun(target.assetFqn(), caller.getName(), clientIp(request));
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  @POST
  @Path("/apply")
  @Secured("PLATFORM_ADMIN")
  public SecureViewService.Outcome apply(
      Approval approval, @Context SecurityContext security, @Context HttpServletRequest request) {
    AuthenticatedUser caller = caller(security);
    if (approval == null
        || approval.assetFqn() == null
        || approval.assetFqn().isBlank()
        || approval.reviewId() == null
        || approval.reviewId().isBlank()) {
      throw new BadRequestException(
          "Send {\"assetFqn\": \"…\", \"reviewId\": \"…\"} -- the id of the dry run you read");
    }
    UUID reviewId;
    try {
      reviewId = UUID.fromString(approval.reviewId().trim());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("reviewId must be the id a dry run returned");
    }
    try {
      return views.apply(approval.assetFqn(), reviewId, caller.getName(), clientIp(request));
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  @POST
  @Path("/rollback")
  @Secured("PLATFORM_ADMIN")
  public SecureViewService.Outcome rollback(
      Target target, @Context SecurityContext security, @Context HttpServletRequest request) {
    AuthenticatedUser caller = caller(security);
    if (target == null || target.assetFqn() == null || target.assetFqn().isBlank()) {
      throw new BadRequestException("Send {\"assetFqn\": \"…\"}");
    }
    try {
      return views.rollback(target.assetFqn().trim(), caller.getName(), clientIp(request));
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  private static void requireGoverns(AuthenticatedUser caller, String fqn) {
    if (!Stewardship.governs(caller, fqn)) {
      throw new ForbiddenException(
          "You can review enforcement only on tables you own; " + fqn.trim() + " is not one of them");
    }
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (!(security.getUserPrincipal() instanceof AuthenticatedUser caller)) {
      throw new ForbiddenException("No caller on this request");
    }
    return caller;
  }

  /** The service's four refusals, as the statuses a client can act on. */
  private static RuntimeException translate(RuntimeException e) {
    Response.Status status;
    if (e instanceof SecureViewService.NotFoundException) {
      status = Response.Status.NOT_FOUND;
    } else if (e instanceof SecureViewService.ConflictException) {
      status = Response.Status.CONFLICT;
    } else if (e instanceof SecureViewService.RefusedException) {
      return new WebApplicationException(
          Response.status(422)
              .entity(Map.of("message", String.valueOf(e.getMessage())))
              .type(MediaType.APPLICATION_JSON)
              .build());
    } else if (e instanceof SecureViewService.SourceFailureException) {
      status = Response.Status.BAD_GATEWAY;
    } else {
      return e;
    }
    return new WebApplicationException(
        Response.status(status)
            .entity(Map.of("message", String.valueOf(e.getMessage())))
            .type(MediaType.APPLICATION_JSON)
            .build());
  }

  private static String clientIp(HttpServletRequest request) {
    return request == null ? null : request.getRemoteAddr();
  }
}
