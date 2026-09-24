package com.mfec.dac.enforcement;

import com.mfec.dac.compiler.sql.RowEntitlementMaintainer;
import java.util.List;
import java.util.Objects;
import org.jdbi.v3.core.Jdbi;

/**
 * Answers "which values of this entitlement key does this person hold" from the
 * platform's own {@code row_entitlement} table.
 *
 * <p>{@code ENTITLEMENT_JOIN} is the one row-predicate kind whose values do not
 * travel inside the decision. A branch-to-analyst mapping is too large and
 * changes too often to inline into every {@code PolicyDecision}, so the
 * decision carries only the key and something has to look the values up. In
 * the proxy this is a join the rewritten query performs itself; for a secure
 * view the values have to be copied to the source ahead of time, and this class
 * is what reads them.
 *
 * <h2>Two tables, one name</h2>
 *
 * <p>There are two {@code row_entitlement} tables in this system and they are
 * not the same table. This one lives in the platform's own database and is
 * where entitlements are <em>authored</em>. The other lives in the customer's
 * database under the {@code acl} schema and is where they are <em>enforced</em>;
 * {@link com.mfec.dac.source.jdbc.SecureViewApplier} writes that one. Reading
 * from the wrong one would make the applier compare a table against itself and
 * conclude there was never anything to do.
 *
 * <h2>Expiry is applied here, not later</h2>
 *
 * <p>Rows past their {@code valid_until} are filtered out in SQL. The reason to
 * do it at this edge rather than anywhere downstream is that everything
 * downstream copies these values into a customer's database, where they sit
 * until something replaces them. An expired entitlement that leaks through this
 * method does not expire again on the other side — it becomes a standing grant
 * that outlives the decision that created it, and nothing in the source knows
 * it was ever supposed to end.
 */
public final class AppDbEntitlementSource implements RowEntitlementMaintainer.EntitlementSource {

  private final Jdbi jdbi;

  public AppDbEntitlementSource(Jdbi jdbi) {
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
  }

  /**
   * The values held by one principal for one key on one asset, sorted.
   *
   * <p>The sort is not cosmetic. The maintainer diffs these values against what
   * is installed at the source, and an unordered read would make the diff
   * depend on the order Postgres happened to return rows in — which would show
   * up as a dry run that looks different every time it is opened, and,
   * downstream, as a stale-review refusal on a change nobody actually made.
   *
   * <p>An unknown principal, asset or key reads as no values, which the
   * maintainer turns into "this person sees no rows of this asset". That is the
   * direction to fail in: the alternative reading, that an absent mapping means
   * no restriction, would hand over the whole table.
   */
  @Override
  public List<String> valuesFor(String principal, String assetKey, String entitlementKey) {
    if (principal == null || assetKey == null || entitlementKey == null) {
      return List.of();
    }
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT DISTINCT entitlement_value
                    FROM row_entitlement
                    WHERE principal_name = :principal
                      AND target_fqn = :asset
                      AND entitlement_key = :key
                      AND (valid_until IS NULL OR valid_until > now())
                    ORDER BY entitlement_value
                    """)
                .bind("principal", principal)
                .bind("asset", assetKey)
                .bind("key", entitlementKey)
                .mapTo(String.class)
                .list());
  }
}
