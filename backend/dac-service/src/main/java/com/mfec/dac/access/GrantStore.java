package com.mfec.dac.access;

import com.mfec.dac.common.ChangeNotifier;
import com.mfec.dac.purpose.PurposeStore;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.PrincipalMatch;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
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

  /**
   * A grant as stored, with the principal resolved for display.
   *
   * @param purpose the register's key for what the grant was given for (FR-21);
   *     null when it was given without one
   */
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
      String revokeReason,
      String purpose) {

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

  /**
   * The principals a grant can reach this person through: themselves and every
   * group they are in, however deep. Empty when nobody has that name.
   */
  public java.util.Set<UUID> reachableFrom(String username) {
    if (username == null || username.isBlank()) {
      return java.util.Set.of();
    }
    return jdbi.withHandle(
        handle ->
            java.util.Set.copyOf(
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
                        SELECT principal_id FROM reachable
                        """)
                    .bind("username", username)
                    .mapTo(UUID.class)
                    .list()));
  }

  /**
   * Whether a grant to {@code principalId} would reach {@code username}: it is
   * them, or a group they belong to directly or through another group.
   *
   * <p>The same walk as {@link #heldBy}, so "would this grant be mine" and
   * "what do I hold" cannot disagree -- which is what keeps a grant to a group
   * of one from being a way round the rule that nobody grants to themselves.
   */
  public boolean reaches(String username, UUID principalId) {
    if (username == null || principalId == null) {
      return false;
    }
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
                    SELECT EXISTS (SELECT 1 FROM reachable WHERE principal_id = :principal)
                    """)
                .bind("username", username)
                .bind("principal", principalId)
                .mapTo(Boolean.class)
                .one());
  }

  /**
   * Every grant that is live at {@code now} and ends by {@code until}, soonest
   * first (M9 slice 2c).
   *
   * <p>Live means what the engine means by it -- not revoked, started, and not
   * yet ended -- so a grant the expiry job has not tombstoned yet but that has
   * already lapsed is not "ending soon": it has ended, and a countdown that went
   * below zero would be telling a dashboard reader something the engine stopped
   * honouring an hour ago. Open-ended grants never end, so they are never here.
   * A disabled principal's grant is left out for the same reason: the engine
   * already ignores it.
   */
  public List<StoredGrant> expiring(Instant now, Instant until) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT g.*, p.username, p.display_name, p.principal_type, p.source AS principal_source
                    FROM access_grant g
                    JOIN principal p ON p.id = g.principal_id
                    WHERE g.revoked_at IS NULL
                      AND p.enabled
                      AND g.valid_from <= :now
                      AND g.valid_until IS NOT NULL
                      AND g.valid_until > :now
                      AND g.valid_until <= :until
                    ORDER BY g.valid_until, g.asset_fqn, p.username
                    """)
                .bind("now", now)
                .bind("until", until)
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
      String reason,
      String purpose) {}

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
                            rs.getString("reason"),
                            rs.getString("purpose")))
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
   * The grant an owner could give this person, as the engine would read it --
   * never stored, only evaluated (see {@code DecisionService#decideAsIfGranted}).
   *
   * <p>Built through {@link #asPolicy} rather than by hand so that the question
   * "would a grant open this" is answered about the same document a real grant
   * becomes. A hand-built look-alike that differed in scope level would give
   * the button an answer the approval then contradicts.
   */
  public static Policy hypothetical(String username, String assetFqn, Instant at) {
    return asPolicy(
        new StoredGrant(
            new UUID(0L, 0L),
            assetFqn,
            new UUID(0L, 0L),
            username,
            username,
            "USER",
            null,
            "request",
            null,
            // Open from the beginning of time rather than from `at`: the
            // question is whether a grant would let them in, not whether one
            // written this exact millisecond has started yet.
            null,
            null,
            "If the owner approved a request",
            "(hypothetical)",
            at,
            null,
            null,
            null,
            null));
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

  /**
   * What a caller asks for when granting.
   *
   * @param purpose the register's key for what the access is for; null for none
   */
  public record NewGrant(
      String assetFqn,
      UUID principalId,
      Instant validFrom,
      Instant validUntil,
      String reason,
      String grantedBy,
      String purpose) {

    /** A grant given without a purpose. */
    public NewGrant(
        String assetFqn,
        UUID principalId,
        Instant validFrom,
        Instant validUntil,
        String reason,
        String grantedBy) {
      this(assetFqn, principalId, validFrom, validUntil, reason, grantedBy, null);
    }
  }

  /** Where a grant being written comes from; the purpose is checked differently for each. */
  private enum Origin {
    DIRECT,
    REQUEST,
    EDIT
  }

  /**
   * How far a browser's clock may run ahead of the server's before a grant
   * computed there as "N days from now" reads as longer than N days here.
   */
  private static final Duration CLOCK_SLACK = Duration.ofMinutes(10);

  /**
   * Creates a grant and records it.
   *
   * <p>The insert and the audit row go in one transaction: a grant that exists
   * without a trail is exactly the row an access review cannot account for.
   */
  public StoredGrant grant(NewGrant request) {
    StoredGrant created =
        jdbi.inTransaction(handle -> insert(handle, request, null, Origin.DIRECT));
    announce(created);
    return created;
  }

  /**
   * Creates the grant an approved access request produces, inside the caller's
   * transaction.
   *
   * <p>The caller's transaction rather than one of its own, because the request
   * turning APPROVED and the grant existing are one fact: a grant whose request
   * still reads PENDING would be approved a second time, and a request marked
   * APPROVED with no grant behind it tells the requester they have access they
   * do not. Nothing is announced here -- the caller does that with {@link
   * #announce} once the transaction has committed, so the decision cache is
   * never cleared for a grant that then rolls back.
   */
  public StoredGrant grantForRequest(Handle handle, NewGrant request, UUID requestId) {
    if (requestId == null) {
      throw new IllegalArgumentException("a grant from a request must name the request");
    }
    return insert(handle, request, requestId, Origin.REQUEST);
  }

  /** Tells every listener that a grant now exists; see {@link #grantForRequest}. */
  public void announce(StoredGrant created) {
    LOG.info(
        "Grant {} on {} to {} until {} by {}{}",
        created.id(),
        created.assetFqn(),
        created.username(),
        created.validUntil() == null ? "(no expiry)" : created.validUntil(),
        created.grantedBy(),
        created.requestId() == null ? "" : " (request " + created.requestId() + ")");
    changes.fire("grant " + created.id() + " on " + created.assetFqn());
  }

  private StoredGrant insert(Handle handle, NewGrant request, UUID requestId, Origin origin) {
    if (request.reason() == null || request.reason().isBlank()) {
      throw new IllegalArgumentException("a grant must say why it was given");
    }
    Instant from = request.validFrom() == null ? Instant.now() : request.validFrom();
    if (request.validUntil() != null && !request.validUntil().isAfter(from)) {
      throw new IllegalArgumentException("a grant must end after it starts");
    }
    // The rule amend already keeps. A window wholly in the past opens nothing,
    // yet it would sit in the trail as access given, and the expiry job would
    // then write a second line saying it lapsed.
    if (request.validUntil() != null && !request.validUntil().isAfter(Instant.now())) {
      throw new IllegalArgumentException(
          "the end is already past; a grant has to end in the future");
    }
    String purpose = checkedPurpose(handle, request, from, origin);

    UUID id =
        handle
            .createQuery(
                """
                INSERT INTO access_grant
                  (asset_fqn, principal_id, source, request_id, valid_from,
                   valid_until, reason, granted_by, purpose)
                VALUES
                  (:fqn, :principal, :source, :request, :from, :until, :reason, :by,
                   :purpose)
                RETURNING id
                """)
            .bind("fqn", request.assetFqn())
            .bind("source", requestId == null ? "manual" : "request")
            .bind("request", requestId)
            .bind("principal", request.principalId())
            .bind("from", from)
            .bind("until", request.validUntil())
            .bind("reason", request.reason())
            .bind("by", request.grantedBy())
            .bind("purpose", purpose)
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
  }

  /**
   * The purpose a grant is stored with, as the register spells its key (FR-21).
   *
   * <p>A grant given directly must name a purpose the register lists and has
   * not retired, as a request must. A grant from a request takes the request's
   * purpose, checked when it was asked for: one the register never listed -- a
   * template's own word from before the register -- is kept as it is, but one
   * retired since is refused, because granting now would be using it now. An
   * edit keeps whatever the grant already had, retired or not; what happens to
   * those grants is an access review's question, not an edit's.
   *
   * <p>Wherever the register caps the days, the grant has to end, and within
   * that many days of its start. It is the limit a request already obeys, held
   * on the grant itself, so it cannot be had by asking an owner directly or by
   * extending the grant afterwards.
   */
  private static String checkedPurpose(
      Handle handle, NewGrant request, Instant from, Origin origin) {
    String asked = request.purpose() == null ? null : request.purpose().strip();
    if (asked == null || asked.isEmpty()) {
      return null;
    }
    Optional<PurposeStore.Purpose> listed = PurposeStore.find(handle, asked);
    if (listed.isEmpty()) {
      if (origin == Origin.DIRECT) {
        throw new IllegalArgumentException(
            "\"" + asked + "\" is not in the register of purposes; choose one that is");
      }
      return asked;
    }
    PurposeStore.Purpose purpose = listed.get();
    if (!purpose.active() && origin != Origin.EDIT) {
      throw new IllegalArgumentException(
          "The purpose " + purpose.name() + " was retired, so access cannot be given for it"
              + (origin == Origin.REQUEST
                  ? " any more; decline the request, or reinstate the purpose first"
                  : " any more; choose another"));
    }
    Integer most = purpose.maxDays();
    if (most != null) {
      String limit = "Access for " + purpose.name() + " lasts at most " + most + " days";
      if (request.validUntil() == null) {
        throw new IllegalArgumentException(limit + "; give the grant an end");
      }
      if (Duration.between(from, request.validUntil())
              .compareTo(Duration.ofDays(most).plus(CLOCK_SLACK))
          > 0) {
        throw new IllegalArgumentException(
            limit + (origin == Origin.EDIT ? " from when the grant started" : ""));
      }
    }
    return purpose.key();
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
   * Changes a grant's window or reason by replacing it.
   *
   * <p>The row is not updated in place. A grant is also the record of what was
   * given, and "who had access last March" has to stay answerable after
   * somebody extends the window in April -- so the old row is revoked, saying
   * why, and a new one with the same person, table and request is inserted in
   * the same transaction. Nobody is without the grant in between, and the
   * trail reads as what happened: this was replaced by that, by whom, and why.
   *
   * <p>Returns empty when the grant does not exist or is already revoked, as
   * {@link #revoke} does; an edit of a tombstone would bring it back.
   */
  public Optional<StoredGrant> amend(
      UUID id, Instant validFrom, Instant validUntil, String reason, String actor) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("say why the grant is being changed");
    }
    if (validUntil != null && !validUntil.isAfter(Instant.now())) {
      throw new IllegalArgumentException(
          "the new end is already past; to end a grant now, revoke it");
    }
    Optional<StoredGrant> amended =
        jdbi.inTransaction(
            handle -> {
              Optional<StoredGrant> current =
                  handle
                      .createQuery(
                          """
                          SELECT g.*, p.username, p.display_name, p.principal_type,
                                 p.source AS principal_source
                          FROM access_grant g
                          JOIN principal p ON p.id = g.principal_id
                          WHERE g.id = :id AND g.revoked_at IS NULL
                          FOR UPDATE OF g
                          """)
                      .bind("id", id)
                      .map(GrantStore::map)
                      .findOne();
              if (current.isEmpty()) {
                return Optional.<StoredGrant>empty();
              }
              StoredGrant old = current.get();
              // A start already past stays where it was: moving it would say the
              // person had no access over a stretch in which they did.
              Instant from =
                  validFrom == null || !old.validFrom().isAfter(Instant.now())
                      ? old.validFrom()
                      : validFrom;
              String why = "Replaced by an edit: " + reason.trim();
              handle
                  .createUpdate(
                      """
                      UPDATE access_grant
                      SET revoked_at = now(), revoked_by = :by, revoke_reason = :why
                      WHERE id = :id
                      """)
                  .bind("id", id)
                  .bind("by", actor)
                  .bind("why", why)
                  .execute();
              StoredGrant retired =
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
              audit(handle, "REVOKE", actor, retired, why);
              return Optional.of(
                  insert(
                      handle,
                      new NewGrant(
                          old.assetFqn(),
                          old.principalId(),
                          from,
                          validUntil,
                          reason.trim(),
                          actor,
                          old.purpose()),
                      old.requestId(),
                      Origin.EDIT));
            });
    amended.ifPresent(this::announce);
    return amended;
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
               valid_from, valid_until, reason, purpose)
            VALUES
              (:actor, :action, :grant, :fqn, :username, :source, :from, :until, :why,
               :purpose)
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
        .bind("purpose", grant.purpose())
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
        rs.getString("revoke_reason"),
        rs.getString("purpose"));
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
