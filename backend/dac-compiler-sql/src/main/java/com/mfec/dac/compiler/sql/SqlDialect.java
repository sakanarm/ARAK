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

  // --------------------------------------------------- DDL spelling (FR-6.1)
  //
  // The secure view is the one mode that installs objects of its own, so the
  // difference between the engines stops being about expressions and starts
  // being about statements. They live here, next to the expression spellings,
  // so that a third engine is a new file rather than a new branch in old code.

  /** Expression naming the database principal running the statement. */
  String currentDbPrincipal();

  /**
   * Expression naming the principal a trusted proxy put on this session, or
   * NULL when nothing did.
   *
   * <p>Only safe where every connection to the data comes through that proxy.
   * On both engines the setting is writable by the session itself, so a reader
   * who can open a direct connection can name themselves anybody at all — which
   * is why {@link #currentDbPrincipal()} is the default and this is the opt-in.
   */
  String sessionPrincipal();

  /** The type the entitlement tables use for a name or a value. */
  String aclTextType();

  /** Creates a schema if it is not there yet, in a single statement. */
  String createSchemaIfAbsent(String schema);

  /**
   * Creates a table if it is not there yet.
   *
   * @param body the column list and constraints, already rendered and indented
   */
  String createTableIfAbsent(String qualifiedName, String body);

  /** Replaces a view definition in place, keeping the grants already on it. */
  String createOrReplaceView(String qualifiedName, String body);

  /** Drops a view, succeeding when it was never there. */
  String dropViewIfExists(String qualifiedName);

  String grantSelect(String qualifiedName, String role);

  /** Takes every privilege on an object away from one role. */
  String revokeAllOn(String qualifiedName, String role);
}
