package com.mfec.dac.om;

import com.mfec.dac.om.client.ApiClient;
import com.mfec.dac.om.client.ApiException;
import com.mfec.dac.om.client.api.ClassificationsApi;
import com.mfec.dac.om.client.api.DatabaseSchemasApi;
import com.mfec.dac.om.client.api.DatabaseServicesApi;
import com.mfec.dac.om.client.api.DatabasesApi;
import com.mfec.dac.om.client.api.DomainsApi;
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
 */
public class OpenMetadataClient {

  private static final Logger LOG = LoggerFactory.getLogger(OpenMetadataClient.class);

  private final ApiClient apiClient;
  private final String baseUrl;
  private final String expectedVersion;

  public OpenMetadataClient(
      String baseUrl, String jwtToken, String expectedVersion, int connectTimeoutMs,
      int readTimeoutMs) {
    this.baseUrl = trimTrailingSlash(baseUrl);
    this.expectedVersion = expectedVersion;
    this.apiClient =
        new ApiClient()
            .setBasePath(this.baseUrl + "/api")
            .setConnectTimeout(connectTimeoutMs)
            .setReadTimeout(readTimeoutMs);
    if (jwtToken != null && !jwtToken.isBlank()) {
      apiClient.setBearerToken(jwtToken);
    } else {
      // Not fatal: a development instance may be open, and refusing to start
      // would make the catalog harder to try than it needs to be. Anything the
      // instance protects will fail with 401 at the call, which says more.
      LOG.warn("No OpenMetadata token configured; only unauthenticated endpoints will answer.");
    }
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
    try {
      OpenMetadataServerVersion version = system().getCatalogVersion();
      return version == null ? null : version.getVersion();
    } catch (ApiException e) {
      // The instance answered, but not with a version: wrong path, an auth
      // failure, or an error page from something in front of it.
      LOG.warn("Could not read the OpenMetadata version from {}: {}", baseUrl, e.getMessage());
      return null;
    } catch (ProcessingException e) {
      // The request never completed at all -- connection refused, DNS failure,
      // timeout. This is the case that must not escape: an unreachable catalog
      // is exactly when the operator's failOnVersionMismatch choice applies,
      // and letting the transport exception through takes that choice away and
      // stops the platform from starting (NFR-3).
      LOG.warn("Could not reach OpenMetadata at {}: {}", baseUrl, rootCauseOf(e));
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
    String actual = readVersion();
    if (actual == null) {
      String message = "OpenMetadata at " + baseUrl + " did not report a version";
      if (failOnMismatch) {
        throw new IllegalStateException(message);
      }
      LOG.warn("{}; continuing without a version check.", message);
      return null;
    }
    if (expectedVersion != null && !expectedVersion.isBlank() && !expectedVersion.equals(actual)) {
      String message =
          "OpenMetadata at " + baseUrl + " reports version " + actual + " but this client was "
              + "generated from the " + expectedVersion + " spec";
      if (failOnMismatch) {
        throw new IllegalStateException(message);
      }
      LOG.warn("{}. Fields the crawler reads may be missing or renamed.", message);
    } else {
      LOG.info("Connected to OpenMetadata {} at {}", actual, baseUrl);
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
