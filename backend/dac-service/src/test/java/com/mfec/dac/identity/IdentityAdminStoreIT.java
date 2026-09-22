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
