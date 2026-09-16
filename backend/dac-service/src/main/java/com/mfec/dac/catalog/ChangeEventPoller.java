package com.mfec.dac.catalog;

import com.mfec.dac.om.OpenMetadataClient;
import com.mfec.dac.om.events.ChangeEventReader;
import io.dropwizard.lifecycle.Managed;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads OpenMetadata's change feed on a timer (FR-1.5).
 *
 * <p>The webhook is the fast path and this is the one that makes it safe to rely
 * on. A webhook is a single delivery attempt across a network to a service that
 * may be restarting; miss one and the cache is wrong until somebody notices. The
 * poller closes that gap without anyone noticing, and it is also the entire sync
 * mechanism on a deployment where nobody registered a subscription.
 *
 * <p>The two overlap on purpose. Applying a change twice is free — the applier
 * re-reads the entity by FQN rather than replaying the event's payload — so the
 * cursor is deliberately rewound a little on every tick rather than tracking
 * which event ids have been seen.
 */
public class ChangeEventPoller implements Managed {

  private static final Logger LOG = LoggerFactory.getLogger(ChangeEventPoller.class);

  /**
   * How far back the cursor is rewound on each read.
   *
   * <p>OpenMetadata stamps an event with the time it was written, and a request
   * issued at that same millisecond can miss it — the row is not yet visible, or
   * the clocks are a hair apart. Re-reading a minute of already-applied events
   * costs a collapse in the applier; missing one costs a column that stays
   * unmasked until the nightly reconcile.
   */
  private static final Duration OVERLAP = Duration.ofMinutes(1);

  /**
   * How far back a first-ever poll starts when nothing says otherwise.
   *
   * <p>Not from the beginning of time: a fresh deployment's first job is a full
   * crawl, and replaying the catalog's entire history through a targeted
   * refresher would be a slow, noisy way of arriving at what the crawl already
   * produced.
   */
  private static final Duration COLD_START = Duration.ofHours(1);

  /**
   * How many polls in a row may hold the cursor back before it is advanced anyway.
   *
   * <p>Holding is the right answer to a catalog that is restarting or a database
   * that is briefly gone, because the next tick then re-reads what was missed. It
   * is the wrong answer to a change that will never apply: the window grows on
   * every tick and the same failing read is retried against an ever larger slice
   * of the feed. Ten minutes of holding, then the nightly reconcile owns it.
   */
  private static final int MAX_STALLED_POLLS = 10;

  private final SyncStateDao syncState;
  private final ChangeEventReader reader;
  private final CatalogChangeApplier applier;
  private final Duration interval;

  private ScheduledExecutorService executor;

  /** Consecutive polls that left the cursor where it was. Touched only by the poll thread. */
  private int stalledPolls;

  public ChangeEventPoller(
      OpenMetadataClient client,
      CatalogChangeApplier applier,
      SyncStateDao syncState,
      int maxEventsPerPoll,
      Duration interval) {
    this(new ChangeEventReader(client, maxEventsPerPoll), applier, syncState, interval);
  }

  /** For tests, which need a reader that answers without a catalog behind it. */
  ChangeEventPoller(
      ChangeEventReader reader,
      CatalogChangeApplier applier,
      SyncStateDao syncState,
      Duration interval) {
    this.reader = reader;
    this.applier = applier;
    this.syncState = syncState;
    this.interval = interval;
  }

  @Override
  public void start() {
    executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "om-change-poller");
              // A daemon so a shutdown that never reaches stop() is not held up
              // by a poll waiting on a catalog that has gone away.
              thread.setDaemon(true);
              return thread;
            });
    // Fixed delay, not fixed rate: a poll that took longer than the interval
    // means OpenMetadata is slow or the batch was large, and queueing another
    // one behind it makes both worse.
    executor.scheduleWithFixedDelay(
        this::tick, interval.toSeconds(), interval.toSeconds(), TimeUnit.SECONDS);
    LOG.info("Polling OpenMetadata for changes every {}s", interval.toSeconds());
  }

  @Override
  public void stop() {
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  /** One read-and-apply. Package-private so a test can run a tick without a clock. */
  void tick() {
    try {
      pollOnce();
    } catch (Exception e) {
      // Never let it out: an exception escaping a scheduled task cancels the
      // schedule silently, and the platform would stop syncing without a single
      // line saying it had.
      LOG.warn("Change poll failed; the cursor is unchanged and the next tick will retry", e);
    }
  }

  /**
   * Reads whatever has happened since the cursor and applies it.
   *
   * @return how much was applied, or empty when the poll was skipped
   */
  public Optional<CatalogChangeApplier.Outcome> pollOnce() throws Exception {
    Optional<SyncStateDao.SyncState> state = syncState.find(CatalogSyncService.SOURCE);
    if (state.isPresent() && state.get().running()) {
      // A full crawl is in flight and is about to write every asset there is.
      // Refreshing individual assets underneath it would stamp them with a
      // different last_seen_at than the crawl's, and the crawl's closing sweep
      // retires anything not carrying its stamp.
      LOG.debug("A full crawl is running; skipping this poll");
      return Optional.empty();
    }

    long since = cursorFrom(state);
    ChangeEventReader.Batch batch = reader.read(since);
    if (batch.seen() == 0) {
      return Optional.empty();
    }

    CatalogChangeApplier.Outcome outcome = applier.apply(batch.changes());

    if (outcome.failed() > 0 && stalledPolls < MAX_STALLED_POLLS) {
      // The cursor stays where it is so the next tick reads this window again.
      // Re-applying what already succeeded costs one collapse in the applier;
      // advancing past what failed loses it until the nightly reconcile, and the
      // change that failed is as likely as not the one that adds a PII tag.
      stalledPolls++;
      LOG.warn(
          "{} of {} change(s) could not be applied; holding the cursor at {} (hold {} of {})",
          outcome.failed(),
          batch.changes().size(),
          Instant.ofEpochMilli(since),
          stalledPolls,
          MAX_STALLED_POLLS);
      return Optional.of(outcome);
    }

    if (outcome.failed() > 0) {
      LOG.error(
          "{} change(s) have failed {} polls in a row; advancing the cursor past them and leaving "
              + "them to the nightly reconcile, which re-reads everything",
          outcome.failed(),
          stalledPolls);
    }

    // Only after applying. A cursor advanced before the work is a cursor that
    // has quietly agreed the work will never be done.
    stalledPolls = 0;
    syncState.eventCursor(CatalogSyncService.SOURCE, batch.highWaterMark());
    LOG.debug(
        "Polled {} event(s) since {} ({} ignored): {}",
        batch.seen(),
        Instant.ofEpochMilli(since),
        batch.ignored(),
        outcome);
    return Optional.of(outcome);
  }

  /**
   * Where to read from.
   *
   * <p>The stored cursor first; failing that the last full crawl, because
   * everything before it is already in the cache by definition; failing that a
   * short window, on the reasoning above.
   */
  private long cursorFrom(Optional<SyncStateDao.SyncState> state) {
    Long stored = state.map(SyncStateDao.SyncState::lastEventTs).orElse(null);
    if (stored != null && stored > 0) {
      return Math.max(0, stored - OVERLAP.toMillis());
    }
    Instant crawled = state.map(SyncStateDao.SyncState::lastFullCrawlAt).orElse(null);
    if (crawled != null) {
      return crawled.minus(OVERLAP).toEpochMilli();
    }
    return Instant.now().minus(COLD_START).toEpochMilli();
  }
}
