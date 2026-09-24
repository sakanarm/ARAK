package com.mfec.dac.home;

import com.mfec.dac.home.HomeLayout.Layout;
import com.mfec.dac.home.HomeLayout.Preset;
import com.mfec.dac.home.HomeLayout.Widget;
import com.mfec.dac.home.HomeLayout.WidgetType;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * Turns a layout somebody posted into a layout that is safe to store and render.
 *
 * <p>This class is the whole security argument for the authored widgets, so it
 * is worth being explicit about what the threat is. A home page can hold HTML
 * and links that a person typed. That page is rendered inside a browser session
 * which, for some of the people who will open it, can call the policy API — it
 * can activate a policy, grant access, or register a data source. Script that
 * reaches a rendered dashboard therefore does not merely deface a page; it acts
 * as whoever is reading it. That is the reason for an allowlist rather than a
 * blocklist, and the reason nothing here tries to "remove the dangerous parts"
 * of what arrived: it rebuilds the content from the parts that are permitted and
 * discards everything else, including anything it did not recognise.
 *
 * <p>The same function runs on write and again on read. Cleaning on write alone
 * would be enough only if this table could never be written any other way, and
 * that is a property of today's code rather than of the schema. Cleaning a
 * single row on read costs microseconds and removes the assumption.
 *
 * <p>Rejection is loud rather than silent for the things a person chose — an
 * unusable video host is an error they can fix. Cleaning is silent for markup,
 * because there is no useful message to give somebody who pasted a page of a
 * website into a note field, and refusing the whole save would lose the rest of
 * their work.
 */
public class HomeLayoutValidator {

  /** Raised for a layout that cannot be repaired, with a message fit to show. */
  public static class InvalidLayoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidLayoutException(String message) {
      super(message);
    }
  }

  private static final int MAX_WIDGETS = 24;
  private static final int MAX_TITLE = 80;
  private static final int MAX_HTML = 20_000;
  private static final int MAX_NOTE = 4_000;
  private static final int MAX_LINKS = 12;
  private static final int MAX_URL = 2_000;

  /**
   * What an HTML widget may contain.
   *
   * <p>Built from {@code basicWithImages} — text, lists, quotes, links, images —
   * plus headings, rules and tables, which is what people actually put on a team
   * dashboard. Deliberately absent, and worth naming so that nobody adds them
   * back without meaning to:
   *
   * <ul>
   *   <li>{@code script}, {@code iframe}, {@code object}, {@code embed},
   *       {@code form}, {@code input} — each is a way to execute or to collect.
   *   <li>The {@code style} attribute and {@code <style>} — CSS can position an
   *       invisible overlay across the page and turn any click into a click on
   *       something else.
   *   <li>Every {@code on*} attribute; the safelist admits named attributes
   *       only, so these are gone by construction rather than by a pattern.
   * </ul>
   *
   * <p>Link and image URLs are held to {@code http}/{@code https}/{@code mailto}
   * by the safelist's protocol rules, which is what stops {@code javascript:} and
   * {@code data:} from arriving inside an {@code href}.
   */
  private static final Safelist HTML_SAFELIST =
      Safelist.basicWithImages()
          .addTags("h1", "h2", "h3", "h4", "h5", "h6", "hr", "table", "thead", "tbody", "tr", "th",
              "td", "caption", "figure", "figcaption")
          .addAttributes("table", "summary")
          .addAttributes("th", "colspan", "rowspan", "scope")
          .addAttributes("td", "colspan", "rowspan")
          .addAttributes("a", "target")
          // Opened in a new tab, so the dashboard is not replaced by whatever
          // was linked; `noopener` because a page opened with `target=_blank`
          // can otherwise navigate the opener via `window.opener`.
          .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer")
          .addEnforcedAttribute("a", "target", "_blank")
          .preserveRelativeLinks(false);

  /**
   * Hosts whose embed player may be framed.
   *
   * <p>An {@code iframe} runs another origin's page inside ours. It cannot read
   * this one, but it can navigate the top window, play audio, and show whatever
   * it likes in the space it was given — so the list is short, and it is a list
   * of hosts rather than of URLs somebody pasted. Anything outside it has to be
   * a video file, which is served through a {@code <video>} element and cannot
   * execute anything at all.
   */
  private static final Set<String> VIDEO_EMBED_HOSTS =
      Set.of(
          "youtube.com",
          "www.youtube.com",
          "youtu.be",
          "youtube-nocookie.com",
          "www.youtube-nocookie.com",
          "vimeo.com",
          "www.vimeo.com",
          "player.vimeo.com");

  private static final Set<String> VIDEO_FILE_SUFFIXES = Set.of(".mp4", ".webm", ".ogg", ".ogv");

  /**
   * Returns a clean copy of {@code layout}, or throws for one that cannot be.
   *
   * @throws InvalidLayoutException with a message meant for the person who saved
   */
  public Layout clean(Layout layout) {
    if (layout == null) {
      throw new InvalidLayoutException("No layout was supplied.");
    }
    Preset preset = layout.preset() == null ? Preset.WIDE_LEFT : layout.preset();
    List<Widget> incoming = layout.widgets() == null ? List.of() : layout.widgets();
    if (incoming.size() > MAX_WIDGETS) {
      throw new InvalidLayoutException(
          "A home page may hold at most " + MAX_WIDGETS + " widgets; this one has "
              + incoming.size() + ".");
    }

    Set<String> seen = new HashSet<>();
    List<Widget> cleaned = new ArrayList<>(incoming.size());
    for (Widget widget : incoming) {
      if (widget == null || widget.type() == null) {
        // Not an error worth stopping a save for: an empty slot is what a
        // half-finished client sends, and dropping it loses nothing.
        continue;
      }
      String id = widget.id() == null || widget.id().isBlank()
          ? UUID.randomUUID().toString()
          : trim(widget.id(), 64);
      // A duplicate id makes two widgets indistinguishable to the editor, which
      // shows up later as "deleting one deletes both".
      if (!seen.add(id)) {
        id = UUID.randomUUID().toString();
        seen.add(id);
      }
      int column = Math.max(0, Math.min(widget.column(), preset.columns() - 1));
      String title = widget.title() == null || widget.title().isBlank()
          ? null
          : trim(widget.title(), MAX_TITLE);
      cleaned.add(new Widget(id, widget.type(), column, title, config(widget)));
    }
    return new Layout(preset, cleaned);
  }

  // ------------------------------------------------------------ per-widget

  private Map<String, Object> config(Widget widget) {
    Map<String, Object> in =
        widget.config() == null ? Map.of() : widget.config();
    Map<String, Object> out = new LinkedHashMap<>();

    switch (widget.type()) {
      case HTML -> out.put("html", cleanHtml(string(in.get("html")), MAX_HTML));
      case NOTE -> out.put("text", trim(string(in.get("text")), MAX_NOTE));
      case LINKS -> out.put("links", cleanLinks(in.get("links")));
      case VIDEO -> {
        Video video = cleanVideo(string(in.get("url")));
        out.put("url", video.url());
        out.put("kind", video.kind());
        String caption = trim(string(in.get("caption")), MAX_TITLE);
        if (!caption.isBlank()) {
          out.put("caption", caption);
        }
      }
      case CHART_ASSETS_BY_TYPE,
          CHART_POLICIES_BY_STATE,
          CHART_POLICIES_BY_SCOPE,
          CHART_SOURCES_BY_MODE -> {
        // The only setting a chart carries is how it is drawn, and an
        // unrecognised value falls back rather than failing a save — a shape
        // nobody can name is not worth refusing somebody's dashboard over.
        String shape = string(in.get("shape")).toUpperCase(Locale.ROOT);
        out.put("shape", shape.equals("DONUT") || shape.equals("BARS") ? shape : "BARS");
      }
      case RECENT_POLICIES, SOURCES -> {
        int limit = integer(in.get("limit"), 5);
        out.put("limit", Math.max(3, Math.min(limit, 12)));
      }
      default -> {
        // Reading widgets carry no settings. Anything sent for them is dropped
        // rather than stored, so the row never holds a field nothing reads.
      }
    }
    return out;
  }

  /**
   * Rebuilds the fragment from permitted parts.
   *
   * <p>Truncation happens before cleaning, deliberately: cleaning first and
   * cutting after can slice through a tag and leave the browser to guess how to
   * close it, and a browser guessing about markup is the situation this whole
   * class exists to avoid. Jsoup will close whatever the truncation opened.
   */
  private String cleanHtml(String html, int max) {
    if (html == null || html.isBlank()) {
      return "";
    }
    String bounded = html.length() > max ? html.substring(0, max) : html;
    return Jsoup.clean(bounded, "", HTML_SAFELIST);
  }

  private List<Map<String, Object>> cleanLinks(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object item : list) {
      if (out.size() >= MAX_LINKS) {
        break;
      }
      if (!(item instanceof Map<?, ?> map)) {
        continue;
      }
      String url = trim(string(map.get("url")), MAX_URL);
      if (url.isBlank()) {
        continue;
      }
      String safe = webUrl(url);
      if (safe == null) {
        throw new InvalidLayoutException(
            "A link must be an http:// or https:// address. \"" + trim(url, 60)
                + "\" is not one.");
      }
      String label = trim(string(map.get("label")), MAX_TITLE);
      Map<String, Object> link = new LinkedHashMap<>();
      link.put("label", label.isBlank() ? safe : label);
      link.put("url", safe);
      String note = trim(string(map.get("note")), MAX_TITLE);
      if (!note.isBlank()) {
        link.put("note", note);
      }
      out.add(link);
    }
    return out;
  }

  private record Video(String url, String kind) {}

  /**
   * Resolves a pasted video address to something renderable, or refuses it.
   *
   * <p>A watch URL is rewritten to the embed URL here rather than in the
   * browser, so that what is stored is exactly what will be framed. Doing it on
   * the client would mean the stored value and the rendered value could differ,
   * and the one that was checked would be the wrong one.
   */
  private Video cleanVideo(String raw) {
    String url = trim(raw, MAX_URL);
    if (url.isBlank()) {
      throw new InvalidLayoutException("A video widget needs an address.");
    }
    String safe = webUrl(url);
    if (safe == null) {
      throw new InvalidLayoutException(
          "A video address must start with https:// (or http:// on this network).");
    }
    URI uri = URI.create(safe);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);

    for (String suffix : VIDEO_FILE_SUFFIXES) {
      if (path.endsWith(suffix)) {
        // A file, played by the browser's own player. No other origin's code
        // runs, so the host does not have to be on a list.
        return new Video(safe, "FILE");
      }
    }

    if (!VIDEO_EMBED_HOSTS.contains(host)) {
      throw new InvalidLayoutException(
          "Videos may be embedded from YouTube or Vimeo, or linked directly as an .mp4, .webm "
              + "or .ogg file. \"" + host + "\" is not one of those.");
    }
    String embed = toEmbed(host, uri);
    if (embed == null) {
      throw new InvalidLayoutException(
          "That looks like a " + host + " address, but not one with a video in it.");
    }
    return new Video(embed, "EMBED");
  }

  private String toEmbed(String host, URI uri) {
    String path = uri.getPath() == null ? "" : uri.getPath();
    if (host.endsWith("youtu.be")) {
      String id = strip(path);
      return id.isBlank() ? null : "https://www.youtube-nocookie.com/embed/" + id;
    }
    if (host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")) {
      if (path.startsWith("/embed/")) {
        String id = strip(path.substring("/embed/".length()));
        return id.isBlank() ? null : "https://www.youtube-nocookie.com/embed/" + id;
      }
      String id = queryParam(uri.getRawQuery(), "v");
      return id == null || id.isBlank()
          ? null
          : "https://www.youtube-nocookie.com/embed/" + strip(id);
    }
    if (host.endsWith("vimeo.com")) {
      String id = strip(path.startsWith("/video/") ? path.substring("/video/".length()) : path);
      return id.isBlank() ? null : "https://player.vimeo.com/video/" + id;
    }
    return null;
  }

  // --------------------------------------------------------------- helpers

  /**
   * The URL if it is an ordinary web address, else null.
   *
   * <p>Parsed rather than pattern-matched. A regular expression over a URL is
   * how {@code javascript:alert(1)//https://example.com} gets through: it
   * contains the string somebody was looking for.
   */
  private String webUrl(String raw) {
    try {
      URI uri = new URI(raw.trim());
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if (!scheme.equals("http") && !scheme.equals("https")) {
        return null;
      }
      if (uri.getHost() == null || uri.getHost().isBlank()) {
        return null;
      }
      return uri.toString();
    } catch (URISyntaxException | IllegalArgumentException e) {
      return null;
    }
  }

  /** The first path or id segment, without slashes or a trailing query. */
  private String strip(String value) {
    String out = value == null ? "" : value;
    while (out.startsWith("/")) {
      out = out.substring(1);
    }
    int cut = out.indexOf('/');
    if (cut >= 0) {
      out = out.substring(0, cut);
    }
    cut = out.indexOf('?');
    if (cut >= 0) {
      out = out.substring(0, cut);
    }
    // Whatever survives is put back into a URL we build, so it is held to the
    // alphabet video ids actually use rather than trusted because it was short.
    return out.replaceAll("[^A-Za-z0-9_-]", "");
  }

  private String queryParam(String query, String name) {
    if (query == null) {
      return null;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(name)) {
        return pair.substring(eq + 1);
      }
    }
    return null;
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static int integer(Object value, int fallback) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(string(value).trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static String trim(String value, int max) {
    String out = value == null ? "" : value.trim();
    return out.length() > max ? out.substring(0, max) : out;
  }
}
