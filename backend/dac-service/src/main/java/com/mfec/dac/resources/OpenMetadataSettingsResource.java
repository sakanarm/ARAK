package com.mfec.dac.resources;

import com.mfec.dac.auth.Secured;
import com.mfec.dac.catalog.CatalogSyncService;
import com.mfec.dac.config.OpenMetadataConfiguration;
import com.mfec.dac.om.OpenMetadataClient;
import com.mfec.dac.om.events.EventSignature;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The OpenMetadata connection, as the platform is actually running it (FR-1.1).
 *
 * <p>Read-only on purpose, and that is the whole design of this screen. The bot
 * token and the webhook secret are injected from the environment and are never
 * settable from a browser: a console that can write its own upstream credential
 * is a console whose compromise hands over the catalog, and a credential typed
 * into a form is a credential that ends up in a request log. So what this
 * returns is whether each secret is present, never what it is, and everything
 * else is the effective configuration with its source named.
 *
 * <p>What the page needs beyond the configuration is evidence: whether the
 * instance answers, what version it reports, and whether that matches the spec
 * the client was generated from. That is the probe, behind its own POST so that
 * opening the page does not block on an unreachable host.
 */
@Path("/v1/settings/openmetadata")
@Produces(MediaType.APPLICATION_JSON)
@Secured("PLATFORM_ADMIN")
public class OpenMetadataSettingsResource {

  private final OpenMetadataConfiguration config;
  private final OpenMetadataClient client;
  private final CatalogSyncService sync;

  public OpenMetadataSettingsResource(
      OpenMetadataConfiguration config, OpenMetadataClient client, CatalogSyncService sync) {
    this.config = config;
    this.client = client;
    this.sync = sync;
  }

  /** The effective connection, with both secrets reduced to present / absent. */
  @GET
  public Map<String, Object> settings() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("baseUrl", config.getBaseUrl());
    body.put("expectedVersion", config.getExpectedVersion());
    body.put("failOnVersionMismatch", config.isFailOnVersionMismatch());
    body.put("connectTimeoutMs", config.getConnectTimeoutMs());
    body.put("readTimeoutMs", config.getReadTimeoutMs());

    // Presence, not value, and not a prefix or a length either: a hint about a
    // token's shape is a hint about the token.
    body.put("tokenConfigured", present(config.getJwtToken()));
    body.put("tokenSource", "OM_JWT_TOKEN");
    body.put("webhookSecretConfigured", present(config.getWebhookSecret()));
    body.put("webhookSecretSource", "OM_WEBHOOK_SECRET");
    body.put("webhookPath", "/api/v1/webhooks/openmetadata");
    body.put("webhookSignatureHeader", EventSignature.HEADER);

    body.put("pollEnabled", config.isPollEnabled());
    body.put("pollIntervalSeconds", config.getPollIntervalSeconds());
    body.put("maxEventsPerPoll", config.getMaxEventsPerPoll());
    body.put("reconcileEnabled", config.isReconcileEnabled());
    body.put("reconcileAt", config.getReconcileAt());
    body.put("reconcileZone", config.getReconcileZone());

    // Read-only is a property of this deployment, not a limitation to discover
    // by pressing a disabled button, so the page is told in the payload.
    body.put("editable", false);
    body.put("configSource", "conf/dac.yml, with secrets from the process environment");

    body.put("sync", syncState());
    return body;
  }

  /** Asks the instance who it is, now. */
  @POST
  @Path("/test")
  public Map<String, Object> test() {
    long startedAt = System.nanoTime();
    String version = client.readVersion();
    long tookMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("baseUrl", client.baseUrl());
    body.put("reachable", version != null);
    body.put("version", version);
    body.put("expectedVersion", client.expectedVersion());
    body.put("versionMatches", version != null && version.equals(client.expectedVersion()));
    body.put("tookMs", tookMs);
    body.put(
        "message",
        version == null
            // Deliberately vague about which of the two it was: from here they
            // are indistinguishable, and guessing in the UI sends the operator
            // to the wrong file.
            ? "No version came back. The instance is unreachable, or the bot token was refused."
            : version.equals(client.expectedVersion())
                ? "Connected."
                : "Connected, but this client was generated from the "
                    + client.expectedVersion()
                    + " spec. Fields the crawler reads may be missing or renamed.");
    return body;
  }

  private Map<String, Object> syncState() {
    Map<String, Object> state = new LinkedHashMap<>();
    Optional<com.mfec.dac.catalog.SyncStateDao.SyncState> current = sync.state();
    if (current.isEmpty()) {
      state.put("status", "NEVER_RUN");
      return state;
    }
    com.mfec.dac.catalog.SyncStateDao.SyncState s = current.get();
    state.put("status", s.status());
    state.put("lastFullCrawlAt", s.lastFullCrawlAt());
    state.put("lastReconcileAt", s.lastReconcileAt());
    state.put("lastEventTs", s.lastEventTs());
    state.put("lastError", s.lastError());
    state.put("updatedAt", s.updatedAt());
    return state;
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }
}
