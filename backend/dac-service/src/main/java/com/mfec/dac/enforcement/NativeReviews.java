package com.mfec.dac.enforcement;

import com.mfec.dac.compiler.sql.PostgresGrantCompiler;
import com.mfec.dac.source.jdbc.NativeGrantApplier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Native plans that somebody has been shown, held until they apply one or it
 * goes stale. The same contract as {@link ReviewedPlans}, for the same reasons:
 * the browser sends back an id, never the plan, so nobody can approve a change
 * they wrote themselves; a review lives thirty minutes and is spent by the
 * first apply, whatever its outcome.
 *
 * <p>Keyed by policy and source together. A review taken with the right id
 * but for another policy or source is left where it is.
 */
public final class NativeReviews {

  /** One plan as it was shown. */
  public record Reviewed(
      UUID id,
      String key,
      String actor,
      Instant reviewedAt,
      Instant expiresAt,
      PostgresGrantCompiler.Desired desired,
      NativeGrantApplier.DryRun dryRun) {}

  private final Clock clock;
  private final Duration ttl;
  private final Map<UUID, Reviewed> pending = new LinkedHashMap<>();

  public NativeReviews() {
    this(Clock.systemUTC(), ReviewedPlans.DEFAULT_TTL);
  }

  public NativeReviews(Clock clock, Duration ttl) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
  }

  static String key(UUID policyId, UUID sourceId) {
    return policyId + "/" + sourceId;
  }

  public synchronized Reviewed hold(
      String key,
      String actor,
      PostgresGrantCompiler.Desired desired,
      NativeGrantApplier.DryRun dryRun) {
    Objects.requireNonNull(desired, "desired");
    Objects.requireNonNull(dryRun, "dryRun");
    Instant now = clock.instant();
    evict(now);
    while (pending.size() >= ReviewedPlans.CAPACITY) {
      Iterator<UUID> oldest = pending.keySet().iterator();
      oldest.next();
      oldest.remove();
    }
    Reviewed reviewed =
        new Reviewed(UUID.randomUUID(), key, actor, now, now.plus(ttl), desired, dryRun);
    pending.put(reviewed.id(), reviewed);
    return reviewed;
  }

  public synchronized Optional<Reviewed> take(UUID id, String key) {
    if (id == null) {
      return Optional.empty();
    }
    evict(clock.instant());
    Reviewed reviewed = pending.get(id);
    if (reviewed == null || !reviewed.key().equals(key)) {
      return Optional.empty();
    }
    pending.remove(id);
    return Optional.of(reviewed);
  }

  private void evict(Instant now) {
    pending.values().removeIf(reviewed -> !reviewed.expiresAt().isAfter(now));
  }
}
