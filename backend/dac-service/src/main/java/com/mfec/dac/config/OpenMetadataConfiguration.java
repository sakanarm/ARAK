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
}
