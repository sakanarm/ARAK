package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.JwtService;
import com.mfec.dac.auth.LocalIdentityDao;
import com.mfec.dac.auth.PasswordHasher;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.config.IdentityConfiguration;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sign-in for local principals, and the endpoints the console needs to know who
 * it is talking to.
 *
 * <p>Local username and password is what FR-2.2 asks for and what makes the
 * platform usable before a tenant is wired up. Entra ID (FR-2.1) is the
 * production path and is not here yet; {@link #config()} exists so the login
 * screen can already render the right thing when it appears, without the
 * frontend having to be rebuilt around a second shape of response.
 */
@Path("/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

  private static final Logger LOG = LoggerFactory.getLogger(AuthResource.class);

  /**
   * A hash of a value nobody can present, used to spend the same time verifying
   * a password for a username that does not exist as for one that does.
   * Otherwise the response time answers "is there an account called X" for
   * anyone who cares to ask.
   */
  private static final String DECOY_HASH = PasswordHasher.hash("not-a-password".toCharArray());

  public record LoginRequest(String username, String password) {}

  public record UserSummary(
      String id,
      String username,
      String email,
      String displayName,
      String source,
      Set<String> roles,
      List<String> scopes) {

    static UserSummary of(AuthenticatedUser user) {
      return new UserSummary(
          user.id().toString(),
          user.username(),
          user.email(),
          user.displayName(),
          user.source(),
          user.appRoles(),
          user.scopes());
    }
  }

  public record LoginResponse(
      String accessToken,
      String tokenType,
      long expiresInSeconds,
      boolean mustChangePassword,
      UserSummary user) {}

  public record PasswordChangeRequest(String currentPassword, String newPassword) {}

  /** What the login screen needs before anyone has logged in. */
  public record AuthConfig(
      boolean localLoginEnabled,
      boolean entraEnabled,
      String entraTenantId,
      String entraClientId) {}

  private final LocalIdentityDao identities;
  private final JwtService tokens;
  private final IdentityConfiguration config;

  public AuthResource(
      LocalIdentityDao identities, JwtService tokens, IdentityConfiguration config) {
    this.identities = identities;
    this.tokens = tokens;
    this.config = config;
  }

  @GET
  @Path("/config")
  public AuthConfig config() {
    // The client secret is deliberately absent: this endpoint is unauthenticated
    // and only the public half of the Entra registration belongs in a browser.
    boolean entra =
        isSet(config.getEntraTenantId()) && isSet(config.getEntraClientId());
    return new AuthConfig(
        config.isAllowLocalUsers(), entra, config.getEntraTenantId(), config.getEntraClientId());
  }

  @POST
  @Path("/login")
  @Consumes(MediaType.APPLICATION_JSON)
  public LoginResponse login(LoginRequest request) {
    if (!config.isAllowLocalUsers()) {
      throw error(Response.Status.FORBIDDEN, "local login is disabled on this deployment");
    }
    if (request == null || !isSet(request.username()) || !isSet(request.password())) {
      throw error(Response.Status.BAD_REQUEST, "username and password are required");
    }

    char[] password = request.password().toCharArray();
    try {
      Optional<LocalIdentityDao.LocalAccount> found =
          identities.findLocalAccount(request.username().trim());

      if (found.isEmpty()) {
        PasswordHasher.verify(password, DECOY_HASH);
        throw invalidCredentials();
      }

      LocalIdentityDao.LocalAccount account = found.get();

      if (account.isLocked(Instant.now())) {
        // Named distinctly from a wrong password: the user can do something
        // about waiting, and cannot do anything about a message that pretends
        // their password is wrong.
        throw error(
            Response.Status.TOO_MANY_REQUESTS,
            "too many failed attempts; this account is locked until "
                + account.lockedUntil());
      }

      if (!account.enabled() || !account.hasCredential()) {
        PasswordHasher.verify(password, DECOY_HASH);
        throw invalidCredentials();
      }

      if (!PasswordHasher.verify(password, account.passwordHash())) {
        identities.recordFailure(
            account.id(),
            config.getMaxFailedLoginAttempts(),
            Duration.ofSeconds(config.getLockoutSeconds()));
        LOG.warn("failed local login for {}", account.username());
        throw invalidCredentials();
      }

      identities.recordSuccess(account.id());
      if (PasswordHasher.needsRehash(account.passwordHash())) {
        // The user just proved the password, so this is the only moment we can
        // upgrade its hash without asking them for it again.
        identities.refreshHash(account.id(), PasswordHasher.hash(password));
      }

      AuthenticatedUser user =
          identities
              .loadUser(account.id())
              .orElseThrow(
                  () ->
                      error(
                          Response.Status.FORBIDDEN,
                          "this account exists but is not enabled"));

      LOG.info("local login for {} with roles {}", user.username(), user.appRoles());
      return new LoginResponse(
          tokens.issue(user),
          "Bearer",
          tokens.ttl().toSeconds(),
          account.mustChange(),
          UserSummary.of(user));
    } finally {
      PasswordHasher.wipe(password);
    }
  }

  @GET
  @Path("/me")
  @Secured
  public UserSummary me(@Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    // Read through to the database rather than replay the token claims, so a
    // role change takes effect on the next page load.
    return identities
        .loadUser(caller.id())
        .map(UserSummary::of)
        .orElseThrow(
            () -> error(Response.Status.UNAUTHORIZED, "this account no longer exists"));
  }

  @POST
  @Path("/password")
  @Secured
  @Consumes(MediaType.APPLICATION_JSON)
  public Map<String, String> changePassword(
      @Context SecurityContext security, PasswordChangeRequest request) {
    AuthenticatedUser caller = caller(security);
    if (request == null || !isSet(request.currentPassword()) || !isSet(request.newPassword())) {
      throw error(Response.Status.BAD_REQUEST, "currentPassword and newPassword are required");
    }
    if (request.newPassword().length() < 12) {
      throw error(Response.Status.BAD_REQUEST, "the new password must be at least 12 characters");
    }

    LocalIdentityDao.LocalAccount account =
        identities
            .findLocalAccount(caller.username())
            .filter(LocalIdentityDao.LocalAccount::hasCredential)
            .orElseThrow(
                () ->
                    error(
                        Response.Status.FORBIDDEN,
                        "this account has no local password to change"));

    char[] current = request.currentPassword().toCharArray();
    char[] replacement = request.newPassword().toCharArray();
    try {
      if (!PasswordHasher.verify(current, account.passwordHash())) {
        throw error(Response.Status.FORBIDDEN, "the current password is not correct");
      }
      identities.setPassword(account.id(), PasswordHasher.hash(replacement), false);
      LOG.info("password changed for {}", account.username());
      return Map.of("status", "changed");
    } finally {
      PasswordHasher.wipe(current);
      PasswordHasher.wipe(replacement);
    }
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw error(Response.Status.UNAUTHORIZED, "not authenticated");
    }
    return user;
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * One message for every reason a login can fail on credentials.
   *
   * <p>Distinguishing "no such user" from "wrong password" turns the login form
   * into an account enumerator, and the user cannot act differently on the two
   * answers anyway.
   */
  private static WebApplicationException invalidCredentials() {
    return error(Response.Status.UNAUTHORIZED, "the username or password is not correct");
  }

  private static WebApplicationException error(Response.Status status, String message) {
    return new WebApplicationException(
        Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("code", status.getStatusCode(), "message", message))
            .build());
  }
}
