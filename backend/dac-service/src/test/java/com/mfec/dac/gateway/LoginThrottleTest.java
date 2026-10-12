package com.mfec.dac.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

  /** A clock the test moves by hand. */
  private static final class Hand extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

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

  @Test
  @DisplayName("Turns an address away after too many failures, until they age out")
  void throttles() {
    Hand clock = new Hand();
    LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(15), clock);
    String address = "192.0.2.10";
    for (int i = 0; i < 3; i++) {
      assertThat(throttle.allowed(address)).isTrue();
      throttle.fail(address);
    }
    assertThat(throttle.allowed(address)).isFalse();
    assertThat(throttle.allowed("192.0.2.11")).isTrue();
    clock.now = clock.now.plus(Duration.ofMinutes(16));
    assertThat(throttle.allowed(address)).isTrue();
  }

  @Test
  @DisplayName("A successful sign-in clears the address")
  void successClears() {
    Hand clock = new Hand();
    LoginThrottle throttle = new LoginThrottle(2, Duration.ofMinutes(15), clock);
    throttle.fail("192.0.2.20");
    throttle.succeed("192.0.2.20");
    throttle.fail("192.0.2.20");
    assertThat(throttle.allowed("192.0.2.20")).isTrue();
  }
}
