package com.mfec.dac.enforcement;

import io.dropwizard.lifecycle.Managed;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@link NativeSubscriptionService#sweep} on a fixed period.
 *
 * <p>Unlike {@code GrantExpiryJob}, this job is what enforces expiry on the
 * source: a role on PostgreSQL does not ask ARAK anything when somebody
 * connects, so a member whose access ended keeps the role until a sweep takes
 * it away. The period is therefore the longest a lapsed member can keep
 * reading, and the user guide says so. The sweep only ever takes away; what
 * it cannot do by revoking it leaves for a person.
 *
 * <p>A source that is disabled is not swept: disabling a source is how an
 * admin says "do not connect to it", and that includes this job.
 */
public class NativeSubscriptionJob implements Managed {

  private static final Logger LOG = LoggerFactory.getLogger(NativeSubscriptionJob.class);

  private final NativeSubscriptionService service;
  private final Duration period;

  private ScheduledExecutorService executor;

  public NativeSubscriptionJob(NativeSubscriptionService service, Duration period) {
    this.service = service;
    this.period = period;
  }

  @Override
  public void start() {
    executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "native-subscription-sweep");
              thread.setDaemon(true);
              return thread;
            });
    // A minute after startup rather than at once: the first pass connects to
    // every source with an applied role, and a restart is not the moment to
    // add that to everything else starting up.
    executor.scheduleAtFixedRate(this::sweep, 60, period.toSeconds(), TimeUnit.SECONDS);
    LOG.info("Native subscription sweep every {} minutes", period.toMinutes());
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
      NativeSubscriptionService.SweepReport report = service.sweep();
      if (report.revoked() > 0 || report.failed() > 0) {
        LOG.info(
            "Native sweep: {} role(s) checked, {} revoked from, {} drifted, {} pending, {} failed",
            report.checked(), report.revoked(), report.drifted(), report.pending(),
            report.failed());
      }
    } catch (Exception e) {
      // Swallowed so the schedule survives: a missed pass is a revoke that is
      // ten minutes late, a dead schedule is one that never comes.
      LOG.error("Native subscription sweep failed; the schedule is unaffected", e);
    }
  }
}
