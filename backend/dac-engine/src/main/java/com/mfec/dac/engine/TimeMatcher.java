package com.mfec.dac.engine;

import com.mfec.dac.schema.entity.policy.TimeRule;
import com.mfec.dac.schema.entity.policy.TimeWindow;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether an instant falls inside a policy's time rule.
 *
 * <p>Every window carries its own IANA timezone because the schema requires one.
 * That is not ceremony: "business hours" for a policy written in Bangkok is a
 * different eight hours from one written in Frankfurt, and evaluating either in
 * server-local time makes access silently follow whichever datacentre the
 * request landed in. Daylight saving is handled by resolving the instant into
 * the window's own zone rather than by arithmetic on offsets.
 *
 * <p>The end of a window is exclusive, so 08:00-18:00 ends the moment the clock
 * reaches 18:00. A window whose end is not after its start is read as crossing
 * midnight - 22:00-06:00 is a night shift, not an empty set - and the day list
 * is then checked against the day the window opened on.
 */
public final class TimeMatcher {

  private TimeMatcher() {}

  /**
   * @param zone zone for the validFrom/validUntil dates, used only when no
   *     window declares one of its own
   */
  public static boolean matches(TimeRule rule, java.time.Instant at, ZoneId zone) {
    if (rule == null) {
      return true;
    }
    ZoneId dateZone = zone;
    List<TimeWindow> windows = rule.getWindows();
    if (windows != null && !windows.isEmpty() && windows.get(0).getTimezone() != null) {
      dateZone = safeZone(windows.get(0).getTimezone(), zone);
    }
    java.time.LocalDate today = at.atZone(dateZone).toLocalDate();

    if (rule.getValidFrom() != null && today.isBefore(rule.getValidFrom())) {
      return false;
    }
    if (rule.getValidTo() != null && today.isAfter(rule.getValidTo())) {
      return false;
    }
    if (windows == null || windows.isEmpty()) {
      // A rule with dates but no windows restricts the calendar, not the clock.
      return true;
    }
    for (TimeWindow window : windows) {
      if (inWindow(window, at, zone)) {
        return true;
      }
    }
    return false;
  }

  static boolean inWindow(TimeWindow window, java.time.Instant at, ZoneId fallbackZone) {
    if (window == null || window.getFrom() == null || window.getTo() == null) {
      return false;
    }
    ZoneId zone = safeZone(window.getTimezone(), fallbackZone);
    LocalTime from = parseTime(window.getFrom());
    LocalTime to = parseTime(window.getTo());
    if (from == null || to == null || zone == null) {
      // An unreadable window is not an open one.
      return false;
    }
    ZonedDateTime local = at.atZone(zone);
    LocalTime now = local.toLocalTime();

    if (to.isAfter(from)) {
      return !now.isBefore(from) && now.isBefore(to) && dayAllowed(window, local.getDayOfWeek());
    }
    // Crosses midnight: after the opening time today, or before the closing
    // time on a day that opened yesterday.
    if (!now.isBefore(from)) {
      return dayAllowed(window, local.getDayOfWeek());
    }
    if (now.isBefore(to)) {
      return dayAllowed(window, local.minusDays(1).getDayOfWeek());
    }
    return false;
  }

  /** An empty or absent day list means every day. */
  static boolean dayAllowed(TimeWindow window, DayOfWeek day) {
    List<String> days = window.getDays();
    if (days == null || days.isEmpty()) {
      return true;
    }
    return parseDays(days).contains(day);
  }

  /** Accepts single days and inclusive ranges: MON, SAT, MON-FRI, FRI-MON. */
  static Set<DayOfWeek> parseDays(List<String> days) {
    Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
    for (String entry : days) {
      if (entry == null) {
        continue;
      }
      String token = entry.trim().toUpperCase(Locale.ROOT);
      int dash = token.indexOf('-');
      if (dash < 0) {
        DayOfWeek single = day(token);
        if (single != null) {
          out.add(single);
        }
        continue;
      }
      DayOfWeek start = day(token.substring(0, dash).trim());
      DayOfWeek end = day(token.substring(dash + 1).trim());
      if (start == null || end == null) {
        continue;
      }
      // Walking forward handles a range that wraps the weekend, such as FRI-MON.
      DayOfWeek cursor = start;
      out.add(cursor);
      while (cursor != end) {
        cursor = cursor.plus(1);
        out.add(cursor);
      }
    }
    return out;
  }

  private static DayOfWeek day(String token) {
    if (token == null || token.length() < 3) {
      return null;
    }
    return switch (token.substring(0, 3)) {
      case "MON" -> DayOfWeek.MONDAY;
      case "TUE" -> DayOfWeek.TUESDAY;
      case "WED" -> DayOfWeek.WEDNESDAY;
      case "THU" -> DayOfWeek.THURSDAY;
      case "FRI" -> DayOfWeek.FRIDAY;
      case "SAT" -> DayOfWeek.SATURDAY;
      case "SUN" -> DayOfWeek.SUNDAY;
      default -> null;
    };
  }

  private static LocalTime parseTime(String value) {
    try {
      return LocalTime.parse(value.trim());
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static ZoneId safeZone(String id, ZoneId fallback) {
    if (id == null || id.isBlank()) {
      return fallback;
    }
    try {
      return ZoneId.of(id.trim());
    } catch (RuntimeException e) {
      // A zone the JVM does not know is a policy authoring error. Falling back
      // to the configured zone keeps evaluation deterministic; the alternative
      // is a policy that throws for every request.
      return fallback;
    }
  }
}
