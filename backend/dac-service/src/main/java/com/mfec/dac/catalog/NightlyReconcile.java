package com.mfec.dac.catalog;

import io.dropwizard.lifecycle.Managed;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A full crawl once a night (FR-1.5).
 *
 * <p>The backstop under the webhook and the poller, and it is not redundancy for
 * its own sake. Both of those act on events, and there are changes OpenMetadata
 * does not describe with one: a classification becoming mutually exclusive
 * changes how every asset under it resolves its tags; a domain gaining a parent
 * changes what every asset in it inherits; a soft delete is not requestable
 * through the change feed's filters at all. None of that arrives as "this table
 * changed", so nothing targeted will ever notice it.
 *
 * <p>It is also the only thing that retires an asset nobody told us about. The
 * crawl's closing sweep closes everything it did not see, which is how a table
 * that vanished during an outage eventually stops matching a policy.
 *
 * <p>The hour is read from {@link SyncScheduleStore} before every booking rather
 * than held in a field, so changing it is a save and not a restart. A change
 * takes effect at the next booking, which {@link #reload()} makes immediate.
 */
public class NightlyReconcile implements Managed {

  private static final Logger LOG = LoggerFactory.getLogger(NightlyReconcile.class);

  private final CatalogSyncService sync;
  private final SyncStateDao syncState;
  private final SyncScheduleStore schedules;

  private ScheduledExecutorService executor;

  /**
   * The booking that has not fired yet, so a new schedule can cancel it.
   *
   * <p>Volatile because it is written by the reconcile thread when it rebooks
   * itself and read by whichever request thread saved a new time.
   */
  private volatile ScheduledFuture<?> pending;

  /** When the booking above will fire, for a screen that wants to say so. */
  private volatile Instant nextRun;

  public NightlyReconcile(
      CatalogSyncService sync, SyncStateDao syncState, SyncScheduleStore schedules) {
    this.sync = sync;
    this.syncState = syncState;
    this.schedules = schedules;
  }

  @Override
  public void start() {
    executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "om-nightly-reconcile");
              thread.setDaemon(true);
              return thread;
            });
    schedule();
  }

  @Override
  public void stop() {
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  /**
   * Re-reads the schedule and rebooks against it.
   *
   * <p>Called after a save. The pending booking is cancelled without
   * interrupting: if a reconcile is running right now it finishes, because
   * abandoning a half-written crawl to honour a time change would leave the
   * cache in the state the crawl exists to prevent.
   */
  public void reload() {
    if (executor == null || executor.isShutdown()) {
      return;
    }
    ScheduledFuture<?> current = pending;
    if (current != null) {
      current.cancel(false);
    }
    schedule();
  }

  /** When the next crawl is booked for, or empty while the backstop is off. */
  public Optional<Instant> nextRunAt() {
    return Optional.ofNullable(nextRun);
  }

  /**
   * Books the next run, one at a time.
   *
   * <p>Rescheduling after each run rather than at a fixed period so the hour
   * stays the hour across a daylight-saving change. A 24-hour period booked once
   * drifts to 03:00 in summer, which for an overnight job is the difference
   * between running in a quiet window and running during the morning load.
   */
  private void schedule() {
    SyncScheduleStore.Schedule now = schedules.current();
    if (!now.enabled()) {
      pending = null;
      nextRun = null;
      LOG.info(
          "Nightly reconcile is switched off. Nothing will retire an asset that vanished "
              + "without an event until somebody runs a crawl.");
      return;
    }

    Duration delay = untilNext(now);
    nextRun = Instant.now().plus(delay);
    pending = executor.schedule(this::runThenReschedule, delay.toSeconds(), TimeUnit.SECONDS);
    LOG.info(
        "Nightly reconcile scheduled for {} {} -- next run in {} minutes",
        now.at(),
        now.zone(),
        delay.toMinutes());
  }

  private void runThenReschedule() {
    try {
      reconcile();
    } catch (Exception e) {
      // Swallowed so the schedule survives. A reconcile that failed tonight is
      // a stale cache; a reconcile that cancelled its own schedule is a cache
      // that is stale from now on.
      LOG.error("Nightly reconcile failed; the schedule is unaffected", e);
    } finally {
      if (!executor.isShutdown()) {
        schedule();
      }
    }
  }

  /** One full crawl. Package-private so a test can run it without waiting for midnight. */
  void reconcile() {
    sync.crawl()
        .ifPresentOrElse(
            result -> {
              // Distinct from last_full_crawl_at: a manual sync also sets that,
              // and the compliance question is when the cache was last proved
              // to agree with the catalog, not when it was last written.
              syncState.reconciled(CatalogSyncService.SOURCE, result.finishedAt());
              LOG.info(
                  "Nightly reconcile: {}, {} revised, {} retired",
                  result.assets(),
                  result.revised(),
                  result.retired());
            },
            () -> LOG.info("Nightly reconcile skipped: a crawl was already running"));
  }

  /** How long until the next occurrence of the configured hour. Package-private for tests. */
  static Duration untilNext(SyncScheduleStore.Schedule schedule) {
    ZonedDateTime now = ZonedDateTime.now(schedule.zone());
    ZonedDateTime next = now.with(schedule.at());
    if (!next.isAfter(now)) {
      // Built from the date rather than by adding a day to `next`, so an hour
      // that does not exist tomorrow -- the one a spring-forward skips -- is
      // resolved by the zone's own rules instead of landing before `now` and
      // firing immediately.
      next = ZonedDateTime.of(LocalDate.from(now).plusDays(1), schedule.at(), schedule.zone());
    }
    return Duration.between(now, next);
  }
}
