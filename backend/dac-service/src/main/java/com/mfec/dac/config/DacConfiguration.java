package com.mfec.dac.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.core.Configuration;
import io.dropwizard.db.DataSourceFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Root configuration, deserialized from conf/dac.yml.
 *
 * Every secret in here is expected to arrive through an environment variable
 * substituted by {@code SubstitutingSourceProvider} (see DacApplication), never
 * as a literal in the yaml. The yaml is committed; the .env is not.
 */
@Getter
@Setter
public class DacConfiguration extends Configuration {

  @Valid
  @NotNull
  @JsonProperty("database")
  private DataSourceFactory database = new DataSourceFactory();

  /** Run Flyway against the app DB during startup. Off in prod deploys that migrate separately. */
  @JsonProperty("migrateOnStartup")
  private boolean migrateOnStartup = true;

  @Valid
  @NotNull
  @JsonProperty("openMetadata")
  private OpenMetadataConfiguration openMetadata = new OpenMetadataConfiguration();

  @Valid
  @NotNull
  @JsonProperty("identity")
  private IdentityConfiguration identity = new IdentityConfiguration();

  @Valid
  @NotNull
  @JsonProperty("secrets")
  private SecretsConfiguration secrets = new SecretsConfiguration();
}
