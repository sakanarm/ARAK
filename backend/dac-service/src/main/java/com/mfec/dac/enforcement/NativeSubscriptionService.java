package com.mfec.dac.enforcement;

import com.mfec.dac.compiler.sql.PostgresGrantCompiler;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.AccessLevel;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Actual;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Change;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Desired;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Step;
import com.mfec.dac.compiler.sql.PostgresGrantCompiler.Table;
import com.mfec.dac.engine.Principal;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.policy.PolicyStore;
import com.mfec.dac.policy.PrincipalLoader;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.entity.policy.ContextRule;
import com.mfec.dac.schema.entity.policy.Exemption;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.SubjectRule;
import com.mfec.dac.schema.entity.policy.TimeRule;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.CredentialResolver;
import com.mfec.dac.source.jdbc.NativeGrantApplier;
import com.mfec.dac.source.jdbc.SourceProbe;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscription policies pushed down to PostgreSQL as roles (FR-6.2, mode 5.1.1):
 * plan, apply, check, sweep, roll back.
 *
 * <p>{@link PostgresGrantCompiler} decides what the statements are, {@link
 * NativeGrantApplier} is the only thing that runs them, and {@link
 * NativeMembership} decides who is in the role. This class is where their
 * inputs come from, and almost every rule in it is about those inputs being
 * the same at review and at apply.
 *
 * <h2>What a policy has to be</h2>
 *
 * A role on the database is held or it is not; PostgreSQL cannot ask what time
 * it is, where the client is or what it said it wanted. An ALLOW policy that
 * depends on any of those is refused here rather than pushed without them --
 * pushing it would let people in at times and from places the policy says no
 * to (decision C). A DENY needs no role of its own: it keeps the people it
 * names out of every ALLOW policy's role, and those plans say who and why.
 *
 * <h2>What runs by itself</h2>
 *
 * Only the {@link #sweep}, and it only takes away: a member the policy no
 * longer lets in, a table the policy no longer binds. Anything that would give
 * something -- a new member, a new table, the role itself -- waits for a person
 * to plan it, read it and apply it.
 */
public final class NativeSubscriptionService {

  private static final Logger LOG = LoggerFactory.getLogger(NativeSubscriptionService.class);

  /** How many principals one run may decide for before it refuses. */
  public static final int POPULATION_LIMIT = SecureViewService.POPULATION_LIMIT;

  /** How many decisions (people times tables) one run may make before it refuses. */
  public static final int DECISION_LIMIT = 20_000;

  /** The actor the sweep writes on the trail. */
  public static final String SWEEP_ACTOR = "system:native-sweep";

  static final String MODE = "NATIVE_CONFIG";

  private static final Pattern CONTEXT_REFERENCE = Pattern.compile("\\bcontext\\s*\\.");

  /** The steps the sweep may run without a person: every one of them takes something away. */
  private static final Set<Step> TAKES_AWAY =
      EnumSet.of(Step.REVOKE_MEMBER, Step.REVOKE_SELECT, Step.REVOKE_USAGE, Step.RESET_ROLE);

  // ------------------------------------------------------------ exceptions

  /** No such policy or source. */
  public static class NotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
      super(message);
    }
  }

  /** The request cannot be carried out as asked; nothing was touched. */
  public static class RefusedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RefusedException(String message) {
      super(message);
    }
  }

  /** The review is gone or no longer describes the change. Plan again. */
  public static class ConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
      super(message);
    }
  }

  /** The source said no. The transaction was rolled back. */
  public static class SourceFailureException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SourceFailureException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** The policy is gone. To the sweep, a role nobody should hold any more. */
  static final class PolicyGoneException extends NotFoundException {
    private static final long serialVersionUID = 1L;

    PolicyGoneException(String message) {
      super(message);
    }
  }

  /** The policy cannot be a role. To the sweep, the same as a policy that is gone. */
  static final class PolicyUnusableException extends RefusedException {
    private static final long serialVersionUID = 1L;

    PolicyUnusableException(String message) {
      super(message);
    }
  }

  /**
   * The source is not set to native source config, so its policies are
   * enforced somewhere else and nobody should hold a role on it. To the sweep,
   * the same as a policy that is gone.
   */
  static final class NotNativeException extends RefusedException {
    private static final long serialVersionUID = 1L;

    NotNativeException(String message) {
      super(message);
    }
  }

  // ------------------------------------------------------------ public types

  /**
   * Everybody the role has to answer for, each with their decision on each
   * table.
   *
   * <p>An interface so that the tests can say who is who without building a
   * policy for it; the production one is {@link #everyone}.
   */
  @FunctionalInterface
  public interface Population {
    /**
     * @return username to FQN to decision, for every enabled person
     * @throws RefusedException when the population cannot be decided in full
     */
    Map<String, Map<String, PolicyDecision>> decide(List<String> assetFqns, String environment);
  }

  /** A source roles can be pushed to, and what is set up for it. */
  public record SourceView(
      UUID id,
      String name,
      String engine,
      boolean enabled,
      String database,
      String mode,
      NativeRoleStore.CredentialInfo credential,
      int logins,
      int roles) {}

  /** One policy, whether it can be a role, and the roles it has. */
  public record PolicyView(
      UUID policyId,
      String name,
      String policyType,
      String effect,
      String lifecycleState,
      String environment,
      List<String> unsupported,
      List<NativeRoleStore.Role> roles) {}

  /** A plan, as it is shown to the person who has to agree to it. */
  public record Preview(
      UUID reviewId,
      Instant expiresAt,
      UUID policyId,
      String policyName,
      String lifecycleState,
      UUID dataSourceId,
      String sourceName,
      String role,
      String database,
      String level,
      List<String> tables,
      List<NativeMembership.Member> members,
      List<NativeMembership.Excluded> excluded,
      List<String> unmapped,
      List<String> sharedRefused,
      List<String> exemptions,
      List<Change> changes,
      String applyScript,
      String rollbackScript,
      List<String> blockers,
      List<String> warnings,
      List<String> otherReaders,
      boolean satisfied,
      int principals,
      int serverVersionNum) {}

  /** What an apply or a rollback did. */
  public record Outcome(
      NativeRoleStore.Role role, int statements, String script, List<String> warnings) {}

  /** What a check found. Nothing was changed. */
  public record Check(
      NativeRoleStore.Role role,
      String status,
      boolean drifted,
      boolean satisfied,
      List<Change> changes,
      String applyScript,
      List<String> blockers,
      List<String> warnings) {}

  /** What a rollback would run, read from the source now. */
  public record RollbackPreview(
      String role, String database, List<Change> changes, String script, List<String> notes) {}

  /** What one pass of the sweep did. */
  public record SweepReport(int checked, int revoked, int drifted, int pending, int failed) {}

  // ---------------------------------------------------------------- wiring

  private final Jdbi jdbi;
  private final PolicyStore policies;
  private final DataSourceStore sources;
  private final CredentialResolver credentials;
  private final NativeGrantApplier applier;
  private final Population population;
  private final NativeRoleStore roles;
  private final NativeLoginMap logins;
  private final EnforcementStateStore states;
  private final NativeReviews reviews;
  private final Clock clock;

  /**
   * @param credentials the application's resolver, the same instance every
   *     other source connection uses
   */
  public NativeSubscriptionService(
      Jdbi jdbi,
      PolicyStore policies,
      DataSourceStore sources,
      CredentialResolver credentials,
      NativeGrantApplier applier,
      Population population,
      NativeRoleStore roles,
      NativeLoginMap logins,
      EnforcementStateStore states,
      NativeReviews reviews) {
    this(
        jdbi, policies, sources, credentials, applier, population, roles, logins, states, reviews,
        Clock.systemUTC());
  }

  NativeSubscriptionService(
      Jdbi jdbi,
      PolicyStore policies,
      DataSourceStore sources,
      CredentialResolver credentials,
      NativeGrantApplier applier,
      Population population,
      NativeRoleStore roles,
      NativeLoginMap logins,
      EnforcementStateStore states,
      NativeReviews reviews,
      Clock clock) {
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
    this.policies = Objects.requireNonNull(policies, "policies");
    this.sources = Objects.requireNonNull(sources, "sources");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.applier = Objects.requireNonNull(applier, "applier");
    this.population = Objects.requireNonNull(population, "population");
    this.roles = Objects.requireNonNull(roles, "roles");
    this.logins = Objects.requireNonNull(logins, "logins");
    this.states = Objects.requireNonNull(states, "states");
    this.reviews = Objects.requireNonNull(reviews, "reviews");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * The production population: every enabled user and service account, each
   * decided through the same service the simulator and the proxy use, in the
   * policy's own environment.
   */
  public static Population everyone(
      Jdbi jdbi, PrincipalLoader principals, DecisionService decisions) {
    return (assetFqns, environment) -> {
      if (assetFqns == null || assetFqns.isEmpty()) {
        return Map.of();
      }
      List<Principal> people =
          jdbi.withHandle(
              handle -> {
                int count = principals.countEveryone(handle);
                if (count > POPULATION_LIMIT) {
                  throw new RefusedException(
                      count
                          + " principals are enabled and one run decides for at most "
                          + POPULATION_LIMIT
                          + ". A partial run would leave out whoever is past the cut-off, so"
                          + " nothing was computed.");
                }
                return principals.everyone(handle, POPULATION_LIMIT);
              });
      long cells = (long) people.size() * assetFqns.size();
      if (cells > DECISION_LIMIT) {
        throw new RefusedException(
            people.size()
                + " people on "
                + assetFqns.size()
                + " tables is "
                + cells
                + " decisions, and one run makes at most "
                + DECISION_LIMIT
                + ". Narrow the policy's selector, or push it source by source.");
      }
      Map<String, Map<String, PolicyDecision>> out = new TreeMap<>();
      for (Principal person : people) {
        if (out.containsKey(person.id())) {
          // The same username from two directories is one person to the engine.
          continue;
        }
        Map<String, PolicyDecision> mine = new LinkedHashMap<>();
        for (String fqn : assetFqns) {
          mine.put(
              fqn,
              decisions.decide(
                  new DecisionService.Ask(person.id(), fqn, null, null, null, environment)));
        }
        out.put(person.id(), mine);
      }
      return out;
    };
  }

  // ------------------------------------------------------------------ read

  /** PostgreSQL sources, whether each has a push account, and how much is set up. */
  public List<SourceView> sources() {
    Map<UUID, Integer> roleCounts = new LinkedHashMap<>();
    for (NativeRoleStore.Role role : roles.listInstalled()) {
      roleCounts.merge(role.dataSourceId(), 1, Integer::sum);
    }
    List<SourceView> out = new ArrayList<>();
    for (DataSourceStore.Source source : sources.list()) {
      if (source.engine() != DataSourceStore.Engine.POSTGRES) {
        continue;
      }
      out.add(
          new SourceView(
              source.id(),
              source.name(),
              source.engine().name(),
              source.enabled(),
              source.defaultDatabase(),
              source.defaultEnforcementMode() == null
                  ? null
                  : source.defaultEnforcementMode().name(),
              roles.credential(source.id()),
              logins.count(source.id()),
              roleCounts.getOrDefault(source.id(), 0)));
    }
    return out;
  }

  public PolicyView policy(UUID policyId) {
    PolicyStore.StoredPolicy stored = policyOf(policyId);
    Policy document = stored.document();
    List<String> problems = new ArrayList<>();
    if (document.getPolicyType() != Policy.PolicyType.SUBSCRIPTION) {
      problems.add("It is a data policy; only subscription policies become roles.");
    }
    if (document.getEffect() != Policy.Effect.ALLOW) {
      problems.add(
          "It is a DENY policy. It has no role of its own: it keeps the people it names out of"
              + " the roles of the ALLOW policies it overlaps.");
    }
    problems.addAll(unsupported(document));
    return new PolicyView(
        stored.id(),
        document.getName(),
        document.getPolicyType() == null ? null : document.getPolicyType().value(),
        document.getEffect() == null ? null : document.getEffect().value(),
        stored.lifecycleState(),
        stored.environment(),
        List.copyOf(problems),
        roles.listForPolicy(policyId));
  }

  public List<EnforcementStateStore.AuditEntry> history(UUID policyId, UUID sourceId, int limit) {
    if (policyId == null || sourceId == null) {
      throw new NotFoundException("Say which policy and which source.");
    }
    return states.history(PostgresGrantCompiler.roleName(policyId, sourceId), limit);
  }

  /** Changes to a source's push account and logins, newest first. */
  public List<EnforcementStateStore.AuditEntry> configurationHistory(UUID sourceId, int limit) {
    return states.history(configTarget(postgresSource(sourceId).id()), limit);
  }

  // -------------------------------------------------------------- configure

  /**
   * Sets the account roles are pushed with. The reference is stored as given
   * and never served back; the trail says that it changed and by which
   * scheme, not what it is.
   */
  public NativeRoleStore.CredentialInfo setCredential(
      UUID sourceId, String ref, String actor, String clientIp) {
    DataSourceStore.Source source = postgresSource(sourceId);
    if (ref == null || ref.isBlank()) {
      throw new RefusedException("Give the push account, or a reference to where it is kept.");
    }
    String clean = ref.strip();
    boolean sameLogin;
    try {
      sameLogin = isProxyLogin(source, credentials.resolve(clean));
    } catch (CredentialResolver.UnresolvableCredentialException e) {
      sameLogin = false; // Checked again when a plan resolves it.
    }
    if (clean.equals(source.credentialRef()) || sameLogin) {
      throw new RefusedException(proxyLoginRefusal(source));
    }
    roles.setCredential(source.id(), clean, actor);
    NativeRoleStore.CredentialInfo info = roles.credential(source.id());
    audit(
        configTarget(source.id()), source.id(), "CONFIGURE", "CHANGED",
        "Push account set (" + info.scheme() + ")", actor, clientIp, null);
    return info;
  }

  public boolean deleteCredential(UUID sourceId, String actor, String clientIp) {
    DataSourceStore.Source source = postgresSource(sourceId);
    boolean removed = roles.deleteCredential(source.id());
    if (removed) {
      audit(
          configTarget(source.id()), source.id(), "CONFIGURE", "CHANGED",
          "Push account removed. Roles already applied stay on the source; nothing can check,"
              + " sweep or roll them back until an account is set again.",
          actor, clientIp, null);
    }
    return removed;
  }

  public List<NativeLoginMap.Login> logins(UUID sourceId) {
    return logins.list(postgresSource(sourceId).id());
  }

  /**
   * Records which existing login a person connects as. Nobody maps
   * themselves: a login of your own in the map is a role you can then plan
   * yourself into.
   */
  public NativeLoginMap.Login mapLogin(
      UUID sourceId, String username, String login, String actor, String clientIp) {
    DataSourceStore.Source source = postgresSource(sourceId);
    if (username != null && actor != null && username.strip().equalsIgnoreCase(actor)) {
      throw new RefusedException(
          "You cannot map your own login. Another platform admin maps it, so that nobody puts"
              + " themselves into a role.");
    }
    NativeLoginMap.Login mapped = logins.put(source.id(), username, login);
    audit(
        configTarget(source.id()), source.id(), "CONFIGURE", "CHANGED",
        mapped.username() + " connects as " + mapped.login(), actor, clientIp, null);
    return mapped;
  }

  /** @return the logins that were unmapped; the sweep takes them out of every role */
  public List<String> unmapLogin(UUID sourceId, String username, String actor, String clientIp) {
    DataSourceStore.Source source = postgresSource(sourceId);
    List<String> removed = logins.remove(source.id(), username);
    if (!removed.isEmpty()) {
      audit(
          configTarget(source.id()), source.id(), "CONFIGURE", "CHANGED",
          username.strip() + " no longer mapped to " + String.join(", ", removed)
              + "; the sweep takes those logins out of the roles they are in.",
          actor, clientIp, null);
    }
    return removed;
  }

  // ------------------------------------------------------------------ plan

  /**
   * Works out what applying would do, writes nothing, and holds it for review.
   *
   * @param level {@code BROWSE} or {@code READ}; null keeps the level the role
   *     was applied at, or Read for a role that is new
   */
  public Preview plan(
      UUID policyId, UUID sourceId, String level, String actor, String clientIp) {
    String target = auditTarget(policyId, sourceId);
    try {
      Prepared prepared = prepare(policyId, sourceId, parseLevel(level));
      NativeGrantApplier.DryRun dry;
      try {
        dry = applier.dryRun(request(prepared));
      } catch (SQLException e) {
        throw sourceFailure(prepared.source(), "read the role's grants", e);
      }
      NativeReviews.Reviewed reviewed =
          reviews.hold(NativeReviews.key(policyId, sourceId), actor, prepared.desired(), dry);

      states.audit(
          new EnforcementStateStore.Audit(
              actor, target, sourceId, MODE, "DRY_RUN", "REVIEWED", reviewed.id(),
              dry.signature(), dry.plan().changes().size(), null, null,
              prepared.desired().level() + ", " + prepared.desired().tables().size()
                  + " table(s), " + prepared.desired().members().size() + " member(s), "
                  + prepared.principals() + " principal(s) decided",
              clientIp));

      String account = dry.inspection().account();
      List<String> warnings = new ArrayList<>(prepared.warnings());
      if (memberOf(prepared, actor)) {
        warnings.add(
            "You are one of the role's members, so another platform admin has to apply it.");
      }
      warnings.addAll(scrub(dry.inspection().warnings(), account));
      return new Preview(
          reviewed.id(),
          reviewed.expiresAt(),
          policyId,
          prepared.policy().document().getName(),
          prepared.policy().lifecycleState(),
          sourceId,
          prepared.source().name(),
          prepared.desired().role(),
          prepared.desired().database(),
          prepared.desired().level().name(),
          prepared.tables(),
          prepared.membership().members(),
          prepared.membership().excluded(),
          prepared.membership().unmapped(),
          prepared.membership().sharedRefused(),
          prepared.exemptions(),
          dry.plan().changes(),
          dry.plan().applyScript(),
          dry.plan().rollbackScript(),
          scrub(dry.inspection().blockers(), account),
          List.copyOf(new LinkedHashSet<>(warnings)),
          readers(dry.inspection().otherReaders(), account),
          dry.isSatisfied(),
          prepared.principals(),
          dry.inspection().serverVersionNum());
    } catch (NotFoundException | RefusedException | SourceFailureException e) {
      audit(target, sourceId, "DRY_RUN", outcomeOf(e), e.getMessage(), actor, clientIp, null);
      throw e;
    }
  }

  // ------------------------------------------------------------------ apply

  /**
   * Applies the plan that was reviewed under {@code reviewId}, and only that.
   *
   * <p>The review is spent whatever happens next. The policy is decided again
   * and the role it describes compared with the reviewed one: a person who
   * joined or left the policy in between, or a table that was bound or unbound,
   * is a different change from the one somebody agreed to. The applier then
   * compares the source with what the review read.
   */
  public Outcome apply(
      UUID policyId, UUID sourceId, UUID reviewId, String actor, String clientIp) {
    String target = auditTarget(policyId, sourceId);
    try {
      NativeReviews.Reviewed reviewed =
          reviews
              .take(reviewId, NativeReviews.key(policyId, sourceId))
              .orElseThrow(
                  () ->
                      new ConflictException(
                          "That plan has expired, was already used, or is for another policy or"
                              + " source. Plan again and apply what it shows."));

      Prepared prepared = prepare(policyId, sourceId, reviewed.desired().level());
      if (!prepared.desired().equals(reviewed.desired())) {
        throw new ConflictException(
            "The role this policy describes changed after the plan was reviewed -- a person,"
                + " a login or a table moved in between. Nothing was applied. Plan again.");
      }
      if (memberOf(prepared, actor)) {
        throw new RefusedException(
            "You are one of this role's members. Another platform admin applies it: nobody"
                + " grants themselves access.");
      }

      NativeGrantApplier.Applied applied;
      try {
        applied = applier.apply(request(prepared), reviewed.dryRun().signature());
      } catch (NativeGrantApplier.StaleReviewException e) {
        throw new ConflictException(e.getMessage());
      } catch (SQLException e) {
        failed(prepared, e.getMessage());
        throw sourceFailure(prepared.source(), "apply the plan", e);
      } catch (NativeGrantApplier.RefusedException e) {
        String message = scrub(e.getMessage(), reviewed.dryRun().inspection().account());
        failed(prepared, message);
        throw new RefusedException(message);
      }

      Desired desired = prepared.desired();
      NativeRoleStore.Role role =
          roles.recordApplied(
              policyId,
              sourceId,
              desired.role(),
              desired.database(),
              desired.level().name(),
              "APPLIED",
              applied.script(),
              applied.fingerprint(),
              applied.after().members(),
              names(desired.tables()),
              actor,
              summary(prepared.membership()));
      states.audit(
          new EnforcementStateStore.Audit(
              actor, target, sourceId, MODE, "APPLY", "APPLIED", reviewed.id(),
              reviewed.dryRun().signature(), applied.statements(), null, null,
              desired.level() + ", " + desired.tables().size() + " table(s), "
                  + desired.members().size() + " member(s)",
              clientIp));
      return new Outcome(
          role,
          applied.statements(),
          applied.script(),
          scrub(applied.warnings(), reviewed.dryRun().inspection().account()));
    } catch (ConflictException e) {
      audit(target, sourceId, "APPLY", "STALE", e.getMessage(), actor, clientIp, reviewId);
      throw e;
    } catch (NotFoundException | RefusedException | SourceFailureException e) {
      audit(target, sourceId, "APPLY", outcomeOf(e), e.getMessage(), actor, clientIp, reviewId);
      throw e;
    }
  }

  // ------------------------------------------------------------------ check

  /**
   * Compares the source with what was applied and with what the policy says
   * now. Changes nothing on the source.
   *
   * <ul>
   *   <li>{@code APPLIED}: the source holds what the policy says.
   *   <li>{@code DRIFTED}: somebody changed ARAK's grants by hand since the
   *       last apply.
   *   <li>{@code PENDING}: the source is as ARAK left it, but the policy has
   *       moved on -- a new table, a new person -- and a plan is waiting.
   * </ul>
   */
  public Check check(UUID policyId, UUID sourceId, String actor, String clientIp) {
    String target = auditTarget(policyId, sourceId);
    try {
      NativeRoleStore.Role role = installed(policyId, sourceId);
      DataSourceStore.Source source = sourceOf(sourceId);
      CredentialResolver.Credential credential = pushCredential(source);
      AccessLevel level = AccessLevel.valueOf(role.accessLevel());

      List<String> notes = new ArrayList<>();
      Desired wanted;
      try {
        Prepared prepared = prepare(policyId, sourceId, level);
        wanted = prepared.desired();
        notes.addAll(prepared.warnings());
      } catch (PolicyGoneException | PolicyUnusableException | NotNativeException e) {
        wanted = nobody(role, null);
        notes.add(e.getMessage() + " Nobody should hold the role: roll it back.");
      }

      NativeGrantApplier.DryRun dry;
      try {
        dry = applier.dryRun(new NativeGrantApplier.Request(target(source), credential, wanted));
      } catch (SQLException e) {
        throw sourceFailure(source, "read the role's grants", e);
      }
      boolean drifted =
          !NativeGrantApplier.fingerprint(dry.inspection().actual())
              .equals(role.appliedFingerprint());
      boolean satisfied = dry.isSatisfied();
      String status = satisfied ? "APPLIED" : drifted ? "DRIFTED" : "PENDING";
      String detail =
          satisfied
              ? "The source holds what the policy says."
              : dry.plan().changes().size() + " change(s) waiting"
                  + (drifted ? "; ARAK's grants were changed by hand since the last apply." : ".");
      roles.recordStatus(role.id(), status, null, detail);
      states.audit(
          new EnforcementStateStore.Audit(
              actor, target, sourceId, MODE, "DRIFT_CHECK",
              satisfied ? "IN_SYNC" : drifted ? "DRIFTED" : "PENDING", null, dry.signature(),
              dry.plan().changes().size(), null, null, detail, clientIp));

      String account = dry.inspection().account();
      notes.addAll(scrub(dry.inspection().warnings(), account));
      return new Check(
          roles.find(policyId, sourceId).orElse(role),
          status,
          drifted,
          satisfied,
          dry.plan().changes(),
          dry.plan().applyScript(),
          scrub(dry.inspection().blockers(), account),
          List.copyOf(new LinkedHashSet<>(notes)));
    } catch (ConflictException | NotFoundException | RefusedException
        | SourceFailureException e) {
      audit(target, sourceId, "DRIFT_CHECK", outcomeOf(e), e.getMessage(), actor, clientIp, null);
      throw e;
    }
  }

  // --------------------------------------------------------------- rollback

  /** What a rollback would run. Writes nothing. */
  public RollbackPreview rollbackPlan(UUID policyId, UUID sourceId) {
    NativeRoleStore.Role role = installed(policyId, sourceId);
    DataSourceStore.Source source = sourceOf(sourceId);
    CredentialResolver.Credential credential = pushCredential(source);
    List<Change> changes;
    try {
      changes =
          applier.teardownPlan(target(source), credential, role.roleName(), role.databaseName());
    } catch (SQLException e) {
      throw sourceFailure(source, "read the role's grants", e);
    } catch (NativeGrantApplier.RefusedException e) {
      throw new RefusedException(e.getMessage());
    }
    StringBuilder script = new StringBuilder();
    for (Change change : changes) {
      script.append(change.sql()).append('\n');
    }
    return new RollbackPreview(
        role.roleName(),
        role.databaseName(),
        changes,
        script.toString(),
        List.of(
            "Only what ARAK's account granted is revoked. A grant somebody else made to the role"
                + " stays, and then the role stays with it."));
  }

  /** Takes every grant ARAK made for this role off the source, and the role with it. */
  public Outcome rollback(UUID policyId, UUID sourceId, String actor, String clientIp) {
    String target = auditTarget(policyId, sourceId);
    try {
      NativeRoleStore.Role role = installed(policyId, sourceId);
      DataSourceStore.Source source = sourceOf(sourceId);
      CredentialResolver.Credential credential = pushCredential(source);
      NativeGrantApplier.Applied undone;
      try {
        undone =
            applier.teardown(target(source), credential, role.roleName(), role.databaseName());
      } catch (SQLException e) {
        roles.recordStatus(role.id(), "FAILED", "Rollback failed: " + e.getMessage(), null);
        throw sourceFailure(source, "roll back", e);
      } catch (NativeGrantApplier.RefusedException e) {
        throw new RefusedException(e.getMessage());
      }
      String detail =
          undone.warnings().isEmpty()
              ? "Dropped " + role.roleName()
              : String.join(" ", undone.warnings());
      roles.recordRolledBack(role.id(), actor, detail);
      states.audit(
          new EnforcementStateStore.Audit(
              actor, target, sourceId, MODE, "ROLLBACK", "ROLLED_BACK", null, null,
              undone.statements(), null, null, detail, clientIp));
      return new Outcome(
          roles.find(policyId, sourceId).orElse(role),
          undone.statements(),
          undone.script(),
          undone.warnings());
    } catch (ConflictException | NotFoundException | RefusedException
        | SourceFailureException e) {
      audit(target, sourceId, "ROLLBACK", outcomeOf(e), e.getMessage(), actor, clientIp, null);
      throw e;
    }
  }

  // ------------------------------------------------------------------ sweep

  /**
   * Takes away, from every role ARAK has applied, what the policy no longer
   * gives: members whose access ended or who were denied since, tables the
   * policy no longer binds, everything when the policy is gone or can no
   * longer be a role.
   *
   * <p>It never gives. A role that would need something granted -- a new
   * member, a table, its {@code CONNECT} back after somebody revoked it by hand
   * -- is marked {@code PENDING} or {@code DRIFTED} for a person to plan. One
   * role failing does not stop the others.
   */
  public SweepReport sweep() {
    int checked = 0;
    int revoked = 0;
    int drifted = 0;
    int pending = 0;
    int failed = 0;
    for (NativeRoleStore.Role role : roles.listInstalled()) {
      try {
        Swept swept = sweep(role);
        if (swept == null) {
          continue;
        }
        checked++;
        revoked += swept.revoked() ? 1 : 0;
        drifted += "DRIFTED".equals(swept.status()) ? 1 : 0;
        pending += "PENDING".equals(swept.status()) ? 1 : 0;
      } catch (SQLException | RuntimeException e) {
        failed++;
        LOG.warn("Native sweep of {} failed: {}", role.roleName(), e.toString());
        try {
          roles.recordStatus(
              role.id(), role.status(), "The sweep could not check this role: " + e.getMessage(),
              null);
        } catch (RuntimeException ignored) {
          LOG.error("Could not record the sweep failure of {}", role.roleName(), ignored);
        }
      }
    }
    return new SweepReport(checked, revoked, drifted, pending, failed);
  }

  private record Swept(String status, boolean revoked) {}

  /** One role. Null when the role was skipped because its source cannot be reached. */
  private Swept sweep(NativeRoleStore.Role role) throws SQLException {
    DataSourceStore.Source source = sources.find(role.dataSourceId()).orElse(null);
    if (source == null || !source.enabled()) {
      return null;
    }
    Optional<String> ref = roles.credentialRef(source.id());
    if (ref.isEmpty()) {
      roles.recordStatus(
          role.id(), role.status(),
          "No push account is set for " + source.name() + ", so the sweep cannot reach it.",
          null);
      return null;
    }
    CredentialResolver.Credential credential;
    try {
      credential = credentials.resolve(ref.get());
    } catch (CredentialResolver.UnresolvableCredentialException e) {
      roles.recordStatus(
          role.id(), role.status(),
          "The push account for " + source.name() + " cannot be used: " + e.getMessage(),
          null);
      return null;
    }
    if (isProxyLogin(source, credential)) {
      roles.recordStatus(role.id(), role.status(), proxyLoginRefusal(source), null);
      return null;
    }
    SourceProbe.Target target = target(source);
    AccessLevel level = AccessLevel.valueOf(role.accessLevel());

    Actual actual = applier.read(target, credential, role.roleName());
    String why = null;
    Desired wanted;
    try {
      wanted = prepare(role.policyId(), role.dataSourceId(), level).desired();
    } catch (PolicyGoneException | PolicyUnusableException | NotNativeException e) {
      wanted = nobody(role, actual.comment());
      why = e.getMessage();
    }
    boolean drifted =
        !NativeGrantApplier.fingerprint(actual).equals(role.appliedFingerprint());

    // What may stay: what the policy still gives, of what the source holds now.
    SortedSet<String> keepMembers = new TreeSet<>(wanted.members());
    keepMembers.retainAll(actual.members());
    SortedSet<Table> keepTables = new TreeSet<>();
    for (Table table : wanted.tables()) {
      if (actual.usage().contains(table.schema())
          && (level == AccessLevel.BROWSE || actual.select().contains(table))) {
        keepTables.add(table);
      }
    }
    Desired keep =
        new Desired(
            role.roleName(), role.databaseName(), level, keepTables, keepMembers,
            arakComment(actual.comment()));

    NativeGrantApplier.Request request = new NativeGrantApplier.Request(target, credential, keep);
    NativeGrantApplier.DryRun dry = applier.dryRun(request);
    int version = dry.inspection().serverVersionNum();
    List<Change> changes = dry.plan().changes();
    boolean takesAwayOnly =
        changes.stream().allMatch(change -> TAKES_AWAY.contains(change.step()));

    if (changes.isEmpty() || !takesAwayOnly || dry.isBlocked()) {
      boolean satisfied = PostgresGrantCompiler.compile(wanted, actual, version).isSatisfied();
      String status = satisfied ? "APPLIED" : drifted ? "DRIFTED" : "PENDING";
      String error = null;
      if (!changes.isEmpty()) {
        error =
            "The sweep only takes away, and this role also needs a person: "
                + (dry.isBlocked()
                    ? String.join(" ", scrub(dry.inspection().blockers(), dry.inspection().account()))
                    : "it would have to grant something back. Plan it and apply it.")
                + " Waiting to be taken away: "
                + describe(changes);
      }
      if (!status.equals(role.status()) || !Objects.equals(error, role.lastError())) {
        roles.recordStatus(role.id(), status, error, why);
        if (!status.equals(role.status()) && !"APPLIED".equals(status)) {
          audit(
              role.roleName(), source.id(), "DRIFT_CHECK", status,
              error != null ? error : why != null ? why : status.toLowerCase(Locale.ROOT),
              SWEEP_ACTOR, null, null);
        }
      } else {
        roles.recordStatus(role.id(), status, error, null);
      }
      return new Swept(status, false);
    }

    NativeGrantApplier.Applied applied = applier.apply(request, dry.signature());
    boolean satisfied =
        PostgresGrantCompiler.compile(wanted, applied.after(), version).isSatisfied();
    String status = satisfied ? "APPLIED" : drifted ? "DRIFTED" : "PENDING";
    String detail =
        (why == null
                ? "Taken away because the policy no longer gives it: "
                : "Taken away because nobody should hold the role: ")
            + describe(changes)
            + (why == null ? "" : " (" + why + ")");
    if (drifted) {
      // Keep the fingerprint of the last apply a person made, so the hand
      // change stays visible until somebody looks at it.
      roles.recordStatus(role.id(), status, null, detail);
    } else {
      roles.recordApplied(
          role.policyId(),
          role.dataSourceId(),
          role.roleName(),
          role.databaseName(),
          level.name(),
          status,
          applied.script(),
          applied.fingerprint(),
          applied.after().members(),
          names(keepTables),
          SWEEP_ACTOR,
          detail);
    }
    states.audit(
        new EnforcementStateStore.Audit(
            SWEEP_ACTOR, role.roleName(), source.id(), MODE, "EXPIRE", "APPLIED", null,
            dry.signature(), applied.statements(), null, null, detail, null));
    return new Swept(status, true);
  }

  // ---------------------------------------------------------------- prepare

  /** Everything the applier needs, and what the reviewer needs to hear about it. */
  record Prepared(
      PolicyStore.StoredPolicy policy,
      DataSourceStore.Source source,
      SourceProbe.Target target,
      CredentialResolver.Credential credential,
      Desired desired,
      NativeMembership.Result membership,
      List<String> tables,
      List<String> warnings,
      List<String> exemptions,
      int principals) {}

  /** One table the policy binds on the source, by catalogue and physical name. */
  record Bound(String fqn, String database, String schema, String name) {}

  Prepared prepare(UUID policyId, UUID sourceId, AccessLevel requested) {
    PolicyStore.StoredPolicy stored = policyOf(policyId);
    DataSourceStore.Source source = sourceOf(sourceId);
    nativeMode(source);
    Policy document = stored.document();
    usable(document);
    CredentialResolver.Credential credential = pushCredential(source);
    Optional<NativeRoleStore.Role> existing = roles.find(policyId, sourceId);
    boolean installed = existing.map(NativeRoleStore.Role::isInstalled).orElse(false);

    List<String> warnings = new ArrayList<>();
    String database = source.defaultDatabase();
    List<String> fqns = new ArrayList<>();
    SortedSet<Table> tables = new TreeSet<>();
    List<String> elsewhere = new ArrayList<>();
    for (Bound bound : bound(policyId, sourceId)) {
      if (!database.equals(bound.database())) {
        elsewhere.add(bound.fqn());
        continue;
      }
      try {
        tables.add(new Table(bound.schema(), bound.name()));
      } catch (IllegalArgumentException e) {
        throw new RefusedException(bound.fqn() + " has no schema or name on the source.");
      }
      fqns.add(bound.fqn());
    }
    if (!elsewhere.isEmpty()) {
      warnings.add(
          elsewhere.size()
              + " table(s) the policy binds are in another database on "
              + source.name()
              + " and are left out; a role is granted CONNECT on "
              + database
              + " only: "
              + String.join(", ", elsewhere));
    }
    if (tables.isEmpty()) {
      if (!installed) {
        throw new RefusedException(
            document.getName()
                + " binds no table in "
                + database
                + " on "
                + source.name()
                + ", so there is no role to make. Check the policy's selector, or that the"
                + " catalogue maps its tables to this source.");
      }
      warnings.add(
          "The policy binds no table on "
              + source.name()
              + " any more. Applying takes every table and member off the role; roll back to"
              + " drop the role as well.");
    }

    AccessLevel level =
        requested != null
            ? requested
            : existing.map(row -> AccessLevel.valueOf(row.accessLevel())).orElse(AccessLevel.READ);
    if (installed && !existing.get().accessLevel().equals(level.name())) {
      warnings.add(
          "The role was applied at "
              + existing.get().accessLevel()
              + " and this plan is for "
              + level
              + (level == AccessLevel.BROWSE
                  ? ": applying revokes SELECT on every table in it."
                  : ": applying grants SELECT on every table in it."));
    }
    if (level == AccessLevel.BROWSE) {
      warnings.add(
          "Browse lets members connect and see the schema. PostgreSQL's catalogue shows every"
              + " table and column name in the database to anybody who can connect; that is"
              + " PostgreSQL, not this policy.");
    }

    warnings.addAll(timeWarnings(document));
    boolean active = "ACTIVE".equals(stored.lifecycleState());
    if (!active) {
      warnings.add(
          document.getName()
              + " is "
              + stored.lifecycleState()
              + ", so it lets nobody in: the role has no members until it is active and"
              + " planned again.");
    }

    Map<String, Map<String, PolicyDecision>> decisions =
        active && !fqns.isEmpty() ? population.decide(fqns, stored.environment()) : Map.of();
    NativeMembership.Result membership =
        NativeMembership.compute(
            policyId, level, fqns, decisions, logins.loginsByPerson(sourceId));
    if (!membership.unmapped().isEmpty()) {
      warnings.add(
          membership.unmapped().size()
              + " person(s) qualify but have no login mapped on "
              + source.name()
              + ", so they are not in the role: "
              + String.join(", ", membership.unmapped()));
    }

    Desired desired;
    try {
      desired =
          new Desired(
              PostgresGrantCompiler.roleName(policyId, sourceId),
              database,
              level,
              tables,
              membership.logins(),
              PostgresGrantCompiler.comment(policyId, document.getName()));
    } catch (IllegalArgumentException e) {
      throw new RefusedException(e.getMessage());
    }
    return new Prepared(
        stored,
        source,
        target(source),
        credential,
        desired,
        membership,
        List.copyOf(fqns),
        List.copyOf(warnings),
        exemptions(document),
        decisions.size());
  }

  private List<Bound> bound(UUID policyId, UUID sourceId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT DISTINCT m.om_fqn, m.database_name, m.schema_name, m.object_name
                      FROM policy_binding b
                      JOIN asset_fqn_map m ON m.om_fqn = b.target_fqn
                     WHERE b.policy_id = :p
                       AND b.target_kind = 'TABLE'
                       AND m.data_source_id = :s
                       AND m.verification_status <> 'ORPHANED'
                       AND m.object_kind IN ('TABLE', 'VIEW')
                     ORDER BY m.om_fqn
                    """)
                .bind("p", policyId)
                .bind("s", sourceId)
                .map(
                    (rs, ctx) ->
                        new Bound(
                            rs.getString("om_fqn"),
                            rs.getString("database_name"),
                            rs.getString("schema_name"),
                            rs.getString("object_name")))
                .list());
  }

  // ---------------------------------------------------------------- policy

  private PolicyStore.StoredPolicy policyOf(UUID policyId) {
    if (policyId == null) {
      throw new PolicyGoneException("Say which policy.");
    }
    return policies
        .find(policyId)
        .orElseThrow(() -> new PolicyGoneException("No policy " + policyId + " exists."));
  }

  private static void usable(Policy document) {
    if (document.getPolicyType() != Policy.PolicyType.SUBSCRIPTION) {
      throw new PolicyUnusableException(
          document.getName()
              + " is a data policy. Only subscription policies become roles; enforce a data"
              + " policy through the query proxy or a secure view.");
    }
    if (document.getEffect() != Policy.Effect.ALLOW) {
      throw new PolicyUnusableException(
          document.getName()
              + " is a DENY policy, which has no role of its own: it keeps the people it names"
              + " out of the roles of the ALLOW policies it overlaps, and their plans say who.");
    }
    List<String> unsupported = unsupported(document);
    if (!unsupported.isEmpty()) {
      throw new PolicyUnusableException(
          "A role on the database is either held or not: PostgreSQL cannot check "
              + String.join(", ", unsupported)
              + " when somebody connects, so pushing "
              + document.getName()
              + " would let people in when it says no. Enforce it through the query proxy, or"
              + " take those conditions off it.");
    }
  }

  /** The conditions of an ALLOW policy a role cannot carry. Empty when there are none. */
  static List<String> unsupported(Policy document) {
    List<String> out = new ArrayList<>();
    SubjectRule subject = document.getSubject();
    if (subject == null) {
      return out;
    }
    TimeRule time = subject.getTime();
    if (time != null && time.getWindows() != null && !time.getWindows().isEmpty()) {
      out.add("the time of day (time windows)");
    }
    ContextRule context = subject.getContext();
    if (context != null) {
      if (context.getIpCidr() != null && !context.getIpCidr().isEmpty()) {
        out.add("the client's network address");
      }
      if (context.getPurpose() != null && !context.getPurpose().isEmpty()) {
        out.add("a declared purpose");
      }
    }
    String expression = subject.getExpression();
    if (expression != null && CONTEXT_REFERENCE.matcher(expression).find()) {
      out.add("an expression that reads the request's context");
    }
    return out;
  }

  /** Dates a role does not know about; the sweep acts on them instead. */
  private List<String> timeWarnings(Policy document) {
    List<String> out = new ArrayList<>();
    Instant now = clock.instant();
    if (document.getValidFrom() != null && document.getValidFrom().isAfter(now)) {
      out.add(
          "The policy starts at "
              + document.getValidFrom()
              + "; until then it lets nobody in, so nobody is a member yet. Plan again once it"
              + " has started.");
    }
    if (document.getValidUntil() != null) {
      out.add(
          "The policy ends at "
              + document.getValidUntil()
              + ". The role does not know that: the sweep, every ten minutes, takes members"
              + " away once the policy stops letting them in.");
    }
    SubjectRule subject = document.getSubject();
    TimeRule time = subject == null ? null : subject.getTime();
    if (time != null) {
      LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
      if (time.getValidFrom() != null && time.getValidFrom().isAfter(today)) {
        out.add(
            "The policy's subject is valid from "
                + time.getValidFrom()
                + "; nobody qualifies before then. Plan again once it has started.");
      }
      if (time.getValidTo() != null) {
        out.add(
            "The policy's subject is valid to "
                + time.getValidTo()
                + ". The sweep takes members away once that has passed.");
      }
    }
    return out;
  }

  /** Exemptions still in force: shown, because they are why somebody is not a member. */
  private List<String> exemptions(Policy document) {
    List<Exemption> listed = document.getExemptions();
    if (listed == null || listed.isEmpty()) {
      return List.of();
    }
    Instant now = clock.instant();
    List<String> out = new ArrayList<>();
    for (Exemption exemption : listed) {
      if (exemption.getExpiresAt() != null && !exemption.getExpiresAt().isAfter(now)) {
        continue;
      }
      out.add(
          exemption.getPrincipal()
              + (exemption.getReason() == null || exemption.getReason().isBlank()
                  ? ""
                  : " -- " + exemption.getReason().strip())
              + (exemption.getExpiresAt() == null
                  ? ""
                  : " (until " + exemption.getExpiresAt() + ")"));
    }
    return List.copyOf(out);
  }

  // ---------------------------------------------------------------- helpers

  private NativeRoleStore.Role installed(UUID policyId, UUID sourceId) {
    if (policyId == null || sourceId == null) {
      throw new NotFoundException("Say which policy and which source.");
    }
    return roles
        .find(policyId, sourceId)
        .filter(NativeRoleStore.Role::isInstalled)
        .orElseThrow(
            () ->
                new ConflictException(
                    "Nothing is applied for this policy on this source, so there is nothing to"
                        + " check or roll back."));
  }

  private static boolean memberOf(Prepared prepared, String actor) {
    if (actor == null) {
      return false;
    }
    for (NativeMembership.Member member : prepared.membership().members()) {
      for (String person : member.people()) {
        if (person.equalsIgnoreCase(actor)) {
          return true;
        }
      }
    }
    return false;
  }

  /** A PostgreSQL source, enabled or not: enough to configure it. */
  private DataSourceStore.Source postgresSource(UUID sourceId) {
    if (sourceId == null) {
      throw new NotFoundException("Say which source.");
    }
    DataSourceStore.Source source =
        sources
            .find(sourceId)
            .orElseThrow(() -> new NotFoundException("No source " + sourceId + " exists."));
    if (source.engine() != DataSourceStore.Engine.POSTGRES) {
      throw new RefusedException(
          "Roles are pushed to PostgreSQL sources only, and "
              + source.name()
              + " is "
              + source.engine()
              + ". Enforce the policy there through the query proxy.");
    }
    return source;
  }

  private static String configTarget(UUID sourceId) {
    return "native-config:" + sourceId;
  }

  private DataSourceStore.Source sourceOf(UUID sourceId) {
    DataSourceStore.Source source = postgresSource(sourceId);
    if (!source.enabled()) {
      throw new RefusedException(source.name() + " is disabled; enable it to push roles to it.");
    }
    if (source.engine() != DataSourceStore.Engine.POSTGRES) {
      throw new RefusedException(
          "Roles are pushed to PostgreSQL sources only, and "
              + source.name()
              + " is "
              + source.engine()
              + ". Enforce the policy there through the query proxy.");
    }
    if (source.defaultDatabase() == null || source.defaultDatabase().isBlank()) {
      throw new RefusedException(
          source.name() + " has no default database, so there is nothing to grant CONNECT on.");
    }
    return source;
  }

  /**
   * A connection has one enforcement mode, for its subscription and its data
   * policies alike. Roles are pushed only to a source set to native source
   * config; under any other mode its policies are enforced there instead.
   * Rolling back works under every mode, so roles left from before can go.
   */
  private static void nativeMode(DataSourceStore.Source source) {
    DataSourceStore.EnforcementMode mode = source.defaultEnforcementMode();
    if (mode != DataSourceStore.EnforcementMode.NATIVE_CONFIG) {
      throw new NotNativeException(
          source.name()
              + " is enforced by "
              + (mode == null ? "no mode" : modeLabel(mode))
              + ", not native source config, so its policies are not pushed to it as roles."
              + " Set it to native source config under Sources to push them.");
    }
  }

  private static String modeLabel(DataSourceStore.EnforcementMode mode) {
    return switch (mode) {
      case PROXY -> "the query proxy";
      case SECURE_VIEW -> "secure views";
      case NATIVE_CONFIG -> "native source config";
      case NONE -> "no mode";
    };
  }

  /** The push account. Never the source's own, which the proxy reads with. */
  private CredentialResolver.Credential pushCredential(DataSourceStore.Source source) {
    String ref =
        roles
            .credentialRef(source.id())
            .orElseThrow(
                () ->
                    new RefusedException(
                        "No push account is set for "
                            + source.name()
                            + ". A platform admin sets one under PostgreSQL roles, Source"
                            + " setup: an account with CREATEROLE and grant options, kept apart"
                            + " from the read-only account the proxy uses."));
    CredentialResolver.Credential credential;
    try {
      credential = credentials.resolve(ref);
    } catch (CredentialResolver.UnresolvableCredentialException e) {
      throw new RefusedException(
          "The push account for " + source.name() + " cannot be used: " + e.getMessage());
    }
    if (isProxyLogin(source, credential)) {
      throw new RefusedException(proxyLoginRefusal(source));
    }
    return credential;
  }

  /**
   * Whether a push account logs in as the proxy does. Compared by login, so a
   * typed account is caught as well as the same reference; a proxy account
   * that cannot be resolved here is not a match.
   */
  private boolean isProxyLogin(
      DataSourceStore.Source source, CredentialResolver.Credential push) {
    if (source.credentialRef() == null || source.credentialRef().isBlank()) {
      return false;
    }
    try {
      String proxy = credentials.resolve(source.credentialRef()).username();
      return proxy != null && proxy.equalsIgnoreCase(push.username());
    } catch (CredentialResolver.UnresolvableCredentialException e) {
      return false;
    }
  }

  private static String proxyLoginRefusal(DataSourceStore.Source source) {
    return "That is the account the query proxy reads " + source.name() + " with. The push"
        + " account has to be a different one: it can create roles, and the proxy's"
        + " account has to stay read-only.";
  }

  private static NativeGrantApplier.Request request(Prepared prepared) {
    return new NativeGrantApplier.Request(
        prepared.target(), prepared.credential(), prepared.desired());
  }

  private static SourceProbe.Target target(DataSourceStore.Source source) {
    return new SourceProbe.Target(
        source.engine().name(), source.host(), source.port(), source.defaultDatabase());
  }

  /** A role that should hold nothing and nobody, keeping the comment it has. */
  private static Desired nobody(NativeRoleStore.Role role, String comment) {
    return new Desired(
        role.roleName(),
        role.databaseName(),
        AccessLevel.valueOf(role.accessLevel()),
        new TreeSet<>(),
        new TreeSet<>(),
        arakComment(comment));
  }

  private static String arakComment(String comment) {
    return comment != null && comment.startsWith(PostgresGrantCompiler.COMMENT_PREFIX)
        ? comment
        : null;
  }

  static AccessLevel parseLevel(String level) {
    if (level == null || level.isBlank()) {
      return null;
    }
    try {
      return AccessLevel.valueOf(level.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new RefusedException("The access level is BROWSE or READ, not " + level.trim() + ".");
    }
  }

  private void failed(Prepared prepared, String error) {
    Desired desired = prepared.desired();
    try {
      roles.recordFailure(
          prepared.policy().id(),
          prepared.source().id(),
          desired.role(),
          desired.database(),
          desired.level().name(),
          error);
    } catch (RuntimeException failure) {
      LOG.error("Could not record the failed apply of {}", desired.role(), failure);
    }
  }

  private static List<String> names(Collection<Table> tables) {
    List<String> out = new ArrayList<>();
    for (Table table : tables) {
      out.add(table.toString());
    }
    return out;
  }

  private static String summary(NativeMembership.Result membership) {
    List<String> parts = new ArrayList<>();
    parts.add(membership.members().size() + " login(s) in the role");
    if (!membership.excluded().isEmpty()) {
      parts.add(membership.excluded().size() + " person(s) kept out");
    }
    if (!membership.unmapped().isEmpty()) {
      parts.add(membership.unmapped().size() + " without a login");
    }
    if (!membership.sharedRefused().isEmpty()) {
      parts.add(membership.sharedRefused().size() + " shared login(s) refused");
    }
    return String.join("; ", parts) + ".";
  }

  private static String describe(List<Change> changes) {
    List<String> parts = new ArrayList<>();
    for (Change change : changes) {
      parts.add(change.step().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " "
          + change.target());
    }
    return String.join(", ", parts);
  }

  /**
   * The push account's name, taken out of the source's messages. It is half of
   * a credential, and the screens that show these messages are not the place
   * for it.
   */
  static List<String> scrub(List<String> messages, String account) {
    List<String> out = new ArrayList<>(messages.size());
    for (String message : messages) {
      out.add(scrub(message, account));
    }
    return List.copyOf(out);
  }

  static String scrub(String message, String account) {
    if (message == null || account == null || account.isBlank()) {
      return message;
    }
    return message
        .replace("ARAK's own account " + account, "ARAK's own push account")
        .replace("ARAK's account " + account, "ARAK's push account");
  }

  /**
   * The push account holds SELECT on every table in scope, with grant option,
   * so it is always one of the other readers. Named as what it is, not by its
   * login, for the same reason the blockers are scrubbed.
   */
  static List<String> readers(List<String> readers, String account) {
    if (account == null || account.isBlank()) {
      return List.copyOf(readers);
    }
    List<String> out = new ArrayList<>(readers.size());
    for (String reader : readers) {
      int colon = reader.lastIndexOf(": ");
      if (colon >= 0) {
        String who = reader.substring(colon + 2);
        if (who.equals(account) || who.startsWith(account + " (")) {
          reader = reader.substring(0, colon + 2) + "ARAK's push account"
              + who.substring(account.length());
        }
      }
      out.add(reader);
    }
    return List.copyOf(out);
  }

  private static String auditTarget(UUID policyId, UUID sourceId) {
    return policyId == null || sourceId == null
        ? "native:" + policyId + "/" + sourceId
        : PostgresGrantCompiler.roleName(policyId, sourceId);
  }

  private static String outcomeOf(RuntimeException e) {
    return e instanceof SourceFailureException ? "FAILED" : "REFUSED";
  }

  private static SourceFailureException sourceFailure(
      DataSourceStore.Source source, String doing, SQLException e) {
    LOG.warn("Native subscription on {}: could not {}: {}", source.name(), doing, e.toString());
    return new SourceFailureException(
        source.name() + " refused to " + doing + ": " + e.getMessage(), e);
  }

  private void audit(
      String target,
      UUID sourceId,
      String action,
      String outcome,
      String detail,
      String actor,
      String clientIp,
      UUID reviewId) {
    try {
      states.audit(
          new EnforcementStateStore.Audit(
              actor, target, sourceId, MODE, action, outcome, reviewId, null, null, null, null,
              detail, clientIp));
    } catch (RuntimeException failure) {
      // The caller is about to be told the operation failed; failing to write
      // that down must not replace the message they get with a worse one.
      LOG.error("Could not write the enforcement audit trail for {}", target, failure);
    }
  }
}
