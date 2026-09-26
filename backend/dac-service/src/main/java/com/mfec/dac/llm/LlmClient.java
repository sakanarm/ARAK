package com.mfec.dac.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mfec.dac.llm.LlmSettings.Gateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls the OpenAI-compatible gateway.
 *
 * <p>Hand-written against the two endpoints this feature uses rather than
 * pulling in a vendor SDK. The gateway in front of us is LiteLLM, which speaks
 * the OpenAI shape for a dozen different model families; an SDK tied to one
 * vendor would have brought a transport, a retry policy and an auth scheme we
 * would then have to talk out of doing what it wants.
 *
 * <p>Three things here are load-bearing rather than incidental:
 *
 * <ul>
 *   <li>The client is handed a {@link Gateway} per call and holds neither an
 *       address nor a token in a field. It therefore has no opinion about
 *       <em>whose</em> key it is carrying — one person's own, or the shared one
 *       — and no way to reach for a different one. Deciding that is
 *       {@code LlmSettingStore.gatewayFor}, in one place.
 *   <li>Failures carry the gateway's own message through to the console.
 *       "Assistant unavailable" sends somebody to read a server log; "model
 *       gpt-5 does not exist" is a problem they can fix in ten seconds.
 *   <li>Nothing here logs a prompt. The prompt contains the schema the
 *       requester is allowed to see, and a log file is read by people who were
 *       never checked against that.
 * </ul>
 */
public class LlmClient {

  private static final Logger LOG = LoggerFactory.getLogger(LlmClient.class);

  /** Raised with a message fit to show the person who pressed the button. */
  public static class LlmException extends Exception {
    private static final long serialVersionUID = 1L;

    public LlmException(String message) {
      super(message);
    }

    public LlmException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** What one completion cost, for the console to show alongside the answer. */
  public record Completion(String text, String model, int promptTokens, int completionTokens) {}

  private final HttpClient http;
  private final ObjectMapper json;
  private final Duration requestTimeout;

  public LlmClient(ObjectMapper json) {
    this(json, Duration.ofSeconds(120));
  }

  public LlmClient(ObjectMapper json, Duration requestTimeout) {
    this.json = json;
    this.requestTimeout = requestTimeout;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // A redirect would re-send the Authorization header to wherever the
            // gateway pointed us. Follow nothing; a moved gateway is a
            // configuration change, not something to chase at runtime.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /**
   * The models the gateway is willing to serve.
   *
   * <p>Asked of the gateway rather than hard-coded, because the list is whatever
   * this organisation has put behind it this week, and a hard-coded list is a
   * dropdown that offers a model nobody can call.
   */
  public List<String> models(Gateway gateway) throws LlmException {
    JsonNode body = get(gateway, "/v1/models");
    JsonNode data = body.path("data");
    if (!data.isArray()) {
      throw new LlmException("The gateway answered /v1/models without a data array.");
    }
    List<String> ids = new ArrayList<>();
    for (JsonNode model : data) {
      String id = model.path("id").asText(null);
      if (id != null && !id.isBlank()) {
        ids.add(id);
      }
    }
    ids.sort(String::compareToIgnoreCase);
    return ids;
  }

  /**
   * One completion.
   *
   * @param system the instruction, which is ours
   * @param user the request, which carries only metadata the caller may read
   */
  public Completion complete(Gateway gateway, String model, String system, String user)
      throws LlmException {
    if (model == null || model.isBlank()) {
      throw new LlmException("No model has been chosen, and the platform has no default.");
    }

    ObjectNode request = json.createObjectNode();
    request.put("model", model);
    ArrayNode messages = request.putArray("messages");
    if (system != null && !system.isBlank()) {
      messages.addObject().put("role", "system").put("content", system);
    }
    messages.addObject().put("role", "user").put("content", user);
    // `max_completion_tokens`, not `max_tokens`: the newer OpenAI models behind
    // this gateway reject the older field outright, and the gateway passes the
    // rejection straight through.
    request.put("max_completion_tokens", 4000);

    JsonNode body = post(gateway, "/v1/chat/completions", request);
    JsonNode choice = body.path("choices").path(0);
    String text = choice.path("message").path("content").asText(null);
    if (text == null) {
      String finish = choice.path("finish_reason").asText("");
      throw new LlmException(
          finish.isBlank()
              ? "The gateway returned no message content."
              : "The model returned no content (finish_reason: " + finish + ").");
    }
    JsonNode usage = body.path("usage");
    return new Completion(
        text,
        body.path("model").asText(model),
        usage.path("prompt_tokens").asInt(0),
        usage.path("completion_tokens").asInt(0));
  }

  /** One tool the model asked for, with its arguments as the JSON text it wrote. */
  public record ToolCall(String id, String name, String arguments) {}

  /**
   * One step of a conversation (M28).
   *
   * @param content what the model said, or null when it only asked for tools
   * @param calls the tools it asked for; empty when this is its answer
   * @param message the assistant message as the gateway returned it, to be put
   *     back in the history verbatim so the tool results line up with it
   */
  public record Turn(
      String content,
      List<ToolCall> calls,
      String model,
      int promptTokens,
      int completionTokens,
      ObjectNode message) {}

  /**
   * One step of a conversation with tools, in the OpenAI shape the gateway
   * speaks.
   *
   * <p>The caller owns the loop and the messages. This only sends them and says
   * what came back; it runs no tool itself, so nothing here can act on what the
   * model asked for.
   */
  public Turn converse(Gateway gateway, String model, ArrayNode messages, ArrayNode tools)
      throws LlmException {
    if (model == null || model.isBlank()) {
      throw new LlmException("No model has been chosen, and the platform has no default.");
    }
    ObjectNode request = json.createObjectNode();
    request.put("model", model);
    request.set("messages", messages);
    if (tools != null && !tools.isEmpty()) {
      request.set("tools", tools);
      request.put("tool_choice", "auto");
    }
    request.put("max_completion_tokens", 4000);

    JsonNode body = post(gateway, "/v1/chat/completions", request);
    JsonNode choice = body.path("choices").path(0);
    JsonNode message = choice.path("message");
    if (!message.isObject()) {
      throw new LlmException("The gateway returned no message.");
    }
    List<ToolCall> calls = new ArrayList<>();
    for (JsonNode call : message.path("tool_calls")) {
      String name = call.path("function").path("name").asText("");
      if (!name.isBlank()) {
        calls.add(
            new ToolCall(
                call.path("id").asText(""),
                name,
                call.path("function").path("arguments").asText("{}")));
      }
    }
    String content = message.path("content").isTextual() ? message.path("content").asText() : null;
    if (calls.isEmpty() && content == null) {
      String finish = choice.path("finish_reason").asText("");
      throw new LlmException(
          finish.isBlank()
              ? "The gateway returned no message content."
              : "The model returned no content (finish_reason: " + finish + ").");
    }
    // Only the fields the next request needs. Some gateways add their own keys
    // to the message, and echoing an unknown key back is a 400 on others.
    ObjectNode echo = json.createObjectNode();
    echo.put("role", "assistant");
    if (content != null) {
      echo.put("content", content);
    } else {
      echo.putNull("content");
    }
    if (!calls.isEmpty()) {
      echo.set("tool_calls", message.path("tool_calls").deepCopy());
    }
    JsonNode usage = body.path("usage");
    return new Turn(
        content,
        List.copyOf(calls),
        body.path("model").asText(model),
        usage.path("prompt_tokens").asInt(0),
        usage.path("completion_tokens").asInt(0),
        echo);
  }

  // ------------------------------------------------------------------ plumbing

  private JsonNode get(Gateway gateway, String path) throws LlmException {
    HttpRequest request = base(gateway, path).GET().build();
    return send(request, path);
  }

  private JsonNode post(Gateway gateway, String path, JsonNode body) throws LlmException {
    byte[] payload;
    try {
      payload = json.writeValueAsBytes(body);
    } catch (Exception e) {
      throw new LlmException("Could not serialise the request.", e);
    }
    HttpRequest request =
        base(gateway, path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();
    return send(request, path);
  }

  private HttpRequest.Builder base(Gateway gateway, String path) throws LlmException {
    if (gateway == null || gateway.baseUrl() == null || gateway.baseUrl().isBlank()) {
      throw new LlmException("No LLM gateway is configured.");
    }
    if (gateway.token() == null || gateway.token().isBlank()) {
      throw new LlmException("This gateway has no API key saved for it.");
    }
    URI uri;
    try {
      uri = URI.create(gateway.baseUrl() + path);
    } catch (IllegalArgumentException e) {
      throw new LlmException("The gateway base URL is not a valid URL.", e);
    }
    return HttpRequest.newBuilder(uri)
        .timeout(requestTimeout)
        .header("Authorization", "Bearer " + gateway.token())
        .header("Accept", "application/json");
  }

  private JsonNode send(HttpRequest request, String path) throws LlmException {
    HttpResponse<byte[]> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (java.io.IOException e) {
      // The URI, not the message alone: "connection refused" without a host is
      // the least useful error in this product.
      throw new LlmException(
          "Could not reach the LLM gateway at " + request.uri() + " — " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmException("The call to the LLM gateway was interrupted.", e);
    }

    String text = new String(response.body(), StandardCharsets.UTF_8);
    if (response.statusCode() / 100 != 2) {
      throw new LlmException(
          "The LLM gateway refused " + path + " with HTTP " + response.statusCode() + ": "
              + describe(text));
    }
    try {
      return json.readTree(text);
    } catch (Exception e) {
      throw new LlmException("The LLM gateway answered " + path + " with something that is not JSON.", e);
    }
  }

  /**
   * The gateway's complaint, in a form safe to show.
   *
   * <p>Truncated, because an HTML error page from a proxy in front of the
   * gateway is several kilobytes of markup and none of it helps. Reduced to the
   * message field when the body is the usual JSON error envelope.
   */
  private String describe(String body) {
    if (body == null || body.isBlank()) {
      return "(no body)";
    }
    try {
      JsonNode node = json.readTree(body);
      JsonNode message = node.path("error").path("message");
      if (message.isTextual()) {
        return message.asText();
      }
    } catch (Exception ignored) {
      // Not JSON. Fall through and show the beginning of whatever it was.
    }
    String flat = body.replaceAll("\\s+", " ").trim();
    return flat.length() > 400 ? flat.substring(0, 400) + "…" : flat;
  }
}
