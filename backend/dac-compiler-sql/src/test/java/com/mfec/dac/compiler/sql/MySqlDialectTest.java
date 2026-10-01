package com.mfec.dac.compiler.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.MaskingSpec.MaskingFunction;
import org.junit.jupiter.api.Test;

/**
 * The MySQL spelling of names, values and masks.
 *
 * <p>The strings are pinned here; that MySQL takes them and answers with the
 * masked value is {@code MySqlProxyIT}'s to show, against a real server.
 */
class MySqlDialectTest {

  private final MySqlDialect dialect = new MySqlDialect();

  @Test
  void quotesANameWithBackticksAndDoublesOneInside() {
    assertThat(dialect.quote("customer")).isEqualTo("`customer`");
    assertThat(dialect.quote("odd`name")).isEqualTo("`odd``name`");
    assertThatThrownBy(() -> dialect.quote(" ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void doublesAQuoteAndLeavesABackslashAsItIs() {
    assertThat(dialect.literal("O'Brien")).isEqualTo("'O''Brien'");
    // Right only on a session with NO_BACKSLASH_ESCAPES, which is every session
    // the platform opens on MySQL. Escaping it here as well would put two
    // backslashes in the value on exactly those sessions.
    assertThat(dialect.literal("BKK\\01")).isEqualTo("'BKK\\01'");
    assertThat(dialect.literal(null)).isEqualTo("NULL");
  }

  @Test
  void refusesAValueItCannotWriteAsALiteral() {
    assertThatThrownBy(() -> dialect.literal("a\0b")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aMissingFunctionMasksToNull() {
    assertThat(dialect.mask("`t`.`email`", null)).isEqualTo("NULL");
    assertThat(dialect.mask("`t`.`email`", new MaskingSpec())).isEqualTo("NULL");
    // A conditional mask that lost its condition masks always.
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
        .isEqualTo("SHA2(CONCAT(COALESCE(CAST(`t`.`email` AS CHAR), ''), 'email-salt'), 256)");
  }

  @Test
  void showsTheEndsOfAPartialMaskAndStarsTheRest() {
    String sql =
        dialect.mask(
            "`t`.`citizen_id`",
            new MaskingSpec().withFunction(MaskingFunction.PARTIAL).withShowFirst(1).withShowLast(4));
    assertThat(sql)
        .isEqualTo(
            "CASE WHEN `t`.`citizen_id` IS NULL THEN NULL ELSE"
                + " CONCAT(LEFT(CAST(`t`.`citizen_id` AS CHAR), 1),"
                + " REPEAT('*', GREATEST(CHAR_LENGTH(CAST(`t`.`citizen_id` AS CHAR)) - 5, 0)),"
                + " RIGHT(CAST(`t`.`citizen_id` AS CHAR), 4)) END");
    // || is OR on MySQL unless the server was told otherwise; a mask joined
    // with it would come back as 0 or 1.
    assertThat(sql).doesNotContain("||");
  }

  @Test
  void aPartialMaskShowingNothingIsTheConstant() {
    assertThat(dialect.mask("`t`.`phone`", new MaskingSpec().withFunction(MaskingFunction.PARTIAL)))
        .isEqualTo("'***REDACTED***'");
  }

  @Test
  void replacesByRegularExpression() {
    assertThat(
            dialect.mask(
                "`t`.`phone`",
                new MaskingSpec()
                    .withFunction(MaskingFunction.REGEX_REPLACE)
                    .withRegex("\\d")
                    .withReplacement("#")))
        .isEqualTo("REGEXP_REPLACE(CAST(`t`.`phone` AS CHAR), '\\d', '#')");
  }

  @Test
  void roundsADateOrANumber() {
    assertThat(round("YEAR")).isEqualTo("MAKEDATE(YEAR(`t`.`born`), 1)");
    assertThat(round("month")).isEqualTo("CAST(DATE_FORMAT(`t`.`born`, '%Y-%m-01') AS DATE)");
    assertThat(round("DAY")).isEqualTo("CAST(`t`.`born` AS DATE)");
    assertThat(round("1000")).isEqualTo("FLOOR(`t`.`born` / 1000.0) * 1000.0");
    assertThatThrownBy(() -> round("fortnight")).isInstanceOf(UnsupportedMaskingException.class);
    assertThatThrownBy(() -> round("-5")).isInstanceOf(UnsupportedMaskingException.class);
  }

  private String round(String to) {
    return dialect.mask(
        "`t`.`born`", new MaskingSpec().withFunction(MaskingFunction.ROUNDING).withRoundTo(to));
  }

  @Test
  void refusesToSpellASecureViewRatherThanGuessAtOne() {
    assertThatThrownBy(dialect::currentDbPrincipal)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("query proxy");
    assertThatThrownBy(() -> dialect.createOrReplaceView("`sec`.`customer`", "SELECT 1"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> dialect.revokeAllOn("`sales`.`customer`", "analyst"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
