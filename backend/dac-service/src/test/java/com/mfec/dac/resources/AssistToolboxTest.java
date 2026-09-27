package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.access.AccessEligibility;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.catalog.CatalogQuery;
import com.mfec.dac.catalog.SearchQuery;
import com.mfec.dac.llm.ArakAgent.Card;
import com.mfec.dac.llm.ArakAgent.Result;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.source.DataSourceStore;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The chat's tools (M28), each run as one person.
 *
 * <p>The promise checked here is that a tool says nothing the person's own
 * pages would not: a table they may neither read nor request is not found,
 * columns their decision hides are not listed, and nothing is run.
 */
@DisplayName("AssistToolbox — the chat's tools, as the person chatting")
class AssistToolboxTest {

  private static final String READABLE = "demo-pg.salesdb.sales.customer";
  private static final String REQUESTABLE = "demo-pg.hrdb.hr.salary";
  private static final String HIDDEN = "demo-pg.secretdb.vault.keys";
  private static final String IP = "192.0.2.77";

  private final ObjectMapper json = new ObjectMapper();
  private final CatalogQuery catalog = mock(CatalogQuery.class);
  private final SearchQuery search = mock(SearchQuery.class);
  private final AccessEligibility eligibility = mock(AccessEligibility.class);
  private final DecisionService decisions = mock(DecisionService.class);
  private final DataSourceStore sources = mock(DataSourceStore.class);
  private final AuditResource audit = mock(AuditResource.class);
  private final DashboardResource dashboard = mock(DashboardResource.class);
  private final SecurityContext security = mock(SecurityContext.class);

  private final AuthenticatedUser analyst =
      new AuthenticatedUser(
          UUID.randomUUID(),
          "analyst_a",
          "analyst_a@example.test",
          "Analyst A",
          "local",
          Set.of("REQUESTER"),
          List.of());

  private String drafted;

  private AssistToolbox toolbox;

  @BeforeEach
  void wire() {
    toolbox =
        new AssistToolbox(
            new AssistToolbox.Deps(
                json, catalog, search, eligibility, decisions, sources, audit, dashboard),
            analyst,
            security,
            IP,
            new AssistToolbox.Here(null, null),
            (intent, sourceId) -> {
              drafted = intent;
              return "{\"name\":\"draft\"}";
            });
    when(eligibility.check(eq("analyst_a"), eq(READABLE), eq(IP), any()))
        .thenReturn(verdict(READABLE, true, false));
    when(eligibility.check(eq("analyst_a"), eq(REQUESTABLE), eq(IP), any()))
        .thenReturn(verdict(REQUESTABLE, false, true));
    when(eligibility.check(eq("analyst_a"), eq(HIDDEN), eq(IP), any()))
        .thenReturn(verdict(HIDDEN, false, false));
  }

  private static AccessEligibility.Verdict verdict(String fqn, boolean read, boolean request) {
    return new AccessEligibility.Verdict(
        fqn, read, request, null, List.of(), null, false, null, true, null, null, null, null);
  }

  private static SearchQuery.Hit table(String fqn, String description) {
    return new SearchQuery.Hit(
        "asset", null, "TABLE", null, fqn, fqn, fqn, description, null, -1);
  }

  private static SearchQuery.Hit column(String tableFqn) {
    return new SearchQuery.Hit(
        "column", null, "varchar", null, tableFqn + ".x", "x", "x", null, tableFqn, -1);
  }

  private Result run(String tool, String arguments) throws Exception {
    return toolbox.run(tool, json.readTree(arguments));
  }

  // ---------------------------------------------------------------- search

  @Test
  void searchListsOnlyTablesThePersonCanReadOrRequest() throws Exception {
    when(search.search(anyString(), anyInt()))
        .thenReturn(
            new SearchQuery.Results(
                "salary",
                50,
                List.of(
                    table(HIDDEN, "Master keys"),
                    table(READABLE, "One row per customer"),
                    column(REQUESTABLE),
                    new SearchQuery.Hit(
                        "tag", "TAG", null, null, "PII.Sensitive", "Sensitive", null, null, null,
                        3))));

    Result result = run("search_catalog", "{\"query\":\"salary\"}");

    assertThat(result.content())
        .contains(READABLE, "READABLE", REQUESTABLE, "REQUESTABLE")
        .doesNotContain(HIDDEN)
        .doesNotContain("Master keys")
        .doesNotContain("PII.Sensitive");
    assertThat(result.cards()).extracting(Card::assetFqn).containsExactly(READABLE, REQUESTABLE);
    assertThat(result.cards()).extracting(Card::kind).containsOnly("asset");
  }

  @Test
  void searchAsksEachTableItsDecisionOnce() throws Exception {
    when(search.search(anyString(), anyInt()))
        .thenReturn(new SearchQuery.Results("x", 50, List.of(table(READABLE, null))));

    run("search_catalog", "{\"query\":\"customer email\"}");

    verify(eligibility, org.mockito.Mockito.times(1)).check(eq("analyst_a"), eq(READABLE), eq(IP), any());
  }

  @Test
  void aOneLetterSearchIsRefused() throws Exception {
    assertThat(run("search_catalog", "{\"query\":\"a\"}").content()).startsWith("error:");
    verify(search, never()).search(anyString(), anyInt());
  }

  // -------------------------------------------------------------- describe

  @Test
  void aHiddenTableAndAMissingOneLookTheSame() throws Exception {
    when(catalog.asset(HIDDEN)).thenReturn(Optional.of(detail(HIDDEN)));
    when(catalog.asset("nowhere.at.all")).thenReturn(Optional.empty());

    String hidden = run("describe_asset", "{\"fqn\":\"" + HIDDEN + "\"}").content();
    String missing = run("describe_asset", "{\"fqn\":\"nowhere.at.all\"}").content();

    assertThat(hidden).isEqualTo("not found, or not visible to this person: " + HIDDEN);
    assertThat(missing).isEqualTo("not found, or not visible to this person: nowhere.at.all");
    assertThat(hidden).doesNotContain("secret_col");
  }

  @Test
  void columnsTheDecisionHidesAreLeftOut() throws Exception {
    when(catalog.asset(READABLE)).thenReturn(Optional.of(detail(READABLE)));
    when(decisions.decide(any()))
        .thenReturn(new PolicyDecision().withAllowed(true).withHiddenColumns(List.of("SECRET_COL")));

    String described = run("describe_asset", "{\"fqn\":\"" + READABLE + "\"}").content();

    assertThat(described).contains("\"access\":\"READABLE\"", "email", "PII.Sensitive");
    assertThat(described).doesNotContain("secret_col");
  }

  @Test
  void aRequestableTableSaysHowToGetIt() throws Exception {
    when(catalog.asset(REQUESTABLE)).thenReturn(Optional.of(detail(REQUESTABLE)));

    String described = run("describe_asset", "{\"fqn\":\"" + REQUESTABLE + "\"}").content();

    assertThat(described).contains("REQUESTABLE", "request access");
    // A requestable table is not decided as readable, so nothing is asked of the engine.
    verify(decisions, never()).decide(any());
  }

  private static CatalogQuery.AssetDetail detail(String fqn) {
    CatalogQuery.AssetSummary summary =
        new CatalogQuery.AssetSummary(
            UUID.randomUUID(), fqn, "t", "t", "TABLE", "demo-pg.salesdb.sales", "A table", null,
            null, "demo-pg", 3, 1, List.of(), List.of(), 0, "discovered", "demo-pg");
    return new CatalogQuery.AssetDetail(
        summary,
        null,
        java.util.Map.of(),
        List.of(
            column(fqn, "id", "bigint", List.of()),
            column(fqn, "email", "varchar", List.of("PII.Sensitive")),
            column(fqn, "secret_col", "varchar", List.of())),
        List.of(),
        List.of());
  }

  private static CatalogQuery.ColumnDetail column(
      String table, String name, String type, List<String> tags) {
    return new CatalogQuery.ColumnDetail(
        UUID.randomUUID(),
        table + "." + name,
        name,
        1,
        type,
        null,
        true,
        null,
        tags.stream()
            .map(
                tag ->
                    new CatalogQuery.FacetRow(
                        "TAG", tag, null, 0, true, null, "openmetadata", "Confirmed", "Manual"))
            .toList());
  }

  // ----------------------------------------------------------------- cards

  @Test
  void aSelectBecomesACardAndIsNotRun() throws Exception {
    Result result =
        run("write_sql", "{\"sql\":\"SELECT id FROM sales.customer\",\"title\":\"Customers\"}");

    assertThat(result.content()).contains("NOT been run");
    assertThat(result.cards()).singleElement().satisfies(card -> {
      assertThat(card.kind()).isEqualTo("sql");
      assertThat(card.text()).isEqualTo("SELECT id FROM sales.customer");
      assertThat(card.route()).isEqualTo("/query");
    });
  }

  @Test
  void aStatementThatWritesIsNeverOffered() throws Exception {
    for (String sql :
        List.of(
            "DELETE FROM sales.customer",
            "SELECT 1; DROP TABLE sales.customer",
            "UPDATE sales.customer SET email = null")) {
      Result result = run("write_sql", json.createObjectNode().put("sql", sql).toString());
      assertThat(result.cards()).as(sql).isEmpty();
      assertThat(result.content()).as(sql).startsWith("error:");
    }
  }

  @Test
  void aTableNamedOnTheCardMustBeOneThePersonCanSee() throws Exception {
    when(catalog.asset(HIDDEN)).thenReturn(Optional.of(detail(HIDDEN)));

    Result result =
        run(
            "write_sql",
            "{\"sql\":\"SELECT * FROM vault.keys\",\"table\":\"" + HIDDEN + "\"}");

    assertThat(result.cards()).singleElement().extracting(Card::assetFqn).isNull();
  }

  @Test
  void aDraftPolicyIsACardForTheBuilder() throws Exception {
    Result result = run("draft_policy", "{\"intent\":\"mask email for everyone\"}");

    assertThat(drafted).isEqualTo("mask email for everyone");
    assertThat(result.content()).contains("NOT saved", "NOT active");
    assertThat(result.cards()).singleElement().satisfies(card -> {
      assertThat(card.kind()).isEqualTo("policy");
      assertThat(card.route()).isEqualTo("/policies/new");
    });
  }

  @Test
  void aDraftThatFailsSaysSo() throws Exception {
    toolbox =
        new AssistToolbox(
            new AssistToolbox.Deps(
                json, catalog, search, eligibility, decisions, sources, audit, dashboard),
            analyst,
            security,
            IP,
            null,
            (intent, sourceId) -> {
              throw new ServiceUnavailableException("gateway down");
            });

    Result result = run("draft_policy", "{\"intent\":\"x\"}");

    assertThat(result.content()).startsWith("error:");
    assertThat(result.cards()).isEmpty();
  }

  @Test
  void navigationOnlyToPagesOfTheApp() throws Exception {
    assertThat(run("navigate", "{\"page\":\"query_log\"}").cards())
        .extracting(Card::route)
        .containsExactly("/audit");
    Result outside = run("navigate", "{\"page\":\"https://evil.example.test\"}");
    assertThat(outside.cards()).isEmpty();
    assertThat(outside.content()).startsWith("error: no such page");
  }

  // -------------------------------------------------------------- insights

  @Test
  void theQueryLogIsReadAsThePersonAndLiteralsGo() throws Exception {
    AuditResource.QueryRow row =
        new AuditResource.QueryRow(
            1L,
            Instant.parse("2026-09-01T03:00:00Z"),
            "analyst_a",
            null,
            UUID.randomUUID(),
            "demo-pg",
            "REJECTED",
            null,
            "value 'a@example.test' is not allowed",
            "SELECT * FROM sales.customer WHERE email = 'a@example.test' AND id = 42",
            "rewritten text that is not sent",
            false,
            null,
            12,
            List.of(READABLE),
            2,
            true);
    when(audit.queries(
            eq("REJECTED"), any(), any(), any(), any(), eq(7), any(), any(), any(), eq(20),
            eq(security)))
        .thenReturn(
            new AuditResource.QueryPage(
                Instant.parse("2026-08-25T00:00:00Z"), null, "OWN", List.of(row), null, null));

    Result result = run("query_log", "{\"outcome\":\"rejected\"}");

    assertThat(result.content())
        .contains("email = ?", "id = ?", "only their own queries", "\"otherTables\":2")
        .doesNotContain("example.test")
        .doesNotContain("42")
        .doesNotContain("rewritten");
  }

  @Test
  void someoneWhoCannotReadTheLogIsToldSo() throws Exception {
    when(audit.queries(any(), any(), any(), any(), any(), anyInt(), any(), any(), any(), anyInt(), any()))
        .thenThrow(new ForbiddenException("no"));

    assertThat(run("query_log", "{}").content())
        .isEqualTo("error: this person cannot read the query log");
  }

  @Test
  void theDashboardIsRefusedAsItsPageRefusesIt() throws Exception {
    when(dashboard.dashboard(anyInt(), anyString(), any())).thenThrow(new ForbiddenException("no"));

    Result result = run("dashboard", "{\"days\":500}");

    assertThat(result.content()).startsWith("error: the dashboard is only for");
    verify(dashboard).dashboard(eq(90), eq("PII"), eq(security));
  }
}
