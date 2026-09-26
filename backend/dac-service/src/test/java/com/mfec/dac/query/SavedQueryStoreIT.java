package com.mfec.dac.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.query.SavedQueryStore.Clean;
import com.mfec.dac.query.SavedQueryStore.Draft;
import com.mfec.dac.query.SavedQueryStore.Saved;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Saved queries against a real database: who sees which, and one name per owner. */
@Testcontainers
class SavedQueryStoreIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static Jdbi jdbi;
  private SavedQueryStore store;
  private UUID source;

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

  @BeforeEach
  void clean() {
    jdbi.useHandle(handle -> handle.execute("TRUNCATE saved_query"));
    source =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        INSERT INTO data_source (name, engine, host, port, credential_ref)
                        VALUES ('src-' || gen_random_uuid(), 'POSTGRES', 'db.example.test', 5432, 'env:TEST_ONLY')
                        RETURNING id
                        """)
                    .mapTo(UUID.class)
                    .one());
    store = new SavedQueryStore(jdbi);
  }

  private static Clean draft(String name, String sql, boolean shared, UUID source) {
    return SavedQueryStore.validate(
        new Draft(name, null, source == null ? null : source.toString(), sql, shared));
  }

  @Test
  @DisplayName("your own queries and those shared with everyone; another's private one is not there")
  void visibility() {
    Saved mine = store.create(draft("Mine", "SELECT 1", false, source), "analyst_a");
    Saved open = store.create(draft("Open", "SELECT 2", true, source), "analyst_b");
    Saved closed = store.create(draft("Closed", "SELECT 3", false, null), "analyst_b");

    assertThat(store.list("analyst_a")).extracting(Saved::name).containsExactly("Mine", "Open");
    assertThat(store.list("ANALYST_B")).extracting(Saved::name).containsExactly("Closed", "Open");
    assertThat(store.find(open.id(), "analyst_a")).isPresent();
    assertThat(store.find(closed.id(), "analyst_a")).isEmpty();
    assertThat(store.find(mine.id(), "analyst_a").orElseThrow().sourceId()).isEqualTo(source);
  }

  @Test
  @DisplayName("one name per owner, ignoring case, refused with the query that has it")
  void names() {
    Saved first = store.create(draft("Monthly sales", "SELECT 1", false, null), "analyst_a");
    assertThatThrownBy(() -> store.create(draft("monthly SALES", "SELECT 2", false, null), "analyst_a"))
        .isInstanceOfSatisfying(
            SavedQueryStore.NameTakenException.class,
            e -> assertThat(e.existingId()).isEqualTo(first.id()));
    // Somebody else may use the same name.
    assertThat(store.create(draft("Monthly sales", "SELECT 3", false, null), "analyst_b").name())
        .isEqualTo("Monthly sales");

    // Renaming onto a taken name is refused the same way.
    Saved second = store.create(draft("Weekly", "SELECT 4", false, null), "analyst_a");
    assertThatThrownBy(() -> store.update(second.id(), draft("Monthly Sales", "SELECT 4", false, null), "analyst_a"))
        .isInstanceOf(SavedQueryStore.NameTakenException.class);
    // Keeping its own name is not a clash.
    Saved replaced = store.update(first.id(), draft("Monthly sales", "SELECT 9", true, null), "analyst_a");
    assertThat(replaced.sql()).isEqualTo("SELECT 9");
    assertThat(replaced.shared()).isTrue();
    assertThat(replaced.updatedAt()).isAfterOrEqualTo(first.updatedAt());
  }

  @Test
  @DisplayName("only the owner changes or deletes a query, even one that is shared")
  void owners() {
    Saved open = store.create(draft("Open", "SELECT 1", true, null), "analyst_b");
    assertThatThrownBy(() -> store.update(open.id(), draft("Mine now", "SELECT 2", true, null), "analyst_a"))
        .isInstanceOf(SavedQueryStore.NoSuchQueryException.class);
    assertThatThrownBy(() -> store.delete(open.id(), "analyst_a"))
        .isInstanceOf(SavedQueryStore.NoSuchQueryException.class);
    assertThat(store.find(open.id(), "analyst_b").orElseThrow().sql()).isEqualTo("SELECT 1");

    store.delete(open.id(), "analyst_b");
    assertThat(store.find(open.id(), "analyst_b")).isEmpty();
  }

  @Test
  @DisplayName("a source that is not registered is refused; a deleted source leaves the query")
  void sources() {
    assertThatThrownBy(() -> store.create(draft("Lost", "SELECT 1", false, UUID.randomUUID()), "analyst_a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No registered source");
    Saved kept = store.create(draft("Kept", "SELECT 1", false, source), "analyst_a");
    jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM data_source WHERE id = :id").bind("id", source).execute());
    assertThat(store.find(kept.id(), "analyst_a").orElseThrow().sourceId()).isNull();
  }
}
