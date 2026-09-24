package com.mfec.dac.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.common.engine.SourceEngine;
import com.mfec.dac.common.engine.SourceEngines;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * That the {@code source_engine} table and the code registry say the same thing.
 *
 * <p>V18 replaced a {@code CHECK (engine IN (...))} with a reference table, so
 * that SQL and the API can read the list instead of the console keeping its own
 * copy. The cost of that choice is a second place the list is written down, and
 * this test is the payment: an engine added to the registry and not to a
 * migration is caught here, not by a source that registers and then cannot be
 * saved.
 *
 * <p>The code is the authoritative side. The table exists to be read.
 */
@Testcontainers
class SourceEngineRegistryIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static Jdbi jdbi;

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbi.installPlugins();
  }

  @Test
  @DisplayName("every registered engine has a row, and every row is a registered engine")
  void theTableAndTheRegistryAgree() {
    List<String> rows =
        jdbi.withHandle(
            handle ->
                handle.createQuery("SELECT id FROM source_engine ORDER BY id").mapTo(String.class).list());
    assertThat(Set.copyOf(rows)).isEqualTo(SourceEngines.ids());
  }

  @Test
  @DisplayName("the row repeats what the engine says about itself")
  void theRowMatchesTheEngine() {
    for (SourceEngine engine : SourceEngines.all()) {
      Map<String, Object> row =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(
                          "SELECT display_name, default_port, supports_schemas"
                              + "  FROM source_engine WHERE id = :id")
                      .bind("id", engine.id())
                      .mapToMap()
                      .one());
      assertThat(row.get("display_name")).as(engine.id()).isEqualTo(engine.displayName());
      assertThat(row.get("default_port")).as(engine.id()).isEqualTo(engine.defaultPort());
      assertThat(row.get("supports_schemas")).as(engine.id()).isEqualTo(engine.supportsSchemas());
    }
  }

  @Test
  @DisplayName("a source naming an engine nobody supports is still refused")
  void theForeignKeyGuardsWhatTheCheckUsedTo() {
    // The point of V18 was to make the list readable, not to loosen it. If this
    // ever passes, a typo in an engine name becomes a source that registers and
    // fails at the first connection instead of at the INSERT.
    assertThatThrownBy(
            () ->
                jdbi.useHandle(
                    handle ->
                        handle.execute(
                            "INSERT INTO data_source"
                                + " (name, engine, host, port, credential_ref)"
                                + " VALUES ('typo', 'POSTGRESQL', 'db.example.test', 5432,"
                                + " 'vault://secret/data/sources/typo')")))
        .hasMessageContaining("data_source_engine_fkey");
  }

  @Test
  @DisplayName("an engine still in use cannot be removed from under its sources")
  void deletingAnEngineInUseIsRefused() {
    jdbi.useHandle(
        handle ->
            handle.execute(
                "INSERT INTO data_source (name, engine, host, port, credential_ref)"
                    + " VALUES ('in-use', 'POSTGRES', 'db.example.test', 5432,"
                    + " 'vault://secret/data/sources/in-use')"));
    try {
      // ON DELETE RESTRICT rather than CASCADE: a cascade here would take the
      // registry row with it, and with it the credential reference and the
      // enforcement mode of every table that engine was protecting.
      assertThatThrownBy(
              () ->
                  jdbi.useHandle(
                      handle -> handle.execute("DELETE FROM source_engine WHERE id = 'POSTGRES'")))
          .hasMessageContaining("data_source_engine_fkey");
    } finally {
      jdbi.useHandle(handle -> handle.execute("DELETE FROM data_source WHERE name = 'in-use'"));
    }
  }

  @Test
  @DisplayName("the proxy's capabilities were taken out of the capability table")
  void theCapabilityTableNoLongerSpeaksForTheProxy() {
    // Two places holding the same fact is how they drift. What the proxy can
    // express is a fact about this build's rewriter, and it lives beside the
    // rewriter in SourceEngine.proxyCapabilities().
    Integer proxyRows =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery("SELECT count(*) FROM engine_capability WHERE mode = 'PROXY'")
                    .mapTo(Integer.class)
                    .one());
    assertThat(proxyRows).isZero();

    // The native modes still have theirs, because those really are statements
    // about the engine and the compilers do need them (FR-6.0b).
    Integer nativeRows =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery("SELECT count(*) FROM engine_capability WHERE mode <> 'PROXY'")
                    .mapTo(Integer.class)
                    .one());
    assertThat(nativeRows).isPositive();
  }
}
