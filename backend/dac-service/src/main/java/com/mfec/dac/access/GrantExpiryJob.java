package com.mfec.dac.access;

import io.dropwizard.lifecycle.Managed;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retires grants whose window has closed (FR-7.2).
 *
 * <p>This job does not enforce expiry. Expiry is enforced on the read path,
 * where a lapsed grant is simply not among the policies handed to the engine,
 * and that is deliberate: an expiry that depended on a background thread having
 * run would be an expiry that a stopped thread quietly cancels.
 *
 * <p>What the job does is make expiry <em>visible</em>. It writes the tombstone
 * and the audit row, so that "expired" is something the table and the trail say
 * rather than something a reader has to infer by comparing two timestamps, and
 * so the partial indexes over live grants stay small.
 *
 * <p>A fixed period rather than a wall-clock hour, because unlike a nightly
 * crawl there is no quiet window to aim for: the work is proportional to how
 * many grants lapsed, which is normally none.
 */
public class GrantExpiryJob implements Managed {

  private static final Logger LOG = LoggerFactory.getLogger(GrantExpiryJob.class);

  private final GrantStore grants;
  private final Duration period;

  private ScheduledExecutorService executor;

  public GrantExpiryJob(GrantStore grants, Duration period) {
    this.grants = grants;
    this.period = period;
  }

  @Override
  public void start() {
    executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "grant-expiry");
              thread.setDaemon(true);
              return thread;
            });
    // Runs once at startup as well as on the period: a deployment that was down
    // over a weekend should not wait another interval before its trail catches
    // up with what its decisions have been saying all along.
    executor.scheduleAtFixedRate(this::sweep, 0, period.toSeconds(), TimeUnit.SECONDS);
    LOG.info("Grant expiry sweeping every {} minutes", period.toMinutes());
  }

  @Override
  public void stop() {
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  /** One pass. Package-private so a test can run it without waiting for the period. */
  void sweep() {
    try {
      int retired = grants.expire(Instant.now());
      if (retired > 0) {
        LOG.info("Retired {} expired grant(s)", retired);
      }
    } catch (Exception e) {
      // Swallowed so the schedule survives, as in NightlyReconcile. A sweep that
      // failed is a trail that is late; a sweep that killed its own schedule is
      // a trail that is wrong from now on. Nobody's access changes either way.
      LOG.error("Grant expiry sweep failed; the schedule is unaffected", e);
    }
  }
}
