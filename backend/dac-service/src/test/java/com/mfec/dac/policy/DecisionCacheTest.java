package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.schema.api.PolicyDecision;
import io.dropwizard.jackson.Jackson;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the decision cache is allowed to hand back (FR-5.5).
 *
 * <p>The failure this class exists to prevent is one that no exception reports:
 * an entry that outlives the answer it holds, and goes on saying "allowed" after
 * a policy was switched off or an evening came. So most of what is asserted here
 * is that something is <em>not</em> returned.
 */
class DecisionCacheTest {

  private static final Instant NOON = Instant.parse("2026-09-15T05:00:00Z");

  private static final DecisionCache.Key KEY =
      new DecisionCache.Key("analyst_a", "prod.SalesDB.dbo.customer", "prod", null, null);

  /**
   * The mapper the application builds, not a bare one.
   *
   * <p>A plain {@code new ObjectMapper()} cannot write an {@link Instant}, so
   * every put would fail and the cache would hold nothing -- and because a
   * cache that holds nothing still answers every question correctly, the whole
   * suite would go green on a cache that does not work. The mapper is part of
   * what is under test here.
   */
  private static ObjectMapper mapper() {
    return Jackson.newObjectMapper();
  }

  private static DecisionCache cache() {
    return new DecisionCache(mapper(), true, 1000, Duration.ofSeconds(60));
  }

  private static PolicyDecision decision(boolean allowed) {
    return new PolicyDecision()
        .withPrincipal("analyst_a")
        .withAssetFqn("prod.SalesDB.dbo.customer")
        .withAllowed(allowed)
        .withEvaluatedAt(NOON)
        .withCacheKey("k");
  }

  /** The ordinary case: read the generation, evaluate, store. */
  private static void store(DecisionCache cache, DecisionCache.Key key, PolicyDecision decision,
      Instant now, Instant expiresAt) {
    cache.put(key, decision, now, expiresAt, cache.generation());
  }

  @Nested
  @DisplayName("holding and returning")
  class Basics {

    @Test
    @DisplayName("a held decision comes back, marked as having come from cache")
    void hit() {
      DecisionCache cache = cache();
      store(cache, KEY, decision(true), NOON, null);

      Optional<PolicyDecision> held = cache.get(KEY, NOON.plusSeconds(1));

      assertThat(held).isPresent();
      assertThat(held.get().getAllowed()).isTrue();
      assertThat(held.get().getFromCache()).isTrue();
      assertThat(cache.stats().hits()).isEqualTo(1);
    }

    @Test
    @DisplayName("each caller gets its own object, so one cannot edit what the next reads")
    void callersDoNotShareAnObject() {
      // PolicyDecision.withX mutates in place and returns this. If entries were
      // stored as objects rather than bytes, the first caller marking fromCache
      // would permanently mark the stored copy, and anything else it touched
      // would follow.
      DecisionCache cache = cache();
      store(cache, KEY, decision(true), NOON, null);

      PolicyDecision first = cache.get(KEY, NOON).orElseThrow();
      first.withAllowed(false).withPrincipal("somebody_else");
      PolicyDecision second = cache.get(KEY, NOON).orElseThrow();

      assertThat(second.getAllowed()).isTrue();
      assertThat(second.getPrincipal()).isEqualTo("analyst_a");
    }

    @Test
    @DisplayName("a request from a different address is a different question")
    void contextIsPartOfTheKey() {
      DecisionCache cache = cache();
      store(cache, KEY, decision(true), NOON, null);

      DecisionCache.Key fromElsewhere =
          new DecisionCache.Key(
              KEY.principal(), KEY.assetFqn(), KEY.environment(), "203.0.113.9", null);

      assertThat(cache.get(fromElsewhere, NOON)).isEmpty();
    }

    @Test
    @DisplayName("a disabled cache holds nothing and answers nothing")
    void disabled() {
      DecisionCache cache = DecisionCache.disabled();
      store(cache, KEY, decision(true), NOON, null);

      assertThat(cache.get(KEY, NOON)).isEmpty();
      assertThat(cache.stats().entries()).isZero();
      assertThat(cache.enabled()).isFalse();
    }
  }

  @Nested
  @DisplayName("expiry")
  class Expiry {

    @Test
    @DisplayName("an entry ends at the instant it was given, not a second later")
    void expiresAtIsHonoured() {
      // The time-to-live is deliberately long here so that the boundary under
      // test is the one the policy gave, not the backstop. A grant written to
      // end at 17:00:00 has to end at 17:00:00; ending at 17:00:59 because
      // some coarser clock rounded it is a minute of access nobody granted.
      DecisionCache cache = new DecisionCache(mapper(), true, 10, Duration.ofHours(1));
      Instant sixPm = NOON.plusSeconds(600);
      store(cache, KEY, decision(true), NOON, sixPm);

      assertThat(cache.get(KEY, sixPm.minusSeconds(1))).isPresent();
      assertThat(cache.get(KEY, sixPm)).isEmpty();
      assertThat(cache.stats().stale()).isEqualTo(1);
    }

    @Test
    @DisplayName("the time-to-live bounds an entry nothing else would end")
    void ttlIsTheBackstop() {
      DecisionCache cache = cache();
      store(cache, KEY, decision(true), NOON, null);

      assertThat(cache.get(KEY, NOON.plusSeconds(59))).isPresent();
      assertThat(cache.get(KEY, NOON.plusSeconds(60))).isEmpty();
    }

    @Test
    @DisplayName("an expiry sooner than the time-to-live wins")
    void earlierOfTheTwo() {
      DecisionCache cache = cache();
      store(cache, KEY, decision(true), NOON, NOON.plusSeconds(5));

      assertThat(cache.get(KEY, NOON.plusSeconds(6))).isEmpty();
    }

    @Test
    @DisplayName("a decision that was already over is not held at all")
    void alreadyExpiredIsNotStored() {
      DecisionCache cache = cache();
      store(cache, KEY, decision(true), NOON, NOON);

      assertThat(cache.stats().entries()).isZero();
      assertThat(cache.get(KEY, NOON)).isEmpty();
    }
  }

  @Nested
  @DisplayName("invalidation")
  class Invalidation {

    @Test
    @DisplayName("a flush empties the cache and says why")
    void flush() {
      DecisionCache cache = cache();
      store(cache, KEY, decision(true), NOON, null);

      cache.invalidateAll("policy pii-mask moved to DISABLED");

      assertThat(cache.get(KEY, NOON)).isEmpty();
      assertThat(cache.stats().entries()).isZero();
      assertThat(cache.stats().bytes()).isZero();
      assertThat(cache.stats().invalidations()).isEqualTo(1);
      assertThat(cache.stats().lastInvalidationReason())
          .isEqualTo("policy pii-mask moved to DISABLED");
    }

    @Test
    @DisplayName("an evaluation that began before a flush cannot land its answer after it")
    void aLappedEvaluationIsDropped() {
      // The race the generation counter exists for. Thread A reads the policy
      // stack at 17:59:59; thread B disables a policy and flushes at 18:00:00;
      // thread A finishes and tries to store, at 18:00:01, an answer computed
      // from policies that no longer apply. Storing it would be a flush that
      // appears to have worked and did not.
      DecisionCache cache = cache();
      long readAt = cache.generation();

      cache.invalidateAll("policy disabled while the evaluation was in flight");
      cache.put(KEY, decision(true), NOON, null, readAt);

      assertThat(cache.get(KEY, NOON)).isEmpty();
      assertThat(cache.stats().entries()).isZero();
      assertThat(cache.stats().lapped()).isEqualTo(1);
    }

    @Test
    @DisplayName("an evaluation that began after the flush is stored as normal")
    void anEvaluationAfterTheFlushIsKept() {
      DecisionCache cache = cache();
      cache.invalidateAll("policy disabled");

      store(cache, KEY, decision(false), NOON, null);

      assertThat(cache.get(KEY, NOON)).isPresent();
      assertThat(cache.stats().lapped()).isZero();
      assertThat(cache.stats().generation()).isEqualTo(1);
    }

    @Test
    @DisplayName("an entry written before a flush is not served after it")
    void entriesFromBeforeAFlushAreRejected() {
      DecisionCache cache = cache();
      store(cache, KEY, decision(true), NOON, null);

      cache.invalidateAll("policy edited");

      assertThat(cache.get(KEY, NOON)).isEmpty();
    }
  }

  @Nested
  @DisplayName("memory")
  class Memory {

    @Test
    @DisplayName("the cap evicts the least recently used, and the byte count follows")
    void lruCap() {
      DecisionCache cache = new DecisionCache(mapper(), true, 2, Duration.ofSeconds(60));
      DecisionCache.Key a = new DecisionCache.Key("a", "t", "prod", null, null);
      DecisionCache.Key b = new DecisionCache.Key("b", "t", "prod", null, null);
      DecisionCache.Key c = new DecisionCache.Key("c", "t", "prod", null, null);

      store(cache, a, decision(true), NOON, null);
      store(cache, b, decision(true), NOON, null);
      cache.get(a, NOON); // a becomes the most recently used, b the least
      store(cache, c, decision(true), NOON, null);

      assertThat(cache.stats().entries()).isEqualTo(2);
      assertThat(cache.stats().evictions()).isEqualTo(1);
      assertThat(cache.get(b, NOON)).isEmpty();
      assertThat(cache.get(a, NOON)).isPresent();
      assertThat(cache.get(c, NOON)).isPresent();
      assertThat(cache.stats().bytes()).isPositive();
    }

    @Test
    @DisplayName("a decision that cannot be serialised is counted, not just logged")
    void unstorableIsVisible() {
      // The failure this test exists for is the quiet one: a mapper without
      // the JSR-310 module makes every put a no-op, and a cache that holds
      // nothing is never wrong about anything. Without a number on the stats
      // endpoint there is nothing to look at.
      DecisionCache cache = new DecisionCache(new ObjectMapper(), true, 10, Duration.ofSeconds(60));

      store(cache, KEY, decision(true), NOON, null);

      assertThat(cache.get(KEY, NOON)).isEmpty();
      assertThat(cache.stats().entries()).isZero();
      assertThat(cache.stats().unstorable()).isEqualTo(1);
    }

    @Test
    @DisplayName("replacing an entry does not double-count its bytes")
    void replaceKeepsTheByteCountHonest() {
      DecisionCache cache = cache();
      store(cache, KEY, decision(true), NOON, null);
      long once = cache.stats().bytes();

      store(cache, KEY, decision(true), NOON, null);

      assertThat(cache.stats().entries()).isEqualTo(1);
      assertThat(cache.stats().bytes()).isEqualTo(once);
    }
  }
}
