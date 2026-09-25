package com.mfec.dac.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.home.HomeLayout.Layout;
import com.mfec.dac.home.HomeLayout.Preset;
import com.mfec.dac.home.HomeLayout.Widget;
import com.mfec.dac.home.HomeLayout.WidgetType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which panels an account is offered, and what the defaults are made of.
 *
 * <p>This is the menu rather than the boundary: every endpoint behind a
 * governance panel answers any signed-in account on purpose, because somebody
 * who is refused data has to be able to see which policy refused them. So none
 * of these tests claims anything about what may be read. What they hold in
 * place is narrower and easier to break — that filtering a page never leaves
 * somebody with a blank screen, and never edits the row they had stored.
 */
class HomeLayoutStoreTest {

  private static Widget widget(String id, WidgetType type, int column) {
    return new Widget(id, type, column, null, Map.of());
  }

  @Nested
  @DisplayName("the role filter")
  class Filter {

    @Test
    @DisplayName("hands a governance reader exactly what was stored")
    void governanceReaderKeepsEverything() {
      Layout stored =
          new Layout(
              Preset.WIDE_LEFT,
              List.of(
                  widget("a", WidgetType.RECENT_POLICIES, 0),
                  widget("b", WidgetType.SEARCH, 1)));

      assertThat(HomeLayoutStore.forRole(stored, true)).isSameAs(stored);
    }

    @Test
    @DisplayName("drops only the governance panels, keeping order and preset")
    void requesterLosesGovernanceOnly() {
      Layout stored =
          new Layout(
              Preset.THIRDS,
              List.of(
                  widget("search", WidgetType.SEARCH, 0),
                  widget("recent", WidgetType.RECENT_POLICIES, 0),
                  widget("note", WidgetType.NOTE, 1),
                  widget("sources", WidgetType.SOURCES, 1),
                  widget("assets", WidgetType.CHART_ASSETS_BY_TYPE, 2)));

      Layout filtered = HomeLayoutStore.forRole(stored, false);

      assertThat(filtered.preset()).isEqualTo(Preset.THIRDS);
      assertThat(filtered.widgets())
          .extracting(Widget::id)
          .containsExactly("search", "note", "assets");
    }

    @Test
    @DisplayName("never edits the row it filtered")
    void leavesTheStoredLayoutAlone() {
      Layout stored =
          new Layout(
              Preset.HALVES,
              List.of(
                  widget("search", WidgetType.SEARCH, 0),
                  widget("sources", WidgetType.SOURCES, 1)));

      HomeLayoutStore.forRole(stored, false);

      // The point of filtering on read: an auditor demoted today and restored
      // tomorrow gets their page back, panels and all.
      assertThat(stored.widgets()).hasSize(2);
    }

    @Test
    @DisplayName("gives the default page rather than a blank one")
    void allGovernanceFallsBackToTheDefault() {
      Layout stored =
          new Layout(
              Preset.WIDE_LEFT,
              List.of(
                  widget("recent", WidgetType.RECENT_POLICIES, 0),
                  widget("sources", WidgetType.SOURCES, 1)));

      // A page emptied by filtering looks exactly like a broken deployment,
      // and the account has no way to tell the difference.
      assertThat(HomeLayoutStore.forRole(stored, false))
          .isEqualTo(HomeLayoutStore.REQUESTER_DEFAULT);
    }
  }

  @Nested
  @DisplayName("the defaults")
  class Defaults {

    @Test
    @DisplayName("the requester page survives its own filter")
    void requesterDefaultHoldsNoGovernanceWidget() {
      // If it did, filtering it would empty it, and the fallback above would
      // recurse into the same empty page.
      assertThat(HomeLayoutStore.REQUESTER_DEFAULT.widgets())
          .noneMatch(widget -> widget.type().governance());
      assertThat(HomeLayoutStore.forRole(HomeLayoutStore.REQUESTER_DEFAULT, false))
          .isEqualTo(HomeLayoutStore.REQUESTER_DEFAULT);
    }

    @Test
    @DisplayName("the requester page opens on search")
    void requesterDefaultLeadsWithSearch() {
      assertThat(HomeLayoutStore.REQUESTER_DEFAULT.preset()).isEqualTo(Preset.SINGLE);
      assertThat(HomeLayoutStore.REQUESTER_DEFAULT.widgets().get(0).type())
          .isEqualTo(WidgetType.SEARCH);
    }

    @Test
    @DisplayName("the governance page is the page that shipped, plus the access cards")
    void governanceDefaultIsUnchanged() {
      // Somebody who never opens the editor should not be able to tell that
      // one exists, so this default is pinned to what the hand-written page
      // held, in the column it held it in -- with the two access cards of M9
      // slice 2c each at the foot of the column that held its kind.
      assertThat(HomeLayoutStore.DEFAULT.preset()).isEqualTo(Preset.WIDE_LEFT);
      assertThat(HomeLayoutStore.DEFAULT.widgets())
          .extracting(Widget::type)
          .containsExactly(
              WidgetType.RECENT_POLICIES,
              WidgetType.GOVERNANCE_COVERAGE,
              WidgetType.EXPIRING_ACCESS,
              WidgetType.SOURCES,
              WidgetType.ACCESS_REQUEST_STATS,
              WidgetType.VOCABULARY,
              WidgetType.PLATFORM);
      assertThat(HomeLayoutStore.DEFAULT.widgets())
          .filteredOn(widget -> widget.column() == 0)
          .extracting(Widget::id)
          .containsExactly("recent-policies", "coverage", "expiring-access");
    }

    @Test
    @DisplayName("a requester's page warns them before their own access runs out")
    void requesterDefaultCarriesExpiringAccess() {
      assertThat(HomeLayoutStore.REQUESTER_DEFAULT.widgets())
          .extracting(Widget::type)
          .containsExactly(
              WidgetType.SEARCH,
              WidgetType.EXPIRING_ACCESS,
              WidgetType.VOCABULARY,
              WidgetType.CHART_ASSETS_BY_TYPE);
      // Grants ending soon are everybody's business about their own grants;
      // which tables other people ask for is not, so that card stays off.
      assertThat(WidgetType.EXPIRING_ACCESS.governance()).isFalse();
      assertThat(WidgetType.ACCESS_REQUEST_STATS.governance()).isTrue();
      assertThat(HomeLayoutStore.forRole(HomeLayoutStore.DEFAULT, false).widgets())
          .extracting(Widget::type)
          .contains(WidgetType.EXPIRING_ACCESS)
          .doesNotContain(WidgetType.ACCESS_REQUEST_STATS);
    }
  }
}
