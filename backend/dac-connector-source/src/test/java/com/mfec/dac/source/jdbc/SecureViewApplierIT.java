package com.mfec.dac.source.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.compiler.sql.PostgresDialect;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer;
import com.mfec.dac.compiler.sql.SqlDialect;
import com.mfec.dac.compiler.sql.ViewCompiler;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The secure view, on a real database, read by real logins.
 *
 * <p>Everything up to here has been argument: the compiler produces SQL that
 * looks right, the maintainer produces rows that look right, and both are
 * tested against each other. This is the first test that asks Postgres.
 *
 * <p>What it checks is not that the statements execute. It is that two people
 * with different entitlements, selecting from the same view in the same
 * database, get different answers, and that those answers are the ones the
 * policies said they should get. A test that only asserted the DDL ran would
 * pass just as happily over a view that showed everybody everything.
 */
@Testcontainers
class SecureViewApplierIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final SqlDialect DIALECT = new PostgresDialect();
  private static final String ASSET = "sales.customer";

  /** The masks the policies use, named once so the tests and the view agree. */
  private static final MaskingSpec REDACT_LOCAL_PART =
      new MaskingSpec()
          .withFunction(MaskingSpec.MaskingFunction.REGEX_REPLACE)
          .withRegex("^[^@]+")
          .withReplacement("***");

  private static final MaskingSpec NULLIFY =
      new MaskingSpec().withFunction(MaskingSpec.MaskingFunction.NULLIFY);

  private static final MaskingSpec LAST_FOUR =
      new MaskingSpec().withFunction(MaskingSpec.MaskingFunction.PARTIAL).withShowLast(4);

  private static final MaskingSpec REDACTED =
      new MaskingSpec()
          .withFunction(MaskingSpec.MaskingFunction.CONSTANT)
          .withConstant("***REDACTED***");

  private static SourceProbe.Target target;
  private static CredentialResolver.Credential owner;

  private final SecureViewApplier applier = new SecureViewApplier();

  @BeforeAll
  static void coordinates() {
    target =
        new SourceProbe.Target(
            "POSTGRES",
            POSTGRES.getHost(),
            POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
            POSTGRES.getDatabaseName());
    owner = new CredentialResolver.Credential(POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /**
   * A fresh source table and fresh logins before every test.
   *
   * <p>Dropped and recreated rather than cleaned, because several of these
   * tests are about what the applier does to rows that are already there, and
   * a leftover row from a previous test would make one of them pass for the
   * wrong reason.
   */
  @BeforeEach
  void seed() throws SQLException {
    run(
        "DROP SCHEMA IF EXISTS sales CASCADE",
        "DROP SCHEMA IF EXISTS sec CASCADE",
        "DROP SCHEMA IF EXISTS acl CASCADE",
        "CREATE SCHEMA sales",
        "CREATE TABLE sales.customer ("
            + " id int PRIMARY KEY,"
            + " email text,"
            + " citizen_id text,"
            + " branch_code text)",
        "INSERT INTO sales.customer VALUES"
            + " (1, 'anan@example.com',  '1103700011111', 'BKK-01'),"
            + " (2, 'bee@example.com',   '1103700022222', 'CNX-01'),"
            + " (3, 'chai@example.com',  '1103700033333', 'SGN-01')");

    for (String login : List.of("analyst_a", "analyst_b")) {
      run(
          "DROP OWNED BY " + login + " CASCADE",
          "DROP ROLE IF EXISTS " + login);
      run(
          "CREATE ROLE " + login + " LOGIN PASSWORD 'it-only-" + login + "'",
          // Deliberately no privilege on sales.customer. The whole point of the
          // mode is that the base table is unreachable and the view is not.
          "REVOKE ALL ON sales.customer FROM " + login);
    }
  }

  // ------------------------------------------------------------------ cases

  @Test
  @DisplayName("on a database with nothing installed, everything is an insert")
  void nothingInstalledMeansEverythingIsAnInsert() throws SQLException {
    SecureViewApplier.DryRun dry = applier.dryRun(request(shapes()));

    assertThat(dry.installed()).isEqualTo(RowEntitlementMaintainer.Rows.NONE);
    assertThat(dry.rows().delete().isEmpty()).isTrue();
    assertThat(dry.isSatisfied()).isFalse();
    assertThat(dry.applyScript()).contains("CREATE OR REPLACE VIEW");
    // Reading the plan is how somebody decides whether to press the button, so
    // it has to be there before anything is written.
    assertThat(dry.rows().insert().subscriptions()).hasSize(2);
  }

  @Test
  @DisplayName("after applying, two readers of one view see two different tables")
  void theViewShowsEachReaderWhatTheirPolicySays() throws SQLException {
    SecureViewApplier.Request request = request(shapes());
    applier.apply(request, applier.dryRun(request));
    grantReadOn("sec", "customer", "analyst_a", "analyst_b");

    // analyst_a: branches BKK-01 and CNX-01, email redacted, citizen_id last four.
    List<String> a = rowsAs("analyst_a", "SELECT branch_code, email, citizen_id FROM sec.customer ORDER BY branch_code");
    assertThat(a)
        .containsExactly(
            "BKK-01|***@example.com|*********1111",
            "CNX-01|***@example.com|*********2222");

    // analyst_b: branch SGN-01 only, and both sensitive columns nulled.
    List<String> b = rowsAs("analyst_b", "SELECT branch_code, email, citizen_id FROM sec.customer ORDER BY branch_code");
    assertThat(b).containsExactly("SGN-01|null|null");
  }

  @Test
  @DisplayName("the base table stays unreachable; the view is the only way in")
  void theBaseTableIsNotReadable() throws SQLException {
    SecureViewApplier.Request request = request(shapes());
    applier.apply(request, applier.dryRun(request));
    grantReadOn("sec", "customer", "analyst_a");

    assertThatThrownBy(() -> rowsAs("analyst_a", "SELECT * FROM sales.customer"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  @DisplayName("running it twice has nothing to do the second time")
  void applyingTwiceIsIdempotent() throws SQLException {
    SecureViewApplier.Request request = request(shapes());
    applier.apply(request, applier.dryRun(request));

    SecureViewApplier.DryRun again = applier.dryRun(request);
    assertThat(again.isSatisfied()).isTrue();
    assertThat(again.rows().insert().isEmpty()).isTrue();
    assertThat(again.rows().delete().isEmpty()).isTrue();
  }

  @Test
  @DisplayName("a principal the policies no longer mention loses their rows and their view")
  void revokingAPrincipalTakesTheirRowsAway() throws SQLException {
    SecureViewApplier.Request full = request(shapes());
    applier.apply(full, applier.dryRun(full));
    grantReadOn("sec", "customer", "analyst_a", "analyst_b");
    assertThat(rowsAs("analyst_b", "SELECT id FROM sec.customer")).hasSize(1);

    // The same asset, evaluated again, with analyst_b gone from the population.
    SecureViewApplier.Request narrowed = request(List.of(shapes().get(0)));
    SecureViewApplier.DryRun dry = applier.dryRun(narrowed);
    assertThat(dry.rows().delete().subscriptions())
        .containsExactly(new RowEntitlementMaintainer.Subscription("analyst_b", ASSET));

    applier.apply(narrowed, dry);

    // Still able to run the query; simply has nothing to see. Losing access to
    // rows and losing access to the object are different events, and a reader
    // should be able to tell which one happened to them.
    assertThat(rowsAs("analyst_b", "SELECT id FROM sec.customer")).isEmpty();
    assertThat(rowsAs("analyst_a", "SELECT id FROM sec.customer")).hasSize(2);
  }

  @Test
  @DisplayName("another asset's entitlements are neither read nor deleted")
  void oneAssetsMaintenanceLeavesEveryOtherAssetAlone() throws SQLException {
    SecureViewApplier.Request request = request(shapes());
    applier.apply(request, applier.dryRun(request));

    // A second governed asset on the same database, with its own rows. These
    // three tables are shared by every secure view here, so an unscoped read
    // would offer these rows to the maintainer as this asset's current state,
    // and it would correctly compute a deletion for all of them.
    run(
        "INSERT INTO acl.asset_subscription VALUES ('analyst_a', 'hr.employee')",
        "INSERT INTO acl.row_entitlement VALUES ('analyst_a', 'hr.employee', 'branch_code', 'BKK-01')",
        "INSERT INTO acl.column_grant VALUES ('analyst_a', 'hr.employee', 'salary', 'PLAIN')");

    SecureViewApplier.DryRun dry = applier.dryRun(request);
    assertThat(dry.installed().subscriptions())
        .allSatisfy(row -> assertThat(row.asset()).isEqualTo(ASSET));
    assertThat(dry.isSatisfied()).isTrue();

    applier.apply(request, dry);

    assertThat(count("SELECT count(*) FROM acl.asset_subscription WHERE asset = 'hr.employee'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM acl.row_entitlement WHERE asset = 'hr.employee'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM acl.column_grant WHERE asset = 'hr.employee'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("changing a reader's treatment replaces the grant instead of colliding with it")
  void aChangedTreatmentReplacesTheRowItReplaces() throws SQLException {
    SecureViewApplier.Request before = request(shapes());
    applier.apply(before, applier.dryRun(before));
    grantReadOn("sec", "customer", "analyst_a");
    assertThat(rowsAs("analyst_a", "SELECT citizen_id FROM sec.customer ORDER BY id"))
        .containsExactly("*********1111", "*********2222");

    // analyst_a's citizen_id goes from the last four digits to a constant.
    // analyst_b still nullifies it, so NULLIFY stays the fallback and both of
    // these are branches that need a grant row: one primary key in
    // column_grant, two different values, which is a delete and an insert of
    // the same row rather than an update.
    List<PolicyDecision> changed =
        List.of(
            allowed("analyst_a")
                .withRowPredicates(List.of(branches("BKK-01", "CNX-01")))
                .withColumnMasks(
                    List.of(mask("email", REDACT_LOCAL_PART), mask("citizen_id", REDACTED))),
            shapes().get(1));

    SecureViewApplier.Request after = request(changed);
    SecureViewApplier.DryRun dry = applier.dryRun(after);
    assertThat(dry.rows().delete().grants())
        .containsExactly(
            new RowEntitlementMaintainer.ColumnGrant(
                "analyst_a", ASSET, "citizen_id", ViewCompiler.treatmentKey(LAST_FOUR, null)));
    applier.apply(after, dry);

    assertThat(rowsAs("analyst_a", "SELECT citizen_id FROM sec.customer ORDER BY id"))
        .containsExactly("***REDACTED***", "***REDACTED***");
    assertThat(
            count(
                "SELECT count(*) FROM acl.column_grant"
                    + " WHERE asset = '" + ASSET + "' AND principal = 'analyst_a'"
                    + " AND column_name = 'citizen_id'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("refuses to apply a review the source has moved on from, and changes nothing")
  void aStaleReviewIsRefused() throws SQLException {
    SecureViewApplier.Request request = request(shapes());
    applier.apply(request, applier.dryRun(request));

    SecureViewApplier.DryRun reviewed = applier.dryRun(request);
    assertThat(reviewed.isSatisfied()).isTrue();

    // Somebody else's work lands between the review and the click.
    run(
        "DELETE FROM acl.row_entitlement"
            + " WHERE asset = '" + ASSET + "' AND principal = 'analyst_b'");

    assertThatThrownBy(() -> applier.apply(request, reviewed))
        .isInstanceOf(SecureViewApplier.StaleReviewException.class)
        .hasMessageContaining("changed after this change was reviewed")
        .hasMessageContaining("Nothing was applied");

    // And it means it: the row it would have re-inserted is still missing.
    assertThat(
            count(
                "SELECT count(*) FROM acl.row_entitlement"
                    + " WHERE asset = '" + ASSET + "' AND principal = 'analyst_b'"))
        .isZero();
  }

  @Test
  @DisplayName("a failed apply leaves the database as it found it")
  void aFailureRollsBackTheWholeChange() throws SQLException {
    // A view over a column introspection claims exists and the table does not
    // have, which is the shape of a stale catalogue. Every masked column is
    // still there, so the maintainer has nothing to object to and the failure
    // happens where it is meant to: at the database, half way through a plan
    // that has already created two schemas and three tables.
    ViewCompiler.Target broken =
        new ViewCompiler.Target(
            "sales",
            "customer",
            "sec",
            "customer",
            "acl",
            ASSET,
            List.of("id", "email", "citizen_id", "branch_code", "no_such_column"),
            ViewCompiler.IdentitySource.DB_PRINCIPAL,
            null);
    ViewCompiler compiler = new ViewCompiler(DIALECT);
    ViewCompiler.Plan plan = compiler.compile(shapes(), broken);
    SecureViewApplier.Request request =
        new SecureViewApplier.Request(
            target, owner, DIALECT, broken, plan, shapes(), entitlements());

    assertThatThrownBy(() -> applier.apply(request, applier.dryRun(request)))
        .isInstanceOf(SQLException.class);

    assertThat(count("SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'acl'"))
        .isZero();
    assertThat(count("SELECT count(*) FROM information_schema.views WHERE table_schema = 'sec'"))
        .isZero();
  }

  @Test
  @DisplayName("rolling back drops the view and leaves the entitlements for the other views")
  void rollbackDropsTheViewOnly() throws SQLException {
    SecureViewApplier.Request request = request(shapes());
    applier.apply(request, applier.dryRun(request));

    applier.rollback(request);

    assertThat(count("SELECT count(*) FROM information_schema.views WHERE table_schema = 'sec'"))
        .isZero();
    assertThat(count("SELECT count(*) FROM acl.asset_subscription WHERE asset = '" + ASSET + "'"))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("applying without a reviewed dry run is refused")
  void applyingUnreviewedIsRefused() {
    SecureViewApplier.Request request = request(shapes());
    assertThatThrownBy(() -> applier.apply(request, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("a change nobody read");
  }

  // --------------------------------------------------------------- fixtures

  /** The two readers of this asset, and how each of them reads it. */
  private static List<PolicyDecision> shapes() {
    return List.of(
        allowed("analyst_a")
            .withRowPredicates(List.of(branches("BKK-01", "CNX-01")))
            .withColumnMasks(
                List.of(mask("email", REDACT_LOCAL_PART), mask("citizen_id", LAST_FOUR))),
        allowed("analyst_b")
            .withRowPredicates(List.of(branches("SGN-01")))
            .withColumnMasks(List.of(mask("email", NULLIFY), mask("citizen_id", NULLIFY))));
  }

  private SecureViewApplier.Request request(List<PolicyDecision> decisions) {
    ViewCompiler.Target view =
        new ViewCompiler.Target(
            "sales",
            "customer",
            "sec",
            "customer",
            "acl",
            ASSET,
            List.of("id", "email", "citizen_id", "branch_code"),
            ViewCompiler.IdentitySource.DB_PRINCIPAL,
            null);
    // Compiled from the same decisions that are maintained from, because a plan
    // and rows that came from different evaluations is the one combination the
    // maintainer refuses, and it should never arise here.
    ViewCompiler.Plan plan = new ViewCompiler(DIALECT).compile(decisions, view);
    return new SecureViewApplier.Request(
        target, owner, DIALECT, view, plan, decisions, entitlements());
  }

  /** No {@code ENTITLEMENT_JOIN} predicates here; the gates carry their values. */
  private static RowEntitlementMaintainer.EntitlementSource entitlements() {
    return RowEntitlementMaintainer.EntitlementSource.NONE;
  }

  private static PolicyDecision allowed(String principal) {
    return new PolicyDecision()
        .withPrincipal(principal)
        .withAssetFqn(ASSET)
        .withAllowed(true);
  }

  private static ResolvedRowPredicate branches(String... values) {
    return new ResolvedRowPredicate()
        .withKind(ResolvedRowPredicate.Kind.IN_LIST)
        .withColumn("branch_code")
        .withOperator(ResolvedRowPredicate.FacetOperator.IN)
        .withValues(List.of((Object[]) values));
  }

  private static ResolvedColumnMask mask(String column, MaskingSpec spec) {
    return new ResolvedColumnMask().withColumn(column).withMasking(spec);
  }

  // ---------------------------------------------------------------- plumbing

  private static void run(String... statements) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        try {
          statement.execute(sql);
        } catch (SQLException failure) {
          // DROP OWNED BY on a role that does not exist, and similar teardown
          // that only matters when the previous test got further than this one.
          if (!sql.startsWith("DROP")) {
            throw failure;
          }
        }
      }
    }
  }

  /** Lets a login reach the view, which the plan leaves to the cutover step. */
  private static void grantReadOn(String schema, String view, String... logins)
      throws SQLException {
    for (String login : logins) {
      run(
          "GRANT USAGE ON SCHEMA " + schema + " TO " + login,
          "GRANT USAGE ON SCHEMA acl TO " + login,
          "GRANT SELECT ON " + schema + "." + view + " TO " + login,
          // The view reads the entitlement tables as the caller, so the caller
          // has to be able to read them. They say who may see what, not what,
          // and every secure view on the database depends on them being legible.
          "GRANT SELECT ON acl.asset_subscription TO " + login,
          "GRANT SELECT ON acl.row_entitlement TO " + login,
          "GRANT SELECT ON acl.column_grant TO " + login);
    }
  }

  /** Rows as one pipe-joined string each, read over a login of that name. */
  private static List<String> rowsAs(String login, String sql) throws SQLException {
    String url =
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/"
            + POSTGRES.getDatabaseName();
    List<String> out = new ArrayList<>();
    try (Connection connection = DriverManager.getConnection(url, login, "it-only-" + login);
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

  private static long count(String sql) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
