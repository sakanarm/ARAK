package com.mfec.dac.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.home.HomeLayout.Layout;
import com.mfec.dac.home.HomeLayout.LayoutView;
import com.mfec.dac.home.HomeLayout.Preset;
import com.mfec.dac.home.HomeLayout.Widget;
import com.mfec.dac.home.HomeLayout.WidgetType;
import java.util.List;
import java.util.Map;
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

/**
 * The round trip: what is stored, and what the browser is handed back.
 *
 * <p>Those are deliberately not the same document, and the difference is the
 * thing this file exists to hold in place. The row keeps every widget that was
 * saved, so an account that gains a role gets its old panels back; the reply is
 * filtered for the account that saved it, because the browser draws the reply
 * rather than re-reading. Getting that second half wrong is invisible in a unit
 * test of the filter and obvious to the person using it — the panel appears,
 * survives until the next reload, and then vanishes, which reads as the save
 * having failed.
 */
@Testcontainers
class HomeLayoutStoreIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static Jdbi jdbi;
  private HomeLayoutStore store;
  private UUID principal;

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
    store = new HomeLayoutStore(jdbi, new ObjectMapper(), new HomeLayoutValidator());
    principal =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        INSERT INTO principal (principal_type, username, source)
                        VALUES ('USER', 'analyst', 'local')
                        RETURNING id
                        """)
                    .mapTo(UUID.class)
                    .one());
  }

  private static Widget widget(String id, WidgetType type, int column, Map<String, Object> config) {
    return new Widget(id, type, column, null, config);
  }

  private static List<String> ids(Layout layout) {
    return layout.widgets().stream().map(Widget::id).toList();
  }

  @Test
  @DisplayName("a requester is handed back the page they will see on reload")
  void saveIsFilteredForTheCaller() {
    Layout posted =
        new Layout(
            Preset.HALVES,
            List.of(
                widget("mine", WidgetType.SEARCH, 0, Map.of()),
                widget("theirs", WidgetType.RECENT_POLICIES, 1, Map.of("limit", 6))));

    LayoutView saved = store.save(principal, posted, "analyst", false);

    assertThat(ids(saved.layout())).containsExactly("mine");
    // ...and a second read agrees with what the save replied, which is the
    // whole point: no widget that appears and then disappears.
    assertThat(ids(store.forPrincipal(principal, false).layout())).containsExactly("mine");
  }

  @Test
  @DisplayName("but the row keeps the widget, for the day the role changes")
  void theStoredRowIsNotFiltered() {
    store.save(
        principal,
        new Layout(
            Preset.HALVES,
            List.of(
                widget("mine", WidgetType.SEARCH, 0, Map.of()),
                widget("theirs", WidgetType.RECENT_POLICIES, 1, Map.of("limit", 6)))),
        "analyst",
        false);

    LayoutView asAuditor = store.forPrincipal(principal, true);

    assertThat(ids(asAuditor.layout())).containsExactly("mine", "theirs");
  }

  @Test
  @DisplayName("a governance account gets back exactly what it saved")
  void governanceSaveIsUntouched() {
    Layout posted =
        new Layout(
            Preset.THIRDS,
            List.of(
                widget("r", WidgetType.RECENT_POLICIES, 0, Map.of("limit", 6)),
                widget("s", WidgetType.SOURCES, 1, Map.of("limit", 5)),
                widget("n", WidgetType.NOTE, 2, Map.of("text", "mine"))));

    LayoutView saved = store.save(principal, posted, "admin", true);

    assertThat(saved.layout().preset()).isEqualTo(Preset.THIRDS);
    assertThat(ids(saved.layout())).containsExactly("r", "s", "n");
    assertThat(saved.isDefault()).isFalse();
    assertThat(saved.updatedBy()).isEqualTo("admin");
  }

  @Test
  @DisplayName("the markup is cleaned before it reaches the row, not on the way out")
  void hostileHtmlNeverReachesTheDatabase() {
    store.save(
        principal,
        new Layout(
            Preset.SINGLE,
            List.of(
                widget(
                    "h",
                    WidgetType.HTML,
                    0,
                    Map.of("html", "<p onclick=\"x()\">hi</p><script>alert(1)</script>")))),
        "analyst",
        false);

    // Read the raw column, not the store: a sanitiser that only cleaned on the
    // way out would still leave the payload sitting in the database for
    // whatever reads it next -- a report, an export, a future endpoint.
    String raw =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery("SELECT layout::text FROM home_layout WHERE principal_id = :pid")
                    .bind("pid", principal)
                    .mapTo(String.class)
                    .one());

    assertThat(raw).doesNotContain("script").doesNotContain("onclick").contains("hi");
  }

  @Test
  @DisplayName("resetting forgets the row and hands back the default")
  void resetRestoresTheDefault() {
    store.save(
        principal,
        new Layout(Preset.SINGLE, List.of(widget("n", WidgetType.NOTE, 0, Map.of("text", "x")))),
        "analyst",
        false);

    LayoutView afterReset = store.reset(principal, false);

    assertThat(afterReset.isDefault()).isTrue();
    assertThat(afterReset.layout()).isEqualTo(HomeLayoutStore.REQUESTER_DEFAULT);
    int rows =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery("SELECT count(*) FROM home_layout WHERE principal_id = :pid")
                    .bind("pid", principal)
                    .mapTo(Integer.class)
                    .one());
    assertThat(rows).isZero();
  }

  @Test
  @DisplayName("one account's page is not another's")
  void pagesAreNotShared() {
    UUID other =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        INSERT INTO principal (principal_type, username, source)
                        VALUES ('USER', 'somebody-else', 'local')
                        RETURNING id
                        """)
                    .mapTo(UUID.class)
                    .one());

    store.save(
        principal,
        new Layout(Preset.SINGLE, List.of(widget("n", WidgetType.NOTE, 0, Map.of("text", "x")))),
        "analyst",
        false);

    assertThat(store.forPrincipal(other, false).isDefault()).isTrue();
    assertThat(ids(store.forPrincipal(other, false).layout())).doesNotContain("n");
  }
}
