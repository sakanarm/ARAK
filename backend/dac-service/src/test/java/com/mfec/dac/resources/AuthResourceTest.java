package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.JwtService;
import com.mfec.dac.auth.LocalIdentityDao;
import com.mfec.dac.auth.LocalIdentityDao.LocalAccount;
import com.mfec.dac.auth.PasswordHasher;
import com.mfec.dac.config.IdentityConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Changing one's own password: what the form refuses, and that a wrong
 * current password counts towards the same lock as a wrong sign-in.
 */
@DisplayName("AuthResource — changing your own password")
class AuthResourceTest {

  private static final String CURRENT = "handed-over-by-admin";
  private static final String CHOSEN = "a much longer one of my own";

  private final LocalIdentityDao identities = mock(LocalIdentityDao.class);
  private final IdentityConfiguration config = new IdentityConfiguration();
  private final AuthResource resource =
      new AuthResource(identities, mock(JwtService.class), config);

  private final AuthenticatedUser analyst = user("analyst_a", "local");
  private final HttpServletRequest http = mock(HttpServletRequest.class);

  private static AuthenticatedUser user(String name, String source) {
    return new AuthenticatedUser(
        UUID.randomUUID(), name, name + "@example.test", name, source, Set.of(), List.of());
  }

  private static LocalAccount account(
      AuthenticatedUser who, String password, boolean mustChange, Instant lockedUntil) {
    return new LocalAccount(
        who.id(),
        who.username(),
        who.email(),
        who.displayName(),
        true,
        password == null ? null : PasswordHasher.hash(password.toCharArray()),
        mustChange,
        0,
        lockedUntil);
  }

  private void holds(LocalAccount account) {
    when(identities.findLocalAccount(account.username())).thenReturn(Optional.of(account));
  }

  private static int status(Runnable call) {
    try {
      call.run();
    } catch (WebApplicationException e) {
      return e.getResponse().getStatus();
    }
    throw new AssertionError("expected the call to be refused");
  }

  private void change(String current, String chosen) {
    resource.changePassword(
        as(analyst), http, new AuthResource.PasswordChangeRequest(current, chosen));
  }

  @Test
  @DisplayName("the new password is stored, the change audited under the holder's name")
  void changes() {
    holds(account(analyst, CURRENT, true, null));
    when(http.getRemoteAddr()).thenReturn("192.0.2.10");

    resource.changePassword(
        as(analyst), http, new AuthResource.PasswordChangeRequest(CURRENT, CHOSEN));

    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    verify(identities)
        .changeOwnPassword(eq(analyst.id()), eq("analyst_a"), hash.capture(), eq("192.0.2.10"));
    assertThat(PasswordHasher.verify(CHOSEN.toCharArray(), hash.getValue())).isTrue();
  }

  @Test
  @DisplayName("a wrong current password counts towards the sign-in lock")
  void wrongCurrentPasswordIsCounted() {
    holds(account(analyst, CURRENT, false, null));

    assertThat(status(() -> change("not-the-password", CHOSEN))).isEqualTo(403);
    verify(identities)
        .recordFailure(
            analyst.id(),
            config.getMaxFailedLoginAttempts(),
            Duration.ofSeconds(config.getLockoutSeconds()));
    verify(identities, never()).changeOwnPassword(any(), any(), any(), any());
  }

  @Test
  @DisplayName("a locked account is refused before the current password is tried")
  void lockedAccountIsRefused() {
    holds(account(analyst, CURRENT, false, Instant.now().plusSeconds(600)));

    assertThat(status(() -> change(CURRENT, CHOSEN))).isEqualTo(429);
    verify(identities, never()).recordFailure(any(), anyInt(), any());
    verify(identities, never()).changeOwnPassword(any(), any(), any(), any());
  }

  @Test
  @DisplayName("a password too short, too long, the username or the old one is refused unread")
  void weakChoicesAreRefused() {
    assertThat(status(() -> change(CURRENT, "short-one"))).isEqualTo(400);
    assertThat(status(() -> change(CURRENT, "x".repeat(AuthResource.MAX_PASSWORD + 1))))
        .isEqualTo(400);
    assertThat(status(() -> change(CURRENT, CURRENT))).isEqualTo(400);
    assertThat(status(() -> change(CURRENT, null))).isEqualTo(400);

    AuthenticatedUser longName = user("analyst_with_long_name", "local");
    assertThat(
            status(
                () ->
                    resource.changePassword(
                        as(longName),
                        http,
                        new AuthResource.PasswordChangeRequest(CURRENT, "ANALYST_WITH_LONG_NAME"))))
        .isEqualTo(400);
    verifyNoInteractions(identities);
  }

  @Test
  @DisplayName("somebody without a local password of their own has nothing to change")
  void noLocalPassword() {
    // Somebody else's local account under the same name: never theirs to change.
    AuthenticatedUser namesake = user("analyst_a", "local");
    holds(account(namesake, CURRENT, false, null));
    assertThat(status(() -> change(CURRENT, CHOSEN))).isEqualTo(403);

    // Their own account, with no password set.
    holds(account(analyst, null, false, null));
    assertThat(status(() -> change(CURRENT, CHOSEN))).isEqualTo(403);
    verify(identities, never()).changeOwnPassword(any(), any(), any(), any());
  }

  @Test
  @DisplayName("/me says whether the password still has to be replaced")
  void meCarriesTheFlag() {
    when(identities.loadUser(analyst.id())).thenReturn(Optional.of(analyst));
    holds(account(analyst, CURRENT, true, null));

    AuthResource.Me me = resource.me(as(analyst));
    assertThat(me.mustChangePassword()).isTrue();
    assertThat(me.username()).isEqualTo("analyst_a");
    assertThat(me.id()).isEqualTo(analyst.id().toString());
  }

  @Test
  @DisplayName("/me never asks somebody from the directory to change a password ARAK does not hold")
  void directoryUserIsNeverAsked() {
    AuthenticatedUser entra = user("analyst_b", "entra");
    when(identities.loadUser(entra.id())).thenReturn(Optional.of(entra));

    assertThat(resource.me(as(entra)).mustChangePassword()).isFalse();
    verify(identities, never()).findLocalAccount(anyString());
  }

  private static SecurityContext as(Principal who) {
    return new SecurityContext() {
      @Override
      public Principal getUserPrincipal() {
        return who;
      }

      @Override
      public boolean isUserInRole(String role) {
        return false;
      }

      @Override
      public boolean isSecure() {
        return false;
      }

      @Override
      public String getAuthenticationScheme() {
        return "Bearer";
      }
    };
  }
}
