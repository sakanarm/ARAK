package com.mfec.dac.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mfec.dac.llm.LlmClient.LlmException;
import com.mfec.dac.llm.LlmClient.ToolCall;
import com.mfec.dac.llm.LlmClient.Turn;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The conversation loop behind the assistant panel (M28).
 *
 * <p>The model asks for tools, this runs them through a {@link Toolbox} and
 * hands back what they said, until the model answers or the step budget runs
 * out. It knows nothing about the catalogue, the query log or who is asking:
 * the toolbox it is given was built for one caller and does only what that
 * caller could do on the pages it stands for. That split is what makes the
 * loop testable with a scripted model, and what keeps the rules about data in
 * one place instead of two.
 */
public final class ArakAgent {

  private static final Logger LOG = LoggerFactory.getLogger(ArakAgent.class);

  /** Tool rounds before the loop gives up and says so. */
  public static final int MAX_STEPS = 6;

  /** Cards kept per answer. More than this is a page, not a reply. */
  public static final int MAX_CARDS = 10;

  /** One step of the model. */
  @FunctionalInterface
  public interface Model {
    Turn next(ArrayNode messages, ArrayNode tools) throws LlmException;
  }

  /** The tools, already bound to the person chatting. */
  @FunctionalInterface
  public interface Toolbox {
    /** Runs one tool. Returns a result the model reads; never throws for a bad argument. */
    Result run(String name, JsonNode arguments);
  }

  /**
   * Something the console shows beside the answer, for the person to act on.
   *
   * @param kind {@code sql}, {@code policy}, {@code link} or {@code asset}
   * @param access READABLE or REQUESTABLE, on an asset card
   */
  public record Card(
      String kind,
      String title,
      String text,
      String route,
      String sourceId,
      String engine,
      String assetFqn,
      String access) {}

  /** What one tool said: text for the model, and cards for the person. */
  public record Result(String content, List<Card> cards) {
    public static Result text(String content) {
      return new Result(content, List.of());
    }
  }

  /** The answer. */
  public record Reply(String text, List<Card> cards, List<String> toolsUsed, String model) {}

  private final ObjectMapper json;

  public ArakAgent(ObjectMapper json) {
    this.json = json;
  }

  public Reply run(
      Model model,
      Toolbox toolbox,
      String system,
      List<AgentPrompts.Message> history,
      String message,
      ArrayNode tools)
      throws LlmException {
    ArrayNode messages = json.createArrayNode();
    messages.addObject().put("role", "system").put("content", system);
    for (AgentPrompts.Message earlier : AgentPrompts.history(history)) {
      messages.addObject().put("role", earlier.role()).put("content", earlier.content());
    }
    messages
        .addObject()
        .put("role", "user")
        .put("content", AgentPrompts.cap(message.trim(), AgentPrompts.MAX_MESSAGE));

    Set<String> offered = new HashSet<>();
    for (JsonNode tool : tools) {
      offered.add(tool.path("function").path("name").asText());
    }

    List<Card> cards = new ArrayList<>();
    Set<String> used = new LinkedHashSet<>();
    String lastModel = null;
    for (int step = 0; step < MAX_STEPS; step++) {
      Turn turn = model.next(messages, tools);
      lastModel = turn.model();
      messages.add(turn.message());
      if (turn.calls().isEmpty()) {
        return new Reply(clean(turn.content()), List.copyOf(cards), List.copyOf(used), lastModel);
      }
      for (ToolCall call : turn.calls()) {
        Result result = invoke(toolbox, offered, call);
        used.add(call.name());
        for (Card card : result.cards()) {
          if (cards.size() < MAX_CARDS && !cards.contains(card)) {
            cards.add(card);
          }
        }
        ObjectNode reply = messages.addObject();
        reply.put("role", "tool");
        reply.put("tool_call_id", call.id());
        reply.put("content", AgentPrompts.cap(result.content(), AgentPrompts.MAX_TOOL_RESULT));
      }
    }
    return new Reply(
        "I stopped before finishing: that took more steps than I am allowed. Try asking for one"
            + " thing at a time.",
        List.copyOf(cards),
        List.copyOf(used),
        lastModel);
  }

  private Result invoke(Toolbox toolbox, Set<String> offered, ToolCall call) {
    if (!offered.contains(call.name())) {
      // Asked for a tool it was not given: one that exists for somebody else,
      // or one it made up. Neither is run.
      return Result.text("error: there is no tool called " + call.name());
    }
    JsonNode arguments;
    try {
      String raw = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
      arguments = json.readTree(raw);
      if (!arguments.isObject()) {
        return Result.text("error: the arguments must be a JSON object");
      }
    } catch (Exception e) {
      return Result.text("error: the arguments were not valid JSON");
    }
    try {
      Result result = toolbox.run(call.name(), arguments);
      return result == null ? Result.text("error: the tool returned nothing") : result;
    } catch (RuntimeException e) {
      // The message is not passed on: it can name a table or a statement the
      // person was not meant to learn about from an exception.
      LOG.warn("Assistant tool {} failed", call.name(), e);
      return Result.text("error: the tool failed. Tell the person it could not be done just now.");
    }
  }

  private static String clean(String text) {
    return text == null ? "" : text.trim();
  }
}
