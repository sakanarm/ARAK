package com.mfec.dac.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SsoCodeStoreTest {

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

  private static final UUID ANALYST = UUID.fromString("11111111-2222-3333-4444-555555555555");

  @Test
  @DisplayName("a code is traded once for the person it was issued to")
  void singleUse() {
    SsoCodeStore codes = new SsoCodeStore(new Hand());
    String code = codes.issue(ANALYST).orElseThrow();

    assertThat(codes.redeem(code)).contains(ANALYST);
    assertThat(codes.redeem(code)).isEmpty();
  }

  @Test
  @DisplayName("a code is gone a minute after it was issued")
  void expires() {
    Hand clock = new Hand();
    SsoCodeStore codes = new SsoCodeStore(clock);
    String code = codes.issue(ANALYST).orElseThrow();

    clock.now = clock.now.plus(SsoCodeStore.LIFETIME);

    assertThat(codes.redeem(code)).isEmpty();
  }

  @Test
  @DisplayName("an unknown, blank or missing code opens nothing")
  void unknown() {
    SsoCodeStore codes = new SsoCodeStore(new Hand());
    codes.issue(ANALYST);

    assertThat(codes.redeem("not-a-code-we-issued")).isEmpty();
    assertThat(codes.redeem("")).isEmpty();
    assertThat(codes.redeem(null)).isEmpty();
  }

  @Test
  @DisplayName("every code is different")
  void distinct() {
    SsoCodeStore codes = new SsoCodeStore(new Hand());

    assertThat(codes.issue(ANALYST).orElseThrow()).isNotEqualTo(codes.issue(ANALYST).orElseThrow());
  }
}
