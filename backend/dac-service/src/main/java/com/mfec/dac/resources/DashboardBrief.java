package com.mfec.dac.resources;

import com.mfec.dac.dashboard.DashboardQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The dashboard as NokRak is told it (M15 (ข)): the counts on the page, in
 * lines a model reads, and nobody's name.
 *
 * <p>The dashboard already counts rather than quotes -- no statement text and
 * no client address is in it -- so what is left to keep back is people. Each
 * person or group the page names (the most active people, whose grants end
 * soon) goes out as {@code [P1]}, {@code [P2]} and so on, and {@link
 * #restorePeople} puts the names back into the answer for the person who
 * asked, who could already see them on the page. Owners go out as a count.
 * Tables go out by name: the other NokRak jobs already send the catalogue.
 */
public final class DashboardBrief {

  /** The most sensitive tables listed; the counts always cover all of them. */
  static final int TABLES = 15;

  /** Past a month the days are summed into about thirty buckets. */
  static final int DAILY_UP_TO = 31;

  private static final Pattern TAG = Pattern.compile("\\[P(\\d{1,4})\\]");

  /** Which part of the page to explain. */
  public enum Focus {
    ALL,
    COVERAGE,
    ACTIVITY,
    ACCESS,
    REQUESTS,
    HEALTH;

    /** Blank asks for the whole page. */
    public static Focus parse(String text) {
      if (text == null || text.isBlank()) {
        return ALL;
      }
      try {
        return valueOf(text.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "focus must be one of ALL, COVERAGE, ACTIVITY, ACCESS, REQUESTS or HEALTH; got " + text.trim());
      }
    }
  }

  /**
   * @param text what the model is sent
   * @param people the names behind {@code [P1]}, {@code [P2]}, ... in that order
   */
  public record Brief(String text, List<String> people) {}

  private DashboardBrief() {}

  public static Brief of(DashboardResource.Dashboard dashboard, Focus focus) {
    People people = new People();
    StringBuilder out = new StringBuilder();
    out.append("Window: the last ")
        .append(count(dashboard.days(), "day"))
        .append(dashboard.since() == null ? "" : " (since " + date(dashboard.since()) + ", UTC)")
        .append(". Sensitive label: \"")
        .append(dashboard.label())
        .append("\". Part of the page asked about: ")
        .append(focus.name())
        .append(".\n");

    attention(out, dashboard, focus, people);
    if (focus == Focus.ALL || focus == Focus.COVERAGE) {
      coverage(out, dashboard);
    }
    if (focus == Focus.ALL || focus == Focus.ACTIVITY) {
      activity(out, dashboard, people);
    }
    if (focus == Focus.ALL || focus == Focus.ACCESS) {
      access(out, dashboard, people);
    }
    if (focus == Focus.ALL || focus == Focus.REQUESTS) {
      requests(out, dashboard);
    }
    if (focus == Focus.ALL || focus == Focus.HEALTH) {
      health(out, dashboard, focus);
    }
    return new Brief(out.toString().trim(), List.copyOf(people.names));
  }

  /**
   * The answer with each {@code [Pn]} the brief handed out turned back into the
   * name. A tag the brief did not hand out is left as the model wrote it.
   */
  public static String restorePeople(String text, List<String> people) {
    if (text == null || text.isEmpty() || people.isEmpty()) {
      return text == null ? "" : text;
    }
    Matcher tags = TAG.matcher(text);
    return tags.replaceAll(
        found -> {
          int n = Integer.parseInt(found.group(1));
          String name = n >= 1 && n <= people.size() ? people.get(n - 1) : found.group();
          return Matcher.quoteReplacement(name);
        });
  }

  // ------------------------------------------------------------------ sections

  private static final Map<Focus, Set<String>> ATTENTION_FOR =
      Map.of(
          Focus.COVERAGE, Set.of("UNPROTECTED_READ", "UNPROTECTED_REACHABLE", "UNPROTECTED_CLOSED"),
          Focus.ACCESS, Set.of("GRANTS_ENDING", "GRANTS_UNUSED", "GRANTS_OPEN_ENDED"),
          Focus.REQUESTS, Set.of("REQUESTS_WAITING"),
          Focus.HEALTH, Set.of("ENFORCEMENT_FAULT", "SYNC_FAILED", "SYNC_STALE"));

  private static void attention(
      StringBuilder out, DashboardResource.Dashboard dashboard, Focus focus, People people) {
    if (focus == Focus.ACTIVITY) {
      return;
    }
    Set<String> kinds = ATTENTION_FOR.get(focus);
    List<DashboardResource.Attention> items =
        dashboard.attention().stream()
            .filter(item -> kinds == null || kinds.contains(item.kind()))
            .toList();
    out.append("\nNeeds attention, most urgent first:\n");
    if (items.isEmpty()) {
      out.append("- nothing\n");
      return;
    }
    for (DashboardResource.Attention item : items) {
      out.append("- ")
          .append(item.severity())
          .append(' ')
          .append(item.kind())
          .append(": ")
          .append(item.count());
      if (item.subject() != null && !item.subject().isBlank()) {
        switch (item.kind()) {
          case "GRANTS_ENDING" -> out.append(" (the first ends for ").append(people.tag(item.subject())).append(')');
          case "REQUESTS_WAITING" -> out.append(" (the oldest has waited ").append(count(parse(item.subject()), "day")).append(')');
          default -> out.append(" (the first is ").append(item.subject()).append(')');
        }
      }
      out.append('\n');
    }
  }

  private static void coverage(StringBuilder out, DashboardResource.Dashboard dashboard) {
    DashboardResource.Coverage coverage = dashboard.coverage();
    out.append("\nCoverage of the labelled tables:\n")
        .append("- ")
        .append(count(coverage.tables(), "table or view", "tables and views"))
        .append(" in the catalogue; ")
        .append(coverage.sensitive())
        .append(" carry the label; ")
        .append(coverage.protectedTables())
        .append(" of those have an active data policy; ")
        .append(coverage.exposed())
        .append(" have none and somebody can get in; ")
        .append(coverage.readUnprotected())
        .append(" have none and were read in the window\n");
    List<DashboardQuery.SensitiveTable> rows = coverage.rows();
    if (rows.isEmpty()) {
      return;
    }
    out.append("Labelled tables, unprotected first (labelled columns, data policies,")
        .append(" subscription policies letting people in, grants, readers and refused queries")
        .append(" in the window, owners):\n");
    for (DashboardQuery.SensitiveTable table : rows.subList(0, Math.min(TABLES, rows.size()))) {
      out.append("- ")
          .append(table.fqn())
          .append(": ")
          .append(table.sensitiveColumns())
          .append(" labelled columns, ")
          .append(table.dataPolicies())
          .append(" data, ")
          .append(table.subscriptionPolicies())
          .append(" subscription, ")
          .append(table.activeGrants())
          .append(" grants, ")
          .append(table.readers())
          .append(" readers, ")
          .append(table.refused())
          .append(" refused, ")
          .append(table.owners() == null ? 0 : table.owners().size())
          .append(" owners\n");
    }
    int unlisted = coverage.sensitive() - Math.min(TABLES, rows.size());
    if (unlisted > 0) {
      out.append("- and ").append(count(unlisted, "more labelled table", "more labelled tables")).append(" not listed here\n");
    }
  }

  private static void activity(StringBuilder out, DashboardResource.Dashboard dashboard, People people) {
    DashboardResource.Activity activity = dashboard.activity();
    out.append("\nQueries sent through the proxy in the window:\n")
        .append("- ")
        .append(activity.counts().total())
        .append(" in all: ")
        .append(activity.counts().executed())
        .append(" ran, ")
        .append(activity.counts().rejected())
        .append(" refused, ")
        .append(activity.counts().failed())
        .append(" failed at the source\n");
    perDay(out, dashboard.days(), activity.perDay());

    if (!activity.refusals().isEmpty()) {
      out.append("- Refused and failed, by reason: ");
      List<String> parts = new ArrayList<>();
      for (DashboardResource.Refusal refusal : activity.refusals()) {
        parts.add(refusal.category() + " " + refusal.count());
      }
      out.append(String.join(", ", parts)).append('\n');
    }
    if (!activity.busiestTables().isEmpty()) {
      out.append("- Most-read tables:\n");
      for (DashboardQuery.Busiest table : activity.busiestTables()) {
        out.append("  - ")
            .append(table.name())
            .append(": ")
            .append(count(table.queries(), "query", "queries"))
            .append(", ")
            .append(table.refused())
            .append(" refused, by ")
            .append(count(table.people(), "person", "people"))
            .append(", last ")
            .append(date(table.lastAt()))
            .append('\n');
      }
    }
    if (!activity.busiestPeople().isEmpty()) {
      out.append("- Most active people:\n");
      for (DashboardQuery.Busiest person : activity.busiestPeople()) {
        out.append("  - ")
            .append(people.tag(person.name()))
            .append(": ")
            .append(count(person.queries(), "query", "queries"))
            .append(", ")
            .append(person.refused())
            .append(" refused, on ")
            .append(count(person.people(), "table"))
            .append(", last ")
            .append(date(person.lastAt()))
            .append('\n');
      }
    }
    decisions(out, activity.decisions());
  }

  /**
   * The days of the window, the quiet ones left out. A long window is summed
   * into buckets so the brief stays the same size whatever the window.
   */
  static void perDay(StringBuilder out, int days, List<DashboardResource.Day> perDay) {
    int size = days <= DAILY_UP_TO ? 1 : (days + 29) / 30;
    List<String> lines = new ArrayList<>();
    for (int from = 0; from < perDay.size(); from += size) {
      List<DashboardResource.Day> bucket = perDay.subList(from, Math.min(perDay.size(), from + size));
      int executed = 0;
      int rejected = 0;
      int failed = 0;
      for (DashboardResource.Day day : bucket) {
        executed += day.executed();
        rejected += day.rejected();
        failed += day.failed();
      }
      if (executed + rejected + failed == 0) {
        continue;
      }
      String when =
          bucket.size() == 1
              ? bucket.get(0).date()
              : bucket.get(0).date() + " to " + bucket.get(bucket.size() - 1).date();
      lines.add("  - " + when + ": " + executed + " / " + rejected + " / " + failed);
    }
    if (lines.isEmpty()) {
      out.append("- No query on any day of the window\n");
      return;
    }
    out.append(size == 1 ? "- By day" : "- By " + size + "-day stretch")
        .append(" (ran / refused / failed), days with none left out:\n");
    for (String line : lines) {
      out.append(line).append('\n');
    }
  }

  private static void decisions(StringBuilder out, DashboardQuery.Decisions decisions) {
    out.append("- Policy engine decisions: ")
        .append(decisions.total())
        .append(", of which ")
        .append(decisions.denied())
        .append(" denied and ")
        .append(decisions.fromCache())
        .append(" answered from cache");
    if (decisions.p50Ms() != null) {
      out.append("; median ").append(decisions.p50Ms()).append(" ms");
    }
    if (decisions.p95Ms() != null) {
      out.append(", 95th percentile ").append(decisions.p95Ms()).append(" ms");
    }
    out.append('\n');
  }

  private static void access(StringBuilder out, DashboardResource.Dashboard dashboard, People people) {
    DashboardQuery.GrantPicture grants = dashboard.grants();
    out.append("\nGrants in force now:\n")
        .append("- ")
        .append(grants.active())
        .append(" in all; ")
        .append(grants.onSensitive())
        .append(" on labelled tables; ")
        .append(grants.openEnded())
        .append(" with no end date; ")
        .append(grants.unused())
        .append(" held for over 90 days with no query; ")
        .append(grants.endingCount())
        .append(" end in the next 14 days\n");
    if (!grants.endingSoon().isEmpty()) {
      out.append("- Ending soonest:\n");
      for (DashboardQuery.EndingGrant grant : grants.endingSoon()) {
        out.append("  - ")
            .append(people.tag(grant.principal()))
            .append(grant.principalType() == null ? "" : " (" + grant.principalType().toLowerCase(Locale.ROOT) + ")")
            .append(" on ")
            .append(grant.assetFqn())
            .append(", ends ")
            .append(date(grant.validUntil()))
            .append(grant.source() == null ? "" : ", given by " + grant.source().toLowerCase(Locale.ROOT))
            .append('\n');
      }
      if (grants.endingCount() > grants.endingSoon().size()) {
        out.append("  - and ")
            .append(grants.endingCount() - grants.endingSoon().size())
            .append(" more not listed here\n");
      }
    }
  }

  private static void requests(StringBuilder out, DashboardResource.Dashboard dashboard) {
    DashboardQuery.Requests requests = dashboard.requests();
    out.append("\nAccess requests:\n")
        .append("- Open now: ")
        .append(requests.pending())
        .append(" waiting for approval, ")
        .append(requests.approved())
        .append(" approved and waiting to be carried out, ")
        .append(requests.inProgress())
        .append(" in progress");
    if (requests.oldestOpenAt() != null && dashboard.generatedAt() != null) {
      out.append("; the oldest was asked ")
          .append(date(requests.oldestOpenAt()))
          .append(", ")
          .append(count(ChronoUnit.DAYS.between(requests.oldestOpenAt(), dashboard.generatedAt()), "day"))
          .append(" ago");
    }
    out.append('\n')
        .append("- Asked in the window: ")
        .append(requests.asked())
        .append("; closed in the window: ")
        .append(requests.completed())
        .append(" completed, ")
        .append(requests.rejected())
        .append(" rejected, ")
        .append(requests.withdrawn())
        .append(" withdrawn");
    if (requests.medianHoursToClose() != null) {
      out.append("; median ")
          .append(String.format(Locale.ROOT, "%.1f", requests.medianHoursToClose()))
          .append(" hours from asking to a final answer");
    }
    out.append('\n');
  }

  private static void health(StringBuilder out, DashboardResource.Dashboard dashboard, Focus focus) {
    DashboardQuery.Health health = dashboard.health();
    out.append("\nThe platform itself:\n")
        .append("- Data sources: ")
        .append(health.sources())
        .append(", of which ")
        .append(health.sourcesEnabled())
        .append(" switched on\n")
        .append("- Enforced objects (secure views and native) by status: ")
        .append(tally(health.enforcement()))
        .append('\n')
        .append("- Policies by type and state: ")
        .append(tally(health.policies()))
        .append('\n')
        .append("- OpenMetadata sync: ");
    if (health.syncStatus() == null) {
      out.append("never run");
    } else {
      out.append(health.syncFailed() ? "the last one failed" : health.syncStatus())
          .append(", last crawl ")
          .append(health.lastCrawlAt() == null ? "never finished" : minute(health.lastCrawlAt()));
    }
    out.append('\n');
    if (focus == Focus.HEALTH) {
      decisions(out, dashboard.activity().decisions());
    }
  }

  // ------------------------------------------------------------------- helpers

  /** Hands out {@code [P1]}, {@code [P2]}, ... one per distinct name. */
  private static final class People {
    final List<String> names = new ArrayList<>();
    private final Map<String, String> tags = new LinkedHashMap<>();

    String tag(String name) {
      if (name == null || name.isBlank()) {
        return "someone unnamed";
      }
      return tags.computeIfAbsent(
          name,
          key -> {
            names.add(key);
            return "[P" + names.size() + "]";
          });
    }
  }

  private static String tally(Map<String, Integer> counts) {
    if (counts == null || counts.isEmpty()) {
      return "none";
    }
    List<String> parts = new ArrayList<>();
    counts.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(e -> parts.add(e.getKey() + " " + e.getValue()));
    return String.join(", ", parts);
  }

  private static long parse(String number) {
    try {
      return Long.parseLong(number.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String count(long n, String noun) {
    return count(n, noun, noun + "s");
  }

  private static String count(long n, String one, String many) {
    return n + " " + (n == 1 ? one : many);
  }

  private static String date(Instant at) {
    return at == null ? "never" : LocalDate.ofInstant(at, ZoneOffset.UTC).toString();
  }

  private static String minute(Instant at) {
    return at.truncatedTo(ChronoUnit.MINUTES).toString();
  }
}
