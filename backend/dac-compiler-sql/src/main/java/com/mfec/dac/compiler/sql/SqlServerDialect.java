package com.mfec.dac.compiler.sql;

import com.mfec.dac.schema.api.MaskingSpec;
import java.util.Locale;
import java.util.function.Function;

/**
 * SQL Server spelling of the masking library (FR-4.4).
 *
 * <p>One function cannot be spelled at all: there is no regular-expression
 * replace before SQL Server 2025, and {@code REGEX_REPLACE} therefore throws
 * {@link UnsupportedMaskingException} rather than degrading into something that
 * looks similar. The capability matrix exists so that this is discovered when a
 * mode is chosen, not when a column leaks.
 */
public final class SqlServerDialect implements SqlDialect {

  private final Function<String, String> salts;

  public SqlServerDialect() {
    this(ref -> ref);
  }

  public SqlServerDialect(Function<String, String> salts) {
    this.salts = salts == null ? ref -> ref : salts;
  }

  @Override
  public String name() {
    return "SQLSERVER";
  }

  @Override
  public String quote(String identifier) {
    if (identifier == null || identifier.isBlank()) {
      throw new IllegalArgumentException("blank identifier");
    }
    return "[" + identifier.replace("]", "]]") + "]";
  }

  @Override
  public String literal(String value) {
    if (value == null) {
      return "NULL";
    }
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("value contains a NUL byte and cannot be a SQL literal");
    }
    // N'' so that a Thai branch name or a Unicode purpose string survives a
    // column collation that would otherwise fold it.
    return "N'" + value.replace("'", "''") + "'";
  }

  @Override
  public String toText(String expression) {
    return "CAST(" + expression + " AS nvarchar(max))";
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
          "CONVERT(varchar(64), HASHBYTES('SHA2_256', CONCAT(COALESCE("
              + toText(column)
              + ", N''), "
              + literal(salt(spec, column))
              + ")), 2)";
      case PARTIAL -> partial(column, spec);
      case REGEX_REPLACE ->
          throw new UnsupportedMaskingException(
              name(),
              "REGEX_REPLACE",
              "no regular-expression replace before SQL Server 2025; use PARTIAL, or enforce this"
                  + " asset through the proxy or a secure view");
      case ROUNDING -> rounding(column, spec);
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
    String hidden = "CASE WHEN LEN(" + text + ") - " + (last + first) + " > 0 THEN LEN(" + text
        + ") - " + (last + first) + " ELSE 0 END";
    String head = first > 0 ? "LEFT(" + text + ", " + first + ") + " : "";
    String tail = last > 0 ? " + RIGHT(" + text + ", " + last + ")" : "";
    return "CASE WHEN " + column + " IS NULL THEN NULL ELSE " + head + "REPLICATE(N'*', " + hidden
        + ")" + tail + " END";
  }

  private String rounding(String column, MaskingSpec spec) {
    String to = spec.getRoundTo() == null ? "YEAR" : spec.getRoundTo().trim();
    switch (to.toUpperCase(Locale.ROOT)) {
      case "YEAR":
        return "DATEFROMPARTS(YEAR(" + column + "), 1, 1)";
      case "MONTH":
        return "DATEFROMPARTS(YEAR(" + column + "), MONTH(" + column + "), 1)";
      case "DAY":
        return "CAST(" + column + " AS date)";
      default:
        try {
          double bucket = Double.parseDouble(to);
          if (bucket <= 0) {
            throw new NumberFormatException(to);
          }
          return "FLOOR(" + column + " / " + bucket + ") * " + bucket;
        } catch (NumberFormatException e) {
          throw new UnsupportedMaskingException(
              name(),
              "ROUNDING",
              "roundTo must be YEAR, MONTH, DAY or a positive number, not " + to);
        }
    }
  }

  private String salt(MaskingSpec spec, String column) {
    String ref =
        spec.getSaltRef() == null || spec.getSaltRef().isBlank() ? column : spec.getSaltRef();
    return salts.apply(ref);
  }

  private static String constant(MaskingSpec spec) {
    return spec.getConstant() == null || spec.getConstant().isEmpty()
        ? "***REDACTED***"
        : spec.getConstant();
  }
}
