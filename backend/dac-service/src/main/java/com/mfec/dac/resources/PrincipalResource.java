package com.mfec.dac.resources;

import com.mfec.dac.auth.Secured;
import com.mfec.dac.identity.PrincipalQuery;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

/**
 * Reading the identity cache (FR-2.2, FR-2.4).
 *
 * <p>Read-only in Phase 1, and deliberately so: Entra is the source of truth
 * for production identity and local principals are provisioned by the bootstrap
 * and by tests. An edit here would be silently reverted by the next sync, which
 * is a worse failure than not offering the button.
 */
@Path("/v1/principals")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class PrincipalResource {

  /** The platform's own roles. Fixed by the CHECK constraint in V2, listed here for the pickers. */
  private static final List<String> APP_ROLES =
      List.of("PLATFORM_ADMIN", "POLICY_AUTHOR", "DATA_OWNER", "AUDITOR", "REQUESTER");

  private final PrincipalQuery principals;

  public PrincipalResource(PrincipalQuery principals) {
    this.principals = principals;
  }

  /**
   * The directory, narrowed.
   *
   * <p>{@code attr} repeats, once per condition, written {@code key} or
   * {@code key=value}: {@code ?attr=department=FINANCE&attr=clearance}. The
   * conditions are ANDed, because a subject rule ANDs its own attribute list —
   * so what comes back is the set of people that rule would match, which is the
   * question somebody has while writing one. Anything unreadable is ignored
   * rather than rejected: a filter is a way of looking, and a 400 in the middle
   * of typing helps nobody.
   */
  @GET
  public List<PrincipalQuery.Principal> list(
      @QueryParam("type") String principalType,
      @QueryParam("source") String source,
      @QueryParam("q") String search,
      @QueryParam("attr") List<String> attributes,
      @QueryParam("limit") @DefaultValue("200") int limit) {
    return principals.list(
        principalType,
        source,
        search,
        PrincipalQuery.AttributeFilter.parseAll(attributes),
        Math.min(limit, 500));
  }

  @GET
  @Path("/{username}")
  public PrincipalQuery.PrincipalDetail get(@PathParam("username") String username) {
    return principals
        .detail(username)
        .orElseThrow(() -> new NotFoundException("No principal named " + username));
  }

  /**
   * The attribute vocabulary, with the app roles beside it.
   *
   * <p>Both halves feed the same control in the Policy Builder — "who is this
   * about" is answered either by a role or by an attribute, and an author
   * choosing between them should see both lists without changing screens.
   */
  @GET
  @Path("/attributes")
  public Map<String, Object> attributes(
      @QueryParam("values") @DefaultValue("50") int valuesPerKey) {
    return Map.of(
        "keys", principals.attributeKeys(Math.min(valuesPerKey, 200)),
        "appRoles", APP_ROLES);
  }
}
