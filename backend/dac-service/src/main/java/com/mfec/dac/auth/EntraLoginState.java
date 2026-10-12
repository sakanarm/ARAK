package com.mfec.dac.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * What the browser carries between leaving for Microsoft and coming back: the
 * state, the nonce, the PKCE verifier and the page to return to, in a signed
 * cookie that lives ten minutes.
 *
 * <p>A cookie rather than server memory so that nothing is held for a
 * sign-in somebody abandons. Signed, so the browser cannot choose its own
 * nonce or verifier; and the verifier stays secret all the same because the
 * cookie is HttpOnly and the page never sees it.
 *
 * <p>The key is derived from the session signing secret but is not that key,
 * and the issuer differs, so one of these can never be passed off as a
 * session token or the other way round.
 */
public class EntraLoginState {

  public static final Duration LIFETIME = Duration.ofMinutes(10);

  private static final String ISSUER = "arak-entra-login-state";

  /** The values carried; {@code returnTo} is a path inside the console, or null. */
  public record State(String state, String nonce, String verifier, String returnTo) {}

  private final Algorithm algorithm;
  private final JWTVerifier verifier;
  private final Clock clock;

  public EntraLoginState(String sessionSecret, Clock clock) {
    this.algorithm =
        Algorithm.HMAC256(("entra-login-state:" + sessionSecret).getBytes(StandardCharsets.UTF_8));
    this.verifier = JWT.require(algorithm).withIssuer(ISSUER).build();
    this.clock = clock;
  }

  public String seal(State state) {
    Instant now = clock.instant();
    var builder =
        JWT.create()
            .withIssuer(ISSUER)
            .withIssuedAt(now)
            .withExpiresAt(now.plus(LIFETIME))
            .withClaim("st", state.state())
            .withClaim("nn", state.nonce())
            .withClaim("cv", state.verifier());
    if (state.returnTo() != null) {
      builder.withClaim("to", state.returnTo());
    }
    return builder.sign(algorithm);
  }

  /** The state in a cookie, or empty when it is missing, forged or older than ten minutes. */
  public Optional<State> open(String sealed) {
    if (sealed == null || sealed.isBlank()) {
      return Optional.empty();
    }
    try {
      DecodedJWT jwt = verifier.verify(sealed);
      if (jwt.getExpiresAtAsInstant() == null
          || !jwt.getExpiresAtAsInstant().isAfter(clock.instant())) {
        return Optional.empty();
      }
      String st = jwt.getClaim("st").asString();
      String nn = jwt.getClaim("nn").asString();
      String cv = jwt.getClaim("cv").asString();
      if (st == null || nn == null || cv == null) {
        return Optional.empty();
      }
      return Optional.of(new State(st, nn, cv, jwt.getClaim("to").asString()));
    } catch (JWTVerificationException e) {
      return Optional.empty();
    }
  }
}
