package com.mfec.dac.engine;

import com.mfec.dac.schema.entity.policy.FacetCondition;
import java.util.List;

/**
 * Anything a facet condition can be evaluated against: a table, or a column.
 *
 * <p>Column rules reuse {@code assetSelector} to pick columns, so the same
 * matcher has to run over both. Giving them one interface is what keeps
 * {@code tags contains 'PII'} meaning exactly the same thing whether it selects
 * the tables a policy binds to or the columns inside one.
 */
public interface FacetSource {

  /** The fully-qualified name of this asset or column. */
  String fqn();

  /**
   * Every value of the given facet, already expanded to include ancestors.
   *
   * @param property the custom-property name; ignored for every other facet
   * @return an empty list when the facet is not present — never null
   */
  List<FacetValue> facetValues(FacetCondition.FacetType type, String property);
}
