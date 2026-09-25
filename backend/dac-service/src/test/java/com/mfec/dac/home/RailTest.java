package com.mfec.dac.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.home.Rail.InvalidRailException;
import com.mfec.dac.home.Rail.Layout;
import com.mfec.dac.home.Rail.Section;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What a posted rail may and may not be. */
class RailTest {

  private static Layout layout(Section... sections) {
    return new Layout(Arrays.asList(sections));
  }

  @Test
  @DisplayName("keeps the order it was given, and reads a missing flag as shown")
  void keepsOrder() {
    List<Section> clean =
        Rail.validate(
            layout(
                new Section("/requests", true),
                new Section("/", false),
                new Section("/settings/system", null)));

    assertThat(clean)
        .containsExactly(
            new Section("/requests", true),
            new Section("/", false),
            new Section("/settings/system", true));
  }

  @Test
  @DisplayName("an empty list is a rail somebody chose to empty, not an error")
  void emptyIsAllowed() {
    assertThat(Rail.validate(layout())).isEmpty();
  }

  @Test
  @DisplayName("refuses a body with no list at all")
  void refusesNothing() {
    assertThatThrownBy(() -> Rail.validate(null)).isInstanceOf(InvalidRailException.class);
    assertThatThrownBy(() -> Rail.validate(new Layout(null)))
        .isInstanceOf(InvalidRailException.class);
    assertThatThrownBy(() -> Rail.validate(layout(new Section(null, true))))
        .isInstanceOf(InvalidRailException.class)
        .hasMessage("Every section needs an address");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "catalog",
        "/Catalog",
        "//evil.example.test",
        "https://evil.example.test/",
        "/../settings",
        "/catalog?x=1",
        "/catalog#top",
        "/catalog/",
        "javascript:alert(1)",
        "/cat alog",
        ""
      })
  @DisplayName("refuses anything that is not a console route")
  void refusesNonRoutes(String href) {
    assertThatThrownBy(() -> Rail.validate(layout(new Section(href, true))))
        .isInstanceOf(InvalidRailException.class)
        .hasMessageStartingWith("Not a section of this console");
  }

  @Test
  @DisplayName("refuses a long address without echoing all of it back")
  void refusesLongHref() {
    String href = "/" + "a".repeat(200);
    assertThatThrownBy(() -> Rail.validate(layout(new Section(href, true))))
        .isInstanceOf(InvalidRailException.class)
        .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(120));
  }

  @Test
  @DisplayName("refuses the same section twice")
  void refusesDuplicates() {
    assertThatThrownBy(
            () -> Rail.validate(layout(new Section("/query", true), new Section("/query", false))))
        .isInstanceOf(InvalidRailException.class)
        .hasMessage("Listed twice: /query");
  }

  @Test
  @DisplayName("the size defaults to the rail as it always was, and is one of two")
  void density() {
    assertThat(Rail.density(layout())).isEqualTo(Rail.COMFORTABLE);
    assertThat(Rail.density(new Layout(List.of(), "compact"))).isEqualTo(Rail.COMPACT);
    assertThat(Rail.density(new Layout(List.of(), "comfortable"))).isEqualTo(Rail.COMFORTABLE);
    for (String odd : List.of("Compact", "tiny", "", "x".repeat(200))) {
      assertThatThrownBy(() -> Rail.density(new Layout(List.of(), odd)))
          .isInstanceOf(InvalidRailException.class)
          .hasMessageStartingWith("Unknown rail size")
          .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(120));
    }
  }

  @Test
  @DisplayName("refuses a list longer than any menu")
  void refusesTooMany() {
    List<Section> many = new ArrayList<>();
    for (int i = 0; i <= Rail.MAX_SECTIONS; i++) {
      many.add(new Section("/s" + i, true));
    }
    assertThatThrownBy(() -> Rail.validate(new Layout(many)))
        .isInstanceOf(InvalidRailException.class);
    assertThat(Rail.validate(new Layout(many.subList(0, Rail.MAX_SECTIONS))))
        .hasSize(Rail.MAX_SECTIONS);
  }
}
