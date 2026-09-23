package com.mfec.dac.engine;

import com.mfec.dac.common.Fqns;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.entity.policy.ColumnRule;
import com.mfec.dac.schema.entity.policy.DataPolicy;
import com.mfec.dac.schema.entity.policy.Exemption;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.RowFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns a principal, an asset and a set of policies into one decision.
 *
 * <p>This is the only place in the platform where access is decided. The three
 * enforcement modes — native source configuration (5.1.1), secure view (5.1.2)
 * and the query proxy (5.2) — are compilers over the {@link PolicyDecision}
 * this produces; none of them evaluates a policy itself. That is what makes the
 * cross-mode consistency requirement (FR-6.0c) achievable at all: there is
 * nothing for the modes to disagree about.
 *
 * <p>Nothing here emits SQL, and nothing here knows which mode will run.
 *
 * <h2>How the layers compose</h2>
 *
 * <p>Policies bind at seven levels, ORG through COLUMN, with sub-domains
 * ordered by depth inside the DOMAIN level. The levels compose by intersection,
 * not by replacement: a subscription has to survive every layer that has an
 * opinion, row filters accumulate and are ANDed by the compilers, and the
 * strictest mask on a column wins. A local policy can therefore add strictness
 * and cannot remove it — unless the policy being relaxed set
 * {@code allowLocalOverride}, which is the only way a lower layer ever loosens
 * an upper one, and every use of it is recorded in the reasons (FR-3.1.4).
 *
 * <h2>Where it fails, it fails closed</h2>
 *
 * <ul>
 *   <li>No subscription policy binding the asset is a deny, not an allow.
 *   <li>A matched DENY beats any number of matched ALLOWs.
 *   <li>An expression that cannot be decided counts against the principal: an
 *       ALLOW carrying it does not grant, a DENY carrying it still denies, and a
 *       column rule carrying it keeps its restriction.
 *   <li>A row filter whose user attribute is missing becomes ALWAYS_FALSE rather
 *       than being dropped, so an unpopulated directory hides rows instead of
 *       exposing them.
 * </ul>
 */
public final class PolicyEngine {

  private final EngineConfig config;

  public PolicyEngine() {
    this(EngineConfig.defaults());
  }

  public PolicyEngine(EngineConfig config) {
    this.config = config == null ? EngineConfig.defaults() : config;
  }

  public EngineConfig config() {
    return config;
  }

  public PolicyDecision evaluate(
      Principal principal, AssetContext asset, RequestContext context, List<Policy> policies) {

    List<DecisionReason> reasons = new ArrayList<>();
    List<Candidate> bound = bind(principal, asset, context, policies, reasons);

    boolean allowed = decideSubscription(bound, reasons);

    PolicyDecision decision =
        new PolicyDecision()
            .withPrincipal(principal.id())
            .withAssetFqn(asset.fqn())
            .withAllowed(allowed)
            .withEvaluatedAt(context.at())
            .withFromCache(false)
            .withCacheKey(cacheKey(principal, asset, context, bound));

    if (!allowed) {
      // A denied subscription means there is nothing to enforce inside the
      // table. Emitting masks and filters anyway would hand the compilers SQL
      // to generate for an asset the principal cannot reach, and the three
      // modes would then have to agree about unreachable code.
      return decision
          .withReasons(reasons)
          .withRowPredicates(List.of())
          .withColumnMasks(List.of())
          .withHiddenColumns(List.of())
          .withUnenforceable(List.of());
    }

    DataOutcome data = applyDataPolicies(principal, asset, context, bound, reasons);
    return decision
        .withReasons(reasons)
        .withRowPredicates(data.rowPredicates())
        .withColumnMasks(data.columnMasks())
        .withHiddenColumns(data.hiddenColumns())
        // Populated by each compiler, which alone knows what its engine cannot
        // express (FR-6.0b). The engine has no mode and so has no opinion.
        .withUnenforceable(List.of());
  }

  // ---------------------------------------------------------------- binding

  /**
   * A policy that binds to this asset, together with what its subject rule said.
   *
   * @param additive true for a policy that only ever opens a door for the
   *     people it names -- a direct grant (FR-7). It is the one kind of ALLOW
   *     that must not narrow the asset for anybody else, so it is tracked
   *     separately from the authored policies that do.
   */
  private record Candidate(
      Policy policy,
      int layer,
      boolean subjectMatched,
      String explanation,
      boolean exempt,
      boolean additive) {

    boolean allows() {
      return effect() == Policy.Effect.ALLOW;
    }

    boolean denies() {
      return effect() == Policy.Effect.DENY;
    }

    Policy.Effect effect() {
      return effectOf(policy);
    }

    boolean applies() {
      return subjectMatched && !exempt;
    }
  }

  private List<Candidate> bind(
      Principal principal,
      AssetContext asset,
      RequestContext context,
      List<Policy> policies,
      List<DecisionReason> reasons) {

    List<Candidate> out = new ArrayList<>();
    if (policies == null) {
      return out;
    }
    for (Policy policy : policies) {
      if (policy == null || !enforceable(policy, context)) {
        continue;
      }
      if (!selects(policy, asset)) {
        continue;
      }

      Exemption exemption = activeExemption(policy, principal, context.at());
      if (exemption != null) {
        reasons.add(
            reason(
                policy,
                false,
                "principal is exempt until "
                    + exemption.getExpiresAt()
                    + ": "
                    + exemption.getReason()));
        out.add(new Candidate(policy, layerOf(policy), false, "exempt", true, additive(policy)));
        continue;
      }

      boolean matched;
      String explanation;
      try {
        SubjectMatcher.Result result =
            SubjectMatcher.matches(policy.getSubject(), principal, asset, context, config);
        matched = result.matched();
        explanation = result.explanation();
      } catch (ExpressionUnavailableException e) {
        // Undecidable counts against the principal, so which way that falls
        // depends on what the policy was trying to do.
        Policy.Effect effect = effectOf(policy);
        matched = effect == Policy.Effect.DENY;
        explanation =
            "expression could not be evaluated ("
                + e.getMessage()
                + "); failing closed, so this "
                + effect
                + " policy is treated as "
                + (matched ? "matching" : "not matching");
      }
      reasons.add(reason(policy, matched, explanation));
      out.add(
          new Candidate(policy, layerOf(policy), matched, explanation, false, additive(policy)));
    }
    return out;
  }

  /** Lifecycle, validity window and environment — everything independent of the principal. */
  private boolean enforceable(Policy policy, RequestContext context) {
    Policy.LifecycleState state = policy.getLifecycleState();
    // A null state is read as ACTIVE. Skipping an unlabelled policy would mean
    // a DENY silently stopping applying because someone forgot a field.
    if (state != null && state != Policy.LifecycleState.ACTIVE) {
      return false;
    }
    Instant at = context.at();
    if (policy.getValidFrom() != null && at.isBefore(policy.getValidFrom())) {
      return false;
    }
    if (policy.getValidUntil() != null && !at.isBefore(policy.getValidUntil())) {
      return false;
    }
    // A policy pinned to another environment is not ours to enforce (FR-9.4).
    // When this engine is not environment-aware, every policy applies.
    return config.environment() == null
        || policy.getEnvironment() == null
        || config.environment() == policy.getEnvironment();
  }

  private static Exemption activeExemption(Policy policy, Principal principal, Instant at) {
    if (policy.getExemptions() == null) {
      return null;
    }
    for (Exemption exemption : policy.getExemptions()) {
      if (exemption == null || exemption.getPrincipal() == null) {
        continue;
      }
      // An exemption is a hole in a policy. One with no expiry is not a
      // permanent hole, it is an unfinished record, and it is ignored (FR-3.5).
      if (exemption.getExpiresAt() == null || !at.isBefore(exemption.getExpiresAt())) {
        continue;
      }
      if (principal.is(exemption.getPrincipal()) || principal.hasTeam(exemption.getPrincipal())) {
        return exemption;
      }
    }
    return null;
  }

  /**
   * Whether this policy reaches this asset at all.
   *
   * <p>A policy is normally bound by its selector, and a selector that says
   * nothing selects nothing. That is the fail-closed reading and the one a
   * half-written policy has to get: "I never said which assets" must not
   * come out as "all of them".
   *
   * <p>A direct grant (FR-7) is the one thing that legitimately has no
   * selector. It was never written against a facet -- somebody named one
   * table and one person -- so it carries the asset in {@code scopeFqn}
   * instead, and is only ever loaded for that asset in the first place.
   * Requiring the FQN to match is what keeps this exception from widening
   * into "a policy with no selector applies everywhere", which is the
   * failure this whole method exists to avoid.
   */
  /**
   * Whether this policy only ever adds access for the people it names.
   *
   * <p>Read off the same field {@link #selects} reads: a direct grant is the
   * one policy with no selector, because it was never written against a facet.
   * Everything else here was authored against a set of assets and speaks for
   * that set, which is what makes it able to narrow as well as widen.
   */
  private static boolean additive(Policy policy) {
    return policy.getSelector() == null;
  }

  private static boolean selects(Policy policy, AssetContext asset) {
    if (policy.getSelector() != null) {
      return SelectorMatcher.matches(policy.getSelector(), asset);
    }
    return policy.getScopeFqn() != null && policy.getScopeFqn().equals(asset.fqn());
  }

  /**
   * Orders the layers. Sub-domains sort by FQN depth inside DOMAIN, so a policy
   * on {@code Finance.Risk.Credit} sits below one on {@code Finance} and can
   * only tighten it (FR-3.1.3).
   */
  static int layerOf(Policy policy) {
    ResolvedColumnMask.ScopeLevel level = policy.getScopeLevel();
    int base = (level == null ? ResolvedColumnMask.ScopeLevel.ORG : level).ordinal() * 100;
    if (level == ResolvedColumnMask.ScopeLevel.DOMAIN) {
      base += Math.min(99, Fqns.depth(policy.getScopeFqn()));
    }
    return base;
  }

  // ----------------------------------------------------------- subscription

  private boolean decideSubscription(List<Candidate> bound, List<DecisionReason> reasons) {
    List<Candidate> subscriptions = new ArrayList<>();
    for (Candidate c : bound) {
      if (c.policy().getPolicyType() == Policy.PolicyType.SUBSCRIPTION) {
        subscriptions.add(c);
      }
    }
    if (subscriptions.isEmpty()) {
      reasons.add(
          bareReason(
              false, "no subscription policy binds to this asset; access is denied by default"));
      return false;
    }

    boolean denied = false;
    for (Candidate c : subscriptions) {
      if (!c.denies() || !c.applies()) {
        continue;
      }
      if (Boolean.TRUE.equals(c.policy().getAllowLocalOverride())
          && hasMatchedAllowBelow(subscriptions, c.layer())) {
        reasons.add(
            reason(
                c.policy(),
                false,
                "DENY relaxed by a more specific policy, permitted because this one sets "
                    + "allowLocalOverride"));
        continue;
      }
      reasons.add(reason(c.policy(), true, "DENY matched; a deny always beats an allow"));
      denied = true;
    }
    if (denied) {
      return false;
    }

    // Only layers that actually contain an ALLOW act as gates. A layer holding
    // nothing but unmatched DENY policies has no opinion about who may in, and
    // treating it as a gate would deny everyone for having written a DENY.
    //
    // Direct grants are left out of the gate set for the same reason, and it is
    // the sharper one: a grant names one person, so counting it would turn
    // "Ann may read this table" into "only Ann may read this table" and quietly
    // shut out everybody an organisation-wide policy already let in. A grant
    // adds access and never removes it; it can satisfy a gate somebody else
    // put up (below), but it may not put one up itself.
    Set<Integer> gates = new TreeSet<>();
    boolean anyGrant = false;
    for (Candidate c : subscriptions) {
      if (!c.allows()) {
        continue;
      }
      if (c.additive()) {
        anyGrant = true;
        continue;
      }
      gates.add(c.layer());
    }
    if (gates.isEmpty()) {
      // Nothing gates the asset, so a grant is the whole of the answer -- which
      // is the ordinary case for FR-7: a table nobody wrote a policy for.
      for (Candidate c : subscriptions) {
        if (c.additive() && c.allows() && c.applies()) {
          reasons.add(
              reason(
                  c.policy(),
                  true,
                  "granted directly to this principal, and no policy gates this asset"));
          return true;
        }
      }
      reasons.add(
          bareReason(
              false,
              anyGrant
                  ? "the only thing that opens this asset is a direct grant, and none names this "
                      + "principal; access is denied by default"
                  : "only deny policies bind to this asset; nothing grants access, so access is "
                      + "denied by default"));
      return false;
    }

    for (Integer gate : gates) {
      if (hasMatchedAllowAt(subscriptions, gate)) {
        continue;
      }
      if (overridable(subscriptions, gate) && hasMatchedAllowBelow(subscriptions, gate)) {
        reasons.add(
            bareReason(
                true,
                "layer "
                    + gate
                    + " granted nothing, but every policy in it sets allowLocalOverride and a "
                    + "more specific layer allows"));
        continue;
      }
      // Name the policies standing in the gate before summarising it. A refusal
      // that says only "a layer refused" leaves the reader to go and find which
      // of forty policies it was, and the commonest reader of this is somebody
      // looking at a direct grant that is in force and admitting nobody -- for
      // whom the answer is one policy's name and whether it consented to being
      // passed.
      boolean relaxable = overridable(subscriptions, gate);
      for (Candidate c : subscriptions) {
        if (c.layer() != gate || !c.allows() || c.applies() || c.additive()) {
          continue;
        }
        reasons.add(
            reason(
                c.policy(),
                false,
                relaxable
                    ? "holds the gate at this layer and does not grant this principal access; it "
                        + "allows a lower layer to relax it, but nothing below has granted access "
                        + "either"
                    : "holds the gate at this layer and does not grant this principal access; it "
                        + "does not allow a lower layer to relax it, so no direct grant can pass "
                        + "it either"));
      }
      reasons.add(
          bareReason(
              false,
              "no policy at layer "
                  + gate
                  + " grants this principal access; every layer must allow, because layers "
                  + "intersect rather than replace one another"));
      return false;
    }
    return true;
  }

  private static boolean hasMatchedAllowAt(List<Candidate> candidates, int layer) {
    for (Candidate c : candidates) {
      if (c.layer() == layer && c.allows() && c.applies()) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasMatchedAllowBelow(List<Candidate> candidates, int layer) {
    for (Candidate c : candidates) {
      if (c.layer() > layer && c.allows() && c.applies()) {
        return true;
      }
    }
    return false;
  }

  /** True when every ALLOW policy at this layer consented to being relaxed. */
  private static boolean overridable(List<Candidate> candidates, int layer) {
    boolean seen = false;
    for (Candidate c : candidates) {
      if (c.layer() != layer || !c.allows()) {
        continue;
      }
      seen = true;
      if (!Boolean.TRUE.equals(c.policy().getAllowLocalOverride())) {
        return false;
      }
    }
    return seen;
  }

  // ------------------------------------------------------------ data policy

  private record DataOutcome(
      List<ResolvedRowPredicate> rowPredicates,
      List<ResolvedColumnMask> columnMasks,
      List<String> hiddenColumns) {}

  /** A mask or hide proposed by one policy, before the layers are resolved. */
  private record MaskCandidate(
      Candidate source, MaskingSpec masking, String condition, boolean hide) {}

  private DataOutcome applyDataPolicies(
      Principal principal,
      AssetContext asset,
      RequestContext context,
      List<Candidate> bound,
      List<DecisionReason> reasons) {

    List<ResolvedRowPredicate> rowPredicates = new ArrayList<>();
    Map<String, List<MaskCandidate>> proposals = new LinkedHashMap<>();
    Map<String, List<Candidate>> releases = new LinkedHashMap<>();

    for (Candidate c : bound) {
      if (c.policy().getPolicyType() != Policy.PolicyType.DATA || !c.applies()) {
        continue;
      }
      DataPolicy data = c.policy().getData();
      if (data == null) {
        continue;
      }
      if (data.getRowFilters() != null) {
        for (RowFilter filter : data.getRowFilters()) {
          if (filter != null) {
            rowPredicates.add(resolve(filter, c.policy(), principal, reasons));
          }
        }
      }
      if (data.getColumnRules() != null) {
        for (ColumnRule rule : data.getColumnRules()) {
          if (rule != null) {
            applyColumnRule(rule, c, principal, asset, context, proposals, releases, reasons);
          }
        }
      }
    }

    Set<String> hidden = new LinkedHashSet<>();
    List<ResolvedColumnMask> masks = new ArrayList<>();

    for (Map.Entry<String, List<MaskCandidate>> entry : proposals.entrySet()) {
      String column = entry.getKey();
      List<Candidate> released = releases.getOrDefault(column, List.of());
      MaskCandidate winner = null;
      boolean hide = false;
      for (MaskCandidate proposal : entry.getValue()) {
        if (releasedBy(proposal.source(), released)) {
          reasons.add(
              reason(
                  proposal.source().policy(),
                  false,
                  "restriction on "
                      + column
                      + " released by a more specific policy, permitted because this one sets "
                      + "allowLocalOverride"));
          continue;
        }
        if (proposal.hide()) {
          hide = true;
          continue;
        }
        winner = stricter(winner, proposal);
      }
      if (hide) {
        // Hiding removes the column from the projection entirely (FR-4.5), so
        // there is nothing left for a mask to apply to.
        hidden.add(column);
        continue;
      }
      if (winner != null) {
        masks.add(
            new ResolvedColumnMask()
                .withColumn(column)
                .withMasking(winner.masking())
                .withCondition(winner.condition())
                .withSourcePolicyId(winner.source().policy().getId())
                .withSourceScopeLevel(winner.source().policy().getScopeLevel()));
      }
    }

    // Deterministic order, so two runs of the same inputs — and therefore the
    // three compilers — produce byte-identical output (FR-6.0c). The predicates
    // are sorted for the same reason as the masks: they are ANDed together, so
    // the order carries no meaning, and leaving it to follow whatever order the
    // policies were loaded in turns FR-6.0c's byte comparison into a diff that
    // appears and disappears with the row order of a query nobody is looking at.
    rowPredicates.sort(Comparator.comparing(PolicyEngine::signature));
    masks.sort((a, b) -> nullSafe(a.getColumn()).compareTo(nullSafe(b.getColumn())));
    List<String> sortedHidden = new ArrayList<>(hidden);
    Collections.sort(sortedHidden);
    return new DataOutcome(rowPredicates, masks, sortedHidden);
  }

  private void applyColumnRule(
      ColumnRule rule,
      Candidate source,
      Principal principal,
      AssetContext asset,
      RequestContext context,
      Map<String, List<MaskCandidate>> proposals,
      Map<String, List<Candidate>> releases,
      List<DecisionReason> reasons) {

    ColumnRule.Action action = rule.getAction() == null ? ColumnRule.Action.MASK : rule.getAction();
    // A null selector means "every column" here, and only here: everywhere else
    // an empty selector matches nothing. The difference is deliberate — a
    // column rule that names no columns is a rule about the whole table.
    boolean selectsEverything = rule.getColumns() == null;
    if (selectsEverything && action == ColumnRule.Action.ALLOW) {
      // Reading it as "every column" is safe for MASK and HIDE and reckless for
      // ALLOW, so an unfinished ALLOW releases nothing.
      reasons.add(
          reason(
              source.policy(),
              false,
              "column rule with action ALLOW names no columns; it releases nothing, because an "
                  + "unfinished rule must not unmask the whole table"));
      return;
    }

    String condition = rule.getCondition();
    if (condition != null && !condition.isBlank()) {
      ExpressionEvaluator.Result result;
      try {
        result = config.expressions().evaluate(condition, principal, asset, context);
      } catch (ExpressionUnavailableException e) {
        // Undecidable: keep the restriction and hand the text to the compiler,
        // which can render it as a cell-level condition in the generated SQL.
        result = ExpressionEvaluator.Result.ROW_DEPENDENT;
      }
      if (result == ExpressionEvaluator.Result.FALSE) {
        reasons.add(
            reason(
                source.policy(),
                false,
                "column rule condition is false for this principal: " + condition));
        return;
      }
      if (result == ExpressionEvaluator.Result.TRUE) {
        // Settled here, so the compiler need not re-test it for every row.
        condition = null;
      }
      if (result == ExpressionEvaluator.Result.ROW_DEPENDENT
          && action == ColumnRule.Action.ALLOW) {
        // A restriction is kept or dropped; there is no way to record "released
        // for some rows only". A MASK can carry an undecided condition down to
        // the compiler and become a cell mask, but a release has nowhere to put
        // it, and dropping it hands the column in plaintext to exactly the
        // principals the condition existed to exclude. Every other undecidable
        // input here counts against the principal and so does this one.
        reasons.add(
            reason(
                source.policy(),
                false,
                "column rule with action ALLOW carries a condition that cannot be decided "
                    + "without a row ("
                    + condition
                    + "); it releases nothing, because a release cannot be made conditional"));
        return;
      }
    }

    List<ColumnContext> columns = asset.columns() == null ? List.of() : asset.columns();
    for (ColumnContext column : columns) {
      if (!selectsEverything && !SelectorMatcher.matches(rule.getColumns(), column)) {
        continue;
      }
      if (action == ColumnRule.Action.ALLOW) {
        releases.computeIfAbsent(column.name(), k -> new ArrayList<>()).add(source);
        continue;
      }
      MaskingSpec spec = rule.getMasking();
      if (action == ColumnRule.Action.MASK && (spec == null || spec.getFunction() == null)) {
        // A MASK with no function is a policy that meant to conceal something.
        // Falling back to the strictest function keeps that intent; falling
        // back to plaintext would invert it.
        spec = new MaskingSpec().withFunction(MaskingSpec.MaskingFunction.NULLIFY);
        reasons.add(
            reason(
                source.policy(),
                true,
                "column rule on "
                    + column.name()
                    + " asks for a mask but names no masking function; NULLIFY applied"));
      }
      proposals
          .computeIfAbsent(column.name(), k -> new ArrayList<>())
          .add(new MaskCandidate(source, spec, condition, action == ColumnRule.Action.HIDE));
    }
  }

  /**
   * A restriction is released only when the policy that imposed it consented to
   * being relaxed and the release comes from a strictly more specific layer.
   * Without both, a local policy could quietly undo a global one.
   */
  private static boolean releasedBy(Candidate restriction, List<Candidate> releases) {
    if (!Boolean.TRUE.equals(restriction.policy().getAllowLocalOverride())) {
      return false;
    }
    for (Candidate release : releases) {
      if (release.layer() > restriction.layer()) {
        return true;
      }
    }
    return false;
  }

  private static MaskCandidate stricter(MaskCandidate incumbent, MaskCandidate candidate) {
    if (incumbent == null) {
      return candidate;
    }
    int byRank = MaskStrength.rank(candidate.masking()) - MaskStrength.rank(incumbent.masking());
    if (byRank != 0) {
      return byRank > 0 ? candidate : incumbent;
    }
    // Same function: an unconditional mask outranks a conditional one, which
    // releases the value on the rows where its condition does not hold.
    if (incumbent.condition() != null && candidate.condition() == null) {
      return candidate;
    }
    // A genuine tie: same function, and both unconditional or both
    // conditional. Keeping the incumbent here is not order-independence, it
    // only looks like it. The mask itself comes out the same either way, so
    // the generated SQL is safe; what moves is the policy named on it, and
    // that is what the explanation screen shows an owner who asks why a
    // column is blank. Getting a different policy on a refresh sends them to
    // edit whichever rule happened to load first.
    //
    // The broader layer wins, because it is the one that would still impose
    // the mask if the other were deleted, and the id settles whatever is
    // left so that the order is total rather than merely usually stable.
    int byBreadth = breadth(incumbent) - breadth(candidate);
    if (byBreadth != 0) {
      return byBreadth > 0 ? candidate : incumbent;
    }
    return maskId(candidate).compareTo(maskId(incumbent)) < 0 ? candidate : incumbent;
  }

  /** How wide a candidate's layer is. ORG is 0; an absent level never wins a tie. */
  private static int breadth(MaskCandidate candidate) {
    ResolvedColumnMask.ScopeLevel level = candidate.source().policy().getScopeLevel();
    return level == null ? Integer.MAX_VALUE : level.ordinal();
  }

  /** A candidate's policy id as a comparable string, never null. */
  private static String maskId(MaskCandidate candidate) {
    return nullSafe(String.valueOf(candidate.source().policy().getId()));
  }

  /**
   * A total order over predicates. Every field participates, including the
   * source policy, so that two predicates compare equal only when they say the
   * same thing — in which case which one survives the sort cannot matter.
   */
  private static String signature(ResolvedRowPredicate predicate) {
    return nullSafe(String.valueOf(predicate.getKind()))
        + ' '
        + nullSafe(predicate.getColumn())
        + ' '
        + nullSafe(String.valueOf(predicate.getOperator()))
        + ' '
        + nullSafe(String.valueOf(predicate.getValues()))
        + ' '
        + nullSafe(predicate.getEntitlementKey())
        + ' '
        + nullSafe(predicate.getRawPredicate())
        + ' '
        + nullSafe(String.valueOf(predicate.getSourcePolicyId()));
  }

  private ResolvedRowPredicate resolve(
      RowFilter filter, Policy policy, Principal principal, List<DecisionReason> reasons) {

    ResolvedRowPredicate out = new ResolvedRowPredicate().withSourcePolicyId(policy.getId());
    RowFilter.Kind kind = filter.getKind() == null ? RowFilter.Kind.ALWAYS_FALSE : filter.getKind();

    switch (kind) {
      case ATTRIBUTE_COMPARE:
      case IN_LIST: {
        List<String> values = principal.attributeValues(filter.getUserAttribute(), null);
        if (values.isEmpty()) {
          // The filter says "rows where branch = your branch" and the principal
          // has no branch. Dropping the filter would show every row; an empty
          // comparison set is the honest answer.
          reasons.add(
              reason(
                  policy,
                  true,
                  "row filter needs the attribute "
                      + filter.getUserAttribute()
                      + ", which this principal does not have; no rows match"));
          return out.withKind(ResolvedRowPredicate.Kind.ALWAYS_FALSE);
        }
        return out.withKind(ResolvedRowPredicate.Kind.fromValue(kind.value()))
            .withColumn(filter.getColumn())
            .withOperator(filter.getOperator())
            .withValues(new ArrayList<Object>(values));
      }
      case ENTITLEMENT_JOIN:
        return out.withKind(ResolvedRowPredicate.Kind.ENTITLEMENT_JOIN)
            .withColumn(filter.getColumn())
            .withEntitlementKey(filter.getEntitlementKey());
      case RAW_PREDICATE:
        return out.withKind(ResolvedRowPredicate.Kind.RAW_PREDICATE)
            .withRawPredicate(filter.getRawPredicate());
      case ALWAYS_FALSE:
      default:
        return out.withKind(ResolvedRowPredicate.Kind.ALWAYS_FALSE);
    }
  }

  // -------------------------------------------------------------- utilities

  private static Policy.Effect effectOf(Policy policy) {
    // The schema defaults effect to ALLOW; a policy built in code rather than
    // deserialised can still arrive without one.
    return policy.getEffect() == null ? Policy.Effect.ALLOW : policy.getEffect();
  }

  private static DecisionReason reason(Policy policy, boolean matched, String explanation) {
    return new DecisionReason()
        .withPolicyId(policy.getId())
        .withPolicyName(policy.getName())
        .withScopeLevel(policy.getScopeLevel())
        .withScopeFqn(policy.getScopeFqn())
        .withEffect(DecisionReason.Effect.fromValue(effectOf(policy).value()))
        .withMatched(matched)
        .withExplanation(explanation);
  }

  /**
   * The name the composer signs its own reasons with.
   *
   * <p>Public because a caller reading a refusal has to be able to tell a
   * policy's verdict from the composer's summary of all of them, and comparing
   * against a copy of this string in another module is how the two drift.
   */
  public static final String COMPOSITION = "(composition)";

  /**
   * A reason that belongs to the composition itself rather than to one policy.
   * {@code policyName} is required by the schema, so the composer names itself.
   */
  private static DecisionReason bareReason(boolean matched, String explanation) {
    return new DecisionReason()
        .withPolicyName(COMPOSITION)
        .withMatched(matched)
        .withExplanation(explanation);
  }

  /**
   * A key the decision cache can be keyed on (FR-5.5).
   *
   * <p>It covers the principal, the asset, and the identity and version of every
   * policy that bound — so editing a policy changes the key without anyone
   * having to remember to invalidate it. When a bound policy is time-dependent
   * the current minute goes in too, because a decision that was correct at 17:59
   * is wrong at 18:01 and no policy edit will have signalled that.
   */
  private static String cacheKey(
      Principal principal, AssetContext asset, RequestContext context, List<Candidate> bound) {

    List<String> parts = new ArrayList<>();
    boolean timeDependent = false;
    for (Candidate c : bound) {
      parts.add(c.policy().getId() + ":" + c.policy().getVersion());
      if (c.policy().getSubject() != null && c.policy().getSubject().getTime() != null) {
        timeDependent = true;
      }
    }
    Collections.sort(parts);

    StringBuilder sb = new StringBuilder();
    sb.append(principal.id()).append('|').append(asset.fqn()).append('|');
    sb.append(String.join(",", parts));
    if (timeDependent) {
      sb.append('|').append(context.at().truncatedTo(ChronoUnit.MINUTES));
    }
    return sha256(sb.toString());
  }

  private static String sha256(String input) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every JVM this runs on", e);
    }
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }
}
