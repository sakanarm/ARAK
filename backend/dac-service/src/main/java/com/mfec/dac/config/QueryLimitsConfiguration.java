package com.mfec.dac.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * How much of the sources the Query API may use at once, and how expensive a
 * single statement may be (FR-6.3).
 *
 * <p>{@code maxPerSource} is the number that matters most in practice: every
 * read is a connection to a database somebody else runs, frequently a server
 * other applications share, so it is kept well under the connection limit such
 * a server usually has. {@code maxPerCaller} keeps one person or one dashboard
 * from taking the slots everyone else needs, and {@code maxConcurrent} bounds
 * the service itself. A statement that finds no room waits up to
 * {@code queueWaitMillis} and is then refused as busy (HTTP 429).
 *
 * <p>The cost ceilings are in each engine's own planner units and apply to the
 * statement as it will run, row cap included: {@code maxCostPostgres} against
 * {@code EXPLAIN}'s total cost, {@code maxCostSqlServer} against the showplan's
 * subtree cost. Zero turns the guard off for that engine.
 *
 * <p>{@code exportTimeoutSeconds} bounds a download of every row, from the
 * first byte to the last. A download holds a source connection and one of
 * the caller's slots for as long as it runs, so it has an end like any other
 * read; it is much longer than a query's because it is doing much more.
 */
@Getter
@Setter
public class QueryLimitsConfiguration {

  @JsonProperty("enabled")
  private boolean enabled = true;

  @Min(1)
  @JsonProperty("maxConcurrent")
  private int maxConcurrent = 16;

  @Min(1)
  @JsonProperty("maxPerSource")
  private int maxPerSource = 4;

  @Min(1)
  @JsonProperty("maxPerCaller")
  private int maxPerCaller = 2;

  @Min(0)
  @JsonProperty("queueWaitMillis")
  private long queueWaitMillis = 3_000;

  @JsonProperty("costGuard")
  private boolean costGuard = true;

  @Min(0)
  @JsonProperty("maxCostPostgres")
  private double maxCostPostgres = 10_000_000;

  @Min(0)
  @JsonProperty("maxCostSqlServer")
  private double maxCostSqlServer = 5_000;

  @Min(0)
  @JsonProperty("maxCostMySql")
  private double maxCostMySql = 10_000_000;

  @Min(1)
  @JsonProperty("exportTimeoutSeconds")
  private int exportTimeoutSeconds = 600;

  public Duration queueWait() {
    return Duration.ofMillis(queueWaitMillis);
  }

  /** Engine id to ceiling, in the form {@code QueryCostGuard} takes. */
  public Map<String, Double> costCeilings() {
    return Map.of(
        "POSTGRES", maxCostPostgres, "SQLSERVER", maxCostSqlServer, "MYSQL", maxCostMySql);
  }
}
