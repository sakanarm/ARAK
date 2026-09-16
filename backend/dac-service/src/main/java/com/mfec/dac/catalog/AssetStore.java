package com.mfec.dac.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.common.Fqns;
import com.mfec.dac.om.crawl.AssetCrawler;
import com.mfec.dac.om.crawl.AssetMapper;
import com.mfec.dac.om.crawl.AssetSink;
import com.mfec.dac.om.crawl.CrawledAsset;
import com.mfec.dac.om.facet.ExtractedFacet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes one crawl into the metadata cache (FR-1.4).
 *
 * <p>The adapter behind {@link AssetSink}: the crawler decides what a facet
 * means, this decides how it is stored. Assets and columns are versioned (SCD2)
 * so an audit can ask what the catalog said on the day a decision was made;
 * facets and owners are not, because they are derived — they are recomputed
 * whole on every crawl and their history is the history of the asset versions
 * they were computed from.
 *
 * <p><b>One store per crawl.</b> Construction stamps the generation marker that
 * {@link #finished} uses to tell an asset OpenMetadata has stopped mentioning
 * from one it simply has not reached yet. Reusing a store across two crawls
 * would make the second one retire nothing.
 *
 * <p>Each asset is written in its own transaction. A crawl that fails half way
 * leaves the assets it did reach fully written and the rest untouched, which is
 * a stale cache — a state the system can detect. The alternative, one
 * transaction per crawl, holds a lock on the whole catalog for thirty minutes.
 */
public class AssetStore implements AssetSink {

  private static final Logger LOG = LoggerFactory.getLogger(AssetStore.class);

  private final Jdbi jdbi;
  private final ObjectMapper json;
  private final Instant seenAt;

  /** Resolved data sources, including the misses — most services have none. */
  private final Map<String, Optional<UUID>> dataSources = new HashMap<>();

  private int unchanged;
  private int revised;
  private int retired;

  public AssetStore(Jdbi jdbi, ObjectMapper json) {
    this(jdbi, json, Instant.now());
  }

  public AssetStore(Jdbi jdbi, ObjectMapper json, Instant seenAt) {
    this.jdbi = jdbi;
    this.json = json;
    this.seenAt = seenAt;
  }

  /** Assets whose stored version already matched what OpenMetadata now says. */
  public int unchanged() {
    return unchanged;
  }

  /** Assets that gained a new version, new ones included. */
  public int revised() {
    return revised;
  }

  /** Assets closed by the sweep because the crawl never mentioned them. */
  public int retired() {
    return retired;
  }

  @Override
  public void asset(CrawledAsset asset) {
    CrawledAsset.AssetRow row = asset.asset();
    jdbi.useTransaction(
        handle -> {
          UUID dataSourceId = dataSourceFor(handle, row.fqn());
          UUID assetId = upsertAsset(handle, row, dataSourceId);
          Map<String, UUID> columnIds = upsertColumns(handle, assetId, asset.columns());
          replaceFacets(handle, row.fqn(), assetId, columnIds, asset.facets());
          replaceOwners(handle, row.fqn(), asset.owners());
          mapPhysical(handle, row, dataSourceId);
        });
  }

  /**
   * Closes everything this crawl did not mention.
   *
   * <p>Called only on a crawl that completed, which is the whole reason the
   * sweep is safe: a partial crawl reaching this point would retire every asset
   * it had not got to yet and take the catalog offline.
   *
   * <p>A crawl that found nothing at all still does not sweep. An OpenMetadata
   * answering an empty list — wrong token, wrong scope, a service filter that
   * matched nothing — is indistinguishable at this layer from an organisation
   * that deleted every table, and only one of those is likely.
   *
   * <p>The test is what this store actually wrote, not what the crawl says it
   * read. They normally agree, and where they do not it is because assets were
   * counted but never handed over — which leaves nothing stamped, so sweeping
   * on the strength of the count would retire the entire cache.
   */
  @Override
  public void finished(AssetCrawler.Stats stats) {
    if (unchanged == 0 && revised == 0) {
      LOG.warn(
          "Crawl wrote no assets at all ({}); skipping retirement sweep to protect the cache",
          stats);
      return;
    }
    jdbi.useTransaction(
        handle -> {
          // Derived rows first: once the asset row stops being current the
          // predicate no longer finds it, and the facets would be orphaned
          // where a selector could still read them.
          handle
              .createUpdate(
                  """
                  DELETE FROM asset_facet WHERE column_id IN (
                      SELECT c.id FROM asset_column c
                      WHERE c.is_current AND (c.last_seen_at IS NULL OR c.last_seen_at < :seen))
                  """)
              .bind("seen", seenAt)
              .execute();
          handle
              .createUpdate(
                  """
                  DELETE FROM asset_facet WHERE asset_id IN (
                      SELECT a.id FROM asset a
                      WHERE a.is_current AND (a.last_seen_at IS NULL OR a.last_seen_at < :seen))
                  """)
              .bind("seen", seenAt)
              .execute();
          handle
              .createUpdate(
                  """
                  DELETE FROM asset_owner WHERE target_fqn IN (
                      SELECT a.fqn FROM asset a
                      WHERE a.is_current AND (a.last_seen_at IS NULL OR a.last_seen_at < :seen))
                  """)
              .bind("seen", seenAt)
              .execute();
          handle
              .createUpdate(
                  """
                  DELETE FROM asset_fqn_map WHERE om_fqn IN (
                      SELECT a.fqn FROM asset a
                      WHERE a.is_current AND (a.last_seen_at IS NULL OR a.last_seen_at < :seen))
                  """)
              .bind("seen", seenAt)
              .execute();
          handle
              .createUpdate(
                  """
                  UPDATE asset_column SET is_current = false, valid_to = :seen
                  WHERE is_current AND (last_seen_at IS NULL OR last_seen_at < :seen)
                  """)
              .bind("seen", seenAt)
              .execute();
          retired =
              handle
                  .createUpdate(
                      """
                      UPDATE asset SET is_current = false, valid_to = :seen
                      WHERE is_current AND (last_seen_at IS NULL OR last_seen_at < :seen)
                      """)
                  .bind("seen", seenAt)
                  .execute();
        });
    LOG.info(
        "Crawl stored: {} assets unchanged, {} revised, {} retired", unchanged, revised, retired);
  }

  // ---------------------------------------------------------------- assets

  private UUID upsertAsset(Handle handle, CrawledAsset.AssetRow row, UUID dataSourceId) {
    String props = jsonOf(row.customProperties());

    // The common case by a long way: nothing about this asset moved since the
    // last crawl. Stamping it as seen in the same statement that checks keeps
    // that case to one round trip, and writing a new version for an asset that
    // did not change would fill the history with noise an auditor has to read
    // past.
    Optional<UUID> same =
        handle
            .createQuery(
                """
                UPDATE asset SET last_seen_at = :seen
                WHERE fqn = :fqn AND is_current
                  AND data_source_id IS NOT DISTINCT FROM CAST(:dataSourceId AS uuid)
                  AND om_id IS NOT DISTINCT FROM CAST(:omId AS uuid)
                  AND asset_type = :assetType
                  AND parent_fqn IS NOT DISTINCT FROM CAST(:parentFqn AS text)
                  AND name = :name
                  AND display_name IS NOT DISTINCT FROM CAST(:displayName AS text)
                  AND description IS NOT DISTINCT FROM CAST(:description AS text)
                  AND tier IS NOT DISTINCT FROM CAST(:tier AS text)
                  AND certification IS NOT DISTINCT FROM CAST(:certification AS text)
                  AND custom_properties = CAST(:props AS jsonb)
                RETURNING id
                """)
            .bind("seen", seenAt)
            .bind("fqn", row.fqn())
            .bind("dataSourceId", dataSourceId)
            .bind("omId", row.omId())
            .bind("assetType", row.assetType())
            .bind("parentFqn", row.parentFqn())
            .bind("name", row.name())
            .bind("displayName", row.displayName())
            .bind("description", row.description())
            .bind("tier", row.tier())
            .bind("certification", row.certification())
            .bind("props", props)
            .mapTo(UUID.class)
            .findOne();
    if (same.isPresent()) {
      unchanged++;
      return same.get();
    }

    handle
        .createUpdate(
            "UPDATE asset SET valid_to = :seen, is_current = false WHERE fqn = :fqn AND is_current")
        .bind("seen", seenAt)
        .bind("fqn", row.fqn())
        .execute();

    UUID id =
        handle
            .createQuery(
                """
                INSERT INTO asset (data_source_id, om_id, fqn, asset_type, parent_fqn, name,
                                   display_name, description, tier, certification,
                                   custom_properties, valid_from, last_seen_at)
                VALUES (CAST(:dataSourceId AS uuid), CAST(:omId AS uuid), :fqn, :assetType,
                        :parentFqn, :name, :displayName, :description, :tier, :certification,
                        CAST(:props AS jsonb), :seen, :seen)
                RETURNING id
                """)
            .bind("seen", seenAt)
            .bind("fqn", row.fqn())
            .bind("dataSourceId", dataSourceId)
            .bind("omId", row.omId())
            .bind("assetType", row.assetType())
            .bind("parentFqn", row.parentFqn())
            .bind("name", row.name())
            .bind("displayName", row.displayName())
            .bind("description", row.description())
            .bind("tier", row.tier())
            .bind("certification", row.certification())
            .bind("props", props)
            .mapTo(UUID.class)
            .one();
    revised++;
    return id;
  }

  // --------------------------------------------------------------- columns

  private Map<String, UUID> upsertColumns(
      Handle handle, UUID assetId, List<CrawledAsset.ColumnRow> columns) {

    Map<String, UUID> ids = new LinkedHashMap<>();
    for (CrawledAsset.ColumnRow column : columns) {
      ids.put(column.fqn(), upsertColumn(handle, assetId, column));
    }
    return ids;
  }

  private UUID upsertColumn(Handle handle, UUID assetId, CrawledAsset.ColumnRow column) {
    String props = jsonOf(column.customProperties());

    // asset_id is reassigned even when nothing else moved: a column whose table
    // gained a new version has not itself changed, and its own history should
    // not restart because the table's description did.
    Optional<UUID> same =
        handle
            .createQuery(
                """
                UPDATE asset_column SET last_seen_at = :seen, asset_id = :assetId
                WHERE fqn = :fqn AND is_current
                  AND name = :name
                  AND ordinal IS NOT DISTINCT FROM CAST(:ordinal AS integer)
                  AND data_type IS NOT DISTINCT FROM CAST(:dataType AS text)
                  AND data_length IS NOT DISTINCT FROM CAST(:dataLength AS integer)
                  AND nullable IS NOT DISTINCT FROM CAST(:nullable AS boolean)
                  AND description IS NOT DISTINCT FROM CAST(:description AS text)
                  AND custom_properties = CAST(:props AS jsonb)
                RETURNING id
                """)
            .bind("seen", seenAt)
            .bind("assetId", assetId)
            .bind("fqn", column.fqn())
            .bind("name", column.name())
            .bind("ordinal", column.ordinal())
            .bind("dataType", column.dataType())
            .bind("dataLength", column.dataLength())
            .bind("nullable", column.nullable())
            .bind("description", column.description())
            .bind("props", props)
            .mapTo(UUID.class)
            .findOne();
    if (same.isPresent()) {
      return same.get();
    }

    handle
        .createUpdate(
            """
            UPDATE asset_column SET valid_to = :seen, is_current = false
            WHERE fqn = :fqn AND is_current
            """)
        .bind("seen", seenAt)
        .bind("fqn", column.fqn())
        .execute();

    return handle
        .createQuery(
            """
            INSERT INTO asset_column (asset_id, fqn, name, ordinal, data_type, data_length,
                                      nullable, description, custom_properties,
                                      valid_from, last_seen_at)
            VALUES (:assetId, :fqn, :name, CAST(:ordinal AS integer), :dataType,
                    CAST(:dataLength AS integer), CAST(:nullable AS boolean), :description,
                    CAST(:props AS jsonb), :seen, :seen)
            RETURNING id
            """)
        .bind("seen", seenAt)
        .bind("assetId", assetId)
        .bind("fqn", column.fqn())
        .bind("name", column.name())
        .bind("ordinal", column.ordinal())
        .bind("dataType", column.dataType())
        .bind("dataLength", column.dataLength())
        .bind("nullable", column.nullable())
        .bind("description", column.description())
        .bind("props", props)
        .mapTo(UUID.class)
        .one();
  }

  // ---------------------------------------------------------------- facets

  /**
   * Replaces every facet row for this asset and its columns.
   *
   * <p>Delete then insert rather than merge. A facet that disappeared from
   * OpenMetadata has to disappear here too, and the row that says a column is
   * {@code PII.Sensitive} is exactly the row a merge would leave behind.
   */
  private void replaceFacets(
      Handle handle,
      String assetFqn,
      UUID assetId,
      Map<String, UUID> columnIds,
      List<ExtractedFacet> facets) {

    List<String> targets = new ArrayList<>(columnIds.keySet());
    targets.add(assetFqn);
    handle
        .createUpdate("DELETE FROM asset_facet WHERE target_fqn IN (<targets>)")
        .bindList("targets", targets)
        .execute();

    if (facets.isEmpty()) {
      return;
    }
    PreparedBatch batch =
        handle.prepareBatch(
            """
            INSERT INTO asset_facet (asset_id, column_id, target_fqn, facet_type, facet_fqn,
                                     property, depth, is_direct, inherited_from,
                                     om_state, om_label_type, computed_at)
            VALUES (CAST(:assetId AS uuid), CAST(:columnId AS uuid), :targetFqn, :facetType,
                    :facetFqn, CAST(:property AS text), :depth, :isDirect,
                    CAST(:inheritedFrom AS text), CAST(:omState AS text),
                    CAST(:omLabelType AS text), :computedAt)
            """);
    int rows = 0;
    for (ExtractedFacet facet : facets) {
      UUID columnId = columnIds.get(facet.targetFqn());
      boolean onAsset = columnId == null && assetFqn.equals(facet.targetFqn());
      if (columnId == null && !onAsset) {
        // The crawler only ever produces facets for the asset it is emitting or
        // for that asset's own columns. Anything else is a bug there, not data.
        LOG.warn("Dropping facet for unknown target {} while writing {}",
            facet.targetFqn(), assetFqn);
        continue;
      }
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
          .bind("computedAt", seenAt)
          .add();
      rows++;
    }
    if (rows > 0) {
      batch.execute();
    }
  }

  // ---------------------------------------------------------------- owners

  private void replaceOwners(Handle handle, String assetFqn, List<CrawledAsset.OwnerRow> owners) {
    handle
        .createUpdate("DELETE FROM asset_owner WHERE target_fqn = :fqn")
        .bind("fqn", assetFqn)
        .execute();
    if (owners.isEmpty()) {
      return;
    }
    PreparedBatch batch =
        handle.prepareBatch(
            """
            INSERT INTO asset_owner (target_fqn, owner_type, owner_name, owner_om_id,
                                     is_direct, inherited_from, updated_at)
            VALUES (:targetFqn, :ownerType, :ownerName, CAST(:ownerOmId AS uuid),
                    :isDirect, CAST(:inheritedFrom AS text), :updatedAt)
            """);
    for (CrawledAsset.OwnerRow owner : owners) {
      batch
          .bind("targetFqn", owner.targetFqn())
          .bind("ownerType", owner.ownerType())
          .bind("ownerName", owner.ownerName())
          .bind("ownerOmId", owner.ownerOmId())
          .bind("isDirect", owner.direct())
          .bind("inheritedFrom", owner.inheritedFrom())
          .bind("updatedAt", seenAt)
          .add();
    }
    batch.execute();
  }

  // -------------------------------------------------------- physical mapping

  /**
   * Records where a table actually lives, when we know (FR-1.6).
   *
   * <p>Only for assets that will carry DDL, and only once a data source has been
   * registered for the service: without a connection there is nothing to verify
   * the mapping against, and a row claiming a physical object nobody can reach
   * is a claim the drift detector would have to treat as real.
   */
  private void mapPhysical(Handle handle, CrawledAsset.AssetRow row, UUID dataSourceId) {
    if (dataSourceId == null) {
      return;
    }
    AssetMapper.Physical physical = AssetMapper.physicalOf(row.fqn(), row.assetType());
    if (physical == null) {
      return;
    }
    // A rename in OpenMetadata gives the same physical object a second FQN. The
    // physical tuple is the one that has to stay unique, so the older claim to
    // it goes.
    handle
        .createUpdate(
            """
            DELETE FROM asset_fqn_map
            WHERE data_source_id = :dataSourceId AND database_name = :databaseName
              AND schema_name = :schemaName AND object_name = :objectName
              AND om_fqn <> :omFqn
            """)
        .bind("dataSourceId", dataSourceId)
        .bind("databaseName", physical.databaseName())
        .bind("schemaName", physical.schemaName())
        .bind("objectName", physical.objectName())
        .bind("omFqn", row.fqn())
        .execute();

    handle
        .createUpdate(
            """
            INSERT INTO asset_fqn_map (om_fqn, om_id, data_source_id, database_name,
                                       schema_name, object_name, object_kind)
            VALUES (:omFqn, CAST(:omId AS uuid), :dataSourceId, :databaseName,
                    :schemaName, :objectName, :objectKind)
            ON CONFLICT (om_fqn) DO UPDATE SET
                om_id = EXCLUDED.om_id,
                data_source_id = EXCLUDED.data_source_id,
                database_name = EXCLUDED.database_name,
                schema_name = EXCLUDED.schema_name,
                object_name = EXCLUDED.object_name,
                object_kind = EXCLUDED.object_kind,
                -- A mapping that now points somewhere else has never been
                -- verified against that object, whatever it said before.
                verification_status = CASE
                    WHEN asset_fqn_map.database_name = EXCLUDED.database_name
                     AND asset_fqn_map.schema_name = EXCLUDED.schema_name
                     AND asset_fqn_map.object_name = EXCLUDED.object_name
                    THEN asset_fqn_map.verification_status ELSE 'UNVERIFIED' END,
                last_verified_at = CASE
                    WHEN asset_fqn_map.database_name = EXCLUDED.database_name
                     AND asset_fqn_map.schema_name = EXCLUDED.schema_name
                     AND asset_fqn_map.object_name = EXCLUDED.object_name
                    THEN asset_fqn_map.last_verified_at ELSE NULL END
            """)
        .bind("omFqn", row.fqn())
        .bind("omId", row.omId())
        .bind("dataSourceId", dataSourceId)
        .bind("databaseName", physical.databaseName())
        .bind("schemaName", physical.schemaName())
        .bind("objectName", physical.objectName())
        .bind("objectKind", physical.objectKind())
        .execute();
  }

  /** The data source registered for this asset's service, if there is one. */
  private UUID dataSourceFor(Handle handle, String fqn) {
    List<String> segments = Fqns.segments(fqn);
    if (segments.isEmpty()) {
      return null;
    }
    String serviceFqn = segments.get(0);
    return dataSources
        .computeIfAbsent(
            serviceFqn,
            key ->
                handle
                    .createQuery(
                        "SELECT id FROM data_source WHERE om_service_fqn = :fqn AND enabled")
                    .bind("fqn", key)
                    .mapTo(UUID.class)
                    .findOne())
        .orElse(null);
  }

  private String jsonOf(Map<String, Object> values) {
    if (values == null || values.isEmpty()) {
      return "{}";
    }
    try {
      return json.writeValueAsString(values);
    } catch (JsonProcessingException e) {
      // Nothing here is worth failing a crawl over, but silently storing {} would
      // make an ABAC rule on a custom property quietly stop matching.
      throw new IllegalStateException("Cannot serialise custom properties: " + values.keySet(), e);
    }
  }
}
