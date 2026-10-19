package com.mfec.dac.source.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.common.engine.SourceEngine;
import com.mfec.dac.common.engine.SourceEngines;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * That every registered engine can actually be connected to from this module.
 *
 * <p>This is the last third of the engine contract, and it lives here because
 * this is the only module with the drivers on its classpath. A driver named in
 * a constant and missing from the pom produces a source that registers
 * happily, probes green in nothing, and fails on the first real connection —
 * so the class name is checked by loading it, not by comparing strings.
 */
class JdbcTargetsTest {

  static List<String> engineIds() {
    return List.copyOf(SourceEngines.ids());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engineIds")
  void theNamedDriverIsOnTheClasspath(String id) {
    String driver = SourceEngines.of(id).driverClassName();
    try {
      assertThat(Class.forName(driver)).isNotNull();
    } catch (ClassNotFoundException e) {
      throw new AssertionError(
          id + " names the driver " + driver + " but it is not on the classpath", e);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engineIds")
  void buildsTheSameUrlTheEngineWouldBuild(String id) {
    SourceEngine engine = SourceEngines.of(id);
    // An engine that is told apart by an HTTP path cannot be built without one.
    String httpPath = engine.usesHttpPath() ? "/sql/1.0/warehouses/abc123" : null;
    SourceProbe.Target target =
        new SourceProbe.Target(id, "db.example.test", engine.defaultPort(), "salesdb", httpPath);
    assertThat(JdbcTargets.url(target))
        .isEqualTo(
            engine.jdbcUrl(
                new SourceEngine.JdbcCoordinates(
                    "db.example.test", engine.defaultPort(), "salesdb", httpPath)));
  }

  @Test
  void aDatabricksUrlNeedsItsWarehouseAndCatalogAndSaysSo() {
    assertThatThrownBy(
            () -> JdbcTargets.url(new SourceProbe.Target("DATABRICKS", "dbx.example.test", 443, "main")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTP path");
    assertThatThrownBy(
            () ->
                JdbcTargets.url(
                    new SourceProbe.Target(
                        "DATABRICKS", "dbx.example.test", 443, null, "/sql/1.0/warehouses/abc123")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("catalog");
  }

  @Test
  void aDatabricksTokenGoesInTheDriversOwnPropertyNames() {
    Properties properties =
        JdbcTargets.properties(
            SourceEngines.of("DATABRICKS"),
            new CredentialResolver.Credential("token", "not-a-token"));
    assertThat(properties.getProperty("UID")).isEqualTo("token");
    assertThat(properties.getProperty("PWD")).isEqualTo("not-a-token");
    assertThat(properties.getProperty("user")).isNull();
    // The driver folds names to lower case, so the two spellings the other
    // engines are sent are one duplicate key to it and the connection is refused.
    assertThat(properties.stringPropertyNames())
        .extracting(String::toLowerCase)
        .doesNotHaveDuplicates()
        .doesNotContain("applicationname");
  }

  @Test
  void acceptsTheEngineHoweverTheRegistryRowSpeltIt() {
    assertThat(JdbcTargets.url(new SourceProbe.Target("postgres", "db.example.test", 5432, "sales")))
        .isEqualTo("jdbc:postgresql://db.example.test:5432/sales");
  }

  @Test
  void refusesAnEngineNobodyRegistered() {
    // It used to say "Phase 1 can only connect to POSTGRES and SQLSERVER",
    // which was a claim about the roadmap dressed up as a claim about this
    // build. The registry answers with what is actually registered.
    assertThatThrownBy(
            () -> JdbcTargets.url(new SourceProbe.Target("ORACLE", "db.example.test", 1521, "x")))
        .isInstanceOf(SourceEngines.UnsupportedEngineException.class)
        .hasMessageContaining("ORACLE")
        .hasMessageContaining("POSTGRES");
  }

  @Test
  void refusesATargetWithNoHostRatherThanBuildingANonsenseUrl() {
    assertThatThrownBy(() -> JdbcTargets.url(new SourceProbe.Target("POSTGRES", "  ", 5432, "x")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aMySqlConnectionCarriesTheSameNameInItsUrl() {
    // Connector/J has no ApplicationName property; what a DBA sees there is a
    // connection attribute, which has to be the name the other engines show.
    assertThat(JdbcTargets.url(new SourceProbe.Target("MYSQL", "db.example.test", 3306, "sales")))
        .contains("connectionAttributes=program_name:" + JdbcTargets.APPLICATION_NAME);
  }

  @Test
  void everyConnectionAnnouncesItselfUnderTheSameName() {
    // The proxy's safety argument in FR-6.3.1 is that a DBA can tell our
    // traffic from a user's in pg_stat_activity, which only holds if the
    // probe, the introspector and the proxy all say the same thing.
    Properties properties =
        JdbcTargets.properties(new CredentialResolver.Credential("arak_reader", "not-a-password"));
    assertThat(properties.getProperty("user")).isEqualTo("arak_reader");
    assertThat(properties.getProperty("ApplicationName")).isEqualTo(JdbcTargets.APPLICATION_NAME);
    assertThat(properties.getProperty("applicationName")).isEqualTo(JdbcTargets.APPLICATION_NAME);
  }
}
