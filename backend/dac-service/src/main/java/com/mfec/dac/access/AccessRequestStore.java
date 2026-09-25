package com.mfec.dac.access;

import com.mfec.dac.engine.Principal;
import com.mfec.dac.policy.PrincipalLoader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Asking an asset's owner for access, and the owner's answer.
 *
 * <p>The first slice of the Phase 2 workflow, built now because the query page
 * refuses people and a refusal that names no way forward sends them to chat
 * instead. It is deliberately thin: one asset, one requester, one decision, and
 * approval produces an ordinary grant ({@code source = 'request'}) so that the
 * engine, the composition rules, expiry and the asset page all keep working
 * without knowing requests exist.
 *
 * <h2>Who decides</h2>
 *
 * <p>The asset's owners as OpenMetadata records them -- a user by name or
 * email, or any member of an owning team, matched exactly the way the engine
 * matches {@code assetOwner: true} -- and platform administrators, who can
 * always decide so that a table nobody owns is not a table nobody can ever be
 * let into. Nobody decides their own request, administrators included: that is
 * the separation of duty FR-2.6 asks for, and an administrator who needs access
 * can ask a colleague like anybody else.
 *
 * <h2>What approval cannot do</h2>
 *
 * <p>Whatever a grant cannot do (V11). The grant enters at the TABLE layer and
 * composes by intersection, so approving opens a table nothing else speaks to
 * and never passes a DENY or a higher layer that refuses the requester. The
 * query page asks the engine before offering the button at all.
 */
public class AccessRequestStore {

  private static final Logger LOG = LoggerFactory.getLogger(AccessRequestStore.class);

  /** The longest a request may ask for, and so the longest an approval may give. */
  public static final int MAX_DAYS = 365;

  private final Jdbi jdbi;
  private final GrantStore grants;
  private final PrincipalLoader principals;

  public AccessRequestStore(Jdbi jdbi, GrantStore grants, PrincipalLoader principals) {
    this.jdbi = jdbi;
    this.grants = grants;
    this.principals = principals;
  }

  /** Why a request could not be made or moved, and which HTTP answer that is. */
  public static class RequestException extends RuntimeException {

    /** How the resource should report it. */
    public enum Kind {
      INVALID,
      NOT_FOUND,
      CONFLICT,
      FORBIDDEN
    }

    private final Kind kind;

    public RequestException(Kind kind, String message) {
      super(message);
      this.kind = kind;
    }

    public Kind kind() {
      return kind;
    }
  }

  /** Somebody who can decide a request on this asset, as OpenMetadata names them. */
  public record Approver(String type, String name, boolean direct, String inheritedFrom) {}

  /**
   * A request as stored.
   *
   * @param approvers the asset's owners today, not when the request was made:
   *     ownership moves, and the inbox follows the current owner
   * @param mayDecide whether the person reading this may approve or reject it;
   *     filled per reader, never stored
   */
  public record StoredRequest(
      UUID id,
      String assetFqn,
      UUID requesterId,
      String requesterUsername,
      UUID dataSourceId,
      String reason,
      String purpose,
      Integer requestedDays,
      String attemptedSql,
      String deniedBy,
      String status,
      Instant createdAt,
      String decidedBy,
      Instant decidedAt,
      String decisionNote,
      UUID grantId,
      List<Approver> approvers,
      boolean mayDecide,
      boolean stranded) {

    StoredRequest seenBy(List<Approver> owners, boolean decides, boolean nobodyElse) {
      return new StoredRequest(
          id, assetFqn, requesterId, requesterUsername, dataSourceId, reason, purpose,
          requestedDays, attemptedSql, deniedBy, status, createdAt, decidedBy, decidedAt,
          decisionNote, grantId, owners, decides, nobodyElse);
    }

    public boolean pending() {
      return "PENDING".equals(status);
    }
  }

  /** What a requester sends. */
  public record NewRequest(
      String assetFqn,
      UUID requesterId,
      String requesterUsername,
      UUID dataSourceId,
      String reason,
      String purpose,
      Integer requestedDays,
      String attemptedSql,
      String deniedBy) {}

  /**
   * The person reading or acting, reduced to what deciding needs.
   *
   * @param username as they signed in
   * @param platformAdmin whether they hold PLATFORM_ADMIN
   */
  public record Actor(String username, boolean platformAdmin) {}

  // --------------------------------------------------------------- reading

  /** The asset's owners, direct and inherited, as the crawl last saw them. */
  public List<Approver> approversFor(String assetFqn) {
    return jdbi.withHandle(handle -> owners(handle, List.of(assetFqn)).getOrDefault(assetFqn, List.of()));
  }

  /**
   * Whether a request from this person on this asset would wait for nobody.
   *
   * <p>Nobody decides their own request, so an owner who is the requester does
   * not count, and neither does an administrator who is the requester. When
   * that leaves no owner and no other enabled administrator, the request sits
   * pending forever while the page says "an administrator decides" -- this is
   * the flag that lets the page say so instead.
   */
  public boolean nobodyElseDecides(String requesterUsername, String assetFqn) {
    return jdbi.withHandle(
        handle ->
            nobodyElseDecides(
                handle,
                requesterUsername,
                owners(handle, List.of(assetFqn)).getOrDefault(assetFqn, List.of())));
  }

  private boolean nobodyElseDecides(
      Handle handle, String requesterUsername, List<Approver> ofAsset) {
    Principal requester = principals.find(handle, requesterUsername).orElse(null);
    for (Approver owner : ofAsset) {
      // A team may hold other members; only a user owner can be the requester.
      boolean self =
          "user".equalsIgnoreCase(owner.type()) && requester != null && requester.is(owner.name());
      if (!self) {
        return false;
      }
    }
    int otherAdmins =
        handle
            .createQuery(
                """
                SELECT count(*) FROM app_role_assignment r
                JOIN principal p ON p.id = r.principal_id
                WHERE r.app_role = 'PLATFORM_ADMIN'
                  AND r.scope_fqn IS NULL
                  AND p.enabled = true
                  AND lower(p.username) <> lower(:who)
                """)
            .bind("who", requesterUsername)
            .mapTo(Integer.class)
            .one();
    return otherAdmins == 0;
  }

  /** The open request this person already has on this asset, if any. */
  public Optional<StoredRequest> openRequest(String assetFqn, String requesterUsername) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT * FROM access_request
                    WHERE asset_fqn = :fqn AND lower(requester_username) = lower(:who)
                      AND status = 'PENDING'
                    """)
                .bind("fqn", assetFqn)
                .bind("who", requesterUsername)
                .map(AccessRequestStore::map)
                .findOne());
  }

  /** What one person has asked for, newest first. */
  public List<StoredRequest> madeBy(Actor actor, int limit) {
    return jdbi.withHandle(
        handle -> {
          List<StoredRequest> rows =
              handle
                  .createQuery(
                      """
                      SELECT * FROM access_request
                      WHERE lower(requester_username) = lower(:who)
                      ORDER BY created_at DESC
                      LIMIT :limit
                      """)
                  .bind("who", actor.username())
                  .bind("limit", clamp(limit))
                  .map(AccessRequestStore::map)
                  .list();
          return annotate(handle, rows, actor);
        });
  }

  /**
   * The requests this person may decide.
   *
   * <p>Filtered after reading rather than in SQL because "may decide" is the
   * engine's owner match -- team membership walked transitively, users matched
   * by name or email -- and writing it a second time in SQL is how the inbox and
   * the approval check would one day disagree about who the owner is.
   *
   * @param status one status, or null for every status
   */
  public List<StoredRequest> decidableBy(Actor actor, String status, int limit) {
    String wanted = status == null || status.isBlank() ? null : status.trim().toUpperCase();
    if (wanted != null && !List.of("PENDING", "APPROVED", "REJECTED", "WITHDRAWN").contains(wanted)) {
      throw new RequestException(RequestException.Kind.INVALID, "Unknown status " + status);
    }
    return jdbi.withHandle(
        handle -> {
          List<StoredRequest> rows =
              handle
                  .createQuery(
                      """
                      SELECT * FROM access_request
                      WHERE (CAST(:status AS text) IS NULL OR status = :status)
                      ORDER BY (status = 'PENDING') DESC, created_at DESC
                      LIMIT 2000
                      """)
                  .bind("status", wanted)
                  .map(AccessRequestStore::map)
                  .list();
          List<StoredRequest> mine = new ArrayList<>();
          for (StoredRequest one : annotate(handle, rows, actor)) {
            // Somebody's own request is never in their inbox, even though they
            // can see it: it is on the "mine" list, and putting it here too
            // would put an Approve button beside it that the server refuses.
            if (one.mayDecide()) {
              mine.add(one);
            }
            if (mine.size() >= clamp(limit)) {
              break;
            }
          }
          return mine;
        });
  }

  /**
   * One request, for whoever is allowed to see it: the requester and the
   * people who may decide it.
   *
   * <p>Anybody else gets "not found" rather than "forbidden", because the
   * request carries the SQL somebody tried to run and the reason they gave,
   * and whether such a request exists is itself the first thing that leaks.
   */
  public StoredRequest find(UUID id, Actor actor) {
    return jdbi.withHandle(
        handle -> {
          StoredRequest row = load(handle, id, false);
          StoredRequest seen = annotate(handle, List.of(row), actor).get(0);
          if (!seen.mayDecide() && !seen.requesterUsername().equalsIgnoreCase(actor.username())) {
            throw notFound(id);
          }
          return seen;
        });
  }

  // --------------------------------------------------------------- writing

  /**
   * Opens a request.
   *
   * <p>One open request per person per asset: asking twice is the same question
   * and must not queue twice in the owner's inbox, so the second attempt is a
   * conflict that names the first.
   */
  public StoredRequest create(NewRequest request, Actor actor) {
    String fqn = request.assetFqn() == null ? "" : request.assetFqn().trim();
    if (fqn.isEmpty()) {
      throw new RequestException(RequestException.Kind.INVALID, "Name the table you are asking for");
    }
    if (request.reason() == null || request.reason().isBlank()) {
      throw new RequestException(
          RequestException.Kind.INVALID,
          "Say why you need it; the owner decides on what you write here");
    }
    if (request.requestedDays() != null
        && (request.requestedDays() < 1 || request.requestedDays() > MAX_DAYS)) {
      throw new RequestException(
          RequestException.Kind.INVALID, "Ask for between 1 and " + MAX_DAYS + " days, or leave it open");
    }
    if (request.requesterId() == null) {
      throw new RequestException(RequestException.Kind.INVALID, "No requester on this request");
    }

    StoredRequest created;
    try {
      created = insert(request, actor, fqn);
    } catch (UnableToExecuteStatementException e) {
      // Two clicks that both passed the lookup below before either committed:
      // the partial unique index lets one through and refuses the other, and
      // the other is the same "already open" answer, not a server error.
      if (String.valueOf(e.getMessage()).contains("access_request_one_open_idx")) {
        throw new RequestException(
            RequestException.Kind.CONFLICT, "You already have an open request for " + fqn);
      }
      throw e;
    }
    LOG.info(
        "Access request {} on {} by {}", created.id(), created.assetFqn(), created.requesterUsername());
    return created;
  }

  private StoredRequest insert(NewRequest request, Actor actor, String fqn) {
    return jdbi.inTransaction(
            handle -> {
              boolean exists =
                  handle
                      .createQuery(
                          """
                          SELECT count(*) > 0 FROM asset
                          WHERE fqn = :fqn AND is_current AND asset_type IN ('TABLE', 'VIEW')
                          """)
                      .bind("fqn", fqn)
                      .mapTo(Boolean.class)
                      .one();
              if (!exists) {
                throw new RequestException(
                    RequestException.Kind.INVALID,
                    fqn + " is not a table or view in the catalog, so there is nothing to grant");
              }

              Optional<UUID> open =
                  handle
                      .createQuery(
                          """
                          SELECT id FROM access_request
                          WHERE asset_fqn = :fqn AND requester_id = :who AND status = 'PENDING'
                          """)
                      .bind("fqn", fqn)
                      .bind("who", request.requesterId())
                      .mapTo(UUID.class)
                      .findOne();
              if (open.isPresent()) {
                throw new RequestException(
                    RequestException.Kind.CONFLICT,
                    "You already have an open request for " + fqn + " (" + open.get() + ")");
              }

              UUID id =
                  handle
                      .createQuery(
                          """
                          INSERT INTO access_request
                            (asset_fqn, requester_id, requester_username, data_source_id,
                             reason, purpose, requested_days, attempted_sql, denied_by)
                          VALUES
                            (:fqn, :who, :username, :source, :reason, :purpose, :days,
                             :sql, :deniedBy)
                          RETURNING id
                          """)
                      .bind("fqn", fqn)
                      .bind("who", request.requesterId())
                      .bind("username", request.requesterUsername())
                      .bind("source", request.dataSourceId())
                      .bind("reason", request.reason().trim())
                      .bind("purpose", blankToNull(request.purpose()))
                      .bind("days", request.requestedDays())
                      .bind("sql", truncate(request.attemptedSql(), 20_000))
                      .bind("deniedBy", truncate(request.deniedBy(), 2_000))
                      .mapTo(UUID.class)
                      .one();
              StoredRequest row = load(handle, id, false);
              audit(handle, "REQUEST", request.requesterUsername(), row, null, row.reason());
              return annotate(handle, List.of(row), actor).get(0);
            });
  }

  /**
   * Approves a request and grants what it asked for.
   *
   * <p>The row is locked first, so two owners approving at once produce one
   * grant and one "already decided", not two grants.
   *
   * @param days how long to grant for; null means what was asked. An owner may
   *     shorten a request and may not lengthen it: the requester asked for a
   *     week, and a year they never asked for is access nobody requested
   */
  public StoredRequest approve(UUID id, Actor actor, Integer days, String note) {
    GrantStore.StoredGrant[] made = new GrantStore.StoredGrant[1];
    StoredRequest decided =
        jdbi.inTransaction(
            handle -> {
              StoredRequest row = decidable(handle, id, actor);
              Integer asked = row.requestedDays();
              Integer granted = days == null ? asked : days;
              if (granted != null && (granted < 1 || granted > MAX_DAYS)) {
                throw new RequestException(
                    RequestException.Kind.INVALID, "Grant for between 1 and " + MAX_DAYS + " days");
              }
              if (asked != null && (granted == null || granted > asked)) {
                throw new RequestException(
                    RequestException.Kind.INVALID,
                    "The request asked for " + asked + " days; an approval can shorten that, not extend it");
              }
              if (row.requesterId() == null) {
                throw new RequestException(
                    RequestException.Kind.CONFLICT,
                    row.requesterUsername() + " is no longer in the directory; nothing to grant to");
              }

              Instant now = Instant.now();
              Instant until = granted == null ? null : now.plus(Duration.ofDays(granted));
              String why =
                  "Access request " + row.id() + ": " + row.reason()
                      + (blankToNull(note) == null ? "" : " — approved: " + note.trim());
              made[0] =
                  grants.grantForRequest(
                      handle,
                      new GrantStore.NewGrant(
                          row.assetFqn(), row.requesterId(), now, until, why, actor.username()),
                      row.id());

              handle
                  .createUpdate(
                      """
                      UPDATE access_request
                         SET status = 'APPROVED', decided_by = :by, decided_at = :at,
                             decision_note = :note, grant_id = :grant
                       WHERE id = :id
                      """)
                  .bind("by", actor.username())
                  .bind("at", now)
                  .bind("note", blankToNull(note))
                  .bind("grant", made[0].id())
                  .bind("id", row.id())
                  .execute();
              StoredRequest after = load(handle, id, false);
              audit(handle, "APPROVE", actor.username(), after, made[0].id(), blankToNull(note));
              return annotate(handle, List.of(after), actor).get(0);
            });
    // After the commit, never inside it: clearing the decision cache for a
    // grant that then rolls back would be harmless, but announcing a grant the
    // requester cannot yet read would not be.
    grants.announce(made[0]);
    LOG.info("Access request {} approved by {} -> grant {}", id, actor.username(), made[0].id());
    return decided;
  }

  /** Rejects a request. A reason is required, because the requester reads it. */
  public StoredRequest reject(UUID id, Actor actor, String note) {
    if (note == null || note.isBlank()) {
      throw new RequestException(
          RequestException.Kind.INVALID,
          "Say why; the requester reads this and it is the only answer they get");
    }
    return jdbi.inTransaction(
        handle -> {
          StoredRequest row = decidable(handle, id, actor);
          handle
              .createUpdate(
                  """
                  UPDATE access_request
                     SET status = 'REJECTED', decided_by = :by, decided_at = now(),
                         decision_note = :note
                   WHERE id = :id
                  """)
              .bind("by", actor.username())
              .bind("note", note.trim())
              .bind("id", row.id())
              .execute();
          StoredRequest after = load(handle, id, false);
          audit(handle, "REJECT", actor.username(), after, null, note.trim());
          return annotate(handle, List.of(after), actor).get(0);
        });
  }

  /** Takes a request back. Only the person who made it can, and only while it is open. */
  public StoredRequest withdraw(UUID id, Actor actor) {
    return jdbi.inTransaction(
        handle -> {
          StoredRequest row = load(handle, id, true);
          if (!row.requesterUsername().equalsIgnoreCase(actor.username())) {
            // The same answer a stranger gets from find(): whether somebody
            // else has asked for something is not the caller's to learn.
            throw notFound(id);
          }
          if (!row.pending()) {
            throw new RequestException(
                RequestException.Kind.CONFLICT, "This request was already " + row.status().toLowerCase());
          }
          handle
              .createUpdate(
                  """
                  UPDATE access_request
                     SET status = 'WITHDRAWN', decided_by = :by, decided_at = now()
                   WHERE id = :id
                  """)
              .bind("by", actor.username())
              .bind("id", row.id())
              .execute();
          StoredRequest after = load(handle, id, false);
          audit(handle, "WITHDRAW", actor.username(), after, null, null);
          return annotate(handle, List.of(after), actor).get(0);
        });
  }

  // --------------------------------------------------------------- deciding

  /**
   * Locks the request and checks this actor may decide it now.
   *
   * <p>The checks run in the order that gives the most useful answer: somebody
   * who cannot see a request is told it does not exist, the requester is told
   * why they cannot approve their own, and an owner arriving second is told it
   * was already decided.
   */
  private StoredRequest decidable(Handle handle, UUID id, Actor actor) {
    StoredRequest row = load(handle, id, true);
    boolean own = row.requesterUsername().equalsIgnoreCase(actor.username());
    if (own) {
      throw new RequestException(
          RequestException.Kind.FORBIDDEN,
          "Nobody decides their own request; the table's owner or an administrator has to");
    }
    if (!mayDecide(handle, actor, row.assetFqn(), owners(handle, List.of(row.assetFqn())))) {
      throw notFound(id);
    }
    if (!row.pending()) {
      throw new RequestException(
          RequestException.Kind.CONFLICT,
          "This request was already " + row.status().toLowerCase()
              + (row.decidedBy() == null ? "" : " by " + row.decidedBy()));
    }
    return row;
  }

  private boolean mayDecide(
      Handle handle, Actor actor, String assetFqn, Map<String, List<Approver>> owners) {
    if (actor.platformAdmin()) {
      return true;
    }
    List<Approver> ofAsset = owners.getOrDefault(assetFqn, List.of());
    if (ofAsset.isEmpty()) {
      return false;
    }
    Principal who = principals.find(handle, actor.username()).orElse(null);
    if (who == null) {
      return false;
    }
    for (Approver owner : ofAsset) {
      // A user owner by name or email, a team owner through membership -- and
      // each only as what it is. Matching a name against both would let a user
      // called "finance" decide for tables the Finance team owns, or a member
      // of a team called "alice" decide for tables Alice owns.
      boolean match =
          "team".equalsIgnoreCase(owner.type())
              ? who.hasTeam(owner.name())
              : "user".equalsIgnoreCase(owner.type()) && who.is(owner.name());
      if (match) {
        return true;
      }
    }
    return false;
  }

  /** Fills the per-reader fields: the asset's owners today, and whether this reader decides. */
  private List<StoredRequest> annotate(Handle handle, List<StoredRequest> rows, Actor actor) {
    if (rows.isEmpty()) {
      return rows;
    }
    List<String> assets = rows.stream().map(StoredRequest::assetFqn).distinct().toList();
    Map<String, List<Approver>> owners = owners(handle, assets);
    Map<String, Boolean> decides = new LinkedHashMap<>();
    List<StoredRequest> out = new ArrayList<>(rows.size());
    for (StoredRequest row : rows) {
      boolean own = row.requesterUsername().equalsIgnoreCase(actor.username());
      boolean may =
          !own
              && decides.computeIfAbsent(
                  row.assetFqn(), fqn -> mayDecide(handle, actor, fqn, owners));
      List<Approver> ofAsset = owners.getOrDefault(row.assetFqn(), List.of());
      boolean stranded = row.pending() && nobodyElseDecides(handle, row.requesterUsername(), ofAsset);
      out.add(row.seenBy(ofAsset, may, stranded));
    }
    return out;
  }

  private static Map<String, List<Approver>> owners(Handle handle, List<String> assets) {
    Map<String, List<Approver>> out = new LinkedHashMap<>();
    if (assets.isEmpty()) {
      return out;
    }
    handle
        .createQuery(
            """
            SELECT target_fqn, owner_type, owner_name, is_direct, inherited_from
            FROM asset_owner
            WHERE target_fqn IN (<assets>)
            ORDER BY is_direct DESC, owner_type DESC, owner_name
            """)
        .bindList("assets", assets)
        .map(
            (rs, ctx) ->
                Map.entry(
                    rs.getString("target_fqn"),
                    new Approver(
                        rs.getString("owner_type"),
                        rs.getString("owner_name"),
                        rs.getBoolean("is_direct"),
                        rs.getString("inherited_from"))))
        .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
    return out;
  }

  // ---------------------------------------------------------------- notices

  /**
   * One thing that happened to a request, told to somebody who should hear it.
   *
   * @param kind {@code REQUESTED} or {@code WITHDRAWN} for someone who decides
   *     the table; {@code APPROVED} or {@code REJECTED} for the requester
   * @param side {@code INBOX} when the reader decides, {@code MINE} when it is
   *     the reader's own request -- which tab of the requests page it opens
   */
  public record Notice(
      long id,
      String kind,
      String side,
      UUID requestId,
      String assetFqn,
      String actor,
      String requesterUsername,
      String note,
      Instant occurredAt,
      boolean unseen) {}

  /**
   * What the header needs: the newest notices, how many are new, and the two
   * counts the requests page puts on its tabs.
   */
  public record Notices(
      int unseen, int inboxPending, int minePending, Instant seenAt, List<Notice> items) {}

  /** How far back the bell looks: enough to count "new" honestly, not the whole history. */
  static final int NOTICE_WINDOW = 500;

  /**
   * What this person should hear about, newest first.
   *
   * <p>Built from {@code audit_access_request}, not stored separately. The
   * reader hears about requests for tables they may decide <em>today</em> --
   * the owner match is {@link #mayDecide}, the same one the inbox and approval
   * use -- and about answers to their own requests. Nobody is told about what
   * they did themselves.
   */
  public Notices notices(Actor actor, int limit) {
    int keep = limit <= 0 ? 20 : Math.min(limit, 50);
    return jdbi.withHandle(
        handle -> {
          Instant seenAt =
              handle
                  .createQuery(
                      "SELECT seen_at FROM access_request_notice_seen WHERE username = lower(:who)")
                  .bind("who", actor.username())
                  .map((rs, ctx) -> instant(rs, "seen_at"))
                  .findOne()
                  .orElse(null);

          List<Notice> all = new ArrayList<>();
          all.addAll(
              handle
                  .createQuery(
                      """
                      SELECT * FROM audit_access_request
                      WHERE lower(requester_username) = lower(:who)
                        AND action IN ('APPROVE', 'REJECT')
                        AND lower(actor) <> lower(:who)
                      ORDER BY occurred_at DESC
                      LIMIT :window
                      """)
                  .bind("who", actor.username())
                  .bind("window", NOTICE_WINDOW)
                  .map((rs, ctx) -> notice(rs, "MINE", seenAt))
                  .list());

          List<Notice> asked =
              handle
                  .createQuery(
                      """
                      SELECT * FROM audit_access_request
                      WHERE action IN ('REQUEST', 'WITHDRAW')
                        AND lower(requester_username) <> lower(:who)
                      ORDER BY occurred_at DESC
                      LIMIT :window
                      """)
                  .bind("who", actor.username())
                  .bind("window", NOTICE_WINDOW)
                  .map((rs, ctx) -> notice(rs, "INBOX", seenAt))
                  .list();
          Map<String, List<Approver>> owners =
              owners(handle, asked.stream().map(Notice::assetFqn).distinct().toList());
          Map<String, Boolean> decides = new LinkedHashMap<>();
          for (Notice one : asked) {
            if (decides.computeIfAbsent(
                one.assetFqn(), fqn -> mayDecide(handle, actor, fqn, owners))) {
              all.add(one);
            }
          }

          // Newest first; the audit id breaks a tie in the same instant.
          all.sort(
              java.util.Comparator.comparing(Notice::occurredAt)
                  .thenComparingLong(Notice::id)
                  .reversed());
          int unseen = (int) all.stream().filter(Notice::unseen).count();

          int minePending =
              handle
                  .createQuery(
                      """
                      SELECT count(*) FROM access_request
                      WHERE lower(requester_username) = lower(:who) AND status = 'PENDING'
                      """)
                  .bind("who", actor.username())
                  .mapTo(Integer.class)
                  .one();
          int inboxPending = pendingFor(handle, actor);

          return new Notices(
              unseen,
              inboxPending,
              minePending,
              seenAt,
              List.copyOf(all.subList(0, Math.min(keep, all.size()))));
        });
  }

  /** Everything up to now is read. The bell stops counting it; nothing is deleted. */
  public void markNoticesSeen(Actor actor) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO access_request_notice_seen (username, seen_at)
                    VALUES (lower(:who), now())
                    ON CONFLICT (username) DO UPDATE SET seen_at = EXCLUDED.seen_at
                    """)
                .bind("who", actor.username())
                .execute());
  }

  /** Pending requests this person may decide, counted the way the inbox lists them. */
  private int pendingFor(Handle handle, Actor actor) {
    List<StoredRequest> pending =
        handle
            .createQuery(
                """
                SELECT * FROM access_request
                WHERE status = 'PENDING' AND lower(requester_username) <> lower(:who)
                LIMIT 2000
                """)
            .bind("who", actor.username())
            .map(AccessRequestStore::map)
            .list();
    return (int) annotate(handle, pending, actor).stream().filter(StoredRequest::mayDecide).count();
  }

  private static Notice notice(ResultSet rs, String side, Instant seenAt) throws SQLException {
    Instant at = instant(rs, "occurred_at");
    String kind =
        switch (rs.getString("action")) {
          case "REQUEST" -> "REQUESTED";
          case "WITHDRAW" -> "WITHDRAWN";
          case "APPROVE" -> "APPROVED";
          default -> "REJECTED";
        };
    return new Notice(
        rs.getLong("id"),
        kind,
        side,
        UUID.fromString(rs.getString("request_id")),
        rs.getString("asset_fqn"),
        rs.getString("actor"),
        rs.getString("requester_username"),
        rs.getString("note"),
        at,
        seenAt == null || at.isAfter(seenAt));
  }

  // --------------------------------------------------------------- plumbing

  private static StoredRequest load(Handle handle, UUID id, boolean lock) {
    return handle
        .createQuery("SELECT * FROM access_request WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
        .bind("id", id)
        .map(AccessRequestStore::map)
        .findOne()
        .orElseThrow(() -> notFound(id));
  }

  private static RequestException notFound(UUID id) {
    return new RequestException(RequestException.Kind.NOT_FOUND, "No access request " + id);
  }

  private static void audit(
      Handle handle, String action, String actor, StoredRequest row, UUID grantId, String note) {
    handle
        .createUpdate(
            """
            INSERT INTO audit_access_request
              (actor, action, request_id, asset_fqn, requester_username, grant_id, note)
            VALUES (:actor, :action, :request, :fqn, :requester, :grant, :note)
            """)
        .bind("actor", actor)
        .bind("action", action)
        .bind("request", row.id())
        .bind("fqn", row.assetFqn())
        .bind("requester", row.requesterUsername())
        .bind("grant", grantId)
        .bind("note", note)
        .execute();
  }

  private static StoredRequest map(ResultSet rs, org.jdbi.v3.core.statement.StatementContext ctx)
      throws SQLException {
    int days = rs.getInt("requested_days");
    Integer requestedDays = rs.wasNull() ? null : days;
    return new StoredRequest(
        UUID.fromString(rs.getString("id")),
        rs.getString("asset_fqn"),
        uuidOrNull(rs.getString("requester_id")),
        rs.getString("requester_username"),
        uuidOrNull(rs.getString("data_source_id")),
        rs.getString("reason"),
        rs.getString("purpose"),
        requestedDays,
        rs.getString("attempted_sql"),
        rs.getString("denied_by"),
        rs.getString("status"),
        instant(rs, "created_at"),
        rs.getString("decided_by"),
        instant(rs, "decided_at"),
        rs.getString("decision_note"),
        uuidOrNull(rs.getString("grant_id")),
        List.of(),
        false,
        false);
  }

  private static UUID uuidOrNull(String value) {
    return value == null ? null : UUID.fromString(value);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    java.sql.Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static int clamp(int limit) {
    return limit <= 0 ? 100 : Math.min(limit, 500);
  }
}
