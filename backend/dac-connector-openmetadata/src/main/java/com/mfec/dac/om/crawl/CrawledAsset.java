package com.mfec.dac.om.crawl;

import com.mfec.dac.om.facet.ExtractedFacet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One asset as the crawl saw it, with everything needed to write it down.
 *
 * <p>Emitted one at a time rather than collected into a snapshot. A catalogue of
 * a hundred thousand tables, each with its columns and its expanded facet rows,
 * does not belong in a single list held in memory while the crawl is still
 * running (NFR-2); the sink decides how much to batch.
 *
 * <p>The facets here are already <em>effective</em> — the asset's own, plus what
 * it inherits from its service, database and schema, plus every ancestor a
 * hierarchical value implies. Each row carries where it came from, so the answer
 * to "why is this column PII?" is a lookup, not a re-derivation (FR-2A.1).
 *
 * @param asset   the row destined for {@code asset}
 * @param columns rows destined for {@code asset_column}; empty for containers
 * @param facets  rows destined for {@code asset_facet}, covering the asset and
 *                each of its columns
 * @param owners  rows destined for {@code asset_owner}
 */
public record CrawledAsset(
    AssetRow asset,
    List<ColumnRow> columns,
    List<ExtractedFacet> facets,
    List<OwnerRow> owners) {

  public CrawledAsset {
    columns = columns == null ? List.of() : List.copyOf(columns);
    facets = facets == null ? List.of() : List.copyOf(facets);
    owners = owners == null ? List.of() : List.copyOf(owners);
  }

  /**
   * A service, database, schema, table or view.
   *
   * @param assetType        SERVICE, DATABASE, SCHEMA, TABLE or VIEW — the set
   *                         the {@code asset} table's check constraint allows
   * @param tier             the leaf of the {@code Tier} tag, denormalised onto
   *                         the asset because every list screen shows it
   * @param certification    likewise, the leaf of the certification tag
   * @param customProperties the raw {@code extension} object, kept whole so a
   *                         property added in OpenMetadata is readable here
   *                         before anyone has taught the policy builder about it
   */
  public record AssetRow(
      UUID omId,
      String fqn,
      String assetType,
      String parentFqn,
      String name,
      String displayName,
      String description,
      String tier,
      String certification,
      Map<String, Object> customProperties) {

    public AssetRow {
      customProperties = customProperties == null ? Map.of() : Map.copyOf(customProperties);
    }
  }

  /**
   * One column.
   *
   * @param dataType the OpenMetadata type name (VARCHAR, BIGINT, …). The masking
   *                 library needs it to reject a mask a column cannot carry —
   *                 {@code PARTIAL} on an integer, say — before the DDL is
   *                 generated rather than when the source refuses it.
   * @param nullable null when OpenMetadata states no constraint, which is not
   *                 the same as stating that the column is nullable
   */
  public record ColumnRow(
      String fqn,
      String name,
      Integer ordinal,
      String dataType,
      Integer dataLength,
      Boolean nullable,
      String description,
      Map<String, Object> customProperties) {

    public ColumnRow {
      customProperties = customProperties == null ? Map.of() : Map.copyOf(customProperties);
    }
  }

  /**
   * An owner of an asset, as {@code assetOwner: true} resolves it (FR-3.2a) and
   * as local policy authority is decided (FR-3.1.2).
   *
   * @param ownerType     {@code user} or {@code team}
   * @param direct        false when the ownership comes from an enclosing level
   * @param inheritedFrom the FQN it came down from; null when set here
   */
  public record OwnerRow(
      String targetFqn,
      String ownerType,
      String ownerName,
      UUID ownerOmId,
      boolean direct,
      String inheritedFrom) {}
}
