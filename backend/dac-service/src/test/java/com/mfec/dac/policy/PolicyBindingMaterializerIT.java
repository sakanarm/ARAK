package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mfec.dac.catalog.AssetStore;
import com.mfec.dac.om.crawl.AssetCrawler;
import com.mfec.dac.om.crawl.CrawledAsset;
import com.mfec.dac.om.facet.ExtractedFacet;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.ColumnRule;
import com.mfec.dac.schema.entity.policy.DataPolicy;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.Policy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * Selector resolution into {@code policy_binding} against a real PostgreSQL
 * (FR-3.1.6).
 *
 * <p>The failures worth pinning here are the quiet ones. A binding set that is
 * too wide protects tables nobody meant to protect, which somebody will notice.
 * A binding set that is too narrow, or one a refresh deleted because it was
 * never looked at, leaves a table protected by nothing at all — and the first
 * evidence of that is a row somebody could read.
 */
@Testcontainers
class PolicyBindingMaterializerIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String SERVICE = "prod-mssql";
  private static final String SALES = "prod-mssql.SalesDB";
  private static final String SALES_DBO = "prod-mssql.SalesDB.dbo";
  private static final String CUSTOMER = "prod-mssql.SalesDB.dbo.customer";
  private static final String ORDER = "prod-mssql.SalesDB.dbo.order";

  // Named to share a prefix with SalesDB. A scope built by string concatenation
  // rather than by segment reaches straight into it, and everything in here is
  // tagged exactly like the table in scope so that mistake cannot hide.
  private static final String ARCHIVE = "prod-mssql.SalesDBArchive";
  private static final String ARCHIVE_DBO = "prod-mssql.SalesDBArchive.dbo";
  private static final String ARCHIVED_CUSTOMER = "prod-mssql.SalesDBArchive.dbo.customer";

  private static Jdbi jdbi;
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  private PolicyStore store;
  private PolicyBindingMaterializer materializer;

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
          // Listed rather than CASCADE: a table added later should fail here
          // loudly instead of being emptied by a suite never meant to touch it.
          handle.execute(
              """
              TRUNCATE access_request, policy_version, policy_binding, access_grant, row_entitlement,
                       enforcement_state, asset_facet, asset_owner, asset_fqn_map,
                       asset_column, asset, policy
              """);
          handle.execute("DELETE FROM data_source");
          handle.execute(
              """
              INSERT INTO data_source (name, engine, host, port, credential_ref, om_service_fqn)
              VALUES ('prod-mssql', 'SQLSERVER', 'db.internal', 1433, 'vault://x', 'prod-mssql')
              """);
        });
    store = new PolicyStore(jdbi, json);
    materializer = new PolicyBindingMaterializer(jdbi, json, new AssetContextLoader(json));
    crawl(true, false, true);
  }

  // --------------------------------------------------------------- resolution

  @Test
  @DisplayName("a policy binds what its selector matches, and leaves the rest alone")
  void bindsMatchingAssets() {
    UUID id = policy("mask-pii", ResolvedColumnMask.ScopeLevel.ORG, null);

    PolicyBindingMaterializer.Result result = materializer.materialize(id);

    // Three tables were considered and the untagged one was not bound. Saying
    // both is the point of recording `scanned` next to `matched`.
    assertThat(result.scanned()).isEqualTo(3);
    assertThat(result.matched()).isEqualTo(2);
    assertThat(result.added()).isEqualTo(2);
    assertThat(result.removed()).isZero();
    assertThat(targets(id)).containsExactlyInAnyOrder(CUSTOMER, ARCHIVED_CUSTOMER);
  }

  @Test
  @DisplayName("a binding row carries the asset id, so the asset page can find it")
  void bindingsAreJoinedToTheAsset() {
    UUID id = policy("mask-pii", ResolvedColumnMask.ScopeLevel.ORG, null);
    materializer.materialize(id);

    var row =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT b.target_kind, b.asset_id, b.column_id, a.fqn AS asset_fqn
                        FROM policy_binding b JOIN asset a ON a.id = b.asset_id
                        WHERE b.policy_id = :id AND b.target_fqn = :fqn
                        """)
                    .bind("id", id)
                    .bind("fqn", CUSTOMER)
                    .mapToMap()
                    .one());

    // Without the id, "which policies affect this table" (FR-3.1.5) would have
    // to be answered by matching strings the next rename invalidates.
    assertThat(row.get("target_kind")).isEqualTo("TABLE");
    assertThat(row.get("asset_fqn")).isEqualTo(CUSTOMER);
    assertThat(row.get("column_id")).isNull();
  }

  @Test
  @DisplayName("a scope anchored on SalesDB does not reach into SalesDBArchive")
  void scopeIsComparedBySegment() {
    UUID id = policy("sales-only", ResolvedColumnMask.ScopeLevel.DATABASE, SALES);

    PolicyBindingMaterializer.Result result = materializer.materialize(id);

    // The archive table carries the same tag, so the only thing keeping it out
    // is the prefix guard. A plain LIKE 'prod-mssql.SalesDB%' would have bound
    // it, and a local policy would silently govern an estate nobody scoped it
    // to.
    assertThat(result.scanned()).isEqualTo(2);
    assertThat(targets(id)).containsExactly(CUSTOMER);
  }

  @Test
  @DisplayName("column rules bind the columns they pick out, and say which rule did")
  void columnRulesBindColumns() {
    Policy document = document("mask-email");
    document.setPolicyType(Policy.PolicyType.DATA);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.DATABASE);
    document.setScopeFqn(SALES);

    ColumnRule rule = new ColumnRule();
    rule.setColumns(selector("PII.Sensitive"));
    rule.setAction(ColumnRule.Action.MASK);
    DataPolicy data = new DataPolicy();
    data.setColumnRules(List.of(rule));
    document.setData(data);

    UUID id = store.create(document, "alice").id();
    materializer.materialize(id);

    assertThat(targets(id)).containsExactlyInAnyOrder(CUSTOMER, CUSTOMER + ".email");

    var bound =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT target_kind, match_reason::text AS reason FROM policy_binding
                        WHERE policy_id = :id AND target_fqn = :fqn
                        """)
                    .bind("id", id)
                    .bind("fqn", CUSTOMER + ".email")
                    .mapToMap()
                    .one());

    // "This policy covers the table" is not the answer a data owner came for.
    // Which column, and under which rule, is (FR-3.1.5).
    assertThat(bound.get("target_kind")).isEqualTo("COLUMN");
    assertThat((String) bound.get("reason")).contains("\"columnRule\": 0").contains("MASK");
  }

  // ------------------------------------------------------------- re-resolving

  @Test
  @DisplayName("re-resolving an unchanged estate keeps the date each binding started")
  void resolvedAtSurvivesReResolve() {
    UUID id = policy("mask-pii", ResolvedColumnMask.ScopeLevel.ORG, null);
    materializer.materialize(id);
    OffsetDateTime first = resolvedAt(id, CUSTOMER);

    PolicyBindingMaterializer.Result again = materializer.materialize(id);

    // Rebuilding instead of diffing would reset this every night, and "this
    // policy started covering this table on Tuesday" would stop being a
    // question the platform can answer.
    assertThat(again.changed()).isFalse();
    assertThat(again.added()).isZero();
    assertThat(again.removed()).isZero();
    assertThat(resolvedAt(id, CUSTOMER)).isEqualTo(first);
  }

  @Test
  @DisplayName("an asset that loses its tag is unbound on the next resolve")
  void unbindsWhenTagRemoved() {
    UUID id = policy("mask-pii", ResolvedColumnMask.ScopeLevel.DATABASE, SALES);
    materializer.materialize(id);
    assertThat(targets(id)).containsExactly(CUSTOMER);

    crawl(false, false, true);
    PolicyBindingMaterializer.Result result = materializer.materialize(id);

    assertThat(result.removed()).isOne();
    assertThat(targets(id)).isEmpty();
  }

  @Test
  @DisplayName("a newly tagged asset is covered by the refresh the webhook triggers")
  void newlyTaggedAssetIsCoveredOnRefresh() {
    UUID id = policy("mask-pii", ResolvedColumnMask.ScopeLevel.ORG, null);
    materializer.materialize(id);
    OffsetDateTime customerBoundAt = resolvedAt(id, CUSTOMER);

    crawl(true, true, true);
    List<PolicyBindingMaterializer.Result> results = materializer.refresh(List.of(ORDER));

    // This is FR-3.1.6 in one line: nobody pressed anything, and the table is
    // covered because it now carries the tag the policy was written against.
    assertThat(targets(id)).contains(ORDER);
    assertThat(results).anyMatch(result -> result.policyId().equals(id) && result.added() == 1);
    // And the rest of the estate was not re-dated by a pass that never looked
    // at it.
    assertThat(resolvedAt(id, CUSTOMER)).isEqualTo(customerBoundAt);
  }

  @Test
  @DisplayName("a refresh unbinds only what it was asked to look at")
  void refreshOnlyTouchesNamedAssets() {
    UUID id = policy("mask-pii", ResolvedColumnMask.ScopeLevel.ORG, null);
    materializer.materialize(id);

    // customer loses its tag, but the refresh is told about order only.
    crawl(false, true, true);
    materializer.refresh(List.of(ORDER));

    // Deleting what you did not evaluate is how a refresh quietly unbinds an
    // estate: every table would fall out of every policy the first time one
    // webhook arrived.
    assertThat(targets(id)).contains(CUSTOMER, ORDER);
  }

  @Test
  @DisplayName("a refresh for an asset outside a policy's scope changes nothing")
  void refreshSkipsPoliciesOutOfScope() {
    UUID id = policy("archive-only", ResolvedColumnMask.ScopeLevel.DATABASE, ARCHIVE);
    materializer.materialize(id);

    List<PolicyBindingMaterializer.Result> results = materializer.refresh(List.of(CUSTOMER));

    assertThat(results).allMatch(result -> result.scanned() == 0 && !result.changed());
    assertThat(targets(id)).containsExactly(ARCHIVED_CUSTOMER);
  }

  @Test
  @DisplayName("the nightly reconcile covers every policy that could be enforced")
  void materializeAllCoversEveryPolicy() {
    UUID org = policy("org-wide", ResolvedColumnMask.ScopeLevel.ORG, null);
    UUID local = policy("sales-only", ResolvedColumnMask.ScopeLevel.DATABASE, SALES);

    List<PolicyBindingMaterializer.Result> results = materializer.materializeAll();

    assertThat(results)
        .extracting(PolicyBindingMaterializer.Result::policyId)
        .containsExactlyInAnyOrder(org, local);
    assertThat(targets(org)).containsExactlyInAnyOrder(CUSTOMER, ARCHIVED_CUSTOMER);
    assertThat(targets(local)).containsExactly(CUSTOMER);
  }

  // ------------------------------------------------------------------ queries

  private List<String> targets(UUID policyId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT target_fqn FROM policy_binding
                    WHERE policy_id = :id ORDER BY target_fqn
                    """)
                .bind("id", policyId)
                .mapTo(String.class)
                .list());
  }

  private OffsetDateTime resolvedAt(UUID policyId, String targetFqn) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT resolved_at FROM policy_binding
                    WHERE policy_id = :id AND target_fqn = :fqn
                    """)
                .bind("id", policyId)
                .bind("fqn", targetFqn)
                .mapTo(OffsetDateTime.class)
                .one());
  }

  // ----------------------------------------------------------------- fixtures

  private UUID policy(String name, ResolvedColumnMask.ScopeLevel level, String scopeFqn) {
    Policy document = document(name);
    document.setScopeLevel(level);
    document.setScopeFqn(scopeFqn);
    return store.create(document, "alice").id();
  }

  private static Policy document(String name) {
    Policy document = new Policy();
    document.setName(name);
    document.setPolicyType(Policy.PolicyType.SUBSCRIPTION);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.ORG);
    document.setSelector(selector("PII.Sensitive"));
    return document;
  }

  private static AssetSelector selector(String tag) {
    FacetCondition condition = new FacetCondition();
    condition.setFacet(FacetCondition.FacetType.TAGS);
    condition.setOperator(ResolvedRowPredicate.FacetOperator.CONTAINS);
    condition.setValue(tag);
    AssetSelector selector = new AssetSelector();
    selector.setCondition(condition);
    return selector;
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

  /**
   * A two-column table, optionally carrying PII on itself and on its email
   * column, with the tag hierarchy already expanded the way the crawler writes
   * it.
   */
  private static CrawledAsset table(String fqn, String parent, String name, boolean pii) {
    CrawledAsset.AssetRow row =
        new CrawledAsset.AssetRow(
            omId(fqn), fqn, "TABLE", parent, name, null, null, null, null, Map.of());
    List<CrawledAsset.ColumnRow> columns =
        List.of(
            new CrawledAsset.ColumnRow(fqn + ".id", "id", 0, "BIGINT", null, false, null, Map.of()),
            new CrawledAsset.ColumnRow(
                fqn + ".email", "email", 1, "VARCHAR", 255, true, null, Map.of()));
    List<ExtractedFacet> facets = new ArrayList<>();
    if (pii) {
      for (String target : List.of(fqn, fqn + ".email")) {
        facets.add(tag(target, "PII.Sensitive", 0, true));
        facets.add(tag(target, "PII", 1, false));
      }
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

  /**
   * Re-runs the whole crawl.
   *
   * <p>Whole rather than incremental on purpose: the store treats a crawl as
   * the complete truth, so dropping a tag here is the same event as a steward
   * removing it in OpenMetadata.
   */
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
