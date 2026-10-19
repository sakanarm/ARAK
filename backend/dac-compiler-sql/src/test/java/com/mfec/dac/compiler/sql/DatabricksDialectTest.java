package com.mfec.dac.compiler.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.MaskingSpec.MaskingFunction;
import org.junit.jupiter.api.Test;

/**
 * The Databricks SQL spelling of names, values and masks.
 *
 * <p>The strings are pinned here; that a SQL warehouse takes them and answers
 * with the masked value has not been shown against a real workspace.
 */
class DatabricksDialectTest {

  private final DatabricksDialect dialect = new DatabricksDialect();

  @Test
  void quotesANameWithBackticksAndDoublesOneInside() {
    assertThat(dialect.quote("customer")).isEqualTo("`customer`");
    assertThat(dialect.quote("odd`name")).isEqualTo("`odd``name`");
    assertThatThrownBy(() -> dialect.quote(" ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void writesAPlainValueAsAPlainString() {
    assertThat(dialect.literal("BKK-01")).isEqualTo("'BKK-01'");
    assertThat(dialect.literal(null)).isEqualTo("NULL");
  }

  @Test
  void neverPutsAQuoteOrABackslashInsideQuotes() {
    // '' is two strings side by side to Databricks, and \ is an escape, so
    // neither can be written inside quotes and be read the way it was meant.
    assertThat(dialect.literal("O'Brien")).isEqualTo("concat('O', chr(39), 'Brien')");
    assertThat(dialect.literal("BKK\\01")).isEqualTo("concat('BKK', chr(92), '01')");
    assertThat(dialect.literal("'")).isEqualTo("concat(chr(39))");
    assertThat(dialect.literal("a''b")).isEqualTo("concat('a', chr(39), chr(39), 'b')");
    assertThat(dialect.literal("O'Brien")).doesNotContain("\\").doesNotContain("''");
  }

  @Test
  void refusesAValueItCannotWriteAsALiteral() {
    assertThatThrownBy(() -> dialect.literal("a\0b")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aMissingFunctionMasksToNull() {
    assertThat(dialect.mask("`t`.`email`", null)).isEqualTo("NULL");
    assertThat(dialect.mask("`t`.`email`", new MaskingSpec())).isEqualTo("NULL");
    assertThat(
            dialect.mask("`t`.`email`", new MaskingSpec().withFunction(MaskingFunction.CONDITIONAL)))
        .isEqualTo("NULL");
  }

  @Test
  void hashesWithSha256OverTheValueAndItsSalt() {
    assertThat(
            dialect.mask(
                "`t`.`email`",
                new MaskingSpec().withFunction(MaskingFunction.HASH).withSaltRef("email-salt")))
        .isEqualTo("sha2(concat(coalesce(CAST(`t`.`email` AS STRING), ''), 'email-salt'), 256)");
  }

  @Test
  void showsTheEndsOfAValueAndStarsTheMiddle() {
    assertThat(
            dialect.mask(
                "`t`.`citizen_id`",
                new MaskingSpec().withFunction(MaskingFunction.PARTIAL).withShowLast(4)))
        .isEqualTo(
            "CASE WHEN `t`.`citizen_id` IS NULL THEN NULL ELSE concat(repeat('*',"
                + " greatest(char_length(CAST(`t`.`citizen_id` AS STRING)) - 4, 0)),"
                + " right(CAST(`t`.`citizen_id` AS STRING), 4)) END");
    assertThat(
            dialect.mask(
                "`t`.`name`",
                new MaskingSpec()
                    .withFunction(MaskingFunction.PARTIAL)
                    .withShowFirst(1)
                    .withShowLast(1)))
        .contains("left(CAST(`t`.`name` AS STRING), 1)")
        .contains("right(CAST(`t`.`name` AS STRING), 1)")
        .contains("- 2");
  }

  @Test
  void aPartialMaskThatShowsNothingIsAConstant() {
    assertThat(
            dialect.mask(
                "`t`.`name`",
                new MaskingSpec().withFunction(MaskingFunction.PARTIAL).withConstant("hidden")))
        .isEqualTo("'hidden'");
  }

  @Test
  void writesARegexWithoutABackslashInTheText() {
    String sql =
        dialect.mask(
            "`t`.`phone`",
            new MaskingSpec()
                .withFunction(MaskingFunction.REGEX_REPLACE)
                .withRegex("\\d{4}$")
                .withReplacement("****"));
    assertThat(sql)
        .isEqualTo(
            "regexp_replace(CAST(`t`.`phone` AS STRING), concat(chr(92), 'd{4}$'), '****')");
    assertThat(sql).doesNotContain("\\");
  }

  @Test
  void roundsADateToItsYearOrMonthAndANumberToABucket() {
    assertThat(
            dialect.mask(
                "`t`.`born`",
                new MaskingSpec().withFunction(MaskingFunction.ROUNDING).withRoundTo("YEAR")))
        .isEqualTo("CAST(date_trunc('YEAR', `t`.`born`) AS DATE)");
    assertThat(
            dialect.mask(
                "`t`.`born`",
                new MaskingSpec().withFunction(MaskingFunction.ROUNDING).withRoundTo("month")))
        .isEqualTo("CAST(date_trunc('MONTH', `t`.`born`) AS DATE)");
    assertThat(
            dialect.mask(
                "`t`.`salary`",
                new MaskingSpec().withFunction(MaskingFunction.ROUNDING).withRoundTo("1000")))
        .isEqualTo("floor(`t`.`salary` / 1000.0) * 1000.0");
    assertThatThrownBy(
            () ->
                dialect.mask(
                    "`t`.`salary`",
                    new MaskingSpec().withFunction(MaskingFunction.ROUNDING).withRoundTo("-5")))
        .isInstanceOf(UnsupportedMaskingException.class);
  }

  @Test
  void refusesEverySecureViewStatementByName() {
    assertThatThrownBy(dialect::currentDbPrincipal)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Databricks");
    assertThatThrownBy(() -> dialect.createOrReplaceView("a.b", "select 1"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> dialect.grantSelect("a.b", "r"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
