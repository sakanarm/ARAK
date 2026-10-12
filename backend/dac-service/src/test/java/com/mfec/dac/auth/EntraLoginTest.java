package com.mfec.dac.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The Microsoft side of signing in, against a fake tenant: a key pair made
 * here, published as the tenant's keys through a fake transport, and id_tokens
 * signed with it.
 */
class EntraLoginTest {

  /** A clock the test moves by hand; starts at the real time so java-jwt agrees with it. */
  private static final class Hand extends Clock {
    Instant now = Instant.now();

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static final String TENANT = "00000000-0000-0000-0000-0000000000aa";
  private static final String CLIENT = "11111111-0000-0000-0000-0000000000bb";
  private static final String SECRET = "fake-client-secret";
  private static final String REDIRECT =
      "https://arak.example.test/console/api/auth/callback/microsoft-entra-id";
  private static final String NONCE = "the-nonce";
  private static final ObjectMapper JSON = new ObjectMapper();

  private static RSAPublicKey publicKey;
  private static RSAPrivateKey privateKey;

  @BeforeAll
  static void keys() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    publicKey = (RSAPublicKey) pair.getPublic();
    privateKey = (RSAPrivateKey) pair.getPrivate();
  }

  /** The tenant as this test plays it: a token reply and a key set, both changeable. */
  private static final class Tenant implements EntraLogin.Transport {
    EntraLogin.HttpReply tokenReply = new EntraLogin.HttpReply(500, "");
    EntraLogin.HttpReply keysReply = new EntraLogin.HttpReply(200, jwks("k1"));
    boolean down;
    final List<String> posted = new ArrayList<>();
    int keyReads;

    @Override
    public EntraLogin.HttpReply postForm(URI uri, Map<String, String> form) throws IOException {
      if (down) {
        throw new IOException("unreachable");
      }
      posted.add(uri + " " + form);
      return tokenReply;
    }

    @Override
    public EntraLogin.HttpReply get(URI uri) throws IOException {
      keyReads++;
      if (down) {
        throw new IOException("unreachable");
      }
      assertThat(uri.toString())
          .isEqualTo("https://login.microsoftonline.com/" + TENANT + "/discovery/v2.0/keys");
      return keysReply;
    }
  }

  private static String unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes[0] == 0) {
      bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String jwks(String kid) {
    return "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"kid\":\""
        + kid
        + "\",\"n\":\""
        + unsigned(publicKey.getModulus())
        + "\",\"e\":\""
        + unsigned(publicKey.getPublicExponent())
        + "\"}]}";
  }

  /** A token Microsoft would issue, changed by {@code adjust} before it is signed. */
  private static String idToken(Consumer<JWTCreator.Builder> adjust) {
    Instant now = Instant.now();
    JWTCreator.Builder builder =
        JWT.create()
            .withKeyId("k1")
            .withIssuer("https://login.microsoftonline.com/" + TENANT + "/v2.0")
            .withAudience(CLIENT)
            .withClaim("tid", TENANT)
            .withClaim("nonce", NONCE)
            .withClaim("email", "Analyst_A@Example.com")
            .withClaim("preferred_username", "analyst.a@example.com")
            .withSubject("not-an-arak-id")
            .withIssuedAt(now)
            .withNotBefore(now)
            .withExpiresAt(now.plus(Duration.ofHours(1)));
    adjust.accept(builder);
    return builder.sign(Algorithm.RSA256(publicKey, privateKey));
  }

  private static String idToken() {
    return idToken(b -> {});
  }

  private static EntraLogin login(Tenant tenant, Clock clock) {
    // Upper case on purpose: the token spells the tenant in lower case.
    return new EntraLogin(TENANT.toUpperCase(), CLIENT, SECRET, REDIRECT, tenant, JSON, clock);
  }

  @Test
  @DisplayName("PKCE S256 is the base64url SHA-256 of the verifier, unpadded")
  void challenge() {
    // The expected value computed outside this JVM, so a change in either side
    // of challengeFor shows up here rather than agreeing with itself.
    assertThat(EntraLogin.challengeFor("dBjftJeZ4CVP-mJ92K9qe2b4oJGDBDqyw1mY_TrJ9ZM"))
        .isEqualTo("BNunyGWFljY1y_KSCV0ds7C40LW_pzaDWAMAYANh-aY");
    assertThat(EntraLogin.challengeFor("")).doesNotContain("=").doesNotContain("+");
  }

  @Test
  @DisplayName("sends the browser to the tenant with state, nonce and the S256 challenge")
  void authorize() {
    URI uri = login(new Tenant(), new Hand()).authorizeUri("st", "nn", "the-verifier");

    assertThat(uri.toString())
        .startsWith("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/authorize?")
        .contains("client_id=" + CLIENT)
        .contains("response_type=code")
        .contains("redirect_uri=https%3A%2F%2Farak.example.test%2Fconsole%2Fapi%2Fauth%2Fcallback")
        .contains("scope=openid+profile+email")
        .contains("state=st")
        .contains("nonce=nn")
        .contains("code_challenge=" + EntraLogin.challengeFor("the-verifier"))
        .contains("code_challenge_method=S256")
        .doesNotContain(SECRET);
  }

  @Nested
  @DisplayName("redeeming the code")
  class Redeeming {

    @Test
    @DisplayName("posts the code, verifier and secret, and returns the email in lower case")
    void good() throws Exception {
      Tenant tenant = new Tenant();
      tenant.tokenReply = new EntraLogin.HttpReply(200, "{\"id_token\":\"" + idToken() + "\"}");

      assertThat(login(tenant, new Hand()).redeem("the-code", "the-verifier", NONCE))
          .isEqualTo("analyst_a@example.com");
      assertThat(tenant.posted).hasSize(1);
      assertThat(tenant.posted.get(0))
          .startsWith("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token ")
          .contains("grant_type=authorization_code")
          .contains("code=the-code")
          .contains("code_verifier=the-verifier")
          .contains("client_secret=" + SECRET)
          .contains("redirect_uri=" + REDIRECT);
    }

    @Test
    @DisplayName("a stale or reused code is the browser's problem")
    void invalidGrant() {
      Tenant tenant = new Tenant();
      tenant.tokenReply = new EntraLogin.HttpReply(400, "{\"error\":\"invalid_grant\"}");

      assertThatThrownBy(() -> login(tenant, new Hand()).redeem("c", "v", NONCE))
          .isInstanceOfSatisfying(
              EntraLogin.LoginFailed.class, e -> assertThat(e.ourFault()).isFalse());
    }

    @Test
    @DisplayName("a refused secret, an unreachable tenant or a reply without id_token is ours")
    void ourFault() {
      Tenant secretRefused = new Tenant();
      secretRefused.tokenReply = new EntraLogin.HttpReply(401, "{\"error\":\"invalid_client\"}");
      Tenant down = new Tenant();
      down.down = true;
      Tenant noToken = new Tenant();
      noToken.tokenReply = new EntraLogin.HttpReply(200, "{\"access_token\":\"x\"}");

      for (Tenant tenant : List.of(secretRefused, down, noToken)) {
        assertThatThrownBy(() -> login(tenant, new Hand()).redeem("c", "v", NONCE))
            .isInstanceOfSatisfying(
                EntraLogin.LoginFailed.class, e -> assertThat(e.ourFault()).isTrue());
      }
    }
  }

  @Nested
  @DisplayName("checking the id_token")
  class Checking {

    private void refused(String token) {
      assertThatThrownBy(() -> login(new Tenant(), new Hand()).verifiedEmail(token, NONCE))
          .isInstanceOfSatisfying(
              EntraLogin.LoginFailed.class, e -> assertThat(e.ourFault()).isFalse());
    }

    @Test
    @DisplayName("falls back to preferred_username when there is no email")
    void upn() throws Exception {
      String token = idToken(b -> b.withClaim("email", (String) null));

      assertThat(login(new Tenant(), new Hand()).verifiedEmail(token, NONCE))
          .isEqualTo("analyst.a@example.com");
    }

    @Test
    @DisplayName("refuses a token with no address at all")
    void noAddress() {
      refused(
          idToken(b -> b.withClaim("email", (String) null).withClaim("preferred_username", "abc")));
    }

    @Test
    @DisplayName("refuses another application's token")
    void audience() {
      refused(idToken(b -> b.withAudience("another-application")));
    }

    @Test
    @DisplayName("refuses a token from another issuer or another tenant")
    void issuerAndTenant() {
      refused(idToken(b -> b.withIssuer("https://login.microsoftonline.com/common/v2.0")));
      refused(idToken(b -> b.withClaim("tid", "99999999-0000-0000-0000-000000000000")));
    }

    @Test
    @DisplayName("refuses a token minted for another sign-in")
    void nonce() {
      refused(idToken(b -> b.withClaim("nonce", "someone-elses-nonce")));
    }

    @Test
    @DisplayName("refuses an expired token")
    void expired() {
      Instant past = Instant.now().minus(Duration.ofHours(2));
      refused(
          idToken(
              b -> b.withIssuedAt(past).withNotBefore(past).withExpiresAt(past.plusSeconds(60))));
    }

    @Test
    @DisplayName("refuses a token signed with the wrong key or a shared secret")
    void signature() throws Exception {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair other = generator.generateKeyPair();
      String forged =
          JWT.create()
              .withKeyId("k1")
              .withIssuer("https://login.microsoftonline.com/" + TENANT + "/v2.0")
              .withAudience(CLIENT)
              .withClaim("tid", TENANT)
              .withClaim("nonce", NONCE)
              .withClaim("email", "analyst_a@example.com")
              .withExpiresAt(Instant.now().plus(Duration.ofHours(1)))
              .sign(
                  Algorithm.RSA256(
                      (RSAPublicKey) other.getPublic(), (RSAPrivateKey) other.getPrivate()));
      refused(forged);

      String hmac =
          JWT.create()
              .withKeyId("k1")
              .withIssuer("https://login.microsoftonline.com/" + TENANT + "/v2.0")
              .withAudience(CLIENT)
              .withClaim("tid", TENANT)
              .withClaim("nonce", NONCE)
              .withClaim("email", "analyst_a@example.com")
              .withExpiresAt(Instant.now().plus(Duration.ofHours(1)))
              .sign(Algorithm.HMAC256(SECRET));
      refused(hmac);
      refused("not-a-jwt");
    }
  }

  @Nested
  @DisplayName("the tenant's keys")
  class Keys {

    @Test
    @DisplayName("are read once and reused")
    void cached() throws Exception {
      Tenant tenant = new Tenant();
      EntraLogin login = login(tenant, new Hand());

      login.verifiedEmail(idToken(), NONCE);
      login.verifiedEmail(idToken(), NONCE);

      assertThat(tenant.keyReads).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown key id is read for at most once every five minutes")
    void unknownKey() throws Exception {
      Hand clock = new Hand();
      Tenant tenant = new Tenant();
      EntraLogin login = login(tenant, clock);
      login.verifiedEmail(idToken(), NONCE);
      String rolled = idToken(b -> b.withKeyId("k2"));

      clock.now = clock.now.plus(Duration.ofMinutes(1));
      assertThatThrownBy(() -> login.verifiedEmail(rolled, NONCE))
          .isInstanceOf(EntraLogin.LoginFailed.class);
      assertThat(tenant.keyReads).isEqualTo(1);

      // Microsoft rolls its key: the next read after the gap picks it up.
      tenant.keysReply = new EntraLogin.HttpReply(200, jwks("k2"));
      clock.now = clock.now.plus(Duration.ofMinutes(5));
      assertThat(login.verifiedEmail(rolled, NONCE)).isEqualTo("analyst_a@example.com");
      assertThat(tenant.keyReads).isEqualTo(2);
    }

    @Test
    @DisplayName("that cannot be read at all make the failure ours")
    void unreadable() {
      Tenant tenant = new Tenant();
      tenant.keysReply = new EntraLogin.HttpReply(503, "");

      assertThatThrownBy(() -> login(tenant, new Hand()).verifiedEmail(idToken(), NONCE))
          .isInstanceOfSatisfying(
              EntraLogin.LoginFailed.class, e -> assertThat(e.ourFault()).isTrue());
    }

    @Test
    @DisplayName("that fail to refresh keep the ones already read")
    void keepsOldKeys() throws Exception {
      Hand clock = new Hand();
      Tenant tenant = new Tenant();
      EntraLogin login = login(tenant, clock);
      login.verifiedEmail(idToken(), NONCE);

      tenant.down = true;
      clock.now = clock.now.plus(Duration.ofHours(13));

      assertThat(login.verifiedEmail(idToken(), NONCE)).isEqualTo("analyst_a@example.com");
      assertThat(tenant.keyReads).isEqualTo(2);
    }
  }
}
