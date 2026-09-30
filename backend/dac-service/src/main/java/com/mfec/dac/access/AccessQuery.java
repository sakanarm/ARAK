package com.mfec.dac.access;

import com.mfec.dac.engine.AssetContext;
import com.mfec.dac.engine.PolicyEngine;
import com.mfec.dac.engine.Principal;
import com.mfec.dac.engine.RequestContext;
import com.mfec.dac.policy.AssetContextLoader;
import com.mfec.dac.policy.PolicyStore;
import com.mfec.dac.policy.PrincipalLoader;
import com.mfec.dac.schema.api.DecisionReason;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.entity.policy.Policy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;

/**
 * "Who can reach this table, and why" (FR-7.3, FR-3.1.5).
 *
 * <p>The question the asset page's Access tab asks. It is answered by running
 * the engine, not by reading the grant table and the policy table and adding
 * them up. Those two answers differ constantly — a grant that a global DENY
 * overrules is in the grant table and is not access — and a page that showed
 * the second answer would be a page that tells owners they have given access
 * they have not given.
 *
 * <p>So every row here is the verdict the engine would return for a real query
 * by that person at this moment, and the {@code via} field says which policy or
 * grant produced it. A grant with nobody behind it is reported as a grant that
 * currently grants nothing, which is the single most useful thing this page can
 * tell someone.
 */
public class AccessQuery {

  /**
   * How many people are evaluated.
   *
   * <p>The same cap as impact analysis, for the same reason: this runs the
   * engine once per person and the page must answer while somebody is looking
   * at it. Above the cap the answer is honest about being a sample rather than
   * quietly short.
   */
  public static final int PRINCIPAL_LIMIT = 200;

  /** The prefix {@link GrantStore#asPolicy} gives every synthetic policy. */
  static final String GRANT_PREFIX = "grant:";

  private final Jdbi jdbi;
  private final AssetContextLoader contexts;
  private final PrincipalLoader principals;
  private final PolicyStore policies;
  private final GrantStore grants;
  private final PolicyEngine engine;

  public AccessQuery(
      Jdbi jdbi,
      AssetContextLoader contexts,
      PrincipalLoader principals,
      PolicyStore policies,
      GrantStore grants,
      PolicyEngine engine) {
    this.jdbi = jdbi;
    this.contexts = contexts;
    this.principals = principals;
    this.policies = policies;
    this.grants = grants;
    this.engine = engine;
  }

  /** Where one person's access comes from. */
  public enum Origin {
    /** A grant naming them, or naming a group they are in. */
    GRANT,
    /** A policy whose subject rule they satisfy. */
    POLICY,
    /** Both, which means revoking the grant would not remove their access. */
    BOTH
  }

  /**
   * One person who can read this table.
   *
   * @param restricted true when they get in but see less than everything —
   *     masked columns, hidden columns or row filters. The tab has to
   *     distinguish "can read it" from "can read all of it".
   */
  public record PersonAccess(
      String principal,
      Origin origin,
      List<String> viaPolicies,
      List<String> viaGrants,
      boolean restricted,
      int maskedColumns,
      int hiddenColumns,
      int rowFilters) {}

  /**
   * One grant, with what it is actually doing.
   *
   * @param live whether the window is open and it is not revoked
   * @param effectiveFor how many of the evaluated people are allowed <em>by
   *     this grant</em>. Zero on a live grant means a policy is overruling it.
   * @param purpose the register key it was given for; null when none was
   */
  public record GrantAccess(
      String id,
      String principal,
      String displayName,
      String principalType,
      String principalSource,
      Instant validFrom,
      Instant validUntil,
      String reason,
      String grantedBy,
      Instant grantedAt,
      boolean live,
      int effectiveFor,
      String purpose) {}

  /**
   * The whole tab.
   *
   * @param principalsKnown how many people exist
   * @param principalsEvaluated how many were measured
   * @param sampled true when those two differ, so the page can say so
   */
  public record AssetAccess(
      String assetFqn,
      boolean known,
      List<GrantAccess> grants,
      List<PersonAccess> people,
      int principalsKnown,
      int principalsEvaluated,
      boolean sampled,
      Instant evaluatedAt) {}

  /** Runs the whole tab for one asset. */
  public AssetAccess onAsset(String assetFqn) {
    Instant now = Instant.now();

    return jdbi.withHandle(
        handle -> {
          List<GrantStore.StoredGrant> stored = grants.onAsset(assetFqn);
          AssetContext asset = contexts.load(handle, assetFqn).orElse(null);
          if (asset == null) {
            // The grants are still worth showing: a grant on an asset the crawl
            // has lost is exactly the row somebody needs to see and clean up.
            // What cannot be shown is who it lets in, because nothing here can
            // be evaluated -- so no access is claimed either way.
            return new AssetAccess(
                assetFqn, false, describe(stored, Map.of(), now), List.of(), 0, 0, false, now);
          }

          List<Policy> documents = new ArrayList<>();
          for (PolicyStore.StoredPolicy one :
              policies.activeFor(assetFqn, com.mfec.dac.policy.DecisionService.DEFAULT_ENVIRONMENT)) {
            documents.add(one.document());
          }
          documents.addAll(grants.policiesFor(handle, assetFqn, now));

          int known = principals.countEveryone(handle);
          List<Principal> people = principals.everyone(handle, PRINCIPAL_LIMIT);
          RequestContext context = RequestContext.at(now);

          List<PersonAccess> allowed = new ArrayList<>();
          Map<String, Integer> perGrant = new TreeMap<>();

          for (Principal person : people) {
            PolicyDecision decision = engine.evaluate(person, asset, context, documents);
            if (!Boolean.TRUE.equals(decision.getAllowed())) {
              continue;
            }
            Set<String> viaPolicies = new LinkedHashSet<>();
            Set<String> viaGrants = new LinkedHashSet<>();
            split(decision, viaPolicies, viaGrants);
            for (String grantId : viaGrants) {
              perGrant.merge(grantId, 1, Integer::sum);
            }

            int masked = size(decision.getColumnMasks());
            int hidden = size(decision.getHiddenColumns());
            int rows = size(decision.getRowPredicates());
            allowed.add(
                new PersonAccess(
                    person.id(),
                    origin(viaPolicies, viaGrants),
                    List.copyOf(viaPolicies),
                    List.copyOf(viaGrants),
                    masked + hidden + rows > 0,
                    masked,
                    hidden,
                    rows));
          }

          allowed.sort(
              Comparator.comparingInt((PersonAccess one) -> one.origin().ordinal())
                  .thenComparing(PersonAccess::principal));

          return new AssetAccess(
              assetFqn,
              true,
              describe(stored, perGrant, now),
              List.copyOf(allowed),
              known,
              people.size(),
              known > people.size(),
              now);
        });
  }

  /** Every grant this person holds directly, for "my access" (FR-7.3). */
  public List<GrantStore.StoredGrant> heldBy(UUID principalId) {
    return grants.heldBy(principalId);
  }

  // ------------------------------------------------------------- internals

  /**
   * Splits a decision's reasons into the policies and the grants that allowed.
   *
   * <p>By the synthetic name rather than by a flag on the reason, because the
   * engine has no concept of a grant and must not acquire one: the moment it
   * could tell them apart it could treat them differently, and the whole point
   * of rendering a grant as a policy is that it cannot.
   */
  private static void split(PolicyDecision decision, Set<String> policies, Set<String> grants) {
    List<DecisionReason> reasons = decision.getReasons();
    if (reasons == null) {
      return;
    }
    for (DecisionReason reason : reasons) {
      if (!Boolean.TRUE.equals(reason.getMatched())
          || reason.getEffect() != DecisionReason.Effect.ALLOW) {
        continue;
      }
      String name = reason.getPolicyName();
      if (name == null) {
        continue;
      }
      if (name.startsWith(GRANT_PREFIX)) {
        grants.add(name.substring(GRANT_PREFIX.length()));
      } else {
        policies.add(name);
      }
    }
  }

  private static Origin origin(Set<String> policies, Set<String> grants) {
    if (grants.isEmpty()) {
      return Origin.POLICY;
    }
    return policies.isEmpty() ? Origin.GRANT : Origin.BOTH;
  }

  private static List<GrantAccess> describe(
      List<GrantStore.StoredGrant> stored, Map<String, Integer> perGrant, Instant now) {
    List<GrantAccess> out = new ArrayList<>(stored.size());
    for (GrantStore.StoredGrant grant : stored) {
      out.add(
          new GrantAccess(
              grant.id().toString(),
              grant.username(),
              grant.displayName(),
              grant.principalType(),
              grant.principalSource(),
              grant.validFrom(),
              grant.validUntil(),
              grant.reason(),
              grant.grantedBy(),
              grant.grantedAt(),
              grant.liveAt(now),
              perGrant.getOrDefault(grant.id().toString(), 0),
              grant.purpose()));
    }
    return List.copyOf(out);
  }

  private static int size(List<?> list) {
    return list == null ? 0 : list.size();
  }

  /** Exposed for the resource, which needs to look a grant up before revoking it. */
  public Optional<GrantStore.StoredGrant> find(UUID id) {
    return grants.find(id);
  }
}
