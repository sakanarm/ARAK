package com.mfec.dac.engine;

import com.mfec.dac.schema.entity.policy.AttributeCondition;

/**
 * One attribute value about a principal, tagged with where it came from.
 *
 * <p>The source matters because the same key can arrive from more than one
 * place — {@code department} from Entra and {@code department} typed into a
 * local override — and a policy is allowed to insist on one of them. A
 * condition that names no source accepts any.
 *
 * <p>Multi-valued attributes are modelled as several {@code Attribute} rows with
 * the same key, which is what {@code clearance in (L1, L2)} needs.
 */
public record Attribute(String key, String value, AttributeCondition.Source source) {

  public static Attribute entra(String key, Object value) {
    return new Attribute(key, str(value), AttributeCondition.Source.ENTRA);
  }

  public static Attribute openmetadata(String key, Object value) {
    return new Attribute(key, str(value), AttributeCondition.Source.OPENMETADATA);
  }

  public static Attribute local(String key, Object value) {
    return new Attribute(key, str(value), AttributeCondition.Source.LOCAL);
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
