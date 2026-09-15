package com.mfec.dac.engine;

import com.mfec.dac.schema.api.MaskingSpec;

/**
 * Ranks masking functions by how much they conceal, so that when several
 * policies mask the same column the strictest one wins (FR-5.1).
 *
 * <p>The order is the one written into the schema and it is not a preference:
 *
 * <pre>NULLIFY &gt; CONSTANT &gt; HASH &gt; REGEX_REPLACE &gt; PARTIAL &gt; ROUNDING &gt; plaintext</pre>
 *
 * <p>HASH outranks REGEX_REPLACE and PARTIAL because it leaves nothing of the
 * original readable, even though it still supports a join. ROUNDING sits lowest
 * of the real functions because it keeps the value in the same neighbourhood -
 * a birth date rounded to a year is still a birth year.
 *
 * <p>CONDITIONAL is ranked below all of them because it is a wrapper that may
 * decide not to mask at all. Treating it as strong would let a conditional rule
 * displace an unconditional one and quietly release data the stricter policy
 * meant to hide.
 */
public final class MaskStrength {

  private MaskStrength() {}

  /** Higher conceals more. Plaintext - no spec at all - is 0. */
  public static int rank(MaskingSpec spec) {
    if (spec == null || spec.getFunction() == null) {
      return 0;
    }
    return switch (spec.getFunction()) {
      case NULLIFY -> 7;
      case CONSTANT -> 6;
      case HASH -> 5;
      case REGEX_REPLACE -> 4;
      case PARTIAL -> 3;
      case ROUNDING -> 2;
      case CONDITIONAL -> 1;
    };
  }

  /**
   * The stricter of two specs. Ties keep the incumbent, which makes composition
   * order-independent for equally strict masks and therefore reproducible: the
   * cross-mode consistency suite compares generated SQL byte for byte, and a
   * mask that depended on evaluation order would break it intermittently.
   */
  public static MaskingSpec strictest(MaskingSpec incumbent, MaskingSpec candidate) {
    if (incumbent == null) {
      return candidate;
    }
    if (candidate == null) {
      return incumbent;
    }
    return rank(candidate) > rank(incumbent) ? candidate : incumbent;
  }
}
