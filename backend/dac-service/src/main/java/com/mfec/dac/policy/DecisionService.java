package com.mfec.dac.policy;

import com.mfec.dac.engine.AssetContext;
import com.mfec.dac.engine.PolicyEngine;
import com.mfec.dac.engine.Principal;
import com.mfec.dac.engine.RequestContext;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.entity.policy.Policy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 */
public class DecisionService {

  /** Environments are separate policy sets (FR-9.4); one has to be named. */
  public static final String DEFAULT_ENVIRONMENT = "prod";

  private final Jdbi jdbi;
  private final AssetContextLoader contexts;
  private final PrincipalLoader principals;
  private final PolicyStore policies;
  private final PolicyEngine engine;

  public DecisionService(
      Jdbi jdbi,
      AssetContextLoader contexts,
      PrincipalLoader principals,
      PolicyStore policies,
      PolicyEngine engine) {
    this.jdbi = jdbi;
    this.contexts = contexts;
    this.principals = principals;
    this.policies = policies;
    this.engine = engine;
  }

  /**
   * What the caller is asking about.
   *
   * @param principal the username being decided for — not necessarily the
   *     caller, because "view as user" is the whole point of FR-5.2
   * @param at the moment to evaluate at; a time-windowed policy answers
   *     differently at 20:00, and being able to pass the time is what makes
   *     that testable rather than something you wait until evening to see
   */
  public record Ask(
      String principal,
      String assetFqn,
      Instant at,
      String ip,
      String purpose,
      String environment) {

    public Ask {
      at = at == null ? Instant.now() : at;
      environment = environment == null || environment.isBlank() ? DEFAULT_ENVIRONMENT : environment;
    }

    public static Ask of(String principal, String assetFqn) {
      return new Ask(principal, assetFqn, null, null, null, null);
    }
  }

  public PolicyDecision decide(Ask ask) {
    return jdbi.withHandle(
        handle -> {
          Principal who = principals.find(handle, ask.principal()).orElse(null);
          if (who == null) {
            return denied(
                ask,
                "No principal named " + ask.principal() + " is known to the platform");
          }

          AssetContext asset = contexts.load(handle, ask.assetFqn()).orElse(null);
          if (asset == null) {
            // Not an error: an asset the crawl has never seen is an asset no
            // policy can be bound to, and letting a query touch it because we
            // have nothing to say about it is precisely the hole FR-1.6 exists
            // to close.
            return denied(
                ask,
                "No asset " + ask.assetFqn() + " is in the metadata cache, so no policy governs it");
          }

          List<PolicyStore.StoredPolicy> stored =
              policies.activeFor(ask.assetFqn(), ask.environment());
          List<Policy> documents = new ArrayList<>(stored.size());
          for (PolicyStore.StoredPolicy one : stored) {
            documents.add(one.document());
          }

          RequestContext context =
              RequestContext.at(ask.at()).fromIp(ask.ip()).forPurpose(ask.purpose());

          return engine.evaluate(who, asset, context, documents);
        });
  }

  /**
   * A denial the engine never saw, spelled the same way as one it did.
   *
   * <p>Callers must not have to tell "denied by policy" apart from "denied
   * because something was missing" by inspecting which fields are null.
   */
  private static PolicyDecision denied(Ask ask, String why) {
    return new PolicyDecision()
        .withPrincipal(ask.principal())
        .withAssetFqn(ask.assetFqn())
        .withAllowed(false)
        .withEvaluatedAt(ask.at())
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
