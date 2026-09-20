package com.mfec.dac.compiler.sql;

/**
 * Thrown when a dialect cannot express a masking function at all.
 *
 * <p>SQL Server before 2025 has no regular-expression replace, for instance. A
 * compiler that quietly emitted the column unmasked in that case would be the
 * single worst bug this platform could have, so the failure is loud and the
 * caller's only honest choices are to null the column out and record it as
 * unenforceable (FR-6.0b) or to refuse the whole statement.
 */
public class UnsupportedMaskingException extends RuntimeException {

  private final String function;
  private final String dialect;

  public UnsupportedMaskingException(String dialect, String function, String detail) {
    super(dialect + " cannot express " + function + ": " + detail);
    this.dialect = dialect;
    this.function = function;
  }

  public String function() {
    return function;
  }

  public String dialect() {
    return dialect;
  }
}
