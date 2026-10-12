package com.mfec.dac.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-time codes that carry a finished Microsoft sign-in from the callback to
 * the console.
 *
 * <p>The callback is a browser redirect, so it cannot hand the console a
 * session token without putting it in an address, where it would sit in the
 * browser's history and the proxy's log for as long as it is valid. It puts
 * this code there instead: random, good for one minute, and gone the first
 * time it is traded.
 *
 * <p>In memory, because the service runs as one process and a code outlives
 * a restart by at most a minute; a sign-in caught by a restart is repeated.
 */
public class SsoCodeStore {

  static final Duration LIFETIME = Duration.ofSeconds(60);

  /** Far more than one minute of sign-ins; past it, new codes are refused rather than memory spent. */
  private static final int MAX_PENDING = 10_000;

  private record Pending(UUID principalId, Instant expiresAt) {}

  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private final Clock clock;

  public SsoCodeStore(Clock clock) {
    this.clock = clock;
  }

  /** A new code for a principal who has just signed in, or empty when too many are waiting. */
  public Optional<String> issue(UUID principalId) {
    Instant now = clock.instant();
    pending.values().removeIf(p -> !p.expiresAt().isAfter(now));
    if (pending.size() >= MAX_PENDING) {
      return Optional.empty();
    }
    String code = EntraLogin.randomToken();
    pending.put(code, new Pending(principalId, now.plus(LIFETIME)));
    return Optional.of(code);
  }

  /** The principal a code was issued for, once; empty when unknown, used or expired. */
  public Optional<UUID> redeem(String code) {
    if (code == null || code.isBlank()) {
      return Optional.empty();
    }
    Pending found = pending.remove(code);
    if (found == null || !found.expiresAt().isAfter(clock.instant())) {
      return Optional.empty();
    }
    return Optional.of(found.principalId());
  }
}
