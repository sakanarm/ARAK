package com.mfec.dac.common.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one place that knows which source engines exist.
 *
 * <p>Every component that used to switch on the engine now asks here instead:
 * the registry probe, the introspector, the query proxy and the REST endpoint
 * the browser reads its dropdown from. Adding an engine is adding an entry to
 * {@link #REGISTRY} and nothing else — and if something is missing, {@code
 * SourceEngineConformanceTest} says so before the build finishes rather than a
 * source saying so in production.
 *
 * <p>Registration is a static map rather than a {@link java.util.ServiceLoader}
 * on purpose. A service loader would let an engine appear because a jar was on
 * the classpath, which is a good property for a plugin system and a bad one
 * here: what this platform can enforce policy on is a decision, not a
 * packaging accident, and the list has to be reviewable in a diff.
 */
public final class SourceEngines {

  private SourceEngines() {}

  private static final Map<String, SourceEngine> REGISTRY = register(
      new PostgresEngine(),
      new SqlServerEngine(),
      new MySqlEngine());

  private static Map<String, SourceEngine> register(SourceEngine... engines) {
    Map<String, SourceEngine> map = new LinkedHashMap<>();
    for (SourceEngine engine : engines) {
      SourceEngine clash = map.put(normalise(engine.id()), engine);
      if (clash != null) {
        throw new IllegalStateException("Two engines both claim the id " + engine.id());
      }
    }
    // Not Map.copyOf, which forgets the order the engines were registered in:
    // that order is the one the register page offers them in.
    return Collections.unmodifiableMap(map);
  }

  /** Every engine this platform supports, in the order they are offered on screen. */
  public static List<SourceEngine> all() {
    return List.copyOf(REGISTRY.values());
  }

  /** The ids of every supported engine. */
  public static Set<String> ids() {
    return Set.copyOf(REGISTRY.keySet());
  }

  /** The engine with this id, or empty when it is not one we support. */
  public static Optional<SourceEngine> find(String id) {
    return id == null ? Optional.empty() : Optional.ofNullable(REGISTRY.get(normalise(id)));
  }

  /**
   * The engine with this id.
   *
   * @throws UnsupportedEngineException naming what was asked for and what exists,
   *     because the old message claimed Phase 1 could not connect to the engine
   *     when the truth was that we had not finished adding it
   */
  public static SourceEngine of(String id) {
    return find(id).orElseThrow(() -> new UnsupportedEngineException(id));
  }

  private static String normalise(String id) {
    return id.trim().toUpperCase(Locale.ROOT);
  }

  /** Thrown when something names an engine this platform does not have. */
  public static class UnsupportedEngineException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public UnsupportedEngineException(String id) {
      super("This platform has no source engine called '" + id + "'. It knows " + ids() + ".");
    }
  }
}
