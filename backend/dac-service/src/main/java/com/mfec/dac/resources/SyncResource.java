package com.mfec.dac.resources;

import com.mfec.dac.auth.Secured;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.catalog.CatalogSyncService;
import com.mfec.dac.catalog.NightlyReconcile;
import com.mfec.dac.catalog.SyncScheduleStore;
import com.mfec.dac.catalog.SyncStateDao;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Manual sync and its status (FR-1.5).
 *
 * <p>Platform admins only. A crawl reads every table in the catalog with the bot
 * token and rewrites the facet rows every policy decision is made from; that is
 * not something a data owner should be able to set off, and a failed one leaves
 * the cache stale in a way only an operator can interpret.
 *
 * <p>Synchronous, deliberately, while there is one instance and one upstream:
 * the caller who asked for a crawl is the person who needs to know whether it
 * worked, and a 202 with a job id to poll is a second mechanism to build and
 * explain. The nightly reconcile is what runs unattended.
 */
@Path("/v1/sync")
@Produces(MediaType.APPLICATION_JSON)
@Secured("PLATFORM_ADMIN")
public class SyncResource {

  private final CatalogSyncService sync;
  private final SyncScheduleStore schedules;

  /**
   * Null where the backstop is not managed at all.
   *
   * <p>The schedule is still readable and writable in that case -- it is a
   * stored setting, not a handle on a thread -- but there is nothing running to
   * rebook, and the screen is told so rather than being left to imply a crawl
   * that will never happen.
   */
  private final NightlyReconcile reconcile;

  public SyncResource(
      CatalogSyncService sync, SyncScheduleStore schedules, NightlyReconcile reconcile) {
    this.sync = sync;
    this.schedules = schedules;
    this.reconcile = reconcile;
  }

  /**
   * The schedule, what the configuration file would have used, and when the
   * next crawl is actually booked for.
   *
   * <p>All three, because they answer different questions and people have been
   * caught out by the difference: what is stored, what it was before anyone
   * touched it, and what is really going to happen tonight.
   */
  @GET
  @Path("/openmetadata/schedule")
  public Map<String, Object> schedule() {
    SyncScheduleStore.Schedule now = schedules.current();
    SyncScheduleStore.Schedule file = schedules.configured();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("enabled", now.enabled());
    body.put("at", now.at().toString());
    body.put("zone", now.zone().getId());
    body.put("isDefault", now.isDefault());
    body.put("updatedAt", now.updatedAt());
    body.put("updatedBy", now.updatedBy());
    body.put("configuredAt", file.at().toString());
    body.put("configuredZone", file.zone().getId());
    body.put("nextRunAt", reconcile == null ? null : reconcile.nextRunAt().orElse(null));
    // Distinct from `enabled`: somebody can switch the schedule on and still
    // have nothing running it, which is worth saying out loud on the screen.
    body.put("managed", reconcile != null);
    return body;
  }

  /** What an administrator submits. `at` is HH:MM local to `zone`. */
  public record ScheduleEdit(Boolean enabled, String at, String zone) {}

  /**
   * Moves the nightly crawl.
   *
   * <p>The rebooking happens here rather than on the next tick, because the
   * whole point of moving it to 04:00 is usually that 02:30 tonight is too
   * soon.
   */
  @PUT
  @Path("/openmetadata/schedule")
  @Consumes(MediaType.APPLICATION_JSON)
  public Map<String, Object> saveSchedule(ScheduleEdit edit, @Context SecurityContext security) {
    if (edit == null) {
      throw new BadRequestException("A schedule is required");
    }
    SyncScheduleStore.Schedule now = schedules.current();
    boolean enabled = edit.enabled() == null ? now.enabled() : edit.enabled();
    String at = edit.at() == null || edit.at().isBlank() ? now.at().toString() : edit.at();
    String zone =
        edit.zone() == null || edit.zone().isBlank() ? now.zone().getId() : edit.zone();
    try {
      schedules.save(enabled, at, zone, actor(security));
    } catch (IllegalArgumentException e) {
      // The message is written for the person who typed it, so it is passed
      // through rather than turned into a generic 400.
      throw new BadRequestException(e.getMessage());
    }
    if (reconcile != null) {
      reconcile.reload();
    }
    return schedule();
  }

  /** Null rather than a placeholder: an unattributed change should read as one. */
  private static String actor(SecurityContext security) {
    return security.getUserPrincipal() instanceof AuthenticatedUser user ? user.username() : null;
  }

  /** What the last run did, and whether one is under way now. */
  @GET
  @Path("/openmetadata")
  public Map<String, Object> status() {
    Optional<SyncStateDao.SyncState> state = sync.state();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("source", CatalogSyncService.SOURCE);
    if (state.isEmpty()) {
      // Distinct from IDLE: nothing has ever run, so the cache is not stale,
      // it is absent, and every policy binding resolved from it is empty.
      body.put("status", "NEVER_RUN");
      return body;
    }
    SyncStateDao.SyncState s = state.get();
    body.put("status", s.status());
    body.put("lastFullCrawlAt", s.lastFullCrawlAt());
    body.put("lastReconcileAt", s.lastReconcileAt());
    body.put("lastEventTs", s.lastEventTs());
    body.put("lastError", s.lastError());
    body.put("updatedAt", s.updatedAt());
    return body;
  }

  /** Runs a full crawl now. */
  @POST
  @Path("/openmetadata")
  public Response crawl() {
    Optional<CatalogSyncService.Result> result = sync.crawl();
    return result
        .map(r -> Response.ok(r).build())
        .orElseGet(
            () ->
                // 409, not 200: the caller asked for a crawl and did not get
                // one. Answering with the running crawl's status would read as
                // "done" to anything scripted against this.
                Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("message", "A crawl is already running", "status", status()))
                    .build());
  }
}
