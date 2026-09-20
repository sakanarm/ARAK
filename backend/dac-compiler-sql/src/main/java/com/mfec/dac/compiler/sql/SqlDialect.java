package com.mfec.dac.compiler.sql;

import com.mfec.dac.schema.api.MaskingSpec;

/**
 * Everything the compilers need to know about one SQL engine, and nothing more.
 *
 * <p>All three enforcement modes render the same {@code PolicyDecision}; what
 * differs between PostgreSQL and SQL Server is spelling — how a string is
 * concatenated, what a hash function is called, how an identifier is quoted.
 * Keeping that difference behind this interface is what lets the cross-mode
 * consistency test compare results byte for byte: there is one decision, one
 * projection, and two spellings of it.
 *
 * <h2>On literals rather than bind parameters</h2>
 *
 * <p>Values reach these methods already resolved by the engine — a branch code
 * from the identity cache, a constant written by a policy author. They are
 * rendered as literals because the compiled SQL is also an artefact a human
 * reviews during dry-run, and a statement full of {@code ?} cannot be reviewed.
 * That places the whole weight of correctness on {@link #literal}, which is why
 * it refuses anything it cannot escape rather than doing its best.
 */
public interface SqlDialect {

  /** {@code POSTGRES} or {@code SQLSERVER}, matching the source registry. */
  String name();

  /** Wraps one identifier so that a reserved word or odd casing survives. */
  String quote(String identifier);

  /**
   * Renders a string value as a literal.
   *
   * @throws IllegalArgumentException when the value contains something this
   *     dialect cannot safely escape, which is always a bug upstream and never
   *     something to paper over
   */
  String literal(String value);

  /** The expression for a masked column, given the expression that reads it. */
  String mask(String columnExpression, MaskingSpec spec);

  /** {@code CAST(x AS text)} in whichever way this engine spells it. */
  String toText(String expression);

  /** A literal NULL typed to match the column, where the engine needs that. */
  default String nullLiteral() {
    return "NULL";
  }
}
