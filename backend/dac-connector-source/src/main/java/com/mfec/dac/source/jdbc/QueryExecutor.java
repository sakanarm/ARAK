package com.mfec.dac.source.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one already-rewritten statement against a source and brings the rows back.
 *
 * <p>This is the last step of enforcement mode 5.2 and it is deliberately dumb:
 * it does not know what a policy is and never modifies the SQL it is given. By
 * the time a statement arrives here it has been parsed, resolved and rewritten;
 * a component that could still change the text would be a component that could
 * still undo a mask.
 *
 * <h2>Guards</h2>
 *
 * <ul>
 *   <li>A row cap, applied through {@code setMaxRows} rather than by editing
 *       the SQL, so a missing {@code LIMIT} cannot pull a fact table into heap.
 *   <li>A query timeout, so one careless join cannot hold a source connection
 *       open indefinitely.
 *   <li>Read-only connection and {@code executeQuery}, which fails rather than
 *       runs if something that is not a query got this far.
 * </ul>
 */
public final class QueryExecutor {

  /**
   * @param truncated true when the cap stopped the read, so the caller can say
   *     "first 200 rows" instead of presenting a partial answer as complete
   */
  public record Page(
      List<String> columns,
      List<String> columnTypes,
      List<List<Object>> rows,
      boolean truncated,
      long millis) {}

  private final CredentialResolver credentials;
  private final int loginTimeoutSeconds;

  public QueryExecutor() {
    this(new CredentialResolver(), 10);
  }

  public QueryExecutor(CredentialResolver credentials, int loginTimeoutSeconds) {
    this.credentials = credentials;
    this.loginTimeoutSeconds = loginTimeoutSeconds;
  }

  public Page run(
      SourceProbe.Target target,
      String credentialRef,
      String sql,
      int maxRows,
      int timeoutSeconds)
      throws SQLException, CredentialResolver.UnresolvableCredentialException {

    long started = System.nanoTime();
    CredentialResolver.Credential credential = credentials.resolve(credentialRef);

    try (Connection connection = JdbcTargets.open(target, credential, loginTimeoutSeconds);
        PreparedStatement statement = connection.prepareStatement(sql)) {

      // One over the cap: reading the extra row is how we know there was more
      // to read. Capping at exactly the limit makes a result of precisely N
      // rows indistinguishable from a truncated one.
      statement.setMaxRows(maxRows + 1);
      statement.setQueryTimeout(timeoutSeconds);

      try (ResultSet rs = statement.executeQuery()) {
        ResultSetMetaData meta = rs.getMetaData();
        int width = meta.getColumnCount();
        List<String> columns = new ArrayList<>(width);
        List<String> types = new ArrayList<>(width);
        for (int i = 1; i <= width; i++) {
          String label = meta.getColumnLabel(i);
          columns.add(label == null || label.isBlank() ? meta.getColumnName(i) : label);
          types.add(meta.getColumnTypeName(i));
        }

        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
          if (rows.size() >= maxRows) {
            truncated = true;
            break;
          }
          List<Object> row = new ArrayList<>(width);
          for (int i = 1; i <= width; i++) {
            row.add(portable(rs.getObject(i)));
          }
          rows.add(row);
        }
        return new Page(columns, types, rows, truncated, (System.nanoTime() - started) / 1_000_000);
      }
    }
  }

  /**
   * Turns driver-specific values into something JSON can carry.
   *
   * <p>Anything not recognised becomes its own {@code toString}. A binary
   * column rendered as a string is imperfect; a serialiser exception that
   * fails the whole page because one column was a {@code PGgeometry} is worse,
   * and a user who can see the other columns can at least tell us what broke.
   */
  private static Object portable(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number
        || value instanceof Boolean
        || value instanceof String) {
      return value;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant().toString();
    }
    if (value instanceof java.sql.Date date) {
      return date.toLocalDate().toString();
    }
    if (value instanceof Time time) {
      return time.toLocalTime().toString();
    }
    if (value instanceof byte[] bytes) {
      return "0x" + java.util.HexFormat.of().formatHex(bytes, 0, Math.min(bytes.length, 32));
    }
    return String.valueOf(value);
  }
}
