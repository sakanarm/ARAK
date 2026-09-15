package com.mfec.dac.engine;

/**
 * Evaluates the {@code expr} half of a policy — the part that compares the two
 * sides against each other, as in {@code user.country == asset.prop('dataResidency')}.
 *
 * <p>The ANTLR grammar for this is not written yet. The interface exists now so
 * that the engine's behaviour when an expression cannot be decided is settled
 * and tested from the start, rather than being discovered later.
 */
@FunctionalInterface
public interface ExpressionEvaluator {

  /**
   * The outcome of an expression.
   *
   * <p>{@link #ROW_DEPENDENT} is the one that matters for masking: a cell-level
   * condition such as {@code row.dept != user.dept} cannot be settled without a
   * row, so the engine passes it through to the compiler to become part of the
   * generated SQL instead of deciding it here.
   */
  enum Result {
    TRUE,
    FALSE,
    ROW_DEPENDENT
  }

  Result evaluate(String expression, Principal principal, AssetContext asset, RequestContext context)
      throws ExpressionUnavailableException;

  /**
   * The default until the grammar lands: every expression is undecidable, and
   * the engine falls back to whichever direction is safe for the rule carrying
   * it. Policies that use {@code expr} therefore behave conservatively rather
   * than appearing to work.
   */
  static ExpressionEvaluator unavailable() {
    return (expression, principal, asset, context) -> {
      throw new ExpressionUnavailableException(expression, "no expression evaluator is configured");
    };
  }
}
