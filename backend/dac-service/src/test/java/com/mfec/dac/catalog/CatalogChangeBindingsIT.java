package com.mfec.dac.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mfec.dac.om.OpenMetadataClient;
import com.mfec.dac.om.crawl.AssetCrawler;
import com.mfec.dac.om.crawl.AssetRefresher;
import com.mfec.dac.om.crawl.CrawledAsset;
import com.mfec.dac.om.events.CatalogChange;
import com.mfec.dac.om.facet.ExtractedFacet;
import com.mfec.dac.policy.AssetContextLoader;
import com.mfec.dac.policy.PolicyBindingMaterializer;
import com.mfec.dac.policy.PolicyStore;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.Policy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
 * A change arriving from OpenMetadata moves policy bindings as well as the
 * cache (FR-3.1.6), whichever road it came by: the webhook and the poller both
 * end in this applier.
 *
 * <p>The read from OpenMetadata is stood in for by a refresher that writes the
 * cache the way a real one would; everything after it — the applier, the
 * materialiser, the database — is the real thing.
 */
@Testcontainers
class CatalogChangeBindingsIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String SERVICE = "prod-mssql";
  private static final String SALES = "prod-mssql.SalesDB";
  private static final String SALES_DBO = "prod-mssql.SalesDB.dbo";
  private static final String CUSTOMER = "prod-mssql.SalesDB.dbo.customer";
  private static final String ORDER = "prod-mssql.SalesDB.dbo.order";
  // Shares a prefix with SalesDB, so a change under one reaching into the
  // other cannot hide.
  private static final String ARCHIVE = "prod-mssql.SalesDBArchive";
  private static final String ARCHIVE_DBO = "prod-mssql.SalesDBArchive.dbo";
  private static final String ARCHIVED_CUSTOMER = "prod-mssql.SalesDBArchive.dbo.customer";

  private static Jdbi jdbi;
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  private PolicyStore store;
  private PolicyBindingMaterializer materializer;
  private AssetRefresher refresher;
  private CatalogChangeApplier applier;

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
          handle.execute(
              """
              TRUNCATE access_request_vote, access_request_stage, access_request, policy_version, policy_binding, access_grant, row_entitlement,
                       enforcement_state, asset_facet, asset_owner, asset_fqn_map,
                       asset_column, asset, policy
              """);
          handle.execute("DELETE FROM data_source");
          handle.execute(
              """
              INSERT INTO data_source (name, engine, host, port, credential_ref, om_service_fqn)
              VALUES ('prod-mssql', 'SQLSERVER', 'db.example.test', 1433, 'vault://x', 'prod-mssql')
              """);
        });
    store = new PolicyStore(jdbi, json);
    materializer = new PolicyBindingMaterializer(jdbi, json, new AssetContextLoader(json));
    refresher = mock(AssetRefresher.class);
    applier = applier(bindingsOf(materializer));
    crawl(false, false, true);
  }

  @Test
  @DisplayName("a tag that arrives from OpenMetadata brings its table under the policy at once")
  void aNewTagBindsTheTable() throws Exception {
    UUID id = policy("mask-pii", ResolvedColumnMask.ScopeLevel.ORG, null);
    materializer.materialize(id);
    assertThat(targets(id)).containsExactly(ARCHIVED_CUSTOMER);

    // What the refresher does for real: re-reads the table from OpenMetadata
    // and writes it, now carrying PII.Sensitive, into the cache.
    when(refresher.refresh(any(), anyString(), any(), any()))
        .thenAnswer(
            call -> {
              crawl(true, false, true);
              return new AssetCrawler.Stats();
            });

    CatalogChangeApplier.Outcome outcome = applier.apply(List.of(upserted(CUSTOMER)));

    // Nobody pressed "re-resolve": the webhook did it.
    assertThat(targets(id)).containsExactlyInAnyOrder(CUSTOMER, ARCHIVED_CUSTOMER);
    assertThat(outcome.rebound()).isOne();
    assertThat(outcome.failed()).isZero();
  }

  @Test
  @DisplayName("a schema deleted in OpenMetadata takes its tables out of every policy")
  void aDeletedSchemaUnbindsItsTables() {
    crawl(true, true, true);
    UUID id = policy("mask-pii", ResolvedColumnMask.ScopeLevel.ORG, null);
    materializer.materialize(id);
    assertThat(targets(id)).containsExactlyInAnyOrder(CUSTOMER, ORDER, ARCHIVED_CUSTOMER);

    CatalogChangeApplier.Outcome outcome = applier.apply(List.of(removed(SALES_DBO)));

    // OpenMetadata sends one event for the schema, not one per table; the
    // tables under it go too, and the archive next door stays.
    assertThat(targets(id)).containsExactly(ARCHIVED_CUSTOMER);
    assertThat(outcome.rebound()).isEqualTo(2);
  }

  @Test
  @DisplayName("the tables under a change are found by segment, not by string prefix")
  void tablesUnderAChangeAreFoundBySegment() {
    assertThat(applier.tablesAtOrUnder(List.of(SALES))).containsExactly(CUSTOMER, ORDER);
    assertThat(applier.tablesAtOrUnder(List.of(CUSTOMER))).containsExactly(CUSTOMER);
  }

  @Test
  @DisplayName("a re-resolve that fails counts as a failed change, so it is tried again")
  void aFailedReResolveIsRetried() throws Exception {
    when(refresher.refresh(any(), anyString(), any(), any())).thenReturn(new AssetCrawler.Stats());
    CatalogChangeApplier failing =
        applier(
            new CatalogChangeApplier.Bindings() {
              @Override
              public int refresh(List<String> tableFqns) {
                throw new IllegalStateException("database gone");
              }

              @Override
              public int refreshAll() {
                throw new IllegalStateException("database gone");
              }
            });

    CatalogChangeApplier.Outcome outcome = failing.apply(List.of(upserted(CUSTOMER)));

    // The poller holds its cursor on a failure and the webhook answers 500, so
    // the same change comes round again rather than leaving the table
    // unprotected until the nightly reconcile.
    assertThat(outcome.refreshed()).isOne();
    assertThat(outcome.failed()).isOne();
  }

  // ----------------------------------------------------------------- fixtures

  private CatalogChangeApplier applier(CatalogChangeApplier.Bindings bindings) {
    return new CatalogChangeApplier(
        jdbi, json, mock(OpenMetadataClient.class), bindings, () -> refresher);
  }

  private static CatalogChangeApplier.Bindings bindingsOf(PolicyBindingMaterializer materializer) {
    return new CatalogChangeApplier.Bindings() {
      @Override
      public int refresh(List<String> tableFqns) {
        return materializer.refresh(tableFqns).stream()
            .mapToInt(r -> r.added() + r.removed())
            .sum();
      }

      @Override
      public int refreshAll() {
        return materializer.materializeAll().stream()
            .mapToInt(r -> r.added() + r.removed())
            .sum();
      }
    };
  }

  private static CatalogChange upserted(String fqn) {
    return new CatalogChange(
        CatalogChange.Subject.TABLE,
        CatalogChange.Kind.UPSERTED,
        "table",
        omId(fqn),
        fqn,
        System.currentTimeMillis());
  }

  private static CatalogChange removed(String fqn) {
    return new CatalogChange(
        CatalogChange.Subject.SCHEMA,
        CatalogChange.Kind.REMOVED,
        "databaseSchema",
        omId(fqn),
        fqn,
        System.currentTimeMillis());
  }

  private List<String> targets(UUID policyId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT target_fqn FROM policy_binding WHERE policy_id = :id ORDER BY target_fqn")
                .bind("id", policyId)
                .mapTo(String.class)
                .list());
  }

  private UUID policy(String name, ResolvedColumnMask.ScopeLevel level, String scopeFqn) {
    FacetCondition condition = new FacetCondition();
    condition.setFacet(FacetCondition.FacetType.TAGS);
    condition.setOperator(ResolvedRowPredicate.FacetOperator.CONTAINS);
    condition.setValue("PII.Sensitive");
    AssetSelector selector = new AssetSelector();
    selector.setCondition(condition);

    Policy document = new Policy();
    document.setName(name);
    document.setPolicyType(Policy.PolicyType.SUBSCRIPTION);
    document.setScopeLevel(level);
    document.setScopeFqn(scopeFqn);
    document.setSelector(selector);
    return store.create(document, "alice").id();
  }

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

  private static CrawledAsset table(String fqn, String parent, String name, boolean pii) {
    CrawledAsset.AssetRow row =
        new CrawledAsset.AssetRow(
            omId(fqn), fqn, "TABLE", parent, name, null, null, null, null, Map.of());
    List<CrawledAsset.ColumnRow> columns =
        List.of(
            new CrawledAsset.ColumnRow(fqn + ".id", "id", 0, "BIGINT", null, false, null, Map.of()));
    List<ExtractedFacet> facets = new ArrayList<>();
    if (pii) {
      facets.add(tag(fqn, "PII.Sensitive", 0, true));
      facets.add(tag(fqn, "PII", 1, false));
    }
    return new CrawledAsset(row, columns, facets, List.of());
  }

  private static ExtractedFacet tag(String target, String value, int depth, boolean direct) {
    return new ExtractedFacet(
        target,
        FacetCondition.FacetType.TAGS,
        value,
        null,
        depth,
        direct,
        null,
        "Confirmed",
        "Manual");
  }

  /** The whole tree, written as a full crawl would write it. */
  private void crawl(boolean customerPii, boolean orderPii, boolean archivePii) {
    AssetStore sink = new AssetStore(jdbi, json, Instant.now());
    List<CrawledAsset> tree =
        List.of(
            container(SERVICE, "SERVICE", null, "prod-mssql"),
            container(SALES, "DATABASE", SERVICE, "SalesDB"),
            container(SALES_DBO, "SCHEMA", SALES, "dbo"),
            container(ARCHIVE, "DATABASE", SERVICE, "SalesDBArchive"),
            container(ARCHIVE_DBO, "SCHEMA", ARCHIVE, "dbo"),
            table(CUSTOMER, SALES_DBO, "customer", customerPii),
            table(ORDER, SALES_DBO, "order", orderPii),
            table(ARCHIVED_CUSTOMER, ARCHIVE_DBO, "customer", archivePii));
    tree.forEach(sink::asset);
    sink.finished(new AssetCrawler.Stats());
  }
}
