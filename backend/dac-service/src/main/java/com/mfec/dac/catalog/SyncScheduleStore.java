package com.mfec.dac.catalog;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

/**
 * When the nightly reconcile runs, and who last said so (FR-1.5).
 *
 * <p>The hour used to be an environment variable, which made "run it an hour
 * later, the warehouse load moved" a deploy. It is an operational choice about
 * somebody else's quiet window, so it lives in the database and the running
 * process re-reads it.
 *
 * <p>The configured value stays as the fallback rather than being migrated into
 * the row. A fresh install then behaves exactly as it always did, and an
 * administrator who has never opened the screen has nothing stored that could
 * disagree with the file.
 */
public class SyncScheduleStore {

  private final Jdbi jdbi;
  private final Schedule fallback;

  public SyncScheduleStore(Jdbi jdbi, LocalTime configuredAt, ZoneId configuredZone,
      boolean configuredEnabled) {
    this.jdbi = jdbi;
    this.fallback = new Schedule(configuredEnabled, configuredAt, configuredZone, null, null);
  }

  /**
   * The schedule in force.
   *
   * @param enabled false means the backstop is off on purpose
   * @param at local time in {@code zone}, never UTC
   * @param updatedAt null while nobody has changed it from the configured default
   * @param updatedBy who changed it last, for the same reason
   */
  public record Schedule(
      boolean enabled, LocalTime at, ZoneId zone, Instant updatedAt, String updatedBy) {

    /** True while this is still what the configuration file said. */
    public boolean isDefault() {
      return updatedAt == null;
    }
  }

  /** What the reconcile should be running on right now. */
  public Schedule current() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT enabled, run_at, zone, updated_at, updated_by FROM om_sync_schedule")
                .map(
                    (rs, ctx) ->
                        new Schedule(
                            rs.getBoolean("enabled"),
                            rs.getTime("run_at").toLocalTime(),
                            ZoneId.of(rs.getString("zone")),
                            rs.getTimestamp("updated_at").toInstant(),
                            rs.getString("updated_by")))
                .findOne()
                .orElse(fallback));
  }

  /** What the configuration file asks for, so a screen can offer it back. */
  public Schedule configured() {
    return fallback;
  }

  /**
   * Stores a new schedule.
   *
   * <p>Validated here and not only at the edge, because this is the last place
   * before the value reaches a scheduler that would otherwise throw on every
   * reschedule for the rest of the process's life.
   */
  public Schedule save(boolean enabled, String at, String zone, String actor) {
    LocalTime time = parseTime(at);
    ZoneId id = parseZone(zone);
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO om_sync_schedule (id, enabled, run_at, zone, updated_at, updated_by)
                    VALUES (true, :enabled, :at, :zone, now(), :actor)
                    ON CONFLICT (id) DO UPDATE
                       SET enabled    = excluded.enabled,
                           run_at     = excluded.run_at,
                           zone       = excluded.zone,
                           updated_at = now(),
                           updated_by = excluded.updated_by
                    """)
                .bind("enabled", enabled)
                .bind("at", time)
                .bind("zone", id.getId())
                .bind("actor", actor)
                .execute());
    return current();
  }

  /**
   * Parses the hour.
   *
   * <p>Seconds are accepted and ignored rather than refused: a browser's time
   * input sends {@code 02:30:00} on some platforms and {@code 02:30} on others,
   * and rejecting one of them would make the screen work on one machine.
   */
  static LocalTime parseTime(String at) {
    if (at == null || at.isBlank()) {
      throw new IllegalArgumentException("A time of day is required, as HH:MM");
    }
    try {
      return LocalTime.parse(at.trim()).withSecond(0).withNano(0);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("'" + at + "' is not a time of day. Use HH:MM, like 02:30");
    }
  }

  /**
   * Parses the zone.
   *
   * <p>A region id, not an offset. "+07:00" would be correct today and wrong
   * the first time the region changed its rules, and the whole reason the
   * schedule is stored as a local time is to survive exactly that.
   */
  static ZoneId parseZone(String zone) {
    if (zone == null || zone.isBlank()) {
      throw new IllegalArgumentException("A time zone is required, like Asia/Bangkok");
    }
    String trimmed = zone.trim();
    if (!ZoneId.getAvailableZoneIds().contains(trimmed)) {
      throw new IllegalArgumentException(
          "'" + trimmed + "' is not a time zone. Use a region name, like Asia/Bangkok");
    }
    return ZoneId.of(trimmed);
  }

  /** Kept for callers that only want to know whether a row was ever written. */
  public Optional<Instant> changedAt() {
    return Optional.ofNullable(current().updatedAt());
  }
}
