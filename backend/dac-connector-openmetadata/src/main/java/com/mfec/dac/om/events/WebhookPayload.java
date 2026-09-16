package com.mfec.dac.om.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns whatever OpenMetadata posted to the webhook into changes (FR-1.5, push
 * side).
 *
 * <p>Read as a tree rather than bound to the generated {@code ChangeEvent}. The
 * shape varies with how the subscription was configured — one event, a list of
 * them, or a list wrapped in {@code data} — and the generated model is strict
 * about a payload we do not control and did not ask for the rest of. Binding it
 * would turn a field added in a patch release into a rejected delivery, and a
 * rejected delivery is a change the cache never hears about again.
 *
 * <p>Only six fields are read, and only three are load-bearing: the entity type,
 * the event type and the FQN. Everything else in the payload is ignored,
 * including the embedded entity — see {@link CatalogChange} for why the entity
 * is re-read rather than trusted.
 */
public final class WebhookPayload {

  private static final Logger LOG = LoggerFactory.getLogger(WebhookPayload.class);

  private WebhookPayload() {}

  /** Raised when the body is not JSON at all, which no retry will fix. */
  public static class MalformedPayloadException extends Exception {
    public MalformedPayloadException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Every change in one delivery, in the order it was sent.
   *
   * <p>An empty list is a normal answer, not a failure: OpenMetadata sends a
   * test payload when a subscription is created, and it delivers events for
   * entity types this platform does not cache. Both should be accepted and
   * ignored rather than reported as an error the operator has to chase.
   */
  public static List<CatalogChange> parse(ObjectMapper json, String body)
      throws MalformedPayloadException {

    if (body == null || body.isBlank()) {
      return List.of();
    }
    JsonNode root;
    try {
      root = json.readTree(body);
    } catch (JsonProcessingException e) {
      throw new MalformedPayloadException("the webhook body is not valid JSON", e);
    }
    List<CatalogChange> changes = new ArrayList<>();
    collect(root, changes);
    return changes;
  }

  private static void collect(JsonNode node, List<CatalogChange> into) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isArray()) {
      node.forEach(child -> collect(child, into));
      return;
    }
    if (!node.isObject()) {
      return;
    }
    // A subscription configured to batch wraps the events; one configured to
    // send them singly does not. Both spellings of the wrapper are accepted
    // because both appear depending on the destination type.
    for (String wrapper : new String[] {"data", "events", "changeEvents"}) {
      JsonNode wrapped = node.get(wrapper);
      if (wrapped != null && wrapped.isArray()) {
        collect(wrapped, into);
        return;
      }
    }
    CatalogChange change = one(node);
    if (change != null) {
      into.add(change);
    }
  }

  private static CatalogChange one(JsonNode node) {
    String entityType = text(node, "entityType");
    String eventType = text(node, "eventType");
    String fqn = text(node, "entityFullyQualifiedName");
    if (entityType == null || eventType == null) {
      return null;
    }
    if (fqn == null) {
      // Worth a line in the log: it is the difference between "we ignored a
      // dashboard" and "we were told about a table and could not act on it".
      if (CatalogChange.subjectOf(entityType).isPresent()) {
        LOG.warn("Webhook delivered a {} {} with no fullyQualifiedName; ignoring",
            entityType, eventType);
      }
      return null;
    }
    return CatalogChange.subjectOf(entityType)
        .flatMap(
            subject ->
                CatalogChange.kindOf(eventType)
                    .map(
                        kind ->
                            new CatalogChange(
                                subject,
                                kind,
                                entityType,
                                uuid(node, "entityId"),
                                fqn,
                                node.path("timestamp").asLong(0L))))
        .orElse(null);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      return null;
    }
    String text = value.asText().trim();
    return text.isEmpty() ? null : text;
  }

  private static UUID uuid(JsonNode node, String field) {
    String value = text(node, field);
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      // The id is carried for the log only; the FQN is what the refresh uses.
      return null;
    }
  }
}
