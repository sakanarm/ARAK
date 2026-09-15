package com.mfec.dac.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * Where source-database credentials come from (NFR-1).
 *
 * "fernet" matches what OpenMetadata does and is the fallback for local dev;
 * production is expected to point at a vault. Plaintext is deliberately not an
 * option.
 */
@Getter
@Setter
public class SecretsConfiguration {

  public enum Provider { FERNET, VAULT, AZURE_KEY_VAULT }

  @JsonProperty("provider")
  private Provider provider = Provider.FERNET;

  @JsonProperty("fernetKey")
  private String fernetKey;

  @JsonProperty("vaultAddress")
  private String vaultAddress;

  @JsonProperty("vaultToken")
  private String vaultToken;
}
