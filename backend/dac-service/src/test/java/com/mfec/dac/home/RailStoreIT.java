package com.mfec.dac.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.home.Rail.InvalidRailException;
import com.mfec.dac.home.Rail.Layout;
import com.mfec.dac.home.Rail.Section;
import com.mfec.dac.home.Rail.View;
import java.util.List;
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

/** The rail round trip against the real table (V26). */
@Testcontainers
class RailStoreIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static Jdbi jdbi;
  private RailStore store;
  private UUID analyst;
  private UUID owner;

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
    jdbi.useHandle(handle -> handle.execute("TRUNCATE principal CASCADE"));
    store = new RailStore(jdbi, new ObjectMapper());
    analyst = principal("analyst");
    owner = principal("owner");
  }

  private static UUID principal(String username) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    INSERT INTO principal (principal_type, username, source)
                    VALUES ('USER', :u, 'local')
                    RETURNING id
                    """)
                .bind("u", username)
                .mapTo(UUID.class)
                .one());
  }

  @Test
  @DisplayName("somebody who never arranged it is told so, not handed an empty rail")
  void unarranged() {
    View view = store.forPrincipal(analyst);
    assertThat(view.sections()).isNull();
    assertThat(view.updatedAt()).isNull();
    assertThat(view.density()).isEqualTo(Rail.COMFORTABLE);
  }

  @Test
  @DisplayName("keeps the size with the arrangement, and forgets it on reset")
  void density() {
    List<Section> sections = List.of(new Section("/query", true));
    assertThat(store.save(analyst, new Layout(sections)).density()).isEqualTo(Rail.COMFORTABLE);
    assertThat(store.save(analyst, new Layout(sections, Rail.COMPACT)).density())
        .isEqualTo(Rail.COMPACT);
    assertThat(store.forPrincipal(analyst).density()).isEqualTo(Rail.COMPACT);

    assertThatThrownBy(() -> store.save(analyst, new Layout(sections, "tiny")))
        .isInstanceOf(InvalidRailException.class);
    assertThat(store.forPrincipal(analyst).density()).isEqualTo(Rail.COMPACT);

    assertThat(store.reset(analyst).density()).isEqualTo(Rail.COMFORTABLE);
    // The column refuses what the check refuses, for anything that skips the store.
    store.save(analyst, new Layout(sections));
    assertThatThrownBy(
            () ->
                jdbi.useHandle(
                    handle ->
                        handle.execute(
                            "UPDATE user_rail SET density = 'tiny' WHERE principal_id = ?",
                            analyst)))
        .isInstanceOf(Exception.class);
  }

  @Test
  @DisplayName("saves the order and the hidden ones, and hands back what a reload would")
  void roundTrip() {
    View saved =
        store.save(
            analyst,
            new Layout(
                List.of(
                    new Section("/requests", true),
                    new Section("/", true),
                    new Section("/catalog", false))));

    assertThat(saved.sections())
        .containsExactly(
            new Section("/requests", true), new Section("/", true), new Section("/catalog", false));
    assertThat(saved.updatedAt()).isNotNull();
    assertThat(store.forPrincipal(analyst)).isEqualTo(saved);

    // Saving again replaces the whole arrangement rather than merging into it.
    store.save(analyst, new Layout(List.of(new Section("/query", true))));
    assertThat(store.forPrincipal(analyst).sections())
        .containsExactly(new Section("/query", true));
  }

  @Test
  @DisplayName("one person's rail is theirs alone")
  void perPerson() {
    store.save(analyst, new Layout(List.of(new Section("/query", true))));
    assertThat(store.forPrincipal(owner).sections()).isNull();
  }

  @Test
  @DisplayName("nothing reaches the table without passing the check")
  void validatesOnSave() {
    assertThatThrownBy(
            () ->
                store.save(
                    analyst, new Layout(List.of(new Section("https://evil.example.test", true)))))
        .isInstanceOf(InvalidRailException.class);
    assertThat(store.forPrincipal(analyst).sections()).isNull();
  }

  @Test
  @DisplayName("reset forgets the arrangement, and removing the account removes it too")
  void resetAndCascade() {
    store.save(analyst, new Layout(List.of(new Section("/query", true))));
    assertThat(store.reset(analyst).sections()).isNull();
    assertThat(store.forPrincipal(analyst).sections()).isNull();

    store.save(owner, new Layout(List.of(new Section("/query", true))));
    jdbi.useHandle(handle -> handle.execute("DELETE FROM principal WHERE id = ?", owner));
    int left =
        jdbi.withHandle(
            handle -> handle.createQuery("SELECT count(*) FROM user_rail").mapTo(Integer.class).one());
    assertThat(left).isZero();
  }
}
