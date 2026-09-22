package com.mfec.dac.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.schema.api.PolicyDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The decision cache (FR-5.5), and the only place a decision is reused.
 *
 * <p>A cold decision is three database reads and a JSON parse: the principal
 * with every group they reach through, the asset with its columns and every
 * facet that landed on it, and the composed policy stack for that asset in that
 * environment. The evaluation itself is arithmetic in memory and costs almost
 * nothing. So the budget in NFR-2 — under 100ms cold, under 10ms warm — is a
 * statement about those three reads, and this class is what removes them.
 *
 * <h2>Three ways an entry dies</h2>
 *
 * <ol>
 *   <li><b>Somebody wrote something.</b> Every store that can move an input to a
 *       decision announces it through a {@link com.mfec.dac.common.ChangeNotifier},
 *       and the announcement empties the cache. It is a single global flush
 *       rather than a surgical eviction, and that is the deliberate choice: the
 *       map from "this policy changed" to "these principals on these assets are
 *       now wrong" runs through selectors, group closures and facet inheritance,
 *       and a version of it that is subtly incomplete does not throw — it
 *       quietly keeps letting somebody in. Flushing everything costs a few
 *       hundred re-evaluations after an edit, which is a price worth paying to
 *       never have to be right about that map.
 *   <li><b>The clock moved past a boundary.</b> Nothing announces 18:00, so
 *       {@link com.mfec.dac.engine.DecisionValidity} reads the stack the decision
 *       was made from and says when it stops being the answer.
 *   <li><b>The time-to-live ran out.</b> The backstop for anything the first two
 *       miss — a write path added later that forgets to announce itself, a
 *       time-dependent term a future schema introduces. It bounds how wrong this
 *       cache can be to a number an operator chose, instead of to forever.
 * </ol>
 *
 * <h2>Why entries are stored as JSON rather than as objects</h2>
 *
 * <p>{@link PolicyDecision} is a generated bean whose {@code withX} methods
 * mutate in place and return {@code this}. Handing the same instance to two
 * callers would mean the first one to touch it edits what the second one reads,
 * and the very first thing a caller does here is mark it as served from cache.
 * Serializing on write and deserializing on read costs tens of microseconds
 * against a budget of ten milliseconds and buys an object per caller that cannot
 * corrupt anything. It also makes the memory this holds a number that can be
 * reported rather than guessed at.
 *
 * <h2>Single process</h2>
 *
 * <p>The generation counter and the map are in this JVM. Run two instances and
 * an edit on one does not flush the other, which is what the time-to-live then
 * bounds. The answer when this is scaled out is a shared stamp the instances
 * read — that is a change to this class, not to its callers.
 */
public final class DecisionCache {

  private static final Logger LOG = LoggerFactory.getLogger(DecisionCache.class);

  public static final int DEFAULT_MAX_ENTRIES = 50_000;
  public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

  /**
   * Everything the caller supplied that a decision can turn on.
   *
   * <p>{@code ip} and {@code purpose} are in here because a context rule reads
   * them, and leaving them out would serve an office-hours-and-office-network
   * decision to the same person dialling in from elsewhere.
   */
  public record Key(
      String principal, String assetFqn, String environment, String ip, String purpose) {}

  /** What an operator needs to see to know whether this is helping or lying. */
  public record Stats(
      boolean enabled,
      int entries,
      int maxEntries,
      long ttlSeconds,
      long bytes,
      long hits,
      long misses,
      long stale,
      long evictions,
      long invalidations,
      long lapped,
      long unstorable,
      long generation,
      Instant lastInvalidatedAt,
      String lastInvalidationReason) {}

  private record Entry(byte[] payload, Instant validUntil, long generation) {}

  private final ObjectMapper json;
  private final boolean enabled;
  private final int maxEntries;
  private final Duration ttl;

  private final AtomicLong generation = new AtomicLong();
  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong misses = new AtomicLong();
  private final AtomicLong stale = new AtomicLong();
  private final AtomicLong evictions = new AtomicLong();
  private final AtomicLong invalidations = new AtomicLong();
  private final AtomicLong lapped = new AtomicLong();
  private final AtomicLong unstorable = new AtomicLong();
  private volatile Instant lastInvalidatedAt;
  private volatile String lastInvalidationReason;

  private long bytes;
  private final Map<Key, Entry> entries;

  public DecisionCache(ObjectMapper json, boolean enabled, int maxEntries, Duration ttl) {
    this.json = json;
    this.enabled = enabled;
    this.maxEntries = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
    this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
    this.entries =
        new LinkedHashMap<>(256, 0.75f, true) {
          private static final long serialVersionUID = 1L;

          @Override
          protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
            if (size() <= DecisionCache.this.maxEntries) {
              return false;
            }
            bytes -= eldest.getValue().payload().length;
            evictions.incrementAndGet();
            return true;
          }
        };
  }

  /** A cache that never holds anything, for tests and for turning it off. */
  public static DecisionCache disabled() {
    return new DecisionCache(new ObjectMapper(), false, DEFAULT_MAX_ENTRIES, DEFAULT_TTL);
  }

  public boolean enabled() {
    return enabled;
  }

  /**
   * The current generation, to be read <em>before</em> the inputs to a decision
   * are and handed back to {@link #put}.
   *
   * <p>This is the whole of the race protection, and it only works in that
   * order. A caller that read this after evaluating would be reporting the
   * generation its answer landed in rather than the one it was computed from,
   * and a flush that happened in between would be invisible.
   */
  public long generation() {
    return generation.get();
  }

  /**
   * The decision held for this key, if one is held and is still true.
   *
   * <p>Every way this can fail — no entry, an entry from before the last flush,
   * an entry the clock has moved past, a payload that will not parse — produces
   * an empty result, which sends the caller to the engine. Nothing here can turn
   * a denial into an allow.
   */
  public Optional<PolicyDecision> get(Key key, Instant now) {
    if (!enabled || key == null || now == null) {
      return Optional.empty();
    }
    Entry entry;
    synchronized (entries) {
      entry = entries.get(key);
      if (entry == null) {
        misses.incrementAndGet();
        return Optional.empty();
      }
      if (entry.generation() != generation.get() || !now.isBefore(entry.validUntil())) {
        entries.remove(key);
        bytes -= entry.payload().length;
        stale.incrementAndGet();
        misses.incrementAndGet();
        return Optional.empty();
      }
    }
    try {
      PolicyDecision decision = json.readValue(entry.payload(), PolicyDecision.class);
      hits.incrementAndGet();
      return Optional.of(decision.withFromCache(true));
    } catch (Exception e) {
      // A payload this process wrote and cannot read again is a bug, not a
      // reason to answer wrongly. Drop it and let the engine decide.
      synchronized (entries) {
        Entry removed = entries.remove(key);
        if (removed != null) {
          bytes -= removed.payload().length;
        }
      }
      misses.incrementAndGet();
      LOG.warn("Discarded an unreadable decision cache entry for {}", key.assetFqn(), e);
      return Optional.empty();
    }
  }

  /**
   * Holds a decision until the earlier of its own expiry and the time-to-live.
   *
   * @param expiresAt when the clock alone would change this answer, or null when
   *     nothing in the stack depends on the clock
   * @param readAt the value {@link #generation()} had before this decision's
   *     inputs were read. A flush since then means this answer was computed
   *     from policies that no longer apply, and it is dropped rather than
   *     stored -- the one case where a cache would otherwise outlive the write
   *     that was meant to end it.
   */
  public void put(Key key, PolicyDecision decision, Instant now, Instant expiresAt, long readAt) {
    if (!enabled || key == null || decision == null || now == null) {
      return;
    }
    if (readAt != generation.get()) {
      lapped.incrementAndGet();
      return;
    }
    Instant deadline = now.plus(ttl);
    if (expiresAt != null && expiresAt.isBefore(deadline)) {
      deadline = expiresAt;
    }
    if (!deadline.isAfter(now)) {
      // Already over by the time it was computed: holding it would serve one
      // stale answer before the first check could reject it.
      return;
    }
    byte[] payload;
    try {
      payload = json.writeValueAsBytes(decision);
    } catch (Exception e) {
      // Counted as well as logged. A mapper that cannot write a
      // PolicyDecision -- one without the JSR-310 module, say -- makes every
      // put a no-op, and a cache that holds nothing is a cache that is never
      // wrong. Nothing else in the process would notice, so the only way it
      // becomes findable is a number on the stats endpoint. The stack trace
      // goes out once; after that the reason is already in the log and the
      // count is what matters.
      if (unstorable.getAndIncrement() == 0) {
        LOG.warn("Could not store a decision for {} in the cache", key.assetFqn(), e);
      }
      return;
    }
    synchronized (entries) {
      // Re-checked inside the lock against the same generation the caller read,
      // because a flush can land between the check above and this line.
      if (readAt != generation.get()) {
        lapped.incrementAndGet();
        return;
      }
      Entry previous = entries.put(key, new Entry(payload, deadline, readAt));
      if (previous != null) {
        bytes -= previous.payload().length;
      }
      bytes += payload.length;
    }
  }

  /**
   * Empties the cache because something a decision depends on changed.
   *
   * <p>Emptying the map is only half of it. The other half is the generation
   * bump, which is what stops an evaluation that was already in flight from
   * landing its answer afterwards: that caller read the generation before it
   * read any policy, and {@link #put} refuses anything whose generation no
   * longer matches. Without it, a decision computed at 17:59:59 from a policy
   * disabled at 18:00:00 would be stored at 18:00:01 and served for a minute --
   * a flush that appears to have worked and did not.
   */
  public void invalidateAll(String reason) {
    if (!enabled) {
      return;
    }
    generation.incrementAndGet();
    invalidations.incrementAndGet();
    lastInvalidatedAt = Instant.now();
    lastInvalidationReason = reason;
    int dropped;
    synchronized (entries) {
      dropped = entries.size();
      entries.clear();
      bytes = 0;
    }
    if (dropped > 0) {
      LOG.debug("Decision cache flushed: {} entries dropped after {}", dropped, reason);
    }
  }

  public Stats stats() {
    int size;
    long held;
    synchronized (entries) {
      size = entries.size();
      held = bytes;
    }
    return new Stats(
        enabled,
        size,
        maxEntries,
        ttl.toSeconds(),
        held,
        hits.get(),
        misses.get(),
        stale.get(),
        evictions.get(),
        invalidations.get(),
        lapped.get(),
        unstorable.get(),
        generation.get(),
        lastInvalidatedAt,
        lastInvalidationReason);
  }
}
