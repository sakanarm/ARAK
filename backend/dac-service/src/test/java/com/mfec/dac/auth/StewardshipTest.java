package com.mfec.dac.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StewardshipTest {

  static AuthenticatedUser user(Set<String> roles, List<String> scopes) {
    return new AuthenticatedUser(
        UUID.randomUUID(), "someone", "someone@example.test", "Someone", "local", roles, scopes);
  }

  @Test
  @DisplayName("an administrator and a policy author govern every table")
  void unboundedRoles() {
    assertThat(Stewardship.governs(user(Set.of("PLATFORM_ADMIN"), List.of()), "pg.db.hr.salary"))
        .isTrue();
    assertThat(Stewardship.governs(user(Set.of("POLICY_AUTHOR"), List.of()), "pg.db.hr.salary"))
        .isTrue();
  }

  @Test
  @DisplayName("a data owner governs their scope and what is under it, nothing else")
  void ownerScope() {
    AuthenticatedUser owner = user(Set.of("DATA_OWNER"), List.of("prod.Sales"));
    assertThat(Stewardship.governs(owner, "prod.Sales")).isTrue();
    assertThat(Stewardship.governs(owner, "prod.Sales.dbo.customer")).isTrue();
    assertThat(Stewardship.governs(owner, "prod.HR.dbo.salary")).isFalse();
  }

  @Test
  @DisplayName("owning prod.Sales is not owning prod.SalesArchive")
  void segmentBoundary() {
    AuthenticatedUser owner = user(Set.of("DATA_OWNER"), List.of("prod.Sales"));
    assertThat(Stewardship.governs(owner, "prod.SalesArchive.dbo.customer")).isFalse();
  }

  @Test
  @DisplayName("a data owner with no scopes owns nothing rather than everything")
  void noScopes() {
    assertThat(Stewardship.governs(user(Set.of("DATA_OWNER"), List.of()), "prod.Sales")).isFalse();
    assertThat(Stewardship.governs(user(Set.of("DATA_OWNER"), List.of("prod.Sales")), " "))
        .isFalse();
  }

  @Test
  @DisplayName("an auditor oversees everything and governs nothing")
  void auditor() {
    AuthenticatedUser auditor = user(Set.of("AUDITOR"), List.of());
    assertThat(Stewardship.governs(auditor, "prod.Sales")).isFalse();
    assertThat(Stewardship.oversees(auditor, "prod.Sales")).isTrue();
    assertThat(Stewardship.overseesEverything(auditor)).isTrue();
  }

  @Test
  @DisplayName("a requester neither governs nor oversees, and no caller is nobody")
  void requester() {
    AuthenticatedUser requester = user(Set.of("REQUESTER"), List.of("prod.Sales"));
    assertThat(Stewardship.governs(requester, "prod.Sales")).isFalse();
    assertThat(Stewardship.oversees(requester, "prod.Sales")).isFalse();
    assertThat(Stewardship.overseesEverything(user(Set.of("DATA_OWNER"), List.of("prod"))))
        .isFalse();
    assertThat(Stewardship.governs(null, "prod.Sales")).isFalse();
  }
}
