package com.mfec.dac.policy;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rows as CSV, written the way the console's own CSV button writes them.
 *
 * <p>The two have to agree: somebody who downloads the rows on screen and then
 * every row expects the same file with more lines in it, and a spreadsheet
 * that reads one and chokes on the other makes the second look broken. So the
 * rules are the ones in {@code lib/tabular.ts}: a BOM first, CRLF line ends,
 * RFC 4180 quoting, an empty cell for null, and a quote in front of anything a
 * spreadsheet would take for a formula.
 *
 * <p>The formula guard matters more here than in most exporters. The rows
 * come from a customer's own tables, which is exactly where an attacker puts
 * a string beginning {@code =}; the file is then opened on a trusted laptop
 * because the platform handed it over.
 */
public final class QueryCsv {

  /** Without it, Excel on Windows reads UTF-8 as the system codepage. */
  public static final String BOM = "﻿";

  private static final Pattern FORMULA_START = Pattern.compile("^[=+\\-@\\t\\r]");
  private static final Pattern NEEDS_QUOTES = Pattern.compile("[\",\\r\\n]");

  private QueryCsv() {}

  /** One line, CRLF included. */
  public static void line(Appendable out, List<?> values) throws IOException {
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append(cell(values.get(i)));
    }
    out.append("\r\n");
  }

  static String cell(Object value) {
    String text = text(value);
    if (FORMULA_START.matcher(text).find()) {
      text = "'" + text;
    }
    return NEEDS_QUOTES.matcher(text).find() ? '"' + text.replace("\"", "\"\"") + '"' : text;
  }

  /**
   * A value as the grid shows it. Numbers are written out in full: the grid
   * reads them from JSON, which never shows {@code 1E+3} or {@code 2.0}, and
   * a file that did would not match the screen it was downloaded from.
   */
  private static String text(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof BigDecimal decimal) {
      return plain(decimal);
    }
    if (value instanceof Double || value instanceof Float) {
      double number = ((Number) value).doubleValue();
      return Double.isFinite(number) ? plain(BigDecimal.valueOf(number)) : String.valueOf(value);
    }
    return String.valueOf(value);
  }

  private static String plain(BigDecimal decimal) {
    return decimal.signum() == 0 ? "0" : decimal.stripTrailingZeros().toPlainString();
  }
}
