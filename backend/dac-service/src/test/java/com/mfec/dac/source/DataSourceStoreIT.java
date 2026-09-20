package com.mfec.dac.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The source registry against a real PostgreSQL.
 *
 * <p>The round-trip is the least interesting thing here. What is worth proving
 * is the three ways this table can hurt somebody: a password pasted into the
 * credential field, a schema name that carries SQL into generated DDL, and a
 * delete that cascades an entire crawled estate away because the guard counted
 * the wrong rows.
 */
@Testcontainers
class DataSourceStoreIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static Jdbi jdbi;
  private DataSourceStore store;

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbi.installPlugins();
  }

  @BeforeEach
  void clean() {
    jdbi.useHandle(handle -> handle.execute("TRUNCATE data_source CASCADE"));
    store = new DataSourceStore(jdbi);
  }

  private static DataSourceStore.SourceInput valid(String name) {
    return new DataSourceStore.SourceInput(
        name, "POSTGRES", null, "db.internal", 5432, "salesdb",
        "vault://secret/data/sources/" + name, "SECURE_VIEW", name, "sec", "{table}_secure", true);
  }

  @Test
  @DisplayName("a source keeps every field it was given, and defaults the rest")
  void createsAndReadsBack() {
    DataSourceStore.Source created = store.create(valid("prod_mssql"));

    assertThat(created.id()).isNotNull();
    assertThat(created.engine()).isEqualTo(DataSourceStore.Engine.POSTGRES);
    assertThat(created.defaultEnforcementMode())
        .isEqualTo(DataSourceStore.EnforcementMode.SECURE_VIEW);
    assertThat(created.secureObjectPattern()).isEqualTo("{table}_secure");
    assertThat(created.assetCount()).isZero();
    assertThat(store.find(created.id())).contains(created);
    assertThat(store.list()).containsExactly(created);
  }

  @Test
  @DisplayName("an omitted port becomes the engine's own default, not zero")
  void defaultsThePortPerEngine() {
    DataSourceStore.Source postgres =
        store.create(
            new DataSourceStore.SourceInput(
                "pg", "postgres", null, "h", null, null, "env:PG", null, null, null, null, null));
    DataSourceStore.Source mssql =
        store.create(
            new DataSourceStore.SourceInput(
                "ms", "sqlserver", null, "h", null, null, "env:MS", null, null, null, null, null));

    assertThat(postgres.port()).isEqualTo(5432);
    assertThat(mssql.port()).isEqualTo(1433);
    // Nothing is enforced until somebody chooses to enforce it.
    assertThat(postgres.defaultEnforcementMode()).isEqualTo(DataSourceStore.EnforcementMode.NONE);
  }

  @Test
  @DisplayName("a password in the credential field is refused, whatever it looks like")
  void refusesARawCredential() {
    // Deliberately unusable strings: a fixture that reads like a real password
    // in a public repository invites somebody to try it somewhere.
    for (String pasted : new String[] {"not-a-real-secret", "sa/not-a-real-secret", "postgres:nope"}) {
      assertThatThrownBy(
              () ->
                  store.create(
                      new DataSourceStore.SourceInput(
                          "s", "POSTGRES", null, "h", null, null, pasted, null, null, null, null,
                          null)))
          .isInstanceOf(DataSourceStore.InvalidSourceException.class)
          .hasMessageContaining("never holds the secret");
    }

    // A blank field is not a bad scheme, it is an unanswered question, and it
    // gets the sentence that asks it rather than the one about secrets.
    assertThatThrownBy(
            () ->
                store.create(
                    new DataSourceStore.SourceInput(
                        "s", "POSTGRES", null, "h", null, null, "   ", null, null, null, null,
                        null)))
        .isInstanceOf(DataSourceStore.InvalidSourceException.class)
        .hasMessageContaining("a credential reference, not a credential");
  }

  @Test
  @DisplayName("only the schemes this platform can resolve are accepted")
  void acceptsOnlyKnownSchemes() {
    for (String reference :
        new String[] {
          "vault://secret/data/x", "fernet://x", "azurekeyvault://v/s", "env:SOURCE_CRED"
        }) {
      DataSourceStore.Source created =
          store.create(
              new DataSourceStore.SourceInput(
                  "s" + reference.hashCode(), "POSTGRES", null, "h", null, null, reference, null,
                  null, null, null, null));
      assertThat(created.credentialRef()).isEqualTo(reference);
    }
  }

  @Test
  @DisplayName("a secure schema that is not a plain identifier never reaches the DDL generator")
  void refusesInjectionInDdlIdentifiers() {
    // An empty field is left out: it means "use the default", and the default
    // is the identifier `sec`, which is safe by construction.
    String[] hostile = {"sec; DROP TABLE customer", "sec\"", "pg_temp.x", "1sec", "sec-1"};
    for (String schema : hostile) {
      assertThatThrownBy(
              () ->
                  store.create(
                      new DataSourceStore.SourceInput(
                          "s", "POSTGRES", null, "h", null, null, "env:C", null, null, schema, null,
                          null)))
          .isInstanceOf(DataSourceStore.InvalidSourceException.class);
    }

    assertThatThrownBy(
            () ->
                store.create(
                    new DataSourceStore.SourceInput(
                        "s", "POSTGRES", null, "h", null, null, "env:C", null, null, "sec",
                        "{table}\"; DROP TABLE x --", null)))
        .isInstanceOf(DataSourceStore.InvalidSourceException.class)
        .hasMessageContaining("object name in generated DDL");
  }

  @Test
  @DisplayName("an object pattern without {table} is refused, because every view would collide")
  void refusesAPatternThatNamesEverythingTheSame() {
    assertThatThrownBy(
            () ->
                store.create(
                    new DataSourceStore.SourceInput(
                        "s", "POSTGRES", null, "h", null, null, "env:C", null, null, "sec",
                        "secure_view", null)))
        .isInstanceOf(DataSourceStore.InvalidSourceException.class)
        .hasMessageContaining("{table}");
  }

  @Test
  @DisplayName("a duplicate name is a conflict, not a second source")
  void refusesADuplicateName() {
    store.create(valid("prod_pg"));
    assertThatThrownBy(() -> store.create(valid("prod_pg")))
        .isInstanceOf(DataSourceStore.SourceConflictException.class)
        .hasMessageContaining("already exists");
  }

  @Test
  @DisplayName("an unknown id is a 404 on update, enable and delete alike")
  void reportsAnUnknownId() {
    UUID absent = UUID.randomUUID();
    assertThatThrownBy(() -> store.update(absent, valid("x")))
        .isInstanceOf(DataSourceStore.NoSuchSourceException.class);
    assertThatThrownBy(() -> store.setEnabled(absent, false))
        .isInstanceOf(DataSourceStore.NoSuchSourceException.class);
    assertThatThrownBy(() -> store.delete(absent))
        .isInstanceOf(DataSourceStore.NoSuchSourceException.class);
  }

  @Test
  @DisplayName("disabling keeps the row and everything known about it")
  void disablesWithoutLosingAnything() {
    DataSourceStore.Source created = store.create(valid("prod_pg"));
    store.recordEngineVersion(created.id(), "16.4 (Debian)");

    DataSourceStore.Source disabled = store.setEnabled(created.id(), false);

    assertThat(disabled.enabled()).isFalse();
    assertThat(disabled.engineVersion()).isEqualTo("16.4 (Debian)");
    assertThat(disabled.host()).isEqualTo(created.host());
  }

  @Test
  @DisplayName("an empty source can be deleted")
  void deletesAnEmptySource() {
    DataSourceStore.Source created = store.create(valid("scratch"));
    store.delete(created.id());
    assertThat(store.find(created.id())).isEmpty();
  }

  @Test
  @DisplayName("a source holding tables refuses to be deleted, and says how many")
  void refusesToCascadeAwayAnEstate() {
    DataSourceStore.Source created = store.create(valid("prod_pg"));
    insertAsset(created.id(), "TABLE", "prod_pg.salesdb.dbo.customer");
    insertAsset(created.id(), "VIEW", "prod_pg.salesdb.dbo.customer_v");

    assertThat(store.find(created.id()).orElseThrow().assetCount()).isEqualTo(2);
    assertThatThrownBy(() -> store.delete(created.id()))
        .isInstanceOf(DataSourceStore.SourceConflictException.class)
        .hasMessageContaining("2 catalogued objects");
  }

  @Test
  @DisplayName("containers count too: a crawled but empty source is not a free delete")
  void refusesEvenWhenOnlyContainersWereCrawled() {
    DataSourceStore.Source created = store.create(valid("prod_pg"));
    insertAsset(created.id(), "SERVICE", "prod_pg");
    insertAsset(created.id(), "DATABASE", "prod_pg.salesdb");
    insertAsset(created.id(), "SCHEMA", "prod_pg.salesdb.dbo");

    // The registry screen shows nought tables, which is true and is why the
    // table-and-view count cannot be what guards the delete: dropping these
    // three rows would make the next crawl mint new ids and orphan every
    // binding that pointed at the old ones.
    assertThat(store.find(created.id()).orElseThrow().assetCount()).isZero();
    assertThatThrownBy(() -> store.delete(created.id()))
        .isInstanceOf(DataSourceStore.SourceConflictException.class)
        .hasMessageContaining("3 catalogued objects");
  }

  @Test
  @DisplayName("a retired asset does not hold a source hostage")
  void ignoresSupersededVersions() {
    DataSourceStore.Source created = store.create(valid("prod_pg"));
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO asset (data_source_id, fqn, asset_type, name, valid_from, valid_to,
                                       is_current)
                    VALUES (:sourceId, 'prod_pg.salesdb.dbo.old', 'TABLE', 'old',
                            now() - interval '2 days', now() - interval '1 day', false)
                    """)
                .bind("sourceId", created.id())
                .execute());

    store.delete(created.id());
    assertThat(store.find(created.id())).isEmpty();
  }

  private void insertAsset(UUID sourceId, String type, String fqn) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO asset (data_source_id, fqn, asset_type, name, valid_from, is_current)
                    VALUES (:sourceId, :fqn, :type, :name, now(), true)
                    """)
                .bind("sourceId", sourceId)
                .bind("fqn", fqn)
                .bind("type", type)
                .bind("name", fqn.substring(fqn.lastIndexOf('.') + 1))
                .execute());
  }
}
