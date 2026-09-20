package com.mfec.dac.source.jdbc;

import java.util.Locale;

/**
 * Turns the credential <em>reference</em> stored against a source into an actual
 * username and password (NFR-1).
 *
 * <p>The registry never holds the secret, only a pointer to it, so this is the
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
 * </ul>
 *
 * <p>{@code vault://} and {@code azurekeyvault://} are accepted by the registry
 * because a source may legitimately be configured before the vault client is
 * wired, but resolving one fails with a message that says exactly that. The
 * alternative — quietly falling back to some other credential — is how a
 * connection ends up being made as the wrong identity.
 */
public final class CredentialResolver {

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

  public CredentialResolver() {
    this(System::getenv);
  }

  /** Tests pass their own environment rather than mutating the process's. */
  public CredentialResolver(java.util.function.Function<String, String> environment) {
    this.environment = environment;
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

    if (lower.startsWith("fernet://")) {
      throw new UnresolvableCredentialException(
          "The Fernet secret store is not wired up yet, so fernet:// references cannot be "
              + "resolved. Use an env: reference while developing.");
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
