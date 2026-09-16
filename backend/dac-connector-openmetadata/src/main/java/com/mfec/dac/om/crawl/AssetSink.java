package com.mfec.dac.om.crawl;

/**
 * Where the asset crawl puts what it reads.
 *
 * <p>A port, so the crawler never touches a database. The adapter that writes
 * SCD2 rows lives with the rest of the persistence code, and a test can count
 * facets without one.
 *
 * <p>Assets arrive parent-first — service, then its databases, then their
 * schemas, then their tables — so an implementation may rely on an asset's
 * parent already having been offered when it sees the child.
 */
@FunctionalInterface
public interface AssetSink {

  /** One asset, with its columns, effective facets and effective owners. */
  void asset(CrawledAsset asset);

  /**
   * Called once when the crawl finishes without throwing.
   *
   * <p>The signal to close out a crawl generation: anything the crawl did not
   * mention no longer exists in OpenMetadata and can be retired. It is
   * deliberately not called on failure — a partial crawl that retired every
   * asset it had not reached yet would take the whole catalogue offline.
   */
  default void finished(AssetCrawler.Stats stats) {}
}
