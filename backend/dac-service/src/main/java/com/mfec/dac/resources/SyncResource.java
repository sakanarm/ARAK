package com.mfec.dac.resources;

import com.mfec.dac.auth.Secured;
import com.mfec.dac.catalog.CatalogSyncService;
import com.mfec.dac.catalog.SyncStateDao;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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

  public SyncResource(CatalogSyncService sync) {
    this.sync = sync;
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
