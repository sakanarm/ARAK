package com.mfec.dac.proxy;

import com.mfec.dac.common.engine.SourceEngine;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Refuses a query whose decision the proxy cannot express in the engine's own
 * dialect.
 *
 * <p>This is the fail-closed rule of mode 5.2 (FR-6.3), and it is the reason
 * adding an engine is safe. Until now the proxy assumed every source could be
 * given every treatment: {@code dialectFor()} handed back a Postgres dialect
 * for anything it did not recognise, so an engine that could not express a mask
 * would have produced a statement that ran and returned the column in the
 * clear. A leaked column is a silent failure — nobody files a ticket about
 * data they were not supposed to see — which is why the decision is checked
 * against what the engine declares before a single row is fetched, and why the
 * answer to "we are not sure" is no rows rather than all of them.
 *
 * <h2>Why the engine declares this and not the {@code engine_capability} table</h2>
 *
 * <p>That table answers a different question: what the <em>engine</em> can
 * enforce on its own, natively, which is what modes 5.1.1 and 5.1.2 need to
 * know. In proxy mode nothing is enforced by the engine at all — ARAK rewrites
 * the statement — so the question is what <em>our rewriter</em> can say in that
 * dialect. The two answers diverge in both directions: Postgres has no native
 * column masking whatsoever and the proxy masks its columns perfectly well,
 * while an engine could support native row security and still have no CASE
 * expression for us to emit. Reading the capability table here would refuse
 * queries that work and permit queries that leak.
 */
public final class ProxyCapabilities {

  private ProxyCapabilities() {}

  /**
   * The capabilities this decision needs before it can be rewritten.
   *
   * <p>A decision that denies access needs none: the rewriter never reaches the
   * source, so there is nothing to express.
   */
  public static Set<String> required(PolicyDecision decision) {
    Set<String> needed = new LinkedHashSet<>();
    if (decision == null || !Boolean.TRUE.equals(decision.getAllowed())) {
      return needed;
    }

    if (decision.getRowPredicates() != null && !decision.getRowPredicates().isEmpty()) {
      needed.add(SourceEngine.Capability.ROW_FILTER);
    }
    if (decision.getHiddenColumns() != null && !decision.getHiddenColumns().isEmpty()) {
      needed.add(SourceEngine.Capability.COLUMN_HIDE);
    }
    if (decision.getColumnMasks() != null) {
      for (ResolvedColumnMask mask : decision.getColumnMasks()) {
        // The condition is what turns a column mask into a cell mask — the
        // same value shown on one row and hidden on the next — and it is a
        // strictly harder thing to emit, so it is counted separately rather
        // than folded in with the column masks.
        needed.add(
            mask.getCondition() == null || mask.getCondition().isBlank()
                ? SourceEngine.Capability.COLUMN_MASK
                : SourceEngine.Capability.CELL_MASK);
      }
    }
    return needed;
  }

  /** What this decision needs and the engine does not declare, in a stable order. */
  public static List<String> missing(SourceEngine engine, PolicyDecision decision) {
    Set<String> declared = engine.proxyCapabilities();
    List<String> missing = new ArrayList<>();
    for (String capability : required(decision)) {
      if (declared == null || !declared.contains(capability)) {
        missing.add(capability);
      }
    }
    return missing;
  }

  /**
   * Refuses the query unless the engine can express everything the decision
   * demands.
   *
   * <p>The message names the asset and the treatment, because the only useful
   * version of this refusal is one a data owner can act on: "ARAK cannot mask a
   * column on this engine" tells them to move the asset to a secure view, while
   * "refused" tells them to file a ticket.
   *
   * @throws QueryRewriter.RefusedException when a treatment cannot be expressed
   */
  public static void require(SourceEngine engine, String assetFqn, PolicyDecision decision) {
    List<String> missing = missing(engine, decision);
    if (missing.isEmpty()) {
      return;
    }
    throw new QueryRewriter.RefusedException(
        "Policy on "
            + assetFqn
            + " needs "
            + describe(missing)
            + ", which the query proxy cannot express on "
            + engine.displayName()
            + ". Enforce this asset through a secure view instead.");
  }

  private static String describe(List<String> capabilities) {
    StringBuilder out = new StringBuilder();
    for (String capability : capabilities) {
      if (out.length() > 0) {
        out.append(" and ");
      }
      out.append(
          switch (capability) {
            case SourceEngine.Capability.ROW_FILTER -> "a row filter";
            case SourceEngine.Capability.COLUMN_MASK -> "a column mask";
            case SourceEngine.Capability.CELL_MASK -> "a cell mask";
            case SourceEngine.Capability.COLUMN_HIDE -> "a hidden column";
            default -> capability;
          });
    }
    return out.toString();
  }
}
