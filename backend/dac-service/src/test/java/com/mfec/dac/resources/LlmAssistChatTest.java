package com.mfec.dac.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mfec.dac.access.AccessEligibility;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.catalog.CatalogQuery;
import com.mfec.dac.catalog.SearchQuery;
import com.mfec.dac.llm.AgentPrompts;
import com.mfec.dac.llm.LlmClient;
import com.mfec.dac.llm.LlmFeatureStore;
import com.mfec.dac.llm.LlmFeatureStore.Feature;
import com.mfec.dac.llm.LlmSettingStore;
import com.mfec.dac.llm.LlmSettings.EffectiveSetting;
import com.mfec.dac.llm.LlmSettings.Gateway;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.source.DataSourceStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.SecurityContext;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The chat endpoint and the per-role narrowing of every assistant job (M28). */
@DisplayName("LlmAssistResource — chat and feature access")
class LlmAssistChatTest {

  private final ObjectMapper json = new ObjectMapper();
  private final LlmSettingStore store = mock(LlmSettingStore.class);
  private final LlmClient client = mock(LlmClient.class);
  private final CatalogQuery catalog = mock(CatalogQuery.class);
  private final LlmFeatureStore features = mock(LlmFeatureStore.class);
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final SecurityContext security = mock(SecurityContext.class);

  private final AssistToolbox.Deps deps =
      new AssistToolbox.Deps(
          json,
          catalog,
          mock(SearchQuery.class),
          mock(AccessEligibility.class),
          mock(DecisionService.class),
          mock(DataSourceStore.class),
          mock(AuditResource.class),
          mock(DashboardResource.class));

  private final LlmAssistResource resource =
      new LlmAssistResource(store, client, catalog, features, deps);

  private final AuthenticatedUser analyst =
      new AuthenticatedUser(
          UUID.randomUUID(),
          "analyst_a",
          "analyst_a@example.test",
          "Analyst A",
          "local",
          Set.of("REQUESTER"),
          List.of());

  /** The tool names and messages the model was sent, one entry per step. */
  private final List<List<String>> toolsSent = new ArrayList<>();
  private final List<ArrayNode> messagesSent = new ArrayList<>();

  @BeforeEach
  void wire() throws Exception {
    when(security.getUserPrincipal()).thenReturn(analyst);
    when(request.getRemoteAddr()).thenReturn("192.0.2.77");
    when(store.effectiveFor(analyst.id())).thenReturn(switchedOn(true));
    when(store.gatewayFor(analyst.id()))
        .thenReturn(new Gateway("https://llm.example.test", "not-a-real-token", false));
    when(features.allowedFor(analyst)).thenReturn(EnumSet.allOf(Feature.class));
    when(client.converse(any(), anyString(), any(), any()))
        .thenAnswer(
            call -> {
              ArrayNode messages = call.getArgument(2);
              ArrayNode tools = call.getArgument(3);
              messagesSent.add(messages.deepCopy());
              List<String> names = new ArrayList<>();
              for (JsonNode tool : tools) {
                names.add(tool.path("function").path("name").asText());
              }
              toolsSent.add(names);
              ObjectNode message =
                  json.createObjectNode().put("role", "assistant").put("content", "Hello!");
              return new LlmClient.Turn("Hello!", List.of(), "gpt-test", 5, 5, message);
            });
  }

  private static EffectiveSetting switchedOn(boolean on) {
    return new EffectiveSetting(
        true, on, "gpt-test", "gpt-test", null, false, false, true, false, true, "gpt-test", null);
  }

  private LlmAssistResource.ChatAsk ask(String message) {
    return new LlmAssistResource.ChatAsk(
        message, List.of(), "/catalog/demo-pg.salesdb.sales.customer", null, null, null);
  }

  @Test
  void aTurnAnswersWithTheModelsText() {
    LlmAssistResource.ChatReply reply = resource.chat(ask("hello"), security, request);

    assertThat(reply.text()).isEqualTo("Hello!");
    assertThat(reply.cards()).isEmpty();
    assertThat(reply.model()).isEqualTo("gpt-test");
    assertThat(toolsSent.get(0)).contains("search_catalog", "write_sql", "navigate");
    String system = messagesSent.get(0).get(0).path("content").asText();
    assertThat(system).contains("Analyst A", "demo-pg.salesdb.sales.customer");
    // The address the request came from is used for decisions, never told to the model.
    assertThat(messagesSent.get(0).toString()).doesNotContain("192.0.2.77");
  }

  @Test
  void theModelIsOfferedOnlyTheToolsOfThePersonsFeatures() {
    when(features.allowedFor(analyst)).thenReturn(EnumSet.of(Feature.CHAT, Feature.CATALOG_SEARCH));

    resource.chat(ask("find salary"), security, request);

    assertThat(toolsSent.get(0)).containsExactly("search_catalog", "describe_asset", "navigate");
    assertThat(messagesSent.get(0).get(0).path("content").asText()).doesNotContain("write_sql");
  }

  @Test
  void anEmptyOrOverlongMessageIsRefused() {
    assertThatThrownBy(() -> resource.chat(ask("   "), security, request))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> resource.chat(null, security, request))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () -> resource.chat(ask("x".repeat(AgentPrompts.MAX_MESSAGE + 1)), security, request))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(client);
  }

  @Test
  void theChatIsRefusedWhenTheAssistantIsOff() {
    when(store.effectiveFor(analyst.id())).thenReturn(switchedOn(false));

    assertThatThrownBy(() -> resource.chat(ask("hi"), security, request))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(client);
  }

  @Test
  void theChatIsRefusedToARoleNotOfferedIt() {
    when(features.allowedFor(analyst)).thenReturn(EnumSet.of(Feature.WRITE_SQL));

    assertThatThrownBy(() -> resource.chat(ask("hi"), security, request))
        .isInstanceOf(ForbiddenException.class)
        .hasMessageContaining("Chat with ARAK is not offered to your role");
    verifyNoInteractions(client);
  }

  @Test
  void theOtherJobsAreNarrowedTheSameWay() {
    when(features.allowedFor(analyst)).thenReturn(EnumSet.of(Feature.CHAT));

    assertThatThrownBy(
            () ->
                resource.sql(
                    new LlmAssistResource.SqlAsk(
                        "how many customers", UUID.randomUUID(), null, null),
                    security))
        .isInstanceOf(ForbiddenException.class)
        .hasMessageContaining("Write queries");
    assertThatThrownBy(
            () ->
                resource.policy(
                    new LlmAssistResource.PolicyAsk("mask email", null, null, null), security))
        .isInstanceOf(ForbiddenException.class)
        .hasMessageContaining("Draft policies");
    verifyNoInteractions(client);
  }

  @Test
  void theOfferedListIsWhatTheStoreAllows() {
    when(features.allowedFor(analyst)).thenReturn(EnumSet.of(Feature.CHAT, Feature.INSIGHTS));

    assertThat(resource.offered(security).features())
        .containsExactly(Feature.CHAT, Feature.INSIGHTS);
  }

  @Test
  void aDeploymentWithoutTheChatSaysSo() {
    LlmAssistResource bare = new LlmAssistResource(store, client, catalog);

    assertThatThrownBy(() -> bare.chat(ask("hi"), security, request))
        .isInstanceOf(ServiceUnavailableException.class);
    // With no feature table everything is offered, as before M28.
    assertThat(bare.offered(security).features()).containsExactlyElementsOf(EnumSet.allOf(Feature.class));
  }

  @Test
  void aGatewayFailureIsAServiceUnavailable() throws Exception {
    doThrow(new LlmClient.LlmException("The gateway did not answer"))
        .when(client)
        .converse(any(), anyString(), any(), any());

    assertThatThrownBy(() -> resource.chat(ask("hi"), security, request))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("did not answer");
  }

  @Test
  void aBlankAnswerBecomesAPlainApology() throws Exception {
    doReturn(
            new LlmClient.Turn(
                "  ", List.of(), "gpt-test", 1, 1, json.createObjectNode().put("role", "assistant")))
        .when(client)
        .converse(any(), eq("gpt-test"), any(), any());

    assertThat(resource.chat(ask("hi"), security, request).text())
        .isEqualTo("I have no answer to that. Try asking it another way.");
  }
}
