package com.mfec.dac.resources;

import com.mfec.dac.catalog.CatalogQuery;
import com.mfec.dac.catalog.SearchQuery;
import com.mfec.dac.llm.AgentPrompts;
import com.mfec.dac.llm.AssistPrompts;
import com.mfec.dac.source.DataSourceStore;
import jakarta.ws.rs.ServiceUnavailableException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the tables a sentence describes, among the ones its writer may use (M16).
 *
 * <p>Two questions go to the model, and the permission check sits between them.
 * The first is the sentence alone, turned into English search words. The
 * search is then run and every table it turns up is checked as the asker:
 * one they may neither read nor request is dropped before anything about it
 * is written into a prompt, and a readable one loses the columns their
 * decision hides. Only then is the model shown the survivors -- names, types,
 * tags and descriptions, never a row -- and asked which fit and why.
 *
 * <p>What comes back is a list for the page to show. Nothing here runs a
 * statement or sends a request; the page offers both as buttons, and each goes
 * through the proxy or the request form like anything else the person does.
 */
final class DataFinder {

  /** One answer from the model, to one standing instruction and one message. */
  @FunctionalInterface
  interface Model {
    String answer(String system, String user);
  }

  /**
   * A table found for the sentence, as the page shows it.
   *
   * @param access READABLE or REQUESTABLE, from the permission check, never from the model
   * @param columns the columns the model named, all of them ones this person may see
   * @param sourceId the source a query reaches the table through, or null when none is connected
   */
  public record Found(
      String fqn,
      String access,
      String description,
      String why,
      List<String> columns,
      String sourceId,
      String engine) {}

  /**
   * The words searched for, and the tables found with them.
   *
   * @param outOfReach tables the search reached that this person may neither read
   *     nor request, counted and never named: the catalogue lists them with the
   *     reason, and without the count "nothing matched" would read as the data
   *     not existing
   */
  public record Result(List<String> keywords, List<Found> tables, int outOfReach) {}

  /** Search hits read for one keyword. */
  static final int HITS_PER_KEYWORD = 30;

  /**
   * Tables described to the model. Each is a catalogue read and, for a
   * readable one, a decision, so the list is short.
   */
  static final int MAX_BRIEFED = 10;

  /** Columns of one table the model is shown; the ones a keyword names come first. */
  static final int MAX_BRIEF_COLUMNS = 60;

  private final AssistToolbox.Deps deps;
  private final AssistToolbox toolbox;

  DataFinder(AssistToolbox.Deps deps, AssistToolbox toolbox) {
    this.deps = deps;
    this.toolbox = toolbox;
  }

  Result find(String want, String language, Model model) {
    List<String> keywords =
        AssistPrompts.extractKeywords(
            model.answer(AssistPrompts.findKeywordsSystem(), AssistPrompts.findKeywordsUser(want)));
    if (keywords.isEmpty()) {
      keywords = AssistPrompts.wordsOf(want);
    }

    List<AssistToolbox.Seen> seen = new ArrayList<>();
    int checked = 0;
    int outOfReach = 0;
    for (String fqn : candidates(keywords)) {
      if (checked++ >= AssistToolbox.MAX_CHECKED || seen.size() >= MAX_BRIEFED) {
        break;
      }
      Optional<AssistToolbox.Seen> table = toolbox.seen(fqn);
      if (table.isPresent()) {
        seen.add(table.get());
      } else {
        outOfReach++;
      }
    }
    if (seen.isEmpty()) {
      return new Result(keywords, List.of(), outOfReach);
    }

    List<AssistPrompts.Table> shown = new ArrayList<>();
    for (AssistToolbox.Seen table : seen) {
      shown.add(brief(table, keywords));
    }
    Optional<List<AssistPrompts.Pick>> picks =
        AssistPrompts.extractPicks(
            model.answer(
                AssistPrompts.findDataSystem(language), AssistPrompts.findDataUser(want, shown)),
            shown);
    if (picks.isEmpty()) {
      throw new ServiceUnavailableException(
          "The assistant did not answer with a list of tables. Try again in a moment.");
    }

    Map<String, AssistToolbox.Seen> byFqn = new LinkedHashMap<>();
    for (AssistToolbox.Seen table : seen) {
      byFqn.put(table.asset().fqn(), table);
    }
    List<Found> found = new ArrayList<>();
    for (AssistPrompts.Pick pick : picks.get()) {
      AssistToolbox.Seen table = byFqn.get(pick.fqn());
      CatalogQuery.AssetSummary asset = table.asset();
      Optional<DataSourceStore.Source> source =
          toolbox.sourceNamed(
              asset.querySource() != null ? asset.querySource() : asset.dataSource());
      found.add(
          new Found(
              asset.fqn(),
              table.access(),
              asset.description() == null || asset.description().isBlank()
                  ? null
                  : AgentPrompts.cap(asset.description().trim(), 200),
              pick.why(),
              pick.columns(),
              source.map(s -> s.id().toString()).orElse(null),
              source.map(s -> s.engine().name()).orElse(null)));
    }
    return new Result(keywords, found, outOfReach);
  }

  /**
   * The tables the keywords reach, most reached first. A table matched by its
   * own name or description counts for more than one matched through a column,
   * and a table several keywords reach comes before one only a single keyword
   * does. Ties keep the order the search gave.
   */
  private List<String> candidates(List<String> keywords) {
    Map<String, Integer> scores = new LinkedHashMap<>();
    for (String keyword : keywords) {
      Set<String> counted = new LinkedHashSet<>();
      for (SearchQuery.Hit hit : deps.search().search(keyword, HITS_PER_KEYWORD).items()) {
        String fqn = AssistToolbox.tableOf(hit);
        if (fqn != null && counted.add(fqn)) {
          scores.merge(fqn, "asset".equals(hit.kind()) ? 3 : 1, Integer::sum);
        }
      }
    }
    List<Map.Entry<String, Integer>> ranked = new ArrayList<>(scores.entrySet());
    ranked.sort((a, b) -> b.getValue() - a.getValue());
    return ranked.stream().map(Map.Entry::getKey).toList();
  }

  /** One table for the prompt: its visible columns, the ones a keyword names first. */
  private static AssistPrompts.Table brief(AssistToolbox.Seen table, List<String> keywords) {
    List<CatalogQuery.ColumnDetail> named = new ArrayList<>();
    List<CatalogQuery.ColumnDetail> rest = new ArrayList<>();
    for (CatalogQuery.ColumnDetail column : table.columns()) {
      (mentions(column, keywords) ? named : rest).add(column);
    }
    Set<CatalogQuery.ColumnDetail> kept = new LinkedHashSet<>();
    for (List<CatalogQuery.ColumnDetail> group : List.of(named, rest)) {
      for (CatalogQuery.ColumnDetail column : group) {
        if (kept.size() < MAX_BRIEF_COLUMNS) {
          kept.add(column);
        }
      }
    }
    List<AssistPrompts.Column> columns = new ArrayList<>();
    for (CatalogQuery.ColumnDetail column : table.columns()) {
      if (kept.contains(column)) {
        columns.add(
            new AssistPrompts.Column(
                column.name(), column.dataType(), column.description(), tags(column)));
      }
    }
    return new AssistPrompts.Table(table.asset().fqn(), table.asset().description(), columns);
  }

  private static boolean mentions(CatalogQuery.ColumnDetail column, List<String> keywords) {
    String text =
        (column.name() + " " + (column.description() == null ? "" : column.description()) + " "
                + String.join(" ", tags(column)))
            .toLowerCase(Locale.ROOT);
    for (String keyword : keywords) {
      String word = keyword.toLowerCase(Locale.ROOT);
      if (text.contains(word) || text.contains(word.replace(' ', '_'))) {
        return true;
      }
    }
    return false;
  }

  private static List<String> tags(CatalogQuery.ColumnDetail column) {
    Set<String> names = new LinkedHashSet<>();
    if (column.facets() != null) {
      for (CatalogQuery.FacetRow facet : column.facets()) {
        names.add(facet.facetFqn());
      }
    }
    return List.copyOf(names);
  }
}
