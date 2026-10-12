package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.auth.AuthServiceClient;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.EntraLogin;
import com.mfec.dac.auth.EntraLoginState;
import com.mfec.dac.auth.JwtService;
import com.mfec.dac.auth.LocalIdentityDao;
import com.mfec.dac.auth.SsoCodeStore;
import com.mfec.dac.gateway.AuthEventLog;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the callback does with each answer Microsoft and the directory can give.
 *
 * <p>Nothing here reaches a database or the network. What is being tested is the
 * order of the checks, that a refusal is audited, and that the login page is
 * told which refusal it was without being told anything a stranger could use.
 */
class EntraLoginResourceTest {

  private static final String REDIRECT =
      "https://arak.example.test/console/api/auth/callback/microsoft-entra-id";
  private static final String CONSOLE = "https://arak.example.test/console/";
  private static final String SECRET = "a-test-signing-key-long-enough-to-be-accepted";
  private static final UUID ANALYST = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final Clock CLOCK = Clock.fixed(Instant.now(), ZoneOffset.UTC);

  /** Microsoft is never actually called: {@link FakeLogin} answers instead. */
  private static final EntraLogin.Transport UNUSED =
      new EntraLogin.Transport() {
        @Override
        public EntraLogin.HttpReply postForm(URI uri, Map<String, String> form) throws IOException {
          throw new IOException("the test answers redeem() itself");
        }

        @Override
        public EntraLogin.HttpReply get(URI uri) throws IOException {
          throw new IOException("the test answers redeem() itself");
        }
      };

  /** A real {@link EntraLogin} for everything but the exchange with Microsoft. */
  private static final class FakeLogin extends EntraLogin {
    EntraLogin.LoginFailed refuse;
    String email = "Analyst_A@Example.com";
    String sawCode;

    FakeLogin() {
      super(
          "00000000-0000-0000-0000-0000000000aa",
          "11111111-0000-0000-0000-0000000000bb",
          "fake-client-secret",
          REDIRECT,
          UNUSED,
          new ObjectMapper(),
          CLOCK);
    }

    @Override
    public String redeem(String code, String verifier, String nonce) throws LoginFailed {
      sawCode = code;
      if (refuse != null) {
        throw refuse;
      }
      return email.toLowerCase(Locale.ROOT);
    }
  }

  private final FakeLogin entra = new FakeLogin();
  private final EntraLoginState states = new EntraLoginState(SECRET, CLOCK);
  private final SsoCodeStore codes = new SsoCodeStore(CLOCK);
  private final LocalIdentityDao identities = mock(LocalIdentityDao.class);
  private final AuthEventLog authLog = mock(AuthEventLog.class);
  private final JwtService tokens =
      new JwtService(SECRET, "data-access-control", Duration.ofHours(1));
  private final AuthServiceClient authService = mock(AuthServiceClient.class);

  private EntraLoginResource resource() {
    return new EntraLoginResource(entra, states, authService, codes, identities, tokens, authLog);
  }

  private void says(AuthServiceClient.Verdict verdict) {
    when(authService.signIn(anyString())).thenReturn(verdict);
  }

  private String cookie(String state) {
    return states.seal(new EntraLoginState.State(state, "the-nonce", "the-verifier", null));
  }

  private static String location(Response response) {
    return response.getHeaderString(HttpHeaders.LOCATION);
  }

  /** One ARAK account with this email, holding {@code roles} platform roles. */
  private void accountWith(int roles) {
    when(identities.findSsoCandidates(anyString()))
        .thenReturn(List.of(new LocalIdentityDao.SsoCandidate(ANALYST, "analyst_a", roles)));
  }

  @Nested
  @DisplayName("sending the browser to Microsoft")
  class Start {

    @Test
    @DisplayName("redirects with the state kept in an HttpOnly cookie scoped to the callback")
    void start() {
      Response response = resource().start("/policies");

      assertThat(response.getStatus()).isEqualTo(302);
      assertThat(location(response)).startsWith("https://login.microsoftonline.com/");
      assertThat(response.getHeaderString(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");

      var cookie = response.getCookies().get("arak_entra_login");
      assertThat(cookie.isHttpOnly()).isTrue();
      assertThat(cookie.isSecure()).isTrue();
      assertThat(cookie.getPath()).isEqualTo("/console/api/auth/callback/microsoft-entra-id");

      EntraLoginState.State held = states.open(cookie.getValue()).orElseThrow();
      assertThat(location(response)).contains("state=" + held.state());
      assertThat(held.returnTo()).isEqualTo("/policies");
      // Only the challenge goes to Microsoft; the verifier stays in the cookie.
      assertThat(location(response)).doesNotContain(held.verifier());
    }

    @Test
    @DisplayName("keeps only a path to return to, never another site")
    void returnPath() {
      assertThat(EntraLoginResource.safeReturnPath("/policies/42")).isEqualTo("/policies/42");
      assertThat(EntraLoginResource.safeReturnPath("//elsewhere.example.test/")).isNull();
      assertThat(EntraLoginResource.safeReturnPath("/\\elsewhere.example.test")).isNull();
      assertThat(EntraLoginResource.safeReturnPath("https://elsewhere.example.test")).isNull();
      assertThat(EntraLoginResource.safeReturnPath("/policies\r\nSet-Cookie: x=1")).isNull();
      assertThat(EntraLoginResource.safeReturnPath("/" + "a".repeat(512))).isNull();
      assertThat(EntraLoginResource.safeReturnPath("")).isNull();
      assertThat(EntraLoginResource.safeReturnPath(null)).isNull();
    }
  }

  @Nested
  @DisplayName("coming back from Microsoft")
  class CallingBack {

    private Response back(String code, String state, String error, String cookie) {
      return resource().callback(code, state, error, cookie, "192.0.2.10");
    }

    private void audited(boolean success, String principal) {
      verify(authLog)
          .record(
              eq(AuthEventLog.Event.SSO_LOGIN),
              eq(success),
              principal == null ? any() : eq(principal),
              any(),
              any(),
              anyString(),
              eq("console"),
              eq("192.0.2.10"));
    }

    @Test
    @DisplayName("a person with one account and a role gets a one-time code for the console")
    void signedIn() {
      says(AuthServiceClient.Verdict.ALLOWED);
      accountWith(1);

      Response response = back("the-code", "the-state", null, cookie("the-state"));

      assertThat(response.getStatus()).isEqualTo(302);
      assertThat(location(response)).startsWith(CONSOLE + "login?sso=");
      assertThat(entra.sawCode).isEqualTo("the-code");
      String code = location(response).substring((CONSOLE + "login?sso=").length());
      assertThat(codes.redeem(code)).contains(ANALYST);
      // The state cookie is spent whatever happened.
      assertThat(response.getCookies().get("arak_entra_login").getMaxAge()).isZero();
      audited(true, "analyst_a");
    }

    @Test
    @DisplayName("somebody Microsoft knows and ARAK does not is told to ask an administrator")
    void notRegistered() {
      says(AuthServiceClient.Verdict.ALLOWED);
      when(identities.findSsoCandidates(anyString())).thenReturn(List.of());

      assertThat(location(back("the-code", "s", null, cookie("s"))))
          .isEqualTo(CONSOLE + "login?sso_error=not_registered");
      audited(false, null);
    }

    @Test
    @DisplayName("an account with no platform role is not registered either")
    void noRole() {
      says(AuthServiceClient.Verdict.ALLOWED);
      accountWith(0);

      assertThat(location(back("the-code", "s", null, cookie("s"))))
          .isEqualTo(CONSOLE + "login?sso_error=not_registered");
    }

    @Test
    @DisplayName("two accounts with the same email sign nobody in")
    void ambiguous() {
      says(AuthServiceClient.Verdict.ALLOWED);
      when(identities.findSsoCandidates(anyString()))
          .thenReturn(
              List.of(
                  new LocalIdentityDao.SsoCandidate(ANALYST, "analyst_a", 1),
                  new LocalIdentityDao.SsoCandidate(UUID.randomUUID(), "analyst_a_old", 2)));

      assertThat(location(back("the-code", "s", null, cookie("s"))))
          .isEqualTo(CONSOLE + "login?sso_error=ambiguous");
    }

    @Test
    @DisplayName("the shared auth service refusing is not the same as not being registered")
    void notAllowed() {
      says(AuthServiceClient.Verdict.NOT_ALLOWED);
      accountWith(1);

      assertThat(location(back("the-code", "s", null, cookie("s"))))
          .isEqualTo(CONSOLE + "login?sso_error=not_allowed");
    }

    @Test
    @DisplayName("a service that cannot be asked lets nobody in")
    void unavailable() {
      says(AuthServiceClient.Verdict.UNAVAILABLE);
      accountWith(1);

      assertThat(location(back("the-code", "s", null, cookie("s"))))
          .isEqualTo(CONSOLE + "login?sso_error=unavailable");
    }

    @Test
    @DisplayName("no service configured leaves the decision to ARAK's own accounts")
    void noAuthService() {
      says(AuthServiceClient.Verdict.NOT_CONFIGURED);
      accountWith(1);

      assertThat(location(back("the-code", "s", null, cookie("s"))))
          .startsWith(CONSOLE + "login?sso=");
    }

    @Test
    @DisplayName("a reply that did not start here is refused before Microsoft is asked anything")
    void stateMismatch() {
      says(AuthServiceClient.Verdict.ALLOWED);
      accountWith(1);

      assertThat(location(back("the-code", "another-state", null, cookie("the-state"))))
          .isEqualTo(CONSOLE + "login?sso_error=failed");
      assertThat(location(back("the-code", "the-state", null, null)))
          .isEqualTo(CONSOLE + "login?sso_error=failed");
      assertThat(location(back(null, "the-state", null, cookie("the-state"))))
          .isEqualTo(CONSOLE + "login?sso_error=failed");
      assertThat(entra.sawCode).isNull();
    }

    @Test
    @DisplayName("cancelling at Microsoft says so, and any other error there does not")
    void cancelled() {
      assertThat(location(back(null, "s", "access_denied", cookie("s"))))
          .isEqualTo(CONSOLE + "login?sso_error=cancelled");
      assertThat(location(back(null, "s", "server_error", cookie("s"))))
          .isEqualTo(CONSOLE + "login?sso_error=failed");
      assertThat(entra.sawCode).isNull();
    }

    @Test
    @DisplayName("a code Microsoft refuses is the browser's problem; a tenant we cannot reach is ours")
    void redeemFails() {
      entra.refuse = new EntraLogin.LoginFailed("stale code", false);
      assertThat(location(back("the-code", "s", null, cookie("s"))))
          .isEqualTo(CONSOLE + "login?sso_error=failed");

      entra.refuse = new EntraLogin.LoginFailed("unreachable", true);
      assertThat(location(back("the-code", "s", null, cookie("s"))))
          .isEqualTo(CONSOLE + "login?sso_error=unavailable");
    }

    @Test
    @DisplayName("the page asked for is carried through the round trip")
    void returnTo() {
      says(AuthServiceClient.Verdict.ALLOWED);
      accountWith(1);
      String held =
          states.seal(new EntraLoginState.State("s", "the-nonce", "the-verifier", "/policies/42"));

      assertThat(location(back("the-code", "s", null, held))).contains("&to=%2Fpolicies%2F42");
    }
  }

  @Nested
  @DisplayName("trading the one-time code for a session")
  class Exchange {

    private AuthenticatedUser analyst(Set<String> roles) {
      return new AuthenticatedUser(
          ANALYST, "analyst_a", "analyst_a@example.com", "Analyst A", "local", roles, List.of());
    }

    @Test
    @DisplayName("gives the session a password sign-in gives, with no password to change")
    void exchange() {
      when(identities.loadUser(ANALYST)).thenReturn(Optional.of(analyst(Set.of("AUDITOR"))));
      String code = codes.issue(ANALYST).orElseThrow();

      AuthResource.LoginResponse response =
          resource().exchange(new EntraLoginResource.ExchangeRequest(code));

      assertThat(response.mustChangePassword()).isFalse();
      assertThat(response.user().username()).isEqualTo("analyst_a");
      assertThat(tokens.verify(response.accessToken()).orElseThrow().appRoles())
          .containsExactly("AUDITOR");
    }

    @Test
    @DisplayName("a code already traded, unknown or missing opens nothing")
    void spent() {
      when(identities.loadUser(ANALYST)).thenReturn(Optional.of(analyst(Set.of("AUDITOR"))));
      String code = codes.issue(ANALYST).orElseThrow();
      resource().exchange(new EntraLoginResource.ExchangeRequest(code));

      assertThat(status(() -> resource().exchange(new EntraLoginResource.ExchangeRequest(code))))
          .isEqualTo(401);
      assertThat(status(() -> resource().exchange(new EntraLoginResource.ExchangeRequest("x"))))
          .isEqualTo(401);
      assertThat(status(() -> resource().exchange(null))).isEqualTo(401);
    }

    @Test
    @DisplayName("roles taken away in the minute since the callback are noticed")
    void rolesWithdrawn() {
      String code = codes.issue(ANALYST).orElseThrow();
      when(identities.loadUser(ANALYST)).thenReturn(Optional.of(analyst(Set.of())));

      assertThat(status(() -> resource().exchange(new EntraLoginResource.ExchangeRequest(code))))
          .isEqualTo(403);
    }
  }

  @Nested
  @DisplayName("when Microsoft sign-in is not set up")
  class NotConfigured {

    @Test
    @DisplayName("every endpoint answers 404 rather than half a sign-in")
    void allFourOhFour() {
      EntraLoginResource off =
          new EntraLoginResource(null, states, authService, codes, identities, tokens, authLog);

      assertThat(status(() -> off.start(null))).isEqualTo(404);
      assertThat(status(() -> off.exchange(new EntraLoginResource.ExchangeRequest("x"))))
          .isEqualTo(404);
      assertThat(status(() -> off.callback("c", "s", null, null, null))).isEqualTo(404);
    }
  }

  private static int status(Runnable call) {
    try {
      call.run();
    } catch (WebApplicationException e) {
      return e.getResponse().getStatus();
    }
    throw new AssertionError("expected the call to be refused");
  }
}
