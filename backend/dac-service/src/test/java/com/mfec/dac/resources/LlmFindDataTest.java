package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.mfec.dac.llm.LlmClient;
import com.mfec.dac.llm.LlmFeatureStore;
import com.mfec.dac.llm.LlmFeatureStore.Feature;
import com.mfec.dac.llm.LlmSettingStore;
import com.mfec.dac.llm.LlmSettings.EffectiveSetting;
import com.mfec.dac.llm.LlmSettings.Gateway;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.TableScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Finding tables from a sentence (M16).
 *
 * <p>The promise checked here is the order of things: the permission check
 * comes before the model is told about a table, so a table the person may
 * neither read nor request is never named to the model or to the person, and
 * a column their decision hides is not listed either. The model only orders
 * and explains what was already theirs to see.
 */
@DisplayName("LlmAssistResource — find data from a sentence")
class LlmFindDataTest {

  private static final String READABLE = "demo-pg.salesdb.sales.customer";
  private static final String REQUESTABLE = "demo-pg.hrdb.hr.salary";
  private static final String HIDDEN = "demo-pg.secretdb.vault.keys";
  private static final String IP = "192.0.2.77";

  private final ObjectMapper json = new ObjectMapper();
  private final LlmSettingStore store = mock(LlmSettingStore.class);
  private final LlmClient client = mock(LlmClient.class);
  private final CatalogQuery catalog = mock(CatalogQuery.class);
  private final SearchQuery search = mock(SearchQuery.class);
  private final AccessEligibility eligibility = mock(AccessEligibility.class);
  private final DecisionService decisions = mock(DecisionService.class);
  private final DataSourceStore sources = mock(DataSourceStore.class);
  private final LlmFeatureStore features = mock(LlmFeatureStore.class);
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final SecurityContext security = mock(SecurityContext.class);

  private final LlmAssistResource resource =
      new LlmAssistResource(
          store,
          client,
          catalog,
          features,
          new AssistToolbox.Deps(
              json,
              catalog,
              search,
              eligibility,
              decisions,
              sources,
              mock(AuditResource.class),
              mock(DashboardResource.class)));

  private final AuthenticatedUser analyst =
      new AuthenticatedUser(
          UUID.randomUUID(),
          "analyst_a",
          "analyst_a@example.test",
          "Analyst A",
          "local",
          Set.of("REQUESTER"),
          List.of());

  private final UUID sourceId = UUID.randomUUID();

  /** What the model was sent, system then user, one pair per question. */
  private final List<String[]> asked = new ArrayList<>();

  private String keywordsAnswer = "{\"keywords\":[\"customer\",\"email\"]}";
  private String picksAnswer =
      "{\"tables\":["
          + "{\"fqn\":\"" + HIDDEN + "\",\"why\":\"Keys.\",\"columns\":[\"k\"]},"
          + "{\"fqn\":\"" + READABLE.toUpperCase() + "\",\"why\":\"One row per customer.\","
          + "\"columns\":[\"EMAIL\",\"secret_col\",\"made_up\"]},"
          + "{\"fqn\":\"" + REQUESTABLE + "\",\"why\":\"Pay per person.\",\"columns\":[\"id\"]},"
          + "{\"fqn\":\"demo-pg.nowhere.x.y\",\"why\":\"Invented.\",\"columns\":[]}]}";

  @BeforeEach
  void wire() throws Exception {
    when(security.getUserPrincipal()).thenReturn(analyst);
    when(request.getRemoteAddr()).thenReturn(IP);
    when(store.effectiveFor(analyst.id())).thenReturn(switchedOn(true));
    when(store.gatewayFor(analyst.id()))
        .thenReturn(new Gateway("https://llm.example.test", "not-a-real-token", false));
    when(features.allowedFor(analyst)).thenReturn(EnumSet.allOf(Feature.class));
    when(client.complete(any(), anyString(), anyString(), anyString()))
        .thenAnswer(
            call -> {
              String system = call.getArgument(2);
              String user = call.getArgument(3);
              asked.add(new String[] {system, user});
              String text = system.contains("search words") ? keywordsAnswer : picksAnswer;
              return new LlmClient.Completion(text, "gpt-test", 10, 10);
            });

    when(search.search(anyString(), anyInt()))
        .thenReturn(
            new SearchQuery.Results(
                "x",
                30,
                List.of(
                    table(HIDDEN, "Master keys"),
                    table(READABLE, "One row per customer"),
                    column(REQUESTABLE))));
    when(eligibility.check(eq("analyst_a"), eq(READABLE), eq(IP), any()))
        .thenReturn(verdict(READABLE, true, false));
    when(eligibility.check(eq("analyst_a"), eq(REQUESTABLE), eq(IP), any()))
        .thenReturn(verdict(REQUESTABLE, false, true));
    when(eligibility.check(eq("analyst_a"), eq(HIDDEN), eq(IP), any()))
        .thenReturn(verdict(HIDDEN, false, false));
    when(catalog.asset(READABLE)).thenReturn(Optional.of(detail(READABLE, "demo-pg")));
    when(catalog.asset(REQUESTABLE)).thenReturn(Optional.of(detail(REQUESTABLE, null)));
    when(catalog.asset(HIDDEN)).thenReturn(Optional.of(detail(HIDDEN, "demo-pg")));
    when(decisions.decide(any()))
        .thenReturn(
            new PolicyDecision().withAllowed(true).withHiddenColumns(List.of("SECRET_COL")));
    when(sources.list())
        .thenReturn(
            List.of(
                new DataSourceStore.Source(
                    sourceId, "demo-pg", DataSourceStore.Engine.POSTGRES, null,
                    "db.example.test", 5432, "salesdb", "env:SRC", null, null, "sec", "{table}",
                    TableScope.EVERYTHING, true, Instant.now(), Instant.now(), 0)));
  }

  private static EffectiveSetting switchedOn(boolean on) {
    return new EffectiveSetting(
        true, on, "gpt-test", "gpt-test", null, false, false, true, false, true, "gpt-test", null);
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

  private static CatalogQuery.AssetDetail detail(String fqn, String querySource) {
    CatalogQuery.AssetSummary summary =
        new CatalogQuery.AssetSummary(
            UUID.randomUUID(), fqn, "t", "t", "TABLE", "demo-pg.salesdb.sales", "A table", null,
            null, "demo-pg", 3, 1, List.of(), List.of(), 0, "discovered", querySource);
    return new CatalogQuery.AssetDetail(
        summary,
        null,
        Map.of(),
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
        null,
        tags.stream()
            .map(
                tag ->
                    new CatalogQuery.FacetRow(
                        "TAG", tag, null, 0, true, null, "openmetadata", "Confirmed", "Manual"))
            .toList());
  }

  private LlmAssistResource.FoundData find(String want) {
    return resource.findData(
        new LlmAssistResource.FindAsk(want, "English", null), security, request);
  }

  @Test
  void onlyTablesThePersonMayReadOrRequestAreFound() {
    LlmAssistResource.FoundData found = find("where are customer emails kept?");

    assertThat(found.tables())
        .extracting(DataFinder.Found::fqn)
        .containsExactly(READABLE, REQUESTABLE);
    assertThat(found.tables())
        .extracting(DataFinder.Found::access)
        .containsExactly("READABLE", "REQUESTABLE");
    assertThat(found.keywords()).containsExactly("customer", "email");
    assertThat(found.model()).isEqualTo("gpt-test");
    // The hidden table is counted, never named.
    assertThat(found.outOfReach()).isEqualTo(1);
  }

  @Test
  void aTableOutsideBothIsNeverNamedToTheModel() {
    find("where are customer emails kept?");

    assertThat(asked).hasSize(2);
    String brief = asked.get(1)[1];
    assertThat(brief).contains(READABLE, REQUESTABLE).doesNotContain(HIDDEN, "Master keys");
  }

  @Test
  void theFirstQuestionIsTheSentenceAlone() {
    find("where are customer emails kept?");

    assertThat(asked.get(0)[1]).isEqualTo("Request: where are customer emails kept?");
    // The search is what applies permissions, so it runs on the model's words, not on a table list.
    verify(search).search(eq("customer"), anyInt());
    verify(search).search(eq("email"), anyInt());
  }

  @Test
  void aColumnTheDecisionHidesIsNeitherShownNorReturned() {
    LlmAssistResource.FoundData found = find("customer emails");

    String brief = asked.get(1)[1];
    // The readable table loses its hidden column; the requestable one is not decided as readable.
    assertThat(brief.split(REQUESTABLE)[0]).doesNotContain("secret_col");
    assertThat(found.tables().get(0).columns()).containsExactly("email");
    verify(decisions).decide(any());
  }

  @Test
  void whatTheModelInventsIsDropped() {
    LlmAssistResource.FoundData found = find("customer emails");

    assertThat(found.tables()).extracting(DataFinder.Found::fqn).doesNotContain("demo-pg.nowhere.x.y");
    assertThat(found.tables().get(0).columns()).doesNotContain("made_up");
    assertThat(found.tables().get(0).why()).isEqualTo("One row per customer.");
  }

  @Test
  void aFoundTableCarriesTheSourceAQueryReachesItThrough() {
    LlmAssistResource.FoundData found = find("customer emails");

    DataFinder.Found readable = found.tables().get(0);
    assertThat(readable.sourceId()).isEqualTo(sourceId.toString());
    assertThat(readable.engine()).isEqualTo("POSTGRES");
  }

  @Test
  void keywordsFallBackToTheSentencesOwnWords() {
    keywordsAnswer = "I think you want customers.";

    LlmAssistResource.FoundData found = find("customer emails");

    assertThat(found.keywords()).containsExactly("customer", "email");
  }

  @Test
  void nothingFoundAsksTheModelOnlyOnce() {
    when(search.search(anyString(), anyInt()))
        .thenReturn(new SearchQuery.Results("x", 30, List.of(table(HIDDEN, "Master keys"))));

    LlmAssistResource.FoundData found = find("keys");

    assertThat(found.tables()).isEmpty();
    assertThat(asked).hasSize(1);
    // "Nothing found" would read as no such data; the page says one matched out of reach.
    assertThat(found.outOfReach()).isEqualTo(1);
  }

  @Test
  void anAnswerThatIsNotAListIsReportedNotGuessed() {
    picksAnswer = "Customer is probably the one.";

    assertThatThrownBy(() -> find("customer emails"))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void aSentenceIsRequiredAndKeptShort() {
    assertThatThrownBy(() -> find(" ")).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> find("x".repeat(501))).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> resource.findData(null, security, request))
        .isInstanceOf(BadRequestException.class);
    verify(search, never()).search(anyString(), anyInt());
  }

  @Test
  void itIsOfferedOnlyWithCatalogueSearch() {
    when(features.allowedFor(analyst)).thenReturn(EnumSet.of(Feature.CHAT, Feature.WRITE_SQL));

    assertThatThrownBy(() -> find("customer emails")).isInstanceOf(ForbiddenException.class);
    verify(search, never()).search(anyString(), anyInt());
  }

  @Test
  void itIsOffWhenTheAssistantIsOff() {
    when(store.effectiveFor(analyst.id())).thenReturn(switchedOn(false));

    assertThatThrownBy(() -> find("customer emails")).isInstanceOf(ForbiddenException.class);
  }
}
