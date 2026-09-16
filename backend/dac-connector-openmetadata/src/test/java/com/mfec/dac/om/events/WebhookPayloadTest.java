package com.mfec.dac.om.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the receiver makes of a delivery.
 *
 * <p>The theme is that the parser is tolerant of shape and strict about
 * meaning. OpenMetadata decides the envelope — one event, a list, a list wrapped
 * in {@code data} — and adds fields between releases; none of that should cost a
 * change event, because a rejected delivery is never sent again. What it must
 * not do is invent a change out of a payload that does not describe one.
 */
class WebhookPayloadTest {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void readsASingleEvent() throws Exception {
    List<CatalogChange> changes =
        WebhookPayload.parse(
            json,
            """
            {
              "id": "1f3a4b8e-0000-4000-8000-000000000001",
              "eventType": "entityUpdated",
              "entityType": "table",
              "entityId": "1f3a4b8e-0000-4000-8000-000000000002",
              "entityFullyQualifiedName": "prod-mssql.SalesDB.dbo.customer",
              "timestamp": 1758000000000
            }
            """);

    assertThat(changes).singleElement().satisfies(change -> {
      assertThat(change.subject()).isEqualTo(CatalogChange.Subject.TABLE);
      assertThat(change.kind()).isEqualTo(CatalogChange.Kind.UPSERTED);
      assertThat(change.fqn()).isEqualTo("prod-mssql.SalesDB.dbo.customer");
      assertThat(change.timestamp()).isEqualTo(1758000000000L);
    });
  }

  @Test
  void readsABatchWrappedInData() throws Exception {
    List<CatalogChange> changes =
        WebhookPayload.parse(
            json,
            """
            {"data": [
              {"eventType": "entityUpdated", "entityType": "table",
               "entityFullyQualifiedName": "s.db.dbo.a", "timestamp": 1},
              {"eventType": "entityDeleted", "entityType": "databaseSchema",
               "entityFullyQualifiedName": "s.db.dbo", "timestamp": 2}
            ]}
            """);

    assertThat(changes).hasSize(2);
    assertThat(changes.get(1).kind()).isEqualTo(CatalogChange.Kind.REMOVED);
    assertThat(changes.get(1).subject()).isEqualTo(CatalogChange.Subject.SCHEMA);
  }

  @Test
  void readsABareArray() throws Exception {
    List<CatalogChange> changes =
        WebhookPayload.parse(
            json,
            """
            [{"eventType": "entityCreated", "entityType": "glossaryTerm",
              "entityFullyQualifiedName": "Finance.CustomerIdentity", "timestamp": 3}]
            """);

    assertThat(changes).singleElement().satisfies(
        change -> assertThat(change.governance()).isTrue());
  }

  @Test
  void survivesFieldsItHasNeverHeardOf() throws Exception {
    // The reason this is a tree parse and not a bound model: a field added in a
    // patch release must not turn into a rejected delivery.
    List<CatalogChange> changes =
        WebhookPayload.parse(
            json,
            """
            {"eventType": "entityUpdated", "entityType": "table",
             "entityFullyQualifiedName": "s.db.dbo.a", "timestamp": 4,
             "somethingAddedIn21": {"nested": [1, 2, 3]},
             "entity": {"columns": [{"name": "email"}]}}
            """);

    assertThat(changes).hasSize(1);
  }

  @Test
  void ignoresEntityTypesThisPlatformDoesNotCache() throws Exception {
    // A dashboard changing is not our business, and an empty list is the
    // correct answer rather than an error the operator has to chase.
    assertThat(
            WebhookPayload.parse(
                json,
                """
                {"eventType": "entityUpdated", "entityType": "dashboard",
                 "entityFullyQualifiedName": "looker.sales", "timestamp": 5}
                """))
        .isEmpty();
  }

  @Test
  void ignoresEventTypesThatDoNotChangeAnAsset() throws Exception {
    assertThat(
            WebhookPayload.parse(
                json,
                """
                [{"eventType": "userLogin", "entityType": "table",
                  "entityFullyQualifiedName": "s.db.dbo.a", "timestamp": 6},
                 {"eventType": "threadCreated", "entityType": "table",
                  "entityFullyQualifiedName": "s.db.dbo.a", "timestamp": 7}]
                """))
        .isEmpty();
  }

  @Test
  void ignoresAnEventWithNoFullyQualifiedName() throws Exception {
    // The FQN is how the entity is re-read. Without one there is nothing to
    // refresh, and guessing from the id would be a second lookup path
    // maintained for the sake of malformed payloads.
    assertThat(
            WebhookPayload.parse(
                json,
                """
                {"eventType": "entityUpdated", "entityType": "table", "timestamp": 8}
                """))
        .isEmpty();
  }

  @Test
  void treatsTheSubscriptionTestPayloadAsNothingToDo() throws Exception {
    assertThat(WebhookPayload.parse(json, "{}")).isEmpty();
    assertThat(WebhookPayload.parse(json, "")).isEmpty();
    assertThat(WebhookPayload.parse(json, null)).isEmpty();
  }

  @Test
  void refusesABodyThatIsNotJson() {
    // Distinct from "nothing to do", and the receiver turns it into a 400: a
    // retry of the same bytes cannot succeed.
    assertThatThrownBy(() -> WebhookPayload.parse(json, "<html>502 Bad Gateway</html>"))
        .isInstanceOf(WebhookPayload.MalformedPayloadException.class)
        .hasMessageContaining("not valid JSON");
  }

  @Test
  void toleratesAnEntityIdThatIsNotAUuid() throws Exception {
    List<CatalogChange> changes =
        WebhookPayload.parse(
            json,
            """
            {"eventType": "entityUpdated", "entityType": "table", "entityId": "not-a-uuid",
             "entityFullyQualifiedName": "s.db.dbo.a", "timestamp": 9}
            """);

    // The id is carried for the log; losing it must not lose the change.
    assertThat(changes).singleElement().satisfies(
        change -> assertThat(change.entityId()).isNull());
  }
}
