package com.mfec.dac.enforcement;

import com.mfec.dac.source.jdbc.SecureViewApplier;
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
 * Dry runs that somebody has been shown, held until they apply one or it goes
 * stale.
 *
 * <p>An apply is a statement that a person read a change and agreed to it
 * (FR-6.4). The applier enforces the half of that it can see -- the rows it is
 * about to write must be the rows that were reviewed -- but it cannot know what
 * was reviewed unless something kept it. Sending the dry run back from the
 * browser would let a caller agree to a change they wrote themselves; so the
 * server keeps what it showed, and the browser sends back only the id.
 *
 * <h2>Why in memory</h2>
 *
 * <p>A review is worth minutes, not days: the source moves, policies change,
 * and a dry run from yesterday is a description of yesterday. Holding them in
 * process means a restart forgets every pending review, which is the right
 * outcome -- the person runs the dry run again and reads the current one. The
 * cost is that a second instance behind a load balancer does not know the
 * first one's reviews; this deployment is one process under PM2, and the day
 * that changes this becomes a table.
 *
 * <p>Each review can be used once. Applying it removes it, whatever the
 * outcome, so the same approval cannot be spent twice against a source that
 * has since moved on.
 */
public final class ReviewedPlans {

  /** How long a dry run stays approvable. */
  public static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

  /** Beyond this many pending reviews, the oldest are forgotten first. */
  static final int CAPACITY = 256;

  /**
   * One dry run as it was shown.
   *
   * @param applyScript the DDL the reviewer read, compared again at apply time.
   *     The applier's signature covers the entitlement rows; this covers the
   *     view, which changes when the source grows a column or a policy's mask
   *     changes, without a single row changing.
   */
  public record Reviewed(
      UUID id,
      String assetFqn,
      String actor,
      Instant reviewedAt,
      Instant expiresAt,
      SecureViewApplier.DryRun dryRun,
      String applyScript) {}

  private final Clock clock;
  private final Duration ttl;
  private final Map<UUID, Reviewed> pending = new LinkedHashMap<>();

  public ReviewedPlans() {
    this(Clock.systemUTC(), DEFAULT_TTL);
  }

  public ReviewedPlans(Clock clock, Duration ttl) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
  }

  public synchronized Reviewed hold(
      String assetFqn, String actor, SecureViewApplier.DryRun dryRun, String applyScript) {
    Objects.requireNonNull(dryRun, "dryRun");
    Instant now = clock.instant();
    evict(now);
    while (pending.size() >= CAPACITY) {
      Iterator<UUID> oldest = pending.keySet().iterator();
      oldest.next();
      oldest.remove();
    }
    Reviewed reviewed =
        new Reviewed(UUID.randomUUID(), assetFqn, actor, now, now.plus(ttl), dryRun, applyScript);
    pending.put(reviewed.id(), reviewed);
    return reviewed;
  }

  /**
   * The review with this id for this asset, removed so that it cannot be used
   * again. Empty when it never existed, has expired, or was for another asset.
   *
   * <p>A review for a different asset is left where it is. Taking it would let
   * a mistyped request spend somebody else's approval.
   */
  public synchronized Optional<Reviewed> take(UUID id, String assetFqn) {
    if (id == null) {
      return Optional.empty();
    }
    evict(clock.instant());
    Reviewed reviewed = pending.get(id);
    if (reviewed == null || !reviewed.assetFqn().equals(assetFqn)) {
      return Optional.empty();
    }
    pending.remove(id);
    return Optional.of(reviewed);
  }

  synchronized int size() {
    evict(clock.instant());
    return pending.size();
  }

  private void evict(Instant now) {
    pending.values().removeIf(reviewed -> !reviewed.expiresAt().isAfter(now));
  }
}
