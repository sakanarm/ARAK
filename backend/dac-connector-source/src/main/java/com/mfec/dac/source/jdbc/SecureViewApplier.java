package com.mfec.dac.source.jdbc;

import com.mfec.dac.compiler.sql.RowEntitlementMaintainer;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.ColumnGrant;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.Entitlement;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.Maintenance;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.Rows;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer.Subscription;
import com.mfec.dac.compiler.sql.SqlDialect;
import com.mfec.dac.compiler.sql.ViewCompiler;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.Unenforceable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Puts a compiled secure view and its entitlement rows onto a source database.
 *
 * <p>This is the third and last piece of enforcement mode 5.1.2. The compiler
 * decides what the view looks like, the maintainer decides which rows each
 * principal needs, and neither of them touches a database. This class is the
 * only one that does, which is why it is also the only one that has to worry
 * about what happens between deciding and doing.
 *
 * <p>Three things follow from that, and they are the whole design:
 *
 * <ol>
 *   <li><b>Everything is one transaction.</b> The view and the rows it reads
 *       are a single statement about who may see what. Committing the view
 *       without the rows hides every row from everybody; committing the rows
 *       without the view leaves data in the clear. Neither is a state anybody
 *       should ever be able to observe, so the DDL and the DML commit together
 *       or not at all (FR-6.4).
 *   <li><b>A dry run is not a promise.</b> The rows are read again inside the
 *       apply transaction, and if the change that comes out is not the change
 *       that was approved, nothing is applied. See {@link StaleReviewException}.
 *   <li><b>Deletes go before inserts.</b> Changing a reader's treatment of a
 *       column is a delete and an insert of the same primary key, because
 *       {@code column_grant} deliberately allows only one row per column per
 *       reader. Inserting first collides; deleting first does not.
 * </ol>
 *
 * <p>Every read and write is scoped to one asset key. That scoping is not an
 * optimisation: the three tables hold the entitlements of every governed asset
 * on the database, and an unscoped read would hand the maintainer another
 * asset's rows as this asset's current state, which it would faithfully compute
 * a deletion for. One missing {@code WHERE} clause is the difference between
 * applying a policy and revoking the entire database.
 */
public final class SecureViewApplier {

  /** Seconds to wait for a connection before giving up on the source. */
  private final int loginTimeoutSeconds;

  /**
   * Seconds any one statement may run. Applying is DDL against a customer's
   * production database, and a {@code CREATE VIEW} that blocks behind a lock is
   * better abandoned than left holding one of its own.
   */
  private final int statementTimeoutSeconds;

  public SecureViewApplier() {
    this(10, 60);
  }

  public SecureViewApplier(int loginTimeoutSeconds, int statementTimeoutSeconds) {
    this.loginTimeoutSeconds = loginTimeoutSeconds;
    this.statementTimeoutSeconds = statementTimeoutSeconds;
  }

  /**
   * Everything needed to work out what would change, gathered once so that the
   * dry run and the apply cannot be handed different inputs.
   *
   * @param dialect must be the dialect the plan was compiled with; a plan
   *     compiled for one engine and applied through another would quote
   *     identifiers the target cannot parse
   * @param decisions one per principal evaluated against this asset. A
   *     principal missing from this list is revoked, so the list must be the
   *     whole population, not the ones who changed.
   */
  public record Request(
      SourceProbe.Target target,
      CredentialResolver.Credential credential,
      SqlDialect dialect,
      ViewCompiler.Target view,
      ViewCompiler.Plan plan,
      List<PolicyDecision> decisions,
      RowEntitlementMaintainer.EntitlementSource entitlements) {

    public Request {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(credential, "credential");
      Objects.requireNonNull(dialect, "dialect");
      Objects.requireNonNull(view, "view");
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(decisions, "decisions");
      decisions = List.copyOf(decisions);
      entitlements =
          entitlements == null ? RowEntitlementMaintainer.EntitlementSource.NONE : entitlements;
    }
  }

  /**
   * What an apply would do, in the form a person has to agree to before it
   * happens.
   *
   * @param installed what the three tables hold for this asset right now
   * @param rows the diff, from {@link RowEntitlementMaintainer}
   * @param warnings things that are true and unwelcome: masks this engine
   *     cannot express, gates nobody has a value for
   * @param signature what {@link #apply} compares against, so that agreeing to
   *     this dry run cannot silently agree to a different one
   */
  public record DryRun(
      Rows installed,
      Maintenance rows,
      String applyScript,
      String rollbackScript,
      List<String> notes,
      List<String> warnings,
      String signature) {

    /** True when the source already says exactly what the policies say. */
    public boolean isSatisfied() {
      return rows.isSatisfied();
    }
  }

  /** What an apply did, for {@code enforcement_state} and the audit trail. */
  public record Applied(
      String appliedDdl,
      String rollbackDdl,
      int statements,
      int inserted,
      int deleted,
      List<String> notes) {}

  /**
   * Thrown when the source changed between the review and the apply.
   *
   * <p>Applying anyway would carry out a change nobody read. The rows that
   * appeared in between belong to somebody -- another operator's apply, a
   * catalogue re-sync, a hand-written grant -- and deleting them because they
   * were not in a diff computed minutes ago is the kind of correct-looking
   * behaviour that takes an afternoon to understand afterwards. Re-run the dry
   * run and look at what it says now.
   */
  public static final class StaleReviewException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public StaleReviewException(String message) {
      super(message);
    }
  }

  // ---------------------------------------------------------------- dry run

  /**
   * Reads the source and works out what applying would change. Opens a
   * read-only connection and writes nothing.
   */
  public DryRun dryRun(Request request) throws SQLException {
    Objects.requireNonNull(request, "request");
    try (Connection connection =
        JdbcTargets.open(request.target(), request.credential(), loginTimeoutSeconds)) {
      return dryRun(request, connection);
    }
  }

  /** As {@link #dryRun(Request)}, on a connection the caller owns. */
  public DryRun dryRun(Request request, Connection connection) throws SQLException {
    Rows installed = read(connection, request);
    Maintenance maintenance =
        RowEntitlementMaintainer.maintain(
            request.plan(),
            request.view().assetKey(),
            request.decisions(),
            request.entitlements(),
            installed);

    List<String> warnings = new ArrayList<>();
    for (Unenforceable item : request.plan().unenforceable()) {
      warnings.add(item.getDetail());
    }
    warnings.addAll(maintenance.notes());

    return new DryRun(
        installed,
        maintenance,
        request.plan().applyScript(),
        request.plan().rollbackScript(),
        List.copyOf(request.plan().notes()),
        List.copyOf(warnings),
        signature(maintenance));
  }

  // ------------------------------------------------------------------ apply

  /**
   * Applies the DDL and the rows in one transaction.
   *
   * @param approved the dry run a person agreed to. The rows are read again
   *     here and the diff recomputed; if it no longer matches, nothing is
   *     applied and {@link StaleReviewException} says so.
   */
  public Applied apply(Request request, DryRun approved) throws SQLException {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(
        approved,
        "no approved dry run; applying without one would run a change nobody read (FR-6.4)");

    try (Connection connection =
        JdbcTargets.openWritable(request.target(), request.credential(), loginTimeoutSeconds)) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        // Inside the transaction, so that what is verified is what is written.
        DryRun now = dryRun(request, connection);
        if (!now.signature().equals(approved.signature())) {
          throw new StaleReviewException(
              "the entitlement rows on "
                  + request.target().database()
                  + " changed after this change was reviewed: the approved run would have"
                  + " inserted "
                  + approved.rows().insert().size()
                  + " and deleted "
                  + approved.rows().delete().size()
                  + " rows, and applying now would insert "
                  + now.rows().insert().size()
                  + " and delete "
                  + now.rows().delete().size()
                  + ". Nothing was applied. Run the dry run again and read what it says now.");
        }

        int statements = ddl(connection, request);
        int deleted = delete(connection, request, now.rows().delete());
        int inserted = insert(connection, request, now.rows().insert());

        connection.commit();
        return new Applied(
            request.plan().applyScript(),
            request.plan().rollbackScript(),
            statements,
            inserted,
            deleted,
            now.warnings());
      } catch (SQLException | RuntimeException failure) {
        rollbackQuietly(connection, failure);
        throw failure;
      } finally {
        restoreAutoCommit(connection, autoCommit);
      }
    }
  }

  /**
   * Runs the plan's rollback statements.
   *
   * <p>The entitlement rows are deliberately left where they are. The plan's
   * own rollback says why: the three tables are shared by every secure view on
   * the database. This drops the view and leaves the rows, which grant nothing
   * on their own.
   */
  public Applied rollback(Request request) throws SQLException {
    Objects.requireNonNull(request, "request");
    try (Connection connection =
        JdbcTargets.openWritable(request.target(), request.credential(), loginTimeoutSeconds)) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        int statements = 0;
        try (Statement statement = connection.createStatement()) {
          statement.setQueryTimeout(statementTimeoutSeconds);
          for (String sql : request.plan().rollback()) {
            statement.execute(sql);
            statements++;
          }
        }
        connection.commit();
        return new Applied(
            request.plan().rollbackScript(),
            request.plan().applyScript(),
            statements,
            0,
            0,
            List.copyOf(request.plan().notes()));
      } catch (SQLException | RuntimeException failure) {
        rollbackQuietly(connection, failure);
        throw failure;
      } finally {
        restoreAutoCommit(connection, autoCommit);
      }
    }
  }

  // ------------------------------------------------------------------ reads

  /** What the three tables hold for this asset, and for no other. */
  public Rows read(Connection connection, Request request) throws SQLException {
    SqlDialect dialect = request.dialect();
    ViewCompiler.Target view = request.view();
    String asset = view.assetKey();

    Set<Subscription> subscriptions = new LinkedHashSet<>();
    Set<Entitlement> entitlements = new LinkedHashSet<>();
    Set<ColumnGrant> grants = new LinkedHashSet<>();

    if (!tableExists(connection, view.aclSchema(), "asset_subscription")) {
      // The first apply on this database creates them. Nothing installed is
      // the honest answer, and it is the answer that makes the first dry run
      // read as "everything is an insert", which is what it is.
      return Rows.NONE;
    }

    String subscriptionSql =
        "SELECT "
            + dialect.quote("principal")
            + " FROM "
            + view.acl(dialect, "asset_subscription")
            + " WHERE "
            + dialect.quote("asset")
            + " = ?";
    try (PreparedStatement statement = connection.prepareStatement(subscriptionSql)) {
      statement.setQueryTimeout(statementTimeoutSeconds);
      statement.setString(1, asset);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          subscriptions.add(new Subscription(rows.getString(1), asset));
        }
      }
    }

    String entitlementSql =
        "SELECT "
            + dialect.quote("principal")
            + ", "
            + dialect.quote("entitlement_key")
            + ", "
            + dialect.quote("value")
            + " FROM "
            + view.acl(dialect, "row_entitlement")
            + " WHERE "
            + dialect.quote("asset")
            + " = ?";
    try (PreparedStatement statement = connection.prepareStatement(entitlementSql)) {
      statement.setQueryTimeout(statementTimeoutSeconds);
      statement.setString(1, asset);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          entitlements.add(
              new Entitlement(rows.getString(1), asset, rows.getString(2), rows.getString(3)));
        }
      }
    }

    String grantSql =
        "SELECT "
            + dialect.quote("principal")
            + ", "
            + dialect.quote("column_name")
            + ", "
            + dialect.quote("treatment")
            + " FROM "
            + view.acl(dialect, "column_grant")
            + " WHERE "
            + dialect.quote("asset")
            + " = ?";
    try (PreparedStatement statement = connection.prepareStatement(grantSql)) {
      statement.setQueryTimeout(statementTimeoutSeconds);
      statement.setString(1, asset);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          grants.add(
              new ColumnGrant(rows.getString(1), asset, rows.getString(2), rows.getString(3)));
        }
      }
    }

    return new Rows(subscriptions, entitlements, grants);
  }

  /**
   * Whether the entitlement tables have been created yet.
   *
   * <p>Asked of {@link java.sql.DatabaseMetaData} rather than of the catalogue,
   * because the catalogue can be out of date and this decides whether "no rows"
   * means "nobody has access" or "the table is not there".
   */
  private boolean tableExists(Connection connection, String schema, String table)
      throws SQLException {
    for (String[] spelling : new String[][] {{schema, table}, {upper(schema), upper(table)}}) {
      try (ResultSet found =
          connection.getMetaData().getTables(null, spelling[0], spelling[1], null)) {
        if (found.next()) {
          return true;
        }
      }
    }
    return false;
  }

  private static String upper(String value) {
    return value == null ? null : value.toUpperCase(Locale.ROOT);
  }

  // ----------------------------------------------------------------- writes

  private int ddl(Connection connection, Request request) throws SQLException {
    int count = 0;
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(statementTimeoutSeconds);
      for (String sql : request.plan().apply()) {
        statement.execute(sql);
        count++;
      }
    }
    return count;
  }

  /**
   * Deletes first, and deletes by full primary key.
   *
   * <p>Deleting by {@code (principal, asset)} would be shorter and would remove
   * rows this diff did not ask to remove, which is the one direction that
   * cannot be undone by the insert that follows.
   */
  private int delete(Connection connection, Request request, Rows rows) throws SQLException {
    SqlDialect dialect = request.dialect();
    ViewCompiler.Target view = request.view();
    int count = 0;

    if (!rows.subscriptions().isEmpty()) {
      String sql =
          "DELETE FROM "
              + view.acl(dialect, "asset_subscription")
              + " WHERE "
              + dialect.quote("principal")
              + " = ? AND "
              + dialect.quote("asset")
              + " = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(statementTimeoutSeconds);
        for (Subscription row : rows.subscriptions()) {
          statement.setString(1, row.principal());
          statement.setString(2, row.asset());
          statement.addBatch();
        }
        count += total(statement.executeBatch());
      }
    }

    if (!rows.entitlements().isEmpty()) {
      String sql =
          "DELETE FROM "
              + view.acl(dialect, "row_entitlement")
              + " WHERE "
              + dialect.quote("principal")
              + " = ? AND "
              + dialect.quote("asset")
              + " = ? AND "
              + dialect.quote("entitlement_key")
              + " = ? AND "
              + dialect.quote("value")
              + " = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(statementTimeoutSeconds);
        for (Entitlement row : rows.entitlements()) {
          statement.setString(1, row.principal());
          statement.setString(2, row.asset());
          statement.setString(3, row.entitlementKey());
          statement.setString(4, row.value());
          statement.addBatch();
        }
        count += total(statement.executeBatch());
      }
    }

    if (!rows.grants().isEmpty()) {
      // By column, not by column and treatment: a changed treatment is a delete
      // and an insert of the same key, and matching on the old treatment as
      // well would leave the old row in place for the insert to collide with.
      String sql =
          "DELETE FROM "
              + view.acl(dialect, "column_grant")
              + " WHERE "
              + dialect.quote("principal")
              + " = ? AND "
              + dialect.quote("asset")
              + " = ? AND "
              + dialect.quote("column_name")
              + " = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(statementTimeoutSeconds);
        for (ColumnGrant row : rows.grants()) {
          statement.setString(1, row.principal());
          statement.setString(2, row.asset());
          statement.setString(3, row.column());
          statement.addBatch();
        }
        count += total(statement.executeBatch());
      }
    }

    return count;
  }

  private int insert(Connection connection, Request request, Rows rows) throws SQLException {
    SqlDialect dialect = request.dialect();
    ViewCompiler.Target view = request.view();
    int count = 0;

    if (!rows.subscriptions().isEmpty()) {
      String sql =
          "INSERT INTO "
              + view.acl(dialect, "asset_subscription")
              + " ("
              + dialect.quote("principal")
              + ", "
              + dialect.quote("asset")
              + ") VALUES (?, ?)";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(statementTimeoutSeconds);
        for (Subscription row : rows.subscriptions()) {
          statement.setString(1, row.principal());
          statement.setString(2, row.asset());
          statement.addBatch();
        }
        count += total(statement.executeBatch());
      }
    }

    if (!rows.entitlements().isEmpty()) {
      String sql =
          "INSERT INTO "
              + view.acl(dialect, "row_entitlement")
              + " ("
              + dialect.quote("principal")
              + ", "
              + dialect.quote("asset")
              + ", "
              + dialect.quote("entitlement_key")
              + ", "
              + dialect.quote("value")
              + ") VALUES (?, ?, ?, ?)";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(statementTimeoutSeconds);
        for (Entitlement row : rows.entitlements()) {
          statement.setString(1, row.principal());
          statement.setString(2, row.asset());
          statement.setString(3, row.entitlementKey());
          statement.setString(4, row.value());
          statement.addBatch();
        }
        count += total(statement.executeBatch());
      }
    }

    if (!rows.grants().isEmpty()) {
      String sql =
          "INSERT INTO "
              + view.acl(dialect, "column_grant")
              + " ("
              + dialect.quote("principal")
              + ", "
              + dialect.quote("asset")
              + ", "
              + dialect.quote("column_name")
              + ", "
              + dialect.quote("treatment")
              + ") VALUES (?, ?, ?, ?)";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(statementTimeoutSeconds);
        for (ColumnGrant row : rows.grants()) {
          statement.setString(1, row.principal());
          statement.setString(2, row.asset());
          statement.setString(3, row.column());
          statement.setString(4, row.treatment());
          statement.addBatch();
        }
        count += total(statement.executeBatch());
      }
    }

    return count;
  }

  // ------------------------------------------------------------- signatures

  /**
   * A stable spelling of one diff, for comparing a review against reality.
   *
   * <p>Sorted, because the diff travels through sets and two runs that agree
   * exactly must not disagree about the order they agree in.
   */
  static String signature(Maintenance maintenance) {
    Set<String> lines = new TreeSet<>();
    for (Subscription row : maintenance.insert().subscriptions()) {
      lines.add("+s\u001f" + row.principal() + "\u001f" + row.asset());
    }
    for (Subscription row : maintenance.delete().subscriptions()) {
      lines.add("-s\u001f" + row.principal() + "\u001f" + row.asset());
    }
    for (Entitlement row : maintenance.insert().entitlements()) {
      lines.add(
          "+e\u001f" + row.principal() + "\u001f" + row.asset() + "\u001f" + row.entitlementKey()
              + "\u001f" + row.value());
    }
    for (Entitlement row : maintenance.delete().entitlements()) {
      lines.add(
          "-e\u001f" + row.principal() + "\u001f" + row.asset() + "\u001f" + row.entitlementKey()
              + "\u001f" + row.value());
    }
    for (ColumnGrant row : maintenance.insert().grants()) {
      lines.add(
          "+g\u001f" + row.principal() + "\u001f" + row.asset() + "\u001f" + row.column()
              + "\u001f" + row.treatment());
    }
    for (ColumnGrant row : maintenance.delete().grants()) {
      lines.add(
          "-g\u001f" + row.principal() + "\u001f" + row.asset() + "\u001f" + row.column()
              + "\u001f" + row.treatment());
    }
    return String.join("\n", lines);
  }

  // ------------------------------------------------------------------ plumbing

  private static int total(int[] counts) {
    int sum = 0;
    for (int count : counts) {
      // Drivers may answer SUCCESS_NO_INFO for a batch they carried out but did
      // not count. Treating that as one row is closer to the truth than zero.
      sum += count >= 0 ? count : 1;
    }
    return sum;
  }

  private static void rollbackQuietly(Connection connection, Throwable cause) {
    try {
      connection.rollback();
    } catch (SQLException suppressed) {
      cause.addSuppressed(suppressed);
    }
  }

  private static void restoreAutoCommit(Connection connection, boolean previous) {
    try {
      connection.setAutoCommit(previous);
    } catch (SQLException ignored) {
      // The connection is about to be closed; a failure to restore a setting on
      // it is not worth losing the real exception over.
    }
  }
}
