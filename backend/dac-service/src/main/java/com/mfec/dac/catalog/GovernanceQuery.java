package com.mfec.dac.catalog;

import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.Jdbi;

/**
 * The governance vocabulary a policy can be written against, with the two
 * numbers OpenMetadata cannot give (FR-1.2, FR-3.1.5).
 *
 * <p>OpenMetadata already lists these terms, and listing them again would be a
 * worse copy of its own screens. What it cannot answer is how many assets in
 * this platform actually carry a value, and which policies name it — the first
 * tells a policy author whether a selector will match anything, the second
 * tells a data steward what breaks if they retire a tag.
 *
 * <p>The asset count comes from {@code asset_facet}, so it counts what the
 * value effectively reaches, inherited values included: a tag on a schema is
 * carried by every table under it, and a policy written against that tag will
 * bind to all of them.
 */
public class GovernanceQuery {

  private final Jdbi jdbi;

  public GovernanceQuery(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /**
   * One value in the vocabulary.
   *
   * @param assets how many assets and columns carry it, inheritance included
   * @param directAssets how many carry it because somebody put it there
   * @param policies how many policies name it in a selector
   */
  public record Value(
      String fqn,
      String name,
      String parentFqn,
      String displayName,
      String description,
      int depth,
      String provenance,
      boolean disabled,
      boolean mutuallyExclusive,
      int assets,
      int directAssets,
      int policies,
      List<Value> children) {}

  public record Classifications(List<Value> items) {}

  /** Classifications with their tags nested underneath (FR-2A.3a). */
  public List<Value> classifications() {
    List<Value> tags = values("tag", "tags", "classification_fqn");
    List<Value> out = new ArrayList<>();
    for (Value classification : values("classification", "classifications", null)) {
      List<Value> children = new ArrayList<>();
      for (Value tag : tags) {
        if (classification.fqn().equals(tag.parentFqn())) {
          children.add(tag);
        }
      }
      out.add(withChildren(classification, children));
    }
    return out;
  }

  /** Glossaries with their terms nested underneath. */
  public List<Value> glossaries() {
    List<Value> terms = values("glossary_term", "terms", "glossary_fqn");
    List<Value> out = new ArrayList<>();
    for (Value glossary : values("glossary", "glossaries", null)) {
      List<Value> children = new ArrayList<>();
      for (Value term : terms) {
        if (glossary.fqn().equals(term.parentFqn())) {
          children.add(term);
        }
      }
      out.add(withChildren(glossary, children));
    }
    return out;
  }

  /**
   * Domains as the tree they are.
   *
   * <p>Sub-domains nest arbitrarily deep, and the difference between
   * {@code domains contains 'Finance'} and {@code domains eq 'Finance'} is only
   * legible next to the children it does or does not pick up (FR-2A.2).
   */
  public List<Value> domains() {
    List<Value> flat = values("domain", "domains", "parent_fqn");
    return tree(flat, null);
  }

  /** Data products, each under the domain it belongs to. */
  public List<Value> dataProducts() {
    return values("data_product", "dataProducts", "domain_fqn");
  }

  /** Custom property definitions, the typed ABAC attributes (FR-1.9). */
  public List<CustomProperty> customProperties() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT entity_type, name, display_name, description, data_type,
                           enum_values, multi_select
                    FROM custom_property_def
                    ORDER BY entity_type, name
                    """)
                .map(
                    (rs, ctx) ->
                        new CustomProperty(
                            rs.getString("entity_type"),
                            rs.getString("name"),
                            rs.getString("display_name"),
                            rs.getString("description"),
                            rs.getString("data_type"),
                            rs.getString("enum_values"),
                            rs.getBoolean("multi_select")))
                .list());
  }

  public record CustomProperty(
      String entityType,
      String name,
      String displayName,
      String description,
      String dataType,
      String enumValues,
      boolean multiSelect) {}

  // ------------------------------------------------------------------ queries

  /**
   * Reads one vocabulary table with its usage counts.
   *
   * <p>The table and column names are interpolated, which is safe only because
   * every caller is in this file and passes a literal — there is no path from a
   * request parameter to here. The alternative, six near-identical queries,
   * would drift.
   */
  private List<Value> values(String table, String facetType, String parentColumn) {
    String parent = parentColumn == null ? "NULL" : "v." + parentColumn;
    String optional = optionalColumns(table);

    String sql =
        """
        SELECT v.fqn, v.name, %s AS parent_fqn, v.display_name, v.description,
               %s
               COALESCE(f.total, 0)  AS assets,
               COALESCE(f.direct, 0) AS direct_assets,
               COALESCE(p.total, 0)  AS policies
        FROM %s v
        LEFT JOIN (
            SELECT facet_fqn,
                   count(*)                             AS total,
                   count(*) FILTER (WHERE is_direct)    AS direct
            FROM asset_facet WHERE facet_type = :facetType GROUP BY facet_fqn
        ) f ON f.facet_fqn = v.fqn
        LEFT JOIN (
            -- A policy names a value anywhere in its selector tree, so the
            -- document is searched as text rather than walked: the shape of a
            -- selector is the engine's business, and a count that quietly
            -- missed the nested branches would be worse than no count.
            SELECT value AS fqn, count(*) AS total FROM (
                SELECT DISTINCT pol.id, jsonb_path_query(pol.document, '$.**.value') #>> '{}' AS value
                FROM policy pol
                WHERE pol.lifecycle_state <> 'ARCHIVED'
            ) matched WHERE value IS NOT NULL GROUP BY value
        ) p ON p.fqn = v.fqn
        ORDER BY v.fqn
        """
            .formatted(parent, optional, table);

    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(sql)
                .bind("facetType", facetType)
                .map(
                    (rs, ctx) ->
                        new Value(
                            rs.getString("fqn"),
                            rs.getString("name"),
                            rs.getString("parent_fqn"),
                            rs.getString("display_name"),
                            rs.getString("description"),
                            has(rs, "depth") ? rs.getInt("depth") : 0,
                            has(rs, "provenance") ? rs.getString("provenance") : "openmetadata",
                            has(rs, "disabled") && rs.getBoolean("disabled"),
                            has(rs, "mutually_exclusive") && rs.getBoolean("mutually_exclusive"),
                            rs.getInt("assets"),
                            rs.getInt("direct_assets"),
                            rs.getInt("policies"),
                            List.of()))
                .list());
  }

  /** Columns only some of these tables carry, selected as literals where absent. */
  private static String optionalColumns(String table) {
    return switch (table) {
      case "classification" -> "v.provenance, v.disabled, v.mutually_exclusive, 0 AS depth,";
        // Mutual exclusivity is a property of the classification, not of the
        // tags inside it: it says "an asset may carry only one of these", which
        // is a statement about the set. Reading it off a tag would be asking
        // whether one choice is exclusive of itself.
      case "tag" -> "v.provenance, v.disabled, false AS mutually_exclusive, 0 AS depth,";
      case "domain" -> "v.provenance, false AS disabled, false AS mutually_exclusive, v.depth,";
      default -> "v.provenance, false AS disabled, false AS mutually_exclusive, 0 AS depth,";
    };
  }

  private static boolean has(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
    try {
      rs.findColumn(column);
      return true;
    } catch (java.sql.SQLException e) {
      return false;
    }
  }

  private static Value withChildren(Value value, List<Value> children) {
    return new Value(
        value.fqn(),
        value.name(),
        value.parentFqn(),
        value.displayName(),
        value.description(),
        value.depth(),
        value.provenance(),
        value.disabled(),
        value.mutuallyExclusive(),
        value.assets(),
        value.directAssets(),
        value.policies(),
        children);
  }

  private static List<Value> tree(List<Value> flat, String parentFqn) {
    List<Value> out = new ArrayList<>();
    for (Value value : flat) {
      boolean isChild =
          parentFqn == null ? value.parentFqn() == null : parentFqn.equals(value.parentFqn());
      if (isChild) {
        out.add(withChildren(value, tree(flat, value.fqn())));
      }
    }
    return out;
  }
}
