package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mfec.dac.catalog.AssetStore;
import com.mfec.dac.engine.EngineConfig;
import com.mfec.dac.engine.PolicyEngine;
import com.mfec.dac.om.crawl.AssetCrawler;
import com.mfec.dac.om.crawl.CrawledAsset;
import com.mfec.dac.om.facet.ExtractedFacet;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.AttributeCondition;
import com.mfec.dac.schema.entity.policy.ColumnRule;
import com.mfec.dac.schema.entity.policy.DataPolicy;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.PrincipalMatch;
import com.mfec.dac.schema.entity.policy.SubjectRule;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What activating a policy would do to the people who use the data (FR-5.3).
 *
 * <p>The failure this guards against is a confident number. An impact screen is read once, by
 * somebody deciding whether to press Activate, and it is believed. So the cases below are the ones
 * where a cheaper implementation would be wrong while still looking right: a policy that grants
 * what is already granted (bindings say "40 tables", impact says "nobody"), a policy that denies
 * only the people who fail an attribute test (bindings say "everyone", impact names two), and a
 * policy already in force (the comparison has to run backwards).
 */
@Testcontainers
class ImpactAnalysisIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String SERVICE = "prod-mssql";
  private static final String SALES = "prod-mssql.SalesDB";
  private static final String SALES_DBO = "prod-mssql.SalesDB.dbo";
  private static final String CUSTOMER = "prod-mssql.SalesDB.dbo.customer";
  private static final String ORDER = "prod-mssql.SalesDB.dbo.order";

  private static Jdbi jdbi;
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  private PolicyStore store;
  private PolicyBindingMaterializer materializer;
  private ImpactAnalysis impact;

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
              TRUNCATE policy_version, policy_binding, access_grant, row_entitlement,
                       enforcement_state, asset_facet, asset_owner, asset_fqn_map,
                       asset_column, asset, policy, principal_attribute, app_role_assignment,
                       group_member, principal CASCADE
              """);
          handle.execute("DELETE FROM data_source");
          handle.execute(
              """
              INSERT INTO data_source (name, engine, host, port, credential_ref, om_service_fqn)
              VALUES ('prod-mssql', 'SQLSERVER', 'db.internal', 1433, 'vault://x', 'prod-mssql')
              """);
        });
    store = new PolicyStore(jdbi, json);
    AssetContextLoader contexts = new AssetContextLoader(json);
    materializer = new PolicyBindingMaterializer(jdbi, json, contexts);
    impact =
        new ImpactAnalysis(
            jdbi, contexts, new PrincipalLoader(), store, new PolicyEngine(EngineConfig.defaults()));
    crawl();
    directory();
    baseline();
  }

  // ------------------------------------------------------------- who is counted

  @Nested
  @DisplayName("who the report is about")
  class Population {

    @Test
    @DisplayName("groups are not people and are left out of the count")
    void groupsAreNotPeople() {
      // Three rows in `principal` are people; the fourth is the team they
      // belong to. Counting it would inflate every number on the screen and
      // put a row in the affected list that nobody can log in as.
      ImpactAnalysis.Impact measured = impact.measure(draftDeny("deny-all", null));

      assertThat(measured.principalsKnown()).isEqualTo(3);
      assertThat(measured.principalsMeasured()).isEqualTo(3);
      assertThat(measured.principals()).extracting(ImpactAnalysis.PrincipalChange::principal)
          .doesNotContain("finance-team");
    }

    @Test
    @DisplayName("a small estate is measured whole, and says so")
    void nothingIsSampled() {
      ImpactAnalysis.Impact measured = impact.measure(draftDeny("deny-all", null));

      assertThat(measured.sampled()).isFalse();
      assertThat(measured.tablesBound()).isEqualTo(1);
      assertThat(measured.tablesMeasured()).isEqualTo(1);
    }
  }

  // ------------------------------------------------------------ the comparison

  @Nested
  @DisplayName("what changes")
  class Changes {

    @Test
    @DisplayName("a draft that denies takes access from everyone who had it")
    void denyRemovesAccess() {
      ImpactAnalysis.Impact measured = impact.measure(draftDeny("deny-all", null));

      assertThat(measured.candidateActive()).isFalse();
      assertThat(measured.principalsAffected()).isEqualTo(3);
      assertThat(measured.tablesAffected()).isEqualTo(1);
      assertThat(measured.byChange()).containsEntry("LOSES_ACCESS", 3);
      assertThat(measured.principals())
          .allSatisfy(
              one -> assertThat(one.change()).isEqualTo(ImpactAnalysis.Change.LOSES_ACCESS));
      assertThat(measured.principals().get(0).tables())
          .singleElement()
          .satisfies(
              table -> {
                assertThat(table.assetFqn()).isEqualTo(CUSTOMER);
                assertThat(table.detail()).contains("cannot read this table");
              });
    }

    /**
     * The claim the whole class exists to make. A binding count would say this policy touches a
     * table and grants a team, which sounds like something; the honest answer is that it changes
     * nothing, because the access it grants is already granted.
     */
    @Test
    @DisplayName("a draft that grants what is already granted affects nobody")
    void redundantGrantAffectsNobody() {
      ImpactAnalysis.Impact measured = impact.measure(draftAllow("allow-again"));

      assertThat(measured.tablesBound()).isEqualTo(1);
      assertThat(measured.principalsAffected()).isZero();
      assertThat(measured.tablesAffected()).isZero();
      assertThat(measured.principals()).isEmpty();
      // The unaffected majority is counted rather than dropped: "0 of 3
      // affected" is the reassurance, and an empty map would read as an error.
      assertThat(measured.byChange()).containsEntry("UNCHANGED", 3);
    }

    /**
     * Per-person, not per-binding. The policy binds the same one table for everybody; only the
     * person whose clearance fails the test loses anything.
     */
    @Test
    @DisplayName("an attribute-conditioned denial names only the people who fail it")
    void deniesOnlyThoseWhoFail() {
      ImpactAnalysis.Impact measured = impact.measure(draftDeny("deny-l1", "L1"));

      assertThat(measured.principalsAffected()).isEqualTo(1);
      assertThat(measured.principals())
          .singleElement()
          .satisfies(
              one -> {
                assertThat(one.principal()).isEqualTo("analyst_a");
                assertThat(one.change()).isEqualTo(ImpactAnalysis.Change.LOSES_ACCESS);
                assertThat(one.tablesAffected()).isEqualTo(1);
              });
      assertThat(measured.byChange())
          .containsEntry("LOSES_ACCESS", 1)
          .containsEntry("UNCHANGED", 2);
    }

    @Test
    @DisplayName("a masking draft narrows the view and names the column it narrows")
    void maskingNarrowsTheView() {
      ImpactAnalysis.Impact measured = impact.measure(draftMaskEmail());

      assertThat(measured.byChange()).containsEntry("SEES_LESS", 3);
      assertThat(measured.principals().get(0).tables())
          .singleElement()
          .satisfies(
              table -> {
                assertThat(table.change()).isEqualTo(ImpactAnalysis.Change.SEES_LESS);
                // The column, not a count of columns: "email is now masked"
                // tells a reviewer whether to care, "1 more column is masked"
                // sends them to go and look.
                assertThat(table.detail()).contains("email").contains("masked");
              });
    }

    @Test
    @DisplayName("a policy already in force is measured against the world without it")
    void anActivePolicyIsMeasuredBackwards() {
      UUID id = draftDeny("deny-all", null).id();
      store.transition(id, "ACTIVE", "alice", "test");

      ImpactAnalysis.Impact measured = impact.measure(store.find(id).orElseThrow());

      // Same verdict as the draft case, and that is the point: switching this
      // policy off is what would give the access back, so it is what is
      // currently withholding it. The flag is how the screen knows to say
      // "this policy is stopping" rather than "activating this would stop".
      assertThat(measured.candidateActive()).isTrue();
      assertThat(measured.byChange()).containsEntry("LOSES_ACCESS", 3);
    }

    @Test
    @DisplayName("a policy bound to nothing is an empty report, not a failure")
    void boundToNothing() {
      ImpactAnalysis.Impact measured = impact.measure(draftOverUnknownTag());

      assertThat(measured.tablesBound()).isZero();
      assertThat(measured.tablesMeasured()).isZero();
      assertThat(measured.sampled()).isFalse();
      assertThat(measured.byChange()).isEmpty();
      assertThat(measured.principals()).isEmpty();
    }
  }

  // ----------------------------------------------------------------- fixtures

  /**
   * The world before the candidate: one active ORG policy letting everybody read PII tables.
   *
   * <p>Without it every decision is a denial both ways and every candidate would measure as
   * changing nothing — a green report produced by a broken fixture, which is the one kind of test
   * failure that never fails.
   */
  private void baseline() {
    Policy document = new Policy();
    document.setName("everyone-reads-pii");
    document.setPolicyType(Policy.PolicyType.SUBSCRIPTION);
    document.setEffect(Policy.Effect.ALLOW);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.ORG);
    document.setSelector(selector("PII.Sensitive"));
    UUID id = store.create(document, "alice").id();
    materializer.materialize(id);
    store.transition(id, "ACTIVE", "alice", "baseline");
  }

  /**
   * A draft denial, optionally narrowed to one clearance value.
   *
   * @param clearance when set, the policy denies only principals carrying it
   */
  private PolicyStore.StoredPolicy draftDeny(String name, String clearance) {
    Policy document = new Policy();
    document.setName(name);
    document.setPolicyType(Policy.PolicyType.SUBSCRIPTION);
    document.setEffect(Policy.Effect.DENY);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.ORG);
    document.setSelector(selector("PII.Sensitive"));
    if (clearance != null) {
      AttributeCondition condition = new AttributeCondition();
      condition.setKey("clearance");
      condition.setOperator(ResolvedRowPredicate.FacetOperator.EQ);
      condition.setValue(clearance);
      SubjectRule subject = new SubjectRule();
      subject.setAttributes(List.of(condition));
      document.setSubject(subject);
    }
    return materialized(document);
  }

  /** A draft that grants exactly what the baseline already grants. */
  private PolicyStore.StoredPolicy draftAllow(String name) {
    Policy document = new Policy();
    document.setName(name);
    document.setPolicyType(Policy.PolicyType.SUBSCRIPTION);
    document.setEffect(Policy.Effect.ALLOW);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.ORG);
    document.setSelector(selector("PII.Sensitive"));
    PrincipalMatch anyone = new PrincipalMatch();
    anyone.setGroup("finance-team");
    SubjectRule subject = new SubjectRule();
    subject.setPrincipals(List.of(anyone));
    document.setSubject(subject);
    return materialized(document);
  }

  private PolicyStore.StoredPolicy draftMaskEmail() {
    Policy document = new Policy();
    document.setName("mask-email");
    document.setPolicyType(Policy.PolicyType.DATA);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.ORG);
    document.setSelector(selector("PII.Sensitive"));

    MaskingSpec masking = new MaskingSpec();
    masking.setFunction(MaskingSpec.MaskingFunction.NULLIFY);
    ColumnRule rule = new ColumnRule();
    rule.setColumns(selector("PII.Sensitive"));
    rule.setAction(ColumnRule.Action.MASK);
    rule.setMasking(masking);
    DataPolicy data = new DataPolicy();
    data.setColumnRules(List.of(rule));
    document.setData(data);
    return materialized(document);
  }

  private PolicyStore.StoredPolicy draftOverUnknownTag() {
    Policy document = new Policy();
    document.setName("over-nothing");
    document.setPolicyType(Policy.PolicyType.SUBSCRIPTION);
    document.setEffect(Policy.Effect.DENY);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.ORG);
    document.setSelector(selector("PII.NotAppliedAnywhere"));
    return materialized(document);
  }

  private PolicyStore.StoredPolicy materialized(Policy document) {
    UUID id = store.create(document, "alice").id();
    materializer.materialize(id);
    return store.find(id).orElseThrow();
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

  /** Two analysts, a service account, and the team they are all in. */
  private void directory() {
    jdbi.useHandle(
        handle -> {
          person(handle, "analyst_a", "USER", "L1");
          person(handle, "analyst_b", "USER", "L2");
          person(handle, "bot_sync", "SERVICE", "L2");
          handle.execute(
              """
              INSERT INTO principal (principal_type, username, source, enabled)
              VALUES ('GROUP', 'finance-team', 'local', true)
              """);
          handle.execute(
              """
              INSERT INTO group_member (group_id, member_id, source)
              SELECT g.id, m.id, 'local'
              FROM principal g, principal m
              WHERE g.username = 'finance-team' AND m.principal_type <> 'GROUP'
              """);
        });
  }

  private static void person(org.jdbi.v3.core.Handle handle, String name, String kind, String clearance) {
    handle
        .createUpdate(
            """
            INSERT INTO principal (principal_type, username, email, source, enabled)
            VALUES (:kind, :name, :name || '@example.test', 'local', true)
            """)
        .bind("kind", kind)
        .bind("name", name)
        .execute();
    handle
        .createUpdate(
            """
            INSERT INTO principal_attribute (principal_id, attr_key, attr_value, source)
            SELECT id, 'clearance', :clearance, 'local' FROM principal WHERE username = :name
            """)
        .bind("clearance", clearance)
        .bind("name", name)
        .execute();
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

  /** One tagged table and one untagged one, so "bound to" means something. */
  private void crawl() {
    AssetStore sink = new AssetStore(jdbi, json, Instant.now());
    List.of(
            container(SERVICE, "SERVICE", null, "prod-mssql"),
            container(SALES, "DATABASE", SERVICE, "SalesDB"),
            container(SALES_DBO, "SCHEMA", SALES, "dbo"),
            table(CUSTOMER, SALES_DBO, "customer", true),
            table(ORDER, SALES_DBO, "order", false))
        .forEach(sink::asset);
    sink.finished(new AssetCrawler.Stats());
  }
}
