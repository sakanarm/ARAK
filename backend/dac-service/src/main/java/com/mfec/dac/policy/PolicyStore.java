package com.mfec.dac.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mfec.dac.audit.ClientAddress;
import com.mfec.dac.common.ChangeNotifier;
import com.mfec.dac.common.Fqns;
import com.mfec.dac.engine.PolicyExpressionEvaluator;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.entity.policy.Policy;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
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
 *
 * <p>Every write also appends one row to {@code audit_policy_change}, in the
 * same transaction: the version table says what each version read, and the
 * change log says what was done to get there -- an edit, a publish, a version
 * put back. A write that could land without its log row would be a change
 * nobody can account for, so neither lands without the other.
 */
public class PolicyStore {

  private static final Logger LOG = LoggerFactory.getLogger(PolicyStore.class);

  private final Jdbi jdbi;
  private final ObjectMapper json;
  private final ChangeNotifier changes = new ChangeNotifier();

  public PolicyStore(Jdbi jdbi, ObjectMapper json) {
    this.jdbi = jdbi;
    this.json = json;
  }

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

  /**
   * One version of a policy as its history shows it (FR-9.2).
   *
   * <p>A record of its own rather than a {@link StoredPolicy} with blanks,
   * because what a version is differs from what a policy is: it has a reason
   * somebody gave and an act that produced it, and it has no environment or
   * creator of its own.
   *
   * @param action what produced this version: {@code CREATE}, {@code UPDATE},
   *     {@code SUBMIT}, {@code RETURN}, {@code PUBLISH}, {@code DISABLE},
   *     {@code ARCHIVE} or {@code ROLLBACK}. Read from the change log, and
   *     worked out from the states either side for versions written before
   *     the log was
   * @param restoredFrom for a rollback, the version whose document it copied
   */
  public record Revision(
      UUID policyId,
      int version,
      Policy document,
      String lifecycleState,
      String changedBy,
      String changeReason,
      Instant changedAt,
      String action,
      Integer restoredFrom) {}

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
   * Thrown when the name is already used in that environment. An archived
   * policy keeps its name: the audit trail refers to policies by name, and a
   * name that meant two different rules would make it say the wrong thing.
   */
  public static class NameTakenException extends RuntimeException {
    public NameTakenException(String message) {
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
    return create(document, author, null);
  }

  /**
   * @param clientIp where the request came from, for the change log; never
   *     read back out of it
   */
  public StoredPolicy create(Policy document, String author, String clientIp) {
    validate(document);
    StoredPolicy created;
    try {
      created = insert(document, author, clientIp);
    } catch (UnableToExecuteStatementException e) {
      if (!isNameClash(e)) {
        throw e;
      }
      throw nameTaken(document.getName(), environmentOf(document));
    }
    // A DRAFT decides nothing, but it is created and activated from the same
    // screen seconds apart, and announcing both is cheaper than reasoning about
    // which lifecycle states are safe to stay quiet about.
    changes.fire("policy " + document.getName() + " created");
    return created;
  }

  private static String environmentOf(Policy document) {
    return document.getEnvironment() == null ? "dev" : value(document.getEnvironment());
  }

  private NameTakenException nameTaken(String name, String environment) {
    String state =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        "SELECT lifecycle_state FROM policy WHERE name = :name AND environment = :env")
                    .bind("name", name)
                    .bind("env", environment)
                    .mapTo(String.class)
                    .findOne()
                    .orElse("another"));
    return new NameTakenException(
        "A policy named " + name + " already exists in " + environment
            + " (" + state + "); names are not reused, even after archiving — choose another");
  }

  // Postgres reports a unique violation as SQLSTATE 23505, naming the constraint.
  private static boolean isNameClash(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql
          && "23505".equals(sql.getSQLState())
          && String.valueOf(sql.getMessage()).contains("policy_name_environment_key")) {
        return true;
      }
    }
    return false;
  }

  private StoredPolicy insert(Policy document, String author, String clientIp) {
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
                  .bind("environment", environmentOf(document))
                  .bind("document", serialise(document))
                  .bind("validFrom", instant(document.getValidFrom()))
                  .bind("validUntil", instant(document.getValidUntil()))
                  .bind("author", author)
                  .mapTo(UUID.class)
                  .one();

          String body = serialise(document);
          recordVersion(handle, id, 1, body, "DRAFT", author, "created");
          audit(
              handle,
              new Change(
                  author, id, document.getName(), "CREATE", null, 1, null, body, null, "DRAFT",
                  "created", null, clientIp));
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
    return update(id, document, expectedVersion, author, reason, null);
  }

  public StoredPolicy update(
      UUID id, Policy document, int expectedVersion, String author, String reason, String clientIp) {
    validate(document);
    StoredPolicy updated;
    try {
      updated =
          jdbi.inTransaction(
              handle -> {
                StoredPolicy current = editable(handle, id, expectedVersion, "edited");
                int next = current.version() + 1;
                String body = serialise(document);
                writeDocument(handle, id, document, body, next, author);
                recordVersion(handle, id, next, body, current.lifecycleState(), author, reason);
                audit(
                    handle,
                    new Change(
                        author, id, document.getName(), "UPDATE", current.version(), next,
                        serialise(current.document()), body, current.lifecycleState(),
                        current.lifecycleState(), reason, null, clientIp));
                return read(handle, id).orElseThrow();
              });
    } catch (UnableToExecuteStatementException e) {
      // A rename onto a name already in use. Refused like the same clash at
      // creation, rather than surfacing as a server error with the constraint
      // name in it.
      if (!isNameClash(e)) {
        throw e;
      }
      throw nameTaken(document.getName(), environmentOf(policyOrThrow(id).document()));
    }
    changes.fire("policy " + id + " edited");
    return updated;
  }

  /**
   * Puts an earlier version's document back, as a new version (FR-9.2).
   *
   * <p>Never by rewriting history. The old version stays where it was, and
   * what is written is version {@code current + 1} whose document is a copy of
   * it, with {@code restored_from} in the change log saying which. That is what
   * keeps "what did this policy say on the 3rd" answerable after a rollback:
   * the versions in between were in force, and a rollback that erased them
   * would erase the explanation for every decision they made.
   *
   * <p>The lifecycle state is left as it is. Rolling back an active policy
   * changes what is being enforced now -- which is the point, and why the
   * screen shows the impact first -- but it does not also switch the policy
   * on or off; that is a separate, separately recorded act.
   *
   * <p>The document is checked as if it were being saved today, because it is.
   * An old version can have become unsaveable since -- an exemption whose
   * expiry the rules now require, an expression the parser no longer accepts
   * -- and restoring it anyway would put a policy in force that nobody could
   * then save again.
   *
   * @param toVersion the version to copy
   * @param expectedVersion the version the person restoring was looking at
   * @throws IllegalArgumentException if the version does not exist, is the
   *     current one, reads the same as the current one, or could not be saved
   */
  public StoredPolicy rollback(
      UUID id, int toVersion, int expectedVersion, String author, String reason, String clientIp) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("Say why this version is being put back");
    }
    StoredPolicy restored;
    try {
      restored =
          jdbi.inTransaction(
              handle -> {
                StoredPolicy current = editable(handle, id, expectedVersion, "rolled back");
                if (toVersion == current.version()) {
                  throw new IllegalArgumentException(
                      "Version " + toVersion + " is the current version; there is nothing to put back");
                }
                String stored =
                    handle
                        .createQuery(
                            "SELECT document FROM policy_version WHERE policy_id = :id AND version = :version")
                        .bind("id", id)
                        .bind("version", toVersion)
                        .mapTo(String.class)
                        .findOne()
                        .orElseThrow(
                            () ->
                                new IllegalArgumentException(
                                    "Policy " + id + " has no version " + toVersion));
                Policy document = restorable(id, stored, current);
                if (sameRule(document, current.document())) {
                  throw new IllegalArgumentException(
                      "Version " + toVersion + " reads the same as the current version; "
                          + "there is nothing to put back");
                }
                try {
                  validate(document);
                } catch (IllegalArgumentException e) {
                  throw new IllegalArgumentException(
                      "Version " + toVersion + " can no longer be saved as it is: " + e.getMessage());
                }
                int next = current.version() + 1;
                String body = serialise(document);
                writeDocument(handle, id, document, body, next, author);
                recordVersion(handle, id, next, body, current.lifecycleState(), author, reason);
                audit(
                    handle,
                    new Change(
                        author, id, document.getName(), "ROLLBACK", current.version(), next,
                        serialise(current.document()), body, current.lifecycleState(),
                        current.lifecycleState(), reason, toVersion, clientIp));
                LOG.info(
                    "Policy {} rolled back to the document of version {} as version {} by {}",
                    id, toVersion, next, author);
                return read(handle, id).orElseThrow();
              });
    } catch (UnableToExecuteStatementException e) {
      // The old version's name has since been given to another policy.
      if (!isNameClash(e)) {
        throw e;
      }
      String name = revision(id, toVersion).map(r -> r.document().getName()).orElse("that name");
      throw nameTaken(name, environmentOf(policyOrThrow(id).document()));
    }
    changes.fire("policy " + id + " rolled back to version " + toVersion);
    return restored;
  }

  /**
   * The policy as it stands, if it may be written: not archived, and still at
   * the version the writer was looking at.
   *
   * <p>Archived is checked first, because an archived policy is refused
   * whatever version the writer was holding, and "reload and try again" would
   * be the wrong thing to tell them.
   */
  private StoredPolicy editable(Handle handle, UUID id, int expectedVersion, String verb) {
    StoredPolicy current =
        read(handle, id).orElseThrow(() -> new IllegalArgumentException("No policy " + id));
    if ("ARCHIVED".equals(current.lifecycleState())) {
      throw new IllegalTransitionException("An archived policy cannot be " + verb);
    }
    if (current.version() != expectedVersion) {
      throw new StaleVersionException(
          "Policy " + id + " is at version " + current.version() + ", not " + expectedVersion);
    }
    return current;
  }

  private StoredPolicy policyOrThrow(UUID id) {
    return find(id).orElseThrow(() -> new IllegalArgumentException("No policy " + id));
  }

  /**
   * An old version's document, made fit to be the current one.
   *
   * <p>The fields the store keeps for itself -- id, state, version, who and
   * when -- are dropped, since the row says those and the copy would only say
   * them wrongly. The environment is the policy's present one: an edit never
   * moves a policy between environments, so a rollback must not either.
   */
  private Policy restorable(UUID id, String stored, StoredPolicy current) {
    Policy document = deserialise(id.toString(), stored);
    document.setId(null);
    document.setLifecycleState(null);
    document.setVersion(null);
    document.setUpdatedAt(null);
    document.setUpdatedBy(null);
    document.setEnvironment(current.document().getEnvironment());
    return document;
  }

  /**
   * Whether two documents say the same rule, ignoring the store's own fields
   * and the order keys happen to be in.
   */
  private boolean sameRule(Policy a, Policy b) {
    return rule(a).equals(rule(b));
  }

  private JsonNode rule(Policy document) {
    JsonNode tree = json.valueToTree(document);
    if (tree instanceof ObjectNode object) {
      object.remove(List.of("id", "lifecycleState", "version", "updatedAt", "updatedBy", "environment"));
    }
    return tree;
  }

  /** The columns an edit changes, shared so an edit and a rollback cannot disagree about them. */
  private static void writeDocument(
      Handle handle, UUID id, Policy document, String body, int version, String author) {
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
        .bind("version", version)
        .bind("validFrom", instant(document.getValidFrom()))
        .bind("validUntil", instant(document.getValidUntil()))
        .bind("author", author)
        .execute();
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
    return transition(id, to, author, reason, null);
  }

  public StoredPolicy transition(UUID id, String to, String author, String reason, String clientIp) {
    StoredPolicy moved = jdbi.inTransaction(
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
          String body = serialise(current.document());
          String why = reason == null ? current.lifecycleState() + " -> " + to : reason;
          recordVersion(handle, id, next, body, to, author, why);
          // The document did not move, so only the "after" side is written:
          // it is the rule that went live, or stopped being live, and a
          // "before" identical to it would say nothing.
          audit(
              handle,
              new Change(
                  author, id, current.document().getName(), actionFor(current.lifecycleState(), to),
                  current.version(), next, null, body, current.lifecycleState(), to, why, null,
                  clientIp));
          LOG.info("Policy {} moved {} -> {} by {}", id, current.lifecycleState(), to, author);
          return read(handle, id).orElseThrow();
        });
    // The one that matters most: ACTIVE and DISABLED are the instants
    // enforcement starts and stops, and a cache that outlived either would keep
    // enforcing a policy somebody has just switched off.
    changes.fire("policy " + id + " moved to " + to);
    return moved;
  }

  /** What the change log calls a move from one state to another. */
  static String actionFor(String from, String to) {
    return switch (to) {
      case "ACTIVE" -> "PUBLISH";
      case "DISABLED" -> "DISABLE";
      case "ARCHIVED" -> "ARCHIVE";
      case "PENDING_APPROVAL" -> "SUBMIT";
      case "DRAFT" -> "RETURN";
      default -> throw new IllegalStateException("No log action for " + from + " -> " + to);
    };
  }

  public Optional<StoredPolicy> find(UUID id) {
    return jdbi.withHandle(handle -> read(handle, id));
  }

  /** Policies, newest first, optionally narrowed the way the list screen narrows them. */
  public List<StoredPolicy> list(
      String lifecycleState, String policyType, String scopeLevel, String search, int limit, int offset) {
    Filter filter = filter(lifecycleState, policyType, scopeLevel, search);
    String sql =
        "SELECT * FROM policy "
            + filter.where()
            + " ORDER BY updated_at DESC LIMIT :limit OFFSET :offset";

    return jdbi.withHandle(
        handle -> {
          var query = handle.createQuery(sql).bind("limit", limit).bind("offset", offset);
          filter.bind(query);
          return query.map(this::map).list();
        });
  }

  /**
   * How many policies the same filter matches, for the pager.
   *
   * <p>Separate from {@link #list} rather than returned with it, because the
   * two are asked at different rates: the page changes as somebody clicks
   * through, and the total does not. Splitting them lets the total be cached
   * while the page is not.
   *
   * <p>What matters is that both go through {@link #filter}. A count built from
   * a second copy of the WHERE clause is a page count that drifts from the page
   * the moment somebody adds a filter to one and forgets the other, and the
   * symptom is a last page that is empty.
   */
  public int count(String lifecycleState, String policyType, String scopeLevel, String search) {
    Filter filter = filter(lifecycleState, policyType, scopeLevel, search);
    String sql = "SELECT count(*) FROM policy " + filter.where();

    return jdbi.withHandle(
        handle -> {
          var query = handle.createQuery(sql);
          filter.bind(query);
          return query.mapTo(Integer.class).one();
        });
  }

  /** A WHERE clause and the values it needs, so a count cannot disagree with a page. */
  private record Filter(String where, Map<String, Object> binds) {
    void bind(org.jdbi.v3.core.statement.Query query) {
      binds.forEach(query::bind);
    }
  }

  private static Filter filter(
      String lifecycleState, String policyType, String scopeLevel, String search) {
    StringBuilder where = new StringBuilder("WHERE 1 = 1");
    Map<String, Object> binds = new LinkedHashMap<>();
    if (lifecycleState != null) {
      where.append(" AND lifecycle_state = :lifecycleState");
      binds.put("lifecycleState", lifecycleState);
    }
    if (policyType != null) {
      where.append(" AND policy_type = :policyType");
      binds.put("policyType", policyType);
    }
    if (scopeLevel != null) {
      where.append(" AND scope_level = :scopeLevel");
      binds.put("scopeLevel", scopeLevel);
    }
    String term = search == null ? null : search.trim();
    if (term != null && !term.isEmpty()) {
      // Four columns, because people arrive with whichever one they remember:
      // the name a policy was filed under, the label somebody gave it later,
      // the sentence explaining why it exists, or -- most often -- the table
      // it is about. Searching only the name finds a policy for whoever named
      // it and nobody else.
      where.append(
          " AND (name ILIKE :search OR display_name ILIKE :search"
              + " OR description ILIKE :search OR scope_fqn ILIKE :search)");
      binds.put("search", "%" + escapeLike(term) + "%");
    }
    return new Filter(where.toString(), binds);
  }

  /**
   * Make a typed search term mean itself.
   *
   * <p>An FQN is full of dots, which LIKE does not care about, but a name may
   * hold an underscore -- which LIKE reads as "any one character", so a search
   * for {@code pii_mask} would also return {@code piixmask}. Nobody typing a
   * policy name means a wildcard by it. The backslash matches the ESCAPE that
   * PostgreSQL applies to LIKE by default.
   */
  private static String escapeLike(String term) {
    return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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

  /**
   * The history of one policy, newest first (FR-9.2).
   *
   * <p>Each version with the act that produced it, read from the change log.
   * Versions written before the log was kept have no row there, and for those
   * the act is worked out from the states either side of it -- which is exact
   * for everything but a rollback, and there were no rollbacks before the log.
   *
   * <p>The log's {@code client_ip} is not read. Where somebody was sitting is
   * for an investigation, not for everybody who can open the policy.
   */
  public List<Revision> history(UUID id) {
    record Row(
        int version,
        String document,
        String state,
        String changedBy,
        String reason,
        Instant changedAt,
        String action,
        Integer restoredFrom) {}

    List<Row> rows =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT v.version, v.document, v.lifecycle_state, v.changed_by,
                               v.change_reason, v.changed_at, c.action, c.restored_from
                        FROM policy_version v
                        LEFT JOIN LATERAL (
                            SELECT a.action, a.restored_from
                            FROM audit_policy_change a
                            WHERE a.policy_id = v.policy_id AND a.to_version = v.version
                            ORDER BY a.id DESC
                            LIMIT 1
                        ) c ON true
                        WHERE v.policy_id = :id
                        ORDER BY v.version
                        """)
                    .bind("id", id)
                    .map(
                        (rs, ctx) ->
                            new Row(
                                rs.getInt("version"),
                                rs.getString("document"),
                                rs.getString("lifecycle_state"),
                                rs.getString("changed_by"),
                                rs.getString("change_reason"),
                                rs.getTimestamp("changed_at").toInstant(),
                                rs.getString("action"),
                                (Integer) rs.getObject("restored_from")))
                    .list());

    List<Revision> out = new ArrayList<>(rows.size());
    String previous = null;
    for (Row row : rows) {
      String action = row.action();
      if (action == null) {
        if (previous == null) {
          action = "CREATE";
        } else if (!previous.equals(row.state())) {
          try {
            action = actionFor(previous, row.state());
          } catch (IllegalStateException e) {
            // A state the log has no word for. The history still reads; one
            // row of it is less specific than it could be.
            action = "UPDATE";
          }
        } else {
          action = "UPDATE";
        }
      }
      out.add(
          new Revision(
              id,
              row.version(),
              deserialise(id.toString(), row.document()),
              row.state(),
              row.changedBy(),
              row.reason(),
              row.changedAt(),
              action,
              row.restoredFrom()));
      previous = row.state();
    }
    Collections.reverse(out);
    return out;
  }

  /** One version of a policy, as {@link #history} reads it. */
  public Optional<Revision> revision(UUID id, int version) {
    return history(id).stream().filter(r -> r.version() == version).findFirst();
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

  /**
   * One row of the policy change log (FR-8.1).
   *
   * @param before the document before the change, where it changed
   * @param after the document after it; for a lifecycle move, the document
   *     that went live or stopped being live
   */
  private record Change(
      String actor,
      UUID policyId,
      String policyName,
      String action,
      Integer fromVersion,
      int toVersion,
      String before,
      String after,
      String fromState,
      String toState,
      String reason,
      Integer restoredFrom,
      String clientIp) {}

  /**
   * Written on the same handle as the version it describes, so the two commit
   * or roll back together. Unlike the query log, a failure here is not caught
   * and logged: the change log is the record that the change was made, and a
   * policy change that cannot be recorded is not made.
   */
  private static void audit(Handle handle, Change change) {
    handle
        .createUpdate(
            """
            INSERT INTO audit_policy_change (actor, policy_id, policy_name, action,
                                             from_version, to_version, before_document,
                                             after_document, from_state, to_state, reason,
                                             restored_from, client_ip)
            VALUES (:actor, :policyId, :policyName, :action, :fromVersion, :toVersion,
                    CAST(:before AS jsonb), CAST(:after AS jsonb), :fromState, :toState,
                    :reason, :restoredFrom, CAST(:clientIp AS inet))
            """)
        .bind("actor", change.actor())
        .bind("policyId", change.policyId())
        .bind("policyName", change.policyName())
        .bind("action", change.action())
        .bind("fromVersion", change.fromVersion())
        .bind("toVersion", change.toVersion())
        .bind("before", change.before())
        .bind("after", change.after())
        .bind("fromState", change.fromState())
        .bind("toState", change.toState())
        .bind("reason", change.reason())
        .bind("restoredFrom", change.restoredFrom())
        .bind("clientIp", ClientAddress.normalise(change.clientIp()))
        .execute();
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
    ConditionValues.check(document);
    LookupCheck.check(jdbi, document);
    validateExemptions(document);
    validateExpression(document);
  }

  /**
   * Refuses an exemption the engine would not honour (FR-3.5).
   *
   * <p>The engine ignores an exemption with no expiry or no reason rather than
   * reading it as a permanent hole, which is the safe way round, but on its own
   * it is silent: the policy saves, the author believes a colleague is exempt,
   * and the colleague is still refused. Saying so at save time turns that into
   * a message instead of a support ticket.
   */
  private static void validateExemptions(Policy document) {
    if (document.getExemptions() == null) {
      return;
    }
    for (var exemption : document.getExemptions()) {
      if (exemption == null || exemption.getPrincipal() == null || exemption.getPrincipal().isBlank()) {
        throw new IllegalArgumentException("An exemption needs the principal it exempts");
      }
      if (exemption.getReason() == null || exemption.getReason().isBlank()) {
        throw new IllegalArgumentException("The exemption for " + exemption.getPrincipal() + " needs a reason");
      }
      if (exemption.getExpiresAt() == null) {
        throw new IllegalArgumentException(
            "The exemption for " + exemption.getPrincipal() + " needs an expiry date; exemptions are never permanent");
      }
    }
  }

  /**
   * Refuses a subject expression the engine could not read.
   *
   * <p>Without this the failure is silent and one-sided. An unparseable
   * expression is undecidable at evaluation time, an ALLOW carrying one never
   * grants, and nothing anywhere says so: the policy saves, activates, and
   * reads back exactly as it was typed. The owner sees a policy that is
   * plainly there and a colleague who still cannot open the table.
   *
   * <p>What it cannot catch is a misspelt {@code user.} attribute, because
   * {@code user.} is open on purpose so the directory can grow without a code
   * change, and a name nobody has today is a name somebody may have tomorrow.
   * That one is surfaced as a warning while the policy is being written rather
   * than as a refusal here.
   */
  private void validateExpression(Policy document) {
    var subject = document.getSubject();
    if (subject == null) {
      return;
    }
    String expression = subject.getExpression();
    if (expression == null || expression.isBlank()) {
      return;
    }
    PolicyExpressionEvaluator.Validation check =
        PolicyExpressionEvaluator.validate(expression);
    if (!check.valid()) {
      throw new IllegalArgumentException(
          "The policy's expression cannot be read: " + check.message());
    }
    if (check.rowDependent()) {
      // A subject rule decides who reaches the table at all, before a row
      // exists, so the engine throws on this at evaluation time. Saying so
      // here points at the fix -- a row filter in a data policy -- instead of
      // leaving a rule that denies everybody for reasons nobody can see.
      throw new IllegalArgumentException(
          "The policy's expression refers to row data, which a subject rule cannot use. "
              + "Move it to a data policy row filter.");
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
