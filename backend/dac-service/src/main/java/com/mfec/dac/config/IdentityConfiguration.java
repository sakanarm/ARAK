package com.mfec.dac.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/** Entra ID is the production identity provider; local users exist for tests and service accounts (FR-2.2). */
@Getter
@Setter
public class IdentityConfiguration {

  @JsonProperty("entraTenantId")
  private String entraTenantId;

  @JsonProperty("entraClientId")
  private String entraClientId;

  @JsonProperty("entraClientSecret")
  private String entraClientSecret;

  @JsonProperty("allowLocalUsers")
  private boolean allowLocalUsers = true;

  /** Cache TTL for group membership and attributes resolved from Graph (FR-2.5). */
  @JsonProperty("attributeCacheTtlSeconds")
  private int attributeCacheTtlSeconds = 300;
}
