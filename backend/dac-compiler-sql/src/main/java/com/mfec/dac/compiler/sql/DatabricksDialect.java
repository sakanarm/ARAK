package com.mfec.dac.compiler.sql;

import com.mfec.dac.schema.api.MaskingSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Databricks SQL spelling of the masking library (FR-4.4), for the query proxy.
 *
 * <h2>Why a string literal is built out of pieces</h2>
 *
 * <p>Databricks reads a backslash inside a string as an escape, and has no
 * setting that turns that off. It also does not read {@code ''} as a quote: two
 * strings side by side are one string, so {@code 'it''s'} is {@code its}. So
 * the one escape the parser used by the query proxy would agree with does not
 * exist here. {@link #literal} therefore never puts a quote or a backslash
 * inside quotes: it writes them as {@code chr(39)} and {@code chr(92)} and
 * joins the pieces with {@code concat}. The result is a constant expression the
 * source folds, and text in which no character can end or escape a string.
 *
 * <p>Written for a Databricks SQL warehouse. Identifiers are quoted with
 * backticks; a double-quoted word is a string there and a name to the parser,
 * which is why the lexical gate refuses double quotes on this engine and this
 * class never writes one.
 */
public final class DatabricksDialect implements SqlDialect {

  private final Function<String, String> salts;

  public DatabricksDialect() {
    this(ref -> ref);
  }

  public DatabricksDialect(Function<String, String> salts) {
    this.salts = salts == null ? ref -> ref : salts;
  }

  @Override
  public String name() {
    return "DATABRICKS";
  }

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
    if (value.indexOf('\'') < 0 && value.indexOf('\\') < 0) {
      return "'" + value + "'";
    }
    List<String> parts = new ArrayList<>();
    StringBuilder piece = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c != '\'' && c != '\\') {
        piece.append(c);
        continue;
      }
      if (piece.length() > 0) {
        parts.add("'" + piece + "'");
        piece.setLength(0);
      }
      parts.add("chr(" + (int) c + ")");
    }
    if (piece.length() > 0) {
      parts.add("'" + piece + "'");
    }
    return "concat(" + String.join(", ", parts) + ")";
  }

  @Override
  public String toText(String expression) {
    return "CAST(" + expression + " AS STRING)";
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
          "sha2(concat(coalesce("
              + toText(column)
              + ", ''), "
              + literal(salt(spec, column))
              + "), 256)";
      case PARTIAL -> partial(column, spec);
      case REGEX_REPLACE ->
          "regexp_replace("
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
    StringBuilder shown = new StringBuilder("concat(");
    if (first > 0) {
      shown.append("left(").append(text).append(", ").append(first).append("), ");
    }
    shown
        .append("repeat('*', greatest(char_length(")
        .append(text)
        .append(") - ")
        .append(last + first)
        .append(", 0))");
    if (last > 0) {
      shown.append(", right(").append(text).append(", ").append(last).append(")");
    }
    shown.append(")");
    return "CASE WHEN " + column + " IS NULL THEN NULL ELSE " + shown + " END";
  }

  private String rounding(String column, MaskingSpec spec) {
    String to = spec.getRoundTo() == null ? "YEAR" : spec.getRoundTo().trim();
    switch (to.toUpperCase(Locale.ROOT)) {
      case "YEAR":
        return "CAST(date_trunc('YEAR', " + column + ") AS DATE)";
      case "MONTH":
        return "CAST(date_trunc('MONTH', " + column + ") AS DATE)";
      case "DAY":
        return "CAST(" + column + " AS DATE)";
      default:
        try {
          double bucket = Double.parseDouble(to);
          if (bucket <= 0 || Double.isInfinite(bucket) || Double.isNaN(bucket)) {
            throw new NumberFormatException(to);
          }
          return "floor(" + column + " / " + bucket + ") * " + bucket;
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
  // Not written: the secure view is not offered on Databricks, where it would
  // be a Unity Catalog view with grants to groups and none of that has been
  // worked out. Each of these refuses rather than spelling something plausible,
  // because DDL that looks right is what gets applied.

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
        "Secure views are not available on Databricks sources; enforce through the query proxy");
  }
}
