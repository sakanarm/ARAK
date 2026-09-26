package com.mfec.dac.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.TableScope;
import com.mfec.dac.source.jdbc.JdbcIntrospector;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
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
 * The import with a table scope, against a real catalogue database.
 *
 * <p>The source database is stood in for: what is under test is which rows the
 * import writes and what it calls the ones it did not read, not JDBC metadata.
 * The stand-in applies the scope's test the way the introspector does, to the
 * list of names before any column is read.
 */
@Testcontainers
class SourceCatalogImporterIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static Jdbi jdbi;
  private final JdbcIntrospector introspector = mock(JdbcIntrospector.class);
  private DataSourceStore sources;
  private SourceCatalogImporter importer;
  private List<JdbcIntrospector.Table> atSource;

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
  @SuppressWarnings("unchecked")
  void clean() throws Exception {
    jdbi.useHandle(
        handle -> {
          handle.execute("TRUNCATE asset_fqn_map CASCADE");
          handle.execute("TRUNCATE asset CASCADE");
          handle.execute("TRUNCATE data_source CASCADE");
        });
    sources = new DataSourceStore(jdbi);
    importer = new SourceCatalogImporter(jdbi, sources, introspector);
    atSource =
        new ArrayList<>(
            List.of(table("sales", "customer"), table("sales", "orders"), table("sales", "tmp_load")));
    when(introspector.tables(any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              BiPredicate<String, String> keep = call.getArgument(3, BiPredicate.class);
              return atSource.stream().filter(t -> keep.test(t.schema(), t.name())).toList();
            });
  }

  private static JdbcIntrospector.Table table(String schema, String name) {
    return new JdbcIntrospector.Table(
        "salesdb",
        schema,
        name,
        "TABLE",
        List.of(new JdbcIntrospector.Column("id", 1, "int4", 10, false)));
  }

  private DataSourceStore.Source register(TableScope scope) {
    return sources.create(
        new DataSourceStore.SourceInput(
            "demo_pg", "POSTGRES", null, "db.example.test", 5432, "salesdb",
            "vault://secret/data/demo", "NONE", null, null, null, true, null, null, scope));
  }

  private static final TableScope NO_TMP =
      new TableScope(
          TableScope.Mode.ALL,
          List.of(),
          List.of(new TableScope.Rule(TableScope.Match.STARTS_WITH, "tmp_")));

  private List<String> currentTables() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT fqn FROM asset WHERE is_current AND asset_type = 'TABLE' ORDER BY fqn")
                .mapTo(String.class)
                .list());
  }

  private String verification(String fqn) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT verification_status FROM asset_fqn_map WHERE om_fqn = :fqn")
                .bind("fqn", fqn)
                .mapTo(String.class)
                .one());
  }

  @Test
  @DisplayName("a table the scope leaves out is not imported, and is counted")
  void theScopeDecidesWhatIsImported() {
    DataSourceStore.Source source = register(NO_TMP);

    SourceCatalogImporter.Report report = importer.importFrom(source.id(), null);

    assertThat(report.tables()).isEqualTo(2);
    assertThat(report.excluded()).isEqualTo(1);
    assertThat(report.outOfScope()).isEmpty();
    assertThat(currentTables())
        .containsExactly("demo_pg.salesdb.sales.customer", "demo_pg.salesdb.sales.orders");
  }

  @Test
  @DisplayName("narrowing the scope later keeps the tables it now leaves out, and does not orphan them")
  void narrowingKeepsWhatWasImported() {
    DataSourceStore.Source source = register(null);
    importer.importFrom(source.id(), null);
    sources.update(
        source.id(),
        new DataSourceStore.SourceInput(
            "demo_pg", "POSTGRES", null, "db.example.test", 5432, "salesdb",
            "vault://secret/data/demo", "NONE", null, null, null, true, null, null, NO_TMP));

    SourceCatalogImporter.Report report = importer.importFrom(source.id(), null);

    assertThat(report.outOfScope()).containsExactly("demo_pg.salesdb.sales.tmp_load");
    assertThat(report.missingTables()).isEmpty();
    assertThat(verification("demo_pg.salesdb.sales.tmp_load")).isEqualTo("MATCHED");
    assertThat(currentTables()).contains("demo_pg.salesdb.sales.tmp_load");
  }

  @Test
  @DisplayName("a table in scope that is gone from the source is still called orphaned")
  void aDroppedTableInScopeIsOrphaned() {
    DataSourceStore.Source source = register(NO_TMP);
    importer.importFrom(source.id(), null);
    atSource.removeIf(t -> t.name().equals("orders"));

    SourceCatalogImporter.Report report = importer.importFrom(source.id(), null);

    assertThat(report.missingTables()).containsExactly("demo_pg.salesdb.sales.orders");
    assertThat(report.outOfScope()).isEmpty();
    assertThat(verification("demo_pg.salesdb.sales.orders")).isEqualTo("ORPHANED");
  }

  @Test
  @DisplayName("a scope on schema.table reads only the schema it names")
  void aSchemaScope() {
    atSource.add(table("staging", "customer"));
    DataSourceStore.Source source =
        register(
            new TableScope(
                TableScope.Mode.ONLY,
                List.of(new TableScope.Rule(TableScope.Match.STARTS_WITH, "staging.")),
                List.of()));

    SourceCatalogImporter.Report report = importer.importFrom(source.id(), null);

    assertThat(report.excluded()).isEqualTo(3);
    assertThat(currentTables()).containsExactly("demo_pg.salesdb.staging.customer");
  }

  private void linkTo(DataSourceStore.Source source, String omService) {
    sources.update(
        source.id(),
        new DataSourceStore.SourceInput(
            "demo_pg", "POSTGRES", null, "db.example.test", 5432, "salesdb",
            "vault://secret/data/demo", "NONE", omService, null, null, true, null, null, NO_TMP));
  }

  private String mappedFqn(String object) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT om_fqn FROM asset_fqn_map WHERE object_name = :object")
                .bind("object", object)
                .mapTo(String.class)
                .one());
  }

  @Test
  @DisplayName("a source linked to an OpenMetadata service imports under that service's name")
  void aLinkedSourceImportsUnderTheServiceName() {
    DataSourceStore.Source source = register(NO_TMP);
    linkTo(source, "sales_service");

    importer.importFrom(source.id(), null);

    assertThat(currentTables())
        .containsExactly(
            "sales_service.salesdb.sales.customer", "sales_service.salesdb.sales.orders");
    assertThat(mappedFqn("customer")).isEqualTo("sales_service.salesdb.sales.customer");
  }

  @Test
  @DisplayName("linking after an import moves the tables to the service's name, and re-imports cleanly")
  void linkingLaterMovesTheImport() {
    DataSourceStore.Source source = register(NO_TMP);
    importer.importFrom(source.id(), null);
    linkTo(source, "sales_service");

    SourceCatalogImporter.Report report = importer.importFrom(source.id(), null);
    // Twice: the physical table has one claim, so a second run must not collide.
    importer.importFrom(source.id(), null);

    assertThat(report.missingTables()).isEmpty();
    assertThat(currentTables())
        .containsExactly(
            "sales_service.salesdb.sales.customer", "sales_service.salesdb.sales.orders");
    assertThat(mappedFqn("orders")).isEqualTo("sales_service.salesdb.sales.orders");
    assertThat(verification("sales_service.salesdb.sales.orders")).isEqualTo("MATCHED");
    // Nothing is left current under the old name, the containers included.
    long left =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT (SELECT count(*) FROM asset
                                 WHERE is_current AND (fqn = 'demo_pg' OR fqn LIKE 'demo!_pg.%' ESCAPE '!'))
                             + (SELECT count(*) FROM asset_column
                                 WHERE is_current AND fqn LIKE 'demo!_pg.%' ESCAPE '!')
                        """)
                    .mapTo(Long.class)
                    .one());
    assertThat(left).isZero();
  }

  @Test
  @DisplayName("a table OpenMetadata already crawled is verified, not duplicated or taken over")
  void theCrawledTableIsTheSameAsset() {
    jdbi.useHandle(
        handle ->
            handle.execute(
                """
                INSERT INTO asset (fqn, asset_type, parent_fqn, name, description, provenance, valid_from)
                VALUES ('sales_service.salesdb.sales.customer', 'TABLE', 'sales_service.salesdb.sales',
                        'customer', 'Written in OpenMetadata', 'openmetadata', now())
                """));
    DataSourceStore.Source source = register(NO_TMP);
    linkTo(source, "sales_service");

    SourceCatalogImporter.Report report = importer.importFrom(source.id(), null);

    assertThat(report.newTables()).isEqualTo(1);
    List<String> kept =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT provenance || ':' || description || ':' || (data_source_id IS NOT NULL)
                          FROM asset WHERE fqn = 'sales_service.salesdb.sales.customer' AND is_current
                        """)
                    .mapTo(String.class)
                    .list());
    assertThat(kept).containsExactly("openmetadata:Written in OpenMetadata:true");
    assertThat(verification("sales_service.salesdb.sales.customer")).isEqualTo("MATCHED");
  }
}
