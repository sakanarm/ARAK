package com.mfec.dac.engine;

import com.mfec.dac.schema.api.ResolvedRowPredicate.FacetOperator;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates one facet condition against one asset or column.
 *
 * <p>The difference between contains and eq is the reason this class exists
 * rather than a string comparison. "domains contains Finance" is meant to cover
 * Finance.Risk.Credit and every sub-domain someone adds next year, which is what
 * lets a policy survive a reorganisation without being rewritten. "domains eq
 * Finance" is meant to cover only the top level. Both are legitimate, and
 * confusing one for the other either leaks a department's data or locks it out.
 */
public final class FacetMatcher {

  private FacetMatcher() {}

  public static boolean matches(FacetCondition condition, FacetSource source) {
    if (condition == null || condition.getFacet() == null || condition.getOperator() == null) {
      // The schema requires both. A malformed condition selects nothing rather
      // than everything.
      return false;
    }
    List<String> values = eligible(condition, source, condition.getOperator());
    return Operators.evaluate(
        condition.getOperator(), values, condition.getValue(), condition.getValues());
  }

  /**
   * The values this condition is allowed to see.
   *
   * <p>By default only confirmed labels count. A tag OpenMetadata merely
   * suggested is a classifier's guess that no steward has approved, and letting
   * it lock data on its own would put the model, not the data owner, in charge
   * of who sees what (FR-1.3a). Propagated and derived labels are excluded for a
   * different reason: this engine computes inheritance itself, so honouring
   * theirs as well would count the same tag twice and make the explanation wrong.
   *
   * <p>Ancestor rows are filtered out for every operator but {@code contains}.
   * They exist so that a hierarchical lookup can be served from an index on
   * {@code asset_facet} rather than by a recursive query (FR-2A.2) — they are a
   * storage device, not additional bindings. An asset in
   * {@code Finance.Risk.Credit} carries a {@code Finance} row, and letting
   * {@code eq} see it would make {@code domains eq 'Finance'} match an asset
   * three levels down, which is the exact distinction between {@code eq} and
   * {@code contains} that the requirement asks for. {@code contains} keeps them
   * so the in-memory path and the SQL path read the same rows.
   */
  private static List<String> eligible(
      FacetCondition condition, FacetSource source, FacetOperator operator) {
    List<FacetValue> all = source.facetValues(condition.getFacet(), condition.getProperty());
    boolean suggested = Boolean.TRUE.equals(condition.getIncludeSuggested());
    boolean propagated = Boolean.TRUE.equals(condition.getIncludePropagated());
    boolean ancestors = operator == FacetOperator.CONTAINS;
    List<String> out = new ArrayList<>(all.size());
    for (FacetValue v : all) {
      if ((v.suggested() && !suggested) || (v.propagated() && !propagated)) {
        continue;
      }
      if (!v.direct() && !ancestors) {
        continue;
      }
      out.add(v.value());
    }
    return out;
  }
}
