package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.catalog.SourceCatalogImporter;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.SourceProbe;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The source registry (FR-6.0a) — where enforcement is pointed.
 *
 * <p>Reading is open to any authenticated caller: a data owner needs to know
 * which mode governs their tables to understand what a policy will actually do
 * to them. Writing is admin-only, because the fields on this row decide whether
 * a policy becomes a security policy on a production table, a view beside it,
 * or nothing at all — that is an operational decision about a database, not a
 * governance decision about data, and the two are held by different people.
 */
@Path("/v1/sources")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class SourceResource {

  private final DataSourceStore sources;
  private final SourceProbe probe;
  private final SourceCatalogImporter importer;

  public SourceResource(
      DataSourceStore sources, SourceProbe probe, SourceCatalogImporter importer) {
    this.sources = sources;
    this.probe = probe;
    this.importer = importer;
  }

  @GET
  public List<DataSourceStore.Source> list() {
    return sources.list();
  }

  @GET
  @Path("/{id}")
  public DataSourceStore.Source get(@PathParam("id") UUID id) {
    return sources.find(id).orElseThrow(() -> new NotFoundException("No data source " + id));
  }

  @POST
  public Response create(
      DataSourceStore.SourceInput input, @Context SecurityContext security) {
    requireAdmin(security);
    try {
      DataSourceStore.Source created = sources.create(input);
      return Response.status(Response.Status.CREATED).entity(created).build();
    } catch (DataSourceStore.InvalidSourceException e) {
      throw new BadRequestException(e.getMessage());
    } catch (DataSourceStore.SourceConflictException e) {
      throw conflict(e.getMessage());
    }
  }

  @PUT
  @Path("/{id}")
  public DataSourceStore.Source update(
      @PathParam("id") UUID id,
      DataSourceStore.SourceInput input,
      @Context SecurityContext security) {
    requireAdmin(security);
    try {
      return sources.update(id, input);
    } catch (DataSourceStore.NoSuchSourceException e) {
      throw new NotFoundException(e.getMessage());
    } catch (DataSourceStore.InvalidSourceException e) {
      throw new BadRequestException(e.getMessage());
    } catch (DataSourceStore.SourceConflictException e) {
      throw conflict(e.getMessage());
    }
  }

  /**
   * Turns a source on or off without losing what is known about it.
   *
   * <p>Separate from the full update because it is the reaction to an incident
   * — a source being migrated, a credential rotated — and needing to send a
   * whole valid document to stop a crawl would make the safe action the slow
   * one.
   */
  @POST
  @Path("/{id}/enabled")
  public DataSourceStore.Source setEnabled(
      @PathParam("id") UUID id, Map<String, Object> body, @Context SecurityContext security) {
    requireAdmin(security);
    Object value = body == null ? null : body.get("enabled");
    if (!(value instanceof Boolean enabled)) {
      throw new BadRequestException("Send {\"enabled\": true} or {\"enabled\": false}");
    }
    try {
      return sources.setEnabled(id, enabled);
    } catch (DataSourceStore.NoSuchSourceException e) {
      throw new NotFoundException(e.getMessage());
    }
  }

  /**
   * Opens one connection and reports what answered (read-only).
   *
   * <p>When it succeeds the engine version is written back to the row, because
   * the capability matrix reads it: SQL Server below 2022 cannot grant
   * {@code UNMASK} on a single column, and a policy that relies on it has to be
   * refused at authoring time rather than half-applied.
   */
  @POST
  @Path("/{id}/test")
  public Map<String, Object> test(@PathParam("id") UUID id, @Context SecurityContext security) {
    requireAdmin(security);
    DataSourceStore.Source source =
        sources.find(id).orElseThrow(() -> new NotFoundException("No data source " + id));

    SourceProbe.Result result =
        probe.probe(
            new SourceProbe.Target(
                source.engine().name(), source.host(), source.port(), source.defaultDatabase()),
            source.credentialRef());

    if (result.reachable() && result.engineVersion() != null) {
      sources.recordEngineVersion(id, result.engineVersion());
    }
    return Map.of(
        "reachable", result.reachable(),
        "engineVersion", result.engineVersion() == null ? "" : result.engineVersion(),
        "productName", result.productName() == null ? "" : result.productName(),
        "message", result.message(),
        "millis", result.millis());
  }

  /**
   * Reads the source's own catalog and writes what is really there (FR-1.6).
   *
   * <p>This is how a database that OpenMetadata has never ingested still gets
   * assets to write policies about, and it is the live verification that has to
   * happen before any DDL is generated. The report names new columns rather
   * than counting them: a column that appeared since the last run is
   * unprotected until a policy covers it, and that is the sentence somebody
   * needs to read.
   */
  @POST
  @Path("/{id}/introspect")
  public Map<String, Object> introspect(
      @PathParam("id") UUID id,
      @QueryParam("schema") String schema,
      @Context SecurityContext security) {

    requireAdmin(security);
    try {
      SourceCatalogImporter.Report report = importer.importFrom(id, blankToNull(schema));
      return Map.of(
          "source", report.source(),
          "tables", report.tables(),
          "columns", report.columns(),
          "newTables", report.newTables(),
          "newColumns", report.newColumns(),
          "missingTables", report.missingTables());
    } catch (IllegalArgumentException e) {
      throw new NotFoundException(e.getMessage());
    } catch (SourceCatalogImporter.IntrospectionFailedException e) {
      throw new WebApplicationException(
          Response.status(Response.Status.BAD_GATEWAY)
              .entity(Map.of("message", e.getMessage()))
              .type(MediaType.APPLICATION_JSON)
              .build());
    }
  }

  @DELETE
  @Path("/{id}")
  public Response delete(@PathParam("id") UUID id, @Context SecurityContext security) {
    requireAdmin(security);
    try {
      sources.delete(id);
      return Response.noContent().build();
    } catch (DataSourceStore.NoSuchSourceException e) {
      throw new NotFoundException(e.getMessage());
    } catch (DataSourceStore.SourceConflictException e) {
      throw conflict(e.getMessage());
    }
  }

  private static void requireAdmin(SecurityContext security) {
    if (!(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    if (!user.isPlatformAdmin()) {
      throw new ForbiddenException(
          "Registering a source needs PLATFORM_ADMIN: these fields decide what the platform "
              + "will create and alter on a production database");
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static WebApplicationException conflict(String message) {
    return new WebApplicationException(
        Response.status(Response.Status.CONFLICT)
            .entity(Map.of("message", message))
            .type(MediaType.APPLICATION_JSON)
            .build());
  }
}
