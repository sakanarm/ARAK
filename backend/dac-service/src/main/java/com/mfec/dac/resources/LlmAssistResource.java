package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.catalog.CatalogQuery;
import com.mfec.dac.llm.AssistPrompts;
import com.mfec.dac.llm.LlmClient;
import com.mfec.dac.llm.LlmSettingStore;
import com.mfec.dac.llm.LlmSettings.EffectiveSetting;
import com.mfec.dac.llm.LlmSettings.Gateway;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two places the assistant is actually useful (FR-2.6, M11).
 *
 * <p>M11 shipped in two halves. The first was the gateway each person
 * configures for themselves; this is the second, and without it the first was
 * a settings page that did nothing. A question becomes a statement on the
 * query console, and a sentence becomes a draft policy in the builder.
 *
 * <h2>What the assistant is not allowed to do</h2>
 *
 * <ul>
 *   <li><b>It never sees a row.</b> The prompt is built from the catalogue
 *       cache — names, types, descriptions and tags. There is no path from a
 *       result set to a gateway, and there must not be one: a model that has
 *       been shown a row has read data on behalf of whoever asked, outside
 *       every policy this product exists to apply. The prompt-building lives in
 *       {@link AssistPrompts} precisely so that this is testable without a
 *       network.
 *   <li><b>It never applies anything.</b> Both endpoints return text. The SQL
 *       goes into an editor the reader must press Run on, and that run goes
 *       through the proxy and is rewritten against their policy like any other.
 *       The policy comes back as a document with {@code lifecycleState: DRAFT}
 *       that somebody has to save and then activate. This is FR-2.6 separation
 *       of duty, and it is the reason this resource has no write path at all.
 *   <li><b>It runs as the caller, through the caller's own gateway.</b> The
 *       setting is read here rather than trusted from the request, so somebody
 *       who has the assistant switched off cannot reach a gateway by posting
 *       here directly.
 * </ul>
 *
 * <h2>What the caller can already see</h2>
 *
 * <p>The catalogue is metadata, and this platform does not treat metadata as
 * secret — the same names and tags are on the catalogue pages every signed-in
 * account can open. So the brief is not narrowed per caller, and it would be
 * misleading to imply it was. What is narrowed is the outcome: the statement
 * the assistant writes is only worth what the reader's own policy allows when
 * they run it.
 */
@Path("/v1/llm/assist")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class LlmAssistResource {

  private static final Logger LOG = LoggerFactory.getLogger(LlmAssistResource.class);

  /**
   * How many tables the model is shown.
   *
   * <p>Enough that a join across a couple of fact and dimension tables is
   * possible, few enough that the prompt stays affordable on a gateway
   * somebody is paying for personally. Beyond this the brief is the most
   * relevant tables rather than all of them.
   */
  private static final int MAX_TABLES = 12;

  /** How wide the net is cast before relevance narrows it. */
  private static final int CANDIDATES = 300;

  private final LlmSettingStore store;
  private final LlmClient client;
  private final CatalogQuery catalog;

  public LlmAssistResource(LlmSettingStore store, LlmClient client, CatalogQuery catalog) {
    this.store = store;
    this.client = client;
    this.catalog = catalog;
  }

  // ---------------------------------------------------------------- requests

  /** A question about a source, in whatever words the asker used. */
  public record SqlAsk(String question, UUID sourceId, String engine, String model) {}

  /** What the assistant made of it. */
  public record SqlDraft(
      String sql,
      String model,
      List<String> tables,
      /** Set when the model said the question cannot be answered from these tables. */
      String problem,
      boolean personal) {}

  /** A sentence describing the policy somebody wants. */
  public record PolicyAsk(String intent, UUID sourceId, String model) {}

  /** A policy document, as text, for the builder to load and a human to save. */
  public record PolicyDraft(String document, String model, boolean personal) {}

  // -------------------------------------------------------------- NL -> SQL

  /**
   * Turns a question into one SELECT statement.
   *
   * <p>The statement is a suggestion and is returned as text, not run. What
   * comes back has not touched a database.
   */
  @POST
  @Path("/sql")
  public SqlDraft sql(SqlAsk ask, @Context SecurityContext security) {
    if (ask == null || ask.question() == null || ask.question().isBlank()) {
      throw new BadRequestException("A question is required");
    }
    if (ask.sourceId() == null) {
      throw new BadRequestException("Choose a source first, so the tables can be looked up");
    }
    AuthenticatedUser actor = caller(security);
    EffectiveSetting mine = ready(actor);

    List<AssistPrompts.Table> tables = tablesFor(ask.sourceId(), ask.question());
    if (tables.isEmpty()) {
      throw new BadRequestException(
          "Nothing has been crawled for this source yet, so there is no schema to write against");
    }
    String brief = AssistPrompts.schemaBrief(tables);

    String answer =
        ask(
            actor,
            mine,
            ask.model(),
            AssistPrompts.sqlSystem(ask.engine()),
            AssistPrompts.sqlUser(ask.question(), brief));
    String model = chosenModel(ask.model(), mine);
    boolean personal = mine.usingOwnGateway();

    String sql = AssistPrompts.extractSql(answer);
    List<String> used = tables.stream().map(AssistPrompts.Table::fqn).toList();

    if (sql.isEmpty() || AssistPrompts.isRefusal(sql)) {
      return new SqlDraft(
          "",
          model,
          used,
          "The assistant could not answer that from the tables in this source.",
          personal);
    }
    if (!AssistPrompts.looksReadOnly(sql)) {
      // Not put in the editor at all. The proxy would refuse it, but a console
      // that offers a statement beside a Run button is making a suggestion, and
      // this is not one worth making.
      LOG.warn(
          "Discarded an assistant suggestion for {} that was not a read-only query",
          actor.username());
      return new SqlDraft(
          "",
          model,
          used,
          "The assistant answered with something that was not a read-only query, so it was"
              + " discarded.",
          personal);
    }
    return new SqlDraft(sql, model, used, null, personal);
  }

  // ---------------------------------------------------------- policy drafts

  /**
   * Turns a sentence into a draft policy document.
   *
   * <p>Returned, never stored. The builder loads it as an unsaved form for
   * somebody to correct, and saving it is their act under their name — which is
   * the whole point of drafting rather than applying (FR-9.1).
   */
  @POST
  @Path("/policy")
  public PolicyDraft policy(PolicyAsk ask, @Context SecurityContext security) {
    if (ask == null || ask.intent() == null || ask.intent().isBlank()) {
      throw new BadRequestException("Say what the policy should do");
    }
    AuthenticatedUser actor = caller(security);
    EffectiveSetting mine = ready(actor);

    String brief =
        ask.sourceId() == null
            ? ""
            : AssistPrompts.schemaBrief(tablesFor(ask.sourceId(), ask.intent()));

    String answer =
        ask(
            actor,
            mine,
            ask.model(),
            AssistPrompts.policySystem(policySchemas()),
            AssistPrompts.policyUser(ask.intent(), brief));

    String document = AssistPrompts.extractJson(answer);
    if (document.isEmpty()) {
      throw new ServiceUnavailableException(
          "The assistant did not answer with a policy document. Try saying it a different way.");
    }
    return new PolicyDraft(document, chosenModel(ask.model(), mine), mine.usingOwnGateway());
  }

  // ----------------------------------------------------------------- helpers

  /**
   * The tables of one source, narrowed to the question.
   *
   * <p>Columns come from the asset detail rather than the summary, because the
   * summary carries a count and the model needs the names. That is one query
   * per table, which is why the count is capped before the loop and not after.
   */
  private List<AssistPrompts.Table> tablesFor(UUID sourceId, String question) {
    CatalogQuery.AssetPage page =
        catalog.assets(null, "TABLE", List.of(), null, sourceId, CANDIDATES, 0);

    List<AssistPrompts.Table> shallow = new ArrayList<>();
    for (CatalogQuery.AssetSummary asset : page.items()) {
      shallow.add(new AssistPrompts.Table(asset.fqn(), asset.description(), List.of()));
    }
    List<AssistPrompts.Table> chosen = AssistPrompts.mostRelevant(shallow, question, MAX_TABLES);

    List<AssistPrompts.Table> full = new ArrayList<>();
    for (AssistPrompts.Table table : chosen) {
      Optional<CatalogQuery.AssetDetail> detail = catalog.asset(table.fqn());
      if (detail.isEmpty()) {
        continue;
      }
      List<AssistPrompts.Column> columns = new ArrayList<>();
      for (CatalogQuery.ColumnDetail column : detail.get().columns()) {
        columns.add(
            new AssistPrompts.Column(
                column.name(), column.dataType(), column.description(), tagNames(column)));
      }
      full.add(new AssistPrompts.Table(table.fqn(), table.description(), columns));
    }
    return full;
  }

  /** The facet names on a column, deduplicated, for the brief. */
  private static List<String> tagNames(CatalogQuery.ColumnDetail column) {
    Set<String> names = new LinkedHashSet<>();
    if (column.facets() != null) {
      for (CatalogQuery.FacetRow facet : column.facets()) {
        names.add(facet.facetFqn());
      }
    }
    return List.copyOf(names);
  }

  /**
   * The policy JSON Schemas, handed to the model as the contract.
   *
   * <p>Read from the classpath rather than restated here, so the shape the
   * model is asked for is the same file the engine's classes are generated
   * from. A prose summary of a schema is a second source of truth and would be
   * the one that rots.
   */
  private String policySchemas() {
    StringBuilder out = new StringBuilder();
    for (String name : List.of("policy.json", "subjectRule.json", "dataPolicy.json")) {
      String path = "json/schema/entity/policy/" + name;
      try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
        if (in == null) {
          LOG.warn("Policy schema {} is not on the classpath; drafting without it", path);
          continue;
        }
        out.append("// ").append(name).append('\n');
        out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim()).append("\n\n");
      } catch (IOException e) {
        LOG.warn("Could not read policy schema {}", path, e);
      }
    }
    return out.toString().trim();
  }

  /**
   * The caller's setting, or the reason they cannot use this.
   *
   * <p>Read from the store, never from the request: switching the assistant off
   * has to mean it is off, including for somebody who knows the URL of this
   * endpoint.
   */
  private EffectiveSetting ready(AuthenticatedUser actor) {
    EffectiveSetting mine = store.effectiveFor(actor.id());
    if (!mine.enabled()) {
      throw new ForbiddenException("The assistant is not switched on for this account");
    }
    if (!mine.available()) {
      throw new ServiceUnavailableException(mine.problem());
    }
    return mine;
  }

  private String ask(
      AuthenticatedUser actor,
      EffectiveSetting mine,
      String requestedModel,
      String system,
      String user) {
    try {
      Gateway gateway = store.gatewayFor(actor.id());
      LlmClient.Completion answer =
          client.complete(gateway, chosenModel(requestedModel, mine), system, user);
      return answer.text() == null ? "" : answer.text();
    } catch (LlmClient.LlmException e) {
      throw new ServiceUnavailableException(e.getMessage());
    }
  }

  private static String chosenModel(String requested, EffectiveSetting mine) {
    return requested != null && !requested.isBlank()
        ? requested.trim()
        : mine.effectiveModel();
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }
}
