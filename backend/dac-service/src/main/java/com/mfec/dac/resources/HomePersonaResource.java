package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.home.HomeLayout.Layout;
import com.mfec.dac.home.HomeLayout.Persona;
import com.mfec.dac.home.HomeLayout.PersonaLayout;
import com.mfec.dac.home.HomeLayoutStore;
import com.mfec.dac.home.HomeLayoutValidator;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.Locale;

/**
 * The home page an administrator arranges on behalf of a role (M12b).
 *
 * <p>Five pages, one per platform role, each of them the arrangement somebody
 * with that role is given on their first morning. An auditor and a requester
 * want different pages, and until now the only lever for that was the code.
 *
 * <p>This is the one place in the product where one person's layout is rendered
 * into another person's session, and the reason it is a separate resource from
 * {@link HomeResource} rather than a principal parameter on it. Two things
 * follow from that and neither is optional:
 *
 * <ul>
 *   <li>Only PLATFORM_ADMIN may write here. Arranging the POLICY_AUTHOR page is
 *       arranging the page of every policy author who has not customised their
 *       own, which is a platform act, not an authoring one.
 *   <li>The layout is cleaned by {@link HomeLayoutValidator} on the way in and
 *       again on the way out, exactly as a personal one is. An administrator's
 *       markup is not safer than anybody else's — it merely reaches more
 *       people, and a stored script in this table would run in the session of
 *       every auditor in the organisation.
 * </ul>
 *
 * <p>What is written here never overwrites an account that arranged its own
 * page. That is what makes this a default rather than a mandate, and it is why
 * an administrator editing a persona cannot be used to push a widget onto
 * somebody who has opted out of it by rearranging.
 */
@Path("/v1/home/personas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured("PLATFORM_ADMIN")
public class HomePersonaResource {

  private final HomeLayoutStore store;

  public HomePersonaResource(HomeLayoutStore store) {
    this.store = store;
  }

  /**
   * Every persona, arranged or not.
   *
   * <p>All five always, because "what does a data owner see" has an answer
   * whether or not anybody has touched it. An untouched one comes back with
   * {@code configured = false} and the built-in page, so the editor opens on
   * what those people actually see today.
   */
  @GET
  public List<PersonaLayout> personas(@Context SecurityContext security) {
    caller(security);
    return store.personas();
  }

  /** Arranges the page for one role. */
  @PUT
  @Path("/{role}")
  public PersonaLayout save(
      @PathParam("role") String role, Layout layout, @Context SecurityContext security) {
    if (layout == null) {
      throw new BadRequestException("No layout");
    }
    AuthenticatedUser actor = caller(security);
    try {
      return store.savePersona(persona(role), layout, actor.username());
    } catch (HomeLayoutValidator.InvalidLayoutException e) {
      // Same as the personal editor: the validator's messages are written for
      // the person who pressed Save, so they are passed through.
      throw new BadRequestException(e.getMessage());
    }
  }

  /**
   * Forgets the arrangement for one role.
   *
   * <p>Returns those people to the built-in page. It touches nobody who has
   * arranged their own, because they were never reading this row.
   */
  @DELETE
  @Path("/{role}")
  public PersonaLayout reset(@PathParam("role") String role, @Context SecurityContext security) {
    caller(security);
    return store.resetPersona(persona(role));
  }

  /**
   * The role named in the path, or a 404.
   *
   * <p>Not a 400: a persona for a role that does not exist is a page that does
   * not exist, and the caller asked for it by address. Case is forgiven because
   * the five names are shouted in the database and typed in a URL, and the
   * difference between those two habits should not be an error.
   */
  private static Persona persona(String role) {
    if (role == null || role.isBlank()) {
      throw new NotFoundException("No such role");
    }
    try {
      return Persona.valueOf(role.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new NotFoundException("No such role: " + role);
    }
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }
}
