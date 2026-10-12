package com.mfec.dac.gateway;

/**
 * An error to send the client as an ErrorResponse, with the SQLSTATE a
 * PostgreSQL server would use for the same situation, so that a tool which
 * reacts to the code (retry on 53300, ask again for a password on 28P01)
 * reacts correctly.
 */
final class PgError extends Exception {
  private static final long serialVersionUID = 1L;

  static final String PROTOCOL_VIOLATION = "08P01";
  static final String INVALID_PASSWORD = "28P01";
  static final String INVALID_AUTHORIZATION = "28000";
  static final String INVALID_CATALOG = "3D000";
  static final String TOO_MANY_CONNECTIONS = "53300";
  static final String READ_ONLY = "25006";
  static final String IN_FAILED_TRANSACTION = "25P02";
  static final String INSUFFICIENT_PRIVILEGE = "42501";
  static final String SYNTAX_ERROR = "42601";
  static final String UNDEFINED_OBJECT = "42704";
  static final String UNDEFINED_PSTATEMENT = "26000";
  static final String UNDEFINED_CURSOR = "34000";
  static final String INVALID_PARAMETER = "22023";
  static final String INVALID_TEXT = "22P02";
  static final String FEATURE_NOT_SUPPORTED = "0A000";
  static final String QUERY_CANCELED = "57014";
  static final String CONNECTION_FAILURE = "08006";
  static final String INTERNAL = "XX000";

  private final String sqlState;
  private final String severity;
  private final String detail;
  private final String hint;

  PgError(String sqlState, String message) {
    this(sqlState, message, null, null, "ERROR");
  }

  PgError(String sqlState, String message, String detail, String hint) {
    this(sqlState, message, detail, hint, "ERROR");
  }

  private PgError(String sqlState, String message, String detail, String hint, String severity) {
    super(message);
    this.sqlState = sqlState;
    this.detail = detail;
    this.hint = hint;
    this.severity = severity;
  }

  /** An error that ends the connection. */
  static PgError fatal(String sqlState, String message) {
    return new PgError(sqlState, message, null, null, "FATAL");
  }

  String sqlState() {
    return sqlState;
  }

  String severity() {
    return severity;
  }

  String detail() {
    return detail;
  }

  String hint() {
    return hint;
  }

  boolean isFatal() {
    return "FATAL".equals(severity);
  }
}
