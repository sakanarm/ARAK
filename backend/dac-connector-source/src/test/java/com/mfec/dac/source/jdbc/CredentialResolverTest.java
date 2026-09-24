package com.mfec.dac.source.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.source.jdbc.CredentialResolver.Credential;
import com.mfec.dac.source.jdbc.CredentialResolver.UnresolvableCredentialException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How a stored credential is opened, and what happens when it cannot be.
 *
 * <p>The cases that matter here are the ones where getting it wrong is silent.
 * A resolver built without an opener would read every sealed credential as
 * unresolvable, which looks like a broken source rather than a missing key; and
 * the registry serves {@code fernet:stored} in place of the ciphertext, so the
 * edit form posts that word back on every save. If either of those were ever
 * accepted as a credential, a source would connect as nobody, or the marker
 * would be written over the real ciphertext and the password lost.
 */
class CredentialResolverTest {

  /** A stand-in for the deployment key: reversible, and obviously not Fernet. */
  private static String seal(String plaintext) {
    return new StringBuilder(plaintext).reverse().toString();
  }

  private static String open(String sealed) {
    return new StringBuilder(sealed).reverse().toString();
  }

  private static CredentialResolver withKey(Map<String, String> environment) {
    return new CredentialResolver(environment::get, CredentialResolverTest::open);
  }

  @Nested
  @DisplayName("a credential typed into the console")
  class Sealed {

    @Test
    @DisplayName("comes back as the username and password that were sealed")
    void roundTrips() throws Exception {
      CredentialResolver resolver = withKey(Map.of());

      Credential credential = resolver.resolve("fernet:" + seal("arak:s3cret"));

      assertThat(credential).isEqualTo(new Credential("arak", "s3cret"));
    }

    @Test
    @DisplayName("keeps a password that has colons in it")
    void splitsOnTheFirstColonOnly() throws Exception {
      CredentialResolver resolver = withKey(Map.of());

      Credential credential = resolver.resolve("fernet:" + seal("arak:a:b:c"));

      assertThat(credential.password()).isEqualTo("a:b:c");
    }

    @Test
    @DisplayName("is refused when the deployment has no key to open it with")
    void needsAnOpener() {
      // The default constructor is what a second, casually-built resolver looks
      // like. It must say why rather than reporting a bad credential.
      CredentialResolver resolver = new CredentialResolver();

      assertThatThrownBy(() -> resolver.resolve("fernet:" + seal("arak:s3cret")))
          .isInstanceOf(UnresolvableCredentialException.class)
          .hasMessageContaining("FERNET_KEY");
    }

    @Test
    @DisplayName("is not the word the registry serves in place of the ciphertext")
    void refusesTheRedactionMarker() {
      CredentialResolver resolver = withKey(Map.of());

      assertThatThrownBy(() -> resolver.resolve("fernet:" + CredentialResolver.STORED))
          .isInstanceOf(UnresolvableCredentialException.class)
          .hasMessageContaining("Re-enter");
    }

    @Test
    @DisplayName("is refused rather than guessed at when it opens to nonsense")
    void refusesAPlaintextWithoutAColon() {
      CredentialResolver resolver = withKey(Map.of());

      assertThatThrownBy(() -> resolver.resolve("fernet:" + seal("no-colon-here")))
          .isInstanceOf(UnresolvableCredentialException.class)
          .hasMessageContaining("expected shape");
    }

    @Test
    @DisplayName("reports the decryption failure instead of the credential")
    void reportsAnOpenerThatThrows() {
      CredentialResolver resolver =
          new CredentialResolver(
              name -> null,
              sealed -> {
                throw new IllegalStateException("key mismatch");
              });

      assertThatThrownBy(() -> resolver.resolve("fernet:whatever"))
          .isInstanceOf(UnresolvableCredentialException.class)
          .hasMessageContaining("key mismatch");
    }
  }

  @Nested
  @DisplayName("a pointer at a secret store")
  class Pointer {

    @Test
    @DisplayName("reads an environment variable as user:password")
    void readsTheEnvironment() throws Exception {
      CredentialResolver resolver = withKey(Map.of("SRC_PG", "arak:s3cret"));

      assertThat(resolver.resolve("env:SRC_PG"))
          .isEqualTo(new Credential("arak", "s3cret"));
    }

    @Test
    @DisplayName("says which variable is missing rather than that the source is broken")
    void namesTheMissingVariable() {
      CredentialResolver resolver = withKey(Map.of());

      assertThatThrownBy(() -> resolver.resolve("env:SRC_PG"))
          .isInstanceOf(UnresolvableCredentialException.class)
          .hasMessageContaining("SRC_PG");
    }

    @Test
    @DisplayName("is still unimplemented for a vault, and says so")
    void vaultIsNotWiredUp() {
      CredentialResolver resolver = withKey(Map.of());

      assertThatThrownBy(() -> resolver.resolve("vault://secret/data/dac/prod"))
          .isInstanceOf(UnresolvableCredentialException.class);
    }

    @Test
    @DisplayName("refuses a scheme nobody implements")
    void refusesAnUnknownScheme() {
      CredentialResolver resolver = withKey(Map.of());

      assertThatThrownBy(() -> resolver.resolve("arak:s3cret"))
          .isInstanceOf(UnresolvableCredentialException.class);
    }

    @Test
    @DisplayName("refuses an empty reference")
    void refusesNothingAtAll() {
      CredentialResolver resolver = withKey(Map.of());

      assertThatThrownBy(() -> resolver.resolve("   "))
          .isInstanceOf(UnresolvableCredentialException.class);
    }
  }
}
