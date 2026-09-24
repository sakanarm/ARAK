package com.mfec.dac.crypto;

import com.macasaet.fernet.Key;
import com.macasaet.fernet.StringValidator;
import com.macasaet.fernet.Token;
import com.mfec.dac.config.SecretsConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.temporal.TemporalAmount;
import java.util.Base64;

/**
 * Encrypts a secret this platform has to keep, rather than point at.
 *
 * <p>Most secrets in this product are never stored: {@code credential_ref} holds
 * a pointer and the value lives in an environment variable or a vault, which is
 * the right answer whenever one person configures something on behalf of the
 * whole deployment. It stops being the right answer the moment each person
 * supplies their own — nobody is going to add an environment variable per
 * analyst, and asking them to would mean the feature is administrator-only
 * again, which is the thing it is explicitly not.
 *
 * <p>So a personal API key is held here, encrypted, with the same Fernet
 * primitive OpenMetadata uses for connection secrets. What that buys and what it
 * does not is worth being exact about:
 *
 * <ul>
 *   <li>A copy of the database — a backup, a {@code pg_dump}, a replica, a
 *       screenshot of a table — does not yield the keys. That is the threat this
 *       is for, and it is the common one.
 *   <li>It is <em>not</em> protection from this service. The process holds the
 *       Fernet key, so anything that can run code here can read every stored
 *       key. There is no arrangement that both lets the service call a gateway
 *       on somebody's behalf and hides the credential from the service.
 * </ul>
 *
 * <p>Fernet's own validator exists for message passing and expires a token after
 * sixty seconds. At-rest ciphertext is the opposite case, so the validator here
 * does not expire. That is a deliberate departure from the library's default and
 * not an oversight — a stored key that stopped decrypting after a minute would
 * have been noticed, but it is worth saying which behaviour was chosen.
 */
public class SecretBox {

  /** Raised when there is nothing to encrypt with, or the ciphertext is bad. */
  public static class SecretBoxException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SecretBoxException(String message) {
      super(message);
    }

    public SecretBoxException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * A validator that checks the signature and nothing about the clock.
   *
   * <p>Both windows are set past any plausible lifetime rather than to zero:
   * the library subtracts them from the token timestamp, so "no limit" has to be
   * expressed as a very large limit. The authentication tag is still verified,
   * which is the part that matters — ciphertext altered in the database fails to
   * open rather than opening as something else.
   */
  private static final StringValidator FOREVER =
      new StringValidator() {
        @Override
        public TemporalAmount getTimeToLive() {
          return Duration.ofDays(365L * 1000);
        }

        @Override
        public TemporalAmount getMaxClockSkew() {
          return Duration.ofDays(365L * 1000);
        }
      };

  private final Key key;
  private final String problem;

  private SecretBox(Key key, String problem) {
    this.key = key;
    this.problem = problem;
  }

  /**
   * Builds one from the deployment's secrets configuration.
   *
   * <p>A missing or unusable key is not a startup failure. This product runs
   * perfectly well without anyone storing a personal credential, and refusing to
   * boot over a feature nobody has used yet would be a poor trade. The failure
   * surfaces where it is actionable — at the moment somebody tries to save a
   * key — with a message naming the environment variable to set.
   */
  public static SecretBox fromConfiguration(SecretsConfiguration config) {
    String configured = config == null ? null : config.getFernetKey();
    if (configured == null || configured.isBlank()) {
      return new SecretBox(
          null,
          "No FERNET_KEY is set on this service, so a personal API key cannot be stored "
              + "encrypted. Ask an administrator to set one.");
    }
    try {
      return new SecretBox(parse(configured.trim()), null);
    } catch (RuntimeException e) {
      return new SecretBox(
          null,
          "The FERNET_KEY on this service is not a usable encryption key, so a personal API "
              + "key cannot be stored encrypted.");
    }
  }

  /** For tests, and for anything that wants a box without the config object. */
  public static SecretBox withKey(String fernetKey) {
    return new SecretBox(parse(fernetKey), null);
  }

  /**
   * Accepts the key in the shapes deployments actually produce.
   *
   * <p>Fernet keys are conventionally 32 bytes in URL-safe base64, which is what
   * {@code cryptography.fernet.Fernet.generate_key()} emits and what this
   * project's {@code .env.example} carries. Standard base64 is accepted too,
   * because {@code openssl rand -base64 32} is the other way people generate
   * one and producing a service that rejects it teaches nothing useful.
   * Anything else is hashed to 32 bytes rather than refused: a deployment that
   * put a passphrase in the variable gets working encryption instead of a
   * feature that silently does not exist.
   */
  private static Key parse(String configured) {
    byte[] raw = decode(configured);
    if (raw.length != 32) {
      try {
        raw = MessageDigest.getInstance("SHA-256")
            .digest(configured.getBytes(StandardCharsets.UTF_8));
      } catch (Exception e) {
        throw new SecretBoxException("SHA-256 is unavailable on this JVM.", e);
      }
    }
    return new Key(raw);
  }

  private static byte[] decode(String value) {
    try {
      return Base64.getUrlDecoder().decode(value);
    } catch (IllegalArgumentException notUrlSafe) {
      try {
        return Base64.getDecoder().decode(value);
      } catch (IllegalArgumentException notBase64) {
        return new byte[0];
      }
    }
  }

  /** Whether a secret can be stored at all right now. */
  public boolean available() {
    return key != null;
  }

  /** Why not, in a sentence fit to show, or null when it can. */
  public String problem() {
    return problem;
  }

  /** The ciphertext to store. */
  public String seal(String plaintext) {
    if (key == null) {
      throw new SecretBoxException(problem);
    }
    if (plaintext == null || plaintext.isEmpty()) {
      throw new SecretBoxException("There is nothing to encrypt.");
    }
    return Token.generate(key, plaintext).serialise();
  }

  /**
   * The secret back.
   *
   * <p>Called on the path of an outbound request and nowhere else. The result is
   * passed straight to the one call that needs it and is never put in a field, a
   * log line or a response body.
   */
  public String open(String ciphertext) {
    if (key == null) {
      throw new SecretBoxException(problem);
    }
    if (ciphertext == null || ciphertext.isBlank()) {
      throw new SecretBoxException("There is no stored secret to decrypt.");
    }
    try {
      return Token.fromString(ciphertext).validateAndDecrypt(key, FOREVER);
    } catch (RuntimeException e) {
      // The likeliest cause by a distance is that FERNET_KEY was rotated or
      // differs between environments, and that is worth saying, because the
      // alternative reading -- "the database is corrupt" -- sends somebody
      // looking in the wrong place for an afternoon.
      throw new SecretBoxException(
          "A stored secret could not be decrypted. This usually means FERNET_KEY has changed "
              + "since it was saved; the secret has to be entered again.",
          e);
    }
  }
}
