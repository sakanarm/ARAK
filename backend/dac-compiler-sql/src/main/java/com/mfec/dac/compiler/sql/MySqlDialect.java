package com.mfec.dac.compiler.sql;

import com.mfec.dac.schema.api.MaskingSpec;
import java.util.Locale;
import java.util.function.Function;

/**
 * MySQL spelling of the masking library (FR-4.4), for the query proxy.
 *
 * <p>Written for MySQL 8.0 and later. {@code REGEX_REPLACE} is spelled with
 * {@code REGEXP_REPLACE}, which 5.7 does not have; there the statement fails at
 * the source rather than returning the column unmasked.
 *
 * <h2>What this assumes about the session</h2>
 *
 * <p>{@link #literal} doubles a quote and leaves a backslash alone, which is
 * only a complete escape where the session runs with {@code
 * NO_BACKSLASH_ESCAPES}. Every connection this platform opens to a MySQL source
 * sets it ({@code MySqlEngine#sessionSetup}), and nothing this class writes is
 * meant to be run anywhere else: the one mode that hands its SQL to a person to
 * run, the secure view, is not offered on MySQL, and its statements are refused
 * below.
 */
public final class MySqlDialect implements SqlDialect {

  private final Function<String, String> salts;

  public MySqlDialect() {
    this(ref -> ref);
  }

  public MySqlDialect(Function<String, String> salts) {
    this.salts = salts == null ? ref -> ref : salts;
  }

  @Override
  public String name() {
    return "MYSQL";
  }

  /**
   * {@inheritDoc}
   *
   * <p>Backticks rather than double quotes: a backtick is a name in every SQL
   * mode, where a double quote is a name only under {@code ANSI_QUOTES} and a
   * string otherwise.
   */
  @Override
  public String quote(String identifier) {
    if (identifier == null || identifier.isBlank()) {
      throw new IllegalArgumentException("blank identifier");
    }
    return "`" + identifier.replace("`", "``") + "`";
  }

  @Override
  public String literal(String value) {
    if (value == null) {
      return "NULL";
    }
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("value contains a NUL byte and cannot be a SQL literal");
    }
    // See the class comment: the backslash is an ordinary character in the
    // sessions this is written for, so doubling the quote is the whole escape.
    return "'" + value.replace("'", "''") + "'";
  }

  @Override
  public String toText(String expression) {
    return "CAST(" + expression + " AS CHAR)";
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
          "SHA2(CONCAT(COALESCE("
              + toText(column)
              + ", ''), "
              + literal(salt(spec, column))
              + "), 256)";
      case PARTIAL -> partial(column, spec);
      case REGEX_REPLACE ->
          "REGEXP_REPLACE("
              + toText(column)
              + ", "
              + literal(require(spec.getRegex(), "REGEX_REPLACE needs a regex"))
              + ", "
              + literal(spec.getReplacement() == null ? "" : spec.getReplacement())
              + ")";
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
    // CONCAT, not ||: that is OR on MySQL unless PIPES_AS_CONCAT is set.
    StringBuilder shown = new StringBuilder("CONCAT(");
    if (first > 0) {
      shown.append("LEFT(").append(text).append(", ").append(first).append("), ");
    }
    shown
        .append("REPEAT('*', GREATEST(CHAR_LENGTH(")
        .append(text)
        .append(") - ")
        .append(last + first)
        .append(", 0))");
    if (last > 0) {
      shown.append(", RIGHT(").append(text).append(", ").append(last).append(")");
    }
    shown.append(")");
    return "CASE WHEN " + column + " IS NULL THEN NULL ELSE " + shown + " END";
  }

  private String rounding(String column, MaskingSpec spec) {
    String to = spec.getRoundTo() == null ? "YEAR" : spec.getRoundTo().trim();
    switch (to.toUpperCase(Locale.ROOT)) {
      case "YEAR":
        return "MAKEDATE(YEAR(" + column + "), 1)";
      case "MONTH":
        return "CAST(DATE_FORMAT(" + column + ", '%Y-%m-01') AS DATE)";
      case "DAY":
        return "CAST(" + column + " AS DATE)";
      default:
        try {
          double bucket = Double.parseDouble(to);
          if (bucket <= 0) {
            throw new NumberFormatException(to);
          }
          return "FLOOR(" + column + " / " + bucket + ") * " + bucket;
        } catch (NumberFormatException e) {
          throw new UnsupportedMaskingException(
              name(), "ROUNDING", "roundTo must be YEAR, MONTH, DAY or a positive number, not " + to);
        }
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

  // --------------------------------------------------- DDL spelling (FR-6.1)
  //
  // Not written. The secure view decides what a reader sees from who the
  // database says is reading, and none of that has been worked out for MySQL:
  // inside a view CURRENT_USER() is the definer, an account is a name at a
  // host, and there is no schema to put the secure objects in beside the
  // database itself. Each of these refuses rather than spelling something
  // plausible, because DDL that looks right is what gets applied.

  @Override
  public String currentDbPrincipal() {
    throw noSecureView();
  }

  @Override
  public String sessionPrincipal() {
    throw noSecureView();
  }

  @Override
  public String aclTextType() {
    throw noSecureView();
  }

  @Override
  public String createSchemaIfAbsent(String schema) {
    throw noSecureView();
  }

  @Override
  public String createTableIfAbsent(String qualifiedName, String body) {
    throw noSecureView();
  }

  @Override
  public String createOrReplaceView(String qualifiedName, String body) {
    throw noSecureView();
  }

  @Override
  public String dropViewIfExists(String qualifiedName) {
    throw noSecureView();
  }

  @Override
  public String grantSelect(String qualifiedName, String role) {
    throw noSecureView();
  }

  @Override
  public String revokeAllOn(String qualifiedName, String role) {
    throw noSecureView();
  }

  private static UnsupportedOperationException noSecureView() {
    return new UnsupportedOperationException(
        "Secure views are not available on MySQL sources; enforce through the query proxy");
  }
}
