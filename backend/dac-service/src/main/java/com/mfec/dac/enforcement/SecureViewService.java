package com.mfec.dac.enforcement;

import com.mfec.dac.common.engine.SourceEngine;
import com.mfec.dac.common.engine.SourceEngines;
import com.mfec.dac.compiler.sql.RowEntitlementMaintainer;
import com.mfec.dac.compiler.sql.SqlDialect;
import com.mfec.dac.compiler.sql.SqlDialects;
import com.mfec.dac.compiler.sql.ViewCompiler;
import com.mfec.dac.engine.Principal;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.policy.PrincipalLoader;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.CredentialResolver;
import com.mfec.dac.source.jdbc.JdbcIntrospector;
import com.mfec.dac.source.jdbc.SecureViewApplier;
import com.mfec.dac.source.jdbc.SourceProbe;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enforcement mode 5.1.2 end to end: review a secure view, apply it, take it
 * off again (FR-6.1, FR-6.4).
 *
 * <p>The three pieces that do the work already exist and are each deliberately
 * narrow: {@link ViewCompiler} decides what the view looks like, {@link
 * RowEntitlementMaintainer} decides who gets which rows, {@link
 * SecureViewApplier} is the only thing that writes to a customer's database.
 * This class is the assembly -- where the inputs to all three come from -- and
 * almost every rule in it is about making sure they come from the same place.
 *
 * <h2>The inputs, and where each one comes from</h2>
 *
 * <ul>
 *   <li><b>The columns come from the source, live.</b> Not from the catalogue.
 *       A column the crawl has not seen yet is exactly the column no policy
 *       covers, and building the view from the catalogue would leave it out
 *       of the projection -- which sounds safe until someone drops the base
 *       table's grants and discovers the view is missing a column people need.
 *       The live list goes into the view, and every live column the catalogue
 *       does not know is reported as a warning the reviewer has to read.
 *   <li><b>The decisions are the whole population.</b> The maintainer revokes
 *       anybody missing from its input. A population the platform cannot load
 *       in full is refused rather than truncated, because a truncated one is a
 *       revocation of everybody past the cut-off that nobody asked for.
 *   <li><b>The plan is compiled again at apply time and compared.</b> The
 *       applier checks that the entitlement rows are the ones reviewed; this
 *       class checks that the DDL is. Both can move without the other.
 *   <li><b>The credential is resolved by the application's one resolver.</b>
 *       Never a new one: a second resolver without the Fernet opener reads a
 *       stored credential as unresolvable.
 * </ul>
 *
 * <h2>What this does not do yet</h2>
 *
 * <p>Cutover (FR-6.1.1) is a separate step: nothing here grants SELECT on the
 * view or revokes it on the base table. An applied view is therefore readable
 * by its owner and nobody else until somebody grants it -- which fails closed,
 * and is said in the dry run so nobody expects otherwise.
 */
public final class SecureViewService {

  private static final Logger LOG = LoggerFactory.getLogger(SecureViewService.class);

  /** How many principals one run may decide for before it refuses. */
  public static final int POPULATION_LIMIT = 2000;

  static final String MODE = EnforcementStateStore.SECURE_VIEW;

  // ------------------------------------------------------------ exceptions

  /** No such asset, or nothing of it that this mode could act on. */
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

  /** The review is gone or no longer describes the change. Run it again. */
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

  // ------------------------------------------------------------ public types

  /**
   * Everybody the view has to answer for, each with their decision on one
   * asset.
   *
   * <p>An interface so that the tests of this class can say who is who without
   * building a policy for it; the production one is {@link #everyone}.
   */
  @FunctionalInterface
  public interface Population {
    List<PolicyDecision> decide(String assetFqn);
  }

  /** A table this mode could govern, and what is on it now. */
  public record Candidate(
      String assetFqn,
      String displayName,
      UUID dataSourceId,
      String sourceName,
      String engine,
      String defaultMode,
      String schema,
      String table,
      String secureObject,
      String status,
      Instant lastAppliedAt,
      String lastAppliedBy,
      String lastError) {}

  /** A dry run, as it is shown to the person who has to agree to it. */
  public record Preview(
      UUID reviewId,
      Instant expiresAt,
      String assetFqn,
      UUID dataSourceId,
      String sourceName,
      String engine,
      String secureObject,
      List<String> liveColumns,
      List<String> uncataloguedColumns,
      int principals,
      int allowed,
      SecureViewApplier.DryRun dryRun,
      List<String> warnings) {}

  /** What an apply or rollback did. */
  public record Outcome(
      EnforcementStateStore.State state,
      int statements,
      int inserted,
      int deleted,
      List<String> notes) {}

  // ---------------------------------------------------------------- wiring

  private final Jdbi jdbi;
  private final DataSourceStore sources;
  private final CredentialResolver credentials;
  private final JdbcIntrospector introspector;
  private final SecureViewApplier applier;
  private final Population population;
  private final RowEntitlementMaintainer.EntitlementSource entitlements;
  private final EnforcementStateStore states;
  private final ReviewedPlans reviews;

  /**
   * @param credentials the application's resolver, the same instance every
   *     other source connection uses
   */
  public SecureViewService(
      Jdbi jdbi,
      DataSourceStore sources,
      CredentialResolver credentials,
      SecureViewApplier applier,
      Population population,
      RowEntitlementMaintainer.EntitlementSource entitlements,
      EnforcementStateStore states,
      ReviewedPlans reviews) {
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
    this.sources = Objects.requireNonNull(sources, "sources");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.introspector = new JdbcIntrospector(credentials, 15);
    this.applier = Objects.requireNonNull(applier, "applier");
    this.population = Objects.requireNonNull(population, "population");
    this.entitlements =
        entitlements == null ? RowEntitlementMaintainer.EntitlementSource.NONE : entitlements;
    this.states = Objects.requireNonNull(states, "states");
    this.reviews = Objects.requireNonNull(reviews, "reviews");
  }

  /**
   * The production population: every enabled user and service account, each
   * decided through the same service the simulator and the proxy use.
   */
  public static Population everyone(
      Jdbi jdbi, PrincipalLoader principals, DecisionService decisions) {
    return assetFqn -> {
      List<Principal> people =
          jdbi.withHandle(
              handle -> {
                int count = principals.countEveryone(handle);
                if (count > POPULATION_LIMIT) {
                  throw new RefusedException(
                      count
                          + " principals are enabled and one run decides for at most "
                          + POPULATION_LIMIT
                          + ". A partial run would revoke everybody past the cut-off, so nothing"
                          + " was computed.");
                }
                return principals.everyone(handle, POPULATION_LIMIT);
              });
      List<PolicyDecision> out = new ArrayList<>(people.size());
      for (Principal person : people) {
        out.add(decisions.decide(DecisionService.Ask.of(person.id(), assetFqn)));
      }
      return out;
    };
  }

  // ------------------------------------------------------------------ list

  /**
   * Tables on enabled sources, with their secure-view state.
   *
   * @param search a case-insensitive fragment of the FQN, or null for all
   */
  public List<Candidate> candidates(String search, int limit) {
    int bounded = Math.max(1, Math.min(limit, 500));
    String like =
        search == null || search.isBlank()
            ? null
            : "%" + search.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT m.om_fqn, coalesce(a.display_name, a.name) AS display_name,
                           s.id AS source_id, s.name AS source_name, s.engine,
                           s.default_enforcement_mode, m.schema_name, m.object_name,
                           s.secure_schema, s.secure_object_pattern,
                           e.status, e.last_applied_at, e.last_applied_by, e.last_error,
                           e.secure_object_schema, e.secure_object_name
                      FROM asset_fqn_map m
                      JOIN data_source s ON s.id = m.data_source_id AND s.enabled
                      JOIN asset a ON a.fqn = m.om_fqn AND a.is_current
                      LEFT JOIN enforcement_state e
                             ON e.target_fqn = m.om_fqn AND e.mode = 'SECURE_VIEW'
                     WHERE m.verification_status <> 'ORPHANED'
                       AND m.object_kind = 'TABLE'
                       AND (CAST(:like AS text) IS NULL OR lower(m.om_fqn) LIKE :like)
                     ORDER BY (e.status IS NULL), lower(m.om_fqn)
                     LIMIT :limit
                    """)
                .bind("like", like)
                .bind("limit", bounded)
                .map(
                    (rs, ctx) -> {
                      String installedSchema = rs.getString("secure_object_schema");
                      String installedName = rs.getString("secure_object_name");
                      String secureObject =
                          installedSchema != null && installedName != null
                              ? installedSchema + "." + installedName
                              : rs.getString("secure_schema")
                                  + "."
                                  + viewName(
                                      rs.getString("secure_object_pattern"),
                                      rs.getString("object_name"));
                      java.sql.Timestamp at = rs.getTimestamp("last_applied_at");
                      String status = rs.getString("status");
                      return new Candidate(
                          rs.getString("om_fqn"),
                          rs.getString("display_name"),
                          rs.getObject("source_id", UUID.class),
                          rs.getString("source_name"),
                          rs.getString("engine"),
                          rs.getString("default_enforcement_mode"),
                          rs.getString("schema_name"),
                          rs.getString("object_name"),
                          secureObject,
                          status == null ? "NOT_ENFORCED" : status,
                          at == null ? null : at.toInstant(),
                          rs.getString("last_applied_by"),
                          rs.getString("last_error"));
                    })
                .list());
  }

  public Optional<EnforcementStateStore.State> state(String assetFqn) {
    return states.find(assetFqn, MODE);
  }

  public List<EnforcementStateStore.AuditEntry> history(String assetFqn, int limit) {
    return states.history(assetFqn, limit);
  }

  // ---------------------------------------------------------------- dry run

  /** Works out what applying would do, writes nothing, and holds it for review. */
  public Preview dryRun(String assetFqn, String actor, String clientIp) {
    Located at = null;
    try {
      at = locate(assetFqn);
      Prepared prepared = prepare(at);
      SecureViewApplier.DryRun dry;
      try {
        dry = applier.dryRun(prepared.request());
      } catch (SQLException e) {
        throw sourceFailure(at, "read the entitlement tables", e);
      } catch (RowEntitlementMaintainer.MismatchedPlanException e) {
        throw new RefusedException(e.getMessage());
      }

      List<String> warnings = new ArrayList<>(prepared.warnings());
      warnings.addAll(dry.warnings());
      ReviewedPlans.Reviewed reviewed =
          reviews.hold(at.fqn(), actor, dry, prepared.request().plan().applyScript());

      int allowed = 0;
      for (PolicyDecision decision : prepared.request().decisions()) {
        if (Boolean.TRUE.equals(decision.getAllowed())) {
          allowed++;
        }
      }

      states.audit(
          new EnforcementStateStore.Audit(
              actor, at.fqn(), at.source().id(), MODE, "DRY_RUN", "REVIEWED", reviewed.id(),
              dry.signature(), prepared.request().plan().apply().size(),
              dry.rows().insert().size(), dry.rows().delete().size(),
              prepared.request().decisions().size() + " principals decided", clientIp));

      return new Preview(
          reviewed.id(),
          reviewed.expiresAt(),
          at.fqn(),
          at.source().id(),
          at.source().name(),
          at.source().engine().name(),
          prepared.request().view().secureSchema() + "." + prepared.request().view().secureView(),
          prepared.request().view().sourceColumns(),
          prepared.uncatalogued(),
          prepared.request().decisions().size(),
          allowed,
          dry,
          List.copyOf(new LinkedHashSet<>(warnings)));
    } catch (NotFoundException | RefusedException | SourceFailureException e) {
      refused(assetFqn, at, "DRY_RUN", e, actor, clientIp, null);
      throw e;
    }
  }

  // ------------------------------------------------------------------ apply

  /**
   * Applies the change that was reviewed under {@code reviewId}, and only that.
   *
   * <p>The review is spent whatever happens next. A review that failed to apply
   * describes a source that has since moved, or one that refused, and in both
   * cases the next attempt should start from a fresh read.
   */
  public Outcome apply(String assetFqn, UUID reviewId, String actor, String clientIp) {
    Located at = null;
    try {
      at = locate(assetFqn);
      ReviewedPlans.Reviewed reviewed =
          reviews
              .take(reviewId, at.fqn())
              .orElseThrow(
                  () ->
                      new ConflictException(
                          "That review has expired, was already used, or is for another table."
                              + " Run the dry run again and apply what it shows."));

      Prepared prepared = prepare(at);
      String script = prepared.request().plan().applyScript();
      if (!script.equals(reviewed.applyScript())) {
        throw new ConflictException(
            "The view that would be created is no longer the one that was reviewed -- a column"
                + " or a policy changed in between. Nothing was applied. Run the dry run again.");
      }

      SecureViewApplier.Applied applied;
      try {
        applied = applier.apply(prepared.request(), reviewed.dryRun());
      } catch (SecureViewApplier.StaleReviewException e) {
        throw new ConflictException(e.getMessage());
      } catch (SQLException e) {
        states.recordFailure(at.assetId(), at.fqn(), at.source().id(), MODE, e.getMessage());
        throw sourceFailure(at, "apply", e);
      } catch (RuntimeException e) {
        states.recordFailure(
            at.assetId(), at.fqn(), at.source().id(), MODE, String.valueOf(e.getMessage()));
        throw new RefusedException(String.valueOf(e.getMessage()));
      }

      EnforcementStateStore.State state =
          states.recordApplied(
              at.assetId(),
              at.fqn(),
              at.source().id(),
              MODE,
              applied.appliedDdl(),
              applied.rollbackDdl(),
              reviewed.dryRun().signature(),
              actor,
              prepared.request().view().secureSchema(),
              prepared.request().view().secureView());
      states.audit(
          new EnforcementStateStore.Audit(
              actor, at.fqn(), at.source().id(), MODE, "APPLY", "APPLIED", reviewed.id(),
              reviewed.dryRun().signature(), applied.statements(), applied.inserted(),
              applied.deleted(), null, clientIp));
      return new Outcome(
          state, applied.statements(), applied.inserted(), applied.deleted(), applied.notes());
    } catch (ConflictException e) {
      audit(assetFqn, at, "APPLY", "STALE", e.getMessage(), actor, clientIp, reviewId);
      throw e;
    } catch (NotFoundException | RefusedException | SourceFailureException e) {
      refused(assetFqn, at, "APPLY", e, actor, clientIp, reviewId);
      throw e;
    }
  }

  // --------------------------------------------------------------- rollback

  /**
   * Drops the view that was applied, by the name it was applied under.
   *
   * <p>Deliberately not recompiled from today's settings: the source's secure
   * schema or naming pattern may have changed since, and a rollback that drops
   * the view those settings would name now reports success and removes
   * nothing. It also works when the table has since left the catalogue -- the
   * state row remembers the source.
   */
  public Outcome rollback(String assetFqn, String actor, String clientIp) {
    EnforcementStateStore.State installed = null;
    DataSourceStore.Source source = null;
    try {
      installed =
          states
              .find(assetFqn, MODE)
              .filter(EnforcementStateStore.State::isInstalled)
              .orElseThrow(
                  () ->
                      new ConflictException(
                          "No secure view is applied to " + assetFqn + ", so there is nothing to"
                              + " roll back."));
      UUID sourceId = installed.dataSourceId();
      source =
          sources
              .find(sourceId)
              .orElseThrow(
                  () -> new NotFoundException("The source this view was applied to is gone."));
      SqlDialect dialect = dialect(source);
      CredentialResolver.Credential credential = credential(source);
      String qualified =
          dialect.quote(installed.secureSchema()) + "." + dialect.quote(installed.secureView());

      // Only the plan's rollback is read by the applier. The target is a
      // placeholder that satisfies the record, naming the view both ways.
      ViewCompiler.Target view =
          new ViewCompiler.Target(
              installed.secureSchema(),
              installed.secureView(),
              installed.secureSchema(),
              installed.secureView(),
              "acl",
              assetFqn,
              List.of(installed.secureView()),
              ViewCompiler.IdentitySource.DB_PRINCIPAL,
              null);
      ViewCompiler.Plan plan =
          new ViewCompiler.Plan(
              "",
              List.of(),
              List.of(dialect.dropViewIfExists(qualified)),
              List.of(
                  "The entitlement rows under acl are left in place; every secure view on this"
                      + " database reads the same three tables, and on their own they grant"
                      + " nothing."),
              List.of(),
              List.of(),
              List.of());
      SecureViewApplier.Request request =
          new SecureViewApplier.Request(
              target(source), credential, dialect, view, plan, List.of(), entitlements);

      SecureViewApplier.Applied undone;
      try {
        undone = applier.rollback(request);
      } catch (SQLException e) {
        states.recordFailure(
            installed.assetId(), assetFqn, sourceId, MODE, "Rollback failed: " + e.getMessage());
        throw new SourceFailureException(
            source.name() + " refused the rollback: " + e.getMessage(), e);
      }

      EnforcementStateStore.State state = states.recordRolledBack(assetFqn, MODE, actor);
      states.audit(
          new EnforcementStateStore.Audit(
              actor, assetFqn, sourceId, MODE, "ROLLBACK", "ROLLED_BACK", null, null,
              undone.statements(), 0, 0, "Dropped " + qualified, clientIp));
      return new Outcome(state, undone.statements(), 0, 0, plan.notes());
    } catch (ConflictException | NotFoundException | RefusedException
        | SourceFailureException e) {
      states.audit(
          new EnforcementStateStore.Audit(
              actor, assetFqn, source == null ? null : source.id(), MODE, "ROLLBACK",
              e instanceof SourceFailureException ? "FAILED" : "REFUSED", null, null, null, null,
              null, e.getMessage(), clientIp));
      throw e;
    }
  }

  // ---------------------------------------------------------------- helpers

  /** One table, found through the catalogue's mapping to the physical object. */
  record Located(
      UUID assetId, String fqn, DataSourceStore.Source source, String schema, String table) {}

  /** Everything the applier needs, plus what the reviewer needs to hear about it. */
  record Prepared(
      SecureViewApplier.Request request, List<String> uncatalogued, List<String> warnings) {}

  Located locate(String assetFqn) {
    if (assetFqn == null || assetFqn.isBlank()) {
      throw new NotFoundException("Say which table: send its FQN.");
    }
    String fqn = assetFqn.trim();
    record Row(UUID assetId, UUID sourceId, String schema, String table) {}
    Row row =
        jdbi.withHandle(
                handle ->
                    handle
                        .createQuery(
                            """
                            SELECT a.id AS asset_id, m.data_source_id, m.schema_name,
                                   m.object_name
                              FROM asset_fqn_map m
                              JOIN asset a ON a.fqn = m.om_fqn AND a.is_current
                             WHERE m.om_fqn = :fqn
                               AND m.verification_status <> 'ORPHANED'
                            """)
                        .bind("fqn", fqn)
                        .map(
                            (rs, ctx) ->
                                new Row(
                                    rs.getObject("asset_id", UUID.class),
                                    rs.getObject("data_source_id", UUID.class),
                                    rs.getString("schema_name"),
                                    rs.getString("object_name")))
                        .findOne())
            .orElseThrow(
                () ->
                    new NotFoundException(
                        fqn + " is not a table the platform can place on a registered source."));
    DataSourceStore.Source source =
        sources
            .find(row.sourceId())
            .orElseThrow(() -> new NotFoundException("The source of " + fqn + " is gone."));
    if (!source.enabled()) {
      throw new RefusedException(source.name() + " is disabled; enable it to enforce on it.");
    }
    return new Located(row.assetId(), fqn, source, row.schema(), row.table());
  }

  private Prepared prepare(Located at) {
    DataSourceStore.Source source = at.source();
    SourceEngine engine = SourceEngines.of(source.engine().name());
    if (!engine.supportsSecureViews()) {
      // Said before anything is read or compiled, and in words that name the
      // way that does work, so nobody takes a failed apply for a broken source.
      throw new RefusedException(
          "Secure views are not available on "
              + engine.displayName()
              + " sources. Enforce "
              + at.fqn()
              + " through the query proxy instead.");
    }
    SqlDialect dialect = dialect(source);
    CredentialResolver.Credential credential = credential(source);
    SourceProbe.Target target = target(source);

    List<String> live = liveColumns(at, target);
    List<String> catalogued = cataloguedColumns(at.fqn());
    Set<String> known = new java.util.HashSet<>();
    for (String column : catalogued) {
      known.add(column.toLowerCase(Locale.ROOT));
    }
    List<String> uncatalogued = new ArrayList<>();
    for (String column : live) {
      if (!known.contains(column.toLowerCase(Locale.ROOT))) {
        uncatalogued.add(column);
      }
    }
    List<String> warnings = new ArrayList<>();
    if (!uncatalogued.isEmpty()) {
      warnings.add(
          "The source has "
              + uncatalogued.size()
              + " column(s) the catalogue has not seen: "
              + String.join(", ", uncatalogued)
              + ". A tag-based policy cannot reach a column nobody has tagged, so these pass"
              + " through the view unmasked. Re-sync the catalogue, or mask them by name.");
    }
    Set<String> liveNames = new java.util.HashSet<>();
    for (String column : live) {
      liveNames.add(column.toLowerCase(Locale.ROOT));
    }
    List<String> gone = new ArrayList<>();
    for (String column : catalogued) {
      if (!liveNames.contains(column.toLowerCase(Locale.ROOT))) {
        gone.add(column);
      }
    }
    if (!gone.isEmpty()) {
      warnings.add(
          "The catalogue lists column(s) the source no longer has: "
              + String.join(", ", gone)
              + ". They are left out of the view.");
    }
    warnings.add(
        "Applying creates the view and its entitlement rows only. Nobody is granted SELECT on"
            + " the view and nothing is revoked on the base table -- that is the cutover step,"
            + " which is not automated yet.");

    ViewCompiler.Target view;
    try {
      view =
          new ViewCompiler.Target(
              at.schema(),
              at.table(),
              source.secureSchema(),
              viewName(source.secureObjectPattern(), at.table()),
              "acl",
              null,
              live,
              ViewCompiler.IdentitySource.DB_PRINCIPAL,
              null);
    } catch (IllegalArgumentException e) {
      throw new RefusedException(
          "The secure view cannot be named from this source's settings: " + e.getMessage());
    }

    List<PolicyDecision> decisions = population.decide(at.fqn());
    if (decisions == null || decisions.isEmpty()) {
      throw new RefusedException(
          "Nobody is enabled on the platform, so there is nobody to build the view for.");
    }

    ViewCompiler.Plan plan;
    try {
      plan = new ViewCompiler(dialect).compile(decisions, view);
    } catch (IllegalArgumentException e) {
      throw new RefusedException(e.getMessage());
    }

    // The ACL tables on the source are keyed by the physical name; the
    // platform's own entitlements are keyed by the catalogue's. The view asks
    // for one and the author wrote the other.
    String fqn = at.fqn();
    RowEntitlementMaintainer.EntitlementSource translated =
        (principal, assetKey, key) -> entitlements.valuesFor(principal, fqn, key);

    SecureViewApplier.Request request =
        new SecureViewApplier.Request(
            target, credential, dialect, view, plan, decisions, translated);
    return new Prepared(request, List.copyOf(uncatalogued), List.copyOf(warnings));
  }

  private List<String> liveColumns(Located at, SourceProbe.Target target) {
    List<JdbcIntrospector.Table> tables;
    try {
      tables = introspector.tables(target, at.source().credentialRef(), at.schema());
    } catch (SQLException e) {
      throw sourceFailure(at, "read the table's columns", e);
    } catch (CredentialResolver.UnresolvableCredentialException e) {
      throw new RefusedException(e.getMessage());
    }
    JdbcIntrospector.Table match =
        tables.stream()
            .filter(t -> t.schema().equals(at.schema()) && t.name().equals(at.table()))
            .findFirst()
            .or(
                () ->
                    tables.stream()
                        .filter(
                            t ->
                                t.schema().equalsIgnoreCase(at.schema())
                                    && t.name().equalsIgnoreCase(at.table()))
                        .findFirst())
            .orElseThrow(
                () ->
                    new RefusedException(
                        at.schema()
                            + "."
                            + at.table()
                            + " is not on "
                            + at.source().name()
                            + " any more. Re-sync the catalogue before enforcing on it."));
    if (!"TABLE".equals(match.kind())) {
      throw new RefusedException(
          at.schema() + "." + at.table() + " is a view on the source; secure views are built"
              + " over tables.");
    }
    return match.columns().stream()
        .sorted(Comparator.comparingInt(JdbcIntrospector.Column::ordinal))
        .map(JdbcIntrospector.Column::name)
        .toList();
  }

  private List<String> cataloguedColumns(String fqn) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT c.name FROM asset_column c
                      JOIN asset a ON a.id = c.asset_id AND a.is_current
                     WHERE a.fqn = :fqn AND c.is_current
                     ORDER BY c.ordinal, c.name
                    """)
                .bind("fqn", fqn)
                .mapTo(String.class)
                .list());
  }

  private CredentialResolver.Credential credential(DataSourceStore.Source source) {
    try {
      return credentials.resolve(source.credentialRef());
    } catch (CredentialResolver.UnresolvableCredentialException e) {
      throw new RefusedException(e.getMessage());
    }
  }

  private static SqlDialect dialect(DataSourceStore.Source source) {
    try {
      return SqlDialects.forEngineId(source.engine().name());
    } catch (RuntimeException e) {
      throw new RefusedException(
          "No SQL dialect is registered for " + source.engine() + ": " + e.getMessage());
    }
  }

  private static SourceProbe.Target target(DataSourceStore.Source source) {
    return new SourceProbe.Target(
        source.engine().name(), source.host(), source.port(), source.defaultDatabase());
  }

  static String viewName(String pattern, String table) {
    String chosen = pattern == null || pattern.isBlank() ? "{table}" : pattern;
    return chosen.replace("{table}", table == null ? "" : table);
  }

  private static SourceFailureException sourceFailure(Located at, String doing, SQLException e) {
    LOG.warn("Secure view on {}: could not {}: {}", at.fqn(), doing, e.toString());
    return new SourceFailureException(
        at.source().name() + " refused to " + doing + ": " + e.getMessage(), e);
  }

  private void refused(
      String assetFqn,
      Located at,
      String action,
      RuntimeException e,
      String actor,
      String clientIp,
      UUID reviewId) {
    audit(
        assetFqn,
        at,
        action,
        e instanceof SourceFailureException ? "FAILED" : "REFUSED",
        e.getMessage(),
        actor,
        clientIp,
        reviewId);
  }

  private void audit(
      String assetFqn,
      Located at,
      String action,
      String outcome,
      String detail,
      String actor,
      String clientIp,
      UUID reviewId) {
    try {
      states.audit(
          new EnforcementStateStore.Audit(
              actor,
              at == null ? String.valueOf(assetFqn) : at.fqn(),
              at == null ? null : at.source().id(),
              MODE,
              action,
              outcome,
              reviewId,
              null,
              null,
              null,
              null,
              detail,
              clientIp));
    } catch (RuntimeException failure) {
      // The caller is about to be told the operation failed; failing to write
      // that down must not replace the message they get with a worse one.
      LOG.error("Could not write the enforcement audit trail for {}", assetFqn, failure);
    }
  }
}
