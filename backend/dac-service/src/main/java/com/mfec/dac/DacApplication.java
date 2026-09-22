package com.mfec.dac;

import com.mfec.dac.auth.AuthFilter;
import com.mfec.dac.auth.JwtService;
import com.mfec.dac.auth.LocalIdentityDao;
import com.mfec.dac.auth.PasswordHasher;
import com.mfec.dac.catalog.CatalogChangeApplier;
import com.mfec.dac.catalog.CatalogQuery;
import com.mfec.dac.catalog.CatalogSyncService;
import com.mfec.dac.catalog.GovernanceQuery;
import com.mfec.dac.catalog.SearchQuery;
import com.mfec.dac.catalog.ChangeEventPoller;
import com.mfec.dac.catalog.NightlyReconcile;
import com.mfec.dac.catalog.SyncStateDao;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mfec.dac.config.DacConfiguration;
import com.mfec.dac.config.IdentityConfiguration;
import com.mfec.dac.json.JsonMapperProvider;
import com.mfec.dac.config.OpenMetadataConfiguration;
import com.mfec.dac.health.AppDatabaseHealthCheck;
import com.mfec.dac.identity.IdentityAdminStore;
import com.mfec.dac.identity.PrincipalQuery;
import com.mfec.dac.om.OpenMetadataClient;
import com.mfec.dac.resources.AuthResource;
import com.mfec.dac.catalog.SourceCatalogImporter;
import com.mfec.dac.engine.EngineConfig;
import com.mfec.dac.engine.PolicyEngine;
import com.mfec.dac.engine.PolicyExpressionEvaluator;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.policy.ImpactAnalysis;
import com.mfec.dac.policy.PrincipalLoader;
import com.mfec.dac.policy.QueryService;
import com.mfec.dac.resources.DecisionResource;
import com.mfec.dac.resources.QueryResource;
import com.mfec.dac.source.jdbc.JdbcIntrospector;
import com.mfec.dac.source.jdbc.QueryExecutor;
import com.mfec.dac.policy.AssetContextLoader;
import com.mfec.dac.policy.PolicyBindingMaterializer;
import com.mfec.dac.policy.PolicyOverview;
import com.mfec.dac.policy.PolicyStore;
import com.mfec.dac.resources.CatalogResource;
import com.mfec.dac.resources.GovernanceResource;
import com.mfec.dac.resources.SearchResource;
import com.mfec.dac.resources.OpenMetadataSettingsResource;
import com.mfec.dac.resources.PolicyResource;
import com.mfec.dac.resources.PrincipalResource;
import com.mfec.dac.resources.SourceResource;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.SourceProbe;
import com.mfec.dac.resources.SyncResource;
import com.mfec.dac.resources.SystemResource;
import com.mfec.dac.resources.WebhookResource;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
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
    // Instants as ISO-8601 everywhere, set on the bootstrap mapper so that the
    // environment's copy and Jersey's message body writer both inherit it.
    bootstrap.getObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // Secrets live in the environment, never in the committed yaml.
    bootstrap.setConfigurationSourceProvider(
        new SubstitutingSourceProvider(
            bootstrap.getConfigurationSourceProvider(),
            new EnvironmentVariableSubstitutor(false)));
  }

  @Override
  public void run(DacConfiguration config, Environment environment) {
    // Instants as ISO-8601, not as an epoch float. Jackson's default writes
    // 1789832026.308722, which every consumer then has to guess the unit of --
    // and the header chip that read it as milliseconds reported a crawl from
    // 1970. A timestamp that crosses an HTTP boundary should say what it means.
    environment.getObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    // And handed to Jersey explicitly: its writer resolves a mapper through a
    // ContextResolver before it considers the one the environment holds.
    environment.jersey().register(new JsonMapperProvider(environment.getObjectMapper()));

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

    OpenMetadataConfiguration om = config.getOpenMetadata();
    OpenMetadataClient omClient =
        new OpenMetadataClient(
            om.getBaseUrl(),
            om.getJwtToken(),
            om.getExpectedVersion(),
            om.getConnectTimeoutMs(),
            om.getReadTimeoutMs());
    // Checked at startup rather than at the first crawl: the generated client is
    // built from a spec pinned at one version, and finding out it no longer fits
    // halfway through a crawl leaves the cache half rewritten.
    omClient.checkVersion(om.isFailOnVersionMismatch());
    CatalogSyncService sync =
        new CatalogSyncService(jdbi, environment.getObjectMapper(), omClient);
    SyncStateDao syncState = new SyncStateDao(jdbi);
    CatalogChangeApplier applier =
        new CatalogChangeApplier(jdbi, environment.getObjectMapper(), omClient);

    environment.healthChecks().register("app-db", new AppDatabaseHealthCheck(jdbi));
    environment.jersey().register(new SystemResource(config));
    environment.jersey().register(new AuthResource(identities, tokens, identity));
    environment.jersey().register(new SyncResource(sync));
    // The connection itself, read-only: the page it feeds exists to say what
    // this deployment is pointed at and whether it answers, not to let a
    // browser rewrite the credential it is pointed at with.
    environment.jersey().register(new OpenMetadataSettingsResource(om, omClient, sync));
    environment.jersey().register(
        new CatalogResource(new CatalogQuery(jdbi, environment.getObjectMapper())));

    // Policy authoring. The materialiser takes the loader rather than building
    // one so that the webhook path and the authoring path resolve bindings
    // through the same read model, and cannot drift apart in how they read a
    // facet (FR-3.1.6).
    AssetContextLoader contexts = new AssetContextLoader(environment.getObjectMapper());
    PolicyStore policyStore = new PolicyStore(jdbi, environment.getObjectMapper());
    PolicyBindingMaterializer materializer =
        new PolicyBindingMaterializer(jdbi, environment.getObjectMapper(), contexts);
    // The expression evaluator is wired here and nowhere else. An engine built
    // without one treats every `expr` as undecidable and fails closed, which
    // looks exactly like a policy that simply does not grant — so the moment a
    // policy uses a cross-side comparison, forgetting this line becomes an
    // outage that reads as correct behaviour (FR-3.2, FR-2A.4).
    //
    // Built here rather than beside the runtime resources below because
    // authoring needs it too: impact analysis (FR-5.3) answers "what changes if
    // this is activated" by running this same engine twice, and an authoring
    // screen that judged a policy with a different engine than the one that
    // will enforce it would be worse than having no screen.
    PolicyEngine engine =
        new PolicyEngine(
            EngineConfig.defaults()
                .withZone(ZoneId.of("Asia/Bangkok"))
                .withExpressions(new PolicyExpressionEvaluator()));
    PrincipalLoader principalLoader = new PrincipalLoader();
    environment
        .jersey()
        .register(
            new PolicyResource(
                policyStore,
                materializer,
                new PolicyOverview(jdbi, environment.getObjectMapper()),
                new ImpactAnalysis(jdbi, contexts, principalLoader, policyStore, engine)));

    // The vocabulary a selector is written against, and the people a subject
    // rule is written about. Both are read-only: OpenMetadata and Entra own
    // this content, and an edit here would be reverted by the next sync.
    environment.jersey().register(new GovernanceResource(new GovernanceQuery(jdbi)));
    environment.jersey().register(new SearchResource(new SearchQuery(jdbi)));
    environment.jersey().register(
        new PrincipalResource(
            new PrincipalQuery(jdbi), new IdentityAdminStore(jdbi, identities)));

    // The source registry (FR-6.0a). The probe is constructed here, with the
    // process environment behind it, so that the only component able to turn a
    // credential reference into a credential is one the application wired
    // itself — a resource that built its own resolver could be handed a
    // different environment by a test and nobody would notice.
    DataSourceStore sources = new DataSourceStore(jdbi);
    SourceCatalogImporter importer =
        new SourceCatalogImporter(jdbi, sources, new JdbcIntrospector());
    environment.jersey().register(new SourceResource(sources, new SourceProbe(), importer));

    // Runtime enforcement, mode 5.2. This is the first place the engine is
    // asked anything at request time rather than at authoring time, and the
    // three components below are deliberately one chain: a decision is made
    // once, rendered once, and the rendered form is what runs. Giving the
    // simulator its own path would mean the thing people check and the thing
    // that enforces could disagree.
    DecisionService decisionService =
        new DecisionService(jdbi, contexts, principalLoader, policyStore, engine);
    environment.jersey().register(new DecisionResource(decisionService));
    environment.jersey().register(
        new QueryResource(
            new QueryService(
                jdbi,
                environment.getObjectMapper(),
                sources,
                decisionService,
                new QueryExecutor())));
    // Registered before the auth filter for no reason other than reading order;
    // the filter is a @Secured name binding and this resource carries no
    // annotation, so it is never in its path. Its authentication is the HMAC.
    environment.jersey().register(
        new WebhookResource(environment.getObjectMapper(), applier, om.getWebhookSecret()));
    environment.jersey().register(new AuthFilter(tokens));

    startCatalogSync(environment, om, omClient, sync, applier, syncState);

    LOG.info("Data Access Control Platform started against OpenMetadata {}",
        config.getOpenMetadata().getBaseUrl());
  }

  /**
   * Starts the two things that keep the cache current (FR-1.5).
   *
   * <p>Both are {@link io.dropwizard.lifecycle.Managed} so that a shutdown stops
   * them before the connection pool closes underneath them. A poll caught
   * mid-apply by a closing pool would fail, and the cursor would stay where it
   * was — harmless, but it fills the log with a failure that is really just a
   * restart.
   */
  private void startCatalogSync(
      Environment environment,
      OpenMetadataConfiguration om,
      OpenMetadataClient omClient,
      CatalogSyncService sync,
      CatalogChangeApplier applier,
      SyncStateDao syncState) {

    if (om.getWebhookSecret() == null || om.getWebhookSecret().isBlank()) {
      LOG.warn(
          "No OM_WEBHOOK_SECRET is set: POST /v1/webhooks/openmetadata will refuse every "
              + "delivery. Changes will still arrive, but only as fast as the poller reads them.");
    }

    if (om.isPollEnabled()) {
      environment
          .lifecycle()
          .manage(
              new ChangeEventPoller(
                  omClient,
                  applier,
                  syncState,
                  om.getMaxEventsPerPoll(),
                  Duration.ofSeconds(om.getPollIntervalSeconds())));
    } else {
      LOG.warn(
          "Change-event polling is disabled. A missed webhook then stays missed until the "
              + "nightly reconcile.");
    }

    if (om.isReconcileEnabled()) {
      environment
          .lifecycle()
          .manage(
              new NightlyReconcile(
                  sync,
                  syncState,
                  LocalTime.parse(om.getReconcileAt()),
                  ZoneId.of(om.getReconcileZone())));
    }
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
