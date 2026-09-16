package com.mfec.dac.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.om.OpenMetadataClient;
import com.mfec.dac.om.client.ApiException;
import com.mfec.dac.om.crawl.AssetRefresher;
import com.mfec.dac.om.crawl.GovernanceCrawler;
import com.mfec.dac.om.crawl.GovernanceSnapshot;
import com.mfec.dac.om.events.CatalogChange;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies what changed in OpenMetadata to the cache (FR-1.5).
 *
 * <p>The single place both transports end up: the webhook OpenMetadata pushes
 * and the {@code GET /v1/events} feed the poller reads both normalise to
 * {@link CatalogChange} and arrive here. Two paths into one applier is the point
 * — a webhook that wrote the cache differently from the poller would leave the
 * catalog in a state that depended on which one got there first.
 *
 * <p>A batch is collapsed before anything is read. Ten edits to one table inside
 * a poll window describe one table, and OpenMetadata emits an event per field
 * touched, so a rename plus a re-tag plus an owner change is three events and
 * one read. Collapsing also makes duplicate delivery free, which is what lets
 * the poller overlap its cursor window rather than keep a set of seen event ids.
 *
 * <p>One change failing does not fail the batch. The alternative — abandon the
 * batch and leave the cursor where it was — replays every change in it on the
 * next tick, so one table OpenMetadata cannot serve would stop every other
 * change from ever landing.
 */
public class CatalogChangeApplier {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogChangeApplier.class);

  /** What one batch did. */
  public record Outcome(int refreshed, int retired, int governanceRuns, int failed) {

    public boolean quiet() {
      return refreshed == 0 && retired == 0 && governanceRuns == 0 && failed == 0;
    }

    @Override
    public String toString() {
      return refreshed + " refreshed, " + retired + " retired, " + governanceRuns
          + " governance re-reads, " + failed + " failed";
    }
  }

  private final Jdbi jdbi;
  private final ObjectMapper json;
  private final OpenMetadataClient client;

  public CatalogChangeApplier(Jdbi jdbi, ObjectMapper json, OpenMetadataClient client) {
    this.jdbi = jdbi;
    this.json = json;
    this.client = client;
  }

  /**
   * Applies a batch of changes.
   *
   * @return what was done; never throws for a single bad change
   */
  public Outcome apply(List<CatalogChange> changes) {
    if (changes == null || changes.isEmpty()) {
      return new Outcome(0, 0, 0, 0);
    }

    Collection<CatalogChange> distinct = collapse(changes);
    boolean governanceTouched = distinct.stream().anyMatch(CatalogChange::governance);

    // Governance first, and for the same reason the full crawl does it first:
    // whether a classification is mutually exclusive decides whether a tag on a
    // table replaces an inherited one or joins it (FR-2A.3a). Refreshing assets
    // against yesterday's answer to that question produces facets that quietly
    // disagree with the next full crawl.
    Set<String> exclusive;
    int governanceRuns = 0;
    int failed = 0;
    if (governanceTouched) {
      try {
        GovernanceSnapshot snapshot = new GovernanceCrawler(client).crawl();
        new GovernanceStore(jdbi, json).store(snapshot);
        exclusive = snapshot.mutuallyExclusiveClassifications();
        governanceRuns = 1;
      } catch (Exception e) {
        // Fall back to what is cached rather than skipping the asset work: the
        // asset changes in this batch are still worth applying, and the
        // classification flags rarely move.
        LOG.warn("Could not re-read governance objects; using the cached flags instead", e);
        exclusive = cachedMutuallyExclusive();
        failed++;
      }
    } else {
      exclusive = cachedMutuallyExclusive();
    }

    AssetRefresher refresher = new AssetRefresher(client);
    AssetStore store = new AssetStore(jdbi, json, Instant.now());
    int refreshed = 0;
    int retired = 0;

    for (CatalogChange change : distinct) {
      if (change.governance()) {
        continue;
      }
      try {
        if (change.kind() == CatalogChange.Kind.REMOVED) {
          retired += retire(change.fqn());
        } else {
          // Never store.finished(): that is the retirement sweep, and a refresh
          // mentions one branch of the tree. Calling it here would retire the
          // rest of the catalog.
          refresher.refresh(change.subject(), change.fqn(), exclusive, store);
          refreshed++;
        }
      } catch (AssetRefresher.MissingAncestorException e) {
        // Ordinary: a delete and an update of its container raced, and this one
        // lost. The cache keeps what it has and the nightly reconcile settles
        // it.
        LOG.debug("Skipping {}: {}", change, e.getMessage());
      } catch (ApiException | RuntimeException e) {
        LOG.warn("Could not apply {}", change, e);
        failed++;
      }
    }

    Outcome outcome = new Outcome(refreshed, retired, governanceRuns, failed);
    if (!outcome.quiet()) {
      LOG.info("Applied {} change(s) from {} event(s): {}", distinct.size(), changes.size(),
          outcome);
    }
    return outcome;
  }

  /**
   * One entry per thing that changed, keeping the latest word on each.
   *
   * <p>Latest by timestamp, not by position: the poller reads pages in the order
   * OpenMetadata returns them, and a webhook retry can deliver an old event
   * after a new one. Taking the last arrival would let a stale "updated" undo a
   * "deleted".
   *
   * <p>Every governance change collapses to one entry, because the response to
   * any of them is the same whole re-read — there is no targeted refresh of a
   * classification, and there should not be: a glossary term's parent moving
   * changes what every asset under it inherits.
   */
  static Collection<CatalogChange> collapse(List<CatalogChange> changes) {
    Map<String, CatalogChange> latest = new LinkedHashMap<>();
    for (CatalogChange change : changes) {
      latest.merge(
          change.key(),
          change,
          (existing, incoming) -> incoming.timestamp() >= existing.timestamp() ? incoming : existing);
    }
    return new ArrayList<>(latest.values());
  }

  /**
   * Closes an asset and everything beneath it.
   *
   * <p>Descendants go too because OpenMetadata deleting a schema does not emit
   * an event per table in it, and a table left current under a schema that no
   * longer exists is a table a policy selector still matches.
   *
   * <p>The prefix is anchored on the separator and the wildcards in the FQN are
   * escaped. Without the anchor, deleting {@code prod.Sales} would retire
   * {@code prod.SalesArchive}; without the escaping, deleting a schema named
   * {@code cust_data} would retire {@code custXdata} as well, because
   * {@code _} is a single-character wildcard in SQL {@code LIKE}.
   */
  private int retire(String fqn) {
    String prefix = escapeLike(fqn) + ".%";
    Instant at = Instant.now();
    return jdbi.inTransaction(
        handle -> {
          // Derived rows first: once the asset stops being current the
          // predicate no longer finds it, and its facets would survive where a
          // selector could still read them.
          handle
              .createUpdate(
                  """
                  DELETE FROM asset_facet WHERE column_id IN (
                      SELECT c.id FROM asset_column c
                      JOIN asset a ON a.id = c.asset_id
                      WHERE a.is_current
                        AND (a.fqn = :fqn OR a.fqn LIKE :prefix ESCAPE '\\'))
                  """)
              .bind("fqn", fqn)
              .bind("prefix", prefix)
              .execute();
          handle
              .createUpdate(
                  """
                  DELETE FROM asset_facet WHERE asset_id IN (
                      SELECT a.id FROM asset a
                      WHERE a.is_current
                        AND (a.fqn = :fqn OR a.fqn LIKE :prefix ESCAPE '\\'))
                  """)
              .bind("fqn", fqn)
              .bind("prefix", prefix)
              .execute();
          handle
              .createUpdate(
                  """
                  DELETE FROM asset_owner
                  WHERE target_fqn = :fqn OR target_fqn LIKE :prefix ESCAPE '\\'
                  """)
              .bind("fqn", fqn)
              .bind("prefix", prefix)
              .execute();
          handle
              .createUpdate(
                  """
                  DELETE FROM asset_fqn_map
                  WHERE om_fqn = :fqn OR om_fqn LIKE :prefix ESCAPE '\\'
                  """)
              .bind("fqn", fqn)
              .bind("prefix", prefix)
              .execute();
          handle
              .createUpdate(
                  """
                  UPDATE asset_column SET is_current = false, valid_to = :at
                  WHERE is_current AND asset_id IN (
                      SELECT a.id FROM asset a
                      WHERE a.is_current
                        AND (a.fqn = :fqn OR a.fqn LIKE :prefix ESCAPE '\\'))
                  """)
              .bind("at", at)
              .bind("fqn", fqn)
              .bind("prefix", prefix)
              .execute();
          return handle
              .createUpdate(
                  """
                  UPDATE asset SET is_current = false, valid_to = :at
                  WHERE is_current AND (fqn = :fqn OR fqn LIKE :prefix ESCAPE '\\')
                  """)
              .bind("at", at)
              .bind("fqn", fqn)
              .bind("prefix", prefix)
              .execute();
        });
  }

  /** Makes an FQN safe to use as a literal prefix in {@code LIKE}. */
  static String escapeLike(String fqn) {
    return fqn.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /**
   * The mutually exclusive classifications as the last governance crawl left
   * them.
   *
   * <p>Read from our own table rather than from OpenMetadata: it is a property
   * of the classification, not of the asset that changed, and asking OM for all
   * of them on every single-table refresh would turn a webhook into a crawl.
   */
  private Set<String> cachedMutuallyExclusive() {
    return Set.copyOf(
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        "SELECT fqn FROM classification WHERE mutually_exclusive AND NOT disabled")
                    .mapTo(String.class)
                    .list()));
  }
}
