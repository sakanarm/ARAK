package com.mfec.dac.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {

  @Test
  @DisplayName("a password verifies against its own hash")
  void roundTrip() {
    String hash = PasswordHasher.hash("correct horse battery staple".toCharArray());
    assertThat(PasswordHasher.verify("correct horse battery staple".toCharArray(), hash)).isTrue();
    assertThat(PasswordHasher.verify("Correct horse battery staple".toCharArray(), hash)).isFalse();
  }

  @Test
  @DisplayName("the same password hashes differently every time")
  void saltIsPerHash() {
    char[] password = "correct horse battery staple".toCharArray();
    assertThat(PasswordHasher.hash(password)).isNotEqualTo(PasswordHasher.hash(password));
  }

  @Test
  @DisplayName("the encoded form names its algorithm and work factor")
  void encodedFormIsSelfDescribing() {
    String hash = PasswordHasher.hash("anything".toCharArray());
    assertThat(hash).startsWith("pbkdf2-sha256$210000$");
    assertThat(hash.split("\\$")).hasSize(4);
  }

  @Test
  @DisplayName("a hash nobody can parse admits nobody")
  void unreadableHashFailsClosed() {
    assertThat(PasswordHasher.verify("anything".toCharArray(), null)).isFalse();
    assertThat(PasswordHasher.verify("anything".toCharArray(), "")).isFalse();
    assertThat(PasswordHasher.verify("anything".toCharArray(), "plaintext")).isFalse();
    // The shape of a bcrypt hash, which this deployment has never issued.
    assertThat(PasswordHasher.verify("anything".toCharArray(), "$2a$10$abcdefgh")).isFalse();
    // Right shape, unparseable work factor.
    assertThat(PasswordHasher.verify("anything".toCharArray(), "pbkdf2-sha256$x$AAAA$AAAA"))
        .isFalse();
  }

  @Test
  @DisplayName("a hash weaker than the current parameters is flagged for re-hashing")
  void rehashDetection() {
    assertThat(PasswordHasher.needsRehash(PasswordHasher.hash("anything".toCharArray()))).isFalse();
    assertThat(PasswordHasher.needsRehash("pbkdf2-sha256$1000$AAAA$AAAA")).isTrue();
    assertThat(PasswordHasher.needsRehash("$2a$10$abcdefgh")).isTrue();
    assertThat(PasswordHasher.needsRehash(null)).isTrue();
  }

  @Test
  @DisplayName("wiping clears the buffer")
  void wipe() {
    char[] password = "secret".toCharArray();
    PasswordHasher.wipe(password);
    assertThat(new String(password)).isEqualTo("\0\0\0\0\0\0");
  }
}
