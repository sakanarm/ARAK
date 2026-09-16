package com.mfec.dac.om.events;

import com.mfec.dac.om.OpenMetadataClient;
import com.mfec.dac.om.client.ApiException;
import com.mfec.dac.om.client.model.ChangeEvent;
import com.mfec.dac.om.client.model.EventList;
import com.mfec.dac.om.client.model.Paging;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads OpenMetadata's change feed from a cursor (FR-1.5, pull side).
 *
 * <p>The backup for the webhook, and the only path that works at all where
 * OpenMetadata cannot reach us — behind a firewall, on a laptop, in CI. A
 * webhook that silently stopped being delivered is otherwise invisible: the
 * cache simply stops changing, and every policy decision made from it stays
 * confidently wrong.
 *
 * <p>{@code GET /v1/events} takes a millisecond timestamp and pages with the
 * same {@code after} cursor as every other collection. Whether its lower bound
 * is inclusive is not documented, and this does not depend on the answer: the
 * cursor is advanced to the newest event seen, so an inclusive bound re-delivers
 * that one event on the next poll, and re-applying a change is free because
 * every change is applied by re-reading the entity rather than by replaying a
 * diff.
 */
public class ChangeEventReader {

  private static final Logger LOG = LoggerFactory.getLogger(ChangeEventReader.class);

  /** OpenMetadata caps this endpoint at 1000. */
  private static final long PAGE_SIZE = 500;

  /** What one read of the feed found. */
  public record Batch(List<CatalogChange> changes, long highWaterMark, int seen, int ignored) {

    public Batch {
      changes = changes == null ? List.of() : List.copyOf(changes);
    }

    public boolean empty() {
      return changes.isEmpty();
    }
  }

  private final OpenMetadataClient client;
  private final int maxEventsPerRead;

  public ChangeEventReader(OpenMetadataClient client, int maxEventsPerRead) {
    this.client = client;
    this.maxEventsPerRead = maxEventsPerRead;
  }

  /**
   * Everything the catalog says changed since {@code sinceMillis}.
   *
   * <p>Bounded by {@code maxEventsPerRead}. A poller that has been down for a
   * week would otherwise spend its first tick walking a million events while the
   * cache it is meant to be refreshing stays stale; stopping early and coming
   * back in a few seconds makes progress visible and keeps one tick short. The
   * high-water mark is the newest event actually returned, so the next read
   * resumes exactly where this one stopped.
   *
   * @param sinceMillis epoch millis to read from; the stored cursor
   * @return the changes worth acting on, oldest first
   */
  public Batch read(long sinceMillis) throws ApiException {
    List<CatalogChange> changes = new ArrayList<>();
    long highWaterMark = sinceMillis;
    int seen = 0;
    int ignored = 0;
    String after = null;
    String previousCursor = null;

    while (seen < maxEventsPerRead) {
      long limit = Math.min(PAGE_SIZE, maxEventsPerRead - seen);
      EventList page =
          client
              .events()
              .listChangeEvents(
                  sinceMillis,
                  CatalogChange.WATCHED_ENTITY_TYPES,
                  CatalogChange.WATCHED_ENTITY_TYPES,
                  CatalogChange.WATCHED_ENTITY_TYPES,
                  CatalogChange.WATCHED_ENTITY_TYPES,
                  null,
                  limit,
                  after);
      if (page == null || page.getData() == null || page.getData().isEmpty()) {
        break;
      }
      for (ChangeEvent event : page.getData()) {
        seen++;
        if (event != null && event.getTimestamp() != null) {
          highWaterMark = Math.max(highWaterMark, event.getTimestamp());
        }
        CatalogChange change = normalise(event);
        if (change == null) {
          ignored++;
        } else {
          changes.add(change);
        }
      }
      Paging paging = page.getPaging();
      after = paging == null ? null : paging.getAfter();
      if (after == null || after.isBlank() || after.equals(previousCursor)) {
        break;
      }
      previousCursor = after;
    }

    if (seen >= maxEventsPerRead) {
      LOG.info(
          "Stopped after {} change events at {}; more remain and the next poll will continue",
          seen,
          highWaterMark);
    }
    return new Batch(changes, highWaterMark, seen, ignored);
  }

  /**
   * One event as a {@link CatalogChange}, or null if it is not ours to act on.
   *
   * <p>An event with no FQN is dropped rather than guessed at. The FQN is how
   * the entity is re-read, and the id alone would mean a different lookup path
   * for a case that, in practice, only arises on malformed payloads.
   */
  static CatalogChange normalise(ChangeEvent event) {
    if (event == null) {
      return null;
    }
    String entityType = event.getEntityType();
    String eventType =
        event.getEventType() == null ? null : event.getEventType().getValue();
    String fqn = event.getEntityFullyQualifiedName();
    if (fqn == null || fqn.isBlank()) {
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
                                event.getEntityId(),
                                fqn,
                                event.getTimestamp() == null ? 0L : event.getTimestamp())))
        .orElse(null);
  }
}
