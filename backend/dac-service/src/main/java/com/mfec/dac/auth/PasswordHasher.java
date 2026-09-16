package com.mfec.dac.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing for local principals (FR-2.2).
 *
 * <p>PBKDF2-HMAC-SHA256 from the JDK rather than bcrypt or argon2 from a third
 * party. The reason is not that it is better — argon2 is — but that it is the
 * strongest construction available without adding a dependency, and a local
 * password here guards a test account or a service account on a deployment that
 * has Entra ID for everyone else. Adding a native library to the fat jar for
 * that trade is not worth the deployment surface.
 *
 * <p>The encoded form carries its own parameters:
 *
 * <pre>pbkdf2-sha256$210000$&lt;base64 salt&gt;$&lt;base64 hash&gt;</pre>
 *
 * <p>so the iteration count can be raised later and old hashes still verify.
 * {@link #needsRehash(String)} says when a stored hash was produced with weaker
 * parameters than the current ones, which is the signal to re-hash the password
 * the user has just proved they know.
 */
public final class PasswordHasher {

  private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
  private static final String PREFIX = "pbkdf2-sha256";
  private static final int ITERATIONS = 210_000;
  private static final int SALT_BYTES = 16;
  private static final int KEY_BITS = 256;

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getDecoder();

  private PasswordHasher() {}

  public static String hash(char[] password) {
    byte[] salt = new byte[SALT_BYTES];
    RANDOM.nextBytes(salt);
    byte[] key = derive(password, salt, ITERATIONS);
    return PREFIX + "$" + ITERATIONS + "$" + ENCODER.encodeToString(salt) + "$"
        + ENCODER.encodeToString(key);
  }

  /**
   * Verifies a candidate password against a stored hash.
   *
   * <p>Returns false rather than throwing for every malformed or unrecognised
   * stored value. A row whose hash we cannot read is a row nobody can log in
   * with, which is the safe reading of a corrupted credential.
   */
  public static boolean verify(char[] password, String stored) {
    if (stored == null) {
      return false;
    }
    String[] parts = stored.split("\\$");
    if (parts.length != 4 || !PREFIX.equals(parts[0])) {
      return false;
    }
    int iterations;
    byte[] salt;
    byte[] expected;
    try {
      iterations = Integer.parseInt(parts[1]);
      salt = DECODER.decode(parts[2]);
      expected = DECODER.decode(parts[3]);
    } catch (IllegalArgumentException e) {
      return false;
    }
    if (iterations <= 0 || salt.length == 0 || expected.length == 0) {
      return false;
    }
    byte[] actual = derive(password, salt, iterations, expected.length * 8);
    // Constant time: a length-sensitive or short-circuiting comparison here
    // leaks how much of the hash matched.
    return MessageDigest.isEqual(expected, actual);
  }

  /** True when the stored hash was produced with fewer rounds than we now use. */
  public static boolean needsRehash(String stored) {
    if (stored == null) {
      return true;
    }
    String[] parts = stored.split("\\$");
    if (parts.length != 4 || !PREFIX.equals(parts[0])) {
      return true;
    }
    try {
      return Integer.parseInt(parts[1]) < ITERATIONS;
    } catch (NumberFormatException e) {
      return true;
    }
  }

  private static byte[] derive(char[] password, byte[] salt, int iterations) {
    return derive(password, salt, iterations, KEY_BITS);
  }

  private static byte[] derive(char[] password, byte[] salt, int iterations, int bits) {
    KeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
    try {
      return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
    } catch (java.security.GeneralSecurityException e) {
      // PBKDF2WithHmacSHA256 is mandatory in every JDK we support; if it is
      // missing the deployment is broken in a way no fallback should paper over.
      throw new IllegalStateException("password hashing unavailable", e);
    }
  }

  /** Overwrites a password buffer once it is no longer needed. */
  public static void wipe(char[] password) {
    if (password != null) {
      java.util.Arrays.fill(password, '\0');
    }
  }
}
