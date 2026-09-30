package com.mfec.dac.policy;

import com.mfec.dac.catalog.SourceCatalogImporter;
import com.mfec.dac.common.Fqns;
import com.mfec.dac.schema.api.ResolvedColumnMask.ScopeLevel;
import com.mfec.dac.schema.api.ResolvedRowPredicate.FacetOperator;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.source.DataSourceStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Which connections a policy is written for, read from the policy itself.
 *
 * <p>A policy does not name a connection. It names an anchor and a selector,
 * and a connection is whatever those confine it to: an anchor at the service
 * layer or below sits on one service, and a selector that requires
 * {@code service eq x} (the one the new-policy page writes) can only match
 * assets on {@code x}. Anything else is written for every connection.
 *
 * <p>Only what is certain counts. A selector that could reach a second service
 * -- an {@code or} with one unconfined branch, a {@code not}, a bare
 * {@code schema eq 'dbo'} that matches any service's {@code dbo} -- reads as
 * every connection, because listing one connection for a policy that also
 * applies elsewhere would tell its reader it is narrower than it is.
 *
 * <p>The mode is the connection's, looked up now: it is not stored in the
 * policy, and the source's own mode is what enforces it.
 */
public final class PolicyReach {

  /** Where one policy applies: every connection, or the ones listed. */
  public record Reach(boolean everyConnection, List<Connection> connections) {}

  /**
   * One connection a policy is confined to.
   *
   * @param sourceId null when no registered source has this service, so the
   *     policy names a service ARAK does not connect to
   * @param mode the source's enforcement mode today; null with no source
   */
  public record Connection(
      String service, UUID sourceId, String name, String engine, String mode) {}

  private final Supplier<List<DataSourceStore.Source>> sources;

  public PolicyReach(Supplier<List<DataSourceStore.Source>> sources) {
    this.sources = sources;
  }

  /** The registered sources, read once for a page of policies. */
  public List<DataSourceStore.Source> sources() {
    return sources.get();
  }

  public Reach of(Policy policy, List<DataSourceStore.Source> registered) {
    Set<String> services = services(policy);
    if (services == null) {
      return new Reach(true, List.of());
    }
    List<Connection> out = new ArrayList<>();
    for (String service : services) {
      DataSourceStore.Source match = null;
      for (DataSourceStore.Source source : registered) {
        if (SourceCatalogImporter.serviceOf(source).equalsIgnoreCase(service)) {
          match = source;
          break;
        }
      }
      out.add(
          match == null
              ? new Connection(service, null, service, null, null)
              : new Connection(
                  service,
                  match.id(),
                  match.name(),
                  match.engine().name(),
                  match.defaultEnforcementMode().name()));
    }
    return new Reach(false, List.copyOf(out));
  }

  /**
   * The services a policy is confined to, or null for every connection.
   *
   * <p>An empty set is a policy confined twice to different services -- an
   * anchor on one and a selector on another -- which covers nothing.
   */
  static Set<String> services(Policy policy) {
    Set<String> anchor = null;
    ScopeLevel level = policy.getScopeLevel();
    String fqn = policy.getScopeFqn();
    if (level != null
        && level != ScopeLevel.ORG
        && level != ScopeLevel.DOMAIN
        && fqn != null
        && !fqn.isBlank()) {
      anchor = one(Fqns.segments(fqn.strip()).get(0));
    }
    return intersect(anchor, confined(policy.getSelector()));
  }

  private static Set<String> confined(AssetSelector selector) {
    if (selector == null) {
      return null;
    }
    Set<String> result = null;
    if (selector.getCondition() != null) {
      result = intersect(result, confined(selector.getCondition()));
    }
    if (selector.getAnd() != null) {
      for (AssetSelector child : selector.getAnd()) {
        result = intersect(result, confined(child));
      }
    }
    if (selector.getOr() != null && !selector.getOr().isEmpty()) {
      // Confined only when every branch is: one open branch reaches anywhere.
      Set<String> union = new LinkedHashSet<>();
      boolean all = true;
      for (AssetSelector child : selector.getOr()) {
        Set<String> branch = confined(child);
        if (branch == null) {
          all = false;
          break;
        }
        union.addAll(branch);
      }
      if (all) {
        result = intersect(result, union);
      }
    }
    // A `not` never confines: what it leaves is everywhere but somewhere.
    return result;
  }

  private static Set<String> confined(FacetCondition condition) {
    FacetType facet = condition.getFacet();
    FacetOperator operator = condition.getOperator();
    if (facet == null || operator == null) {
      return null;
    }
    boolean physical =
        facet == FacetType.SERVICE
            || facet == FacetType.DATABASE
            || facet == FacetType.SCHEMA
            || facet == FacetType.TABLE;
    if (!physical) {
      return null;
    }
    List<String> values = new ArrayList<>();
    switch (operator) {
      case EQ, CONTAINS -> {
        if (condition.getValue() instanceof String text) {
          values.add(text);
        }
      }
      case IN -> {
        if (condition.getValues() != null && !condition.getValues().isEmpty()) {
          for (Object value : condition.getValues()) {
            values.add(String.valueOf(value));
          }
        } else if (condition.getValue() instanceof String text) {
          // Saved before lists were lists: the text split at commas, as the
          // matcher reads it.
          for (String part : text.split(",")) {
            values.add(part);
          }
        }
      }
      default -> {
        return null;
      }
    }
    Set<String> services = new LinkedHashSet<>();
    for (String value : values) {
      String text = value.strip();
      if (text.isEmpty()) {
        continue;
      }
      List<String> segments = Fqns.segments(text);
      // Below the service layer `eq` also takes a bare leaf, so `schema eq
      // 'dbo'` matches every service's dbo. Only a qualified name is confined.
      if (facet != FacetType.SERVICE && operator != FacetOperator.CONTAINS
          && segments.size() < 2) {
        return null;
      }
      services.add(segments.get(0));
    }
    return services.isEmpty() ? null : services;
  }

  /** Null means unconfined, so it is the identity; names compare ignoring case. */
  private static Set<String> intersect(Set<String> a, Set<String> b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    Set<String> lower = new LinkedHashSet<>();
    for (String name : b) {
      lower.add(name.toLowerCase(Locale.ROOT));
    }
    Set<String> out = new LinkedHashSet<>();
    for (String name : a) {
      if (lower.contains(name.toLowerCase(Locale.ROOT))) {
        out.add(name);
      }
    }
    return out;
  }

  private static Set<String> one(String service) {
    Set<String> out = new LinkedHashSet<>();
    out.add(service);
    return out;
  }
}
