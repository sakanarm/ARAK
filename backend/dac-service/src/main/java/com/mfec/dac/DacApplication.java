package com.mfec.dac;

import com.mfec.dac.auth.AuthFilter;
import com.mfec.dac.auth.JwtService;
import com.mfec.dac.auth.LocalIdentityDao;
import com.mfec.dac.auth.PasswordHasher;
import com.mfec.dac.config.DacConfiguration;
import com.mfec.dac.config.IdentityConfiguration;
import com.mfec.dac.health.AppDatabaseHealthCheck;
import com.mfec.dac.resources.AuthResource;
import com.mfec.dac.resources.SystemResource;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
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

    IdentityConfiguration identity = config.getIdentity();
    LocalIdentityDao identities = new LocalIdentityDao(jdbi);
    JwtService tokens =
        new JwtService(
            identity.getJwtSecret(),
            identity.getJwtIssuer(),
            Duration.ofSeconds(identity.getJwtTtlSeconds()));

    bootstrapLocalAdmin(identity, identities);

    environment.healthChecks().register("app-db", new AppDatabaseHealthCheck(jdbi));
    environment.jersey().register(new SystemResource(config));
    environment.jersey().register(new AuthResource(identities, tokens, identity));
    environment.jersey().register(new AuthFilter(tokens));

    LOG.info("Data Access Control Platform started against OpenMetadata {}",
        config.getOpenMetadata().getBaseUrl());
  }

  /**
   * Gives the seeded {@code admin} account a password, once.
   *
   * <p>The condition is deliberately "no local credential exists anywhere"
   * rather than "the admin has no password". Otherwise leaving the variable set
   * in a deployment file would silently reset the administrator password on
   * every restart, quietly undoing a rotation nobody would think to check.
   */
  private void bootstrapLocalAdmin(IdentityConfiguration identity, LocalIdentityDao identities) {
    String password = identity.getBootstrapAdminPassword();
    if (password == null || password.isBlank()) {
      if (!identities.hasAnyCredential()) {
        LOG.warn(
            "No local credential exists and IDENTITY_BOOTSTRAP_ADMIN_PASSWORD is unset: "
                + "nobody can sign in locally. Set it once, restart, then change the password.");
      }
      return;
    }
    if (identities.hasAnyCredential()) {
      LOG.debug("Local credentials already exist; leaving the bootstrap password unused.");
      return;
    }
    Optional<UUID> admin = identities.findLocalAccount("admin").map(a -> a.id());
    if (admin.isEmpty()) {
      LOG.error("The bootstrap admin principal is missing; migration V6 did not run.");
      return;
    }
    char[] chars = password.toCharArray();
    try {
      // must_change is set so the console asks for a new one immediately: the
      // bootstrap value has been through a deployment file and an environment,
      // and neither is a place a lasting password should have been.
      identities.setPassword(admin.get(), PasswordHasher.hash(chars), true);
      LOG.warn("Bootstrapped the local admin password. Change it at first sign-in.");
    } finally {
      PasswordHasher.wipe(chars);
    }
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
