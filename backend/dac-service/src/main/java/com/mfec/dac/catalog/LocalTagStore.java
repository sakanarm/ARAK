package com.mfec.dac.catalog;

import com.mfec.dac.common.ChangeNotifier;
import com.mfec.dac.common.Fqns;
import com.mfec.dac.om.client.model.TagLabel;
import com.mfec.dac.om.facet.ExtractedFacet;
import com.mfec.dac.om.facet.FacetExtractor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;

/**
 * Tags attached in ARAK rather than in OpenMetadata (FR-1.7).
 *
 * <p>{@code local_tag} is what somebody attached; the {@code local} rows in
 * {@code asset_facet} are what a selector reads, derived from it. They are
 * rebuilt here after every change and by {@link AssetStore} every time a sync
 * writes the asset's facets, so a sync that wipes the asset's facets to write
 * OpenMetadata's puts the local ones straight back in the same transaction.
 *
 * <p>Only a tag in the vocabulary, and not disabled, may be attached: one the
 * governance crawl brought in, or one made here ({@link LocalVocabularyStore}). A local tag is expanded exactly the
 * way a crawled one is ({@link FacetExtractor#fromTagLabels}), so a policy on
 * {@code classifications contains 'PII'} cannot tell the two apart -- which is
 * the point.
 */
public class LocalTagStore {

  /** One tag attached here. */
  public record LocalTag(
      String targetFqn, String assetFqn, String tagFqn, String reason, String addedBy,
      Instant addedAt) {}

  /** Why a change was refused; the resource turns each kind into a status. */
  public static final class Refused extends RuntimeException {
    public enum Kind { NOT_FOUND, INVALID, CONFLICT }

    private final Kind kind;

    Refused(Kind kind, String message) {
      super(message);
      this.kind = kind;
    }

    public Kind kind() {
      return kind;
    }
  }

  private final Jdbi jdbi;
  private final ChangeNotifier notifier = new ChangeNotifier();

  public LocalTagStore(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /** Fires after a tag is added or removed: a mask may now apply, or stop applying. */
  public ChangeNotifier changes() {
    return notifier;
  }

  /**
   * The table a target is, or belongs to, if it is a current table or view or
   * one of their current columns. Empty for anything else, including a schema:
   * tags are attached where a selector and a mask can use them.
   */
  public Optional<String> assetOf(String targetFqn) {
    return jdbi.withHandle(h -> assetOf(h, targetFqn));
  }

  /** What was attached here on a table and its columns, oldest first. */
  public List<LocalTag> forAsset(String assetFqn) {
    return jdbi.withHandle(
        h ->
            h.createQuery(
                    """
                    SELECT target_fqn, asset_fqn, tag_fqn, reason, added_by, added_at
                      FROM local_tag WHERE asset_fqn = :asset
                     ORDER BY added_at, target_fqn, tag_fqn
                    """)
                .bind("asset", assetFqn)
                .map(
                    (rs, ctx) ->
                        new LocalTag(
                            rs.getString("target_fqn"),
                            rs.getString("asset_fqn"),
                            rs.getString("tag_fqn"),
                            rs.getString("reason"),
                            rs.getString("added_by"),
                            rs.getTimestamp("added_at").toInstant()))
                .list());
  }

  /** Attaches a tag, audits it and rebuilds the table's local facets, in one transaction. */
  public LocalTag add(String targetFqn, String tagFqn, String reason, String actor) {
    LocalTag added =
        jdbi.inTransaction(
            h -> {
              String asset =
                  assetOf(h, targetFqn)
                      .orElseThrow(
                          () ->
                              new Refused(
                                  Refused.Kind.NOT_FOUND,
                                  "No current table, view or column " + targetFqn));
              checkTag(h, targetFqn, tagFqn);
              Instant now = Instant.now();
              h.createUpdate(
                      """
                      INSERT INTO local_tag (target_fqn, asset_fqn, tag_fqn, reason, added_by, added_at)
                      VALUES (:target, :asset, :tag, :reason, :actor, :at)
                      """)
                  .bind("target", targetFqn)
                  .bind("asset", asset)
                  .bind("tag", tagFqn)
                  .bind("reason", reason)
                  .bind("actor", actor)
                  .bind("at", now)
                  .execute();
              audit(h, "ADD", actor, targetFqn, asset, tagFqn, reason);
              rederive(h, asset);
              return new LocalTag(targetFqn, asset, tagFqn, reason, actor, now);
            });
    notifier.fire("local tag " + tagFqn + " added to " + targetFqn);
    return added;
  }

  /**
   * Takes a tag off. Only one attached here: a tag from OpenMetadata is taken
   * off in OpenMetadata, or the next sync would put it back.
   */
  public LocalTag remove(String targetFqn, String tagFqn, String reason, String actor) {
    LocalTag removed =
        jdbi.inTransaction(
            h -> {
              Optional<String> asset =
                  h.createQuery(
                          """
                          DELETE FROM local_tag WHERE target_fqn = :target AND tag_fqn = :tag
                          RETURNING asset_fqn
                          """)
                      .bind("target", targetFqn)
                      .bind("tag", tagFqn)
                      .mapTo(String.class)
                      .findOne();
              if (asset.isEmpty()) {
                throw new Refused(
                    Refused.Kind.NOT_FOUND,
                    tagFqn + " was not attached to " + targetFqn + " in ARAK");
              }
              audit(h, "REMOVE", actor, targetFqn, asset.get(), tagFqn, reason);
              rederive(h, asset.get());
              return new LocalTag(targetFqn, asset.get(), tagFqn, reason, actor, Instant.now());
            });
    notifier.fire("local tag " + tagFqn + " removed from " + targetFqn);
    return removed;
  }

  // -------------------------------------------------------------- derivation

  /** Rebuilds the local facet rows of a table from what is attached, reading its current ids. */
  static void rederive(Handle h, String assetFqn) {
    Optional<UUID> assetId =
        h.createQuery("SELECT id FROM asset WHERE fqn = :fqn AND is_current")
            .bind("fqn", assetFqn)
            .mapTo(UUID.class)
            .findOne();
    if (assetId.isEmpty()) {
      return;
    }
    Map<String, UUID> columnIds = new HashMap<>();
    h.createQuery("SELECT fqn, id FROM asset_column WHERE asset_id = :id AND is_current")
        .bind("id", assetId.get())
        .map((rs, ctx) -> Map.entry(rs.getString("fqn"), (UUID) rs.getObject("id")))
        .forEach(e -> columnIds.put(e.getKey(), e.getValue()));
    rederive(h, assetFqn, assetId.get(), columnIds, Instant.now());
  }

  /**
   * Replaces the local facet rows of a table and its columns with those its
   * local tags imply. A tag whose target is no longer a current column stays
   * attached and produces nothing until the column comes back.
   */
  static void rederive(
      Handle h, String assetFqn, UUID assetId, Map<String, UUID> columnIds, Instant at) {
    List<String> targets = new ArrayList<>(columnIds.keySet());
    targets.add(assetFqn);
    h.createUpdate(
            "DELETE FROM asset_facet WHERE provenance = 'local' AND target_fqn IN (<targets>)")
        .bindList("targets", targets)
        .execute();

    List<Map.Entry<String, String>> tags =
        h.createQuery("SELECT target_fqn, tag_fqn FROM local_tag WHERE asset_fqn = :asset")
            .bind("asset", assetFqn)
            .map((rs, ctx) -> Map.entry(rs.getString("target_fqn"), rs.getString("tag_fqn")))
            .list();
    if (tags.isEmpty()) {
      return;
    }
    PreparedBatch batch =
        h.prepareBatch(
            """
            INSERT INTO asset_facet (asset_id, column_id, target_fqn, facet_type, facet_fqn,
                                     property, depth, is_direct, inherited_from, provenance,
                                     om_state, om_label_type, computed_at)
            VALUES (CAST(:assetId AS uuid), CAST(:columnId AS uuid), :targetFqn, :facetType,
                    :facetFqn, CAST(:property AS text), :depth, :isDirect,
                    CAST(:inheritedFrom AS text), 'local', CAST(:omState AS text),
                    CAST(:omLabelType AS text), :computedAt)
            """);
    int rows = 0;
    for (Map.Entry<String, String> tag : tags) {
      String target = tag.getKey();
      UUID columnId = columnIds.get(target);
      boolean onAsset = columnId == null && assetFqn.equals(target);
      if (columnId == null && !onAsset) {
        continue;
      }
      for (ExtractedFacet facet : facetsOf(target, tag.getValue())) {
        batch
            .bind("assetId", onAsset ? assetId : null)
            .bind("columnId", columnId)
            .bind("targetFqn", facet.targetFqn())
            .bind("facetType", facet.facetType().value())
            .bind("facetFqn", facet.facetFqn())
            .bind("property", facet.property())
            .bind("depth", facet.depth())
            .bind("isDirect", facet.direct())
            .bind("inheritedFrom", facet.inheritedFrom())
            .bind("omState", facet.omState())
            .bind("omLabelType", facet.omLabelType())
            .bind("computedAt", at)
            .add();
        rows++;
      }
    }
    if (rows > 0) {
      batch.execute();
    }
  }

  /** The facet rows one tag implies, expanded the way a crawled tag is. */
  static List<ExtractedFacet> facetsOf(String targetFqn, String tagFqn) {
    // Confirmed and manual: a person attached it on purpose, which is what a
    // confirmed label means in OpenMetadata too.
    TagLabel label =
        new TagLabel()
            .tagFQN(tagFqn)
            .source(TagLabel.SourceEnum.CLASSIFICATION)
            .labelType(TagLabel.LabelTypeEnum.MANUAL)
            .state(TagLabel.StateEnum.CONFIRMED);
    return FacetExtractor.fromTagLabels(targetFqn, List.of(label));
  }

  // -------------------------------------------------------------- plumbing

  private static Optional<String> assetOf(Handle h, String targetFqn) {
    if (targetFqn == null || targetFqn.isBlank()) {
      return Optional.empty();
    }
    Optional<String> table =
        h.createQuery(
                """
                SELECT fqn FROM asset
                 WHERE fqn = :fqn AND is_current AND asset_type IN ('TABLE', 'VIEW')
                """)
            .bind("fqn", targetFqn)
            .mapTo(String.class)
            .findOne();
    if (table.isPresent()) {
      return table;
    }
    return h.createQuery(
            """
            SELECT a.fqn FROM asset_column c JOIN asset a ON a.id = c.asset_id
             WHERE c.fqn = :fqn AND c.is_current AND a.is_current
            """)
        .bind("fqn", targetFqn)
        .mapTo(String.class)
        .findOne();
  }

  /** A known, enabled tag the target does not carry yet, and that its classification allows. */
  private static void checkTag(Handle h, String targetFqn, String tagFqn) {
    Optional<Map<String, Object>> tag =
        h.createQuery(
                """
                SELECT t.classification_fqn AS classification,
                       t.disabled OR COALESCE(c.disabled, false) AS disabled,
                       COALESCE(c.mutually_exclusive, false) AS exclusive
                  FROM tag t LEFT JOIN classification c ON c.fqn = t.classification_fqn
                 WHERE t.fqn = :tag
                """)
            .bind("tag", tagFqn)
            .mapToMap()
            .findOne();
    if (tag.isEmpty()) {
      throw new Refused(
          Refused.Kind.INVALID,
          "No tag " + tagFqn + " in the catalog; make it in OpenMetadata, or under Governance here");
    }
    if (Boolean.TRUE.equals(tag.get().get("disabled"))) {
      throw new Refused(Refused.Kind.INVALID, tagFqn + " is disabled and cannot be attached");
    }
    List<String> carried =
        h.createQuery(
                """
                SELECT DISTINCT facet_fqn FROM asset_facet
                 WHERE target_fqn = :target AND facet_type = 'tags' AND is_direct
                """)
            .bind("target", targetFqn)
            .mapTo(String.class)
            .list();
    for (String existing : carried) {
      if (Fqns.equal(existing, tagFqn)) {
        throw new Refused(Refused.Kind.CONFLICT, targetFqn + " already carries " + tagFqn);
      }
    }
    String classification = (String) tag.get().get("classification");
    if (Boolean.TRUE.equals(tag.get().get("exclusive")) && classification != null) {
      for (String existing : carried) {
        if (Fqns.isDescendantOrSelf(existing, classification)) {
          throw new Refused(
              Refused.Kind.CONFLICT,
              classification + " allows one tag per target, and " + targetFqn
                  + " already carries " + existing);
        }
      }
    }
  }

  private static void audit(
      Handle h, String action, String actor, String target, String asset, String tag,
      String reason) {
    h.createUpdate(
            """
            INSERT INTO audit_local_tag (actor, action, target_fqn, asset_fqn, tag_fqn, reason)
            VALUES (:actor, :action, :target, :asset, :tag, :reason)
            """)
        .bind("actor", actor)
        .bind("action", action)
        .bind("target", target)
        .bind("asset", asset)
        .bind("tag", tag)
        .bind("reason", reason)
        .execute();
  }
}
