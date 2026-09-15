package com.mfec.dac;

import com.mfec.dac.config.DacConfiguration;
import com.mfec.dac.health.AppDatabaseHealthCheck;
import com.mfec.dac.resources.SystemResource;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Modelled on OpenMetadata's {@code OpenMetadataApplication}:
 * configuration is env-substituted, migrations run before anything touches the
 * schema, and everything else is registered from here rather than discovered by
 * classpath scanning.
 */
public class DacApplication extends Application<DacConfiguration> {

  private static final Logger LOG = LoggerFactory.getLogger(DacApplication.class);

  public static void main(String[] args) throws Exception {
    new DacApplication().run(args);
  }

  @Override
  public String getName() {
    return "data-access-control";
  }

  @Override
  public void initialize(Bootstrap<DacConfiguration> bootstrap) {
    // Secrets live in the environment, never in the committed yaml.
    bootstrap.setConfigurationSourceProvider(
        new SubstitutingSourceProvider(
            bootstrap.getConfigurationSourceProvider(),
            new EnvironmentVariableSubstitutor(false)));
  }

  @Override
  public void run(DacConfiguration config, Environment environment) {
    DataSource appDataSource =
        config.getDatabase().build(environment.metrics(), "app-db");

    if (config.isMigrateOnStartup()) {
      migrate(appDataSource);
    }

    Jdbi jdbi = Jdbi.create(appDataSource);
    jdbi.installPlugins();

    environment.healthChecks().register("app-db", new AppDatabaseHealthCheck(jdbi));
    environment.jersey().register(new SystemResource(config));

    LOG.info("Data Access Control Platform started against OpenMetadata {}",
        config.getOpenMetadata().getBaseUrl());
  }

  private void migrate(DataSource dataSource) {
    Flyway flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load();
    flyway.migrate();
  }
}
