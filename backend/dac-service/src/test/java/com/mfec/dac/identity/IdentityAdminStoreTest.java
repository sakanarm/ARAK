package com.mfec.dac.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the store refuses before it reaches the database.
 *
 * <p>Built on a null {@link org.jdbi.v3.core.Jdbi} on purpose. Every case
 * below must fail with a stated reason, and a NullPointerException here means
 * the input travelled as far as a connection before anybody checked it — which
 * is exactly the regression this file exists to catch. The rules that need the
 * directory's state to answer (the last administrator, a name already taken)
 * are in {@code IdentityAdminStoreIT}, where there is a directory.
 */
class IdentityAdminStoreTest {

  private final IdentityAdminStore store = new IdentityAdminStore(null, null);

  private static IdentityAdminStore.NewLocalPrincipal account(
      String username, String password) {
    return new IdentityAdminStore.NewLocalPrincipal(
        username, "Analyst A", "analyst_a@example.com", "USER", password, List.of());
  }

  @Nested
  @DisplayName("creating a local account")
  class Creating {

    @Test
    @DisplayName("needs a username shaped like one")
    void username() {
      assertThatThrownBy(() -> store.createLocal(account("a b", "correct horse battery"), "admin", null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class)
          .hasMessageContaining("username");
      assertThatThrownBy(() -> store.createLocal(account("x", "correct horse battery"), "admin", null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class);
      assertThatThrownBy(() -> store.createLocal(account("  ", "correct horse battery"), "admin", null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class);
    }

    @Test
    @DisplayName("needs a password that is not the username")
    void password() {
      assertThatThrownBy(() -> store.createLocal(account("analyst_a", "short"), "admin", null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class)
          .hasMessageContaining("10");
      // The one nobody types on purpose and everybody types under pressure.
      assertThatThrownBy(() -> store.createLocal(account("analyst_aa", "ANALYST_AA"), "admin", null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class)
          .hasMessageContaining("cannot be the username");
    }

    @Test
    @DisplayName("will not give a group a password, because nobody signs in as one")
    void type() {
      IdentityAdminStore.NewLocalPrincipal group =
          new IdentityAdminStore.NewLocalPrincipal(
              "Finance", "Finance", null, "GROUP", "correct horse battery", List.of());
      assertThatThrownBy(() -> store.createLocal(group, "admin", null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class)
          .hasMessageContaining("no password");
    }

    @Test
    @DisplayName("rejects a bad role before the account exists, not after")
    void rolesAreCheckedFirst() {
      // Otherwise an account is created, the role fails, and the operator is
      // left with half of what they asked for and no way to tell.
      IdentityAdminStore.NewLocalPrincipal withBadRole =
          new IdentityAdminStore.NewLocalPrincipal(
              "analyst_a",
              "Analyst A",
              null,
              "USER",
              "correct horse battery",
              List.of(new IdentityAdminStore.RoleRequest("SUPERUSER", null)));
      assertThatThrownBy(() -> store.createLocal(withBadRole, "admin", null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class)
          .hasMessageContaining("SUPERUSER");
    }
  }

  @Nested
  @DisplayName("granting a role")
  class Granting {

    @Test
    @DisplayName("a data owner without a scope owns nothing, so it is refused")
    void ownerNeedsScope() {
      assertThatThrownBy(
              () ->
                  store.grant(
                      java.util.UUID.randomUUID(),
                      new IdentityAdminStore.RoleRequest("DATA_OWNER", null),
                      null,
                      "admin",
                      null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class)
          .hasMessageContaining("scope");
    }

    @Test
    @DisplayName("a scope on a platform-wide role is refused rather than ignored")
    void scopeOnlyMeansSomethingForOwners() {
      // AuthenticatedUser pools every assignment's scope into one list, so a
      // scope attached here reads as authority it was never meant to describe.
      assertThatThrownBy(
              () ->
                  store.grant(
                      java.util.UUID.randomUUID(),
                      new IdentityAdminStore.RoleRequest("AUDITOR", "prod.SalesDB"),
                      null,
                      "admin",
                      null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class)
          .hasMessageContaining("only DATA_OWNER");
    }

    @Test
    @DisplayName("an unknown role is refused by name")
    void unknownRole() {
      assertThatThrownBy(
              () ->
                  store.grant(
                      java.util.UUID.randomUUID(),
                      new IdentityAdminStore.RoleRequest("ADMIN", null),
                      null,
                      "admin",
                      null))
          .isInstanceOf(IdentityAdminStore.InvalidPrincipalException.class)
          .hasMessageContaining("No such role");
    }
  }
}
