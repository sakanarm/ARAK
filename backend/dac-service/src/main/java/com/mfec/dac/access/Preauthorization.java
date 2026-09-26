package com.mfec.dac.access;

import com.mfec.dac.engine.AssetContext;
import com.mfec.dac.engine.EngineConfig;
import com.mfec.dac.engine.ExpressionUnavailableException;
import com.mfec.dac.engine.Principal;
import com.mfec.dac.engine.RequestContext;
import com.mfec.dac.engine.SelectorMatcher;
import com.mfec.dac.engine.SubjectMatcher;
import com.mfec.dac.policy.AssetContextLoader;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.policy.PrincipalLoader;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.entity.policy.AssetSelector;
import com.mfec.dac.schema.entity.policy.AttributeCondition;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.PrincipalMatch;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import com.mfec.dac.schema.entity.policy.TimeRule;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * A pre-authorization: access asked for ahead of need, for every table under a
 * scope that carries some tags, terms or domains, and for a group, a team, or
 * everybody holding some attributes.
 *
 * <p>It is never a grant. A grant is for one person on one table; this names
 * tables by what they are and people by what they belong to, which is exactly
 * what a subscription policy says. So what fulfils it is a policy drafted from
 * it -- shown to the configurer, opened in the builder, saved as a DRAFT and
 * activated through the policy lifecycle like any other. Nothing here writes a
 * policy, and nothing here activates one.
 *
 * <p>The target is checked here, before it is stored, so that a request cannot
 * carry a condition the builder could not express or a subject that names
 * nobody: an empty subject rule matches nobody, and an empty selector nothing,
 * which would turn an approved request into a policy that silently does
 * nothing.
 */
public class Preauthorization {

  public static final String ASSET = "ASSET";
  public static final String KIND = "PREAUTHORIZATION";

  static final int MAX_CONDITIONS = 8;
  static final int MAX_SUBJECTS = 10;
  static final int MAX_VALUE = 300;

  /** How many tables and people are named, beside the counts. */
  public static final int SAMPLE = 20;

  /** How many people are read to size the audience; beyond it the count says "at least". */
  static final int PEOPLE_LIMIT = 5000;

  /** The facets a pre-authorization may select tables by: what a table is, not where it is. */
  static final Map<String, FacetCondition.FacetType> FACETS = new LinkedHashMap<>();

  static {
    for (FacetCondition.FacetType type :
        List.of(
            FacetCondition.FacetType.CLASSIFICATIONS,
            FacetCondition.FacetType.TAGS,
            FacetCondition.FacetType.GLOSSARIES,
            FacetCondition.FacetType.TERMS,
            FacetCondition.FacetType.DOMAINS,
            FacetCondition.FacetType.DATA_PRODUCTS,
            FacetCondition.FacetType.TIER,
            FacetCondition.FacetType.CERTIFICATION)) {
      FACETS.put(type.value().toLowerCase(Locale.ROOT), type);
    }
  }

  /** {@code contains} takes a tag's children and a domain's sub-domains with it; {@code eq} does not. */
  static final List<String> FACET_OPERATORS = List.of("contains", "eq");

  static final List<String> ATTRIBUTE_OPERATORS = List.of("eq", "ne", "gte", "lte");

  static final List<String> PRINCIPAL_TYPES = List.of("group", "team");

  static final List<String> SCOPE_TYPES = List.of("SERVICE", "DATABASE", "SCHEMA", "TABLE", "VIEW");

  /** One facet a table must carry. */
  public record Condition(String facet, String operator, String value) {}

  /** A group or an OpenMetadata team. */
  public record PrincipalRef(String type, String name) {}

  /** One attribute somebody must hold. */
  public record Attribute(String key, String operator, String value) {}

  /**
   * Who it is for.
   *
   * @param kind {@code GROUP}: anybody in one of the principals; {@code
   *     ATTRIBUTE}: anybody holding every attribute
   */
  public record Subject(String kind, List<PrincipalRef> principals, List<Attribute> attributes) {}

  /** Which tables, and for whom. Every condition must hold. */
  public record Target(List<Condition> conditions, Subject subject) {}

  /**
   * What a pre-authorization would cover today.
   *
   * @param tables tables and views under the scope carrying every condition
   * @param people people the subject matches; with {@code peopleAtLeast} the
   *     directory was larger than was read, and there may be more
   * @param peopleSample names, only for a reader deciding the request
   */
  public record Coverage(
      String scopeFqn,
      String scopeType,
      int tables,
      List<String> tableSample,
      int people,
      boolean peopleAtLeast,
      List<String> peopleSample) {}

  private final Jdbi jdbi;
  private final AssetContextLoader contexts;
  private final PrincipalLoader principals;

  public Preauthorization(Jdbi jdbi, AssetContextLoader contexts, PrincipalLoader principals) {
    this.jdbi = jdbi;
    this.contexts = contexts;
    this.principals = principals;
  }

  // --------------------------------------------------------------- checking

  /**
   * The target, trimmed, lower-cased where case means nothing, and with
   * duplicates dropped; or INVALID naming the first thing wrong.
   */
  public static Target normalise(Target raw) {
    if (raw == null) {
      throw invalid("Say which tables and for whom");
    }
    List<Condition> conditions = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Condition c : safe(raw.conditions())) {
      if (c == null) {
        continue;
      }
      String facet = lower(c.facet());
      if (!FACETS.containsKey(facet)) {
        throw invalid(
            "Choose tables by classification, tag, glossary, term, domain, data product, tier or"
                + " certification");
      }
      String operator = c.operator() == null || c.operator().isBlank() ? "contains" : lower(c.operator());
      if (!FACET_OPERATORS.contains(operator)) {
        throw invalid("A table condition is \"contains\" or \"eq\"");
      }
      String value = text(c.value(), "Give each table condition a value");
      Condition clean = new Condition(FACETS.get(facet).value(), operator, value);
      if (seen.add(clean.toString())) {
        conditions.add(clean);
      }
    }
    if (conditions.isEmpty()) {
      throw invalid(
          "Name at least one tag, term or domain; a pre-authorization for every table under a"
              + " scope is a policy to write, not to ask for");
    }
    if (conditions.size() > MAX_CONDITIONS) {
      throw invalid("At most " + MAX_CONDITIONS + " table conditions");
    }

    Subject subject = raw.subject();
    String kind = subject == null ? "" : lower(subject.kind()).toUpperCase(Locale.ROOT);
    if ("GROUP".equals(kind)) {
      if (!safe(subject.attributes()).isEmpty()) {
        throw invalid("A pre-authorization for a group names no attributes");
      }
      List<PrincipalRef> who = new ArrayList<>();
      Set<String> names = new LinkedHashSet<>();
      for (PrincipalRef p : safe(subject.principals())) {
        if (p == null) {
          continue;
        }
        String type = lower(p.type());
        if (!PRINCIPAL_TYPES.contains(type)) {
          throw invalid("Name a group or a team");
        }
        String name = text(p.name(), "Name the group or team");
        if (names.add(type + ":" + name.toLowerCase(Locale.ROOT))) {
          who.add(new PrincipalRef(type, name));
        }
      }
      if (who.isEmpty()) {
        throw invalid("Name the group or team it is for");
      }
      if (who.size() > MAX_SUBJECTS) {
        throw invalid("At most " + MAX_SUBJECTS + " groups or teams");
      }
      return new Target(List.copyOf(conditions), new Subject("GROUP", List.copyOf(who), List.of()));
    }
    if ("ATTRIBUTE".equals(kind)) {
      if (!safe(subject.principals()).isEmpty()) {
        throw invalid("A pre-authorization by attribute names no group");
      }
      List<Attribute> attrs = new ArrayList<>();
      Set<String> keys = new LinkedHashSet<>();
      for (Attribute a : safe(subject.attributes())) {
        if (a == null) {
          continue;
        }
        String key = text(a.key(), "Name the attribute");
        String operator = a.operator() == null || a.operator().isBlank() ? "eq" : lower(a.operator());
        if (!ATTRIBUTE_OPERATORS.contains(operator)) {
          throw invalid("An attribute condition is eq, ne, gte or lte");
        }
        String value = text(a.value(), "Give " + key + " a value");
        Attribute clean = new Attribute(key, operator, value);
        if (keys.add(clean.toString())) {
          attrs.add(clean);
        }
      }
      if (attrs.isEmpty()) {
        throw invalid("Name at least one attribute the people it is for hold");
      }
      if (attrs.size() > MAX_SUBJECTS) {
        throw invalid("At most " + MAX_SUBJECTS + " attribute conditions");
      }
      return new Target(List.copyOf(conditions), new Subject("ATTRIBUTE", List.of(), List.copyOf(attrs)));
    }
    throw invalid("Say who it is for: a group or team, or people holding some attributes");
  }

  // --------------------------------------------------------------- the policy words

  /** Every condition, ANDed, as the builder would write it. */
  public static AssetSelector selector(Target target) {
    List<AssetSelector> each = new ArrayList<>();
    for (Condition c : target.conditions()) {
      each.add(
          new AssetSelector()
              .withCondition(
                  new FacetCondition()
                      .withFacet(FacetCondition.FacetType.fromValue(c.facet()))
                      .withOperator(ResolvedRowPredicate.FacetOperator.fromValue(c.operator()))
                      .withValue(c.value())));
    }
    return each.size() == 1 ? each.get(0) : new AssetSelector().withAnd(each);
  }

  /**
   * The subject rule, with an end date when one is given. The end date is in
   * the rule's own time window, so the policy stops matching by itself rather
   * than waiting for somebody to disable it.
   */
  public static SubjectRule subject(Target target, LocalDate validTo) {
    SubjectRule rule = new SubjectRule();
    Subject subject = target.subject();
    if ("GROUP".equals(subject.kind())) {
      List<PrincipalMatch> who = new ArrayList<>();
      for (PrincipalRef p : subject.principals()) {
        PrincipalMatch match = new PrincipalMatch();
        if ("team".equals(p.type())) {
          match.setTeam(p.name());
        } else {
          match.setGroup(p.name());
        }
        who.add(match);
      }
      rule.setPrincipals(who);
    } else {
      List<AttributeCondition> attrs = new ArrayList<>();
      for (Attribute a : subject.attributes()) {
        attrs.add(
            new AttributeCondition()
                .withKey(a.key())
                .withOperator(ResolvedRowPredicate.FacetOperator.fromValue(a.operator()))
                .withValue(a.value()));
      }
      rule.setAttributes(attrs);
    }
    if (validTo != null) {
      rule.setTime(new TimeRule().withValidTo(validTo));
    }
    return rule;
  }

  /** The tables in words: {@code tags contains PII.Sensitive and domains contains Finance}. */
  public static String describeTables(Target target) {
    List<String> parts = new ArrayList<>();
    for (Condition c : target.conditions()) {
      parts.add(c.facet() + " " + c.operator() + " " + c.value());
    }
    return String.join(" and ", parts);
  }

  /** The people in words: {@code group Finance or team Risk}, {@code department eq FINANCE}. */
  public static String describePeople(Target target) {
    Subject subject = target.subject();
    List<String> parts = new ArrayList<>();
    if ("GROUP".equals(subject.kind())) {
      for (PrincipalRef p : subject.principals()) {
        parts.add(p.type() + " " + p.name());
      }
      return String.join(" or ", parts);
    }
    for (Attribute a : subject.attributes()) {
      parts.add(a.key() + " " + a.operator() + " " + a.value());
    }
    return "people with " + String.join(" and ", parts);
  }

  /**
   * The subscription policy a pre-authorization is fulfilled by. Returned
   * unsaved: saving it makes a DRAFT, and only the lifecycle activates it.
   *
   * @param scopeType the scope's asset type, which fixes the policy's layer
   * @param today the day the draft is made; the end date counts from it
   */
  public static Policy draft(
      AccessRequestStore.StoredRequest request, Target target, String scopeType, LocalDate today) {
    String scope = request.assetFqn();
    String leaf = scope.contains(".") ? scope.substring(scope.lastIndexOf('.') + 1) : scope;
    LocalDate until =
        request.requestedDays() == null ? null : today.plusDays(request.requestedDays());
    String people = describePeople(target);
    return new Policy()
        .withName(AccessReview.slug("preauth-" + request.ticket() + "-" + leaf))
        .withDisplayName(request.ticket() + ": " + people + " — " + leaf)
        .withDescription(
            "Drafted from pre-authorization "
                + request.ticket()
                + ", asked by "
                + request.requesterUsername()
                + ": "
                + people
                + " may read tables under "
                + scope
                + " where "
                + describeTables(target)
                + (until == null ? ", until disabled." : ", until " + until + ".")
                + " Reason given: "
                + request.reason())
        .withPolicyType(Policy.PolicyType.SUBSCRIPTION)
        .withScopeLevel(level(scopeType))
        .withScopeFqn(scope)
        .withSelector(selector(target))
        .withSubject(subject(target, until))
        .withEffect(Policy.Effect.ALLOW)
        .withEnvironment(Policy.Environment.fromValue(DecisionService.DEFAULT_ENVIRONMENT));
  }

  static ResolvedColumnMask.ScopeLevel level(String scopeType) {
    return switch (scopeType == null ? "" : scopeType) {
      case "SERVICE" -> ResolvedColumnMask.ScopeLevel.SERVICE;
      case "DATABASE" -> ResolvedColumnMask.ScopeLevel.DATABASE;
      case "SCHEMA" -> ResolvedColumnMask.ScopeLevel.SCHEMA;
      default -> ResolvedColumnMask.ScopeLevel.TABLE;
    };
  }

  // --------------------------------------------------------------- measuring

  /** The scope's asset type, or empty when it is not a service, database, schema or table. */
  public static Optional<String> scopeType(Handle handle, String fqn) {
    return handle
        .createQuery(
            """
            SELECT asset_type FROM asset
            WHERE fqn = :fqn AND is_current AND asset_type IN (<types>)
            """)
        .bind("fqn", fqn)
        .bindList("types", SCOPE_TYPES)
        .mapTo(String.class)
        .findFirst();
  }

  /**
   * The tables and people a pre-authorization covers today.
   *
   * <p>The same selector and subject rule the draft carries, run through the
   * engine's own matchers, so that what the form and the review promise is
   * what the policy would bind to and whom it would let in.
   *
   * @param withNames whether to name the people; only for somebody deciding
   */
  public Coverage measure(String scopeFqn, Target target, boolean withNames) {
    String scope = scopeFqn == null ? "" : scopeFqn.trim();
    if (scope.isEmpty()) {
      throw invalid("Choose the service, database, schema or table it is under");
    }
    Target clean = normalise(target);
    AssetSelector selector = selector(clean);
    SubjectRule rule = subject(clean, null);
    return jdbi.withHandle(
        handle -> {
          String type =
              scopeType(handle, scope)
                  .orElseThrow(
                      () ->
                          invalid(
                              scope
                                  + " is not a service, database, schema or table in the catalog"));
          int[] tables = {0};
          List<String> sample = new ArrayList<>();
          contexts.forEachInScope(
              handle,
              scope,
              batch -> {
                for (AssetContext asset : batch) {
                  if (SelectorMatcher.matches(selector, asset)) {
                    tables[0]++;
                    if (sample.size() < SAMPLE) {
                      sample.add(asset.fqn());
                    }
                  }
                }
              });

          List<Principal> everyone = principals.everyone(handle, PEOPLE_LIMIT);
          AssetContext anchor = AssetContext.of(scope).physicalFromFqn().build();
          RequestContext now = RequestContext.at(Instant.now());
          EngineConfig config = EngineConfig.defaults();
          int people = 0;
          List<String> names = new ArrayList<>();
          for (Principal person : everyone) {
            boolean matched;
            try {
              matched = SubjectMatcher.matches(rule, person, anchor, now, config).matched();
            } catch (ExpressionUnavailableException e) {
              matched = false;
            }
            if (matched) {
              people++;
              if (withNames && names.size() < SAMPLE) {
                names.add(person.id());
              }
            }
          }
          return new Coverage(
              scope,
              type,
              tables[0],
              List.copyOf(sample),
              people,
              everyone.size() >= PEOPLE_LIMIT,
              List.copyOf(names));
        });
  }

  // --------------------------------------------------------------- small things

  private static AccessRequestStore.RequestException invalid(String message) {
    return new AccessRequestStore.RequestException(
        AccessRequestStore.RequestException.Kind.INVALID, message);
  }

  private static String text(String raw, String missing) {
    if (raw == null || raw.isBlank()) {
      throw invalid(missing);
    }
    String value = raw.trim();
    if (value.length() > MAX_VALUE) {
      throw invalid("Keep each value under " + MAX_VALUE + " characters");
    }
    return value;
  }

  private static String lower(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static <T> List<T> safe(List<T> list) {
    return list == null ? List.of() : list;
  }
}
