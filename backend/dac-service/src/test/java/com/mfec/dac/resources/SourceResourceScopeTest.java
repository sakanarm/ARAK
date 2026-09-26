package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.catalog.SourceCatalogImporter;
import com.mfec.dac.crypto.SecretBox;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.TableScope;
import com.mfec.dac.source.jdbc.JdbcIntrospector;
import com.mfec.dac.source.jdbc.SourceProbe;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.SecurityContext;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SourceResource — checking a table scope before it is saved")
class SourceResourceScopeTest {

  private final DataSourceStore sources = mock(DataSourceStore.class);
  private final SourceProbe probe = mock(SourceProbe.class);
  private final SourceCatalogImporter importer = mock(SourceCatalogImporter.class);
  private final JdbcIntrospector introspector = mock(JdbcIntrospector.class);
  private final SecurityContext security = mock(SecurityContext.class);
  private SourceResource resource;

  @BeforeEach
  void setUp() throws Exception {
    resource =
        new SourceResource(
            sources,
            probe,
            importer,
            // No key: every body here points at a secret rather than typing one.
            SecretBox.fromConfiguration(null),
            introspector);
    when(security.getUserPrincipal()).thenReturn(user(Set.of("PLATFORM_ADMIN")));
    when(introspector.names(any(), anyString(), isNull()))
        .thenReturn(
            List.of(
                new JdbcIntrospector.Name("sales", "customer", "TABLE"),
                new JdbcIntrospector.Name("sales", "tmp_customer", "TABLE"),
                new JdbcIntrospector.Name("staging", "orders", "TABLE"),
                new JdbcIntrospector.Name("sales", "orders_v", "VIEW")));
  }

  private static AuthenticatedUser user(Set<String> roles) {
    return new AuthenticatedUser(
        UUID.randomUUID(), "admin", "admin@example.test", "Admin", "local", roles, List.of());
  }

  private static Map<String, Object> body(Object scope) {
    Map<String, Object> body = new HashMap<>();
    body.put("engine", "POSTGRES");
    body.put("host", "db.example.test");
    body.put("port", 5432);
    body.put("defaultDatabase", "salesdb");
    body.put("credentialRef", "vault://secret/data/salesdb");
    body.put("tableScope", scope);
    return body;
  }

  private static Map<String, Object> excluding(String match, String value) {
    return Map.of(
        "mode", "ALL", "include", List.of(), "exclude", List.of(Map.of("match", match, "value", value)));
  }

  @Test
  void countsWhatTheScopeReadsAndWhatItLeavesOut() throws Exception {
    Map<String, Object> out =
        resource.previewScope(body(excluding("STARTS_WITH", "tmp_")), security);

    assertThat(out)
        .containsEntry("total", 4)
        .containsEntry("inScope", 3)
        .containsEntry("excluded", 1)
        .containsEntry("excludedSample", List.of("sales.tmp_customer"));
    assertThat(out.get("inScopeSample"))
        .isEqualTo(List.of("sales.customer", "staging.orders", "sales.orders_v"));
    verify(introspector)
        .names(
            eq(new SourceProbe.Target("POSTGRES", "db.example.test", 5432, "salesdb")),
            eq("vault://secret/data/salesdb"),
            isNull());
  }

  @Test
  void aSchemaRuleNeedsItsDot() throws Exception {
    Map<String, Object> out =
        resource.previewScope(body(excluding("STARTS_WITH", "staging.")), security);
    assertThat(out).containsEntry("excludedSample", List.of("staging.orders"));
  }

  @Test
  void noScopeMeansEveryTable() throws Exception {
    assertThat(resource.previewScope(body(null), security))
        .containsEntry("inScope", 4)
        .containsEntry("excluded", 0);
  }

  @Test
  void theSamplesStopButTheCountsDoNot() throws Exception {
    List<JdbcIntrospector.Name> many = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      many.add(new JdbcIntrospector.Name("s", "t" + i, "TABLE"));
    }
    when(introspector.names(any(), anyString(), isNull())).thenReturn(many);

    Map<String, Object> out = resource.previewScope(body(null), security);

    assertThat(out).containsEntry("inScope", 60);
    assertThat((List<?>) out.get("inScopeSample")).hasSize(SourceResource.PREVIEW_SAMPLE);
  }

  @Test
  void aBadScopeIsRefusedBeforeAnythingConnects() throws Exception {
    assertThatThrownBy(
            () ->
                resource.previewScope(
                    body(Map.of("mode", "ONLY", "include", List.of(), "exclude", List.of())),
                    security))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("at least one table");
    assertThatThrownBy(() -> resource.previewScope(body(excluding("MATCHES_REGEX", ".*")), security))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("STARTS_WITH");
    verifyNoInteractions(introspector);
  }

  @Test
  void onlyAnAdministratorMayConnect() throws Exception {
    when(security.getUserPrincipal()).thenReturn(user(Set.of("POLICY_AUTHOR")));
    assertThatThrownBy(() -> resource.previewScope(body(null), security))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(introspector);
  }

  @Test
  void aSourceThatCannotBeReadIsABadGateway() throws Exception {
    when(introspector.names(any(), anyString(), isNull()))
        .thenThrow(new SQLException("Connection refused"));

    assertThatThrownBy(() -> resource.previewScope(body(null), security))
        .isInstanceOfSatisfying(
            WebApplicationException.class,
            e -> assertThat(e.getResponse().getStatus()).isEqualTo(502));
  }

  @Test
  void aSavedSourceIsCheckedWithItsStoredCredential() throws Exception {
    UUID id = UUID.randomUUID();
    when(sources.find(id))
        .thenReturn(
            Optional.of(
                new DataSourceStore.Source(
                    id, "salesdb", DataSourceStore.Engine.POSTGRES, null, "db.example.test", 5433,
                    "salesdb", "vault://secret/data/stored", DataSourceStore.EnforcementMode.NONE,
                    null, "sec", "{table}", TableScope.EVERYTHING, true, Instant.now(),
                    Instant.now(), 0)));
    Map<String, Object> body = new HashMap<>();
    body.put("id", id.toString());
    body.put("credentialRef", "fernet:stored");

    resource.previewScope(body, security);

    verify(introspector)
        .names(
            eq(new SourceProbe.Target("POSTGRES", "db.example.test", 5433, "salesdb")),
            eq("vault://secret/data/stored"),
            isNull());
    verify(probe, never()).probe(any(), anyString());
  }

  @Test
  void theIntrospectReportSaysWhatTheScopeLeftOut() {
    UUID id = UUID.randomUUID();
    when(importer.importFrom(id, null))
        .thenReturn(
            new SourceCatalogImporter.Report(
                "salesdb", 3, 12, 1, List.of(), List.of(), 2, List.of("salesdb.salesdb.sales.tmp_x")));

    Map<String, Object> out = resource.introspect(id, null, security);

    assertThat(out)
        .containsEntry("excluded", 2)
        .containsEntry("outOfScope", List.of("salesdb.salesdb.sales.tmp_x"));
  }
}
