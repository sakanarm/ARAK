package com.mfec.dac.access;

import com.mfec.dac.common.ChangeNotifier;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.PrincipalMatch;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Direct access grants: one named principal, one named table, a window of time
 * (FR-7).
 *
 * <p>A grant is not a second kind of authorisation. It is turned into an
 * ordinary {@code SUBSCRIPTION} policy at the {@code TABLE} layer and handed to
 * the same engine as everything else, which is what makes its behaviour
 * predictable rather than merely documented:
 *
 * <ul>
 *   <li>Where no policy binds the asset, the grant is the only gate and it
 *       opens. This is the case grants exist for.
 *   <li>Where a higher layer holds an ALLOW that does not match this person,
 *       that layer is still a gate and still refuses. A grant cannot buy its
 *       way past a global policy (FR-3.1.4).
 *   <li>Where any DENY matches, the DENY wins, as it does against any ALLOW.
 * </ul>
 *
 * <p>None of those three rules is implemented here. They are the engine's
 * existing composition rules, and writing a grant as a policy is how this
 * feature inherits them instead of restating them and drifting.
 */
public class GrantStore {

  private static final Logger LOG = LoggerFactory.getLogger(GrantStore.class);

  /**
   * The scope level a grant enters the engine at.
   *
   * <p>{@code TABLE} rather than {@code COLUMN} because a grant is about
   * reaching the table at all; what the holder then sees inside it is a data
   * policy's business, and a grant must not quietly unmask anything.
   */
  private static final ResolvedColumnMask.ScopeLevel GRANT_LAYER =
      ResolvedColumnMask.ScopeLevel.TABLE;

  private final Jdbi jdbi;
  private final ChangeNotifier changes = new ChangeNotifier();

  public GrantStore(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /**
   * Announces every write that could change an access decision (FR-5.5).
   *
   * <p>Here rather than in the resource for the same reason as in {@code
   * PolicyStore}: the expiry job writes grants too, and it never goes near a
   * REST endpoint.
   */
  public ChangeNotifier changes() {
    return changes;
  }

  /** A grant as stored, with the principal resolved for display. */
  public record StoredGrant(
      UUID id,
      String assetFqn,
      UUID principalId,
      String username,
      String displayName,
      String principalType,
      String principalSource,
      String source,
      UUID requestId,
      Instant validFrom,
      Instant validUntil,
      String reason,
      String grantedBy,
      Instant grantedAt,
      Instant revokedAt,
      String revokedBy,
      String revokeReason) {

    /** True while this grant is neither revoked nor outside its window. */
    public boolean liveAt(Instant at) {
      if (revokedAt != null) {
        return false;
      }
      if (validFrom != null && at.isBefore(validFrom)) {
        return false;
      }
      return validUntil == null || at.isBefore(validUntil);
    }

    /** True when this grant names a group rather than a person. */
    public boolean isGroup() {
      return "GROUP".equals(principalType);
    }
  }

  // --------------------------------------------------------------- reading

  /**
   * Every grant on an asset that has not been revoked, newest first.
   *
   * <p>Includes grants whose window has not opened yet and grants that have
   * lapsed but not been tombstoned, because the asset page has to be able to
   * show "starts Monday" and "expired yesterday" rather than only the rows that
   * happen to be live this second.
   */
  public List<StoredGrant> onAsset(String assetFqn) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT g.*, p.username, p.display_name, p.principal_type, p.source AS principal_source
                    FROM access_grant g
                    JOIN principal p ON p.id = g.principal_id
                    WHERE g.asset_fqn = :fqn AND g.revoked_at IS NULL
                    ORDER BY g.granted_at DESC
                    """)
                .bind("fqn", assetFqn)
                .map(GrantStore::map)
                .list());
  }

  /** Every grant one principal holds directly, for the "my access" view (FR-7.3). */
  public List<StoredGrant> heldBy(UUID principalId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT g.*, p.username, p.display_name, p.principal_type, p.source AS principal_source
                    FROM access_grant g
                    JOIN principal p ON p.id = g.principal_id
                    WHERE g.principal_id = :id AND g.revoked_at IS NULL
                    ORDER BY g.asset_fqn
                    """)
                .bind("id", principalId)
                .map(GrantStore::map)
                .list());
  }

  /**
   * Every grant that reaches one person, named rather than identified.
   *
   * <p>Includes grants made to a group they belong to, and to a group that
   * group belongs to, because "my access" that omitted them would answer a
   * question nobody asked: people do not experience their group memberships as
   * a separate kind of access. The membership walk is the same recursive one
   * {@code PrincipalLoader} uses, so the two cannot disagree about who is in
   * what.
   */
  public List<StoredGrant> heldBy(String username) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    WITH RECURSIVE me AS (
                      SELECT id FROM principal WHERE lower(username) = lower(:username)
                    ),
                    reachable AS (
                        SELECT id AS principal_id FROM me
                      UNION
                        SELECT m.group_id
                        FROM group_member m
                        JOIN reachable r ON m.member_id = r.principal_id
                    )
                    SELECT g.*, p.username, p.display_name, p.principal_type, p.source AS principal_source
                    FROM access_grant g
                    JOIN principal p ON p.id = g.principal_id
                    WHERE g.principal_id IN (SELECT principal_id FROM reachable)
                      AND g.revoked_at IS NULL
                    ORDER BY g.asset_fqn
                    """)
                .bind("username", username)
                .map(GrantStore::map)
                .list());
  }

  public Optional<StoredGrant> find(UUID id) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT g.*, p.username, p.display_name, p.principal_type, p.source AS principal_source
                    FROM access_grant g
                    JOIN principal p ON p.id = g.principal_id
                    WHERE g.id = :id
                    """)
                .bind("id", id)
                .map(GrantStore::map)
                .findOne());
  }

  /**
   * One entry in an asset's grant history.
   *
   * <p>Flat strings rather than references, matching the table: the trail has to
   * stay readable after the person it names has been deleted, which is the one
   * case an audit is for.
   */
  public record HistoryEntry(
      long id,
      Instant occurredAt,
      String actor,
      String action,
      String grantId,
      String assetFqn,
      String targetUsername,
      String targetSource,
      Instant validFrom,
      Instant validUntil,
      String reason) {}

  /**
   * Everything that has ever been granted or revoked on one asset, newest first.
   *
   * <p>Read from the audit table rather than from the grants themselves, because
   * the two answer different questions: the grant table says what is true now,
   * this says what happened -- including grants that were created and revoked
   * inside the same afternoon and no longer appear anywhere else.
   */
  public List<HistoryEntry> historyFor(String assetFqn, int limit) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT * FROM audit_grant_change
                    WHERE asset_fqn = :fqn
                    ORDER BY occurred_at DESC, id DESC
                    LIMIT :limit
                    """)
                .bind("fqn", assetFqn)
                .bind("limit", Math.max(1, Math.min(limit, 500)))
                .map(
                    (rs, ctx) ->
                        new HistoryEntry(
                            rs.getLong("id"),
                            instant(rs, "occurred_at"),
                            rs.getString("actor"),
                            rs.getString("action"),
                            rs.getString("grant_id"),
                            rs.getString("asset_fqn"),
                            rs.getString("target_username"),
                            rs.getString("target_source"),
                            instant(rs, "valid_from"),
                            instant(rs, "valid_until"),
                            rs.getString("reason")))
                .list());
  }

  // ------------------------------------------------------ the engine bridge

  /**
   * The grants that bear on one asset right now, already shaped as policies.
   *
   * <p>Called on the hot path, once per decision, and deliberately narrow: one
   * indexed read of the live rows for this asset. A grant to a group is not
   * expanded into its members here -- the policy names the group and the
   * engine's own {@code hasGroup} check resolves it, so membership arriving
   * from Entra, from an OpenMetadata team or from a local group all work
   * without this class knowing the difference.
   */
  public List<Policy> policiesFor(Handle handle, String assetFqn, Instant at) {
    List<StoredGrant> live =
        handle
            .createQuery(
                """
                SELECT g.*, p.username, p.display_name, p.principal_type, p.source AS principal_source
                FROM access_grant g
                JOIN principal p ON p.id = g.principal_id
                WHERE g.asset_fqn = :fqn
                  AND g.revoked_at IS NULL
                  AND p.enabled
                  AND g.valid_from <= :at
                  AND (g.valid_until IS NULL OR g.valid_until > :at)
                """)
            .bind("fqn", assetFqn)
            .bind("at", at)
            .map(GrantStore::map)
            .list();

    List<Policy> documents = new ArrayList<>(live.size());
    for (StoredGrant grant : live) {
      documents.add(asPolicy(grant));
    }
    return documents;
  }

  /**
   * One grant rendered as the policy the engine will evaluate.
   *
   * <p>The name is {@code grant:<uuid>} so that an explanation (FR-5.4) can be
   * traced back to the exact row, and the display name is what a person reads
   * in the decision's reasons. The window is carried on the policy rather than
   * filtered in SQL as well, so that a simulation asking "what will this look
   * like next Tuesday" gets the right answer from the same object.
   */
  static Policy asPolicy(StoredGrant grant) {
    PrincipalMatch who = new PrincipalMatch();
    if (grant.isGroup()) {
      who.setGroup(grant.username());
    } else {
      who.setUser(grant.username());
    }

    // `principals` is the OR list: any one entry matching is enough. A grant
    // has exactly one entry, so the distinction does not bite here -- but it is
    // the reason a grant reads as "this person, alternatively to whoever else
    // the layer allows" rather than as an extra condition on them.
    SubjectRule subject = new SubjectRule().withPrincipals(List.of(who));

    return new Policy()
        .withName("grant:" + grant.id())
        .withDisplayName(
            "Direct grant to "
                + (grant.displayName() == null ? grant.username() : grant.displayName()))
        .withDescription(grant.reason())
        .withPolicyType(Policy.PolicyType.SUBSCRIPTION)
        .withScopeLevel(GRANT_LAYER)
        .withScopeFqn(grant.assetFqn())
        .withSubject(subject)
        .withEffect(Policy.Effect.ALLOW)
        .withValidFrom(grant.validFrom())
        .withValidUntil(grant.validUntil())
        .withLifecycleState(Policy.LifecycleState.ACTIVE)
        .withUpdatedBy(grant.grantedBy());
  }

  // --------------------------------------------------------------- writing

  /** What a caller asks for when granting. */
  public record NewGrant(
      String assetFqn,
      UUID principalId,
      Instant validFrom,
      Instant validUntil,
      String reason,
      String grantedBy) {}

  /**
   * Creates a grant and records it.
   *
   * <p>The insert and the audit row go in one transaction: a grant that exists
   * without a trail is exactly the row an access review cannot account for.
   */
  public StoredGrant grant(NewGrant request) {
    if (request.reason() == null || request.reason().isBlank()) {
      throw new IllegalArgumentException("a grant must say why it was given");
    }
    Instant from = request.validFrom() == null ? Instant.now() : request.validFrom();
    if (request.validUntil() != null && !request.validUntil().isAfter(from)) {
      throw new IllegalArgumentException("a grant must end after it starts");
    }

    StoredGrant created =
        jdbi.inTransaction(
            handle -> {
              UUID id =
                  handle
                      .createQuery(
                          """
                          INSERT INTO access_grant
                            (asset_fqn, principal_id, source, valid_from, valid_until,
                             reason, granted_by)
                          VALUES
                            (:fqn, :principal, 'manual', :from, :until, :reason, :by)
                          RETURNING id
                          """)
                      .bind("fqn", request.assetFqn())
                      .bind("principal", request.principalId())
                      .bind("from", from)
                      .bind("until", request.validUntil())
                      .bind("reason", request.reason())
                      .bind("by", request.grantedBy())
                      .mapTo(UUID.class)
                      .one();

              StoredGrant stored =
                  handle
                      .createQuery(
                          """
                          SELECT g.*, p.username, p.display_name, p.principal_type,
                                 p.source AS principal_source
                          FROM access_grant g
                          JOIN principal p ON p.id = g.principal_id
                          WHERE g.id = :id
                          """)
                      .bind("id", id)
                      .map(GrantStore::map)
                      .one();

              audit(handle, "GRANT", request.grantedBy(), stored, request.reason());
              return stored;
            });

    LOG.info(
        "Grant {} on {} to {} until {} by {}",
        created.id(),
        created.assetFqn(),
        created.username(),
        created.validUntil() == null ? "(no expiry)" : created.validUntil(),
        request.grantedBy());
    changes.fire("grant " + created.id() + " on " + created.assetFqn());
    return created;
  }

  /**
   * Revokes a grant, leaving the row in place as a tombstone.
   *
   * <p>Returns empty when the grant does not exist or was already revoked, so
   * that a second click is a no-op rather than a second audit entry.
   */
  public Optional<StoredGrant> revoke(UUID id, String actor, String why) {
    Optional<StoredGrant> revoked =
        jdbi.inTransaction(
            handle -> {
              int touched =
                  handle
                      .createUpdate(
                          """
                          UPDATE access_grant
                          SET revoked_at = now(), revoked_by = :by, revoke_reason = :why
                          WHERE id = :id AND revoked_at IS NULL
                          """)
                      .bind("id", id)
                      .bind("by", actor)
                      .bind("why", why)
                      .execute();
              if (touched == 0) {
                return Optional.<StoredGrant>empty();
              }
              StoredGrant stored =
                  handle
                      .createQuery(
                          """
                          SELECT g.*, p.username, p.display_name, p.principal_type,
                                 p.source AS principal_source
                          FROM access_grant g
                          JOIN principal p ON p.id = g.principal_id
                          WHERE g.id = :id
                          """)
                      .bind("id", id)
                      .map(GrantStore::map)
                      .one();
              audit(handle, "REVOKE", actor, stored, why);
              return Optional.of(stored);
            });

    revoked.ifPresent(
        grant -> {
          LOG.info("Grant {} on {} revoked by {}", grant.id(), grant.assetFqn(), actor);
          changes.fire("grant " + grant.id() + " revoked on " + grant.assetFqn());
        });
    return revoked;
  }

  /**
   * Tombstones every grant whose window has closed (FR-7.2).
   *
   * <p>Expiry is already honoured on the read path, so this job does not change
   * who can reach what. It exists so that "expired" is a fact in the table and
   * in the audit trail rather than an inference someone has to make from two
   * timestamps, and so the live indexes stay small.
   *
   * @return how many grants were retired
   */
  public int expire(Instant now) {
    List<StoredGrant> lapsed =
        jdbi.inTransaction(
            handle -> {
              List<StoredGrant> found =
                  handle
                      .createQuery(
                          """
                          SELECT g.*, p.username, p.display_name, p.principal_type,
                                 p.source AS principal_source
                          FROM access_grant g
                          JOIN principal p ON p.id = g.principal_id
                          WHERE g.revoked_at IS NULL
                            AND g.valid_until IS NOT NULL
                            AND g.valid_until <= :now
                          """)
                      .bind("now", now)
                      .map(GrantStore::map)
                      .list();

              for (StoredGrant grant : found) {
                handle
                    .createUpdate(
                        """
                        UPDATE access_grant
                        SET revoked_at = :now, revoked_by = 'system',
                            revoke_reason = 'expired'
                        WHERE id = :id AND revoked_at IS NULL
                        """)
                    .bind("id", grant.id())
                    .bind("now", now)
                    .execute();
                audit(handle, "EXPIRE", "system", grant, "expired");
              }
              return found;
            });

    if (!lapsed.isEmpty()) {
      LOG.info("Retired {} expired grant(s)", lapsed.size());
      // Announced even though no decision changes: the cache holds answers
      // keyed on nothing but principal and asset, and an entry made before the
      // expiry instant could still be inside its TTL.
      changes.fire(lapsed.size() + " grant(s) expired");
    }
    return lapsed.size();
  }

  // ----------------------------------------------------------------- audit

  private static void audit(
      Handle handle, String action, String actor, StoredGrant grant, String why) {
    Update update =
        handle.createUpdate(
            """
            INSERT INTO audit_grant_change
              (actor, action, grant_id, asset_fqn, target_username, target_source,
               valid_from, valid_until, reason)
            VALUES
              (:actor, :action, :grant, :fqn, :username, :source, :from, :until, :why)
            """);
    update
        .bind("actor", actor)
        .bind("action", action)
        .bind("grant", grant.id())
        .bind("fqn", grant.assetFqn())
        .bind("username", grant.username())
        .bind("source", grant.principalSource())
        .bind("from", grant.validFrom())
        .bind("until", grant.validUntil())
        .bind("why", why)
        .execute();
  }

  // --------------------------------------------------------------- mapping

  private static StoredGrant map(ResultSet rs, org.jdbi.v3.core.statement.StatementContext ctx)
      throws SQLException {
    return new StoredGrant(
        UUID.fromString(rs.getString("id")),
        rs.getString("asset_fqn"),
        UUID.fromString(rs.getString("principal_id")),
        rs.getString("username"),
        rs.getString("display_name"),
        rs.getString("principal_type"),
        rs.getString("principal_source"),
        rs.getString("source"),
        uuidOrNull(rs.getString("request_id")),
        instant(rs, "valid_from"),
        instant(rs, "valid_until"),
        rs.getString("reason"),
        rs.getString("granted_by"),
        instant(rs, "granted_at"),
        instant(rs, "revoked_at"),
        rs.getString("revoked_by"),
        rs.getString("revoke_reason"));
  }

  private static UUID uuidOrNull(String value) {
    return value == null ? null : UUID.fromString(value);
  }

  /**
   * Reads a nullable timestamp as an {@link Instant}.
   *
   * <p>Via {@code getTimestamp} rather than {@code getObject(..., Instant.class)}
   * because the driver returns the former for {@code timestamptz} on every
   * version we support, and a null column has to stay null rather than becoming
   * the epoch.
   */
  private static Instant instant(ResultSet rs, String column) throws SQLException {
    java.sql.Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
