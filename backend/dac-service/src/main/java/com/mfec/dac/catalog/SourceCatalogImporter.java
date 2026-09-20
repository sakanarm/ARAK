package com.mfec.dac.catalog;

import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.JdbcIntrospector;
import com.mfec.dac.source.jdbc.SourceProbe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes what the database actually has into the catalog (FR-1.6).
 *
 * <p>Two jobs, and they are the same job. It is how a source that OpenMetadata
 * has never ingested still gets assets to write policies about, and it is the
 * live verification that has to happen before any DDL is generated, because the
 * catalog lags DDL and a column the cache has not heard of is exactly the
 * column no mask covers.
 *
 * <h2>Provenance is a boundary, not a label</h2>
 *
 * <p>Everything born here is {@code provenance = 'discovered'}. The
 * OpenMetadata sync owns {@code openmetadata} rows and will not overwrite
 * these; equally, this will not overwrite a row the crawl owns — it only stamps
 * it as still present. Where both know a table, OpenMetadata wins on
 * description, tags and ownership because it holds the governance, while this
 * decides only whether the columns are really there. Mixing those two
 * authorities is how a catalog starts disagreeing with itself.
 */
public class SourceCatalogImporter {

  private static final Logger LOG = LoggerFactory.getLogger(SourceCatalogImporter.class);

  /**
   * @param newColumns columns that exist at the source but were not in the
   *     cache before this run. Reported rather than counted, because a new
   *     column on a governed table is unprotected until a policy covers it —
   *     that is a warning, not a statistic (plan section 6, step 16).
   */
  public record Report(
      String source,
      int tables,
      int columns,
      int newTables,
      List<String> newColumns,
      List<String> missingTables) {}

  /** Thrown when the source cannot be read; never leaves a partial import. */
  public static class IntrospectionFailedException extends RuntimeException {
    public IntrospectionFailedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final Jdbi jdbi;
  private final DataSourceStore sources;
  private final JdbcIntrospector introspector;

  public SourceCatalogImporter(
      Jdbi jdbi, DataSourceStore sources, JdbcIntrospector introspector) {
    this.jdbi = jdbi;
    this.sources = sources;
    this.introspector = introspector;
  }

  /**
   * @param schemaFilter one schema, or null for every non-system schema
   */
  public Report importFrom(UUID sourceId, String schemaFilter) {
    DataSourceStore.Source source =
        sources
            .find(sourceId)
            .orElseThrow(() -> new IllegalArgumentException("No source " + sourceId));

    String database =
        source.defaultDatabase() == null || source.defaultDatabase().isBlank()
            ? source.name()
            : source.defaultDatabase();

    List<JdbcIntrospector.Table> tables;
    try {
      tables =
          introspector.tables(
              new SourceProbe.Target(
                  source.engine().name(), source.host(), source.port(), source.defaultDatabase()),
              source.credentialRef(),
              schemaFilter);
    } catch (Exception e) {
      throw new IntrospectionFailedException(
          "Could not read the catalog of " + source.name() + ": " + e.getMessage(), e);
    }

    Instant seen = Instant.now();
    return jdbi.inTransaction(
        handle -> {
          List<String> newColumns = new ArrayList<>();
          List<String> seenTables = new ArrayList<>();
          int newTables = 0;
          int columns = 0;

          // The service and database rows exist so a policy can be scoped at
          // SERVICE or DATABASE level against a source nobody has ingested.
          upsert(handle, source.id(), source.name(), "SERVICE", null, source.name(), seen);
          String databaseFqn = source.name() + "." + database;
          upsert(handle, source.id(), databaseFqn, "DATABASE", source.name(), database, seen);

          for (JdbcIntrospector.Table table : tables) {
            String schemaFqn = databaseFqn + "." + table.schema();
            upsert(handle, source.id(), schemaFqn, "SCHEMA", databaseFqn, table.schema(), seen);

            String tableFqn = schemaFqn + "." + table.name();
            boolean existed = exists(handle, tableFqn);
            UUID assetId =
                upsert(
                    handle,
                    source.id(),
                    tableFqn,
                    "VIEW".equals(table.kind()) ? "VIEW" : "TABLE",
                    schemaFqn,
                    table.name(),
                    seen);
            if (!existed) {
              newTables++;
            }
            seenTables.add(tableFqn);
            map(handle, source.id(), tableFqn, database, table, seen);

            for (JdbcIntrospector.Column column : table.columns()) {
              String columnFqn = tableFqn + "." + column.name();
              // Only worth reporting on a table we already knew: every column
              // of a brand new table is new, and saying so would bury the one
              // case that matters.
              if (existed && !columnExists(handle, columnFqn)) {
                newColumns.add(columnFqn);
              }
              upsertColumn(handle, assetId, columnFqn, column, seen);
              columns++;
            }
            retireMissingColumns(handle, assetId, seen);
          }

          List<String> missing = missingTables(handle, source.id(), seenTables, schemaFilter);
          LOG.info(
              "Introspected {}: {} tables, {} columns, {} new tables, {} new columns, {} missing",
              source.name(),
              tables.size(),
              columns,
              newTables,
              newColumns.size(),
              missing.size());
          return new Report(
              source.name(), tables.size(), columns, newTables, List.copyOf(newColumns), missing);
        });
  }

  // ------------------------------------------------------------------ rows

  private static boolean exists(Handle handle, String fqn) {
    return handle
        .createQuery("SELECT 1 FROM asset WHERE fqn = :fqn AND is_current")
        .bind("fqn", fqn)
        .mapTo(Integer.class)
        .findOne()
        .isPresent();
  }

  private static boolean columnExists(Handle handle, String fqn) {
    return handle
        .createQuery("SELECT 1 FROM asset_column WHERE fqn = :fqn AND is_current")
        .bind("fqn", fqn)
        .mapTo(Integer.class)
        .findOne()
        .isPresent();
  }

  /**
   * Inserts one asset, or leaves an existing row alone.
   *
   * <p>A row the crawl owns is only stamped with {@code last_seen_at}: the
   * table is confirmed to exist, and nothing else about it is this component's
   * to say.
   */
  private static UUID upsert(
      Handle handle,
      UUID sourceId,
      String fqn,
      String type,
      String parentFqn,
      String name,
      Instant seen) {

    Optional<UUID> existing =
        handle
            .createQuery("SELECT id FROM asset WHERE fqn = :fqn AND is_current")
            .bind("fqn", fqn)
            .mapTo(UUID.class)
            .findOne();

    if (existing.isPresent()) {
      handle
          .createUpdate(
              """
              UPDATE asset
                 SET last_seen_at = :seen,
                     data_source_id = COALESCE(data_source_id, CAST(:sourceId AS uuid))
               WHERE id = CAST(:id AS uuid)
              """)
          .bind("seen", seen)
          .bind("sourceId", sourceId)
          .bind("id", existing.get())
          .execute();
      return existing.get();
    }

    return handle
        .createQuery(
            """
            INSERT INTO asset (data_source_id, fqn, asset_type, parent_fqn, name,
                               provenance, valid_from, last_seen_at)
            VALUES (CAST(:sourceId AS uuid), :fqn, :type, :parentFqn, :name,
                    'discovered', :seen, :seen)
            RETURNING id
            """)
        .bind("sourceId", sourceId)
        .bind("fqn", fqn)
        .bind("type", type)
        .bind("parentFqn", parentFqn)
        .bind("name", name)
        .bind("seen", seen)
        .mapTo(UUID.class)
        .one();
  }

  private static void upsertColumn(
      Handle handle, UUID assetId, String fqn, JdbcIntrospector.Column column, Instant seen) {

    int updated =
        handle
            .createUpdate(
                """
                UPDATE asset_column
                   SET ordinal = :ordinal, data_type = :dataType, data_length = :length,
                       nullable = :nullable, last_seen_at = :seen
                 WHERE fqn = :fqn AND is_current
                """)
            .bind("fqn", fqn)
            .bind("ordinal", column.ordinal())
            .bind("dataType", column.dataType())
            .bind("length", column.length())
            .bind("nullable", column.nullable())
            .bind("seen", seen)
            .execute();
    if (updated > 0) {
      return;
    }

    handle
        .createUpdate(
            """
            INSERT INTO asset_column (asset_id, fqn, name, ordinal, data_type, data_length,
                                      nullable, valid_from, last_seen_at)
            VALUES (CAST(:assetId AS uuid), :fqn, :name, :ordinal, :dataType, :length,
                    :nullable, :seen, :seen)
            """)
        .bind("assetId", assetId)
        .bind("fqn", fqn)
        .bind("name", column.name())
        .bind("ordinal", column.ordinal())
        .bind("dataType", column.dataType())
        .bind("length", column.length())
        .bind("nullable", column.nullable())
        .bind("seen", seen)
        .execute();
  }

  /**
   * Closes columns that were not seen this run.
   *
   * <p>SCD2 rather than a delete: a decision made last month referred to these
   * columns, and an audit that cannot find the column it masked cannot explain
   * the decision (FR-1.4).
   */
  private static void retireMissingColumns(Handle handle, UUID assetId, Instant seen) {
    handle
        .createUpdate(
            """
            UPDATE asset_column
               SET is_current = false, valid_to = :seen
             WHERE asset_id = CAST(:assetId AS uuid) AND is_current
               AND (last_seen_at IS NULL OR last_seen_at < :seen)
            """)
        .bind("assetId", assetId)
        .bind("seen", seen)
        .execute();
  }

  /** The physical mapping, and the record that it was verified just now. */
  private static void map(
      Handle handle,
      UUID sourceId,
      String fqn,
      String database,
      JdbcIntrospector.Table table,
      Instant seen) {

    handle
        .createUpdate(
            """
            INSERT INTO asset_fqn_map (om_fqn, data_source_id, database_name, schema_name,
                                       object_name, object_kind, last_verified_at,
                                       verification_status)
            VALUES (:fqn, CAST(:sourceId AS uuid), :database, :schema, :object, :kind,
                    :seen, 'MATCHED')
            ON CONFLICT (om_fqn) DO UPDATE
               SET data_source_id = EXCLUDED.data_source_id,
                   database_name = EXCLUDED.database_name,
                   schema_name = EXCLUDED.schema_name,
                   object_name = EXCLUDED.object_name,
                   object_kind = EXCLUDED.object_kind,
                   last_verified_at = EXCLUDED.last_verified_at,
                   verification_status = 'MATCHED'
            """)
        .bind("fqn", fqn)
        .bind("sourceId", sourceId)
        .bind("database", database)
        .bind("schema", table.schema())
        .bind("object", table.name())
        .bind("kind", table.kind())
        .bind("seen", seen)
        .execute();
  }

  /**
   * Tables this source used to have and no longer does.
   *
   * <p>Marked {@code ORPHANED} rather than removed. A policy still bound to a
   * dropped table is a loose end somebody has to tidy deliberately; deleting
   * the row would tidy it by making the evidence disappear.
   */
  private static List<String> missingTables(
      Handle handle, UUID sourceId, List<String> seenTables, String schemaFilter) {

    List<String> known =
        handle
            .createQuery(
                """
                SELECT fqn FROM asset
                 WHERE data_source_id = CAST(:sourceId AS uuid)
                   AND is_current AND asset_type IN ('TABLE', 'VIEW')
                   AND provenance = 'discovered'
                """)
            .bind("sourceId", sourceId)
            .mapTo(String.class)
            .list();

    List<String> missing = new ArrayList<>();
    for (String fqn : known) {
      if (seenTables.contains(fqn)) {
        continue;
      }
      // A filtered run saw only one schema, so anything outside it is absent
      // from this run by construction and must not be called orphaned.
      if (schemaFilter != null && !fqn.contains("." + schemaFilter + ".")) {
        continue;
      }
      missing.add(fqn);
      handle
          .createUpdate(
              "UPDATE asset_fqn_map SET verification_status = 'ORPHANED' WHERE om_fqn = :fqn")
          .bind("fqn", fqn)
          .execute();
    }
    return missing;
  }
}
