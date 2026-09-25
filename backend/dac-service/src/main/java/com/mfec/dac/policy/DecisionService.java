package com.mfec.dac.policy;

import com.mfec.dac.access.GrantStore;
import com.mfec.dac.engine.AssetContext;
import com.mfec.dac.engine.DecisionValidity;
import com.mfec.dac.engine.PolicyEngine;
import com.mfec.dac.engine.Principal;
import com.mfec.dac.engine.RequestContext;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.entity.policy.Policy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;

/**
 * The one place the engine is actually called.
 *
 * <p>Until this existed the engine was exercised only by its own tests, which
 * is a comfortable place for a policy engine to stay and a dangerous one: the
 * hard part was never the evaluation but assembling honest inputs for it. This
 * class is that assembly, and every runtime path — the simulator (FR-5.2), the
 * query proxy (FR-6.3), the compilers — goes through it so that a decision
 * shown in a preview is the same object that governs a query.
 *
 * <h2>Fail closed at every gap</h2>
 *
 * <p>An unknown principal, an asset not in the cache and an asset with no
 * policies bound to it all produce a denial rather than an exception or a
 * default allow. Each carries a reason saying which of the three it was,
 * because "denied" without that distinction sends people looking in the wrong
 * place.
 *
 * <h2>The cache sits here, not in the engine</h2>
 *
 * <p>What costs time is this method, not the evaluation it ends with: three
 * database reads and a JSON parse against arithmetic in memory. So the cache
 * (FR-5.5) wraps the assembly rather than the engine, and the engine stays a
 * pure function of its inputs — which is what lets the simulator, the impact
 * report and the three compilers all call it without wondering whose cached
 * answer they are looking at.
 *
 * <p>Only an ask that did not pin a time is cached. A simulation of 20:00
 * asks a different question from the one a query at 14:00 asks, and neither may
 * answer the other.
 *
 * <h2>Direct grants arrive as policies</h2>
 *
 * <p>A grant (FR-7) is read here alongside the bound policies and appended to
 * the same list. It is not a second authorisation path with its own rules: it
 * enters the engine as a TABLE-layer ALLOW and is composed by the same
 * intersection as everything else, which is what makes "a grant cannot open a
 * table a global policy has closed" (FR-3.1.4) a property of the code rather
 * than a promise in a document.
 */
public class DecisionService {

  /** Environments are separate policy sets (FR-9.4); one has to be named. */
  public static final String DEFAULT_ENVIRONMENT = "prod";

  private final Jdbi jdbi;
  private final AssetContextLoader contexts;
  private final PrincipalLoader principals;
  private final PolicyStore policies;
  private final GrantStore grants;
  private final PolicyEngine engine;
  private final DecisionCache cache;

  public DecisionService(
      Jdbi jdbi,
      AssetContextLoader contexts,
      PrincipalLoader principals,
      PolicyStore policies,
      PolicyEngine engine) {
    this(jdbi, contexts, principals, policies, null, engine, DecisionCache.disabled());
  }

  /**
   * @param grants may be null, which means "no grants exist in this deployment"
   *     and is how a test that is only about policies avoids standing one up. It
   *     does not mean "grants are ignored": where a store is given, every
   *     decision reads it.
   */
  public DecisionService(
      Jdbi jdbi,
      AssetContextLoader contexts,
      PrincipalLoader principals,
      PolicyStore policies,
      GrantStore grants,
      PolicyEngine engine,
      DecisionCache cache) {
    this.jdbi = jdbi;
    this.contexts = contexts;
    this.principals = principals;
    this.policies = policies;
    this.grants = grants;
    this.engine = engine;
    this.cache = cache == null ? DecisionCache.disabled() : cache;
  }

  public DecisionCache cache() {
    return cache;
  }

  /**
   * What the caller is asking about.
   *
   * @param principal the username being decided for — not necessarily the
   *     caller, because "view as user" is the whole point of FR-5.2
   * @param at the moment to evaluate at, or null to mean now. A time-windowed
   *     policy answers differently at 20:00, and being able to pass the time is
   *     what makes that testable rather than something you wait until evening
   *     to see. Leaving it null is not the same as passing {@code Instant.now()}:
   *     null says "whenever this runs", and only that is cacheable.
   */
  public record Ask(
      String principal,
      String assetFqn,
      Instant at,
      String ip,
      String purpose,
      String environment) {

    public Ask {
      environment = environment == null || environment.isBlank() ? DEFAULT_ENVIRONMENT : environment;
    }

    public static Ask of(String principal, String assetFqn) {
      return new Ask(principal, assetFqn, null, null, null, null);
    }

    /**
     * The instant to evaluate against.
     *
     * <p>Read once per decision and carried from there. Calling this twice in
     * one evaluation would be two different instants, and a decision that was
     * checked against one clock and stamped with another is a decision nobody
     * can reproduce.
     */
    public Instant when() {
      return at == null ? Instant.now() : at;
    }

    /** True when this asks about now rather than about a chosen moment. */
    public boolean live() {
      return at == null;
    }
  }

  public PolicyDecision decide(Ask ask) {
    Instant now = ask.when();
    DecisionCache.Key key =
        ask.live()
            ? new DecisionCache.Key(
                ask.principal(), ask.assetFqn(), ask.environment(), ask.ip(), ask.purpose())
            : null;

    if (key != null) {
      Optional<PolicyDecision> held = cache.get(key, now);
      if (held.isPresent()) {
        return held.get();
      }
    }

    // Read before anything else, and carried through to the put below. A flush
    // that happens while the three reads underneath are in flight has to beat
    // this answer, not be beaten by it.
    long readAt = cache.generation();

    return jdbi.withHandle(
        handle -> {
          Principal who = principals.find(handle, ask.principal()).orElse(null);
          if (who == null) {
            // Held like any other answer. An unknown name is what a scan of the
            // estate produces thousands of, and re-deriving "no such person" by
            // querying the directory each time is the one case where the cache
            // earns its keep without any policy being involved at all. The
            // moment the person is created, the identity store announces it and
            // this goes.
            return hold(
                key,
                now,
                denied(ask, now, "No principal named " + ask.principal() + " is known to the platform"),
                null,
                readAt);
          }

          AssetContext asset = contexts.load(handle, ask.assetFqn()).orElse(null);
          if (asset == null) {
            // Not an error: an asset the crawl has never seen is an asset no
            // policy can be bound to, and letting a query touch it because we
            // have nothing to say about it is precisely the hole FR-1.6 exists
            // to close.
            return hold(
                key,
                now,
                denied(
                    ask,
                    now,
                    "No asset " + ask.assetFqn() + " is in the metadata cache, so no policy governs it"),
                null,
                readAt);
          }

          List<Policy> documents = stack(handle, ask, now);

          RequestContext context =
              RequestContext.at(now).fromIp(ask.ip()).forPurpose(ask.purpose());

          PolicyDecision decision = engine.evaluate(who, asset, context, documents);
          // The stack the decision was made from, not only the part of it that
          // matched: a policy that starts applying at midnight has to end this
          // entry even though it had nothing to say about today.
          return hold(
              key, now, decision, DecisionValidity.until(documents, now).orElse(null), readAt);
        });
  }

  /**
   * The decision this principal would get if the asset's owner granted them the
   * asset directly -- the question behind the query page's "ask the owner"
   * button.
   *
   * <p>Asked of the engine rather than guessed at from the refusal, because a
   * grant can only do what a grant can do: it enters at the TABLE layer and
   * composes by intersection, so it opens a table nothing else speaks to and
   * never passes a DENY or a higher layer that refuses this person (V11,
   * {@code GrantCompositionIT}). Offering to route a request that approval
   * could not satisfy would send an owner a question whose "yes" changes
   * nothing, and tell the requester they had been let in when they had not.
   *
   * <p>Never cached and never cacheable: it is a decision about a world that
   * does not exist, and holding it next to real ones is how it would one day be
   * served as one.
   */
  public PolicyDecision decideAsIfGranted(Ask ask) {
    Instant now = ask.when();
    return jdbi.withHandle(
        handle -> {
          Principal who = principals.find(handle, ask.principal()).orElse(null);
          if (who == null) {
            return denied(ask, now, "No principal named " + ask.principal() + " is known to the platform");
          }
          AssetContext asset = contexts.load(handle, ask.assetFqn()).orElse(null);
          if (asset == null) {
            return denied(
                ask,
                now,
                "No asset " + ask.assetFqn() + " is in the metadata cache, so no policy governs it");
          }
          List<Policy> documents = stack(handle, ask, now);
          documents.add(GrantStore.hypothetical(ask.principal(), ask.assetFqn(), now));
          RequestContext context =
              RequestContext.at(now).fromIp(ask.ip()).forPurpose(ask.purpose());
          return engine.evaluate(who, asset, context, documents);
        });
  }

  /**
   * The decision this principal would get if one more policy were in force: a
   * draft somebody wrote or changed to answer an access request, asked about
   * before anybody activates it.
   *
   * <p>The candidate joins the stack only where it is bound to the asset, as
   * activating it would bind it; one that does not reach the asset changes
   * nothing, and {@link Candidate#bound()} says so instead of letting an
   * unchanged decision pass for the policy's verdict. A candidate that is
   * already active is simply part of the stack.
   *
   * <p>Never cached, for the reason {@link #decideAsIfGranted} is not: this is
   * a world that does not exist yet. And nothing here writes: asking whether a
   * draft would open a table is not a way of opening it.
   */
  public Candidate decideWithCandidate(Ask ask, UUID candidate) {
    Instant now = ask.when();
    return jdbi.withHandle(
        handle -> {
          Principal who = principals.find(handle, ask.principal()).orElse(null);
          if (who == null) {
            return new Candidate(
                denied(ask, now, "No principal named " + ask.principal() + " is known to the platform"),
                false);
          }
          AssetContext asset = contexts.load(handle, ask.assetFqn()).orElse(null);
          if (asset == null) {
            return new Candidate(
                denied(
                    ask,
                    now,
                    "No asset " + ask.assetFqn() + " is in the metadata cache, so no policy governs it"),
                false);
          }
          List<PolicyStore.StoredPolicy> stored =
              policies.activeForIncluding(ask.assetFqn(), ask.environment(), candidate);
          boolean bound = false;
          for (PolicyStore.StoredPolicy one : stored) {
            if (candidate.equals(one.id())) {
              bound = true;
              // Read as it would be once activated. The copy is this call's own,
              // freshly read, so nothing stored or cached sees the change.
              one.document().setLifecycleState(Policy.LifecycleState.ACTIVE);
            }
          }
          List<Policy> documents = withGrants(handle, ask, now, stored);
          RequestContext context =
              RequestContext.at(now).fromIp(ask.ip()).forPurpose(ask.purpose());
          return new Candidate(engine.evaluate(who, asset, context, documents), bound);
        });
  }

  /**
   * A decision made with a candidate policy in the stack.
   *
   * @param bound the candidate reaches the asset, so the decision includes it;
   *     false means the decision is the asset's as it stands
   */
  public record Candidate(PolicyDecision decision, boolean bound) {}

  /** Every policy document that speaks to this asset at this moment, grants included. */
  private List<Policy> stack(org.jdbi.v3.core.Handle handle, Ask ask, Instant now) {
    return withGrants(handle, ask, now, policies.activeFor(ask.assetFqn(), ask.environment()));
  }

  private List<Policy> withGrants(
      org.jdbi.v3.core.Handle handle, Ask ask, Instant now, List<PolicyStore.StoredPolicy> stored) {
    List<Policy> documents = new ArrayList<>(stored.size() + 1);
    for (PolicyStore.StoredPolicy one : stored) {
      documents.add(one.document());
    }

    // Appended rather than merged: the engine sorts by scope level, and a
    // grant is a TABLE-layer policy like any other. Reading at `now`
    // rather than filtering later means a simulation of next Tuesday sees
    // the grants that will exist then, not the ones that exist today.
    if (grants != null) {
      documents.addAll(grants.policiesFor(handle, ask.assetFqn(), now));
    }
    return documents;
  }

  /** Puts a freshly made decision in the cache, when there is a cache to put it in. */
  private PolicyDecision hold(
      DecisionCache.Key key,
      Instant now,
      PolicyDecision decision,
      Instant expiresAt,
      long readAt) {
    if (key != null) {
      cache.put(key, decision, now, expiresAt, readAt);
    }
    return decision;
  }

  /**
   * A denial the engine never saw, spelled the same way as one it did.
   *
   * <p>Callers must not have to tell "denied by policy" apart from "denied
   * because something was missing" by inspecting which fields are null.
   */
  private static PolicyDecision denied(Ask ask, Instant at, String why) {
    return new PolicyDecision()
        .withPrincipal(ask.principal())
        .withAssetFqn(ask.assetFqn())
        .withAllowed(false)
        .withEvaluatedAt(at)
        .withFromCache(false)
        .withRowPredicates(List.of())
        .withColumnMasks(List.of())
        .withHiddenColumns(List.of())
        .withUnenforceable(List.of())
        .withReasons(
            List.of(
                new com.mfec.dac.schema.api.DecisionReason()
                    .withPolicyName("(none)")
                    .withMatched(false)
                    .withEffect(com.mfec.dac.schema.api.DecisionReason.Effect.DENY)
                    .withExplanation(why)));
  }
}
