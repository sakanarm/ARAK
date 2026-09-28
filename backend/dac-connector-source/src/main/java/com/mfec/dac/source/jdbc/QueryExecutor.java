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
 *   <li>A cost ceiling, when the caller sets one: the source's planner prices
 *       the statement on the same connection first, and one priced above the
 *       ceiling is never sent ({@link CostEstimate}).
 * </ul>
 */
public final class QueryExecutor {

  /**
   * @param truncated true when the cap stopped the read, so the caller can say
   *     "first 200 rows" instead of presenting a partial answer as complete
   * @param estimatedCost what the source's planner priced the statement at,
   *     in its own units; null when it was not asked or could not answer
   * @param unpricedBecause why a guarded read has no estimate; null otherwise
   */
  public record Page(
      List<String> columns,
      List<String> columnTypes,
      List<List<Object>> rows,
      boolean truncated,
      long millis,
      Double estimatedCost,
      String unpricedBecause) {

    public Page(
        List<String> columns,
        List<String> columnTypes,
        List<List<Object>> rows,
        boolean truncated,
        long millis) {
      this(columns, columnTypes, rows, truncated, millis, null, null);
    }
  }

  /** The planner priced the statement over the ceiling, so it was not run. */
  public static final class CostExceededException extends Exception {
    private final double estimate;
    private final double ceiling;

    public CostExceededException(double estimate, double ceiling) {
      super("Estimated cost " + estimate + " is over the ceiling of " + ceiling);
      this.estimate = estimate;
      this.ceiling = ceiling;
    }

    public double estimate() {
      return estimate;
    }

    public double ceiling() {
      return ceiling;
    }
  }

  /**
   * A read that hands its rows over one at a time, for a caller that writes
   * each out before asking for the next: a download of every row, where a
   * {@link Page} would hold the whole table in heap.
   *
   * <p>It holds a source connection until it is closed, so whoever opens one
   * closes it, on every path.
   */
  public static final class Cursor implements AutoCloseable {
    private final Connection connection;
    private final PreparedStatement statement;
    private final ResultSet rows;
    private final List<String> columns;
    private final List<String> columnTypes;
    private final CostEstimate.Price price;
    private boolean finished;

    private Cursor(
        Connection connection,
        PreparedStatement statement,
        ResultSet rows,
        CostEstimate.Price price)
        throws SQLException {
      this.connection = connection;
      this.statement = statement;
      this.rows = rows;
      this.price = price;
      ResultSetMetaData meta = rows.getMetaData();
      int width = meta.getColumnCount();
      List<String> names = new ArrayList<>(width);
      List<String> types = new ArrayList<>(width);
      for (int i = 1; i <= width; i++) {
        String label = meta.getColumnLabel(i);
        names.add(label == null || label.isBlank() ? meta.getColumnName(i) : label);
        types.add(meta.getColumnTypeName(i));
      }
      this.columns = List.copyOf(names);
      this.columnTypes = List.copyOf(types);
    }

    public List<String> columns() {
      return columns;
    }

    public List<String> columnTypes() {
      return columnTypes;
    }

    /** What the planner priced the whole read at; null when it was not asked. */
    public Double estimatedCost() {
      return price.cost();
    }

    public String unpricedBecause() {
      return price.unpricedBecause();
    }

    /** The next row, rendered as a {@link Page} renders it; null after the last. */
    public List<Object> next() throws SQLException {
      if (finished || !rows.next()) {
        finished = true;
        return null;
      }
      List<Object> row = new ArrayList<>(columns.size());
      for (int i = 1; i <= columns.size(); i++) {
        row.add(portable(rows.getObject(i)));
      }
      return row;
    }

    /**
     * Gives the connection back. A read stopped part-way is cancelled first, so
     * the source stops producing rows nobody will take.
     */
    @Override
    public void close() {
      try {
        if (!finished) {
          statement.cancel();
        }
      } catch (SQLException | RuntimeException e) {
        // Closing below still frees the connection.
      }
      quietly(rows);
      quietly(statement);
      try {
        if (!connection.getAutoCommit()) {
          connection.rollback();
        }
      } catch (SQLException | RuntimeException e) {
        // Nothing was written; the close below ends the transaction anyway.
      }
      quietly(connection);
    }
  }

  private final CredentialResolver credentials;
  private final int loginTimeoutSeconds;

  public QueryExecutor() {
    this(new CredentialResolver(), 10);
  }

  public QueryExecutor(CredentialResolver credentials, int loginTimeoutSeconds) {
    this.credentials = credentials;
    this.loginTimeoutSeconds = loginTimeoutSeconds;
  }

  /** Unpriced: as the six-argument form with no ceiling. */
  public Page run(
      SourceProbe.Target target,
      String credentialRef,
      String sql,
      int maxRows,
      int timeoutSeconds)
      throws SQLException, CredentialResolver.UnresolvableCredentialException {
    try {
      return run(target, credentialRef, sql, maxRows, timeoutSeconds, 0);
    } catch (CostExceededException e) {
      throw new IllegalStateException("A read with no ceiling was refused on cost", e);
    }
  }

  /**
   * @param maxCost the most the source's planner may price this statement at,
   *     in that engine's units; zero or less sends it unpriced
   * @throws CostExceededException when the planner priced it higher, in which
   *     case nothing but the {@code EXPLAIN} reached the source
   */
  public Page run(
      SourceProbe.Target target,
      String credentialRef,
      String sql,
      int maxRows,
      int timeoutSeconds,
      double maxCost)
      throws SQLException, CredentialResolver.UnresolvableCredentialException,
          CostExceededException {

    long started = System.nanoTime();
    CredentialResolver.Credential credential = credentials.resolve(credentialRef);

    try (Connection connection = JdbcTargets.open(target, credential, loginTimeoutSeconds)) {
      // Priced on the connection the statement will run on: a second login
      // just to ask the planner would double what every query costs the
      // source, which is the opposite of what the guard is for.
      CostEstimate.Price price =
          maxCost > 0
              ? CostEstimate.price(connection, target.engine(), sql, maxRows + 1, timeoutSeconds)
              : new CostEstimate.Price(null, null);
      if (price.cost() != null && price.cost() > maxCost) {
        throw new CostExceededException(price.cost(), maxCost);
      }
      return read(connection, sql, maxRows, timeoutSeconds, started, price);
    }
  }

  /**
   * Starts a read of every row the statement returns, for {@link Cursor}.
   *
   * <p>Priced without a row cap, because there is none. The source is asked to
   * send rows in batches of {@code fetchSize} rather than all at once: on
   * PostgreSQL that takes a transaction, since the driver only reads through
   * a cursor inside one, so autocommit is turned off after the price is taken
   * (a failed {@code EXPLAIN} inside a transaction would abort it). The
   * connection is still read-only, and the transaction is rolled back on close.
   *
   * @param timeoutSeconds how long the source may take to start answering
   * @throws CostExceededException when the planner priced it over {@code maxCost}
   */
  public Cursor open(
      SourceProbe.Target target,
      String credentialRef,
      String sql,
      int timeoutSeconds,
      double maxCost,
      int fetchSize)
      throws SQLException, CredentialResolver.UnresolvableCredentialException,
          CostExceededException {

    CredentialResolver.Credential credential = credentials.resolve(credentialRef);
    Connection connection = JdbcTargets.open(target, credential, loginTimeoutSeconds);
    PreparedStatement statement = null;
    try {
      CostEstimate.Price price =
          maxCost > 0
              ? CostEstimate.price(connection, target.engine(), sql, 0, timeoutSeconds)
              : new CostEstimate.Price(null, null);
      if (price.cost() != null && price.cost() > maxCost) {
        throw new CostExceededException(price.cost(), maxCost);
      }
      if ("POSTGRES".equals(target.engine())) {
        connection.setAutoCommit(false);
      }
      statement =
          connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
      statement.setFetchSize(fetchSize);
      statement.setQueryTimeout(timeoutSeconds);
      return new Cursor(connection, statement, statement.executeQuery(), price);
    } catch (SQLException | CostExceededException | RuntimeException e) {
      quietly(statement);
      quietly(connection);
      throw e;
    }
  }

  private static void quietly(AutoCloseable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (Exception e) {
      // Freeing a connection that has already failed has nothing left to report.
    }
  }

  private static Page read(
      Connection connection,
      String sql,
      int maxRows,
      int timeoutSeconds,
      long started,
      CostEstimate.Price price)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {

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
        return new Page(
            columns,
            types,
            rows,
            truncated,
            (System.nanoTime() - started) / 1_000_000,
            price.cost(),
            price.unpricedBecause());
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
