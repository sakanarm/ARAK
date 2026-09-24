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
