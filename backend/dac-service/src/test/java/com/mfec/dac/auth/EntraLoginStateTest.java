package com.mfec.dac.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EntraLoginStateTest {

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

  private static final String SECRET = "a-test-signing-key-long-enough-to-be-accepted";

  private static final EntraLoginState.State HELD =
      new EntraLoginState.State("the-state", "the-nonce", "the-verifier", "/policies");

  @Test
  @DisplayName("what is sealed opens to the same values")
  void roundTrip() {
    EntraLoginState states = new EntraLoginState(SECRET, new Hand());

    assertThat(states.open(states.seal(HELD))).contains(HELD);
    EntraLoginState.State noReturn = new EntraLoginState.State("s", "n", "v", null);
    assertThat(states.open(states.seal(noReturn))).contains(noReturn);
  }

  @Test
  @DisplayName("a cookie changed in the browser, or sealed with another key, opens to nothing")
  void tampered() {
    EntraLoginState states = new EntraLoginState(SECRET, new Hand());
    String sealed = states.seal(HELD);
    String[] parts = sealed.split("\\.");
    String forged = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();

    assertThat(states.open(forged)).isEmpty();
    assertThat(states.open(new EntraLoginState("another-secret-long-enough-to-be-used", new Hand())
            .seal(HELD)))
        .isEmpty();
    assertThat(states.open("")).isEmpty();
    assertThat(states.open(null)).isEmpty();
    assertThat(states.open("not a jwt")).isEmpty();
  }

  @Test
  @DisplayName("a cookie older than ten minutes opens to nothing")
  void expires() {
    Hand clock = new Hand();
    EntraLoginState states = new EntraLoginState(SECRET, clock);
    String sealed = states.seal(HELD);

    clock.now = clock.now.plus(EntraLoginState.LIFETIME);

    assertThat(states.open(sealed)).isEmpty();
  }

  @Test
  @DisplayName("a session token signed with the same secret is not a sign-in state")
  void notASession() {
    JwtService tokens = new JwtService(SECRET, "data-access-control", Duration.ofHours(1));
    String session =
        tokens.issue(
            new AuthenticatedUser(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                "analyst_a",
                "analyst_a@example.com",
                "Analyst A",
                "local",
                Set.of("AUDITOR"),
                List.of()));

    assertThat(new EntraLoginState(SECRET, new Hand()).open(session)).isEmpty();
  }
}
