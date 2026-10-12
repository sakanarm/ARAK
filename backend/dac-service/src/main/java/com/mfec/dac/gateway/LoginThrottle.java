package com.mfec.dac.gateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing at the gateway: an address that fails to sign
 * in too often within a window is turned away until the oldest failure ages
 * out.
 *
 * <p>Counted per address, never per account. Locking an account after failed
 * tries would let anyone who knows a username lock its owner out, and an
 * access token is too long to guess anyway; what this stops is somebody
 * hammering the port. Held in memory, so a restart forgets it, which is the
 * right trade for something that only ever delays.
 */
final class LoginThrottle {

  private final int maxFailures;
  private final Duration window;
  private final Clock clock;
  private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

  LoginThrottle(int maxFailures, Duration window, Clock clock) {
    this.maxFailures = maxFailures;
    this.window = window;
    this.clock = clock;
  }

  /** Whether this address may try to sign in now. */
  boolean allowed(String address) {
    Deque<Instant> seen = failures.get(address);
    if (seen == null) {
      return true;
    }
    synchronized (seen) {
      prune(seen);
      return seen.size() < maxFailures;
    }
  }

  void fail(String address) {
    Deque<Instant> seen = failures.computeIfAbsent(address, a -> new ArrayDeque<>());
    synchronized (seen) {
      prune(seen);
      seen.addLast(clock.instant());
      while (seen.size() > maxFailures) {
        seen.removeFirst();
      }
    }
    if (failures.size() > 10_000) {
      sweep();
    }
  }

  /** A successful sign-in clears the address. */
  void succeed(String address) {
    failures.remove(address);
  }

  private void prune(Deque<Instant> seen) {
    Instant cutoff = clock.instant().minus(window);
    while (!seen.isEmpty() && seen.peekFirst().isBefore(cutoff)) {
      seen.removeFirst();
    }
  }

  /** Drops addresses whose failures have all aged out, so the map cannot grow without end. */
  private void sweep() {
    failures
        .entrySet()
        .removeIf(
            e -> {
              synchronized (e.getValue()) {
                prune(e.getValue());
                return e.getValue().isEmpty();
              }
            });
  }
}
