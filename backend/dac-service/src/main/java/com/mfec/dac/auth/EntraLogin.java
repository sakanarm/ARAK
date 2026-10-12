package com.mfec.dac.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Microsoft side of signing in to the console (FR-2.1): the address the
 * browser is sent to, and turning what comes back into a verified email.
 *
 * <p>Authorization code with PKCE, and the client secret as well, because the
 * registration is a Web one shared with the other applications. The id_token
 * comes straight from the token endpoint over TLS, and is still verified in
 * full — signature against the tenant's published keys, issuer, audience,
 * tenant and nonce — because a check skipped on the grounds that the channel
 * was trusted is the check missing the day it is not.
 *
 * <p>What it returns is an email and nothing else. Microsoft proves who the
 * person is; whether they may use ARAK is decided afterwards, from ARAK's own
 * accounts. The {@code sub} and {@code oid} claims are never used: they are not
 * ARAK's user ids, and letting one reach a session is how a person signs in
 * successfully and then can see nothing.
 */
public class EntraLogin {

  private static final Logger LOG = LoggerFactory.getLogger(EntraLogin.class);

  static final String AUTHORITY = "https://login.microsoftonline.com/";
  static final String SCOPE = "openid profile email";

  /** How long the tenant's signing keys are trusted before they are read again. */
  private static final Duration KEYS_FRESH_FOR = Duration.ofHours(12);

  /**
   * The shortest gap between two reads of the keys prompted by an unknown key
   * id. Microsoft rolls keys rarely, and a token naming a key nobody published
   * should not be a way to make this process fetch on every request.
   */
  private static final Duration KEYS_REFETCH_GAP = Duration.ofMinutes(5);

  private static final SecureRandom RANDOM = new SecureRandom();

  /** A reply from Microsoft, as status and body. */
  public record HttpReply(int status, String body) {}

  /** The two calls this makes to Microsoft; replaced in tests. */
  public interface Transport {
    HttpReply postForm(URI uri, Map<String, String> form) throws IOException;

    HttpReply get(URI uri) throws IOException;
  }

  /** Why a sign-in could not be completed; {@code ourFault} says whose problem it is. */
  public static final class LoginFailed extends Exception {
    private static final long serialVersionUID = 1L;
    private final boolean ourFault;

    public LoginFailed(String message, boolean ourFault) {
      super(message);
      this.ourFault = ourFault;
    }

    /**
     * True when Microsoft could not be reached or answered something this
     * cannot use; false when what the browser brought back does not check out.
     */
    public boolean ourFault() {
      return ourFault;
    }
  }

  private final String tenantId;
  private final String clientId;
  private final String clientSecret;
  private final String redirectUri;
  private final Transport transport;
  private final ObjectMapper json;
  private final Clock clock;

  private final Map<String, RSAPublicKey> keys = new HashMap<>();
  private Instant keysReadAt = Instant.EPOCH;

  public EntraLogin(
      String tenantId,
      String clientId,
      String clientSecret,
      String redirectUri,
      Transport transport,
      ObjectMapper json,
      Clock clock) {
    // Lower case because that is how the id_token's tid and issuer spell it.
    this.tenantId = tenantId.trim().toLowerCase(Locale.ROOT);
    this.clientId = clientId.trim();
    this.clientSecret = clientSecret;
    this.redirectUri = redirectUri.trim();
    this.transport = transport;
    this.json = json;
    this.clock = clock;
  }

  /** The address registered on the app registration; also where the state cookie is scoped. */
  public String redirectUri() {
    return redirectUri;
  }

  /** A fresh random value for state, nonce and the PKCE verifier: 32 bytes, URL-safe. */
  public static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** The S256 code challenge for a PKCE verifier. */
  public static String challengeFor(String verifier) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing from this JVM", e);
    }
  }

  /** Where to send the browser to sign in. */
  public URI authorizeUri(String state, String nonce, String verifier) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("client_id", clientId);
    query.put("response_type", "code");
    query.put("redirect_uri", redirectUri);
    query.put("response_mode", "query");
    query.put("scope", SCOPE);
    query.put("state", state);
    query.put("nonce", nonce);
    query.put("code_challenge", challengeFor(verifier));
    query.put("code_challenge_method", "S256");
    return URI.create(tenantUrl("oauth2/v2.0/authorize") + "?" + encode(query));
  }

  /**
   * Trades the code Microsoft sent back for an id_token, verifies it, and
   * returns the email it proves, in lower case.
   */
  public String redeem(String code, String verifier, String nonce) throws LoginFailed {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("client_id", clientId);
    form.put("client_secret", clientSecret);
    form.put("grant_type", "authorization_code");
    form.put("code", code);
    form.put("redirect_uri", redirectUri);
    form.put("code_verifier", verifier);
    form.put("scope", SCOPE);

    HttpReply reply;
    try {
      reply = transport.postForm(URI.create(tenantUrl("oauth2/v2.0/token")), form);
    } catch (IOException e) {
      LOG.warn("Microsoft's token endpoint could not be reached: {}", e.getMessage());
      throw new LoginFailed("Microsoft's token endpoint could not be reached", true);
    }
    JsonNode body = parse(reply.body());
    if (reply.status() != 200) {
      // The error code only: the description can echo parts of the request.
      String error = body == null ? "" : body.path("error").asText("");
      LOG.warn("Microsoft refused the code: HTTP {} {}", reply.status(), error);
      // invalid_grant is a code that is stale, reused or not ours: the
      // browser's problem. Anything else (a wrong secret above all) is ours.
      throw new LoginFailed(
          "Microsoft refused the code (" + error + ")", !"invalid_grant".equals(error));
    }
    String idToken = body == null ? null : body.path("id_token").asText(null);
    if (idToken == null || idToken.isBlank()) {
      throw new LoginFailed("Microsoft returned no id_token", true);
    }
    return verifiedEmail(idToken, nonce);
  }

  /** Checks an id_token and returns the email in it. */
  String verifiedEmail(String idToken, String nonce) throws LoginFailed {
    DecodedJWT unverified;
    try {
      unverified = JWT.decode(idToken);
    } catch (JWTDecodeException e) {
      throw new LoginFailed("the id_token is not a JWT", false);
    }
    if (!"RS256".equals(unverified.getAlgorithm())) {
      throw new LoginFailed("the id_token is not signed with RS256", false);
    }
    RSAPublicKey key = signingKey(unverified.getKeyId());
    DecodedJWT token;
    try {
      token =
          JWT.require(Algorithm.RSA256(key, null))
              .withIssuer(AUTHORITY + tenantId + "/v2.0")
              .withAudience(clientId)
              .withClaim("tid", tenantId)
              .withClaim("nonce", nonce)
              .acceptLeeway(60)
              .build()
              .verify(idToken);
    } catch (JWTVerificationException e) {
      LOG.warn("Rejected an id_token: {}", e.getMessage());
      throw new LoginFailed("the id_token did not verify", false);
    }
    String email = token.getClaim("email").asString();
    if (email == null || !email.contains("@")) {
      // A work account without a mail attribute still has its UPN here, which
      // is its email address in every tenant that sends mail.
      email = token.getClaim("preferred_username").asString();
    }
    if (email == null || !email.contains("@") || email.isBlank()) {
      throw new LoginFailed("the id_token carries no email address", false);
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private synchronized RSAPublicKey signingKey(String kid) throws LoginFailed {
    if (kid == null) {
      throw new LoginFailed("the id_token names no signing key", false);
    }
    Instant now = clock.instant();
    boolean stale = keysReadAt.plus(KEYS_FRESH_FOR).isBefore(now);
    boolean unknownAndMayAsk =
        !keys.containsKey(kid) && keysReadAt.plus(KEYS_REFETCH_GAP).isBefore(now);
    if (stale || unknownAndMayAsk) {
      readKeys(now);
    }
    RSAPublicKey key = keys.get(kid);
    if (key == null) {
      throw new LoginFailed("the id_token is signed with a key the tenant does not publish", false);
    }
    return key;
  }

  private void readKeys(Instant now) throws LoginFailed {
    HttpReply reply;
    try {
      reply = transport.get(URI.create(tenantUrl("discovery/v2.0/keys")));
    } catch (IOException e) {
      LOG.warn("Microsoft's signing keys could not be read: {}", e.getMessage());
      if (keys.isEmpty()) {
        throw new LoginFailed("Microsoft's signing keys could not be read", true);
      }
      return;
    }
    JsonNode body = parse(reply.body());
    if (reply.status() != 200 || body == null || !body.path("keys").isArray()) {
      LOG.warn("Microsoft's signing keys came back as HTTP {}", reply.status());
      if (keys.isEmpty()) {
        throw new LoginFailed("Microsoft's signing keys could not be read", true);
      }
      return;
    }
    Map<String, RSAPublicKey> read = new HashMap<>();
    for (JsonNode jwk : body.path("keys")) {
      if (!"RSA".equals(jwk.path("kty").asText()) || !jwk.hasNonNull("kid")) {
        continue;
      }
      try {
        read.put(jwk.get("kid").asText(), rsaKey(jwk.path("n").asText(), jwk.path("e").asText()));
      } catch (GeneralSecurityException | IllegalArgumentException e) {
        LOG.warn("Skipped an unreadable signing key {}", jwk.get("kid").asText());
      }
    }
    keys.clear();
    keys.putAll(read);
    keysReadAt = now;
  }

  static RSAPublicKey rsaKey(String modulus, String exponent) throws GeneralSecurityException {
    Base64.Decoder decoder = Base64.getUrlDecoder();
    RSAPublicKeySpec spec =
        new RSAPublicKeySpec(
            new BigInteger(1, decoder.decode(modulus)), new BigInteger(1, decoder.decode(exponent)));
    return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
  }

  private String tenantUrl(String path) {
    return AUTHORITY + URLEncoder.encode(tenantId, StandardCharsets.UTF_8) + "/" + path;
  }

  private JsonNode parse(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return json.readTree(body);
    } catch (IOException e) {
      return null;
    }
  }

  static String encode(Map<String, String> values) {
    return values.entrySet().stream()
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }

  /** The real transport: the JDK client, no redirects followed, short timeouts. */
  public static Transport httpTransport() {
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    Duration timeout = Duration.ofSeconds(15);
    return new Transport() {
      @Override
      public HttpReply postForm(URI uri, Map<String, String> form) throws IOException {
        HttpRequest request =
            HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                .build();
        return send(client, request);
      }

      @Override
      public HttpReply get(URI uri) throws IOException {
        HttpRequest request =
            HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        return send(client, request);
      }
    };
  }

  static HttpReply send(HttpClient client, HttpRequest request) throws IOException {
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return new HttpReply(response.statusCode(), response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }
}
