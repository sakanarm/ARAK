package com.mfec.dac.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

/**
 * Connection to the OpenMetadata instance we treat as the source of truth for
 * discovery metadata (FR-1.1).
 *
 * {@code expectedVersion} is checked against GET /api/v1/system/version at
 * startup: the generated client in dac-connector-openmetadata is built from a
 * spec pinned at one version, and talking to a different one is a silent-drift
 * bug we would rather fail loudly on.
 */
@Getter
@Setter
public class OpenMetadataConfiguration {

  @NotEmpty
  @JsonProperty("baseUrl")
  private String baseUrl = "http://localhost:8585";

  /** Bot token, not a human account token. Injected from OM_JWT_TOKEN. */
  @JsonProperty("jwtToken")
  private String jwtToken;

  @JsonProperty("expectedVersion")
  private String expectedVersion = "2.0.1";

  @JsonProperty("failOnVersionMismatch")
  private boolean failOnVersionMismatch = false;

  @JsonProperty("connectTimeoutMs")
  private int connectTimeoutMs = 5_000;

  @JsonProperty("readTimeoutMs")
  private int readTimeoutMs = 60_000;

  /**
   * Shared secret on the OpenMetadata event subscription that pushes to us
   * (FR-1.5).
   *
   * <p>Empty by default, and an empty one makes the receiver refuse every
   * delivery rather than accept unsigned ones. The webhook is the only endpoint
   * with no session behind it, so the secret is the whole of its authentication:
   * without it anyone who can reach the port can tell the platform that a
   * column's PII tag has gone. Injected from OM_WEBHOOK_SECRET.
   */
  @JsonProperty("webhookSecret")
  private String webhookSecret;

  /**
   * Whether to read the change feed on a timer as well as receiving the webhook.
   *
   * <p>On by default. The webhook is one delivery attempt to a service that may
   * be restarting, and the poller is what makes missing one a delay rather than
   * a permanently wrong cache.
   */
  @JsonProperty("pollEnabled")
  private boolean pollEnabled = true;

  /**
   * Seconds between change-feed reads.
   *
   * <p>A minute is a compromise the plan names: fast enough that a tag change
   * reaches a policy decision while the person who made it is still watching,
   * slow enough not to be a load on the catalog.
   */
  @JsonProperty("pollIntervalSeconds")
  private int pollIntervalSeconds = 60;

  /**
   * Most events to read in one poll.
   *
   * <p>A bound, not a target. After an outage the feed may hold a day of
   * history, and a single unbounded read of it would hold the applier for as
   * long as it took while the cursor stayed where it was. Whatever is left is
   * read by the next tick.
   */
  @JsonProperty("maxEventsPerPoll")
  private int maxEventsPerPoll = 2_000;

  /** Whether to run a full crawl overnight (FR-1.5). */
  @JsonProperty("reconcileEnabled")
  private boolean reconcileEnabled = true;

  /** Local time of the nightly reconcile, as HH:mm. */
  @JsonProperty("reconcileAt")
  private String reconcileAt = "02:30";

  /** Time zone the reconcile hour is read in; the operator's, not UTC. */
  @JsonProperty("reconcileZone")
  private String reconcileZone = "Asia/Bangkok";
}
