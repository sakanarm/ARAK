package com.mfec.dac.om.events;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One thing that changed in OpenMetadata, in the only shape this platform cares
 * about (FR-1.5).
 *
 * <p>Two transports deliver the same news: the webhook OpenMetadata pushes, and
 * the {@code GET /v1/events} feed the poller reads when the webhook has been
 * missed. They carry it in different envelopes, so both are normalised to this
 * before anything acts on it. Everything downstream — the applier, its tests —
 * then has one shape to handle rather than two, and a third transport later
 * costs a parser rather than a second code path through the cache.
 *
 * <p>What is deliberately <em>not</em> here is the entity itself. OpenMetadata
 * does attach it to the event, but acting on it would make the cache a replay of
 * a message queue: miss one event, or take two out of order, and the cache holds
 * a state the catalog was never in. Re-reading the entity by FQN makes every
 * change idempotent and every duplicate free, which is what lets the poller
 * overlap its window without keeping a set of seen event ids.
 *
 * @param subject   what kind of thing changed, as far as the cache is concerned
 * @param kind      whether it still exists
 * @param entityType OpenMetadata's own name for the type, kept for the log
 * @param entityId  OpenMetadata's UUID; null on a payload that omitted it
 * @param fqn       the fully-qualified name, which is how it is re-read
 * @param timestamp OpenMetadata's event time in epoch millis, which is the
 *                  poller's cursor
 */
public record CatalogChange(
    Subject subject, Kind kind, String entityType, UUID entityId, String fqn, long timestamp) {

  /** Whether the entity is still there. */
  public enum Kind {
    /** Created, updated, restored — re-read it. */
    UPSERTED,
    /** Deleted or soft-deleted — stop letting policies match it. */
    REMOVED
  }

  /**
   * What the change means for the cache.
   *
   * <p>The asset levels are separate because refreshing each costs a different
   * amount: a table is one re-read, a service is a re-crawl of everything under
   * it. {@code GOVERNANCE} lumps together classifications, tags, glossaries,
   * domains and data products, because those collections number in the hundreds
   * and are re-read whole — splitting them would buy nothing.
   */
  public enum Subject {
    SERVICE,
    DATABASE,
    SCHEMA,
    TABLE,
    GOVERNANCE
  }

  /**
   * OpenMetadata entity types this platform caches, and what each one is.
   *
   * <p>Anything absent is ignored on purpose. The feed carries dashboards,
   * pipelines, test cases, threads, logins — the whole catalog — and a change to
   * something we never cached cannot make our copy of it stale.
   */
  private static final Map<String, Subject> SUBJECTS =
      Map.ofEntries(
          Map.entry("databaseService", Subject.SERVICE),
          Map.entry("database", Subject.DATABASE),
          Map.entry("databaseSchema", Subject.SCHEMA),
          Map.entry("table", Subject.TABLE),
          Map.entry("classification", Subject.GOVERNANCE),
          Map.entry("tag", Subject.GOVERNANCE),
          Map.entry("glossary", Subject.GOVERNANCE),
          Map.entry("glossaryTerm", Subject.GOVERNANCE),
          Map.entry("domain", Subject.GOVERNANCE),
          Map.entry("dataProduct", Subject.GOVERNANCE),
          Map.entry("type", Subject.GOVERNANCE));

  /** The entity types above that live in the asset tree. */
  public static final String ASSET_ENTITY_TYPES =
      "databaseService,database,databaseSchema,table";

  /** The entity types above that are governance objects. */
  public static final String GOVERNANCE_ENTITY_TYPES =
      "classification,tag,glossary,glossaryTerm,domain,dataProduct,type";

  /** Every entity type worth asking the change feed for. */
  public static final String WATCHED_ENTITY_TYPES =
      ASSET_ENTITY_TYPES + "," + GOVERNANCE_ENTITY_TYPES;

  /** What a change to this entity type means here, or empty if it means nothing. */
  public static Optional<Subject> subjectOf(String entityType) {
    return entityType == null ? Optional.empty() : Optional.ofNullable(SUBJECTS.get(entityType));
  }

  /**
   * How an OpenMetadata event type lands here, or empty for one to skip.
   *
   * <p>{@code entityNoChange} is skipped rather than treated as an update: it is
   * emitted when an ingestion run re-asserted an entity that had not moved, and
   * on a large catalog it is most of the feed.
   *
   * <p>A soft delete counts as removed. OpenMetadata keeps the row so it can be
   * restored, but a soft-deleted table is one nobody may query, and leaving it
   * matchable would let a policy keep granting access to it.
   */
  public static Optional<Kind> kindOf(String eventType) {
    if (eventType == null) {
      return Optional.empty();
    }
    return switch (eventType) {
      case "entityCreated", "entityUpdated", "entityRestored", "entityFieldsChanged" ->
          Optional.of(Kind.UPSERTED);
      case "entityDeleted", "entitySoftDeleted" -> Optional.of(Kind.REMOVED);
      default -> Optional.empty();
    };
  }

  /** True when this change needs the whole governance snapshot re-read. */
  public boolean governance() {
    return subject == Subject.GOVERNANCE;
  }

  /** A stable key for collapsing repeated changes to the same thing in one batch. */
  public String key() {
    return subject == Subject.GOVERNANCE ? "GOVERNANCE" : subject + "|" + fqn;
  }

  @Override
  public String toString() {
    return kind + " " + entityType + " " + fqn + " @" + timestamp;
  }
}
