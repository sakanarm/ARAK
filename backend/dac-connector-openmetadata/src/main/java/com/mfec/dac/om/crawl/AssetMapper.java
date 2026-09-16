package com.mfec.dac.om.crawl;

import com.mfec.dac.common.Fqns;
import com.mfec.dac.om.client.model.Column;
import com.mfec.dac.om.client.model.Database;
import com.mfec.dac.om.client.model.DatabaseSchema;
import com.mfec.dac.om.client.model.DatabaseService;
import com.mfec.dac.om.client.model.EntityReference;
import com.mfec.dac.om.client.model.Table;
import com.mfec.dac.om.facet.ExtractedFacet;
import com.mfec.dac.om.facet.FacetExtractor;
import com.mfec.dac.schema.entity.policy.FacetCondition.FacetType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns OpenMetadata's asset entities into the rows this platform stores.
 *
 * <p>Pure, like {@link GovernanceMapper}: no network, no database. What counts
 * as a view, which owner an asset really has, how a struct column's fields are
 * named — these are the details that decide whether a policy reaches a column,
 * and they are worth testing against a fixture rather than against whatever
 * happens to be in the catalogue this week.
 */
public final class AssetMapper {

  /** Table types that are already a view at the source (FR-6.1 cutover cares). */
  private static final Set<String> VIEW_TYPES =
      Set.of("View", "SecureView", "MaterializedView");

  private AssetMapper() {}

  public static CrawledAsset.AssetRow service(DatabaseService source) {
    if (source == null || blank(source.getFullyQualifiedName())) {
      return null;
    }
    return row(
        source.getId(),
        source.getFullyQualifiedName(),
        "SERVICE",
        source.getName(),
        source.getDisplayName(),
        source.getDescription(),
        source.getExtension(),
        FacetExtractor.fromParts(
            source.getFullyQualifiedName(),
            source.getTags(),
            source.getDomains(),
            source.getDataProducts(),
            source.getOwners(),
            source.getCertification(),
            source.getExtension()));
  }

  public static CrawledAsset.AssetRow database(Database source) {
    if (source == null || blank(source.getFullyQualifiedName())) {
      return null;
    }
    return row(
        source.getId(),
        source.getFullyQualifiedName(),
        "DATABASE",
        source.getName(),
        source.getDisplayName(),
        source.getDescription(),
        source.getExtension(),
        databaseFacets(source));
  }

  public static CrawledAsset.AssetRow schema(DatabaseSchema source) {
    if (source == null || blank(source.getFullyQualifiedName())) {
      return null;
    }
    return row(
        source.getId(),
        source.getFullyQualifiedName(),
        "SCHEMA",
        source.getName(),
        source.getDisplayName(),
        source.getDescription(),
        source.getExtension(),
        schemaFacets(source));
  }

  public static CrawledAsset.AssetRow table(Table source) {
    if (source == null || blank(source.getFullyQualifiedName())) {
      return null;
    }
    return row(
        source.getId(),
        source.getFullyQualifiedName(),
        tableType(source),
        source.getName(),
        source.getDisplayName(),
        source.getDescription(),
        source.getExtension(),
        FacetExtractor.fromTable(source));
  }

  public static List<ExtractedFacet> serviceFacets(DatabaseService source) {
    return source == null
        ? List.of()
        : FacetExtractor.fromParts(
            source.getFullyQualifiedName(),
            source.getTags(),
            source.getDomains(),
            source.getDataProducts(),
            source.getOwners(),
            source.getCertification(),
            source.getExtension());
  }

  public static List<ExtractedFacet> databaseFacets(Database source) {
    return source == null
        ? List.of()
        : FacetExtractor.fromParts(
            source.getFullyQualifiedName(),
            source.getTags(),
            source.getDomains(),
            source.getDataProducts(),
            source.getOwners(),
            source.getCertification(),
            source.getExtension());
  }

  public static List<ExtractedFacet> schemaFacets(DatabaseSchema source) {
    return source == null
        ? List.of()
        : FacetExtractor.fromParts(
            source.getFullyQualifiedName(),
            source.getTags(),
            source.getDomains(),
            source.getDataProducts(),
            source.getOwners(),
            source.getCertification(),
            source.getExtension());
  }

  /**
   * Every column of a table, with nested fields flattened alongside their parent.
   *
   * <p>A struct's fields carry their own tags in OpenMetadata, and a policy has
   * to be able to mask {@code address.postcode} without masking the whole
   * struct. They are stored as columns in their own right — the FQN already
   * distinguishes them — and the ordinal is only meaningful within a level, so
   * nested fields keep whatever position OpenMetadata gave them.
   */
  public static List<CrawledAsset.ColumnRow> columns(Table source) {
    List<CrawledAsset.ColumnRow> out = new ArrayList<>();
    if (source != null) {
      collectColumns(source.getColumns(), out);
    }
    return out;
  }

  /** The facets of a table's columns, before inheritance from the table itself. */
  public static List<ExtractedFacet> columnFacets(Table source) {
    List<ExtractedFacet> out = new ArrayList<>();
    if (source != null) {
      collectColumnFacets(source.getColumns(), out);
    }
    return out;
  }

  private static void collectColumns(List<Column> columns, List<CrawledAsset.ColumnRow> out) {
    if (columns == null) {
      return;
    }
    for (Column column : columns) {
      if (column == null || blank(column.getFullyQualifiedName())) {
        continue;
      }
      out.add(
          new CrawledAsset.ColumnRow(
              column.getFullyQualifiedName(),
              column.getName(),
              column.getOrdinalPosition(),
              column.getDataType() == null ? null : column.getDataType().getValue(),
              column.getDataLength(),
              nullable(column),
              column.getDescription(),
              extensionOf(column.getExtension())));
      collectColumns(column.getChildren(), out);
    }
  }

  private static void collectColumnFacets(List<Column> columns, List<ExtractedFacet> out) {
    if (columns == null) {
      return;
    }
    for (Column column : columns) {
      if (column == null || blank(column.getFullyQualifiedName())) {
        continue;
      }
      out.addAll(FacetExtractor.fromColumn(column));
      collectColumnFacets(column.getChildren(), out);
    }
  }

  /**
   * Whether the column accepts nulls, or null when OpenMetadata does not say.
   *
   * <p>"No constraint recorded" and "nullable" are different claims, and the
   * difference matters: a masking function that substitutes null cannot be used
   * on a column the source rejects nulls for, and guessing would turn that into
   * a failure at apply time.
   */
  private static Boolean nullable(Column column) {
    Column.ConstraintEnum constraint = column.getConstraint();
    if (constraint == null) {
      return null;
    }
    return switch (constraint) {
      case NOT_NULL, PRIMARY_KEY -> Boolean.FALSE;
      case NULL -> Boolean.TRUE;
      default -> null;
    };
  }

  private static CrawledAsset.AssetRow row(
      UUID omId,
      String fqn,
      String assetType,
      String name,
      String displayName,
      String description,
      Object extension,
      List<ExtractedFacet> ownFacets) {
    return new CrawledAsset.AssetRow(
        omId,
        fqn,
        assetType,
        parentOf(fqn),
        name == null ? Fqns.leaf(fqn) : name,
        displayName,
        description,
        firstOf(ownFacets, FacetType.TIER),
        firstOf(ownFacets, FacetType.CERTIFICATION),
        extensionOf(extension));
  }

  /**
   * Owners of one asset, each as a row.
   *
   * <p>Taken from the entity references rather than from the {@code OWNERS}
   * facets, because the facet keeps only the name and the {@code asset_owner}
   * table needs the type: a team called {@code Finance} and a user called
   * {@code Finance} are not the same principal.
   */
  public static List<CrawledAsset.OwnerRow> owners(String targetFqn, List<EntityReference> refs) {
    if (refs == null || refs.isEmpty() || blank(targetFqn)) {
      return List.of();
    }
    List<CrawledAsset.OwnerRow> out = new ArrayList<>(refs.size());
    for (EntityReference ref : refs) {
      if (ref == null) {
        continue;
      }
      String name = nameOf(ref);
      if (name == null) {
        continue;
      }
      // OpenMetadata already propagates owners downwards and marks what it
      // propagated. We work out inheritance ourselves so it can be explained, so
      // such a reference is recorded as inherited rather than as set here.
      boolean inherited = Boolean.TRUE.equals(ref.getInherited());
      out.add(
          new CrawledAsset.OwnerRow(
              targetFqn,
              "team".equalsIgnoreCase(ref.getType()) ? "team" : "user",
              name,
              ref.getId(),
              !inherited,
              null));
    }
    return out;
  }

  /**
   * An asset's owners together with the owners of everything that encloses it.
   *
   * <p>Ownership adds rather than replaces. A schema's owner is answerable for
   * the tables in it — that is what makes them the person who may write a local
   * policy there (FR-3.1.2) and the person {@code assetOwner: true} means when
   * a table has named nobody. Each row says which level it came from, so the UI
   * can still show the table's own owner as the direct one.
   *
   * @param ancestors enclosing levels, nearest first
   */
  public static List<CrawledAsset.OwnerRow> effectiveOwners(
      String targetFqn, List<CrawledAsset.OwnerRow> own, List<OwnerLevel> ancestors) {

    List<CrawledAsset.OwnerRow> out = new ArrayList<>(own == null ? List.of() : own);
    Set<String> claimed = new LinkedHashSet<>();
    for (CrawledAsset.OwnerRow row : out) {
      claimed.add(row.ownerType() + '\0' + row.ownerName());
    }
    if (ancestors != null) {
      for (OwnerLevel level : ancestors) {
        for (CrawledAsset.OwnerRow row : level.owners()) {
          if (claimed.add(row.ownerType() + '\0' + row.ownerName())) {
            out.add(
                new CrawledAsset.OwnerRow(
                    targetFqn,
                    row.ownerType(),
                    row.ownerName(),
                    row.ownerOmId(),
                    false,
                    level.fqn()));
          }
        }
      }
    }
    return out;
  }

  /** One enclosing level's own owners. */
  public record OwnerLevel(String fqn, List<CrawledAsset.OwnerRow> owners) {
    public OwnerLevel {
      owners = owners == null ? List.of() : List.copyOf(owners);
    }
  }

  /**
   * The physical object a table FQN points at.
   *
   * <p>OpenMetadata names a table {@code service.database.schema.table}. The
   * mapping is recorded rather than recomputed at apply time, because it is what
   * the introspector re-verifies against the source before any DDL is generated
   * — the catalogue is allowed to lag, the DDL is not (FR-1.6).
   *
   * @return null when the FQN does not have the four segments a table must have
   */
  public static Physical physicalOf(String tableFqn, String assetType) {
    List<String> segments = Fqns.segments(tableFqn);
    if (segments.size() != 4) {
      return null;
    }
    return new Physical(
        segments.get(0),
        segments.get(1),
        segments.get(2),
        segments.get(3),
        "VIEW".equals(assetType) ? "VIEW" : "TABLE");
  }

  /** A table's location at the source, as {@code asset_fqn_map} records it. */
  public record Physical(
      String serviceName,
      String databaseName,
      String schemaName,
      String objectName,
      String objectKind) {}

  private static String tableType(Table source) {
    String type = source.getTableType() == null ? null : source.getTableType().getValue();
    return type != null && VIEW_TYPES.contains(type) ? "VIEW" : "TABLE";
  }

  /** The nearest enclosing FQN, or null at the top. Quote-aware through {@link Fqns}. */
  static String parentOf(String fqn) {
    List<String> segments = Fqns.segments(fqn);
    if (segments.size() < 2) {
      return null;
    }
    return Fqns.join(segments.subList(0, segments.size() - 1).toArray(new String[0]));
  }

  private static String firstOf(List<ExtractedFacet> facets, FacetType type) {
    for (ExtractedFacet facet : facets) {
      if (facet.facetType() == type && facet.direct()) {
        return facet.facetFqn();
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> extensionOf(Object extension) {
    if (extension instanceof Map<?, ?> map && !map.isEmpty()) {
      return Map.copyOf((Map<String, Object>) map);
    }
    return Map.of();
  }

  private static String nameOf(EntityReference ref) {
    String fqn = ref.getFullyQualifiedName();
    if (!blank(fqn)) {
      return fqn;
    }
    return blank(ref.getName()) ? null : ref.getName();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
