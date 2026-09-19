package com.mfec.dac.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.om.crawl.GovernanceSnapshot;
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
 * The governance vocabulary against a real PostgreSQL (NFR-5).
 *
 * <p>Everything this store does that can go wrong is the database's: {@code ON
 * CONFLICT} clauses whose {@code WHERE} declines to overwrite a locally
 * authored row, deletes narrowed to one provenance, and jsonb columns. Those
 * are the clauses that decide whether a sync quietly deletes governance the
 * platform itself owns, so they are tested where they actually run.
 */
@Testcontainers
class GovernanceStoreIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static Jdbi jdbi;
  private final ObjectMapper json = new ObjectMapper();
  private GovernanceStore store;

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
    // Listed rather than CASCADE, for the same reason AssetStoreIT lists its
    // tables: a governance table added later should fail here loudly instead of
    // being emptied by a test that never meant to touch it.
    jdbi.useHandle(
        handle ->
            handle.execute(
                """
                TRUNCATE tag, classification, glossary_term, glossary, data_product, domain,
                         custom_property_def
                """));
    store = new GovernanceStore(jdbi, json);
  }

  @Test
  @DisplayName("stores the whole vocabulary a policy can be written against")
  void storesEverything() {
    store.store(snapshot());

    assertThat(fqns("classification")).containsExactlyInAnyOrder("PII", "Tier");
    assertThat(fqns("tag")).containsExactlyInAnyOrder("PII.Sensitive", "Tier.Tier1");
    assertThat(fqns("glossary")).containsExactly("Finance");
    assertThat(fqns("glossary_term")).containsExactly("Finance.CustomerIdentity");
    assertThat(fqns("domain"))
        .containsExactlyInAnyOrder("Finance", "Finance.Risk", "Finance.Risk.Credit");
    assertThat(fqns("data_product")).containsExactly("Customer 360");
    assertThat(count("custom_property_def")).isOne();
  }

  @Test
  @DisplayName("a sub-domain keeps its parent and its depth")
  void subDomainDepth() {
    store.store(snapshot());

    Map<String, Object> credit =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        "SELECT parent_fqn, depth FROM domain WHERE fqn = 'Finance.Risk.Credit'")
                    .mapToMap()
                    .one());

    // FacetInheritance walks this to expand the ancestors that make
    // domains:Finance match an asset three levels down without recursion
    // (FR-2A.2); a lost parent breaks that expansion silently.
    assertThat(credit.get("parent_fqn")).isEqualTo("Finance.Risk");
    assertThat(credit.get("depth")).isEqualTo(2);
  }

  @Test
  @DisplayName("an empty snapshot is refused rather than emptying the vocabulary")
  void emptySnapshotIsRefused() {
    store.store(snapshot());

    store.store(GovernanceSnapshot.empty());

    // NFR-3: a bot token that lost its scope answers an empty list. Believing
    // it would unbind every policy in the platform at once.
    assertThat(fqns("tag")).containsExactlyInAnyOrder("PII.Sensitive", "Tier.Tier1");
    assertThat(fqns("domain")).hasSize(3);
  }

  @Test
  @DisplayName("a tag that has gone from OpenMetadata goes from the cache")
  void removedTagIsDeleted() {
    store.store(snapshot());

    GovernanceSnapshot without =
        new GovernanceSnapshot(
            snapshot().classifications(),
            List.of(tag("PII", "PII.Sensitive")),
            snapshot().glossaries(),
            snapshot().terms(),
            snapshot().domains(),
            snapshot().dataProducts(),
            snapshot().customProperties());

    store.store(without);

    assertThat(fqns("tag")).containsExactly("PII.Sensitive");
  }

  @Test
  @DisplayName("a locally authored tag survives a sync that never mentions it")
  void localTagSurvivesSync() {
    jdbi.useHandle(
        handle ->
            handle.execute(
                """
                INSERT INTO tag (classification_fqn, fqn, name, provenance)
                VALUES ('PII', 'PII.ThaiCitizenId', 'ThaiCitizenId', 'local')
                """));

    store.store(snapshot());

    // FR-1.7: it exists precisely because OpenMetadata did not have one. The
    // delete is confined to provenance = 'openmetadata' so that absence from
    // the snapshot cannot be read as deletion.
    assertThat(fqns("tag"))
        .containsExactlyInAnyOrder("PII.Sensitive", "Tier.Tier1", "PII.ThaiCitizenId");
  }

  @Test
  @DisplayName("a local row is not overwritten by an OpenMetadata row of the same name")
  void localRowWinsOnConflict() {
    jdbi.useHandle(
        handle ->
            handle.execute(
                """
                INSERT INTO tag (classification_fqn, fqn, name, description, provenance)
                VALUES ('PII', 'PII.Sensitive', 'Sensitive', 'ours, and deliberate', 'local')
                """));

    store.store(snapshot());

    Map<String, Object> row =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        "SELECT description, provenance FROM tag WHERE fqn = 'PII.Sensitive'")
                    .mapToMap()
                    .one());

    assertThat(row.get("description")).isEqualTo("ours, and deliberate");
    assertThat(row.get("provenance")).isEqualTo("local");
  }

  @Test
  @DisplayName("applying the same snapshot twice changes nothing")
  void idempotent() {
    store.store(snapshot());
    store.store(snapshot());

    // The crawl reapplies whole snapshots — a manual sync, a nightly
    // reconcile, a poller that rewound its cursor — so duplicate application
    // has to be free.
    assertThat(count("tag")).isEqualTo(2);
    assertThat(count("domain")).isEqualTo(3);
    assertThat(count("custom_property_def")).isOne();
  }

  @Test
  @DisplayName("a custom property definition keeps its enum values and can change type")
  void customPropertyDefinition() {
    store.store(snapshot());

    Map<String, Object> before = customProperty();
    assertThat(before.get("data_type")).isEqualTo("enum");
    assertThat(before.get("enum_values").toString()).contains("TH").contains("SG");
    assertThat(before.get("multi_select")).isEqualTo(false);

    GovernanceSnapshot retyped =
        withCustomProperties(
            List.of(
                new GovernanceSnapshot.CustomPropertyRow(
                    "table", "dataResidency", "Data Residency", null, "string", List.of(), false)));
    store.store(retyped);

    // FR-1.9: the builder decides between a dropdown and a text box from this
    // column, so a type change in OpenMetadata has to reach the cache.
    Map<String, Object> after = customProperty();
    assertThat(after.get("data_type")).isEqualTo("string");
    assertThat(after.get("enum_values").toString()).isEqualTo("[]");
  }

  @Test
  @DisplayName("the last custom property definition being deleted empties the table")
  void lastCustomPropertyIsDeleted() {
    store.store(snapshot());
    assertThat(count("custom_property_def")).isOne();

    store.store(withCustomProperties(List.of()));

    // The snapshot is the whole of what OpenMetadata has. A definition left
    // behind here is an ABAC attribute the builder offers and no asset carries.
    assertThat(count("custom_property_def")).isZero();
  }

  // ------------------------------------------------------------------ fixture

  private GovernanceSnapshot snapshot() {
    return new GovernanceSnapshot(
        List.of(
            new GovernanceSnapshot.ClassificationRow(
                UUID.randomUUID(), "PII", "PII", "Personal data", false, "system", false),
            new GovernanceSnapshot.ClassificationRow(
                UUID.randomUUID(), "Tier", "Tier", "Criticality", true, "system", false)),
        List.of(tag("PII", "PII.Sensitive"), tag("Tier", "Tier.Tier1")),
        List.of(new GovernanceSnapshot.GlossaryRow(UUID.randomUUID(), "Finance", "Finance", null)),
        List.of(
            new GovernanceSnapshot.GlossaryTermRow(
                UUID.randomUUID(),
                "Finance",
                "Finance.CustomerIdentity",
                null,
                "CustomerIdentity",
                null,
                List.of("KYC"),
                List.of())),
        List.of(
            domain("Finance", null, 0),
            domain("Finance.Risk", "Finance", 1),
            domain("Finance.Risk.Credit", "Finance.Risk", 2)),
        List.of(
            new GovernanceSnapshot.DataProductRow(
                UUID.randomUUID(), "Customer 360", "Customer 360", null, "Finance.Risk")),
        List.of(
            new GovernanceSnapshot.CustomPropertyRow(
                "table",
                "dataResidency",
                "Data Residency",
                "Where the rows may be read",
                "enum",
                List.of("TH", "SG"),
                false)));
  }

  private GovernanceSnapshot withCustomProperties(
      List<GovernanceSnapshot.CustomPropertyRow> properties) {
    GovernanceSnapshot base = snapshot();
    return new GovernanceSnapshot(
        base.classifications(),
        base.tags(),
        base.glossaries(),
        base.terms(),
        base.domains(),
        base.dataProducts(),
        properties);
  }

  private static GovernanceSnapshot.TagRow tag(String classification, String fqn) {
    return new GovernanceSnapshot.TagRow(
        UUID.randomUUID(),
        classification,
        fqn,
        null,
        fqn.substring(fqn.indexOf('.') + 1),
        null,
        false);
  }

  private static GovernanceSnapshot.DomainRow domain(String fqn, String parent, int depth) {
    return new GovernanceSnapshot.DomainRow(
        UUID.randomUUID(),
        fqn,
        parent,
        depth,
        fqn.substring(fqn.lastIndexOf('.') + 1),
        null,
        "Aggregate");
  }

  // --------------------------------------------------------------- assertions

  private List<String> fqns(String table) {
    return jdbi.withHandle(
        handle ->
            handle.createQuery("SELECT fqn FROM " + table).mapTo(String.class).list());
  }

  private int count(String table) {
    return jdbi.withHandle(
        handle -> handle.createQuery("SELECT count(*) FROM " + table).mapTo(Integer.class).one());
  }

  private Map<String, Object> customProperty() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT data_type, enum_values, multi_select FROM custom_property_def
                    WHERE entity_type = 'table' AND name = 'dataResidency'
                    """)
                .mapToMap()
                .one());
  }
}
