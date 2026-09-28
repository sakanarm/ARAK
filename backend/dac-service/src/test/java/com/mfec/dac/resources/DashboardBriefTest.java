package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.audit.QueryLog;
import com.mfec.dac.audit.QueryRefusals;
import com.mfec.dac.dashboard.DashboardQuery;
import com.mfec.dac.dashboard.DashboardQuery.SensitiveTable;
import com.mfec.dac.resources.DashboardBrief.Focus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What NokRak is told about the dashboard (M15): the page's counts, and
 * people only as [P1], [P2].
 */
class DashboardBriefTest {

  private static final Instant NOW = Instant.parse("2026-09-25T06:00:00Z");
  private static final String CUSTOMER = "demo-pg.salesdb.sales.customer";
  private static final String ORDERS = "demo-pg.salesdb.sales.orders";
  private static final String SALARY = "demo-pg.salesdb.hr.salary";

  /** A thirty-day dashboard with a little of everything on it. */
  static DashboardResource.Dashboard sample() {
    List<SensitiveTable> sensitive =
        List.of(
            new SensitiveTable(CUSTOMER, 2, 0, 1, 2, 3, 1, List.of("owner_o", "owner_p")),
            new SensitiveTable(ORDERS, 1, 0, 0, 0, 0, 0, List.of("owner_o")),
            new SensitiveTable(SALARY, 1, 1, 1, 1, 1, 0, List.of()));
    DashboardQuery.GrantPicture grants =
        new DashboardQuery.GrantPicture(
            12,
            2,
            List.of(
                new DashboardQuery.EndingGrant(
                    "analyst_b", "USER", CUSTOMER, Instant.parse("2026-09-30T00:00:00Z"), "REQUEST"),
                new DashboardQuery.EndingGrant(
                    "fraud-team", "GROUP", SALARY, Instant.parse("2026-10-03T00:00:00Z"), "MANUAL")),
            3,
            5,
            4);
    DashboardQuery.Requests requests =
        new DashboardQuery.Requests(2, 1, 0, NOW.minus(Duration.ofDays(6)), 9, 5, 1, 1, 5.5);
    DashboardQuery.Health health =
        new DashboardQuery.Health(
            3,
            2,
            Map.of("APPLIED", 4, "DRIFTED", 1),
            Map.of("SUBSCRIPTION ACTIVE", 3, "DATA ACTIVE", 1),
            "SUCCESS",
            NOW.minus(Duration.ofHours(1)),
            false);
    Instant yesterday = NOW.minus(Duration.ofDays(1));
    DashboardResource.Activity activity =
        new DashboardResource.Activity(
            new QueryLog.Counts(40, 30, 8, 2),
            DashboardResource.days(
                LocalDate.parse("2026-08-27"),
                LocalDate.parse("2026-09-25"),
                Map.of("2026-09-01", new int[] {10, 2, 0}, "2026-09-24", new int[] {20, 6, 2})),
            List.of(
                new DashboardResource.Refusal(QueryRefusals.Category.POLICY_DENY, 6),
                new DashboardResource.Refusal(QueryRefusals.Category.BUSY, 2),
                new DashboardResource.Refusal(QueryRefusals.Category.SOURCE_ERROR, 2)),
            List.of(new DashboardQuery.Busiest(CUSTOMER, 25, 5, 3, yesterday)),
            List.of(
                new DashboardQuery.Busiest("analyst_a", 20, 4, 2, yesterday),
                new DashboardQuery.Busiest("analyst_b", 10, 1, 1, yesterday)),
            new DashboardQuery.Decisions(200, 15, 150, 3, 18));
    return new DashboardResource.Dashboard(
        NOW,
        Instant.parse("2026-08-27T00:00:00Z"),
        30,
        "PII",
        DashboardResource.coverage(120, sensitive),
        grants,
        activity,
        requests,
        health,
        DashboardResource.attention(NOW, sensitive, grants, requests, health));
  }

  @Test
  @DisplayName("numbers every person and group it names, and sends owners as a count")
  void namesNobody() {
    DashboardBrief.Brief brief = DashboardBrief.of(sample(), Focus.ALL);

    assertThat(brief.people()).containsExactly("analyst_b", "analyst_a", "fraud-team");
    assertThat(brief.text())
        .contains("[P1]", "[P2]", "[P3]", CUSTOMER, "2 owners")
        .doesNotContain("analyst_a", "analyst_b", "fraud-team", "owner_o", "owner_p");
  }

  @Test
  @DisplayName("carries the whole page's counts, in the page's own terms")
  void wholePage() {
    String text = DashboardBrief.of(sample(), Focus.ALL).text();

    assertThat(text)
        .startsWith(
            "Window: the last 30 days (since 2026-08-27, UTC). Sensitive label: \"PII\"."
                + " Part of the page asked about: ALL.")
        .contains("- HIGH UNPROTECTED_READ: 1 (the first is " + CUSTOMER + ")")
        .contains("- HIGH ENFORCEMENT_FAULT: 1\n")
        .contains("- MEDIUM REQUESTS_WAITING: 3 (the oldest has waited 6 days)")
        .contains("- MEDIUM GRANTS_ENDING: 2 (the first ends for [P1])")
        .contains("- LOW UNPROTECTED_CLOSED: 1 (the first is " + ORDERS + ")")
        .contains(
            "- 120 tables and views in the catalogue; 3 carry the label; 1 of those have an active"
                + " data policy; 1 have none and somebody can get in; 1 have none and were read")
        .contains(
            "- " + CUSTOMER + ": 2 labelled columns, 0 data, 1 subscription, 2 grants, 3 readers,"
                + " 1 refused, 2 owners")
        .contains("- 40 in all: 30 ran, 8 refused, 2 failed at the source")
        .contains("  - 2026-09-01: 10 / 2 / 0\n  - 2026-09-24: 20 / 6 / 2")
        .contains("- Refused and failed, by reason: POLICY_DENY 6, BUSY 2, SOURCE_ERROR 2")
        .contains("  - " + CUSTOMER + ": 25 queries, 5 refused, by 3 people, last 2026-09-24")
        .contains("  - [P2]: 20 queries, 4 refused, on 2 tables, last 2026-09-24")
        .contains("  - [P1]: 10 queries, 1 refused, on 1 table, last 2026-09-24")
        .contains(
            "- Policy engine decisions: 200, of which 15 denied and 150 answered from cache;"
                + " median 3 ms, 95th percentile 18 ms")
        .contains(
            "- 12 in all; 5 on labelled tables; 3 with no end date; 4 held for over 90 days with"
                + " no query; 2 end in the next 14 days")
        .contains("  - [P1] (user) on " + CUSTOMER + ", ends 2026-09-30, given by request")
        .contains("  - [P3] (group) on " + SALARY + ", ends 2026-10-03, given by manual")
        .contains(
            "- Open now: 2 waiting for approval, 1 approved and waiting to be carried out, 0 in"
                + " progress; the oldest was asked 2026-09-19, 6 days ago")
        .contains("9; closed in the window: 5 completed, 1 rejected, 1 withdrawn; median 5.5 hours")
        .contains("- Data sources: 3, of which 2 switched on")
        .contains("by status: APPLIED 4, DRIFTED 1")
        .contains("- Policies by type and state: DATA ACTIVE 1, SUBSCRIPTION ACTIVE 3")
        .contains("- OpenMetadata sync: SUCCESS, last crawl 2026-09-25T05:00:00Z")
        .doesNotContain("more not listed", "more labelled");
  }

  @Test
  @DisplayName("keeps to the part of the page asked about")
  void focus() {
    DashboardResource.Dashboard page = sample();

    DashboardBrief.Brief coverage = DashboardBrief.of(page, Focus.COVERAGE);
    assertThat(coverage.text())
        .contains("Coverage of the labelled tables", "UNPROTECTED_READ", "UNPROTECTED_CLOSED")
        .doesNotContain(
            "GRANTS_ENDING", "ENFORCEMENT_FAULT", "Queries sent", "Grants in force",
            "Access requests", "The platform itself");
    assertThat(coverage.people()).isEmpty();

    assertThat(DashboardBrief.of(page, Focus.ACTIVITY).text())
        .contains("Queries sent", "Most active people", "Policy engine decisions")
        .doesNotContain("Needs attention", "Coverage of", "Grants in force");

    assertThat(DashboardBrief.of(page, Focus.ACCESS).text())
        .contains("GRANTS_ENDING", "GRANTS_UNUSED", "GRANTS_OPEN_ENDED", "Grants in force")
        .doesNotContain("UNPROTECTED", "REQUESTS_WAITING", "Queries sent");

    assertThat(DashboardBrief.of(page, Focus.REQUESTS).text())
        .contains("REQUESTS_WAITING", "Access requests")
        .doesNotContain("GRANTS_ENDING", "Grants in force", "Coverage of");

    assertThat(DashboardBrief.of(page, Focus.HEALTH).text())
        .contains("ENFORCEMENT_FAULT", "The platform itself", "Policy engine decisions")
        .doesNotContain("UNPROTECTED", "Queries sent", "Access requests");
  }

  @Test
  @DisplayName("says so when nothing in the part asked about wants a person")
  void nothingWaiting() {
    DashboardResource.Dashboard page = sample();
    DashboardResource.Dashboard quiet =
        new DashboardResource.Dashboard(
            page.generatedAt(), page.since(), page.days(), page.label(), page.coverage(),
            page.grants(), page.activity(), page.requests(), page.health(), List.of());

    assertThat(DashboardBrief.of(quiet, Focus.HEALTH).text())
        .contains("Needs attention, most urgent first:\n- nothing\n");
  }

  @Test
  @DisplayName("lists fifteen labelled tables and says how many more there are")
  void tablesCapped() {
    List<SensitiveTable> many = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      many.add(new SensitiveTable("demo-pg.salesdb.sales.t" + i, 1, 0, 0, 0, 0, 0, List.of()));
    }
    DashboardResource.Dashboard page = sample();
    DashboardResource.Dashboard wide =
        new DashboardResource.Dashboard(
            page.generatedAt(), page.since(), page.days(), page.label(),
            DashboardResource.coverage(500, many), page.grants(), page.activity(),
            page.requests(), page.health(), page.attention());

    String text = DashboardBrief.of(wide, Focus.COVERAGE).text();

    assertThat(text).contains("demo-pg.salesdb.sales.t14:").doesNotContain("demo-pg.salesdb.sales.t15:");
    assertThat(text).contains("- and 5 more labelled tables not listed here");
  }

  @Test
  @DisplayName("sums a long window into stretches and leaves the quiet ones out")
  void perDay() {
    LocalDate first = LocalDate.parse("2026-06-28");
    List<DashboardResource.Day> ninety =
        DashboardResource.days(
            first,
            first.plusDays(89),
            Map.of(
                "2026-06-28", new int[] {5, 1, 0},
                "2026-06-30", new int[] {1, 0, 0},
                "2026-09-25", new int[] {0, 0, 3}));
    StringBuilder out = new StringBuilder();
    DashboardBrief.perDay(out, 90, ninety);

    assertThat(out.toString())
        .startsWith("- By 3-day stretch (ran / refused / failed), days with none left out:\n")
        .contains("  - 2026-06-28 to 2026-06-30: 6 / 1 / 0\n")
        .contains("  - 2026-09-23 to 2026-09-25: 0 / 0 / 3\n");
    assertThat(out.toString().lines().count()).isEqualTo(3);

    StringBuilder quiet = new StringBuilder();
    DashboardBrief.perDay(
        quiet, 7, DashboardResource.days(first, first.plusDays(6), Map.of()));
    assertThat(quiet.toString()).isEqualTo("- No query on any day of the window\n");
  }

  @Test
  @DisplayName("puts the names back, and leaves a tag it never handed out alone")
  void restoresPeople() {
    String answer = "Ask [P1] and [P2]; [P9], [P0] and P1 stay as written.";

    assertThat(DashboardBrief.restorePeople(answer, List.of("analyst_b", "a$b\\c")))
        .isEqualTo("Ask analyst_b and a$b\\c; [P9], [P0] and P1 stay as written.");
    assertThat(DashboardBrief.restorePeople(answer, List.of())).isEqualTo(answer);
    assertThat(DashboardBrief.restorePeople(null, List.of("x"))).isEmpty();
  }

  @Test
  @DisplayName("reads the part asked about, and refuses one the page does not have")
  void parsesFocus() {
    assertThat(Focus.parse(null)).isEqualTo(Focus.ALL);
    assertThat(Focus.parse("  ")).isEqualTo(Focus.ALL);
    assertThat(Focus.parse(" coverage ")).isEqualTo(Focus.COVERAGE);
    assertThatThrownBy(() -> Focus.parse("everything"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HEALTH")
        .hasMessageContaining("everything");
  }
}
