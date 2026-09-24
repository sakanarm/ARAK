package com.mfec.dac.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.home.HomeLayout.Layout;
import com.mfec.dac.home.HomeLayout.Preset;
import com.mfec.dac.home.HomeLayout.Widget;
import com.mfec.dac.home.HomeLayout.WidgetType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a home page may and may not carry.
 *
 * <p>The HTML cases are written as attacks rather than as examples, because the
 * value of this class is entirely in what it refuses. Each one is a technique
 * that works against a naive filter: a tag the filter did not think of, an
 * attribute that executes, a URL scheme that is not a URL, and markup that
 * contains the very string a pattern-matching filter would have searched for.
 */
class HomeLayoutValidatorTest {

  private final HomeLayoutValidator validator = new HomeLayoutValidator();

  private static Layout with(WidgetType type, Map<String, Object> config) {
    return new Layout(
        Preset.WIDE_LEFT, List.of(new Widget("w1", type, 0, null, config)));
  }

  private String html(String raw) {
    Layout clean = validator.clean(with(WidgetType.HTML, Map.of("html", raw)));
    return String.valueOf(clean.widgets().get(0).config().get("html"));
  }

  @Nested
  @DisplayName("HTML widget")
  class Html {

    @Test
    @DisplayName("keeps the formatting somebody actually wanted")
    void keepsFormatting() {
      String out =
          html("<h2>Team notes</h2><p>Read the <strong>runbook</strong> before deploying.</p>"
              + "<ul><li>One</li><li>Two</li></ul>");
      assertThat(out).contains("<h2>Team notes</h2>");
      assertThat(out).contains("<strong>runbook</strong>");
      assertThat(out).contains("<li>One</li>");
    }

    @Test
    @DisplayName("drops a script tag and its contents")
    void dropsScript() {
      String out = html("<p>Hello</p><script>fetch('/api/v1/policies')</script>");
      assertThat(out).contains("Hello");
      assertThat(out).doesNotContain("script");
      assertThat(out).doesNotContain("fetch");
    }

    @Test
    @DisplayName("drops an event handler while keeping the element")
    void dropsEventHandler() {
      String out = html("<p onclick=\"alert(1)\">Click me</p>");
      assertThat(out).contains("Click me");
      assertThat(out).doesNotContain("onclick");
      assertThat(out).doesNotContain("alert");
    }

    @Test
    @DisplayName("drops an image whose error handler is the payload")
    void dropsImageOnError() {
      String out = html("<img src=x onerror=alert(1)>");
      assertThat(out).doesNotContain("onerror");
      assertThat(out).doesNotContain("alert");
    }

    @Test
    @DisplayName("refuses a javascript: href even when it also names an http URL")
    void dropsJavascriptHref() {
      // The classic defeat of a filter that searches for "https://" to decide
      // whether a link is safe.
      String out = html("<a href=\"javascript:fetch('https://example.com')\">Read</a>");
      assertThat(out).doesNotContain("javascript");
      assertThat(out).doesNotContain("fetch");
      assertThat(out).contains("Read");
    }

    @Test
    @DisplayName("drops an iframe, which is a page from somewhere else")
    void dropsIframe() {
      String out = html("<iframe src=\"https://example.com\"></iframe><p>after</p>");
      assertThat(out).doesNotContain("iframe");
      assertThat(out).contains("after");
    }

    @Test
    @DisplayName("drops a style attribute, which can cover the page")
    void dropsStyle() {
      String out =
          html("<p style=\"position:fixed;inset:0;opacity:0\">invisible overlay</p>");
      assertThat(out).doesNotContain("style");
      assertThat(out).doesNotContain("position");
    }

    @Test
    @DisplayName("drops a form that would collect what somebody types")
    void dropsForm() {
      String out =
          html("<form action=\"https://example.com\"><input name=\"password\"></form>");
      assertThat(out).doesNotContain("<form");
      assertThat(out).doesNotContain("<input");
    }

    @Test
    @DisplayName("forces a surviving link to open safely")
    void linkIsSafe() {
      String out = html("<a href=\"https://example.com/runbook\">Runbook</a>");
      assertThat(out).contains("https://example.com/runbook");
      assertThat(out).contains("rel=\"nofollow noopener noreferrer\"");
      assertThat(out).contains("target=\"_blank\"");
    }

    @Test
    @DisplayName("a data: URL in an image is not a way in")
    void dropsDataUri() {
      String out =
          html("<img src=\"data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\">");
      assertThat(out).doesNotContain("data:");
    }

    @Test
    @DisplayName("truncates before cleaning, so the result is still well formed")
    void truncates() {
      String out = html("<p>" + "x".repeat(30_000) + "</p>");
      assertThat(out.length()).isLessThan(21_000);
      assertThat(out).startsWith("<p>");
      assertThat(out).endsWith("</p>");
    }
  }

  @Nested
  @DisplayName("Video widget")
  class Video {

    private Map<String, Object> video(String url) {
      Layout clean = validator.clean(with(WidgetType.VIDEO, Map.of("url", url)));
      return clean.widgets().get(0).config();
    }

    @Test
    @DisplayName("rewrites a YouTube watch URL to the embed player")
    void youtubeWatch() {
      assertThat(video("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
          .containsEntry("url", "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ")
          .containsEntry("kind", "EMBED");
    }

    @Test
    @DisplayName("rewrites a short youtu.be link")
    void youtubeShort() {
      assertThat(video("https://youtu.be/dQw4w9WgXcQ?t=30"))
          .containsEntry("url", "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ");
    }

    @Test
    @DisplayName("rewrites a Vimeo link")
    void vimeo() {
      assertThat(video("https://vimeo.com/123456789"))
          .containsEntry("url", "https://player.vimeo.com/video/123456789");
    }

    @Test
    @DisplayName("accepts a plain video file from anywhere, because nothing runs")
    void file() {
      assertThat(video("https://example.com/onboarding.mp4"))
          .containsEntry("url", "https://example.com/onboarding.mp4")
          .containsEntry("kind", "FILE");
    }

    @Test
    @DisplayName("refuses to frame an arbitrary page")
    void refusesArbitraryHost() {
      assertThatThrownBy(() -> video("https://example.com/watch"))
          .isInstanceOf(HomeLayoutValidator.InvalidLayoutException.class)
          .hasMessageContaining("example.com");
    }

    @Test
    @DisplayName("refuses a javascript: address")
    void refusesJavascript() {
      assertThatThrownBy(() -> video("javascript:alert(1)"))
          .isInstanceOf(HomeLayoutValidator.InvalidLayoutException.class);
    }

    @Test
    @DisplayName("a host that merely ends with an allowed name is not that host")
    void refusesLookalikeHost() {
      // `evil-youtube.com` would pass a naive `endsWith("youtube.com")`.
      assertThatThrownBy(() -> video("https://evil-youtube.com/watch?v=abc"))
          .isInstanceOf(HomeLayoutValidator.InvalidLayoutException.class);
    }
  }

  @Nested
  @DisplayName("Link widget")
  class Links {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> links(List<Map<String, String>> raw) {
      Layout clean = validator.clean(with(WidgetType.LINKS, Map.of("links", raw)));
      return (List<Map<String, Object>>) clean.widgets().get(0).config().get("links");
    }

    @Test
    @DisplayName("keeps an ordinary link")
    void keepsLink() {
      assertThat(links(List.of(Map.of("label", "Runbook", "url", "https://example.com/rb"))))
          .singleElement()
          .satisfies(
              link -> {
                assertThat(link).containsEntry("label", "Runbook");
                assertThat(link).containsEntry("url", "https://example.com/rb");
              });
    }

    @Test
    @DisplayName("refuses a javascript: link with a message naming it")
    void refusesJavascript() {
      assertThatThrownBy(
              () -> links(List.of(Map.of("label", "Click", "url", "javascript:alert(1)"))))
          .isInstanceOf(HomeLayoutValidator.InvalidLayoutException.class)
          .hasMessageContaining("http");
    }

    @Test
    @DisplayName("caps how many there can be")
    void caps() {
      List<Map<String, String>> many = new ArrayList<>();
      for (int i = 0; i < 40; i++) {
        many.add(Map.of("label", "L" + i, "url", "https://example.com/" + i));
      }
      assertThat(links(many)).hasSize(12);
    }
  }

  @Nested
  @DisplayName("Layout structure")
  class Structure {

    @Test
    @DisplayName("pulls a widget back into a column the preset actually has")
    void clampsColumn() {
      Layout clean =
          validator.clean(
              new Layout(
                  Preset.SINGLE,
                  List.of(new Widget("w1", WidgetType.SOURCES, 7, null, Map.of()))));
      assertThat(clean.widgets().get(0).column()).isZero();
    }

    @Test
    @DisplayName("gives two widgets sharing an id distinct ones")
    void deduplicatesIds() {
      Layout clean =
          validator.clean(
              new Layout(
                  Preset.HALVES,
                  List.of(
                      new Widget("same", WidgetType.SOURCES, 0, null, Map.of()),
                      new Widget("same", WidgetType.PLATFORM, 1, null, Map.of()))));
      assertThat(clean.widgets().get(0).id()).isNotEqualTo(clean.widgets().get(1).id());
    }

    @Test
    @DisplayName("refuses a page with more widgets than anybody can read")
    void capsWidgets() {
      List<Widget> many = new ArrayList<>();
      for (int i = 0; i < 40; i++) {
        many.add(new Widget("w" + i, WidgetType.PLATFORM, 0, null, Map.of()));
      }
      assertThatThrownBy(() -> validator.clean(new Layout(Preset.SINGLE, many)))
          .isInstanceOf(HomeLayoutValidator.InvalidLayoutException.class);
    }

    @Test
    @DisplayName("drops settings a reading widget has no use for")
    void dropsStraySettings() {
      Layout clean =
          validator.clean(with(WidgetType.PLATFORM, Map.of("html", "<script>x</script>")));
      assertThat(clean.widgets().get(0).config()).isEmpty();
    }

    @Test
    @DisplayName("falls back to the shipped arrangement when nothing is chosen")
    void defaultsPreset() {
      Layout clean = validator.clean(new Layout(null, null));
      assertThat(clean.preset()).isEqualTo(Preset.WIDE_LEFT);
      assertThat(clean.widgets()).isEmpty();
    }
  }
}
