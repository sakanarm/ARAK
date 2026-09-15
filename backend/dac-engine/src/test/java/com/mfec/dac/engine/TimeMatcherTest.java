package com.mfec.dac.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.mfec.dac.schema.entity.policy.TimeRule;
import com.mfec.dac.schema.entity.policy.TimeWindow;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TimeMatcherTest {

  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

  /** 2026-09-15 is a Tuesday. */
  private static Instant bangkok(String date, String time) {
    return LocalDateTime.parse(date + "T" + time).atZone(BANGKOK).toInstant();
  }

  private static TimeWindow officeHours() {
    return new TimeWindow()
        .withDays(List.of("MON-FRI"))
        .withFrom("08:00")
        .withTo("18:00")
        .withTimezone("Asia/Bangkok");
  }

  private static TimeRule rule(TimeWindow... windows) {
    return new TimeRule().withWindows(List.of(windows));
  }

  @Test
  @DisplayName("inside the window on a permitted day")
  void insideWindow() {
    assertThat(TimeMatcher.matches(rule(officeHours()), bangkok("2026-09-15", "09:30"), BANGKOK))
        .isTrue();
  }

  @Test
  @DisplayName("the end of the window is exclusive")
  void endIsExclusive() {
    assertThat(TimeMatcher.matches(rule(officeHours()), bangkok("2026-09-15", "17:59"), BANGKOK))
        .isTrue();
    assertThat(TimeMatcher.matches(rule(officeHours()), bangkok("2026-09-15", "18:00"), BANGKOK))
        .isFalse();
  }

  @Test
  @DisplayName("the start of the window is inclusive")
  void startIsInclusive() {
    assertThat(TimeMatcher.matches(rule(officeHours()), bangkok("2026-09-15", "08:00"), BANGKOK))
        .isTrue();
    assertThat(TimeMatcher.matches(rule(officeHours()), bangkok("2026-09-15", "07:59"), BANGKOK))
        .isFalse();
  }

  @Test
  @DisplayName("a day outside the list is excluded even at the right hour")
  void weekendIsExcluded() {
    // 2026-09-19 is a Saturday.
    assertThat(TimeMatcher.matches(rule(officeHours()), bangkok("2026-09-19", "09:30"), BANGKOK))
        .isFalse();
  }

  @Test
  @DisplayName("the window is read in its own zone, not the server's")
  void zoneIsTheWindowsOwn() {
    // 03:00 UTC is 10:00 in Bangkok: inside the window even though the engine
    // default zone says otherwise.
    Instant threeAmUtc = LocalDateTime.parse("2026-09-15T03:00").atZone(ZoneId.of("UTC")).toInstant();
    assertThat(TimeMatcher.matches(rule(officeHours()), threeAmUtc, ZoneId.of("UTC"))).isTrue();
  }

  @Test
  @DisplayName("a window whose end is not after its start crosses midnight")
  void nightShiftCrossesMidnight() {
    TimeWindow night =
        new TimeWindow().withFrom("22:00").withTo("06:00").withTimezone("Asia/Bangkok");
    assertThat(TimeMatcher.matches(rule(night), bangkok("2026-09-15", "23:30"), BANGKOK)).isTrue();
    assertThat(TimeMatcher.matches(rule(night), bangkok("2026-09-16", "05:30"), BANGKOK)).isTrue();
    assertThat(TimeMatcher.matches(rule(night), bangkok("2026-09-16", "12:00"), BANGKOK)).isFalse();
  }

  @Test
  @DisplayName("a midnight-crossing window is judged on the day it opened")
  void nightShiftDayIsTheOpeningDay() {
    // A Friday night shift runs into Saturday morning; the Saturday hours belong
    // to Friday's shift, and Saturday night itself is not permitted.
    TimeWindow fridayNight =
        new TimeWindow()
            .withDays(List.of("FRI"))
            .withFrom("22:00")
            .withTo("06:00")
            .withTimezone("Asia/Bangkok");
    assertThat(TimeMatcher.matches(rule(fridayNight), bangkok("2026-09-18", "23:00"), BANGKOK))
        .isTrue();
    assertThat(TimeMatcher.matches(rule(fridayNight), bangkok("2026-09-19", "02:00"), BANGKOK))
        .isTrue();
    assertThat(TimeMatcher.matches(rule(fridayNight), bangkok("2026-09-19", "23:00"), BANGKOK))
        .isFalse();
  }

  @Test
  @DisplayName("day ranges expand, including ones that wrap the weekend")
  void dayRanges() {
    assertThat(TimeMatcher.parseDays(List.of("MON-FRI")))
        .containsExactlyInAnyOrder(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY);
    assertThat(TimeMatcher.parseDays(List.of("FRI-MON")))
        .containsExactlyInAnyOrder(
            DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY);
    assertThat(TimeMatcher.parseDays(List.of("SAT", "SUN")))
        .containsExactlyInAnyOrder(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
  }

  @Test
  @DisplayName("no rule at all places no restriction on the clock")
  void nullRuleMatches() {
    assertThat(TimeMatcher.matches(null, bangkok("2026-09-15", "03:00"), BANGKOK)).isTrue();
  }

  @Test
  @DisplayName("dates without windows restrict the calendar, not the clock")
  void datesWithoutWindows() {
    TimeRule dates =
        new TimeRule()
            .withValidFrom(LocalDate.parse("2026-01-01"))
            .withValidTo(LocalDate.parse("2026-12-31"));
    assertThat(TimeMatcher.matches(dates, bangkok("2026-09-15", "03:00"), BANGKOK)).isTrue();
    assertThat(TimeMatcher.matches(dates, bangkok("2027-01-02", "09:00"), BANGKOK)).isFalse();
  }

  @Test
  @DisplayName("an unreadable window is not an open one")
  void unreadableWindowIsClosed() {
    TimeWindow broken =
        new TimeWindow().withFrom("not a time").withTo("18:00").withTimezone("Asia/Bangkok");
    assertThat(TimeMatcher.matches(rule(broken), bangkok("2026-09-15", "09:00"), BANGKOK))
        .isFalse();
  }

  @Test
  @DisplayName("an unknown timezone falls back to the configured zone rather than throwing")
  void unknownZoneFallsBack() {
    TimeWindow window =
        new TimeWindow().withFrom("08:00").withTo("18:00").withTimezone("Mars/Olympus_Mons");
    assertThat(TimeMatcher.matches(rule(window), bangkok("2026-09-15", "09:00"), BANGKOK)).isTrue();
  }
}
