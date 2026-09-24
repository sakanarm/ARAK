package com.mfec.dac.om.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.mfec.dac.om.client.model.Column;
import com.mfec.dac.om.client.model.Table;
import com.mfec.dac.om.client.model.TagLabel;
import com.mfec.dac.om.facet.ExtractedFacet;
import com.mfec.dac.om.facet.FacetExtractor;
import com.mfec.dac.om.facet.FacetInheritance.Level;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The descent itself — that a column ends up in the domain its database is
 * in, that each row still names the level it came from, and that a tag stays on
 * whatever a steward attached it to.
 *
 * <p>Exercised through {@code crawlTable} with a hand-built ancestor chain
 * rather than through a live catalogue: the levels are the part that is easy to
 * get wrong, and a running OpenMetadata proves nothing about the two cases that
 * matter most, which are a domain several levels up and a tag that must not move
 * at all.
 */
class AssetCrawlerTest {

  private static final String SERVICE = "prod-mssql";
  private static final String DATABASE = "prod-mssql.SalesDB";
  private static final String SCHEMA = "prod-mssql.SalesDB.dbo";
  private static final String TABLE = "prod-mssql.SalesDB.dbo.customer";

  private static final class Collecting implements AssetSink {
    private final List<CrawledAsset> assets = new ArrayList<>();

    @Override
    public void asset(CrawledAsset asset) {
      assets.add(asset);
    }

    CrawledAsset only() {
      assertThat(assets).hasSize(1);
      return assets.get(0);
    }
  }

  private static TagLabel tag(String fqn) {
    return new TagLabel()
        .tagFQN(fqn)
        .source(TagLabel.SourceEnum.CLASSIFICATION)
        .labelType(TagLabel.LabelTypeEnum.MANUAL)
        .state(TagLabel.StateEnum.CONFIRMED);
  }

  /**
   * A level's facets as the crawler would have built them.
   *
   * <p>Through the real extractor, so the fixture carries the ancestor and
   * classification rows a tag implies. A hand-made single row would test a
   * descent that never happens.
   */
  private static List<ExtractedFacet> tagOn(String target, String value) {
    return FacetExtractor.fromTagLabels(target, List.of(tag(value)));
  }

  /** A level's domain, with the ancestor rows a sub-domain implies. */
  private static List<ExtractedFacet> domainOn(String target, String value) {
    ExtractedFacet own = ExtractedFacet.direct(target, FacetType.DOMAINS, value);
    List<ExtractedFacet> out = new ArrayList<>();
    out.add(own);
    out.addAll(FacetExtractor.ancestorsOf(own));
    return out;
  }

  private static Table customer(Column... columns) {
    return new Table().name("customer").fullyQualifiedName(TABLE).columns(List.of(columns));
  }

  private static Column column(String name) {
    return new Column()
        .name(name)
        .fullyQualifiedName(TABLE + "." + name)
        .dataType(Column.DataTypeEnum.VARCHAR);
  }

  private static CrawledAsset crawl(Table table, List<Level> chain, Set<String> exclusive) {
    Collecting sink = new Collecting();
    new AssetCrawler(null)
        .crawlTable(table, chain, List.of(), exclusive, sink, new AssetCrawler.Stats());
    return sink.only();
  }

  private static List<String> facetsOf(CrawledAsset asset, String target, FacetType type) {
    return asset.facets().stream()
        .filter(f -> f.targetFqn().equals(target) && f.facetType() == type)
        .map(ExtractedFacet::facetFqn)
        .toList();
  }

  @Test
  @DisplayName("a column inherits from every level above it, not only from its table")
  void inheritsThroughTheWholeChain() {
    CrawledAsset asset =
        crawl(
            customer(column("email")),
            List.of(
                new Level(SCHEMA, domainOn(SCHEMA, "Finance.Risk.Credit")),
                new Level(DATABASE, domainOn(DATABASE, "Finance.Ops")),
                new Level(SERVICE, domainOn(SERVICE, "Platform"))),
            Set.of());

    assertThat(facetsOf(asset, TABLE + ".email", FacetType.DOMAINS))
        .containsExactlyInAnyOrder(
            "Finance.Risk.Credit", "Finance.Risk", "Finance", "Finance.Ops", "Platform");
  }

  @Test
  @DisplayName("names the level a facet actually came from, not the nearest one")
  void attributesToTheRightLevel() {
    CrawledAsset asset =
        crawl(
            customer(column("email")),
            List.of(
                new Level(SCHEMA, domainOn(SCHEMA, "Finance.Risk")),
                new Level(DATABASE, domainOn(DATABASE, "Platform.Shared"))),
            Set.of());

    // Passing each level its own facets rather than its effective ones is what
    // keeps this honest: a database's domain arriving at a column must not be
    // reported as having come from the schema it passed through.
    assertThat(asset.facets())
        .filteredOn(f -> f.targetFqn().equals(TABLE + ".email") && f.depth() == 0)
        .extracting(ExtractedFacet::facetFqn, ExtractedFacet::inheritedFrom)
        .contains(tuple("Finance.Risk", SCHEMA), tuple("Platform.Shared", DATABASE));
  }

  @Test
  @DisplayName("a column keeps its own tag, and the database's tag never arrives")
  void columnKeepsOnlyItsOwnTag() {
    Table table = customer(column("email").tags(List.of(tag("Tier.Tier3"))));

    CrawledAsset asset =
        crawl(table, List.of(new Level(DATABASE, tagOn(DATABASE, "Tier.Tier1"))), Set.of("Tier"));

    // Tier is a statement about one asset. A column that states its own must not
    // also count as the database's — and since no tag descends at all, the
    // database's never reaches it, whether or not the column said anything.
    assertThat(facetsOf(asset, TABLE + ".email", FacetType.TAGS))
        .containsExactly("Tier.Tier3", "Tier");
    assertThat(facetsOf(asset, TABLE + ".email", FacetType.TIER)).containsExactly("Tier3");
  }

  @Test
  @DisplayName("a column with no tags of its own is left with none")
  void untaggedColumnsStayUntagged() {
    CrawledAsset asset =
        crawl(
            customer(column("id"), column("email")),
            List.of(new Level(SCHEMA, tagOn(SCHEMA, "PII.Sensitive"))),
            Set.of());

    // OpenMetadata shows these two columns with no tags, so this platform shows
    // them with no tags. Tagging the schema is a statement about the schema; if a
    // policy is meant to cover everything under it, that is a SCHEMA-scoped
    // policy, not a copied row on every column (FR-3.1).
    assertThat(facetsOf(asset, TABLE + ".id", FacetType.TAGS)).isEmpty();
    assertThat(facetsOf(asset, TABLE + ".email", FacetType.TAGS)).isEmpty();
  }

  @Test
  @DisplayName("a column still lands in its schema's domain while its tags stay put")
  void domainDescendsWhereTagsDoNot() {
    List<ExtractedFacet> schemaFacets = new ArrayList<>(tagOn(SCHEMA, "PII.Sensitive"));
    schemaFacets.addAll(domainOn(SCHEMA, "Finance.Risk"));

    CrawledAsset asset =
        crawl(customer(column("id")), List.of(new Level(SCHEMA, schemaFacets)), Set.of());

    // One level, two facet types, two different answers: the whole rule in a
    // single crawl.
    assertThat(facetsOf(asset, TABLE + ".id", FacetType.TAGS)).isEmpty();
    assertThat(facetsOf(asset, TABLE + ".id", FacetType.DOMAINS))
        .containsExactlyInAnyOrder("Finance.Risk", "Finance");
  }

  @Test
  @DisplayName("counts what it wrote, so a crawl that lost a level is visible in the log")
  void countsRows() {
    Collecting sink = new Collecting();
    AssetCrawler.Stats stats = new AssetCrawler.Stats();
    new AssetCrawler(null)
        .crawlTable(
            customer(column("id"), column("email")),
            List.of(new Level(SCHEMA, (tagOn(SCHEMA, "PII.Sensitive")))),
            List.of(),
            Set.of(),
            sink,
            stats);

    assertThat(stats.tables()).isEqualTo(1);
    assertThat(stats.columns()).isEqualTo(2);
    assertThat(stats.facets()).isEqualTo(sink.only().facets().size());
  }
}
