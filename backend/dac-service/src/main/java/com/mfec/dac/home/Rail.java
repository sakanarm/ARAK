package com.mfec.dac.home;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The left-hand rail as one person arranged it: which sections, in what order.
 *
 * <p>A menu preference, not a permission. The console keeps only the entries
 * this account is offered and ignores the rest, so the server's job is limited
 * to refusing a document that is not a rail -- a path that is not a path, the
 * same section twice, or a list long enough to be something other than a menu.
 */
public final class Rail {

  private Rail() {}

  /** More than any console will have; enough to stop the column being used as storage. */
  public static final int MAX_SECTIONS = 40;

  /** A console route: lower-case segments, no query, no scheme, no dots. */
  private static final Pattern HREF = Pattern.compile("^/([a-z0-9-]+(/[a-z0-9-]+)*)?$");

  private static final int MAX_HREF = 64;

  public record Section(String href, Boolean shown) {}

  /** How large the rail is drawn. Absent reads as {@link #COMFORTABLE}, the rail as it always was. */
  public static final String COMFORTABLE = "comfortable";

  public static final String COMPACT = "compact";

  public record Layout(List<Section> sections, String density) {

    public Layout(List<Section> sections) {
      this(sections, null);
    }
  }

  /**
   * What the browser is handed. {@code sections} is null when this person has
   * never arranged their rail -- "draw the default", said without the server
   * having to know what the default is.
   */
  public record View(List<Section> sections, String density, Instant updatedAt) {

    public static View unarranged() {
      return new View(null, COMFORTABLE, null);
    }
  }

  public static final class InvalidRailException extends RuntimeException {
    public InvalidRailException(String message) {
      super(message);
    }
  }

  /**
   * Checks a posted rail and returns it clean.
   *
   * @throws InvalidRailException with a message written for the person who
   *     pressed Save
   */
  public static List<Section> validate(Layout layout) {
    if (layout == null || layout.sections() == null) {
      throw new InvalidRailException("No sections");
    }
    List<Section> posted = layout.sections();
    if (posted.size() > MAX_SECTIONS) {
      throw new InvalidRailException("A rail holds at most " + MAX_SECTIONS + " sections");
    }
    Set<String> seen = new HashSet<>();
    List<Section> clean = new ArrayList<>(posted.size());
    for (Section section : posted) {
      if (section == null || section.href() == null) {
        throw new InvalidRailException("Every section needs an address");
      }
      String href = section.href();
      if (href.length() > MAX_HREF || !HREF.matcher(href).matches()) {
        throw new InvalidRailException("Not a section of this console: " + abbreviate(href));
      }
      if (!seen.add(href)) {
        throw new InvalidRailException("Listed twice: " + href);
      }
      // Absent reads as shown: an entry is in the list because somebody put it there.
      clean.add(new Section(href, section.shown() == null || section.shown()));
    }
    return List.copyOf(clean);
  }

  /**
   * The size a posted rail asked for, or the default when it asked for none.
   *
   * @throws InvalidRailException for anything but the two sizes there are
   */
  public static String density(Layout layout) {
    String density = layout == null ? null : layout.density();
    if (density == null) {
      return COMFORTABLE;
    }
    if (!COMFORTABLE.equals(density) && !COMPACT.equals(density)) {
      throw new InvalidRailException("Unknown rail size: " + abbreviate(density));
    }
    return density;
  }

  private static String abbreviate(String href) {
    return href.length() <= MAX_HREF ? href : href.substring(0, MAX_HREF) + "…";
  }
}
