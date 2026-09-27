package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.Policy;
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
 * The policy change log, the history read from it, and rollback (FR-8.1,
 * FR-9.2), against a real PostgreSQL.
 *
 * <p>The log is written in the same transaction as the change it describes, so
 * what is tested here is that each kind of write leaves the row it should, that
 * a rollback is a new version rather than an edit of an old one, and that the
 * refusals refuse before anything is written.
 */
@Testcontainers
class PolicyHistoryIT {

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

  @Nested
  @DisplayName("the change log")
  class Log {

    @Test
    @DisplayName("every write leaves one row, named for what it did")
    void everyWriteIsLogged() {
      UUID id = store.create(policy("mask-pii", "first"), "alice", "192.0.2.10").id();
      store.update(id, policy("mask-pii", "second"), 1, "bob", "added phone", "192.0.2.11");
      store.transition(id, "PENDING_APPROVAL", "bob", "please review", null);
      store.transition(id, "DRAFT", "carol", "needs an expiry", null);
      store.transition(id, "ACTIVE", "carol", null, null);
      store.transition(id, "DISABLED", "carol", "paused", null);
      store.transition(id, "ACTIVE", "carol", "resumed", null);
      store.archive(id, "carol", "superseded");

      List<Map<String, Object>> rows = log(id);

      assertThat(rows)
          .extracting(row -> row.get("action"))
          .containsExactly(
              "CREATE", "UPDATE", "SUBMIT", "RETURN", "PUBLISH", "DISABLE", "PUBLISH", "ARCHIVE");
      assertThat(rows)
          .extracting(row -> row.get("to_version"))
          .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
      assertThat(rows.get(0).get("from_version")).isNull();
      assertThat(rows.get(1).get("from_version")).isEqualTo(1);
      // A lifecycle move changes no field of the document, so the states are
      // what says what happened.
      assertThat(rows.get(4).get("from_state")).isEqualTo("DRAFT");
      assertThat(rows.get(4).get("to_state")).isEqualTo("ACTIVE");
      assertThat(rows.get(4).get("reason")).isEqualTo("DRAFT -> ACTIVE");
      assertThat(rows.get(3).get("reason")).isEqualTo("needs an expiry");
    }

    @Test
    @DisplayName("an edit records the document before and after")
    void anEditKeepsBothSides() {
      UUID id = store.create(policy("mask-pii", "first"), "alice").id();
      store.update(id, policy("mask-pii", "second"), 1, "bob", "reworded");

      Map<String, Object> edit = log(id).get(1);

      assertThat(String.valueOf(edit.get("before_document"))).contains("first");
      assertThat(String.valueOf(edit.get("after_document"))).contains("second");
      assertThat(edit.get("actor")).isEqualTo("bob");
      assertThat(edit.get("policy_name")).isEqualTo("mask-pii");
    }

    @Test
    @DisplayName("where a change came from is kept in the log and never read back out")
    void theAddressStaysInTheLog() throws Exception {
      UUID id = store.create(policy("mask-pii", "first"), "alice", "203.0.113.7").id();

      String kept =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(
                          "SELECT host(client_ip) FROM audit_policy_change WHERE policy_id = :id")
                      .bind("id", id)
                      .mapTo(String.class)
                      .one());
      assertThat(kept).isEqualTo("203.0.113.7");

      // Anybody who can open the policy can read its history; where its author
      // was sitting is for an investigation.
      assertThat(json.writeValueAsString(store.history(id))).doesNotContain("203.0.113");
    }

    @Test
    @DisplayName("a refused edit writes neither a version nor a log row")
    void aRefusalLeavesNoTrace() {
      UUID id = store.create(policy("mask-pii", "first"), "alice").id();
      store.update(id, policy("mask-pii", "second"), 1, "bob", "first edit");

      assertThatThrownBy(() -> store.update(id, policy("mask-pii", "third"), 1, "carol", "stale"))
          .isInstanceOf(PolicyStore.StaleVersionException.class);

      assertThat(log(id)).hasSize(2);
      assertThat(store.history(id)).hasSize(2);
    }

    @Test
    @DisplayName("renaming onto a name in use is refused as a clash, not a server error")
    void aRenameClashIsNameTaken() {
      store.create(policy("taken", "first"), "alice");
      UUID id = store.create(policy("mine", "first"), "alice").id();

      assertThatThrownBy(() -> store.update(id, policy("taken", "first"), 1, "bob", "rename"))
          .isInstanceOf(PolicyStore.NameTakenException.class)
          .hasMessageContaining("taken");
      assertThat(store.find(id).orElseThrow().document().getName()).isEqualTo("mine");
    }
  }

  @Nested
  @DisplayName("the history")
  class History {

    @Test
    @DisplayName("each version carries the act that produced it, newest first")
    void versionsCarryTheirAct() {
      UUID id = store.create(policy("mask-pii", "first"), "alice").id();
      store.update(id, policy("mask-pii", "second"), 1, "bob", "reworded");
      store.transition(id, "ACTIVE", "carol", "approved");

      List<PolicyStore.Revision> history = store.history(id);

      assertThat(history).extracting(PolicyStore.Revision::version).containsExactly(3, 2, 1);
      assertThat(history)
          .extracting(PolicyStore.Revision::action)
          .containsExactly("PUBLISH", "UPDATE", "CREATE");
      assertThat(history)
          .extracting(PolicyStore.Revision::changeReason)
          .containsExactly("approved", "reworded", "created");
      assertThat(history.get(1).document().getDescription()).isEqualTo("second");
      assertThat(history.get(2).document().getDescription()).isEqualTo("first");
    }

    @Test
    @DisplayName("versions written before the log was kept are named from the states around them")
    void versionsWithoutALogRowAreInferred() {
      UUID id = store.create(policy("mask-pii", "first"), "alice").id();
      store.update(id, policy("mask-pii", "second"), 1, "bob", "reworded");
      store.transition(id, "ACTIVE", "carol", null);
      store.transition(id, "DISABLED", "carol", null);
      // As a policy written before V39 would look: versions, no log.
      jdbi.useHandle(handle -> handle.execute("DELETE FROM audit_policy_change"));

      assertThat(store.history(id))
          .extracting(PolicyStore.Revision::action)
          .containsExactly("DISABLE", "PUBLISH", "UPDATE", "CREATE");
    }

    @Test
    @DisplayName("one version can be read on its own")
    void oneRevision() {
      UUID id = store.create(policy("mask-pii", "first"), "alice").id();
      store.update(id, policy("mask-pii", "second"), 1, "bob", "reworded");

      assertThat(store.revision(id, 1)).get().extracting(r -> r.document().getDescription())
          .isEqualTo("first");
      assertThat(store.revision(id, 9)).isEmpty();
    }
  }

  @Nested
  @DisplayName("rollback")
  class Rollback {

    @Test
    @DisplayName("puts an old document back as a new version and keeps everything in between")
    void rollbackIsANewVersion() {
      UUID id = store.create(policy("mask-pii", "first"), "alice").id();
      Policy wider = policy("mask-pii", "second");
      wider.setSelector(selector("PII"));
      store.update(id, wider, 1, "bob", "cover all PII");
      store.transition(id, "ACTIVE", "carol", "approved");

      PolicyStore.StoredPolicy restored =
          store.rollback(id, 1, 3, "dave", "the wider selector masked the reports", "192.0.2.20");

      assertThat(restored.version()).isEqualTo(4);
      assertThat(restored.updatedBy()).isEqualTo("dave");
      assertThat(restored.document().getDescription()).isEqualTo("first");
      assertThat(restored.document().getSelector().getCondition().getValue())
          .isEqualTo("PII.Sensitive");
      // Rolling back changes what is enforced; it does not also switch the
      // policy on or off.
      assertThat(restored.lifecycleState()).isEqualTo("ACTIVE");

      List<PolicyStore.Revision> history = store.history(id);
      assertThat(history).extracting(PolicyStore.Revision::version).containsExactly(4, 3, 2, 1);
      assertThat(history.get(0).action()).isEqualTo("ROLLBACK");
      assertThat(history.get(0).restoredFrom()).isEqualTo(1);
      assertThat(history.get(0).changedBy()).isEqualTo("dave");
      assertThat(history.get(0).changeReason()).isEqualTo("the wider selector masked the reports");
      // The version that was rolled away from is still there to explain the
      // decisions it made while it was in force.
      assertThat(history.get(2).document().getDescription()).isEqualTo("second");

      Map<String, Object> row = log(id).get(3);
      assertThat(row.get("action")).isEqualTo("ROLLBACK");
      assertThat(row.get("restored_from")).isEqualTo(1);
      assertThat(row.get("from_version")).isEqualTo(3);
      assertThat(row.get("to_version")).isEqualTo(4);
      assertThat(String.valueOf(row.get("before_document"))).contains("second");
      assertThat(String.valueOf(row.get("after_document"))).contains("first");
    }

    @Test
    @DisplayName("the columns the engine and the list read follow the restored document")
    void theRowFollowsTheDocument() {
      Policy first = policy("mask-pii", "first");
      first.setDisplayName("Mask PII");
      UUID id = store.create(first, "alice").id();
      Policy second = policy("mask-pii", "second");
      second.setDisplayName("Mask all PII");
      second.setScopeLevel(ResolvedColumnMask.ScopeLevel.DOMAIN);
      second.setScopeFqn("Finance.Risk");
      store.update(id, second, 1, "bob", "narrowed to risk");

      store.rollback(id, 1, 2, "carol", "risk scope was a mistake", null);

      Map<String, Object> row =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(
                          "SELECT display_name, scope_level, scope_fqn, scope_depth, version"
                              + " FROM policy WHERE id = :id")
                      .bind("id", id)
                      .mapToMap()
                      .one());
      assertThat(row.get("display_name")).isEqualTo("Mask PII");
      assertThat(row.get("scope_level")).isEqualTo("ORG");
      assertThat(row.get("scope_fqn")).isNull();
      assertThat(row.get("version")).isEqualTo(3);
    }

    @Test
    @DisplayName("a copy that says it is a draft does not come back saying so")
    void theCopyDropsTheStoresOwnFields() {
      Policy first = policy("mask-pii", "first");
      first.setLifecycleState(Policy.LifecycleState.DRAFT);
      UUID id = store.create(first, "alice").id();
      store.transition(id, "ACTIVE", "carol", null);
      store.update(id, policy("mask-pii", "second"), 2, "bob", "reworded");

      PolicyStore.StoredPolicy restored = store.rollback(id, 1, 3, "carol", "reworded badly", null);

      // The engine skips a document that says DRAFT. Restored as it was, an
      // active policy would stop being enforced the moment it was rolled back.
      assertThat(restored.lifecycleState()).isEqualTo("ACTIVE");
      assertThat(restored.document().getLifecycleState()).isNull();
    }

    @Test
    @DisplayName("is refused without a reason, against a moved version, or for a version that is not there")
    void refusals() {
      UUID id = store.create(policy("mask-pii", "first"), "alice").id();
      store.update(id, policy("mask-pii", "second"), 1, "bob", "reworded");

      assertThatThrownBy(() -> store.rollback(id, 1, 2, "carol", "  ", null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Say why");
      assertThatThrownBy(() -> store.rollback(id, 1, 1, "carol", "undo", null))
          .isInstanceOf(PolicyStore.StaleVersionException.class);
      assertThatThrownBy(() -> store.rollback(id, 9, 2, "carol", "undo", null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("has no version 9");
      assertThatThrownBy(() -> store.rollback(id, 2, 2, "carol", "undo", null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("is the current version");

      assertThat(store.history(id)).hasSize(2);
      assertThat(log(id)).hasSize(2);
    }

    @Test
    @DisplayName("is refused when the old version reads the same as the current one")
    void nothingToPutBack() {
      UUID id = store.create(policy("mask-pii", "first"), "alice").id();
      // Publishing bumps the version without touching the document.
      store.transition(id, "ACTIVE", "carol", null);

      assertThatThrownBy(() -> store.rollback(id, 1, 2, "carol", "undo", null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("reads the same");
      assertThat(store.history(id)).hasSize(2);
    }

    @Test
    @DisplayName("is refused on an archived policy")
    void archivedIsFinal() {
      UUID id = store.create(policy("mask-pii", "first"), "alice").id();
      store.update(id, policy("mask-pii", "second"), 1, "bob", "reworded");
      store.archive(id, "alice", "superseded");

      assertThatThrownBy(() -> store.rollback(id, 1, 3, "carol", "bring it back", null))
          .isInstanceOf(PolicyStore.IllegalTransitionException.class)
          .hasMessageContaining("archived");
    }

    @Test
    @DisplayName("to a name another policy has since taken is refused as a clash")
    void theOldNameIsTaken() {
      UUID id = store.create(policy("alpha", "first"), "alice").id();
      store.update(id, policy("beta", "first"), 1, "bob", "renamed");
      store.create(policy("alpha", "someone else's"), "carol");

      assertThatThrownBy(() -> store.rollback(id, 1, 2, "bob", "undo the rename", null))
          .isInstanceOf(PolicyStore.NameTakenException.class)
          .hasMessageContaining("alpha");
      assertThat(store.find(id).orElseThrow().version()).isEqualTo(2);
      assertThat(log(id)).hasSize(2);
    }
  }

  // ------------------------------------------------------------------ fixture

  private static List<Map<String, Object>> log(UUID id) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT actor, policy_name, action, from_version, to_version,
                           before_document::text AS before_document,
                           after_document::text AS after_document,
                           from_state, to_state, reason, restored_from
                    FROM audit_policy_change WHERE policy_id = :id ORDER BY id
                    """)
                .bind("id", id)
                .mapToMap()
                .list());
  }

  private static Policy policy(String name, String description) {
    Policy document = new Policy();
    document.setName(name);
    document.setDescription(description);
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
