package com.mfec.dac.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.catalog.SourceCatalogImporter;
import com.mfec.dac.common.engine.SourceEngines;
import com.mfec.dac.crypto.SecretBox;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.TableScope;
import com.mfec.dac.source.jdbc.CredentialResolver;
import com.mfec.dac.source.jdbc.JdbcIntrospector;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *
 * <p>A credential may be given two ways. Pointing at a vault is better where
 * there is a vault, so that stays. But a deployment without one was being told
 * to put the password in an environment variable instead, which does not keep
 * the password out of the product — it moves it somewhere with no audit trail
 * and no way to change it without a restart. So a username and password may
 * also be typed here, and are sealed with the deployment's Fernet key before
 * they reach the row. What is served back is {@code fernet:stored}: the
 * ciphertext is never in a response, which is what keeps it out of a browser
 * cache, an API log and anything this console is screen-shared into.
 */
@Path("/v1/sources")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class SourceResource {

  /** How many names of each kind a scope preview lists; the counts are exact. */
  static final int PREVIEW_SAMPLE = 25;

  private static final ObjectMapper JSON = new ObjectMapper();

  private final DataSourceStore sources;
  private final SourceProbe probe;
  private final SourceCatalogImporter importer;
  private final SecretBox secretBox;
  private final JdbcIntrospector introspector;

  public SourceResource(
      DataSourceStore sources,
      SourceProbe probe,
      SourceCatalogImporter importer,
      SecretBox secretBox,
      JdbcIntrospector introspector) {
    this.sources = sources;
    this.probe = probe;
    this.importer = importer;
    this.secretBox = secretBox;
    this.introspector = introspector;
  }

  /** The scheme a typed-in credential is stored under. */
  private static final String SEALED = "fernet:";

  /** What is served instead of the ciphertext. */
  private static final String REDACTED = SEALED + CredentialResolver.STORED;

  /**
   * Replaces a sealed credential with a marker on the way out.
   *
   * <p>Applied to every response that carries a source, including the echo of a
   * create, rather than only to the list — one endpoint that forgets is the
   * same leak as none of them redacting. Pointer references are left alone:
   * {@code vault://secret/data/prod} is not a secret, and hiding it would make
   * the screen unable to say where the credential is kept.
   */
  private static DataSourceStore.Source redact(DataSourceStore.Source source) {
    String ref = source.credentialRef();
    if (ref == null || !ref.toLowerCase(java.util.Locale.ROOT).startsWith(SEALED)) {
      return source;
    }
    return new DataSourceStore.Source(
        source.id(),
        source.name(),
        source.engine(),
        source.engineVersion(),
        source.host(),
        source.port(),
        source.defaultDatabase(),
        REDACTED,
        source.defaultEnforcementMode(),
        source.omServiceFqn(),
        source.secureSchema(),
        source.secureObjectPattern(),
        source.tableScope(),
        source.enabled(),
        source.createdAt(),
        source.updatedAt(),
        source.assetCount());
  }

  /**
   * Turns whatever the form sent into the reference the row will hold.
   *
   * <p>Three cases, and the third is the one that matters. A typed username and
   * password is sealed. A pointer is passed through. And a credential field
   * that came back as the redaction marker means the form is echoing what it
   * was served rather than carrying a new secret — so the stored reference is
   * kept. Without that last case, opening a source to change its port and
   * pressing Save would overwrite the password with the word "stored".
   */
  private DataSourceStore.SourceInput withCredential(
      DataSourceStore.SourceInput input, String existingRef) {

    String username = blankToNull(input == null ? null : input.username());
    String password = input == null ? null : input.password();
    String ref = blankToNull(input == null ? null : input.credentialRef());

    boolean typedPassword = password != null && !password.isEmpty();
    if ((username != null) != typedPassword) {
      // Half a credential is the quiet failure worth refusing: a changed
      // username with no password would otherwise fall through to the
      // stored pointer and the source would keep connecting as the old
      // login, with the console showing the new one.
      throw new BadRequestException(
          "A username and a password go together. Give both to set or replace the stored "
              + "credential, or leave both blank to keep the one already stored.");
    }

    if (username != null) {
      if (!secretBox.available()) {
        throw new BadRequestException(
            "This deployment cannot store a password: " + secretBox.problem()
                + " Set FERNET_KEY, or point the credential at a secret store instead.");
      }
      if (username.indexOf(':') >= 0) {
        // The sealed form is user:password split on the first colon, so a colon
        // in the username would silently truncate it into a wrong login.
        throw new BadRequestException("A username may not contain a colon");
      }
      ref = SEALED + secretBox.seal(username + ":" + password);
    } else if (ref != null && ref.equalsIgnoreCase(REDACTED)) {
      ref = existingRef;
    }
    return new DataSourceStore.SourceInput(
        input.name(),
        input.engine(),
        input.engineVersion(),
        input.host(),
        input.port(),
        input.defaultDatabase(),
        ref,
        input.defaultEnforcementMode(),
        input.omServiceFqn(),
        input.secureSchema(),
        input.secureObjectPattern(),
        input.enabled(),
        null,
        null,
        input.tableScope());
  }

  @GET
  public List<DataSourceStore.Source> list() {
    return sources.list().stream().map(SourceResource::redact).toList();
  }

  /**
   * Which engines this build can govern, and what each one needs asked for it.
   *
   * <p>Served so the console stops carrying its own copy of the list. Six
   * screens had the two engines typed into them, which meant adding a third
   * was a backend change plus six frontend changes, and forgetting one of the
   * six produced a dropdown that could register a source the backend would
   * then refuse to connect to.
   *
   * <p>Declared before {@code /{id}} for the reader's benefit only — JAX-RS
   * prefers a literal path over a template regardless of order — but the name
   * {@code engines} can never be a UUID, so the two cannot collide.
   *
   * <p>Open to any authenticated caller, like the rest of the reads here: this
   * is the list of products the platform supports, which is on the box.
   */
  @GET
  @Path("/engines")
  public List<Map<String, Object>> engines() {
    return SourceEngines.all().stream()
        .map(
            engine ->
                Map.<String, Object>of(
                    "id", engine.id(),
                    "displayName", engine.displayName(),
                    "defaultPort", engine.defaultPort(),
                    "supportsSchemas", engine.supportsSchemas(),
                    // What the proxy can express, not what the engine can
                    // enforce natively. The console uses it to warn before a
                    // source is pointed at a mode that will refuse its
                    // queries, rather than after.
                    "proxyCapabilities", List.copyOf(engine.proxyCapabilities())))
        .toList();
  }

  @GET
  @Path("/{id}")
  public DataSourceStore.Source get(@PathParam("id") UUID id) {
    return redact(
        sources.find(id).orElseThrow(() -> new NotFoundException("No data source " + id)));
  }

  @POST
  public Response create(
      DataSourceStore.SourceInput input, @Context SecurityContext security) {
    requireAdmin(security);
    try {
      DataSourceStore.Source created = sources.create(withCredential(input, null));
      return Response.status(Response.Status.CREATED).entity(redact(created)).build();
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
    String existing = sources.find(id).map(DataSourceStore.Source::credentialRef).orElse(null);
    try {
      return redact(sources.update(id, withCredential(input, existing)));
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
      return redact(sources.setEnabled(id, enabled));
    } catch (DataSourceStore.NoSuchSourceException e) {
      throw new NotFoundException(e.getMessage());
    }
  }

  /**
   * Tries a connection that has not been saved yet (read-only).
   *
   * <p>The other test endpoint needs an id, which meant the only way to find
   * out whether a host, port and password were right was to register the source
   * first and correct it afterwards — so the registry filled up with rows that
   * had never connected, and the screen could not tell them apart from the ones
   * that had. This answers the same question before anything is written.
   *
   * <p>Nothing is stored and nothing is logged. The password is read off the
   * request, handed to one connection attempt and dropped; the reply carries
   * the server's version and a sentence, never the credential. An existing
   * source's saved credential can be reused by sending its id instead, which is
   * what lets somebody test a port change without re-typing a password they do
   * not have.
   */
  @POST
  @Path("/test")
  public Map<String, Object> testTarget(Map<String, Object> body, @Context SecurityContext security) {
    requireAdmin(security);
    Unsaved unsaved = unsaved(body, "test");
    SourceProbe.Result result = probe.probe(unsaved.target(), unsaved.credentialRef());
    return Map.of(
        "reachable", result.reachable(),
        "engineVersion", result.engineVersion() == null ? "" : result.engineVersion(),
        "productName", result.productName() == null ? "" : result.productName(),
        "message", result.message(),
        "millis", result.millis());
  }

  /**
   * Which tables a scope would read, before it is saved (read-only).
   *
   * <p>A rule is easy to get subtly wrong — {@code TMP} where the tables are
   * called {@code tmp_}, a schema rule without its dot — and the first time
   * anybody would find out is an import that brought in the wrong tables, or
   * none. This answers it against the database itself: the same connection
   * {@code /test} makes, one catalog call for names only, no columns read and
   * nothing written.
   *
   * <p>The body is what {@code /test} takes, plus {@code tableScope}. The reply
   * gives exact counts and the first {@value #PREVIEW_SAMPLE} names on each
   * side, which is enough to see what a rule catches without shipping the
   * whole catalogue of a large database to the browser.
   */
  @POST
  @Path("/scope-preview")
  public Map<String, Object> previewScope(
      Map<String, Object> body, @Context SecurityContext security) {
    requireAdmin(security);
    Unsaved unsaved = unsaved(body, "check a scope against");
    TableScope scope = scopeFrom(body.get("tableScope"));

    List<JdbcIntrospector.Name> names;
    try {
      names = introspector.names(unsaved.target(), unsaved.credentialRef(), null);
    } catch (Exception e) {
      throw new WebApplicationException(
          Response.status(Response.Status.BAD_GATEWAY)
              .entity(Map.of("message", "Could not list the tables: " + e.getMessage()))
              .type(MediaType.APPLICATION_JSON)
              .build());
    }

    int inScope = 0;
    List<String> inSample = new ArrayList<>();
    List<String> outSample = new ArrayList<>();
    for (JdbcIntrospector.Name name : names) {
      if (scope.includes(name.schema(), name.name())) {
        inScope++;
        if (inSample.size() < PREVIEW_SAMPLE) {
          inSample.add(name.qualified());
        }
      } else if (outSample.size() < PREVIEW_SAMPLE) {
        outSample.add(name.qualified());
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("total", names.size());
    out.put("inScope", inScope);
    out.put("excluded", names.size() - inScope);
    out.put("inScopeSample", inSample);
    out.put("excludedSample", outSample);
    return out;
  }

  /** A scope as the form sent it, checked the way it will be when saved. */
  private static TableScope scopeFrom(Object raw) {
    if (raw == null) {
      return TableScope.EVERYTHING;
    }
    try {
      return TableScope.normalise(JSON.convertValue(raw, TableScope.class));
    } catch (TableScope.InvalidScopeException e) {
      throw new BadRequestException(e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(
          "A scope is {mode: ALL or ONLY, include: [...], exclude: [...]}, each rule being"
              + " {match: STARTS_WITH, ENDS_WITH, CONTAINS or EQUALS, value}");
    }
  }

  /** Where to connect and as whom, for a source that may not be saved yet. */
  private record Unsaved(SourceProbe.Target target, String credentialRef) {}

  private Unsaved unsaved(Map<String, Object> body, String what) {
    if (body == null) {
      throw new BadRequestException("Send a source to " + what);
    }
    String engine = blankToNull(asText(body.get("engine")));
    String host = blankToNull(asText(body.get("host")));
    String database = blankToNull(asText(body.get("defaultDatabase")));
    String username = blankToNull(asText(body.get("username")));
    String password = asText(body.get("password"));
    String ref = blankToNull(asText(body.get("credentialRef")));
    UUID existingId = parseUuid(asText(body.get("id")));

    DataSourceStore.Source existing =
        existingId == null ? null : sources.find(existingId).orElse(null);

    if (engine == null && existing != null) {
      engine = existing.engine().name();
    }
    if (host == null && existing != null) {
      host = existing.host();
    }
    if (engine == null || host == null) {
      throw new BadRequestException("Give at least an engine and a host to " + what);
    }

    int port;
    Object rawPort = body.get("port");
    if (rawPort instanceof Number number) {
      port = number.intValue();
    } else if (rawPort != null && !String.valueOf(rawPort).isBlank()) {
      try {
        port = Integer.parseInt(String.valueOf(rawPort).trim());
      } catch (NumberFormatException e) {
        throw new BadRequestException("Port must be a number");
      }
    } else if (existing != null) {
      port = existing.port();
    } else {
      port = "SQLSERVER".equalsIgnoreCase(engine) ? 1433 : 5432;
    }
    if (port < 1 || port > 65_535) {
      throw new BadRequestException("Port must be between 1 and 65535");
    }
    if (database == null && existing != null) {
      database = existing.defaultDatabase();
    }

    // Order matters: a credential typed into the form is what the operator is
    // trying out, so it wins over whatever is already stored.
    String credentialRef;
    if (username != null && password != null && !password.isEmpty()) {
      if (!secretBox.available()) {
        throw new BadRequestException(
            "This deployment cannot seal a password to test with: " + secretBox.problem());
      }
      if (username.indexOf(':') >= 0) {
        throw new BadRequestException("A username may not contain a colon");
      }
      credentialRef = SEALED + secretBox.seal(username + ":" + password);
    } else if (ref != null && !ref.equalsIgnoreCase(REDACTED)) {
      credentialRef = ref;
    } else if (existing != null) {
      credentialRef = existing.credentialRef();
    } else {
      throw new BadRequestException(
          "Give a username and password, or a credential reference, to " + what + " with");
    }
    return new Unsaved(new SourceProbe.Target(engine, host, port, database), credentialRef);
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
          "missingTables", report.missingTables(),
          "excluded", report.excluded(),
          "outOfScope", report.outOfScope());
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

  private static String asText(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static UUID parseUuid(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("That is not a source id");
    }
  }

  private static WebApplicationException conflict(String message) {
    return new WebApplicationException(
        Response.status(Response.Status.CONFLICT)
            .entity(Map.of("message", message))
            .type(MediaType.APPLICATION_JSON)
            .build());
  }
}
