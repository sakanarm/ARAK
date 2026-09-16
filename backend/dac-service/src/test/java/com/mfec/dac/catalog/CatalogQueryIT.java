package com.mfec.dac.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.om.crawl.AssetCrawler;
import com.mfec.dac.om.crawl.CrawledAsset;
import com.mfec.dac.om.facet.ExtractedFacet;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The catalog read API against a real PostgreSQL (NFR-5).
 *
 * <p>The behaviour worth proving here is entirely the SQL's: that filters AND
 * rather than OR, that a tag on one column surfaces its table, that the
 * pre-expanded ancestors make a broad domain filter find a deeply nested asset
 * without a recursive query, and that a retired asset disappears from the list.
 * Each of those is a wrong answer a human reads as fact.
 */
@Testcontainers
class CatalogQueryIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String SERVICE = "prod-mssql";
  private static final String DATABASE = "prod-mssql.SalesDB";
  private static final String SCHEMA = "prod-mssql.SalesDB.dbo";
  private static final String CUSTOMER = "prod-mssql.SalesDB.dbo.customer";
  private static final String ORDER = "prod-mssql.SalesDB.dbo.order";

  private static Jdbi jdbi;
  private final ObjectMapper json = new ObjectMapper();
  private CatalogQuery catalog;

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbi.installPlugins();
  }

  @BeforeEach
  void clean() {
    jdbi.useHandle(
        handle -> {
          // policy_binding and enforcement_state reference asset, so PostgreSQL
          // refuses to truncate it without them. Listed rather than CASCADE:
          // a table added later should fail here loudly, not be emptied
          // silently by a test that was never meant to touch it.
          handle.execute(
              """
              TRUNCATE policy_binding, enforcement_state, asset_facet, asset_owner,
                       asset_fqn_map, asset_column, asset
              """);
          handle.execute("DELETE FROM data_source");
          handle.execute(
              """
              INSERT INTO data_source (name, engine, host, port, credential_ref, om_service_fqn)
              VALUES ('prod-mssql', 'SQLSERVER', 'db.internal', 1433, 'vault://x', 'prod-mssql')
              """);
        });
    catalog = new CatalogQuery(jdbi, json);
    crawl(Instant.now(), tree());
  }

  // ---------------------------------------------------------------- fixtures

  private static UUID omId(String fqn) {
    return UUID.nameUUIDFromBytes(fqn.getBytes(StandardCharsets.UTF_8));
  }

  private static CrawledAsset container(String fqn, String type, String parent, String name) {
    return new CrawledAsset(
        new CrawledAsset.AssetRow(
            omId(fqn), fqn, type, parent, name, null, null, null, null, Map.of()),
        List.of(),
        List.of(),
        List.of());
  }

  /**
   * A table in a third-level sub-domain with PII on one column only.
   *
   * <p>Its domain facets arrive pre-expanded, exactly as the crawler writes them
   * (FR-2A.2): three rows, depth 0 for the sub-domain the asset is actually in
   * and one per ancestor above it.
   */
  private static CrawledAsset customer() {
    CrawledAsset.AssetRow row =
        new CrawledAsset.AssetRow(
            omId(CUSTOMER),
            CUSTOMER,
            "TABLE",
            SCHEMA,
            "customer",
            "Customer",
            "One row per customer",
            "Tier1",
            null,
            Map.of("dataResidency", "TH"));
    List<CrawledAsset.ColumnRow> columns =
        List.of(
            new CrawledAsset.ColumnRow(
                CUSTOMER + ".id", "id", 0, "BIGINT", null, false, null, Map.of()),
            new CrawledAsset.ColumnRow(
                CUSTOMER + ".email", "email", 1, "VARCHAR", 255, true, "Contact", Map.of()));
    List<ExtractedFacet> facets =
        List.of(
            ExtractedFacet.direct(CUSTOMER, FacetType.TIER, "Tier1"),
            new ExtractedFacet(
                CUSTOMER, FacetType.DOMAINS, "Finance.Risk.Credit", null, 0, true, null, null, null),
            new ExtractedFacet(
                CUSTOMER, FacetType.DOMAINS, "Finance.Risk", null, 1, false, null, null, null),
            new ExtractedFacet(
                CUSTOMER, FacetType.DOMAINS, "Finance", null, 2, false, null, null, null),
            new ExtractedFacet(
                CUSTOMER, FacetType.CUSTOM_PROPERTY, "TH", "dataResidency", 0, true, null, null,
                null),
            new ExtractedFacet(
                CUSTOMER + ".email", FacetType.TAGS, "PII.Sensitive", null, 0, true, null,
                "Confirmed", "Manual"),
            new ExtractedFacet(
                CUSTOMER + ".email", FacetType.TAGS, "PII", null, 1, false, null, "Confirmed",
                "Manual"));
    List<CrawledAsset.OwnerRow> owners =
        List.of(
            new CrawledAsset.OwnerRow(CUSTOMER, "user", "alice", omId("alice"), true, null),
            new CrawledAsset.OwnerRow(CUSTOMER, "team", "Finance", omId("Finance"), false, SCHEMA));
    return new CrawledAsset(row, columns, facets, owners);
  }

  /** A neighbouring table with no governance on it at all, and no owner. */
  private static CrawledAsset order() {
    CrawledAsset.AssetRow row =
        new CrawledAsset.AssetRow(
            omId(ORDER), ORDER, "TABLE", SCHEMA, "order", null, null, null, null, Map.of());
    return new CrawledAsset(
        row,
        List.of(
            new CrawledAsset.ColumnRow(
                ORDER + ".id", "id", 0, "BIGINT", null, false, null, Map.of())),
        List.of(),
        List.of());
  }

  private void crawl(Instant at, List<CrawledAsset> assets) {
    AssetStore store = new AssetStore(jdbi, json, at);
    assets.forEach(store::asset);
    store.finished(new AssetCrawler.Stats());
  }

  private static List<CrawledAsset> tree() {
    return List.of(
        container(SERVICE, "SERVICE", null, "prod-mssql"),
        container(DATABASE, "DATABASE", SERVICE, "SalesDB"),
        container(SCHEMA, "SCHEMA", DATABASE, "dbo"),
        customer(),
        order());
  }

  private CatalogQuery.AssetPage page(CatalogQuery.FacetFilter... filters) {
    return catalog.assets(null, "TABLE", List.of(filters), null, 50, 0);
  }

  private static CatalogQuery.FacetFilter facet(String raw) {
    return CatalogQuery.FacetFilter.parse(raw).orElseThrow();
  }

  // ------------------------------------------------------------------- list

  @Test
  @DisplayName("list every current asset, newest crawl only")
  void listsTheCache() {
    CatalogQuery.AssetPage all = catalog.assets(null, null, List.of(), null, 50, 0);

    assertThat(all.total()).isEqualTo(5);
    assertThat(all.items()).extracting(CatalogQuery.AssetSummary::fqn)
        .containsExactly(SERVICE, DATABASE, SCHEMA, CUSTOMER, ORDER);
  }

  @Test
  @DisplayName("drop an asset from the list once a later crawl stops mentioning it")
  void hidesRetiredAssets() {
    // Not a deletion: the row stays for the audit trail (FR-1.4). A catalog
    // screen still showing it would send a policy author to write a policy
    // against a table that no longer exists.
    crawl(Instant.now().plusSeconds(60), List.of(
        container(SERVICE, "SERVICE", null, "prod-mssql"),
        container(DATABASE, "DATABASE", SERVICE, "SalesDB"),
        container(SCHEMA, "SCHEMA", DATABASE, "dbo"),
        customer()));

    assertThat(page().items()).extracting(CatalogQuery.AssetSummary::fqn)
        .containsExactly(CUSTOMER);
    int rowsForOrder =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery("SELECT count(*)::int FROM asset WHERE fqn = :fqn")
                    .bind("fqn", ORDER)
                    .mapTo(Integer.class)
                    .one());
    assertThat(rowsForOrder).isEqualTo(1);
  }

  @Test
  @DisplayName("search on name and on FQN, not only on one of them")
  void searches() {
    assertThat(catalog.assets("custom", null, List.of(), null, 50, 0).items())
        .extracting(CatalogQuery.AssetSummary::fqn)
        .containsExactly(CUSTOMER);
    assertThat(catalog.assets("SalesDB.dbo", null, List.of(), null, 50, 0).total()).isEqualTo(3);
  }

  @Test
  @DisplayName("find a table by a tag that sits on one of its columns")
  void findsATableByItsColumnTag() {
    // The case a policy author is in most of the time: they know the data is
    // sensitive, not which field carries the label.
    assertThat(page(facet("tags:PII.Sensitive")).items())
        .extracting(CatalogQuery.AssetSummary::fqn)
        .containsExactly(CUSTOMER);
  }

  @Test
  @DisplayName("match a deep sub-domain from its top-level ancestor")
  void matchesAncestorsWithoutRecursion() {
    // The asset is in Finance.Risk.Credit; ancestors were expanded at write
    // time, so this is an equality test rather than a tree walk (FR-2A.2).
    assertThat(page(facet("domains:Finance")).items())
        .extracting(CatalogQuery.AssetSummary::fqn)
        .containsExactly(CUSTOMER);
    assertThat(page(facet("domains:Finance.Risk.Credit")).items()).hasSize(1);
    assertThat(page(facet("domains:Marketing")).items()).isEmpty();
  }

  @Test
  @DisplayName("AND two facet filters rather than OR them")
  void andsFilters() {
    // OR-ing is the easy mistake — one IN clause over every filter — and it
    // widens a search silently: the author narrowing down to PII in Finance
    // would be shown everything in either.
    assertThat(page(facet("domains:Finance"), facet("tags:PII.Sensitive")).items()).hasSize(1);
    assertThat(page(facet("domains:Finance"), facet("tags:Nowhere")).items()).isEmpty();
  }

  @Test
  @DisplayName("filter by owner, direct or inherited")
  void filtersByOwner() {
    assertThat(catalog.assets(null, null, List.of(), "alice", 50, 0).items())
        .extracting(CatalogQuery.AssetSummary::fqn)
        .containsExactly(CUSTOMER);
    assertThat(catalog.assets(null, null, List.of(), "Finance", 50, 0).items()).hasSize(1);
    assertThat(catalog.assets(null, null, List.of(), "nobody", 50, 0).items()).isEmpty();
  }

  @Test
  @DisplayName("carry each row's facets, owners and column counts")
  void decoratesEveryRow() {
    CatalogQuery.AssetSummary row =
        page().items().stream().filter(a -> a.fqn().equals(CUSTOMER)).findFirst().orElseThrow();

    assertThat(row.columnCount()).isEqualTo(2);
    // One column of two carries a tag, and both its tag rows are on that one
    // column: counting facet rows instead of distinct columns would say two.
    assertThat(row.taggedColumnCount()).isEqualTo(1);
    assertThat(row.facets()).extracting(CatalogQuery.FacetRow::facetFqn)
        .contains("Finance", "Finance.Risk", "Finance.Risk.Credit", "Tier1");
    assertThat(row.owners()).extracting(CatalogQuery.Owner::name)
        .containsExactlyInAnyOrder("alice", "Finance");
    assertThat(row.dataSource()).isEqualTo("prod-mssql");
  }

  @Test
  @DisplayName("page without losing the total")
  void pages() {
    CatalogQuery.AssetPage first = catalog.assets(null, null, List.of(), null, 2, 0);

    assertThat(first.items()).hasSize(2);
    assertThat(first.total()).isEqualTo(5);
    assertThat(catalog.assets(null, null, List.of(), null, 2, 4).items()).hasSize(1);
  }

  @Test
  @DisplayName("cap an unreasonable page size instead of trying to serve it")
  void capsThePageSize() {
    assertThat(catalog.assets(null, null, List.of(), null, 100_000, 0).limit())
        .isEqualTo(CatalogQuery.MAX_LIMIT);
  }

  // ----------------------------------------------------------------- detail

  @Test
  @DisplayName("return an asset with its columns, each column's own facets, and why it has them")
  void readsOneAsset() {
    CatalogQuery.AssetDetail detail = catalog.asset(CUSTOMER).orElseThrow();

    assertThat(detail.asset().displayName()).isEqualTo("Customer");
    assertThat(detail.customProperties()).containsEntry("dataResidency", "TH");
    assertThat(detail.columns()).extracting(CatalogQuery.ColumnDetail::name)
        .containsExactly("id", "email");

    CatalogQuery.ColumnDetail email =
        detail.columns().stream().filter(c -> c.name().equals("email")).findFirst().orElseThrow();
    assertThat(email.dataType()).isEqualTo("VARCHAR");
    assertThat(email.facets()).extracting(CatalogQuery.FacetRow::facetFqn)
        .containsExactly("PII.Sensitive", "PII");
    // depth 1 and not direct: PII is the ancestor of the tag actually applied,
    // which is what lets the screen answer "why is this PII?" (FR-2A.1).
    assertThat(email.facets().get(1).direct()).isFalse();
    assertThat(email.facets().get(1).depth()).isEqualTo(1);
    assertThat(email.facets().get(0).omState()).isEqualTo("Confirmed");
  }

  @Test
  @DisplayName("say nothing rather than guess for an FQN that is not cached")
  void missingAssetIsEmpty() {
    assertThat(catalog.asset("prod-mssql.SalesDB.dbo.nosuch")).isEmpty();
  }

  // ---------------------------------------------------------------- filters

  @Test
  @DisplayName("offer only facet values some asset actually carries")
  void listsFacetValuesInUse() {
    List<CatalogQuery.FacetValue> domains = catalog.facetValues("domains", 50);

    assertThat(domains).extracting(CatalogQuery.FacetValue::facetFqn)
        .containsExactlyInAnyOrder("Finance", "Finance.Risk", "Finance.Risk.Credit");
    assertThat(domains).allMatch(value -> value.assets() == 1);

    // A column tag counts towards its table, so the number next to a filter is
    // the number of rows choosing it will return.
    assertThat(catalog.facetValues("tags", 50))
        .extracting(CatalogQuery.FacetValue::facetFqn)
        .contains("PII.Sensitive");
  }

  @Test
  @DisplayName("summarise the cache")
  void summarises() {
    CatalogQuery.CatalogSummary summary = catalog.summary();

    assertThat(summary.assetsByType()).containsEntry("TABLE", 2).containsEntry("SCHEMA", 1);
    assertThat(summary.columns()).isEqualTo(3);
    assertThat(summary.taggedColumns()).isEqualTo(1);
    // `order` has no owner, `customer` does — the number a governance lead is
    // asked for first (FR-8.5).
    assertThat(summary.assetsWithoutOwner()).isEqualTo(1);
  }

  // ----------------------------------------------------------------- parsing

  @Test
  @DisplayName("parse a facet filter, and refuse the shapes that would match everything")
  void parsesFacetFilters() {
    assertThat(CatalogQuery.FacetFilter.parse("tags:PII.Sensitive"))
        .contains(new CatalogQuery.FacetFilter("tags", "PII.Sensitive"));
    // A value that is itself dotted must survive: only the first colon splits.
    assertThat(CatalogQuery.FacetFilter.parse("customProperty:a:b").orElseThrow().facetFqn())
        .isEqualTo("a:b");
    assertThat(CatalogQuery.FacetFilter.parse("tags")).isEmpty();
    assertThat(CatalogQuery.FacetFilter.parse(":PII")).isEmpty();
    assertThat(CatalogQuery.FacetFilter.parse("tags:")).isEmpty();
    assertThat(CatalogQuery.FacetFilter.parse(null)).isEqualTo(Optional.empty());
  }
}
