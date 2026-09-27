package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.auth.Stewardship;
import com.mfec.dac.enforcement.DirectAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

/**
 * Who can read a table at the source without the platform (FR-6.3.1).
 *
 * <p>Open to whoever governs the table and to auditors, the same people who see
 * the table's Access tab: this is the half of "who can reach it" that the
 * platform does not decide. It reads the source's catalogue and nothing else,
 * so it is a POST only because it opens a connection to a customer's database
 * and is written down as having done so.
 */
@Path("/v1/direct-access")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class DirectAccessResource {

  public record Target(String assetFqn) {}

  private final DirectAccessService service;

  public DirectAccessResource(DirectAccessService service) {
    this.service = service;
  }

  @POST
  @Path("/check")
  public DirectAccessService.Check check(
      Target target, @Context SecurityContext security, @Context HttpServletRequest request) {
    if (target == null || target.assetFqn() == null || target.assetFqn().isBlank()) {
      throw new BadRequestException("Say which table: send its assetFqn.");
    }
    AuthenticatedUser caller = caller(security);
    String fqn = target.assetFqn().trim();
    if (!Stewardship.oversees(caller, fqn)) {
      throw new ForbiddenException(
          "Who can read a table at the source is shown to whoever governs it and to auditors");
    }
    try {
      return service.check(fqn, caller.getName(), request == null ? null : request.getRemoteAddr());
    } catch (RuntimeException e) {
      throw EnforcementResource.translate(e);
    }
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }
}
