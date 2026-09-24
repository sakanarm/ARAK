package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.home.HomeLayout.Layout;
import com.mfec.dac.home.HomeLayout.LayoutView;
import com.mfec.dac.home.HomeLayout.Persona;
import com.mfec.dac.home.HomeLayoutStore;
import com.mfec.dac.home.HomeLayoutValidator;
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
import java.util.List;

/**
 * The home page, as arranged by the person reading it (M12).
 *
 * <p>Every endpoint here acts on the caller's own page and takes no principal
 * from the request. That is not an omission: a home page can hold HTML, and an
 * endpoint that let one account write another's would be a way to put markup
 * into somebody else's session — which is precisely the thing
 * {@link HomeLayoutValidator} exists to make harmless, and not something to also
 * leave a door open for.
 *
 * <p>An administrator arranges a role's page instead, through
 * {@link HomePersonaResource} (M12b). That is a starting point rather than an
 * override: it is what somebody sees until they arrange their own, and the
 * moment they do, nothing an administrator saves will move it again. So this
 * class still has no endpoint that writes another account's page, and the
 * reply here says which of the three sources the layout came from so the
 * console can tell somebody whether the page they are looking at is theirs.
 */
@Path("/v1/home/layout")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class HomeResource {

  private final HomeLayoutStore store;

  public HomeResource(HomeLayoutStore store) {
    this.store = store;
  }

  /**
   * The roles for whom a dashboard about governing data is the right page.
   *
   * <p>Everyone else gets the search-led one. This decides which widgets are
   * offered, not which endpoints answer — see
   * {@code HomeLayout.WidgetType#governance()}.
   */
  private static final String[] GOVERNANCE_ROLES = {
    "PLATFORM_ADMIN", "POLICY_AUTHOR", "DATA_OWNER", "AUDITOR"
  };

  /** My page: mine, else my role's, else the one this product ships with. */
  @GET
  public LayoutView layout(@Context SecurityContext security) {
    AuthenticatedUser actor = caller(security);
    return store.forPrincipal(actor.id(), governs(actor), personas(actor));
  }

  /**
   * Saves my page. What comes back is what was stored, cleaned and then
   * filtered for me -- the browser draws the reply, so it has to be the page I
   * would get on a reload rather than the page I posted.
   */
  @PUT
  public LayoutView save(Layout layout, @Context SecurityContext security) {
    if (layout == null) {
      throw new BadRequestException("No layout");
    }
    AuthenticatedUser actor = caller(security);
    try {
      return store.save(actor.id(), layout, actor.username(), governs(actor));
    } catch (HomeLayoutValidator.InvalidLayoutException e) {
      // The validator's messages are written for the person who pressed Save,
      // so they are passed through rather than replaced with a status line.
      throw new BadRequestException(e.getMessage());
    }
  }

  /**
   * Forgets my arrangement and restores whatever I inherit.
   *
   * <p>Which is my role's page if an administrator has arranged one, not
   * necessarily the built-in one. Reset means "as if I had never touched it",
   * and that is the page I would have been given.
   */
  @DELETE
  public LayoutView reset(@Context SecurityContext security) {
    AuthenticatedUser actor = caller(security);
    return store.reset(actor.id(), governs(actor), personas(actor));
  }

  private static boolean governs(AuthenticatedUser actor) {
    return actor.hasAnyRole(GOVERNANCE_ROLES);
  }

  /**
   * The personas this caller may inherit, from the roles on this request.
   *
   * <p>Read off the authenticated caller rather than looked up again, so the
   * page somebody is served can never be decided by a different set of roles
   * than the one that let them in.
   */
  private static List<Persona> personas(AuthenticatedUser actor) {
    return Persona.heldBy(actor.appRoles());
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }
}
