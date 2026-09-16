package com.mfec.dac.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Issues and verifies the console session token.
 *
 * <p>HMAC-SHA256 with a shared secret, because the issuer and the verifier are
 * the same process. When Entra ID login lands (FR-2.1) its tokens arrive signed
 * with the tenant keys and are verified against the JWKS instead; this class
 * keeps its shape so that both can be checked by the same filter, and the
 * {@code iss} claim is what tells them apart.
 *
 * <p>Application roles are put in the token so an authorisation check costs no
 * database round trip. The consequence is that a role revoked mid-session stays
 * effective until the token expires, which is why the default lifetime is short
 * and why anything genuinely destructive should re-read {@code
 * app_role_assignment} rather than trust the claim.
 */
public class JwtService {

  /** Claim carrying the application roles; named after OpenMetadata's own. */
  public static final String CLAIM_ROLES = "roles";

  public static final String CLAIM_SOURCE = "src";
  public static final String CLAIM_EMAIL = "email";
  public static final String CLAIM_NAME = "name";
  public static final String CLAIM_SCOPES = "scopes";

  private final Algorithm algorithm;
  private final JWTVerifier verifier;
  private final String issuer;
  private final Duration ttl;

  public JwtService(String secret, String issuer, Duration ttl) {
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      // HMAC-SHA256 with a key shorter than its block output is a weaker
      // signature than it looks like, and this is the one place we can still
      // refuse it. A deployment that starts with a guessable secret is a
      // deployment where anyone can mint an admin token.
      throw new IllegalArgumentException(
          "identity.jwtSecret must be set to at least 32 bytes; "
              + "generate one with: openssl rand -base64 48");
    }
    this.algorithm = Algorithm.HMAC256(secret.getBytes(StandardCharsets.UTF_8));
    this.issuer = issuer;
    this.ttl = ttl;
    this.verifier = JWT.require(algorithm).withIssuer(issuer).acceptLeeway(30).build();
  }

  public Duration ttl() {
    return ttl;
  }

  public String issue(AuthenticatedUser user) {
    Instant now = Instant.now();
    return JWT.create()
        .withIssuer(issuer)
        .withSubject(user.id().toString())
        .withIssuedAt(now)
        .withExpiresAt(now.plus(ttl))
        .withJWTId(UUID.randomUUID().toString())
        .withClaim(CLAIM_NAME, user.displayName())
        .withClaim(CLAIM_EMAIL, user.email())
        .withClaim(CLAIM_SOURCE, user.source())
        .withClaim("preferred_username", user.username())
        .withArrayClaim(CLAIM_ROLES, user.appRoles().toArray(String[]::new))
        .withArrayClaim(CLAIM_SCOPES, user.scopes().toArray(String[]::new))
        .sign(algorithm);
  }

  /**
   * Reads a token back into a user, or returns empty when it is not one of ours.
   *
   * <p>Every failure — bad signature, wrong issuer, expired, malformed subject —
   * collapses to the same empty result on purpose. Telling a caller which of
   * those it was tells an attacker which part of the forgery to fix.
   */
  public Optional<AuthenticatedUser> verify(String token) {
    try {
      DecodedJWT jwt = verifier.verify(token);
      UUID id = UUID.fromString(jwt.getSubject());
      List<String> roles = jwt.getClaim(CLAIM_ROLES).asList(String.class);
      List<String> scopes = jwt.getClaim(CLAIM_SCOPES).asList(String.class);
      Set<String> roleSet =
          roles == null ? Set.of() : new LinkedHashSet<>(roles);
      return Optional.of(
          new AuthenticatedUser(
              id,
              jwt.getClaim("preferred_username").asString(),
              jwt.getClaim(CLAIM_EMAIL).asString(),
              jwt.getClaim(CLAIM_NAME).asString(),
              jwt.getClaim(CLAIM_SOURCE).asString(),
              roleSet,
              scopes == null ? List.of() : List.copyOf(scopes)));
    } catch (JWTVerificationException | IllegalArgumentException | NullPointerException e) {
      return Optional.empty();
    }
  }
}
