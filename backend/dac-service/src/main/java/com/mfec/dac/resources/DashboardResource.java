package com.mfec.dac.resources;

import com.mfec.dac.audit.QueryLog;
import com.mfec.dac.audit.QueryRefusals;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.auth.Stewardship;
import com.mfec.dac.dashboard.DashboardQuery;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The access-control dashboard (M10, FR-8.5): one page that answers the
 * questions a reviewer brings to the whole estate.
 *
 * <ul>
 *   <li>Which sensitive tables does nothing protect, and does anyone read them?
 *   <li>Who holds access, who is about to lose it, and who holds it without using it?
 *   <li>What did the proxy run and refuse, and why?
 *   <li>Are requests being answered, and is the platform itself healthy?
 * </ul>
 *
 * <p>Only for the roles that oversee everything -- an administrator, policy
 * author or auditor. Everyone else reads their own slice of the same facts
 * where it already lives: their grants, their requests, their query log. The
 * dashboard counts; it does not quote. No statement text and no client
 * address is in the response.
 */
@Path("/v1/dashboard")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class DashboardResource {

  /** The widest window the dashboard reads over. */
  public static final int MAX_DAYS = 365;

  /** How far ahead "ending soon" looks. */
  static final Duration ENDING_HORIZON = Duration.ofDays(14);

  /** A grant nobody has used for this long is worth asking about (FR-8.5). */
  static final Duration IDLE = Duration.ofDays(90);

  /** An OpenMetadata crawl older than this means the catalogue may be out of date. */
  static final Duration STALE_SYNC = Duration.ofDays(2);

  /** An open request older than this has waited too long. */
  static final Duration OLD_REQUEST = Duration.ofDays(3);

  /** The most sensitive tables listed; the counts always cover all of them. */
  static final int TABLE_ROWS = 100;

  private static final int BUSIEST = 8;

  private final DashboardQuery query;
  private final QueryLog log;
  private final Clock clock;

  public DashboardResource(DashboardQuery query, QueryLog log) {
    this(query, log, Clock.systemUTC());
  }

  public DashboardResource(DashboardQuery query, QueryLog log, Clock clock) {
    this.query = query;
    this.log = log;
    this.clock = clock;
  }

  /**
   * How far the sensitive tables are covered.
   *
   * @param tables every table and view in the catalogue
   * @param sensitive those carrying the label
   * @param protectedTables those with an active data policy
   * @param exposed sensitive, unprotected, and somebody can get in
   * @param readUnprotected sensitive, unprotected, and read in the window
   * @param rows the sensitive tables, unprotected first, at most {@link #TABLE_ROWS}
   */
  public record Coverage(
      int tables,
      int sensitive,
      int protectedTables,
      int exposed,
      int readUnprotected,
      List<DashboardQuery.SensitiveTable> rows) {}

  /** One UTC day of queries. */
  public record Day(String date, int executed, int rejected, int failed) {}

  /** Refused and failed queries in the window, by why. */
  public record Refusal(QueryRefusals.Category category, int count) {}

  public record Activity(
      QueryLog.Counts counts,
      List<Day> perDay,
      List<Refusal> refusals,
      List<DashboardQuery.Busiest> busiestTables,
      List<DashboardQuery.Busiest> busiestPeople,
      DashboardQuery.Decisions decisions) {}

  public enum Severity {
    HIGH,
    MEDIUM,
    LOW
  }

  /**
   * Something on the dashboard that wants a person.
   *
   * @param kind what it is; the page words it and links to where it is fixed
   * @param count how many things it is about
   * @param subject the first of them, where one names it (a table, a person)
   */
  public record Attention(String kind, Severity severity, int count, String subject) {}

  public record Dashboard(
      Instant generatedAt,
      Instant since,
      int days,
      String label,
      Coverage coverage,
      DashboardQuery.GrantPicture grants,
      Activity activity,
      DashboardQuery.Requests requests,
      DashboardQuery.Health health,
      List<Attention> attention) {}

  @GET
  public Dashboard dashboard(
      @QueryParam("days") @DefaultValue("30") int days,
      @QueryParam("label") @DefaultValue("PII") String label,
      @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (!Stewardship.overseesEverything(caller)) {
      throw new ForbiddenException(
          "The dashboard covers every table and every person, so it is for administrators,"
              + " policy authors and auditors");
    }
    if (days < 1 || days > MAX_DAYS) {
      throw new BadRequestException("days must be between 1 and " + MAX_DAYS + "; got " + days);
    }
    String sensitiveLabel = label == null ? "" : label.trim();
    if (sensitiveLabel.isEmpty() || sensitiveLabel.length() > 200) {
      throw new BadRequestException("label must be a classification or tag name of 1 to 200 characters");
    }

    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    LocalDate first = today.minusDays(days - 1L);
    Instant since = first.atStartOfDay(ZoneOffset.UTC).toInstant();

    List<DashboardQuery.SensitiveTable> sensitive = query.sensitiveTables(sensitiveLabel, since);
    Coverage coverage = coverage(query.tableCount(), sensitive);

    QueryLog.Reader everything = new QueryLog.Reader(true, "", List.of());
    QueryLog.Filter window = new QueryLog.Filter(null, null, null, null, null, since, null, null);
    Activity activity =
        new Activity(
            log.counts(everything, window),
            days(first, today, log.perDay(everything, window)),
            refusals(log.reasons(everything, window, 1000)),
            query.busiestTables(since, BUSIEST),
            query.busiestPeople(since, BUSIEST),
            query.decisions(since));

    DashboardQuery.GrantPicture grants =
        query.grants(sensitiveLabel, now.plus(ENDING_HORIZON), now.minus(IDLE));
    DashboardQuery.Requests requests = query.requests(since);
    DashboardQuery.Health health = query.health();

    return new Dashboard(
        now,
        since,
        days,
        sensitiveLabel,
        coverage,
        grants,
        activity,
        requests,
        health,
        attention(now, sensitive, grants, requests, health));
  }

  static Coverage coverage(int tables, List<DashboardQuery.SensitiveTable> sensitive) {
    int covered = 0;
    int exposed = 0;
    int read = 0;
    for (DashboardQuery.SensitiveTable table : sensitive) {
      if (table.protectedByPolicy()) {
        covered++;
      } else {
        if (table.reachable()) {
          exposed++;
        }
        if (table.readers() > 0) {
          read++;
        }
      }
    }
    List<DashboardQuery.SensitiveTable> rows =
        sensitive.size() > TABLE_ROWS ? List.copyOf(sensitive.subList(0, TABLE_ROWS)) : List.copyOf(sensitive);
    return new Coverage(tables, sensitive.size(), covered, exposed, read, rows);
  }

  /** Every day of the window, the quiet ones as zeros, so the chart's x-axis is time. */
  static List<Day> days(LocalDate first, LocalDate last, Map<String, int[]> counted) {
    List<Day> out = new ArrayList<>();
    for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
      int[] n = counted.getOrDefault(day.toString(), new int[3]);
      out.add(new Day(day.toString(), n[0], n[1], n[2]));
    }
    return out;
  }

  /** The reasons as the proxy wrote them, gathered into the categories a person reads. */
  static List<Refusal> refusals(List<QueryLog.ReasonCount> reasons) {
    Map<QueryRefusals.Category, Integer> byCategory = new EnumMap<>(QueryRefusals.Category.class);
    for (QueryLog.ReasonCount reason : reasons) {
      byCategory.merge(QueryRefusals.categorize(reason.outcome(), reason.reason()), reason.count(), Integer::sum);
    }
    return byCategory.entrySet().stream()
        .map(e -> new Refusal(e.getKey(), e.getValue()))
        .sorted(Comparator.comparingInt(Refusal::count).reversed().thenComparing(Refusal::category))
        .toList();
  }

  /**
   * What wants a person, most urgent first. Each item is a count, not a
   * verdict: a sensitive table without a data policy may be one nobody is let
   * into, which is why who can reach it decides how loud it is.
   */
  static List<Attention> attention(
      Instant now,
      List<DashboardQuery.SensitiveTable> sensitive,
      DashboardQuery.GrantPicture grants,
      DashboardQuery.Requests requests,
      DashboardQuery.Health health) {
    List<Attention> out = new ArrayList<>();

    List<DashboardQuery.SensitiveTable> read =
        sensitive.stream().filter(t -> !t.protectedByPolicy() && t.readers() > 0).toList();
    if (!read.isEmpty()) {
      out.add(new Attention("UNPROTECTED_READ", Severity.HIGH, read.size(), read.get(0).fqn()));
    }
    List<DashboardQuery.SensitiveTable> exposed =
        sensitive.stream()
            .filter(t -> !t.protectedByPolicy() && t.reachable() && t.readers() == 0)
            .toList();
    if (!exposed.isEmpty()) {
      out.add(new Attention("UNPROTECTED_REACHABLE", Severity.HIGH, exposed.size(), exposed.get(0).fqn()));
    }

    int faults =
        health.enforcement().getOrDefault("DRIFTED", 0) + health.enforcement().getOrDefault("FAILED", 0);
    if (faults > 0) {
      out.add(new Attention("ENFORCEMENT_FAULT", Severity.HIGH, faults, null));
    }
    if (health.syncFailed()) {
      out.add(new Attention("SYNC_FAILED", Severity.HIGH, 1, null));
    } else if (health.syncStatus() != null
        && (health.lastCrawlAt() == null || health.lastCrawlAt().isBefore(now.minus(STALE_SYNC)))) {
      out.add(new Attention("SYNC_STALE", Severity.MEDIUM, 1, null));
    }

    int open = requests.pending() + requests.approved() + requests.inProgress();
    if (open > 0 && requests.oldestOpenAt() != null && requests.oldestOpenAt().isBefore(now.minus(OLD_REQUEST))) {
      out.add(
          new Attention(
              "REQUESTS_WAITING",
              Severity.MEDIUM,
              open,
              Long.toString(ChronoUnit.DAYS.between(requests.oldestOpenAt(), now))));
    }
    if (grants.endingCount() > 0) {
      String first = grants.endingSoon().isEmpty() ? null : grants.endingSoon().get(0).principal();
      out.add(new Attention("GRANTS_ENDING", Severity.MEDIUM, grants.endingCount(), first));
    }

    List<DashboardQuery.SensitiveTable> unread =
        sensitive.stream().filter(t -> !t.protectedByPolicy() && !t.reachable()).toList();
    if (!unread.isEmpty()) {
      out.add(new Attention("UNPROTECTED_CLOSED", Severity.LOW, unread.size(), unread.get(0).fqn()));
    }
    if (grants.unused() > 0) {
      out.add(new Attention("GRANTS_UNUSED", Severity.LOW, grants.unused(), null));
    }
    if (grants.openEnded() > 0) {
      out.add(new Attention("GRANTS_OPEN_ENDED", Severity.LOW, grants.openEnded(), null));
    }
    return out;
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }
}
