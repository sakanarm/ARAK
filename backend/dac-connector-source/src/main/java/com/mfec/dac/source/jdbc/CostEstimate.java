package com.mfec.dac.source.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks a source's own planner what a statement will cost before it is run
 * (FR-6.3 cost guard).
 *
 * <p>The number is the engine's, in the engine's units: arbitrary page-fetch
 * units on PostgreSQL, the optimiser's "subtree cost" on SQL Server. The two
 * are not comparable, so a ceiling is always set per engine, and nothing here
 * pretends to convert one into the other.
 *
 * <h2>Priced as it will run, row cap included</h2>
 *
 * <p>The executor stops reading after the row cap, and so does the source:
 * {@code SELECT * FROM a_billion_rows} costs a few pages when only 201 of them
 * are fetched. Pricing the bare statement would refuse exactly the query that
 * is cheapest to answer, so the cap is part of what is priced — a {@code LIMIT}
 * around it on PostgreSQL, {@code SET ROWCOUNT} on SQL Server, which the
 * optimiser treats as a row goal. What still prices high is what really is
 * expensive under the cap: a sort or an aggregate over a big table, or a join
 * with nothing to join on.
 *
 * <p>A download of every row has no cap, and is priced without one: that is
 * the read it will be, and a ceiling that let it through on the strength of a
 * cap it will not have would guard nothing.
 *
 * <h2>When the planner cannot be asked</h2>
 *
 * <p>No figure, and the statement runs under its timeout as it did
 * before the guard existed. This guard protects the source from load; it is
 * not what protects the data (the rewrite already did that), and a service
 * account without {@code SHOWPLAN} on SQL Server is a common, legitimate setup
 * that must not stop every query. The one failure that is not tolerated is
 * SQL Server's showplan mode refusing to switch off: on that connection the
 * next statement would come back as a plan instead of running, so the
 * connection is given up rather than used.
 */
public final class CostEstimate {

  /**
   * @param cost the planner's figure; null when it could not be had
   * @param unpricedBecause why not, in the source's words, for whoever wonders
   *     why a guard they configured never refuses anything
   */
  public record Price(Double cost, String unpricedBecause) {
    static Price of(double cost) {
      return new Price(cost, null);
    }

    static Price unpriced(String why) {
      return new Price(null, why);
    }
  }

  /** The first figure in PostgreSQL's JSON plan is the top node's. */
  private static final Pattern POSTGRES_TOTAL =
      Pattern.compile("\"Total Cost\"\\s*:\\s*([0-9.]+(?:[eE][+-]?[0-9]+)?)");

  private static final Pattern SQLSERVER_SUBTREE =
      Pattern.compile("StatementSubTreeCost=\"([0-9.]+(?:[eE][+-]?[0-9]+)?)\"");

  private CostEstimate() {}

  /**
   * @param engine the source engine id, {@code POSTGRES} or {@code SQLSERVER};
   *     any other engine is not priced
   * @param rowCap the most rows the statement will be read for; zero or less
   *     prices every row
   * @throws SQLException only when the connection has been left unsafe to use
   */
  public static Price price(
      Connection connection, String engine, String sql, int rowCap, int timeoutSeconds)
      throws SQLException {
    if (engine == null) {
      return Price.unpriced("no engine");
    }
    return switch (engine) {
      case "POSTGRES" -> postgres(connection, sql, rowCap, timeoutSeconds);
      case "SQLSERVER" -> sqlServer(connection, sql, rowCap, timeoutSeconds);
      default -> Price.unpriced(engine + " has no planner estimate ARAK knows how to read");
    };
  }

  private static Price postgres(
      Connection connection, String sql, int rowCap, int timeoutSeconds) {
    // A derived table rather than an appended LIMIT: the statement may carry
    // its own LIMIT, ORDER BY or WITH, all of which are legal inside one. The
    // line breaks keep a trailing comment from swallowing the closing bracket.
    String priced =
        "EXPLAIN (FORMAT JSON) SELECT * FROM (\n"
            + trimmed(sql)
            + "\n) AS arak_priced"
            + (rowCap > 0 ? " LIMIT " + rowCap : "");
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(timeoutSeconds);
      try (ResultSet rs = statement.executeQuery(priced)) {
        OptionalDouble cost = rs.next() ? first(POSTGRES_TOTAL, rs.getString(1)) : OptionalDouble.empty();
        return cost.isPresent()
            ? Price.of(cost.getAsDouble())
            : Price.unpriced("the plan carried no total cost");
      }
    } catch (SQLException e) {
      // Autocommit is on, so a failed EXPLAIN leaves no aborted transaction
      // behind for the real statement to trip over.
      return Price.unpriced(e.getMessage());
    }
  }

  private static Price sqlServer(
      Connection connection, String sql, int rowCap, int timeoutSeconds) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(timeoutSeconds);
      Price estimate = Price.unpriced("the plan carried no subtree cost");
      boolean showplan = false;
      try {
        // ROWCOUNT 0 is SQL Server's "no limit".
        statement.execute("SET ROWCOUNT " + Math.max(rowCap, 0));
        statement.execute("SET SHOWPLAN_XML ON");
        showplan = true;
        // With showplan on, the statement is compiled and not executed: what
        // comes back is one XML document per statement in the batch.
        double highest = -1;
        try (ResultSet rs = statement.executeQuery(sql)) {
          while (rs.next()) {
            OptionalDouble cost = highest(SQLSERVER_SUBTREE, rs.getString(1));
            if (cost.isPresent()) {
              highest = Math.max(highest, cost.getAsDouble());
            }
          }
        }
        if (highest >= 0) {
          estimate = Price.of(highest);
        }
      } catch (SQLException e) {
        // Most often a service account without SHOWPLAN in this database.
        estimate = Price.unpriced(e.getMessage());
      } finally {
        if (showplan) {
          // Deliberately allowed to throw: see the class comment.
          statement.execute("SET SHOWPLAN_XML OFF");
        }
        statement.execute("SET ROWCOUNT 0");
      }
      return estimate;
    }
  }

  private static String trimmed(String sql) {
    String out = sql.strip();
    while (out.endsWith(";")) {
      out = out.substring(0, out.length() - 1).strip();
    }
    return out;
  }

  private static OptionalDouble first(Pattern pattern, String text) {
    if (text == null) {
      return OptionalDouble.empty();
    }
    Matcher m = pattern.matcher(text);
    return m.find() ? parse(m.group(1)) : OptionalDouble.empty();
  }

  private static OptionalDouble highest(Pattern pattern, String text) {
    if (text == null) {
      return OptionalDouble.empty();
    }
    Matcher m = pattern.matcher(text);
    double highest = -1;
    while (m.find()) {
      OptionalDouble value = parse(m.group(1));
      if (value.isPresent()) {
        highest = Math.max(highest, value.getAsDouble());
      }
    }
    return highest < 0 ? OptionalDouble.empty() : OptionalDouble.of(highest);
  }

  private static OptionalDouble parse(String number) {
    try {
      return OptionalDouble.of(Double.parseDouble(number));
    } catch (NumberFormatException e) {
      return OptionalDouble.empty();
    }
  }
}
