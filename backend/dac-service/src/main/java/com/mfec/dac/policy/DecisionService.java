package com.mfec.dac.policy;

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
 */
public class DecisionService {

  /** Environments are separate policy sets (FR-9.4); one has to be named. */
  public static final String DEFAULT_ENVIRONMENT = "prod";

  private final Jdbi jdbi;
  private final AssetContextLoader contexts;
  private final PrincipalLoader principals;
  private final PolicyStore policies;
  private final PolicyEngine engine;
  private final DecisionCache cache;

  public DecisionService(
      Jdbi jdbi,
      AssetContextLoader contexts,
      PrincipalLoader principals,
      PolicyStore policies,
      PolicyEngine engine) {
    this(jdbi, contexts, principals, policies, engine, DecisionCache.disabled());
  }

  public DecisionService(
      Jdbi jdbi,
      AssetContextLoader contexts,
      PrincipalLoader principals,
      PolicyStore policies,
      PolicyEngine engine,
      DecisionCache cache) {
    this.jdbi = jdbi;
    this.contexts = contexts;
    this.principals = principals;
    this.policies = policies;
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

          List<PolicyStore.StoredPolicy> stored =
              policies.activeFor(ask.assetFqn(), ask.environment());
          List<Policy> documents = new ArrayList<>(stored.size());
          for (PolicyStore.StoredPolicy one : stored) {
            documents.add(one.document());
          }

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
