package com.mfec.dac.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Ordering and equality for facet and attribute values.
 *
 * <p>Everything arrives as text - an OpenMetadata custom property, an Entra
 * claim, a JSON literal in a policy - but sensitivityScore >= 8 and
 * retentionDate < '2026-01-01' have to mean what they say. Values are compared
 * as numbers when both parse as numbers, as dates when both parse as dates, and
 * as text otherwise. The type is never inferred from one side alone: comparing
 * "9" with "10" as text would make 9 the larger.
 */
final class Comparisons {

  private Comparisons() {}

  static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  static boolean equal(String a, Object b) {
    String other = asString(b);
    if (a == null || other == null) {
      return a == null && other == null;
    }
    // Numeric equality first, so 8 and 8.0 are the same value here as they are
    // in the JSON the policy was written in.
    BigDecimal na = number(a);
    BigDecimal nb = number(other);
    if (na != null && nb != null) {
      return na.compareTo(nb) == 0;
    }
    return a.equalsIgnoreCase(other);
  }

  /**
   * Three-way comparison, or null when the two are not comparable at all. A null
   * result means the condition does not hold, never that it holds vacuously.
   */
  static Integer compare(String a, Object b) {
    String other = asString(b);
    if (a == null || other == null) {
      return null;
    }
    BigDecimal na = number(a);
    BigDecimal nb = number(other);
    if (na != null && nb != null) {
      return na.compareTo(nb);
    }
    LocalDate da = date(a);
    LocalDate db = date(other);
    if (da != null && db != null) {
      return da.compareTo(db);
    }
    // Text ordering is what makes clearance >= L2 work. It only behaves when
    // the level names sort lexicographically, which is a property of the
    // naming scheme rather than of this code.
    return a.compareToIgnoreCase(other);
  }

  private static BigDecimal number(String s) {
    try {
      return new BigDecimal(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static LocalDate date(String s) {
    try {
      return LocalDate.parse(s.trim());
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
