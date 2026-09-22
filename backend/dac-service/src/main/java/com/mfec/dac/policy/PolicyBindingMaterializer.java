package com.mfec.dac.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.common.ChangeNotifier;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mfec.dac.engine.AssetContext;
import com.mfec.dac.engine.ColumnContext;
import com.mfec.dac.engine.SelectorMatcher;
import com.mfec.dac.schema.entity.policy.ColumnRule;
import com.mfec.dac.schema.entity.policy.Policy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a policy's selector into the list of assets it actually binds to
 * (FR-3.1.6).
 *
 * <p>The selector is evaluated in Java, by the same {@link SelectorMatcher} the
 * engine uses at decision time, over contexts hydrated by {@link
 * AssetContextLoader}. SQL is used only to narrow the candidates by scope
 * prefix first. Translating the selector into SQL would be faster and would
 * eventually answer differently from the engine — and the day those two
 * disagree is the day a table is listed as protected and is not.
 *
 * <p>Writing is a diff, not a rebuild: rows that still match keep their
 * {@code resolved_at}, so "this policy started covering this table on Tuesday"
 * survives the nightly re-resolve.
 */
public class PolicyBindingMaterializer {

  private static final Logger LOG = LoggerFactory.getLogger(PolicyBindingMaterializer.class);

  private final Jdbi jdbi;
  private final ObjectMapper json;
  private final AssetContextLoader loader;

  private final ChangeNotifier changes = new ChangeNotifier();

  /**
   * Announces every write that could change an access decision, so that the
   * decision cache can drop what it is holding (FR-5.5).
   *
   * <p>Published from here rather than from the resource that took the request
   * because writes arrive by more roads than one -- a webhook, a poller, the
   * nightly reconcile -- and an invalidation wired to only some of them is the
   * kind of wrong that never throws.
   */
  public ChangeNotifier changes() {
    return changes;
  }

  public PolicyBindingMaterializer(Jdbi jdbi, ObjectMapper json, AssetContextLoader loader) {
    this.jdbi = jdbi;
    this.json = json;
    this.loader = loader;
  }

  /** What one run changed, for the sync report and for the UI's "re-resolve" button. */
  public record Result(UUID policyId, int scanned, int matched, int added, int removed) {
    public boolean changed() {
      return added > 0 || removed > 0;
    }
  }

  /**
   * Re-resolves one policy over everything in its scope.
   *
   * <p>The whole run is one transaction. A half-written binding set is worse
   * than a stale one: it would mean a policy that covers some of the tables it
   * is supposed to, with nothing saying which.
   */
  public Result materialize(UUID policyId) {
    Result result = jdbi.inTransaction(
        handle -> {
          Stored stored = stored(handle, policyId);
          stage(handle);

          int[] scanned = {0};
          loader.forEachInScope(
              handle,
              stored.scopeFqn,
              batch -> {
                scanned[0] += batch.size();
                collect(handle, stored, batch);
              });

          return write(handle, stored, scanned[0]);
        });
    // Only when the binding set actually moved. A nightly reconcile re-resolves
    // every policy and changes almost none of them; firing regardless would
    // empty the cache hundreds of times for nothing.
    if (result.changed()) {
      changes.fire("bindings re-resolved for policy " + policyId);
    }
    return result;
  }

  /** Re-resolves every policy that could be enforced — the nightly reconcile. */
  public List<Result> materializeAll() {
    List<UUID> ids =
        jdbi.withHandle(
            h ->
                h.createQuery(
                        """
                        SELECT id FROM policy
                        WHERE lifecycle_state IN ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'DISABLED')
                        ORDER BY scope_depth, name
                        """)
                    .mapTo(UUID.class)
                    .list());
    List<Result> results = new ArrayList<>(ids.size());
    for (UUID id : ids) {
      results.add(materialize(id));
    }
    LOG.info("Re-resolved {} policies", results.size());
    return results;
  }

  /**
   * Re-resolves only the assets named, across every policy.
   *
   * <p>This is the path a webhook takes. A tag landing on one table must not
   * cost a sweep of the estate, so only that table's bindings are touched — and
   * only for policies whose scope could reach it, which the scope prefix
   * decides without evaluating anything.
   */
  public List<Result> refresh(List<String> assetFqns) {
    if (assetFqns == null || assetFqns.isEmpty()) {
      return List.of();
    }
    List<UUID> ids =
        jdbi.withHandle(
            h ->
                h.createQuery(
                        """
                        SELECT id FROM policy
                        WHERE lifecycle_state IN ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'DISABLED')
                        """)
                    .mapTo(UUID.class)
                    .list());

    List<Result> results = new ArrayList<>();
    for (UUID id : ids) {
      results.add(
          jdbi.inTransaction(
              handle -> {
                Stored stored = stored(handle, id);
                List<String> inScope = new ArrayList<>();
                for (String fqn : assetFqns) {
                  if (inScope(stored.scopeFqn, fqn)) {
                    inScope.add(fqn);
                  }
                }
                if (inScope.isEmpty()) {
                  return new Result(id, 0, 0, 0, 0);
                }
                stage(handle);
                List<AssetContext> contexts = loader.load(handle, inScope);
                collect(handle, stored, contexts);
                // Narrowed to the assets in hand: a target that no longer
                // matches is unbound, and one that was never in this batch is
                // left exactly as it was.
                return write(handle, stored, contexts.size(), inScope);
              }));
    }
    for (Result result : results) {
      if (result.changed()) {
        changes.fire("bindings re-resolved for " + assetFqns.size() + " assets");
        break;
      }
    }
    return results;
  }

  // ------------------------------------------------------------------ staging

  /**
   * A per-transaction staging table.
   *
   * <p>The matched set at {@code ORG} scope is the size of the estate, and
   * {@code NOT IN (…100k values…)} is not a query. Staging turns both halves of
   * the diff into joins the database can plan.
   */
  private void stage(Handle handle) {
    handle.execute(
        """
        CREATE TEMPORARY TABLE IF NOT EXISTS policy_match (
            target_fqn  text PRIMARY KEY,
            target_kind text NOT NULL,
            reason      jsonb NOT NULL
        ) ON COMMIT DROP
        """);
    handle.execute("DELETE FROM policy_match");
  }

  private void collect(Handle handle, Stored stored, List<AssetContext> contexts) {
    PreparedBatch batch =
        handle.prepareBatch(
            """
            INSERT INTO policy_match (target_fqn, target_kind, reason)
            VALUES (:fqn, :kind, CAST(:reason AS jsonb))
            ON CONFLICT (target_fqn) DO NOTHING
            """);
    int rows = 0;
    for (AssetContext asset : contexts) {
      if (!SelectorMatcher.matches(stored.document.getSelector(), asset)) {
        continue;
      }
      batch.bind("fqn", asset.fqn()).bind("kind", "TABLE").bind("reason", reason(stored)).add();
      rows++;
      rows += columns(batch, stored, asset);
    }
    if (rows > 0) {
      batch.execute();
    }
  }

  /**
   * Columns a data policy's rules pick out, bound in their own right.
   *
   * <p>Without these the "policies affecting this asset" screen (FR-3.1.5)
   * could say a masking policy covers the table but not which columns it
   * reaches — which is the only part of the answer a data owner is actually
   * looking for.
   */
  private int columns(PreparedBatch batch, Stored stored, AssetContext asset) {
    if (stored.document.getData() == null || stored.document.getData().getColumnRules() == null) {
      return 0;
    }
    int rows = 0;
    List<ColumnRule> rules = stored.document.getData().getColumnRules();
    for (ColumnContext column : asset.columns()) {
      for (int i = 0; i < rules.size(); i++) {
        ColumnRule rule = rules.get(i);
        if (rule.getColumns() == null || !SelectorMatcher.matches(rule.getColumns(), column)) {
          continue;
        }
        ObjectNode reason = base(stored);
        reason.put("columnRule", i);
        reason.put("action", rule.getAction() == null ? "MASK" : rule.getAction().toString());
        batch
            .bind("fqn", column.fqn())
            .bind("kind", "COLUMN")
            .bind("reason", reason.toString())
            .add();
        rows++;
        // The first rule that claims a column is the one recorded; the engine
        // still composes every rule at decision time, and repeating the column
        // here would only make the screen list it twice.
        break;
      }
    }
    return rows;
  }

  // ------------------------------------------------------------------ writing

  private Result write(Handle handle, Stored stored, int scanned) {
    return write(handle, stored, scanned, null);
  }

  private Result write(Handle handle, Stored stored, int scanned, List<String> limitTo) {
    int matched =
        handle.createQuery("SELECT count(*) FROM policy_match").mapTo(Integer.class).one();

    int removed;
    if (limitTo == null) {
      removed =
          handle
              .createUpdate(
                  """
                  DELETE FROM policy_binding b
                  WHERE b.policy_id = :policyId
                    AND NOT EXISTS (SELECT 1 FROM policy_match m WHERE m.target_fqn = b.target_fqn)
                  """)
              .bind("policyId", stored.id)
              .execute();
    } else {
      // Only the assets in hand, and the columns beneath them, are up for
      // removal. Anything outside this batch was not evaluated, and deleting
      // what you did not evaluate is how a refresh quietly unbinds an estate.
      removed =
          handle
              .createUpdate(
                  """
                  DELETE FROM policy_binding b
                  WHERE b.policy_id = :policyId
                    AND (b.target_fqn IN (<fqns>)
                         OR EXISTS (SELECT 1 FROM asset_column c
                                    JOIN asset a ON a.id = c.asset_id
                                    WHERE c.fqn = b.target_fqn AND a.fqn IN (<fqns>)))
                    AND NOT EXISTS (SELECT 1 FROM policy_match m WHERE m.target_fqn = b.target_fqn)
                  """)
              .bindList("fqns", limitTo)
              .bind("policyId", stored.id)
              .execute();
    }

    int before =
        handle
            .createQuery("SELECT count(*) FROM policy_binding WHERE policy_id = :id")
            .bind("id", stored.id)
            .mapTo(Integer.class)
            .one();

    handle
        .createUpdate(
            """
            INSERT INTO policy_binding (policy_id, target_fqn, target_kind, asset_id, column_id,
                                        match_reason)
            SELECT :policyId, m.target_fqn, m.target_kind, a.id, c.id, m.reason
            FROM policy_match m
            LEFT JOIN asset a        ON a.fqn = m.target_fqn AND a.is_current
                                        AND m.target_kind = 'TABLE'
            LEFT JOIN asset_column c ON c.fqn = m.target_fqn AND c.is_current
                                        AND m.target_kind = 'COLUMN'
            ON CONFLICT (policy_id, target_fqn) DO UPDATE
              SET match_reason = EXCLUDED.match_reason,
                  asset_id     = EXCLUDED.asset_id,
                  column_id    = EXCLUDED.column_id
            """)
        .bind("policyId", stored.id)
        .execute();

    int after =
        handle
            .createQuery("SELECT count(*) FROM policy_binding WHERE policy_id = :id")
            .bind("id", stored.id)
            .mapTo(Integer.class)
            .one();

    Result result = new Result(stored.id, scanned, matched, after - before, removed);
    if (result.changed()) {
      LOG.info(
          "Policy {} now binds {} targets (+{} -{}) after scanning {}",
          stored.name,
          after,
          result.added(),
          result.removed(),
          scanned);
    }
    return result;
  }

  // ------------------------------------------------------------------ helpers

  private record Stored(UUID id, String name, String scopeFqn, String scopeLevel, Policy document) {}

  private Stored stored(Handle handle, UUID policyId) {
    return handle
        .createQuery("SELECT id, name, scope_fqn, scope_level, document FROM policy WHERE id = :id")
        .bind("id", policyId)
        .map(
            (rs, ctx) -> {
              try {
                return new Stored(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("name"),
                    rs.getString("scope_fqn"),
                    rs.getString("scope_level"),
                    // Same reason as PolicyStore.deserialise: the id lives in the
                    // column, and a policy without one cannot be cited later.
                    json.readValue(rs.getString("document"), Policy.class)
                        .withId(UUID.fromString(rs.getString("id"))));
              } catch (Exception e) {
                throw new IllegalStateException("Cannot read policy " + policyId, e);
              }
            })
        .findOne()
        .orElseThrow(() -> new IllegalArgumentException("No policy " + policyId));
  }

  /**
   * Whether an asset could possibly fall under this scope.
   *
   * <p>Compared segment by segment, not by string prefix: {@code Finance} is
   * not an ancestor of {@code FinanceOps}, and a policy anchored on the first
   * must not reach into the second.
   */
  private static boolean inScope(String scopeFqn, String assetFqn) {
    if (scopeFqn == null || scopeFqn.isBlank()) {
      return true;
    }
    return com.mfec.dac.common.Fqns.isDescendantOrSelf(assetFqn, scopeFqn);
  }

  private String reason(Stored stored) {
    return base(stored).toString();
  }

  private ObjectNode base(Stored stored) {
    ObjectNode node = json.createObjectNode();
    node.put("scopeLevel", stored.scopeLevel);
    if (stored.scopeFqn != null) {
      node.put("scopeFqn", stored.scopeFqn);
    }
    node.put("matchedBy", "assetSelector");
    return node;
  }
}
