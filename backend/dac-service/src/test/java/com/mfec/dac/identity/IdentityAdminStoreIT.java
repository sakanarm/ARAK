package com.mfec.dac.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.auth.LocalIdentityDao;
import com.mfec.dac.auth.PasswordHasher;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
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
 * Local accounts and app roles against a real PostgreSQL.
 *
 * <p>The interesting cases are the ones only a database can answer: the lock
 * that would leave nobody able to open the door again, the second account
 * whose name differs only in case and would break sign-in for both, and the
 * duplicate global grant that the V2 unique constraint cannot see because one
 * of its columns is null.
 */
@Testcontainers
class IdentityAdminStoreIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static Jdbi jdbi;
  private IdentityAdminStore store;

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
          handle.execute("TRUNCATE principal CASCADE");
          handle.execute("TRUNCATE audit_identity_change");
        });
    store = new IdentityAdminStore(jdbi, new LocalIdentityDao(jdbi));
    // The bootstrap administrator that V6 creates and every deployment has.
    UUID admin =
        store.createLocal(
            new IdentityAdminStore.NewLocalPrincipal(
                "admin",
                "Platform Administrator",
                null,
                "USER",
                "bootstrap password",
                List.of(new IdentityAdminStore.RoleRequest("PLATFORM_ADMIN", null))),
            "bootstrap",
            null);
    assertThat(admin).isNotNull();
  }

  private UUID create(String username, IdentityAdminStore.RoleRequest... roles) {
    return store.createLocal(
        new IdentityAdminStore.NewLocalPrincipal(
            username, username, username + "@example.com", "USER", "a passable password",
            List.of(roles)),
        "admin",
        "192.0.2.10");
  }

  private UUID adminId() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT id FROM principal WHERE username = 'admin'")
                .mapTo(UUID.class)
                .one());
  }

  @Nested
  @DisplayName("creating a local account")
  class Creating {

    @Test
    @DisplayName("it can sign in immediately, and must change the password when it does")
    void credentialIsUsable() {
      UUID id = create("analyst_a");

      LocalIdentityDao.LocalAccount account =
          new LocalIdentityDao(jdbi).findLocalAccount("analyst_a").orElseThrow();
      assertThat(account.id()).isEqualTo(id);
      assertThat(account.enabled()).isTrue();
      assertThat(account.hasCredential()).isTrue();
      assertThat(account.mustChange()).isTrue();
      assertThat(PasswordHasher.verify("a passable password".toCharArray(), account.passwordHash()))
          .isTrue();
    }

    @Test
    @DisplayName("a name differing only in case is the same name, because signing in says so")
    void caseInsensitiveNames() {
      create("analyst_a");

      // findLocalAccount matches on lower(username) and takes exactly one row:
      // a second Analyst_A would not shadow the first, it would break both.
      assertThatThrownBy(() -> create("Analyst_A"))
          .isInstanceOf(IdentityAdminStore.IdentityConflictException.class)
          .hasMessageContaining("already exists");
      assertThat(new LocalIdentityDao(jdbi).findLocalAccount("ANALYST_A")).isPresent();
    }

    @Test
    @DisplayName("the roles asked for arrive with it")
    void rolesInTheSameBreath() {
      UUID id = create("author", new IdentityAdminStore.RoleRequest("POLICY_AUTHOR", null));

      assertThat(new LocalIdentityDao(jdbi).loadUser(id).orElseThrow().appRoles())
          .containsExactly("POLICY_AUTHOR");
    }

    @Test
    @DisplayName("a refused role leaves no half-made account behind")
    void refusedRoleLeavesNothing() {
      assertThatThrownBy(
              () ->
                  store.createLocal(
                      new IdentityAdminStore.NewLocalPrincipal(
                          "analyst_b",
                          "Analyst B",
                          null,
                          "USER",
                          "a passable password",
                          List.of(new IdentityAdminStore.RoleRequest("DATA_OWNER", null))),
                      "admin",
                      null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class);

      assertThat(new LocalIdentityDao(jdbi).findLocalAccount("analyst_b")).isEmpty();
    }
  }

  @Nested
  @DisplayName("app roles")
  class Roles {

    @Test
    @DisplayName("granting twice changes nothing the second time")
    void idempotent() {
      UUID id = create("auditor");
      IdentityAdminStore.RoleRequest auditor =
          new IdentityAdminStore.RoleRequest("AUDITOR", null);

      assertThat(store.grant(id, auditor, "on call", "admin", "192.0.2.10")).isTrue();
      assertThat(store.grant(id, auditor, "on call", "admin", "192.0.2.10")).isFalse();

      // The V2 constraint cannot see this duplicate — its third column is null,
      // and a null is distinct from every other null. V10's partial index can.
      assertThat(store.grants()).filteredOn(g -> "AUDITOR".equals(g.appRole())).hasSize(1);
    }

    @Test
    @DisplayName("a data owner's scope is kept as given, and scoped grants stack")
    void scopedGrants() {
      UUID id = create("owner");
      assertThat(
              store.grant(
                  id,
                  new IdentityAdminStore.RoleRequest("DATA_OWNER", "prod.SalesDB"),
                  null,
                  "admin",
                  null))
          .isTrue();
      assertThat(
              store.grant(
                  id,
                  new IdentityAdminStore.RoleRequest("DATA_OWNER", "prod.HrDB"),
                  null,
                  "admin",
                  null))
          .isTrue();

      assertThat(new LocalIdentityDao(jdbi).loadUser(id).orElseThrow().scopes())
          .containsExactlyInAnyOrder("prod.SalesDB", "prod.HrDB");
    }

    @Test
    @DisplayName("revoking a role nobody holds is not an error, it is a no-op")
    void revokeMissing() {
      UUID id = create("analyst_a");
      assertThat(
              store.revoke(
                  id, new IdentityAdminStore.RoleRequest("AUDITOR", null), null, "admin", null))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("the last administrator")
  class LastAdministrator {

    @Test
    @DisplayName("cannot have the role taken away, because nobody could give it back")
    void cannotBeRevoked() {
      assertThatThrownBy(
              () ->
                  store.revoke(
                      adminId(),
                      new IdentityAdminStore.RoleRequest("PLATFORM_ADMIN", null),
                      "tidying up",
                      "admin",
                      null))
          .isInstanceOf(IdentityAdminStore.IdentityConflictException.class)
          .hasMessageContaining("last PLATFORM_ADMIN");

      assertThat(store.globalAdminCount()).isOne();
    }

    @Test
    @DisplayName("cannot be disabled either — the same lockout by another route")
    void cannotBeDisabled() {
      assertThatThrownBy(() -> store.setEnabled(adminId(), false, "left the company", "admin", null))
          .isInstanceOf(IdentityAdminStore.IdentityConflictException.class)
          .hasMessageContaining("no administrator");
    }

    @Test
    @DisplayName("stops being the last one as soon as there is a second, enabled one")
    void aSecondAdminUnlocksIt() {
      UUID second = create("admin_two", new IdentityAdminStore.RoleRequest("PLATFORM_ADMIN", null));
      assertThat(store.globalAdminCount()).isEqualTo(2);

      assertThat(
              store.revoke(
                  adminId(),
                  new IdentityAdminStore.RoleRequest("PLATFORM_ADMIN", null),
                  "handed over",
                  "admin",
                  null))
          .isTrue();
      assertThat(store.globalAdminCount()).isOne();

      // And the guard moves with the role: the survivor is now the last one.
      assertThatThrownBy(
              () ->
                  store.revoke(
                      second,
                      new IdentityAdminStore.RoleRequest("PLATFORM_ADMIN", null),
                      null,
                      "admin_two",
                      null))
          .isInstanceOf(IdentityAdminStore.IdentityConflictException.class);
    }

    @Test
    @DisplayName("a disabled administrator does not count as one")
    void disabledDoesNotCount() {
      UUID second = create("admin_two", new IdentityAdminStore.RoleRequest("PLATFORM_ADMIN", null));
      store.setEnabled(second, false, "on leave", "admin", null);

      assertThat(store.globalAdminCount()).isOne();
      assertThatThrownBy(
              () ->
                  store.revoke(
                      adminId(),
                      new IdentityAdminStore.RoleRequest("PLATFORM_ADMIN", null),
                      null,
                      "admin",
                      null))
          .isInstanceOf(IdentityAdminStore.IdentityConflictException.class);
    }
  }

  @Nested
  @DisplayName("principals from a directory we sync")
  class SyncedPrincipals {

    private UUID entraUser() {
      return jdbi.withHandle(
          handle ->
              handle
                  .createQuery(
                      """
                      INSERT INTO principal
                          (principal_type, username, display_name, source, enabled)
                      VALUES ('USER', 'entra_user', 'Entra User', 'entra', true)
                      RETURNING id
                      """)
                  .mapTo(UUID.class)
                  .one());
    }

    @Test
    @DisplayName("can be granted a role, because the role table is ours")
    void rolesAreOurs() {
      UUID id = entraUser();
      assertThat(
              store.grant(
                  id, new IdentityAdminStore.RoleRequest("AUDITOR", null), null, "admin", null))
          .isTrue();
    }

    @Test
    @DisplayName("cannot be edited here, because the next sync would undo it")
    void accountsAreNot() {
      UUID id = entraUser();
      assertThatThrownBy(() -> store.setEnabled(id, false, null, "admin", null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class)
          .hasMessageContaining("entra");
      assertThatThrownBy(() -> store.resetPassword(id, "a passable password", "admin", null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class);
    }
  }

  @Nested
  @DisplayName("attributes entered here")
  class LocalAttributes {

    private UUID entraUser() {
      return jdbi.withHandle(
          handle ->
              handle
                  .createQuery(
                      """
                      INSERT INTO principal
                          (principal_type, username, display_name, source, enabled)
                      VALUES ('USER', 'entra_user', 'Entra User', 'entra', true)
                      RETURNING id
                      """)
                  .mapTo(UUID.class)
                  .one());
    }

    /** Every row for a value, open or closed, so a reopen can be told from a second insert. */
    private List<Optional<java.time.Instant>> rows(UUID id, String key, String value) {
      return jdbi.withHandle(
          handle ->
              handle
                  .createQuery(
                      """
                      SELECT valid_to FROM principal_attribute
                      WHERE principal_id = :id AND attr_key = :key AND attr_value = :value
                      ORDER BY valid_from
                      """)
                  .bind("id", id)
                  .bind("key", key)
                  .bind("value", value)
                  .mapTo(java.time.Instant.class)
                  .list()
                  .stream()
                  .map(Optional::ofNullable)
                  .toList());
    }

    @Test
    @DisplayName("a value can be given, and giving it again changes nothing")
    void idempotent() {
      UUID id = create("analyst_a");

      assertThat(store.addAttribute(id, "clearance", "L2", "cleared", "admin", "192.0.2.10"))
          .isTrue();
      assertThat(store.addAttribute(id, "clearance", "L2", "cleared", "admin", "192.0.2.10"))
          .isFalse();
      assertThat(rows(id, "clearance", "L2")).hasSize(1);
    }

    @Test
    @DisplayName("an attribute holds several values at once")
    void multiValued() {
      UUID id = create("analyst_a");
      store.addAttribute(id, "branch", "BKK-01", null, "admin", null);
      store.addAttribute(id, "branch", "CNX-01", null, "admin", null);

      List<String> held =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(
                          """
                          SELECT attr_value FROM principal_attribute
                          WHERE principal_id = :id AND attr_key = 'branch' AND valid_to IS NULL
                          """)
                      .bind("id", id)
                      .mapTo(String.class)
                      .list());
      assertThat(held).containsExactlyInAnyOrder("BKK-01", "CNX-01");
    }

    @Test
    @DisplayName("withdrawing closes the row rather than deleting it")
    void withdrawalKeepsTheHistory() {
      // An access decision made in March was made against the attributes of
      // March. Deleting the row would leave an audit replaying it against
      // today's and answering a question nobody asked.
      UUID id = create("analyst_a");
      store.addAttribute(id, "clearance", "L2", null, "admin", null);

      assertThat(store.removeAttribute(id, "clearance", "L2", "left the team", "admin", null))
          .isTrue();
      assertThat(rows(id, "clearance", "L2")).singleElement().matches(Optional::isPresent);
    }

    @Test
    @DisplayName("giving back a withdrawn value reopens that row, it does not add a second")
    void reopensRatherThanInserts() {
      // The unique constraint spans the closed row too, so an insert over a
      // withdrawn value would fail rather than restore it. This is the case
      // that would have shipped broken and only shown up on somebody's second
      // change of mind.
      UUID id = create("analyst_a");
      store.addAttribute(id, "clearance", "L2", null, "admin", null);
      store.removeAttribute(id, "clearance", "L2", null, "admin", null);

      assertThat(store.addAttribute(id, "clearance", "L2", "cleared again", "admin", null))
          .isTrue();
      assertThat(rows(id, "clearance", "L2")).singleElement().matches(Optional::isEmpty);
    }

    @Test
    @DisplayName("withdrawing something they were not carrying is a no-op, not an error")
    void withdrawingNothing() {
      UUID id = create("analyst_a");
      assertThat(store.removeAttribute(id, "clearance", "L2", null, "admin", null)).isFalse();
    }

    @Test
    @DisplayName("a value a directory owns is refused, with the reason")
    void aDirectoryOwnsItsOwn() {
      // Closing an entra row here would hold exactly until the next sync put
      // it back, and the person who did it would have no way of knowing.
      UUID id = entraUser();
      jdbi.useHandle(
          handle ->
              handle
                  .createUpdate(
                      """
                      INSERT INTO principal_attribute (principal_id, attr_key, attr_value, source)
                      VALUES (:id, 'department', 'FINANCE', 'entra')
                      """)
                  .bind("id", id)
                  .execute());

      assertThatThrownBy(
              () -> store.removeAttribute(id, "department", "FINANCE", null, "admin", null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class)
          .hasMessageContaining("came from a directory");
    }

    @Test
    @DisplayName("a synced principal can still be given one, because the two cannot collide")
    void localOnTopOfSynced() {
      // The whole point: entra carries a department because HR does, and
      // carries no clearance because nobody in HR decides one. A sync writes
      // only its own rows, so both live side by side and the engine reads
      // both.
      UUID id = entraUser();
      assertThat(store.addAttribute(id, "clearance", "L3", "cleared", "admin", null)).isTrue();

      String source =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(
                          """
                          SELECT source FROM principal_attribute
                          WHERE principal_id = :id AND attr_key = 'clearance'
                          """)
                      .bind("id", id)
                      .mapTo(String.class)
                      .one());
      assertThat(source).isEqualTo("local");
    }

    @Test
    @DisplayName("a name a rule could not read is refused at the point it is typed")
    void keyMustBeReadableInARule() {
      UUID id = create("analyst_a");
      // An expression reaches it as user.<name>. "cost centre" parses as
      // something else; "user.dept" parses as nothing at all.
      assertThatThrownBy(() -> store.addAttribute(id, "cost centre", "CC-1", null, "admin", null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class);
      assertThatThrownBy(() -> store.addAttribute(id, "user.dept", "FIN", null, "admin", null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class);
      assertThatThrownBy(() -> store.addAttribute(id, "clearance", "  ", null, "admin", null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class);
    }

    @Test
    @DisplayName("the audit trail names the value, not just the attribute")
    void auditCarriesTheValue() {
      // "somebody changed clearance" is not an answer an auditor can use.
      UUID id = create("analyst_a");
      store.addAttribute(id, "clearance", "L2", "CAB-114", "admin", "192.0.2.10");
      store.removeAttribute(id, "clearance", "L2", "role changed", "admin", "192.0.2.10");

      List<String> trail =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(
                          """
                          SELECT action || ' ' || attr_key || ' ' || attr_value || ' ' || reason
                          FROM audit_identity_change
                          WHERE target_username = 'analyst_a' AND attr_key IS NOT NULL
                          ORDER BY id
                          """)
                      .mapTo(String.class)
                      .list());
      assertThat(trail)
          .containsExactly(
              "ADD_ATTRIBUTE clearance L2 CAB-114",
              "REMOVE_ATTRIBUTE clearance L2 role changed");
    }
  }

  @Nested
  @DisplayName("the audit trail")
  class Audit {

    @Test
    @DisplayName("records every change with the actor who made it")
    void everyChange() {
      UUID id = create("analyst_a");
      store.grant(id, new IdentityAdminStore.RoleRequest("AUDITOR", null), "quarter end", "admin",
          "192.0.2.10");
      store.revoke(id, new IdentityAdminStore.RoleRequest("AUDITOR", null), "quarter over",
          "admin", "192.0.2.10");
      store.setEnabled(id, false, "left", "admin", null);
      store.resetPassword(id, "another passable one", "admin", null);

      List<String> actions =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(
                          """
                          SELECT action FROM audit_identity_change
                          WHERE target_username = 'analyst_a'
                          ORDER BY id
                          """)
                      .mapTo(String.class)
                      .list());
      assertThat(actions)
          .containsExactly(
              "CREATE_PRINCIPAL",
              "SET_PASSWORD",
              "GRANT_ROLE",
              "REVOKE_ROLE",
              "DISABLE_PRINCIPAL",
              "SET_PASSWORD");

      String reason =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(
                          """
                          SELECT reason FROM audit_identity_change
                          WHERE action = 'GRANT_ROLE' AND target_username = 'analyst_a'
                          """)
                      .mapTo(String.class)
                      .one());
      assertThat(reason).isEqualTo("quarter end");
    }

    @Test
    @DisplayName("keeps a client address the inet column can hold, and drops what it cannot")
    void addresses() {
      create("analyst_a");
      Optional<String> address =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(
                          """
                          SELECT host(client_ip) FROM audit_identity_change
                          WHERE action = 'CREATE_PRINCIPAL'
                            AND target_username = 'analyst_a'
                          """)
                      .mapTo(String.class)
                      .findFirst());
      assertThat(address).contains("192.0.2.10");
    }
  }
}
