package com.mfec.dac.compiler.sql;

import com.mfec.dac.common.engine.SourceEngine;
import com.mfec.dac.common.engine.SourceEngines;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Turns a dialect id into the object that writes SQL in it.
 *
 * <p>This is the other half of {@link SourceEngines}: an engine names its
 * dialect, and this resolves the name. The two are kept apart because building
 * a connection string must not require the SQL compiler on the classpath, and
 * because several engines can legitimately share one dialect.
 *
 * <p>The pairing is verified by {@code SqlDialectsTest}, which walks every
 * registered engine and resolves its dialect. That test is the reason a new
 * engine cannot reach production with no dialect behind it: the old code
 * instantiated {@code new PostgresDialect()} inside a switch, so a missing arm
 * was a runtime failure at the moment someone ran a query, which is the worst
 * possible moment to discover it.
 */
public final class SqlDialects {

  private SqlDialects() {}

  private static final Map<String, Supplier<SqlDialect>> REGISTRY = registry();

  private static Map<String, Supplier<SqlDialect>> registry() {
    Map<String, Supplier<SqlDialect>> map = new LinkedHashMap<>();
    map.put("POSTGRES", PostgresDialect::new);
    map.put("SQLSERVER", SqlServerDialect::new);
    map.put("MYSQL", MySqlDialect::new);
    return Map.copyOf(map);
  }

  /**
   * A dialect for this id.
   *
   * <p>A fresh instance each call, because a dialect carries the per-column
   * salt function used by {@code HASH} and sharing one across sources would
   * make two sources' hashes correlatable when the whole point of a per-column
   * salt is that they are not.
   */
  public static SqlDialect of(String dialectId) {
    return find(dialectId)
        .orElseThrow(() -> new IllegalArgumentException(
            "No SQL dialect is registered under '" + dialectId + "'. Registered: "
                + REGISTRY.keySet() + "."));
  }

  /** The dialect written by this engine. */
  public static SqlDialect forEngine(SourceEngine engine) {
    return of(engine.dialectId());
  }

  /** The dialect written by the engine with this id. */
  public static SqlDialect forEngineId(String engineId) {
    return forEngine(SourceEngines.of(engineId));
  }

  /** A dialect for this id, or empty when none is registered. */
  public static Optional<SqlDialect> find(String dialectId) {
    if (dialectId == null) {
      return Optional.empty();
    }
    Supplier<SqlDialect> supplier = REGISTRY.get(dialectId.trim().toUpperCase(Locale.ROOT));
    return Optional.ofNullable(supplier).map(Supplier::get);
  }
}
