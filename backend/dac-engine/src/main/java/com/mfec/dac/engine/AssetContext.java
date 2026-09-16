package com.mfec.dac.engine;

import com.mfec.dac.common.Fqns;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The table being evaluated, with its effective facets and its columns.
 *
 * <p>This is a read model assembled from {@code asset_facet}, not a live call to
 * OpenMetadata. Policy evaluation sits in the hot path of every query and has to
 * keep answering when the catalog is down (NFR-3), and an audit has to be able
 * to ask what the tags were on the day a decision was made rather than what they
 * are today.
 *
 * <p>The physical facets are held as FQNs — {@code service}, then
 * {@code service.database}, then {@code service.database.schema} — so that
 * {@code contains} and {@code startsWith} walk a real hierarchy. Because
 * policies are also written as {@code schema eq 'dbo'}, each physical facet
 * additionally reports its leaf segment, and {@code eq} therefore accepts either
 * spelling. {@code contains} and {@code startsWith} deliberately do not: a
 * prefix of a bare leaf name has no meaning.
 */
public record AssetContext(
    String fqn,
    String service,
    String database,
    String schema,
    String table,
    Map<FacetCondition.FacetType, List<FacetValue>> facets,
    Map<String, List<FacetValue>> customProperties,
    List<ColumnContext> columns)
    implements FacetSource {

  public AssetContext {
    facets = facets == null ? Map.of() : Map.copyOf(facets);
    customProperties = customProperties == null ? Map.of() : Map.copyOf(customProperties);
    columns = columns == null ? List.of() : List.copyOf(columns);
  }

  @Override
  public List<FacetValue> facetValues(FacetCondition.FacetType type, String property) {
    return switch (type) {
      case CUSTOM_PROPERTY -> customProperties.getOrDefault(property, List.of());
      case SERVICE -> physical(service);
      case DATABASE -> physical(database);
      case SCHEMA -> physical(schema);
      case TABLE -> physical(table != null ? table : fqn);
      // A table has no column name or data type of its own. Returning nothing
      // rather than throwing means `columnName eq 'email'` written at table
      // scope simply fails to match, instead of taking the evaluation down.
      case COLUMN_NAME, DATA_TYPE -> List.of();
      default -> facets.getOrDefault(type, List.of());
    };
  }

  /** Reports both the FQN and its leaf, so {@code eq} accepts either spelling. */
  private static List<FacetValue> physical(String qualified) {
    if (qualified == null || qualified.isEmpty()) {
      return List.of();
    }
    List<FacetValue> out = new ArrayList<>(2);
    out.add(FacetValue.of(qualified));
    String leaf = Fqns.leaf(qualified);
    if (leaf != null && !leaf.equals(qualified)) {
      out.add(FacetValue.of(leaf));
    }
    return out;
  }

  public static Builder of(String fqn) {
    return new Builder(fqn);
  }

  public static final class Builder {
    private final String fqn;
    private String service;
    private String database;
    private String schema;
    private String table;
    private final Map<FacetCondition.FacetType, List<FacetValue>> facets = new LinkedHashMap<>();
    private final Map<String, List<FacetValue>> props = new LinkedHashMap<>();
    private final List<ColumnContext> columns = new ArrayList<>();

    private Builder(String fqn) {
      this.fqn = fqn;
    }

    /**
     * Derives service / database / schema / table from a four-part table FQN.
     * Anything shorter is left partly unset rather than guessed at.
     */
    public Builder physicalFromFqn() {
      List<String> segs = Fqns.segments(fqn);
      if (segs.size() >= 1) {
        service = Fqns.join(segs.get(0));
      }
      if (segs.size() >= 2) {
        database = Fqns.join(segs.get(0), segs.get(1));
      }
      if (segs.size() >= 3) {
        schema = Fqns.join(segs.get(0), segs.get(1), segs.get(2));
      }
      if (segs.size() >= 4) {
        table = fqn;
      }
      return this;
    }

    public Builder service(String value) {
      this.service = value;
      return this;
    }

    public Builder database(String value) {
      this.database = value;
      return this;
    }

    public Builder schema(String value) {
      this.schema = value;
      return this;
    }

    public Builder table(String value) {
      this.table = value;
      return this;
    }

    public Builder facet(FacetCondition.FacetType type, String... values) {
      List<FacetValue> list = facets.computeIfAbsent(type, k -> new ArrayList<>());
      for (String v : values) {
        list.add(FacetValue.of(v));
      }
      return this;
    }

    public Builder facet(FacetCondition.FacetType type, FacetValue value) {
      facets.computeIfAbsent(type, k -> new ArrayList<>()).add(value);
      return this;
    }

    /**
     * Adds a hierarchical facet together with every ancestor of it, the way the
     * materialiser does. {@code domain("Finance.Risk.Credit")} therefore also
     * matches a policy written against {@code Finance}.
     */
    public Builder hierarchicalFacet(FacetCondition.FacetType type, String value) {
      List<String> segs = Fqns.segments(value);
      List<FacetValue> list = facets.computeIfAbsent(type, k -> new ArrayList<>());
      for (int i = 1; i <= segs.size(); i++) {
        String ancestor = Fqns.join(segs.subList(0, i).toArray(new String[0]));
        int distance = segs.size() - i;
        list.add(
            distance == 0 ? FacetValue.of(ancestor) : FacetValue.ancestor(ancestor, distance));
      }
      return this;
    }

    public Builder property(String name, Object value) {
      props.computeIfAbsent(name, k -> new ArrayList<>())
          .add(FacetValue.of(value == null ? null : String.valueOf(value)));
      return this;
    }

    public Builder column(ColumnContext column) {
      columns.add(column);
      return this;
    }

    public Builder column(ColumnContext.Builder column) {
      return column(column.build());
    }

    public AssetContext build() {
      return new AssetContext(fqn, service, database, schema, table, facets, props, columns);
    }
  }
}
