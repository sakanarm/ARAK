package com.mfec.dac.engine;

import com.mfec.dac.schema.entity.policy.Policy;
import java.time.ZoneId;

/**
 * What the engine needs to know that is not part of a policy.
 *
 * @param defaultZone   zone for date comparisons when a time rule declares no
 *                      window of its own; every window carries its own required
 *                      timezone, so this only covers {@code validFrom}/{@code validUntil}
 * @param environment   the environment this engine instance serves; a policy
 *                      pinned to a different one is ignored. Null means the
 *                      engine is not environment-aware and every policy applies
 * @param expressions   evaluator for the {@code expr} half of a rule
 */
public record EngineConfig(
    ZoneId defaultZone, Policy.Environment environment, ExpressionEvaluator expressions) {

  public EngineConfig {
    defaultZone = defaultZone == null ? ZoneId.of("UTC") : defaultZone;
    expressions = expressions == null ? ExpressionEvaluator.unavailable() : expressions;
  }

  /** UTC, environment-agnostic, no expression support. */
  public static EngineConfig defaults() {
    return new EngineConfig(null, null, null);
  }

  public EngineConfig withZone(ZoneId zone) {
    return new EngineConfig(zone, environment, expressions);
  }

  public EngineConfig withEnvironment(Policy.Environment value) {
    return new EngineConfig(defaultZone, value, expressions);
  }

  public EngineConfig withExpressions(ExpressionEvaluator evaluator) {
    return new EngineConfig(defaultZone, environment, evaluator);
  }
}
