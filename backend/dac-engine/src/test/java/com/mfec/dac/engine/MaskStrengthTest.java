package com.mfec.dac.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.MaskingSpec.MaskingFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaskStrengthTest {

  private static MaskingSpec spec(MaskingFunction function) {
    return new MaskingSpec().withFunction(function);
  }

  @Test
  @DisplayName("the order is the one written into the requirement, strictest first")
  void rankOrder() {
    assertThat(MaskStrength.rank(spec(MaskingFunction.NULLIFY)))
        .isGreaterThan(MaskStrength.rank(spec(MaskingFunction.CONSTANT)));
    assertThat(MaskStrength.rank(spec(MaskingFunction.CONSTANT)))
        .isGreaterThan(MaskStrength.rank(spec(MaskingFunction.HASH)));
    assertThat(MaskStrength.rank(spec(MaskingFunction.HASH)))
        .isGreaterThan(MaskStrength.rank(spec(MaskingFunction.REGEX_REPLACE)));
    assertThat(MaskStrength.rank(spec(MaskingFunction.REGEX_REPLACE)))
        .isGreaterThan(MaskStrength.rank(spec(MaskingFunction.PARTIAL)));
    assertThat(MaskStrength.rank(spec(MaskingFunction.PARTIAL)))
        .isGreaterThan(MaskStrength.rank(spec(MaskingFunction.ROUNDING)));
  }

  @Test
  @DisplayName("plaintext is weaker than every masking function")
  void plaintextIsTheFloor() {
    assertThat(MaskStrength.rank(null)).isZero();
    assertThat(MaskStrength.rank(new MaskingSpec())).isZero();
    assertThat(MaskStrength.rank(spec(MaskingFunction.CONDITIONAL))).isGreaterThan(0);
  }

  @Test
  @DisplayName("CONDITIONAL ranks below the unconditional functions")
  void conditionalIsTheWeakestRealFunction() {
    // It is a wrapper that may end up not masking at all, so it must never beat
    // a function that always does.
    assertThat(MaskStrength.rank(spec(MaskingFunction.CONDITIONAL)))
        .isLessThan(MaskStrength.rank(spec(MaskingFunction.ROUNDING)));
  }

  @Test
  @DisplayName("ties keep the incumbent, so composition does not depend on order")
  void tiesAreOrderIndependent() {
    MaskingSpec first = spec(MaskingFunction.HASH);
    MaskingSpec second = spec(MaskingFunction.HASH);
    assertThat(MaskStrength.strictest(first, second)).isSameAs(first);
    assertThat(MaskStrength.strictest(second, first)).isSameAs(second);
  }

  @Test
  @DisplayName("the stricter of two different functions wins either way round")
  void strictestPicksTheStronger() {
    MaskingSpec partial = spec(MaskingFunction.PARTIAL);
    MaskingSpec nullify = spec(MaskingFunction.NULLIFY);
    assertThat(MaskStrength.strictest(partial, nullify)).isSameAs(nullify);
    assertThat(MaskStrength.strictest(nullify, partial)).isSameAs(nullify);
  }
}
