package com.mfec.dac.llm;

import java.util.Locale;
import java.util.function.Function;

/**
 * Turns the stored pointer into the bearer token used for one gateway call.
 *
 * <p>Deliberately separate from {@code CredentialResolver}, which resolves a
 * database login and contractually returns {@code user:password}. A gateway key
 * is one opaque string; bending the database resolver to sometimes return half a
 * pair would have made both callers harder to read and would have let a source
 * credential be accepted here by accident.
 *
 * <p>What the two do share is the rule that matters: the column holds a
 * <em>reference</em>, never a secret. {@link #validate} is what enforces it at
 * the write, so a key pasted into the field is rejected at the form rather than
 * discovered later in a backup.
 */
public final class LlmSecretRef {

  /** The reference could not be turned into a token, with why. */
  public static class UnresolvableException extends Exception {
    private static final long serialVersionUID = 1L;

    public UnresolvableException(String message) {
      super(message);
    }
  }

  private final Function<String, String> environment;

  public LlmSecretRef() {
    this(System::getenv);
  }

  /** Tests pass their own environment rather than mutating the process's. */
  public LlmSecretRef(Function<String, String> environment) {
    this.environment = environment;
  }

  /**
   * Whether this is a shape we would accept into the column at all.
   *
   * @return null when acceptable, otherwise the reason to show the author
   */
  public static String validate(String reference) {
    if (reference == null || reference.isBlank()) {
      return "A credential reference is required.";
    }
    String lower = reference.trim().toLowerCase(Locale.ROOT);
    boolean known =
        lower.startsWith("env:")
            || lower.startsWith("vault://")
            || lower.startsWith("fernet://")
            || lower.startsWith("azurekeyvault://");
    if (!known) {
      // The likeliest mistake by far, and worth naming precisely, because the
      // person making it believes they are doing the obvious thing.
      return "This field holds a pointer to the key, not the key. Use env:NAME, "
          + "vault://path or azurekeyvault://name — and put the key itself in "
          + "that environment variable or vault entry.";
    }
    if (lower.startsWith("env:") && reference.trim().length() <= "env:".length()) {
      return "env: names no variable.";
    }
    return null;
  }

  /** The token itself. Short-lived on purpose; do not hold one in a field. */
  public String resolve(String reference) throws UnresolvableException {
    String problem = validate(reference);
    if (problem != null) {
      throw new UnresolvableException(problem);
    }
    String ref = reference.trim();
    String lower = ref.toLowerCase(Locale.ROOT);

    if (lower.startsWith("env:")) {
      String name = ref.substring("env:".length()).trim();
      String value = environment.apply(name);
      if (value == null || value.isBlank()) {
        throw new UnresolvableException(
            "The environment variable " + name + " is not set on the service process.");
      }
      return value.trim();
    }

    throw new UnresolvableException(
        "No secret store is wired up for "
            + ref.substring(0, Math.max(1, ref.indexOf(':') + 1))
            + " references on this deployment. Use an env: reference while developing.");
  }

  /** Whether the pointer resolves right now, without revealing anything about it. */
  public String problemWith(String reference) {
    try {
      resolve(reference);
      return null;
    } catch (UnresolvableException e) {
      return e.getMessage();
    }
  }
}
