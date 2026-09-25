package com.mfec.dac.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mfec.dac.catalog.AssetStore;
import com.mfec.dac.engine.EngineConfig;
import com.mfec.dac.engine.PolicyEngine;
import com.mfec.dac.om.crawl.AssetCrawler;
import com.mfec.dac.om.crawl.CrawledAsset;
import com.mfec.dac.om.facet.ExtractedFacet;
import com.mfec.dac.policy.AssetContextLoader;
import com.mfec.dac.policy.DecisionCache;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.policy.PolicyBindingMaterializer;
import com.mfec.dac.policy.PolicyStore;
import com.mfec.dac.policy.PrincipalLoader;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.AttributeCondition;
import com.mfec.dac.schema.entity.policy.ColumnRule;
import com.mfec.dac.schema.entity.policy.DataPolicy;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

  private static Jdbi jdbi;
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  private PolicyStore policies;
  private PolicyBindingMaterializer materializer;
  private GrantStore grants;
  private DecisionService decisions;
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
                       policy_version, policy_binding,
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
    requests = new AccessRequestStore(jdbi, grants, principals);
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
  }

  // ------------------------------------------------------------- deciding

  @Nested
  @DisplayName("deciding")
  class Deciding {

    @Test
    @DisplayName("an owner's yes writes a request grant that opens the table")
    void approveWritesAGrant() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", LEDGER, 7);
      assertThat(read("analyst_a", LEDGER)).isFalse();

      AccessRequestStore.StoredRequest decided =
          requests.approve(made.id(), TEAM_MEMBER, null, "For the audit");

      assertThat(decided.status()).isEqualTo("APPROVED");
      assertThat(decided.decidedBy()).isEqualTo("finance_lead");
      assertThat(decided.decisionNote()).isEqualTo("For the audit");

      GrantStore.StoredGrant grant = grants.find(decided.grantId()).orElseThrow();
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
      assertThat(trail(made.id())).containsExactly("APPROVE", "REQUEST");
      assertThat(requests.openRequest(LEDGER, "analyst_a")).isEmpty();
    }

    @Test
    @DisplayName("revoking the grant an approval wrote closes the table again")
    void revokeUndoesApproval() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", LEDGER, 7);
      AccessRequestStore.StoredRequest decided = requests.approve(made.id(), ADMIN, null, null);

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

      // A decided request waits for nothing.
      requests.approve(own.id(), new AccessRequestStore.Actor("owner_o", true), null, null);
      assertThat(requests.find(own.id(), ADMIN).stranded()).isFalse();
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

      AccessRequestStore.StoredRequest decided = requests.approve(made.id(), ADMIN, null, null);
      // Asked for "until revoked", granted as asked.
      assertThat(grants.find(decided.grantId()).orElseThrow().validUntil()).isNull();
      assertThat(read("analyst_a", ORPHAN)).isTrue();
    }

    @Test
    @DisplayName("an owner may shorten what was asked, never lengthen it")
    void shortenOnly() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);

      assertInvalid(() -> requests.approve(made.id(), OWNER, 30, null));
      assertInvalid(() -> requests.approve(made.id(), OWNER, 0, null));
      // Still open after both refusals, and nothing granted.
      assertThat(requests.find(made.id(), OWNER).pending()).isTrue();
      assertThat(grants.onAsset(CUSTOMER)).isEmpty();

      AccessRequestStore.StoredRequest decided = requests.approve(made.id(), OWNER, 2, null);
      GrantStore.StoredGrant grant = grants.find(decided.grantId()).orElseThrow();
      assertThat(Duration.between(grant.validFrom(), grant.validUntil())).isEqualTo(Duration.ofDays(2));
    }

    @Test
    @DisplayName("an open-ended ask may be given a window")
    void boundAnOpenAsk() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", ORPHAN, null);
      AccessRequestStore.StoredRequest decided = requests.approve(made.id(), ADMIN, 14, null);
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
      assertThat(trail(made.id())).containsExactly("REJECT", "REQUEST");
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
      assertThat(after.minePending()).isEqualTo(1);

      // The owner does not hear about what the owner did.
      assertThat(requests.notices(OWNER, 20).items())
          .extracting(AccessRequestStore.Notice::kind)
          .containsExactly("REQUESTED");
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
      assertThat(a.blockedBy()).startsWith("l2-only");
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
      requests.approve(made.id(), OWNER, null, null);
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
      assertThat(eligibility.check("analyst_b", CUSTOMER, null, null).readable()).isTrue();
    }

    @Test
    @DisplayName("a DENY that arrives after the request: approving still does not open the table")
    void denyAfterAsking() {
      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      activate(orgDeny("no-l1", "L1"));

      // The owner may still answer -- the workflow does not second-guess them --
      // but the grant they write composes like every other grant and loses.
      requests.approve(made.id(), OWNER, null, null);
      assertThat(read("analyst_a", CUSTOMER)).isFalse();
      assertThat(eligibility.check("analyst_a", CUSTOMER, null, null).blockedBy()).startsWith("no-l1");
    }

    @Test
    @DisplayName("an approved request opens the table and leaves its masks on")
    void approvalIsNotAnUnmask() {
      activate(orgMaskEmail("mask-pii"));
      assertThat(eligibility.check("analyst_a", CUSTOMER, null, null).requestable()).isTrue();

      AccessRequestStore.StoredRequest made = ask("analyst_a", CUSTOMER, 7);
      requests.approve(made.id(), OWNER, null, null);

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

  // ------------------------------------------------------------------ fixture

  private AccessRequestStore.StoredRequest ask(String who, String fqn, Integer days) {
    return requests.create(
        newRequest(who, fqn, "Quarter-end reconciliation", days),
        new AccessRequestStore.Actor(who, "admin".equals(who)));
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
