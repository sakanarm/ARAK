package com.mfec.dac.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;

/**
 * How much a decision may be reused (FR-5.5).
 *
 * <p>All three numbers are safety choices rather than tuning knobs, so they are
 * in the configuration an operator can see rather than constants in the code.
 *
 * <p>{@code enabled} exists so that a site which cannot accept any reuse at all
 * can say so, and so that anyone investigating a decision that looks wrong can
 * take the cache out of the picture in one restart instead of arguing about it.
 *
 * <p>{@code ttlSeconds} is the longest a decision can survive a change that
 * nothing announced. Everything that this product knows how to announce is
 * announced, so the time-to-live is covering the unknown: a write path added
 * later that forgets to publish, or a second instance of this service whose
 * flushes this one never hears. Sixty seconds is short enough that an
 * unannounced revocation is a minute of exposure rather than an open door, and
 * long enough that a dashboard refreshing every few seconds is served from
 * memory.
 *
 * <p>{@code maxEntries} bounds memory. A decision serialises to roughly one to
 * two kilobytes, so the default sits in the tens of megabytes; raise it on a
 * large estate and watch {@code bytes} in the cache statistics rather than
 * guessing.
 */
@Getter
@Setter
public class DecisionCacheConfiguration {

  @JsonProperty("enabled")
  private boolean enabled = true;

  @Min(1)
  @JsonProperty("maxEntries")
  private int maxEntries = 50_000;

  @Min(1)
  @JsonProperty("ttlSeconds")
  private long ttlSeconds = 60;

  public Duration ttl() {
    return Duration.ofSeconds(ttlSeconds);
  }
}
