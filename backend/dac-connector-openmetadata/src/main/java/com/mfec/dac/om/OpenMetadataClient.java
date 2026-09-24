package com.mfec.dac.om;

import com.mfec.dac.om.client.ApiClient;
import com.mfec.dac.om.client.ApiException;
import com.mfec.dac.om.client.api.ClassificationsApi;
import com.mfec.dac.om.client.api.DatabaseSchemasApi;
import com.mfec.dac.om.client.api.DatabaseServicesApi;
import com.mfec.dac.om.client.api.DatabasesApi;
import com.mfec.dac.om.client.api.DomainsApi;
import com.mfec.dac.om.client.api.EventsApi;
import com.mfec.dac.om.client.api.GlossariesApi;
import com.mfec.dac.om.client.api.MetadataApi;
import com.mfec.dac.om.client.api.SystemApi;
import com.mfec.dac.om.client.api.TablesApi;
import com.mfec.dac.om.client.model.OpenMetadataServerVersion;
import jakarta.ws.rs.ProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The connection to one OpenMetadata instance.
 *
 * <p>A thin holder rather than a wrapper: the generated APIs are already the
 * contract, and putting a facade of our own in front of a thousand generated
 * types would only be a second thing to keep current. What does belong here is
 * the configuration every call shares — base URL, bot token, timeouts — and the
 * version check.
 *
 * <p>The token must be a bot token. A human account's token carries that
 * person's permissions and expires when they leave, which turns an offboarding
 * into a catalog outage and, through the cache, into a policy that cannot be
 * explained.
 *
 * <p>Where it points can be changed while the platform runs, because an estate
 * moves its catalog and the alternative is a redeploy. The three fields that
 * describe the connection are {@code volatile} and swapped together, and every
 * API accessor reads the current one at the moment of the call rather than
 * holding a reference. A crawl already in flight therefore finishes against the
 * instance it started on, which is the behaviour that keeps a half-written
 * generation from mixing two catalogs.
 */
public class OpenMetadataClient {

  private static final Logger LOG = LoggerFactory.getLogger(OpenMetadataClient.class);

  private volatile ApiClient apiClient;
  private volatile String baseUrl;
  private volatile String expectedVersion;

  public OpenMetadataClient(
      String baseUrl, String jwtToken, String expectedVersion, int connectTimeoutMs,
      int readTimeoutMs) {
    apply(baseUrl, jwtToken, expectedVersion, connectTimeoutMs, readTimeoutMs);
  }

  /**
   * Points this client at a different instance, or at the same one with a new
   * credential.
   *
   * <p>Called from the settings screen. The swap is one assignment per field
   * and the fields are read independently, so a call that lands between two
   * reads can produce a request built from the old timeout and the new URL --
   * which is harmless, and the alternative (locking every accessor) would put a
   * monitor in the hot path of the crawl to protect against a reconfiguration
   * that happens a few times a year.
   */
  public void reconfigure(
      String baseUrl, String jwtToken, String expectedVersion, int connectTimeoutMs,
      int readTimeoutMs) {
    apply(baseUrl, jwtToken, expectedVersion, connectTimeoutMs, readTimeoutMs);
    LOG.info("OpenMetadata connection now points at {}", this.baseUrl);
  }

  private void apply(
      String baseUrl, String jwtToken, String expectedVersion, int connectTimeoutMs,
      int readTimeoutMs) {
    String trimmed = trimTrailingSlash(baseUrl);
    ApiClient built =
        new ApiClient()
            .setBasePath(trimmed + "/api")
            .setConnectTimeout(connectTimeoutMs)
            .setReadTimeout(readTimeoutMs);
    if (jwtToken != null && !jwtToken.isBlank()) {
      built.setBearerToken(jwtToken);
    } else {
      // Not fatal: a development instance may be open, and refusing to start
      // would make the catalog harder to try than it needs to be. Anything the
      // instance protects will fail with 401 at the call, which says more.
      LOG.warn("No OpenMetadata token configured; only unauthenticated endpoints will answer.");
    }
    this.baseUrl = trimmed;
    this.expectedVersion = expectedVersion;
    this.apiClient = built;
  }

  public ApiClient apiClient() {
    return apiClient;
  }

  public String baseUrl() {
    return baseUrl;
  }

  public TablesApi tables() {
    return new TablesApi(apiClient);
  }

  public DatabasesApi databases() {
    return new DatabasesApi(apiClient);
  }

  public DatabaseSchemasApi schemas() {
    return new DatabaseSchemasApi(apiClient);
  }

  public DatabaseServicesApi databaseServices() {
    return new DatabaseServicesApi(apiClient);
  }

  /** Classifications and the tags inside them; OpenMetadata serves both here. */
  public ClassificationsApi classifications() {
    return new ClassificationsApi(apiClient);
  }

  /** Entity type definitions, which is where custom property definitions live. */
  public MetadataApi metadata() {
    return new MetadataApi(apiClient);
  }

  public GlossariesApi glossaries() {
    return new GlossariesApi(apiClient);
  }

  public DomainsApi domains() {
    return new DomainsApi(apiClient);
  }

  /**
   * The change feed and its subscriptions.
   *
   * <p>Only {@code GET /v1/events} is used here. The subscription endpoints on
   * the same API create the webhook that pushes to us, and that is registered by
   * an operator rather than by the platform: a service that creates its own
   * subscription on every start leaves a trail of duplicates behind every
   * rename, and the callback URL it would have to invent is the one thing it
   * cannot know about its own deployment.
   */
  public EventsApi events() {
    return new EventsApi(apiClient);
  }

  public SystemApi system() {
    return new SystemApi(apiClient);
  }

  /**
   * The version the instance reports, or null when it cannot be read.
   *
   * <p>Checked rather than assumed because the client is generated from a spec
   * pinned at one version. Talking to a different one does not fail loudly — it
   * fails by quietly dropping a field that a policy selector depends on, which
   * is the kind of bug that surfaces as "why is this table not masked".
   */
  public String readVersion() {
    String at = baseUrl;
    try {
      OpenMetadataServerVersion version = system().getCatalogVersion();
      return version == null ? null : version.getVersion();
    } catch (ApiException e) {
      // The instance answered, but not with a version: wrong path, an auth
      // failure, or an error page from something in front of it.
      LOG.warn("Could not read the OpenMetadata version from {}: {}", at, e.getMessage());
      return null;
    } catch (ProcessingException e) {
      // The request never completed at all -- connection refused, DNS failure,
      // timeout. This is the case that must not escape: an unreachable catalog
      // is exactly when the operator's failOnVersionMismatch choice applies,
      // and letting the transport exception through takes that choice away and
      // stops the platform from starting (NFR-3).
      LOG.warn("Could not reach OpenMetadata at {}: {}", at, rootCauseOf(e));
      return null;
    }
  }

  /** The innermost message, since a transport failure nests several wrappers. */
  private static String rootCauseOf(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause.toString();
  }

  /**
   * Compares the instance against the spec this client was generated from.
   *
   * <p>A mismatch is reported rather than repaired. Whether it stops startup is
   * the operator's call: on a patch difference it is noise, and across a minor
   * version it is the difference between a policy that selects an asset and one
   * that quietly does not.
   *
   * @param failOnMismatch throw instead of warning
   * @return the version the instance reported, or null if it could not be read
   */
  public String checkVersion(boolean failOnMismatch) {
    String at = baseUrl;
    String expected = expectedVersion;
    String actual = readVersion();
    if (actual == null) {
      String message = "OpenMetadata at " + at + " did not report a version";
      if (failOnMismatch) {
        throw new IllegalStateException(message);
      }
      LOG.warn("{}; continuing without a version check.", message);
      return null;
    }
    if (expected != null && !expected.isBlank() && !expected.equals(actual)) {
      String message =
          "OpenMetadata at " + at + " reports version " + actual + " but this client was "
              + "generated from the " + expected + " spec";
      if (failOnMismatch) {
        throw new IllegalStateException(message);
      }
      LOG.warn("{}. Fields the crawler reads may be missing or renamed.", message);
    } else {
      LOG.info("Connected to OpenMetadata {} at {}", actual, at);
    }
    return actual;
  }

  public String expectedVersion() {
    return expectedVersion;
  }

  private static String trimTrailingSlash(String url) {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("openMetadata.baseUrl is required");
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
