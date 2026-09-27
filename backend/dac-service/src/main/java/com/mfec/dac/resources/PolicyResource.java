package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.common.Fqns;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.policy.ImpactAnalysis;
import com.mfec.dac.policy.PolicyBindingMaterializer;
import com.mfec.dac.policy.PolicyOverview;
import com.mfec.dac.policy.PolicyStore;
import com.mfec.dac.schema.entity.policy.Policy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
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
 * Authoring policies (FR-3, FR-9).
 *
 * <p>Reading is open to any authenticated caller, because a data owner has to
 * be able to see what already governs their tables before writing anything —
 * that is FR-3.1.5, and it is the screen this API exists for. Writing is
 * narrowed by role and, for a data owner, by the scope their ownership covers.
 */
@Path("/v1/policies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class PolicyResource {

  private final PolicyStore policies;
  private final PolicyBindingMaterializer materializer;
  private final PolicyOverview overview;
  private final ImpactAnalysis impact;

  public PolicyResource(
      PolicyStore policies,
      PolicyBindingMaterializer materializer,
      PolicyOverview overview,
      ImpactAnalysis impact) {
    this.policies = policies;
    this.materializer = materializer;
    this.overview = overview;
    this.impact = impact;
  }

  @GET
  public List<PolicyStore.StoredPolicy> list(
      @QueryParam("state") String lifecycleState,
      @QueryParam("type") String policyType,
      @QueryParam("scopeLevel") String scopeLevel,
      @QueryParam("q") String search,
      @QueryParam("limit") @DefaultValue("50") int limit,
      @QueryParam("offset") @DefaultValue("0") int offset) {
    return policies.list(
        lifecycleState, policyType, scopeLevel, search, Math.min(limit, 200), offset);
  }

  /**
   * How many policies the same filter matches, so the list can be paged.
   *
   * <p>Its own endpoint rather than a wrapper around the list, because every
   * other caller of the list wants an array and would have had to learn about
   * an envelope it has no use for. The total is asked once per filter and the
   * page is asked once per click, which is also the rate each of them changes.
   *
   * <p>Declared before {@code /{id}} for the reader's sake only -- JAX-RS
   * prefers a literal path over a template whatever the order -- but a reader
   * who sees {@code /{id}} first spends a moment wondering whether "count"
   * parses as a UUID.
   */
  @GET
  @Path("/count")
  public PolicyCount count(
      @QueryParam("state") String lifecycleState,
      @QueryParam("type") String policyType,
      @QueryParam("scopeLevel") String scopeLevel,
      @QueryParam("q") String search) {
    return new PolicyCount(policies.count(lifecycleState, policyType, scopeLevel, search));
  }

  /** An object rather than a bare number, so a field can be added without a new shape. */
  public record PolicyCount(int total) {}

  @GET
  @Path("/{id}")
  public PolicyStore.StoredPolicy get(@PathParam("id") UUID id) {
    return policies.find(id).orElseThrow(() -> new NotFoundException("No policy " + id));
  }

  /**
   * The history of one policy, newest first, for the diff and rollback screens
   * (FR-9.2).
   *
   * <p>Open to any caller who can read the policy, like the policy itself: who
   * changed a rule and why is part of what the rule is. The address a change
   * came from is kept in the change log for the auditor and is not in this.
   */
  @GET
  @Path("/{id}/versions")
  public List<PolicyStore.Revision> versions(@PathParam("id") UUID id) {
    policies.find(id).orElseThrow(() -> new NotFoundException("No policy " + id));
    return policies.history(id);
  }

  /**
   * What putting this version back would change, measured before anybody does
   * it (FR-9.2, FR-5.3).
   *
   * <p>The same kind of answer as {@link #impact}, between the policy as it
   * reads now and as it read then.
   */
  @GET
  @Path("/{id}/versions/{version}/impact")
  public ImpactAnalysis.Impact rollbackImpact(
      @PathParam("id") UUID id, @PathParam("version") int version) {
    PolicyStore.StoredPolicy current =
        policies.find(id).orElseThrow(() -> new NotFoundException("No policy " + id));
    if (version == current.version()) {
      throw new BadRequestException(
          "Version " + version + " is the current version; there is nothing to put back");
    }
    PolicyStore.Revision revision =
        policies
            .revision(id, version)
            .orElseThrow(() -> new NotFoundException("Policy " + id + " has no version " + version));
    return impact.measureRollback(current, revision.document());
  }

  /**
   * Every active policy that reaches an asset, outermost first (FR-3.1.5).
   *
   * <p>The order is the order they compose in, so the screen can be read top to
   * bottom as "this is what the org says, then the domain, then you".
   */
  @GET
  @Path("/affecting/{fqn: .+}")
  public List<PolicyStore.StoredPolicy> affecting(
      @PathParam("fqn") String fqn,
      // The environment the engine decides in when nobody says otherwise. This
      // used to default to dev, so the screen meant to show what governs a
      // table showed a different environment's answer -- an empty list on an
      // asset that was in fact covered, which is the worst way to be wrong
      // about access control.
      @QueryParam("environment") String environment) {
    return policies.activeFor(fqn, environmentOr(environment));
  }

  /**
   * The same list, with the columns each policy lands on (FR-3.1.5).
   *
   * <p>Separate from {@link #affecting} rather than replacing it: the bare list is what the engine
   * and the tests want, and this is what the asset page wants. Both read the same rows.
   */
  @GET
  @Path("/for-asset/{fqn: .+}")
  public List<PolicyOverview.Applied> forAsset(
      @PathParam("fqn") String fqn, @QueryParam("environment") String environment) {
    return overview.applied(fqn, policies.activeFor(fqn, environmentOr(environment)));
  }

  private static String environmentOr(String environment) {
    return environment == null || environment.isBlank()
        ? DecisionService.DEFAULT_ENVIRONMENT
        : environment;
  }

  /**
   * The tables and columns this policy actually lands on (FR-3.1.5, reversed).
   *
   * <p>A selector is a claim; this is the result. The two differ whenever the
   * estate has moved since the last resolve, which is exactly what somebody
   * about to activate a policy needs to see.
   */
  @GET
  @Path("/{id}/bindings")
  public PolicyOverview.Coverage bindings(@PathParam("id") UUID id) {
    policies.find(id).orElseThrow(() -> new NotFoundException("No policy " + id));
    return overview.coverage(id);
  }

  /**
   * The policies that meet this one on the same targets, and what happens there.
   *
   * <p>Overlap on its own is not news -- layering is the design (FR-3.1.3).
   * What each row carries is the consequence: which of the two still has an
   * effect where they meet.
   */
  @GET
  @Path("/{id}/conflicts")
  public List<PolicyOverview.Overlap> conflicts(@PathParam("id") UUID id) {
    PolicyStore.StoredPolicy policy =
        policies.find(id).orElseThrow(() -> new NotFoundException("No policy " + id));
    return overview.overlaps(id, policy.document(), policy.environment());
  }

  /**
   * Who this policy changes things for, and how (FR-5.3).
   *
   * <p>Deliberately not cached and deliberately not cheap. It evaluates the
   * engine twice for every person against every table the policy lands on,
   * because the only honest answer to "what does activating this do" is the
   * difference between the two worlds. The result declares its own sampling;
   * callers must not present its counts as totals when {@code sampled} is set.
   */
  @GET
  @Path("/{id}/impact")
  public ImpactAnalysis.Impact impact(@PathParam("id") UUID id) {
    PolicyStore.StoredPolicy policy =
        policies.find(id).orElseThrow(() -> new NotFoundException("No policy " + id));
    return impact.measure(policy);
  }

  @POST
  public Response create(
      Policy document,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser caller = caller(security);
    authorise(caller, document);
    PolicyStore.StoredPolicy created =
        guard(() -> policies.create(document, caller.username(), clientIp(request)));
    // Bound at creation, while still DRAFT. Nothing is enforced from a draft,
    // but the author needs the impact analysis (FR-5.3) before deciding to
    // activate, and that is a count of these rows.
    materializer.materialize(created.id());
    return Response.status(Response.Status.CREATED).entity(policies.find(created.id()).orElseThrow()).build();
  }

  @PUT
  @Path("/{id}")
  public PolicyStore.StoredPolicy update(
      @PathParam("id") UUID id,
      Policy document,
      @QueryParam("version") int expectedVersion,
      @QueryParam("reason") String reason,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {

    AuthenticatedUser caller = caller(security);
    PolicyStore.StoredPolicy existing =
        policies.find(id).orElseThrow(() -> new NotFoundException("No policy " + id));
    // Both sides: a data owner may not edit a policy outside their scope, and
    // may not move one into their scope either.
    authorise(caller, existing.document());
    authorise(caller, document);

    PolicyStore.StoredPolicy updated =
        guard(
            () ->
                policies.update(
                    id, document, expectedVersion, caller.username(), reason, clientIp(request)));
    materializer.materialize(id);
    return policies.find(updated.id()).orElseThrow();
  }

  /** What a rollback asks for: the version being looked at, and why. */
  public record RollbackRequest(Integer expectedVersion, String reason) {}

  /**
   * Puts an earlier version's document back, as a new version (FR-9.2).
   *
   * <p>Authority is checked against both documents, as for an edit: a data
   * owner may not roll back a policy outside their scope, nor roll one back to
   * a version whose scope was somebody else's. The reason is required, because
   * a rollback is the one edit whose "why" is never obvious from the diff.
   */
  @POST
  @Path("/{id}/rollback/{version}")
  public PolicyStore.StoredPolicy rollback(
      @PathParam("id") UUID id,
      @PathParam("version") int version,
      RollbackRequest body,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {

    AuthenticatedUser caller = caller(security);
    if (body == null || body.expectedVersion() == null) {
      throw new BadRequestException("Give the version you are looking at as expectedVersion");
    }
    if (body.reason() == null || body.reason().isBlank()) {
      throw new BadRequestException("Say why this version is being put back");
    }
    PolicyStore.StoredPolicy existing =
        policies.find(id).orElseThrow(() -> new NotFoundException("No policy " + id));
    authorise(caller, existing.document());
    PolicyStore.Revision target =
        policies
            .revision(id, version)
            .orElseThrow(() -> new NotFoundException("Policy " + id + " has no version " + version));
    authorise(caller, target.document());

    PolicyStore.StoredPolicy restored =
        guard(
            () ->
                policies.rollback(
                    id,
                    version,
                    body.expectedVersion(),
                    caller.username(),
                    body.reason().trim(),
                    clientIp(request)));
    materializer.materialize(id);
    return policies.find(restored.id()).orElseThrow();
  }

  /**
   * Moves a policy along its lifecycle (FR-9.1).
   *
   * <p>Approving is not editing: the body carries only the target state, so
   * nobody can smuggle a document change through the step that is meant to be a
   * second pair of eyes.
   */
  @POST
  @Path("/{id}/lifecycle")
  public PolicyStore.StoredPolicy transition(
      @PathParam("id") UUID id,
      Map<String, String> body,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {

    AuthenticatedUser caller = caller(security);
    PolicyStore.StoredPolicy existing =
        policies.find(id).orElseThrow(() -> new NotFoundException("No policy " + id));
    authorise(caller, existing.document());

    String to = body == null ? null : body.get("state");
    if (to == null || to.isBlank()) {
      throw new BadRequestException("Give the state to move to");
    }
    separationOfDuty(caller, existing, to);

    PolicyStore.StoredPolicy moved =
        guard(
            () ->
                policies.transition(
                    id, to, caller.username(), body.get("reason"), clientIp(request)));
    if ("ACTIVE".equals(to)) {
      // Re-resolved at the moment it starts being enforced, so the bindings a
      // draft was simulated against cannot be older than the estate.
      materializer.materialize(id);
    }
    return moved;
  }

  /** Re-resolves the bindings by hand — the button behind FR-3.1.6's automatic path. */
  @POST
  @Path("/{id}/bindings/resolve")
  public PolicyBindingMaterializer.Result resolve(
      @PathParam("id") UUID id, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    PolicyStore.StoredPolicy existing =
        policies.find(id).orElseThrow(() -> new NotFoundException("No policy " + id));
    authorise(caller, existing.document());
    return materializer.materialize(id);
  }

  // ------------------------------------------------------------------ authority

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }

  /**
   * The address the change came from, for the change log. The socket's, not a
   * forwarded header's: a header is whatever the caller chose to send.
   */
  private static String clientIp(HttpServletRequest request) {
    return request == null ? null : request.getRemoteAddr();
  }

  /**
   * Who may write a policy at this scope (FR-3.1.2).
   *
   * <p>A data owner's authority is their scope and everything under it, which
   * is compared segment by segment: owning {@code prod.Sales} is not authority
   * over {@code prod.SalesArchive}. An owner with no scopes recorded owns
   * nothing, rather than everything.
   */
  private static void authorise(AuthenticatedUser caller, Policy document) {
    if (caller.isPlatformAdmin() || caller.hasAnyRole("POLICY_AUTHOR")) {
      return;
    }
    if (!caller.hasAnyRole("DATA_OWNER")) {
      throw new ForbiddenException("Writing policies needs POLICY_AUTHOR or DATA_OWNER");
    }
    String scopeFqn = document.getScopeFqn();
    if (scopeFqn == null || scopeFqn.isBlank()) {
      throw new ForbiddenException("A data owner cannot write an organisation-wide policy");
    }
    for (String owned : caller.scopes()) {
      if (owned != null && !owned.isBlank() && Fqns.isDescendantOrSelf(scopeFqn, owned)) {
        return;
      }
    }
    throw new ForbiddenException("You do not own " + scopeFqn);
  }

  /**
   * The approval step needs a second person (FR-2.6).
   *
   * <p>Only on the approval path. {@code DRAFT → ACTIVE} stays open, because an
   * organisation that has not chosen to require approval should not be forced
   * into it by the API — but once a policy has been sent for approval, the
   * author is not the one who grants it.
   */
  private static void separationOfDuty(
      AuthenticatedUser caller, PolicyStore.StoredPolicy existing, String to) {
    if (!"ACTIVE".equals(to) || !"PENDING_APPROVAL".equals(existing.lifecycleState())) {
      return;
    }
    if (caller.username().equals(existing.updatedBy())
        || caller.username().equals(existing.createdBy())) {
      throw new ForbiddenException(
          "A policy is approved by someone other than the person who wrote it");
    }
    if (!caller.isPlatformAdmin() && !caller.hasAnyRole("POLICY_AUTHOR", "DATA_OWNER")) {
      throw new ForbiddenException("Approving a policy needs POLICY_AUTHOR or DATA_OWNER");
    }
  }

  /**
   * Turns the store's refusals into the status codes they mean.
   *
   * <p>A stale version is 409 rather than 400 — the request was well formed and
   * will succeed once the editor reloads, and that is the difference the UI
   * needs to decide between "fix this" and "somebody else changed it".
   */
  private static <T> T guard(java.util.function.Supplier<T> action) {
    try {
      return action.get();
    } catch (PolicyStore.StaleVersionException e) {
      throw new WebApplicationException(
          Response.status(Response.Status.CONFLICT)
              .entity(Map.of("code", 409, "message", e.getMessage()))
              .type(MediaType.APPLICATION_JSON)
              .build());
    } catch (PolicyStore.NameTakenException e) {
      throw new WebApplicationException(
          Response.status(Response.Status.CONFLICT)
              .entity(Map.of("code", 409, "message", e.getMessage()))
              .type(MediaType.APPLICATION_JSON)
              .build());
    } catch (PolicyStore.IllegalTransitionException e) {
      throw new WebApplicationException(
          Response.status(Response.Status.CONFLICT)
              .entity(Map.of("code", 409, "message", e.getMessage()))
              .type(MediaType.APPLICATION_JSON)
              .build());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
  }
}
