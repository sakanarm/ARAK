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
 * Reading a policy back: what it covers, and what it meets there (FR-3.1.5, FR-5.1).
 *
 * <p>These two answers are what somebody sees before pressing Activate, so the failure that
 * matters is a reassuring one — an empty conflict list next to a policy that is in fact dead, or a
 * coverage count that says a policy protects columns it never bound. Both read as "all clear".
 */
@Testcontainers
class PolicyOverviewIT {

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
  private PolicyOverview overview;

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
              VALUES ('prod-mssql', 'SQLSERVER', 'db.internal', 1433, 'vault://x', 'prod-mssql')
              """);
        });
    store = new PolicyStore(jdbi, json);
    materializer = new PolicyBindingMaterializer(jdbi, json, new AssetContextLoader(json));
    overview = new PolicyOverview(jdbi, json);
    crawl();
  }

  // ----------------------------------------------------------------- coverage

  @Nested
  @DisplayName("what a policy covers")
  class Coverage {

    @Test
    @DisplayName("counts tables and columns apart, and names each one")
    void countsAndNames() {
      UUID id = maskEmail();

      PolicyOverview.Coverage coverage = overview.coverage(id);

      // The column is the whole point of a masking policy, so a screen that
      // reported "2 targets" would hide the only number the author cares about.
      assertThat(coverage.tableCount()).isEqualTo(1);
      assertThat(coverage.columnCount()).isEqualTo(1);
      assertThat(coverage.truncated()).isFalse();
      assertThat(coverage.sample()).extracting(PolicyOverview.Target::fqn)
          .containsExactly(CUSTOMER, CUSTOMER + ".email");
      assertThat(coverage.resolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("tables come before columns, so the sample reads as a tree")
    void tablesFirst() {
      UUID id = maskEmail();

      assertThat(overview.coverage(id).sample())
          .extracting(PolicyOverview.Target::kind)
          .containsExactly("TABLE", "COLUMN");
    }

    @Test
    @DisplayName("a column target carries its type, a table target does not")
    void columnsCarryTheirType() {
      UUID id = maskEmail();

      List<PolicyOverview.Target> sample = overview.coverage(id).sample();

      assertThat(sample.get(0).dataType()).isNull();
      assertThat(sample.get(1).name()).isEqualTo("email");
      assertThat(sample.get(1).dataType()).isEqualTo("VARCHAR");
      // Without the parent an owner reading the list cannot tell which table a
      // bare column name belongs to once two tables both have an `email`.
      assertThat(sample.get(1).parentFqn()).isEqualTo(CUSTOMER);
    }

    @Test
    @DisplayName("each target says why it matched")
    void targetsExplainThemselves() {
      UUID id = maskEmail();

      PolicyOverview.Target column =
          overview.coverage(id).sample().stream()
              .filter(t -> "COLUMN".equals(t.kind()))
              .findFirst()
              .orElseThrow();

      // FR-3.1.5: an owner is never told only that a policy matched. This is
      // the reason `policy_binding.match_reason` was written in the first
      // place, and until now nothing read it back.
      assertThat(column.matchReason()).isNotEmpty();
    }

    @Test
    @DisplayName("a policy that binds nothing reports zero rather than failing")
    void nothingBoundIsAnAnswer() {
      UUID id = subscription("finance-only", Policy.Effect.ALLOW, "Nothing.Matches.This");

      PolicyOverview.Coverage coverage = overview.coverage(id);

      // A policy covering nothing is a real and common state — a selector that
      // is wrong, or an estate that has not been crawled — and the screen has
      // to be able to say so. It is also the state most worth seeing.
      assertThat(coverage.tableCount()).isZero();
      assertThat(coverage.columnCount()).isZero();
      assertThat(coverage.sample()).isEmpty();
      assertThat(coverage.truncated()).isFalse();
      assertThat(coverage.resolvedAt()).isNull();
    }
  }

  // ---------------------------------------------------------------- overlaps

  @Nested
  @DisplayName("what a policy meets")
  class Overlaps {

    @Test
    @DisplayName("a deny on the same tables tells an allow that it grants nothing")
    void denyBeatsAllow() {
      UUID allow = subscription("analysts-read", Policy.Effect.ALLOW, null);
      UUID deny = subscription("no-pii-below-l2", Policy.Effect.DENY, null);
      materializer.materialize(allow);
      materializer.materialize(deny);

      List<PolicyOverview.Overlap> seenByAllow = overlapsOf(allow);
      List<PolicyOverview.Overlap> seenByDeny = overlapsOf(deny);

      // The same pair, read from both ends. This is the case FR-5.1 warns
      // about: the author of the allow believes they have granted something.
      assertThat(seenByAllow).singleElement().satisfies(o -> {
        assertThat(o.name()).isEqualTo("no-pii-below-l2");
        assertThat(o.relation()).isEqualTo("BLOCKED_BY");
        assertThat(o.sharedTargets()).isEqualTo(1);
        assertThat(o.examples()).containsExactly(CUSTOMER);
      });
      assertThat(seenByDeny).singleElement().satisfies(o ->
          assertThat(o.relation()).isEqualTo("BLOCKS"));
    }

    @Test
    @DisplayName("two allows narrow each other rather than adding up")
    void allowsCompose() {
      UUID first = subscription("analysts-read", Policy.Effect.ALLOW, null);
      UUID second = subscription("finance-read", Policy.Effect.ALLOW, null);
      materializer.materialize(first);
      materializer.materialize(second);

      // The mistake this catches is reading two allows as "either will do".
      assertThat(overlapsOf(first)).singleElement()
          .extracting(PolicyOverview.Overlap::relation)
          .isEqualTo("NARROWS");
    }

    @Test
    @DisplayName("a subscription and a data policy are not in conflict")
    void differentTypesCompose() {
      UUID subscription = subscription("analysts-read", Policy.Effect.ALLOW, null);
      UUID data = maskEmail();
      materializer.materialize(subscription);

      // They answer different questions, and calling that a conflict would
      // make the screen cry wolf on every well-formed estate.
      assertThat(overlapsOf(subscription)).singleElement().satisfies(o -> {
        assertThat(o.policyId()).isEqualTo(data);
        assertThat(o.relation()).isEqualTo("COMPOSES");
      });
    }

    @Test
    @DisplayName("two masking policies on the same columns warn that one wins")
    void masksOverlap() {
      UUID first = maskEmail();
      UUID second = maskEmail("hash-email");

      assertThat(overlapsOf(first)).singleElement().satisfies(o -> {
        assertThat(o.policyId()).isEqualTo(second);
        assertThat(o.relation()).isEqualTo("MASK_OVERLAP");
      });
    }

    @Test
    @DisplayName("an archived policy is not an overlap")
    void archivedIsGone() {
      UUID live = subscription("analysts-read", Policy.Effect.ALLOW, null);
      UUID old = subscription("old-rule", Policy.Effect.DENY, null);
      materializer.materialize(live);
      materializer.materialize(old);
      store.transition(old, "ACTIVE", "alice", null);
      store.transition(old, "DISABLED", "alice", null);
      store.transition(old, "ARCHIVED", "alice", null);

      // Its bindings survive the transition, so leaving it in would report a
      // conflict with something that can no longer refuse anybody.
      assertThat(overlapsOf(live)).isEmpty();
    }

    @Test
    @DisplayName("a draft is an overlap, because that is when it can still be changed")
    void draftsAreShown() {
      UUID live = subscription("analysts-read", Policy.Effect.ALLOW, null);
      UUID draft = subscription("no-pii-below-l2", Policy.Effect.DENY, null);
      materializer.materialize(live);
      materializer.materialize(draft);
      store.transition(live, "ACTIVE", "alice", null);

      assertThat(overlapsOf(live)).singleElement().satisfies(o -> {
        assertThat(o.lifecycleState()).isEqualTo("DRAFT");
        assertThat(o.relation()).isEqualTo("BLOCKED_BY");
      });
    }

    @Test
    @DisplayName("a policy in another environment is not an overlap")
    void environmentsAreSeparate() {
      UUID here = subscription("analysts-read", Policy.Effect.ALLOW, null);
      Policy elsewhere = subscriptionDocument("analysts-read", Policy.Effect.DENY, null);
      elsewhere.setEnvironment(Policy.Environment.UAT);
      UUID there = store.create(elsewhere, "alice").id();
      materializer.materialize(here);
      materializer.materialize(there);

      // Same name, same selector, same bound table. Only the environment
      // differs, and promoting a policy across environments (FR-9.4) means
      // this pair exists on every estate that uses the feature.
      assertThat(overlapsOf(here)).isEmpty();
    }

    @Test
    @DisplayName("a blocking policy is listed above a merely narrowing one")
    void worstFirst() {
      UUID subject = subscription("analysts-read", Policy.Effect.ALLOW, null);
      UUID narrows = subscription("finance-read", Policy.Effect.ALLOW, null);
      UUID blocks = subscription("no-pii-below-l2", Policy.Effect.DENY, null);
      materializer.materialize(subject);
      materializer.materialize(narrows);
      materializer.materialize(blocks);

      // Reading order is the finding. The one that kills the policy has to be
      // the first row, not the alphabetically lucky one.
      assertThat(overlapsOf(subject)).extracting(PolicyOverview.Overlap::relation)
          .containsExactly("BLOCKED_BY", "NARROWS");
    }

    @Test
    @DisplayName("a policy sharing no target is not listed at all")
    void disjointPoliciesAreSilent() {
      UUID subject = subscription("analysts-read", Policy.Effect.ALLOW, null);
      UUID elsewhere = subscription("orders-only", Policy.Effect.DENY, SALES);
      elsewhere = retarget(elsewhere, "Confidential");
      materializer.materialize(subject);
      materializer.materialize(elsewhere);

      assertThat(overlapsOf(subject)).isEmpty();
    }
  }

  // ------------------------------------------------------------ local override

  @Nested
  @DisplayName("the note about authority")
  class OverrideNotes {

    @Test
    @DisplayName("a policy above that forbids override is called out")
    void higherAndClosed() {
      Policy mine = subscriptionDocument("table-rule", Policy.Effect.ALLOW, CUSTOMER);
      mine.setScopeLevel(ResolvedColumnMask.ScopeLevel.TABLE);

      assertThat(PolicyOverview.overrideNote(mine, "ORG", false))
          .contains("ORG")
          .contains("only tighten");
    }

    @Test
    @DisplayName("a policy above that permits override says nothing")
    void higherAndOpen() {
      Policy mine = subscriptionDocument("table-rule", Policy.Effect.ALLOW, CUSTOMER);
      mine.setScopeLevel(ResolvedColumnMask.ScopeLevel.TABLE);

      assertThat(PolicyOverview.overrideNote(mine, "ORG", true)).isNull();
    }

    @Test
    @DisplayName("a policy below cannot constrain this one")
    void lowerSaysNothing() {
      Policy mine = subscriptionDocument("org-rule", Policy.Effect.ALLOW, null);
      mine.setScopeLevel(ResolvedColumnMask.ScopeLevel.ORG);

      // The note is about authority, and authority runs one way. Printing it
      // on the higher policy would tell an org author they may not loosen a
      // table rule, which is backwards.
      assertThat(PolicyOverview.overrideNote(mine, "TABLE", false)).isNull();
    }
  }

  @Nested
  @DisplayName("the shared-target examples")
  class Examples {

    @Test
    @DisplayName("the count is exact and the names agree with it")
    void countIsExactNamesAgree() {
      UUID mine = activate(maskEmail("mine"));
      UUID theirs = activate(maskEmail("theirs"));

      PolicyOverview.Overlap overlap = overlapsOf(mine).get(0);

      assertThat(overlap.policyId()).isEqualTo(theirs);
      // Two fixtures on the same selector share a table and a column. The
      // count is SQL's, the names are Java's, and the reason to assert both
      // is that they are now computed in different places and must still line
      // up. The cap the names are subject to is above two.
      assertThat(overlap.sharedTargets()).isEqualTo(2);
      assertThat(overlap.examples()).containsExactly(CUSTOMER, CUSTOMER + ".email");
    }
  }

  // ------------------------------------------------------------- from the asset

  @Nested
  @DisplayName("what governs one table")
  class AppliedToAsset {

    @Test
    @DisplayName("subscription and data policies both arrive")
    void bothKinds() {
      UUID sub = activate(subscription("org-allow", Policy.Effect.ALLOW, null));
      materializer.materialize(sub);
      UUID mask = activate(maskEmail());

      List<PolicyOverview.Applied> applied = appliedAt(CUSTOMER);

      // Order is the engine's composition order -- scope layer, then depth,
      // then name (FR-3.1.3). Both of these sit at ORG, so what separates them
      // is the alphabet, which is not a fact worth pinning. The screen splits
      // the two kinds into their own lists anyway.
      assertThat(applied).extracting(a -> a.policy().id()).containsExactlyInAnyOrder(sub, mask);
    }

    @Test
    @DisplayName("a data policy says which columns of this table it lands on")
    void columnsAreNamed() {
      activate(maskEmail());

      PolicyOverview.Applied applied = appliedAt(CUSTOMER).get(0);

      // The whole reason this is not just the policy list: an owner opening a
      // table wants to know it is `email` that gets masked, not that some
      // masking policy is somewhere in force.
      assertThat(applied.columns()).extracting(PolicyOverview.Target::name)
          .containsExactly("email");
      assertThat(applied.columns().get(0).matchReason()).containsEntry("action", "MASK");
      assertThat(applied.matchReason()).isNotEmpty();
    }

    @Test
    @DisplayName("a subscription policy lands on the table and no columns")
    void subscriptionHasNoColumns() {
      UUID sub = activate(subscription("org-allow", Policy.Effect.ALLOW, null));
      materializer.materialize(sub);

      assertThat(appliedAt(CUSTOMER).get(0).columns()).isEmpty();
    }

    @Test
    @DisplayName("columns of a different table are not counted against this one")
    void otherTablesStayOut() {
      activate(maskEmail());

      // `order` carries no PII tag, so the masking policy never reached it.
      assertThat(appliedAt(ORDER)).isEmpty();
    }

    @Test
    @DisplayName("a draft governs nothing yet")
    void draftsAreNotInForce() {
      maskEmail();

      assertThat(appliedAt(CUSTOMER)).isEmpty();
    }

    @Test
    @DisplayName("no policy at all is an empty answer, not a failure")
    void nothingIsAnAnswer() {
      assertThat(appliedAt(CUSTOMER)).isEmpty();
    }
  }

  // ----------------------------------------------------------------- fixtures

  /**
   * The asset-side read exactly as the resource performs it.
   *
   * <p>The environment is dev because that is what the store writes for a document that does not
   * name one, which these fixtures do not. Asking in prod here would pass by finding nothing.
   */
  private List<PolicyOverview.Applied> appliedAt(String fqn) {
    return overview.applied(fqn, store.activeFor(fqn, "dev"));
  }

  private UUID activate(UUID id) {
    store.transition(id, "ACTIVE", "alice", "test");
    return id;
  }

  private List<PolicyOverview.Overlap> overlapsOf(UUID id) {
    PolicyStore.StoredPolicy stored = store.find(id).orElseThrow();
    return overview.overlaps(id, stored.document(), stored.environment());
  }

  private UUID subscription(String name, Policy.Effect effect, String scopeFqn) {
    return store.create(subscriptionDocument(name, effect, scopeFqn), "alice").id();
  }

  private static Policy subscriptionDocument(String name, Policy.Effect effect, String scopeFqn) {
    Policy document = new Policy();
    document.setName(name);
    document.setPolicyType(Policy.PolicyType.SUBSCRIPTION);
    document.setEffect(effect);
    document.setScopeLevel(
        scopeFqn == null
            ? ResolvedColumnMask.ScopeLevel.ORG
            : ResolvedColumnMask.ScopeLevel.DATABASE);
    document.setScopeFqn(scopeFqn);
    document.setSelector(selector("PII.Sensitive"));
    return document;
  }

  private UUID maskEmail() {
    return maskEmail("mask-email");
  }

  /** A data policy that binds the table and, through its column rule, the email column. */
  private UUID maskEmail(String name) {
    Policy document = new Policy();
    document.setName(name);
    document.setPolicyType(Policy.PolicyType.DATA);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.ORG);
    document.setSelector(selector("PII.Sensitive"));

    ColumnRule rule = new ColumnRule();
    rule.setColumns(selector("PII.Sensitive"));
    rule.setAction(ColumnRule.Action.MASK);
    DataPolicy data = new DataPolicy();
    data.setColumnRules(List.of(rule));
    document.setData(data);

    UUID id = store.create(document, "alice").id();
    materializer.materialize(id);
    return id;
  }

  /** Re-points a policy at a facet nothing in the fixture carries. */
  private UUID retarget(UUID id, String tag) {
    PolicyStore.StoredPolicy stored = store.find(id).orElseThrow();
    Policy document = stored.document();
    document.setSelector(selector(tag));
    store.update(id, document, stored.version(), "alice", null);
    return id;
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

  /** One tagged table and one untagged one, so an overlap has somewhere not to be. */
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
