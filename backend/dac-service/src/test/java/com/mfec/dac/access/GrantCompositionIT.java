package com.mfec.dac.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

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
import com.mfec.dac.schema.entity.policy.RowFilter;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * What a direct grant can and cannot do to a decision (FR-7, FR-3.1.4).
 *
 * <p>A grant is the one thing in the platform that hands access to a named
 * person without a policy being written, which makes it the one thing that
 * could quietly turn every global policy into a suggestion. The three
 * properties asserted here are what stops that, and none of them is visible
 * from the grant code alone -- they are properties of the grant, the engine and
 * the composition rules together, so they are tested together against a real
 * database:
 *
 * <ol>
 *   <li>a grant opens a table that nothing else speaks to,
 *   <li>a grant cannot pass a higher layer that refuses this principal,
 *   <li>a grant loses to a DENY, always.
 * </ol>
 *
 * <p>The fourth case is the deliberate hole: a higher layer that set {@code
 * allowLocalOverride} has consented in advance, and a grant may then pass it.
 * It is asserted for the same reason as the other three -- if it ever stopped
 * working, the escape hatch the model documents would be fiction.
 */
@Testcontainers
class GrantCompositionIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String SERVICE = "prod-pg";
  private static final String SALES = "prod-pg.SalesDB";
  private static final String DBO = "prod-pg.SalesDB.dbo";

  /** Tagged PII, so an ORG policy written against the tag reaches it. */
  private static final String CUSTOMER = "prod-pg.SalesDB.dbo.customer";

  /** Untagged, so no policy binds to it and only a grant can open it. */
  private static final String LEDGER = "prod-pg.SalesDB.dbo.ledger";

  private static Jdbi jdbi;
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  private PolicyStore policies;
  private PolicyBindingMaterializer materializer;
  private GrantStore grants;
  private DecisionService decisions;

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
              TRUNCATE policy_version, policy_binding, access_grant, audit_grant_change,
                       row_entitlement, enforcement_state, asset_facet, asset_owner,
                       asset_fqn_map, asset_column, asset, policy, principal_attribute,
                       app_role_assignment, group_member, principal CASCADE
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
    decisions =
        new DecisionService(
            jdbi,
            contexts,
            new PrincipalLoader(),
            policies,
            grants,
            new PolicyEngine(EngineConfig.defaults()),
            // Disabled on purpose: these tests change the world between two
            // decisions about the same principal and asset, which is exactly
            // what a cache is built to hide.
            DecisionCache.disabled());

    crawl();
    directory();
  }

  // ------------------------------------------------- 1. where nothing binds

  @Nested
  @DisplayName("a table no policy reaches")
  class Unbound {

    @Test
    @DisplayName("is closed to everyone until a grant opens it")
    void grantOpensIt() {
      // Default deny (FR-5.1): nothing is said about this table, so nobody
      // reaches it. This half matters as much as the other -- if the fixture
      // were already allowing, the next assertion would pass without the grant
      // doing anything.
      assertThat(decisions.decide(DecisionService.Ask.of("analyst_a", LEDGER)).getAllowed())
          .isFalse();

      grants.grant(
          new GrantStore.NewGrant(
              LEDGER, idOf("analyst_a"), null, null, "Quarter-end reconciliation", "owner_o"));

      PolicyDecision after = decisions.decide(DecisionService.Ask.of("analyst_a", LEDGER));
      assertThat(after.getAllowed()).isTrue();
      // And the decision says which grant did it, not merely that something did.
      assertThat(after.getReasons())
          .extracting(reason -> String.valueOf(reason.getPolicyName()))
          .anySatisfy(name -> assertThat(name).startsWith("grant:"));
    }

    @Test
    @DisplayName("opens for the holder alone, not for their colleagues")
    void notContagious() {
      grants.grant(
          new GrantStore.NewGrant(
              LEDGER, idOf("analyst_a"), null, null, "Quarter-end reconciliation", "owner_o"));

      assertThat(decisions.decide(DecisionService.Ask.of("analyst_b", LEDGER)).getAllowed())
          .isFalse();
    }

    @Test
    @DisplayName("opens for everyone in a group when the grant names the group")
    void groupGrant() {
      grants.grant(
          new GrantStore.NewGrant(
              LEDGER, idOf("finance-team"), null, null, "The whole team is on this", "owner_o"));

      // Granting to a group is the one that keeps working when somebody joins
      // the team, so membership has to be resolved rather than the group name
      // compared against a username.
      assertThat(decisions.decide(DecisionService.Ask.of("analyst_a", LEDGER)).getAllowed())
          .isTrue();
      assertThat(decisions.decide(DecisionService.Ask.of("analyst_b", LEDGER)).getAllowed())
          .isTrue();
    }
  }

  // ------------------------------------------- 2. and 3. against the layers

  @Nested
  @DisplayName("a grant meeting a policy")
  class AgainstPolicies {

    @Test
    @DisplayName("cannot pass an ORG layer that refuses this principal")
    void cannotPassARefusingGate() {
      // An ORG ALLOW that only lets L2 through. It binds this table, so the ORG
      // layer becomes a gate, and analyst_a (L1) does not satisfy it.
      activate(orgAllowForClearance("l2-only", "L2"));

      assertThat(decisions.decide(DecisionService.Ask.of("analyst_a", CUSTOMER)).getAllowed())
          .isFalse();

      grants.grant(
          new GrantStore.NewGrant(
              CUSTOMER, idOf("analyst_a"), null, null, "Owner asked for it", "owner_o"));

      PolicyDecision after = decisions.decide(DecisionService.Ask.of("analyst_a", CUSTOMER));

      // This is FR-3.1.4. The grant is real, it is recorded, it is in the
      // trail, and it still does not let analyst_a in -- because layers
      // intersect, and a grant sits at the TABLE layer beneath a gate that has
      // not consented to being relaxed. If this ever flips, every global policy
      // in the estate becomes advisory and nothing else in the suite notices.
      assertThat(after.getAllowed()).isFalse();
      assertThat(grants.onAsset(CUSTOMER)).hasSize(1);
      assertThat(grants.historyFor(CUSTOMER, 10))
          .extracting(GrantStore.HistoryEntry::action)
          .containsExactly("GRANT");
    }

    @Test
    @DisplayName("passes a gate that consented in advance to being relaxed")
    void passesAnOverridableGate() {
      Policy document = orgAllowForClearance("l2-only-overridable", "L2");
      document.setAllowLocalOverride(true);
      activate(document);

      grants.grant(
          new GrantStore.NewGrant(
              CUSTOMER, idOf("analyst_a"), null, null, "Owner asked for it", "owner_o"));

      // The documented escape hatch. It is the difference between "strictest
      // wins" and "strictest wins unless the stricter policy said otherwise",
      // and the second is what the model actually promises.
      assertThat(decisions.decide(DecisionService.Ask.of("analyst_a", CUSTOMER)).getAllowed())
          .isTrue();
    }

    @Test
    @DisplayName("loses to a DENY even where the gate would have let them in")
    void losesToDeny() {
      activate(orgAllowForClearance("everyone", null));
      activate(orgDenyForClearance("no-l1", "L1"));

      grants.grant(
          new GrantStore.NewGrant(
              CUSTOMER, idOf("analyst_a"), null, null, "Owner asked for it", "owner_o"));

      // DENY wins over everything, including an overridable gate and a grant.
      // There is no configuration that reverses this, which is why the test
      // does not take one.
      assertThat(decisions.decide(DecisionService.Ask.of("analyst_a", CUSTOMER)).getAllowed())
          .isFalse();
      // analyst_b is L2, so the same fixture still lets somebody through --
      // otherwise this would pass just as well with a broken ALLOW.
      assertThat(decisions.decide(DecisionService.Ask.of("analyst_b", CUSTOMER)).getAllowed())
          .isTrue();
    }

    @Test
    @DisplayName("does not shut out the people a policy already let in")
    void addsWithoutNarrowing() {
      activate(orgAllowForClearance("everyone", null));

      // analyst_b can already read this table, and nobody touched their
      // access. Giving analyst_a a grant has to leave that alone.
      assertThat(decisions.decide(DecisionService.Ask.of("analyst_b", CUSTOMER)).getAllowed())
          .isTrue();

      grants.grant(
          new GrantStore.NewGrant(
              CUSTOMER, idOf("analyst_a"), null, null, "Owner asked for it", "owner_o"));

      // Composition is by intersection, so every layer that has an opinion has
      // to allow -- and the trap is that a grant looks exactly like an opinion
      // about the table layer. It is not: it names one person and says nothing
      // about anybody else. Read the other way, one owner handing one analyst
      // one table would revoke that table from the whole organisation, and it
      // would do it silently, which is why this is asserted rather than left
      // to the deny test's control.
      assertThat(decisions.decide(DecisionService.Ask.of("analyst_b", CUSTOMER)).getAllowed())
          .isTrue();
      assertThat(decisions.decide(DecisionService.Ask.of("analyst_a", CUSTOMER)).getAllowed())
          .isTrue();
    }
  }

  // --------------------------------------------------------- the life cycle

  @Nested
  @DisplayName("a grant's window")
  class Lifecycle {

    @Test
    @DisplayName("closes access the moment it lapses, without waiting for a job")
    void lapsesOnItsOwn() {
      Instant from = Instant.now().minus(2, ChronoUnit.DAYS);
      Instant until = Instant.now().minus(1, ChronoUnit.HOURS);
      jdbi.useHandle(
          handle ->
              handle
                  .createUpdate(
                      """
                      INSERT INTO access_grant
                        (asset_fqn, principal_id, source, valid_from, valid_until,
                         reason, granted_by)
                      VALUES (:fqn, :principal, 'manual', :from, :until, 'Short loan', 'owner_o')
                      """)
                  .bind("fqn", LEDGER)
                  .bind("principal", idOf("analyst_a"))
                  .bind("from", from)
                  .bind("until", until)
                  .execute());

      // The expiry job has not run. Access is gone anyway, because the engine
      // reads the window rather than the tombstone -- the job writes history,
      // it does not enforce anything. A platform where access outlives its
      // window until a scheduler wakes up has no meaningful expiry at all.
      assertThat(decisions.decide(DecisionService.Ask.of("analyst_a", LEDGER)).getAllowed())
          .isFalse();
    }

    @Test
    @DisplayName("is not yet open before it starts")
    void notYetOpen() {
      grants.grant(
          new GrantStore.NewGrant(
              LEDGER,
              idOf("analyst_a"),
              Instant.now().plus(2, ChronoUnit.DAYS),
              null,
              "Starts Monday",
              "owner_o"));

      assertThat(decisions.decide(DecisionService.Ask.of("analyst_a", LEDGER)).getAllowed())
          .isFalse();
    }

    @Test
    @DisplayName("revoking shuts the door and leaves the row behind")
    void revokeIsATombstone() {
      GrantStore.StoredGrant given =
          grants.grant(
              new GrantStore.NewGrant(
                  LEDGER, idOf("analyst_a"), null, null, "Quarter-end", "owner_o"));
      assertThat(decisions.decide(DecisionService.Ask.of("analyst_a", LEDGER)).getAllowed())
          .isTrue();

      grants.revoke(given.id(), "owner_o", "Left the team");

      assertThat(decisions.decide(DecisionService.Ask.of("analyst_a", LEDGER)).getAllowed())
          .isFalse();
      // Deleted rows cannot answer "who had access last March", so the row
      // stays and carries who closed it and why.
      GrantStore.StoredGrant after = grants.find(given.id()).orElseThrow();
      assertThat(after.revokedAt()).isNotNull();
      assertThat(after.revokedBy()).isEqualTo("owner_o");
      assertThat(after.revokeReason()).isEqualTo("Left the team");
      assertThat(grants.historyFor(LEDGER, 10))
          .extracting(GrantStore.HistoryEntry::action)
          .containsExactly("REVOKE", "GRANT");
    }

    @Test
    @DisplayName("an edit replaces the grant: the old row is a tombstone, access never drops")
    void amendReplaces() {
      Instant started = Instant.now().minus(3, ChronoUnit.DAYS);
      GrantStore.StoredGrant given =
          grants.grant(
              new GrantStore.NewGrant(
                  LEDGER,
                  idOf("analyst_a"),
                  started,
                  Instant.now().plus(1, ChronoUnit.DAYS),
                  "Quarter-end",
                  "owner_o"));
      Instant later = Instant.now().plus(30, ChronoUnit.DAYS);

      GrantStore.StoredGrant next =
          grants
              .amend(
                  given.id(),
                  Instant.now().plus(5, ChronoUnit.DAYS),
                  later,
                  "Audit ran long",
                  "owner_o")
              .orElseThrow();

      assertThat(next.id()).isNotEqualTo(given.id());
      assertThat(next.principalId()).isEqualTo(given.principalId());
      assertThat(next.assetFqn()).isEqualTo(LEDGER);
      assertThat(next.validUntil()).isCloseTo(later, within(1, ChronoUnit.MILLIS));
      // The grant had already started, so its start is history, not a setting.
      assertThat(next.validFrom()).isEqualTo(given.validFrom());
      assertThat(next.reason()).isEqualTo("Audit ran long");
      assertThat(next.grantedBy()).isEqualTo("owner_o");

      GrantStore.StoredGrant old = grants.find(given.id()).orElseThrow();
      assertThat(old.revokedAt()).isNotNull();
      assertThat(old.revokeReason()).isEqualTo("Replaced by an edit: Audit ran long");
      assertThat(decisions.decide(DecisionService.Ask.of("analyst_a", LEDGER)).getAllowed())
          .isTrue();
      assertThat(grants.historyFor(LEDGER, 10))
          .extracting(GrantStore.HistoryEntry::action)
          .containsExactlyInAnyOrder("GRANT", "REVOKE", "GRANT");

      // A tombstone is not brought back by editing it.
      assertThat(grants.amend(given.id(), null, null, "again", "owner_o")).isEmpty();
    }

    @Test
    @DisplayName("an edit can move a start that has not come yet, and cannot end in the past")
    void amendWindow() {
      GrantStore.StoredGrant given =
          grants.grant(
              new GrantStore.NewGrant(
                  LEDGER,
                  idOf("analyst_a"),
                  Instant.now().plus(10, ChronoUnit.DAYS),
                  null,
                  "Starts later",
                  "owner_o"));
      Instant sooner = Instant.now().plus(1, ChronoUnit.DAYS);

      GrantStore.StoredGrant next =
          grants.amend(given.id(), sooner, null, "Sooner", "owner_o").orElseThrow();
      assertThat(next.validFrom()).isCloseTo(sooner, within(1, ChronoUnit.MILLIS));
      assertThat(next.validUntil()).isNull();

      assertThatThrownBy(
              () ->
                  grants.amend(
                      next.id(),
                      null,
                      Instant.now().minus(1, ChronoUnit.HOURS),
                      "Backdated",
                      "owner_o"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> grants.amend(next.id(), null, null, " ", "owner_o"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(grants.find(next.id()).orElseThrow().revokedAt()).isNull();
    }

    @Test
    @DisplayName("the expiry job tombstones what has lapsed and nothing else")
    void expiryJobIsNarrow() {
      // A grant cannot be written already over, so the job is run as of a
      // moment after this one lapses rather than the grant being backdated.
      Instant now = Instant.now().plus(2, ChronoUnit.HOURS);
      GrantStore.StoredGrant lapsing =
          grants.grant(
              new GrantStore.NewGrant(
                  LEDGER,
                  idOf("analyst_a"),
                  now.minus(2, ChronoUnit.DAYS),
                  now.minus(1, ChronoUnit.HOURS),
                  "Short loan",
                  "owner_o"));
      GrantStore.StoredGrant standing =
          grants.grant(
              new GrantStore.NewGrant(
                  LEDGER, idOf("analyst_b"), null, null, "Open ended", "owner_o"));

      assertThat(grants.expire(now)).isOne();

      // The open-ended one is the trap: an expiry query that matches every live
      // grant rather than only the ones with a closed window would revoke it
      // here, and the only symptom would be somebody losing access overnight.
      assertThat(grants.find(standing.id()).orElseThrow().revokedAt()).isNull();

      GrantStore.StoredGrant closed = grants.find(lapsing.id()).orElseThrow();
      assertThat(closed.revokedBy()).isEqualTo("system");
      assertThat(closed.revokeReason()).isEqualTo("expired");
      assertThat(grants.historyFor(LEDGER, 10))
          .extracting(GrantStore.HistoryEntry::action)
          .containsExactly("EXPIRE", "GRANT", "GRANT");

      // Running it twice must not write a second tombstone for the same row.
      assertThat(grants.expire(now)).isZero();
    }
  }

  // ------------------------------------------------------------ what it refuses

  @Nested
  @DisplayName("what a grant refuses to be")
  class Rejected {

    @Test
    @DisplayName("a grant without a reason is not written")
    void reasonIsMandatory() {
      assertThatThrownBy(
              () ->
                  grants.grant(
                      new GrantStore.NewGrant(
                          LEDGER, idOf("analyst_a"), null, null, "  ", "owner_o")))
          .isInstanceOf(IllegalArgumentException.class);

      // Rejected before anything is written, so a failed grant leaves no
      // half-row and no audit entry claiming access was given.
      assertThat(grants.onAsset(LEDGER)).isEmpty();
      assertThat(grants.historyFor(LEDGER, 10)).isEmpty();
    }

    @Test
    @DisplayName("a window that ends before it starts is not written")
    void windowMustBeCoherent() {
      Instant now = Instant.now();
      assertThatThrownBy(
              () ->
                  grants.grant(
                      new GrantStore.NewGrant(
                          LEDGER,
                          idOf("analyst_a"),
                          now,
                          now.minusSeconds(1),
                          "Backwards",
                          "owner_o")))
          .isInstanceOf(IllegalArgumentException.class);

      assertThat(grants.onAsset(LEDGER)).isEmpty();
    }

    @Test
    @DisplayName("a window that is already over is not written")
    void windowMustNotBeOver() {
      Instant now = Instant.now();
      assertThatThrownBy(
              () ->
                  grants.grant(
                      new GrantStore.NewGrant(
                          LEDGER,
                          idOf("analyst_a"),
                          now.minus(30, ChronoUnit.DAYS),
                          now.minus(1, ChronoUnit.DAYS),
                          "Last month's close",
                          "owner_o")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already past");

      assertThat(grants.onAsset(LEDGER)).isEmpty();
      assertThat(grants.historyFor(LEDGER, 10)).isEmpty();

      // A start in the past is fine while the end is still ahead: that is a
      // grant backdated to when the work began, and it is live now.
      grants.grant(
          new GrantStore.NewGrant(
              LEDGER,
              idOf("analyst_a"),
              now.minus(3, ChronoUnit.DAYS),
              now.plus(1, ChronoUnit.DAYS),
              "Since Monday",
              "owner_o"));
      assertThat(grants.onAsset(LEDGER)).hasSize(1);
    }
  }

  // ------------------------------------- 5. against the other half of the model

  @Nested
  @DisplayName("a grant meeting a data policy")
  class AgainstDataPolicies {

    @Test
    @DisplayName("opens the table without unmasking anything inside it")
    void grantIsNotAnUnmask() {
      // The confusion this guards against is a natural one, because the UI
      // calls both of them "access": an owner clicks Grant on a table and
      // reasonably expects the person to see the table. They do -- and the
      // columns an ORG data policy masks stay masked, because a grant is a
      // subscription and subscriptions have nothing to say about columns.
      //
      // Getting this wrong would be the worst failure in the platform: an
      // ordinary, frequent, one-click action that silently strips masking from
      // PII, with a successful query and no error anywhere.
      activate(orgMaskEmail("mask-pii"));
      grants.grant(
          new GrantStore.NewGrant(
              CUSTOMER, idOf("analyst_a"), null, null, "Quarter-end reconciliation", "owner_o"));

      PolicyDecision decision = decisions.decide(DecisionService.Ask.of("analyst_a", CUSTOMER));
      assertThat(decision.getAllowed()).isTrue();
      assertThat(decision.getColumnMasks())
          .singleElement()
          .satisfies(
              mask -> {
                assertThat(mask.getColumn()).isEqualTo("email");
                assertThat(mask.getMasking().getFunction())
                    .isEqualTo(MaskingSpec.MaskingFunction.NULLIFY);
              });
    }

    @Test
    @DisplayName("carries the row filter that was already on the table")
    void grantInheritsRowFilters() {
      // Same principle one level down. A grant says who may reach the table; it
      // never says which rows, so a row filter written for everybody still
      // applies to the person who arrived through a grant.
      activate(orgRowFilter("branch-rows"));
      grants.grant(
          new GrantStore.NewGrant(
              CUSTOMER, idOf("analyst_a"), null, null, "Quarter-end reconciliation", "owner_o"));

      PolicyDecision decision = decisions.decide(DecisionService.Ask.of("analyst_a", CUSTOMER));
      assertThat(decision.getAllowed()).isTrue();
      assertThat(decision.getRowPredicates())
          .singleElement()
          .satisfies(predicate -> assertThat(predicate.getColumn()).isEqualTo("email"));
    }

    @Test
    @DisplayName("two grants to the same person are one access, not two")
    void grantsUnion() {
      // A grant direct to the person and another to a team they are in. Union,
      // and an idempotent one: the answer is the same allow, and the masks are
      // not applied twice or dropped because two grants disagreed about
      // nothing. Duplicate grants are ordinary -- an owner grants, forgets, and
      // grants again -- so this is a case the system meets in its first week.
      activate(orgMaskEmail("mask-pii"));
      grants.grant(
          new GrantStore.NewGrant(
              CUSTOMER, idOf("analyst_a"), null, null, "Quarter-end reconciliation", "owner_o"));
      grants.grant(
          new GrantStore.NewGrant(
              CUSTOMER, idOf("finance-team"), null, null, "The whole team is on this", "owner_o"));

      PolicyDecision decision = decisions.decide(DecisionService.Ask.of("analyst_a", CUSTOMER));
      assertThat(decision.getAllowed()).isTrue();
      assertThat(decision.getColumnMasks()).hasSize(1);
    }

    @Test
    @DisplayName("revoking one of two grants leaves the other standing")
    void revokeIsNotUnion() {
      // The complement half of the same case, and the one an owner gets wrong:
      // they revoke the grant they remember making and assume access is gone.
      // It is not, because the team grant is still there -- so the assertion is
      // that the platform keeps the access, and the owner has to be shown both.
      UUID direct =
          grants
              .grant(
                  new GrantStore.NewGrant(
                      CUSTOMER, idOf("analyst_a"), null, null, "Quarter-end", "owner_o"))
              .id();
      grants.grant(
          new GrantStore.NewGrant(
              CUSTOMER, idOf("finance-team"), null, null, "The whole team is on this", "owner_o"));

      grants.revoke(direct, "owner_o", "Finished the reconciliation");

      assertThat(decisions.decide(DecisionService.Ask.of("analyst_a", CUSTOMER)).getAllowed())
          .isTrue();
    }
  }

  // ------------------------------------------------------------------ fixture

  /** An ORG data policy that nullifies {@code email} wherever PII is tagged. */
  private static Policy orgMaskEmail(String name) {
    ColumnRule rule = new ColumnRule();
    rule.setAction(ColumnRule.Action.MASK);
    rule.setColumns(columnNamed("email"));
    MaskingSpec masking = new MaskingSpec();
    masking.setFunction(MaskingSpec.MaskingFunction.NULLIFY);
    rule.setMasking(masking);

    DataPolicy data = new DataPolicy();
    data.setColumnRules(List.of(rule));
    return dataPolicy(name, data);
  }

  /** An ORG data policy filtering rows by an attribute the principal carries. */
  private static Policy orgRowFilter(String name) {
    RowFilter filter = new RowFilter();
    filter.setKind(RowFilter.Kind.ATTRIBUTE_COMPARE);
    filter.setColumn("email");
    filter.setUserAttribute("clearance");

    DataPolicy data = new DataPolicy();
    data.setRowFilters(List.of(filter));
    return dataPolicy(name, data);
  }

  private static Policy dataPolicy(String name, DataPolicy data) {
    Policy document = new Policy();
    document.setName(name);
    document.setPolicyType(Policy.PolicyType.DATA);
    document.setEffect(Policy.Effect.ALLOW);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.ORG);
    document.setSelector(piiSelector());
    document.setData(data);
    return document;
  }

  private static AssetSelector columnNamed(String name) {
    FacetCondition condition = new FacetCondition();
    condition.setFacet(FacetCondition.FacetType.COLUMN_NAME);
    condition.setOperator(ResolvedRowPredicate.FacetOperator.EQ);
    condition.setValue(name);
    AssetSelector selector = new AssetSelector();
    selector.setCondition(condition);
    return selector;
  }

  /**
   * Publishes a policy into the environment the engine actually enforces.
   *
   * <p>Set here rather than left to the store, which defaults a policy with
   * no environment to {@code dev} while every decision is taken in
   * {@code prod}. A gate authored that way binds, activates, reads back
   * correctly and is never consulted -- so a test that forgot this would
   * assert about composition while composing against nothing.
   */
  private void activate(Policy document) {
    document.setEnvironment(
        Policy.Environment.fromValue(DecisionService.DEFAULT_ENVIRONMENT));
    UUID id = policies.create(document, "alice").id();
    materializer.materialize(id);
    policies.transition(id, "ACTIVE", "alice", "for the test");
  }

  /**
   * An ORG-level ALLOW over the PII tag.
   *
   * @param clearance when set, only principals carrying it satisfy the layer,
   *     which is what turns the layer into a gate somebody can fail
   */
  private static Policy orgAllowForClearance(String name, String clearance) {
    Policy document = new Policy();
    document.setName(name);
    document.setPolicyType(Policy.PolicyType.SUBSCRIPTION);
    document.setEffect(Policy.Effect.ALLOW);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.ORG);
    document.setSelector(piiSelector());
    if (clearance != null) {
      document.setSubject(clearanceIs(clearance));
    }
    return document;
  }

  private static Policy orgDenyForClearance(String name, String clearance) {
    Policy document = new Policy();
    document.setName(name);
    document.setPolicyType(Policy.PolicyType.SUBSCRIPTION);
    document.setEffect(Policy.Effect.DENY);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.ORG);
    document.setSelector(piiSelector());
    document.setSubject(clearanceIs(clearance));
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

  /** Two analysts on different clearances, and the team they are both in. */
  private void directory() {
    jdbi.useHandle(
        handle -> {
          person(handle, "analyst_a", "L1");
          person(handle, "analyst_b", "L2");
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

  /** One tagged table and one untagged one, so "nothing binds" is a real state. */
  private void crawl() {
    AssetStore sink = new AssetStore(jdbi, json, Instant.now());
    List.of(
            container(SERVICE, "SERVICE", null, "prod-pg"),
            container(SALES, "DATABASE", SERVICE, "SalesDB"),
            container(DBO, "SCHEMA", SALES, "dbo"),
            table(CUSTOMER, "customer", true),
            table(LEDGER, "ledger", false))
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

  private static CrawledAsset table(String fqn, String name, boolean pii) {
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
}
