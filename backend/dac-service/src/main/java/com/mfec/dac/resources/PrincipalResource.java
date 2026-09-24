package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.identity.IdentityAdminStore;
import com.mfec.dac.identity.PrincipalQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The identity cache: reading it, and writing the parts we own (FR-2.2,
 * FR-2.4, FR-2.6).
 *
 * <p>Reading is open to any authenticated caller — a policy author has to see
 * who exists to write a subject rule about them. Writing is admin-only and
 * covers exactly two things, both of which belong to this platform rather than
 * to a directory it syncs from:
 *
 * <ul>
 *   <li><b>Local principals</b> — accounts for service integrations, tests and
 *       people no directory holds. Only {@code source = 'local'} rows can be
 *       touched; an Entra or OpenMetadata row edited here would be silently
 *       reverted by the next sync, which is a worse failure than not offering
 *       the button.
 *   <li><b>App roles</b> — {@code app_role_assignment} is ours outright, so a
 *       role may be granted to any principal whatever directory they came
 *       from.
 *   <li><b>Local attributes</b> — the values an ABAC rule is written against
 *       that no directory carries. A {@code source = 'local'} attribute row may
 *       be put on any principal, synced or not, because a sync writes only its
 *       own rows: the two cannot overwrite each other, and the engine reads
 *       both.
 * </ul>
 *
 * <p>A role change reaches the console immediately, because {@code /auth/me}
 * re-reads the table. It reaches <em>authorisation</em> when the affected
 * person next signs in, because the filter reads the roles baked into their
 * token. Anything that says otherwise on screen would be lying.
 */
@Path("/v1/principals")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class PrincipalResource {

  /** The platform's own roles. Fixed by the CHECK constraint in V2, listed here for the pickers. */
  private static final List<String> APP_ROLES =
      List.of("PLATFORM_ADMIN", "POLICY_AUTHOR", "DATA_OWNER", "AUDITOR", "REQUESTER");

  private final PrincipalQuery principals;
  private final IdentityAdminStore admin;

  public PrincipalResource(PrincipalQuery principals, IdentityAdminStore admin) {
    this.principals = principals;
    this.admin = admin;
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

  /**
   * One principal, with the attributes, groups and members a policy reads.
   *
   * <p>Takes an id or a username. The directory links by id because a username
   * is only unique within a source — two directories may each hold a
   * {@code Finance} — and "show me the members of this group" has to reach the
   * group that was clicked.
   */
  @GET
  @Path("/{key}")
  public PrincipalQuery.PrincipalDetail get(@PathParam("key") String key) {
    return principals
        .detail(key)
        .orElseThrow(() -> new NotFoundException("No principal named " + key));
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

  // ------------------------------------------------------------------ writing

  /** What a role change carries beyond the role itself. */
  public record RoleChange(String appRole, String scopeFqn, String reason) {

    IdentityAdminStore.RoleRequest request() {
      return new IdentityAdminStore.RoleRequest(appRole, scopeFqn);
    }
  }

  /** Enabling or disabling an account, with the reason it happened. */
  public record EnabledChange(Boolean enabled, String reason) {}

  /** A new password chosen by an administrator on somebody else's behalf. */
  public record PasswordReset(String password) {}

  /** One attribute value to give somebody, and why. */
  public record AttributeChange(String key, String value, String reason) {}

  /**
   * Every role in force, with the count of administrators beside it.
   *
   * <p>The count is here rather than derived on the client because the client
   * would have to derive it from a list it may have filtered, and the number
   * decides whether a revoke button is offered at all.
   */
  @GET
  @Path("/roles")
  @Secured({"PLATFORM_ADMIN"})
  public Map<String, Object> roles() {
    return Map.of(
        "grants", admin.grants(),
        "appRoles", APP_ROLES,
        "globalAdminCount", admin.globalAdminCount());
  }

  /** Creates a local account. */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Secured({"PLATFORM_ADMIN"})
  public Response create(
      IdentityAdminStore.NewLocalPrincipal input,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser actor = caller(security);
    UUID id = translate(() -> admin.createLocal(input, actor.username(), clientIp(request)));
    return Response.status(Response.Status.CREATED)
        .entity(principals.detail(id.toString()).orElseThrow())
        .build();
  }

  /**
   * Grants a role.
   *
   * <p>Answers 200 either way: a role somebody already holds is the state the
   * caller asked for, and two administrators pressing the same button should
   * not produce an error for the slower one.
   */
  @POST
  @Path("/{id}/roles")
  @Consumes(MediaType.APPLICATION_JSON)
  @Secured({"PLATFORM_ADMIN"})
  public Map<String, Object> grant(
      @PathParam("id") UUID id,
      RoleChange change,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser actor = caller(security);
    if (change == null) {
      throw new BadRequestException("Name the role to grant");
    }
    boolean changed =
        translate(
            () ->
                admin.grant(
                    id, change.request(), change.reason(), actor.username(), clientIp(request)));
    return Map.of("changed", changed);
  }

  /**
   * Withdraws a role.
   *
   * <p>Query parameters rather than a body: a DELETE with a body is read
   * inconsistently by proxies and clients, and what is being deleted is
   * identified by the pair (role, scope) rather than by an id the caller
   * holds.
   */
  @DELETE
  @Path("/{id}/roles")
  @Secured({"PLATFORM_ADMIN"})
  public Map<String, Object> revoke(
      @PathParam("id") UUID id,
      @QueryParam("role") String role,
      @QueryParam("scope") String scope,
      @QueryParam("reason") String reason,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser actor = caller(security);
    IdentityAdminStore.RoleRequest requested = new IdentityAdminStore.RoleRequest(role, scope);
    boolean changed =
        translate(() -> admin.revoke(id, requested, reason, actor.username(), clientIp(request)));
    return Map.of("changed", changed);
  }

  /** Turns a local account on or off. */
  @POST
  @Path("/{id}/enabled")
  @Consumes(MediaType.APPLICATION_JSON)
  @Secured({"PLATFORM_ADMIN"})
  public PrincipalQuery.PrincipalDetail setEnabled(
      @PathParam("id") UUID id,
      EnabledChange change,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser actor = caller(security);
    if (change == null || change.enabled() == null) {
      throw new BadRequestException("Say whether the account is enabled");
    }
    translate(
        () -> {
          admin.setEnabled(
              id, change.enabled(), change.reason(), actor.username(), clientIp(request));
          return null;
        });
    return principals.detail(id.toString()).orElseThrow(() -> new NotFoundException("No principal " + id));
  }

  /**
   * Gives a principal an attribute (FR-2.4).
   *
   * <p>Answers with the principal rather than with an acknowledgement, because
   * the question the screen has next is what they now carry — and the answer
   * includes the rows this call did not write, from every directory they are
   * in.
   *
   * <p>200 either way: an attribute somebody already carries is the state the
   * caller asked for. {@code changed} says which it was.
   */
  @POST
  @Path("/{id}/attributes")
  @Consumes(MediaType.APPLICATION_JSON)
  @Secured({"PLATFORM_ADMIN"})
  public Map<String, Object> addAttribute(
      @PathParam("id") UUID id,
      AttributeChange change,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser actor = caller(security);
    if (change == null) {
      throw new BadRequestException("Send an attribute to add");
    }
    boolean changed =
        translate(
            () ->
                admin.addAttribute(
                    id,
                    change.key(),
                    change.value(),
                    change.reason(),
                    actor.username(),
                    clientIp(request)));
    return Map.of(
        "changed",
        changed,
        "principal",
        principals.detail(id.toString()).orElseThrow(() -> new NotFoundException("No principal " + id)));
  }

  /**
   * Withdraws an attribute this platform holds.
   *
   * <p>Query parameters rather than a body, for the same reason the role
   * withdrawal takes them: a DELETE with a body is read inconsistently, and
   * what is being withdrawn is identified by the pair (key, value) — an
   * attribute is multi-valued, so withdrawing {@code clearance} without saying
   * which one would be ambiguous.
   */
  @DELETE
  @Path("/{id}/attributes")
  @Secured({"PLATFORM_ADMIN"})
  public Map<String, Object> removeAttribute(
      @PathParam("id") UUID id,
      @QueryParam("key") String key,
      @QueryParam("value") String value,
      @QueryParam("reason") String reason,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser actor = caller(security);
    boolean changed =
        translate(
            () ->
                admin.removeAttribute(
                    id, key, value, reason, actor.username(), clientIp(request)));
    return Map.of(
        "changed",
        changed,
        "principal",
        principals.detail(id.toString()).orElseThrow(() -> new NotFoundException("No principal " + id)));
  }

  /** Replaces a local account's password; the holder must change it at next login. */
  @POST
  @Path("/{id}/password")
  @Consumes(MediaType.APPLICATION_JSON)
  @Secured({"PLATFORM_ADMIN"})
  public Response resetPassword(
      @PathParam("id") UUID id,
      PasswordReset reset,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser actor = caller(security);
    if (reset == null) {
      throw new BadRequestException("Send a password");
    }
    translate(
        () -> {
          admin.resetPassword(id, reset.password(), actor.username(), clientIp(request));
          return null;
        });
    return Response.noContent().build();
  }

  // ------------------------------------------------------------------ plumbing

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }

  /**
   * The store speaks in two failures; HTTP has a status for each.
   *
   * <p>A refused change is a 409 and not a 400 on purpose: the input was
   * fine, the directory's current state is what says no, and the caller can
   * fix that by changing the directory rather than by changing the request.
   */
  private static <T> T translate(java.util.function.Supplier<T> action) {
    try {
      return action.get();
    } catch (IdentityAdminStore.InvalidPrincipalException e) {
      throw new BadRequestException(e.getMessage());
    } catch (IdentityAdminStore.IdentityConflictException e) {
      throw new WebApplicationException(
          Response.status(Response.Status.CONFLICT)
              .entity(Map.of("message", e.getMessage()))
              .type(MediaType.APPLICATION_JSON)
              .build());
    }
  }

  /**
   * The address the request actually arrived from.
   *
   * <p>Not {@code X-Forwarded-For}, for the same reason the query audit
   * refuses it: a header the caller sets is not evidence about the caller.
   */
  private static String clientIp(HttpServletRequest request) {
    return request == null ? null : request.getRemoteAddr();
  }
}
