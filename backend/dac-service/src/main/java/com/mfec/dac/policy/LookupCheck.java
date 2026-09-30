package com.mfec.dac.policy;

import com.mfec.dac.common.Fqns;
import com.mfec.dac.schema.entity.policy.LookupKey;
import com.mfec.dac.schema.entity.policy.Policy;
import com.mfec.dac.schema.entity.policy.RowFilter;
import com.mfec.dac.schema.entity.policy.RowLookup;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;

/**
 * Refuses a lookup row filter that could never find its mapping, at the moment
 * the author saves it.
 *
 * <p>The engine and the query proxy both fail closed on a lookup that is
 * incomplete or points at nothing: no rows, or a refused query. That is safe
 * and it is also silent until somebody queries, so the same questions are
 * asked here first: is there a mapping table, is it in the catalog, and does
 * it have the columns the filter names.
 *
 * <p>Whether the mapping is on the same source as each filtered table is not
 * asked. One policy can cover tables on several sources, and a mapping beside
 * some of them is a legitimate design; the query proxy says so per table.
 */
final class LookupCheck {

  private LookupCheck() {}

  static void check(Jdbi jdbi, Policy document) {
    if (document.getData() == null || document.getData().getRowFilters() == null) {
      return;
    }
    for (RowFilter filter : document.getData().getRowFilters()) {
      if (filter == null || filter.getKind() != RowFilter.Kind.LOOKUP) {
        continue;
      }
      if (blank(filter.getColumn()) && filter.getColumns() == null) {
        throw new IllegalArgumentException(
            "A row filter that reads a mapping table needs the column it filters,"
                + " by name or by tag");
      }
      RowLookup lookup = filter.getLookup();
      if (lookup == null || blank(lookup.getTable())) {
        throw new IllegalArgumentException(
            "A row filter that reads a mapping table needs the mapping table");
      }
      String table = lookup.getTable();
      if (Fqns.segments(table).size() != 4) {
        throw new IllegalArgumentException(
            "The mapping table " + table + " must be a table, named service.database.schema.table");
      }
      if (blank(lookup.getValueColumn())) {
        throw new IllegalArgumentException(
            "Say which column of " + table + " holds the values a person may see");
      }
      if (lookup.getKeys() == null || lookup.getKeys().isEmpty()) {
        throw new IllegalArgumentException(
            "Say how a person is matched to rows of " + table
                + ": at least one of its columns and the attribute it is compared with");
      }
      for (LookupKey key : lookup.getKeys()) {
        if (key == null || blank(key.getColumn()) || blank(key.getUserAttribute())) {
          throw new IllegalArgumentException(
              "Every key of the mapping table " + table + " needs a column and an attribute");
        }
      }

      Optional<String> catalogued = catalogued(jdbi, table);
      if (catalogued.isEmpty()) {
        throw new IllegalArgumentException(
            "The mapping table " + table + " is not in the catalog; sync it first");
      }
      List<String> columns = columns(jdbi, catalogued.get());
      require(columns, lookup.getValueColumn(), table);
      for (LookupKey key : lookup.getKeys()) {
        require(columns, key.getColumn(), table);
      }
    }
  }

  private static Optional<String> catalogued(Jdbi jdbi, String table) {
    List<String> found =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT om_fqn FROM asset_fqn_map
                         WHERE lower(om_fqn) = lower(:fqn)
                           AND verification_status <> 'ORPHANED'
                        """)
                    .bind("fqn", table)
                    .mapTo(String.class)
                    .list());
    // The proxy accepts another spelling only when it names one table; so
    // does this, so what saves is what runs.
    if (found.contains(table)) {
      return Optional.of(table);
    }
    return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
  }

  private static List<String> columns(Jdbi jdbi, String fqn) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT c.name FROM asset_column c
                      JOIN asset a ON a.id = c.asset_id AND a.is_current
                     WHERE a.fqn = :fqn AND c.is_current
                    """)
                .bind("fqn", fqn)
                .mapTo(String.class)
                .list());
  }

  private static void require(List<String> columns, String wanted, String table) {
    for (String column : columns) {
      if (column.equalsIgnoreCase(wanted)) {
        return;
      }
    }
    throw new IllegalArgumentException("The mapping table " + table + " has no column " + wanted);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
