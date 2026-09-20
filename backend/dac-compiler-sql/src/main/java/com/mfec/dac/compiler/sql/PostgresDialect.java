package com.mfec.dac.compiler.sql;

import com.mfec.dac.schema.api.MaskingSpec;
import java.util.function.Function;

/**
 * PostgreSQL spelling of the masking library (FR-4.4).
 *
 * <p>Nothing here needs an extension. {@code sha256()} has been in core since
 * 11, which matters because {@code pgcrypto} is not installable on several
 * managed offerings and a masking library that silently needs one would be a
 * library that works in dev and fails in production.
 */
public final class PostgresDialect implements SqlDialect {

  private final Function<String, String> salts;

  public PostgresDialect() {
    this(ref -> ref);
  }

  /**
   * @param salts turns a {@code saltRef} into the salt itself. The default
   *     returns the reference unchanged, which is enough to keep hashes
   *     unjoinable across columns but is <em>not</em> a secret; a deployment
   *     that cares hands in a resolver backed by the vault (NFR-1).
   */
  public PostgresDialect(Function<String, String> salts) {
    this.salts = salts == null ? ref -> ref : salts;
  }

  @Override
  public String name() {
    return "POSTGRES";
  }

  @Override
  public String quote(String identifier) {
    if (identifier == null || identifier.isBlank()) {
      throw new IllegalArgumentException("blank identifier");
    }
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  @Override
  public String literal(String value) {
    if (value == null) {
      return "NULL";
    }
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("value contains a NUL byte and cannot be a SQL literal");
    }
    // Standard-conforming strings are on by default since 9.1, so a backslash
    // is an ordinary character and doubling the quote is the whole escape.
    // E'' syntax is never emitted, which keeps that assumption checkable by
    // reading the output rather than the server's configuration.
    return "'" + value.replace("'", "''") + "'";
  }

  @Override
  public String toText(String expression) {
    return "CAST(" + expression + " AS text)";
  }

  @Override
  public String mask(String column, MaskingSpec spec) {
    MaskingSpec.MaskingFunction function =
        spec == null || spec.getFunction() == null
            ? MaskingSpec.MaskingFunction.NULLIFY
            : spec.getFunction();

    return switch (function) {
      case NULLIFY -> "NULL";
      case CONSTANT -> literal(constant(spec));
      case HASH ->
          "encode(sha256(convert_to(COALESCE("
              + toText(column)
              + ", '') || "
              + literal(salt(spec, column))
              + ", 'UTF8')), 'hex')";
      case PARTIAL -> partial(column, spec);
      case REGEX_REPLACE ->
          "regexp_replace("
              + toText(column)
              + ", "
              + literal(require(spec.getRegex(), "REGEX_REPLACE needs a regex"))
              + ", "
              + literal(spec.getReplacement() == null ? "" : spec.getReplacement())
              + ", 'g')";
      case ROUNDING -> rounding(column, spec);
      // A conditional mask is a wrapper the projection resolves before it gets
      // here. Arriving with one means the condition was lost, and the safe
      // reading of a lost condition is "mask always".
      case CONDITIONAL -> "NULL";
    };
  }

  private String partial(String column, MaskingSpec spec) {
    int last = spec.getShowLast() == null ? 0 : Math.max(0, spec.getShowLast());
    int first = spec.getShowFirst() == null ? 0 : Math.max(0, spec.getShowFirst());
    String text = toText(column);
    if (last == 0 && first == 0) {
      return literal(constant(spec));
    }
    String stars = "repeat('*', GREATEST(length(" + text + ") - " + (last + first) + ", 0))";
    String head = first > 0 ? "left(" + text + ", " + first + ") || " : "";
    String tail = last > 0 ? " || right(" + text + ", " + last + ")" : "";
    return "CASE WHEN " + column + " IS NULL THEN NULL ELSE " + head + stars + tail + " END";
  }

  private String rounding(String column, MaskingSpec spec) {
    String to = spec.getRoundTo() == null ? "YEAR" : spec.getRoundTo().trim();
    if (to.equalsIgnoreCase("YEAR") || to.equalsIgnoreCase("MONTH") || to.equalsIgnoreCase("DAY")) {
      return "date_trunc(" + literal(to.toLowerCase(java.util.Locale.ROOT)) + ", " + column + ")";
    }
    try {
      double bucket = Double.parseDouble(to);
      if (bucket <= 0) {
        throw new NumberFormatException(to);
      }
      return "floor(" + column + " / " + bucket + ") * " + bucket;
    } catch (NumberFormatException e) {
      throw new UnsupportedMaskingException(
          name(), "ROUNDING", "roundTo must be YEAR, MONTH, DAY or a positive number, not " + to);
    }
  }

  private String salt(MaskingSpec spec, String column) {
    String ref = spec.getSaltRef() == null || spec.getSaltRef().isBlank() ? column : spec.getSaltRef();
    return salts.apply(ref);
  }

  private static String constant(MaskingSpec spec) {
    return spec.getConstant() == null || spec.getConstant().isEmpty()
        ? "***REDACTED***"
        : spec.getConstant();
  }

  private static String require(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }
}
