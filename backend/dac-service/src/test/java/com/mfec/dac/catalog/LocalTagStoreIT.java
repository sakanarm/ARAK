package com.mfec.dac.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mfec.dac.om.crawl.AssetCrawler;
import com.mfec.dac.om.crawl.CrawledAsset;
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
import java.util.concurrent.atomic.AtomicInteger;
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
 * Tags attached in ARAK against a real PostgreSQL (FR-1.7).
 *
 * <p>The one failure that matters most is quiet: a crawl wiping a local tag.
 * The mask it put on a column goes with it, and nothing says so.
 */
@Testcontainers
class LocalTagStoreIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String SERVICE = "demo-pg";
  private static final String DB = "demo-pg.salesdb";
  private static final String SCHEMA = "demo-pg.salesdb.sales";
  private static final String CUSTOMER = "demo-pg.salesdb.sales.customer";
  private static final String ORDERS = "demo-pg.salesdb.sales.orders";

  private static Jdbi jdbi;
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  private LocalTagStore tags;
  private PolicyStore policies;
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
          handle.execute(
              """
              TRUNCATE local_tag, audit_local_tag, access_request_vote, access_request_stage, access_request,
                       policy_version, policy_binding, access_grant, row_entitlement, enforcement_state, asset_facet,
                       asset_owner, asset_fqn_map, asset_column, asset, policy, tag, classification
              """);
          handle.execute("DELETE FROM data_source");
          handle.execute(
              """
              INSERT INTO classification (fqn, name, mutually_exclusive) VALUES
                ('PII', 'PII', false), ('Tier', 'Tier', true)
              """);
          handle.execute(
              """
              INSERT INTO tag (classification_fqn, fqn, parent_fqn, name, disabled) VALUES
                ('PII', 'PII.Sensitive', 'PII', 'Sensitive', false),
                ('PII', 'PII.NonSensitive', 'PII', 'NonSensitive', false),
                ('PII', 'PII.Retired', 'PII', 'Retired', true),
                ('Tier', 'Tier.Tier1', 'Tier', 'Tier1', false),
                ('Tier', 'Tier.Tier2', 'Tier', 'Tier2', false)
              """);
        });
    tags = new LocalTagStore(jdbi);
    policies = new PolicyStore(jdbi, json);
    materializer = new PolicyBindingMaterializer(jdbi, json, new AssetContextLoader(json));
    crawl();
  }

  @Test
  @DisplayName("a local tag becomes the same facet rows a crawled one does, marked local")
  void expandsLikeACrawledTag() {
    tags.add(ORDERS + ".email", "PII.Sensitive", "holds customer email", "steward");

    List<Map<String, Object>> rows = facets(ORDERS + ".email");
    assertThat(rows)
        .extracting(r -> r.get("facet_type") + ":" + r.get("facet_fqn") + ":" + r.get("depth")
            + ":" + r.get("is_direct") + ":" + r.get("provenance"))
        .containsExactlyInAnyOrder(
            "tags:PII.Sensitive:0:true:local",
            "tags:PII:1:false:local",
            "classifications:PII:0:false:local");
    // On a column the row points at the column, as a crawled one does.
    assertThat(rows).allSatisfy(r -> {
      assertThat(r.get("column_id")).isNotNull();
      assertThat(r.get("asset_id")).isNull();
    });
  }

  @Test
  @DisplayName("a crawl that rewrites the table's facets puts the local tag straight back")
  void survivesACrawl() {
    tags.add(ORDERS, "PII.Sensitive", "orders carry PII", "steward");
    tags.add(ORDERS + ".email", "PII.Sensitive", "holds customer email", "steward");

    crawl();

    assertThat(facets(ORDERS)).extracting(r -> r.get("facet_fqn") + ":" + r.get("provenance"))
        .contains("PII.Sensitive:local");
    assertThat(facets(ORDERS + ".email")).extracting(r -> r.get("facet_fqn"))
        .contains("PII.Sensitive");
    // The crawled tag on customer is still there beside it, untouched.
    assertThat(facets(CUSTOMER)).extracting(r -> r.get("facet_fqn") + ":" + r.get("provenance"))
        .contains("PII.Sensitive:openmetadata");
  }

  @Test
  @DisplayName("a policy on the tag binds the table once it is tagged here, and lets go when it is taken off")
  void policiesSeeLocalTags() {
    UUID id = policy();
    materializer.materialize(id);
    assertThat(targets(id)).containsExactly(CUSTOMER);

    tags.add(ORDERS, "PII.Sensitive", "orders carry PII", "steward");
    materializer.refresh(List.of(ORDERS));
    assertThat(targets(id)).containsExactlyInAnyOrder(CUSTOMER, ORDERS);

    // And a whole re-resolution after a crawl agrees.
    crawl();
    materializer.materialize(id);
    assertThat(targets(id)).containsExactlyInAnyOrder(CUSTOMER, ORDERS);

    tags.remove(ORDERS, "PII.Sensitive", "tagged by mistake", "steward");
    materializer.refresh(List.of(ORDERS));
    assertThat(targets(id)).containsExactly(CUSTOMER);
    assertThat(facets(ORDERS)).isEmpty();
  }

  @Test
  @DisplayName("every add and remove is audited with who, what and why")
  void audits() {
    tags.add(ORDERS + ".email", "PII.Sensitive", "holds customer email", "steward");
    tags.remove(ORDERS + ".email", "PII.Sensitive", "moved to another column", "admin");

    List<String> trail =
        jdbi.withHandle(
            h ->
                h.createQuery(
                        """
                        SELECT action || ':' || actor || ':' || target_fqn || ':' || asset_fqn
                               || ':' || tag_fqn || ':' || reason
                          FROM audit_local_tag ORDER BY id
                        """)
                    .mapTo(String.class)
                    .list());
    assertThat(trail)
        .containsExactly(
            "ADD:steward:" + ORDERS + ".email:" + ORDERS + ":PII.Sensitive:holds customer email",
            "REMOVE:admin:" + ORDERS + ".email:" + ORDERS + ":PII.Sensitive:moved to another column");
    assertThat(tags.forAsset(ORDERS)).isEmpty();
  }

  @Test
  @DisplayName("lists what was attached on a table and its columns, and fires a change each time")
  void listsAndNotifies() {
    AtomicInteger fired = new AtomicInteger();
    tags.changes().listen(reason -> fired.incrementAndGet());

    tags.add(ORDERS, "Tier.Tier1", "critical", "steward");
    tags.add(ORDERS + ".email", "PII.Sensitive", "email", "steward");

    assertThat(tags.forAsset(ORDERS))
        .extracting(LocalTagStore.LocalTag::targetFqn, LocalTagStore.LocalTag::tagFqn)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(ORDERS, "Tier.Tier1"),
            org.assertj.core.groups.Tuple.tuple(ORDERS + ".email", "PII.Sensitive"));
    assertThat(tags.assetOf(ORDERS + ".email")).contains(ORDERS);
    assertThat(fired).hasValue(2);
    // A tier tag also says the tier, as it does when it comes from OpenMetadata.
    assertThat(facets(ORDERS)).extracting(r -> r.get("facet_type") + ":" + r.get("facet_fqn"))
        .contains("tier:Tier1");
  }

  @Test
  @DisplayName("refuses what is not a tag in the vocabulary, a disabled tag, or a target that is not a table or column")
  void refusesTheWrongThings() {
    assertRefused(() -> tags.add(ORDERS, "PII.Invented", "x", "s"), LocalTagStore.Refused.Kind.INVALID);
    assertRefused(() -> tags.add(ORDERS, "PII.Retired", "x", "s"), LocalTagStore.Refused.Kind.INVALID);
    assertRefused(() -> tags.add(SCHEMA, "PII.Sensitive", "x", "s"), LocalTagStore.Refused.Kind.NOT_FOUND);
    assertRefused(() -> tags.add(ORDERS + ".nope", "PII.Sensitive", "x", "s"), LocalTagStore.Refused.Kind.NOT_FOUND);
    assertRefused(() -> tags.remove(CUSTOMER, "PII.Sensitive", "x", "s"), LocalTagStore.Refused.Kind.NOT_FOUND);
    assertThat(auditCount()).isZero();
  }

  @Test
  @DisplayName("refuses a tag the target already carries, and a second tag from a classification that allows one")
  void refusesConflicts() {
    // Already there from OpenMetadata.
    assertRefused(() -> tags.add(CUSTOMER, "PII.Sensitive", "x", "s"), LocalTagStore.Refused.Kind.CONFLICT);

    tags.add(ORDERS, "Tier.Tier1", "critical", "s");
    assertRefused(() -> tags.add(ORDERS, "Tier.Tier1", "again", "s"), LocalTagStore.Refused.Kind.CONFLICT);
    assertRefused(() -> tags.add(ORDERS, "Tier.Tier2", "both", "s"), LocalTagStore.Refused.Kind.CONFLICT);

    // PII is not exclusive: two of its tags on one column is fine.
    tags.add(ORDERS + ".email", "PII.Sensitive", "email", "s");
    tags.add(ORDERS + ".email", "PII.NonSensitive", "and also", "s");
    assertThat(tags.forAsset(ORDERS)).hasSize(3);
  }

  // ------------------------------------------------------------------ helpers

  private static void assertRefused(Runnable call, LocalTagStore.Refused.Kind kind) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(LocalTagStore.Refused.class, e -> assertThat(e.kind()).isEqualTo(kind));
  }

  private int auditCount() {
    return jdbi.withHandle(h -> h.createQuery("SELECT count(*) FROM audit_local_tag").mapTo(Integer.class).one());
  }

  private List<Map<String, Object>> facets(String target) {
    return jdbi.withHandle(
        h ->
            h.createQuery(
                    """
                    SELECT facet_type, facet_fqn, depth, is_direct, provenance, asset_id, column_id
                      FROM asset_facet WHERE target_fqn = :t ORDER BY facet_type, depth
                    """)
                .bind("t", target)
                .mapToMap()
                .list());
  }

  private List<String> targets(UUID policyId) {
    return jdbi.withHandle(
        h ->
            h.createQuery("SELECT target_fqn FROM policy_binding WHERE policy_id = :id ORDER BY target_fqn")
                .bind("id", policyId)
                .mapTo(String.class)
                .list());
  }

  private UUID policy() {
    FacetCondition condition = new FacetCondition();
    condition.setFacet(FacetCondition.FacetType.TAGS);
    condition.setOperator(ResolvedRowPredicate.FacetOperator.CONTAINS);
    condition.setValue("PII.Sensitive");
    AssetSelector selector = new AssetSelector();
    selector.setCondition(condition);
    Policy document = new Policy();
    document.setName("pii-tables");
    document.setPolicyType(Policy.PolicyType.SUBSCRIPTION);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.ORG);
    document.setSelector(selector);
    return policies.create(document, "alice").id();
  }

  private static UUID omId(String fqn) {
    return UUID.nameUUIDFromBytes(fqn.getBytes(StandardCharsets.UTF_8));
  }

  private static CrawledAsset container(String fqn, String type, String parent, String name) {
    return new CrawledAsset(
        new CrawledAsset.AssetRow(omId(fqn), fqn, type, parent, name, null, null, null, null, Map.of()),
        List.of(), List.of(), List.of());
  }

  /** customer carries PII.Sensitive from OpenMetadata on itself; orders carries nothing. */
  private static CrawledAsset table(String fqn, String name, boolean pii) {
    CrawledAsset.AssetRow row =
        new CrawledAsset.AssetRow(omId(fqn), fqn, "TABLE", SCHEMA, name, null, null, null, null, Map.of());
    List<CrawledAsset.ColumnRow> columns =
        List.of(
            new CrawledAsset.ColumnRow(fqn + ".id", "id", 0, "BIGINT", null, false, null, Map.of()),
            new CrawledAsset.ColumnRow(fqn + ".email", "email", 1, "VARCHAR", 255, true, null, Map.of()));
    List<ExtractedFacet> facets = new ArrayList<>();
    if (pii) {
      facets.add(new ExtractedFacet(fqn, FacetCondition.FacetType.TAGS, "PII.Sensitive", null, 0, true, null, "Confirmed", "Manual"));
      facets.add(new ExtractedFacet(fqn, FacetCondition.FacetType.TAGS, "PII", null, 1, false, null, "Confirmed", "Manual"));
    }
    return new CrawledAsset(row, columns, facets, List.of());
  }

  private void crawl() {
    AssetStore sink = new AssetStore(jdbi, json, Instant.now());
    List.of(
            container(SERVICE, "SERVICE", null, "demo-pg"),
            container(DB, "DATABASE", SERVICE, "salesdb"),
            container(SCHEMA, "SCHEMA", DB, "sales"),
            table(CUSTOMER, "customer", true),
            table(ORDERS, "orders", false))
        .forEach(sink::asset);
    sink.finished(new AssetCrawler.Stats());
  }
}
