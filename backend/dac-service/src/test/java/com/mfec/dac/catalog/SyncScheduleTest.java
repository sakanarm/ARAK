package com.mfec.dac.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the schedule accepts, and when it books.
 *
 * <p>Both halves matter for the same reason: this value is handed to a
 * scheduler that rebooks itself forever. A bad zone stored once would throw on
 * every rebooking for the life of the process, and a booking computed in the
 * wrong direction fires immediately and crawls the whole estate at midday.
 */
class SyncScheduleTest {

  private static SyncScheduleStore.Schedule at(String time, String zone) {
    return new SyncScheduleStore.Schedule(true, LocalTime.parse(time), ZoneId.of(zone), null, null);
  }

  @Nested
  @DisplayName("the time of day")
  class Time {

    @Test
    void takesHoursAndMinutes() {
      assertThat(SyncScheduleStore.parseTime("02:30")).isEqualTo(LocalTime.of(2, 30));
    }

    @Test
    @DisplayName("accepts the seconds a browser adds, and drops them")
    void dropsSeconds() {
      // Chrome sends 02:30 and Firefox has been seen to send 02:30:00 from the
      // same control. Refusing one of them makes the screen work on one
      // machine and not the next, which is the worst kind of bug to be told
      // about second-hand.
      assertThat(SyncScheduleStore.parseTime("02:30:45")).isEqualTo(LocalTime.of(2, 30));
    }

    @Test
    void refusesNonsense() {
      assertThatThrownBy(() -> SyncScheduleStore.parseTime("half two"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("02:30");
    }

    @Test
    void refusesBlank() {
      assertThatThrownBy(() -> SyncScheduleStore.parseTime("  "))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("the zone")
  class Zone {

    @Test
    void takesARegion() {
      assertThat(SyncScheduleStore.parseZone("Asia/Bangkok")).isEqualTo(ZoneId.of("Asia/Bangkok"));
    }

    @Test
    @DisplayName("refuses a fixed offset, which would be wrong after the next rule change")
    void refusesAnOffset() {
      // "+07:00" parses perfectly well as a ZoneId, which is exactly why it has
      // to be refused here rather than left to ZoneId.of: it would be correct
      // today and silently wrong the first time the region moved its clocks,
      // and the whole point of storing a local time is to survive that.
      assertThatThrownBy(() -> SyncScheduleStore.parseZone("+07:00"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Asia/Bangkok");
    }

    @Test
    void refusesATypo() {
      assertThatThrownBy(() -> SyncScheduleStore.parseZone("Asia/Bangcock"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("the next booking")
  class Booking {

    @Test
    @DisplayName("is always in the future")
    void neverInThePast() {
      // Whatever hour is set and whenever this runs, the delay has to be
      // positive. A zero or negative delay makes the executor fire at once,
      // and a full crawl at 11am is the failure this job exists to avoid.
      for (int hour = 0; hour < 24; hour++) {
        Duration delay = NightlyReconcile.untilNext(at(String.format("%02d:00", hour), "UTC"));
        assertThat(delay).isPositive();
      }
    }

    @Test
    @DisplayName("is within a day")
    void withinADay() {
      for (int hour = 0; hour < 24; hour++) {
        Duration delay = NightlyReconcile.untilNext(at(String.format("%02d:17", hour), "UTC"));
        assertThat(delay).isLessThanOrEqualTo(Duration.ofHours(24));
      }
    }

    @Test
    @DisplayName("reads the hour in the schedule's own zone, not the server's")
    void usesTheScheduledZone() {
      // Two schedules naming the same wall-clock hour in zones seven hours
      // apart must not book at the same instant. If they did, the zone would
      // be decoration and an estate in Bangkok would be crawled on London's
      // night.
      Duration bangkok = NightlyReconcile.untilNext(at("02:30", "Asia/Bangkok"));
      Duration london = NightlyReconcile.untilNext(at("02:30", "Europe/London"));
      assertThat(bangkok.minus(london).abs()).isNotEqualTo(Duration.ZERO);
    }
  }

  @Nested
  @DisplayName("what has been changed")
  class Provenance {

    @Test
    @DisplayName("an untouched schedule says so")
    void defaultIsMarked() {
      // The screen offers "put it back the way the file had it", and that
      // offer is only honest while nothing has been stored. isDefault is how
      // it knows, so it cannot be inferred from the values matching.
      assertThat(at("02:30", "Asia/Bangkok").isDefault()).isTrue();
    }

    @Test
    void anEditedScheduleDoesNot() {
      SyncScheduleStore.Schedule edited =
          new SyncScheduleStore.Schedule(
              true, LocalTime.of(4, 0), ZoneId.of("Asia/Bangkok"), java.time.Instant.now(), "admin");
      assertThat(edited.isDefault()).isFalse();
    }
  }
}
