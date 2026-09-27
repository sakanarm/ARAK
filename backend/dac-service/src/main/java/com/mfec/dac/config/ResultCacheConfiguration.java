package com.mfec.dac.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;

/**
 * How long the Query API may answer from memory (FR-6.3).
 *
 * <p>{@code ttlSeconds} is the whole of how stale a result can be, because the
 * source never says when its data moves. Thirty seconds lets a dashboard or a
 * person re-running the same statement while reading it be served from memory,
 * and is short enough that nobody mistakes it for a snapshot. Every cached
 * result says how old it is, and the console can always ask for a fresh read.
 *
 * <p>{@code maxCells} bounds memory in the unit rows actually cost: a result of
 * 5,000 rows by 50 columns is 250,000 cells, and one result may take at most a
 * quarter of the budget. {@code maxEntries} bounds the number of statements
 * held regardless of their size.
 *
 * <p>{@code enabled: false} sends every statement to the source, which is the
 * first thing to try when a result looks older than it should.
 */
@Getter
@Setter
public class ResultCacheConfiguration {

  @JsonProperty("enabled")
  private boolean enabled = true;

  @Min(1)
  @JsonProperty("maxEntries")
  private int maxEntries = 500;

  @Min(1)
  @JsonProperty("maxCells")
  private long maxCells = 1_000_000;

  @Min(1)
  @JsonProperty("ttlSeconds")
  private long ttlSeconds = 30;

  public Duration ttl() {
    return Duration.ofSeconds(ttlSeconds);
  }
}
