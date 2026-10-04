package com.mfec.dac.source.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.compiler.sql.PostgresGrantCompiler;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.AccessLevel;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Desired;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Step;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Table;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A subscription policy pushed down to PostgreSQL 16, checked by logging in as
 * the people it names.
 *
 * <p>The assertions are what each login can and cannot do afterwards, not that
 * the statements ran: a test that only counted statements would pass over a
 * role that let everybody read everything.
 */
@Testcontainers
class NativeGrantApplierIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String PASSWORD = "it-only-password";
  private static final UUID POLICY = UUID.fromString("5e1f0c2a-0000-4000-8000-000000000001");
  private static final String ROLE =
      PostgresGrantCompiler.roleName(
          POLICY, UUID.fromString("0a7b3c9d-0000-4000-8000-000000000002"));

  private static final Table ORDERS = new Table("sales", "orders");
  private static final Table CUSTOMER = new Table("sales", "customer");
  private static final Table LEDGER = new Table("finance", "ledger");

  private static SourceProbe.Target target;
  private static CredentialResolver.Credential superuser;
  private static CredentialResolver.Credential push;

  private final NativeGrantApplier applier = new NativeGrantApplier();

  @BeforeAll
  static void coordinates() {
    target =
        new SourceProbe.Target(
            "POSTGRES",
            POSTGRES.getHost(),
            POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
            POSTGRES.getDatabaseName());
    superuser = new CredentialResolver.Credential(POSTGRES.getUsername(), POSTGRES.getPassword());
    push = new CredentialResolver.Credential("arak_push", PASSWORD);
  }

  /**
   * Fresh tables, a fresh push account and fresh logins before every test.
   * The push account holds exactly what the design asks of it: CREATEROLE, and
   * each privilege WITH GRANT OPTION, granted to it directly.
   */
  @BeforeEach
  void seed() throws SQLException {
    String db = POSTGRES.getDatabaseName();
    List<String> logins =
        List.of("arak_push", "arak_weak", "alice", "bob", "carol", "nobody_role", "owners");
    // DROP OWNED revokes only what the owner granted. What the push account
    // granted goes with the push account's own grant options, by CASCADE, so
    // the push accounts' privileges go first and the policy roles after.
    for (String login : logins) {
      run(
          "DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + login + "') THEN"
              + " EXECUTE 'DROP OWNED BY " + login + " CASCADE'; END IF; END $$");
    }
    for (String role :
        query(superuser, "SELECT rolname FROM pg_roles WHERE rolname LIKE 'arak\\_sub\\_%'")) {
      run("DROP OWNED BY \"" + role + "\" CASCADE", "DROP ROLE \"" + role + "\"");
    }
    for (String login : logins) {
      run("DROP ROLE IF EXISTS " + login);
    }
    run(
        "DROP SCHEMA IF EXISTS sales CASCADE",
        "DROP SCHEMA IF EXISTS finance CASCADE",
        "CREATE SCHEMA sales",
        "CREATE SCHEMA finance",
        "CREATE TABLE sales.orders (id int PRIMARY KEY, amount numeric)",
        "CREATE TABLE sales.customer (id int PRIMARY KEY, email text)",
        "CREATE TABLE finance.ledger (id int PRIMARY KEY, total numeric)",
        "INSERT INTO sales.orders VALUES (1, 10), (2, 20)",
        "INSERT INTO sales.customer VALUES (1, 'anan@example.com')",
        "INSERT INTO finance.ledger VALUES (1, 1000)",
        "REVOKE CONNECT ON DATABASE \"" + db + "\" FROM PUBLIC",
        "CREATE ROLE arak_push LOGIN CREATEROLE PASSWORD '" + PASSWORD + "'",
        "GRANT CONNECT ON DATABASE \"" + db + "\" TO arak_push WITH GRANT OPTION",
        "GRANT USAGE ON SCHEMA sales, finance TO arak_push WITH GRANT OPTION",
        "GRANT SELECT ON ALL TABLES IN SCHEMA sales, finance TO arak_push WITH GRANT OPTION",
        "CREATE ROLE arak_weak LOGIN CREATEROLE PASSWORD '" + PASSWORD + "'",
        "GRANT CONNECT ON DATABASE \"" + db + "\" TO arak_weak WITH GRANT OPTION",
        "GRANT USAGE ON SCHEMA sales, finance TO arak_weak WITH GRANT OPTION",
        "GRANT SELECT ON sales.orders TO arak_weak WITH GRANT OPTION",
        "CREATE ROLE nobody_role NOLOGIN");
    for (String login : List.of("alice", "bob", "carol")) {
      run("CREATE ROLE " + login + " LOGIN PASSWORD '" + PASSWORD + "'");
    }
  }

  // ------------------------------------------------------------------ read

  @Test
  void readLetsMembersSelectTheTablesInScopeAndNothingElse() throws SQLException {
    applyNow(desired(AccessLevel.READ, List.of(ORDERS, CUSTOMER), List.of("alice", "bob")));

    assertThat(count("alice", "sales.orders")).isEqualTo(2);
    assertThat(count("bob", "sales.customer")).isEqualTo(1);
    assertThatThrownBy(() -> count("alice", "finance.ledger"))
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> count("carol", "sales.orders"))
        .hasMessageContaining("permission denied for database");
  }

  @Test
  void membersCannotBecomeTheRole() throws SQLException {
    applyNow(desired(AccessLevel.READ, List.of(ORDERS), List.of("alice")));
    try (Connection c = connect(new CredentialResolver.Credential("alice", PASSWORD));
        Statement s = c.createStatement()) {
      assertThatThrownBy(() -> s.execute("SET ROLE \"" + ROLE + "\""))
          .hasMessageContaining("permission denied");
    }
  }

  @Test
  void theRoleCannotLogIn() throws SQLException {
    applyNow(desired(AccessLevel.READ, List.of(ORDERS), List.of("alice")));
    assertThat(
            query(
                superuser,
                "SELECT rolcanlogin::text || rolsuper::text || rolinherit::text"
                    + " || rolcreaterole::text || rolbypassrls::text FROM pg_roles"
                    + " WHERE rolname = '" + ROLE + "'"))
        .containsExactly("falsefalsefalsefalsefalse");
  }

  // ---------------------------------------------------------------- browse

  @Test
  void browseLetsMembersConnectAndSeeTheSchemaButNotRead() throws SQLException {
    applyNow(desired(AccessLevel.BROWSE, List.of(ORDERS), List.of("alice")));

    assertThat(query(new CredentialResolver.Credential("alice", PASSWORD),
            "SELECT has_schema_privilege('sales', 'USAGE')::text"))
        .containsExactly("true");
    assertThatThrownBy(() -> count("alice", "sales.orders"))
        .hasMessageContaining("permission denied for table orders");
    assertThatThrownBy(() -> count("carol", "sales.orders"))
        .hasMessageContaining("permission denied for database");
  }

  @Test
  void steppingDownFromReadToBrowseTakesSelectAway() throws SQLException {
    applyNow(desired(AccessLevel.READ, List.of(ORDERS), List.of("alice")));
    NativeGrantApplier.DryRun down =
        applier.dryRun(request(desired(AccessLevel.BROWSE, List.of(ORDERS), List.of("alice"))));
    assertThat(down.plan().changes())
        .extracting(PostgresGrantCompiler.Change::step)
        .containsExactly(Step.REVOKE_SELECT);
    applier.apply(request(desired(AccessLevel.BROWSE, List.of(ORDERS), List.of("alice"))),
        down.signature());
    assertThatThrownBy(() -> count("alice", "sales.orders"))
        .hasMessageContaining("permission denied");
  }

  // ------------------------------------------------------------- re-apply

  @Test
  void aSecondPlanOfTheSamePolicyIsEmpty() throws SQLException {
    Desired want = desired(AccessLevel.READ, List.of(ORDERS), List.of("alice"));
    applyNow(want);
    NativeGrantApplier.DryRun again = applier.dryRun(request(want));
    assertThat(again.isSatisfied()).isTrue();
    assertThat(again.plan().rollback()).isNotEmpty();
  }

  @Test
  void membersWhoNoLongerQualifyLoseTheRoleButKeepWhatTheDbaGave() throws SQLException {
    applyNow(desired(AccessLevel.READ, List.of(ORDERS, CUSTOMER), List.of("alice", "bob")));
    run(
        "GRANT CONNECT ON DATABASE \"" + POSTGRES.getDatabaseName() + "\" TO bob",
        "GRANT USAGE ON SCHEMA sales TO bob",
        "GRANT SELECT ON sales.orders TO bob");

    applyNow(desired(AccessLevel.READ, List.of(ORDERS, CUSTOMER), List.of("alice", "carol")));

    assertThat(count("carol", "sales.customer")).isEqualTo(1);
    assertThat(count("bob", "sales.orders")).isEqualTo(2); // the DBA's own grant
    assertThatThrownBy(() -> count("bob", "sales.customer"))
        .hasMessageContaining("permission denied");
  }

  @Test
  void aTableLeavingScopeIsRevoked() throws SQLException {
    applyNow(desired(AccessLevel.READ, List.of(ORDERS, LEDGER), List.of("alice")));
    applyNow(desired(AccessLevel.READ, List.of(ORDERS), List.of("alice")));
    assertThatThrownBy(() -> count("alice", "finance.ledger"))
        .hasMessageContaining("permission denied");
    assertThat(query(superuser,
            "SELECT count(*)::text FROM pg_namespace n, aclexplode(n.nspacl) a"
                + " WHERE n.nspname = 'finance' AND pg_get_userbyid(a.grantee) = '" + ROLE + "'"))
        .containsExactly("0");
  }

  // ----------------------------------------------------------------- stale

  @Test
  void aReviewOfAStateThatHasSinceChangedIsRefused() throws SQLException {
    Desired want = desired(AccessLevel.READ, List.of(ORDERS), List.of("alice"));
    NativeGrantApplier.DryRun reviewed = applier.dryRun(request(want));
    applier.apply(request(want), reviewed.signature());

    Desired more = desired(AccessLevel.READ, List.of(ORDERS), List.of("alice", "bob"));
    assertThatThrownBy(() -> applier.apply(request(more), reviewed.signature()))
        .isInstanceOf(NativeGrantApplier.StaleReviewException.class);
    assertThatThrownBy(() -> count("bob", "sales.orders"))
        .hasMessageContaining("permission denied");
  }

  // -------------------------------------------------------------- blockers

  @Test
  void aSuperuserAccountIsRefused() throws SQLException {
    Desired want = desired(AccessLevel.READ, List.of(ORDERS), List.of("alice"));
    NativeGrantApplier.Request request = new NativeGrantApplier.Request(target, superuser, want);
    NativeGrantApplier.DryRun plan = applier.dryRun(request);
    assertThat(plan.isBlocked()).isTrue();
    assertThat(plan.inspection().blockers()).anyMatch(b -> b.contains("superuser"));
    assertThatThrownBy(() -> applier.apply(request, plan.signature()))
        .isInstanceOf(NativeGrantApplier.RefusedException.class);
    assertThat(roleExists()).isFalse();
  }

  @Test
  void aMissingGrantOptionIsABlockerAndNothingIsCreated() throws SQLException {
    Desired want = desired(AccessLevel.READ, List.of(ORDERS, LEDGER), List.of("alice"));
    NativeGrantApplier.Request request =
        new NativeGrantApplier.Request(
            target, new CredentialResolver.Credential("arak_weak", PASSWORD), want);
    NativeGrantApplier.DryRun plan = applier.dryRun(request);
    assertThat(plan.inspection().blockers())
        .singleElement()
        .asString()
        .contains("SELECT WITH GRANT OPTION on finance.ledger");
    assertThatThrownBy(() -> applier.apply(request, plan.signature()))
        .isInstanceOf(NativeGrantApplier.RefusedException.class);
    assertThat(roleExists()).isFalse();
  }

  @Test
  void aGrantOptionHeldOnlyThroughARoleIsABlocker() throws SQLException {
    run(
        "CREATE ROLE owners NOLOGIN",
        "GRANT SELECT ON finance.ledger TO owners WITH GRANT OPTION",
        "GRANT owners TO arak_weak");
    NativeGrantApplier.DryRun plan =
        applier.dryRun(
            new NativeGrantApplier.Request(
                target,
                new CredentialResolver.Credential("arak_weak", PASSWORD),
                desired(AccessLevel.READ, List.of(LEDGER), List.of("alice"))));
    assertThat(plan.inspection().blockers())
        .anyMatch(b -> b.contains("granted to the account itself"));
  }

  @Test
  void missingTablesAndLoginsAreBlockers() throws SQLException {
    NativeGrantApplier.DryRun plan =
        applier.dryRun(
            request(
                desired(
                    AccessLevel.READ,
                    List.of(ORDERS, new Table("sales", "gone")),
                    List.of("alice", "ghost", "nobody_role", "arak_push"))));
    assertThat(plan.inspection().blockers())
        .anyMatch(b -> b.contains("Table sales.gone does not exist"))
        .anyMatch(b -> b.contains("Login ghost does not exist"))
        .anyMatch(b -> b.contains("nobody_role is a role that cannot log in"))
        .anyMatch(b -> b.contains("ARAK's own account arak_push"));
  }

  @Test
  void aRoleOfTheSameNameThatArakDidNotCreateIsNotTakenOver() throws SQLException {
    run("CREATE ROLE \"" + ROLE + "\" NOLOGIN");
    NativeGrantApplier.DryRun plan =
        applier.dryRun(request(desired(AccessLevel.READ, List.of(ORDERS), List.of("alice"))));
    assertThat(plan.inspection().blockers()).anyMatch(b -> b.contains("ARAK's comment"));
  }

  @Test
  void publicConnectIsReportedAndLeftAlone() throws SQLException {
    run("GRANT CONNECT ON DATABASE \"" + POSTGRES.getDatabaseName() + "\" TO PUBLIC");
    Desired want = desired(AccessLevel.READ, List.of(ORDERS), List.of("alice"));
    NativeGrantApplier.DryRun plan = applier.dryRun(request(want));
    assertThat(plan.inspection().warnings()).anyMatch(w -> w.startsWith("PUBLIC holds CONNECT"));
    applier.apply(request(want), plan.signature());
    applier.teardown(target, push, ROLE, POSTGRES.getDatabaseName());
    assertThat(query(superuser,
            "SELECT has_database_privilege('carol', current_database(), 'CONNECT')::text"))
        .containsExactly("true");
  }

  @Test
  void otherReadersOfATableInScopeAreReported() throws SQLException {
    run("GRANT SELECT ON sales.orders TO carol");
    NativeGrantApplier.DryRun plan =
        applier.dryRun(request(desired(AccessLevel.READ, List.of(ORDERS), List.of("alice"))));
    assertThat(plan.inspection().otherReaders())
        .contains("sales.orders: carol")
        .anyMatch(line -> line.endsWith("(owner)"));
  }

  // ----------------------------------------------------------------- drift

  @Test
  void handEditsShowAsDriftAndAreUndoneByTheNextApply() throws SQLException {
    Desired want = desired(AccessLevel.READ, List.of(ORDERS), List.of("alice"));
    NativeGrantApplier.Applied applied = applyNow(want);

    // Somebody with the push account's own password, working by hand.
    run("ALTER ROLE \"" + ROLE + "\" LOGIN");
    runAs(
        push,
        "REVOKE SELECT ON sales.orders FROM \"" + ROLE + "\"",
        "GRANT \"" + ROLE + "\" TO carol");

    PostgresGrantCompiler.Actual now = applier.read(target, push, ROLE);
    assertThat(NativeGrantApplier.fingerprint(now)).isNotEqualTo(applied.fingerprint());
    NativeGrantApplier.DryRun fix = applier.dryRun(request(want));
    assertThat(fix.plan().changes())
        .extracting(PostgresGrantCompiler.Change::step)
        .containsExactly(Step.RESET_ROLE, Step.REVOKE_MEMBER, Step.GRANT_SELECT);

    NativeGrantApplier.Applied reapplied = applier.apply(request(want), fix.signature());
    assertThat(reapplied.fingerprint()).isEqualTo(applied.fingerprint());
    assertThatThrownBy(() -> count("carol", "sales.orders"))
        .hasMessageContaining("permission denied");
    assertThat(count("alice", "sales.orders")).isEqualTo(2);
  }

  @Test
  void createroleAndInheritGivenByHandAreTakenAway() throws SQLException {
    Desired want = desired(AccessLevel.READ, List.of(ORDERS), List.of("alice"));
    NativeGrantApplier.Applied applied = applyNow(want);
    run("ALTER ROLE \"" + ROLE + "\" CREATEROLE INHERIT");

    NativeGrantApplier.DryRun fix = applier.dryRun(request(want));
    assertThat(fix.plan().changes())
        .extracting(PostgresGrantCompiler.Change::sql)
        .containsExactly("ALTER ROLE \"" + ROLE + "\" NOCREATEROLE NOINHERIT;");
    assertThat(applier.apply(request(want), fix.signature()).fingerprint())
        .isEqualTo(applied.fingerprint());
  }

  @Test
  void createdbGivenByHandIsABlockerForAnAccountWithoutIt() throws SQLException {
    Desired want = desired(AccessLevel.READ, List.of(ORDERS), List.of("alice"));
    applyNow(want);
    run("ALTER ROLE \"" + ROLE + "\" CREATEDB");
    NativeGrantApplier.DryRun plan = applier.dryRun(request(want));
    assertThat(plan.inspection().blockers()).anyMatch(b -> b.contains("CREATEDB"));
    assertThatThrownBy(() -> applier.apply(request(want), plan.signature()))
        .isInstanceOf(NativeGrantApplier.RefusedException.class);
  }

  @Test
  void aRoleMadeSuperuserByHandIsABlocker() throws SQLException {
    Desired want = desired(AccessLevel.READ, List.of(ORDERS), List.of("alice"));
    applyNow(want);
    run("ALTER ROLE \"" + ROLE + "\" BYPASSRLS");
    assertThat(applier.dryRun(request(want)).inspection().blockers())
        .anyMatch(b -> b.contains("BYPASSRLS"));
  }

  // -------------------------------------------------------------- teardown

  @Test
  void teardownPutsEveryAclBackAsItWas() throws SQLException {
    String before = acls();
    applyNow(desired(AccessLevel.READ, List.of(ORDERS, CUSTOMER, LEDGER), List.of("alice", "bob")));
    assertThat(acls()).isNotEqualTo(before);

    NativeGrantApplier.Applied gone =
        applier.teardown(target, push, ROLE, POSTGRES.getDatabaseName());

    assertThat(gone.after().roleExists()).isFalse();
    assertThat(roleExists()).isFalse();
    assertThat(acls()).isEqualTo(before);
    assertThat(gone.script()).doesNotContain("DROP OWNED");
    assertThatThrownBy(() -> count("alice", "sales.orders"))
        .hasMessageContaining("permission denied for database");
  }

  @Test
  void teardownLeavesTheDbasGrantsAndSaysWhyTheRoleStays() throws SQLException {
    applyNow(desired(AccessLevel.READ, List.of(ORDERS), List.of("alice")));
    run("GRANT SELECT ON finance.ledger TO \"" + ROLE + "\"");

    NativeGrantApplier.Applied result =
        applier.teardown(target, push, ROLE, POSTGRES.getDatabaseName());

    assertThat(roleExists()).isTrue();
    assertThat(result.warnings()).anyMatch(w -> w.contains("was not dropped"));
    assertThat(result.after().members()).isEmpty();
    assertThat(result.after().select()).isEmpty();
    assertThatThrownBy(() -> count("alice", "finance.ledger"))
        .hasMessageContaining("permission denied");
  }

  @Test
  void teardownOfARoleThatIsNotThereDoesNothing() throws SQLException {
    NativeGrantApplier.Applied result =
        applier.teardown(target, push, ROLE, POSTGRES.getDatabaseName());
    assertThat(result.statements()).isZero();
  }

  @Test
  void teardownRefusesARoleWithoutArakComment() throws SQLException {
    run("CREATE ROLE \"" + ROLE + "\" NOLOGIN");
    assertThatThrownBy(() -> applier.teardown(target, push, ROLE, POSTGRES.getDatabaseName()))
        .isInstanceOf(NativeGrantApplier.RefusedException.class);
    assertThat(roleExists()).isTrue();
  }

  // --------------------------------------------------------------- helpers

  private static Desired desired(AccessLevel level, List<Table> tables, List<String> members) {
    return new Desired(
        ROLE,
        POSTGRES.getDatabaseName(),
        level,
        new TreeSet<>(tables),
        new TreeSet<>(members),
        PostgresGrantCompiler.comment(POLICY, "IT"));
  }

  private static NativeGrantApplier.Request request(Desired desired) {
    return new NativeGrantApplier.Request(target, push, desired);
  }

  private NativeGrantApplier.Applied applyNow(Desired desired) throws SQLException {
    NativeGrantApplier.DryRun plan = applier.dryRun(request(desired));
    assertThat(plan.inspection().blockers()).isEmpty();
    return applier.apply(request(desired), plan.signature());
  }

  private static long count(String login, String table) throws SQLException {
    try (Connection c = connect(new CredentialResolver.Credential(login, PASSWORD));
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery("SELECT count(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  private static boolean roleExists() throws SQLException {
    return !query(superuser, "SELECT 1 FROM pg_roles WHERE rolname = '" + ROLE + "'").isEmpty();
  }

  /** Every ACL ARAK could have touched, as text, for a before-and-after comparison. */
  private static String acls() throws SQLException {
    return String.join(
        "\n",
        query(
            superuser,
            "SELECT 'db ' || coalesce(datacl::text, '') FROM pg_database"
                + " WHERE datname = current_database()"
                + " UNION ALL SELECT 'ns ' || nspname || ' ' || coalesce(nspacl::text, '')"
                + " FROM pg_namespace WHERE nspname IN ('sales', 'finance')"
                + " UNION ALL SELECT 'rel ' || relname || ' ' || coalesce(relacl::text, '')"
                + " FROM pg_class WHERE relnamespace::regnamespace::text IN ('sales', 'finance')"
                + " AND relkind = 'r'"
                + " UNION ALL SELECT 'member ' || pg_get_userbyid(roleid) || ' '"
                + " || pg_get_userbyid(member) FROM pg_auth_members"
                + " WHERE pg_get_userbyid(member) IN ('alice', 'bob', 'carol')"
                + " ORDER BY 1"));
  }

  private static List<String> query(CredentialResolver.Credential who, String sql)
      throws SQLException {
    List<String> out = new ArrayList<>();
    try (Connection c = connect(who);
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery(sql)) {
      while (r.next()) {
        out.add(r.getString(1));
      }
    }
    return out;
  }

  private static void run(String... sql) throws SQLException {
    runAs(superuser, sql);
  }

  private static void runAs(CredentialResolver.Credential who, String... sql)
      throws SQLException {
    try (Connection c = connect(who);
        Statement s = c.createStatement()) {
      for (String statement : sql) {
        s.execute(statement);
      }
    }
  }

  private static Connection connect(CredentialResolver.Credential who) throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", who.username());
    properties.setProperty("password", who.password());
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), properties);
  }
}
