package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.engine.AssetContext;
import com.mfec.dac.engine.ColumnContext;
import com.mfec.dac.engine.EngineConfig;
import com.mfec.dac.engine.PolicyEngine;
import com.mfec.dac.engine.Principal;
import com.mfec.dac.engine.RequestContext;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask.ScopeLevel;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.api.ResolvedRowPredicate.FacetOperator;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.DataPolicy;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import com.mfec.dac.schema.entity.policy.LookupKey;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.RowFilter;
import com.mfec.dac.schema.entity.policy.RowLookup;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.CredentialResolver;
import com.mfec.dac.source.jdbc.QueryExecutor;
import io.dropwizard.jackson.Jackson;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
 * A row filter whose allowed values live in a mapping table, end to end: the
 * real engine decides, the query proxy binds and rewrites, and a real Postgres
 * answers.
 *
 * <p>The case it exists for: a table with a row for division A and a row for
 * division B, a person in department AA, and a mapping that puts AA in
 * division A. They see A's row and nothing else, whether the mapping is joined
 * into the statement or read first; and every case where which rows they may
 * see cannot be known is no rows or a refused query, never every row.
 */
@Testcontainers
class LookupRowFilterIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
  private static final Instant NOW = Instant.parse("2026-09-15T02:00:00Z");
  private static final PolicyEngine ENGINE =
      new PolicyEngine(EngineConfig.defaults().withZone(BANGKOK));
  private static final String SQL = "SELECT division, value FROM sales.results ORDER BY division";

  private static Jdbi jdbi;
  private static String database;

  private final DecisionService decisions = mock(DecisionService.class);
  private final AtomicReference<RowLookup> lookup = new AtomicReference<>();
  /** When set, the filter picks its column by this selector instead of naming division. */
  private final AtomicReference<AssetSelector> byTag = new AtomicReference<>();
  private final Map<String, Principal> people =
      Map.of(
          "reader_aa", person("reader_aa", "AA"),
          "reader_zz", person("reader_zz", "ZZ"),
          "reader_none", person("reader_none"),
          "reader_big", person("reader_big", "BIG"),
          "reader_exact", person("reader_exact", "EXACT"),
          "reader_quote", person("reader_quote", "AA' OR '1'='1"));
  private QueryExecutor executor;
  private QueryService service;
  private UUID tables;
  private UUID mappings;

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbi.installPlugins();
    database = POSTGRES.getDatabaseName();
    // The source's own data, beside ARAK's tables in schemas of its own.
    jdbi.useHandle(
        h -> {
          h.execute("CREATE SCHEMA sales");
          h.execute("CREATE SCHEMA ref");
          h.execute("CREATE TABLE sales.results (division varchar(10), value int)");
          h.execute("INSERT INTO sales.results VALUES ('A', 123), ('B', 456), ('D0500', 789)");
          h.execute(
              "CREATE TABLE ref.department_division (department varchar(40), division varchar(10))");
          h.execute(
              "CREATE TABLE ref.department_division_fixed (department varchar(40), division char(3))");
          h.execute("INSERT INTO ref.department_division_fixed VALUES ('AA', 'A')");
          h.execute("CREATE TABLE ref.big_map (department varchar(40), division varchar(10))");
          h.execute(
              "INSERT INTO ref.big_map SELECT 'BIG', 'D' || lpad(g::text, 4, '0')"
                  + " FROM generate_series(1, 1001) g");
          h.execute(
              "INSERT INTO ref.big_map SELECT 'EXACT', 'D' || lpad(g::text, 4, '0')"
                  + " FROM generate_series(1, 1000) g");
          h.execute("CREATE TABLE ref.department_since (department varchar(40), since timestamptz)");
          h.execute("INSERT INTO ref.department_since VALUES ('AA', '2026-01-01T00:00:00Z')");
        });
  }

  @BeforeEach
  void setUp() {
    jdbi.useHandle(
        h -> {
          h.execute("DELETE FROM audit_query");
          h.execute("DELETE FROM audit_decision");
          h.execute("DELETE FROM asset_fqn_map");
          h.execute("DELETE FROM asset_column");
          h.execute("DELETE FROM asset");
          h.execute("DELETE FROM data_source");
          h.execute("DELETE FROM ref.department_division");
          h.execute("INSERT INTO ref.department_division VALUES ('AA', 'A')");
        });
    tables = source("lk-pg");
    mappings = source("map-pg");
    catalogue(tables, "lk-pg", "sales", "results", "division", "value");
    catalogue(tables, "lk-pg", "ref", "department_division", "department", "division");
    catalogue(tables, "lk-pg", "ref", "department_division_fixed", "department", "division");
    catalogue(tables, "lk-pg", "ref", "big_map", "department", "division");
    catalogue(tables, "lk-pg", "ref", "department_since", "department", "since");
    catalogue(mappings, "map-pg", "ref", "department_division", "department", "division");

    when(decisions.decide(any()))
        .thenAnswer(
            call -> {
              DecisionService.Ask ask = call.getArgument(0);
              return decide(ask.principal(), ask.assetFqn());
            });
    CredentialResolver credentials =
        new CredentialResolver(
            name ->
                "IT_LOOKUP_LOGIN".equals(name)
                    ? POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                    : null);
    executor = new QueryExecutor(credentials, 10);
    service = service(Jackson.newObjectMapper());
  }

  // ------------------------------------------------------------------ setup

  private QueryService service(ObjectMapper json) {
    // The result cache is on, so a test that sees a change to the mapping has
    // shown that a held result did not hide it.
    return new QueryService(
        jdbi,
        json,
        new DataSourceStore(jdbi),
        decisions,
        executor,
        new QueryResultCache(true, 100, 10_000, Duration.ofSeconds(30)),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static Principal person(String id, String... departments) {
    Principal.Builder builder = Principal.withId(id).roles("analyst");
    for (String department : departments) {
      builder.attribute("department", department);
    }
    return builder.build();
  }

  private static UUID source(String name) {
    return jdbi.withHandle(
        h ->
            h.createQuery(
                    """
                    INSERT INTO data_source (name, engine, host, port, default_database,
                                             credential_ref, om_service_fqn)
                    VALUES (:name, 'POSTGRES', :host, :port, :database, 'env:IT_LOOKUP_LOGIN', :name)
                    RETURNING id
                    """)
                .bind("name", name)
                .bind("host", POSTGRES.getHost())
                .bind("port", POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
                .bind("database", database)
                .mapTo(UUID.class)
                .one());
  }

  private static String fqn(String service, String schema, String table) {
    return service + "." + database + "." + schema + "." + table;
  }

  private static void catalogue(
      UUID source, String service, String schema, String table, String... columns) {
    String fqn = fqn(service, schema, table);
    jdbi.useHandle(
        h -> {
          UUID asset =
              h.createQuery(
                      "INSERT INTO asset (data_source_id, fqn, asset_type, name)"
                          + " VALUES (:source, :fqn, 'TABLE', :name) RETURNING id")
                  .bind("source", source)
                  .bind("fqn", fqn)
                  .bind("name", table)
                  .mapTo(UUID.class)
                  .one();
          h.createUpdate(
                  "INSERT INTO asset_fqn_map (om_fqn, data_source_id, database_name, schema_name,"
                      + " object_name, object_kind, verification_status)"
                      + " VALUES (:fqn, :source, :database, :schema, :table, 'TABLE', 'MATCHED')")
              .bind("fqn", fqn)
              .bind("source", source)
              .bind("database", database)
              .bind("schema", schema)
              .bind("table", table)
              .execute();
          int ordinal = 1;
          for (String column : columns) {
            h.createUpdate(
                    "INSERT INTO asset_column (asset_id, fqn, name, ordinal)"
                        + " VALUES (:asset, :fqn, :name, :ordinal)")
                .bind("asset", asset)
                .bind("fqn", fqn + "." + column)
                .bind("name", column)
                .bind("ordinal", ordinal++)
                .execute();
          }
        });
  }

  private static Policy policy(String name, Policy.PolicyType type) {
    return new Policy()
        .withId(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)))
        .withName(name)
        .withVersion(1)
        .withPolicyType(type)
        .withScopeLevel(ScopeLevel.ORG)
        .withEffect(Policy.Effect.ALLOW)
        .withSelector(
            new AssetSelector()
                .withCondition(
                    new FacetCondition()
                        .withFacet(FacetType.TABLE)
                        .withOperator(FacetOperator.EQ)
                        .withValue("results")));
  }

  /** What the engine decides for this person on this table, under the lookup set now. */
  private PolicyDecision decide(String principal, String assetFqn) {
    AssetContext asset =
        AssetContext.of(assetFqn)
            .physicalFromFqn()
            .column(
                ColumnContext.named("division")
                    .dataType("VARCHAR")
                    .facet(FacetType.TAGS, "Org", "Org.Division"))
            .column(ColumnContext.named("value").dataType("INTEGER"))
            .build();
    RowFilter filter = new RowFilter().withKind(RowFilter.Kind.LOOKUP).withLookup(lookup.get());
    filter = byTag.get() == null ? filter.withColumn("division") : filter.withColumns(byTag.get());
    List<Policy> policies =
        List.of(
            policy("door", Policy.PolicyType.SUBSCRIPTION),
            policy("divisions-by-department", Policy.PolicyType.DATA)
                .withData(new DataPolicy().withRowFilters(List.of(filter))));
    return ENGINE.evaluate(people.get(principal), asset, RequestContext.at(NOW), policies);
  }

  private static RowLookup mapping(String table, RowLookup.Mode mode) {
    return mapping(table, "division", mode);
  }

  private static RowLookup mapping(String table, String valueColumn, RowLookup.Mode mode) {
    return new RowLookup()
        .withTable(table)
        .withKeys(List.of(new LookupKey().withColumn("department").withUserAttribute("department")))
        .withValueColumn(valueColumn)
        .withMode(mode);
  }

  private QueryService.Result run(String principal) throws Exception {
    return service.run(tables, SQL, principal, principal, 50, null, null);
  }

  private List<List<Object>> rows(String principal) throws Exception {
    return run(principal).rows();
  }

  private record Logged(String outcome, String reason) {}

  private static List<Logged> log() {
    return jdbi.withHandle(
        h ->
            h.createQuery("SELECT outcome, reject_reason FROM audit_query ORDER BY id")
                .map((rs, ctx) -> new Logged(rs.getString(1), rs.getString(2)))
                .list());
  }

  private static void mapAlso(String department, String division) {
    jdbi.useHandle(
        h ->
            h.createUpdate("INSERT INTO ref.department_division VALUES (:department, :division)")
                .bind("department", department)
                .bind("division", division)
                .execute());
  }

  // ------------------------------------------------------ the case it is for

  @Test
  @DisplayName("joined into the statement: department AA sees division A's row only")
  void joinedSeesOnlyTheMappedDivision() throws Exception {
    lookup.set(mapping(fqn("lk-pg", "ref", "department_division"), RowLookup.Mode.SUBQUERY));

    QueryService.Result result = run("reader_aa");

    assertThat(result.rows()).containsExactly(List.of("A", 123));
    assertThat(result.rewrittenSql())
        .contains("IN (SELECT")
        .contains("\"ref\".\"department_division\"")
        .contains("IN ('AA')");
    assertThat(result.explanations().get(0).rowFilters())
        .containsExactly(
            "division is one of the division values in "
                + fqn("lk-pg", "ref", "department_division")
                + " for department AA");
  }

  @Test
  @DisplayName("read first: department AA sees division A's row only, and the list is what was sent")
  void readFirstSeesOnlyTheMappedDivision() throws Exception {
    lookup.set(mapping(fqn("lk-pg", "ref", "department_division"), RowLookup.Mode.READ_VALUES));

    QueryService.Result result = run("reader_aa");

    assertThat(result.rows()).containsExactly(List.of("A", 123));
    assertThat(result.rewrittenSql()).contains("IN ('A')").doesNotContain("department_division");
    assertThat(result.explanations().get(0).rowFilters()).hasSize(1);
    assertThat(result.explanations().get(0).rowFilters().get(0))
        .endsWith(", read when the query ran");
  }

  @Test
  @DisplayName("a change to the mapping counts from the next query, in both modes, with the cache on")
  void aChangedMappingCountsAtOnce() throws Exception {
    for (RowLookup.Mode mode : RowLookup.Mode.values()) {
      jdbi.useHandle(h -> h.execute("DELETE FROM ref.department_division"));
      mapAlso("AA", "A");
      lookup.set(mapping(fqn("lk-pg", "ref", "department_division"), mode));

      assertThat(rows("reader_aa")).as(mode.name()).containsExactly(List.of("A", 123));

      mapAlso("AA", "B");
      QueryService.Result widened = run("reader_aa");
      assertThat(widened.rows())
          .as(mode.name())
          .containsExactly(List.of("A", 123), List.of("B", 456));
      assertThat(widened.cached()).as(mode.name()).isFalse();

      jdbi.useHandle(h -> h.execute("DELETE FROM ref.department_division"));
      QueryService.Result emptied = run("reader_aa");
      assertThat(emptied.rows()).as(mode.name()).isEmpty();
      assertThat(emptied.cached()).as(mode.name()).isFalse();
    }
  }

  // ------------------------------------------------------ the column by tag

  private static AssetSelector tagged(String tag) {
    return new AssetSelector()
        .withCondition(
            new FacetCondition()
                .withFacet(FacetType.TAGS)
                .withOperator(FacetOperator.CONTAINS)
                .withValue(tag));
  }

  @Test
  @DisplayName("the column picked by its tag is filtered the same way, joined or read first")
  void theColumnCanBePickedByTag() throws Exception {
    byTag.set(tagged("Org.Division"));
    for (RowLookup.Mode mode : RowLookup.Mode.values()) {
      lookup.set(mapping(fqn("lk-pg", "ref", "department_division"), mode));

      QueryService.Result result = run("reader_aa");

      assertThat(result.rows()).as(mode.name()).containsExactly(List.of("A", 123));
      assertThat(result.explanations().get(0).rowFilters())
          .as(mode.name())
          .singleElement()
          .asString()
          .startsWith("division is one of the division values in ");
    }
  }

  @Test
  @DisplayName("a tag that no column of the table carries shows no rows, in both modes")
  void aTagNoColumnCarriesShowsNothing() throws Exception {
    byTag.set(tagged("Org.Region"));
    for (RowLookup.Mode mode : RowLookup.Mode.values()) {
      lookup.set(mapping(fqn("lk-pg", "ref", "department_division"), mode));

      assertThat(rows("reader_aa")).as(mode.name()).isEmpty();
    }
  }

  // ------------------------------------------------------- who sees nothing

  @Test
  @DisplayName("a department the mapping does not know, or no department at all, sees no rows")
  void unknownOrMissingDepartmentSeesNothing() throws Exception {
    for (RowLookup.Mode mode : RowLookup.Mode.values()) {
      lookup.set(mapping(fqn("lk-pg", "ref", "department_division"), mode));

      assertThat(rows("reader_zz")).as(mode.name()).isEmpty();
      assertThat(rows("reader_none")).as(mode.name()).isEmpty();
    }
  }

  @Test
  @DisplayName("a department with a quote in it is compared as a value, never run as SQL")
  void aQuotedDepartmentIsOnlyAValue() throws Exception {
    for (RowLookup.Mode mode : RowLookup.Mode.values()) {
      lookup.set(mapping(fqn("lk-pg", "ref", "department_division"), mode));

      assertThat(rows("reader_quote")).as(mode.name()).isEmpty();
    }
  }

  // ------------------------------------------------------------ where it is

  @Test
  @DisplayName("a mapping on another source cannot be joined: refused, and the refusal is logged")
  void joiningAcrossSourcesIsRefused() {
    lookup.set(mapping(fqn("map-pg", "ref", "department_division"), RowLookup.Mode.SUBQUERY));

    assertThatThrownBy(() -> run("reader_aa"))
        .isInstanceOf(QueryService.RejectedException.class)
        .hasMessageContaining("another data source")
        .hasMessageContaining("READ_VALUES");
    assertThat(log()).hasSize(1);
    assertThat(log().get(0).outcome()).isEqualTo("REJECTED");
  }

  @Test
  @DisplayName("a mapping on another source can be read first")
  void readingAcrossSourcesWorks() throws Exception {
    lookup.set(mapping(fqn("map-pg", "ref", "department_division"), RowLookup.Mode.READ_VALUES));

    assertThat(rows("reader_aa")).containsExactly(List.of("A", 123));
  }

  @Test
  @DisplayName("a mapping whose source is switched off is not read")
  void aDisabledMappingSourceIsRefused() {
    lookup.set(mapping(fqn("map-pg", "ref", "department_division"), RowLookup.Mode.READ_VALUES));
    jdbi.useHandle(
        h -> h.createUpdate("UPDATE data_source SET enabled = false WHERE id = :id")
            .bind("id", mappings)
            .execute());

    assertThatThrownBy(() -> run("reader_aa"))
        .isInstanceOf(QueryService.RejectedException.class)
        .hasMessageContaining("missing or disabled");
  }

  @Test
  @DisplayName("a mapping that is not in the catalog is refused in both modes")
  void anUncataloguedMappingIsRefused() {
    for (RowLookup.Mode mode : RowLookup.Mode.values()) {
      lookup.set(mapping(fqn("lk-pg", "ref", "nowhere"), mode));

      assertThatThrownBy(() -> run("reader_aa"))
          .as(mode.name())
          .isInstanceOf(QueryService.RejectedException.class)
          .hasMessageContaining("not in the catalog");
    }
  }

  // ------------------------------------------------------------- the values

  @Test
  @DisplayName("more values than a list can carry is refused when read, and joined works")
  void aLongListIsRefusedAndTheJoinIsNot() throws Exception {
    lookup.set(mapping(fqn("lk-pg", "ref", "big_map"), RowLookup.Mode.READ_VALUES));
    assertThatThrownBy(() -> run("reader_big"))
        .isInstanceOf(QueryService.RejectedException.class)
        .hasMessageContaining("more than " + LookupBinder.MAX_VALUES);
    assertThat(rows("reader_exact")).containsExactly(List.of("D0500", 789));

    lookup.set(mapping(fqn("lk-pg", "ref", "big_map"), RowLookup.Mode.SUBQUERY));
    assertThat(rows("reader_big")).containsExactly(List.of("D0500", 789));
  }

  @Test
  @DisplayName("a fixed-width mapping column matches the same rows in both modes")
  void aPaddedMappingMatches() throws Exception {
    for (RowLookup.Mode mode : RowLookup.Mode.values()) {
      lookup.set(mapping(fqn("lk-pg", "ref", "department_division_fixed"), mode));

      assertThat(rows("reader_aa")).as(mode.name()).containsExactly(List.of("A", 123));
    }
  }

  @Test
  @DisplayName("a value that cannot be written back exactly is refused rather than approximated")
  void aTimestampIsNotListed() {
    lookup.set(
        mapping(fqn("lk-pg", "ref", "department_since"), "since", RowLookup.Mode.READ_VALUES));

    assertThatThrownBy(() -> run("reader_aa"))
        .isInstanceOf(QueryService.RejectedException.class)
        .hasMessageContaining("cannot be sent as a list");
  }

  // -------------------------------------------------------------- isolation

  @Test
  @DisplayName("binding never changes the decision it was handed, which a cache may share")
  void theHeldDecisionIsNotChanged() throws Exception {
    for (RowLookup.Mode mode : RowLookup.Mode.values()) {
      lookup.set(mapping(fqn("lk-pg", "ref", "department_division"), mode));
      PolicyDecision held = decide("reader_aa", fqn("lk-pg", "sales", "results"));
      // doReturn: when(...) would call the answer already stubbed, with no ask.
      doReturn(held).when(decisions).decide(any());

      assertThat(rows("reader_aa")).as(mode.name()).containsExactly(List.of("A", 123));

      ResolvedRowPredicate predicate = held.getRowPredicates().get(0);
      assertThat(predicate.getKind()).as(mode.name()).isEqualTo(ResolvedRowPredicate.Kind.LOOKUP);
      assertThat(predicate.getValues()).as(mode.name()).isNullOrEmpty();
      assertThat(predicate.getLookup().getSchemaName()).as(mode.name()).isNull();
      assertThat(predicate.getLookup().getTableName()).as(mode.name()).isNull();
    }
  }

  @Test
  @DisplayName("a mapper that knows nothing of dates still binds a decision that carries one")
  void aPlainMapperStillBinds() throws Exception {
    service = service(new ObjectMapper());
    lookup.set(mapping(fqn("lk-pg", "ref", "department_division"), RowLookup.Mode.READ_VALUES));

    assertThat(decide("reader_aa", fqn("lk-pg", "sales", "results")).getEvaluatedAt()).isNotNull();
    assertThat(rows("reader_aa")).containsExactly(List.of("A", 123));
  }

  // -------------------------------------------------------------- what saves

  private static Policy saving(RowLookup lookup) {
    return new Policy()
        .withName("divisions-by-department")
        .withPolicyType(Policy.PolicyType.DATA)
        .withData(
            new DataPolicy()
                .withRowFilters(
                    List.of(
                        new RowFilter()
                            .withKind(RowFilter.Kind.LOOKUP)
                            .withColumn("division")
                            .withLookup(lookup))));
  }

  @Test
  @DisplayName("a lookup saves only when its mapping and its columns are in the catalog")
  void saveTimeCheck() {
    String table = fqn("lk-pg", "ref", "department_division");

    assertThatCode(() -> LookupCheck.check(jdbi, saving(mapping(table, RowLookup.Mode.SUBQUERY))))
        .doesNotThrowAnyException();
    // Spelled another way, it is still the one table the proxy would find.
    assertThatCode(
            () ->
                LookupCheck.check(
                    jdbi, saving(mapping(table.toUpperCase(), RowLookup.Mode.READ_VALUES))))
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () -> LookupCheck.check(jdbi, saving(mapping(fqn("lk-pg", "ref", "nowhere"), null))))
        .hasMessageContaining("not in the catalog");
    assertThatThrownBy(
            () -> LookupCheck.check(jdbi, saving(mapping(table, "region", RowLookup.Mode.SUBQUERY))))
        .hasMessageContaining("has no column region");
    assertThatThrownBy(
            () ->
                LookupCheck.check(
                    jdbi, saving(mapping("ref.department_division", RowLookup.Mode.SUBQUERY))))
        .hasMessageContaining("service.database.schema.table");
    assertThatThrownBy(
            () ->
                LookupCheck.check(
                    jdbi,
                    saving(
                        mapping(table, RowLookup.Mode.SUBQUERY)
                            .withKeys(List.of(new LookupKey().withColumn("department"))))))
        .hasMessageContaining("needs a column and an attribute");
  }

  @Test
  @DisplayName("a lookup that picks its column by tag saves; one with neither name nor tag does not")
  void saveTimeCheckByTag() {
    Policy pickedByTag = saving(mapping(fqn("lk-pg", "ref", "department_division"), null));
    pickedByTag.getData().getRowFilters().get(0).withColumn(null).withColumns(tagged("Org.Division"));

    assertThatCode(() -> ConditionValues.check(pickedByTag)).doesNotThrowAnyException();
    assertThatCode(() -> LookupCheck.check(jdbi, pickedByTag)).doesNotThrowAnyException();

    Policy neither = saving(mapping(fqn("lk-pg", "ref", "department_division"), null));
    neither.getData().getRowFilters().get(0).withColumn(null);
    assertThatThrownBy(() -> LookupCheck.check(jdbi, neither))
        .hasMessageContaining("by name or by tag");
  }
}
