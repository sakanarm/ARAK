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
            "One row per customer", null, null, "demo-pg", 3, 1, List.of(), List.of(), 0,
            "discovered", "demo-pg");
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

  @Nested
  @DisplayName("a policy draft")
  class PolicyDraft {

    @Test
    @DisplayName("is asked for against every schema the document refers to, the selector's included")
    void sendsTheSelectorSchema() throws Exception {
      answers("{\"name\":\"p\",\"lifecycleState\":\"DRAFT\"}");

      LlmAssistResource.PolicyDraft draft =
          resource.policy(
              new LlmAssistResource.PolicyAsk("mask PII below L2", null, null, null), as(analyst));

      assertThat(draft.document()).contains("\"lifecycleState\":\"DRAFT\"");
      // The selector and the operators live in type/facet.json. Left out, the
      // model saw only a $ref and invented a selector the builder cannot show.
      assertThat(system.getValue())
          .contains("// entity/policy/policy.json")
          .contains("// type/facet.json")
          .contains("assetSelector")
          .contains("facetOperator");
    }
  }

  @Nested
  @DisplayName("a change to a policy")
  class PolicyEdit {

    private static final String STORED =
        "{\"id\":\"3f1c2e4a-0000-4000-8000-000000000001\",\"name\":\"mask-pii\","
            + "\"effect\":\"ALLOW\",\"version\":3,\"lifecycleState\":\"ACTIVE\","
            + "\"updatedAt\":\"2026-09-20T10:00:00Z\",\"updatedBy\":\"author@example.com\"}";

    @Test
    @DisplayName("sends the policy as it is, without what the store owns, and asks for it changed")
    void sendsTheCurrentPolicy() throws Exception {
      answers("{\"name\":\"mask-pii\",\"effect\":\"DENY\"}");

      LlmAssistResource.PolicyDraft draft =
          resource.policy(
              new LlmAssistResource.PolicyAsk("make it a deny", null, null, STORED), as(analyst));

      assertThat(draft.document()).isEqualTo("{\"name\":\"mask-pii\",\"effect\":\"DENY\"}");
      assertThat(system.getValue())
          .contains("You change an existing access policy")
          .contains("Change only what the sentence asks for")
          .contains("// entity/policy/policy.json");
      assertThat(user.getValue())
          .startsWith("Change this policy: make it a deny")
          .contains("{\"name\":\"mask-pii\",\"effect\":\"ALLOW\"}")
          // Which policy, which version, whether it is in force and who
          // touched it last are the store's to decide on save, not the model's.
          .doesNotContain("3f1c2e4a")
          .doesNotContain("ACTIVE")
          .doesNotContain("lifecycleState")
          .doesNotContain("author@example.com")
          .doesNotContain("\"version\"");
    }

    @Test
    @DisplayName("is refused before anything is asked when the policy is not a JSON object")
    void notAnObject() throws Exception {
      for (String bad : List.of("not json", "[1,2]", "\"a string\"", "  ")) {
        assertThatThrownBy(
                () ->
                    resource.policy(
                        new LlmAssistResource.PolicyAsk("make it a deny", null, null, bad),
                        as(analyst)))
            .as(bad)
            .isInstanceOf(BadRequestException.class);
      }
      verifyNoInteractions(client);
    }

    @Test
    @DisplayName("is refused before anything is asked when the policy is too long to send")
    void tooLong() throws Exception {
      String huge =
          "{\"description\":\"" + "x".repeat(AssistPrompts.MAX_POLICY) + "\"}";

      assertThatThrownBy(
              () ->
                  resource.policy(
                      new LlmAssistResource.PolicyAsk("make it a deny", null, null, huge),
                      as(analyst)))
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining(String.valueOf(AssistPrompts.MAX_POLICY));
      verifyNoInteractions(client);
    }

    @Test
    @DisplayName("still says so when the answer is not a document")
    void notADocument() throws Exception {
      answers("Sorry, I can't do that.");

      assertThatThrownBy(
              () ->
                  resource.policy(
                      new LlmAssistResource.PolicyAsk("make it a deny", null, null, STORED),
                      as(analyst)))
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessageContaining("changed policy");
    }
  }

  @Nested
  @DisplayName("describe columns")
  class DescribeColumns {

    private final AuthenticatedUser steward =
        new AuthenticatedUser(
            UUID.randomUUID(),
            "steward_a",
            "steward_a@example.test",
            "steward_a",
            "local",
            Set.of("POLICY_AUTHOR"),
            List.of());

    @BeforeEach
    void wireSteward() throws Exception {
      when(store.effectiveFor(steward.id())).thenReturn(switchedOn());
      when(store.gatewayFor(steward.id()))
          .thenReturn(new Gateway("https://llm.example.test", "not-a-real-token", false));
    }

    private LlmAssistResource.DescribeAsk asking(String... columns) {
      return new LlmAssistResource.DescribeAsk(CUSTOMER, List.of(columns), "English", null);
    }

    @Test
    @DisplayName("sends the table's metadata and the columns asked about -- no rows")
    void whatLeaves() throws Exception {
      answers("{\"columns\":[{\"name\":\"email\",\"description\":\"The customer's email\"}]}");

      resource.describeColumns(asking("email"), as(steward));

      assertThat(user.getValue())
          .contains("Table: " + CUSTOMER)
          .contains("About the table: One row per customer")
          .contains("- email (varchar), tagged PII.Sensitive")
          .contains("Its other columns, for context: id, branch_code")
          .doesNotContain("- id");
      assertThat(system.getValue())
          .contains("in English")
          .contains("You have not seen any data")
          .contains("leave its description empty");
    }

    @Test
    @DisplayName("keeps drafts for the columns asked about, under the catalogue's names")
    void drafts() throws Exception {
      answers(
          "Here you go:\n```json\n{\"columns\":["
              + "{\"name\":\"EMAIL\",\"description\":\"  The customer's   email address \"},"
              + "{\"name\":\"id\",\"description\":\"\"},"
              + "{\"name\":\"password\",\"description\":\"Made up\"},"
              + "{\"name\":\"branch_code\",\"description\":\"The branch the customer banks with\"}"
              + "]}\n```");

      LlmAssistResource.ColumnDrafts drafts =
          resource.describeColumns(asking("branch_code", "email", "id"), as(steward));

      assertThat(drafts.drafts())
          .containsExactly(
              new AssistPrompts.ColumnDraft("branch_code", "The branch the customer banks with"),
              new AssistPrompts.ColumnDraft("email", "The customer's email address"));
      assertThat(drafts.model()).isEqualTo("gpt-test");
    }

    @Test
    @DisplayName("an answer that is not the JSON asked for gives no drafts, not a guess")
    void notJson() throws Exception {
      answers("I think email holds an email.");

      assertThat(resource.describeColumns(asking("email"), as(steward)).drafts()).isEmpty();
    }

    @Test
    @DisplayName("only whoever governs the table may ask, and nothing is sent otherwise")
    void onlyAStewardMayAsk() throws Exception {
      assertThatThrownBy(() -> resource.describeColumns(asking("email"), as(analyst)))
          .isInstanceOf(ForbiddenException.class);
      verifyNoInteractions(client);
    }

    @Test
    @DisplayName("is refused when the job is not offered to the caller")
    void featureGate() throws Exception {
      com.mfec.dac.llm.LlmFeatureStore features = mock(com.mfec.dac.llm.LlmFeatureStore.class);
      when(features.allowedFor(steward))
          .thenReturn(java.util.EnumSet.of(com.mfec.dac.llm.LlmFeatureStore.Feature.CHAT));
      LlmAssistResource gated = new LlmAssistResource(store, client, catalog, features, null);

      assertThatThrownBy(() -> gated.describeColumns(asking("email"), as(steward)))
          .isInstanceOf(ForbiddenException.class)
          .hasMessageContaining("Describe columns");
      verifyNoInteractions(client);
    }

    @Test
    @DisplayName("refuses an empty ask, too many columns, and names that are not columns")
    void validates() {
      assertThatThrownBy(() -> resource.describeColumns(asking(), as(steward)))
          .isInstanceOf(BadRequestException.class);
      String[] many = new String[LlmAssistResource.MAX_DESCRIBE + 1];
      java.util.Arrays.fill(many, "email");
      assertThatThrownBy(() -> resource.describeColumns(asking(many), as(steward)))
          .isInstanceOf(BadRequestException.class);
      assertThatThrownBy(() -> resource.describeColumns(asking("nope"), as(steward)))
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining("None of those");
      verifyNoInteractions(client);
    }
  }
}
