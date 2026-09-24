package com.mfec.dac.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.home.HomeLayout.Layout;
import com.mfec.dac.home.HomeLayout.LayoutSource;
import com.mfec.dac.home.HomeLayout.LayoutView;
import com.mfec.dac.home.HomeLayout.Persona;
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
    // home_role_layout is keyed on a role name, not on a principal, so the
    // CASCADE above does not reach it and a persona saved by one test would
    // otherwise decide what the next test's caller is served.
    jdbi.useHandle(handle -> handle.execute("TRUNCATE home_role_layout"));
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
    assertThat(ids(store.forPrincipal(principal, false, List.of()).layout())).containsExactly("mine");
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

    LayoutView asAuditor = store.forPrincipal(principal, true, List.of());

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

    LayoutView afterReset = store.reset(principal, false, List.of());

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

    assertThat(store.forPrincipal(other, false, List.of()).isDefault()).isTrue();
    assertThat(ids(store.forPrincipal(other, false, List.of()).layout())).doesNotContain("n");
  }

  // ------------------------------------------------------------- personas

  private static Layout page(String widgetId) {
    return new Layout(
        Preset.SINGLE, List.of(widget(widgetId, WidgetType.NOTE, 0, Map.of("text", "x"))));
  }

  @Test
  @DisplayName("a role's page is served to somebody who has not arranged their own")
  void aPersonaIsServedUntilSomebodyArrangesTheirOwn() {
    store.savePersona(Persona.REQUESTER, page("from-persona"), "admin");

    LayoutView served = store.forPrincipal(principal, false, List.of(Persona.REQUESTER));

    assertThat(served.source()).isEqualTo(LayoutSource.ROLE);
    assertThat(served.sourceRole()).isEqualTo(Persona.REQUESTER);
    assertThat(served.isDefault()).isTrue();
    assertThat(served.updatedBy()).isEqualTo("admin");
    assertThat(ids(served.layout())).containsExactly("from-persona");
  }

  @Test
  @DisplayName("a personal page beats the role's, and an administrator cannot take it back")
  void aPersonalPageIsNeverOverwrittenByAPersona() {
    store.save(principal, page("mine"), "analyst", false);

    // The administrator arranges the role afterwards, which is the order that
    // would break this if the persona were copied into home_layout instead of
    // being consulted at read time.
    store.savePersona(Persona.REQUESTER, page("from-persona"), "admin");

    LayoutView served = store.forPrincipal(principal, false, List.of(Persona.REQUESTER));

    assertThat(served.source()).isEqualTo(LayoutSource.PERSONAL);
    assertThat(served.sourceRole()).isNull();
    assertThat(ids(served.layout())).containsExactly("mine");
  }

  @Test
  @DisplayName("the strongest arranged role wins, and an unarranged one is fallen through")
  void theStrongestArrangedRoleWins() {
    store.savePersona(Persona.AUDITOR, page("auditor-page"), "admin");
    store.savePersona(Persona.REQUESTER, page("requester-page"), "admin");

    // Holds both. AUDITOR is declared first, so it decides.
    LayoutView both =
        store.forPrincipal(principal, true, List.of(Persona.AUDITOR, Persona.REQUESTER));
    assertThat(both.sourceRole()).isEqualTo(Persona.AUDITOR);
    assertThat(ids(both.layout())).containsExactly("auditor-page");

    // Holds a stronger role that nobody has arranged. Falling through matters:
    // stopping at the strongest held role would mean arranging the requester
    // page had no effect on the requesters who also audit.
    LayoutView fellThrough =
        store.forPrincipal(principal, true, List.of(Persona.PLATFORM_ADMIN, Persona.REQUESTER));
    assertThat(fellThrough.sourceRole()).isEqualTo(Persona.REQUESTER);
    assertThat(ids(fellThrough.layout())).containsExactly("requester-page");
  }

  @Test
  @DisplayName("no arranged role leaves the built-in page")
  void withoutAPersonaThePageIsTheBuiltInOne() {
    LayoutView served = store.forPrincipal(principal, true, List.of(Persona.AUDITOR));

    assertThat(served.source()).isEqualTo(LayoutSource.BUILT_IN);
    assertThat(served.sourceRole()).isNull();
    assertThat(served.layout()).isEqualTo(HomeLayoutStore.DEFAULT);
  }

  @Test
  @DisplayName("resetting lands on the role's page, not on the built-in one")
  void resetLandsOnTheRolePageWhenThereIsOne() {
    store.savePersona(Persona.REQUESTER, page("from-persona"), "admin");
    store.save(principal, page("mine"), "analyst", false);

    LayoutView afterReset = store.reset(principal, false, List.of(Persona.REQUESTER));

    assertThat(afterReset.source()).isEqualTo(LayoutSource.ROLE);
    assertThat(ids(afterReset.layout())).containsExactly("from-persona");
  }

  @Test
  @DisplayName("the editor is offered all five roles, arranged or not")
  void everyPersonaComesBackArrangedOrNot() {
    store.savePersona(Persona.AUDITOR, page("auditor-page"), "admin");

    List<HomeLayout.PersonaLayout> all = store.personas();

    assertThat(all).hasSize(Persona.values().length);
    assertThat(all.stream().map(HomeLayout.PersonaLayout::role).toList())
        .containsExactly(Persona.values());
    assertThat(all.stream().filter(HomeLayout.PersonaLayout::configured).toList()).hasSize(1);

    HomeLayout.PersonaLayout requester =
        all.stream().filter(row -> row.role() == Persona.REQUESTER).findFirst().orElseThrow();
    assertThat(requester.configured()).isFalse();
    // Not an empty canvas: the built-in page those people see today.
    assertThat(requester.layout()).isEqualTo(HomeLayoutStore.REQUESTER_DEFAULT);
    assertThat(requester.updatedAt()).isNull();
  }

  @Test
  @DisplayName("forgetting a persona returns those people to the built-in page")
  void resettingAPersonaReturnsPeopleToTheBuiltIn() {
    store.savePersona(Persona.REQUESTER, page("from-persona"), "admin");

    HomeLayout.PersonaLayout forgotten = store.resetPersona(Persona.REQUESTER);

    assertThat(forgotten.configured()).isFalse();
    assertThat(forgotten.layout()).isEqualTo(HomeLayoutStore.REQUESTER_DEFAULT);
    assertThat(store.forPrincipal(principal, false, List.of(Persona.REQUESTER)).source())
        .isEqualTo(LayoutSource.BUILT_IN);
  }

  @Test
  @DisplayName("an administrator's markup is cleaned before it reaches anybody else")
  void aPersonaIsCleanedLikeAnyOtherLayout() {
    store.savePersona(
        Persona.AUDITOR,
        new Layout(
            Preset.SINGLE,
            List.of(
                widget(
                    "h",
                    WidgetType.HTML,
                    0,
                    Map.of("html", "<p>hello</p><script>steal()</script>")))),
        "admin");

    // Read back by somebody else, which is the path that matters: this is the
    // one layout in the product written by one person and rendered to another.
    LayoutView served = store.forPrincipal(principal, true, List.of(Persona.AUDITOR));
    String html = (String) served.layout().widgets().get(0).config().get("html");

    assertThat(html).contains("hello");
    assertThat(html).doesNotContain("script");
  }
}
