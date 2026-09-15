package com.mfec.dac.engine;

import com.mfec.dac.schema.entity.policy.FacetCondition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One column, with the facets that are effective on it.
 *
 * <p>Effective means direct bindings unioned with everything inherited from the
 * table, schema, database and service above it (FR-2A.1). The caller does that
 * union when it loads {@code asset_facet}; the engine only reads the result, so
 * that "why is this column masked" can be answered from one place.
 */
public record ColumnContext(
    String name,
    String fqn,
    String dataType,
    Map<FacetCondition.FacetType, List<FacetValue>> facets,
    Map<String, List<FacetValue>> customProperties)
    implements FacetSource {

  public ColumnContext {
    facets = facets == null ? Map.of() : Map.copyOf(facets);
    customProperties = customProperties == null ? Map.of() : Map.copyOf(customProperties);
  }

  @Override
  public List<FacetValue> facetValues(FacetCondition.FacetType type, String property) {
    return switch (type) {
      case CUSTOM_PROPERTY -> customProperties.getOrDefault(property, List.of());
      // A column name is written both ways in practice: `columnName eq 'email'`
      // at org scope, and the full FQN when one specific column is meant. Both
      // are offered so `eq` accepts either; `contains` and `startsWith` still
      // walk the FQN, where hierarchy actually exists.
      case COLUMN_NAME -> physical(name, fqn);
      case DATA_TYPE -> physical(dataType, null);
      default -> facets.getOrDefault(type, List.of());
    };
  }

  private static List<FacetValue> physical(String simple, String qualified) {
    List<FacetValue> out = new ArrayList<>(2);
    if (simple != null && !simple.isEmpty()) {
      out.add(FacetValue.of(simple));
    }
    if (qualified != null && !qualified.isEmpty() && !qualified.equals(simple)) {
      out.add(FacetValue.of(qualified));
    }
    return out;
  }

  /** Builder — a column carries enough optional state that positional construction misleads. */
  public static Builder named(String name) {
    return new Builder(name);
  }

  public static final class Builder {
    private final String name;
    private String fqn;
    private String dataType;
    private final Map<FacetCondition.FacetType, List<FacetValue>> facets = new LinkedHashMap<>();
    private final Map<String, List<FacetValue>> props = new LinkedHashMap<>();

    private Builder(String name) {
      this.name = name;
    }

    public Builder fqn(String value) {
      this.fqn = value;
      return this;
    }

    public Builder dataType(String value) {
      this.dataType = value;
      return this;
    }

    /** Adds facet values, expanding nothing: pass the ancestors you want matched. */
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

    public Builder property(String name, Object value) {
      props.computeIfAbsent(name, k -> new ArrayList<>())
          .add(FacetValue.of(value == null ? null : String.valueOf(value)));
      return this;
    }

    public ColumnContext build() {
      return new ColumnContext(name, fqn, dataType, facets, props);
    }
  }
}
