package com.mfec.dac.catalog;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Column descriptions written in ARAK rather than in OpenMetadata.
 *
 * <p>OpenMetadata's description stays in {@code asset_column} and is rewritten
 * by every crawl; what somebody wrote here is in {@code column_description},
 * which no sync touches. Where both exist this one is shown ({@link
 * CatalogQuery}), because it is the later and more deliberate of the two.
 *
 * <p>A description decides no access, so unlike a local tag it asks for no
 * reason. Every change is still audited with what the column said before, so
 * "who wrote this, and what did it say last week" has an answer.
 */
public class ColumnDescriptionStore {

  /** The longest description kept. A sentence or a short paragraph, not a document. */
  public static final int MAX_LENGTH = 2000;

  /** One description written here. */
  public record Written(
      String columnFqn,
      String assetFqn,
      String description,
      boolean assisted,
      String writtenBy,
      Instant writtenAt) {}

  /** One column to describe; a blank description takes the one written here away. */
  public record Entry(String columnFqn, String description, boolean assisted) {}

  /** What a save did. */
  public record Saved(int set, int cleared, int unchanged) {}

  /** Why a save was refused; the resource turns it into a 400. */
  public static final class Refused extends RuntimeException {
    public Refused(String message) {
      super(message);
    }
  }

  private final Jdbi jdbi;

  public ColumnDescriptionStore(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /** Whether the FQN is a current table or view, the only things with columns to describe. */
  public boolean isTable(String assetFqn) {
    return jdbi.withHandle(
        h ->
            h.createQuery(
                    """
                    SELECT count(*) FROM asset
                     WHERE fqn = :fqn AND is_current AND asset_type IN ('TABLE', 'VIEW')
                    """)
                .bind("fqn", assetFqn)
                .mapTo(Integer.class)
                .one()
                > 0);
  }

  /** What was written here on a table's columns, in column order. */
  public List<Written> forAsset(String assetFqn) {
    return jdbi.withHandle(
        h ->
            h.createQuery(
                    """
                    SELECT d.target_fqn, d.asset_fqn, d.description, d.assisted, d.written_by,
                           d.written_at
                      FROM column_description d
                      LEFT JOIN asset_column c ON c.fqn = d.target_fqn AND c.is_current
                     WHERE d.asset_fqn = :asset
                     ORDER BY c.ordinal NULLS LAST, d.target_fqn
                    """)
                .bind("asset", assetFqn)
                .map(
                    (rs, ctx) ->
                        new Written(
                            rs.getString("target_fqn"),
                            rs.getString("asset_fqn"),
                            rs.getString("description"),
                            rs.getBoolean("assisted"),
                            rs.getString("written_by"),
                            rs.getTimestamp("written_at").toInstant()))
                .list());
  }

  /**
   * Writes, rewrites or takes away descriptions of one table's columns, in one
   * transaction. Every column must be a current column of that table; one that
   * is not refuses the whole save, so a half-applied save is not possible.
   */
  public Saved save(String assetFqn, List<Entry> entries, String actor) {
    return jdbi.inTransaction(
        h -> {
          Set<String> columns = new HashSet<>();
          h.createQuery(
                  """
                  SELECT c.fqn FROM asset_column c JOIN asset a ON a.id = c.asset_id
                   WHERE a.fqn = :asset AND a.is_current AND c.is_current
                  """)
              .bind("asset", assetFqn)
              .mapTo(String.class)
              .forEach(columns::add);

          Map<String, String> before = new HashMap<>();
          h.createQuery(
                  "SELECT target_fqn, description FROM column_description WHERE asset_fqn = :asset")
              .bind("asset", assetFqn)
              .map((rs, ctx) -> Map.entry(rs.getString("target_fqn"), rs.getString("description")))
              .forEach(e -> before.put(e.getKey(), e.getValue()));

          // The last entry for a column wins, as it would typed twice in a form.
          Map<String, Entry> wanted = new LinkedHashMap<>();
          for (Entry entry : entries) {
            if (entry == null || entry.columnFqn() == null || entry.columnFqn().isBlank()) {
              throw new Refused("Every entry needs the column it describes");
            }
            String column = entry.columnFqn().trim();
            if (!columns.contains(column)) {
              throw new Refused(column + " is not a current column of " + assetFqn);
            }
            String text = entry.description() == null ? "" : entry.description().strip();
            if (text.length() > MAX_LENGTH) {
              throw new Refused(
                  "Keep the description of " + column + " under " + MAX_LENGTH + " characters");
            }
            wanted.put(column, new Entry(column, text, entry.assisted()));
          }

          int set = 0;
          int cleared = 0;
          int unchanged = 0;
          Instant now = Instant.now();
          for (Entry entry : wanted.values()) {
            String old = before.get(entry.columnFqn());
            if (entry.description().isEmpty()) {
              if (old == null) {
                unchanged++;
                continue;
              }
              h.createUpdate("DELETE FROM column_description WHERE target_fqn = :target")
                  .bind("target", entry.columnFqn())
                  .execute();
              audit(h, "CLEAR", actor, entry.columnFqn(), assetFqn, old, null, false);
              cleared++;
              continue;
            }
            if (entry.description().equals(old)) {
              unchanged++;
              continue;
            }
            h.createUpdate(
                    """
                    INSERT INTO column_description
                           (target_fqn, asset_fqn, description, assisted, written_by, written_at)
                    VALUES (:target, :asset, :text, :assisted, :actor, :at)
                    ON CONFLICT (target_fqn) DO UPDATE
                       SET asset_fqn = EXCLUDED.asset_fqn,
                           description = EXCLUDED.description,
                           assisted = EXCLUDED.assisted,
                           written_by = EXCLUDED.written_by,
                           written_at = EXCLUDED.written_at
                    """)
                .bind("target", entry.columnFqn())
                .bind("asset", assetFqn)
                .bind("text", entry.description())
                .bind("assisted", entry.assisted())
                .bind("actor", actor)
                .bind("at", now)
                .execute();
            audit(
                h, "SET", actor, entry.columnFqn(), assetFqn, old, entry.description(),
                entry.assisted());
            set++;
          }
          return new Saved(set, cleared, unchanged);
        });
  }

  /**
   * The description each current column of a table shows, keyed by the
   * column's name in lower case: the one written here, else OpenMetadata's.
   * Columns with neither are left out.
   */
  public static Map<String, String> effectiveByName(Handle h, String assetFqn) {
    Map<String, String> out = new HashMap<>();
    if (assetFqn == null) {
      return out;
    }
    h.createQuery(
            """
            SELECT c.name,
                   COALESCE(d.description, NULLIF(btrim(c.description), '')) AS description
              FROM asset_column c
              JOIN asset a ON a.id = c.asset_id
              LEFT JOIN column_description d ON d.target_fqn = c.fqn
             WHERE a.fqn = :asset AND a.is_current AND c.is_current
            """)
        .bind("asset", assetFqn)
        .map((rs, ctx) -> Map.entry(rs.getString("name"), Optional.ofNullable(rs.getString("description"))))
        .forEach(
            e ->
                e.getValue()
                    .ifPresent(text -> out.put(e.getKey().toLowerCase(Locale.ROOT), text)));
    return out;
  }

  /** The descriptions of one table's columns, as {@link #effectiveByName(Handle, String)}. */
  public Map<String, String> effectiveByName(String assetFqn) {
    return jdbi.withHandle(h -> effectiveByName(h, assetFqn));
  }

  private static void audit(
      Handle h, String action, String actor, String target, String asset, String before,
      String after, boolean assisted) {
    h.createUpdate(
            """
            INSERT INTO audit_column_description
                   (actor, action, target_fqn, asset_fqn, before, after, assisted)
            VALUES (:actor, :action, :target, :asset, :before, :after, :assisted)
            """)
        .bind("actor", actor)
        .bind("action", action)
        .bind("target", target)
        .bind("asset", asset)
        .bind("before", before)
        .bind("after", after)
        .bind("assisted", assisted)
        .execute();
  }
}
