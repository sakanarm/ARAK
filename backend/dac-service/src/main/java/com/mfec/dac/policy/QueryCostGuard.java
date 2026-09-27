package com.mfec.dac.policy;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The ceiling a source's planner estimate must stay under before the Query API
 * sends a statement (FR-6.3 cost guard), and what the guard has done so far.
 *
 * <p>One ceiling per engine, because the engines price in different units:
 * PostgreSQL's cost is a count of notional page fetches, SQL Server's subtree
 * cost is its optimiser's own scale. The estimate is taken with the row cap
 * applied (see {@code CostEstimate}), so it prices the work the source will
 * really do for the rows that come back, not the whole table.
 *
 * <p>The defaults are set to refuse only what would be unreasonable through an
 * interactive proxy with a 30-second timeout — a join with nothing to join on,
 * an aggregate or sort over hundreds of millions of rows — and to let every
 * ordinary lookup through untouched. A deployment that knows its data sets its
 * own.
 */
public final class QueryCostGuard {

  /**
   * @param priced statements the planner put a figure on
   * @param unpriced statements that ran without one, because the engine has no
   *     estimate ARAK reads or the planner could not be asked
   * @param refused statements priced over their engine's ceiling and not run
   */
  public record Stats(
      boolean enabled,
      Map<String, Double> ceilings,
      long priced,
      long unpriced,
      long refused,
      Double highestAdmitted,
      Double lastRefusedEstimate) {}

  private final boolean enabled;
  private final Map<String, Double> ceilings;

  private long priced;
  private long unpriced;
  private long refused;
  private Double highestAdmitted;
  private Double lastRefusedEstimate;
  private String lastUnpricedBecause;

  /**
   * @param ceilings engine id to the highest estimate that may run; an engine
   *     with no ceiling, or one at zero, is not priced
   */
  public QueryCostGuard(boolean enabled, Map<String, Double> ceilings) {
    this.enabled = enabled;
    Map<String, Double> copy = new TreeMap<>();
    if (ceilings != null) {
      ceilings.forEach(
          (engine, ceiling) -> {
            if (engine != null && ceiling != null && ceiling > 0) {
              copy.put(engine.toUpperCase(Locale.ROOT), ceiling);
            }
          });
    }
    this.ceilings = Map.copyOf(copy);
  }

  public static QueryCostGuard off() {
    return new QueryCostGuard(false, Map.of());
  }

  /** Zero when statements to this engine are not to be priced. */
  public double ceilingFor(String engine) {
    if (!enabled || engine == null) {
      return 0;
    }
    return ceilings.getOrDefault(engine.toUpperCase(Locale.ROOT), 0d);
  }

  /**
   * A statement ran; {@code estimate} is null when it went unpriced.
   *
   * @return true when pricing failed for a reason other than the last one, so
   *     the caller logs a reason once rather than once per query. The reason is
   *     the source's own text and can name its objects, which is why it goes to
   *     the log and not to the statistics any signed-in user can read.
   */
  public synchronized boolean admitted(Double estimate, String unpricedBecause) {
    if (estimate == null) {
      unpriced++;
      if (unpricedBecause != null && !unpricedBecause.equals(lastUnpricedBecause)) {
        lastUnpricedBecause = unpricedBecause;
        return true;
      }
      return false;
    }
    priced++;
    highestAdmitted = highestAdmitted == null ? estimate : Math.max(highestAdmitted, estimate);
    return false;
  }

  public synchronized void refused(double estimate) {
    priced++;
    refused++;
    lastRefusedEstimate = estimate;
  }

  public synchronized Stats stats() {
    return new Stats(
        enabled,
        ceilings,
        priced,
        unpriced,
        refused,
        highestAdmitted,
        lastRefusedEstimate);
  }

  /** A cost the way the refusal message prints it. */
  static String format(double cost) {
    return cost >= 100
        ? String.format(Locale.ROOT, "%,.0f", cost)
        : String.format(Locale.ROOT, "%.2f", cost);
  }
}
