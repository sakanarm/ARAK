package com.mfec.dac.catalog;

import io.dropwizard.lifecycle.Managed;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
 */
public class NightlyReconcile implements Managed {

  private static final Logger LOG = LoggerFactory.getLogger(NightlyReconcile.class);

  private final CatalogSyncService sync;
  private final SyncStateDao syncState;
  private final LocalTime at;
  private final ZoneId zone;

  private ScheduledExecutorService executor;

  public NightlyReconcile(
      CatalogSyncService sync, SyncStateDao syncState, LocalTime at, ZoneId zone) {
    this.sync = sync;
    this.syncState = syncState;
    this.at = at;
    this.zone = zone;
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
    LOG.info("Nightly reconcile scheduled for {} {}", at, zone);
  }

  @Override
  public void stop() {
    if (executor != null) {
      executor.shutdownNow();
    }
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
    Duration delay = untilNext();
    executor.schedule(this::runThenReschedule, delay.toSeconds(), TimeUnit.SECONDS);
    LOG.debug("Next reconcile in {} minutes", delay.toMinutes());
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

  private Duration untilNext() {
    ZonedDateTime now = ZonedDateTime.now(zone);
    ZonedDateTime next = now.with(at);
    if (!next.isAfter(now)) {
      next = ZonedDateTime.of(LocalDate.from(now).plusDays(1), at, zone);
    }
    return Duration.between(now, next);
  }
}
