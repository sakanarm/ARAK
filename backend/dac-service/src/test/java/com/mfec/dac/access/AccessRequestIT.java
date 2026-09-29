package com.mfec.dac.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mfec.dac.access.AccessWorkflow.Draft;
import com.mfec.dac.access.AccessWorkflow.Join;
import com.mfec.dac.access.AccessWorkflow.Kind;
import com.mfec.dac.access.AccessWorkflow.OnReject;
import com.mfec.dac.access.AccessWorkflow.Rule;
import com.mfec.dac.access.AccessWorkflow.Seat;
import com.mfec.dac.access.AccessWorkflow.Stage;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.catalog.AssetStore;
import com.mfec.dac.engine.EngineConfig;
import com.mfec.dac.engine.PolicyEngine;
import com.mfec.dac.identity.PrincipalQuery;
import com.mfec.dac.om.crawl.AssetCrawler;
import com.mfec.dac.om.crawl.CrawledAsset;
import com.mfec.dac.om.facet.ExtractedFacet;
import com.mfec.dac.policy.AssetContextLoader;
import com.mfec.dac.policy.DecisionCache;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.policy.PolicyBindingMaterializer;
import com.mfec.dac.policy.PolicyStore;
import com.mfec.dac.policy.PrincipalLoader;
import com.mfec.dac.resources.AccessRequestResource;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.AttributeCondition;
import com.mfec.dac.schema.entity.policy.ColumnRule;
import com.mfec.dac.schema.entity.policy.ContextRule;
import com.mfec.dac.schema.entity.policy.DataPolicy;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.PrincipalMatch;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Handle;
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
 * Asking a table's owner for access, and what their answer can and cannot do.
 *
 * <p>Two halves, tested together because neither means anything alone. The
 * workflow half is who may ask, who may answer and what an answer writes. The
 * policy half is the one the query page leans on: a "Request access" button
 * is only honest if a "yes" would actually open the table, so the eligibility
 * check is asserted against every way a data policy can stand in the way --
 * a DENY, a higher layer that refuses the person, one that consented to being
 * relaxed, and a mask that a grant must never lift.
 */
@Testcontainers
class AccessRequestIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String SERVICE = "prod-pg";
  private static final String SALES = "prod-pg.SalesDB";
  private static final String DBO = "prod-pg.SalesDB.dbo";

  /** Tagged PII, owned by the user owner_o. */
  private static final String CUSTOMER = "prod-pg.SalesDB.dbo.customer";

  /** Untagged, owned by the OpenMetadata team Finance, so no policy binds to it. */
  private static final String LEDGER = "prod-pg.SalesDB.dbo.ledger";

  /** Untagged and without an owner: only a platform administrator decides. */
  private static final String ORPHAN = "prod-pg.SalesDB.dbo.orphan";

  private static final AccessRequestStore.Actor ADMIN =
      new AccessRequestStore.Actor("admin", true);
  private static final AccessRequestStore.Actor OWNER =
      new AccessRequestStore.Actor("owner_o", false);
  private static final AccessRequestStore.Actor TEAM_MEMBER =
      new AccessRequestStore.Actor("finance_lead", false);
  private static final AccessRequestStore.Actor ANALYST_A =
      new AccessRequestStore.Actor("analyst_a", false);
  private static final AccessRequestStore.Actor ANALYST_B =
      new AccessRequestStore.Actor("analyst_b", false);
  private static final AccessRequestStore.Actor SEC_A = new AccessRequestStore.Actor("sec_a", false);
  private static final AccessRequestStore.Actor SEC_B = new AccessRequestStore.Actor("sec_b", false);
  private static final AccessRequestStore.Actor SEC_C = new AccessRequestStore.Actor("sec_c", false);

  private static Jdbi jdbi;
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  private PolicyStore policies;
  private PolicyBindingMaterializer materializer;
  private GrantStore grants;
  private DecisionService decisions;
  private WorkflowStore workflows;
  private RequestTemplateStore templates;
  private AccessRequestStore requests;
  private AccessEligibility eligibility;

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
              TRUNCATE access_request, audit_access_request, access_request_notice_seen,
                       access_workflow, audit_access_workflow, access_request_template,
                       audit_access_request_template, policy_version, policy_binding,
                       access_grant, audit_grant_change, row_entitlement, enforcement_state,
                       asset_facet, asset_owner, asset_fqn_map, asset_column, asset, policy,
                       principal_attribute, app_role_assignment, group_member, principal CASCADE
              """);
          handle.execute("DELETE FROM data_source");
          handle.execute(
              """
              INSERT INTO data_source (name, engine, host, port, credential_ref, om_service_fqn)
              VALUES ('prod-pg', 'POSTGRES', 'db.example.test', 5432, 'vault://x', 'prod-pg')
              """);
        });

    policies = new PolicyStore(jdbi, json);
    AssetContextLoader contexts = new AssetContextLoader(json);
    materializer = new PolicyBindingMaterializer(jdbi, json, contexts);
    grants = new GrantStore(jdbi);
    PrincipalLoader principals = new PrincipalLoader();
    decisions =
        new DecisionService(
            jdbi,
            contexts,
            principals,
            policies,
            grants,
            new PolicyEngine(EngineConfig.defaults()),
            // Off for the same reason as GrantCompositionIT: every test changes
            // the world between two decisions about the same pair.
            DecisionCache.disabled());
    workflows = new WorkflowStore(jdbi, json);
    templates = new RequestTemplateStore(jdbi, json);
    requests = new AccessRequestStore(jdbi, json, grants, workflows, templates);
    eligibility = new AccessEligibility(decisions, requests);

    crawl();
    directory();
  }

  // ------------------------------------------------------------- asking

  @Nested
  @DisplayName("asking")
  class Asking {

    @Test
    @DisplayName("opens a pending request that names the owners who decide it")
    void opens() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);

      assertThat(made.status()).isEqualTo("PENDING");
      assertThat(made.requesterUsername()).isEqualTo("analyst_a");
      assertThat(made.requestedDays()).isEqualTo(7);
      assertThat(made.attemptedSql()).isEqualTo("SELECT * FROM customer");
      assertThat(made.decidedBy()).isNull();
      assertThat(made.grantId()).isNull();
      // The requester sees who will decide, and is not one of them.
      assertThat(made.approvers())
          .extracting(AccessRequestStore.Approver::name)
          .containsExactly("owner_o");
      assertThat(made.mayDecide()).isFalse();
      // With no workflow configured, the built-in one: any one owner.
      assertThat(made.workflowName()).isEqualTo("Built-in");
      assertThat(made.currentStep()).isEqualTo(1);
      assertThat(made.stages())
          .singleElement()
          .satisfies(
              stage -> {
                assertThat(stage.name()).isEqualTo("Owner approval");
                assertThat(stage.status()).isEqualTo("OPEN");
                assertThat(stage.pool())
                    .extracting(ApproverDirectory.Member::username)
                    .containsExactly("owner_o");
                assertThat(stage.fallback()).isFalse();
                assertThat(stage.mayVote()).isFalse();
              });

      assertThat(requests.madeBy(ANALYST_A, 10))
          .extracting(AccessRequestStore.StoredRequest::id)
          .containsExactly(made.id());
      assertThat(requests.openRequest(CUSTOMER, "analyst_a")).isPresent();
      assertThat(trail(made.id())).containsExactly("REQUEST");
    }

    @Test
    @DisplayName("asking twice for the same table is one request, not two")
    void once() {
      AccessRequestStore.StoredRequest first = ask("analyst_a", CUSTOMER, 7);

      assertThatThrownBy(() -> ask("analyst_a", CUSTOMER, 30))
          .isInstanceOf(AccessRequestStore.RequestException.class)
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT))
          .hasMessageContaining(first.id().toString());

      // Somebody else asking for the same table is a different question.
      assertThat(ask("analyst_b", CUSTOMER, 7).id()).isNotEqualTo(first.id());
    }

    @Test
    @DisplayName("may ask again once the first request is closed")
    void againAfterClosing() {
      AccessRequestStore.StoredRequest first = ask("analyst_a", CUSTOMER, 7);
      requests.withdraw(first.id(), ANALYST_A);

      assertThat(ask("analyst_a", CUSTOMER, 7).id()).isNotEqualTo(first.id());
    }

    @Test
    @DisplayName("refuses a request without a reason, a table that is not there, or a silly duration")
    void refusesNonsense() {
      assertInvalid(() -> requests.create(newRequest("analyst_a", CUSTOMER, "   ", 7), ANALYST_A));
      assertInvalid(
          () -> requests.create(newRequest("analyst_a", DBO + ".nope", "Need it", 7), ANALYST_A));
      // A schema is in the catalog, but a grant is on a table.
      assertInvalid(() -> requests.create(newRequest("analyst_a", DBO, "Need it", 7), ANALYST_A));
      assertInvalid(() -> requests.create(newRequest("analyst_a", CUSTOMER, "Need it", 0), ANALYST_A));
      assertInvalid(
          () -> requests.create(newRequest("analyst_a", CUSTOMER, "Need it", 366), ANALYST_A));

      // Nothing half-written by any of them.
      assertThat(requests.madeBy(ANALYST_A, 10)).isEmpty();
    }

    @Test
    @DisplayName("every request gets its own ticket number, found however it is typed")
    void ticket() {
      AccessRequestStore.StoredRequest first = ask("analyst_a", CUSTOMER, 7);
      AccessRequestStore.StoredRequest second = ask("analyst_b", CUSTOMER, 7);

      assertThat(first.ticket()).matches("REQ-\\d{6}");
      assertThat(second.ticket()).isNotEqualTo(first.ticket());
      long number = AccessRequestStore.ticketNumber(first.ticket()).orElseThrow();
      // The number survives a reload, and the owner reaches it by any spelling.
      assertThat(requests.find(first.id(), OWNER).ticket()).isEqualTo(first.ticket());
      for (String typed :
          List.of(first.ticket(), first.ticket().toLowerCase(), "#" + number, " " + number + " ",
              "REQ " + number)) {
        assertThat(requests.findByTicket(typed, OWNER).id()).as(typed).isEqualTo(first.id());
      }
      assertThat(requests.findByTicket(first.ticket(), ANALYST_A).id()).isEqualTo(first.id());
    }

    @Test
    @DisplayName("a ticket number opens nothing its reader could not open by id")
    void ticketKeepsItsSecrets() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);

      // Somebody else's request and one that does not exist read the same,
      // and neither message gives the request's id away.
      assertThatThrownBy(() -> requests.findByTicket(made.ticket(), ANALYST_B))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.NOT_FOUND))
          .hasMessage("No access request " + made.ticket());
      assertThatThrownBy(() -> requests.findByTicket("REQ-999999", OWNER))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.NOT_FOUND))
          .hasMessage("No access request REQ-999999");
      for (String typed : List.of("", "REQ-", "REQ-0", "abc", "1; DROP TABLE x", "-4")) {
        assertInvalid(() -> requests.findByTicket(typed, OWNER));
      }
    }
  }

  // ------------------------------------------------------------- deciding

  @Nested
  @DisplayName("deciding")
  class Deciding {

    @Test
    @DisplayName("an owner's yes waits to be configured; configuring it as a grant opens the table")
    void approveWritesAGrant() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", LEDGER, 7);
      assertThat(read("analyst_a", LEDGER)).isFalse();

      AccessRequestStore.StoredRequest decided =
          requests.approve(made.id(), TEAM_MEMBER, "For the audit", null);

      assertThat(decided.status()).isEqualTo("APPROVED");
      assertThat(decided.decidedBy()).isEqualTo("finance_lead");
      assertThat(decided.decisionNote()).isEqualTo("For the audit");
      // Approved is not configured: nothing is open yet.
      assertThat(decided.grantId()).isNull();
      assertThat(read("analyst_a", LEDGER)).isFalse();
      assertThat(decided.stages().get(0).status()).isEqualTo("APPROVED");
      assertThat(decided.stages().get(0).votes())
          .singleElement()
          .satisfies(
              v -> {
                assertThat(v.voter()).isEqualTo("finance_lead");
                assertThat(v.override()).isFalse();
              });
      // The team owns the ledger, so its member configures it too.
      assertThat(decided.mayConfigure()).isTrue();
      assertThat(requests.openRequest(LEDGER, "analyst_a")).isPresent();

      AccessRequestStore.StoredRequest done =
          requests.complete(
              made.id(), TEAM_MEMBER, new AccessRequestStore.Completion("GRANT", null, null, null));
      assertThat(done.status()).isEqualTo("COMPLETED");
      assertThat(done.fulfilment()).isEqualTo("GRANT");
      assertThat(done.completedBy()).isEqualTo("finance_lead");
      assertThat(done.assignee()).isEqualTo("finance_lead");

      GrantStore.StoredGrant grant = grants.find(done.grantId()).orElseThrow();
      assertThat(grant.source()).isEqualTo("request");
      assertThat(grant.requestId()).isEqualTo(made.id());
      assertThat(grant.username()).isEqualTo("analyst_a");
      assertThat(grant.grantedBy()).isEqualTo("finance_lead");
      assertThat(grant.reason()).contains(made.id().toString()).contains("For the audit");
      // Seven days, as asked -- not open-ended because the owner left it blank.
      assertThat(Duration.between(grant.validFrom(), grant.validUntil())).isEqualTo(Duration.ofDays(7));

      assertThat(read("analyst_a", LEDGER)).isTrue();
      // The grant names one person; the colleague is still outside.
      assertThat(read("analyst_b", LEDGER)).isFalse();
      assertThat(trail(made.id())).containsExactly("COMPLETE", "APPROVE", "VOTE", "REQUEST");
      assertThat(requests.openRequest(LEDGER, "analyst_a")).isEmpty();
    }

    @Test
    @DisplayName("revoking the grant an approval wrote closes the table again")
    void revokeUndoesApproval() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", LEDGER, 7);
      AccessRequestStore.StoredRequest decided = approveAndGrant(made.id(), ADMIN, null);

      grants.revoke(decided.grantId(), "owner_o", "Audit is over");

      assertThat(read("analyst_a", LEDGER)).isFalse();
    }

    @Test
    @DisplayName("nobody decides their own request, not even an administrator")
    void notOwnRequest() {
      AccessRequestStore.StoredRequest made = ask("admin", CUSTOMER, 7);

      assertThatThrownBy(() -> requests.approve(made.id(), ADMIN, null, null))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.FORBIDDEN));
      assertThatThrownBy(() -> requests.reject(made.id(), ADMIN, "No"))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.FORBIDDEN));
      // And it is not in their inbox to tempt them.
      assertThat(requests.decidableBy(ADMIN, null, 100)).isEmpty();
      assertThat(grants.onAsset(CUSTOMER)).isEmpty();
    }

    @Test
    @DisplayName("somebody who does not own the table is told the request does not exist")
    void strangerSeesNothing() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);

      assertThatThrownBy(() -> requests.approve(made.id(), ANALYST_B, null, null))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.NOT_FOUND));
      assertThatThrownBy(() -> requests.find(made.id(), ANALYST_B))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.NOT_FOUND));
      // A team member owns the ledger, not the customer table.
      assertThatThrownBy(() -> requests.approve(made.id(), TEAM_MEMBER, null, null))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.NOT_FOUND));
      assertThat(requests.decidableBy(TEAM_MEMBER, null, 100)).isEmpty();
      assertThat(grants.onAsset(CUSTOMER)).isEmpty();
    }

    @Test
    @DisplayName("a team named like the owning user is not the owner")
    void ownerTypeMatters() {
      // The customer table is owned by the *user* owner_o. A team that happens
      // to be called owner_o owns nothing, and neither do its members.
      jdbi.useHandle(
          handle -> {
            handle.execute(
                """
                INSERT INTO principal (principal_type, username, source, enabled)
                VALUES ('GROUP', 'owner_o', 'openmetadata', true)
                """);
            handle.execute(
                """
                INSERT INTO group_member (group_id, member_id, source)
                SELECT g.id, m.id, 'openmetadata'
                FROM principal g, principal m
                WHERE g.username = 'owner_o' AND g.principal_type = 'GROUP'
                  AND m.username = 'analyst_b'
                """);
          });
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);

      assertThatThrownBy(() -> requests.approve(made.id(), ANALYST_B, null, null))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.NOT_FOUND));
      assertThat(requests.decidableBy(ANALYST_B, null, 100)).isEmpty();
      // The real owner still can.
      assertThat(requests.decidableBy(OWNER, null, 100)).hasSize(1);
    }

    @Test
    @DisplayName("a request only its requester could decide says so, instead of waiting forever")
    void stranded() {
      // No administrator holds the role yet: an orphan waits for nobody.
      AccessRequestStore.StoredRequest orphan = ask("analyst_a", ORPHAN, null);
      assertThat(requests.find(orphan.id(), ANALYST_A).stranded()).isTrue();

      administrator("admin");
      assertThat(requests.find(orphan.id(), ANALYST_A).stranded()).isFalse();
      assertThat(requests.nobodyElseDecides("analyst_b", ORPHAN)).isFalse();

      // The only administrator asking for an unowned table: nobody decides
      // their own request, so it waits for nobody, and the page must say so.
      AccessRequestStore.StoredRequest own = ask("admin", ORPHAN, null);
      assertThat(requests.find(own.id(), ADMIN).stranded()).isTrue();
      assertThat(requests.decidableBy(ADMIN, null, 100))
          .extracting(AccessRequestStore.StoredRequest::id)
          .doesNotContain(own.id());
      // An owned table is decided by its owner, whoever asks.
      assertThat(requests.nobodyElseDecides("admin", CUSTOMER)).isFalse();
      // The sole owner asking about their own table still has an administrator.
      assertThat(requests.nobodyElseDecides("owner_o", CUSTOMER)).isFalse();

      administrator("owner_o");
      assertThat(requests.find(own.id(), ADMIN).stranded()).isFalse();

      // A decided request waits for nothing: another administrator configures it.
      AccessRequestStore.StoredRequest approved =
          requests.approve(own.id(), new AccessRequestStore.Actor("owner_o", true), null, null);
      assertThat(approved.status()).isEqualTo("APPROVED");
      assertThat(approved.stages().get(0).votes().get(0).override()).isTrue();
      assertThat(requests.find(own.id(), ADMIN).stranded()).isFalse();
      assertThat(requests.find(own.id(), ADMIN).configurerPool())
          .extracting(ApproverDirectory.Member::username)
          .containsExactly("owner_o");
    }

    @Test
    @DisplayName("a grant reaches its principal, and every member of a group, however deep")
    void grantReach() {
      UUID financeTeam =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery("SELECT id FROM principal WHERE username = 'Finance'")
                      .mapTo(UUID.class)
                      .one());
      UUID analystA =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery("SELECT id FROM principal WHERE username = 'analyst_a'")
                      .mapTo(UUID.class)
                      .one());

      assertThat(grants.reaches("finance_lead", financeTeam)).isTrue();
      assertThat(grants.reaches("FINANCE_LEAD", financeTeam)).isTrue();
      assertThat(grants.reaches("analyst_a", analystA)).isTrue();
      assertThat(grants.reaches("analyst_a", financeTeam)).isFalse();
      assertThat(grants.reaches("finance_lead", analystA)).isFalse();

      // A member of a group inside Finance is reached by a grant to Finance.
      jdbi.useHandle(
          handle -> {
            handle.execute(
                """
                INSERT INTO principal (principal_type, username, source, enabled)
                VALUES ('GROUP', 'Finance Ops', 'local', true)
                """);
            handle.execute(
                """
                INSERT INTO group_member (group_id, member_id, source)
                SELECT g.id, m.id, 'local' FROM principal g, principal m
                WHERE g.username = 'Finance' AND m.username = 'Finance Ops'
                """);
            handle.execute(
                """
                INSERT INTO group_member (group_id, member_id, source)
                SELECT g.id, m.id, 'local' FROM principal g, principal m
                WHERE g.username = 'Finance Ops' AND m.username = 'analyst_a'
                """);
          });
      assertThat(grants.reaches("analyst_a", financeTeam)).isTrue();
      assertThat(grants.reaches(null, financeTeam)).isFalse();
    }

    @Test
    @DisplayName("the inbox holds what this person may decide, and nothing else")
    void inbox() {
      AccessRequestStore.StoredRequest customer = ask("analyst_a", CUSTOMER, 7);
      AccessRequestStore.StoredRequest ledger = ask("analyst_a", LEDGER, 7);
      AccessRequestStore.StoredRequest orphan = ask("analyst_b", ORPHAN, null);

      assertThat(requests.decidableBy(OWNER, null, 100))
          .extracting(AccessRequestStore.StoredRequest::id)
          .containsExactly(customer.id());
      assertThat(requests.decidableBy(TEAM_MEMBER, null, 100))
          .extracting(AccessRequestStore.StoredRequest::id)
          .containsExactly(ledger.id());
      assertThat(requests.decidableBy(ADMIN, null, 100))
          .extracting(AccessRequestStore.StoredRequest::id)
          .containsExactlyInAnyOrder(customer.id(), ledger.id(), orphan.id());
      assertThat(requests.decidableBy(ADMIN, null, 100))
          .allSatisfy(r -> assertThat(r.mayDecide()).isTrue());

      requests.reject(customer.id(), OWNER, "Use the reporting view instead");
      assertThat(requests.decidableBy(OWNER, "PENDING", 100)).isEmpty();
      assertThat(requests.decidableBy(OWNER, "REJECTED", 100))
          .extracting(AccessRequestStore.StoredRequest::id)
          .containsExactly(customer.id());
      assertThatThrownBy(() -> requests.decidableBy(OWNER, "MAYBE", 100))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.INVALID));
    }

    @Test
    @DisplayName("a table with no owner recorded is decided by an administrator")
    void orphanGoesToAdmin() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", ORPHAN, null);
      assertThat(made.approvers()).isEmpty();

      assertThatThrownBy(() -> requests.approve(made.id(), OWNER, null, null))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.NOT_FOUND));

      AccessRequestStore.StoredRequest decided = approveAndGrant(made.id(), ADMIN, null);
      // Asked for "until revoked", granted as asked.
      assertThat(grants.find(decided.grantId()).orElseThrow().validUntil()).isNull();
      assertThat(read("analyst_a", ORPHAN)).isTrue();
    }

    @Test
    @DisplayName("whoever configures may shorten what was asked, never lengthen it")
    void shortenOnly() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      requests.approve(made.id(), OWNER, null, null);

      assertInvalid(() -> requests.complete(made.id(), OWNER, grantFor(30)));
      assertInvalid(() -> requests.complete(made.id(), OWNER, grantFor(0)));
      // Still waiting after both refusals, and nothing granted.
      assertThat(requests.find(made.id(), OWNER).status()).isEqualTo("APPROVED");
      assertThat(grants.onAsset(CUSTOMER)).isEmpty();

      AccessRequestStore.StoredRequest decided = requests.complete(made.id(), OWNER, grantFor(2));
      GrantStore.StoredGrant grant = grants.find(decided.grantId()).orElseThrow();
      assertThat(Duration.between(grant.validFrom(), grant.validUntil())).isEqualTo(Duration.ofDays(2));
    }

    @Test
    @DisplayName("an open-ended ask may be given a window")
    void boundAnOpenAsk() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", ORPHAN, null);
      AccessRequestStore.StoredRequest decided = approveAndGrant(made.id(), ADMIN, 14);
      GrantStore.StoredGrant grant = grants.find(decided.grantId()).orElseThrow();
      assertThat(Duration.between(grant.validFrom(), grant.validUntil())).isEqualTo(Duration.ofDays(14));
    }

    @Test
    @DisplayName("the second answer is told the first one won")
    void decidedOnce() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      requests.approve(made.id(), OWNER, null, null);

      assertThatThrownBy(() -> requests.approve(made.id(), ADMIN, null, null))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT))
          .hasMessageContaining("owner_o");
      assertThatThrownBy(() -> requests.reject(made.id(), ADMIN, "Too late"))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT));

      requests.complete(made.id(), OWNER, grantFor(null));
      assertThatThrownBy(() -> requests.complete(made.id(), ADMIN, grantFor(null)))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT))
          .hasMessageContaining("configured by owner_o");
      assertThatThrownBy(() -> requests.withdraw(made.id(), ANALYST_A))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT));
      // One grant, however many people pressed the button.
      assertThat(grants.onAsset(CUSTOMER)).hasSize(1);
    }

    @Test
    @DisplayName("a no must say why, and the requester reads it")
    void rejectNeedsANote() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);

      assertInvalid(() -> requests.reject(made.id(), OWNER, "  "));
      AccessRequestStore.StoredRequest decided =
          requests.reject(made.id(), OWNER, "Use the reporting view instead");

      assertThat(decided.status()).isEqualTo("REJECTED");
      assertThat(decided.grantId()).isNull();
      assertThat(requests.madeBy(ANALYST_A, 10))
          .singleElement()
          .satisfies(r -> assertThat(r.decisionNote()).isEqualTo("Use the reporting view instead"));
      assertThat(read("analyst_a", CUSTOMER)).isFalse();
      assertThat(trail(made.id())).containsExactly("REJECT", "VOTE", "REQUEST");
    }

    @Test
    @DisplayName("only the requester takes a request back, and only while it is open")
    void withdraw() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);

      assertThatThrownBy(() -> requests.withdraw(made.id(), ANALYST_B))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.NOT_FOUND));
      // Not even the owner withdraws it for them; the owner's word is reject.
      assertThatThrownBy(() -> requests.withdraw(made.id(), OWNER))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.NOT_FOUND));

      AccessRequestStore.StoredRequest gone = requests.withdraw(made.id(), ANALYST_A);
      assertThat(gone.status()).isEqualTo("WITHDRAWN");
      assertThat(gone.decidedBy()).isEqualTo("analyst_a");
      assertThat(requests.decidableBy(OWNER, "PENDING", 100)).isEmpty();
      assertThat(trail(made.id())).containsExactly("WITHDRAW", "REQUEST");
    }
  }

  // ------------------------------------------------------------- the bell

  @Nested
  @DisplayName("notices")
  class Notices {

    @Test
    @DisplayName("an owner hears about asks for tables they decide, and only those")
    void ownerHearsAsks() {
      AccessRequestStore.StoredRequest customer = ask("analyst_a", CUSTOMER, 7);
      ask("analyst_a", LEDGER, 7);
      ask("analyst_b", ORPHAN, null);

      AccessRequestStore.Notices owner = requests.notices(OWNER, 20);
      assertThat(owner.items())
          .extracting(AccessRequestStore.Notice::requestId)
          .containsExactly(customer.id());
      assertThat(owner.items().get(0).kind()).isEqualTo("REQUESTED");
      assertThat(owner.items().get(0).side()).isEqualTo("INBOX");
      assertThat(owner.items().get(0).actor()).isEqualTo("analyst_a");
      assertThat(owner.unseen()).isEqualTo(1);
      assertThat(owner.inboxPending()).isEqualTo(1);
      assertThat(owner.minePending()).isZero();

      // Through the team, the ledger only.
      assertThat(requests.notices(TEAM_MEMBER, 20).items())
          .extracting(AccessRequestStore.Notice::assetFqn)
          .containsExactly(LEDGER);
      // An administrator decides everything, so hears everything.
      AccessRequestStore.Notices admin = requests.notices(ADMIN, 20);
      assertThat(admin.items()).hasSize(3);
      assertThat(admin.inboxPending()).isEqualTo(3);
      // A stranger hears nothing, and learns nothing about who asked for what.
      assertThat(requests.notices(ANALYST_B, 20).items())
          .extracting(AccessRequestStore.Notice::assetFqn)
          .doesNotContain(CUSTOMER, LEDGER);
    }

    @Test
    @DisplayName("a requester hears the answer, not their own ask")
    void requesterHearsAnswers() {
      AccessRequestStore.StoredRequest approved = ask("analyst_a", CUSTOMER, 7);
      AccessRequestStore.StoredRequest rejected = ask("analyst_a", LEDGER, 7);
      ask("analyst_a", ORPHAN, null);

      AccessRequestStore.Notices before = requests.notices(ANALYST_A, 20);
      assertThat(before.items()).isEmpty();
      assertThat(before.minePending()).isEqualTo(3);
      assertThat(before.inboxPending()).isZero();

      requests.approve(approved.id(), OWNER, null, null);
      requests.reject(rejected.id(), TEAM_MEMBER, "Use the reporting view");

      AccessRequestStore.Notices after = requests.notices(ANALYST_A, 20);
      assertThat(after.items())
          .extracting(AccessRequestStore.Notice::kind)
          .containsExactly("REJECTED", "APPROVED");
      assertThat(after.items()).allSatisfy(n -> assertThat(n.side()).isEqualTo("MINE"));
      assertThat(after.items().get(0).note()).isEqualTo("Use the reporting view");
      assertThat(after.items().get(0).actor()).isEqualTo("finance_lead");
      assertThat(after.unseen()).isEqualTo(2);
      // Approved is still open until somebody configures it.
      assertThat(after.minePending()).isEqualTo(2);

      // The owner does not hear that they approved -- only that it now waits
      // for them to configure it.
      AccessRequestStore.Notices owner = requests.notices(OWNER, 20);
      assertThat(owner.items())
          .extracting(AccessRequestStore.Notice::kind)
          .containsExactly("TO_CONFIGURE", "REQUESTED");
      assertThat(owner.inboxPending()).isEqualTo(1);

      requests.complete(approved.id(), OWNER, grantFor(null));
      assertThat(requests.notices(ANALYST_A, 20).items())
          .extracting(AccessRequestStore.Notice::kind)
          .containsExactly("COMPLETED", "REJECTED", "APPROVED");
      assertThat(requests.notices(ANALYST_A, 20).minePending()).isEqualTo(1);
      assertThat(requests.notices(OWNER, 20).inboxPending()).isZero();
    }

    @Test
    @DisplayName("a withdrawn ask is heard by whoever decides it, and leaves the pending count")
    void withdrawn() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      requests.withdraw(made.id(), ANALYST_A);

      AccessRequestStore.Notices owner = requests.notices(OWNER, 20);
      assertThat(owner.items())
          .extracting(AccessRequestStore.Notice::kind)
          .containsExactly("WITHDRAWN", "REQUESTED");
      assertThat(owner.inboxPending()).isZero();
      // Taking one's own request back is not news to oneself.
      assertThat(requests.notices(ANALYST_A, 20).items()).isEmpty();
    }

    @Test
    @DisplayName("marking seen stops the count, keeps the history, and is per person")
    void seen() {
      ask("analyst_a", CUSTOMER, 7);
      ask("analyst_b", ORPHAN, null);
      assertThat(requests.notices(OWNER, 20).unseen()).isEqualTo(1);

      requests.markNoticesSeen(OWNER);
      AccessRequestStore.Notices read = requests.notices(OWNER, 20);
      assertThat(read.unseen()).isZero();
      assertThat(read.seenAt()).isNotNull();
      assertThat(read.items()).hasSize(1).allSatisfy(n -> assertThat(n.unseen()).isFalse());
      // Somebody else's bell is untouched.
      assertThat(requests.notices(ADMIN, 20).unseen()).isEqualTo(2);

      // Marking twice is harmless, and the name is matched without case.
      requests.markNoticesSeen(new AccessRequestStore.Actor("OWNER_O", false));
      assertThat(requests.notices(OWNER, 20).unseen()).isZero();

      // Something new after reading counts again.
      ask("analyst_b", CUSTOMER, 3);
      assertThat(requests.notices(OWNER, 20).unseen()).isEqualTo(1);
    }

    @Test
    @DisplayName("the list is capped, the count is not")
    void capped() {
      ask("analyst_a", CUSTOMER, 7);
      ask("analyst_a", LEDGER, 7);
      ask("analyst_a", ORPHAN, null);
      AccessRequestStore.Notices one = requests.notices(ADMIN, 1);
      assertThat(one.items()).hasSize(1);
      assertThat(one.unseen()).isEqualTo(3);
      // Newest first.
      assertThat(one.items().get(0).assetFqn()).isEqualTo(ORPHAN);
    }
  }

  // ------------------------------------------------------------- workflows

  @Nested
  @DisplayName("workflows: steps one after another, stages side by side")
  class Workflows {

    @BeforeEach
    void securityDesk() {
      jdbi.useHandle(
          handle -> {
            person(handle, "sec_a", "L2");
            person(handle, "sec_b", "L2");
            person(handle, "sec_c", "L2");
          });
    }

    @Test
    @DisplayName("the next step opens only once this one passes, and asks only its own people")
    void sequence() {
      workflow(
          DBO,
          List.of(),
          stage(1, "Owner", Rule.ANY, null, OnReject.VETO, Seat.of(Kind.ASSET_OWNERS)),
          stage(2, "Security", Rule.ALL, null, OnReject.VETO, user("sec_a"), user("sec_b")));
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);

      assertThat(made.workflowName()).isEqualTo("Workflow on " + DBO);
      assertThat(made.currentStep()).isEqualTo(1);
      assertThat(made.stages()).extracting(AccessRequestStore.StageView::status)
          .containsExactly("OPEN", "WAITING");
      // Who a later step asks is settled when it opens, not before.
      assertThat(made.stages().get(1).pool()).isEmpty();
      // Until then, security has nothing to see, answer or hear.
      assertThatThrownBy(() -> requests.approve(made.id(), SEC_A, null, null))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.NOT_FOUND));
      assertThatThrownBy(() -> requests.find(made.id(), SEC_A))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.NOT_FOUND));
      assertThat(requests.notices(SEC_A, 20).items()).isEmpty();

      AccessRequestStore.StoredRequest advanced = requests.approve(made.id(), OWNER, null, null);
      assertThat(advanced.status()).isEqualTo("PENDING");
      assertThat(advanced.currentStep()).isEqualTo(2);
      assertThat(advanced.stages()).extracting(AccessRequestStore.StageView::status)
          .containsExactly("APPROVED", "OPEN");
      assertThat(advanced.stages().get(1).pool())
          .extracting(ApproverDirectory.Member::username)
          .containsExactly("sec_a", "sec_b");
      assertThat(advanced.stages().get(1).needed()).isEqualTo(2);

      // Security hears it now; the owner, who is not asked at step 2, does not.
      assertThat(requests.notices(SEC_A, 20).items())
          .extracting(AccessRequestStore.Notice::kind)
          .containsExactly("ADVANCED");
      assertThat(requests.notices(OWNER, 20).items())
          .extracting(AccessRequestStore.Notice::kind)
          .containsExactly("REQUESTED");
      assertThat(requests.notices(SEC_A, 20).inboxPending()).isEqualTo(1);
      assertThat(requests.notices(OWNER, 20).inboxPending()).isZero();
      // The owner's part is over.
      assertThatThrownBy(() -> requests.approve(made.id(), OWNER, null, null))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.FORBIDDEN))
          .hasMessageContaining("Security");

      AccessRequestStore.StoredRequest half = requests.approve(made.id(), SEC_A, "Fine by me", null);
      assertThat(half.status()).isEqualTo("PENDING");
      assertThat(half.stages().get(1).approvals()).isEqualTo(1);
      assertThatThrownBy(() -> requests.approve(made.id(), SEC_A, null, null))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT))
          .hasMessageContaining("You already answered Security");

      AccessRequestStore.StoredRequest done = requests.approve(made.id(), SEC_B, null, null);
      assertThat(done.status()).isEqualTo("APPROVED");
      assertThat(done.decidedBy()).isEqualTo("sec_b");
      assertThat(done.currentStep()).isNull();
      assertThat(done.stages()).extracting(AccessRequestStore.StageView::status)
          .containsExactly("APPROVED", "APPROVED");
      assertThat(trail(made.id()))
          .containsExactly("APPROVE", "VOTE", "VOTE", "ADVANCE", "VOTE", "REQUEST");
      // Configured by the default configurers: the table's owners.
      assertThat(requests.find(made.id(), OWNER).mayConfigure()).isTrue();
      assertThat(requests.find(made.id(), SEC_A).mayConfigure()).isFalse();
      assertThat(read("analyst_a", CUSTOMER)).isFalse();
    }

    @Test
    @DisplayName("stages of one step run side by side, and all of them must pass")
    void parallel() {
      workflow(
          DBO,
          List.of(),
          stage(1, "Owner", Rule.ANY, null, OnReject.VETO, Seat.of(Kind.ASSET_OWNERS)),
          stage(1, "Security", Rule.ANY, null, OnReject.VETO, user("sec_a")));
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      assertThat(made.stages()).extracting(AccessRequestStore.StageView::status)
          .containsExactly("OPEN", "OPEN");
      // Both are asked now, so both hear it now.
      assertThat(requests.notices(SEC_A, 20).items())
          .extracting(AccessRequestStore.Notice::kind)
          .containsExactly("REQUESTED");

      AccessRequestStore.StoredRequest one = requests.approve(made.id(), OWNER, null, null);
      assertThat(one.status()).isEqualTo("PENDING");
      assertThat(one.stages()).extracting(AccessRequestStore.StageView::status)
          .containsExactly("APPROVED", "OPEN");

      assertThat(requests.approve(made.id(), SEC_A, null, null).status()).isEqualTo("APPROVED");
      assertThat(trail(made.id())).containsExactly("APPROVE", "VOTE", "VOTE", "REQUEST");
    }

    @Test
    @DisplayName("stages of one step side by side, any one enough: the first to pass moves it on")
    void parallelAnyOne() {
      workflow(
          DBO,
          List.of(),
          new Stage(1, "Owner", Rule.ANY, null, OnReject.VETO, List.of(Seat.of(Kind.ASSET_OWNERS)), Join.ANY),
          new Stage(1, "Security", Rule.ANY, null, OnReject.VETO, List.of(user("sec_a")), Join.ANY),
          stage(2, "Privacy", Rule.ANY, null, OnReject.VETO, user("sec_b")));

      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      assertThat(made.stages()).extracting(AccessRequestStore.StageView::join)
          .containsExactly("ANY", "ANY", "ALL");
      assertThat(made.stages()).extracting(AccessRequestStore.StageView::status)
          .containsExactly("OPEN", "OPEN", "WAITING");

      // Security says yes: the step passes without the owner, whose stage closes.
      AccessRequestStore.StoredRequest moved = requests.approve(made.id(), SEC_A, null, null);
      assertThat(moved.status()).isEqualTo("PENDING");
      assertThat(moved.currentStep()).isEqualTo(2);
      assertThat(moved.stages()).extracting(AccessRequestStore.StageView::status)
          .containsExactly("CLOSED", "APPROVED", "OPEN");
      assertThatThrownBy(() -> requests.approve(made.id(), OWNER, null, 0))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT));
      assertThat(requests.approve(made.id(), SEC_B, null, null).status()).isEqualTo("APPROVED");

      // A no in one stage waits for the other; only both failing rejects.
      AccessRequestStore.StoredRequest other = ask("analyst_b", CUSTOMER, 7);
      AccessRequestStore.StoredRequest one = requests.reject(other.id(), OWNER, "Not from me");
      assertThat(one.status()).isEqualTo("PENDING");
      assertThat(one.stages()).extracting(AccessRequestStore.StageView::status)
          .containsExactly("REJECTED", "OPEN", "WAITING");
      AccessRequestStore.StoredRequest both = requests.reject(other.id(), SEC_A, "Nor me");
      assertThat(both.status()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("somebody asked in two stages answers both at once, or one by name")
    void twoSeats() {
      workflow(
          DBO,
          List.of(),
          stage(1, "Owner", Rule.ANY, null, OnReject.VETO, Seat.of(Kind.ASSET_OWNERS)),
          stage(1, "Custodian", Rule.ANY, null, OnReject.VETO, user("owner_o")));

      AccessRequestStore.StoredRequest both = ask("analyst_a", CUSTOMER, 7);
      AccessRequestStore.StoredRequest answered = requests.approve(both.id(), OWNER, null, null);
      assertThat(answered.status()).isEqualTo("APPROVED");
      assertThat(answered.stages()).allSatisfy(s -> assertThat(s.votes()).hasSize(1));

      AccessRequestStore.StoredRequest byName = ask("analyst_b", CUSTOMER, 7);
      assertThatThrownBy(() -> requests.approve(byName.id(), OWNER, null, 9))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.INVALID));
      AccessRequestStore.StoredRequest first = requests.approve(byName.id(), OWNER, null, 0);
      assertThat(first.status()).isEqualTo("PENDING");
      assertThat(first.stages()).extracting(AccessRequestStore.StageView::status)
          .containsExactly("APPROVED", "OPEN");
      assertThat(first.mayDecide()).isTrue();
      // The stage already passed is not waiting for anybody.
      assertThatThrownBy(() -> requests.approve(byName.id(), OWNER, null, 0))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT))
          .hasMessageContaining("not waiting");
      assertThat(requests.approve(byName.id(), OWNER, null, 1).status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("any one, quorum: a no waits for the others, and fails only when nobody is left")
    void anyQuorum() {
      workflow(DBO, List.of(), stage(1, "Security", Rule.ANY, null, OnReject.QUORUM, user("sec_a"), user("sec_b")));

      AccessRequestStore.StoredRequest carried = ask("analyst_a", CUSTOMER, 7);
      AccessRequestStore.StoredRequest after = requests.reject(carried.id(), SEC_A, "Not me");
      assertThat(after.status()).isEqualTo("PENDING");
      assertThat(after.stages().get(0).status()).isEqualTo("OPEN");
      assertThat(after.stages().get(0).rejections()).isEqualTo(1);
      assertThat(requests.approve(carried.id(), SEC_B, null, null).status()).isEqualTo("APPROVED");

      AccessRequestStore.StoredRequest failed = ask("analyst_b", CUSTOMER, 7);
      requests.reject(failed.id(), SEC_A, "Not me");
      AccessRequestStore.StoredRequest no = requests.reject(failed.id(), SEC_B, "Nor me");
      assertThat(no.status()).isEqualTo("REJECTED");
      assertThat(no.decidedBy()).isEqualTo("sec_b");
      assertThat(no.decisionNote()).isEqualTo("Nor me");
      assertThat(no.stages().get(0).status()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("any one, veto: one no fails the request at once, and closes what was waiting")
    void anyVeto() {
      workflow(
          DBO,
          List.of(),
          stage(1, "Security", Rule.ANY, null, OnReject.VETO, user("sec_a"), user("sec_b")),
          stage(2, "Owner", Rule.ANY, null, OnReject.VETO, Seat.of(Kind.ASSET_OWNERS)));
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);

      AccessRequestStore.StoredRequest no = requests.reject(made.id(), SEC_A, "Not this quarter");
      assertThat(no.status()).isEqualTo("REJECTED");
      assertThat(no.currentStep()).isNull();
      assertThat(no.stages()).extracting(AccessRequestStore.StageView::status)
          .containsExactly("REJECTED", "CLOSED");
      assertThatThrownBy(() -> requests.approve(made.id(), SEC_B, null, null))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT))
          .hasMessageContaining("rejected by sec_a");
      assertThat(trail(made.id())).containsExactly("REJECT", "VOTE", "REQUEST");
      // The requester hears the no.
      assertThat(requests.notices(ANALYST_A, 20).items())
          .extracting(AccessRequestStore.Notice::kind)
          .containsExactly("REJECTED");
    }

    @Test
    @DisplayName("any one, first answer decides: whichever way it goes")
    void firstResponse() {
      workflow(DBO, List.of(), stage(1, "Security", Rule.ANY, null, OnReject.FIRST_RESPONSE, user("sec_a"), user("sec_b")));

      AccessRequestStore.StoredRequest yes = ask("analyst_a", CUSTOMER, 7);
      assertThat(requests.approve(yes.id(), SEC_B, null, null).status()).isEqualTo("APPROVED");

      AccessRequestStore.StoredRequest no = ask("analyst_b", CUSTOMER, 7);
      assertThat(requests.reject(no.id(), SEC_B, "No").status()).isEqualTo("REJECTED");
      assertThatThrownBy(() -> requests.approve(no.id(), SEC_A, null, null))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT));
    }

    @Test
    @DisplayName("at least n, quorum: carried by enough yeses, failed once they cannot come")
    void atLeastQuorum() {
      workflow(
          DBO,
          List.of(),
          stage(1, "Panel", Rule.AT_LEAST, 2, OnReject.QUORUM, user("sec_a"), user("sec_b"), user("sec_c")));

      AccessRequestStore.StoredRequest carried = ask("analyst_a", CUSTOMER, 7);
      requests.reject(carried.id(), SEC_A, "Not me");
      assertThat(requests.approve(carried.id(), SEC_B, null, null).status()).isEqualTo("PENDING");
      AccessRequestStore.StoredRequest yes = requests.approve(carried.id(), SEC_C, null, null);
      assertThat(yes.status()).isEqualTo("APPROVED");
      assertThat(yes.stages().get(0).approvals()).isEqualTo(2);
      assertThat(yes.stages().get(0).rejections()).isEqualTo(1);
      assertThat(yes.stages().get(0).needed()).isEqualTo(2);

      AccessRequestStore.StoredRequest failed = ask("analyst_b", CUSTOMER, 7);
      assertThat(requests.reject(failed.id(), SEC_A, "No").status()).isEqualTo("PENDING");
      // Two noes of three: two yeses can no longer come.
      assertThat(requests.reject(failed.id(), SEC_B, "No").status()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("at least n, veto: one no fails it even when enough yeses could still come")
    void atLeastVeto() {
      workflow(
          DBO,
          List.of(),
          stage(1, "Panel", Rule.AT_LEAST, 2, OnReject.VETO, user("sec_a"), user("sec_b"), user("sec_c")));
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      requests.approve(made.id(), SEC_A, null, null);
      assertThat(requests.reject(made.id(), SEC_B, "No").status()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("everyone: every yes is needed, so one no fails it whatever else was chosen")
    void allRejects() {
      workflow(DBO, List.of(), stage(1, "Everyone", Rule.ALL, null, OnReject.QUORUM, user("sec_a"), user("sec_b")));
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      requests.approve(made.id(), SEC_A, null, null);
      assertThat(requests.reject(made.id(), SEC_B, "No").status()).isEqualTo("REJECTED");

      AccessRequestStore.StoredRequest early = ask("analyst_b", CUSTOMER, 7);
      assertThat(requests.reject(early.id(), SEC_A, "No").status()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("an administrator may answer for any stage, and the answer says it was for the approvers")
    void adminOverride() {
      workflow(
          DBO,
          List.of(),
          stage(1, "Everyone", Rule.ALL, null, OnReject.VETO, user("sec_a"), user("sec_b")),
          stage(2, "Owner", Rule.ANY, null, OnReject.VETO, Seat.of(Kind.ASSET_OWNERS)));
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      assertThat(requests.find(made.id(), ADMIN).mayDecide()).isTrue();

      AccessRequestStore.StoredRequest advanced = requests.approve(made.id(), ADMIN, null, null);
      // One administrator's yes stands for the whole stage, not one seat of it.
      assertThat(advanced.currentStep()).isEqualTo(2);
      assertThat(advanced.stages().get(0).votes())
          .singleElement()
          .satisfies(v -> assertThat(v.override()).isTrue());
      String note =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(
                          "SELECT note FROM audit_access_request WHERE request_id = :id AND action = 'VOTE'")
                      .bind("id", made.id())
                      .mapTo(String.class)
                      .one());
      assertThat(note).isEqualTo("Everyone: approved for the approvers");

      AccessRequestStore.StoredRequest no = requests.reject(made.id(), ADMIN, "Not now");
      assertThat(no.status()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("a seat that names nobody falls back to the administrators, and says so")
    void fallback() {
      workflow(DBO, List.of(), stage(1, "Security", Rule.ANY, null, OnReject.VETO, user("nobody_here")));

      AccessRequestStore.StoredRequest alone = ask("analyst_a", CUSTOMER, 7);
      assertThat(alone.stages().get(0).fallback()).isTrue();
      assertThat(alone.stages().get(0).pool()).isEmpty();
      assertThat(alone.stranded()).isTrue();

      administrator("admin");
      AccessRequestStore.StoredRequest made = ask("analyst_b", CUSTOMER, 7);
      AccessRequestStore.StageView stage = made.stages().get(0);
      assertThat(stage.fallback()).isTrue();
      assertThat(stage.pool())
          .singleElement()
          .satisfies(
              m -> {
                assertThat(m.username()).isEqualTo("admin");
                assertThat(m.via()).isEqualTo("Platform administrator");
              });
      assertThat(made.stranded()).isFalse();
      // Asked, so not an override.
      AccessRequestStore.StoredRequest yes = requests.approve(made.id(), ADMIN, null, null);
      assertThat(yes.stages().get(0).votes().get(0).override()).isFalse();
    }

    @Test
    @DisplayName("a stage that asks too few people for its rule is stranded until an administrator answers")
    void strandedRule() {
      workflow(DBO, List.of(), stage(1, "Panel", Rule.AT_LEAST, 3, OnReject.QUORUM, user("sec_a"), user("sec_b")));
      assertThat(requests.nobodyElseDecides("analyst_a", CUSTOMER)).isTrue();
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      assertThat(made.stranded()).isTrue();
      assertThat(made.stages().get(0).stranded()).isTrue();

      administrator("admin");
      assertThat(requests.nobodyElseDecides("analyst_a", CUSTOMER)).isFalse();
      assertThat(requests.find(made.id(), ANALYST_A).stranded()).isFalse();
      // Two yeses of the two asked are still not three.
      requests.approve(made.id(), SEC_A, null, null);
      assertThat(requests.approve(made.id(), SEC_B, null, null).status()).isEqualTo("PENDING");
      assertThat(requests.approve(made.id(), ADMIN, null, null).status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("every kind of seat resolves to the right people, never the requester")
    void seats() {
      jdbi.useHandle(
          handle -> {
            role(handle, "sec_a", "DATA_OWNER", DBO);
            role(handle, "sec_b", "DATA_OWNER", "prod-pg.OtherDB");
            role(handle, "sec_c", "DATA_OWNER", null);
            handle
                .createUpdate(
                    "UPDATE asset SET custom_properties = CAST(:p AS jsonb) WHERE fqn = :fqn AND is_current")
                .bind("p", "{\"DataSteward\": \"sec_b\", \"dataCustodian\": \"Finance\"}")
                .bind("fqn", CUSTOMER)
                .execute();
          });
      workflow(
          DBO,
          List.of(),
          stage(1, "Team", Rule.ANY, null, OnReject.VETO, new Seat(Kind.TEAM, "Finance")),
          stage(1, "Role", Rule.ANY, null, OnReject.VETO, new Seat(Kind.ROLE, "data_owner")),
          stage(1, "Steward", Rule.ANY, null, OnReject.VETO, Seat.of(Kind.DATA_STEWARD)),
          stage(1, "Custodian", Rule.ANY, null, OnReject.VETO, Seat.of(Kind.DATA_CUSTODIAN)),
          stage(1, "Self", Rule.ALL, null, OnReject.VETO, user("analyst_a"), user("sec_a")));
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);

      assertThat(pool(made, "Team")).containsExactly("finance_lead|Team Finance");
      // Scoped to the schema, or everywhere; not to another database.
      assertThat(pool(made, "Role")).containsExactly("sec_a|Role Data owner", "sec_c|Role Data owner");
      // The property's name is matched without case.
      assertThat(pool(made, "Steward")).containsExactly("sec_b|Data steward");
      // A bare name that is no person is a team.
      assertThat(pool(made, "Custodian")).containsExactly("finance_lead|Data custodian (team Finance)");
      // Nobody is asked about their own request, so "everyone" is everyone else.
      assertThat(pool(made, "Self")).containsExactly("sec_a|sec_a");
      assertThat(made.stages()).allSatisfy(s -> assertThat(s.fallback()).isFalse());
    }

    @Test
    @DisplayName("a workflow lists the requests that walked it, and where each got to")
    void executions() {
      // Asked before any workflow existed, so it walked the built-in one.
      AccessRequestStore.StoredRequest early = ask("analyst_a", LEDGER, 7);
      WorkflowStore.Stored flow =
          workflow(
              DBO,
              List.of(),
              stage(1, "Owner", Rule.ANY, null, OnReject.VETO, Seat.of(Kind.ASSET_OWNERS)),
              stage(2, "Security", Rule.ANY, null, OnReject.VETO, user("sec_a")));
      UUID id = flow.workflow().id();
      AccessRequestStore.StoredRequest refused = ask("analyst_a", CUSTOMER, 7);
      AccessRequestStore.StoredRequest waiting = ask("analyst_b", CUSTOMER, 7);
      requests.reject(refused.id(), OWNER, "Not this quarter");
      requests.approve(waiting.id(), OWNER, null, null);

      WorkflowStore.Executions all = workflows.executions(id, null, 50, 0);
      assertThat(all.total()).isEqualTo(2);
      assertThat(all.counts()).containsExactly(Map.entry("PENDING", 1), Map.entry("REJECTED", 1));
      assertThat(all.executions()).extracting(WorkflowStore.Execution::ticket)
          .containsExactly(waiting.ticket(), refused.ticket());
      WorkflowStore.Execution open = all.executions().get(0);
      assertThat(open.ticket()).startsWith("REQ-");
      assertThat(open.assetFqn()).isEqualTo(CUSTOMER);
      assertThat(open.status()).isEqualTo("PENDING");
      assertThat(open.currentStep()).isEqualTo(2);
      assertThat(open.openStages()).containsExactly("Security");
      assertThat(open.steps()).isEqualTo(2);
      assertThat(open.closedAt()).isNull();
      WorkflowStore.Execution closed = all.executions().get(1);
      assertThat(closed.status()).isEqualTo("REJECTED");
      assertThat(closed.openStages()).isEmpty();
      assertThat(closed.closedAt()).isNotNull();

      WorkflowStore.Executions rejected = workflows.executions(id, "REJECTED", 50, 0);
      assertThat(rejected.total()).isEqualTo(1);
      assertThat(rejected.executions()).extracting(WorkflowStore.Execution::ticket)
          .containsExactly(refused.ticket());
      WorkflowStore.Executions page = workflows.executions(id, null, 1, 1);
      assertThat(page.total()).isEqualTo(2);
      assertThat(page.executions()).extracting(WorkflowStore.Execution::ticket)
          .containsExactly(refused.ticket());

      assertThat(workflows.executions(null, null, 50, 0).executions())
          .extracting(WorkflowStore.Execution::ticket)
          .containsExactly(early.ticket());
      assertThat(workflows.executions(UUID.randomUUID(), null, 50, 0).total()).isZero();
    }

    @Test
    @DisplayName("a request keeps the workflow it started with; editing or deleting it changes new requests only")
    void keepsItsCopy() {
      WorkflowStore.Stored first =
          workflow(DBO, List.of(), stage(1, "Security", Rule.ANY, null, OnReject.VETO, user("sec_a")));
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);

      workflows.update(
          first.workflow().id(),
          AccessWorkflow.validate(
              new Draft(
                  "Stricter", null, DBO, true,
                  List.of(stage(1, "Panel", Rule.ALL, null, OnReject.VETO, user("sec_b"), user("sec_c"))),
                  List.of())),
          "admin");
      assertThat(requests.find(made.id(), ANALYST_A).stages())
          .extracting(AccessRequestStore.StageView::name)
          .containsExactly("Security");
      assertThat(ask("analyst_b", CUSTOMER, 7).stages())
          .extracting(AccessRequestStore.StageView::name)
          .containsExactly("Panel");

      workflows.delete(first.workflow().id(), "admin");
      AccessRequestStore.StoredRequest kept = requests.find(made.id(), ANALYST_A);
      assertThat(kept.workflowName()).isEqualTo("Workflow on " + DBO);
      assertThat(requests.approve(made.id(), SEC_A, null, null).status()).isEqualTo("APPROVED");
      assertThat(workflows.history(first.workflow().id()))
          .extracting(row -> row.get("action"))
          .containsExactly("DELETE", "UPDATE", "CREATE");
      // With nothing configured any more, the built-in one again.
      assertThat(ask("analyst_a", LEDGER, 7).workflowName()).isEqualTo("Built-in");
    }

    @Test
    @DisplayName("the deepest enabled workflow wins; one per scope, the default included")
    void scopes() {
      workflow(null, List.of(), stage(1, "Org", Rule.ANY, null, OnReject.VETO, user("sec_a")));
      workflow(DBO, List.of(), stage(1, "Schema", Rule.ANY, null, OnReject.VETO, user("sec_a")));
      WorkflowStore.Stored table =
          workflows.create(
              AccessWorkflow.validate(
                  new Draft(
                      "Table", null, CUSTOMER, false,
                      List.of(stage(1, "Table", Rule.ANY, null, OnReject.VETO, user("sec_a"))),
                      List.of())),
              "admin");

      assertThat(workflows.effective(CUSTOMER).name()).isEqualTo("Workflow on " + DBO);
      assertThat(workflows.effective(LEDGER).name()).isEqualTo("Workflow on " + DBO);
      // Matched by segment: "customer_x" is not under "customer", "dbo2" not under "dbo".
      assertThat(workflows.effective(DBO + ".customer_x").name()).isEqualTo("Workflow on " + DBO);
      assertThat(workflows.effective(SALES + ".dbo2.t").name()).isEqualTo("Workflow on the organisation");

      workflows.update(
          table.workflow().id(),
          AccessWorkflow.validate(
              new Draft("Table", null, CUSTOMER, true, table.workflow().stages(), List.of())),
          "admin");
      assertThat(workflows.effective(CUSTOMER).name()).isEqualTo("Table");
      assertThat(requests.route(CUSTOMER).stages())
          .singleElement()
          .satisfies(s -> assertThat(s.approvers()).containsExactly("sec_a"));

      assertThatThrownBy(() -> workflow(DBO, List.of(), stage(1, "Again", Rule.ANY, null, OnReject.VETO, user("sec_b"))))
          .isInstanceOf(WorkflowStore.ScopeTakenException.class)
          .hasMessageContaining(DBO);
      assertThatThrownBy(() -> workflow(null, List.of(), stage(1, "Again", Rule.ANY, null, OnReject.VETO, user("sec_b"))))
          .isInstanceOf(WorkflowStore.ScopeTakenException.class)
          .hasMessageContaining("organisation-wide");
      assertThat(workflows.list())
          .extracting(s -> s.workflow().scopeFqn())
          .containsExactly(null, DBO, CUSTOMER);
    }

    @Test
    @DisplayName("a DENY is not something any number of approvals can answer")
    void denyAfterSteps() {
      workflow(
          DBO,
          List.of(),
          stage(1, "Owner", Rule.ANY, null, OnReject.VETO, Seat.of(Kind.ASSET_OWNERS)),
          stage(2, "Security", Rule.ANY, null, OnReject.VETO, user("sec_a")));
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      requests.approve(made.id(), OWNER, null, null);
      activate(orgDeny("no-l1", "L1"));

      // The approvers may still say yes, and the grant is still written, but it
      // composes like every other grant and loses.
      requests.approve(made.id(), SEC_A, null, null);
      requests.complete(made.id(), OWNER, grantFor(null));
      assertThat(read("analyst_a", CUSTOMER)).isFalse();
      AccessEligibility.Verdict verdict = eligibility.check("analyst_a", CUSTOMER, null, null);
      assertThat(verdict.requestable()).isFalse();
      assertThat(verdict.blockedBy()).startsWith("no-l1");
    }

    @Test
    @DisplayName("a gate that refuses the person: the route is still shown, because the request still goes")
    void gateKeepsTheRoute() {
      workflow(DBO, List.of(), stage(1, "Security", Rule.ANY, null, OnReject.VETO, user("sec_a")));
      activate(orgAllow("l2-only", "L2"));

      AccessEligibility.Verdict verdict = eligibility.check("analyst_a", CUSTOMER, null, null);
      assertThat(verdict.requestable()).isFalse();
      assertThat(verdict.blockedKind()).isEqualTo(AccessEligibility.NOT_ADMITTED);
      // A grant alone would not do, but the request is sent all the same, so
      // the form says where it goes.
      assertThat(verdict.route().workflowName()).isEqualTo("Workflow on " + DBO);
      // The ledger is outside the gate's selector: the workflow's route is shown.
      assertThat(eligibility.check("analyst_a", LEDGER, null, null).route().workflowName())
          .isEqualTo("Workflow on " + DBO);
    }
  }

  // ------------------------------------------------------------- configuring

  @Nested
  @DisplayName("configuring an approved request")
  class Configuring {

    @BeforeEach
    void configurer() {
      jdbi.useHandle(handle -> person(handle, "sec_c", "L2"));
    }

    @Test
    @DisplayName("the workflow's configurers take it, one at a time; an administrator may still finish it")
    void startAndFinish() {
      workflow(
          DBO,
          List.of(user("sec_c")),
          stage(1, "Owner", Rule.ANY, null, OnReject.VETO, Seat.of(Kind.ASSET_OWNERS)));
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      AccessRequestStore.StoredRequest approved = requests.approve(made.id(), OWNER, null, null);

      assertThat(approved.configurerPool())
          .extracting(ApproverDirectory.Member::username)
          .containsExactly("sec_c");
      assertThat(approved.mayConfigure()).isFalse();
      assertThatThrownBy(() -> requests.complete(made.id(), OWNER, grantFor(null)))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.FORBIDDEN))
          .hasMessageContaining("Configured by sec_c");
      assertThatThrownBy(() -> requests.start(made.id(), ANALYST_B))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.NOT_FOUND));
      assertThatThrownBy(() -> requests.start(made.id(), ANALYST_A))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.FORBIDDEN));
      // The configurer hears it waits for them; the owner is done.
      assertThat(requests.notices(SEC_C, 20).items())
          .extracting(AccessRequestStore.Notice::kind)
          .containsExactly("TO_CONFIGURE");
      assertThat(requests.notices(OWNER, 20).items())
          .extracting(AccessRequestStore.Notice::kind)
          .doesNotContain("TO_CONFIGURE");

      AccessRequestStore.StoredRequest taken = requests.start(made.id(), SEC_C);
      assertThat(taken.status()).isEqualTo("IN_PROGRESS");
      assertThat(taken.assignee()).isEqualTo("sec_c");
      assertThat(taken.assignedAt()).isNotNull();
      // Taking it again is harmless; somebody else taking it is not.
      assertThat(requests.start(made.id(), SEC_C).status()).isEqualTo("IN_PROGRESS");
      assertThatThrownBy(() -> requests.start(made.id(), ADMIN))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT))
          .hasMessageContaining("sec_c is already configuring");
      // Still open for the requester: it is not done until it is configured.
      assertThat(requests.openRequest(CUSTOMER, "analyst_a")).isPresent();

      AccessRequestStore.StoredRequest done = requests.complete(made.id(), ADMIN, grantFor(3));
      assertThat(done.status()).isEqualTo("COMPLETED");
      assertThat(done.completedBy()).isEqualTo("admin");
      assertThat(done.assignee()).isEqualTo("sec_c");
      assertThat(read("analyst_a", CUSTOMER)).isTrue();
      assertThat(trail(made.id())).containsExactly("COMPLETE", "START", "APPROVE", "VOTE", "REQUEST");
    }

    @Test
    @DisplayName("configured as a policy: points at it, never activates it, and needs a note")
    void policyFulfilment() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      Policy widen = orgAllow("widen", null);
      widen.setEnvironment(Policy.Environment.fromValue(DecisionService.DEFAULT_ENVIRONMENT));
      UUID policyId = policies.create(widen, "alice").id();

      // Not yet approved: nothing to configure.
      assertThatThrownBy(
              () ->
                  requests.complete(
                      made.id(),
                      OWNER,
                      new AccessRequestStore.Completion("POLICY_UPDATED", null, policyId.toString(), "Widened")))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT))
          .hasMessageContaining("still waiting");
      requests.approve(made.id(), OWNER, null, null);

      assertInvalid(() -> requests.complete(made.id(), OWNER, new AccessRequestStore.Completion("MAYBE", null, null, null)));
      assertInvalid(() -> requests.complete(made.id(), OWNER, null));
      assertInvalid(
          () ->
              requests.complete(
                  made.id(), OWNER,
                  new AccessRequestStore.Completion("POLICY_UPDATED", 7, policyId.toString(), "Widened")));
      assertInvalid(
          () ->
              requests.complete(
                  made.id(), OWNER,
                  new AccessRequestStore.Completion("POLICY_UPDATED", null, policyId.toString(), "  ")));
      assertInvalid(
          () ->
              requests.complete(
                  made.id(), OWNER, new AccessRequestStore.Completion("POLICY_UPDATED", null, "nope", "Widened")));
      assertThatThrownBy(
              () ->
                  requests.complete(
                      made.id(), OWNER,
                      new AccessRequestStore.Completion(
                          "POLICY_CREATED", null, UUID.randomUUID().toString(), "Wrote one")))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.INVALID))
          .hasMessageContaining("There is no policy");
      assertThat(requests.find(made.id(), OWNER).status()).isEqualTo("APPROVED");

      AccessRequestStore.StoredRequest done =
          requests.complete(
              made.id(), OWNER,
              new AccessRequestStore.Completion(
                  "policy_updated", null, policyId.toString(), "Widened the reader group"));
      assertThat(done.status()).isEqualTo("COMPLETED");
      assertThat(done.fulfilment()).isEqualTo("POLICY_UPDATED");
      assertThat(done.fulfilmentRef()).isEqualTo(policyId.toString());
      assertThat(done.fulfilmentNote()).isEqualTo("Widened the reader group");
      assertThat(done.grantId()).isNull();
      assertThat(grants.onAsset(CUSTOMER)).isEmpty();
      // Pointed at, not switched on: the draft is still a draft, and the table still closed.
      assertThat(policies.find(policyId).orElseThrow().lifecycleState()).isEqualTo("DRAFT");
      assertThat(read("analyst_a", CUSTOMER)).isFalse();
      assertThat(requests.notices(ANALYST_A, 20).items().get(0).kind()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("whoever configures may decline it, with a reason the requester reads")
    void decline() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      assertThatThrownBy(() -> requests.decline(made.id(), OWNER, "Retired"))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT));
      requests.approve(made.id(), OWNER, null, null);

      assertInvalid(() -> requests.decline(made.id(), OWNER, "  "));
      assertThatThrownBy(() -> requests.decline(made.id(), ANALYST_A, "Never mind"))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.FORBIDDEN));

      AccessRequestStore.StoredRequest no = requests.decline(made.id(), OWNER, "The table is being retired");
      assertThat(no.status()).isEqualTo("REJECTED");
      assertThat(no.completedBy()).isEqualTo("owner_o");
      assertThat(no.fulfilmentNote()).isEqualTo("The table is being retired");
      assertThat(no.fulfilment()).isNull();
      assertThat(grants.onAsset(CUSTOMER)).isEmpty();
      assertThat(requests.openRequest(CUSTOMER, "analyst_a")).isEmpty();
      assertThat(trail(made.id())).containsExactly("REJECT", "APPROVE", "VOTE", "REQUEST");
      assertThat(requests.notices(ANALYST_A, 20).items())
          .extracting(AccessRequestStore.Notice::kind)
          .containsExactly("REJECTED", "APPROVED");
      assertThatThrownBy(() -> requests.complete(made.id(), OWNER, grantFor(null)))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT));
    }

    @Test
    @DisplayName("the requester may take it back until it is configured")
    void withdrawBeforeConfigured() {
      AccessRequestStore.StoredRequest approved = ask("analyst_a", CUSTOMER, 7);
      requests.approve(approved.id(), OWNER, null, null);
      AccessRequestStore.StoredRequest gone = requests.withdraw(approved.id(), ANALYST_A);
      assertThat(gone.status()).isEqualTo("WITHDRAWN");
      assertThat(gone.completedBy()).isEqualTo("analyst_a");
      assertThatThrownBy(() -> requests.complete(approved.id(), OWNER, grantFor(null)))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT))
          .hasMessageContaining("withdrawn");

      AccessRequestStore.StoredRequest taken = ask("analyst_a", LEDGER, 7);
      requests.approve(taken.id(), TEAM_MEMBER, null, null);
      requests.start(taken.id(), TEAM_MEMBER);
      assertThat(requests.withdraw(taken.id(), ANALYST_A).status()).isEqualTo("WITHDRAWN");
      assertThat(grants.onAsset(LEDGER)).isEmpty();
      assertThat(requests.notices(TEAM_MEMBER, 20).items().get(0).kind()).isEqualTo("WITHDRAWN");
    }
  }

  // ------------------------------------------------------ before workflows

  @Nested
  @DisplayName("requests from before workflows")
  class Legacy {

    @Test
    @DisplayName("an open stage with nobody recorded is resolved when read, and kept once answered")
    void lazyPool() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      jdbi.useHandle(
          handle ->
              handle
                  .createUpdate("UPDATE access_request_stage SET pool = NULL WHERE request_id = :id")
                  .bind("id", made.id())
                  .execute());

      AccessRequestStore.StoredRequest seen = requests.find(made.id(), OWNER);
      assertThat(seen.stages().get(0).pool())
          .extracting(ApproverDirectory.Member::username)
          .containsExactly("owner_o");
      assertThat(seen.mayDecide()).isTrue();
      assertThat(requests.decidableBy(OWNER, null, 10)).hasSize(1);

      requests.approve(made.id(), OWNER, null, null);
      Boolean kept =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery("SELECT pool IS NOT NULL FROM access_request_stage WHERE request_id = :id")
                      .bind("id", made.id())
                      .mapTo(Boolean.class)
                      .one());
      assertThat(kept).isTrue();
    }

    @Test
    @DisplayName("an approved request with no configurers recorded is configured by the defaults")
    void lazyConfigurers() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      requests.approve(made.id(), OWNER, null, null);
      jdbi.useHandle(
          handle ->
              handle
                  .createUpdate("UPDATE access_request SET configurer_pool = NULL WHERE id = :id")
                  .bind("id", made.id())
                  .execute());

      AccessRequestStore.StoredRequest seen = requests.find(made.id(), OWNER);
      assertThat(seen.mayConfigure()).isTrue();
      assertThat(seen.configurerPool())
          .extracting(ApproverDirectory.Member::username)
          .containsExactly("owner_o");
      assertThat(requests.complete(made.id(), OWNER, grantFor(null)).status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a request decided before stages existed is still the owner's to read")
    void stageless() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      approveAndGrant(made.id(), ADMIN, null);
      jdbi.useHandle(
          handle ->
              handle
                  .createUpdate("DELETE FROM access_request_stage WHERE request_id = :id")
                  .bind("id", made.id())
                  .execute());

      AccessRequestStore.StoredRequest seen = requests.find(made.id(), OWNER);
      assertThat(seen.status()).isEqualTo("COMPLETED");
      assertThat(seen.stages()).isEmpty();
      assertThatThrownBy(() -> requests.find(made.id(), ANALYST_B))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.NOT_FOUND));
    }

    @Test
    @DisplayName("an owner added since does not hear about a table's old requests")
    void newOwnerHearsNothingOld() {
      AccessRequestStore.StoredRequest rejected = ask("analyst_a", CUSTOMER, 7);
      requests.reject(rejected.id(), OWNER, "Not this quarter");
      AccessRequestStore.StoredRequest withdrawn = ask("analyst_a", CUSTOMER, 3);
      requests.withdraw(withdrawn.id(), ANALYST_A);
      jdbi.useHandle(
          handle -> {
            handle.execute("DELETE FROM access_request_stage");
            handle.execute(
                """
                INSERT INTO asset_owner (target_fqn, owner_type, owner_name)
                VALUES ('prod-pg.SalesDB.dbo.customer', 'user', 'analyst_b')
                """);
          });

      // Today's owners still read them: that is what makes the history theirs.
      assertThat(requests.find(rejected.id(), ANALYST_B).status()).isEqualTo("REJECTED");
      // But the bell is news, and none of this is news to somebody who was not there.
      assertThat(requests.notices(ANALYST_B, 20).items()).isEmpty();
      assertThat(requests.notices(ANALYST_B, 20).unseen()).isZero();
      // The owner who decided keeps hearing that it was asked; nobody hears
      // the withdrawal of one nobody recorded answering.
      assertThat(requests.notices(OWNER, 20).items())
          .extracting(n -> n.kind() + " " + n.requestId())
          .containsExactly("REQUESTED " + rejected.id());
    }
  }

  // ---------------------------------------- whether asking would help at all

  @Nested
  @DisplayName("whether a yes would open the table (data policy conflicts)")
  class Eligibility {

    @Test
    @DisplayName("a table nothing speaks to: not readable, requestable, owners named")
    void unbound() {
      AccessEligibility.Verdict verdict = eligibility.check("analyst_a", LEDGER, null, null);

      assertThat(verdict.readable()).isFalse();
      assertThat(verdict.requestable()).isTrue();
      assertThat(verdict.blockedBy()).isNull();
      assertThat(verdict.approvers())
          .extracting(AccessRequestStore.Approver::name)
          .containsExactly("Finance");
      assertThat(verdict.openRequestId()).isNull();
      // The page shows the route before anybody asks: the built-in one here.
      assertThat(verdict.route().workflowName()).isEqualTo("Built-in");
      assertThat(verdict.route().stages())
          .singleElement()
          .satisfies(s -> assertThat(s.approvers()).containsExactly("Owners of the table"));

      AccessRequestStore.StoredRequest made = ask("analyst_a", LEDGER, 7);
      assertThat(eligibility.check("analyst_a", LEDGER, null, null).openRequestId())
          .isEqualTo(made.id().toString());
    }

    @Test
    @DisplayName("already readable: nothing to ask for")
    void readable() {
      activate(orgAllow("everyone", null));

      AccessEligibility.Verdict verdict = eligibility.check("analyst_a", CUSTOMER, null, null);
      assertThat(verdict.readable()).isTrue();
      assertThat(verdict.requestable()).isFalse();
      assertThat(eligibility.readable("analyst_a", CUSTOMER, null, null)).isTrue();
    }

    @Test
    @DisplayName("a higher layer that refuses this person: a yes would change nothing, and it says who")
    void refusingGate() {
      activate(orgAllow("l2-only", "L2"));

      AccessEligibility.Verdict a = eligibility.check("analyst_a", CUSTOMER, null, null);
      assertThat(a.readable()).isFalse();
      assertThat(a.requestable()).isFalse();
      assertThat(a.queryable()).isTrue();
      assertThat(a.blockedBy()).startsWith("l2-only");
      assertThat(a.blockedKind()).isEqualTo(AccessEligibility.NOT_ADMITTED);
      assertThat(a.blockedByPolicyId()).isEqualTo(policyNamed("l2-only"));
      assertThat(a.blockedByPolicy()).isEqualTo("l2-only");
      // The request still goes to the owner, so the route is shown.
      assertThat(a.route().workflowName()).isEqualTo("Built-in");
      // L2 satisfies the gate, but the gate only speaks for layers; with no
      // grant the TABLE layer is silent, so analyst_b is already in.
      assertThat(eligibility.check("analyst_b", CUSTOMER, null, null).readable()).isTrue();
    }

    @Test
    @DisplayName("a higher layer that consented to being relaxed: requestable, and the yes works")
    void overridableGate() {
      Policy gate = orgAllow("l2-only-overridable", "L2");
      gate.setAllowLocalOverride(true);
      activate(gate);

      AccessEligibility.Verdict verdict = eligibility.check("analyst_a", CUSTOMER, null, null);
      assertThat(verdict.readable()).isFalse();
      assertThat(verdict.requestable()).isTrue();

      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      approveAndGrant(made.id(), OWNER, null);
      assertThat(read("analyst_a", CUSTOMER)).isTrue();
    }

    @Test
    @DisplayName("a DENY: never requestable, and the DENY is what it names")
    void deny() {
      activate(orgAllow("everyone", null));
      activate(orgDeny("no-l1", "L1"));

      AccessEligibility.Verdict verdict = eligibility.check("analyst_a", CUSTOMER, null, null);
      assertThat(verdict.readable()).isFalse();
      assertThat(verdict.requestable()).isFalse();
      assertThat(verdict.blockedBy()).startsWith("no-l1");
      assertThat(verdict.blockedKind()).isEqualTo(AccessEligibility.DENIED);
      assertThat(verdict.blockedByPolicyId()).isEqualTo(policyNamed("no-l1"));
      // A matched DENY's own words only say that it matched; they are not a reason.
      assertThat(verdict.blockedByReason()).isNull();
      assertThat(eligibility.check("analyst_b", CUSTOMER, null, null).readable()).isTrue();
    }

    @Test
    @DisplayName("a DENY that arrives after the request: approving still does not open the table")
    void denyAfterAsking() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      activate(orgDeny("no-l1", "L1"));

      // The owner may still answer -- the workflow does not second-guess them --
      // but the grant they write composes like every other grant and loses.
      approveAndGrant(made.id(), OWNER, null);
      assertThat(read("analyst_a", CUSTOMER)).isFalse();
      assertThat(eligibility.check("analyst_a", CUSTOMER, null, null).blockedBy()).startsWith("no-l1");
    }

    @Test
    @DisplayName("an approved request opens the table and leaves its masks on")
    void approvalIsNotAnUnmask() {
      activate(orgMaskEmail("mask-pii"));
      assertThat(eligibility.check("analyst_a", CUSTOMER, null, null).requestable()).isTrue();

      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      approveAndGrant(made.id(), OWNER, null);

      PolicyDecision decision = decisions.decide(DecisionService.Ask.of("analyst_a", CUSTOMER));
      assertThat(decision.getAllowed()).isTrue();
      assertThat(decision.getColumnMasks())
          .singleElement()
          .satisfies(mask -> assertThat(mask.getColumn()).isEqualTo("email"));
    }

    @Test
    @DisplayName("asking about somebody the directory does not know is a no, not an error")
    void unknownPrincipal() {
      AccessEligibility.Verdict verdict = eligibility.check("ghost", LEDGER, null, null);
      assertThat(verdict.readable()).isFalse();
      assertThat(verdict.requestable()).isFalse();
    }
  }

  // ------------------------------------------------- a table not connected

  @Nested
  @DisplayName("a table no registered source maps (not connected)")
  class NotConnected {

    @Test
    @DisplayName("is neither readable nor requestable, whatever the policies say")
    void neither() {
      activate(orgAllow("everyone", null));
      assertThat(eligibility.check("analyst_a", CUSTOMER, null, null).readable()).isTrue();
      assertThat(eligibility.check("analyst_a", LEDGER, null, null).requestable()).isTrue();

      disconnect(CUSTOMER);
      disconnect(LEDGER);

      // The ALLOW still holds, but nothing reads the table, so nothing is open.
      AccessEligibility.Verdict customer = eligibility.check("analyst_a", CUSTOMER, null, null);
      assertThat(customer.queryable()).isFalse();
      assertThat(customer.readable()).isFalse();
      assertThat(customer.requestable()).isFalse();
      AccessEligibility.Verdict ledger = eligibility.check("analyst_a", LEDGER, null, null);
      assertThat(ledger.queryable()).isFalse();
      assertThat(ledger.requestable()).isFalse();
      // Nothing to ask for, so nobody to ask and no route; and no rule to name,
      // since none is what is in the way.
      assertThat(ledger.approvers()).isEmpty();
      assertThat(ledger.route()).isNull();
      assertThat(ledger.blockedBy()).isNull();
      assertThat(ledger.blockedKind()).isNull();
    }

    @Test
    @DisplayName("a disabled source disconnects every table it maps")
    void disabledSource() {
      jdbi.useHandle(handle -> handle.execute("UPDATE data_source SET enabled = false"));

      assertThat(eligibility.check("analyst_a", LEDGER, null, null).queryable()).isFalse();
      assertThat(eligibility.check("analyst_a", LEDGER, null, null).requestable()).isFalse();
    }

    @Test
    @DisplayName("a request for one is refused with 409, and nothing is written")
    void createRefused() {
      disconnect(LEDGER);

      assertThatThrownBy(() -> resource().create(askFor(LEDGER), as("analyst_a"), null))
          .isInstanceOfSatisfying(
              jakarta.ws.rs.WebApplicationException.class,
              e -> {
                assertThat(e.getResponse().getStatus()).isEqualTo(409);
                assertThat(e.getResponse().getEntity().toString())
                    .contains(LEDGER + " is not connected", "The request was not sent");
              });
      assertThat(requests.madeBy(ANALYST_A, 10)).isEmpty();
    }

    @Test
    @DisplayName("a request already waiting from before is still the requester's to see")
    void waitingRequestStays() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", LEDGER, 7);
      disconnect(LEDGER);

      AccessEligibility.Verdict verdict = eligibility.check("analyst_a", LEDGER, null, null);
      assertThat(verdict.queryable()).isFalse();
      assertThat(verdict.openRequestId()).isEqualTo(made.id().toString());
    }
  }

  // ------------------------------------------------------- many at once

  @Nested
  @DisplayName("a page of catalog rows at once")
  class Briefs {

    @Test
    @DisplayName("says of every row what the single answer says, in the order asked, once each")
    void agreesWithTheSingleAnswer() {
      activate(orgAllow("l2-only", "L2"));
      AccessRequestStore.StoredRequest waiting = ask("analyst_a", LEDGER, 7);
      disconnect(ORPHAN);
      String missing = DBO + ".nope";

      List<String> asked = List.of(CUSTOMER, LEDGER, ORPHAN, missing, CUSTOMER);
      List<AccessEligibility.Brief> briefs = eligibility.briefs("analyst_a", asked, null, null);

      assertThat(briefs)
          .extracting(AccessEligibility.Brief::assetFqn)
          .containsExactly(CUSTOMER, LEDGER, ORPHAN, missing);
      for (AccessEligibility.Brief brief : briefs) {
        AccessEligibility.Verdict one = eligibility.check("analyst_a", brief.assetFqn(), null, null);
        assertThat(brief.queryable()).as(brief.assetFqn()).isEqualTo(one.queryable());
        assertThat(brief.readable()).as(brief.assetFqn()).isEqualTo(one.readable());
        assertThat(brief.requestable()).as(brief.assetFqn()).isEqualTo(one.requestable());
        assertThat(brief.openRequestId()).as(brief.assetFqn()).isEqualTo(one.openRequestId());
        assertThat(brief.blockedKind()).as(brief.assetFqn()).isEqualTo(one.blockedKind());
      }
      // And what that is, spelled out: the gate keeps analyst_a off the
      // customer table; the ledger is waiting on the request already made; the
      // orphan is not connected; a table not in the catalog is the same "no"
      // as one that is not connected.
      assertThat(briefs)
          .extracting(
              AccessEligibility.Brief::queryable,
              AccessEligibility.Brief::readable,
              AccessEligibility.Brief::requestable,
              AccessEligibility.Brief::openRequestId,
              AccessEligibility.Brief::blockedKind)
          .containsExactly(
              tuple(true, false, false, null, AccessEligibility.NOT_ADMITTED),
              tuple(true, false, true, waiting.id().toString(), null),
              tuple(false, false, false, null, null),
              tuple(false, false, false, null, null));
    }

    @Test
    @DisplayName("another person's requests are not the caller's")
    void callerOnly() {
      ask("analyst_b", LEDGER, 7);

      assertThat(eligibility.briefs("analyst_a", List.of(LEDGER), null, null))
          .singleElement()
          .satisfies(b -> assertThat(b.openRequestId()).isNull());
    }

    @Test
    @DisplayName("names no policy, whoever asks")
    void namesNoPolicy() throws Exception {
      activate(orgAllow("everyone", null));
      activate(orgDeny("no-l1", "L1"));

      List<AccessEligibility.Brief> briefs =
          resource().eligibilities(
              new AccessRequestResource.Check(List.of(CUSTOMER, LEDGER), null),
              as("admin", "PLATFORM_ADMIN"),
              null);

      assertThat(briefs.get(0).blockedKind()).isNull();
      String body = json.writeValueAsString(briefs);
      assertThat(body).doesNotContain("no-l1", policyNamed("no-l1").toString(), "everyone");

      List<AccessEligibility.Brief> mine =
          resource().eligibilities(
              new AccessRequestResource.Check(List.of(CUSTOMER), null), as("analyst_a"), null);
      assertThat(mine.get(0).blockedKind()).isEqualTo(AccessEligibility.DENIED);
      assertThat(json.writeValueAsString(mine))
          .doesNotContain("no-l1", policyNamed("no-l1").toString());
    }
  }

  // --------------------------------- asking when a grant alone would not do

  @Nested
  @DisplayName("asking when a grant alone would not open the table")
  class BlockedAsk {

    @BeforeEach
    void denied() {
      activate(orgAllow("everyone", null));
      activate(orgDeny("no-l1", "L1"));
    }

    @Test
    @DisplayName("the request is still sent (201), and nothing the requester gets back names the policy")
    void sent() throws Exception {
      Response response = resource().create(askFor(CUSTOMER), as("analyst_a"), null);

      assertThat(response.getStatus()).isEqualTo(201);
      AccessRequestStore.StoredRequest made = (AccessRequestStore.StoredRequest) response.getEntity();
      assertThat(made.status()).isEqualTo("PENDING");
      assertThat(made.approvers())
          .extracting(AccessRequestStore.Approver::name)
          .containsExactly("owner_o");
      String policyId = policyNamed("no-l1").toString();
      assertThat(json.writeValueAsString(made)).doesNotContain("no-l1", policyId);

      // Its own page afterwards: the kind of rule, the request waiting, no policy.
      AccessEligibility.Verdict seen =
          resource().eligibility(CUSTOMER, null, as("analyst_a"), null);
      assertThat(seen.requestable()).isFalse();
      assertThat(seen.blockedKind()).isEqualTo(AccessEligibility.DENIED);
      assertThat(seen.blockedBy()).isNull();
      assertThat(seen.blockedByPolicyId()).isNull();
      assertThat(seen.blockedByPolicy()).isNull();
      assertThat(seen.blockedByReason()).isNull();
      assertThat(seen.openRequestId()).isEqualTo(made.id().toString());
      assertThat(json.writeValueAsString(seen)).doesNotContain("no-l1", policyId);
    }

    @Test
    @DisplayName("somebody who could change the policy is told which one")
    void overseersAreTold() {
      UUID policy = policyNamed("no-l1");
      // An administrator, looking at it as if they were the one refused.
      AccessEligibility.Verdict admin =
          AccessEligibility.toldTo(
              signedIn("admin", "PLATFORM_ADMIN"), eligibility.check("analyst_a", CUSTOMER, null, null));
      assertThat(admin.blockedBy()).startsWith("no-l1");
      assertThat(admin.blockedByPolicyId()).isEqualTo(policy);
      assertThat(admin.blockedByPolicy()).isEqualTo("no-l1");
      assertThat(admin.blockedKind()).isEqualTo(AccessEligibility.DENIED);

      // The table's owner holds no role in ARAK, but a request goes to them.
      AccessEligibility.Verdict owner =
          AccessEligibility.toldTo(
              signedIn("owner_o"), eligibility.check("analyst_a", CUSTOMER, null, null));
      assertThat(owner.blockedByPolicyId()).isEqualTo(policy);

      // Another analyst is not.
      AccessEligibility.Verdict other =
          AccessEligibility.toldTo(
              signedIn("analyst_b"), eligibility.check("analyst_a", CUSTOMER, null, null));
      assertThat(other.blockedBy()).isNull();
      assertThat(other.blockedByPolicyId()).isNull();
      assertThat(other.blockedKind()).isEqualTo(AccessEligibility.DENIED);
    }

    @Test
    @DisplayName("the owner's review names the policy, and configuring it as a grant is still refused")
    void reviewerSeesTheBlocker() {
      AccessRequestStore.StoredRequest made =
          (AccessRequestStore.StoredRequest)
              resource().create(askFor(CUSTOMER), as("analyst_a"), null).getEntity();
      AccessReview review =
          new AccessReview(
              jdbi,
              decisions,
              requests,
              policies,
              new PrincipalQuery(jdbi),
              new AssetContextLoader(json),
              grants);

      AccessReview.Review seen = review.review(made.id(), OWNER, null);
      assertThat(seen.ifGranted().allowed()).isFalse();
      assertThat(seen.ifGranted().blockedBy()).startsWith("no-l1");
      assertThat(seen.conflicts().get(0).policyId()).isEqualTo(policyNamed("no-l1"));

      requests.approve(made.id(), OWNER, null, null);
      AccessRequestResource reviewing = new AccessRequestResource(requests, eligibility, review);
      assertThatThrownBy(
              () ->
                  reviewing.complete(
                      made.id(),
                      new AccessRequestResource.Configure("GRANT", 7, null, null),
                      as("owner_o")))
          .isInstanceOfSatisfying(
              jakarta.ws.rs.WebApplicationException.class,
              e -> assertThat(e.getResponse().getStatus()).isEqualTo(409));
      assertThat(requests.find(made.id(), OWNER).status()).isEqualTo("APPROVED");
      assertThat(read("analyst_a", CUSTOMER)).isFalse();
    }

    @Test
    @DisplayName("one the caller can already read is still refused with 409")
    void readableRefused() {
      assertThatThrownBy(() -> resource().create(askFor(CUSTOMER), as("analyst_b"), null))
          .isInstanceOfSatisfying(
              jakarta.ws.rs.WebApplicationException.class,
              e -> {
                assertThat(e.getResponse().getStatus()).isEqualTo(409);
                assertThat(e.getResponse().getEntity().toString()).contains("already read");
              });
    }
  }

  @Nested
  @DisplayName("asking past a higher layer that does not admit the person")
  class GatedAsk {

    @Test
    @DisplayName("the request is sent too, and the requester is told only the kind of rule")
    void notAdmitted() throws Exception {
      activate(orgAllow("l2-only", "L2"));

      AccessEligibility.Verdict seen =
          resource().eligibility(CUSTOMER, null, as("analyst_a"), null);
      assertThat(seen.requestable()).isFalse();
      assertThat(seen.blockedKind()).isEqualTo(AccessEligibility.NOT_ADMITTED);
      assertThat(seen.blockedBy()).isNull();
      assertThat(seen.blockedByPolicyId()).isNull();
      assertThat(json.writeValueAsString(seen))
          .doesNotContain("l2-only", policyNamed("l2-only").toString());

      Response created = resource().create(askFor(CUSTOMER), as("analyst_a"), null);
      assertThat(created.getStatus()).isEqualTo(201);
      assertThat(json.writeValueAsString(created.getEntity()))
          .doesNotContain("l2-only", policyNamed("l2-only").toString());

      // The administrator, who could change the gate, is told which it is.
      AccessEligibility.Verdict told =
          AccessEligibility.toldTo(
              signedIn("admin", "PLATFORM_ADMIN"),
              eligibility.check("analyst_a", CUSTOMER, null, null));
      assertThat(told.blockedByPolicy()).isEqualTo("l2-only");
      assertThat(told.blockedByPolicyId()).isEqualTo(policyNamed("l2-only"));
    }
  }

  // ------------------------------------------------------------ reviewing

  @Nested
  @DisplayName("reviewing a request before answering it")
  class Reviewing {

    private AccessReview review() {
      return new AccessReview(
          jdbi,
          decisions,
          requests,
          policies,
          new PrincipalQuery(jdbi),
          new AssetContextLoader(json),
          grants);
    }

    @Test
    @DisplayName("a DENY a grant cannot pass is a blocker that names the policy, and the grant is refused")
    void grantBlocked() {
      activate(orgAllow("everyone", null));
      activate(orgDeny("no-l1", "L1"));
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);

      AccessReview.Review seen = review().review(made.id(), OWNER, null);

      assertThat(seen.now().allowed()).isFalse();
      assertThat(seen.ifGranted().allowed()).isFalse();
      assertThat(seen.ifGranted().blockedBy()).startsWith("no-l1");
      AccessReview.Conflict first = seen.conflicts().get(0);
      assertThat(first.code()).isEqualTo("GRANT_BLOCKED");
      assertThat(first.severity()).isEqualTo("BLOCKER");
      assertThat(first.policyId()).isEqualTo(policyNamed("no-l1"));
      assertThat(seen.suggestions())
          .extracting(AccessReview.Suggestion::kind)
          .containsExactly("UPDATE_POLICY");
      assertThat(seen.suggestions().get(0).policyId()).isEqualTo(policyNamed("no-l1"));

      assertThat(review().grantWouldNotOpen(requests.find(made.id(), OWNER)))
          .hasValueSatisfying(blocker -> assertThat(blocker).startsWith("no-l1"));
    }

    @Test
    @DisplayName("what a grant opens, column by column: the mask that stays, and who owns the table")
    void columns() {
      activate(orgMaskEmail("mask-pii"));
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 14);

      AccessReview.Review seen = review().review(made.id(), OWNER, null);

      assertThat(seen.now().allowed()).isFalse();
      assertThat(seen.ifGranted().allowed()).isTrue();
      assertThat(seen.ifGranted().columns())
          .extracting(AccessReview.ColumnFate::name, AccessReview.ColumnFate::fate)
          .containsExactly(tuple("id", "VISIBLE"), tuple("email", "MASKED"));
      AccessReview.ColumnFate email = seen.ifGranted().columns().get(1);
      assertThat(email.policy()).isEqualTo("mask-pii");
      assertThat(email.masking()).isEqualTo("NULLIFY");
      assertThat(email.sensitive()).isTrue();
      assertThat(email.sensitiveTags()).contains("PII.Sensitive");
      assertThat(seen.conflicts())
          .extracting(AccessReview.Conflict::code)
          .containsExactly("MASKS_REMAIN");
      assertThat(seen.risk().level()).isEqualTo("LOW");
      assertThat(seen.risk().factors())
          .extracting(AccessReview.Factor::code)
          .containsExactlyInAnyOrder("SENSITIVE_PROTECTED", "NO_PURPOSE");
      assertThat(seen.table().owners())
          .extracting(AccessRequestStore.Approver::name)
          .containsExactly("owner_o");
      assertThat(seen.table().columns()).isEqualTo(2);
      assertThat(seen.table().sensitiveColumns()).isEqualTo(1);
      assertThat(seen.requester().attributes())
          .extracting(AccessReview.Attribute::key, AccessReview.Attribute::value)
          .contains(tuple("clearance", "L1"));
      assertThat(review().grantWouldNotOpen(requests.find(made.id(), OWNER))).isEmpty();
    }

    @Test
    @DisplayName("sensitive columns in clear for an open-ended ask: high risk, and a short grant offered first")
    void sensitiveInClear() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, null);

      AccessReview.Review seen = review().review(made.id(), OWNER, null);

      assertThat(seen.ifGranted().sensitiveInClear())
          .extracting(AccessReview.ColumnFate::name)
          .containsExactly("email");
      assertThat(seen.risk().level()).isEqualTo("HIGH");
      assertThat(seen.risk().factors())
          .extracting(AccessReview.Factor::code)
          .contains("SENSITIVE_IN_CLEAR", "OPEN_ENDED");
      assertThat(seen.suggestions())
          .extracting(AccessReview.Suggestion::kind, AccessReview.Suggestion::days)
          .containsExactly(tuple("GRANT", AccessReview.SHORTER_DAYS), tuple("GRANT", null));
    }

    @Test
    @DisplayName("the requester may not read the review; a stranger is told there is no such request")
    void who() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);

      assertThatThrownBy(() -> review().review(made.id(), ANALYST_A, null))
          .satisfies(
              e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.FORBIDDEN));
      assertThatThrownBy(() -> review().review(made.id(), ANALYST_B, null))
          .satisfies(
              e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.NOT_FOUND));
      assertThat(review().review(made.id(), ADMIN, null).requester().username())
          .isEqualTo("analyst_a");
    }

    @Test
    @DisplayName("a draft policy that would open the table is said to, and stays a draft")
    void draftPolicyOpens() {
      UUID draft = draft(tableAllow("ledger-analyst-a", LEDGER, "analyst_a"));
      AccessRequestStore.StoredRequest made = ask("analyst_a", LEDGER, 7);

      AccessReview.Review seen = review().review(made.id(), TEAM_MEMBER, draft);

      assertThat(seen.policy().lifecycleState()).isEqualTo("DRAFT");
      assertThat(seen.policy().bound()).isTrue();
      assertThat(seen.ifPolicy().allowed()).isTrue();
      assertThat(seen.conflicts())
          .extracting(AccessReview.Conflict::code)
          .containsExactly("POLICY_NOT_ACTIVE", "POLICY_OPENS");
      // Asked about, not done: nothing was activated and nobody reads the table.
      assertThat(policies.find(draft).orElseThrow().lifecycleState()).isEqualTo("DRAFT");
      assertThat(read("analyst_a", LEDGER)).isFalse();
    }

    @Test
    @DisplayName("a policy that misses the table is a blocker; one for somebody else still refuses")
    void policyMisses() {
      UUID elsewhere = draft(tableAllow("orphan-analyst-a", ORPHAN, "analyst_a"));
      UUID someoneElse = draft(tableAllow("ledger-analyst-b", LEDGER, "analyst_b"));
      AccessRequestStore.StoredRequest made = ask("analyst_a", LEDGER, 7);

      assertThat(review().review(made.id(), TEAM_MEMBER, elsewhere).conflicts())
          .extracting(AccessReview.Conflict::code)
          .containsExactly("POLICY_NOT_BOUND");
      assertThat(review().review(made.id(), TEAM_MEMBER, someoneElse).conflicts())
          .extracting(AccessReview.Conflict::code)
          .containsExactly("POLICY_NOT_ACTIVE", "POLICY_STILL_REFUSES");
      assertThat(review().review(made.id(), TEAM_MEMBER, UUID.randomUUID()).conflicts())
          .extracting(AccessReview.Conflict::code)
          .containsExactly("POLICY_NOT_FOUND");
    }

    @Test
    @DisplayName("when others in the requester's group hold grants, a draft policy for the group is offered")
    void peers() {
      jdbi.useHandle(
          handle -> {
            person(handle, "analyst_c", "L1");
            handle.execute(
                """
                INSERT INTO principal (principal_type, username, source, enabled)
                VALUES ('GROUP', 'analysts', 'local', true)
                """);
            handle.execute(
                """
                INSERT INTO group_member (group_id, member_id, source)
                SELECT g.id, m.id, 'local' FROM principal g, principal m
                WHERE g.username = 'analysts'
                  AND m.username IN ('analyst_a', 'analyst_b', 'analyst_c')
                """);
          });
      for (String peer : List.of("analyst_b", "analyst_c")) {
        grants.grant(
            new GrantStore.NewGrant(
                LEDGER, idOf(peer), Instant.now().minusSeconds(60), null, "month end", "finance_lead"));
      }
      AccessRequestStore.StoredRequest made = ask("analyst_a", LEDGER, 7);

      AccessReview.Review seen = review().review(made.id(), TEAM_MEMBER, null);

      AccessReview.Suggestion offered =
          seen.suggestions().stream()
              .filter(s -> s.kind().equals("CREATE_POLICY_DRAFT"))
              .findFirst()
              .orElseThrow();
      assertThat(offered.detail()).startsWith("2 other members of analysts");
      Policy document = offered.draft();
      assertThat(document.getSubject().getPrincipals().get(0).getGroup()).isEqualTo("analysts");
      assertThat(document.getScopeFqn()).isEqualTo(LEDGER);
      // Others' grants are counted in the group, not listed as the requester's.
      assertThat(seen.requester().grantsHere()).isEmpty();
      assertThat(seen.requester().grantsElsewhere()).isZero();
      // The group chip opens the group: the review carries its id.
      String group = jdbi.withHandle(h -> h.createQuery(
              "SELECT id::text FROM principal WHERE username = 'analysts'").mapTo(String.class).one());
      assertThat(seen.requester().memberships())
          .extracting(AccessReview.Membership::id, AccessReview.Membership::name)
          .contains(tuple(group, "analysts"));
      // Peers holding it lift the lean, and the signal says who they are.
      assertThat(seen.recommendation().signals())
          .filteredOn(s -> s.code().equals("PEERS_HOLD"))
          .singleElement()
          .satisfies(s -> assertThat(s.detail()).contains("analysts"));
      assertThat(seen.recommendation().score())
          .isEqualTo(
              AccessReview.NEUTRAL
                  + seen.recommendation().signals().stream().mapToInt(AccessReview.Signal::points).sum());
      // The group's own page counts what its three members carry.
      PrincipalQuery.PrincipalDetail analysts = new PrincipalQuery(jdbi).detail(group).orElseThrow();
      assertThat(analysts.members()).hasSize(3);
      assertThat(analysts.memberAttributes())
          .filteredOn(a -> a.key().equals("clearance"))
          .allSatisfy(a -> assertThat(a.keyHolders()).isEqualTo(3))
          .extracting(PrincipalQuery.MemberAttribute::members)
          .satisfies(counts -> assertThat(counts.stream().mapToInt(Integer::intValue).sum()).isEqualTo(3));
      assertThat(new PrincipalQuery(jdbi).detail("analyst_a").orElseThrow().memberAttributes()).isEmpty();
      // The draft is valid as it stands, and saving it makes a DRAFT, nothing more.
      PolicyStore.StoredPolicy saved = policies.create(document, "finance_lead");
      assertThat(saved.lifecycleState()).isEqualTo("DRAFT");
      assertThat(read("analyst_a", LEDGER)).isFalse();
    }

    @Test
    @DisplayName("an earlier refusal on this table is listed, and counts against the ask")
    void history() {
      AccessRequestStore.StoredRequest first = ask("analyst_a", LEDGER, 7);
      requests.reject(first.id(), TEAM_MEMBER, "Not this quarter");
      AccessRequestStore.StoredRequest again = ask("analyst_a", LEDGER, 7);

      AccessReview.Review seen = review().review(again.id(), TEAM_MEMBER, null);

      assertThat(seen.requester().earlier())
          .singleElement()
          .satisfies(past -> assertThat(past.status()).isEqualTo("REJECTED"));
      assertThat(seen.requester().recentRequests()).isEqualTo(1);
      assertThat(seen.requester().recentRejected()).isEqualTo(1);
      assertThat(seen.risk().factors())
          .extracting(AccessReview.Factor::code)
          .contains("REJECTED_BEFORE");
    }

    @Test
    @DisplayName("the address a request came from decides an ipCidr rule, and is never shown")
    void address() throws Exception {
      activate(orgAllow("everyone", null));
      Policy guest = subscription("no-guest-network", Policy.Effect.DENY);
      guest.setSubject(
          new SubjectRule().withContext(new ContextRule().withIpCidr(List.of("198.51.100.0/24"))));
      activate(guest);

      AccessRequestStore.StoredRequest outside =
          requests.create(
              withIp(newRequest("analyst_a", CUSTOMER, "month end", 7), "198.51.100.7"), ANALYST_A);
      AccessRequestStore.StoredRequest inside =
          requests.create(
              withIp(newRequest("analyst_b", CUSTOMER, "month end", 7), "192.0.2.10"), ANALYST_B);

      assertThat(requests.askedFrom(inside.id())).isEqualTo("192.0.2.10");
      AccessReview.Review far = review().review(outside.id(), OWNER, null);
      AccessReview.Review near = review().review(inside.id(), OWNER, null);
      assertThat(far.addressKnown()).isTrue();
      assertThat(far.conflicts())
          .extracting(AccessReview.Conflict::code)
          .contains("GRANT_BLOCKED");
      assertThat(near.now().allowed()).isTrue();
      assertThat(near.conflicts())
          .extracting(AccessReview.Conflict::code)
          .contains("ALREADY_READS");

      String written =
          json.writeValueAsString(far)
              + json.writeValueAsString(near)
              + json.writeValueAsString(requests.find(inside.id(), OWNER))
              + json.writeValueAsString(requests.find(outside.id(), OWNER));
      assertThat(written).doesNotContain("192.0.2.10").doesNotContain("198.51.100.7");
    }
  }

  private static AccessRequestStore.NewRequest withIp(AccessRequestStore.NewRequest r, String ip) {
    return new AccessRequestStore.NewRequest(
        r.assetFqn(),
        r.requesterId(),
        r.requesterUsername(),
        r.dataSourceId(),
        r.reason(),
        r.purpose(),
        r.requestedDays(),
        r.attemptedSql(),
        r.deniedBy(),
        ip);
  }

  /** Saved and bound, never activated. */
  private UUID draft(Policy document) {
    document.setEnvironment(Policy.Environment.fromValue(DecisionService.DEFAULT_ENVIRONMENT));
    UUID id = policies.create(document, "alice").id();
    materializer.materialize(id);
    return id;
  }

  private static Policy tableAllow(String name, String fqn, String user) {
    FacetCondition table = new FacetCondition();
    table.setFacet(FacetCondition.FacetType.TABLE);
    table.setOperator(ResolvedRowPredicate.FacetOperator.EQ);
    table.setValue(fqn);
    AssetSelector selector = new AssetSelector();
    selector.setCondition(table);
    Policy document = new Policy();
    document.setName(name);
    document.setPolicyType(Policy.PolicyType.SUBSCRIPTION);
    document.setEffect(Policy.Effect.ALLOW);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.TABLE);
    document.setScopeFqn(fqn);
    document.setSelector(selector);
    document.setSubject(
        new SubjectRule().withPrincipals(List.of(new PrincipalMatch().withUser(user))));
    return document;
  }

  private static UUID policyNamed(String name) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT id FROM policy WHERE name = :name")
                .bind("name", name)
                .mapTo(UUID.class)
                .one());
  }

  // ------------------------------------------------------------ templates

  @Nested
  @DisplayName("pre-authorizing a class of tables for a group")
  class Preauthorizing {

    private static final String IP = "192.0.2.77";

    private AccessReview review() {
      return new AccessReview(
          jdbi,
          decisions,
          requests,
          policies,
          new PrincipalQuery(jdbi),
          new AssetContextLoader(json),
          grants);
    }

    private Preauthorization.Target piiForTeam(String team) {
      return new Preauthorization.Target(
          List.of(new Preauthorization.Condition("tags", "contains", "PII.Sensitive")),
          new Preauthorization.Subject(
              "GROUP", List.of(new Preauthorization.PrincipalRef("team", team)), List.of()));
    }

    private AccessRequestStore.StoredRequest preauthorize(
        String scope, Integer days, Preauthorization.Target target) {
      return requests.create(
          new AccessRequestStore.NewRequest(
              scope,
              idOf("analyst_a"),
              "analyst_a",
              null,
              "The fraud team reads PII tables under dbo every quarter",
              null,
              days,
              null,
              null,
              IP,
              null,
              Preauthorization.KIND,
              target),
          ANALYST_A);
    }

    @Test
    @DisplayName("a target names at least one facet and exactly one kind of subject")
    void normalises() {
      Preauthorization.Subject team =
          new Preauthorization.Subject(
              "group", List.of(new Preauthorization.PrincipalRef("TEAM", " Finance ")), List.of());
      assertInvalid(() -> Preauthorization.normalise(null));
      assertInvalid(() -> Preauthorization.normalise(new Preauthorization.Target(List.of(), team)));
      assertInvalid(
          () ->
              Preauthorization.normalise(
                  new Preauthorization.Target(
                      List.of(new Preauthorization.Condition("owner", "eq", "x")), team)));
      assertInvalid(
          () ->
              Preauthorization.normalise(
                  new Preauthorization.Target(
                      List.of(new Preauthorization.Condition("tags", "startsWith", "PII")), team)));
      assertInvalid(
          () ->
              Preauthorization.normalise(
                  new Preauthorization.Target(
                      List.of(new Preauthorization.Condition("tags", "contains", "PII")), null)));
      assertInvalid(
          () ->
              Preauthorization.normalise(
                  new Preauthorization.Target(
                      List.of(new Preauthorization.Condition("tags", "contains", "PII")),
                      new Preauthorization.Subject(
                          "GROUP",
                          List.of(new Preauthorization.PrincipalRef("team", "Finance")),
                          List.of(new Preauthorization.Attribute("clearance", "eq", "L2"))))));

      Preauthorization.Target clean =
          Preauthorization.normalise(
              new Preauthorization.Target(
                  List.of(
                      new Preauthorization.Condition(" Tags ", null, " PII.Sensitive "),
                      new Preauthorization.Condition("tags", "contains", "PII.Sensitive")),
                  team));
      assertThat(clean.conditions())
          .containsExactly(new Preauthorization.Condition("tags", "contains", "PII.Sensitive"));
      assertThat(clean.subject().kind()).isEqualTo("GROUP");
      assertThat(clean.subject().principals())
          .containsExactly(new Preauthorization.PrincipalRef("team", "Finance"));
    }

    @Test
    @DisplayName("asked for a scope, not a table: two may be open at once, and a refused statement is no reason")
    void asks() {
      AccessRequestStore.StoredRequest one = preauthorize(DBO, 90, piiForTeam("Finance"));
      AccessRequestStore.StoredRequest two = preauthorize(DBO, 30, piiForTeam("Risk"));

      assertThat(one.kind()).isEqualTo(Preauthorization.KIND);
      assertThat(one.preauthorization()).isTrue();
      assertThat(one.target().subject().principals())
          .containsExactly(new Preauthorization.PrincipalRef("team", "Finance"));
      assertThat(requests.find(two.id(), ANALYST_A).target().subject().principals())
          .containsExactly(new Preauthorization.PrincipalRef("team", "Risk"));
      // The ordinary kind still holds one open per person per table.
      ask("analyst_a", CUSTOMER, 7);
      assertThatThrownBy(() -> ask("analyst_a", CUSTOMER, 7))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.CONFLICT));

      assertInvalid(
          () ->
              requests.create(
                  new AccessRequestStore.NewRequest(
                      DBO, idOf("analyst_a"), "analyst_a", null,
                      "The fraud team reads PII tables under dbo every quarter", null, 7,
                      "SELECT * FROM customer", "Denied by default", IP, null,
                      Preauthorization.KIND, piiForTeam("Finance")),
                  ANALYST_A));
      assertInvalid(
          () ->
              requests.create(
                  new AccessRequestStore.NewRequest(
                      CUSTOMER, idOf("analyst_a"), "analyst_a", null,
                      "The fraud team reads PII tables under dbo every quarter", null, 7, null,
                      null, IP, null, Preauthorization.ASSET, piiForTeam("Finance")),
                  ANALYST_A));
      assertThatThrownBy(() -> preauthorize("prod-pg.NoSuchDB", 7, piiForTeam("Finance")))
          .isInstanceOf(AccessRequestStore.RequestException.class);
    }

    @Test
    @DisplayName("the review measures who and what it reaches, and offers the policy only as an unsaved draft")
    void reviews() throws Exception {
      administrator("admin");
      AccessRequestStore.StoredRequest made = preauthorize(DBO, 90, piiForTeam("Finance"));

      AccessReview.Review seen = review().review(made.id(), ADMIN, null);

      assertThat(seen.kind()).isEqualTo(Preauthorization.KIND);
      assertThat(seen.now()).isNull();
      assertThat(seen.ifGranted()).isNull();
      assertThat(seen.coverage().scopeType()).isEqualTo("SCHEMA");
      assertThat(seen.coverage().tables()).isEqualTo(1);
      assertThat(seen.coverage().tableSample()).containsExactly(CUSTOMER);
      assertThat(seen.coverage().people()).isEqualTo(1);
      assertThat(seen.coverage().peopleSample()).containsExactly("finance_lead");
      assertThat(seen.recommendation().verdict()).isEqualTo("REVIEW");
      assertThat(seen.conflicts())
          .extracting(AccessReview.Conflict::code)
          .containsExactly("POLICY_ONLY");
      assertThat(seen.suggestions())
          .extracting(AccessReview.Suggestion::kind)
          .containsExactly("CREATE_POLICY_DRAFT", "DECLINE");

      Policy draft = seen.suggestions().get(0).draft();
      assertThat(draft.getPolicyType()).isEqualTo(Policy.PolicyType.SUBSCRIPTION);
      assertThat(draft.getEffect()).isEqualTo(Policy.Effect.ALLOW);
      assertThat(draft.getScopeFqn()).isEqualTo(DBO);
      assertThat(draft.getScopeLevel()).isEqualTo(ResolvedColumnMask.ScopeLevel.SCHEMA);
      assertThat(draft.getSelector().getCondition().getValue()).isEqualTo("PII.Sensitive");
      assertThat(draft.getSubject().getPrincipals().get(0).getTeam()).isEqualTo("Finance");
      assertThat(draft.getSubject().getTime().getValidTo())
          .isEqualTo(java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(90));
      assertThat(draft.getName()).startsWith("preauth-");
      // Reviewing saved nothing and switched nothing on.
      assertThat(jdbi.<Integer, RuntimeException>withHandle(h -> h.createQuery("SELECT count(*) FROM policy").mapTo(Integer.class).one()).intValue())
          .isZero();

      // Nobody in the team, no end date: both said, and the audience is not named to the asker.
      AccessRequestStore.StoredRequest empty = preauthorize(DBO, null, piiForTeam("Nobody"));
      AccessReview.Review open = review().review(empty.id(), ADMIN, null);
      assertThat(open.conflicts())
          .extracting(AccessReview.Conflict::code)
          .containsExactly("NOBODY", "POLICY_ONLY");
      assertThat(open.risk().factors()).extracting(AccessReview.Factor::code).contains("NO_END");
      assertThat(review().coverage(DBO, piiForTeam("Finance")).peopleSample()).isEmpty();
      assertThat(review().coverage(DBO, piiForTeam("Finance")).people()).isEqualTo(1);

      // The address it was asked from is never in what a reviewer is sent.
      assertThat(json.writeValueAsString(seen)).doesNotContain(IP);
      assertThat(json.writeValueAsString(requests.find(made.id(), ADMIN))).doesNotContain(IP);
    }

    @Test
    @DisplayName("configured by a policy only: a grant is refused and nothing is granted")
    void noGrant() {
      administrator("admin");
      AccessRequestStore.StoredRequest made = preauthorize(DBO, 30, piiForTeam("Finance"));
      requests.approve(made.id(), ADMIN, null, null);

      assertThatThrownBy(() -> requests.complete(made.id(), ADMIN, grantFor(30)))
          .isInstanceOf(AccessRequestStore.RequestException.class)
          .hasMessageContaining("policy, not a grant");
      assertThat(review().grantWouldNotOpen(requests.find(made.id(), ADMIN))).isEmpty();
      assertThat(jdbi.<Integer, RuntimeException>withHandle(h -> h.createQuery("SELECT count(*) FROM access_grant").mapTo(Integer.class).one()).intValue())
          .isZero();
      assertThat(read("finance_lead", CUSTOMER)).isFalse();
    }
  }

  @Nested
  @DisplayName("request templates")
  class Templates {

    private final RequestTemplate.Form strict =
        new RequestTemplate.Form(
            List.of("Fraud investigation", "Regulatory report"),
            true,
            List.of(7, 14),
            7,
            30,
            false,
            "DPIA number",
            true,
            20,
            "PII: a DPIA number is required.\n<b>not html</b>");

    private RequestTemplate.Stored template(
        String name, String scope, List<String> facets, RequestTemplate.Form form) {
      return templates.create(
          RequestTemplate.validate(new RequestTemplate.Draft(name, null, scope, facets, true, form)),
          "admin");
    }

    private AccessRequestStore.StoredRequest askWith(
        String fqn, String reason, String purpose, Integer days, String reference) {
      return requests.create(
          new AccessRequestStore.NewRequest(
              fqn, idOf("analyst_a"), "analyst_a", null, reason, purpose, days, null, null, null,
              reference),
          ANALYST_A);
    }

    private RequestTemplate.Draft draft(RequestTemplate.Form form) {
      return new RequestTemplate.Draft("x", null, null, null, true, form);
    }

    @Test
    @DisplayName("with none configured, the built-in form asks what it always did")
    void builtIn() {
      assertThat(templates.effective(CUSTOMER).builtIn()).isTrue();
      AccessRequestStore.StoredRequest made = askWith(CUSTOMER, "x", null, null, null);
      assertThat(made.templateName()).isNull();
      assertThat(made.reference()).isNull();
    }

    @Test
    @DisplayName("a template naming a tag covers tables that carry it on the table or a column, and no others")
    void matchesByFacet() {
      template("PII tables", null, List.of("PII"), strict);
      assertThat(templates.effective(CUSTOMER).name()).isEqualTo("PII tables");
      assertThat(templates.effective(LEDGER).builtIn()).isTrue();

      // Only a column carries it: asking for the table is asking for that column.
      jdbi.useHandle(
          handle ->
              handle.execute(
                  """
                  INSERT INTO asset_facet
                    (column_id, target_fqn, facet_type, facet_fqn, depth, is_direct)
                  SELECT id, fqn, 'tags', 'PII.Financial', 0, true FROM asset_column WHERE fqn = ?
                  """,
                  LEDGER + ".email"));
      assertThat(templates.effective(LEDGER).name()).isEqualTo("PII tables");
      // Segment by segment: PIIX is not under PII.
      assertThat(RequestTemplateStore.matches(List.of("PII"), List.of("PIIX.Thing"))).isFalse();
    }

    @Test
    @DisplayName("a template naming facets beats a deeper scope that names none; then the deepest scope wins")
    void resolution() {
      RequestTemplate.Form plain = RequestTemplate.builtIn().form();
      template("Organisation", null, List.of(), plain);
      template("Sales", SALES, List.of(), plain);
      template("Sales dbo", DBO, List.of(), plain);
      assertThat(templates.effective(LEDGER).name()).isEqualTo("Sales dbo");
      template("PII anywhere", null, List.of("PII.Sensitive"), strict);
      assertThat(templates.effective(CUSTOMER).name()).isEqualTo("PII anywhere");
      assertThat(templates.effective(LEDGER).name()).isEqualTo("Sales dbo");
      assertThat(templates.effective("other-svc.db.s.t").name()).isEqualTo("Organisation");
    }

    @Test
    @DisplayName("the server holds a request to its table's template, whatever the form sent")
    void enforced() {
      template("PII tables", null, List.of("PII"), strict);
      String reason = "Investigating case 4411 for the fraud team";

      assertInvalid(() -> askWith(CUSTOMER, "too short", "Fraud investigation", 7, "DPIA-1"));
      assertInvalid(() -> askWith(CUSTOMER, reason, null, 7, "DPIA-1"));
      assertInvalid(() -> askWith(CUSTOMER, reason, "Marketing", 7, "DPIA-1"));
      assertInvalid(() -> askWith(CUSTOMER, reason, "Fraud investigation", null, "DPIA-1"));
      assertInvalid(() -> askWith(CUSTOMER, reason, "Fraud investigation", 31, "DPIA-1"));
      assertInvalid(() -> askWith(CUSTOMER, reason, "Fraud investigation", 7, "  "));
      int stored =
          jdbi.withHandle(
              h -> h.createQuery("SELECT count(*) FROM access_request").mapTo(Integer.class).one());
      assertThat(stored).isZero();

      AccessRequestStore.StoredRequest made =
          askWith(CUSTOMER, reason, "fraud INVESTIGATION", 30, " DPIA-2026-017 ");
      assertThat(made.templateName()).isEqualTo("PII tables");
      assertThat(made.reference()).isEqualTo("DPIA-2026-017");
      // Stored as the template spells it, so reports count one purpose once.
      assertThat(made.purpose()).isEqualTo("Fraud investigation");
      assertThat(made.requestedDays()).isEqualTo(30);

      // A table the template does not cover asks nothing extra, and takes no reference.
      assertInvalid(() -> askWith(LEDGER, "x", null, null, "DPIA-1"));
      assertThat(askWith(LEDGER, "x", null, null, null).templateName()).isNull();
    }

    @Test
    @DisplayName("editing or deleting a template leaves a request made on it as it was")
    void requestsKeepTheirCopy() {
      RequestTemplate.Stored t = template("PII tables", null, List.of("PII"), strict);
      AccessRequestStore.StoredRequest made =
          askWith(
              CUSTOMER, "Investigating case 4411 for the fraud team", "Regulatory report", 14, "D-1");
      templates.delete(t.template().id(), "admin");
      AccessRequestStore.StoredRequest again = requests.find(made.id(), ANALYST_A);
      assertThat(again.templateName()).isEqualTo("PII tables");
      assertThat(again.reference()).isEqualTo("D-1");
      assertThat(templates.history(t.template().id()))
          .extracting(row -> row.get("action"))
          .containsExactly("DELETE", "CREATE");
    }

    @Test
    @DisplayName("a template that contradicts itself is refused before it is stored")
    void validates() {
      assertThatThrownBy(
              () ->
                  RequestTemplate.validate(
                      draft(
                          new RequestTemplate.Form(
                              List.of(), true, List.of(7), 7, 30, true, null, false, 1, null))))
          .hasMessageContaining("until revoked");
      assertThatThrownBy(
              () ->
                  RequestTemplate.validate(
                      draft(
                          new RequestTemplate.Form(
                              List.of(), false, List.of(90), 7, 30, false, null, false, 1, null))))
          .hasMessageContaining("between 1 and 30");
      assertThatThrownBy(
              () ->
                  RequestTemplate.validate(
                      draft(
                          new RequestTemplate.Form(
                              List.of(), false, List.of(), 7, null, true, null, true, 1, null))))
          .hasMessageContaining("Name the reference");
      assertThatThrownBy(
              () ->
                  RequestTemplate.validate(
                      draft(
                          new RequestTemplate.Form(
                              List.of(), false, List.of(), null, 30, false, null, false, 1, null))))
          .hasMessageContaining("starts on");
      template("Same", null, List.of(), RequestTemplate.builtIn().form());
      assertThatThrownBy(() -> template("same", DBO, List.of(), RequestTemplate.builtIn().form()))
          .isInstanceOf(RequestTemplateStore.NameTakenException.class);
    }
  }

  @Nested
  @DisplayName("purposes")
  class Purposes {

    private com.mfec.dac.purpose.PurposeStore register;
    private AccessRequestStore listed;

    @BeforeEach
    void registerOfThree() {
      jdbi.useHandle(
          handle -> {
            handle.execute("DELETE FROM purpose WHERE created_by <> 'system'");
            handle.execute("UPDATE purpose SET status = 'ACTIVE', max_days = NULL");
          });
      register = new com.mfec.dac.purpose.PurposeStore(jdbi);
      listed = new AccessRequestStore(jdbi, json, grants, workflows, templates, register);
    }

    private AccessRequestStore.StoredRequest askFor(String purpose, Integer days) {
      return listed.create(
          new AccessRequestStore.NewRequest(
              CUSTOMER, idOf("analyst_a"), "analyst_a", null, "Quarter-end reconciliation",
              purpose, days, null, null, null, null),
          ANALYST_A);
    }

    @Test
    @DisplayName("a purpose in the register is stored by its key, whatever case was sent")
    void storesTheKey() {
      assertThat(askFor("Fraud-Analysis", 7).purpose()).isEqualTo("fraud-analysis");
    }

    @Test
    @DisplayName("one the register lacks, or one retired, is refused before anything is stored")
    void refusesUnlisted() {
      assertInvalid(() -> askFor("marketing", 7));
      register.retire("support", "Merged into reporting", "admin");
      assertThatThrownBy(() -> askFor("support", 7))
          .isInstanceOf(AccessRequestStore.RequestException.class)
          .hasMessageContaining("retired");
      int stored =
          jdbi.withHandle(
              h -> h.createQuery("SELECT count(*) FROM access_request").mapTo(Integer.class).one());
      assertThat(stored).isZero();
    }

    @Test
    @DisplayName("a purpose with a longest access holds the days asked for to it, and refuses until revoked")
    void capsTheDays() {
      register.update(
          "reporting",
          new com.mfec.dac.purpose.PurposeStore.Details("Reporting", null, null, false, null, 30),
          "admin");

      assertThatThrownBy(() -> askFor("reporting", 60))
          .isInstanceOf(AccessRequestStore.RequestException.class)
          .hasMessageContaining("at most 30 days");
      assertThatThrownBy(() -> askFor("reporting", null))
          .isInstanceOf(AccessRequestStore.RequestException.class)
          .hasMessageContaining("choose a number of days");
      assertThat(askFor("reporting", 30).requestedDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("a template written before the register keeps the purposes it lists by name")
    void keepsTheTemplatesWords() {
      templates.create(
          RequestTemplate.validate(
              new RequestTemplate.Draft(
                  "PII tables",
                  null,
                  null,
                  List.of("PII"),
                  true,
                  new RequestTemplate.Form(
                      List.of("Fraud investigation", "reporting"),
                      false, List.of(), null, null, true, null, false, 1, null))),
          "admin");

      assertThat(askFor("fraud investigation", null).purpose()).isEqualTo("Fraud investigation");
      listed.withdraw(
          listed.openRequest(CUSTOMER, "analyst_a").orElseThrow().id(), ANALYST_A);
      assertThat(askFor("REPORTING", null).purpose()).isEqualTo("reporting");
    }

    @Test
    @DisplayName("without a purpose, nothing is asked of the register")
    void none() {
      assertThat(askFor(null, 7).purpose()).isNull();
    }

    @Test
    @DisplayName("the grant a request becomes keeps its purpose")
    void grantKeepsIt() {
      AccessRequestStore.StoredRequest made = askFor("Reporting", 7);
      listed.approve(made.id(), OWNER, null, null);
      AccessRequestStore.StoredRequest done = listed.complete(made.id(), OWNER, grantFor(7));

      assertThat(grants.find(done.grantId()).orElseThrow().purpose()).isEqualTo("reporting");
      assertThat(grants.historyFor(CUSTOMER, 10))
          .singleElement()
          .extracting(GrantStore.HistoryEntry::purpose)
          .isEqualTo("reporting");
    }

    @Test
    @DisplayName("a limit tightened after the ask holds the grant to it")
    void limitTightenedSince() {
      AccessRequestStore.StoredRequest made = askFor("reporting", 7);
      listed.approve(made.id(), OWNER, null, null);
      register.update(
          "reporting",
          new com.mfec.dac.purpose.PurposeStore.Details("Reporting", null, null, false, null, 5),
          "admin");

      assertThatThrownBy(() -> listed.complete(made.id(), OWNER, grantFor(7)))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.INVALID))
          .hasMessageContaining("at most 5 days");
      // Refused whole: still waiting, and nothing granted.
      assertThat(listed.find(made.id(), OWNER).status()).isEqualTo("APPROVED");
      assertThat(grants.onAsset(CUSTOMER)).isEmpty();

      AccessRequestStore.StoredRequest done = listed.complete(made.id(), OWNER, grantFor(5));
      assertThat(grants.find(done.grantId()).orElseThrow().purpose()).isEqualTo("reporting");
    }

    @Test
    @DisplayName("a purpose retired after the ask cannot be granted for")
    void retiredSince() {
      AccessRequestStore.StoredRequest made = askFor("support", 7);
      listed.approve(made.id(), OWNER, null, null);
      register.retire("support", "Merged into reporting", "admin");

      assertThatThrownBy(() -> listed.complete(made.id(), OWNER, grantFor(7)))
          .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.INVALID))
          .hasMessageContaining("retired");
      assertThat(grants.onAsset(CUSTOMER)).isEmpty();
    }

    @Test
    @DisplayName("a template's own word from before the register reaches the grant as it is")
    void templatesWordReachesTheGrant() {
      templates.create(
          RequestTemplate.validate(
              new RequestTemplate.Draft(
                  "PII tables",
                  null,
                  null,
                  List.of("PII"),
                  true,
                  new RequestTemplate.Form(
                      List.of("Fraud investigation"),
                      false, List.of(), null, null, true, null, false, 1, null))),
          "admin");
      AccessRequestStore.StoredRequest made = askFor("fraud investigation", 7);
      listed.approve(made.id(), OWNER, null, null);
      AccessRequestStore.StoredRequest done = listed.complete(made.id(), OWNER, grantFor(7));

      assertThat(grants.find(done.grantId()).orElseThrow().purpose())
          .isEqualTo("Fraud investigation");
    }
  }

  @Nested
  @DisplayName("sensitive data")
  class Sensitive {

    private com.mfec.dac.purpose.PurposeStore register;
    private com.mfec.dac.purpose.SensitiveData sensitive;
    private AccessRequestStore guarded;

    @BeforeEach
    void builtInWarning() {
      jdbi.useHandle(
          handle -> {
            handle.execute("DELETE FROM purpose WHERE created_by <> 'system'");
            handle.execute("UPDATE purpose SET status = 'ACTIVE', max_days = NULL");
            handle.execute(
                "UPDATE sensitive_data_rule SET built_in = true, include = '[]', exclude = '[]',"
                    + " mode = 'WARN', updated_by = 'system'");
            handle.execute("DELETE FROM audit_sensitive_data_rule WHERE actor <> 'system'");
          });
      register = new com.mfec.dac.purpose.PurposeStore(jdbi);
      sensitive = new com.mfec.dac.purpose.SensitiveData(jdbi, json);
      guarded =
          new AccessRequestStore(jdbi, json, grants, workflows, templates, register, sensitive);
    }

    private void mode(com.mfec.dac.purpose.SensitiveData.Mode mode) {
      sensitive.update(
          new com.mfec.dac.purpose.SensitiveData.Settings(true, List.of(), List.of(), mode),
          "Test " + mode,
          "admin");
    }

    private AccessRequestStore.StoredRequest askFor(String purpose) {
      return guarded.create(
          new AccessRequestStore.NewRequest(
              CUSTOMER, idOf("analyst_a"), "analyst_a", null, "Quarter-end reconciliation",
              purpose, 7, null, null, null, null),
          ANALYST_A);
    }

    private int stored() {
      return jdbi.withHandle(
          h -> h.createQuery("SELECT count(*) FROM access_request").mapTo(Integer.class).one());
    }

    @Test
    @DisplayName("under warn, a purpose that does not allow it is still asked for; the reviewer is told")
    void warnAsks() {
      assertThat(askFor("reporting").purpose()).isEqualTo("reporting");
    }

    @Test
    @DisplayName("under enforce, it is refused before anything is stored, and the refusal names the purpose")
    void enforceRefuses() {
      mode(com.mfec.dac.purpose.SensitiveData.Mode.ENFORCE);

      assertThatThrownBy(() -> askFor("reporting"))
          .isInstanceOf(AccessRequestStore.RequestException.class)
          .hasMessageContaining(CUSTOMER)
          .hasMessageContaining("Reporting is not a purpose sensitive data may be used for");
      assertThatThrownBy(() -> askFor(null))
          .isInstanceOf(AccessRequestStore.RequestException.class)
          .hasMessageContaining("no purpose was named");
      assertThat(stored()).isZero();
    }

    @Test
    @DisplayName("under enforce, a purpose that allows sensitive data is asked for as ever")
    void enforceAllows() {
      register.create(
          "fraud-review",
          new com.mfec.dac.purpose.PurposeStore.Details("Fraud review", null, null, true, null, null),
          "admin");
      mode(com.mfec.dac.purpose.SensitiveData.Mode.ENFORCE);

      assertThat(askFor("fraud-review").purpose()).isEqualTo("fraud-review");
    }

    @Test
    @DisplayName("off asks nothing of the rule")
    void offAsksNothing() {
      mode(com.mfec.dac.purpose.SensitiveData.Mode.OFF);

      assertThat(askFor("reporting").purpose()).isEqualTo("reporting");
    }
  }

  // ------------------------------------------------------------------ fixture

  private AccessRequestStore.StoredRequest ask(String who, String fqn, Integer days) {
    return requests.create(
        newRequest(who, fqn, "Quarter-end reconciliation", days),
        new AccessRequestStore.Actor(who, "admin".equals(who)));
  }

  /** Approves on the only open stage, then configures it as a grant. */
  private AccessRequestStore.StoredRequest approveAndGrant(
      UUID id, AccessRequestStore.Actor who, Integer days) {
    requests.approve(id, who, null, null);
    return requests.complete(id, who, grantFor(days));
  }

  private static AccessRequestStore.Completion grantFor(Integer days) {
    return new AccessRequestStore.Completion("GRANT", days, null, null);
  }

  /** A workflow on this scope (null: the organisation's default), named after it. */
  private WorkflowStore.Stored workflow(String scope, List<Seat> configurers, Stage... stages) {
    return workflows.create(
        AccessWorkflow.validate(
            new Draft(
                "Workflow on " + (scope == null ? "the organisation" : scope),
                null,
                scope,
                true,
                List.of(stages),
                configurers)),
        "admin");
  }

  private static Stage stage(
      int step, String name, Rule rule, Integer min, OnReject onReject, Seat... seats) {
    return new Stage(step, name, rule, min, onReject, List.of(seats));
  }

  private static Seat user(String name) {
    return new Seat(Kind.USER, name);
  }

  /** An app role, scoped to an FQN or (null) everywhere. */
  private static void role(Handle handle, String name, String role, String scope) {
    handle
        .createUpdate(
            """
            INSERT INTO app_role_assignment (principal_id, app_role, scope_fqn)
            SELECT id, :role, :scope FROM principal WHERE username = :name
            """)
        .bind("role", role)
        .bind("scope", scope)
        .bind("name", name)
        .execute();
  }

  /** Who a stage asks, as "username|via". */
  private static List<String> pool(AccessRequestStore.StoredRequest request, String stage) {
    return request.stages().stream()
        .filter(s -> s.name().equals(stage))
        .findFirst()
        .orElseThrow()
        .pool()
        .stream()
        .map(m -> m.username() + "|" + m.via())
        .toList();
  }

  private static AccessRequestStore.NewRequest newRequest(
      String who, String fqn, String reason, Integer days) {
    return new AccessRequestStore.NewRequest(
        fqn,
        idOf(who),
        who,
        null,
        reason,
        null,
        days,
        "SELECT * FROM customer",
        "Denied by default");
  }

  private boolean read(String who, String fqn) {
    return Boolean.TRUE.equals(decisions.decide(DecisionService.Ask.of(who, fqn)).getAllowed());
  }

  private static AccessRequestStore.RequestException.Kind kind(Throwable e) {
    assertThat(e).isInstanceOf(AccessRequestStore.RequestException.class);
    return ((AccessRequestStore.RequestException) e).kind();
  }

  private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .satisfies(e -> assertThat(kind(e)).isEqualTo(AccessRequestStore.RequestException.Kind.INVALID));
  }

  /** The audit trail of one request, newest first. */
  private static List<String> trail(UUID id) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT action FROM audit_access_request WHERE request_id = :id ORDER BY id DESC")
                .bind("id", id)
                .mapTo(String.class)
                .list());
  }

  private void activate(Policy document) {
    document.setEnvironment(
        Policy.Environment.fromValue(DecisionService.DEFAULT_ENVIRONMENT));
    UUID id = policies.create(document, "alice").id();
    materializer.materialize(id);
    policies.transition(id, "ACTIVE", "alice", "for the test");
  }

  private static Policy orgAllow(String name, String clearance) {
    Policy document = subscription(name, Policy.Effect.ALLOW);
    if (clearance != null) {
      document.setSubject(clearanceIs(clearance));
    }
    return document;
  }

  private static Policy orgDeny(String name, String clearance) {
    Policy document = subscription(name, Policy.Effect.DENY);
    document.setSubject(clearanceIs(clearance));
    return document;
  }

  private static Policy subscription(String name, Policy.Effect effect) {
    Policy document = new Policy();
    document.setName(name);
    document.setPolicyType(Policy.PolicyType.SUBSCRIPTION);
    document.setEffect(effect);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.ORG);
    document.setSelector(piiSelector());
    return document;
  }

  private static Policy orgMaskEmail(String name) {
    FacetCondition named = new FacetCondition();
    named.setFacet(FacetCondition.FacetType.COLUMN_NAME);
    named.setOperator(ResolvedRowPredicate.FacetOperator.EQ);
    named.setValue("email");
    AssetSelector columns = new AssetSelector();
    columns.setCondition(named);

    ColumnRule rule = new ColumnRule();
    rule.setAction(ColumnRule.Action.MASK);
    rule.setColumns(columns);
    MaskingSpec masking = new MaskingSpec();
    masking.setFunction(MaskingSpec.MaskingFunction.NULLIFY);
    rule.setMasking(masking);
    DataPolicy data = new DataPolicy();
    data.setColumnRules(List.of(rule));

    Policy document = new Policy();
    document.setName(name);
    document.setPolicyType(Policy.PolicyType.DATA);
    document.setEffect(Policy.Effect.ALLOW);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.ORG);
    document.setSelector(piiSelector());
    document.setData(data);
    return document;
  }

  private static SubjectRule clearanceIs(String clearance) {
    AttributeCondition condition = new AttributeCondition();
    condition.setKey("clearance");
    condition.setOperator(ResolvedRowPredicate.FacetOperator.EQ);
    condition.setValue(clearance);
    SubjectRule subject = new SubjectRule();
    subject.setAttributes(List.of(condition));
    return subject;
  }

  private static AssetSelector piiSelector() {
    FacetCondition condition = new FacetCondition();
    condition.setFacet(FacetCondition.FacetType.TAGS);
    condition.setOperator(ResolvedRowPredicate.FacetOperator.CONTAINS);
    condition.setValue("PII.Sensitive");
    AssetSelector selector = new AssetSelector();
    selector.setCondition(condition);
    return selector;
  }

  /** The endpoints, over this test's stores; review-less, as the create path needs none. */
  private AccessRequestResource resource() {
    return new AccessRequestResource(requests, eligibility);
  }

  /** An ordinary ask for one table, as the form sends it. */
  private static AccessRequestResource.Ask askFor(String fqn) {
    return new AccessRequestResource.Ask(fqn, null, "Quarter-end reconciliation", null, 7, null, null);
  }

  /** Signed in as this directory person, holding these ARAK roles and no scopes. */
  private static AuthenticatedUser signedIn(String username, String... roles) {
    return new AuthenticatedUser(
        idOf(username), username, username + "@example.test", username, "local", Set.of(roles),
        List.of());
  }

  private static SecurityContext as(String username, String... roles) {
    AuthenticatedUser user = signedIn(username, roles);
    SecurityContext security = mock(SecurityContext.class);
    when(security.getUserPrincipal()).thenReturn(user);
    return security;
  }

  /**
   * Takes the table off every registered source, as if its mapping had gone:
   * the catalog calls it not connected from then on.
   */
  private static void disconnect(String fqn) {
    int gone =
        jdbi.withHandle(
            handle ->
                handle
                    .createUpdate(
                        "DELETE FROM asset_fqn_map WHERE om_fqn = :fqn OR om_fqn LIKE :fqn || '.%'")
                    .bind("fqn", fqn)
                    .execute());
    assertThat(gone).as("mapped rows of " + fqn).isPositive();
  }

  private static UUID idOf(String username) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT id FROM principal WHERE username = :name")
                .bind("name", username)
                .mapTo(UUID.class)
                .one());
  }

  /**
   * Two analysts, the customer table's owner, an administrator, and a member of
   * the OpenMetadata team that owns the ledger.
   */
  private void directory() {
    jdbi.useHandle(
        handle -> {
          person(handle, "analyst_a", "L1");
          person(handle, "analyst_b", "L2");
          person(handle, "owner_o", "L2");
          person(handle, "admin", "L2");
          person(handle, "finance_lead", "L2");
          // The team comes from OpenMetadata, which is what makes membership
          // count as ownership (SubjectMatcher.isOwner reads teams, not groups).
          handle.execute(
              """
              INSERT INTO principal (principal_type, username, source, enabled)
              VALUES ('GROUP', 'Finance', 'openmetadata', true)
              """);
          handle.execute(
              """
              INSERT INTO group_member (group_id, member_id, source)
              SELECT g.id, m.id, 'openmetadata'
              FROM principal g, principal m
              WHERE g.username = 'Finance' AND m.username = 'finance_lead'
              """);
        });
  }

  private void administrator(String name) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO app_role_assignment (principal_id, app_role)
                    SELECT id, 'PLATFORM_ADMIN' FROM principal WHERE username = :name
                    """)
                .bind("name", name)
                .execute());
  }

  private static void person(Handle handle, String name, String clearance) {
    handle
        .createUpdate(
            """
            INSERT INTO principal (principal_type, username, email, source, enabled)
            VALUES ('USER', :name, :name || '@example.test', 'local', true)
            """)
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

  private void crawl() {
    AssetStore sink = new AssetStore(jdbi, json, Instant.now());
    List.of(
            container(SERVICE, "SERVICE", null, "prod-pg"),
            container(SALES, "DATABASE", SERVICE, "SalesDB"),
            container(DBO, "SCHEMA", SALES, "dbo"),
            table(
                CUSTOMER,
                "customer",
                true,
                new CrawledAsset.OwnerRow(CUSTOMER, "user", "owner_o", omId("owner_o"), true, null)),
            table(
                LEDGER,
                "ledger",
                false,
                new CrawledAsset.OwnerRow(LEDGER, "team", "Finance", omId("Finance"), true, null)),
            table(ORPHAN, "orphan", false, null))
        .forEach(sink::asset);
    sink.finished(new AssetCrawler.Stats());
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

  private static CrawledAsset table(
      String fqn, String name, boolean pii, CrawledAsset.OwnerRow owner) {
    CrawledAsset.AssetRow row =
        new CrawledAsset.AssetRow(
            omId(fqn), fqn, "TABLE", DBO, name, null, null, null, null, Map.of());
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
    return new CrawledAsset(row, columns, facets, owner == null ? List.of() : List.of(owner));
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
}
