package com.mfec.dac.policy;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * How many statements the Query API has out at the sources at once (FR-6.3
 * concurrency limit).
 *
 * <p>Three ceilings, all of which must have room before a statement is sent:
 *
 * <ul>
 *   <li><b>the service</b>, because every read holds a request thread and a
 *       result in memory until it returns;
 *   <li><b>one source</b>, because each read is a connection to somebody
 *       else's database, often a server other applications share, and ARAK
 *       should never be the reason it runs out of connections;
 *   <li><b>one caller</b>, so that a single person — or a single dashboard
 *       firing every tile at once — cannot take every slot the other two
 *       leave.
 * </ul>
 *
 * <p>A statement that finds no room waits a short while for one, since a
 * double click or a dashboard refresh usually clears in well under a second,
 * and is then refused as busy. Refused rather than queued without end: a queue
 * nobody bounds is a timeout moved somewhere nobody is looking.
 *
 * <p>Only reads that go to a source take a slot. A refusal by policy never
 * gets this far, and a result answered from {@link QueryResultCache} costs the
 * source nothing, so neither is limited.
 *
 * <p>Per process: with several instances each keeps its own counts, and the
 * source-wide ceiling becomes one per instance. That is stated rather than
 * solved, because the deployment today is one instance.
 */
public final class QueryAdmission {

  /** Which ceiling was full; the message a caller reads depends on it. */
  public enum Scope {
    CALLER,
    SOURCE,
    SERVICE
  }

  /** There was no room within the wait; nothing was sent. */
  public static final class BusyException extends RuntimeException {
    private final Scope scope;
    private final int limit;
    private final int retryAfterSeconds;

    BusyException(Scope scope, int limit, int retryAfterSeconds) {
      super(scope + " is at its limit of " + limit + " queries at once");
      this.scope = scope;
      this.limit = limit;
      this.retryAfterSeconds = retryAfterSeconds;
    }

    public Scope scope() {
      return scope;
    }

    public int limit() {
      return limit;
    }

    public int retryAfterSeconds() {
      return retryAfterSeconds;
    }
  }

  /** A held slot; closing it more than once is harmless. */
  public interface Permit extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * @param running reads out at a source right now
   * @param waiting statements waiting for a slot right now
   * @param queued statements that had to wait at all, since start
   * @param throttled statements refused as busy, since start, by the ceiling
   *     that was full
   */
  public record Stats(
      boolean enabled,
      int maxConcurrent,
      int maxPerSource,
      int maxPerCaller,
      long queueWaitMillis,
      int running,
      int waiting,
      long admitted,
      long queued,
      long throttled,
      Map<Scope, Long> throttledBy) {}

  private static final Permit NONE = () -> {};

  private final boolean enabled;
  private final int maxConcurrent;
  private final int maxPerSource;
  private final int maxPerCaller;
  private final Duration wait;

  private final Map<UUID, Integer> bySource = new HashMap<>();
  private final Map<String, Integer> byCaller = new HashMap<>();
  private final EnumMap<Scope, Long> throttledBy = new EnumMap<>(Scope.class);
  private int running;
  private int waiting;
  private long admitted;
  private long queued;
  private long throttled;

  /**
   * @param wait how long a statement may wait for room before it is refused;
   *     zero refuses at once
   */
  public QueryAdmission(
      boolean enabled, int maxConcurrent, int maxPerSource, int maxPerCaller, Duration wait) {
    this.enabled = enabled;
    this.maxConcurrent = Math.max(1, maxConcurrent);
    this.maxPerSource = Math.max(1, maxPerSource);
    this.maxPerCaller = Math.max(1, maxPerCaller);
    this.wait = wait == null || wait.isNegative() ? Duration.ZERO : wait;
  }

  /** Lets everything through and counts nothing. */
  public static QueryAdmission unlimited() {
    return new QueryAdmission(false, 1, 1, 1, Duration.ZERO);
  }

  /**
   * Takes a slot for one read of {@code source} on behalf of {@code caller},
   * waiting up to the configured time for one to free up.
   *
   * @param caller the signed-in account sending the statement — not the
   *     principal it runs as, since the load is the sender's
   * @throws BusyException when there was still no room at the end of the wait
   */
  public synchronized Permit admit(UUID source, String caller) {
    if (!enabled) {
      return NONE;
    }
    String who = caller == null ? "" : caller.toLowerCase(Locale.ROOT);
    long deadline = System.nanoTime() + wait.toNanos();
    boolean counted = false;
    try {
      Scope full;
      while ((full = full(source, who)) != null) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw refuse(full);
        }
        if (!counted) {
          counted = true;
          waiting++;
          queued++;
        }
        TimeUnit.NANOSECONDS.timedWait(this, remaining);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw refuse(Scope.SERVICE);
    } finally {
      if (counted) {
        waiting--;
      }
    }

    running++;
    admitted++;
    bySource.merge(source, 1, Integer::sum);
    byCaller.merge(who, 1, Integer::sum);
    AtomicBoolean open = new AtomicBoolean(true);
    return () -> {
      if (open.compareAndSet(true, false)) {
        release(source, who);
      }
    };
  }

  public synchronized Stats stats() {
    return new Stats(
        enabled,
        maxConcurrent,
        maxPerSource,
        maxPerCaller,
        wait.toMillis(),
        running,
        waiting,
        admitted,
        queued,
        throttled,
        Map.copyOf(throttledBy));
  }

  /** The narrowest ceiling with no room, or null when all three have some. */
  private Scope full(UUID source, String who) {
    if (byCaller.getOrDefault(who, 0) >= maxPerCaller) {
      return Scope.CALLER;
    }
    if (bySource.getOrDefault(source, 0) >= maxPerSource) {
      return Scope.SOURCE;
    }
    if (running >= maxConcurrent) {
      return Scope.SERVICE;
    }
    return null;
  }

  private BusyException refuse(Scope scope) {
    throttled++;
    throttledBy.merge(scope, 1L, Long::sum);
    int limit =
        switch (scope) {
          case CALLER -> maxPerCaller;
          case SOURCE -> maxPerSource;
          case SERVICE -> maxConcurrent;
        };
    // A whole second at least, and no longer than the wait itself: by then the
    // reads that were holding the slots have usually finished.
    int retryAfter = (int) Math.max(1, Math.min(10, (wait.toMillis() + 999) / 1000));
    return new BusyException(scope, limit, retryAfter);
  }

  private synchronized void release(UUID source, String who) {
    running--;
    // Entries at zero are removed, so the maps hold only who is running now
    // and do not grow with every account that has ever sent a query.
    bySource.computeIfPresent(source, (k, v) -> v <= 1 ? null : v - 1);
    byCaller.computeIfPresent(who, (k, v) -> v <= 1 ? null : v - 1);
    notifyAll();
  }
}
