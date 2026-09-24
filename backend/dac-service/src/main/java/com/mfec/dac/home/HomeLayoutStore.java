package com.mfec.dac.home;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.home.HomeLayout.Layout;
import com.mfec.dac.home.HomeLayout.LayoutView;
import com.mfec.dac.home.HomeLayout.Persona;
import com.mfec.dac.home.HomeLayout.PersonaLayout;
import com.mfec.dac.home.HomeLayout.Preset;
import com.mfec.dac.home.HomeLayout.Widget;
import com.mfec.dac.home.HomeLayout.WidgetType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;

/**
 * Reads and writes one account's home page.
 *
 * <p>Absence is the default, not an error: an account that has never arranged
 * its page gets {@link #DEFAULT}, which is the arrangement this page shipped
 * with. Keeping the default in code rather than seeding a row per account means
 * a widget added next month appears for everybody who never customised, instead
 * of only for accounts created after it existed.
 */
public class HomeLayoutStore {

  /**
   * The page as it was before anybody could rearrange it.
   *
   * <p>Deliberately the same content and the same order as the hand-written page
   * this replaced. Somebody who never opens the editor should not be able to
   * tell that one exists.
   */
  public static final Layout DEFAULT =
      new Layout(
          Preset.WIDE_LEFT,
          List.of(
              widget("recent-policies", WidgetType.RECENT_POLICIES, 0, Map.of("limit", 5)),
              widget("coverage", WidgetType.GOVERNANCE_COVERAGE, 0, Map.of()),
              widget("sources", WidgetType.SOURCES, 1, Map.of("limit", 5)),
              widget("vocabulary", WidgetType.VOCABULARY, 1, Map.of()),
              widget("platform", WidgetType.PLATFORM, 1, Map.of())));

  /**
   * The page for somebody who came here to find data, not to govern it.
   *
   * <p>Search first and full width, because that is the whole errand: a
   * requester knows a table name or a tag and wants the asset page. What
   * follows is the vocabulary they would browse by when they do not know the
   * name, and the shape of the catalogue they are searching — nothing that
   * reports on policies they cannot write or sources they cannot register.
   *
   * <p>It is still only a default. Every widget stays available in the
   * editor for the roles that may hold it, so an auditor who prefers the
   * search-led page can have it and keep their coverage meter.
   */
  public static final Layout REQUESTER_DEFAULT =
      new Layout(
          Preset.SINGLE,
          List.of(
              widget("search", WidgetType.SEARCH, 0, Map.of()),
              widget("vocabulary", WidgetType.VOCABULARY, 0, Map.of()),
              widget(
                  "assets-by-type",
                  WidgetType.CHART_ASSETS_BY_TYPE,
                  0,
                  Map.of("shape", "BARS"))));

  private static Widget widget(String id, WidgetType type, int column, Map<String, Object> config) {
    return new Widget(id, type, column, null, config);
  }

  private final Jdbi jdbi;
  private final ObjectMapper json;
  private final HomeLayoutValidator validator;

  public HomeLayoutStore(Jdbi jdbi, ObjectMapper json, HomeLayoutValidator validator) {
    this.jdbi = jdbi;
    this.json = json;
    this.validator = validator;
  }

  /**
   * The account's page: its own, else its role's, else the built-in one.
   *
   * <p>Three sources in that order, and the reply says which one it came from.
   * A personal arrangement wins over anything an administrator set, always: the
   * persona is where somebody starts, and an administrator retouching it must
   * not rearrange the page of someone who already made it theirs. That is the
   * difference between a default and a policy, and this one is a default.
   *
   * @param governanceReader whether this account is offered the widgets that
   *     report on policy, sources and coverage — see
   *     {@link WidgetType#governance()} for why this is a matter of what is
   *     worth showing rather than of what may be read
   * @param personas the account's roles as personas, strongest first, from
   *     {@link Persona#heldBy}. Passed in rather than looked up here because
   *     the roles are already on the request and a second read of them could
   *     disagree with the one authorisation used.
   */
  public LayoutView forPrincipal(
      UUID principalId, boolean governanceReader, List<Persona> personas) {
    Optional<LayoutView> own =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT layout::text AS layout, updated_at, updated_by
                          FROM home_layout
                         WHERE principal_id = :pid
                        """)
                    .bind("pid", principalId)
                    .map(
                        (rs, ctx) ->
                            LayoutView.personal(
                                // Cleaned on the way out as well as on the way
                                // in. See HomeLayoutValidator for why that is
                                // not belt-and-braces.
                                forRole(
                                    validator.clean(parse(rs.getString("layout"))),
                                    governanceReader),
                                rs.getTimestamp("updated_at").toInstant(),
                                rs.getString("updated_by")))
                    .findOne());
    return own.orElseGet(() -> inheritedFor(governanceReader, personas));
  }

  /**
   * The page for somebody who has never arranged one.
   *
   * <p>Split out because {@link #reset} has to answer the same question: after
   * forgetting an arrangement, what a person sees is whatever they would have
   * seen had they never made one, and working that out in two places is how the
   * two answers drift.
   */
  private LayoutView inheritedFor(boolean governanceReader, List<Persona> personas) {
    for (Persona persona : personas == null ? List.<Persona>of() : personas) {
      Optional<LayoutView> arranged =
          jdbi.withHandle(
              handle ->
                  handle
                      .createQuery(
                          """
                          SELECT layout::text AS layout, updated_at, updated_by
                            FROM home_role_layout
                           WHERE app_role = :role
                          """)
                      .bind("role", persona.roleName())
                      .map(
                          (rs, ctx) ->
                              LayoutView.ofRole(
                                  forRole(
                                      validator.clean(parse(rs.getString("layout"))),
                                      governanceReader),
                                  persona,
                                  rs.getTimestamp("updated_at").toInstant(),
                                  rs.getString("updated_by")))
                      .findOne());
      if (arranged.isPresent()) {
        return arranged.get();
      }
    }
    return LayoutView.builtIn(governanceReader ? DEFAULT : REQUESTER_DEFAULT);
  }

  /**
   * The page this product ships with for one role.
   *
   * <p>What the persona editor opens on when nobody has arranged that role yet,
   * so the administrator starts from the page those people actually see rather
   * than from an empty canvas they would have to rebuild before they could
   * change one thing about it.
   */
  public static Layout builtInFor(Persona persona) {
    return persona == Persona.REQUESTER ? REQUESTER_DEFAULT : DEFAULT;
  }

  /**
   * Drops the widgets this account is not offered.
   *
   * <p>Applied on the way out as well as on the way in, for the same reason
   * the validator cleans twice: a row written before somebody changed roles
   * would otherwise keep serving panels the editor no longer offers, and the
   * first thing that account saved would silently delete them anyway.
   * Dropping a widget never touches the stored row, so a requester promoted
   * to auditor tomorrow gets their old page back intact.
   */
  // Package-private rather than private: this is the whole of the role
  // filtering, and testing it through the store would mean standing up a
  // database to assert a property of a list.
  static Layout forRole(Layout layout, boolean governanceReader) {
    if (governanceReader) {
      return layout;
    }
    List<Widget> kept =
        layout.widgets().stream().filter(w -> !w.type().governance()).toList();
    // An account whose whole page was governance widgets gets the default
    // rather than a blank screen that looks like a broken deployment.
    return kept.isEmpty() ? REQUESTER_DEFAULT : new Layout(layout.preset(), kept);
  }

  /**
   * Writes the account's page, cleaned.
   *
   * <p>What is stored is the whole cleaned layout; what comes back is that
   * layout filtered for the account, which is not the same thing. It matters
   * because the browser renders the reply rather than re-reading: a page saved
   * with a widget this account is not offered would otherwise show it until the
   * next reload and then lose it, which reads as the save having failed. The
   * stored row keeps the widget, so an account that gains the role later gets
   * it back.
   */
  public LayoutView save(
      UUID principalId, Layout layout, String actor, boolean governanceReader) {
    Layout clean = validator.clean(layout);
    String document = write(clean);
    Instant now =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        INSERT INTO home_layout (principal_id, layout, updated_by)
                        VALUES (:pid, CAST(:layout AS jsonb), :actor)
                        ON CONFLICT (principal_id) DO UPDATE
                           SET layout     = EXCLUDED.layout,
                               updated_at = now(),
                               updated_by = EXCLUDED.updated_by
                        RETURNING updated_at
                        """)
                    .bind("pid", principalId)
                    .bind("layout", document)
                    .bind("actor", actor)
                    .mapTo(Instant.class)
                    .one());
    return LayoutView.personal(forRole(clean, governanceReader), now, actor);
  }

  /**
   * Forgets the account's arrangement, which restores whatever it inherits.
   *
   * <p>Which may be a persona rather than the built-in page, and the reply says
   * so. Somebody pressing Reset after their administrator arranged the auditor
   * page should land on that page, not on the one the product shipped with a
   * year ago.
   */
  public LayoutView reset(UUID principalId, boolean governanceReader, List<Persona> personas) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate("DELETE FROM home_layout WHERE principal_id = :pid")
                .bind("pid", principalId)
                .execute());
    return inheritedFor(governanceReader, personas);
  }

  // ------------------------------------------------------------- personas

  /**
   * Every persona, arranged or not, for the administrator's editor.
   *
   * <p>All five rather than the rows that exist, because the editor's job is to
   * answer "what does a data owner see" and the answer is a page whether or not
   * anybody has touched it. An unarranged persona comes back with
   * {@code configured = false} and the built-in page, which is the honest
   * answer to that question.
   *
   * <p>Not filtered by {@link #forRole}: this is the page as it will be stored,
   * seen by an administrator who holds every role. Filtering it here would show
   * them a page nobody will actually be served.
   */
  public List<PersonaLayout> personas() {
    List<PersonaLayout> rows =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT app_role, layout::text AS layout, updated_at, updated_by
                          FROM home_role_layout
                        """)
                    .map(
                        (rs, ctx) ->
                            new PersonaLayout(
                                Persona.valueOf(rs.getString("app_role")),
                                validator.clean(parse(rs.getString("layout"))),
                                true,
                                rs.getTimestamp("updated_at").toInstant(),
                                rs.getString("updated_by")))
                    .list());
    Map<Persona, PersonaLayout> arranged = new EnumMap<>(Persona.class);
    for (PersonaLayout row : rows) {
      arranged.put(row.role(), row);
    }
    List<PersonaLayout> all = new ArrayList<>(Persona.values().length);
    for (Persona persona : Persona.values()) {
      PersonaLayout row = arranged.get(persona);
      all.add(
          row != null
              ? row
              : new PersonaLayout(persona, builtInFor(persona), false, null, null));
    }
    return all;
  }

  /**
   * Arranges the page for one role.
   *
   * <p>Cleaned like any other layout, and for a sharper reason: this is the one
   * layout written by one person and rendered to another. Nothing about the
   * writer holding PLATFORM_ADMIN makes markup safe — an administrator's
   * session is the most valuable one to steal, and a persona is the only way
   * this product will put authored HTML in front of somebody who did not type
   * it.
   */
  public PersonaLayout savePersona(Persona persona, Layout layout, String actor) {
    Layout clean = validator.clean(layout);
    String document = write(clean);
    Instant now =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        INSERT INTO home_role_layout (app_role, layout, updated_by)
                        VALUES (:role, CAST(:layout AS jsonb), :actor)
                        ON CONFLICT (app_role) DO UPDATE
                           SET layout     = EXCLUDED.layout,
                               updated_at = now(),
                               updated_by = EXCLUDED.updated_by
                        RETURNING updated_at
                        """)
                    .bind("role", persona.roleName())
                    .bind("layout", document)
                    .bind("actor", actor)
                    .mapTo(Instant.class)
                    .one());
    return new PersonaLayout(persona, clean, true, now, actor);
  }

  /**
   * Forgets the arrangement for one role.
   *
   * <p>Which returns those people to the built-in page, and leaves everybody
   * who arranged their own alone — they were never reading this row.
   */
  public PersonaLayout resetPersona(Persona persona) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate("DELETE FROM home_role_layout WHERE app_role = :role")
                .bind("role", persona.roleName())
                .execute());
    return new PersonaLayout(persona, builtInFor(persona), false, null, null);
  }

  /** Whether this account has arranged its own page. */
  public boolean isCustomised(UUID principalId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT 1 FROM home_layout WHERE principal_id = :pid")
                .bind("pid", principalId)
                .mapTo(Integer.class)
                .findOne()
                .isPresent());
  }

  // ---------------------------------------------------------------- codec

  private Layout parse(String document) {
    try {
      return json.readValue(document, Layout.class);
    } catch (Exception e) {
      // A row this process cannot read is not a reason to give somebody a blank
      // page every morning. The default is always renderable, and the editor
      // will overwrite the unreadable row the next time they save.
      return DEFAULT;
    }
  }

  private String write(Layout layout) {
    try {
      return json.writeValueAsString(layout);
    } catch (Exception e) {
      throw new HomeLayoutValidator.InvalidLayoutException("That layout could not be stored.");
    }
  }

  /** Present so callers can ask for the default without reaching for a field. */
  public Optional<Layout> defaults() {
    return Optional.of(DEFAULT);
  }
}
