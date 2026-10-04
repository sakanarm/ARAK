package com.mfec.dac.enforcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.policy.PolicyStore;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.Exemption;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import com.mfec.dac.schema.entity.policy.TimeRule;
import com.mfec.dac.schema.entity.policy.TimeWindow;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.CredentialResolver;
import com.mfec.dac.source.jdbc.NativeGrantApplier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
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
 * Plan, apply, check, sweep and roll back a subscription policy's role, through
 * the service the endpoints call, against a real PostgreSQL.
 *
 * <p>As in {@link SecureViewServiceIT}, one container plays both parts: its
 * {@code public} schema is the platform's database and its {@code sales}
 * schema is the customer's. The people, their logins and the push account are
 * real roles on it, and every "can read" below is a connection as that login.
 *
 * <p>The population is a stub, so that each test says who the engine lets in
 * without writing the policies that would. Everything after it -- the
 * membership rule, the compiler, the applier, the role row, the audit trail --
 * is the real thing.
 */
@Testcontainers
class NativeSubscriptionServiceIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String ADMIN = "admin";
  private static final String ADMIN_IP = "192.0.2.10";
  private static final UUID OTHER = UUID.fromString("0d3e1a7c-0000-4000-8000-0000000000aa");
  private static final List<String> LOGINS =
      List.of(
          "arak_push", "arak_weak", "alice", "bob", "carol", "dave", "erin", "frank",
          "team_login");
  private static final List<String> PEOPLE =
      List.of("alice", "bob", "carol", "dave", "erin", "frank");

  private static Jdbi jdbi;
  private static String db;
  private static String orders;
  private static String customer;
  private static String returns;

  private PolicyStore policies;
  private DataSourceStore sources;
  private NativeRoleStore roles;
  private NativeSubscriptionService service;
  private UUID src;
  private final Map<String, UUID> assets = new LinkedHashMap<>();
  /** Person to their decision on a table, by FQN; whoever is not here is not decided. */
  private final Map<String, Function<String, PolicyDecision>> people = new LinkedHashMap<>();

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbi.installPlugins();
    db = POSTGRES.getDatabaseName();
    orders = "it_pg." + db + ".sales.orders";
    customer = "it_pg." + db + ".sales.customer";
    returns = "it_pg." + db + ".sales.returns";
  }

  @BeforeEach
  void seed() throws SQLException {
    // DROP OWNED revokes what the role was granted, and by CASCADE what it
    // granted on with those grant options; so the push accounts go first and
    // the policy roles after, as in NativeGrantApplierIT.
    for (String login : LOGINS) {
      run(
          "DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + login + "') THEN"
              + " EXECUTE 'DROP OWNED BY " + login + " CASCADE'; END IF; END $$");
    }
    for (String role : column("SELECT rolname FROM pg_roles WHERE rolname LIKE 'arak\\_sub\\_%'")) {
      run("DROP OWNED BY \"" + role + "\" CASCADE", "DROP ROLE \"" + role + "\"");
    }
    for (String login : LOGINS) {
      run("DROP ROLE IF EXISTS " + login);
    }
    run(
        "DROP SCHEMA IF EXISTS sales CASCADE",
        "CREATE SCHEMA sales",
        "CREATE TABLE sales.orders (id int PRIMARY KEY, amount numeric)",
        "CREATE TABLE sales.customer (id int PRIMARY KEY, email text)",
        "CREATE TABLE sales.returns (id int PRIMARY KEY, reason text)",
        "INSERT INTO sales.orders VALUES (1, 10), (2, 20)",
        "INSERT INTO sales.customer VALUES (1, 'anan@example.com')",
        "INSERT INTO sales.returns VALUES (1, 'damaged')",
        "REVOKE CONNECT ON DATABASE \"" + db + "\" FROM PUBLIC",
        "CREATE ROLE arak_push LOGIN CREATEROLE PASSWORD 'it-only-arak_push'",
        "GRANT CONNECT ON DATABASE \"" + db + "\" TO arak_push WITH GRANT OPTION",
        "GRANT USAGE ON SCHEMA sales TO arak_push WITH GRANT OPTION",
        "GRANT SELECT ON ALL TABLES IN SCHEMA sales TO arak_push WITH GRANT OPTION",
        "CREATE ROLE arak_weak LOGIN NOCREATEROLE PASSWORD 'it-only-arak_weak'",
        "GRANT CONNECT ON DATABASE \"" + db + "\" TO arak_weak WITH GRANT OPTION",
        "GRANT USAGE ON SCHEMA sales TO arak_weak WITH GRANT OPTION",
        "GRANT SELECT ON ALL TABLES IN SCHEMA sales TO arak_weak WITH GRANT OPTION");
    for (String login : List.of("alice", "bob", "carol", "dave", "erin", "frank", "team_login")) {
      run("CREATE ROLE " + login + " LOGIN PASSWORD 'it-only-" + login + "'");
    }

    jdbi.useHandle(
        handle -> {
          handle.execute(
              """
              TRUNCATE policy_version, policy_binding, policy, principal, asset_fqn_map,
                       asset_column, asset, native_role, native_credential, db_principal_map,
                       audit_enforcement, data_source CASCADE
              """);
          for (String person : PEOPLE) {
            handle.execute(
                "INSERT INTO principal (principal_type, username, source) VALUES ('USER', ?,"
                    + " 'local')",
                person);
          }
        });

    policies =
        new PolicyStore(jdbi, new ObjectMapper().registerModule(new JavaTimeModule()));
    sources = new DataSourceStore(jdbi);
    roles = new NativeRoleStore(jdbi);
    people.clear();
    assets.clear();

    src =
        sources
            .create(
                new DataSourceStore.SourceInput(
                    "it_pg", "POSTGRES", null, POSTGRES.getHost(),
                    POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), db,
                    "env:IT_SOURCE_LOGIN", "NATIVE_CONFIG", "it_pg", "sec", "{table}", true))
            .id();
    for (String table : List.of("orders", "customer", "returns")) {
      assets.put(table, catalogue(src, table));
    }

    Map<String, String> env =
        Map.of(
            "IT_SOURCE_LOGIN", POSTGRES.getUsername() + ":" + POSTGRES.getPassword(),
            "IT_PUSH_LOGIN", "arak_push:it-only-arak_push",
            "IT_WEAK_LOGIN", "arak_weak:it-only-arak_weak",
            "IT_PROXY_TYPED", POSTGRES.getUsername() + ":it-only-another-password");
    service =
        new NativeSubscriptionService(
            jdbi,
            policies,
            sources,
            new CredentialResolver(env::get),
            new NativeGrantApplier(),
            this::decide,
            roles,
            new NativeLoginMap(jdbi),
            new EnforcementStateStore(jdbi),
            new NativeReviews());

    service.setCredential(src, "env:IT_PUSH_LOGIN", ADMIN, ADMIN_IP);
    for (String person : List.of("alice", "bob", "dave")) {
      service.mapLogin(src, person, person, ADMIN, ADMIN_IP);
    }
  }

  // ------------------------------------------------------------------ read

  @Test
  @DisplayName("Read: a member selects every bound table; a person the policy does not let in"
      + " cannot even connect")
  void readGivesMembersTheBoundTables() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders", "customer");
    letIn("alice", policy);
    notLetIn("bob", policy);

    NativeSubscriptionService.Preview preview = service.plan(policy, src, "READ", ADMIN, ADMIN_IP);
    assertThat(preview.level()).isEqualTo("READ");
    assertThat(preview.role()).isEqualTo(PostgresGrantCompiler.roleName(policy, src));
    assertThat(preview.tables()).containsExactly(customer, orders);
    assertThat(preview.members()).extracting(NativeMembership.Member::login)
        .containsExactly("alice");
    assertThat(preview.blockers()).isEmpty();
    assertThat(preview.satisfied()).isFalse();
    assertThat(preview.applyScript()).contains("CREATE ROLE").contains("GRANT SELECT");
    // The push account always reads the tables it grants on; it is named as
    // what it is, never by its login.
    assertThat(preview.otherReaders()).contains("sales.orders: ARAK's push account")
        .noneMatch(reader -> reader.contains("arak_push"));

    NativeSubscriptionService.Outcome outcome =
        service.apply(policy, src, preview.reviewId(), ADMIN, ADMIN_IP);
    assertThat(outcome.role().status()).isEqualTo("APPLIED");
    assertThat(outcome.role().members()).containsExactly("alice");
    assertThat(outcome.statements()).isPositive();

    assertThat(rowsAs("alice", "SELECT count(*) FROM sales.orders")).containsExactly("2");
    assertThat(rowsAs("alice", "SELECT email FROM sales.customer"))
        .containsExactly("anan@example.com");
    assertThatThrownBy(() -> rowsAs("alice", "SELECT count(*) FROM sales.returns"))
        .hasMessageContaining("permission denied for table returns");
    assertThatThrownBy(() -> rowsAs("bob", "SELECT 1"))
        .hasMessageContaining("permission denied for database");

    assertThat(service.history(policy, src, 10))
        .extracting(EnforcementStateStore.AuditEntry::action, EnforcementStateStore.AuditEntry::outcome)
        .containsExactly(tuple("APPLY", "APPLIED"), tuple("DRY_RUN", "REVIEWED"));
    assertThat(service.plan(policy, src, null, ADMIN, ADMIN_IP).satisfied()).isTrue();
  }

  @Test
  @DisplayName("Browse: a member connects and sees the schema but reads no row; the level"
      + " sticks until a plan asks for another")
  void browseLetsMembersConnectButNotSelect() throws SQLException {
    UUID policy = activePolicy("Sales browsers");
    bind(policy, "orders", "customer");
    letIn("alice", policy);

    NativeSubscriptionService.Preview preview =
        service.plan(policy, src, "BROWSE", ADMIN, ADMIN_IP);
    assertThat(preview.warnings()).anyMatch(w -> w.startsWith("Browse lets members connect"));
    assertThat(preview.applyScript()).doesNotContain("GRANT SELECT");
    service.apply(policy, src, preview.reviewId(), ADMIN, ADMIN_IP);

    assertThat(rowsAs("alice", "SELECT 1")).containsExactly("1");
    assertThat(
            rowsAs(
                "alice",
                // information_schema shows only what one holds a privilege on;
                // the catalogue psql and DBeaver read shows every table.
                "SELECT tablename FROM pg_tables WHERE schemaname = 'sales' ORDER BY 1"))
        .contains("customer", "orders");
    assertThatThrownBy(() -> rowsAs("alice", "SELECT count(*) FROM sales.orders"))
        .hasMessageContaining("permission denied for table orders");

    // No level given: the role keeps the one it was applied at.
    NativeSubscriptionService.Preview again = service.plan(policy, src, null, ADMIN, ADMIN_IP);
    assertThat(again.level()).isEqualTo("BROWSE");
    assertThat(again.satisfied()).isTrue();

    NativeSubscriptionService.Preview up = service.plan(policy, src, "READ", ADMIN, ADMIN_IP);
    assertThat(up.warnings()).anyMatch(w -> w.contains("applying grants SELECT"));
    service.apply(policy, src, up.reviewId(), ADMIN, ADMIN_IP);
    assertThat(rowsAs("alice", "SELECT count(*) FROM sales.orders")).containsExactly("2");
  }

  // --------------------------------------------------------------- reviews

  @Test
  @DisplayName("a review is spent by its apply, and is no good for another policy")
  void aReviewIsSingleUseAndForOnePolicy() {
    UUID policy = activePolicy("Sales readers");
    UUID second = activePolicy("Returns readers");
    bind(policy, "orders");
    bind(second, "returns");
    letIn("alice", policy);

    NativeSubscriptionService.Preview preview = service.plan(policy, src, null, ADMIN, ADMIN_IP);
    service.apply(policy, src, preview.reviewId(), ADMIN, ADMIN_IP);
    assertThatThrownBy(() -> service.apply(policy, src, preview.reviewId(), ADMIN, ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.ConflictException.class)
        .hasMessageContaining("already used");

    NativeSubscriptionService.Preview other = service.plan(policy, src, null, ADMIN, ADMIN_IP);
    assertThatThrownBy(() -> service.apply(second, src, other.reviewId(), ADMIN, ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.ConflictException.class);
    assertThatThrownBy(() -> service.apply(policy, src, UUID.randomUUID(), ADMIN, ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.ConflictException.class);
  }

  @Test
  @DisplayName("a person who joins between plan and apply makes the plan stale; nothing is"
      + " applied")
  void aChangedPopulationInvalidatesTheReview() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    NativeSubscriptionService.Preview preview = service.plan(policy, src, null, ADMIN, ADMIN_IP);

    letIn("carol", policy);
    service.mapLogin(src, "carol", "carol", ADMIN, ADMIN_IP);

    assertThatThrownBy(() -> service.apply(policy, src, preview.reviewId(), ADMIN, ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.ConflictException.class)
        .hasMessageContaining("changed after the plan was reviewed");
    assertThat(roleExists(PostgresGrantCompiler.roleName(policy, src))).isFalse();
    assertThat(service.history(policy, src, 1))
        .extracting(EnforcementStateStore.AuditEntry::action, EnforcementStateStore.AuditEntry::outcome)
        .containsExactly(tuple("APPLY", "STALE"));
  }

  @Test
  @DisplayName("a role a DBA creates by hand under the policy's name is never taken over")
  void aRoleMadeByHandAfterThePlanIsRefused() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    NativeSubscriptionService.Preview preview = service.plan(policy, src, null, ADMIN, ADMIN_IP);

    String role = PostgresGrantCompiler.roleName(policy, src);
    run("CREATE ROLE \"" + role + "\" NOLOGIN");

    assertThatThrownBy(() -> service.apply(policy, src, preview.reviewId(), ADMIN, ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.RefusedException.class)
        .hasMessageContaining("does not carry ARAK's comment");
    assertThatThrownBy(() -> rowsAs("alice", "SELECT 1"))
        .hasMessageContaining("permission denied for database");
    assertThat(service.history(policy, src, 1))
        .extracting(EnforcementStateStore.AuditEntry::action, EnforcementStateStore.AuditEntry::outcome)
        .containsExactly(tuple("APPLY", "REFUSED"));
  }

  @Test
  @DisplayName("a grant changed on the source between plan and apply makes the plan stale")
  void aSourceChangedAfterThePlanIsStale() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    applyNow(policy, null);

    NativeSubscriptionService.Preview preview = service.plan(policy, src, null, ADMIN, ADMIN_IP);
    assertThat(preview.changes()).isEmpty();
    String role = PostgresGrantCompiler.roleName(policy, src);
    runAs("arak_push", "REVOKE \"" + role + "\" FROM alice");

    assertThatThrownBy(() -> service.apply(policy, src, preview.reviewId(), ADMIN, ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.ConflictException.class)
        .hasMessageContaining("source changed after the plan");
  }

  // ------------------------------------------------------------ self-grant

  @Test
  @DisplayName("nobody applies a role they are a member of")
  void aMemberCannotApplyTheirOwnRole() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);

    NativeSubscriptionService.Preview preview =
        service.plan(policy, src, null, "alice", ADMIN_IP);
    assertThat(preview.warnings())
        .contains("You are one of the role's members, so another platform admin has to apply it.");
    assertThatThrownBy(() -> service.apply(policy, src, preview.reviewId(), "ALICE", ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.RefusedException.class)
        .hasMessageContaining("nobody grants themselves access");
    assertThat(roleExists(PostgresGrantCompiler.roleName(policy, src))).isFalse();
  }

  @Test
  @DisplayName("nobody maps their own login")
  void nobodyMapsThemselves() {
    assertThatThrownBy(() -> service.mapLogin(src, "carol", "carol", "Carol", ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.RefusedException.class)
        .hasMessageContaining("cannot map your own login");
    assertThat(service.logins(src)).extracting(NativeLoginMap.Login::username)
        .doesNotContain("carol");
  }

  // ------------------------------------------------------- unusable policy

  @Test
  @DisplayName("a DENY policy and an ALLOW with time windows cannot be a role")
  void policiesARoleCannotCarryAreRefused() {
    Policy deny = document("No contractors");
    deny.setEffect(Policy.Effect.DENY);
    UUID denyId = create(deny, true);
    bind(denyId, "orders");
    assertThatThrownBy(() -> service.plan(denyId, src, null, ADMIN, ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.RefusedException.class)
        .hasMessageContaining("is a DENY policy");

    Policy hours = document("Office hours");
    hours.setSubject(
        new SubjectRule()
            .withTime(
                new TimeRule()
                    .withWindows(
                        List.of(
                            new TimeWindow()
                                .withDays(List.of("MON"))
                                .withFrom("08:00")
                                .withTo("18:00")
                                .withTimezone("Asia/Bangkok")))));
    UUID hoursId = create(hours, true);
    bind(hoursId, "orders");
    assertThatThrownBy(() -> service.plan(hoursId, src, null, ADMIN, ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.RefusedException.class)
        .hasMessageContaining("time windows");
    assertThat(service.history(hoursId, src, 1))
        .extracting(EnforcementStateStore.AuditEntry::action, EnforcementStateStore.AuditEntry::outcome)
        .containsExactly(tuple("DRY_RUN", "REFUSED"));
  }

  @Test
  @DisplayName("a draft policy lets nobody in, and its plan says why")
  void aDraftPolicyHasNoMembers() {
    UUID policy = create(document("Draft readers"), false);
    bind(policy, "orders");
    letIn("alice", policy);

    NativeSubscriptionService.Preview preview = service.plan(policy, src, null, ADMIN, ADMIN_IP);
    assertThat(preview.lifecycleState()).isEqualTo("DRAFT");
    assertThat(preview.members()).isEmpty();
    assertThat(preview.warnings()).anyMatch(w -> w.contains("is DRAFT, so it lets nobody in"));
  }

  @Test
  @DisplayName("a policy that binds no table on the source has no role to make")
  void aPolicyBindingNothingIsRefused() {
    UUID policy = activePolicy("Nothing bound");
    letIn("alice", policy);
    assertThatThrownBy(() -> service.plan(policy, src, null, ADMIN, ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.RefusedException.class)
        .hasMessageContaining("binds no table");
  }

  // ------------------------------------------------------------- membership

  @Test
  @DisplayName("a DENY on one table keeps the person out of the whole role; the plan lists"
      + " what they lose")
  void aDenyOnOneTableFailsClosed() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders", "customer");
    letIn("alice", policy);
    deniedOn("bob", policy, customer);

    NativeSubscriptionService.Preview preview = service.plan(policy, src, null, ADMIN, ADMIN_IP);
    assertThat(preview.members()).extracting(NativeMembership.Member::login)
        .containsExactly("alice");
    assertThat(preview.excluded()).singleElement().satisfies(
        bob -> {
          assertThat(bob.person()).isEqualTo("bob");
          assertThat(bob.reasons())
              .containsExactly(customer + ": denied by No contractors on customer");
          assertThat(bob.lost()).containsExactly(orders);
        });

    service.apply(policy, src, preview.reviewId(), ADMIN, ADMIN_IP);
    assertThatThrownBy(() -> rowsAs("bob", "SELECT count(*) FROM sales.orders"))
        .hasMessageContaining("permission denied for database");
  }

  @Test
  @DisplayName("a row filter keeps a person out at Read, where a plain SELECT would bypass it,"
      + " but not at Browse")
  void aDataPolicyKeepsAPersonOutOfRead() {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    filteredOn("dave", policy);

    NativeSubscriptionService.Preview read = service.plan(policy, src, "READ", ADMIN, ADMIN_IP);
    assertThat(read.members()).extracting(NativeMembership.Member::login)
        .containsExactly("alice");
    assertThat(read.excluded()).singleElement().satisfies(
        dave -> {
          assertThat(dave.person()).isEqualTo("dave");
          assertThat(dave.reasons()).singleElement().asString().contains("a row filter");
        });

    NativeSubscriptionService.Preview browse =
        service.plan(policy, src, "BROWSE", ADMIN, ADMIN_IP);
    assertThat(browse.members()).extracting(NativeMembership.Member::login)
        .containsExactly("alice", "dave");
  }

  @Test
  @DisplayName("an exempt person is not a member, and the plan lists the exemption")
  void anExemptPersonIsNotAMember() {
    Policy document = document("Sales readers");
    document.setExemptions(
        List.of(
            new Exemption()
                .withPrincipal("carol")
                .withReason("on leave")
                .withGrantedBy(ADMIN)
                .withExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS))));
    UUID policy = create(document, true);
    bind(policy, "orders");
    letIn("alice", policy);
    exempt("carol", policy);
    service.mapLogin(src, "carol", "carol", ADMIN, ADMIN_IP);

    NativeSubscriptionService.Preview preview = service.plan(policy, src, null, ADMIN, ADMIN_IP);
    assertThat(preview.exemptions()).singleElement().asString().startsWith("carol -- on leave");
    assertThat(preview.members()).extracting(NativeMembership.Member::login)
        .containsExactly("alice");
  }

  @Test
  @DisplayName("a person without a login is reported, and a login shared with somebody who does"
      + " not qualify stays out")
  void unmappedAndSharedLogins() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    letIn("carol", policy);
    letIn("erin", policy);
    notLetIn("frank", policy);
    service.mapLogin(src, "erin", "team_login", ADMIN, ADMIN_IP);
    service.mapLogin(src, "frank", "team_login", ADMIN, ADMIN_IP);

    NativeSubscriptionService.Preview preview = service.plan(policy, src, null, ADMIN, ADMIN_IP);
    assertThat(preview.members()).extracting(NativeMembership.Member::login)
        .containsExactly("alice");
    assertThat(preview.unmapped()).containsExactly("carol");
    assertThat(preview.warnings())
        .anyMatch(w -> w.startsWith("1 person(s) qualify but have no login mapped"));
    assertThat(preview.sharedRefused()).singleElement().asString()
        .startsWith("team_login is shared by erin");

    service.apply(policy, src, preview.reviewId(), ADMIN, ADMIN_IP);
    assertThatThrownBy(() -> rowsAs("team_login", "SELECT 1"))
        .hasMessageContaining("permission denied for database");
  }

  // ---------------------------------------------------------- push account

  @Test
  @DisplayName("without a push account nothing is planned")
  void noPushAccountNoPlan() {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    assertThat(service.deleteCredential(src, ADMIN, ADMIN_IP)).isTrue();

    assertThatThrownBy(() -> service.plan(policy, src, null, ADMIN, ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.RefusedException.class)
        .hasMessageContaining("No push account is set");
  }

  @Test
  @DisplayName("the proxy's read-only account cannot be the push account")
  void theProxyAccountIsNotAPushAccount() {
    assertThatThrownBy(() -> service.setCredential(src, "env:IT_SOURCE_LOGIN", ADMIN, ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.RefusedException.class)
        .hasMessageContaining("the query proxy reads");
    // The same login typed again, under another reference, is the same account.
    assertThatThrownBy(() -> service.setCredential(src, "env:IT_PROXY_TYPED", ADMIN, ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.RefusedException.class)
        .hasMessageContaining("the query proxy reads");
  }

  @Test
  @DisplayName("a push account without CREATEROLE blocks the plan, and its login is never shown")
  void aWeakPushAccountIsABlocker() {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    service.setCredential(src, "env:IT_WEAK_LOGIN", ADMIN, ADMIN_IP);

    NativeSubscriptionService.Preview preview = service.plan(policy, src, null, ADMIN, ADMIN_IP);
    assertThat(preview.blockers())
        .contains("ARAK's push account needs CREATEROLE to create the policy's role.");
    List<String> shown = new ArrayList<>(preview.blockers());
    shown.addAll(preview.warnings());
    shown.addAll(preview.otherReaders());
    assertThat(shown).noneMatch(line -> line.contains("arak_weak"));

    assertThatThrownBy(() -> service.apply(policy, src, preview.reviewId(), ADMIN, ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.RefusedException.class)
        .hasMessageContaining("needs CREATEROLE")
        .hasMessageNotContaining("arak_weak");
  }

  @Test
  @DisplayName("PUBLIC's CONNECT is reported, not revoked")
  void publicConnectIsAWarning() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    run("GRANT CONNECT ON DATABASE \"" + db + "\" TO PUBLIC");

    NativeSubscriptionService.Preview preview = service.plan(policy, src, null, ADMIN, ADMIN_IP);
    assertThat(preview.warnings()).anyMatch(w -> w.startsWith("PUBLIC holds CONNECT"));
    service.apply(policy, src, preview.reviewId(), ADMIN, ADMIN_IP);
    assertThat(rowsAs("bob", "SELECT 1")).containsExactly("1");
  }

  @Test
  @DisplayName("the configuration trail says what changed, never the account or its secret")
  void configurationHistoryHidesTheCredential() {
    List<EnforcementStateStore.AuditEntry> trail = service.configurationHistory(src, 20);
    assertThat(trail)
        .extracting(EnforcementStateStore.AuditEntry::action, EnforcementStateStore.AuditEntry::outcome)
        .containsOnly(tuple("CONFIGURE", "CHANGED"));
    assertThat(trail).extracting(EnforcementStateStore.AuditEntry::detail)
        .contains("Push account set (env)", "alice connects as alice")
        .noneMatch(detail -> detail.contains("arak_push") || detail.contains("it-only"));
    assertThat(service.sources()).singleElement().satisfies(
        view -> {
          assertThat(view.credential().configured()).isTrue();
          assertThat(view.credential().scheme()).isEqualTo("env");
          assertThat(view.logins()).isEqualTo(3);
        });
  }

  // ------------------------------------------------------------- check

  @Test
  @DisplayName("a hand revoke is drift; the sweep does not grant it back, a new apply does")
  void driftIsFoundAndOnlyAPersonRepairsIt() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    applyNow(policy, null);
    assertThat(service.check(policy, src, ADMIN, ADMIN_IP).status()).isEqualTo("APPLIED");

    String role = PostgresGrantCompiler.roleName(policy, src);
    runAs("arak_push", "REVOKE \"" + role + "\" FROM alice");

    NativeSubscriptionService.Check check = service.check(policy, src, ADMIN, ADMIN_IP);
    assertThat(check.status()).isEqualTo("DRIFTED");
    assertThat(check.drifted()).isTrue();
    assertThat(check.applyScript()).contains("GRANT \"" + role + "\" TO");

    NativeSubscriptionService.SweepReport sweep = service.sweep();
    assertThat(sweep.revoked()).isZero();
    assertThat(sweep.drifted()).isEqualTo(1);
    assertThatThrownBy(() -> rowsAs("alice", "SELECT count(*) FROM sales.orders"))
        .hasMessageContaining("permission denied");

    applyNow(policy, null);
    assertThat(rowsAs("alice", "SELECT count(*) FROM sales.orders")).containsExactly("2");
    assertThat(service.check(policy, src, ADMIN, ADMIN_IP).status()).isEqualTo("APPLIED");
  }

  @Test
  @DisplayName("a table bound after the apply leaves the role pending, not drifted")
  void aNewTableIsPending() {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    applyNow(policy, null);

    bind(policy, "returns");
    NativeSubscriptionService.Check check = service.check(policy, src, ADMIN, ADMIN_IP);
    assertThat(check.status()).isEqualTo("PENDING");
    assertThat(check.drifted()).isFalse();
    assertThat(check.applyScript()).contains("\"sales\".\"returns\"");
    assertThat(roles.find(policy, src).orElseThrow().status()).isEqualTo("PENDING");
  }

  // ------------------------------------------------------------- sweep

  @Test
  @DisplayName("the sweep takes a member out once the policy stops letting them in")
  void theSweepRevokesLapsedMembers() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    letIn("bob", policy);
    applyNow(policy, null);
    assertThat(rowsAs("bob", "SELECT count(*) FROM sales.orders")).containsExactly("2");

    notLetIn("bob", policy);
    NativeSubscriptionService.SweepReport sweep = service.sweep();
    assertThat(sweep.checked()).isEqualTo(1);
    assertThat(sweep.revoked()).isEqualTo(1);

    assertThatThrownBy(() -> rowsAs("bob", "SELECT count(*) FROM sales.orders"))
        .hasMessageContaining("permission denied for database");
    assertThat(rowsAs("alice", "SELECT count(*) FROM sales.orders")).containsExactly("2");
    assertThat(service.history(policy, src, 1)).singleElement().satisfies(
        entry -> {
          assertThat(entry.action()).isEqualTo("EXPIRE");
          assertThat(entry.outcome()).isEqualTo("APPLIED");
          assertThat(entry.actor()).isEqualTo(NativeSubscriptionService.SWEEP_ACTOR);
        });
    NativeRoleStore.Role role = roles.find(policy, src).orElseThrow();
    assertThat(role.status()).isEqualTo("APPLIED");
    assertThat(role.members()).containsExactly("alice");

    assertThat(service.sweep().revoked()).isZero();
  }

  @Test
  @DisplayName("an unmapped login is taken out by the sweep")
  void theSweepRevokesUnmappedLogins() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    letIn("bob", policy);
    applyNow(policy, null);

    assertThat(service.unmapLogin(src, "bob", ADMIN, ADMIN_IP)).containsExactly("bob");
    assertThat(service.sweep().revoked()).isEqualTo(1);
    assertThatThrownBy(() -> rowsAs("bob", "SELECT 1"))
        .hasMessageContaining("permission denied for database");
  }

  @Test
  @DisplayName("the sweep never grants: a newly qualified person waits for a person to apply")
  void theSweepNeverGrants() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    applyNow(policy, null);

    letIn("carol", policy);
    service.mapLogin(src, "carol", "carol", ADMIN, ADMIN_IP);
    NativeSubscriptionService.SweepReport sweep = service.sweep();
    assertThat(sweep.revoked()).isZero();
    assertThat(sweep.pending()).isEqualTo(1);
    assertThatThrownBy(() -> rowsAs("carol", "SELECT 1"))
        .hasMessageContaining("permission denied for database");
    assertThat(roles.find(policy, src).orElseThrow().status()).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("a disabled policy's role is emptied by the sweep")
  void aDisabledPolicyIsSweptEmpty() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    applyNow(policy, null);

    policies.transition(policy, "DISABLED", ADMIN, "for the test");
    assertThat(service.sweep().revoked()).isEqualTo(1);
    assertThatThrownBy(() -> rowsAs("alice", "SELECT 1"))
        .hasMessageContaining("permission denied for database");
  }

  @Test
  @DisplayName("a disabled source is not swept, nor planned")
  void aDisabledSourceIsLeftAlone() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    applyNow(policy, null);

    notLetIn("alice", policy);
    sources.setEnabled(src, false);
    assertThat(service.sweep().checked()).isZero();
    assertThat(rowsAs("alice", "SELECT count(*) FROM sales.orders")).containsExactly("2");
    assertThatThrownBy(() -> service.plan(policy, src, null, ADMIN, ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.RefusedException.class)
        .hasMessageContaining("is disabled");
  }

  // ---------------------------------------------------------- rollback

  @Test
  @DisplayName("a rollback takes off what ARAK granted, and the role with it")
  void rollbackDropsTheRole() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    applyNow(policy, null);
    String role = PostgresGrantCompiler.roleName(policy, src);

    NativeSubscriptionService.RollbackPreview plan = service.rollbackPlan(policy, src);
    assertThat(plan.notes()).singleElement().asString()
        .startsWith("Only what ARAK's account granted");
    assertThat(plan.script()).contains("DROP ROLE");
    assertThat(roleExists(role)).isTrue();

    NativeSubscriptionService.Outcome outcome = service.rollback(policy, src, ADMIN, ADMIN_IP);
    assertThat(outcome.role().status()).isEqualTo("ROLLED_BACK");
    assertThat(roleExists(role)).isFalse();
    assertThatThrownBy(() -> rowsAs("alice", "SELECT 1"))
        .hasMessageContaining("permission denied for database");
    assertThat(service.history(policy, src, 1))
        .extracting(EnforcementStateStore.AuditEntry::action, EnforcementStateStore.AuditEntry::outcome)
        .containsExactly(tuple("ROLLBACK", "ROLLED_BACK"));

    assertThatThrownBy(() -> service.rollback(policy, src, ADMIN, ADMIN_IP))
        .isInstanceOf(NativeSubscriptionService.ConflictException.class)
        .hasMessageContaining("Nothing is applied");
  }

  @Test
  @DisplayName("a DBA's own grants are reported and never revoked, by apply or by rollback")
  void grantsSomebodyElseMadeStay() throws SQLException {
    UUID policy = activePolicy("Sales readers");
    bind(policy, "orders");
    letIn("alice", policy);
    notLetIn("bob", policy);
    run(
        "GRANT CONNECT ON DATABASE \"" + db + "\" TO bob",
        "GRANT USAGE ON SCHEMA sales TO bob",
        "GRANT SELECT ON sales.orders TO bob");

    NativeSubscriptionService.Preview preview = service.plan(policy, src, null, ADMIN, ADMIN_IP);
    assertThat(preview.otherReaders()).contains("sales.orders: bob");
    service.apply(policy, src, preview.reviewId(), ADMIN, ADMIN_IP);
    assertThat(rowsAs("bob", "SELECT count(*) FROM sales.orders")).containsExactly("2");

    String role = PostgresGrantCompiler.roleName(policy, src);
    run("GRANT SELECT ON sales.returns TO \"" + role + "\"");
    NativeSubscriptionService.Outcome outcome = service.rollback(policy, src, ADMIN, ADMIN_IP);
    assertThat(outcome.warnings()).anyMatch(w -> w.contains("was not dropped"));
    assertThat(roleExists(role)).isTrue();
    assertThat(
            column(
                "SELECT has_table_privilege('" + role + "', 'sales.returns', 'SELECT')::text"))
        .containsExactly("true");
    assertThat(rowsAs("bob", "SELECT count(*) FROM sales.orders")).containsExactly("2");
    assertThatThrownBy(() -> rowsAs("alice", "SELECT 1"))
        .hasMessageContaining("permission denied for database");
  }

  // ------------------------------------------------------------- helpers

  private Map<String, Map<String, PolicyDecision>> decide(List<String> fqns, String environment) {
    Map<String, Map<String, PolicyDecision>> out = new LinkedHashMap<>();
    people.forEach(
        (person, rule) -> {
          Map<String, PolicyDecision> perTable = new LinkedHashMap<>();
          for (String fqn : fqns) {
            perTable.put(fqn, rule.apply(fqn));
          }
          out.put(person, perTable);
        });
    return out;
  }

  private void letIn(String person, UUID policy) {
    people.put(person, fqn -> decision(person, fqn, true, allow(policy, true)));
  }

  private void notLetIn(String person, UUID policy) {
    people.put(person, fqn -> decision(person, fqn, false, allow(policy, false)));
  }

  private void deniedOn(String person, UUID policy, String deniedFqn) {
    people.put(
        person,
        fqn ->
            fqn.equals(deniedFqn)
                ? decision(
                    person, fqn, false, allow(policy, true),
                    reason(OTHER, "No contractors on customer", DecisionReason.Effect.DENY, true))
                : decision(person, fqn, true, allow(policy, true)));
  }

  private void filteredOn(String person, UUID policy) {
    people.put(
        person,
        fqn ->
            decision(person, fqn, true, allow(policy, true))
                .withRowPredicates(List.of(new ResolvedRowPredicate().withColumn("region"))));
  }

  private void exempt(String person, UUID policy) {
    people.put(
        person,
        fqn ->
            decision(
                person, fqn, false,
                allow(policy, false)
                    .withExplanation(person + " is exempt from this policy until 2099-12-31")));
  }

  private static PolicyDecision decision(
      String person, String fqn, boolean allowed, DecisionReason... reasons) {
    return new PolicyDecision()
        .withPrincipal(person)
        .withAssetFqn(fqn)
        .withAllowed(allowed)
        .withReasons(List.of(reasons));
  }

  private static DecisionReason allow(UUID policy, boolean matched) {
    return reason(policy, "Sales readers", DecisionReason.Effect.ALLOW, matched);
  }

  private static DecisionReason reason(
      UUID policy, String name, DecisionReason.Effect effect, boolean matched) {
    return new DecisionReason()
        .withPolicyId(policy)
        .withPolicyName(name)
        .withEffect(effect)
        .withPolicyType(DecisionReason.PolicyType.SUBSCRIPTION)
        .withMatched(matched);
  }

  private UUID activePolicy(String name) {
    return create(document(name), true);
  }

  private UUID create(Policy document, boolean activate) {
    UUID id = policies.create(document, ADMIN).id();
    if (activate) {
      policies.transition(id, "ACTIVE", ADMIN, "for the test");
    }
    return id;
  }

  private static Policy document(String name) {
    FacetCondition condition = new FacetCondition();
    condition.setFacet(FacetCondition.FacetType.TAGS);
    condition.setOperator(ResolvedRowPredicate.FacetOperator.CONTAINS);
    condition.setValue("Tier.Tier1");
    AssetSelector selector = new AssetSelector();
    selector.setCondition(condition);
    Policy document = new Policy();
    document.setName(name);
    document.setPolicyType(Policy.PolicyType.SUBSCRIPTION);
    document.setEffect(Policy.Effect.ALLOW);
    document.setScopeLevel(ResolvedColumnMask.ScopeLevel.ORG);
    document.setSelector(selector);
    document.setEnvironment(Policy.Environment.fromValue(DecisionService.DEFAULT_ENVIRONMENT));
    return document;
  }

  /** What the materializer would write; the selector itself is not under test here. */
  private void bind(UUID policy, String... tables) {
    jdbi.useHandle(
        handle -> {
          for (String table : tables) {
            handle
                .createUpdate(
                    "INSERT INTO policy_binding (policy_id, target_fqn, target_kind, asset_id)"
                        + " VALUES (:p, :fqn, 'TABLE', :asset)")
                .bind("p", policy)
                .bind("fqn", "it_pg." + db + ".sales." + table)
                .bind("asset", assets.get(table))
                .execute();
          }
        });
  }

  private void applyNow(UUID policy, String level) {
    NativeSubscriptionService.Preview preview = service.plan(policy, src, level, ADMIN, ADMIN_IP);
    assertThat(preview.blockers()).isEmpty();
    service.apply(policy, src, preview.reviewId(), ADMIN, ADMIN_IP);
  }

  private static UUID catalogue(UUID sourceId, String table) {
    String fqn = "it_pg." + db + ".sales." + table;
    return jdbi.withHandle(
        handle -> {
          UUID assetId =
              handle
                  .createQuery(
                      "INSERT INTO asset (data_source_id, fqn, asset_type, name)"
                          + " VALUES (:source, :fqn, 'TABLE', :name) RETURNING id")
                  .bindMap(Map.of("source", sourceId, "fqn", fqn, "name", table))
                  .mapTo(UUID.class)
                  .one();
          handle
              .createUpdate(
                  "INSERT INTO asset_fqn_map (om_fqn, data_source_id, database_name, schema_name,"
                      + " object_name, object_kind, verification_status)"
                      + " VALUES (:fqn, :source, :db, 'sales', :name, 'TABLE', 'MATCHED')")
              .bind("fqn", fqn)
              .bind("source", sourceId)
              .bind("db", db)
              .bind("name", table)
              .execute();
          return assetId;
        });
  }

  private static boolean roleExists(String role) throws SQLException {
    return !column("SELECT rolname FROM pg_roles WHERE rolname = '" + role + "'").isEmpty();
  }

  private static void run(String... statements) throws SQLException {
    withConnection(
        POSTGRES.getUsername(), POSTGRES.getPassword(),
        statement -> {
          for (String sql : statements) {
            try {
              statement.execute(sql);
            } catch (SQLException failure) {
              if (!sql.startsWith("DROP")) {
                throw new IllegalStateException(sql + ": " + failure.getMessage(), failure);
              }
            }
          }
        });
  }

  /** As the push account, for a change made with ARAK's own grant options. */
  private static void runAs(String login, String sql) throws SQLException {
    withConnection(
        login, "it-only-" + login,
        statement -> {
          try {
            statement.execute(sql);
          } catch (SQLException failure) {
            throw new IllegalStateException(sql + ": " + failure.getMessage(), failure);
          }
        });
  }

  private static List<String> column(String sql) throws SQLException {
    List<String> out = new ArrayList<>();
    withConnection(
        POSTGRES.getUsername(), POSTGRES.getPassword(),
        statement -> {
          try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
              out.add(rows.getString(1));
            }
          } catch (SQLException failure) {
            throw new IllegalStateException(failure);
          }
        });
    return out;
  }

  private static List<String> rowsAs(String login, String sql) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Connection connection = DriverManager.getConnection(url(), login, "it-only-" + login);
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      int columns = rows.getMetaData().getColumnCount();
      while (rows.next()) {
        StringBuilder line = new StringBuilder();
        for (int i = 1; i <= columns; i++) {
          if (i > 1) {
            line.append('|');
          }
          line.append(rows.getString(i));
        }
        out.add(line.toString());
      }
    }
    return out;
  }

  private static void withConnection(String user, String password, Consumer<Statement> body)
      throws SQLException {
    try (Connection connection = DriverManager.getConnection(url(), user, password);
        Statement statement = connection.createStatement()) {
      body.accept(statement);
    }
  }

  private static String url() {
    return "jdbc:postgresql://" + POSTGRES.getHost() + ":"
        + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + db;
  }
}
