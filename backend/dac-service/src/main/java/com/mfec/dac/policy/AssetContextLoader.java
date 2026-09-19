package com.mfec.dac.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.engine.AssetContext;
import com.mfec.dac.engine.ColumnContext;
import com.mfec.dac.engine.FacetValue;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the engine's read model of an asset out of the cache.
 *
 * <p>The engine deliberately knows nothing about SQL: it is given an {@link
 * AssetContext} and answers from it, which is what lets the same evaluation run
 * in a unit test, in the simulator and in the hot path of a query. Somebody has
 * to assemble that context from {@code asset_facet}, and this is the only place
 * that does — so "why did this policy match?" has one answer, not one per
 * caller.
 *
 * <p>The facet rows are read as materialised, ancestors and all. No expansion
 * happens here: doing it twice would double-count depth and make an inherited
 * domain look like a direct one.
 */
public class AssetContextLoader {

  private static final Logger LOG = LoggerFactory.getLogger(AssetContextLoader.class);

  /** How many assets are hydrated in one round trip. */
  static final int BATCH = 500;

  private final ObjectMapper json;

  public AssetContextLoader(ObjectMapper json) {
    this.json = json;
  }

  /** One asset by FQN, or empty when it is not in the cache. */
  public java.util.Optional<AssetContext> load(Handle handle, String fqn) {
    List<AssetContext> one = load(handle, List.of(fqn));
    return one.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(one.get(0));
  }

  /**
   * Hydrates the named assets in three queries rather than three per asset.
   *
   * <p>An asset missing from the cache is skipped, not faked. A selector
   * evaluated against an empty context would answer "does not match", which
   * reads as a deliberate exclusion rather than a gap in the crawl.
   */
  public List<AssetContext> load(Handle handle, List<String> fqns) {
    if (fqns == null || fqns.isEmpty()) {
      return List.of();
    }
    Map<UUID, Row> assets = assets(handle, fqns);
    if (assets.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<ColumnRow>> columns = columns(handle, assets.keySet());
    Map<String, Facets> facets = facets(handle, assets, columns);

    List<AssetContext> out = new ArrayList<>(assets.size());
    for (Row asset : assets.values()) {
      AssetContext.Builder builder = AssetContext.of(asset.fqn).physicalFromFqn();
      apply(builder, facets.get(asset.fqn));
      properties(asset.customProperties).forEach(builder::property);

      for (ColumnRow column : columns.getOrDefault(asset.id, List.of())) {
        ColumnContext.Builder col =
            ColumnContext.named(column.name).fqn(column.fqn).dataType(column.dataType);
        apply(col, facets.get(column.fqn));
        properties(column.customProperties).forEach(col::property);
        builder.column(col);
      }
      out.add(builder.build());
    }
    return out;
  }

  /**
   * Every table and view under a scope, in FQN order, handed over in batches.
   *
   * <p>Batched because a materialisation at {@code ORG} scope covers the whole
   * estate — a hundred thousand assets in the size NFR-2 asks for — and holding
   * that many hydrated contexts at once is the one thing the crawler was
   * careful not to do either.
   */
  public void forEachInScope(Handle handle, String scopeFqn, java.util.function.Consumer<List<AssetContext>> consumer) {
    List<String> page = new ArrayList<>(BATCH);
    Iterator<String> it = fqnsInScope(handle, scopeFqn).iterator();
    while (it.hasNext()) {
      page.add(it.next());
      if (page.size() == BATCH) {
        consumer.accept(load(handle, page));
        page.clear();
      }
    }
    if (!page.isEmpty()) {
      consumer.accept(load(handle, page));
    }
  }

  /**
   * The FQNs a policy at this scope could possibly bind to.
   *
   * <p>A scope anchor is a physical prefix, so this is a cheap narrowing before
   * the selector runs — not a second implementation of it. The underscore and
   * percent in a FQN are escaped, and the prefix carries its dot: without it
   * {@code prod.Sales} would drag in {@code prod.SalesArchive}, which is the
   * same trap the retirement sweep had to avoid.
   */
  public List<String> fqnsInScope(Handle handle, String scopeFqn) {
    if (scopeFqn == null || scopeFqn.isBlank()) {
      return handle
          .createQuery(
              """
              SELECT fqn FROM asset
              WHERE is_current AND asset_type IN ('TABLE', 'VIEW')
              ORDER BY fqn
              """)
          .mapTo(String.class)
          .list();
    }
    String prefix = scopeFqn.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%") + ".%";
    return handle
        .createQuery(
            """
            SELECT fqn FROM asset
            WHERE is_current AND asset_type IN ('TABLE', 'VIEW')
              AND (fqn = :exact OR fqn LIKE :prefix ESCAPE '\\')
            ORDER BY fqn
            """)
        .bind("exact", scopeFqn)
        .bind("prefix", prefix)
        .mapTo(String.class)
        .list();
  }

  // ------------------------------------------------------------------ queries

  private Map<UUID, Row> assets(Handle handle, List<String> fqns) {
    Map<UUID, Row> byId = new LinkedHashMap<>();
    handle
        .createQuery(
            """
            SELECT id, fqn, custom_properties FROM asset
            WHERE is_current AND fqn IN (<fqns>)
            ORDER BY fqn
            """)
        .bindList("fqns", fqns)
        .map(
            (rs, ctx) ->
                new Row(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("fqn"),
                    rs.getString("custom_properties")))
        .forEach(row -> byId.put(row.id, row));
    return byId;
  }

  private Map<UUID, List<ColumnRow>> columns(Handle handle, java.util.Set<UUID> assetIds) {
    Map<UUID, List<ColumnRow>> byAsset = new LinkedHashMap<>();
    handle
        .createQuery(
            """
            SELECT asset_id, fqn, name, data_type, custom_properties FROM asset_column
            WHERE is_current AND asset_id IN (<ids>)
            ORDER BY asset_id, ordinal NULLS LAST, name
            """)
        .bindList("ids", new ArrayList<>(assetIds))
        .map(
            (rs, ctx) ->
                new ColumnRow(
                    UUID.fromString(rs.getString("asset_id")),
                    rs.getString("fqn"),
                    rs.getString("name"),
                    rs.getString("data_type"),
                    rs.getString("custom_properties")))
        .forEach(row -> byAsset.computeIfAbsent(row.assetId, k -> new ArrayList<>()).add(row));
    return byAsset;
  }

  /**
   * Facets for the assets and their columns, keyed by the FQN they sit on.
   *
   * <p>Keyed by {@code target_fqn} rather than by id because a facet row
   * carries exactly one of {@code asset_id} or {@code column_id}, and the
   * target FQN is the one column that is always populated.
   */
  private Map<String, Facets> facets(
      Handle handle, Map<UUID, Row> assets, Map<UUID, List<ColumnRow>> columns) {

    List<String> targets = new ArrayList<>();
    for (Row asset : assets.values()) {
      targets.add(asset.fqn);
      for (ColumnRow column : columns.getOrDefault(asset.id, List.of())) {
        targets.add(column.fqn);
      }
    }
    Map<String, Facets> byTarget = new LinkedHashMap<>();
    handle
        .createQuery(
            """
            SELECT target_fqn, facet_type, facet_fqn, property, depth, is_direct,
                   om_state, om_label_type
            FROM asset_facet
            WHERE target_fqn IN (<targets>)
            """)
        .bindList("targets", targets)
        .map(
            (rs, ctx) ->
                new FacetRow(
                    rs.getString("target_fqn"),
                    rs.getString("facet_type"),
                    rs.getString("facet_fqn"),
                    rs.getString("property"),
                    rs.getInt("depth"),
                    rs.getBoolean("is_direct"),
                    rs.getString("om_state"),
                    rs.getString("om_label_type")))
        .forEach(
            row -> {
              FacetCondition.FacetType type = facetType(row.facetType);
              if (type == null) {
                return;
              }
              FacetValue value =
                  new FacetValue(
                      row.facetFqn,
                      row.depth,
                      row.direct,
                      "Suggested".equals(row.omState),
                      propagated(row.omLabelType));
              Facets bucket = byTarget.computeIfAbsent(row.targetFqn, k -> new Facets());
              if (type == FacetCondition.FacetType.CUSTOM_PROPERTY) {
                if (row.property != null) {
                  bucket.properties.computeIfAbsent(row.property, k -> new ArrayList<>()).add(value);
                }
              } else {
                bucket.byType.computeIfAbsent(type, k -> new ArrayList<>()).add(value);
              }
            });
    return byTarget;
  }

  // ------------------------------------------------------------------ mapping

  private void apply(AssetContext.Builder builder, Facets facets) {
    if (facets == null) {
      return;
    }
    facets.byType.forEach((type, values) -> values.forEach(v -> builder.facet(type, v)));
    facets.properties.forEach(
        (name, values) -> values.forEach(v -> builder.property(name, v.value())));
  }

  private void apply(ColumnContext.Builder builder, Facets facets) {
    if (facets == null) {
      return;
    }
    facets.byType.forEach((type, values) -> values.forEach(v -> builder.facet(type, v)));
    facets.properties.forEach(
        (name, values) -> values.forEach(v -> builder.property(name, v.value())));
  }

  /**
   * The raw {@code extension} values OpenMetadata stored on the entity.
   *
   * <p>These are read straight off the asset rather than from {@code
   * asset_facet}: the facet table holds what the crawl chose to index, and a
   * property added to a type after the last crawl is still a legitimate ABAC
   * input the moment its value lands on the asset.
   */
  private Map<String, Object> properties(String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      return Map.of();
    }
    try {
      JsonNode node = json.readTree(rawJson);
      if (!node.isObject()) {
        return Map.of();
      }
      Map<String, Object> out = new LinkedHashMap<>();
      node.fields()
          .forEachRemaining(
              entry -> {
                JsonNode value = entry.getValue();
                if (value.isArray()) {
                  // Multi-value properties compare element by element; flattening
                  // to the array's toString would make `in` test one long string.
                  value.forEach(item -> out.put(entry.getKey(), scalar(item)));
                } else if (!value.isNull()) {
                  out.put(entry.getKey(), scalar(value));
                }
              });
      return out;
    } catch (Exception e) {
      LOG.warn("Ignoring unreadable custom properties: {}", e.getMessage());
      return Map.of();
    }
  }

  private static Object scalar(JsonNode node) {
    return node.isValueNode() ? node.asText() : node.toString();
  }

  private static boolean propagated(String labelType) {
    return "Propagated".equals(labelType) || "Derived".equals(labelType);
  }

  /** Unknown facet types are skipped: a newer crawl must not break an older engine. */
  private static FacetCondition.FacetType facetType(String value) {
    for (FacetCondition.FacetType type : FacetCondition.FacetType.values()) {
      if (type.value().equals(value)) {
        return type;
      }
    }
    return null;
  }

  private record Row(UUID id, String fqn, String customProperties) {}

  private record FacetRow(
      String targetFqn,
      String facetType,
      String facetFqn,
      String property,
      int depth,
      boolean direct,
      String omState,
      String omLabelType) {}

  private record ColumnRow(
      UUID assetId, String fqn, String name, String dataType, String customProperties) {}

  private static final class Facets {
    private final Map<FacetCondition.FacetType, List<FacetValue>> byType = new LinkedHashMap<>();
    private final Map<String, List<FacetValue>> properties = new LinkedHashMap<>();
  }
}
