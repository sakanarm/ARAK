package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.Exemption;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.Policy;
import java.time.Instant;
import java.util.List;
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
 * Policy persistence, versioning and lifecycle against a real PostgreSQL.
 *
 * <p>What is worth testing here is not that a row round-trips. It is the three
 * rules that decide whether the platform can be trusted with an access
 * decision: a policy is born in draft, every change leaves a version behind,
 * and two editors cannot silently overwrite each other (FR-9.1, FR-9.2).
 */
@Testcontainers
class PolicyStoreIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static Jdbi jdbi;
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  private PolicyStore store;

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
        handle ->
            handle.execute(
                "TRUNCATE audit_policy_change, policy_version, policy_binding, policy CASCADE"));
    store = new PolicyStore(jdbi, json);
  }

  @Test
  @DisplayName("a new policy is a draft, whatever the document claims")
  void alwaysBornDraft() {
    Policy document = policy("mask-pii");
    document.setLifecycleState(Policy.LifecycleState.ACTIVE);

    PolicyStore.StoredPolicy stored = store.create(document, "alice");

    // Creating straight into ACTIVE would let an author skip the simulation
    // that FR-5.2 exists to make unskippable.
    assertThat(stored.lifecycleState()).isEqualTo("DRAFT");
    assertThat(stored.version()).isOne();
    assertThat(stored.createdBy()).isEqualTo("alice");
  }

  @Test
  @DisplayName("the scope columns are projections of the document, not a second copy")
  void scopeIsProjected() {
    Policy document = policy("finance-risk");
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.DOMAIN);
    document.setScopeFqn("Finance.Risk.Credit");

    UUID id = store.create(document, "alice").id();

    var row =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        "SELECT scope_level, scope_fqn, scope_depth FROM policy WHERE id = :id")
                    .bind("id", id)
                    .mapToMap()
                    .one());

    assertThat(row.get("scope_level")).isEqualTo("DOMAIN");
    // Two DOMAIN policies are not peers: the deeper sub-domain composes last
    // and may only tighten the one above it (FR-3.1.3).
    assertThat(row.get("scope_depth")).isEqualTo(2);
  }

  @Test
  @DisplayName("every change leaves a version behind")
  void historyIsAppendOnly() {
    UUID id = store.create(policy("mask-pii"), "alice").id();

    Policy edited = policy("mask-pii");
    edited.setDescription("now also covers phone");
    store.update(id, edited, 1, "bob", "added phone");
    store.transition(id, "ACTIVE", "carol", "approved");

    List<PolicyStore.Revision> history = store.history(id);

    // A decision made last quarter has to be explainable against the policy as
    // it read last quarter (FR-8.1), so nothing here is ever overwritten.
    assertThat(history).hasSize(3);
    assertThat(history.get(0).lifecycleState()).isEqualTo("ACTIVE");
    assertThat(history).extracting(PolicyStore.Revision::changedBy)
        .containsExactly("carol", "bob", "alice");
  }

  @Test
  @DisplayName("an edit against a version somebody else has moved past is refused")
  void staleWriteIsRefused() {
    UUID id = store.create(policy("mask-pii"), "alice").id();
    store.update(id, policy("mask-pii"), 1, "bob", "first");

    // Last-write-wins here would lose the restriction bob just added, and
    // nothing would say it had gone.
    assertThatThrownBy(() -> store.update(id, policy("mask-pii"), 1, "carol", "second"))
        .isInstanceOf(PolicyStore.StaleVersionException.class)
        .hasMessageContaining("version 2");
  }

  @Test
  @DisplayName("a name already used in the environment is refused, even by an archived policy")
  void aTakenNameIsRefusedNotAServerError() {
    UUID id = store.create(policy("mask-pii"), "alice").id();
    store.transition(id, "ARCHIVED", "alice", "retired");

    // Was a raw unique-constraint violation, which the API answered with 500.
    assertThatThrownBy(() -> store.create(policy("mask-pii"), "bob"))
        .isInstanceOf(PolicyStore.NameTakenException.class)
        .hasMessageContaining("mask-pii")
        .hasMessageContaining("ARCHIVED");
  }

  @Test
  @DisplayName("a policy with an empty selector is refused at save")
  void emptySelectorIsRefused() {
    Policy document = policy("mask-pii");
    document.setSelector(new AssetSelector());

    // The engine already reads an empty selector as "matches nothing". Catching
    // it here means the author finds out now, rather than believing a policy is
    // protecting something it never bound to.
    assertThatThrownBy(() -> store.create(document, "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("protects nobody");
  }

  @Test
  @DisplayName("an exemption with no expiry or no reason is refused at save")
  void openEndedExemptionIsRefused() {
    // The engine ignores such an exemption, so the person named in it is still
    // refused while the author believes they are exempt. Found in UAT.
    Policy noExpiry = policy("deny-contractors");
    noExpiry.setExemptions(List.of(new Exemption().withPrincipal("ann").withReason("ERP migration")));
    assertThatThrownBy(() -> store.create(noExpiry, "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("needs an expiry date");

    Policy noReason = policy("deny-contractors");
    noReason.setExemptions(
        List.of(new Exemption().withPrincipal("ann").withExpiresAt(Instant.parse("2026-10-15T00:00:00Z"))));
    assertThatThrownBy(() -> store.create(noReason, "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("needs a reason");

    Policy complete = policy("deny-contractors");
    complete.setExemptions(
        List.of(
            new Exemption()
                .withPrincipal("ann")
                .withReason("ERP migration")
                .withExpiresAt(Instant.parse("2026-10-15T00:00:00Z"))));
    assertThat(store.create(complete, "alice").document().getExemptions()).hasSize(1);
  }

  @Test
  @DisplayName("a local policy has to say what it is anchored to")
  void localPolicyNeedsAnFqn() {
    Policy document = policy("table-local");
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.TABLE);
    document.setScopeFqn(null);

    assertThatThrownBy(() -> store.create(document, "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("anchored");
  }

  @Test
  @DisplayName("the lifecycle only moves the way FR-9.1 allows")
  void lifecycleIsAStateMachine() {
    UUID id = store.create(policy("mask-pii"), "alice").id();

    assertThatThrownBy(() -> store.transition(id, "DISABLED", "alice", null))
        .isInstanceOf(PolicyStore.IllegalTransitionException.class);

    store.transition(id, "ACTIVE", "alice", null);
    store.transition(id, "DISABLED", "alice", "paused for the migration");
    store.transition(id, "ACTIVE", "alice", "migration done");
    store.archive(id, "alice", "superseded");

    // Archived is terminal: the policy is evidence of a decision already made.
    assertThatThrownBy(() -> store.transition(id, "ACTIVE", "alice", null))
        .isInstanceOf(PolicyStore.IllegalTransitionException.class);
    assertThatThrownBy(() -> store.update(id, policy("mask-pii"), 1, "alice", null))
        .isInstanceOf(PolicyStore.IllegalTransitionException.class);
  }

  @Test
  @DisplayName("active policies for an asset arrive outermost layer first")
  void activeForIsOrderedForComposition() {
    UUID org = active("org-wide", ResolvedColumnMask.ScopeLevel.ORG, null, "alice");
    UUID domain = active("domain", ResolvedColumnMask.ScopeLevel.DOMAIN, "Finance", "alice");
    UUID deep = active("sub-domain", ResolvedColumnMask.ScopeLevel.DOMAIN, "Finance.Risk", "alice");
    UUID table =
        active("table", ResolvedColumnMask.ScopeLevel.TABLE, "prod.SalesDB.dbo.customer", "alice");

    bind(org, deep, domain, table);

    List<PolicyStore.StoredPolicy> ordered = store.activeFor("prod.SalesDB.dbo.customer", "dev");

    // The composer walks this list in order and has to be able to say which
    // layer each restriction came from (FR-5.4). Sorting here keeps that
    // knowledge out of the engine.
    assertThat(ordered).extracting(PolicyStore.StoredPolicy::id)
        .containsExactly(org, domain, deep, table);
  }

  @Test
  @DisplayName("a policy whose validity window has closed is not active for an asset")
  void expiredPolicyIsNotReturned() {
    UUID id = active("expired", ResolvedColumnMask.ScopeLevel.ORG, null, "alice");
    bind(id);
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate("UPDATE policy SET valid_until = now() - interval '1 day' WHERE id = :id")
                .bind("id", id)
                .execute());

    assertThat(store.activeFor("prod.SalesDB.dbo.customer", "dev")).isEmpty();
  }

  @Test
  @DisplayName("a policy in another environment does not reach this one")
  void environmentsAreSeparate() {
    UUID id = active("prod-only", ResolvedColumnMask.ScopeLevel.ORG, null, "alice");
    bind(id);
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate("UPDATE policy SET environment = 'prod' WHERE id = :id")
                .bind("id", id)
                .execute());

    assertThat(store.activeFor("prod.SalesDB.dbo.customer", "dev")).isEmpty();
    assertThat(store.activeFor("prod.SalesDB.dbo.customer", "prod")).hasSize(1);
  }

  // ------------------------------------------------------------------ fixture

  private UUID active(String name, ResolvedColumnMask.ScopeLevel level, String scopeFqn, String author) {
    Policy document = policy(name);
    document.setScopeLevel(level);
    document.setScopeFqn(scopeFqn);
    UUID id = store.create(document, author).id();
    store.transition(id, "ACTIVE", author, null);
    return id;
  }

  /** Bindings written by hand: this suite is about the store, not the materialiser. */
  private void bind(UUID... policyIds) {
    jdbi.useHandle(
        handle -> {
          for (UUID id : policyIds) {
            handle
                .createUpdate(
                    """
                    INSERT INTO policy_binding (policy_id, target_fqn, target_kind)
                    VALUES (:id, 'prod.SalesDB.dbo.customer', 'TABLE')
                    """)
                .bind("id", id)
                .execute();
          }
        });
  }

  private static Policy policy(String name) {
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
}
