package com.mfec.dac.resources;

import com.mfec.dac.audit.ClientAddress;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.catalog.CatalogSyncService;
import com.mfec.dac.catalog.OmConnectionStore;
import com.mfec.dac.config.OpenMetadataConfiguration;
import com.mfec.dac.om.OpenMetadataClient;
import com.mfec.dac.om.events.EventSignature;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The OpenMetadata connection, as the platform is actually running it (FR-1.1).
 *
 * <p>This screen used to be read-only, on the argument that a console able to
 * write its own upstream credential is a console whose compromise hands over
 * the catalog. The argument was half right and the design that followed from it
 * was wrong. Where the catalog lives is not a secret and it is not a constant
 * either: an estate moves its instance, promotes a second environment, renames
 * a host. Under the old design that was a redeploy — and the one symptom an
 * administrator could see was an "Open in OpenMetadata" button that silently
 * failed to appear, with nothing on any screen to correct it.
 *
 * <p>So it writes now, under three rules that keep the original concern intact:
 *
 * <ul>
 *   <li>Only a platform administrator reaches it, enforced at the class.
 *   <li>Both secrets are sealed with the same key as every other stored
 *       credential, and neither is ever returned in any form — not the value,
 *       not a prefix, not a length. What comes back is present or absent.
 *   <li>Leaving a secret field empty keeps the one in force, so moving the URL
 *       does not require re-typing a token, and an estate that injects its
 *       token through the environment keeps doing so untouched.
 * </ul>
 *
 * <p>Every change is written to {@code audit_connection_change} with a summary
 * that names the URL and never the credential, because repointing the catalog
 * changes the meaning of every policy selector that reads a tag.
 *
 * <p>What the page needs beyond the configuration is evidence: whether the
 * instance answers, what version it reports, and whether that matches the spec
 * the client was generated from. That is the probe, behind its own POST so that
 * opening the page does not block on an unreachable host — and it takes an
 * optional candidate connection, so an address can be tested before it is
 * saved rather than after it has broken the crawl.
 */
@Path("/v1/settings/openmetadata")
@Produces(MediaType.APPLICATION_JSON)
@Secured("PLATFORM_ADMIN")
public class OpenMetadataSettingsResource {

  private static final Logger LOG = LoggerFactory.getLogger(OpenMetadataSettingsResource.class);

  private final OmConnectionStore connections;
  private final OpenMetadataConfiguration config;
  private final OpenMetadataClient client;
  private final CatalogSyncService sync;

  public OpenMetadataSettingsResource(
      OmConnectionStore connections,
      OpenMetadataConfiguration config,
      OpenMetadataClient client,
      CatalogSyncService sync) {
    this.connections = connections;
    this.config = config;
    this.client = client;
    this.sync = sync;
  }

  /** The effective connection, with both secrets reduced to present / absent. */
  @GET
  public Map<String, Object> settings() {
    return describe(connections.current());
  }

  /**
   * Points the platform at an instance.
   *
   * <p>The client is reconfigured in the same call rather than at the next
   * restart: a setting that needs a restart to take effect is a setting that
   * looks broken, and the operator would have no way to tell a wrong URL from
   * one that simply has not been picked up yet.
   */
  @PUT
  public Map<String, Object> update(
      OmConnectionStore.Edit edit,
      @Context SecurityContext security,
      @Context HttpServletRequest request) {

    String actor = security.getUserPrincipal() == null
        ? "unknown"
        : security.getUserPrincipal().getName();
    OmConnectionStore.Connection saved =
        connections.save(edit, actor, ClientAddress.normalise(clientIp(request)));
    OmConnectionStore.Credentials credentials = connections.credentials();
    client.reconfigure(
        saved.baseUrl(),
        credentials.jwtToken(),
        saved.expectedVersion(),
        saved.connectTimeoutMs(),
        saved.readTimeoutMs());
    LOG.info("{} repointed OpenMetadata at {}", actor, saved.baseUrl());
    return describe(saved);
  }

  /**
   * Asks an instance who it is, now.
   *
   * <p>With a body, it asks the instance described in that body instead of the
   * one in force. That is the whole point of testing before saving: an address
   * that cannot be probed until it has been stored can only be found wrong
   * after it has already stopped the crawl.
   *
   * <p>A candidate with no token reuses the stored one, so "does the new host
   * answer" can be asked without re-typing a credential. A candidate that
   * carries a token is probed with that token, because that is the pair the
   * operator is about to save.
   */
  @POST
  @Path("/test")
  public Map<String, Object> test(OmConnectionStore.Edit candidate) {
    if (candidate == null || candidate.baseUrl() == null || candidate.baseUrl().isBlank()) {
      return probe(client);
    }
    String url = OmConnectionStore.requireUrl(candidate.baseUrl());
    OmConnectionStore.Connection current = connections.current();
    String token =
        candidate.jwtToken() == null || candidate.jwtToken().isBlank()
            ? connections.credentials().jwtToken()
            : candidate.jwtToken();
    // A throwaway client. Reconfiguring the real one to run a probe would point
    // the crawler at an instance nobody has agreed to yet, and a failed probe
    // would leave it there.
    OpenMetadataClient candidateClient =
        new OpenMetadataClient(
            url,
            token,
            candidate.expectedVersion() == null
                ? current.expectedVersion()
                : candidate.expectedVersion(),
            candidate.connectTimeoutMs() == null
                ? current.connectTimeoutMs()
                : candidate.connectTimeoutMs(),
            candidate.readTimeoutMs() == null
                ? current.readTimeoutMs()
                : candidate.readTimeoutMs());
    return probe(candidateClient);
  }

  private Map<String, Object> describe(OmConnectionStore.Connection connection) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("baseUrl", connection.baseUrl());
    body.put("expectedVersion", connection.expectedVersion());
    body.put("failOnVersionMismatch", connection.failOnVersionMismatch());
    body.put("connectTimeoutMs", connection.connectTimeoutMs());
    body.put("readTimeoutMs", connection.readTimeoutMs());

    // Presence, not value, and not a prefix or a length either: a hint about a
    // token's shape is a hint about the token.
    body.put("tokenConfigured", connection.hasToken());
    body.put("tokenSource", connection.isDefault() ? "OM_JWT_TOKEN" : "stored on this platform");
    body.put("webhookSecretConfigured", connection.hasWebhookSecret());
    body.put(
        "webhookSecretSource",
        connection.isDefault() ? "OM_WEBHOOK_SECRET" : "stored on this platform");
    body.put("webhookPath", "/api/v1/webhooks/openmetadata");
    body.put("webhookSignatureHeader", EventSignature.HEADER);

    // Still from the file. The poller's cadence is an operational tuning knob
    // rather than a thing an estate changes, and the nightly reconcile already
    // has its own stored schedule and its own screen.
    body.put("pollEnabled", config.isPollEnabled());
    body.put("pollIntervalSeconds", config.getPollIntervalSeconds());
    body.put("maxEventsPerPoll", config.getMaxEventsPerPoll());
    body.put("reconcileEnabled", config.isReconcileEnabled());
    body.put("reconcileAt", config.getReconcileAt());
    body.put("reconcileZone", config.getReconcileZone());

    body.put("editable", true);
    body.put("source", connection.source());
    body.put("isDefault", connection.isDefault());
    body.put("updatedAt", connection.updatedAt());
    body.put("updatedBy", connection.updatedBy());
    body.put(
        "configSource",
        connection.isDefault()
            ? "conf/dac.yml, with secrets from the process environment"
            : "set on this screen; the file remains the fallback");

    body.put("sync", syncState());
    return body;
  }

  private static Map<String, Object> probe(OpenMetadataClient against) {
    long startedAt = System.nanoTime();
    String version = against.readVersion();
    long tookMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("baseUrl", against.baseUrl());
    body.put("reachable", version != null);
    body.put("version", version);
    body.put("expectedVersion", against.expectedVersion());
    body.put("versionMatches", version != null && version.equals(against.expectedVersion()));
    body.put("tookMs", tookMs);
    body.put(
        "message",
        version == null
            // Deliberately vague about which of the two it was: from here they
            // are indistinguishable, and guessing in the UI sends the operator
            // to the wrong file.
            ? "No version came back. The instance is unreachable, or the bot token was refused."
            : version.equals(against.expectedVersion())
                ? "Connected."
                : "Connected, but this client was generated from the "
                    + against.expectedVersion()
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

  /**
   * The address the request came from.
   *
   * <p>Taken from the socket and deliberately not from {@code X-Forwarded-For},
   * for the same reason as every other audited act here: a header a caller can
   * write is a header a caller can write into an audit trail.
   */
  private static String clientIp(HttpServletRequest request) {
    return request == null ? null : request.getRemoteAddr();
  }
}
