package com.mfec.dac.purpose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.purpose.PurposeStore.Change;
import com.mfec.dac.purpose.PurposeStore.Details;
import com.mfec.dac.purpose.PurposeStore.LegalBasis;
import com.mfec.dac.purpose.PurposeStore.Purpose;
import com.mfec.dac.purpose.PurposeStore.Refused;
import com.mfec.dac.purpose.PurposeStore.Usage;
import java.util.List;
import java.util.Map;
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
 * The register of purposes against a real PostgreSQL (FR-21, M31).
 *
 * <p>What matters most is that a purpose is one thing everywhere: the key a
 * policy stores is the key a query declares, whatever case either was typed
 * in, and a purpose nobody listed -- or one retired -- is refused rather than
 * matched against nothing.
 */
@Testcontainers
class PurposeStoreIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static Jdbi jdbi;
  private PurposeStore purposes;

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
              TRUNCATE access_request_vote, access_request_stage, access_request,
                       access_request_template, audit_decision, policy_version, policy_binding,
                       policy CASCADE
              """);
          // Back to what V43 made: the three the query page offered.
          handle.execute("DELETE FROM purpose WHERE created_by <> 'system'");
          handle.execute(
              """
              UPDATE purpose
                 SET status = 'ACTIVE', legal_basis = NULL, sensitive_allowed = false, owner = NULL,
                     max_days = NULL, updated_by = 'system',
                     description = 'Offered on the query page before the register existed.',
                     name = CASE key WHEN 'fraud-analysis' THEN 'Fraud analysis'
                                     WHEN 'reporting' THEN 'Reporting' ELSE 'Support' END
               WHERE created_by = 'system'
              """);
          handle.execute("DELETE FROM audit_purpose WHERE actor <> 'system'");
        });
    purposes = new PurposeStore(jdbi);
  }

  private static Details details(String name) {
    return new Details(name, null, null, false, null, null);
  }

  @Test
  @DisplayName("the migration lists the three the query page offered, each with its CREATE")
  void seeded() {
    assertThat(purposes.list())
        .extracting(Purpose::key)
        .containsExactly("fraud-analysis", "reporting", "support");
    assertThat(purposes.list()).allMatch(Purpose::active);
    assertThat(purposes.history("reporting"))
        .extracting(Change::action)
        .containsExactly("CREATE");
  }

  @Test
  @DisplayName("a new purpose keeps what the PDPA asks of it, and its history says who made it")
  void creates() {
    Purpose made =
        purposes.create(
            "credit-review",
            new Details(
                "Credit review",
                "Deciding a loan",
                LegalBasis.CONTRACT,
                true,
                "Risk office",
                30),
            "alice");

    assertThat(made.key()).isEqualTo("credit-review");
    assertThat(made.legalBasis()).isEqualTo(LegalBasis.CONTRACT);
    assertThat(made.sensitiveAllowed()).isTrue();
    assertThat(made.maxDays()).isEqualTo(30);
    assertThat(made.createdBy()).isEqualTo("alice");
    assertThat(purposes.find("CREDIT-Review")).map(Purpose::name).contains("Credit review");
    List<Change> history = purposes.history("credit-review");
    assertThat(history).hasSize(1);
    assertThat(history.get(0).actor()).isEqualTo("alice");
    assertThat(history.get(0).after().get("legalBasis").asText()).isEqualTo("CONTRACT");
  }

  @Test
  @DisplayName("a key or a name already listed is refused, and a retired key says to reinstate it")
  void refusesTaken() {
    assertThatThrownBy(() -> purposes.create("reporting", details("Monthly reporting"), "alice"))
        .isInstanceOf(Refused.class)
        .extracting(e -> ((Refused) e).reason())
        .isEqualTo(Refused.Reason.CONFLICT);
    assertThatThrownBy(() -> purposes.create("reports", details("REPORTING"), "alice"))
        .isInstanceOf(Refused.class)
        .hasMessageContaining("is already called");

    purposes.retire("support", "Merged into reporting", "alice");
    assertThatThrownBy(() -> purposes.create("support", details("Customer support"), "alice"))
        .isInstanceOf(Refused.class)
        .hasMessageContaining("reinstate it instead");
  }

  @Test
  @DisplayName("an edit is audited with before and after; one that changes nothing writes nothing")
  void updates() {
    Purpose seeded = purposes.find("reporting").orElseThrow();
    Purpose same =
        purposes.update(
            "reporting",
            new Details(
                seeded.name(), seeded.description(), seeded.legalBasis(), seeded.sensitiveAllowed(),
                seeded.owner(), seeded.maxDays()),
            "bob");
    assertThat(same.updatedBy()).isEqualTo("system");
    assertThat(purposes.history("reporting")).hasSize(1);

    Purpose changed =
        purposes.update(
            "reporting",
            new Details("Reporting", "Monthly figures", LegalBasis.LEGITIMATE_INTEREST, false, null, 90),
            "bob");

    assertThat(changed.legalBasis()).isEqualTo(LegalBasis.LEGITIMATE_INTEREST);
    assertThat(changed.maxDays()).isEqualTo(90);
    assertThat(changed.updatedBy()).isEqualTo("bob");
    Change latest = purposes.history("reporting").get(0);
    assertThat(latest.action()).isEqualTo("UPDATE");
    assertThat(latest.before().get("maxDays").isNull()).isTrue();
    assertThat(latest.after().get("maxDays").asInt()).isEqualTo(90);

    assertThatThrownBy(() -> purposes.update("reporting", details("Support"), "bob"))
        .isInstanceOf(Refused.class)
        .extracting(e -> ((Refused) e).reason())
        .isEqualTo(Refused.Reason.CONFLICT);
    assertThatThrownBy(() -> purposes.update("nothing-here", details("Nothing"), "bob"))
        .isInstanceOf(Refused.class)
        .extracting(e -> ((Refused) e).reason())
        .isEqualTo(Refused.Reason.NOT_FOUND);
  }

  @Test
  @DisplayName("retiring and reinstating keep the reason, and doing either twice is refused")
  void retires() {
    Purpose retired = purposes.retire("support", "Nobody asks for it", "carol");
    assertThat(retired.active()).isFalse();
    assertThat(purposes.list()).extracting(Purpose::key).last().isEqualTo("support");
    assertThatThrownBy(() -> purposes.retire("support", "Again", "carol"))
        .isInstanceOf(Refused.class)
        .extracting(e -> ((Refused) e).reason())
        .isEqualTo(Refused.Reason.CONFLICT);

    Purpose back = purposes.reinstate("support", "The help desk needs it", "carol");
    assertThat(back.active()).isTrue();
    assertThat(purposes.history("support"))
        .extracting(Change::action, Change::reason)
        .startsWith(
            org.assertj.core.groups.Tuple.tuple("REINSTATE", "The help desk needs it"),
            org.assertj.core.groups.Tuple.tuple("RETIRE", "Nobody asks for it"));
  }

  @Test
  @DisplayName("a declared purpose comes back as the register's key, whatever its case")
  void declares() {
    assertThat(purposes.declared(" Fraud-Analysis ")).isEqualTo("fraud-analysis");
    assertThat(purposes.declared("  ")).isNull();
    assertThat(purposes.declared(null)).isNull();
  }

  @Test
  @DisplayName("a purpose nobody listed, or one retired, cannot be declared")
  void refusesUnlisted() {
    assertThatThrownBy(() -> purposes.declared("marketing"))
        .isInstanceOf(Refused.class)
        .hasMessageContaining("not in the register");

    purposes.retire("support", "Merged", "carol");
    assertThatThrownBy(() -> purposes.declared("support"))
        .isInstanceOf(Refused.class)
        .hasMessageContaining("was retired");
  }

  @Test
  @DisplayName("what a policy already named stays allowed on an edit; what it newly names must be listed")
  void grandfathers() {
    purposes.requireListed(List.of("marketing", "Reporting"), List.of("MARKETING"), "a policy");

    assertThatThrownBy(
            () -> purposes.requireListed(List.of("marketing", "billing"), List.of("marketing"), "a policy"))
        .isInstanceOf(Refused.class)
        .hasMessageContaining("\"billing\"");

    purposes.retire("support", "Merged", "carol");
    assertThatThrownBy(() -> purposes.requireListed(List.of("support"), List.of(), "a policy"))
        .isInstanceOf(Refused.class)
        .hasMessageContaining("cannot be used on a policy");
    purposes.requireListed(List.of("support"), List.of("support"), "a policy");
  }

  @Test
  @DisplayName("usage counts live policies, templates, open requests and recent decisions, by any spelling")
  void counts() {
    jdbi.useHandle(
        handle -> {
          handle.execute(
              """
              INSERT INTO policy (name, policy_type, scope_level, lifecycle_state, document) VALUES
                ('p1', 'SUBSCRIPTION', 'ORG', 'ACTIVE',
                 '{"subject": {"context": {"purpose": ["Fraud-Analysis", "marketing"]}}}'),
                ('p2', 'SUBSCRIPTION', 'ORG', 'DRAFT',
                 '{"subject": {"context": {"purpose": ["fraud-analysis"]}}}'),
                ('p3', 'SUBSCRIPTION', 'ORG', 'ARCHIVED',
                 '{"subject": {"context": {"purpose": ["reporting"]}}}'),
                ('p4', 'SUBSCRIPTION', 'ORG', 'ACTIVE', '{"subject": {}}')
              """);
          handle.execute(
              """
              INSERT INTO access_request_template (name, form, created_by, updated_by) VALUES
                ('t1', '{"purposes": ["reporting", "marketing"]}', 'x', 'x')
              """);
          handle.execute(
              """
              INSERT INTO access_request
                (asset_fqn, requester_username, reason, purpose, status, decided_by, decided_at)
              VALUES
                ('demo-pg.salesdb.sales.customer', 'dave', 'why', 'marketing', 'PENDING', NULL, NULL),
                ('demo-pg.salesdb.sales.orders', 'dave', 'why', 'marketing', 'REJECTED', 'erin', now())
              """);
          handle.execute(
              """
              INSERT INTO audit_decision (principal_name, target_fqn, allowed, decision, purpose, occurred_at)
              VALUES ('dave', 'demo-pg.salesdb.sales.customer', true, '{}', 'reporting', now()),
                     ('dave', 'demo-pg.salesdb.sales.customer', true, '{}', 'reporting',
                      now() - interval '120 days')
              """);
        });

    Map<String, Usage> usage = purposes.usage();

    assertThat(usage.get("fraud-analysis"))
        .extracting(Usage::policies, Usage::templates, Usage::openRequests, Usage::recentQueries)
        .containsExactly(2, 0, 0, 0);
    assertThat(usage.get("reporting"))
        .extracting(Usage::policies, Usage::templates, Usage::openRequests, Usage::recentQueries)
        .containsExactly(0, 1, 0, 1);
    assertThat(usage.get("marketing"))
        .extracting(Usage::policies, Usage::templates, Usage::openRequests, Usage::recentQueries)
        .containsExactly(1, 1, 1, 0);
    assertThat(usage).doesNotContainKey("support");
  }
}
