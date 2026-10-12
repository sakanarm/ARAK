package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthServiceClient;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.EntraLogin;
import com.mfec.dac.auth.EntraLoginState;
import com.mfec.dac.auth.JwtService;
import com.mfec.dac.auth.LocalIdentityDao;
import com.mfec.dac.auth.SsoCodeStore;
import com.mfec.dac.config.IdentityConfiguration;
import com.mfec.dac.gateway.AuthEventLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signing in to the console with Microsoft (FR-2.1).
 *
 * <p>Microsoft proves who the person is; ARAK decides whether they may come
 * in. The steps, in order:
 *
 * <ol>
 *   <li>{@code GET /v1/auth/entra/start} sends the browser to Microsoft, with
 *       state, nonce and a PKCE verifier kept in a signed HttpOnly cookie.
 *   <li>{@code GET /auth/callback/microsoft-entra-id} ({@link Callback}) takes
 *       the code back, verifies the id_token, asks the shared auth service,
 *       finds the one enabled ARAK person with that email and at least one
 *       platform role, and records the attempt either way.
 *   <li>It sends the browser to {@code /login?sso=<code>}, and the console
 *       trades the code once at {@code POST /v1/auth/entra/exchange} for the
 *       same session a password sign-in gets.
 * </ol>
 *
 * <p>Nobody is created by signing in. A person Microsoft knows and ARAK does
 * not is sent back to the login page with {@code sso_error=not_registered},
 * and an administrator adds them.
 */
@Path("/v1/auth/entra")
public class EntraLoginResource {

  private static final Logger LOG = LoggerFactory.getLogger(EntraLoginResource.class);

  static final String COOKIE = "arak_entra_login";

  /** What the redirect URI has to end with; the console's root is what comes before it. */
  public static final String CALLBACK_PATH = IdentityConfiguration.ENTRA_LOGIN_CALLBACK_PATH;

  private static final String CLIENT_APP = "console";

  public record ExchangeRequest(String code) {}

  private final EntraLogin entra;
  private final EntraLoginState states;
  private final AuthServiceClient authService;
  private final SsoCodeStore codes;
  private final LocalIdentityDao identities;
  private final JwtService tokens;
  private final AuthEventLog authLog;
  private final String consoleRoot;
  private final String cookiePath;
  private final boolean secureCookie;

  /**
   * @param entra null when Microsoft sign-in is not configured, and every
   *     endpoint here then answers 404
   */
  public EntraLoginResource(
      EntraLogin entra,
      EntraLoginState states,
      AuthServiceClient authService,
      SsoCodeStore codes,
      LocalIdentityDao identities,
      JwtService tokens,
      AuthEventLog authLog) {
    this.entra = entra;
    this.states = states;
    this.authService = authService;
    this.codes = codes;
    this.identities = identities;
    this.tokens = tokens;
    this.authLog = authLog;
    if (entra != null) {
      String redirect = entra.redirectUri();
      if (!redirect.endsWith(CALLBACK_PATH)) {
        throw new IllegalArgumentException(
            "ENTRA_LOGIN_REDIRECT_URI must end with " + CALLBACK_PATH);
      }
      // The console lives where the callback's /api does, so the page to send
      // the browser back to comes from the same registered address and never
      // from request headers a proxy may or may not have rewritten.
      this.consoleRoot = redirect.substring(0, redirect.length() - CALLBACK_PATH.length()) + "/";
      this.cookiePath = URI.create(redirect).getPath();
      this.secureCookie = redirect.startsWith("https://");
    } else {
      this.consoleRoot = null;
      this.cookiePath = null;
      this.secureCookie = true;
    }
  }

  @GET
  @Path("/start")
  public Response start(@QueryParam("to") String to) {
    requireConfigured();
    String state = EntraLogin.randomToken();
    String nonce = EntraLogin.randomToken();
    String verifier = EntraLogin.randomToken();
    String sealed =
        states.seal(new EntraLoginState.State(state, nonce, verifier, safeReturnPath(to)));
    return noStore(Response.status(Response.Status.FOUND))
        .header(HttpHeaders.LOCATION, entra.authorizeUri(state, nonce, verifier).toString())
        .cookie(stateCookie(sealed, (int) EntraLoginState.LIFETIME.toSeconds()))
        .build();
  }

  @POST
  @Path("/exchange")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public AuthResource.LoginResponse exchange(ExchangeRequest request) {
    requireConfigured();
    UUID principalId =
        codes
            .redeem(request == null ? null : request.code())
            .orElseThrow(
                () ->
                    error(
                        Response.Status.UNAUTHORIZED,
                        "this sign-in link has expired or was already used; sign in again"));
    // Read again rather than carried from the callback: a minute is enough
    // for an administrator to have disabled the account or taken its roles.
    AuthenticatedUser user =
        identities
            .loadUser(principalId)
            .filter(u -> !u.appRoles().isEmpty())
            .orElseThrow(
                () -> error(Response.Status.FORBIDDEN, "this account no longer has access"));
    LOG.info("Microsoft sign-in for {} with roles {}", user.username(), user.appRoles());
    return new AuthResource.LoginResponse(
        tokens.issue(user, false),
        "Bearer",
        tokens.ttl().toSeconds(),
        false,
        AuthResource.UserSummary.of(user));
  }

  /** Microsoft sends the browser back here: {@code /api/auth/callback/microsoft-entra-id}. */
  @Path("/auth/callback")
  public static class Callback {
    private final EntraLoginResource login;

    public Callback(EntraLoginResource login) {
      this.login = login;
    }

    @GET
    @Path("/microsoft-entra-id")
    public Response callback(
        @QueryParam("code") String code,
        @QueryParam("state") String state,
        @QueryParam("error") String error,
        @CookieParam(COOKIE) String cookie,
        @Context HttpServletRequest http) {
      return login.callback(code, state, error, cookie, http == null ? null : http.getRemoteAddr());
    }
  }

  Response callback(String code, String state, String error, String cookie, String clientIp) {
    requireConfigured();
    Optional<EntraLoginState.State> held = states.open(cookie);
    String returnTo = held.map(EntraLoginState.State::returnTo).orElse(null);

    if (error != null && !error.isBlank()) {
      // The person cancelled, or Microsoft refused before any code existed.
      boolean cancelled = "access_denied".equals(error);
      record(false, null, "Microsoft returned " + error, clientIp);
      return back(cancelled ? "cancelled" : "failed", null, returnTo);
    }
    if (held.isEmpty() || state == null || !state.equals(held.get().state())) {
      // No cookie, an old one, or a state that is not the one this browser
      // was given: a sign-in that did not start here.
      record(false, null, "state did not match", clientIp);
      return back("failed", null, returnTo);
    }
    if (code == null || code.isBlank()) {
      record(false, null, "no code came back", clientIp);
      return back("failed", null, returnTo);
    }

    String email;
    try {
      email = entra.redeem(code, held.get().verifier(), held.get().nonce());
    } catch (EntraLogin.LoginFailed e) {
      record(false, null, e.getMessage(), clientIp);
      return back(e.ourFault() ? "unavailable" : "failed", null, returnTo);
    }

    AuthServiceClient.Verdict verdict = authService.signIn(email);
    if (verdict == AuthServiceClient.Verdict.NOT_ALLOWED) {
      record(false, email, "the auth service does not allow this account", clientIp);
      return back("not_allowed", null, returnTo);
    }
    if (verdict == AuthServiceClient.Verdict.UNAVAILABLE) {
      record(false, email, "the auth service could not be asked", clientIp);
      return back("unavailable", null, returnTo);
    }

    List<LocalIdentityDao.SsoCandidate> withRoles =
        identities.findSsoCandidates(email).stream().filter(c -> c.roles() > 0).toList();
    if (withRoles.isEmpty()) {
      record(false, email, "no enabled ARAK account with a platform role has this email", clientIp);
      return back("not_registered", null, returnTo);
    }
    if (withRoles.size() > 1) {
      // Two people, or one person twice: either way, choosing would let one
      // account's sign-in open the other.
      record(false, email, withRoles.size() + " ARAK accounts with a role have this email", clientIp);
      return back("ambiguous", null, returnTo);
    }

    LocalIdentityDao.SsoCandidate who = withRoles.get(0);
    Optional<String> oneTime = codes.issue(who.id());
    if (oneTime.isEmpty()) {
      record(false, who.username(), "too many sign-ins waiting to be completed", clientIp);
      return back("unavailable", null, returnTo);
    }
    record(true, who.username(), "signed in as " + email, clientIp);
    return back(null, oneTime.get(), returnTo);
  }

  /** The browser back to the login page, with a code to trade or the reason it has none. */
  private Response back(String reason, String code, String returnTo) {
    StringBuilder target = new StringBuilder(consoleRoot).append("login?");
    if (code != null) {
      target.append("sso=").append(enc(code));
    } else {
      target.append("sso_error=").append(enc(reason));
    }
    if (returnTo != null) {
      target.append("&to=").append(enc(returnTo));
    }
    return noStore(Response.status(Response.Status.FOUND))
        .header(HttpHeaders.LOCATION, target.toString())
        // Spent either way: a second trip through the callback starts over.
        .cookie(stateCookie("", 0))
        .build();
  }

  private void record(boolean success, String principal, String detail, String clientIp) {
    authLog.record(
        AuthEventLog.Event.SSO_LOGIN, success, principal, null, null, detail, CLIENT_APP, clientIp);
    if (!success) {
      LOG.warn("Microsoft sign-in refused for {}: {}", principal == null ? "-" : principal, detail);
    }
  }

  private NewCookie stateCookie(String value, int maxAge) {
    return new NewCookie.Builder(COOKIE)
        .value(value)
        .path(cookiePath)
        .maxAge(maxAge)
        .httpOnly(true)
        .secure(secureCookie)
        // Lax, not Strict: the trip back from Microsoft is a top-level
        // navigation from another site, which Strict would send without it.
        .sameSite(NewCookie.SameSite.LAX)
        .build();
  }

  private static Response.ResponseBuilder noStore(Response.ResponseBuilder builder) {
    return builder
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("Referrer-Policy", "no-referrer");
  }

  private void requireConfigured() {
    if (entra == null) {
      throw error(Response.Status.NOT_FOUND, "Microsoft sign-in is not set up on this deployment");
    }
  }

  /**
   * A page inside the console to return to, or null. The same rule the login
   * page applies: a path, never another site, so the round trip through
   * Microsoft cannot be turned into a redirect to somebody else's page.
   */
  static String safeReturnPath(String to) {
    if (to == null || to.isEmpty() || to.length() > 512) {
      return null;
    }
    if (!to.startsWith("/") || to.startsWith("//") || to.startsWith("/\\")) {
      return null;
    }
    for (int i = 0; i < to.length(); i++) {
      char c = to.charAt(i);
      if (c < 0x20 || c == 0x7f || c == '\\') {
        return null;
      }
    }
    return to;
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static WebApplicationException error(Response.Status status, String message) {
    return new WebApplicationException(
        Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("code", status.getStatusCode(), "message", message))
            .build());
  }
}
