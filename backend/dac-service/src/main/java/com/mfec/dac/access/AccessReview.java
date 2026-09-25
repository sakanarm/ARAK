package com.mfec.dac.access;

import com.mfec.dac.engine.AssetContext;
import com.mfec.dac.engine.ColumnContext;
import com.mfec.dac.engine.FacetValue;
import com.mfec.dac.identity.PrincipalQuery;
import com.mfec.dac.policy.AssetContextLoader;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.policy.PolicyStore;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.PrincipalMatch;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;

/**
 * What somebody deciding or configuring an access request needs to know before
 * they answer it (M9 slice 2b).
 *
 * <p>Who is asking, what a grant would actually let them read -- column by
 * column, with the masks and row filters that would still apply -- how risky
 * that is, what stands in the way, and what the reviewer might do instead.
 *
 * <h2>Nothing here acts</h2>
 *
 * <p>Every answer is a question put to the engine about a world that does not
 * exist yet: "if this were granted", "if this policy were in force". None of it
 * writes. A suggestion to write a policy comes back as a draft document for a
 * person to open in the builder, save as a DRAFT and activate through the
 * policy lifecycle like any other; a review that could activate a policy would
 * be a way around the review that activation already has.
 *
 * <h2>Who may read it</h2>
 *
 * <p>Whoever the request is visible to, except the requester. A stranger gets
 * the same "no such request" the request itself gives them. The requester is
 * refused outright: the review describes the policies that govern them and the
 * reasoning of the people deciding, and is not a way to learn which policy to
 * argue with before anybody has answered.
 */
public class AccessReview {

  /** Longer than this, with sensitive columns open in clear, is worth a second look. */
  static final int LONG_DAYS = 30;

  /** Longer than this is long regardless of what is in the table. */
  static final int VERY_LONG_DAYS = 90;

  /** The shorter grant suggested when a long one would open sensitive columns in clear. */
  static final int SHORTER_DAYS = 7;

  /** How many peers in one group must already hold a grant before a policy is worth suggesting. */
  static final int PEERS_FOR_POLICY = 2;

  /** How far back the requester's request history is counted. */
  static final Duration HISTORY_WINDOW = Duration.ofDays(90);

  private final Jdbi jdbi;
  private final DecisionService decisions;
  private final AccessRequestStore requests;
  private final PolicyStore policies;
  private final PrincipalQuery people;
  private final AssetContextLoader contexts;
  private final GrantStore grants;

  public AccessReview(
      Jdbi jdbi,
      DecisionService decisions,
      AccessRequestStore requests,
      PolicyStore policies,
      PrincipalQuery people,
      AssetContextLoader contexts,
      GrantStore grants) {
    this.jdbi = jdbi;
    this.decisions = decisions;
    this.requests = requests;
    this.policies = policies;
    this.people = people;
    this.contexts = contexts;
    this.grants = grants;
  }

  // --------------------------------------------------------------- the answer

  /**
   * The whole review.
   *
   * @param now what the requester can read today
   * @param ifGranted what a grant from this request would let them read
   * @param ifPolicy what they could read with the chosen policy in force; null
   *     unless a policy was named
   * @param policy the chosen policy as it stands; null unless one was named
   */
  public record Review(
      UUID requestId,
      String assetFqn,
      String status,
      Integer requestedDays,
      String purpose,
      Instant reviewedAt,
      boolean addressKnown,
      Requester requester,
      TableFacts table,
      Access now,
      Access ifGranted,
      Access ifPolicy,
      PolicyCheck policy,
      Risk risk,
      List<Conflict> conflicts,
      List<Suggestion> suggestions,
      Recommendation recommendation) {}

  /**
   * The person asking.
   *
   * @param known a principal of that name exists; false when they have been
   *     removed since they asked
   * @param grantsHere live grants reaching them on this table, directly or
   *     through a group
   * @param grantsElsewhere how many other tables they hold a live grant on.
   *     Counted, not listed: the review is for this table, and a list would
   *     tell an owner of one table what somebody reads everywhere else.
   * @param earlier their other requests for this table
   * @param recentRequests requests they made in the last 90 days, any table
   * @param recentRejected of those, how many were turned down
   */
  public record Requester(
      String username,
      String displayName,
      String email,
      boolean known,
      boolean enabled,
      String source,
      List<String> appRoles,
      List<Membership> memberships,
      List<Attribute> attributes,
      List<HeldGrant> grantsHere,
      int grantsElsewhere,
      List<PastRequest> earlier,
      int recentRequests,
      int recentRejected) {}

  /** A group or OpenMetadata team the requester belongs to directly. */
  public record Membership(
      String id, String name, String displayName, String kind, String source) {}

  public record Attribute(String key, String value, String source) {}

  /** A live grant on this table that reaches the requester. */
  public record HeldGrant(
      UUID id, String grantedTo, boolean viaGroup, Instant validUntil, String grantedBy) {}

  /** One of the requester's other requests for this table. */
  public record PastRequest(
      UUID id,
      String status,
      Integer requestedDays,
      Instant createdAt,
      String decidedBy,
      String note,
      String fulfilment) {}

  /** The table as the decision sees it. */
  public record TableFacts(
      String fqn,
      boolean known,
      List<String> tiers,
      List<String> domains,
      List<AccessRequestStore.Approver> owners,
      int columns,
      int sensitiveColumns) {}

  /**
   * One reading of what the requester would see.
   *
   * @param blockedBy what refuses them, when {@code allowed} is false
   * @param columns every column of the table and what happens to it; only
   *     meaningful when {@code allowed}
   */
  public record Access(
      boolean allowed,
      String blockedBy,
      UUID blockedByPolicyId,
      List<ColumnFate> columns,
      List<RowFilter> rowFilters,
      List<Reason> reasons,
      List<String> unenforceable) {

    int masked() {
      return (int) columns.stream().filter(c -> Fate.MASKED.name().equals(c.fate())).count();
    }

    int hidden() {
      return (int) columns.stream().filter(c -> Fate.HIDDEN.name().equals(c.fate())).count();
    }

    List<ColumnFate> sensitiveInClear() {
      return columns.stream()
          .filter(c -> c.sensitive() && Fate.VISIBLE.name().equals(c.fate()))
          .toList();
    }
  }

  enum Fate {
    VISIBLE,
    MASKED,
    HIDDEN
  }

  /**
   * One column.
   *
   * @param fate VISIBLE, MASKED or HIDDEN
   * @param masking the masking function, when masked
   * @param policy the policy that masks or hides it
   * @param conditional masked only on some rows (a cell mask)
   * @param sensitiveTags the tags that make it sensitive; empty when it is not
   */
  public record ColumnFate(
      String name,
      String dataType,
      String fate,
      String masking,
      String policy,
      boolean conditional,
      boolean sensitive,
      List<String> sensitiveTags) {}

  /** One restriction on which rows come back. */
  public record RowFilter(String kind, String description, String policy) {}

  public record Reason(
      String policy, String effect, boolean matched, String scopeLevel, String explanation) {}

  /**
   * The policy a reviewer chose to configure the request with.
   *
   * @param bound it reaches this table in the environment requests are
   *     decided in, so activating it would apply here
   */
  public record PolicyCheck(
      UUID id,
      String name,
      String displayName,
      String lifecycleState,
      String environment,
      int version,
      boolean bound) {}

  /** LOW, MEDIUM or HIGH, and the facts that made it so. */
  public record Risk(String level, List<Factor> factors) {}

  public record Factor(String level, String code, String detail) {}

  /**
   * Something that makes one way of answering wrong or pointless.
   *
   * @param severity BLOCKER (that way will not work), WARNING (it will, but
   *     look first) or INFO (what it will and will not change)
   */
  public record Conflict(
      String severity, String code, String detail, UUID policyId, String policyName) {}

  /**
   * One way the reviewer might answer. Offered, never taken.
   *
   * @param kind GRANT, UPDATE_POLICY, CREATE_POLICY_DRAFT or DECLINE
   * @param days for a grant: how long
   * @param policyId for UPDATE_POLICY: the policy to change
   * @param draft for CREATE_POLICY_DRAFT: a document to open in the builder.
   *     It is not saved; saving it makes a DRAFT, and only the policy lifecycle
   *     makes one active.
   */
  public record Suggestion(
      String kind, String title, String detail, Integer days, UUID policyId, Policy draft) {}

  /**
   * Whether ARAK would approve, and how strongly. A lean, never an answer.
   *
   * <p>Every point comes from a signal the reviewer can read and disagree with:
   * there is no model behind it and nothing is learned. The reviewer still
   * decides, and approving still only records the decision.
   *
   * @param verdict APPROVE, REVIEW, REJECT or DECLINE (they can already read it)
   * @param score how far ARAK leans towards approving, 0 to 100
   * @param suggestedDays a shorter grant worth approving instead, or null
   * @param signals what moved the score, in the order they were weighed
   */
  public record Recommendation(
      String verdict, int score, String summary, Integer suggestedDays, List<Signal> signals) {}

  /** One thing that moved the score, and by how much. */
  public record Signal(String code, int points, String detail) {}

  // --------------------------------------------------------------- reviewing

  /**
   * Reviews one request for the reader.
   *
   * @param policyId the policy the reader is thinking of configuring it with,
   *     or null
   * @throws AccessRequestStore.RequestException NOT_FOUND when the reader may
   *     not see the request, FORBIDDEN when the reader asked for it
   */
  public Review review(UUID id, AccessRequestStore.Actor actor, UUID policyId) {
    AccessRequestStore.StoredRequest request = requests.find(id, actor);
    if (request.requesterUsername().equalsIgnoreCase(actor.username())) {
      throw new AccessRequestStore.RequestException(
          AccessRequestStore.RequestException.Kind.FORBIDDEN,
          "A review is for the people deciding a request, not for the person who asked");
    }

    String fqn = request.assetFqn();
    String who = request.requesterUsername();
    String ip = requests.askedFrom(id);
    DecisionService.Ask ask = new DecisionService.Ask(who, fqn, null, ip, request.purpose(), null);
    Instant at = Instant.now();

    AssetContext asset = jdbi.withHandle(handle -> contexts.load(handle, fqn)).orElse(null);
    List<ColumnContext> columns = asset == null ? List.of() : asset.columns();

    Access now = access(decisions.decide(ask), columns);
    Access ifGranted = access(decisions.decideAsIfGranted(ask), columns);

    PolicyCheck policy = null;
    Access ifPolicy = null;
    if (policyId != null) {
      Optional<PolicyStore.StoredPolicy> stored = policies.find(policyId);
      if (stored.isPresent()) {
        DecisionService.Candidate candidate = decisions.decideWithCandidate(ask, policyId);
        PolicyStore.StoredPolicy one = stored.get();
        policy =
            new PolicyCheck(
                one.id(),
                one.document().getName(),
                one.document().getDisplayName(),
                one.lifecycleState(),
                one.environment(),
                one.version(),
                candidate.bound());
        ifPolicy = access(candidate.decision(), columns);
      } else {
        policy = new PolicyCheck(policyId, null, null, null, null, 0, false);
      }
    }

    Requester requester = requester(request, at);
    TableFacts table = table(fqn, asset, request.approvers());
    List<Peers> peers = peers(who, fqn);

    Judgement judged =
        judge(
            request.requestedDays(),
            request.purpose(),
            requester,
            table,
            now,
            ifGranted,
            ifPolicy,
            policy,
            peers);
    return new Review(
        request.id(),
        fqn,
        request.status(),
        request.requestedDays(),
        request.purpose(),
        at,
        ip != null,
        requester,
        table,
        now,
        ifGranted,
        ifPolicy,
        policy,
        judged.risk(),
        judged.conflicts(),
        judged.suggestions(),
        judged.recommendation());
  }

  /**
   * Why a grant for this request would change nothing, or empty when it would
   * let the requester in.
   *
   * <p>Asked when a request is configured as a grant. A grant that a DENY or a
   * higher layer defeats is not harmless: it lies in wait, and the day that
   * policy is relaxed for some other reason it opens the table for somebody
   * nobody decided about then.
   */
  public Optional<String> grantWouldNotOpen(AccessRequestStore.StoredRequest request) {
    DecisionService.Ask ask =
        new DecisionService.Ask(
            request.requesterUsername(),
            request.assetFqn(),
            null,
            requests.askedFrom(request.id()),
            request.purpose(),
            null);
    PolicyDecision ifGranted = decisions.decideAsIfGranted(ask);
    if (Boolean.TRUE.equals(ifGranted.getAllowed())) {
      return Optional.empty();
    }
    return Optional.of(AccessEligibility.blocker(ifGranted));
  }

  // --------------------------------------------------------------- reading

  private Requester requester(AccessRequestStore.StoredRequest request, Instant at) {
    String who = request.requesterUsername();
    Optional<PrincipalQuery.PrincipalDetail> found = people.detail(who);

    List<HeldGrant> here = new ArrayList<>();
    int elsewhere = 0;
    Set<String> elsewhereTables = new java.util.HashSet<>();
    for (GrantStore.StoredGrant grant : grants.heldBy(who)) {
      if (!grant.liveAt(at)) {
        continue;
      }
      if (grant.assetFqn().equals(request.assetFqn())) {
        here.add(
            new HeldGrant(
                grant.id(),
                grant.username(),
                grant.isGroup(),
                grant.validUntil(),
                grant.grantedBy()));
      } else if (elsewhereTables.add(grant.assetFqn())) {
        elsewhere++;
      }
    }

    List<PastRequest> earlier = new ArrayList<>();
    for (AccessRequestStore.StoredRequest one : requests.earlier(request, 20)) {
      earlier.add(
          new PastRequest(
              one.id(),
              one.status(),
              one.requestedDays(),
              one.createdAt(),
              one.completedBy() != null ? one.completedBy() : one.decidedBy(),
              one.fulfilmentNote() != null ? one.fulfilmentNote() : one.decisionNote(),
              one.fulfilment()));
    }

    int[] recent =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT count(*) AS asked,
                               count(*) FILTER (WHERE status = 'REJECTED') AS rejected
                        FROM access_request
                        WHERE lower(requester_username) = lower(:username)
                          AND created_at >= :since AND id <> :id
                        """)
                    .bind("username", who)
                    .bind("since", at.minus(HISTORY_WINDOW))
                    .bind("id", request.id())
                    .map((rs, ctx) -> new int[] {rs.getInt("asked"), rs.getInt("rejected")})
                    .one());

    if (found.isEmpty()) {
      return new Requester(
          who, null, null, false, false, null, List.of(), List.of(), List.of(), here, elsewhere,
          earlier, recent[0], recent[1]);
    }
    PrincipalQuery.PrincipalDetail detail = found.get();
    PrincipalQuery.Principal person = detail.principal();
    List<Membership> memberships =
        detail.groups().stream()
            .map(
                g ->
                    new Membership(
                        g.id(),
                        g.username(),
                        g.displayName(),
                        "openmetadata".equals(g.source()) ? "team" : "group",
                        g.source()))
            .toList();
    List<Attribute> attributes =
        detail.attributes().stream()
            .map(a -> new Attribute(a.key(), a.value(), a.source()))
            .toList();
    return new Requester(
        person.username(),
        person.displayName(),
        person.email(),
        true,
        person.enabled(),
        person.source(),
        person.appRoles() == null ? List.of() : person.appRoles(),
        memberships,
        attributes,
        here,
        elsewhere,
        earlier,
        recent[0],
        recent[1]);
  }

  private static TableFacts table(
      String fqn, AssetContext asset, List<AccessRequestStore.Approver> owners) {
    if (asset == null) {
      return new TableFacts(fqn, false, List.of(), List.of(), owners, 0, 0);
    }
    int sensitive = 0;
    for (ColumnContext column : asset.columns()) {
      if (!sensitiveTags(column).isEmpty()) {
        sensitive++;
      }
    }
    return new TableFacts(
        fqn,
        true,
        values(asset.facets().get(FacetCondition.FacetType.TIER)),
        values(asset.facets().get(FacetCondition.FacetType.DOMAINS)),
        owners,
        asset.columns().size(),
        sensitive);
  }

  /** A group the requester belongs to, and how many of its other members already hold a grant here. */
  record Peers(String name, String kind, int holding) {}

  /**
   * The requester's direct groups and teams in which at least {@link
   * #PEERS_FOR_POLICY} other people already hold a live grant on this table:
   * the pattern where one policy would say what a row of grants is saying.
   */
  private List<Peers> peers(String username, String fqn) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT grp.username AS name, grp.source AS source,
                           count(DISTINCT peer.member_id) AS holding
                    FROM principal me
                    JOIN group_member mine ON mine.member_id = me.id
                    JOIN principal grp ON grp.id = mine.group_id
                    JOIN group_member peer ON peer.group_id = grp.id AND peer.member_id <> me.id
                    JOIN access_grant g ON g.principal_id = peer.member_id
                    WHERE lower(me.username) = lower(:username)
                      AND g.asset_fqn = :fqn
                      AND g.revoked_at IS NULL
                      AND (g.valid_from IS NULL OR g.valid_from <= now())
                      AND (g.valid_until IS NULL OR g.valid_until > now())
                    GROUP BY grp.username, grp.source
                    HAVING count(DISTINCT peer.member_id) >= :min
                    ORDER BY holding DESC, grp.username
                    """)
                .bind("username", username)
                .bind("fqn", fqn)
                .bind("min", PEERS_FOR_POLICY)
                .map(
                    (rs, ctx) ->
                        new Peers(
                            rs.getString("name"),
                            "openmetadata".equals(rs.getString("source")) ? "team" : "group",
                            rs.getInt("holding")))
                .list());
  }

  // --------------------------------------------------------------- decisions

  /** One decision, read column by column. */
  static Access access(PolicyDecision decision, List<ColumnContext> columns) {
    Map<UUID, String> names = new HashMap<>();
    List<Reason> reasons = new ArrayList<>();
    for (DecisionReason reason : safe(decision.getReasons())) {
      if (reason.getPolicyId() != null && reason.getPolicyName() != null) {
        names.putIfAbsent(reason.getPolicyId(), reason.getPolicyName());
      }
      reasons.add(
          new Reason(
              reason.getPolicyName(),
              reason.getEffect() == null ? null : reason.getEffect().value(),
              Boolean.TRUE.equals(reason.getMatched()),
              reason.getScopeLevel() == null ? null : reason.getScopeLevel().value(),
              reason.getExplanation()));
    }

    Map<String, ResolvedColumnMask> masks = new LinkedHashMap<>();
    for (ResolvedColumnMask mask : safe(decision.getColumnMasks())) {
      masks.putIfAbsent(lower(mask.getColumn()), mask);
    }
    Set<String> hidden = new java.util.HashSet<>();
    for (String column : safe(decision.getHiddenColumns())) {
      hidden.add(lower(column));
    }

    List<ColumnFate> fates = new ArrayList<>(columns.size());
    for (ColumnContext column : columns) {
      String key = lower(column.name());
      List<String> tags = sensitiveTags(column);
      if (hidden.contains(key)) {
        fates.add(
            new ColumnFate(
                column.name(), column.dataType(), Fate.HIDDEN.name(), null, null, false,
                !tags.isEmpty(), tags));
        continue;
      }
      ResolvedColumnMask mask = masks.get(key);
      if (mask != null) {
        fates.add(
            new ColumnFate(
                column.name(),
                column.dataType(),
                Fate.MASKED.name(),
                mask.getMasking() == null || mask.getMasking().getFunction() == null
                    ? null
                    : mask.getMasking().getFunction().value(),
                name(names, mask.getSourcePolicyId()),
                mask.getCondition() != null && !mask.getCondition().isBlank(),
                !tags.isEmpty(),
                tags));
        continue;
      }
      fates.add(
          new ColumnFate(
              column.name(), column.dataType(), Fate.VISIBLE.name(), null, null, false,
              !tags.isEmpty(), tags));
    }

    List<RowFilter> rows = new ArrayList<>();
    for (ResolvedRowPredicate predicate : safe(decision.getRowPredicates())) {
      rows.add(
          new RowFilter(
              predicate.getKind() == null ? null : predicate.getKind().value(),
              describe(predicate),
              name(names, predicate.getSourcePolicyId())));
    }

    boolean allowed = Boolean.TRUE.equals(decision.getAllowed());
    DecisionReason blocking = allowed ? null : AccessEligibility.blocking(decision);
    List<String> unenforceable = new ArrayList<>();
    for (var one : safe(decision.getUnenforceable())) {
      unenforceable.add(one.getDetail());
    }
    return new Access(
        allowed,
        allowed ? null : AccessEligibility.blocker(decision),
        blocking == null ? null : blocking.getPolicyId(),
        List.copyOf(fates),
        List.copyOf(rows),
        List.copyOf(reasons),
        List.copyOf(unenforceable));
  }

  /** A row filter in the words a reviewer reads, not the compiler's. */
  static String describe(ResolvedRowPredicate predicate) {
    if (predicate.getKind() == null) {
      return "a row filter";
    }
    List<Object> values = safe(predicate.getValues());
    return switch (predicate.getKind()) {
      case ALWAYS_FALSE -> "no rows at all";
      case ATTRIBUTE_COMPARE ->
          predicate.getColumn()
              + " "
              + (predicate.getOperator() == null ? "eq" : predicate.getOperator().value())
              + " "
              + (values.size() == 1 ? String.valueOf(values.get(0)) : values.toString());
      case IN_LIST -> predicate.getColumn() + " in " + values;
      case ENTITLEMENT_JOIN ->
          "rows listed for them in the entitlement \"" + predicate.getEntitlementKey() + "\"";
      case RAW_PREDICATE -> predicate.getRawPredicate();
    };
  }

  // --------------------------------------------------------------- sensitivity

  /**
   * The tags that make a column sensitive, most specific only.
   *
   * <p>Anything under the PII or PersonalData classifications, or a tag that
   * calls itself sensitive, confidential, restricted or secret -- except one
   * that says it is not ({@code PII.NonSensitive}). Only the most specific
   * tags count, because the cache spreads every tag's ancestors beside it: the
   * {@code PII} that sits next to {@code PII.NonSensitive} is that tag's
   * parent, not a second label.
   */
  static List<String> sensitiveTags(ColumnContext column) {
    List<String> tags = values(column.facets().get(FacetCondition.FacetType.TAGS));
    List<String> out = new ArrayList<>();
    for (String tag : tags) {
      if (isAncestorOfAnother(tag, tags)) {
        continue;
      }
      if (sensitive(tag)) {
        out.add(tag);
      }
    }
    return List.copyOf(out);
  }

  static boolean sensitive(String tag) {
    String t = tag.toLowerCase(Locale.ROOT);
    if (t.contains("nonsensitive") || t.contains("non-sensitive") || t.contains("public")) {
      return false;
    }
    if (t.equals("pii") || t.startsWith("pii.") || t.equals("personaldata")
        || t.startsWith("personaldata.")) {
      return true;
    }
    for (String word : List.of("sensitive", "confidential", "restricted", "secret")) {
      if (t.contains(word)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isAncestorOfAnother(String tag, List<String> tags) {
    String prefix = tag + ".";
    for (String other : tags) {
      if (other.length() > prefix.length() && other.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  // --------------------------------------------------------------- judging

  record Judgement(
      Risk risk,
      List<Conflict> conflicts,
      List<Suggestion> suggestions,
      Recommendation recommendation) {}

  /**
   * Risk, conflicts and suggestions from what was read. Pure, so every rule can
   * be tried without a database.
   */
  static Judgement judge(
      Integer days,
      String purpose,
      Requester requester,
      TableFacts table,
      Access now,
      Access ifGranted,
      Access ifPolicy,
      PolicyCheck policy,
      List<Peers> peers) {
    List<Factor> factors = new ArrayList<>();
    List<Conflict> conflicts = new ArrayList<>();
    List<Suggestion> suggestions = new ArrayList<>();

    // ---- risk
    if (!requester.known()) {
      factors.add(
          new Factor(
              "HIGH", "REQUESTER_UNKNOWN",
              requester.username() + " is no longer a known principal"));
    } else if (!requester.enabled()) {
      factors.add(
          new Factor("HIGH", "REQUESTER_DISABLED", requester.username() + " is disabled"));
    }
    List<ColumnFate> clear = ifGranted.allowed() ? ifGranted.sensitiveInClear() : List.of();
    if (!clear.isEmpty()) {
      factors.add(
          new Factor(
              "HIGH",
              "SENSITIVE_IN_CLEAR",
              clear.size()
                  + " sensitive column"
                  + (clear.size() == 1 ? "" : "s")
                  + " would be readable in clear: "
                  + String.join(", ", clear.stream().map(ColumnFate::name).toList())));
    } else if (ifGranted.allowed()
        && ifGranted.columns().stream().anyMatch(ColumnFate::sensitive)) {
      factors.add(
          new Factor(
              "LOW",
              "SENSITIVE_PROTECTED",
              "Every sensitive column stays masked or hidden after a grant"));
    }
    if (days == null) {
      factors.add(new Factor("HIGH", "OPEN_ENDED", "Asked until revoked, with no end date"));
    } else if (days > VERY_LONG_DAYS) {
      factors.add(new Factor("HIGH", "VERY_LONG", "Asked for " + days + " days"));
    } else if (days > LONG_DAYS) {
      factors.add(new Factor("MEDIUM", "LONG", "Asked for " + days + " days"));
    }
    if (table.tiers().stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).endsWith("tier1"))) {
      factors.add(new Factor("MEDIUM", "TIER1", "The table is Tier 1"));
    }
    long rejectedHere =
        requester.earlier().stream().filter(r -> "REJECTED".equals(r.status())).count();
    if (rejectedHere > 0) {
      factors.add(
          new Factor(
              "MEDIUM",
              "REJECTED_BEFORE",
              "Turned down for this table "
                  + rejectedHere
                  + (rejectedHere == 1 ? " time" : " times")
                  + " before"));
    }
    if (purpose == null || purpose.isBlank()) {
      factors.add(new Factor("LOW", "NO_PURPOSE", "No purpose was given"));
    }
    if (!table.known()) {
      factors.add(
          new Factor(
              "MEDIUM", "TABLE_UNKNOWN", "The table is not in the metadata cache, so its columns cannot be shown"));
    }
    String level = "LOW";
    for (Factor factor : factors) {
      level = higher(level, factor.level());
    }

    // ---- conflicts
    if (now.allowed()) {
      conflicts.add(
          new Conflict(
              "WARNING",
              "ALREADY_READS",
              requester.username() + " can already read this table; a grant would add nothing",
              null,
              null));
    }
    for (HeldGrant held : requester.grantsHere()) {
      conflicts.add(
          new Conflict(
              "WARNING",
              "LIVE_GRANT",
              (held.viaGroup() ? "A grant to " + held.grantedTo() + " already reaches them" : "They already hold a grant")
                  + (held.validUntil() == null ? ", until revoked" : ", until " + held.validUntil()),
              null,
              null));
    }
    if (!ifGranted.allowed()) {
      conflicts.add(
          new Conflict(
              "BLOCKER",
              "GRANT_BLOCKED",
              "A grant would not let "
                  + requester.username()
                  + " read this table. Still refusing: "
                  + ifGranted.blockedBy(),
              ifGranted.blockedByPolicyId(),
              null));
    } else {
      if (ifGranted.masked() + ifGranted.hidden() > 0) {
        conflicts.add(
            new Conflict(
                "INFO",
                "MASKS_REMAIN",
                "A grant opens the table, but "
                    + ifGranted.masked()
                    + " column(s) stay masked and "
                    + ifGranted.hidden()
                    + " hidden by other policies. A grant cannot lift them.",
                null,
                null));
      }
      if (!ifGranted.rowFilters().isEmpty()) {
        conflicts.add(
            new Conflict(
                "INFO",
                "ROW_FILTERED",
                "Rows stay filtered: "
                    + String.join("; ", ifGranted.rowFilters().stream().map(RowFilter::description).toList()),
                null,
                null));
      }
    }
    if (policy != null) {
      if (policy.name() == null) {
        conflicts.add(
            new Conflict("BLOCKER", "POLICY_NOT_FOUND", "No policy with that id exists", policy.id(), null));
      } else {
        String label = policy.displayName() != null ? policy.displayName() : policy.name();
        if (!policy.bound()) {
          conflicts.add(
              new Conflict(
                  "BLOCKER",
                  "POLICY_NOT_BOUND",
                  label
                      + " does not reach this table in "
                      + DecisionService.DEFAULT_ENVIRONMENT
                      + ", so it cannot be what gives them access. Check its scope, selector and environment.",
                  policy.id(),
                  label));
        } else if (!"ACTIVE".equals(policy.lifecycleState())) {
          conflicts.add(
              new Conflict(
                  "WARNING",
                  "POLICY_NOT_ACTIVE",
                  label
                      + " is "
                      + policy.lifecycleState()
                      + ". ARAK will not activate it from here; it only takes effect once it is activated on its own page.",
                  policy.id(),
                  label));
        }
        if (policy.bound() && ifPolicy != null) {
          if (!ifPolicy.allowed()) {
            conflicts.add(
                new Conflict(
                    "WARNING",
                    "POLICY_STILL_REFUSES",
                    "With " + label + " in force they still could not read it: " + ifPolicy.blockedBy(),
                    policy.id(),
                    label));
          } else if (!now.allowed()) {
            conflicts.add(
                new Conflict(
                    "INFO",
                    "POLICY_OPENS",
                    "With " + label + " in force they could read it"
                        + (ifPolicy.sensitiveInClear().isEmpty()
                            ? ""
                            : ", with " + ifPolicy.sensitiveInClear().size() + " sensitive column(s) in clear"),
                    policy.id(),
                    label));
          }
        }
      }
    }

    // ---- suggestions
    if (now.allowed()) {
      suggestions.add(
          new Suggestion(
              "DECLINE",
              "Decline: they can already read it",
              "Nothing needs configuring. Declining tells them so.",
              null,
              null,
              null));
    } else if (ifGranted.allowed()) {
      boolean shorter =
          !clear.isEmpty() && (days == null || days > SHORTER_DAYS);
      if (shorter) {
        suggestions.add(
            new Suggestion(
                "GRANT",
                "Grant for " + SHORTER_DAYS + " days",
                "Sensitive columns would be readable in clear; a short grant keeps that exposure small.",
                SHORTER_DAYS,
                null,
                null));
      }
      suggestions.add(
          new Suggestion(
              "GRANT",
              days == null ? "Grant until revoked, as asked" : "Grant for " + days + " days, as asked",
              "A direct grant on this table, ending when it was asked to.",
              days,
              null,
              null));
    } else if (ifGranted.blockedByPolicyId() != null) {
      suggestions.add(
          new Suggestion(
              "UPDATE_POLICY",
              "Change the policy in the way",
              "A grant cannot pass it. If they should read this table, change that policy, then finish as \"Policy updated\". Otherwise decline.",
              null,
              ifGranted.blockedByPolicyId(),
              null));
    }
    if (!now.allowed()) {
      for (Peers group : peers) {
        suggestions.add(
            new Suggestion(
                "CREATE_POLICY_DRAFT",
                "A policy for " + group.kind() + " " + group.name(),
                group.holding()
                    + " other members of "
                    + group.name()
                    + " already hold a grant on this table. One policy for the "
                    + group.kind()
                    + " would say the same thing once. Opened as a draft; nothing is saved or activated.",
                null,
                null,
                draftFor(group, table.fqn())));
      }
    }

    conflicts.sort(Comparator.comparingInt(c -> severityRank(c.severity())));
    return new Judgement(
        new Risk(level, List.copyOf(factors)),
        List.copyOf(conflicts),
        List.copyOf(suggestions),
        recommend(days, purpose, requester, table, now, ifGranted, peers));
  }

  // --------------------------------------------------------------- recommending

  /** Where the score starts before any signal: no lean either way. */
  static final int NEUTRAL = 50;

  /** At or above this, ARAK leans towards approving. */
  static final int APPROVE_AT = 70;

  /** Below this, ARAK leans towards turning it down. */
  static final int REJECT_BELOW = 40;

  /**
   * Whether ARAK would approve, from the same facts the risk is read from.
   *
   * <p>Pure and additive: each signal adds or takes points from a neutral 50,
   * so the reviewer can see exactly why the number is what it is. Three cases
   * are not weighed at all, because no number would be honest about them: a
   * requester who is gone or disabled, and one who can already read the table.
   * A grant that a policy would still defeat is weighed, but can never lean to
   * approve -- approving it would open nothing until that policy changes.
   */
  static Recommendation recommend(
      Integer days,
      String purpose,
      Requester requester,
      TableFacts table,
      Access now,
      Access ifGranted,
      List<Peers> peers) {
    if (!requester.known() || !requester.enabled()) {
      String why =
          requester.username()
              + (requester.known() ? " is disabled" : " is no longer a known principal");
      return new Recommendation(
          "REJECT", 0, "Turn it down: " + why + ".", null,
          List.of(new Signal(requester.known() ? "REQUESTER_DISABLED" : "REQUESTER_UNKNOWN", -NEUTRAL, why)));
    }
    if (now.allowed()) {
      return new Recommendation(
          "DECLINE", 0, "Nothing to approve: they can already read this table.", null,
          List.of(new Signal("ALREADY_READS", -NEUTRAL, "They can already read this table")));
    }

    List<Signal> signals = new ArrayList<>();
    if (!ifGranted.allowed()) {
      signals.add(
          new Signal(
              "GRANT_BLOCKED", -30,
              "A grant would not open it; still refusing: " + ifGranted.blockedBy()));
    }

    if (purpose == null || purpose.isBlank()) {
      signals.add(new Signal("NO_PURPOSE", -10, "No purpose was given"));
    } else {
      signals.add(new Signal("PURPOSE", 10, "A purpose was given"));
    }

    List<ColumnFate> clear = ifGranted.allowed() ? ifGranted.sensitiveInClear() : List.of();
    boolean anySensitive = ifGranted.columns().stream().anyMatch(ColumnFate::sensitive);
    if (!clear.isEmpty()) {
      signals.add(
          new Signal(
              "SENSITIVE_IN_CLEAR", -25,
              clear.size() + " sensitive column" + (clear.size() == 1 ? "" : "s") + " would be readable in clear"));
    } else if (ifGranted.allowed() && anySensitive) {
      signals.add(new Signal("SENSITIVE_PROTECTED", 10, "Every sensitive column stays masked or hidden"));
    } else if (ifGranted.allowed() && table.known() && !ifGranted.columns().isEmpty()) {
      signals.add(new Signal("NOTHING_SENSITIVE", 10, "No column on the table is tagged sensitive"));
    }

    if (days == null) {
      signals.add(new Signal("OPEN_ENDED", -20, "Asked until revoked, with no end date"));
    } else if (days > VERY_LONG_DAYS) {
      signals.add(new Signal("VERY_LONG", -20, "Asked for " + days + " days"));
    } else if (days > LONG_DAYS) {
      signals.add(new Signal("LONG", -10, "Asked for " + days + " days"));
    } else if (days <= SHORTER_DAYS) {
      signals.add(new Signal("SHORT", 10, "Asked for only " + days + (days == 1 ? " day" : " days")));
    }

    if (table.tiers().stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).endsWith("tier1"))) {
      signals.add(new Signal("TIER1", -5, "The table is Tier 1"));
    }
    if (!table.known()) {
      signals.add(new Signal("TABLE_UNKNOWN", -10, "The table is not in the metadata cache"));
    }

    long rejectedHere =
        requester.earlier().stream().filter(r -> "REJECTED".equals(r.status())).count();
    long grantedHere =
        requester.earlier().stream()
            .filter(r -> "COMPLETED".equals(r.status()) || "APPROVED".equals(r.status()))
            .count();
    if (rejectedHere > 0) {
      signals.add(
          new Signal(
              "REJECTED_BEFORE", -15,
              "Turned down for this table " + rejectedHere + (rejectedHere == 1 ? " time" : " times") + " before"));
    } else if (grantedHere > 0) {
      signals.add(new Signal("APPROVED_BEFORE", 10, "Given access to this table before"));
    }
    if (requester.recentRejected() >= 2 && requester.recentRejected() * 2 >= requester.recentRequests()) {
      signals.add(
          new Signal(
              "OFTEN_REJECTED", -10,
              requester.recentRejected() + " of their " + requester.recentRequests()
                  + " other requests in 90 days were turned down"));
    }

    if (!peers.isEmpty()) {
      Peers most = peers.get(0);
      signals.add(
          new Signal(
              "PEERS_HOLD", 15,
              most.holding() + " other members of " + most.kind() + " " + most.name()
                  + " already read this table"));
    }
    String match = domainMatch(requester, table.domains());
    if (match != null) {
      signals.add(new Signal("DOMAIN_MATCH", 10, match));
    }

    int score = NEUTRAL;
    for (Signal signal : signals) {
      score += signal.points();
    }
    score = Math.max(0, Math.min(100, score));

    Integer shorter =
        !clear.isEmpty() && (days == null || days > SHORTER_DAYS) ? SHORTER_DAYS : null;
    String verdict;
    String summary;
    if (!ifGranted.allowed()) {
      verdict = score < REJECT_BELOW ? "REJECT" : "REVIEW";
      summary =
          "A grant alone would not let them in. Approve only if the policy in the way should change for them.";
    } else if (score >= APPROVE_AT) {
      verdict = "APPROVE";
      summary = shorter == null ? "Leans to approve." : "Leans to approve, for " + SHORTER_DAYS + " days rather than as asked.";
    } else if (score < REJECT_BELOW) {
      verdict = "REJECT";
      summary = "Leans to turn it down. Read the signals before you do.";
    } else {
      verdict = "REVIEW";
      summary =
          shorter == null
              ? "No clear lean. Check the signals below."
              : "No clear lean. A " + SHORTER_DAYS + "-day grant would keep the exposure small.";
    }
    return new Recommendation(verdict, score, summary, shorter, List.copyOf(signals));
  }

  /**
   * A requester's attribute or group that names one of the table's domains,
   * segment for segment and ignoring case: {@code department = FINANCE} meets
   * {@code Finance.Risk}. Null when none does.
   */
  static String domainMatch(Requester requester, List<String> domains) {
    if (domains.isEmpty()) {
      return null;
    }
    for (String domain : domains) {
      Set<String> segments = new java.util.HashSet<>();
      for (String part : domain.split("\\.")) {
        segments.add(lower(part.trim()));
      }
      for (Attribute attribute : requester.attributes()) {
        if (attribute.value() != null && segments.contains(lower(attribute.value().trim()))) {
          return "Their " + attribute.key() + " " + attribute.value() + " matches the domain " + domain;
        }
      }
      for (Membership group : requester.memberships()) {
        if (segments.contains(lower(group.name()))
            || (group.displayName() != null && segments.contains(lower(group.displayName())))) {
          return "They belong to " + (group.displayName() != null ? group.displayName() : group.name())
              + ", which matches the domain " + domain;
        }
      }
    }
    return null;
  }

  /** A TABLE subscription letting one group read one table. Returned unsaved. */
  static Policy draftFor(Peers group, String fqn) {
    PrincipalMatch who = new PrincipalMatch();
    if ("team".equals(group.kind())) {
      who.setTeam(group.name());
    } else {
      who.setGroup(group.name());
    }
    String leaf = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
    return new Policy()
        .withName(slug(group.name() + "-reads-" + leaf))
        .withDisplayName(group.name() + " reads " + leaf)
        .withDescription(
            "Drafted from an access request: "
                + group.holding()
                + " members of this "
                + group.kind()
                + " already held direct grants on the table.")
        .withPolicyType(Policy.PolicyType.SUBSCRIPTION)
        .withScopeLevel(ResolvedColumnMask.ScopeLevel.TABLE)
        .withScopeFqn(fqn)
        .withSelector(
            new AssetSelector()
                .withCondition(
                    new FacetCondition()
                        .withFacet(FacetCondition.FacetType.TABLE)
                        .withOperator(ResolvedRowPredicate.FacetOperator.EQ)
                        .withValue(fqn)))
        .withSubject(new SubjectRule().withPrincipals(List.of(who)))
        .withEffect(Policy.Effect.ALLOW)
        .withEnvironment(Policy.Environment.fromValue(DecisionService.DEFAULT_ENVIRONMENT));
  }

  // --------------------------------------------------------------- small things

  static String slug(String raw) {
    String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    return s.length() > 120 ? s.substring(0, 120) : s;
  }

  private static String higher(String a, String b) {
    return rank(b) > rank(a) ? b : a;
  }

  private static int rank(String level) {
    return switch (level) {
      case "HIGH" -> 2;
      case "MEDIUM" -> 1;
      default -> 0;
    };
  }

  private static int severityRank(String severity) {
    return switch (severity) {
      case "BLOCKER" -> 0;
      case "WARNING" -> 1;
      default -> 2;
    };
  }

  private static String name(Map<UUID, String> names, UUID id) {
    return id == null ? null : names.getOrDefault(id, id.toString());
  }

  private static List<String> values(List<FacetValue> values) {
    if (values == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>(values.size());
    for (FacetValue value : values) {
      if (value.value() != null && !value.suggested() && !out.contains(value.value())) {
        out.add(value.value());
      }
    }
    return out;
  }

  private static String lower(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }

  private static <T> List<T> safe(List<T> list) {
    return list == null ? List.of() : list;
  }
}
