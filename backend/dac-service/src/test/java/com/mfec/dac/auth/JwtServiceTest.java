package com.mfec.dac.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

  private static final String SECRET = "a-test-signing-key-long-enough-to-be-accepted";
  private static final JwtService TOKENS =
      new JwtService(SECRET, "data-access-control", Duration.ofHours(1));

  private static AuthenticatedUser author() {
    return new AuthenticatedUser(
        UUID.fromString("11111111-2222-3333-4444-555555555555"),
        "policy_author",
        "policy_author@example.com",
        "Policy Author",
        "local",
        Set.of("POLICY_AUTHOR"),
        List.of("prod-mssql.SalesDB"));
  }

  @Test
  @DisplayName("a token issued here reads back as the same user")
  void roundTrip() {
    AuthenticatedUser back = TOKENS.verify(TOKENS.issue(author())).orElseThrow();
    assertThat(back.id()).isEqualTo(author().id());
    assertThat(back.username()).isEqualTo("policy_author");
    assertThat(back.email()).isEqualTo("policy_author@example.com");
    assertThat(back.source()).isEqualTo("local");
    assertThat(back.appRoles()).containsExactly("POLICY_AUTHOR");
    assertThat(back.scopes()).containsExactly("prod-mssql.SalesDB");
  }

  @Test
  @DisplayName("a token signed with another key is not ours")
  void foreignSignatureIsRejected() {
    JwtService other =
        new JwtService(
            "a-different-key-that-is-also-long-enough-ok", "data-access-control",
            Duration.ofHours(1));
    assertThat(TOKENS.verify(other.issue(author()))).isEmpty();
  }

  @Test
  @DisplayName("a token from another issuer is not ours either")
  void foreignIssuerIsRejected() {
    JwtService other = new JwtService(SECRET, "somebody-else", Duration.ofHours(1));
    assertThat(TOKENS.verify(other.issue(author()))).isEmpty();
  }

  @Test
  @DisplayName("an expired token no longer authenticates")
  void expiredTokenIsRejected() {
    // Negative lifetime: issued already past its expiry, which beats sleeping
    // through a real one in a unit test.
    JwtService expiring = new JwtService(SECRET, "data-access-control", Duration.ofSeconds(-120));
    assertThat(TOKENS.verify(expiring.issue(author()))).isEmpty();
  }

  @Test
  @DisplayName("garbage is rejected without throwing")
  void malformedTokenIsRejected() {
    assertThat(TOKENS.verify("")).isEmpty();
    assertThat(TOKENS.verify("not.a.token")).isEmpty();
    assertThat(TOKENS.verify("eyJhbGciOiJub25lIn0.eyJzdWIiOiJhZG1pbiJ9.")).isEmpty();
  }

  @Test
  @DisplayName("a short or missing signing key is refused at construction")
  void weakSecretIsRefused() {
    assertThatThrownBy(() -> new JwtService("short", "iss", Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32 bytes");
    assertThatThrownBy(() -> new JwtService(null, "iss", Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("PLATFORM_ADMIN satisfies any role requirement")
  void adminShortcut() {
    AuthenticatedUser admin =
        new AuthenticatedUser(
            UUID.randomUUID(), "admin", null, "Platform Administrator", "local",
            Set.of("PLATFORM_ADMIN"), List.of());
    assertThat(admin.isPlatformAdmin()).isTrue();
    assertThat(author().isPlatformAdmin()).isFalse();
    assertThat(author().hasAnyRole("AUDITOR", "POLICY_AUTHOR")).isTrue();
    assertThat(author().hasAnyRole("AUDITOR")).isFalse();
  }
}
