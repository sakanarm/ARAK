package com.mfec.dac.compiler.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.common.engine.SourceEngine;
import com.mfec.dac.common.engine.SourceEngines;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The half of the engine contract that only this module can check.
 *
 * <p>An engine names its dialect as a string, so that opening a socket does not
 * require the SQL compiler on the classpath. The price of that is that nothing
 * in {@code dac-common} can tell whether the name resolves. This test is where
 * that debt is paid: every registered engine is walked, and an engine whose
 * dialect was never written fails the build here rather than throwing the first
 * time somebody runs a query against it.
 */
class SqlDialectsTest {

  static List<String> engineIds() {
    return List.copyOf(SourceEngines.ids());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engineIds")
  void everyRegisteredEngineHasADialectBehindIt(String id) {
    SourceEngine engine = SourceEngines.of(id);
    SqlDialect dialect = SqlDialects.forEngine(engine);
    assertThat(dialect).isNotNull();
    assertThat(dialect.name()).isNotBlank();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engineIds")
  void resolvesTheSameDialectFromTheEngineIdAlone(String id) {
    assertThat(SqlDialects.forEngineId(id))
        .isInstanceOf(SqlDialects.forEngine(SourceEngines.of(id)).getClass());
  }

  @Test
  void handsOutAFreshInstanceEachTime() {
    // Not an optimisation left undone: a dialect carries the per-column salt
    // used by HASH, and sharing one instance across two sources would make
    // their hashes correlatable, which is the one property a per-column salt
    // exists to prevent.
    assertThat(SqlDialects.of("POSTGRES")).isNotSameAs(SqlDialects.of("POSTGRES"));
  }

  @Test
  void ignoresCaseAndSurroundingSpace() {
    assertThat(SqlDialects.of("postgres")).isInstanceOf(PostgresDialect.class);
    assertThat(SqlDialects.of("  SqlServer  ")).isInstanceOf(SqlServerDialect.class);
    assertThat(SqlDialects.of("MySql")).isInstanceOf(MySqlDialect.class);
  }

  @Test
  void saysWhatItHasWhenAskedForSomethingItDoesNot() {
    assertThatThrownBy(() -> SqlDialects.of("ORACLE"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ORACLE")
        .hasMessageContaining("POSTGRES");
  }

  @Test
  void findsNothingForNull() {
    assertThat(SqlDialects.find(null)).isEmpty();
  }

  @Test
  void refusesToInventADialectForAnEngineNobodyRegistered() {
    assertThatThrownBy(() -> SqlDialects.forEngineId("ORACLE"))
        .isInstanceOf(SourceEngines.UnsupportedEngineException.class);
  }
}
