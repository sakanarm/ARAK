package com.mfec.dac.common.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds every registered engine to the same standard.
 *
 * <p>This is the test that makes adding an engine a small job rather than a
 * risky one. Before the registry existed the knowledge of an engine was spread
 * across three switches in three modules, and a new engine was complete when
 * somebody remembered all three. Now it is complete when this passes — and a
 * half-added engine fails the build instead of failing a query in production,
 * which is the whole point of moving the check here.
 *
 * <p>Two things this deliberately does not check, because the module that owns
 * them checks them instead: that the dialect name resolves ({@code
 * SqlDialectsTest}, in the SQL compiler) and that the named driver is really on
 * the classpath ({@code JdbcTargetsTest}, in the module that opens sockets).
 */
class SourceEngineConformanceTest {

  static List<String> engineIds() {
    return List.copyOf(SourceEngines.ids());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engineIds")
  void idIsTheUppercaseFormStoredInTheDatabase(String id) {
    SourceEngine engine = SourceEngines.of(id);
    // data_source.engine holds this string and a CHECK constraint names it, so
    // a lowercase id here would need a migration to fix rather than an edit.
    assertThat(engine.id()).isEqualTo(engine.id().toUpperCase(Locale.ROOT));
    assertThat(engine.id()).isNotBlank().doesNotContain(" ");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engineIds")
  void isFindableHoweverTheIdIsSpelt(String id) {
    SourceEngine engine = SourceEngines.of(id);
    // The registry normalises, because the id arrives from a JSON body and
    // from a database row and those will not agree on case forever.
    assertThat(SourceEngines.of(engine.id().toLowerCase(Locale.ROOT))).isSameAs(engine);
    assertThat(SourceEngines.of("  " + engine.id() + "  ")).isSameAs(engine);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engineIds")
  void namesItselfForTheScreen(String id) {
    assertThat(SourceEngines.of(id).displayName()).isNotBlank();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engineIds")
  void offersADefaultPortSoNobodyHasToLookItUp(String id) {
    assertThat(SourceEngines.of(id).defaultPort()).isBetween(1, 65535);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engineIds")
  void namesADriverClass(String id) {
    assertThat(SourceEngines.of(id).driverClassName()).isNotBlank().contains(".");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engineIds")
  void buildsAUrlForItsOwnScheme(String id) {
    SourceEngine engine = SourceEngines.of(id);
    String url =
        engine.jdbcUrl(
            new SourceEngine.JdbcCoordinates("db.example.test", engine.defaultPort(), "salesdb"));
    assertThat(url).startsWith("jdbc:");
    assertThat(url).contains("db.example.test");
    assertThat(url).contains(String.valueOf(engine.defaultPort()));
    assertThat(url).contains("salesdb");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engineIds")
  void buildsAUrlWithNoDatabaseNamed(String id) {
    SourceEngine engine = SourceEngines.of(id);
    // The probe runs before anybody has said which database to use, so every
    // engine needs an answer for that case rather than producing a URL with
    // the word "null" in it.
    String url =
        engine.jdbcUrl(
            new SourceEngine.JdbcCoordinates("db.example.test", engine.defaultPort(), null));
    assertThat(url).startsWith("jdbc:").doesNotContain("null");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engineIds")
  void declaresWhatTheProxyCanExpress(String id) {
    // It may be empty — an engine the proxy cannot rewrite for is a legitimate
    // thing to register, and ProxyCapabilities refuses its queries rather than
    // running them unprotected. It may not be null, because a null here would
    // fail open at exactly the point the fail-closed rule lives.
    assertThat(SourceEngines.of(id).proxyCapabilities()).isNotNull();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engineIds")
  void namesADialect(String id) {
    assertThat(SourceEngines.of(id).dialectId()).isNotBlank();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engineIds")
  void saysWhatASessionNeedsAndWhichSchemasAreItsOwn(String id) {
    SourceEngine engine = SourceEngines.of(id);
    // Neither may be null: the connector runs the first on every connection
    // and the introspector reads the second on every import, and a null in
    // either is a source that cannot be opened at all.
    assertThat(engine.sessionSetup()).isNotNull().doesNotContainNull();
    assertThat(engine.systemSchemas()).isNotNull();
    // Compared against names folded to lower case, so one spelled otherwise
    // would never match and the engine's own tables would land in the catalog.
    assertThat(engine.systemSchemas())
        .allSatisfy(schema -> assertThat(schema).isEqualTo(schema.toLowerCase(Locale.ROOT)));
  }

  @Test
  void refusesAnEngineItDoesNotHaveAndSaysWhatItDoesHave() {
    assertThatThrownBy(() -> SourceEngines.of("ORACLE"))
        .isInstanceOf(SourceEngines.UnsupportedEngineException.class)
        .hasMessageContaining("ORACLE")
        .hasMessageContaining("POSTGRES");
  }

  @Test
  void findsNothingForNull() {
    assertThat(SourceEngines.find(null)).isEmpty();
  }

  @Test
  void stillCoversTheTwoEnginesPhaseOnePromised() {
    assertThat(SourceEngines.ids()).contains("POSTGRES", "SQLSERVER");
    assertThat(SourceEngines.all()).hasSameSizeAs(SourceEngines.ids());
  }

  @Test
  void mySqlStatesWhatThePlatformReliesOnRatherThanInheritingIt() {
    // Each property is one another class depends on: the introspector on
    // databaseTerm, the download on useCursorFetch, the DBA on program_name.
    assertThat(
            SourceEngines.of("MYSQL")
                .jdbcUrl(new SourceEngine.JdbcCoordinates("db.example.test", 3306, "sales")))
        .isEqualTo(
            "jdbc:mysql://db.example.test:3306/sales"
                + "?databaseTerm=SCHEMA"
                + "&allowMultiQueries=false"
                + "&allowLoadLocalInfile=false"
                + "&useCursorFetch=true"
                + "&zeroDateTimeBehavior=CONVERT_TO_NULL"
                + "&characterEncoding=UTF-8"
                + "&connectionTimeZone=UTC"
                + "&sslMode=PREFERRED"
                + "&connectionAttributes=program_name:arak-dac");
  }

  @Test
  void mySqlConnectsWithNoDefaultDatabaseWhenNoneIsNamed() {
    assertThat(
            SourceEngines.of("MYSQL")
                .jdbcUrl(new SourceEngine.JdbcCoordinates("db.example.test", 3306, null)))
        .startsWith("jdbc:mysql://db.example.test:3306/?");
  }

  @Test
  void mySqlIsTheEngineWhoseDatabaseIsItsSchema() {
    assertThat(SourceEngines.of("MYSQL").supportsSchemas()).isFalse();
    assertThat(SourceEngines.of("POSTGRES").supportsSchemas()).isTrue();
    assertThat(SourceEngines.of("SQLSERVER").supportsSchemas()).isTrue();
    assertThat(SourceEngines.of("MYSQL").systemSchemas())
        .containsExactlyInAnyOrder("mysql", "performance_schema");
  }

  @Test
  void aBackslashIsMadeAnOrdinaryCharacterWhereverThatIsASetting() {
    // The proxy's parser always reads a backslash in a string as a backslash.
    // MySQL does not unless told to, and PostgreSQL only by default.
    assertThat(SourceEngines.of("MYSQL").sessionSetup())
        .first()
        .asString()
        .contains("NO_BACKSLASH_ESCAPES")
        // Added to the modes the server already runs with, not put in their place.
        .contains("@@SESSION.sql_mode");
    assertThat(SourceEngines.of("POSTGRES").sessionSetup())
        .containsExactly("SET standard_conforming_strings = on");
    assertThat(SourceEngines.of("SQLSERVER").sessionSetup()).isEmpty();
  }

  @Test
  void aMySqlSessionRunsInTheZoneTheDriverReadsItIn() {
    // The two have to be one zone, or a TIMESTAMP is read as a different moment
    // from the one it records. An offset, because a named zone needs tables a
    // MySQL server may never have had loaded.
    assertThat(SourceEngines.of("MYSQL").sessionSetup()).contains("SET SESSION time_zone = '+00:00'");
    assertThat(
            SourceEngines.of("MYSQL")
                .jdbcUrl(new SourceEngine.JdbcCoordinates("db.example.test", 3306, "sales")))
        .contains("connectionTimeZone=UTC");
  }

  @Test
  void offersTheEnginesInTheOrderTheyWereRegistered() {
    assertThat(SourceEngines.all())
        .extracting(SourceEngine::id)
        .containsExactly("POSTGRES", "SQLSERVER", "MYSQL");
  }

  @Test
  void secureViewsAreOfferedOnlyWhereTheyHaveBeenWritten() {
    assertThat(SourceEngines.of("POSTGRES").supportsSecureViews()).isTrue();
    assertThat(SourceEngines.of("SQLSERVER").supportsSecureViews()).isTrue();
    assertThat(SourceEngines.of("MYSQL").supportsSecureViews()).isFalse();
  }

  @Test
  void refusesCoordinatesThatCannotBeConnectedTo() {
    assertThatThrownBy(() -> new SourceEngine.JdbcCoordinates("", 5432, "db"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SourceEngine.JdbcCoordinates(null, 5432, "db"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SourceEngine.JdbcCoordinates("host", 0, "db"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SourceEngine.JdbcCoordinates("host", 70000, "db"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void treatsABlankDatabaseAsNoDatabase() {
    assertThat(new SourceEngine.JdbcCoordinates("host", 5432, "   ").databaseOr("fallback"))
        .isEqualTo("fallback");
    assertThat(new SourceEngine.JdbcCoordinates("host", 5432, " salesdb ").databaseOr("fallback"))
        .isEqualTo("salesdb");
  }

  @Test
  void postgresFallsBackToTheDatabaseThatAlwaysExists() {
    // Named rather than left to the driver: a Postgres URL with no database is
    // a connection to a database named after the user, which usually is not
    // there, and the probe would report the source unreachable when it is not.
    assertThat(
            SourceEngines.of("POSTGRES")
                .jdbcUrl(new SourceEngine.JdbcCoordinates("db.example.test", 5432, null)))
        .isEqualTo("jdbc:postgresql://db.example.test:5432/postgres");
  }

  @Test
  void sqlServerStatesItsTlsChoiceRatherThanInheritingIt() {
    assertThat(
            SourceEngines.of("SQLSERVER")
                .jdbcUrl(new SourceEngine.JdbcCoordinates("db.example.test", 1433, "SalesDB")))
        .isEqualTo(
            "jdbc:sqlserver://db.example.test:1433"
                + ";encrypt=true;trustServerCertificate=true;databaseName=SalesDB");
  }
}
