package com.mfec.dac.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;

/**
 * The read side of the metadata cache (FR-1.2, FR-1.8, FR-2A.1).
 *
 * <p>Everything the crawl writes is written to be selected on by policy; this is
 * the same data read by a person instead. The two need different shapes — a
 * selector wants one indexed lookup, a screen wants a page of assets each
 * carrying its facets — so the queries live apart from {@link AssetStore}
 * rather than growing read methods onto the writer.
 *
 * <p><b>Two round trips per page, not one per row.</b> The list is one query for
 * the assets and one each for their facets and owners, keyed by the ids that
 * came back. Joining facets into the page query would multiply rows by the
 * number of facets and make LIMIT mean something other than "this many assets".
 *
 * <p>Only current versions are read. The cache is SCD2 so an audit can ask what
 * the catalog said three months ago (FR-1.4), but a catalog screen asking that
 * question by accident would show retired tables as though they still existed.
 */
public class CatalogQuery {

  /** A page nobody asked to size. Large enough to be useful, small enough to render. */
  public static final int DEFAULT_LIMIT = 50;

  /** The ceiling on what one request may pull back, whatever it asks for. */
  public static final int MAX_LIMIT = 500;

  private static final TypeReference<Map<String, Object>> PROPERTIES =
      new TypeReference<>() {};

  private final Jdbi jdbi;
  private final ObjectMapper json;

  public CatalogQuery(Jdbi jdbi, ObjectMapper json) {
    this.jdbi = jdbi;
    this.json = json;
  }

  // ------------------------------------------------------------------ types

  /** One facet binding as the UI shows it, including where it was inherited from. */
  public record FacetRow(
      String facetType,
      String facetFqn,
      String property,
      int depth,
      boolean direct,
      String inheritedFrom,
      String provenance,
      String omState,
      String omLabelType) {}

  /** A user or team OpenMetadata names as an owner (FR-3.2a). */
  public record Owner(String type, String name, boolean direct, String inheritedFrom) {}

  /** A row in the asset list. */
  public record AssetSummary(
      UUID id,
      String fqn,
      String name,
      String displayName,
      String assetType,
      String parentFqn,
      String description,
      String tier,
      String certification,
      String dataSource,
      int columnCount,
      int taggedColumnCount,
      List<FacetRow> facets,
      List<Owner> owners) {}

  /** One page of {@link AssetSummary}, with what it took to get there. */
  public record AssetPage(List<AssetSummary> items, int total, int limit, int offset) {}

  /** A column with the facets that reach it, its own and its table's. */
  public record ColumnDetail(
      UUID id,
      String fqn,
      String name,
      Integer ordinal,
      String dataType,
      Integer dataLength,
      Boolean nullable,
      String description,
      List<FacetRow> facets) {}

  /** Everything one asset page needs in a single response. */
  public record AssetDetail(
      AssetSummary asset,
      Map<String, Object> customProperties,
      List<ColumnDetail> columns,
      List<FacetRow> facets,
      List<Owner> owners) {}

  /** A facet value and how many assets carry it, for the filter menus. */
  public record FacetValue(String facetType, String facetFqn, int assets) {}

  /** What the catalog holds in total, for the header of the list. */
  public record CatalogSummary(
      Map<String, Integer> assetsByType,
      int columns,
      int taggedAssets,
      int taggedColumns,
      int assetsWithoutOwner,
      Map<String, Integer> facetsByType) {}

  /**
   * A facet filter, parsed from {@code <type>:<fqn>}.
   *
   * <p>Ancestors are already expanded in {@code asset_facet} (FR-2A.2), so
   * filtering on {@code domains:Finance} finds the assets in
   * {@code Finance.Risk.Credit} with an equality test and no recursion — the
   * same property that keeps policy evaluation inside its budget.
   */
  public record FacetFilter(String facetType, String facetFqn) {

    public static Optional<FacetFilter> parse(String raw) {
      if (raw == null) {
        return Optional.empty();
      }
      int colon = raw.indexOf(':');
      if (colon <= 0 || colon == raw.length() - 1) {
        return Optional.empty();
      }
      return Optional.of(
          new FacetFilter(raw.substring(0, colon).trim(), raw.substring(colon + 1).trim()));
    }
  }

  // ------------------------------------------------------------------- list

  /**
   * A page of assets matching every filter given.
   *
   * <p>Filters are AND-ed. An asset matches a facet filter when the facet is on
   * the asset <em>or on any of its columns</em>: a table whose only PII is one
   * column is a table a policy author looking for PII needs to find, and
   * requiring them to know which level the tag sits at would hide it.
   */
  public AssetPage assets(
      String search,
      String assetType,
      List<FacetFilter> facets,
      String owner,
      int limit,
      int offset) {

    int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
    int from = Math.max(0, offset);

    return jdbi.withHandle(
        handle -> {
          StringBuilder where = new StringBuilder("WHERE a.is_current");
          Map<String, Object> binds = new LinkedHashMap<>();

          if (search != null && !search.isBlank()) {
            where.append(
                " AND (a.fqn ILIKE :search OR a.name ILIKE :search"
                    + " OR a.display_name ILIKE :search)");
            binds.put("search", "%" + search.trim() + "%");
          }
          if (assetType != null && !assetType.isBlank()) {
            where.append(" AND a.asset_type = :assetType");
            binds.put("assetType", assetType.trim().toUpperCase());
          }
          if (owner != null && !owner.isBlank()) {
            where.append(
                """
                 AND EXISTS (SELECT 1 FROM asset_owner o
                             WHERE o.target_fqn = a.fqn AND o.owner_name = :owner)
                """);
            binds.put("owner", owner.trim());
          }
          for (int i = 0; i < facets.size(); i++) {
            // One EXISTS per filter rather than one IN over all of them: the
            // filters are AND-ed, and a single subquery would return assets
            // carrying any one of the facets instead of all of them.
            where.append(
                """
                 AND EXISTS (SELECT 1 FROM asset_facet f
                             LEFT JOIN asset_column fc ON fc.id = f.column_id
                             WHERE (f.asset_id = a.id OR fc.asset_id = a.id)
                               AND f.facet_type = :facetType%1$d
                               AND f.facet_fqn = :facetFqn%1$d)
                """
                    .formatted(i));
            binds.put("facetType" + i, facets.get(i).facetType());
            binds.put("facetFqn" + i, facets.get(i).facetFqn());
          }

          int total =
              bind(handle.createQuery("SELECT count(*) FROM asset a " + where), binds)
                  .mapTo(Integer.class)
                  .one();

          List<AssetSummary> rows =
              bind(
                      handle.createQuery(
                          """
                          SELECT a.id, a.fqn, a.name, a.display_name, a.asset_type, a.parent_fqn,
                                 a.description, a.tier, a.certification, s.name AS data_source,
                                 (SELECT count(*) FROM asset_column c
                                  WHERE c.asset_id = a.id AND c.is_current) AS column_count,
                                 (SELECT count(DISTINCT f.column_id) FROM asset_facet f
                                  JOIN asset_column c ON c.id = f.column_id
                                  WHERE c.asset_id = a.id AND c.is_current
                                    AND f.facet_type IN ('tags', 'terms')) AS tagged_column_count
                          FROM asset a
                          LEFT JOIN data_source s ON s.id = a.data_source_id
                          """
                              + where
                              + " ORDER BY a.fqn LIMIT :limit OFFSET :offset"),
                      binds)
                  .bind("limit", capped)
                  .bind("offset", from)
                  .map(
                      (rs, ctx) ->
                          new AssetSummary(
                              rs.getObject("id", UUID.class),
                              rs.getString("fqn"),
                              rs.getString("name"),
                              rs.getString("display_name"),
                              rs.getString("asset_type"),
                              rs.getString("parent_fqn"),
                              rs.getString("description"),
                              rs.getString("tier"),
                              rs.getString("certification"),
                              rs.getString("data_source"),
                              rs.getInt("column_count"),
                              rs.getInt("tagged_column_count"),
                              new ArrayList<>(),
                              new ArrayList<>()))
                  .list();

          if (rows.isEmpty()) {
            return new AssetPage(rows, total, capped, from);
          }

          List<String> fqns = rows.stream().map(AssetSummary::fqn).toList();
          Map<String, List<FacetRow>> facetsByFqn = facetsFor(handle, fqns);
          Map<String, List<Owner>> ownersByFqn = ownersFor(handle, fqns);
          for (AssetSummary row : rows) {
            row.facets().addAll(facetsByFqn.getOrDefault(row.fqn(), List.of()));
            row.owners().addAll(ownersByFqn.getOrDefault(row.fqn(), List.of()));
          }
          return new AssetPage(rows, total, capped, from);
        });
  }

  // ----------------------------------------------------------------- detail

  /** One asset with its columns, facets and owners, or empty if it is not cached. */
  public Optional<AssetDetail> asset(String fqn) {
    return jdbi.withHandle(
        handle -> {
          Optional<AssetSummary> summary =
              handle
                  .createQuery(
                      """
                      SELECT a.id, a.fqn, a.name, a.display_name, a.asset_type, a.parent_fqn,
                             a.description, a.tier, a.certification, s.name AS data_source,
                             (SELECT count(*) FROM asset_column c
                              WHERE c.asset_id = a.id AND c.is_current) AS column_count,
                             (SELECT count(DISTINCT f.column_id) FROM asset_facet f
                              JOIN asset_column c ON c.id = f.column_id
                              WHERE c.asset_id = a.id AND c.is_current
                                AND f.facet_type IN ('tags', 'terms')) AS tagged_column_count
                      FROM asset a
                      LEFT JOIN data_source s ON s.id = a.data_source_id
                      WHERE a.fqn = :fqn AND a.is_current
                      """)
                  .bind("fqn", fqn)
                  .map(
                      (rs, ctx) ->
                          new AssetSummary(
                              rs.getObject("id", UUID.class),
                              rs.getString("fqn"),
                              rs.getString("name"),
                              rs.getString("display_name"),
                              rs.getString("asset_type"),
                              rs.getString("parent_fqn"),
                              rs.getString("description"),
                              rs.getString("tier"),
                              rs.getString("certification"),
                              rs.getString("data_source"),
                              rs.getInt("column_count"),
                              rs.getInt("tagged_column_count"),
                              new ArrayList<>(),
                              new ArrayList<>()))
                  .findOne();
          if (summary.isEmpty()) {
            return Optional.empty();
          }
          AssetSummary asset = summary.get();

          Map<String, Object> properties =
              handle
                  .createQuery("SELECT custom_properties FROM asset WHERE id = :id")
                  .bind("id", asset.id())
                  .mapTo(String.class)
                  .findOne()
                  .map(this::readProperties)
                  .orElseGet(Map::of);

          List<FacetRow> assetFacets = facetsFor(handle, List.of(fqn)).getOrDefault(fqn, List.of());
          List<Owner> owners = ownersFor(handle, List.of(fqn)).getOrDefault(fqn, List.of());
          asset.facets().addAll(assetFacets);
          asset.owners().addAll(owners);

          List<ColumnDetail> columns =
              handle
                  .createQuery(
                      """
                      SELECT id, fqn, name, ordinal, data_type, data_length, nullable, description
                      FROM asset_column
                      WHERE asset_id = :assetId AND is_current
                      ORDER BY ordinal NULLS LAST, name
                      """)
                  .bind("assetId", asset.id())
                  .map(
                      (rs, ctx) -> {
                        Integer ordinal = (Integer) rs.getObject("ordinal");
                        Integer length = (Integer) rs.getObject("data_length");
                        Boolean nullable = (Boolean) rs.getObject("nullable");
                        return new ColumnDetail(
                            rs.getObject("id", UUID.class),
                            rs.getString("fqn"),
                            rs.getString("name"),
                            ordinal,
                            rs.getString("data_type"),
                            length,
                            nullable,
                            rs.getString("description"),
                            new ArrayList<>());
                      })
                  .list();

          if (!columns.isEmpty()) {
            Map<String, List<FacetRow>> byColumn =
                facetsFor(handle, columns.stream().map(ColumnDetail::fqn).toList());
            for (ColumnDetail column : columns) {
              column.facets().addAll(byColumn.getOrDefault(column.fqn(), List.of()));
            }
          }

          return Optional.of(new AssetDetail(asset, properties, columns, assetFacets, owners));
        });
  }

  // ---------------------------------------------------------------- filters

  /**
   * The facet values in use, most-used first, for the filter menus.
   *
   * <p>Read from what assets actually carry rather than from the
   * {@code tag}/{@code domain} tables, so the menu never offers a value that
   * would return nothing.
   */
  public List<FacetValue> facetValues(String facetType, int limit) {
    int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
    return jdbi.withHandle(
        handle -> {
          StringBuilder sql =
              new StringBuilder(
                  """
                  SELECT f.facet_type, f.facet_fqn,
                         count(DISTINCT COALESCE(f.asset_id, c.asset_id)) AS assets
                  FROM asset_facet f
                  LEFT JOIN asset_column c ON c.id = f.column_id
                  """);
          if (facetType != null && !facetType.isBlank()) {
            sql.append(" WHERE f.facet_type = :facetType");
          }
          sql.append(" GROUP BY f.facet_type, f.facet_fqn ORDER BY assets DESC, f.facet_fqn");
          sql.append(" LIMIT :limit");

          Query query = handle.createQuery(sql.toString()).bind("limit", capped);
          if (facetType != null && !facetType.isBlank()) {
            query = query.bind("facetType", facetType.trim());
          }
          return query
              .map(
                  (rs, ctx) ->
                      new FacetValue(
                          rs.getString("facet_type"), rs.getString("facet_fqn"), rs.getInt("assets")))
              .list();
        });
  }

  /** Totals for the catalog header. */
  public CatalogSummary summary() {
    return jdbi.withHandle(
        handle -> {
          Map<String, Integer> byType = new LinkedHashMap<>();
          handle
              .createQuery(
                  """
                  SELECT asset_type, count(*) AS n FROM asset WHERE is_current
                  GROUP BY asset_type ORDER BY asset_type
                  """)
              .map((rs, ctx) -> Map.entry(rs.getString("asset_type"), rs.getInt("n")))
              .list()
              .forEach(e -> byType.put(e.getKey(), e.getValue()));

          int columns =
              handle
                  .createQuery("SELECT count(*) FROM asset_column WHERE is_current")
                  .mapTo(Integer.class)
                  .one();
          int taggedAssets =
              handle
                  .createQuery(
                      """
                      SELECT count(DISTINCT asset_id) FROM asset_facet
                      WHERE asset_id IS NOT NULL AND facet_type IN ('tags', 'terms')
                      """)
                  .mapTo(Integer.class)
                  .one();
          int taggedColumns =
              handle
                  .createQuery(
                      """
                      SELECT count(DISTINCT column_id) FROM asset_facet
                      WHERE column_id IS NOT NULL AND facet_type IN ('tags', 'terms')
                      """)
                  .mapTo(Integer.class)
                  .one();
          // The number a data owner is asked about first, and the one the
          // compliance report in FR-8.5 is built from: an asset nobody owns is
          // an asset no local policy has an author for.
          int unowned =
              handle
                  .createQuery(
                      """
                      SELECT count(*) FROM asset a
                      WHERE a.is_current AND a.asset_type IN ('TABLE', 'VIEW')
                        AND NOT EXISTS (SELECT 1 FROM asset_owner o WHERE o.target_fqn = a.fqn)
                      """)
                  .mapTo(Integer.class)
                  .one();

          Map<String, Integer> facetsByType = new LinkedHashMap<>();
          handle
              .createQuery(
                  """
                  SELECT facet_type, count(DISTINCT facet_fqn) AS n FROM asset_facet
                  GROUP BY facet_type ORDER BY facet_type
                  """)
              .map((rs, ctx) -> Map.entry(rs.getString("facet_type"), rs.getInt("n")))
              .list()
              .forEach(e -> facetsByType.put(e.getKey(), e.getValue()));

          return new CatalogSummary(
              byType, columns, taggedAssets, taggedColumns, unowned, facetsByType);
        });
  }

  // ----------------------------------------------------------------- shared

  private Map<String, List<FacetRow>> facetsFor(Handle handle, List<String> targets) {
    Map<String, List<FacetRow>> byTarget = new LinkedHashMap<>();
    handle
        .createQuery(
            """
            SELECT target_fqn, facet_type, facet_fqn, property, depth, is_direct, inherited_from,
                   provenance, om_state, om_label_type
            FROM asset_facet
            WHERE target_fqn IN (<targets>)
            ORDER BY facet_type, depth, facet_fqn
            """)
        .bindList("targets", targets)
        .map(
            (rs, ctx) ->
                Map.entry(
                    rs.getString("target_fqn"),
                    new FacetRow(
                        rs.getString("facet_type"),
                        rs.getString("facet_fqn"),
                        rs.getString("property"),
                        rs.getInt("depth"),
                        rs.getBoolean("is_direct"),
                        rs.getString("inherited_from"),
                        rs.getString("provenance"),
                        rs.getString("om_state"),
                        rs.getString("om_label_type"))))
        .forEach(e -> byTarget.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
    return byTarget;
  }

  private Map<String, List<Owner>> ownersFor(Handle handle, List<String> targets) {
    Map<String, List<Owner>> byTarget = new LinkedHashMap<>();
    handle
        .createQuery(
            """
            SELECT target_fqn, owner_type, owner_name, is_direct, inherited_from
            FROM asset_owner
            WHERE target_fqn IN (<targets>)
            ORDER BY owner_type, owner_name
            """)
        .bindList("targets", targets)
        .map(
            (rs, ctx) ->
                Map.entry(
                    rs.getString("target_fqn"),
                    new Owner(
                        rs.getString("owner_type"),
                        rs.getString("owner_name"),
                        rs.getBoolean("is_direct"),
                        rs.getString("inherited_from"))))
        .forEach(e -> byTarget.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
    return byTarget;
  }

  private Query bind(Query query, Map<String, Object> binds) {
    Query bound = query;
    for (Map.Entry<String, Object> bind : binds.entrySet()) {
      bound = bound.bind(bind.getKey(), bind.getValue());
    }
    return bound;
  }

  private Map<String, Object> readProperties(String raw) {
    if (raw == null || raw.isBlank()) {
      return Map.of();
    }
    try {
      return json.readValue(raw, PROPERTIES);
    } catch (JsonProcessingException e) {
      // The cache wrote this, so it parses; if it somehow does not, an asset
      // page without its custom properties beats an asset page that 500s.
      return Map.of();
    }
  }
}
