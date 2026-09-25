package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.home.Rail;
import com.mfec.dac.home.Rail.Layout;
import com.mfec.dac.home.Rail.View;
import com.mfec.dac.home.RailStore;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

/**
 * My left-hand rail: which sections I keep in it, and in what order.
 *
 * <p>Like the home page, it acts on the caller's own row only and takes no
 * principal from the request. There is no administrator's version: a menu is
 * the one part of the console that is nobody's business but the person
 * reading it.
 */
@Path("/v1/me/rail")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class RailResource {

  private final RailStore store;

  public RailResource(RailStore store) {
    this.store = store;
  }

  @GET
  public View rail(@Context SecurityContext security) {
    return store.forPrincipal(caller(security).id());
  }

  @PUT
  public View save(Layout layout, @Context SecurityContext security) {
    AuthenticatedUser actor = caller(security);
    try {
      return store.save(actor.id(), layout);
    } catch (Rail.InvalidRailException e) {
      throw new BadRequestException(e.getMessage());
    }
  }

  @DELETE
  public View reset(@Context SecurityContext security) {
    return store.reset(caller(security).id());
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }
}
