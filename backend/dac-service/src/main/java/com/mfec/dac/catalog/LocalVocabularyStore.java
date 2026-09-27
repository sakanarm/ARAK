package com.mfec.dac.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Classifications and tags made in ARAK, for vocabulary OpenMetadata does not
 * have yet (FR-1.7).
 *
 * <p>They are written to the same {@code classification} and {@code tag}
 * tables the crawl fills, with provenance {@code local}. Everything that reads
 * the vocabulary therefore sees them with no second code path: the Governance
 * screens, the policy builder's pickers, and {@link LocalTagStore}, which is
 * how a local tag reaches a table. The crawl leaves them alone, because its
 * upserts and its deletes are confined to {@code provenance = 'openmetadata'}
 * ({@link GovernanceStore}); if OpenMetadata later makes a value with the same
 * FQN, the one made here keeps its row.
 *
 * <p>Nothing here is ever deleted. A value somebody no longer wants is
 * disabled: it stops being offered, and a tag already attached stays attached
 * until whoever governs that table takes it off, so a mask never vanishes
 * because somebody tidied a list. Values from OpenMetadata cannot be changed
 * here at all; the next sync would put them back.
 */
public class LocalVocabularyStore {

  public enum Kind { CLASSIFICATION, TAG }

  /** One value as it stands. {@code classificationFqn} is null for a classification. */
  public record Value(
      Kind kind,
      String fqn,
      String name,
      String classificationFqn,
      String displayName,
      String description,
      boolean mutuallyExclusive,
      boolean disabled,
      String provenance,
      String createdBy) {}

  /** Why a change was refused; the resource turns each kind into a status. */
  public static final class Refused extends RuntimeException {
    public enum Reason { NOT_FOUND, INVALID, CONFLICT }

    private final Reason reason;

    public Refused(Reason reason, String message) {
      super(message);
      this.reason = reason;
    }

    public Reason reason() {
      return reason;
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Jdbi jdbi;

  public LocalVocabularyStore(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /**
   * A new classification. Its FQN is its name: the resource has already
   * refused a name with a dot or a quote in it, so no quoting is needed.
   */
  public Value createClassification(
      String name, String displayName, String description, boolean mutuallyExclusive,
      String actor) {
    return jdbi.inTransaction(
        h -> {
          refuseTaken(h, "classification", name);
          h.createUpdate(
                  """
                  INSERT INTO classification (fqn, name, display_name, description,
                                              mutually_exclusive, provider, disabled,
                                              provenance, created_by, updated_at)
                  VALUES (:fqn, :name, CAST(:displayName AS text), :description,
                          :exclusive, 'user', false, 'local', :actor, now())
                  """)
              .bind("fqn", name)
              .bind("name", name)
              .bind("displayName", displayName)
              .bind("description", description)
              .bind("exclusive", mutuallyExclusive)
              .bind("actor", actor)
              .execute();
          Value made = find(h, Kind.CLASSIFICATION, name).orElseThrow();
          audit(h, actor, "CREATE", made, null);
          return made;
        });
  }

  /**
   * A new tag directly under a classification, from OpenMetadata or made here.
   * A tag under OpenMetadata's {@code PII} is still local: it is the tag that
   * was made here, not the classification.
   */
  public Value createTag(
      String classificationFqn, String name, String displayName, String description,
      String actor) {
    return jdbi.inTransaction(
        h -> {
          Value classification =
              find(h, Kind.CLASSIFICATION, classificationFqn)
                  .orElseThrow(
                      () ->
                          new Refused(
                              Refused.Reason.NOT_FOUND,
                              "No classification " + classificationFqn));
          if (classification.disabled()) {
            throw new Refused(
                Refused.Reason.INVALID,
                classification.fqn() + " is disabled, so nothing new can go under it");
          }
          String fqn = classification.fqn() + "." + name;
          refuseTaken(h, "tag", fqn);
          h.createUpdate(
                  """
                  INSERT INTO tag (classification_fqn, fqn, parent_fqn, name, display_name,
                                   description, disabled, provenance, created_by, updated_at)
                  VALUES (:classification, :fqn, NULL, :name, CAST(:displayName AS text),
                          :description, false, 'local', :actor, now())
                  """)
              .bind("classification", classification.fqn())
              .bind("fqn", fqn)
              .bind("name", name)
              .bind("displayName", displayName)
              .bind("description", description)
              .bind("actor", actor)
              .execute();
          Value made = find(h, Kind.TAG, fqn).orElseThrow();
          audit(h, actor, "CREATE", made, null);
          return made;
        });
  }

  /**
   * Changes a value made here. A null argument leaves that field as it is; a
   * blank display name takes it away. Nothing is written, and nothing audited,
   * when the value already says what was asked for.
   */
  public Value update(
      Kind kind, String fqn, String displayName, String description, Boolean disabled,
      String actor) {
    return jdbi.inTransaction(
        h -> {
          Value before =
              find(h, kind, fqn)
                  .orElseThrow(
                      () ->
                          new Refused(
                              Refused.Reason.NOT_FOUND,
                              "No " + kind.name().toLowerCase() + " " + fqn));
          if (!"local".equals(before.provenance())) {
            throw new Refused(
                Refused.Reason.INVALID,
                fqn + " comes from OpenMetadata; change it there and the next sync brings it here");
          }
          String nextName =
              displayName == null ? before.displayName() : displayName.isBlank() ? null : displayName;
          String nextDescription = description == null ? before.description() : description;
          boolean nextDisabled = disabled == null ? before.disabled() : disabled;
          if (Objects.equals(nextName, before.displayName())
              && Objects.equals(nextDescription, before.description())
              && nextDisabled == before.disabled()) {
            return before;
          }
          h.createUpdate(
                  """
                  UPDATE <table> SET display_name = CAST(:displayName AS text),
                                     description = :description, disabled = :disabled,
                                     updated_at = now()
                   WHERE fqn = :fqn AND provenance = 'local'
                  """)
              .define("table", table(kind))
              .bind("displayName", nextName)
              .bind("description", nextDescription)
              .bind("disabled", nextDisabled)
              .bind("fqn", before.fqn())
              .execute();
          Value after = find(h, kind, before.fqn()).orElseThrow();
          audit(h, actor, "UPDATE", after, before);
          return after;
        });
  }

  /** The value as it stands, from OpenMetadata or made here. */
  public Optional<Value> find(Kind kind, String fqn) {
    return jdbi.withHandle(h -> find(h, kind, fqn));
  }

  // --------------------------------------------------------------- plumbing

  private static Optional<Value> find(Handle h, Kind kind, String fqn) {
    String sql =
        kind == Kind.CLASSIFICATION
            ? """
              SELECT fqn, name, NULL AS classification_fqn, display_name, description,
                     mutually_exclusive, disabled, provenance, created_by
                FROM classification WHERE fqn = :fqn
              """
            : """
              SELECT fqn, name, classification_fqn, display_name, description,
                     false AS mutually_exclusive, disabled, provenance, created_by
                FROM tag WHERE fqn = :fqn
              """;
    return h.createQuery(sql)
        .bind("fqn", fqn)
        .map(
            (rs, ctx) ->
                new Value(
                    kind,
                    rs.getString("fqn"),
                    rs.getString("name"),
                    rs.getString("classification_fqn"),
                    rs.getString("display_name"),
                    rs.getString("description"),
                    rs.getBoolean("mutually_exclusive"),
                    rs.getBoolean("disabled"),
                    rs.getString("provenance"),
                    rs.getString("created_by")))
        .findOne();
  }

  /**
   * Refuses an FQN already in use, whatever its case. {@code pii} next to
   * {@code PII} would be two values a person cannot tell apart in a picker,
   * and a policy on one would quietly miss the assets tagged with the other.
   */
  private static void refuseTaken(Handle h, String table, String fqn) {
    Optional<String> taken =
        h.createQuery("SELECT fqn FROM <table> WHERE lower(fqn) = lower(:fqn) LIMIT 1")
            .define("table", table)
            .bind("fqn", fqn)
            .mapTo(String.class)
            .findOne();
    if (taken.isPresent()) {
      throw new Refused(Refused.Reason.CONFLICT, taken.get() + " already exists");
    }
  }

  private static String table(Kind kind) {
    return kind == Kind.CLASSIFICATION ? "classification" : "tag";
  }

  private static void audit(Handle h, String actor, String action, Value after, Value before) {
    h.createUpdate(
            """
            INSERT INTO audit_vocabulary (actor, action, kind, fqn, before, after)
            VALUES (:actor, :action, :kind, :fqn, CAST(:before AS jsonb), CAST(:after AS jsonb))
            """)
        .bind("actor", actor)
        .bind("action", action)
        .bind("kind", after.kind().name())
        .bind("fqn", after.fqn())
        .bind("before", before == null ? null : json(before))
        .bind("after", json(after))
        .execute();
  }

  private static String json(Value value) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("displayName", value.displayName());
    fields.put("description", value.description());
    fields.put("mutuallyExclusive", value.mutuallyExclusive());
    fields.put("disabled", value.disabled());
    try {
      return JSON.writeValueAsString(fields);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
