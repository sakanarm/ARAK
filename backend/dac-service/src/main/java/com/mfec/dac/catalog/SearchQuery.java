package com.mfec.dac.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jdbi.v3.core.Jdbi;

/**
 * One search box over everything the platform knows about.
 *
 * <p>A person looking for {@code citizen_id} does not know, and should not have
 * to know, whether it is a column, a tag, a glossary term or the name of a
 * policy — those are our filing categories, not theirs. So this searches the
 * assets, their columns, all six governance vocabularies and the policies in
 * one query and lets the caller sort out which screen each hit belongs on.
 *
 * <p>It is a UNION rather than one query per kind because the ranking has to be
 * global: an exact match on a tag must beat a substring match on an asset, and
 * that ordering does not exist if each kind is ranked in its own list.
 *
 * <p>Deliberately not full-text search. Postgres {@code tsvector} would stem
 * and tokenise, which is right for prose and wrong here: these are identifiers,
 * and somebody typing {@code cust} expects {@code customer} — a prefix, not a
 * lexeme. When this outgrows {@code ILIKE} the answer is the OpenSearch index
 * the plan already has in Phase 1.5, not a worse index here.
 */
public class SearchQuery {

  /** Above this, the box is not the right tool and the Catalog page is. */
  private static final int MAX_LIMIT = 50;

  private final Jdbi jdbi;

  public SearchQuery(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /**
   * One hit.
   *
   * @param kind what it is: {@code asset}, {@code column}, {@code policy}, or a
   *     governance facet type
   * @param facetType the {@code asset_facet.facet_type} a vocabulary hit filters
   *     the catalog by, null for assets, columns and policies
   * @param subtype an asset's type, a column's data type, a policy's kind
   * @param id set for a policy, whose route is by id rather than by name
   * @param assets how many assets a vocabulary value reaches, inheritance
   *     included; -1 when the number does not apply
   */
  public record Hit(
      String kind,
      String facetType,
      String subtype,
      String id,
      String fqn,
      String name,
      String displayName,
      String description,
      String parentFqn,
      int assets) {}

  public record Results(String query, int limit, List<Hit> items) {}

  public Results search(String query, int limit) {
    String term = query == null ? "" : query.trim();
    int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
    if (term.length() < 2) {
      // One character matches most of the catalog, which is the same as no
      // answer but slower and with a scroll bar.
      return new Results(term, capped, List.of());
    }

    String lower = term.toLowerCase(Locale.ROOT);
    String contains = '%' + escapeLike(lower) + '%';
    String prefix = escapeLike(lower) + '%';

    List<Hit> hits =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(SQL)
                    .bind("exact", lower)
                    .bind("prefix", prefix)
                    .bind("contains", contains)
                    .bind("limit", capped)
                    .map(
                        (rs, ctx) ->
                            new Hit(
                                rs.getString("kind"),
                                rs.getString("facet_type"),
                                rs.getString("subtype"),
                                rs.getString("id"),
                                rs.getString("fqn"),
                                rs.getString("name"),
                                rs.getString("display_name"),
                                rs.getString("description"),
                                rs.getString("parent_fqn"),
                                rs.getInt("assets")))
                    .list());

    return new Results(term, capped, new ArrayList<>(hits));
  }

  /**
   * Escapes the wildcards, so a tag literally named {@code 100%} is findable.
   *
   * <p>The escape character is {@code !} rather than the conventional
   * backslash, because JDBI scans this SQL itself to find the named
   * parameters: a backslash inside the {@code ESCAPE} literal leaves it
   * thinking the string never closed, so every {@code :name} after that point
   * goes unbound and Postgres reports a syntax error at the colon.
   */
  private static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  /**
   * The ranking, written once and reused by every branch.
   *
   * <p>Exact name, then name prefix, then fqn prefix, then anywhere. Ties break
   * on how short the name is, because a match inside {@code customer} is more
   * likely what was meant than the same match inside
   * {@code customer_address_history_archive}.
   */
  private static final String RANK =
      """
      CASE
        WHEN lower(%1$s) = :exact              THEN 0
        WHEN lower(%1$s) LIKE :prefix ESCAPE '!' THEN 1
        WHEN lower(%2$s) LIKE :prefix ESCAPE '!' THEN 2
        ELSE 3
      END""";

  private static String rank(String nameColumn, String fqnColumn) {
    return RANK.formatted(nameColumn, fqnColumn);
  }

  private static String matches(String nameColumn, String fqnColumn, String displayColumn) {
    String display =
        displayColumn == null
            ? ""
            : " OR lower(" + displayColumn + ") LIKE :contains ESCAPE '!'";
    return "(lower("
        + nameColumn
        + ") LIKE :contains ESCAPE '!' OR lower("
        + fqnColumn
        + ") LIKE :contains ESCAPE '!'"
        + display
        + ")";
  }

  /**
   * Vocabulary branches differ only in table and facet type, so they are built
   * from one template rather than written out six times and drifting apart.
   */
  private static String vocabulary(
      String kind, String table, String facetType, String parentColumn) {
    return """
        SELECT '%1$s' AS kind, '%2$s' AS facet_type, NULL AS subtype, NULL AS id,
               v.fqn, v.name, v.display_name, v.description,
               %3$s AS parent_fqn,
               COALESCE(f.total, 0) AS assets,
               %4$s AS rank, length(v.name) AS tiebreak
        FROM %5$s v
        LEFT JOIN (
            SELECT facet_fqn, count(*) AS total FROM asset_facet
            WHERE facet_type = '%2$s' GROUP BY facet_fqn
        ) f ON f.facet_fqn = v.fqn
        WHERE %6$s
        """
        .formatted(
            kind,
            facetType,
            parentColumn == null ? "NULL" : "v." + parentColumn,
            rank("v.name", "v.fqn"),
            table,
            matches("v.name", "v.fqn", "v.display_name"));
  }

  private static final String SQL =
      """
      SELECT kind, facet_type, subtype, id, fqn, name, display_name, description,
             parent_fqn, assets
      FROM (
        SELECT 'asset' AS kind, NULL AS facet_type, a.asset_type AS subtype, NULL AS id,
               a.fqn, a.name, a.display_name, a.description, a.parent_fqn,
               -1 AS assets, %1$s AS rank, length(a.name) AS tiebreak
        FROM asset a
        WHERE a.is_current AND %2$s

        UNION ALL

        -- Columns are where the sensitive things live, so they are searchable
        -- in their own right rather than only through the table they sit in.
        -- On the name alone, unlike every other branch: a column's fqn ends in
        -- the table's, so matching it would return all forty columns of
        -- `customers` for the word "cus" and bury the table itself.
        SELECT 'column', NULL, c.data_type, NULL,
               c.fqn, c.name, NULL, c.description, a.fqn,
               -1, %3$s, length(c.name)
        FROM asset_column c
        JOIN asset a ON a.id = c.asset_id AND a.is_current
        WHERE c.is_current AND %4$s

        UNION ALL
        %5$s
        UNION ALL
        %6$s
        UNION ALL
        %7$s
        UNION ALL
        %8$s
        UNION ALL
        %9$s
        UNION ALL
        %10$s

        UNION ALL

        -- Archived policies are left out: they cannot be edited and cannot
        -- affect a decision, so offering one is offering a dead end.
        SELECT 'policy', NULL, p.policy_type, p.id::text,
               p.scope_fqn, p.name, p.display_name, p.description, p.scope_level,
               -1, %11$s, length(p.name)
        FROM policy p
        WHERE p.lifecycle_state <> 'ARCHIVED' AND %12$s
      ) hits
      ORDER BY rank, tiebreak, name
      LIMIT :limit
      """
          .formatted(
              rank("a.name", "a.fqn"),
              matches("a.name", "a.fqn", "a.display_name"),
              rank("c.name", "c.name"),
              matches("c.name", "c.name", null),
              vocabulary("tag", "tag", "tags", "classification_fqn"),
              vocabulary("classification", "classification", "classifications", null),
              vocabulary("term", "glossary_term", "terms", "glossary_fqn"),
              vocabulary("glossary", "glossary", "glossaries", null),
              vocabulary("domain", "domain", "domains", "parent_fqn"),
              vocabulary("dataProduct", "data_product", "dataProducts", "domain_fqn"),
              rank("p.name", "COALESCE(p.scope_fqn, p.name)"),
              matches("p.name", "COALESCE(p.scope_fqn, '')", "p.display_name"));
}
