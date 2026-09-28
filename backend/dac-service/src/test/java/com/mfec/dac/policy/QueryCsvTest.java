package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The server's CSV has to match the console's own ({@code lib/tabular.ts}),
 * so the rows on screen and every row read the same in a spreadsheet.
 */
@DisplayName("Query CSV")
class QueryCsvTest {

  private static String line(Object... values) throws Exception {
    StringBuilder out = new StringBuilder();
    QueryCsv.line(out, Arrays.asList(values));
    return out.toString();
  }

  @Test
  @DisplayName("comma-separated, CRLF-terminated, empty for null")
  void plain() throws Exception {
    assertThat(line("id", "name", null, 3)).isEqualTo("id,name,,3\r\n");
    assertThat(line()).isEqualTo("\r\n");
  }

  @Test
  @DisplayName("quotes only what needs it, doubling the quotes inside")
  void quoting() throws Exception {
    assertThat(QueryCsv.cell("a,b")).isEqualTo("\"a,b\"");
    assertThat(QueryCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    assertThat(QueryCsv.cell("two\nlines")).isEqualTo("\"two\nlines\"");
    assertThat(QueryCsv.cell("two\r\nlines")).isEqualTo("\"two\r\nlines\"");
    assertThat(QueryCsv.cell("plain text")).isEqualTo("plain text");
    assertThat(QueryCsv.cell("ภาษาไทย")).isEqualTo("ภาษาไทย");
  }

  @Test
  @DisplayName("a value a spreadsheet would run as a formula is defused")
  void formulas() {
    assertThat(QueryCsv.cell("=HYPERLINK(\"http://x.example\")"))
        .isEqualTo("\"'=HYPERLINK(\"\"http://x.example\"\")\"");
    assertThat(QueryCsv.cell("+1")).isEqualTo("'+1");
    assertThat(QueryCsv.cell("-cmd")).isEqualTo("'-cmd");
    assertThat(QueryCsv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    assertThat(QueryCsv.cell("\tx")).isEqualTo("'\tx");
    assertThat(QueryCsv.cell("a=b")).isEqualTo("a=b");
  }

  @Test
  @DisplayName("numbers as the grid shows them, never in exponent form")
  void numbers() {
    assertThat(QueryCsv.cell(new BigDecimal("1E+3"))).isEqualTo("1000");
    assertThat(QueryCsv.cell(new BigDecimal("2.500"))).isEqualTo("2.5");
    assertThat(QueryCsv.cell(new BigDecimal("0.000"))).isEqualTo("0");
    assertThat(QueryCsv.cell(2.0d)).isEqualTo("2");
    assertThat(QueryCsv.cell(1.0e-7d)).isEqualTo("0.0000001");
    assertThat(QueryCsv.cell(Double.NaN)).isEqualTo("NaN");
    assertThat(QueryCsv.cell(42L)).isEqualTo("42");
    assertThat(QueryCsv.cell(true)).isEqualTo("true");
  }

  @Test
  @DisplayName("a negative number is written as a number, defused like the console does")
  void negative() {
    // lib/tabular.ts defuses anything starting '-', numbers included, so the
    // two files agree; a spreadsheet still reads '-5 as text, which is the
    // price of never running a cell that starts with a minus.
    assertThat(QueryCsv.cell(-5)).isEqualTo(QueryCsv.cell("-5"));
  }

  @Test
  @DisplayName("the BOM is the one character Excel looks for")
  void bom() {
    assertThat(QueryCsv.BOM).isEqualTo("﻿");
    assertThat(List.of(QueryCsv.BOM.codePointAt(0))).containsExactly(0xFEFF);
  }
}
