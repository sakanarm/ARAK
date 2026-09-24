package com.mfec.dac.source.jdbc;

import java.util.Locale;

/**
 * Turns the credential <em>reference</em> stored against a source into an actual
 * username and password (NFR-1).
 *
 * <p>The registry holds either a pointer to the secret or the secret sealed,
 * never one that can be read off the row, so this is the
 * one place a real credential exists in the process — and it exists for the
 * length of one connection attempt. Nothing here caches, logs or returns the
 * resolved value to a caller that would serialise it.
 *
 * <p>Supported today:
 *
 * <ul>
 *   <li>{@code env:NAME} — the environment variable {@code NAME} holds
 *       {@code user:password}. This is the development path, and it is spelled
 *       out rather than implied so that a row using it is obviously not
 *       production.
 *   <li>{@code fernet:<ciphertext>} — a username and password somebody typed
 *       into the console, sealed with the deployment's Fernet key. The pointer
 *       shapes above are better when there is a vault to point at; insisting on
 *       them when there is not does not keep the password out of the product,
 *       it just moves it to whichever text file the operator uses instead. The
 *       ciphertext never leaves the server: the registry serves
 *       {@code fernet:stored} in its place, so a reader of the API, of a
 *       backup taken without the key, or of an audit export gets nothing
 *       usable.
 * </ul>
 *
 * <p>{@code vault://} and {@code azurekeyvault://} are accepted by the registry
 * because a source may legitimately be configured before the vault client is
 * wired, but resolving one fails with a message that says exactly that. The
 * alternative — quietly falling back to some other credential — is how a
 * connection ends up being made as the wrong identity.
 */
public final class CredentialResolver {

  /**
   * What the registry serves in place of a sealed credential.
   *
   * <p>Shared with the resource that does the redacting so that the two cannot
   * drift apart and leave a ciphertext being served as if it were a pointer.
   */
  public static final String STORED = "stored";

  /** A resolved credential. Short-lived on purpose; do not put one in a field. */
  public record Credential(String username, String password) {}

  /** The reference could not be turned into a credential, with why. */
  public static class UnresolvableCredentialException extends Exception {
    private static final long serialVersionUID = 1L;

    public UnresolvableCredentialException(String message) {
      super(message);
    }
  }

  private final java.util.function.Function<String, String> environment;

  /**
   * Turns a sealed token back into {@code user:password}, or null when this
   * deployment has no key configured.
   *
   * <p>Injected rather than imported because the cipher lives in the service
   * module and this connector is below it. That is worth the indirection: it
   * keeps the thing that opens secrets in one place instead of giving every
   * connector its own copy of the key handling.
   */
  private final java.util.function.UnaryOperator<String> unseal;

  public CredentialResolver() {
    this(System::getenv, null);
  }

  /** Tests pass their own environment rather than mutating the process's. */
  public CredentialResolver(java.util.function.Function<String, String> environment) {
    this(environment, null);
  }

  public CredentialResolver(
      java.util.function.Function<String, String> environment,
      java.util.function.UnaryOperator<String> unseal) {
    this.environment = environment;
    this.unseal = unseal;
  }

  public Credential resolve(String reference) throws UnresolvableCredentialException {
    if (reference == null || reference.isBlank()) {
      throw new UnresolvableCredentialException("This source has no credential reference");
    }
    String ref = reference.trim();
    String lower = ref.toLowerCase(Locale.ROOT);

    if (lower.startsWith("env:")) {
      String name = ref.substring("env:".length()).trim();
      if (name.isEmpty()) {
        throw new UnresolvableCredentialException("env: reference names no variable");
      }
      String value = environment.apply(name);
      if (value == null || value.isBlank()) {
        throw new UnresolvableCredentialException(
            "The environment variable " + name + " is not set on the service process");
      }
      int colon = value.indexOf(':');
      if (colon < 1 || colon == value.length() - 1) {
        // Deliberately does not echo the value: the message is read from a
        // browser and may be pasted into a ticket.
        throw new UnresolvableCredentialException(
            "The environment variable " + name + " must hold user:password");
      }
      return new Credential(value.substring(0, colon), value.substring(colon + 1));
    }

    if (lower.startsWith("vault://") || lower.startsWith("azurekeyvault://")) {
      throw new UnresolvableCredentialException(
          "No vault client is configured on this deployment, so " + scheme(ref)
              + " references cannot be resolved yet. Configure one, or use an env: reference "
              + "while developing.");
    }

    if (lower.startsWith("fernet:")) {
      if (unseal == null) {
        throw new UnresolvableCredentialException(
            "This deployment has no FERNET_KEY set, so a stored username and password cannot be "
                + "decrypted. Set one, or use an env: reference while developing.");
      }
      String sealed = ref.substring(ref.indexOf(':') + 1).trim();
      if (sealed.startsWith("//")) {
        sealed = sealed.substring(2).trim();
      }
      if (sealed.isEmpty() || STORED.equalsIgnoreCase(sealed)) {
        // The redacted form the API serves. Reaching here means it was read
        // back out of a response and sent in as if it were the secret, which
        // is a bug worth naming rather than a wrong password to chase.
        throw new UnresolvableCredentialException(
            "This source's stored credential was not sent back to the browser and cannot be "
                + "used as one. Re-enter the username and password to replace it.");
      }
      String plain;
      try {
        plain = unseal.apply(sealed);
      } catch (RuntimeException e) {
        throw new UnresolvableCredentialException(
            "The stored credential could not be decrypted: " + e.getMessage());
      }
      int colon = plain == null ? -1 : plain.indexOf(':');
      if (colon < 1 || colon == plain.length() - 1) {
        throw new UnresolvableCredentialException(
            "The stored credential is not in the expected shape. Re-enter it.");
      }
      return new Credential(plain.substring(0, colon), plain.substring(colon + 1));
    }

    throw new UnresolvableCredentialException(
        "Unknown credential scheme in " + scheme(ref)
            + ". This field holds a pointer to a secret, never the secret itself.");
  }

  private static String scheme(String ref) {
    int colon = ref.indexOf(':');
    return colon <= 0 ? ref : ref.substring(0, colon + 1);
  }
}
