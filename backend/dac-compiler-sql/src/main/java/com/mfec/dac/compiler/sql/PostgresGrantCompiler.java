package com.mfec.dac.compiler.sql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Enforcement mode 5.1.1 for a subscription policy on PostgreSQL: the policy
 * becomes a role on the source, its tables become privileges of the role, and
 * the people it lets in become members of it (FR-6.2).
 *
 * <p>Pure. It is handed what the policy says should be on the source and what
 * the source holds now, and returns the statements that turn one into the
 * other. Reading the source and running the statements belong to the applier
 * in {@code dac-connector-source}; deciding who is a member belongs to the
 * policy engine. Neither is done here, which is what lets the whole of the
 * grant logic be tested without a database.
 *
 * <h2>What ARAK puts on a source, and nothing more</h2>
 *
 * <ul>
 *   <li>One {@code NOLOGIN} role per policy and source, named {@code
 *       arak_sub_<policy>_<source>} and created without any of the attributes
 *       that could reach further than its grants.
 *   <li>{@code CONNECT} on the database and {@code USAGE} on each schema in
 *       scope: that is the Browse level.
 *   <li>{@code SELECT} on each table or view in scope, on top of Browse: that is
 *       the Read level. Never a write privilege, never ownership, never a
 *       predefined {@code pg_*} role, never a column list.
 *   <li>Membership of the role for each mapped login, with {@code INHERIT TRUE}
 *       so the privileges apply without a {@code SET ROLE}, and on 16 and later
 *       {@code SET FALSE} so that a member cannot become the role either.
 * </ul>
 *
 * <p>Every statement is a {@code GRANT} or {@code REVOKE} issued by ARAK's own
 * account, so PostgreSQL records that account as the grantor, and a {@code
 * REVOKE} from it takes back only what it gave. A privilege the DBA granted to
 * the same login stays where it is: removing it is not ARAK's decision.
 */
public final class PostgresGrantCompiler {

  /** Every role ARAK creates starts with this, and ARAK touches no other. */
  public static final String ROLE_PREFIX = "arak_sub_";

  /** The first words of the comment on every role ARAK creates. */
  public static final String COMMENT_PREFIX = "Managed by ARAK.";

  /** {@code server_version_num} from which a membership grant takes options. */
  public static final int MEMBERSHIP_OPTIONS_FROM = 160000;

  private static final Pattern ROLE_NAME = Pattern.compile("arak_sub_[0-9a-f]{8}_[0-9a-f]{8}");

  private static final PostgresDialect SQL = new PostgresDialect();

  /**
   * Everything the role is not allowed to be. {@code NOINHERIT} is there for
   * releases before 16, where it stops the chain of inherited privileges at
   * this role: a DBA who makes the role a member of something else does not
   * thereby hand that something to every member of the policy.
   */
  private static final String ATTRIBUTES =
      " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;";

  private PostgresGrantCompiler() {}

  /** How far a subscription policy reaches into the tables it binds. */
  public enum AccessLevel {
    /**
     * Connect and see the schema. A {@code SELECT} is refused with "permission
     * denied". Note that the catalogue ({@code pg_class}, {@code pg_attribute})
     * shows every table and column name to anybody who can connect: that is
     * PostgreSQL, not a choice made here.
     */
    BROWSE,
    /** Browse, and read every row and column of the tables in scope. */
    READ
  }

  /** A table or view, by the names the source knows it by. */
  public record Table(String schema, String name) implements Comparable<Table> {

    private static final Comparator<Table> ORDER =
        Comparator.comparing(Table::schema).thenComparing(Table::name);

    public Table {
      if (schema == null || schema.isBlank() || name == null || name.isBlank()) {
        throw new IllegalArgumentException("a table needs a schema and a name");
      }
    }

    public String qualified() {
      return SQL.quote(schema) + "." + SQL.quote(name);
    }

    @Override
    public int compareTo(Table other) {
      return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
      return schema + "." + name;
    }
  }

  /**
   * What the policy says should be on the source.
   *
   * @param members the logins that should hold the role. Who they are is the
   *     engine's answer, already narrowed to the people who pass every table
   *     in scope; this class takes the list as given.
   * @param comment written on the role so that a DBA reading {@code \du+} can
   *     tell where it came from and that hand edits will be reported as drift
   */
  public record Desired(
      String role,
      String database,
      AccessLevel level,
      SortedSet<Table> tables,
      SortedSet<String> members,
      String comment) {

    public Desired {
      requireManaged(role);
      if (database == null || database.isBlank()) {
        throw new IllegalArgumentException("no database to grant CONNECT on");
      }
      Objects.requireNonNull(level, "level");
      tables = new TreeSet<>(tables == null ? Set.of() : tables);
      members = new TreeSet<>(members == null ? Set.of() : members);
      comment = comment == null || comment.isBlank() ? COMMENT_PREFIX : comment;
      if (!comment.startsWith(COMMENT_PREFIX)) {
        throw new IllegalArgumentException("the role comment must start with " + COMMENT_PREFIX);
      }
    }

    /** Every schema a table in scope lives in: those get {@code USAGE}. */
    public SortedSet<String> schemas() {
      SortedSet<String> schemas = new TreeSet<>();
      for (Table table : tables) {
        schemas.add(table.schema());
      }
      return schemas;
    }

    /** The tables that get {@code SELECT}: all of them at Read, none at Browse. */
    public SortedSet<Table> selectable() {
      return level == AccessLevel.READ ? tables : new TreeSet<>();
    }

    /** What the source holds once this has been applied in full. */
    public Actual asApplied() {
      return new Actual(true, null, comment, true, schemas(), selectable(), members);
    }
  }

  /**
   * What the source holds for the role now, counting only what ARAK's own
   * account granted.
   *
   * @param roleExists the role is in {@code pg_roles}
   * @param attributes what the role has that it was not created with, such
   *     as {@code LOGIN} or {@code INHERIT}: somebody altered it by hand. Empty
   *     for a role as ARAK left it.
   * @param comment the role's comment, or null
   * @param connect {@code CONNECT} on the database, granted by ARAK
   * @param usage schemas with {@code USAGE}, granted by ARAK
   * @param select tables with {@code SELECT}, granted by ARAK
   * @param members logins holding the role, granted by ARAK
   */
  public record Actual(
      boolean roleExists,
      SortedSet<String> attributes,
      String comment,
      boolean connect,
      SortedSet<String> usage,
      SortedSet<Table> select,
      SortedSet<String> members) {

    public static final Actual NONE = new Actual(false, null, null, false, null, null, null);

    public Actual {
      attributes = new TreeSet<>(attributes == null ? Set.of() : attributes);
      usage = new TreeSet<>(usage == null ? Set.of() : usage);
      select = new TreeSet<>(select == null ? Set.of() : select);
      members = new TreeSet<>(members == null ? Set.of() : members);
    }

    /** One line per fact, sorted: the input to a signature and to drift detection. */
    public String canonical() {
      StringBuilder out = new StringBuilder();
      out.append("role=").append(roleExists).append('\n');
      for (String attribute : attributes) {
        out.append("attribute=").append(attribute).append('\n');
      }
      out.append("comment=").append(comment == null ? "" : comment).append('\n');
      out.append("connect=").append(connect).append('\n');
      for (String schema : usage) {
        out.append("usage=").append(schema).append('\n');
      }
      for (Table table : select) {
        out.append("select=").append(table).append('\n');
      }
      for (String member : members) {
        out.append("member=").append(member).append('\n');
      }
      return out.toString();
    }
  }

  /** What one statement does, for the review screen and for counting. */
  public enum Step {
    CREATE_ROLE,
    RESET_ROLE,
    COMMENT_ROLE,
    REVOKE_MEMBER,
    REVOKE_SELECT,
    REVOKE_USAGE,
    REVOKE_CONNECT,
    GRANT_CONNECT,
    GRANT_USAGE,
    GRANT_SELECT,
    GRANT_MEMBER,
    DROP_ROLE
  }

  /** One statement and what it is about. */
  public record Change(Step step, String target, String sql) {}

  /**
   * The statements to apply, and the statements that would take everything
   * off again afterwards.
   */
  public record Plan(List<Change> changes, List<Change> rollback) {

    public Plan {
      changes = List.copyOf(changes);
      rollback = List.copyOf(rollback);
    }

    public boolean isSatisfied() {
      return changes.isEmpty();
    }

    public String applyScript() {
      return script(changes);
    }

    public String rollbackScript() {
      return script(rollback);
    }

    public long count(Step step) {
      return changes.stream().filter(change -> change.step() == step).count();
    }
  }

  // ---------------------------------------------------------------- compile

  /**
   * The statements that make the source say what {@code desired} says.
   *
   * <p>Revocations come before grants. Inside one transaction the order does not
   * change what is committed, but a failure part-way through rolls back to a
   * state where nobody gained anything, and a person reading the script sees
   * what is taken away before what is given.
   *
   * @param serverVersionNum the source's {@code server_version_num}; from 16 a
   *     membership grant says {@code INHERIT TRUE, SET FALSE}
   */
  public static Plan compile(Desired desired, Actual actual, int serverVersionNum) {
    Objects.requireNonNull(desired, "desired");
    actual = actual == null ? Actual.NONE : actual;
    String role = SQL.quote(desired.role());
    List<Change> changes = new ArrayList<>();

    if (!actual.roleExists()) {
      changes.add(new Change(Step.CREATE_ROLE, desired.role(), createRole(role)));
    } else if (!actual.attributes().isEmpty()) {
      // Only what changed: PostgreSQL refuses a non-superuser who so much as
      // names SUPERUSER, REPLICATION or BYPASSRLS in an ALTER ROLE, even to
      // say NO. A role that has gained one of those is beyond ARAK's account,
      // and the applier refuses to plan for it at all.
      StringBuilder reset = new StringBuilder("ALTER ROLE ").append(role);
      for (String attribute : actual.attributes()) {
        reset.append(" NO").append(attribute);
      }
      changes.add(new Change(Step.RESET_ROLE, desired.role(), reset.append(';').toString()));
    }
    if (!actual.roleExists() || !desired.comment().equals(actual.comment())) {
      changes.add(
          new Change(
              Step.COMMENT_ROLE,
              desired.role(),
              "COMMENT ON ROLE " + role + " IS " + SQL.literal(desired.comment()) + ";"));
    }

    for (String member : minus(actual.members(), desired.members())) {
      changes.add(
          new Change(
              Step.REVOKE_MEMBER, member, "REVOKE " + role + " FROM " + SQL.quote(member) + ";"));
    }
    for (Table table : minus(actual.select(), desired.selectable())) {
      changes.add(
          new Change(
              Step.REVOKE_SELECT,
              table.toString(),
              "REVOKE SELECT ON TABLE " + table.qualified() + " FROM " + role + ";"));
    }
    for (String schema : minus(actual.usage(), desired.schemas())) {
      changes.add(
          new Change(
              Step.REVOKE_USAGE,
              schema,
              "REVOKE USAGE ON SCHEMA " + SQL.quote(schema) + " FROM " + role + ";"));
    }

    if (!actual.connect()) {
      changes.add(
          new Change(
              Step.GRANT_CONNECT,
              desired.database(),
              "GRANT CONNECT ON DATABASE " + SQL.quote(desired.database()) + " TO " + role + ";"));
    }
    for (String schema : minus(desired.schemas(), actual.usage())) {
      changes.add(
          new Change(
              Step.GRANT_USAGE,
              schema,
              "GRANT USAGE ON SCHEMA " + SQL.quote(schema) + " TO " + role + ";"));
    }
    for (Table table : minus(desired.selectable(), actual.select())) {
      changes.add(
          new Change(
              Step.GRANT_SELECT,
              table.toString(),
              "GRANT SELECT ON TABLE " + table.qualified() + " TO " + role + ";"));
    }
    for (String member : minus(desired.members(), actual.members())) {
      changes.add(
          new Change(Step.GRANT_MEMBER, member, grantMember(role, member, serverVersionNum)));
    }

    return new Plan(changes, teardown(desired.role(), desired.database(), desired.asApplied()));
  }

  /**
   * The statements that take everything ARAK granted for this role off the
   * source, and then the role itself.
   *
   * <p>Each grant is revoked by name rather than with {@code DROP OWNED BY}.
   * On 16 a {@code CREATEROLE} account does not hold the privileges of the
   * roles it creates, so {@code DROP OWNED BY} can fail there; and an explicit
   * list is what a reviewer can read and check against what was applied.
   */
  public static List<Change> teardown(String roleName, String database, Actual actual) {
    requireManaged(roleName);
    if (actual == null || !actual.roleExists()) {
      return List.of();
    }
    String role = SQL.quote(roleName);
    List<Change> out = new ArrayList<>();
    for (String member : actual.members()) {
      out.add(
          new Change(
              Step.REVOKE_MEMBER, member, "REVOKE " + role + " FROM " + SQL.quote(member) + ";"));
    }
    for (Table table : actual.select()) {
      out.add(
          new Change(
              Step.REVOKE_SELECT,
              table.toString(),
              "REVOKE SELECT ON TABLE " + table.qualified() + " FROM " + role + ";"));
    }
    for (String schema : actual.usage()) {
      out.add(
          new Change(
              Step.REVOKE_USAGE,
              schema,
              "REVOKE USAGE ON SCHEMA " + SQL.quote(schema) + " FROM " + role + ";"));
    }
    if (actual.connect()) {
      out.add(
          new Change(
              Step.REVOKE_CONNECT,
              database,
              "REVOKE CONNECT ON DATABASE " + SQL.quote(database) + " FROM " + role + ";"));
    }
    out.add(new Change(Step.DROP_ROLE, roleName, "DROP ROLE " + role + ";"));
    return List.copyOf(out);
  }

  // ----------------------------------------------------------------- naming

  /**
   * The role for one policy on one source: eight hex digits of each id.
   *
   * <p>Short enough to read in {@code \du} and well under the 63-byte limit,
   * and long enough that two policies colliding on one source is a one in four
   * billion event -- which is still checked, by the unique constraint on the
   * role register, rather than assumed.
   */
  public static String roleName(UUID policyId, UUID sourceId) {
    Objects.requireNonNull(policyId, "policyId");
    Objects.requireNonNull(sourceId, "sourceId");
    return ROLE_PREFIX + hex8(policyId) + "_" + hex8(sourceId);
  }

  /** True for a name ARAK could have generated; nothing else is ever touched. */
  public static boolean isManagedName(String role) {
    return role != null && ROLE_NAME.matcher(role).matches();
  }

  /** The comment written on the role. */
  public static String comment(UUID policyId, String policyName) {
    return COMMENT_PREFIX
        + " Subscription policy "
        + policyId
        + (policyName == null || policyName.isBlank() ? "" : " (" + policyName.strip() + ")")
        + ". Changes made here by hand are reported as drift and undone by the next apply.";
  }

  // ---------------------------------------------------------------- helpers

  private static String createRole(String role) {
    return "CREATE ROLE " + role + ATTRIBUTES;
  }

  private static String grantMember(String role, String member, int serverVersionNum) {
    String grant = "GRANT " + role + " TO " + SQL.quote(member);
    if (serverVersionNum >= MEMBERSHIP_OPTIONS_FROM) {
      return grant + " WITH INHERIT TRUE, SET FALSE;";
    }
    return grant + ";";
  }

  private static <T extends Comparable<T>> SortedSet<T> minus(Collection<T> from, Collection<T> take) {
    SortedSet<T> out = new TreeSet<>(from);
    out.removeAll(take);
    return out;
  }

  private static void requireManaged(String role) {
    if (!isManagedName(role)) {
      throw new IllegalArgumentException(
          "ARAK manages only roles named " + ROLE_PREFIX + "<8 hex>_<8 hex>, not " + role);
    }
  }

  private static String hex8(UUID id) {
    return id.toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
  }

  private static String script(List<Change> changes) {
    StringBuilder out = new StringBuilder();
    for (Change change : changes) {
      out.append(change.sql()).append('\n');
    }
    return out.toString();
  }
}
