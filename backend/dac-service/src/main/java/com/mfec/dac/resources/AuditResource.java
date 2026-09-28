package com.mfec.dac.resources;

import com.mfec.dac.audit.QueryLog;
import com.mfec.dac.audit.QueryRefusals;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.auth.Stewardship;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The query log: every statement sent through the query proxy, what it became
 * and how it ended (FR-8.3, M10).
 *
 * <p>Open to every signed-in person, and each reads a different log:
 *
 * <ul>
 *   <li>an administrator, policy author or auditor reads every row;
 *   <li>a data owner reads their own rows and the rows that touched a table
 *       they own;
 *   <li>anyone else reads their own rows, which includes a row somebody ran as
 *       them, marked with who did.
 * </ul>
 *
 * <p>An owner reading somebody else's row learns that person read their table,
 * when, and how many rows came back. They read the statement itself only when
 * every table in it is theirs: the same statement may join a table they do not
 * own, and what somebody asked of that table is its owner's business. The
 * tables in the row that are not theirs are counted, not named.
 *
 * <p>No response from here carries {@code client_ip}.
 */
@Path("/v1/audit")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class AuditResource {

  private static final Set<String> OUTCOMES = Set.of("EXECUTED", "REJECTED", "FAILED");

  /** Refusals whose message names only the table the refusal is about. */
  private static final Set<QueryRefusals.Category> REASON_ABOUT_ITS_TABLE =
      Set.of(
          QueryRefusals.Category.POLICY_DENY,
          QueryRefusals.Category.CANNOT_ENFORCE,
          QueryRefusals.Category.ALL_COLUMNS_HIDDEN);

  private final QueryLog log;
  private final Clock clock;

  public AuditResource(QueryLog log) {
    this(log, Clock.systemUTC());
  }

  public AuditResource(QueryLog log, Clock clock) {
    this.log = log;
    this.clock = clock;
  }

  /**
   * One row of the log as this reader may see it.
   *
   * @param runBy who sent it, when that is not the principal it ran as
   * @param category why it did not run, sorted; null for a statement that ran
   * @param rejectReason the proxy's own words, or null where they would name
   *     tables the reader does not own
   * @param originalSql null with {@code sqlHidden} when the reader may not read it
   * @param assets the tables it touched that the reader oversees
   * @param hiddenAssets how many more it touched that the reader does not
   * @param fromCache answered from the result cache, so the source has no
   *     record of this read
   * @param exported a download of every row rather than a page on screen, so
   *     the rows left the platform as a file
   */
  public record QueryRow(
      long id,
      Instant occurredAt,
      String principal,
      String runBy,
      UUID sourceId,
      String sourceName,
      String outcome,
      QueryRefusals.Category category,
      String rejectReason,
      String originalSql,
      String rewrittenSql,
      boolean sqlHidden,
      Long rowCount,
      Integer durationMs,
      List<String> assets,
      int hiddenAssets,
      boolean own,
      boolean fromCache,
      boolean exported) {}

  /**
   * @param nextBefore pass as {@code before} for the page after this one; null
   *     on the last page
   * @param counts the whole window by outcome, on the first page only
   * @param scope EVERYTHING, OWNED (own rows and owned tables) or OWN
   */
  public record QueryPage(
      Instant since,
      Instant until,
      String scope,
      List<QueryRow> rows,
      Long nextBefore,
      QueryLog.Counts counts) {}

  @GET
  @Path("/queries")
  public QueryPage queries(
      @QueryParam("outcome") String outcome,
      @QueryParam("principal") String principal,
      @QueryParam("q") String text,
      @QueryParam("assetFqn") String assetFqn,
      @QueryParam("sourceId") String sourceId,
      @QueryParam("days") @DefaultValue("30") int days,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("before") Long before,
      @QueryParam("limit") @DefaultValue("50") int limit,
      @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (days < 1 || days > QueryLog.MAX_DAYS) {
      throw new BadRequestException(
          "days must be between 1 and " + QueryLog.MAX_DAYS + "; got " + days);
    }
    if (limit < 1 || limit > QueryLog.MAX_LIMIT) {
      throw new BadRequestException(
          "limit must be between 1 and " + QueryLog.MAX_LIMIT + "; got " + limit);
    }
    if (before != null && before < 1) {
      throw new BadRequestException("before must be the id of a row from the previous page");
    }
    String wanted = blankToNull(outcome);
    if (wanted != null) {
      wanted = wanted.toUpperCase(Locale.ROOT);
      if (!OUTCOMES.contains(wanted)) {
        throw new BadRequestException("outcome must be EXECUTED, REJECTED or FAILED");
      }
    }
    UUID source = null;
    if (blankToNull(sourceId) != null) {
      try {
        source = UUID.fromString(sourceId.trim());
      } catch (IllegalArgumentException e) {
        throw new BadRequestException("sourceId must be the UUID of a registered source");
      }
    }
    Instant until = instant(to, "to");
    Instant since = instant(from, "from");
    if (since == null) {
      since = (until == null ? clock.instant() : until).minus(Duration.ofDays(days));
    }
    if (until != null && !since.isBefore(until)) {
      throw new BadRequestException("from must be before to");
    }
    String search = blankToNull(text);
    if (search != null && search.length() > 200) {
      throw new BadRequestException("Search for at most 200 characters");
    }

    boolean everything = Stewardship.overseesEverything(caller);
    List<String> scopes = new ArrayList<>();
    if (!everything && caller.hasAnyRole("DATA_OWNER")) {
      for (String scope : caller.scopes()) {
        if (scope != null && !scope.isBlank()) {
          scopes.add(scope.trim());
        }
      }
    }
    QueryLog.Reader reader = new QueryLog.Reader(everything, caller.getName(), scopes);
    QueryLog.Filter filter =
        new QueryLog.Filter(
            wanted,
            blankToNull(principal),
            search,
            blankToNull(assetFqn),
            source,
            since,
            until,
            before);

    // One row more than asked for says whether there is a next page without a count.
    List<QueryLog.Entry> entries = log.page(reader, filter, limit + 1);
    boolean more = entries.size() > limit;
    if (more) {
      entries = entries.subList(0, limit);
    }
    List<QueryRow> rows = new ArrayList<>();
    for (QueryLog.Entry entry : entries) {
      QueryRow row = present(caller, everything, entry);
      // The SQL already decided these; this is the same decision made again with
      // the console's own scope comparison, so a scope the SQL's prefix match
      // reads differently can hide a row but never show one.
      if (row == null || (search != null && row.sqlHidden())) {
        continue;
      }
      rows.add(row);
    }
    Long nextBefore = more ? entries.get(entries.size() - 1).id() : null;
    QueryLog.Counts counts = before == null ? log.counts(reader, filter) : null;
    String scope = everything ? "EVERYTHING" : scopes.isEmpty() ? "OWN" : "OWNED";
    return new QueryPage(since, until, scope, List.copyOf(rows), nextBefore, counts);
  }

  /** What of one stored row this caller reads; null when they read none of it. */
  static QueryRow present(AuthenticatedUser caller, boolean everything, QueryLog.Entry entry) {
    String me = caller.getName();
    boolean own =
        me != null
            && (me.equalsIgnoreCase(entry.principal())
                || (entry.runBy() != null && me.equalsIgnoreCase(entry.runBy())));
    QueryRefusals.Category category =
        QueryRefusals.categorize(entry.outcome(), entry.rejectReason());
    if (everything || own) {
      return new QueryRow(
          entry.id(),
          entry.occurredAt(),
          entry.principal(),
          entry.runBy(),
          entry.sourceId(),
          entry.sourceName(),
          entry.outcome(),
          category,
          entry.rejectReason(),
          entry.originalSql(),
          entry.rewrittenSql(),
          false,
          entry.rowCount(),
          entry.durationMs(),
          entry.assets(),
          0,
          own,
          entry.fromCache(),
          entry.exported());
    }
    List<String> mine = new ArrayList<>();
    for (String fqn : entry.assets()) {
      if (Stewardship.oversees(caller, fqn)) {
        mine.add(fqn);
      }
    }
    if (mine.isEmpty()) {
      return null;
    }
    int hidden = entry.assets().size() - mine.size();
    boolean allMine = hidden == 0;
    boolean sqlVisible =
        allMine && ("EXECUTED".equals(entry.outcome()) || "FAILED".equals(entry.outcome()));
    boolean reasonVisible =
        "EXECUTED".equals(entry.outcome())
            || (allMine && REASON_ABOUT_ITS_TABLE.contains(category));
    return new QueryRow(
        entry.id(),
        entry.occurredAt(),
        entry.principal(),
        entry.runBy(),
        entry.sourceId(),
        entry.sourceName(),
        entry.outcome(),
        category,
        reasonVisible ? entry.rejectReason() : null,
        sqlVisible ? entry.originalSql() : null,
        sqlVisible ? entry.rewrittenSql() : null,
        !sqlVisible,
        entry.rowCount(),
        entry.durationMs(),
        List.copyOf(mine),
        hidden,
        false,
        entry.fromCache(),
        entry.exported());
  }

  private static Instant instant(String value, String name) {
    String text = blankToNull(value);
    if (text == null) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      throw new BadRequestException(name + " must be an ISO-8601 instant such as 2026-09-01T00:00:00Z");
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }
}
