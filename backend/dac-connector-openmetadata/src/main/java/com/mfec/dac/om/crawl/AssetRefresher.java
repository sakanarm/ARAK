package com.mfec.dac.om.crawl;

import com.mfec.dac.common.Fqns;
import com.mfec.dac.om.OmPager;
import com.mfec.dac.om.OpenMetadataClient;
import com.mfec.dac.om.client.ApiException;
import com.mfec.dac.om.client.model.Database;
import com.mfec.dac.om.client.model.DatabaseSchema;
import com.mfec.dac.om.client.model.DatabaseService;
import com.mfec.dac.om.client.model.Table;
import com.mfec.dac.om.events.CatalogChange;
import com.mfec.dac.om.facet.ExtractedFacet;
import com.mfec.dac.om.facet.FacetInheritance.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-reads one asset, and everything under it, without re-crawling the catalog
 * (FR-1.5).
 *
 * <p>This is what makes the webhook worth having. A tag added to a column has to
 * reach the cache in seconds, and crawling a hundred thousand tables to learn
 * about one of them means the change lands in half an hour — or never, because
 * nobody schedules a full crawl that often.
 *
 * <p>The work it cannot skip is the ancestor chain. A table's effective facets
 * include everything its schema, database and service hold, so those are fetched
 * before the table is written: three extra reads against a full crawl. They are
 * fetched from OpenMetadata rather than read back from our own cache on purpose
 * — the cache is the thing being corrected, and deriving inheritance from it
 * would let one stale row keep reproducing itself.
 *
 * <p>Refreshing a container refreshes everything below it, because that is what
 * a change to a container means: a domain set on a schema lands on every table
 * in it. A change to a service is therefore expensive, and it should be — it is
 * a change to every asset the service holds.
 */
public class AssetRefresher {

  private static final Logger LOG = LoggerFactory.getLogger(AssetRefresher.class);

  /** Deleted entities are retired, never refreshed, so only live ones are wanted. */
  private static final String INCLUDE = "non-deleted";

  private static final int NOT_FOUND = 404;

  /** The entity, or something it hangs from, is no longer readable. */
  public static class MissingAncestorException extends Exception {
    public MissingAncestorException(String message) {
      super(message);
    }
  }

  private final OpenMetadataClient client;
  private final AssetCrawler crawler;
  private final AssetCrawler.Fields fields;

  public AssetRefresher(OpenMetadataClient client) {
    this(client, AssetCrawler.Fields.defaults(), OmPager.DEFAULT_PAGE_SIZE);
  }

  public AssetRefresher(OpenMetadataClient client, AssetCrawler.Fields fields, int pageSize) {
    this.client = client;
    this.fields = fields;
    this.crawler = new AssetCrawler(client, fields, pageSize);
  }

  /**
   * Re-reads {@code fqn} and everything under it, offering each asset to
   * {@code sink}.
   *
   * <p>{@link AssetSink#finished} is deliberately never called. That is the
   * signal to retire everything the crawl did not mention, and a refresh
   * mentions one branch of the tree — calling it would retire the rest of the
   * catalog.
   *
   * @param subject which level of the tree {@code fqn} names
   * @param fqn the fully qualified name of the entity that changed
   * @param mutuallyExclusiveRoots classifications permitting one tag per asset,
   *     read back from our own {@code classification} table rather than
   *     re-crawled: it is a property of the classification, not of this asset,
   *     and the governance crawl is what keeps it current
   * @return what was rewritten
   * @throws MissingAncestorException when the entity or an ancestor has gone, in
   *     which case the cache keeps what it has rather than being rewritten from
   *     a tree that no longer holds together
   */
  public AssetCrawler.Stats refresh(
      CatalogChange.Subject subject, String fqn, Set<String> mutuallyExclusiveRoots, AssetSink sink)
      throws ApiException, MissingAncestorException {

    AssetCrawler.Stats stats = new AssetCrawler.Stats();
    switch (subject) {
      case SERVICE -> crawler.crawlService(service(fqn), mutuallyExclusiveRoots, sink, stats);
      case DATABASE ->
          crawler.crawlDatabase(
              database(fqn),
              chainAbove(fqn),
              ownersAbove(fqn),
              mutuallyExclusiveRoots,
              sink,
              stats);
      case SCHEMA ->
          crawler.crawlSchema(
              schema(fqn), chainAbove(fqn), ownersAbove(fqn), mutuallyExclusiveRoots, sink, stats);
      case TABLE ->
          crawler.crawlTable(
              table(fqn), chainAbove(fqn), ownersAbove(fqn), mutuallyExclusiveRoots, sink, stats);
      case GOVERNANCE ->
          throw new IllegalArgumentException(
              "Governance objects are re-read whole, not through the asset refresher");
    }
    LOG.debug("Refreshed {} {}: {}", subject, fqn, stats);
    return stats;
  }

  /**
   * The facets each ancestor of {@code fqn} holds in its own right, nearest
   * first — the order {@link AssetCrawler} would have had them on its stack.
   *
   * <p>Own facets, never effective ones. A table that counts as PII because its
   * service said so has to record the service as the origin; handing down an
   * ancestor's inherited set would make every level look like the source of
   * everything above it, and {@code inherited_from} is the field that answers
   * for a decision (FR-2A.1).
   */
  private List<Level> chainAbove(String fqn) throws ApiException, MissingAncestorException {
    List<Level> chain = new ArrayList<>(3);
    for (String ancestor = AssetMapper.parentOf(fqn);
        ancestor != null;
        ancestor = AssetMapper.parentOf(ancestor)) {
      chain.add(new Level(ancestor, ownFacetsOf(ancestor)));
    }
    return List.copyOf(chain);
  }

  private List<AssetMapper.OwnerLevel> ownersAbove(String fqn)
      throws ApiException, MissingAncestorException {
    List<AssetMapper.OwnerLevel> chain = new ArrayList<>(3);
    for (String ancestor = AssetMapper.parentOf(fqn);
        ancestor != null;
        ancestor = AssetMapper.parentOf(ancestor)) {
      chain.add(new AssetMapper.OwnerLevel(ancestor, ownersOf(ancestor)));
    }
    return List.copyOf(chain);
  }

  /** Depth is what distinguishes the levels of an asset FQN; there is no type on it. */
  private List<ExtractedFacet> ownFacetsOf(String fqn)
      throws ApiException, MissingAncestorException {
    return switch (Fqns.depth(fqn)) {
      case 1 -> AssetMapper.serviceFacets(service(fqn));
      case 2 -> AssetMapper.databaseFacets(database(fqn));
      case 3 -> AssetMapper.schemaFacets(schema(fqn));
      default -> List.of();
    };
  }

  private List<CrawledAsset.OwnerRow> ownersOf(String fqn)
      throws ApiException, MissingAncestorException {
    return switch (Fqns.depth(fqn)) {
      case 1 -> AssetMapper.owners(fqn, service(fqn).getOwners());
      case 2 -> AssetMapper.owners(fqn, database(fqn).getOwners());
      case 3 -> AssetMapper.owners(fqn, schema(fqn).getOwners());
      default -> List.of();
    };
  }

  // -------------------------------------------------------------- one read each

  private DatabaseService service(String fqn) throws ApiException, MissingAncestorException {
    try {
      return present(
          client.databaseServices().getDatabaseServiceByFQN(fqn, fields.service(), INCLUDE, null),
          "database service",
          fqn);
    } catch (ApiException e) {
      throw gone(e, "database service", fqn);
    }
  }

  private Database database(String fqn) throws ApiException, MissingAncestorException {
    try {
      return present(
          client.databases().getDatabaseByFQN(fqn, fields.database(), INCLUDE, null),
          "database",
          fqn);
    } catch (ApiException e) {
      throw gone(e, "database", fqn);
    }
  }

  private DatabaseSchema schema(String fqn) throws ApiException, MissingAncestorException {
    try {
      return present(
          client.schemas().getDBSchemaByFQN(fqn, fields.schema(), INCLUDE, null), "schema", fqn);
    } catch (ApiException e) {
      throw gone(e, "schema", fqn);
    }
  }

  private Table table(String fqn) throws ApiException, MissingAncestorException {
    try {
      return present(
          client.tables().getTableByFQN(fqn, fields.table(), INCLUDE, null), "table", fqn);
    } catch (ApiException e) {
      throw gone(e, "table", fqn);
    }
  }

  /**
   * Separates "OpenMetadata says this is not there" from "OpenMetadata could not
   * answer".
   *
   * <p>The first is ordinary: a delete event and its container's update event
   * race, and the loser reads a thing that has just gone. The second is a
   * failure the caller must be allowed to retry, so it stays an
   * {@link ApiException} and keeps the poller's cursor where it is.
   */
  private static ApiException gone(ApiException e, String what, String fqn)
      throws MissingAncestorException {
    if (e.getCode() == NOT_FOUND) {
      throw new MissingAncestorException(
          "OpenMetadata has no " + what + " " + fqn + " any more; leaving the cached copy alone");
    }
    return e;
  }

  /**
   * Refuses to carry on with a hole in the tree.
   *
   * <p>Substituting an empty level would write the asset with whatever
   * inheritance survived, which in the case that matters means writing a column
   * without the PII tag its schema gave it. A stale row is correctable by the
   * nightly reconcile; a confidently wrong one unmasks data.
   */
  private static <T> T present(T entity, String what, String fqn)
      throws MissingAncestorException {
    if (entity == null) {
      throw new MissingAncestorException(
          "OpenMetadata returned no " + what + " for " + fqn + "; leaving the cached copy alone");
    }
    return entity;
  }
}
