package com.mfec.dac.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.common.Fqns;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.entity.policy.Policy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores policies, their history and their lifecycle state (FR-9.1, FR-9.2).
 *
 * <p>The whole policy lives in one {@code jsonb} document, and the columns
 * beside it are projections of fields inside it. That is on purpose: the
 * document is the schema-generated {@link Policy}, shared byte for byte with
 * the Policy Builder, and duplicating its fields into columns would create two
 * definitions that drift. The columns exist only so the engine can find the
 * policies that apply to an asset without deserialising the estate.
 *
 * <p>Every write appends to {@code policy_version} before it changes {@code
 * policy}. An access decision made last quarter has to be explainable against
 * the policy as it read last quarter, not as it reads now (FR-8.1).
 */
public class PolicyStore {

  private static final Logger LOG = LoggerFactory.getLogger(PolicyStore.class);

  private final Jdbi jdbi;
  private final ObjectMapper json;

  public PolicyStore(Jdbi jdbi, ObjectMapper json) {
    this.jdbi = jdbi;
    this.json = json;
  }

  /** A stored policy: the document, plus what the database knows about it. */
  public record StoredPolicy(
      UUID id,
      Policy document,
      String lifecycleState,
      String environment,
      int version,
      String createdBy,
      String updatedBy,
      java.time.Instant updatedAt) {}

  /** Thrown when a write is based on a version somebody else has already moved past. */
  public static class StaleVersionException extends RuntimeException {
    public StaleVersionException(String message) {
      super(message);
    }
  }

  /** Thrown when a lifecycle transition is not one the state machine allows (FR-9.1). */
  public static class IllegalTransitionException extends RuntimeException {
    public IllegalTransitionException(String message) {
      super(message);
    }
  }

  /**
   * Creates a policy in {@code DRAFT}.
   *
   * <p>Always draft, whatever the document says. A policy that could be created
   * straight into {@code ACTIVE} would let an author skip the simulation step
   * that FR-5.2 exists to make unskippable.
   */
  public StoredPolicy create(Policy document, String author) {
    validate(document);
    return jdbi.inTransaction(
        handle -> {
          UUID id =
              handle
                  .createQuery(
                      """
                      INSERT INTO policy (name, display_name, description, policy_type,
                                          scope_level, scope_fqn, scope_depth, effect,
                                          allow_local_override, lifecycle_state, environment,
                                          document, version, valid_from, valid_until,
                                          created_by, updated_by)
                      VALUES (:name, :displayName, :description, :policyType,
                              :scopeLevel, :scopeFqn, :scopeDepth, :effect,
                              :allowLocalOverride, 'DRAFT', :environment,
                              CAST(:document AS jsonb), 1, :validFrom, :validUntil,
                              :author, :author)
                      RETURNING id
                      """)
                  .bind("name", document.getName())
                  .bind("displayName", document.getDisplayName())
                  .bind("description", document.getDescription())
                  .bind("policyType", value(document.getPolicyType()))
                  .bind("scopeLevel", value(document.getScopeLevel()))
                  .bind("scopeFqn", document.getScopeFqn())
                  .bind("scopeDepth", depthOf(document.getScopeFqn()))
                  .bind("effect", document.getEffect() == null ? "ALLOW" : value(document.getEffect()))
                  .bind(
                      "allowLocalOverride",
                      document.getAllowLocalOverride() != null && document.getAllowLocalOverride())
                  .bind("environment", document.getEnvironment() == null ? "dev" : value(document.getEnvironment()))
                  .bind("document", serialise(document))
                  .bind("validFrom", instant(document.getValidFrom()))
                  .bind("validUntil", instant(document.getValidUntil()))
                  .bind("author", author)
                  .mapTo(UUID.class)
                  .one();

          recordVersion(handle, id, 1, serialise(document), "DRAFT", author, "created");
          LOG.info("Policy {} created as DRAFT by {}", document.getName(), author);
          return read(handle, id).orElseThrow();
        });
  }

  /**
   * Replaces the document, bumping the version.
   *
   * @param expectedVersion the version the editor was looking at; a mismatch is
   *     rejected rather than merged, because two people editing the same policy
   *     from different screens is exactly the case where last-write-wins loses
   *     an access restriction somebody meant to add
   */
  public StoredPolicy update(UUID id, Policy document, int expectedVersion, String author, String reason) {
    validate(document);
    return jdbi.inTransaction(
        handle -> {
          StoredPolicy current =
              read(handle, id).orElseThrow(() -> new IllegalArgumentException("No policy " + id));
          // Checked before the version, because an archived policy is refused
          // whatever version the editor was holding, and "reload and try again"
          // would be the wrong thing to tell them.
          if ("ARCHIVED".equals(current.lifecycleState())) {
            throw new IllegalTransitionException("An archived policy cannot be edited");
          }
          if (current.version() != expectedVersion) {
            throw new StaleVersionException(
                "Policy " + id + " is at version " + current.version() + ", not " + expectedVersion);
          }
          int next = current.version() + 1;
          String body = serialise(document);
          handle
              .createUpdate(
                  """
                  UPDATE policy SET
                      name = :name, display_name = :displayName, description = :description,
                      policy_type = :policyType, scope_level = :scopeLevel, scope_fqn = :scopeFqn,
                      scope_depth = :scopeDepth, effect = :effect,
                      allow_local_override = :allowLocalOverride,
                      document = CAST(:document AS jsonb), version = :version,
                      valid_from = :validFrom, valid_until = :validUntil,
                      updated_by = :author, updated_at = now()
                  WHERE id = :id
                  """)
              .bind("id", id)
              .bind("name", document.getName())
              .bind("displayName", document.getDisplayName())
              .bind("description", document.getDescription())
              .bind("policyType", value(document.getPolicyType()))
              .bind("scopeLevel", value(document.getScopeLevel()))
              .bind("scopeFqn", document.getScopeFqn())
              .bind("scopeDepth", depthOf(document.getScopeFqn()))
              .bind("effect", document.getEffect() == null ? "ALLOW" : value(document.getEffect()))
              .bind(
                  "allowLocalOverride",
                  document.getAllowLocalOverride() != null && document.getAllowLocalOverride())
              .bind("document", body)
              .bind("version", next)
              .bind("validFrom", instant(document.getValidFrom()))
              .bind("validUntil", instant(document.getValidUntil()))
              .bind("author", author)
              .execute();

          recordVersion(handle, id, next, body, current.lifecycleState(), author, reason);
          return read(handle, id).orElseThrow();
        });
  }

  /**
   * Moves a policy along its lifecycle.
   *
   * <p>The transitions are listed rather than left open because two of them
   * change who can see data: {@code ACTIVE} is the point a policy starts being
   * enforced, and {@code DISABLED} the point it stops. Both are recorded as
   * versions so the audit can say when enforcement began.
   */
  public StoredPolicy transition(UUID id, String to, String author, String reason) {
    return jdbi.inTransaction(
        handle -> {
          StoredPolicy current =
              read(handle, id).orElseThrow(() -> new IllegalArgumentException("No policy " + id));
          if (!allowed(current.lifecycleState(), to)) {
            throw new IllegalTransitionException(
                "Cannot move a policy from " + current.lifecycleState() + " to " + to);
          }
          // A state change is a revision of the record, not a footnote to one:
          // it bumps the version like any other write. That is what keeps the
          // history a single ordered log, and it means an editor who was
          // holding version 3 while somebody activated the policy has to look
          // again before saving -- which is the right outcome, because what
          // they were editing is now live.
          int next = current.version() + 1;
          handle
              .createUpdate(
                  """
                  UPDATE policy SET lifecycle_state = :to, version = :version,
                                    updated_by = :author, updated_at = now()
                  WHERE id = :id
                  """)
              .bind("id", id)
              .bind("to", to)
              .bind("version", next)
              .bind("author", author)
              .execute();
          recordVersion(
              handle,
              id,
              next,
              serialise(current.document()),
              to,
              author,
              reason == null ? current.lifecycleState() + " -> " + to : reason);
          LOG.info("Policy {} moved {} -> {} by {}", id, current.lifecycleState(), to, author);
          return read(handle, id).orElseThrow();
        });
  }

  public Optional<StoredPolicy> find(UUID id) {
    return jdbi.withHandle(handle -> read(handle, id));
  }

  /** Policies, newest first, optionally narrowed the way the list screen narrows them. */
  public List<StoredPolicy> list(String lifecycleState, String policyType, String scopeLevel, int limit, int offset) {
    StringBuilder sql = new StringBuilder("SELECT * FROM policy WHERE 1 = 1");
    if (lifecycleState != null) {
      sql.append(" AND lifecycle_state = :lifecycleState");
    }
    if (policyType != null) {
      sql.append(" AND policy_type = :policyType");
    }
    if (scopeLevel != null) {
      sql.append(" AND scope_level = :scopeLevel");
    }
    sql.append(" ORDER BY updated_at DESC LIMIT :limit OFFSET :offset");

    return jdbi.withHandle(
        handle -> {
          var query = handle.createQuery(sql.toString()).bind("limit", limit).bind("offset", offset);
          if (lifecycleState != null) {
            query.bind("lifecycleState", lifecycleState);
          }
          if (policyType != null) {
            query.bind("policyType", policyType);
          }
          if (scopeLevel != null) {
            query.bind("scopeLevel", scopeLevel);
          }
          return query.map(this::map).list();
        });
  }

  /**
   * The active policies that could apply to an asset, outermost layer first.
   *
   * <p>Ordered {@code ORG → DOMAIN → SERVICE → … → COLUMN} and, within DOMAIN,
   * by sub-domain depth, because that is the order FR-3.1.3 composes them in:
   * the composer needs to know which layer a restriction came from to answer
   * FR-5.4, and sorting here keeps that knowledge out of the engine.
   */
  public List<StoredPolicy> activeFor(String assetFqn, String environment) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT p.* FROM policy p
                    WHERE p.lifecycle_state = 'ACTIVE'
                      AND p.environment = :environment
                      AND (p.valid_from IS NULL OR p.valid_from <= now())
                      AND (p.valid_until IS NULL OR p.valid_until > now())
                      AND EXISTS (SELECT 1 FROM policy_binding b
                                  WHERE b.policy_id = p.id AND b.target_fqn = :fqn)
                    ORDER BY CASE p.scope_level
                               WHEN 'ORG' THEN 0 WHEN 'DOMAIN' THEN 1 WHEN 'SERVICE' THEN 2
                               WHEN 'DATABASE' THEN 3 WHEN 'SCHEMA' THEN 4 WHEN 'TABLE' THEN 5
                               ELSE 6 END,
                             p.scope_depth, p.name
                    """)
                .bind("fqn", assetFqn)
                .bind("environment", environment)
                .map(this::map)
                .list());
  }

  /**
   * The same list, with one named policy forced in whatever state it is in.
   *
   * <p>This is what makes impact analysis (FR-5.3) an honest comparison rather
   * than a second implementation of it. A draft is not active, so
   * {@link #activeFor} cannot see it; the alternative to this method is for the
   * caller to fetch the draft separately and splice it into the list, which
   * means copying the {@code ORDER BY} below into Java and hoping the two
   * agree about where a {@code SCHEMA} policy sits relative to a {@code TABLE}
   * one. They would not agree for long. Here the database still decides the
   * layering, and the caller gets the composed order it would really see.
   *
   * <p>The candidate bypasses the lifecycle and validity filters but not the
   * binding check: a policy that resolves onto nothing changes nothing, and
   * pretending otherwise would invent an impact that activating it would not
   * produce.
   */
  public List<StoredPolicy> activeForIncluding(String assetFqn, String environment, UUID candidate) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT p.* FROM policy p
                    WHERE (p.id = :candidate
                           OR (p.lifecycle_state = 'ACTIVE'
                               AND (p.valid_from IS NULL OR p.valid_from <= now())
                               AND (p.valid_until IS NULL OR p.valid_until > now())))
                      AND p.environment = :environment
                      AND EXISTS (SELECT 1 FROM policy_binding b
                                  WHERE b.policy_id = p.id AND b.target_fqn = :fqn)
                    ORDER BY CASE p.scope_level
                               WHEN 'ORG' THEN 0 WHEN 'DOMAIN' THEN 1 WHEN 'SERVICE' THEN 2
                               WHEN 'DATABASE' THEN 3 WHEN 'SCHEMA' THEN 4 WHEN 'TABLE' THEN 5
                               ELSE 6 END,
                             p.scope_depth, p.name
                    """)
                .bind("fqn", assetFqn)
                .bind("environment", environment)
                .bind("candidate", candidate)
                .map(this::map)
                .list());
  }

  /** The history of one policy, newest first (FR-9.2). */
  public List<StoredPolicy> history(UUID id) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT id, policy_id, version, document, lifecycle_state, changed_by,
                           change_reason, changed_at
                    FROM policy_version WHERE policy_id = :id
                    ORDER BY changed_at DESC, version DESC
                    """)
                .bind("id", id)
                .map(
                    (rs, ctx) ->
                        new StoredPolicy(
                            UUID.fromString(rs.getString("policy_id")),
                            deserialise(rs.getString("policy_id"), rs.getString("document")),
                            rs.getString("lifecycle_state"),
                            null,
                            rs.getInt("version"),
                            null,
                            rs.getString("changed_by"),
                            rs.getTimestamp("changed_at").toInstant()))
                .list());
  }

  /** Archives rather than deletes: a policy that once decided an access is evidence. */
  public void archive(UUID id, String author, String reason) {
    transition(id, "ARCHIVED", author, reason);
  }

  // ------------------------------------------------------------------ helpers

  private Optional<StoredPolicy> read(Handle handle, UUID id) {
    return handle
        .createQuery("SELECT * FROM policy WHERE id = :id")
        .bind("id", id)
        .map(this::map)
        .findOne();
  }

  private StoredPolicy map(java.sql.ResultSet rs, org.jdbi.v3.core.statement.StatementContext ctx)
      throws java.sql.SQLException {
    return new StoredPolicy(
        UUID.fromString(rs.getString("id")),
        deserialise(rs.getString("id"), rs.getString("document")),
        rs.getString("lifecycle_state"),
        rs.getString("environment"),
        rs.getInt("version"),
        rs.getString("created_by"),
        rs.getString("updated_by"),
        rs.getTimestamp("updated_at").toInstant());
  }

  private void recordVersion(
      Handle handle, UUID id, int version, String document, String state, String author, String reason) {
    handle
        .createUpdate(
            """
            INSERT INTO policy_version (policy_id, version, document, lifecycle_state,
                                        changed_by, change_reason)
            VALUES (:id, :version, CAST(:document AS jsonb), :state, :author, :reason)
            """)
        .bind("id", id)
        .bind("version", version)
        .bind("document", document)
        .bind("state", state)
        .bind("author", author)
        .bind("reason", reason)
        .execute();
  }

  /**
   * The transitions FR-9.1 allows.
   *
   * <p>{@code ARCHIVED} is terminal and {@code ACTIVE} is reachable only from a
   * state somebody deliberately moved the policy into.
   */
  private static boolean allowed(String from, String to) {
    if (from.equals(to)) {
      return false;
    }
    return switch (from) {
      case "DRAFT" -> List.of("PENDING_APPROVAL", "ACTIVE", "ARCHIVED").contains(to);
      case "PENDING_APPROVAL" -> List.of("ACTIVE", "DRAFT", "ARCHIVED").contains(to);
      case "ACTIVE" -> List.of("DISABLED", "ARCHIVED").contains(to);
      case "DISABLED" -> List.of("ACTIVE", "ARCHIVED").contains(to);
      default -> false;
    };
  }

  /**
   * Rejects documents the engine could not evaluate.
   *
   * <p>A selector is required by the schema, but an empty object satisfies it
   * and matches nothing — a policy that looks saved and protects nobody. The
   * engine already reads an empty selector as "no match"; catching it here
   * means the author finds out at the moment they press save.
   */
  private void validate(Policy document) {
    if (document.getName() == null || document.getName().isBlank()) {
      throw new IllegalArgumentException("A policy needs a name");
    }
    if (document.getPolicyType() == null) {
      throw new IllegalArgumentException("A policy needs a policyType");
    }
    if (document.getScopeLevel() == null) {
      throw new IllegalArgumentException("A policy needs a scopeLevel");
    }
    if (document.getScopeLevel() != ResolvedColumnMask.ScopeLevel.ORG
        && (document.getScopeFqn() == null || document.getScopeFqn().isBlank())) {
      throw new IllegalArgumentException(
          "A " + value(document.getScopeLevel()) + " policy needs the FQN it is anchored to");
    }
    var selector = document.getSelector();
    if (selector == null
        || (selector.getCondition() == null
            && empty(selector.getAnd())
            && empty(selector.getOr())
            && selector.getNot() == null)) {
      throw new IllegalArgumentException(
          "A policy needs a selector; an empty one binds to nothing and protects nobody");
    }
  }

  private static boolean empty(List<?> list) {
    return list == null || list.isEmpty();
  }

  /** Sub-domain depth, so DOMAIN policies compose deepest-last (FR-3.1.3). */
  private static int depthOf(String scopeFqn) {
    if (scopeFqn == null || scopeFqn.isBlank()) {
      return 0;
    }
    return Fqns.depth(scopeFqn) - 1;
  }

  private static String value(Object enumConstant) {
    return enumConstant == null ? null : enumConstant.toString();
  }

  private static java.sql.Timestamp instant(java.time.Instant at) {
    return at == null ? null : java.sql.Timestamp.from(at);
  }

  private String serialise(Policy document) {
    try {
      return json.writeValueAsString(document);
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot serialise policy " + document.getName(), e);
    }
  }

  /**
   * The stored document with its row's id stamped back onto it.
   *
   * <p>The id is a column, not part of the JSON — so a policy read straight out
   * of the document has {@code id == null}, and every {@link
   * com.mfec.dac.schema.api.DecisionReason} the engine builds from it carries a
   * null policy id. That is what left {@code audit_decision.matched_policy_ids}
   * empty on every row: the audit could name the policy but not point at it
   * (FR-8.2, FR-5.4).
   */
  private Policy deserialise(String id, String document) {
    try {
      return json.readValue(document, Policy.class).withId(UUID.fromString(id));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read stored policy document", e);
    }
  }

  /** Exposed for the materialiser, which wants ids without the documents. */
  public List<UUID> activePolicyIds() {
    return jdbi.withHandle(
        handle ->
            new ArrayList<>(
                handle
                    .createQuery(
                        "SELECT id FROM policy WHERE lifecycle_state IN ('ACTIVE', 'DRAFT')")
                    .mapTo(UUID.class)
                    .list()));
  }
}
