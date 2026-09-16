package com.mfec.dac.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.om.OpenMetadataClient;
import com.mfec.dac.om.crawl.AssetCrawler;
import com.mfec.dac.om.crawl.GovernanceCrawler;
import com.mfec.dac.om.crawl.GovernanceSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One full synchronisation of OpenMetadata into the cache (FR-1.5).
 *
 * <p>Governance first, then assets, and not by preference: the asset crawl needs
 * the set of mutually exclusive classifications to decide whether a tag replaces
 * an inherited one or joins it (FR-2A.3a). Running the assets first would make
 * the first crawl of a fresh instance compute inheritance against an empty set
 * and quietly disagree with every crawl after it.
 *
 * <p>Only one run at a time, per process and per row. Two crawls stamping
 * different {@code last_seen_at} values would each end by retiring what the
 * other had just written.
 */
public class CatalogSyncService {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogSyncService.class);

  /** The key in {@code sync_state}; one row per upstream, and we have one. */
  public static final String SOURCE = "openmetadata";

  /**
   * How long a {@code RUNNING} row is believed before a new run ignores it.
   *
   * <p>A process killed mid-crawl leaves the row RUNNING with nobody to clear
   * it, and without a limit the platform would never crawl again until someone
   * noticed and edited a table by hand. Longer than the 30 minutes NFR-2 allows
   * a 100k-asset crawl, so a slow but living crawl is never cut in on.
   */
  private static final Duration STALE_RUN = Duration.ofHours(2);

  /** What one run did, for the API response and the log. */
  public record Result(
      Instant startedAt,
      Instant finishedAt,
      int classifications,
      int tags,
      int glossaryTerms,
      int domains,
      String assets,
      int unchanged,
      int revised,
      int retired) {}

  private final Jdbi jdbi;
  private final ObjectMapper json;
  private final OpenMetadataClient client;
  private final SyncStateDao syncState;

  /** Guards this process; the {@code sync_state} row guards the others. */
  private final AtomicBoolean running = new AtomicBoolean();

  public CatalogSyncService(Jdbi jdbi, ObjectMapper json, OpenMetadataClient client) {
    this.jdbi = jdbi;
    this.json = json;
    this.client = client;
    this.syncState = new SyncStateDao(jdbi);
  }

  public Optional<SyncStateDao.SyncState> state() {
    return syncState.find(SOURCE);
  }

  /**
   * Runs a full crawl, or explains why it did not.
   *
   * @return empty when another run holds the lock
   */
  public Optional<Result> crawl() {
    if (!running.compareAndSet(false, true)) {
      LOG.info("A crawl is already running in this process; ignoring the request");
      return Optional.empty();
    }
    try {
      if (heldElsewhere()) {
        return Optional.empty();
      }
      return Optional.of(runCrawl());
    } finally {
      running.set(false);
    }
  }

  private boolean heldElsewhere() {
    Optional<SyncStateDao.SyncState> current = syncState.find(SOURCE);
    if (current.isEmpty() || !current.get().running()) {
      return false;
    }
    Instant since = current.get().updatedAt();
    if (since != null && since.isAfter(Instant.now().minus(STALE_RUN))) {
      LOG.info("A crawl started at {} is still running; ignoring the request", since);
      return true;
    }
    LOG.warn(
        "The last crawl has been marked RUNNING since {} and is presumed dead; starting a new one",
        since);
    return false;
  }

  private Result runCrawl() {
    Instant startedAt = Instant.now();
    syncState.started(SOURCE);
    try {
      GovernanceSnapshot governance = new GovernanceCrawler(client).crawl();
      new GovernanceStore(jdbi, json).store(governance);

      // The stamp is taken once, here, and every write in this crawl carries
      // it. Taking it per asset would let the sweep retire an asset written
      // moments earlier by the same crawl.
      AssetStore assets = new AssetStore(jdbi, json, Instant.now());
      AssetCrawler.Stats stats =
          new AssetCrawler(client).crawl(governance.mutuallyExclusiveClassifications(), assets);

      Instant finishedAt = Instant.now();
      syncState.crawlSucceeded(SOURCE, finishedAt);
      Result result =
          new Result(
              startedAt,
              finishedAt,
              governance.classifications().size(),
              governance.tags().size(),
              governance.terms().size(),
              governance.domains().size(),
              stats.toString(),
              assets.unchanged(),
              assets.revised(),
              assets.retired());
      LOG.info(
          "Crawl of {} finished in {}s: {}, {} unchanged, {} revised, {} retired",
          client.baseUrl(),
          Duration.between(startedAt, finishedAt).toSeconds(),
          stats,
          result.unchanged(),
          result.revised(),
          result.retired());
      return result;
    } catch (Exception e) {
      // The row is the only durable record that this failed. A caller that went
      // away mid-request, or a scheduled run nobody was watching, would
      // otherwise leave a cache that is stale with nothing saying so.
      syncState.failed(SOURCE, e.getClass().getSimpleName() + ": " + e.getMessage());
      LOG.error("Crawl of {} failed", client.baseUrl(), e);
      throw new IllegalStateException("OpenMetadata crawl failed: " + e.getMessage(), e);
    }
  }
}
