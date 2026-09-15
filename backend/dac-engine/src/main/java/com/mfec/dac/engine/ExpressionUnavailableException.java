package com.mfec.dac.engine;

/**
 * Thrown when an expression cannot be decided for this principal and asset —
 * the grammar is not implemented yet, the text does not parse, or it refers to
 * something that is not loaded.
 *
 * <p>It is a checked exception on purpose. The caller has to choose a direction
 * to fail in, and that choice depends on what the expression was guarding: an
 * unreadable condition on an ALLOW must not grant, and an unreadable condition
 * on a DENY or a mask must not release data. Swallowing it as "false" would do
 * one of those two things silently.
 */
public class ExpressionUnavailableException extends Exception {

  private static final long serialVersionUID = 1L;

  private final String expression;

  public ExpressionUnavailableException(String expression, String reason) {
    super(reason + ": " + expression);
    this.expression = expression;
  }

  public String expression() {
    return expression;
  }
}
