package com.mfec.dac.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mfec.dac.access.AccessEligibility;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.catalog.CatalogQuery;
import com.mfec.dac.catalog.SearchQuery;
import com.mfec.dac.llm.AgentPrompts;
import com.mfec.dac.llm.ArakAgent.Card;
import com.mfec.dac.llm.ArakAgent.Result;
import com.mfec.dac.llm.ArakAgent.Toolbox;
import com.mfec.dac.llm.AssistPrompts;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.source.DataSourceStore;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.SecurityContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The assistant's tools, bound to one person for one request (M28).
 *
 * <p>Each tool is a page of this console the person could open themselves,
 * asked the same way the page asks:
 *
 * <ul>
 *   <li>{@code search_catalog} and {@code describe_asset} check every table
 *       against the person's own decision before the model hears of it, and
 *       say the same "not found" for a table that does not exist as for one
 *       they may neither read nor request. The assistant is not a way to learn
 *       what is there.
 *   <li>{@code query_log} and {@code dashboard} call the resources behind those
 *       pages with the person's own security context, so the scoping is not a
 *       copy that could drift: it is the same method. What comes back has its
 *       statements' literals taken out, and carries no client address, because
 *       neither the log page nor this ever has one.
 *   <li>{@code write_sql}, {@code draft_policy} and {@code navigate} only make
 *       cards. A card is something the person clicks; nothing here runs,
 *       saves, approves or opens anything.
 * </ul>
 */
public final class AssistToolbox implements Toolbox {

  private static final Logger LOG = LoggerFactory.getLogger(AssistToolbox.class);

  /** Tables a search checks decisions for. Each check is a decision, so few. */
  static final int MAX_CHECKED = 30;

  /** Tables a search returns to the model. */
  static final int MAX_FOUND = 15;

  /** Asset cards a search shows the person. */
  static final int MAX_ASSET_CARDS = 6;

  /** Columns described per table. A very wide table is summarised by its first ones. */
  static final int MAX_COLUMNS = 120;

  /** How much of one logged statement the model is shown. */
  static final int MAX_LOGGED_SQL = 300;

  /** How a policy draft is produced; the resource owns the prompt for it. */
  @FunctionalInterface
  interface PolicyDrafter {
    String draft(String intent, UUID sourceId);
  }

  /** Everything the tools read, wired once by the application. */
  public record Deps(
      ObjectMapper json,
      CatalogQuery catalog,
      SearchQuery search,
      AccessEligibility eligibility,
      DecisionService decisions,
      DataSourceStore sources,
      AuditResource audit,
      DashboardResource dashboard) {}

  /** Where the person is: the source the editor points at, the table on screen. */
  record Here(UUID sourceId, String assetFqn) {}

  private final Deps deps;
  private final AuthenticatedUser actor;
  private final SecurityContext security;
  private final String ip;
  private final Here here;
  private final PolicyDrafter drafter;

  /** Verdicts already asked for in this conversation step, by FQN. */
  private final Map<String, AccessEligibility.Verdict> verdicts = new LinkedHashMap<>();

  AssistToolbox(
      Deps deps,
      AuthenticatedUser actor,
      SecurityContext security,
      String ip,
      Here here,
      PolicyDrafter drafter) {
    this.deps = deps;
    this.actor = actor;
    this.security = security;
    this.ip = ip;
    this.here = here == null ? new Here(null, null) : here;
    this.drafter = drafter;
  }

  @Override
  public Result run(String name, JsonNode arguments) {
    return switch (name) {
      case "search_catalog" -> searchCatalog(text(arguments, "query", 120));
      case "describe_asset" -> describeAsset(text(arguments, "fqn", 500));
      case "write_sql" ->
          writeSql(
              text(arguments, "sql", LlmAssistResource.MAX_STATEMENT + 100),
              text(arguments, "table", 500),
              text(arguments, "title", 80));
      case "draft_policy" -> draftPolicy(text(arguments, "intent", 1000));
      case "navigate" ->
          navigate(
              text(arguments, "page", 40),
              text(arguments, "target", 500),
              text(arguments, "title", 80));
      case "query_log" ->
          queryLog(
              text(arguments, "outcome", 20),
              text(arguments, "principal", 200),
              text(arguments, "text", 200),
              text(arguments, "table", 500),
              integer(arguments, "days", 7, 1, 90),
              integer(arguments, "limit", 20, 1, 50));
      case "dashboard" ->
          dashboard(integer(arguments, "days", 30, 1, 90), text(arguments, "label", 100));
      default -> Result.text("error: there is no tool called " + name);
    };
  }

  // ---------------------------------------------------------------- catalogue

  Result searchCatalog(String query) {
    if (query == null || query.length() < 2) {
      return Result.text("error: give at least one keyword of two or more letters");
    }
    Set<String> terms = new LinkedHashSet<>();
    terms.add(query);
    for (String word : query.split("[\\s,;]+")) {
      if (word.length() >= 2 && terms.size() < 5) {
        terms.add(word);
      }
    }

    // Candidates first, decisions second: a decision per hit is the expensive
    // part, so it is asked only of tables, and only of the first few.
    Map<String, String> candidates = new LinkedHashMap<>();
    for (String term : terms) {
      for (SearchQuery.Hit hit : deps.search().search(term, 50).items()) {
        String fqn = tableOf(hit);
        if (fqn != null && !candidates.containsKey(fqn)) {
          candidates.put(fqn, "asset".equals(hit.kind()) ? hit.description() : null);
        }
      }
    }

    ArrayNode tables = deps.json().createArrayNode();
    List<Card> cards = new ArrayList<>();
    int checked = 0;
    for (Map.Entry<String, String> candidate : candidates.entrySet()) {
      if (checked++ >= MAX_CHECKED || tables.size() >= MAX_FOUND) {
        break;
      }
      String access = access(candidate.getKey());
      if (access == null) {
        continue;
      }
      ObjectNode table = tables.addObject();
      table.put("fqn", candidate.getKey());
      String description = candidate.getValue();
      if (description != null && !description.isBlank()) {
        table.put("description", AgentPrompts.cap(description.trim(), 200));
      }
      table.put("access", access);
      if (cards.size() < MAX_ASSET_CARDS) {
        cards.add(assetCard(candidate.getKey(), description, access));
      }
    }

    ObjectNode out = deps.json().createObjectNode();
    out.put("query", query);
    out.set("tables", tables);
    out.put(
        "note",
        tables.isEmpty()
            ? "No table this person can read or request matched. Try other English keywords, or"
                + " say nothing was found."
            : "Only tables this person can read or may request are listed.");
    return new Result(out.toString(), cards);
  }

  Result describeAsset(String fqn) {
    if (fqn == null || fqn.isBlank()) {
      return Result.text("error: fqn is required");
    }
    Optional<CatalogQuery.AssetDetail> detail = deps.catalog().asset(fqn.trim());
    String access = detail.isEmpty() ? null : access(detail.get().asset().fqn());
    if (access == null) {
      // The same words either way, so this cannot be used to test for a name.
      return Result.text("not found, or not visible to this person: " + fqn.trim());
    }
    CatalogQuery.AssetSummary asset = detail.get().asset();

    Set<String> hidden = new HashSet<>();
    if ("READABLE".equals(access)) {
      List<String> dropped =
          deps.decisions()
              .decide(new DecisionService.Ask(actor.getName(), asset.fqn(), null, ip, null, null))
              .getHiddenColumns();
      if (dropped != null) {
        for (String column : dropped) {
          hidden.add(column.toLowerCase(Locale.ROOT));
        }
      }
    }

    ObjectNode out = deps.json().createObjectNode();
    out.put("fqn", asset.fqn());
    out.put("type", asset.assetType());
    if (asset.description() != null && !asset.description().isBlank()) {
      out.put("description", AgentPrompts.cap(asset.description().trim(), 400));
    }
    out.put("access", access);
    Optional<DataSourceStore.Source> source = sourceNamed(asset.dataSource());
    source.ifPresent(
        s -> {
          out.put("sourceId", s.id().toString());
          out.put("engine", s.engine().name());
        });
    ArrayNode columns = out.putArray("columns");
    int kept = 0;
    for (CatalogQuery.ColumnDetail column : detail.get().columns()) {
      if (hidden.contains(lower(column.name())) || hidden.contains(lower(column.fqn()))) {
        continue;
      }
      if (kept++ >= MAX_COLUMNS) {
        out.put("moreColumns", true);
        break;
      }
      ObjectNode c = columns.addObject();
      c.put("name", column.name());
      c.put("type", column.dataType());
      if (column.description() != null && !column.description().isBlank()) {
        c.put("description", AgentPrompts.cap(column.description().trim(), 150));
      }
      Set<String> tags = new LinkedHashSet<>();
      if (column.facets() != null) {
        for (CatalogQuery.FacetRow facet : column.facets()) {
          tags.add(facet.facetFqn());
        }
      }
      if (!tags.isEmpty()) {
        ArrayNode t = c.putArray("tags");
        tags.forEach(t::add);
      }
    }
    if ("REQUESTABLE".equals(access)) {
      out.put(
          "note",
          "This person cannot read this table yet but may request access on its page. A query"
              + " against it will be refused until a request is approved.");
    }
    return Result.text(out.toString());
  }

  // ------------------------------------------------------------------ cards

  Result writeSql(String sql, String table, String title) {
    if (sql == null || sql.isBlank()) {
      return Result.text("error: sql is required");
    }
    String statement = AssistPrompts.extractSql(sql);
    if (statement.isEmpty() || AssistPrompts.isRefusal(statement)) {
      return Result.text("error: that is not a statement");
    }
    if (statement.length() > LlmAssistResource.MAX_STATEMENT) {
      return Result.text("error: the statement is too long");
    }
    if (!AssistPrompts.looksReadOnly(statement)) {
      LOG.warn("Discarded an assistant statement for {} that was not read-only", actor.username());
      return Result.text(
          "error: only one read-only SELECT statement can be offered. Nothing was shown.");
    }

    UUID sourceId = here.sourceId();
    String assetFqn = null;
    if (table != null && !table.isBlank()) {
      Optional<CatalogQuery.AssetDetail> detail = deps.catalog().asset(table.trim());
      if (detail.isPresent() && access(detail.get().asset().fqn()) != null) {
        assetFqn = detail.get().asset().fqn();
        Optional<DataSourceStore.Source> source = sourceNamed(detail.get().asset().dataSource());
        if (source.isPresent()) {
          sourceId = source.get().id();
        }
      }
    }
    String engine =
        sourceId == null
            ? null
            : deps.sources().find(sourceId).map(s -> s.engine().name()).orElse(null);
    Card card =
        new Card(
            "sql",
            title == null || title.isBlank() ? "Suggested query" : title,
            statement,
            "/query",
            sourceId == null ? null : sourceId.toString(),
            engine,
            assetFqn,
            null);
    return new Result(
        "Shown to the person as a card they can put in the query editor. It has NOT been run:"
            + " they run it themselves, and their policy is applied then.",
        List.of(card));
  }

  Result draftPolicy(String intent) {
    if (intent == null || intent.isBlank()) {
      return Result.text("error: intent is required");
    }
    String document;
    try {
      document = drafter.draft(intent, here.sourceId());
    } catch (WebApplicationException e) {
      return Result.text("error: " + e.getMessage());
    }
    Card card =
        new Card("policy", "Draft policy", document, "/policies/new", null, null, null, null);
    return new Result(
        "Shown to the person as a draft they can load into the policy builder. It is NOT saved"
            + " and NOT active. The draft:\n"
            + AgentPrompts.cap(document, 3000),
        List.of(card));
  }

  Result navigate(String page, String target, String title) {
    String route = AgentPrompts.route(page, target);
    if (route == null) {
      return Result.text(
          "error: no such page. Pages: " + String.join(", ", AgentPrompts.PAGES.keySet()));
    }
    String label =
        title == null || title.isBlank()
            ? "Open " + page.trim().toLowerCase(Locale.ROOT).replace('_', ' ')
            : title;
    return new Result(
        "Shown to the person as a link. They open it if they want to.",
        List.of(new Card("link", label, null, route, null, null, null, null)));
  }

  // ---------------------------------------------------------------- insights

  Result queryLog(
      String outcome, String principal, String text, String table, int days, int limit) {
    String wanted = outcome == null ? null : outcome.toUpperCase(Locale.ROOT);
    if (wanted != null && !Set.of("EXECUTED", "REJECTED", "FAILED").contains(wanted)) {
      wanted = null;
    }
    AuditResource.QueryPage page;
    try {
      page =
          deps.audit()
              .queries(
                  wanted, principal, text, table, null, days, null, null, null, limit, security);
    } catch (BadRequestException e) {
      return Result.text("error: " + e.getMessage());
    } catch (ForbiddenException e) {
      return Result.text("error: this person cannot read the query log");
    }

    ObjectNode out = deps.json().createObjectNode();
    out.put("since", String.valueOf(page.since()));
    out.put(
        "scope",
        switch (String.valueOf(page.scope())) {
          case "EVERYTHING" -> "every query (this person oversees everything)";
          case "OWNED" -> "their own queries and queries on tables they own";
          default -> "only their own queries";
        });
    if (page.counts() != null) {
      ObjectNode counts = out.putObject("counts");
      counts.put("total", page.counts().total());
      counts.put("executed", page.counts().executed());
      counts.put("rejected", page.counts().rejected());
      counts.put("failed", page.counts().failed());
    }
    ArrayNode rows = out.putArray("rows");
    for (AuditResource.QueryRow row : page.rows()) {
      ObjectNode r = rows.addObject();
      r.put("at", String.valueOf(row.occurredAt()));
      r.put("principal", row.principal());
      if (row.runBy() != null) {
        r.put("runBy", row.runBy());
      }
      r.put("source", row.sourceName());
      r.put("outcome", row.outcome());
      if (row.category() != null) {
        r.put("category", row.category().name());
      }
      String sql = row.sqlHidden() ? null : AgentPrompts.redactLiterals(row.originalSql());
      if (sql != null) {
        r.put("sql", AgentPrompts.cap(sql.replaceAll("\\s+", " ").trim(), MAX_LOGGED_SQL));
      } else if (row.sqlHidden()) {
        r.put("sql", "(hidden from this person)");
      }
      if (row.rejectReason() != null) {
        // Known text is the statement with its literals already gone, so a
        // value the database quoted back in its error goes too.
        r.put(
            "reason",
            AgentPrompts.cap(
                AssistPrompts.redactError(row.rejectReason(), sql == null ? "" : sql, ""), 300));
      }
      if (row.rowCount() != null) {
        r.put("rows", row.rowCount());
      }
      if (row.durationMs() != null) {
        r.put("ms", row.durationMs());
      }
      ArrayNode assets = r.putArray("tables");
      if (row.assets() != null) {
        row.assets().forEach(assets::add);
      }
      if (row.hiddenAssets() > 0) {
        r.put("otherTables", row.hiddenAssets());
      }
    }
    if (page.nextBefore() != null) {
      out.put("more", true);
    }
    return new Result(
        out.toString(),
        List.of(new Card("link", "Open the query log", null, "/audit", null, null, null, null)));
  }

  Result dashboard(int days, String label) {
    DashboardResource.Dashboard dashboard;
    try {
      dashboard =
          deps.dashboard()
              .dashboard(days, label == null || label.isBlank() ? "PII" : label, security);
    } catch (ForbiddenException e) {
      return Result.text(
          "error: the dashboard is only for administrators, policy authors and auditors; this"
              + " person cannot see it");
    } catch (BadRequestException e) {
      return Result.text("error: " + e.getMessage());
    }
    return new Result(
        deps.json().valueToTree(dashboard).toString(),
        List.of(new Card("link", "Open the dashboard", null, "/dashboard", null, null, null, null)));
  }

  // ----------------------------------------------------------------- helpers

  /**
   * READABLE, REQUESTABLE, or null when this person may do neither -- in which
   * case the table is, as far as the assistant is concerned, not there.
   */
  String access(String fqn) {
    AccessEligibility.Verdict verdict =
        verdicts.computeIfAbsent(
            fqn, f -> deps.eligibility().check(actor.getName(), f, ip, null));
    if (verdict.readable()) {
      return "READABLE";
    }
    return verdict.requestable() ? "REQUESTABLE" : null;
  }

  /** The table a hit is about, or null for a hit that is not one. */
  static String tableOf(SearchQuery.Hit hit) {
    if ("asset".equals(hit.kind())) {
      String type = hit.subtype() == null ? "" : hit.subtype().toUpperCase(Locale.ROOT);
      return type.contains("TABLE") || type.contains("VIEW") ? hit.fqn() : null;
    }
    if ("column".equals(hit.kind())) {
      return hit.parentFqn();
    }
    return null;
  }

  private Card assetCard(String fqn, String description, String access) {
    return new Card(
        "asset",
        fqn,
        description == null ? null : AgentPrompts.cap(description.trim(), 160),
        "/catalog/" + AgentPrompts.encodeFqn(fqn),
        null,
        null,
        fqn,
        access);
  }

  private Optional<DataSourceStore.Source> sourceNamed(String name) {
    if (name == null) {
      return Optional.empty();
    }
    return deps.sources().list().stream().filter(s -> name.equals(s.name())).findFirst();
  }

  private static String lower(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }

  private static String text(JsonNode arguments, String field, int max) {
    JsonNode value = arguments.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    String text = value.isTextual() ? value.asText() : value.toString();
    text = text.trim();
    if (text.isEmpty()) {
      return null;
    }
    return text.length() > max ? text.substring(0, max) : text;
  }

  private static int integer(JsonNode arguments, String field, int fallback, int min, int max) {
    JsonNode value = arguments.path(field);
    int n = value.canConvertToInt() ? value.asInt() : fallback;
    if (value.isTextual()) {
      try {
        n = Integer.parseInt(value.asText().trim());
      } catch (NumberFormatException e) {
        n = fallback;
      }
    }
    return Math.max(min, Math.min(max, n));
  }
}
