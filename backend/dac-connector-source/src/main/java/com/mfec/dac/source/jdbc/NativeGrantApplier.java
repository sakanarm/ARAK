package com.mfec.dac.source.jdbc;

import com.mfec.dac.compiler.sql.PostgresGrantCompiler;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.AccessLevel;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Actual;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Change;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Desired;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Plan;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Step;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Puts a subscription policy onto a PostgreSQL source as a role, its grants and
 * its members, and takes it off again (enforcement mode 5.1.1, FR-6.2).
 *
 * <p>{@link PostgresGrantCompiler} decides the statements; this class reads the
 * source, checks that ARAK's account may run them, runs them, and proves they
 * did what they said. It follows {@link SecureViewApplier} in the three things
 * that matter:
 *
 * <ol>
 *   <li><b>One transaction.</b> The role, its grants and its members commit
 *       together or not at all. A role with members and half its grants is a
 *       state nobody approved.
 *   <li><b>A dry run is not a promise.</b> The source is read again inside the
 *       apply, and if what it holds is not what was reviewed, nothing runs.
 *   <li><b>Apply proves itself.</b> After the statements run, the source is
 *       read once more: the plan must now be empty, and every member must be
 *       able to do what the level says. Otherwise the transaction is rolled
 *       back and the failure is reported, rather than a success being recorded
 *       for grants that are not there.
 * </ol>
 *
 * <h2>Only what ARAK granted is ARAK's</h2>
 *
 * Every read is filtered to grants whose grantor is the account ARAK connects
 * as. A DBA's grant to the same role or the same login is reported and left
 * alone, and so is {@code PUBLIC}. For that filter to be true, ARAK's grants
 * must be recorded as ARAK's: PostgreSQL records a grant made through a
 * membership as made by the role that holds the privilege, and a superuser's
 * grant as made by the object's owner. The prechecks therefore refuse a
 * superuser account, and require the grant options to be held by the account
 * itself rather than through a role.
 */
public final class NativeGrantApplier {

  /** Seconds to wait for a connection before giving up on the source. */
  private final int loginTimeoutSeconds;

  /** Seconds any one statement may run, and the transaction may wait for a lock. */
  private final int statementTimeoutSeconds;

  public NativeGrantApplier() {
    this(10, 60);
  }

  public NativeGrantApplier(int loginTimeoutSeconds, int statementTimeoutSeconds) {
    this.loginTimeoutSeconds = loginTimeoutSeconds;
    this.statementTimeoutSeconds = statementTimeoutSeconds;
  }

  /** The source, the account that may change it, and what the policy says. */
  public record Request(
      SourceProbe.Target target, CredentialResolver.Credential credential, Desired desired) {

    public Request {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(credential, "credential");
      Objects.requireNonNull(desired, "desired");
      if (!"POSTGRES".equalsIgnoreCase(target.engine())) {
        throw new IllegalArgumentException(
            "native subscription grants are built for PostgreSQL only, not " + target.engine());
      }
    }
  }

  /**
   * What the source holds and what stands in the way, read in one go.
   *
   * @param blockers reasons the apply would fail or would not mean what it
   *     says; an apply is refused while there is one
   * @param warnings true and worth knowing, but not ARAK's to change: grants by
   *     somebody else, PUBLIC's {@code CONNECT}
   * @param otherReaders who else holds {@code SELECT} on a table in scope, so
   *     that a reviewer knows the policy is not the only way in
   */
  public record Inspection(
      int serverVersionNum,
      String account,
      Actual actual,
      List<String> blockers,
      List<String> warnings,
      List<String> otherReaders) {

    public Inspection {
      blockers = List.copyOf(blockers);
      warnings = List.copyOf(warnings);
      otherReaders = List.copyOf(otherReaders);
    }
  }

  /** What an apply would do, as a reviewer is asked to agree to it. */
  public record DryRun(Inspection inspection, Plan plan, String signature) {

    public boolean isSatisfied() {
      return plan.isSatisfied();
    }

    public boolean isBlocked() {
      return !inspection.blockers().isEmpty();
    }
  }

  /** What an apply or a teardown did. */
  public record Applied(
      int statements, String script, String fingerprint, Actual after, List<String> warnings) {

    public Applied {
      warnings = List.copyOf(warnings);
    }
  }

  /** The source no longer holds what the review was of. */
  public static final class StaleReviewException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public StaleReviewException(String message) {
      super(message);
    }
  }

  /** A precheck failed, or the source did not end up where the plan said. */
  public static final class RefusedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public RefusedException(String message) {
      super(message);
    }
  }

  // ---------------------------------------------------------------- read

  /** Reads the source and works out what applying would change. Writes nothing. */
  public DryRun dryRun(Request request) throws SQLException {
    Objects.requireNonNull(request, "request");
    try (Connection connection =
        JdbcTargets.open(request.target(), request.credential(), loginTimeoutSeconds)) {
      Inspection inspection = inspect(connection, request.desired());
      Plan plan =
          PostgresGrantCompiler.compile(
              request.desired(), inspection.actual(), inspection.serverVersionNum());
      return new DryRun(inspection, plan, signature(inspection, request.desired()));
    }
  }

  /**
   * What the source holds for one role now, for drift detection and teardown.
   * Needs no tables and no members: it only reads.
   */
  public Actual read(
      SourceProbe.Target target, CredentialResolver.Credential credential, String role)
      throws SQLException {
    requireManaged(role);
    try (Connection connection = JdbcTargets.open(target, credential, loginTimeoutSeconds)) {
      return actual(connection, role);
    }
  }

  /** The value stored at apply and compared by the drift check. */
  public static String fingerprint(Actual actual) {
    return sha256(actual.canonical());
  }

  // ---------------------------------------------------------------- apply

  /**
   * Applies what was reviewed, or nothing.
   *
   * @param approvedSignature the {@link DryRun#signature()} the reviewer saw
   * @throws StaleReviewException when the source changed since the review
   * @throws RefusedException when a precheck fails, or the source does not end
   *     up holding what the plan said it would
   */
  public Applied apply(Request request, String approvedSignature) throws SQLException {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(approvedSignature, "approvedSignature");
    Desired desired = request.desired();
    try (Connection connection =
        JdbcTargets.openWritable(request.target(), request.credential(), loginTimeoutSeconds)) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        limits(connection);
        Inspection now = inspect(connection, desired);
        if (!now.blockers().isEmpty()) {
          throw new RefusedException(String.join(" ", now.blockers()));
        }
        if (!signature(now, desired).equals(approvedSignature)) {
          throw new StaleReviewException(
              "The source changed after the plan was reviewed. Make a new plan and review it.");
        }
        Plan plan = PostgresGrantCompiler.compile(desired, now.actual(), now.serverVersionNum());
        int count = execute(connection, plan.changes());

        Actual after = actual(connection, desired.role());
        Plan left = PostgresGrantCompiler.compile(desired, after, now.serverVersionNum());
        if (!left.isSatisfied()) {
          throw new RefusedException(
              "The source did not take every change; nothing was applied. Still missing: "
                  + left.applyScript().strip());
        }
        verifyMembers(connection, desired, now.serverVersionNum());
        connection.commit();
        return new Applied(
            count, plan.applyScript(), fingerprint(after), after, now.warnings());
      } catch (SQLException | RuntimeException failure) {
        rollbackQuietly(connection, failure);
        throw failure;
      } finally {
        restoreAutoCommit(connection, autoCommit);
      }
    }
  }

  /**
   * The statements a teardown would run, read from the source as it is now.
   * Writes nothing.
   */
  public List<Change> teardownPlan(
      SourceProbe.Target target,
      CredentialResolver.Credential credential,
      String role,
      String database)
      throws SQLException {
    requireManaged(role);
    try (Connection connection = JdbcTargets.open(target, credential, loginTimeoutSeconds)) {
      return teardownChanges(connection, role, database, new ArrayList<>());
    }
  }

  /**
   * Takes every grant ARAK made for this role off the source, and the role
   * with it.
   *
   * <p>A role that somebody else has also granted privileges to cannot be
   * dropped without revoking theirs, which is not ARAK's decision. Then the
   * role is left in place, holding only those grants and no member ARAK
   * added, and the result says so.
   */
  public Applied teardown(
      SourceProbe.Target target,
      CredentialResolver.Credential credential,
      String role,
      String database)
      throws SQLException {
    requireManaged(role);
    try (Connection connection = JdbcTargets.openWritable(target, credential, loginTimeoutSeconds)) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        limits(connection);
        List<String> warnings = new ArrayList<>();
        Actual before = actual(connection, role);
        if (before.roleExists() && !isArakComment(before.comment())) {
          throw new RefusedException(
              "Role " + role + " does not carry ARAK's comment; it is left untouched.");
        }
        List<Change> changes = teardownChanges(connection, role, database, warnings);
        int count = execute(connection, changes);
        Actual after = actual(connection, role);
        if (after.roleExists()
            && (after.connect() || !after.usage().isEmpty() || !after.select().isEmpty()
                || !after.members().isEmpty())) {
          throw new RefusedException(
              "The source still holds ARAK's grants for " + role + "; nothing was changed.");
        }
        connection.commit();
        StringBuilder script = new StringBuilder();
        for (Change change : changes) {
          script.append(change.sql()).append('\n');
        }
        return new Applied(count, script.toString(), fingerprint(after), after, warnings);
      } catch (SQLException | RuntimeException failure) {
        rollbackQuietly(connection, failure);
        throw failure;
      } finally {
        restoreAutoCommit(connection, autoCommit);
      }
    }
  }

  // ------------------------------------------------------------ inspection

  private Inspection inspect(Connection connection, Desired desired) throws SQLException {
    List<String> blockers = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    List<String> otherReaders = new ArrayList<>();

    int version;
    String account;
    String database;
    boolean superuser;
    boolean createRole;
    boolean createDb;
    try (PreparedStatement statement =
        prepare(
            connection,
            "SELECT current_setting('server_version_num')::int, current_user, current_database(),"
                + " r.rolsuper, r.rolcreaterole, r.rolcreatedb FROM pg_roles r"
                + " WHERE r.rolname = current_user")) {
      try (ResultSet row = statement.executeQuery()) {
        row.next();
        version = row.getInt(1);
        account = row.getString(2);
        database = row.getString(3);
        superuser = row.getBoolean(4);
        createRole = row.getBoolean(5);
        createDb = row.getBoolean(6);
      }
    }

    if (superuser) {
      blockers.add(
          "ARAK's account " + account + " is a superuser. PostgreSQL records a superuser's grants"
              + " as made by each object's owner, so ARAK could not tell its grants from the DBA's."
              + " Use an account with CREATEROLE that is not a superuser.");
    }
    if (!createRole) {
      blockers.add("ARAK's account " + account + " needs CREATEROLE to create the policy's role.");
    }
    if (!database.equals(desired.database())) {
      blockers.add(
          "The account is connected to database " + database + ", not " + desired.database() + ".");
    }

    // Grant options held by the account itself, not through a role (see the class comment).
    if (!holdsDirectly(
        connection,
        "SELECT d.datdba = r.oid OR EXISTS (SELECT 1 FROM aclexplode(d.datacl) a"
            + " WHERE a.grantee = r.oid AND a.privilege_type = 'CONNECT' AND a.is_grantable)"
            + " FROM pg_database d, pg_roles r"
            + " WHERE d.datname = current_database() AND r.rolname = current_user",
        null,
        null)) {
      blockers.add(
          "ARAK's account needs CONNECT WITH GRANT OPTION on database " + database
              + ", granted to the account itself.");
    }
    for (String schema : desired.schemas()) {
      Boolean ok =
          holdsDirectlyOrMissing(
              connection,
              "SELECT n.nspowner = r.oid OR EXISTS (SELECT 1 FROM aclexplode(n.nspacl) a"
                  + " WHERE a.grantee = r.oid AND a.privilege_type = 'USAGE' AND a.is_grantable)"
                  + " FROM pg_namespace n, pg_roles r"
                  + " WHERE n.nspname = ? AND r.rolname = current_user",
              schema,
              null);
      if (ok == null) {
        blockers.add("Schema " + schema + " does not exist on the source.");
      } else if (!ok) {
        blockers.add(
            "ARAK's account needs USAGE WITH GRANT OPTION on schema " + schema
                + ", granted to the account itself.");
      }
    }
    for (Table table : desired.tables()) {
      Boolean ok =
          holdsDirectlyOrMissing(
              connection,
              "SELECT c.relkind IN ('r','p','v','m','f') AND (c.relowner = r.oid OR EXISTS ("
                  + "SELECT 1 FROM aclexplode(c.relacl) a WHERE a.grantee = r.oid"
                  + " AND a.privilege_type = 'SELECT' AND a.is_grantable))"
                  + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace, pg_roles r"
                  + " WHERE n.nspname = ? AND c.relname = ? AND r.rolname = current_user",
              table.schema(),
              table.name());
      if (ok == null) {
        blockers.add("Table " + table + " does not exist on the source.");
      } else if (!ok && desired.level() == AccessLevel.READ) {
        blockers.add(
            "ARAK's account needs SELECT WITH GRANT OPTION on " + table
                + ", granted to the account itself (or must own it).");
      }
    }

    logins(connection, desired, account, version, blockers, warnings);

    Actual actual = actual(connection, desired.role());
    if (actual.roleExists() && !isArakComment(actual.comment())) {
      blockers.add(
          "A role named " + desired.role() + " exists and does not carry ARAK's comment."
              + " ARAK will not take over a role it did not create.");
    }
    for (String attribute : List.of("SUPERUSER", "REPLICATION", "BYPASSRLS")) {
      if (actual.attributes().contains(attribute)) {
        blockers.add(
            "Role " + desired.role() + " has been given " + attribute + " by hand. Only a"
                + " superuser can take that away again; ask the DBA to, then plan again.");
      }
    }
    if (actual.attributes().contains("CREATEDB") && !createDb) {
      blockers.add(
          "Role " + desired.role() + " has been given CREATEDB by hand. Only an account that"
              + " has CREATEDB itself can take that away; ask the DBA to, then plan again.");
    }
    if (actual.roleExists()) {
      foreign(connection, desired.role(), account, warnings);
    }

    if (publicCanConnect(connection)) {
      warnings.add(
          "PUBLIC holds CONNECT on database " + database + ": every login can connect, whether or"
              + " not the policy lets them. ARAK does not revoke PUBLIC's privileges.");
    }
    otherReaders(connection, desired, otherReaders);

    return new Inspection(version, account, actual, blockers, warnings, otherReaders);
  }

  /** Each member must be a login that exists and that the membership will reach. */
  private void logins(
      Connection connection,
      Desired desired,
      String account,
      int version,
      List<String> blockers,
      List<String> warnings)
      throws SQLException {
    if (desired.members().isEmpty()) {
      return;
    }
    Map<String, boolean[]> found = new LinkedHashMap<>();
    try (PreparedStatement statement =
        prepare(
            connection,
            "SELECT rolname, rolcanlogin, rolsuper, rolinherit FROM pg_roles"
                + " WHERE rolname = ANY (?)")) {
      Array names = connection.createArrayOf("text", desired.members().toArray());
      statement.setArray(1, names);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.put(
              rows.getString(1),
              new boolean[] {rows.getBoolean(2), rows.getBoolean(3), rows.getBoolean(4)});
        }
      }
    }
    for (String member : desired.members()) {
      boolean[] flags = found.get(member);
      if (member.equals(account)) {
        blockers.add("ARAK's own account " + member + " cannot be a member of a policy role.");
      } else if (PostgresGrantCompiler.isManagedName(member)) {
        blockers.add(member + " is a policy role, not a login.");
      } else if (flags == null) {
        blockers.add("Login " + member + " does not exist on the source.");
      } else if (!flags[0]) {
        blockers.add(member + " is a role that cannot log in; map a login instead.");
      } else {
        if (flags[1]) {
          warnings.add(member + " is a superuser and can read everything regardless.");
        }
        if (!flags[2] && version < PostgresGrantCompiler.MEMBERSHIP_OPTIONS_FROM) {
          warnings.add(
              member + " is NOINHERIT: on this release the policy's privileges reach it only"
                  + " after SET ROLE.");
        }
      }
    }
  }

  /** Grants on or to the role that somebody other than ARAK made. Reported, never revoked. */
  private void foreign(Connection connection, String role, String account, List<String> warnings)
      throws SQLException {
    String sql =
        "WITH me AS (SELECT oid FROM pg_roles WHERE rolname = current_user),"
            + " it AS (SELECT oid FROM pg_roles WHERE rolname = ?)"
            + " SELECT 'privilege', a.privilege_type || ' on database ' || d.datname,"
            + "        pg_get_userbyid(a.grantor)"
            + "   FROM pg_database d, aclexplode(d.datacl) a, me, it"
            + "  WHERE d.datname = current_database() AND a.grantee = it.oid AND a.grantor <> me.oid"
            + " UNION ALL"
            + " SELECT 'privilege', a.privilege_type || ' on schema ' || n.nspname,"
            + "        pg_get_userbyid(a.grantor)"
            + "   FROM pg_namespace n, aclexplode(n.nspacl) a, me, it"
            + "  WHERE a.grantee = it.oid AND a.grantor <> me.oid"
            + " UNION ALL"
            + " SELECT 'privilege', a.privilege_type || ' on ' || n.nspname || '.' || c.relname,"
            + "        pg_get_userbyid(a.grantor)"
            + "   FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace,"
            + "        aclexplode(c.relacl) a, me, it"
            + "  WHERE a.grantee = it.oid AND a.grantor <> me.oid"
            + " UNION ALL"
            + " SELECT 'privilege', a.privilege_type || ' on column ' || n.nspname || '.'"
            + "        || c.relname || '.' || att.attname, pg_get_userbyid(a.grantor)"
            + "   FROM pg_attribute att JOIN pg_class c ON c.oid = att.attrelid"
            + "   JOIN pg_namespace n ON n.oid = c.relnamespace,"
            + "        aclexplode(att.attacl) a, it"
            + "  WHERE a.grantee = it.oid"
            + " UNION ALL"
            + " SELECT 'member', pg_get_userbyid(am.member), pg_get_userbyid(am.grantor)"
            + "   FROM pg_auth_members am, me, it"
            + "  WHERE am.roleid = it.oid AND am.member <> me.oid AND am.grantor <> me.oid"
            + " UNION ALL"
            + " SELECT 'memberof', pg_get_userbyid(am.roleid), pg_get_userbyid(am.grantor)"
            + "   FROM pg_auth_members am, it WHERE am.member = it.oid"
            + " ORDER BY 1, 2";
    try (PreparedStatement statement = prepare(connection, sql)) {
      statement.setString(1, role);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          String kind = rows.getString(1);
          String what = rows.getString(2);
          String by = rows.getString(3);
          switch (kind) {
            case "member" ->
                warnings.add(
                    what + " is a member of " + role + " through a grant by " + by
                        + "; ARAK does not revoke it.");
            case "memberof" ->
                warnings.add(
                    role + " has been made a member of " + what + " by " + by
                        + "; ARAK does not change it.");
            default ->
                warnings.add(
                    role + " holds " + what + " granted by " + by + "; ARAK does not revoke it.");
          }
        }
      }
    }
  }

  private boolean publicCanConnect(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        prepare(
            connection,
            "SELECT EXISTS (SELECT 1 FROM pg_database d,"
                + " aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) a"
                + " WHERE d.datname = current_database() AND a.grantee = 0"
                + " AND a.privilege_type = 'CONNECT')")) {
      try (ResultSet row = statement.executeQuery()) {
        return row.next() && row.getBoolean(1);
      }
    }
  }

  /** Who other than the policy's role can SELECT a table in scope, by a direct grant. */
  private void otherReaders(Connection connection, Desired desired, List<String> out)
      throws SQLException {
    if (desired.tables().isEmpty()) {
      return;
    }
    String sql =
        "SELECT DISTINCT n.nspname || '.' || c.relname,"
            + " CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END,"
            + " a.grantee = c.relowner"
            + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace,"
            + " aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) a"
            + " WHERE n.nspname = ? AND c.relname = ? AND a.privilege_type = 'SELECT'"
            + " AND (a.grantee = 0 OR pg_get_userbyid(a.grantee) <> ?)"
            + " ORDER BY 2";
    try (PreparedStatement statement = prepare(connection, sql)) {
      for (Table table : desired.tables()) {
        statement.setString(1, table.schema());
        statement.setString(2, table.name());
        statement.setString(3, desired.role());
        try (ResultSet rows = statement.executeQuery()) {
          while (rows.next() && out.size() < 200) {
            out.add(
                rows.getString(1) + ": " + rows.getString(2)
                    + (rows.getBoolean(3) ? " (owner)" : ""));
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------- actual

  /** What ARAK's account has granted to and for the role. */
  private Actual actual(Connection connection, String role) throws SQLException {
    boolean exists;
    SortedSet<String> attributes = new TreeSet<>();
    String comment;
    try (PreparedStatement statement =
        prepare(
            connection,
            "SELECT r.rolcanlogin, r.rolsuper, r.rolcreatedb, r.rolcreaterole, r.rolinherit,"
                + " r.rolreplication, r.rolbypassrls, shobj_description(r.oid, 'pg_authid')"
                + " FROM pg_roles r WHERE r.rolname = ?")) {
      statement.setString(1, role);
      try (ResultSet row = statement.executeQuery()) {
        exists = row.next();
        comment = exists ? row.getString(8) : null;
        if (exists) {
          String[] names = {
            "LOGIN", "SUPERUSER", "CREATEDB", "CREATEROLE", "INHERIT", "REPLICATION", "BYPASSRLS"
          };
          for (int i = 0; i < names.length; i++) {
            if (row.getBoolean(i + 1)) {
              attributes.add(names[i]);
            }
          }
        }
      }
    }
    if (!exists) {
      return Actual.NONE;
    }

    String mine =
        "WITH me AS (SELECT oid FROM pg_roles WHERE rolname = current_user),"
            + " it AS (SELECT oid FROM pg_roles WHERE rolname = ?) ";

    boolean connect;
    try (PreparedStatement statement =
        prepare(
            connection,
            mine
                + "SELECT EXISTS (SELECT 1 FROM pg_database d, aclexplode(d.datacl) a, me, it"
                + " WHERE d.datname = current_database() AND a.grantee = it.oid"
                + " AND a.grantor = me.oid AND a.privilege_type = 'CONNECT')")) {
      statement.setString(1, role);
      try (ResultSet row = statement.executeQuery()) {
        connect = row.next() && row.getBoolean(1);
      }
    }

    SortedSet<String> usage = new TreeSet<>();
    try (PreparedStatement statement =
        prepare(
            connection,
            mine
                + "SELECT n.nspname FROM pg_namespace n, aclexplode(n.nspacl) a, me, it"
                + " WHERE a.grantee = it.oid AND a.grantor = me.oid"
                + " AND a.privilege_type = 'USAGE'")) {
      statement.setString(1, role);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          usage.add(rows.getString(1));
        }
      }
    }

    SortedSet<Table> select = new TreeSet<>();
    try (PreparedStatement statement =
        prepare(
            connection,
            mine
                + "SELECT n.nspname, c.relname FROM pg_class c"
                + " JOIN pg_namespace n ON n.oid = c.relnamespace, aclexplode(c.relacl) a, me, it"
                + " WHERE a.grantee = it.oid AND a.grantor = me.oid"
                + " AND a.privilege_type = 'SELECT'")) {
      statement.setString(1, role);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          select.add(new Table(rows.getString(1), rows.getString(2)));
        }
      }
    }

    // The account that created the role holds ADMIN on it from 16 on; that is
    // how it may grant it, not a member the policy put there.
    SortedSet<String> members = new TreeSet<>();
    try (PreparedStatement statement =
        prepare(
            connection,
            mine
                + "SELECT DISTINCT pg_get_userbyid(am.member) FROM pg_auth_members am, me, it"
                + " WHERE am.roleid = it.oid AND am.grantor = me.oid AND am.member <> me.oid")) {
      statement.setString(1, role);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          members.add(rows.getString(1));
        }
      }
    }
    return new Actual(true, attributes, comment, connect, usage, select, members);
  }

  // ---------------------------------------------------------------- writes

  private List<Change> teardownChanges(
      Connection connection, String role, String database, List<String> warnings)
      throws SQLException {
    Actual actual = actual(connection, role);
    if (!actual.roleExists()) {
      return List.of();
    }
    String account;
    try (PreparedStatement statement = prepare(connection, "SELECT current_user")) {
      try (ResultSet row = statement.executeQuery()) {
        row.next();
        account = row.getString(1);
      }
    }
    List<String> foreign = new ArrayList<>();
    foreign(connection, role, account, foreign);
    List<Change> changes = PostgresGrantCompiler.teardown(role, database, actual);
    // DROP ROLE takes memberships with it, in both directions; only a privilege
    // somebody else granted to the role makes PostgreSQL refuse the drop.
    boolean blocksDrop = foreign.stream().anyMatch(line -> line.contains(" holds "));
    if (blocksDrop) {
      warnings.addAll(foreign);
      warnings.add(
          role + " was not dropped: it still holds grants made by somebody else. ARAK took away"
              + " its own grants and members; remove the rest by hand and roll back again.");
      return changes.stream().filter(change -> change.step() != Step.DROP_ROLE).toList();
    }
    warnings.addAll(foreign);
    return changes;
  }

  private int execute(Connection connection, List<Change> changes) throws SQLException {
    int count = 0;
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(statementTimeoutSeconds);
      for (Change change : changes) {
        statement.execute(change.sql());
        count++;
      }
    }
    return count;
  }

  /**
   * Every member can now do what the level promises. Asked of PostgreSQL's own
   * privilege functions, which follow memberships the way a query would.
   */
  private void verifyMembers(Connection connection, Desired desired, int version)
      throws SQLException {
    if (desired.members().isEmpty()) {
      return;
    }
    List<String> failures = new ArrayList<>();
    try (PreparedStatement inherits =
            prepare(connection, "SELECT rolinherit FROM pg_roles WHERE rolname = ?");
        PreparedStatement db =
            prepare(connection, "SELECT has_database_privilege(?, current_database(), 'CONNECT')");
        PreparedStatement schema =
            prepare(connection, "SELECT has_schema_privilege(?, ?, 'USAGE')");
        PreparedStatement table =
            prepare(connection, "SELECT has_table_privilege(?, ?, 'SELECT')")) {
      for (String member : desired.members()) {
        if (version < PostgresGrantCompiler.MEMBERSHIP_OPTIONS_FROM && !flag(inherits, member)) {
          continue; // warned about at inspection: reaches the privileges only through SET ROLE
        }
        if (!flag(db, member)) {
          failures.add(member + " cannot connect");
        }
        for (String name : desired.schemas()) {
          if (!flag(schema, member, name)) {
            failures.add(member + " cannot use schema " + name);
          }
        }
        if (desired.level() == AccessLevel.READ) {
          for (Table t : desired.tables()) {
            if (!flag(table, member, t.qualified())) {
              failures.add(member + " cannot read " + t);
            }
          }
        }
      }
    }
    if (!failures.isEmpty()) {
      throw new RefusedException(
          "After applying, PostgreSQL does not give the members what the policy says; nothing"
              + " was applied. " + String.join("; ", failures) + ".");
    }
  }

  // ---------------------------------------------------------------- helpers

  private void limits(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET LOCAL lock_timeout = '" + statementTimeoutSeconds + "s'");
      statement.execute("SET LOCAL statement_timeout = '" + statementTimeoutSeconds + "s'");
    }
  }

  private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    statement.setQueryTimeout(statementTimeoutSeconds);
    return statement;
  }

  private boolean holdsDirectly(Connection connection, String sql, String a, String b)
      throws SQLException {
    Boolean ok = holdsDirectlyOrMissing(connection, sql, a, b);
    return ok != null && ok;
  }

  /** True or false for the object's single row; null when there is no such object. */
  private Boolean holdsDirectlyOrMissing(Connection connection, String sql, String a, String b)
      throws SQLException {
    try (PreparedStatement statement = prepare(connection, sql)) {
      int i = 1;
      if (a != null) {
        statement.setString(i++, a);
      }
      if (b != null) {
        statement.setString(i, b);
      }
      try (ResultSet row = statement.executeQuery()) {
        return row.next() ? row.getBoolean(1) : null;
      }
    }
  }

  private static boolean flag(PreparedStatement statement, String... args) throws SQLException {
    for (int i = 0; i < args.length; i++) {
      statement.setString(i + 1, args[i]);
    }
    try (ResultSet row = statement.executeQuery()) {
      return row.next() && row.getBoolean(1);
    }
  }

  private static boolean isArakComment(String comment) {
    return comment != null && comment.startsWith(PostgresGrantCompiler.COMMENT_PREFIX);
  }

  private static void requireManaged(String role) {
    if (!PostgresGrantCompiler.isManagedName(role)) {
      throw new IllegalArgumentException("ARAK manages only its own policy roles, not " + role);
    }
  }

  /**
   * What the review is of: the source as read and the policy as asked. The
   * blockers and warnings follow from these, so they are not part of it.
   */
  static String signature(Inspection inspection, Desired desired) {
    StringBuilder out = new StringBuilder();
    out.append("version=").append(inspection.serverVersionNum()).append('\n');
    out.append(inspection.actual().canonical());
    out.append("--\n");
    out.append("role=").append(desired.role()).append('\n');
    out.append("database=").append(desired.database()).append('\n');
    out.append("level=").append(desired.level()).append('\n');
    out.append("comment=").append(desired.comment()).append('\n');
    for (Table table : desired.tables()) {
      out.append("table=").append(table).append('\n');
    }
    for (String member : desired.members()) {
      out.append("member=").append(member).append('\n');
    }
    return sha256(out.toString());
  }

  private static String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
    }
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
      // The connection is about to be closed; the real exception matters more.
    }
  }
}
