package com.mfec.dac.home;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The shapes of a personal home page (M12).
 *
 * <p>A layout is a preset plus an ordered list of widgets, each pinned to a
 * column. Order within a column is the order in the list, which means moving a
 * widget is a list operation and never a renumbering — there is no position
 * field to drift out of step with the array holding it.
 *
 * <p>Widget configuration is a free map rather than a field per widget type. The
 * alternative is a sealed hierarchy with a subtype per widget, which is the
 * right shape when the set is fixed and small; here every new widget would add a
 * class, a Jackson subtype registration and a migration for accounts that
 * already stored the old shape. The cost of the map is that nothing about it is
 * type-checked, which is exactly why {@link HomeLayoutValidator} exists and why
 * nothing reaches the database without going through it.
 */
public final class HomeLayout {

  private HomeLayout() {}

  /**
   * How many columns the page is divided into, and in what proportion.
   *
   * <p>Presets rather than free-form widths: a person arranging a dashboard is
   * choosing a shape, not authoring a grid, and four shapes cover what the
   * screens here actually hold. It also means the page cannot be arranged into
   * something that does not survive a narrow window, because the responsive
   * behaviour is written once per preset instead of derived from numbers
   * somebody typed.
   */
  public enum Preset {
    /** One full-width column. */
    SINGLE(1),
    /** Two equal columns. */
    HALVES(2),
    /** A wide column and a narrow one — what this page shipped with. */
    WIDE_LEFT(2),
    /** A narrow column and a wide one. */
    WIDE_RIGHT(2),
    /** Three equal columns. */
    THIRDS(3);

    private final int columns;

    Preset(int columns) {
      this.columns = columns;
    }

    public int columns() {
      return columns;
    }
  }

  /**
   * What a widget can be.
   *
   * <p>Split into three kinds by where their content comes from, because that is
   * what decides how each one has to be checked. The reading widgets render data
   * this platform already serves and carry no input at all. The charts render the
   * same data differently. The authored ones carry text somebody typed, and are
   * the only ones {@link HomeLayoutValidator} has real work to do on.
   */
  public enum WidgetType {
    // Finding things — the only widget that is a way in rather than a
    // readout, and the one a requester's page is built around.
    SEARCH,

    // Reading widgets — the panels this page already had.
    RECENT_POLICIES,
    GOVERNANCE_COVERAGE,
    SOURCES,
    VOCABULARY,
    PLATFORM,

    // Charts over the same data.
    CHART_ASSETS_BY_TYPE,
    CHART_POLICIES_BY_STATE,
    CHART_POLICIES_BY_SCOPE,
    CHART_SOURCES_BY_MODE,

    // Authored content.
    LINKS,
    HTML,
    VIDEO,
    NOTE;

    /**
     * Whether this widget is about governing data rather than using it.
     *
     * <p>A requester signs in to find a table and query it. Handing them a
     * stream of recent policies, a coverage meter and a list of registered
     * sources is not a security failure — every endpoint behind those panels
     * is readable by any signed-in account, on purpose, because a person has
     * to be able to see why they were refused. It is a usability one: it is a
     * dashboard about somebody else's job, opened every morning by somebody
     * who cannot act on any of it.
     *
     * <p>So this is the menu, not the boundary, in the same sense as the
     * navigation rail. Narrowing it does not narrow anyone's rights, and
     * widening it would not widen them.
     */
    public boolean governance() {
      return switch (this) {
        case RECENT_POLICIES,
                GOVERNANCE_COVERAGE,
                SOURCES,
                PLATFORM,
                CHART_POLICIES_BY_STATE,
                CHART_POLICIES_BY_SCOPE,
                CHART_SOURCES_BY_MODE ->
            true;
        default -> false;
      };
    }
  }

  /**
   * One widget on the page.
   *
   * @param id stable across saves so that React can key on it and so that a
   *     reorder is not indistinguishable from a delete-and-add
   * @param column which column it sits in, zero-based
   * @param title an override for the widget's own heading, or null to keep it
   * @param config type-specific settings, validated per type
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Widget(
      String id, WidgetType type, int column, String title, Map<String, Object> config) {}

  /** A whole page. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Layout(Preset preset, List<Widget> widgets) {}

  /**
   * A layout as served, with the provenance the console shows.
   *
   * @param isDefault true when this account has never saved one, so the console
   *     can say "this is the default" rather than implying somebody chose it
   */
  public record LayoutView(
      Layout layout, boolean isDefault, Instant updatedAt, String updatedBy) {}
}
