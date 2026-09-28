package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.policy.QueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.ByteArrayOutputStream;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The console's "Download all rows" (FR-6.3): a file of every row, as the
 * caller, refused as JSON before the file starts.
 */
@DisplayName("Query export — resource")
class QueryExportResourceTest {

  private final QueryService queries = mock(QueryService.class);
  private final QueryResource resource = new QueryResource(queries, null, 90);
  private final HttpServletRequest request = mock(HttpServletRequest.class);

  private final AuthenticatedUser analyst =
      new AuthenticatedUser(
          UUID.randomUUID(),
          "analyst_a",
          "analyst_a@example.test",
          "analyst_a",
          "local",
          Set.of("REQUESTER"),
          List.of());

  private final AuthenticatedUser admin =
      new AuthenticatedUser(
          UUID.randomUUID(),
          "admin_x",
          "admin_x@example.test",
          "admin_x",
          "local",
          Set.of("PLATFORM_ADMIN"),
          List.of());

  private final String sourceId = UUID.randomUUID().toString();

  @Test
  @DisplayName("streams the file as the caller, under the configured time limit")
  void streams() throws Exception {
    when(request.getRemoteAddr()).thenReturn("192.0.2.10");
    QueryService.Download download = mock(QueryService.Download.class);
    when(download.assets()).thenReturn(List.of("demo-pg.salesdb.sales.customer"));
    when(queries.export(any(), anyString(), anyString(), any(), any(), anyInt()))
        .thenReturn(download);

    Response response =
        resource.export(
            new QueryResource.Ask(sourceId, "SELECT * FROM sales.customer", null, null, "audit", null),
            as(analyst),
            request);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getMediaType().toString()).startsWith("text/csv");
    assertThat(response.getHeaderString("Content-Disposition"))
        .isEqualTo("attachment; filename=\"customer-all-rows.csv\"");
    assertThat(response.getHeaderString("Cache-Control")).isEqualTo("no-store");
    verify(queries)
        .export(
            UUID.fromString(sourceId),
            "SELECT * FROM sales.customer",
            "analyst_a",
            "192.0.2.10",
            "audit",
            90);

    // The body is the download writing itself out.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ((StreamingOutput) response.getEntity()).write(out);
    verify(download).writeCsv(out);
  }

  @Test
  @DisplayName("naming yourself as the principal is the same as naming nobody")
  void asYourself() {
    when(queries.export(any(), anyString(), anyString(), any(), any(), anyInt()))
        .thenReturn(mock(QueryService.Download.class));
    Response response =
        resource.export(
            new QueryResource.Ask(sourceId, "SELECT 1", "ANALYST_A", null, null, null),
            as(analyst),
            request);
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("never as somebody else, not even for an administrator")
  void notAsSomebodyElse() {
    ForbiddenException thrown =
        catchThrowableOfType(
            ForbiddenException.class,
            () ->
                resource.export(
                    new QueryResource.Ask(sourceId, "SELECT 1", "analyst_a", null, null, null),
                    as(admin),
                    request));
    assertThat(thrown).isNotNull();
    verify(queries, never()).export(any(), anyString(), anyString(), any(), any(), anyInt());
  }

  @Test
  @DisplayName("a refusal is 403 JSON, before any of the file")
  @SuppressWarnings("unchecked")
  void refused() {
    when(queries.export(any(), anyString(), anyString(), any(), any(), anyInt()))
        .thenThrow(
            new QueryService.RejectedException(
                "Access to demo-pg.salesdb.sales.customer is denied",
                "demo-pg.salesdb.sales.customer",
                false));
    WebApplicationException thrown =
        catchThrowableOfType(
            WebApplicationException.class,
            () ->
                resource.export(
                    new QueryResource.Ask(sourceId, "SELECT 1", null, null, null, null),
                    as(analyst),
                    request));
    assertThat(thrown.getResponse().getStatus()).isEqualTo(403);
    assertThat(thrown.getResponse().getMediaType().toString()).isEqualTo("application/json");
    assertThat((Map<String, Object>) thrown.getResponse().getEntity())
        .containsEntry("fixable", false)
        .containsEntry("assetFqn", "demo-pg.salesdb.sales.customer");
  }

  @Test
  @DisplayName("a full source is 429 with Retry-After, as for a query")
  void busy() {
    when(queries.export(any(), anyString(), anyString(), any(), any(), anyInt()))
        .thenThrow(new QueryService.BusyException("demo-pg is busy", 7));
    WebApplicationException thrown =
        catchThrowableOfType(
            WebApplicationException.class,
            () ->
                resource.export(
                    new QueryResource.Ask(sourceId, "SELECT 1", null, null, null, null),
                    as(analyst),
                    request));
    assertThat(thrown.getResponse().getStatus()).isEqualTo(429);
    assertThat(thrown.getResponse().getHeaderString("Retry-After")).isEqualTo("7");
  }

  @Test
  @DisplayName("the file is named for the first table, in characters any disk accepts")
  void fileName() {
    assertThat(QueryResource.fileName(List.of())).isEqualTo("query-all-rows.csv");
    assertThat(QueryResource.fileName(null)).isEqualTo("query-all-rows.csv");
    assertThat(QueryResource.fileName(List.of("svc.db.sch.order lines", "svc.db.sch.x")))
        .isEqualTo("order_lines-all-rows.csv");
    assertThat(QueryResource.fileName(List.of("svc.db.sch.\"quoted\"")))
        .isEqualTo("_quoted_-all-rows.csv");
  }

  @Test
  @DisplayName("the busy-mapping shared with run() still applies there")
  void runStillBusy() {
    when(queries.run(any(), anyString(), anyString(), anyString(), anyInt(), any(), any(), eq(false)))
        .thenThrow(new QueryService.BusyException("demo-pg is busy", 3));
    WebApplicationException thrown =
        catchThrowableOfType(
            WebApplicationException.class,
            () ->
                resource.run(
                    new QueryResource.Ask(sourceId, "SELECT 1", null, null, null, null),
                    as(analyst),
                    request));
    assertThat(thrown.getResponse().getStatus()).isEqualTo(429);
  }

  private SecurityContext as(Principal who) {
    return new SecurityContext() {
      @Override
      public Principal getUserPrincipal() {
        return who;
      }

      @Override
      public boolean isUserInRole(String role) {
        return false;
      }

      @Override
      public boolean isSecure() {
        return true;
      }

      @Override
      public String getAuthenticationScheme() {
        return "Bearer";
      }
    };
  }
}
