package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.crypto.SecretBox;
import com.mfec.dac.enforcement.NativeLoginMap;
import com.mfec.dac.enforcement.NativeRoleStore;
import com.mfec.dac.enforcement.NativeSubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Subscription policies pushed down to PostgreSQL as roles, over HTTP
 * (FR-6.2, mode 5.1.1).
 *
 * <h2>Who may do what</h2>
 *
 * Reading is for the people who write and own policy. Planning and checking
 * are for policy authors as well as admins: a plan is how an author finds out
 * who their policy would put in a role. Applying, rolling back, and setting
 * the push account or who connects as which login are for platform admins
 * only -- they change a customer's database under an account that can create
 * roles (FR-2.6, separation of duty).
 *
 * <h2>The push account</h2>
 *
 * Typed in as a username and password and sealed with the deployment's
 * Fernet key before it is stored, as on the source form, or given as a
 * pointer ({@code vault://}, {@code azurekeyvault://}, {@code env:}). Nothing
 * here ever serves it back: the screens get whether one is set, by which
 * scheme, when and by whom.
 */
@Path("/v1/native-subscription")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured({"PLATFORM_ADMIN", "POLICY_AUTHOR", "DATA_OWNER"})
public class NativeSubscriptionResource {

  /** A push account: a username and password to seal, or a pointer. */
  public record CredentialInput(String username, String password, String credentialRef) {}

  /** One person's login on a source. */
  public record LoginInput(String username, String login) {}

  /** Which source a policy is pushed to, and at what level. */
  public record PlanInput(String sourceId, String level) {}

  /** The plan that was read, by id; nothing else. */
  public record ApplyInput(String sourceId, String reviewId) {}

  /** Which source. */
  public record SourceInput(String sourceId) {}

  private static final String SEALED = "fernet:";
  private static final List<String> POINTERS = List.of("vault://", "azurekeyvault://", "env:");

  private final NativeSubscriptionService service;
  private final SecretBox secretBox;
  private final Duration sweepPeriod;

  public NativeSubscriptionResource(
      NativeSubscriptionService service, SecretBox secretBox, Duration sweepPeriod) {
    this.service = service;
    this.secretBox = secretBox;
    this.sweepPeriod = sweepPeriod;
  }

  // ---------------------------------------------------------------- sources

  @GET
  @Path("/sources")
  public Map<String, Object> sources() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("data", service.sources());
    body.put("sweepMinutes", sweepPeriod.toMinutes());
    body.put("populationLimit", NativeSubscriptionService.POPULATION_LIMIT);
    body.put("decisionLimit", NativeSubscriptionService.DECISION_LIMIT);
    return body;
  }

  @PUT
  @Path("/sources/{id}/credential")
  @Secured("PLATFORM_ADMIN")
  public NativeRoleStore.CredentialInfo setCredential(
      @PathParam("id") UUID sourceId,
      CredentialInput input,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser caller = caller(security);
    String ref = reference(input);
    try {
      return service.setCredential(sourceId, ref, caller.getName(), clientIp(request));
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  @DELETE
  @Path("/sources/{id}/credential")
  @Secured("PLATFORM_ADMIN")
  public Map<String, Object> deleteCredential(
      @PathParam("id") UUID sourceId,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser caller = caller(security);
    try {
      return Map.of(
          "removed", service.deleteCredential(sourceId, caller.getName(), clientIp(request)));
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  @GET
  @Path("/sources/{id}/logins")
  @Secured({"PLATFORM_ADMIN", "POLICY_AUTHOR"})
  public Map<String, Object> logins(@PathParam("id") UUID sourceId) {
    try {
      return Map.of("data", service.logins(sourceId));
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  @PUT
  @Path("/sources/{id}/logins")
  @Secured("PLATFORM_ADMIN")
  public NativeLoginMap.Login mapLogin(
      @PathParam("id") UUID sourceId,
      LoginInput input,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser caller = caller(security);
    if (input == null) {
      throw new BadRequestException("Send {\"username\": \"…\", \"login\": \"…\"}");
    }
    try {
      return service.mapLogin(
          sourceId, input.username(), input.login(), caller.getName(), clientIp(request));
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  @DELETE
  @Path("/sources/{id}/logins/{username}")
  @Secured("PLATFORM_ADMIN")
  public Map<String, Object> unmapLogin(
      @PathParam("id") UUID sourceId,
      @PathParam("username") String username,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser caller = caller(security);
    try {
      return Map.of(
          "removed",
          service.unmapLogin(sourceId, username, caller.getName(), clientIp(request)));
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  @GET
  @Path("/sources/{id}/history")
  @Secured({"PLATFORM_ADMIN", "POLICY_AUTHOR"})
  public Map<String, Object> configurationHistory(
      @PathParam("id") UUID sourceId, @DefaultValue("50") @QueryParam("limit") int limit) {
    try {
      return Map.of("data", service.configurationHistory(sourceId, clamp(limit)));
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  // --------------------------------------------------------------- policies

  @GET
  @Path("/policies/{id}")
  public NativeSubscriptionService.PolicyView policy(@PathParam("id") UUID policyId) {
    try {
      return service.policy(policyId);
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  @POST
  @Path("/policies/{id}/plan")
  @Secured({"PLATFORM_ADMIN", "POLICY_AUTHOR"})
  public NativeSubscriptionService.Preview plan(
      @PathParam("id") UUID policyId,
      PlanInput input,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser caller = caller(security);
    UUID sourceId = sourceId(input == null ? null : input.sourceId());
    try {
      return service.plan(
          policyId, sourceId, input.level(), caller.getName(), clientIp(request));
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  @POST
  @Path("/policies/{id}/apply")
  @Secured("PLATFORM_ADMIN")
  public NativeSubscriptionService.Outcome apply(
      @PathParam("id") UUID policyId,
      ApplyInput input,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser caller = caller(security);
    UUID sourceId = sourceId(input == null ? null : input.sourceId());
    if (input.reviewId() == null || input.reviewId().isBlank()) {
      throw new BadRequestException(
          "Send {\"sourceId\": \"…\", \"reviewId\": \"…\"} -- the id of the plan you read");
    }
    UUID reviewId;
    try {
      reviewId = UUID.fromString(input.reviewId().trim());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("reviewId must be the id a plan returned");
    }
    try {
      return service.apply(policyId, sourceId, reviewId, caller.getName(), clientIp(request));
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  @POST
  @Path("/policies/{id}/check")
  @Secured({"PLATFORM_ADMIN", "POLICY_AUTHOR"})
  public NativeSubscriptionService.Check check(
      @PathParam("id") UUID policyId,
      SourceInput input,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser caller = caller(security);
    UUID sourceId = sourceId(input == null ? null : input.sourceId());
    try {
      return service.check(policyId, sourceId, caller.getName(), clientIp(request));
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  @POST
  @Path("/policies/{id}/rollback-plan")
  @Secured({"PLATFORM_ADMIN", "POLICY_AUTHOR"})
  public NativeSubscriptionService.RollbackPreview rollbackPlan(
      @PathParam("id") UUID policyId, SourceInput input) {
    UUID sourceId = sourceId(input == null ? null : input.sourceId());
    try {
      return service.rollbackPlan(policyId, sourceId);
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  @POST
  @Path("/policies/{id}/rollback")
  @Secured("PLATFORM_ADMIN")
  public NativeSubscriptionService.Outcome rollback(
      @PathParam("id") UUID policyId,
      SourceInput input,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {
    AuthenticatedUser caller = caller(security);
    UUID sourceId = sourceId(input == null ? null : input.sourceId());
    try {
      return service.rollback(policyId, sourceId, caller.getName(), clientIp(request));
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  @GET
  @Path("/policies/{id}/history")
  public Map<String, Object> history(
      @PathParam("id") UUID policyId,
      @QueryParam("sourceId") String sourceId,
      @DefaultValue("50") @QueryParam("limit") int limit) {
    try {
      return Map.of("data", service.history(policyId, sourceId(sourceId), clamp(limit)));
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  // ---------------------------------------------------------------- helpers

  /**
   * The reference to store. A typed password is sealed here, so that it is
   * never in a row, a log or a response in the clear.
   */
  private String reference(CredentialInput input) {
    String username = blankToNull(input == null ? null : input.username());
    String password = input == null ? null : input.password();
    String pointer = blankToNull(input == null ? null : input.credentialRef());
    boolean typedPassword = password != null && !password.isEmpty();
    if ((username != null) != typedPassword) {
      throw new BadRequestException(
          "A username and a password go together. Give both, or a reference to where the"
              + " account is kept.");
    }
    if (username != null) {
      if (pointer != null) {
        throw new BadRequestException("Give a username and password, or a reference; not both.");
      }
      if (!secretBox.available()) {
        throw new BadRequestException(
            "This deployment cannot store a password: " + secretBox.problem()
                + " Set FERNET_KEY, or give a reference to a secret store instead.");
      }
      if (username.indexOf(':') >= 0) {
        // Sealed as user:password and split on the first colon.
        throw new BadRequestException("A username may not contain a colon");
      }
      return SEALED + secretBox.seal(username + ":" + password);
    }
    if (pointer == null) {
      throw new BadRequestException(
          "Send {\"username\": \"…\", \"password\": \"…\"} or {\"credentialRef\": \"vault://…\"}");
    }
    String lower = pointer.toLowerCase(Locale.ROOT);
    if (POINTERS.stream().noneMatch(lower::startsWith)) {
      // A fernet: value pasted in would be somebody else's ciphertext, or the
      // redaction marker a form was served; neither is an account.
      throw new BadRequestException(
          "A reference starts with vault://, azurekeyvault:// or env:. To store a password,"
              + " type the username and password instead.");
    }
    return pointer;
  }

  private static UUID sourceId(String value) {
    if (value == null || value.isBlank()) {
      throw new BadRequestException("Say which source: \"sourceId\"");
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("sourceId must be a source's id");
    }
  }

  private static int clamp(int limit) {
    return Math.max(1, Math.min(limit, 500));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (!(security.getUserPrincipal() instanceof AuthenticatedUser caller)) {
      throw new ForbiddenException("No caller on this request");
    }
    return caller;
  }

  /** The service's refusals, as the statuses a client can act on. */
  static RuntimeException translate(RuntimeException e) {
    int status;
    if (e instanceof NativeSubscriptionService.NotFoundException) {
      status = Response.Status.NOT_FOUND.getStatusCode();
    } else if (e instanceof NativeSubscriptionService.ConflictException) {
      status = Response.Status.CONFLICT.getStatusCode();
    } else if (e instanceof NativeSubscriptionService.RefusedException) {
      status = 422;
    } else if (e instanceof NativeSubscriptionService.SourceFailureException) {
      status = Response.Status.BAD_GATEWAY.getStatusCode();
    } else if (e instanceof NativeLoginMap.InvalidLoginException) {
      status = Response.Status.BAD_REQUEST.getStatusCode();
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
