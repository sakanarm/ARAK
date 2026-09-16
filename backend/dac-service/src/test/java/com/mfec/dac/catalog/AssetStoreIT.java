package com.mfec.dac.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.om.crawl.AssetCrawler;
import com.mfec.dac.om.crawl.CrawledAsset;
import com.mfec.dac.om.facet.ExtractedFacet;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
 * The cache against a real PostgreSQL (NFR-5).
 *
 * <p>Not mocked, because every behaviour worth testing here is the database's:
 * partial unique indexes, {@code IS NOT DISTINCT FROM} against nulls, jsonb
 * equality that ignores key order, and {@code ON CONFLICT} clauses that decline
 * to overwrite a locally authored row. A fake would agree with whatever the code
 * believed.
 */
@Testcontainers
class AssetStoreIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String SERVICE = "prod-mssql";
  private static final String DATABASE = "prod-mssql.SalesDB";
  private static final String SCHEMA = "prod-mssql.SalesDB.dbo";
  private static final String TABLE = "prod-mssql.SalesDB.dbo.customer";

  private static Jdbi jdbi;
  private final ObjectMapper json = new ObjectMapper();

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
  }

  // ------------------------------------------------------------- fixtures

  /**
   * OpenMetadata's id for an FQN, stable across crawls the way the real one is.
   *
   * <p>A fresh random id per fixture would make every asset look changed on the
   * second crawl, and the unchanged-asset test would pass for the wrong reason —
   * or rather, fail for a reason that says nothing about the store.
   */
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

  private static CrawledAsset customer(String description) {
    CrawledAsset.AssetRow row =
        new CrawledAsset.AssetRow(
            omId(TABLE),
            TABLE,
            "TABLE",
            SCHEMA,
            "customer",
            "Customer",
            description,
            "Tier1",
            null,
            Map.of("dataResidency", "TH"));
    List<CrawledAsset.ColumnRow> columns =
        List.of(
            new CrawledAsset.ColumnRow(
                TABLE + ".id", "id", 0, "BIGINT", null, false, null, Map.of()),
            new CrawledAsset.ColumnRow(
                TABLE + ".email", "email", 1, "VARCHAR", 255, true, null, Map.of()));
    List<ExtractedFacet> facets =
        List.of(
            ExtractedFacet.direct(TABLE, FacetType.TIER, "Tier1"),
            new ExtractedFacet(
                TABLE, FacetType.CUSTOM_PROPERTY, "TH", "dataResidency", 0, true, null, null, null),
            new ExtractedFacet(
                TABLE + ".email", FacetType.TAGS, "PII.Sensitive", null, 0, true, null,
                "Confirmed", "Manual"),
            new ExtractedFacet(
                TABLE + ".email", FacetType.TAGS, "PII", null, 1, false, null, "Confirmed",
                "Manual"),
            new ExtractedFacet(
                TABLE + ".id", FacetType.TAGS, "PII.Sensitive", null, 0, false, SCHEMA, "Confirmed",
                "Propagated"));
    List<CrawledAsset.OwnerRow> owners =
        List.of(
            new CrawledAsset.OwnerRow(TABLE, "user", "alice", omId("alice"), true, null),
            new CrawledAsset.OwnerRow(TABLE, "team", "Finance", omId("Finance"), false, SCHEMA));
    return new CrawledAsset(row, columns, facets, owners);
  }

  /**
   * The crawl as the crawler would run it: parents first, then the table.
   *
   * <p>Stats arrives empty because only the crawler fills it in, and the store
   * reads it for the log line rather than for the sweep decision — what decides
   * that is what the store itself wrote.
   */
  private AssetStore crawl(Instant at, List<CrawledAsset> assets) {
    AssetStore store = new AssetStore(jdbi, json, at);
    assets.forEach(store::asset);
    store.finished(new AssetCrawler.Stats());
    return store;
  }

  private List<CrawledAsset> tree(String description) {
    return List.of(
        container(SERVICE, "SERVICE", null, "prod-mssql"),
        container(DATABASE, "DATABASE", SERVICE, "SalesDB"),
        container(SCHEMA, "SCHEMA", DATABASE, "dbo"),
        customer(description));
  }

  private int count(String sql) {
    return one(sql, Integer.class);
  }

  private <T> T one(String sql, Class<T> type) {
    return jdbi.withHandle(handle -> handle.createQuery(sql).mapTo(type).one());
  }

  // ---------------------------------------------------------------- tests

  @Test
  @DisplayName("write a crawl down whole — asset, columns, facets, owners and the physical map")
  void writesTheWholeAsset() {
    Instant now = Instant.now();
    crawl(now, tree("Customers"));

    assertThat(count("SELECT count(*)::int FROM asset WHERE is_current")).isEqualTo(4);
    assertThat(count("SELECT count(*)::int FROM asset_column WHERE is_current")).isEqualTo(2);
    assertThat(count("SELECT count(*)::int FROM asset_facet")).isEqualTo(5);
    assertThat(count("SELECT count(*)::int FROM asset_owner")).isEqualTo(2);

    // A facet on a column carries column_id, one on the table carries asset_id.
    // The check constraint allows either, and a selector reading the wrong one
    // would answer for the whole table when it was asked about one field.
    assertThat(
            count(
                """
                SELECT count(*)::int FROM asset_facet
                WHERE target_fqn = 'prod-mssql.SalesDB.dbo.customer.email'
                  AND column_id IS NOT NULL AND asset_id IS NULL
                """))
        .isEqualTo(2);

    // A custom property needs its name as well as its value: dataResidency = TH
    // and country = TH are not the same fact.
    assertThat(
            one(
                """
                SELECT property || '=' || facet_fqn FROM asset_facet
                WHERE facet_type = 'customProperty'
                """,
                String.class))
        .isEqualTo("dataResidency=TH");

    assertThat(
            one(
                """
                SELECT database_name || '.' || schema_name || '.' || object_name
                FROM asset_fqn_map
                """,
                String.class))
        .isEqualTo("SalesDB.dbo.customer");
  }

  @Test
  @DisplayName("leave an unchanged asset on the version it already had")
  void unchangedAssetsKeepTheirVersion() {
    crawl(Instant.now().minus(1, ChronoUnit.HOURS), tree("Customers"));
    UUID first = currentAssetId(TABLE);

    AssetStore second = crawl(Instant.now(), tree("Customers"));

    // A new row per crawl would bury the one version that mattered under a
    // thousand identical ones, and an auditor asking what the catalog said on a
    // given day would have to read every one of them to find out nothing moved.
    assertThat(second.revised()).isZero();
    assertThat(second.unchanged()).isEqualTo(4);
    assertThat(count("SELECT count(*)::int FROM asset WHERE fqn = '" + TABLE + "'")).isEqualTo(1);
    assertThat(currentAssetId(TABLE)).isEqualTo(first);
  }

  @Test
  @DisplayName("close the old version and open a new one when the asset changes")
  void changedAssetsGetANewVersion() {
    Instant first = Instant.now().minus(1, ChronoUnit.HOURS);
    crawl(first, tree("Customers"));
    UUID oldColumnId = currentColumnId(TABLE + ".email");

    AssetStore second = crawl(Instant.now(), tree("Customers of record"));

    assertThat(second.revised()).isEqualTo(1);
    assertThat(count("SELECT count(*)::int FROM asset WHERE fqn = '" + TABLE + "'")).isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*)::int FROM asset WHERE fqn = '"
                    + TABLE
                    + "' AND NOT is_current AND valid_to IS NOT NULL"))
        .isEqualTo(1);

    // The column did not change, so its own history must not restart because
    // the table's description did — but it has to follow the table to its new
    // version, or the facets hanging off it point at a row nobody reads.
    assertThat(currentColumnId(TABLE + ".email")).isEqualTo(oldColumnId);
    assertThat(
            count(
                """
                SELECT count(*)::int FROM asset_column c
                JOIN asset a ON a.id = c.asset_id
                WHERE c.is_current AND a.is_current
                """))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("retire what the crawl stopped mentioning, facets and all")
  void retiresWhatVanished() {
    crawl(Instant.now().minus(1, ChronoUnit.HOURS), tree("Customers"));

    List<CrawledAsset> withoutTheTable = tree("Customers").subList(0, 3);
    AssetStore second = crawl(Instant.now(), withoutTheTable);

    assertThat(second.retired()).isEqualTo(1);
    assertThat(count("SELECT count(*)::int FROM asset WHERE is_current")).isEqualTo(3);
    assertThat(count("SELECT count(*)::int FROM asset_column WHERE is_current")).isZero();

    // The facets are the part that matters. A selector reading a row that says
    // a dropped table's column is PII would go on protecting an object that no
    // longer exists — and, worse, a policy author looking at the impact
    // analysis would see a table nobody can query.
    assertThat(count("SELECT count(*)::int FROM asset_facet")).isZero();
    assertThat(count("SELECT count(*)::int FROM asset_owner")).isZero();
    assertThat(count("SELECT count(*)::int FROM asset_fqn_map")).isZero();
  }

  @Test
  @DisplayName("refuse to retire anything when the crawl found nothing at all")
  void emptyCrawlDoesNotSweep() {
    crawl(Instant.now().minus(1, ChronoUnit.HOURS), tree("Customers"));

    AssetStore second = crawl(Instant.now(), List.of());

    // An OpenMetadata answering an empty list is far more likely to be a token
    // that lost its scope than an organisation that deleted every table, and
    // the two are indistinguishable here. Retiring on the strength of it would
    // empty the cache every policy decision reads.
    assertThat(second.retired()).isZero();
    assertThat(count("SELECT count(*)::int FROM asset WHERE is_current")).isEqualTo(4);
    assertThat(count("SELECT count(*)::int FROM asset_facet")).isEqualTo(5);
  }

  @Test
  @DisplayName("replace a column's facets rather than adding to them")
  void facetsAreReplaced() {
    crawl(Instant.now().minus(1, ChronoUnit.HOURS), tree("Customers"));

    CrawledAsset stripped =
        new CrawledAsset(
            customer("Customers").asset(),
            customer("Customers").columns(),
            List.of(ExtractedFacet.direct(TABLE, FacetType.TIER, "Tier1")),
            List.of());
    crawl(
        Instant.now(),
        List.of(
            container(SERVICE, "SERVICE", null, "prod-mssql"),
            container(DATABASE, "DATABASE", SERVICE, "SalesDB"),
            container(SCHEMA, "SCHEMA", DATABASE, "dbo"),
            stripped));

    // A tag removed in OpenMetadata has to disappear here too. Merging would
    // leave the row that says this column is PII exactly where it was, and the
    // column would stay masked with nothing in the catalog explaining why.
    assertThat(count("SELECT count(*)::int FROM asset_facet")).isEqualTo(1);
    assertThat(count("SELECT count(*)::int FROM asset_owner")).isZero();
  }

  private UUID currentAssetId(String fqn) {
    return one("SELECT id FROM asset WHERE fqn = '" + fqn + "' AND is_current", UUID.class);
  }

  private UUID currentColumnId(String fqn) {
    return one("SELECT id FROM asset_column WHERE fqn = '" + fqn + "' AND is_current", UUID.class);
  }
}
