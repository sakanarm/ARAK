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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.catalog.CatalogQuery;
import com.mfec.dac.llm.AssistPrompts;
import com.mfec.dac.llm.LlmClient;
import com.mfec.dac.llm.LlmSettingStore;
import com.mfec.dac.llm.LlmSettings.EffectiveSetting;
import com.mfec.dac.llm.LlmSettings.Gateway;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Fix with AI and Explain query (M26), with the gateway replaced by a mock.
 *
 * <p>The prompts are captured and read, because the promise here is about what
 * leaves the platform: the statement the reader wrote, the catalogue, and an
 * error with anything that could be a row value taken out. And the answers are
 * checked on the way back, because a suggestion lands beside a Run button.
 */
@DisplayName("LlmAssistResource — fix and explain")
class LlmAssistResourceTest {

  private static final UUID SOURCE = UUID.randomUUID();
  private static final String CUSTOMER = "demo-pg.salesdb.sales.customer";
  private static final String BROKEN =
      "SELECT id, emial FROM sales.customer WHERE branch_code = 'BKK-01'";

  private final LlmSettingStore store = mock(LlmSettingStore.class);
  private final LlmClient client = mock(LlmClient.class);
  private final CatalogQuery catalog = mock(CatalogQuery.class);
  private final LlmAssistResource resource = new LlmAssistResource(store, client, catalog);

  private final ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
  private final ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);

  private final AuthenticatedUser analyst =
      new AuthenticatedUser(
          UUID.randomUUID(),
          "analyst_a",
          "analyst_a@example.test",
          "analyst_a",
          "local",
          Set.of("REQUESTER"),
          List.of());

  @BeforeEach
  void wire() throws Exception {
    when(store.effectiveFor(analyst.id())).thenReturn(switchedOn());
    when(store.gatewayFor(analyst.id()))
        .thenReturn(new Gateway("https://llm.example.test", "not-a-real-token", false));

    CatalogQuery.AssetSummary summary =
        new CatalogQuery.AssetSummary(
            UUID.randomUUID(), CUSTOMER, "customer", "customer", "TABLE", "demo-pg.salesdb.sales",
            "One row per customer", null, null, "demo-pg", 3, 1, List.of(), List.of());
    when(catalog.assets(any(), eq("TABLE"), any(), any(), eq(SOURCE), anyInt(), anyInt()))
        .thenReturn(new CatalogQuery.AssetPage(List.of(summary), 1, 300, 0));
    when(catalog.asset(CUSTOMER))
        .thenReturn(
            Optional.of(
                new CatalogQuery.AssetDetail(
                    summary,
                    null,
                    java.util.Map.of(),
                    List.of(
                        column("id", "bigint", List.of()),
                        column("email", "varchar", List.of("PII.Sensitive")),
                        column("branch_code", "varchar", List.of())),
                    List.of(),
                    List.of())));
  }

  private static CatalogQuery.ColumnDetail column(String name, String type, List<String> tags) {
    return new CatalogQuery.ColumnDetail(
        UUID.randomUUID(),
        CUSTOMER + "." + name,
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

  private static EffectiveSetting switchedOn() {
    return new EffectiveSetting(
        true, true, "gpt-test", "gpt-test", null, false, false, true, false, true, "gpt-test",
        null);
  }

  private void answers(String text) throws Exception {
    when(client.complete(any(), anyString(), system.capture(), user.capture()))
        .thenReturn(new LlmClient.Completion(text, "gpt-test", 10, 10));
  }

  private SecurityContext as(Principal who) {
    return new SecurityContext() {
      @Override
      public Principal getUserPrincipal() {
        return who;
      }

      @Override
      public boolean isUserInRole(String role) {
        return false;
      }

      @Override
      public boolean isSecure() {
        return true;
      }

      @Override
      public String getAuthenticationScheme() {
        return "Bearer";
      }
    };
  }

  private LlmAssistResource.FixAsk fixing(String sql, String error) {
    return new LlmAssistResource.FixAsk(sql, error, SOURCE, "PostgreSQL", null);
  }

  @Nested
  @DisplayName("fix")
  class Fix {

    @Test
    @DisplayName("sends the statement, the catalogue and a redacted error — never a value")
    void whatLeaves() throws Exception {
      answers("SELECT id, email FROM sales.customer WHERE branch_code = 'BKK-01'");

      resource.fix(
          fixing(
              BROKEN,
              "The source rejected the enforced statement: ERROR: invalid input syntax for type"
                  + " integer: \"4111-1111-1111-1111\"\n  Detail: Failing row contains (7,"
                  + " someone@example.test)\n  Position: 12"),
          as(analyst));

      String prompt = user.getValue();
      assertThat(prompt)
          .contains(BROKEN)
          .contains(CUSTOMER)
          .contains("email varchar  [PII.Sensitive]")
          .contains("invalid input syntax for type integer: \"…\"")
          .doesNotContain("4111")
          .doesNotContain("someone@")
          .doesNotContain("Failing row");
      assertThat(system.getValue())
          .contains("repair")
          .contains("Never try to get around that")
          .contains("UNANSWERABLE");
    }

    @Test
    @DisplayName("keeps a quoted name the reader wrote, because that is the useful part")
    void keepsTheReadersNames() throws Exception {
      answers("SELECT id, email FROM sales.customer WHERE branch_code = 'BKK-01'");

      resource.fix(
          fixing(
              BROKEN,
              "The source rejected the enforced statement: ERROR: column \"emial\" does not exist"),
          as(analyst));

      assertThat(user.getValue()).contains("column \"emial\" does not exist");
    }

    @Test
    @DisplayName("hands back the corrected statement as a suggestion, fence and all stripped")
    void suggests() throws Exception {
      answers(
          "Here you go:\n```sql\nSELECT id, email FROM sales.customer WHERE branch_code ="
              + " 'BKK-01'\n```");

      LlmAssistResource.SqlDraft draft =
          resource.fix(fixing(BROKEN, "column \"emial\" does not exist"), as(analyst));

      assertThat(draft.problem()).isNull();
      assertThat(draft.sql())
          .isEqualTo("SELECT id, email FROM sales.customer WHERE branch_code = 'BKK-01'");
      assertThat(draft.tables()).containsExactly(CUSTOMER);
      assertThat(draft.model()).isEqualTo("gpt-test");
    }

    @Test
    @DisplayName("says so when the answer is the statement it was sent")
    void nothingToChange() throws Exception {
      answers(BROKEN.toLowerCase() + ";");

      LlmAssistResource.SqlDraft draft =
          resource.fix(fixing(BROKEN, "column \"emial\" does not exist"), as(analyst));

      assertThat(draft.sql()).isEmpty();
      assertThat(draft.problem()).contains("did not find anything to change");
    }

    @Test
    @DisplayName("discards an answer that is not one read-only query")
    void discardsAWrite() throws Exception {
      answers("DELETE FROM sales.customer WHERE branch_code = 'BKK-01'");

      LlmAssistResource.SqlDraft draft =
          resource.fix(fixing(BROKEN, "column \"emial\" does not exist"), as(analyst));

      assertThat(draft.sql()).isEmpty();
      assertThat(draft.problem()).contains("not a read-only query");
    }

    @Test
    @DisplayName("reports UNANSWERABLE as a problem, not as SQL")
    void unanswerable() throws Exception {
      answers("UNANSWERABLE");

      LlmAssistResource.SqlDraft draft =
          resource.fix(fixing(BROKEN, "column \"emial\" does not exist"), as(analyst));

      assertThat(draft.sql()).isEmpty();
      assertThat(draft.problem()).contains("could not tell how to fix");
    }

    @Test
    @DisplayName("is refused, without reaching a gateway, when the assistant is off")
    void switchedOff() throws Exception {
      when(store.effectiveFor(analyst.id()))
          .thenReturn(
              new EffectiveSetting(
                  false, false, null, null, null, false, false, true, false, true, null,
                  "Switched off"));

      assertThatThrownBy(() -> resource.fix(fixing(BROKEN, "x"), as(analyst)))
          .isInstanceOf(ForbiddenException.class);
      verify(client, never()).complete(any(), any(), any(), any());
    }

    @Test
    @DisplayName("needs a statement, a source, and a statement of a sensible length")
    void validates() {
      assertThatThrownBy(() -> resource.fix(fixing("  ", "x"), as(analyst)))
          .isInstanceOf(BadRequestException.class);
      assertThatThrownBy(
              () ->
                  resource.fix(
                      new LlmAssistResource.FixAsk(BROKEN, "x", null, "PostgreSQL", null),
                      as(analyst)))
          .isInstanceOf(BadRequestException.class);
      String huge = "SELECT " + "a,".repeat(LlmAssistResource.MAX_STATEMENT) + " 1";
      assertThatThrownBy(() -> resource.fix(fixing(huge, "x"), as(analyst)))
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining("too long");
      verifyNoInteractions(client);
    }

    @Test
    @DisplayName("a gateway failure is a 503, not a 500")
    void gatewayDown() throws Exception {
      when(client.complete(any(), anyString(), anyString(), anyString()))
          .thenThrow(new LlmClient.LlmException("The LLM gateway answered 502"));

      assertThatThrownBy(() -> resource.fix(fixing(BROKEN, "x"), as(analyst)))
          .isInstanceOf(ServiceUnavailableException.class);
    }
  }

  @Nested
  @DisplayName("explain")
  class Explain {

    @Test
    @DisplayName("sends the statement and the catalogue, and returns the words")
    void explains() throws Exception {
      answers("Lists the id and email of every customer in branch BKK-01.");

      LlmAssistResource.Explanation out =
          resource.explain(
              new LlmAssistResource.ExplainAsk(BROKEN, SOURCE, "PostgreSQL", null), as(analyst));

      assertThat(out.text()).startsWith("Lists the id and email");
      assertThat(out.tables()).containsExactly(CUSTOMER);
      assertThat(user.getValue()).contains(BROKEN).contains(CUSTOMER);
      assertThat(system.getValue())
          .contains("You have not seen any data")
          .contains("do not rewrite it");
    }

    @Test
    @DisplayName("works without a source, from the statement alone")
    void withoutASource() throws Exception {
      answers("Counts the rows.");

      LlmAssistResource.Explanation out =
          resource.explain(
              new LlmAssistResource.ExplainAsk("SELECT count(*) FROM sales.customer", null, null, null),
              as(analyst));

      assertThat(out.text()).isEqualTo("Counts the rows.");
      assertThat(out.tables()).isEmpty();
      verifyNoInteractions(catalog);
    }

    @Test
    @DisplayName("caps a long answer")
    void caps() throws Exception {
      answers("word ".repeat(5000));

      LlmAssistResource.Explanation out =
          resource.explain(
              new LlmAssistResource.ExplainAsk(BROKEN, SOURCE, "PostgreSQL", null), as(analyst));

      assertThat(out.text().length()).isLessThanOrEqualTo(AssistPrompts.MAX_EXPLANATION);
    }

    @Test
    @DisplayName("an empty answer is a 503")
    void empty() throws Exception {
      answers("   ");

      assertThatThrownBy(
              () ->
                  resource.explain(
                      new LlmAssistResource.ExplainAsk(BROKEN, SOURCE, "PostgreSQL", null),
                      as(analyst)))
          .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    @DisplayName("is refused when the assistant is off")
    void switchedOff() throws Exception {
      when(store.effectiveFor(analyst.id()))
          .thenReturn(
              new EffectiveSetting(
                  false, false, null, null, null, false, false, true, false, true, null,
                  "Switched off"));

      assertThatThrownBy(
              () ->
                  resource.explain(
                      new LlmAssistResource.ExplainAsk(BROKEN, SOURCE, "PostgreSQL", null),
                      as(analyst)))
          .isInstanceOf(ForbiddenException.class);
      verify(client, never()).complete(any(), any(), any(), any());
    }
  }

  @Nested
  @DisplayName("sql, after the refactor")
  class Sql {

    @Test
    @DisplayName("still drafts a statement from a question")
    void drafts() throws Exception {
      answers("SELECT id FROM sales.customer");

      LlmAssistResource.SqlDraft draft =
          resource.sql(
              new LlmAssistResource.SqlAsk("list customers", SOURCE, "PostgreSQL", null),
              as(analyst));

      assertThat(draft.sql()).isEqualTo("SELECT id FROM sales.customer");
      assertThat(draft.problem()).isNull();
    }

    @Test
    @DisplayName("still reports a question it cannot answer")
    void unanswerable() throws Exception {
      answers("UNANSWERABLE");

      LlmAssistResource.SqlDraft draft =
          resource.sql(
              new LlmAssistResource.SqlAsk("the weather", SOURCE, "PostgreSQL", null), as(analyst));

      assertThat(draft.problem()).contains("could not answer that");
    }
  }
}
