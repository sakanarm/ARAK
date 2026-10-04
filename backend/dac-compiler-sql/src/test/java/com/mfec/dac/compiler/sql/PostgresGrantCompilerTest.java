package com.mfec.dac.compiler.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.compiler.sql.PostgresGrantCompiler.AccessLevel;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Actual;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Change;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Desired;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Plan;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Step;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Table;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a subscription policy compiles to on PostgreSQL, and what it refuses
 * to compile. The whole-script case is a golden file for the reason given in
 * {@link ViewCompilerTest}: a person approves this page on a live database.
 */
class PostgresGrantCompilerTest {

  private static final Path GOLDEN = Path.of("src", "test", "resources", "golden");

  private static final UUID POLICY = UUID.fromString("5e1f0c2a-0000-4000-8000-000000000001");
  private static final UUID SOURCE = UUID.fromString("0a7b3c9d-0000-4000-8000-000000000002");
  private static final String ROLE = "arak_sub_5e1f0c2a_0a7b3c9d";

  private static final Table ORDERS = new Table("sales", "orders");
  private static final Table CUSTOMER = new Table("sales", "customer");
  private static final Table LEDGER = new Table("finance", "ledger");

  private static Desired desired(AccessLevel level, List<Table> tables, List<String> members) {
    return new Desired(
        ROLE,
        "shop",
        level,
        new TreeSet<>(tables),
        new TreeSet<>(members),
        PostgresGrantCompiler.comment(POLICY, "Sales analysts"));
  }

  private static <T extends Comparable<T>> SortedSet<T> set(List<T> items) {
    return new TreeSet<>(items);
  }

  // ------------------------------------------------------------- naming

  @Test
  void theRoleIsNamedAfterThePolicyAndTheSource() {
    assertThat(PostgresGrantCompiler.roleName(POLICY, SOURCE)).isEqualTo(ROLE);
    assertThat(PostgresGrantCompiler.isManagedName(ROLE)).isTrue();
  }

  @Test
  void nothingButAGeneratedNameCountsAsManaged() {
    assertThat(PostgresGrantCompiler.isManagedName("arak_reader")).isFalse();
    assertThat(PostgresGrantCompiler.isManagedName("arak_sub_")).isFalse();
    assertThat(PostgresGrantCompiler.isManagedName("arak_sub_5E1F0C2A_0A7B3C9D")).isFalse();
    assertThat(PostgresGrantCompiler.isManagedName("arak_sub_5e1f0c2a_0a7b3c9d; DROP")).isFalse();
    assertThat(PostgresGrantCompiler.isManagedName("postgres")).isFalse();
    assertThat(PostgresGrantCompiler.isManagedName(null)).isFalse();
  }

  @Test
  void aRoleThatArakDidNotNameIsRefused() {
    assertThatThrownBy(
            () ->
                new Desired(
                    "pg_read_all_data", "shop", AccessLevel.READ, set(List.of(ORDERS)), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("arak_sub_");
    assertThatThrownBy(() -> PostgresGrantCompiler.teardown("analyst", "shop", Actual.NONE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theCommentMustSayArakManagesTheRole() {
    assertThatThrownBy(
            () -> new Desired(ROLE, "shop", AccessLevel.READ, set(List.of(ORDERS)), null, "mine"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(PostgresGrantCompiler.comment(POLICY, "Sales analysts"))
        .startsWith("Managed by ARAK. Subscription policy " + POLICY + " (Sales analysts).");
  }

  // ------------------------------------------------------------ compile

  @Test
  void compilesTheWholeScriptFromNothing() throws IOException {
    Plan plan =
        PostgresGrantCompiler.compile(
            desired(AccessLevel.READ, List.of(ORDERS, CUSTOMER, LEDGER), List.of("bob", "alice")),
            Actual.NONE,
            160004);
    String actual = plan.applyScript() + "\n-- rollback\n\n" + plan.rollbackScript();
    Path file = GOLDEN.resolve("native-grant-postgres.sql");
    if (Boolean.getBoolean("golden.write")) {
      Files.createDirectories(GOLDEN);
      Files.writeString(file, actual, StandardCharsets.UTF_8);
    }
    assertThat(Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"))
        .isEqualTo(actual);
  }

  @Test
  void theRoleCanDoNothingButHoldGrants() {
    Plan plan =
        PostgresGrantCompiler.compile(
            desired(AccessLevel.READ, List.of(ORDERS), List.of()), Actual.NONE, 160004);
    assertThat(plan.changes().get(0).sql())
        .isEqualTo(
            "CREATE ROLE \"" + ROLE + "\" NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE"
                + " NOINHERIT NOREPLICATION NOBYPASSRLS;");
  }

  @Test
  void browseGrantsConnectAndUsageButNoSelect() {
    Plan plan =
        PostgresGrantCompiler.compile(
            desired(AccessLevel.BROWSE, List.of(ORDERS, LEDGER), List.of("alice")),
            Actual.NONE,
            160004);
    assertThat(plan.count(Step.GRANT_CONNECT)).isEqualTo(1);
    assertThat(plan.count(Step.GRANT_USAGE)).isEqualTo(2);
    assertThat(plan.count(Step.GRANT_SELECT)).isZero();
    assertThat(plan.applyScript()).doesNotContain("SELECT");
  }

  @Test
  void readGrantsSelectOnEveryTableAndNothingElse() {
    Plan plan =
        PostgresGrantCompiler.compile(
            desired(AccessLevel.READ, List.of(ORDERS, CUSTOMER), List.of("alice")),
            Actual.NONE,
            160004);
    assertThat(plan.count(Step.GRANT_SELECT)).isEqualTo(2);
    assertThat(plan.count(Step.GRANT_USAGE)).isEqualTo(1);
    String script = plan.applyScript();
    for (String forbidden :
        List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE", "TEMP", "ALL PRIVILEGES", "OWNER",
            "pg_", "BYPASSRLS;", "SUPERUSER;", "WITH GRANT OPTION", "WITH ADMIN")) {
      assertThat(script).as(forbidden).doesNotContain(" " + forbidden);
    }
  }

  @Test
  void membershipCannotBeUsedToBecomeTheRoleOnSixteen() {
    Plan on16 =
        PostgresGrantCompiler.compile(
            desired(AccessLevel.READ, List.of(ORDERS), List.of("alice")), Actual.NONE, 160000);
    Plan on15 =
        PostgresGrantCompiler.compile(
            desired(AccessLevel.READ, List.of(ORDERS), List.of("alice")), Actual.NONE, 150008);
    assertThat(on16.applyScript())
        .contains("GRANT \"" + ROLE + "\" TO \"alice\" WITH INHERIT TRUE, SET FALSE;");
    assertThat(on15.applyScript()).contains("GRANT \"" + ROLE + "\" TO \"alice\";");
  }

  @Test
  void whatIsAlreadyThereIsNotGrantedAgain() {
    Desired want = desired(AccessLevel.READ, List.of(ORDERS, CUSTOMER), List.of("alice", "bob"));
    Plan plan = PostgresGrantCompiler.compile(want, want.asApplied(), 160004);
    assertThat(plan.isSatisfied()).isTrue();
    assertThat(plan.applyScript()).isEmpty();
    assertThat(plan.rollback()).isNotEmpty();
  }

  @Test
  void onlyTheDifferenceIsApplied() {
    Actual now =
        new Actual(
            true,
            null,
            PostgresGrantCompiler.comment(POLICY, "Sales analysts"),
            true,
            set(List.of("sales", "finance")),
            set(List.of(ORDERS, LEDGER)),
            set(List.of("alice", "carol")));
    Plan plan =
        PostgresGrantCompiler.compile(
            desired(AccessLevel.READ, List.of(ORDERS, CUSTOMER), List.of("alice", "bob")),
            now,
            160004);
    assertThat(plan.changes())
        .extracting(Change::step, Change::target)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(Step.REVOKE_MEMBER, "carol"),
            org.assertj.core.groups.Tuple.tuple(Step.REVOKE_SELECT, "finance.ledger"),
            org.assertj.core.groups.Tuple.tuple(Step.REVOKE_USAGE, "finance"),
            org.assertj.core.groups.Tuple.tuple(Step.GRANT_SELECT, "sales.customer"),
            org.assertj.core.groups.Tuple.tuple(Step.GRANT_MEMBER, "bob"));
  }

  @Test
  void steppingDownFromReadToBrowseRevokesSelectButKeepsUsage() {
    Desired read = desired(AccessLevel.READ, List.of(ORDERS), List.of("alice"));
    Plan plan =
        PostgresGrantCompiler.compile(
            desired(AccessLevel.BROWSE, List.of(ORDERS), List.of("alice")),
            read.asApplied(),
            160004);
    assertThat(plan.changes())
        .extracting(Change::step)
        .containsExactly(Step.REVOKE_SELECT);
  }

  @Test
  void aCommentChangedByHandIsWrittenBack() {
    Desired want = desired(AccessLevel.READ, List.of(ORDERS), List.of("alice"));
    Actual edited =
        new Actual(
            true, null, "a DBA was here", true, want.schemas(), want.selectable(), want.members());
    assertThat(PostgresGrantCompiler.compile(want, edited, 160004).changes())
        .extracting(Change::step)
        .containsExactly(Step.COMMENT_ROLE);
  }

  @Test
  void aRoleAlteredByHandIsPutBack() {
    Desired want = desired(AccessLevel.READ, List.of(ORDERS), List.of("alice"));
    Actual altered =
        new Actual(
            true,
            set(List.of("LOGIN", "INHERIT")),
            want.comment(),
            true,
            want.schemas(),
            want.selectable(),
            want.members());
    assertThat(PostgresGrantCompiler.compile(want, altered, 160004).changes())
        .extracting(Change::sql)
        .containsExactly("ALTER ROLE \"" + ROLE + "\" NOINHERIT NOLOGIN;");
  }

  @Test
  void namesThatNeedQuotingAreQuoted() {
    Plan plan =
        PostgresGrantCompiler.compile(
            new Desired(
                ROLE,
                "shop \"live\"",
                AccessLevel.READ,
                set(List.of(new Table("Sales Data", "Order\"Lines"))),
                set(List.of("o'brien")),
                PostgresGrantCompiler.comment(POLICY, "it's quoted")),
            Actual.NONE,
            160004);
    String script = plan.applyScript();
    assertThat(script).contains("GRANT CONNECT ON DATABASE \"shop \"\"live\"\"\" TO");
    assertThat(script).contains("GRANT SELECT ON TABLE \"Sales Data\".\"Order\"\"Lines\" TO");
    assertThat(script).contains("TO \"o'brien\" WITH INHERIT TRUE");
    assertThat(script).contains("(it''s quoted)");
  }

  // ----------------------------------------------------------- teardown

  @Test
  void teardownRevokesEachGrantByNameAndThenDropsTheRole() {
    Actual now =
        desired(AccessLevel.READ, List.of(ORDERS, LEDGER), List.of("alice")).asApplied();
    List<Change> out = PostgresGrantCompiler.teardown(ROLE, "shop", now);
    assertThat(out)
        .extracting(Change::step)
        .containsExactly(
            Step.REVOKE_MEMBER,
            Step.REVOKE_SELECT,
            Step.REVOKE_SELECT,
            Step.REVOKE_USAGE,
            Step.REVOKE_USAGE,
            Step.REVOKE_CONNECT,
            Step.DROP_ROLE);
    assertThat(out).extracting(Change::sql).noneMatch(sql -> sql.contains("DROP OWNED"));
    assertThat(out.get(out.size() - 1).sql()).isEqualTo("DROP ROLE \"" + ROLE + "\";");
  }

  @Test
  void thereIsNothingToTearDownWhenTheRoleIsNotThere() {
    assertThat(PostgresGrantCompiler.teardown(ROLE, "shop", Actual.NONE)).isEmpty();
  }

  @Test
  void theCanonicalFormDoesNotDependOnOrder() {
    Actual one =
        new Actual(
            true, null, "c", true, set(List.of("b", "a")), set(List.of(ORDERS, LEDGER)),
            set(List.of("bob", "alice")));
    Actual two =
        new Actual(
            true, null, "c", true, set(List.of("a", "b")), set(List.of(LEDGER, ORDERS)),
            set(List.of("alice", "bob")));
    assertThat(one.canonical()).isEqualTo(two.canonical());
  }
}
