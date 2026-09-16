package com.mfec.dac.om.crawl;

import com.mfec.dac.om.OmPager;
import com.mfec.dac.om.OpenMetadataClient;
import com.mfec.dac.om.client.ApiException;
import com.mfec.dac.om.client.model.Database;
import com.mfec.dac.om.client.model.DatabaseList;
import com.mfec.dac.om.client.model.DatabaseSchema;
import com.mfec.dac.om.client.model.DatabaseSchemaList;
import com.mfec.dac.om.client.model.DatabaseService;
import com.mfec.dac.om.client.model.DatabaseServiceList;
import com.mfec.dac.om.client.model.Table;
import com.mfec.dac.om.client.model.TableList;
import com.mfec.dac.om.facet.ExtractedFacet;
import com.mfec.dac.om.facet.FacetExtractor;
import com.mfec.dac.om.facet.FacetInheritance;
import com.mfec.dac.om.facet.FacetInheritance.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks OpenMetadata's asset tree and hands every asset to a sink (FR-1.2).
 *
 * <p>Descends service → database → schema → table → column, carrying each
 * level's own facets down as it goes. The descent is what makes inheritance
 * explainable: a column that counts as PII because its schema said so records
 * the schema as the origin, and it does so because the schema was on the stack
 * when the column was computed — not because something later tried to work
 * backwards from an FQN.
 *
 * <p>Every level's facets are passed down as the facets that level <em>itself</em>
 * holds, never the ones it had already inherited. Passing the inherited set
 * would make a table's tag appear to come from its schema when it really came
 * from the service, and {@code inherited_from} is the field the UI uses to
 * answer for a decision.
 */
public class AssetCrawler {

  private static final Logger LOG = LoggerFactory.getLogger(AssetCrawler.class);

  /** Only assets OpenMetadata still holds; a deleted table must stop matching. */
  private static final String INCLUDE = "non-deleted";

  /**
   * The {@code ?fields=} lists, one per entity type.
   *
   * <p>OpenMetadata validates these per entity type and answers 400 for a field
   * the type does not define, so they are configurable rather than hard-coded: a
   * field added or renamed between versions must be adjustable without a
   * release. They are not, however, quietly dropped on failure — see
   * {@link #crawl}.
   */
  public record Fields(String service, String database, String schema, String table) {

    public static Fields defaults() {
      return new Fields(
          "owners,tags,domains",
          "owners,tags,domains,dataProducts,extension",
          "owners,tags,domains,dataProducts,extension",
          "columns,owners,tags,domains,dataProducts,extension,certification");
    }
  }

  /** What one crawl covered, for the run log and for {@code sync_state}. */
  public static final class Stats {
    private int services;
    private int databases;
    private int schemas;
    private int tables;
    private int columns;
    private int facets;

    public int services() {
      return services;
    }

    public int databases() {
      return databases;
    }

    public int schemas() {
      return schemas;
    }

    public int tables() {
      return tables;
    }

    public int columns() {
      return columns;
    }

    public int facets() {
      return facets;
    }

    @Override
    public String toString() {
      return services + " services, " + databases + " databases, " + schemas + " schemas, "
          + tables + " tables, " + columns + " columns, " + facets + " facet rows";
    }
  }

  /** Carries an {@link ApiException} out of a pager callback, which cannot throw it. */
  private static final class Wrapped extends RuntimeException {
    Wrapped(ApiException cause) {
      super(cause);
    }

    ApiException unwrap() {
      return (ApiException) getCause();
    }
  }

  private final OpenMetadataClient client;
  private final Fields fields;
  private final int pageSize;

  public AssetCrawler(OpenMetadataClient client) {
    this(client, Fields.defaults(), OmPager.DEFAULT_PAGE_SIZE);
  }

  public AssetCrawler(OpenMetadataClient client, Fields fields, int pageSize) {
    this.client = client;
    this.fields = fields;
    this.pageSize = pageSize;
  }

  /**
   * Reads every asset and offers it to {@code sink}.
   *
   * <p>A failure part-way through aborts the crawl rather than finishing what it
   * can. An asset cache that is half-updated is worse than one that is stale:
   * stale is a state the system knows it is in, half-updated looks authoritative
   * while quietly having lost the tag that made a column sensitive.
   *
   * @param mutuallyExclusiveRoots classifications that permit one tag per asset,
   *                               from the governance crawl that runs first
   */
  public Stats crawl(Set<String> mutuallyExclusiveRoots, AssetSink sink) throws ApiException {
    Stats stats = new Stats();
    try {
      OmPager.forEachPage(
          (limit, after) ->
              client
                  .databaseServices()
                  .listDatabaseServices(fields.service(), null, limit, null, after, INCLUDE),
          DatabaseServiceList::getData,
          DatabaseServiceList::getPaging,
          pageSize,
          page -> {
            for (DatabaseService service : page) {
              crawlService(service, mutuallyExclusiveRoots, sink, stats);
            }
          });
    } catch (Wrapped e) {
      throw e.unwrap();
    }
    LOG.info("Asset crawl of {}: {}", client.baseUrl(), stats);
    sink.finished(stats);
    return stats;
  }

  /**
   * Package-private rather than private so {@link AssetRefresher} can re-enter
   * the descent part-way down with an ancestor chain it fetched itself. A
   * targeted refresh that reimplemented the descent would be a second place for
   * inheritance to be computed, and the two would drift.
   */
  void crawlService(
      DatabaseService service, Set<String> exclusive, AssetSink sink, Stats stats) {

    CrawledAsset.AssetRow row = AssetMapper.service(service);
    if (row == null) {
      return;
    }
    List<ExtractedFacet> own = AssetMapper.serviceFacets(service);
    List<CrawledAsset.OwnerRow> owners = AssetMapper.owners(row.fqn(), service.getOwners());
    emit(sink, stats, new CrawledAsset(row, List.of(), own, owners));
    stats.services++;

    List<Level> chain = List.of(new Level(row.fqn(), own));
    List<AssetMapper.OwnerLevel> ownerChain =
        List.of(new AssetMapper.OwnerLevel(row.fqn(), owners));

    paged(
        (limit, after) ->
            client
                .databases()
                .listDatabases(
                    fields.database(), row.fqn(), null, limit, null, after, INCLUDE, null, null),
        DatabaseList::getData,
        DatabaseList::getPaging,
        page -> {
          for (Database database : page) {
            crawlDatabase(database, chain, ownerChain, exclusive, sink, stats);
          }
        });
  }

  void crawlDatabase(
      Database database,
      List<Level> chain,
      List<AssetMapper.OwnerLevel> ownerChain,
      Set<String> exclusive,
      AssetSink sink,
      Stats stats) {

    CrawledAsset.AssetRow row = AssetMapper.database(database);
    if (row == null) {
      return;
    }
    List<ExtractedFacet> own = AssetMapper.databaseFacets(database);
    List<CrawledAsset.OwnerRow> owners = AssetMapper.owners(row.fqn(), database.getOwners());
    emit(
        sink,
        stats,
        new CrawledAsset(
            row,
            List.of(),
            FacetInheritance.effective(row.fqn(), own, chain, exclusive),
            AssetMapper.effectiveOwners(row.fqn(), owners, ownerChain)));
    stats.databases++;

    List<Level> next = nearestFirst(new Level(row.fqn(), own), chain);
    List<AssetMapper.OwnerLevel> nextOwners =
        nearestFirstOwners(new AssetMapper.OwnerLevel(row.fqn(), owners), ownerChain);

    paged(
        (limit, after) ->
            client
                .schemas()
                .listDBSchemas(
                    fields.schema(), row.fqn(), null, limit, null, after, INCLUDE, null, null),
        DatabaseSchemaList::getData,
        DatabaseSchemaList::getPaging,
        page -> {
          for (DatabaseSchema schema : page) {
            crawlSchema(schema, next, nextOwners, exclusive, sink, stats);
          }
        });
  }

  void crawlSchema(
      DatabaseSchema schema,
      List<Level> chain,
      List<AssetMapper.OwnerLevel> ownerChain,
      Set<String> exclusive,
      AssetSink sink,
      Stats stats) {

    CrawledAsset.AssetRow row = AssetMapper.schema(schema);
    if (row == null) {
      return;
    }
    List<ExtractedFacet> own = AssetMapper.schemaFacets(schema);
    List<CrawledAsset.OwnerRow> owners = AssetMapper.owners(row.fqn(), schema.getOwners());
    emit(
        sink,
        stats,
        new CrawledAsset(
            row,
            List.of(),
            FacetInheritance.effective(row.fqn(), own, chain, exclusive),
            AssetMapper.effectiveOwners(row.fqn(), owners, ownerChain)));
    stats.schemas++;

    List<Level> next = nearestFirst(new Level(row.fqn(), own), chain);
    List<AssetMapper.OwnerLevel> nextOwners =
        nearestFirstOwners(new AssetMapper.OwnerLevel(row.fqn(), owners), ownerChain);

    paged(
        (limit, after) ->
            client
                .tables()
                .listTables(
                    fields.table(), null, row.fqn(), null, null, null, limit, null, after, INCLUDE,
                    null, null),
        TableList::getData,
        TableList::getPaging,
        page -> {
          for (Table table : page) {
            crawlTable(table, next, nextOwners, exclusive, sink, stats);
          }
        });
  }

  /**
   * One table and its columns.
   *
   * <p>A column's facets come from three places: its own tags, everything the
   * table holds, and everything the table inherited. All three are folded in at
   * once by putting the table on the front of the same ancestor chain, so a
   * column tagged {@code Tier.Tier3} still overrides a {@code Tier.Tier1} that
   * came down from the database rather than merging with it.
   */
  void crawlTable(
      Table table,
      List<Level> chain,
      List<AssetMapper.OwnerLevel> ownerChain,
      Set<String> exclusive,
      AssetSink sink,
      Stats stats) {

    CrawledAsset.AssetRow row = AssetMapper.table(table);
    if (row == null) {
      return;
    }
    List<ExtractedFacet> own = FacetExtractor.fromTable(table);
    List<CrawledAsset.OwnerRow> owners = AssetMapper.owners(row.fqn(), table.getOwners());

    List<ExtractedFacet> facets =
        new ArrayList<>(FacetInheritance.effective(row.fqn(), own, chain, exclusive));

    List<Level> columnChain = nearestFirst(new Level(row.fqn(), own), chain);
    Map<String, List<ExtractedFacet>> byColumn = groupByTarget(AssetMapper.columnFacets(table));
    List<CrawledAsset.ColumnRow> columns = AssetMapper.columns(table);
    for (CrawledAsset.ColumnRow column : columns) {
      facets.addAll(
          FacetInheritance.effective(
              column.fqn(),
              byColumn.getOrDefault(column.fqn(), List.of()),
              columnChain,
              exclusive));
    }

    emit(
        sink,
        stats,
        new CrawledAsset(
            row,
            columns,
            facets,
            AssetMapper.effectiveOwners(row.fqn(), owners, ownerChain)));
    stats.tables++;
    stats.columns += columns.size();
  }

  private static Map<String, List<ExtractedFacet>> groupByTarget(List<ExtractedFacet> facets) {
    Map<String, List<ExtractedFacet>> out = new LinkedHashMap<>();
    for (ExtractedFacet facet : facets) {
      out.computeIfAbsent(facet.targetFqn(), key -> new ArrayList<>()).add(facet);
    }
    return out;
  }

  private static List<Level> nearestFirst(Level nearest, List<Level> rest) {
    List<Level> out = new ArrayList<>(rest.size() + 1);
    out.add(nearest);
    out.addAll(rest);
    return List.copyOf(out);
  }

  private static List<AssetMapper.OwnerLevel> nearestFirstOwners(
      AssetMapper.OwnerLevel nearest, List<AssetMapper.OwnerLevel> rest) {
    List<AssetMapper.OwnerLevel> out = new ArrayList<>(rest.size() + 1);
    out.add(nearest);
    out.addAll(rest);
    return List.copyOf(out);
  }

  private static void emit(AssetSink sink, Stats stats, CrawledAsset asset) {
    sink.asset(asset);
    stats.facets += asset.facets().size();
  }

  /**
   * The {@code ?fields=} lists this crawler was built with.
   *
   * <p>For {@link AssetRefresher}, which re-reads a single entity and has to ask
   * for exactly the fields the crawl asked for. A refresh that requested fewer
   * would write an asset with, say, no tags — indistinguishable in the cache
   * from an asset whose tags had been removed.
   */
  Fields fields() {
    return fields;
  }

  /** {@link OmPager#forEachPage} with the checked exception carried across. */
  private <R, T> void paged(
      OmPager.PageRequest<R> request,
      java.util.function.Function<R, List<T>> data,
      java.util.function.Function<R, com.mfec.dac.om.client.model.Paging> paging,
      java.util.function.Consumer<List<T>> consumer) {
    try {
      OmPager.forEachPage(request, data, paging, pageSize, consumer);
    } catch (ApiException e) {
      throw new Wrapped(e);
    }
  }
}
