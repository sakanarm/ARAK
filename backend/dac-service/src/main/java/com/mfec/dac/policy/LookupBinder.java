package com.mfec.dac.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mfec.dac.compiler.sql.SqlDialect;
import com.mfec.dac.compiler.sql.SqlDialects;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedLookup;
import com.mfec.dac.schema.api.ResolvedLookupKey;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.QueryExecutor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a lookup row filter into something the query proxy can run: the rows a
 * person may see, read from a mapping table rather than held as their
 * attribute.
 *
 * <p>The engine resolves a lookup as far as it can without a connection: which
 * mapping table, which of its columns, and the person's own values to match
 * against it. What is still missing is the physical table, and that depends on
 * the source the query runs on, so it is filled in here, once per governed
 * table. The two modes finish differently:
 *
 * <ul>
 *   <li><b>SUBQUERY</b> binds the mapping to its schema and table, and the
 *       filter is rendered as {@code column IN (SELECT ...)}. The source reads
 *       the mapping in the same statement, so a change to the mapping counts
 *       from the next query. The mapping has to live where that statement runs:
 *       the same source and the same database as the filtered table.
 *   <li><b>READ_VALUES</b> reads the allowed values first, on whichever source
 *       holds the mapping, and the filter becomes an ordinary list. More than
 *       {@link #MAX_VALUES} values is refused rather than cut, because a cut
 *       list would hide rows the mapping allows without saying so.
 * </ul>
 *
 * <p>Anything that stops a lookup from being bound refuses the query. None of
 * these is a person lacking access -- the engine already turned that into no
 * rows -- they are a mapping that is missing, moved or unreadable, and an
 * empty grid would hide the fault from the one person who can report it.
 *
 * <p>The mapping table's own policies do not apply to this read. It is a
 * control table: whoever may change it decides who sees what, and the person
 * querying is not reading it, ARAK is. The values read go into the statement
 * and nowhere else: they are not cached, not described, and not given to any
 * assistant.
 */
public final class LookupBinder {

  private static final Logger LOG = LoggerFactory.getLogger(LookupBinder.class);

  /** Most values a READ_VALUES lookup may give one person. */
  public static final int MAX_VALUES = 1_000;

  /** How long reading a mapping may take; it is meant to be a small table. */
  public static final int TIMEOUT_SECONDS = 15;

  /**
   * Types a value column may have for READ_VALUES: those whose value comes
   * back as text or a number that means the same thing written into another
   * statement. A timestamp comes back shifted to UTC and binary comes back
   * cut short, so a list of either would match the wrong rows.
   */
  private static final Set<String> LISTABLE_TYPES =
      Set.of(
          "varchar", "text", "bpchar", "char", "character", "nchar", "nvarchar", "ntext",
          "citext", "name", "sysname", "varchar2", "nvarchar2", "int2", "int4", "int8",
          "smallint", "integer", "int", "bigint", "tinyint", "numeric", "decimal", "number",
          "uuid", "uniqueidentifier", "date");

  /**
   * Fixed-width text, which comes back padded with spaces. Both engines ignore
   * that padding when they compare it, so the list drops it too; kept, a
   * padded value would match nothing in a varchar column that the joined
   * mapping matches.
   */
  private static final Set<String> PADDED_TYPES = Set.of("bpchar", "char", "character", "nchar");

  /**
   * Reads a mapping on the source that holds it. Kept outside so the query
   * service can charge the read to the caller's share of the source, as it
   * does every other read.
   */
  @FunctionalInterface
  public interface Reader {
    QueryExecutor.Page read(DataSourceStore.Source source, String sql, int maxRows, String caller)
        throws Exception;
  }

  private final Jdbi jdbi;
  private final ObjectMapper json;
  private final DataSourceStore sources;
  private final Reader reader;

  public LookupBinder(Jdbi jdbi, ObjectMapper json, DataSourceStore sources, Reader reader) {
    this.jdbi = jdbi;
    // The decision says when it was made. A mapper that cannot copy an Instant
    // would fail every lookup query, so the copy is made by one that can.
    this.json = json == null ? null : json.copy().registerModule(new JavaTimeModule());
    this.sources = sources;
    this.reader = reader;
  }

  /** Where a catalogued table physically is. */
  record Located(
      String fqn, UUID sourceId, String database, String schema, String table) {}

  // ------------------------------------------------------------------ binding

  /**
   * The decision with every lookup bound to a real table, or read into a list.
   *
   * <p>A copy whenever there is anything to bind: the decision may be the one
   * the decision cache holds, shared with every other query, and a list read
   * for one person must never be found in another person's decision.
   *
   * @param source the source the query runs on
   * @param fqn the table the decision is about
   * @param caller who a read of the mapping is charged to
   * @throws QueryService.RejectedException when a lookup cannot be bound
   */
  public PolicyDecision bind(
      DataSourceStore.Source source, String fqn, PolicyDecision decision, String caller) {
    if (decision == null
        || !Boolean.TRUE.equals(decision.getAllowed())
        || !hasLookup(decision)) {
      return decision;
    }
    PolicyDecision copy = json.convertValue(decision, PolicyDecision.class);
    Located filtered = locate(fqn).orElse(null);
    List<ResolvedRowPredicate> bound = new ArrayList<>(copy.getRowPredicates().size());
    for (ResolvedRowPredicate predicate : copy.getRowPredicates()) {
      if (predicate != null && predicate.getKind() == ResolvedRowPredicate.Kind.LOOKUP) {
        bound.add(bind(source, fqn, filtered, predicate, caller));
      } else {
        bound.add(predicate);
      }
    }
    copy.setRowPredicates(bound);
    return copy;
  }

  private ResolvedRowPredicate bind(
      DataSourceStore.Source source,
      String fqn,
      Located filtered,
      ResolvedRowPredicate predicate,
      String caller) {

    ResolvedLookup lookup = predicate.getLookup();
    String column = predicate.getColumn();
    if (lookup == null || blank(lookup.getTable()) || blank(lookup.getValueColumn())
        || lookup.getKeys() == null || lookup.getKeys().isEmpty()) {
      throw refused("The row filter on " + column + " of " + fqn
          + " reads a mapping table, but the policy does not say which table, which column or"
          + " which key; nothing was run. Ask the policy's author to finish it.");
    }
    String mapping = lookup.getTable();
    Located where =
        locate(mapping)
            .orElseThrow(
                () -> refused("The row filter on " + column + " of " + fqn
                    + " reads the mapping table " + mapping
                    + ", which is not in the catalog, or is no longer at the source; nothing"
                    + " was run."));

    List<String> known = columns(where.fqn());
    if (known.isEmpty()) {
      throw refused("The row filter on " + column + " of " + fqn + " reads the mapping table "
          + mapping + ", but nothing is known about its columns; sync it and run this again.");
    }
    String valueColumn = canonical(known, lookup.getValueColumn(), mapping);
    List<ResolvedLookupKey> keys = new ArrayList<>(lookup.getKeys().size());
    for (ResolvedLookupKey key : lookup.getKeys()) {
      if (key == null || key.getValues() == null || key.getValues().isEmpty()) {
        // The engine turns a person without the attribute into no rows before
        // this point; a key with nothing to match here is a decision that was
        // not made by the engine, and it is not guessed at.
        throw refused("The row filter on " + column + " of " + fqn
            + " has a key with nothing to look up in " + mapping + "; nothing was run.");
      }
      keys.add(
          new ResolvedLookupKey()
              .withColumn(canonical(known, key.getColumn(), mapping))
              .withUserAttribute(key.getUserAttribute())
              .withValues(new ArrayList<>(key.getValues())));
    }
    lookup.setKeys(keys);
    lookup.setValueColumn(valueColumn);

    if (lookup.getMode() == ResolvedLookup.Mode.READ_VALUES) {
      return readValues(fqn, predicate, lookup, where, caller);
    }

    // SUBQUERY: the mapping is read by the statement itself, so it has to be
    // somewhere that statement can name.
    if (filtered == null
        || !where.sourceId().equals(source.id())
        || !sameDatabase(where.database(), filtered.database())) {
      throw refused("The row filter on " + column + " of " + fqn + " joins the mapping table "
          + mapping + " into the query, but it is on another data source or database, so the"
          + " query cannot reach it; nothing was run. Set the filter to read the values first"
          + " (READ_VALUES), or keep the mapping beside the table.");
    }
    lookup.setSchemaName(where.schema());
    lookup.setTableName(where.table());
    return predicate;
  }

  /** READ_VALUES: the mapping is read now, and the filter becomes a list. */
  private ResolvedRowPredicate readValues(
      String fqn,
      ResolvedRowPredicate predicate,
      ResolvedLookup lookup,
      Located where,
      String caller) {

    String column = predicate.getColumn();
    String mapping = lookup.getTable();
    DataSourceStore.Source holder =
        sources.find(where.sourceId()).filter(DataSourceStore.Source::enabled).orElseThrow(
            () -> refused("The row filter on " + column + " of " + fqn
                + " reads its values from " + mapping
                + ", whose data source is missing or disabled; nothing was run."));
    if (!blank(holder.defaultDatabase())
        && !blank(where.database())
        && !holder.defaultDatabase().equalsIgnoreCase(where.database())) {
      throw refused("The row filter on " + column + " of " + fqn + " reads its values from "
          + mapping + ", which is in another database than the one its source connects to;"
          + " nothing was run.");
    }

    SqlDialect dialect = SqlDialects.forEngineId(holder.engine().name());
    String sql = readStatement(dialect, where, lookup);
    QueryExecutor.Page page;
    try {
      page = reader.read(holder, sql, MAX_VALUES, caller);
    } catch (QueryService.BusyException | QueryService.RejectedException e) {
      throw e;
    } catch (QueryExecutor.CostExceededException e) {
      throw refused("The row filter on " + column + " of " + fqn + " reads its values from "
          + mapping + ", and the source priced that read over its ceiling; nothing was run.");
    } catch (Exception e) {
      // Fail closed. The source's own message can name things the caller has
      // no business knowing about, so it goes to the log only.
      LOG.warn("Reading the mapping table {} for {} failed: {}", mapping, fqn, e.toString());
      throw refused("The row filter on " + column + " of " + fqn + " could not read its values"
          + " from the mapping table " + mapping + ", so which rows you may see is not known;"
          + " nothing was run.");
    }
    if (page.truncated()) {
      throw refused("The row filter on " + column + " of " + fqn + " found more than "
          + MAX_VALUES + " values for you in " + mapping + ", too many to send as a list;"
          + " nothing was run. Joining the mapping into the query (SUBQUERY) has no such limit.");
    }
    String type =
        page.columnTypes() == null || page.columnTypes().isEmpty()
            ? null
            : page.columnTypes().get(0);
    if (!listable(type)) {
      throw refused("The row filter on " + column + " of " + fqn + " reads " + mapping + "."
          + lookup.getValueColumn() + ", whose type (" + type + ") cannot be sent as a list"
          + " exactly; nothing was run. Join the mapping into the query (SUBQUERY) instead.");
    }

    boolean padded = PADDED_TYPES.contains(baseType(type));
    Set<Object> values = new LinkedHashSet<>();
    for (List<Object> row : page.rows()) {
      Object value = row.isEmpty() ? null : row.get(0);
      if (padded && value instanceof String text) {
        value = unpadded(text);
      }
      if (value != null) {
        values.add(value);
      }
    }
    if (values.isEmpty()) {
      // The mapping gives this person nothing: no rows, and the lookup stays
      // on the predicate so the explanation can say where the answer came from.
      return predicate
          .withKind(ResolvedRowPredicate.Kind.ALWAYS_FALSE)
          .withOperator(null)
          .withValues(null);
    }
    // In a stable order, so the same mapping gives the same statement and a
    // held result can be found again.
    List<Object> sorted = new ArrayList<>(values);
    sorted.sort(Comparator.comparing(String::valueOf));
    return predicate
        .withKind(ResolvedRowPredicate.Kind.IN_LIST)
        .withOperator(ResolvedRowPredicate.FacetOperator.IN)
        .withValues(sorted);
  }

  /** What READ_VALUES sends to the mapping's source. */
  static String readStatement(SqlDialect dialect, Located where, ResolvedLookup lookup) {
    String value = dialect.quote(lookup.getValueColumn());
    List<String> conditions = new ArrayList<>();
    for (ResolvedLookupKey key : lookup.getKeys()) {
      List<String> literals = new ArrayList<>(key.getValues().size());
      for (String v : key.getValues()) {
        literals.add(v == null ? "NULL" : dialect.literal(v));
      }
      conditions.add(dialect.quote(key.getColumn()) + " IN (" + String.join(", ", literals) + ")");
    }
    conditions.add(value + " IS NOT NULL");
    return "SELECT DISTINCT "
        + value
        + " FROM "
        + dialect.quote(where.schema())
        + "."
        + dialect.quote(where.table())
        + " WHERE "
        + String.join(" AND ", conditions);
  }

  // ----------------------------------------------------------------- catalog

  /** Where the catalog says a table is, unless it has gone from the source. */
  private Optional<Located> locate(String fqn) {
    List<Located> found =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        """
                        SELECT om_fqn, data_source_id, database_name, schema_name, object_name
                          FROM asset_fqn_map
                         WHERE lower(om_fqn) = lower(:fqn)
                           AND verification_status <> 'ORPHANED'
                        """)
                    .bind("fqn", fqn)
                    .map(
                        (rs, ctx) ->
                            new Located(
                                rs.getString("om_fqn"),
                                rs.getObject("data_source_id", UUID.class),
                                rs.getString("database_name"),
                                rs.getString("schema_name"),
                                rs.getString("object_name")))
                    .list());
    // Written exactly wins; otherwise a different spelling is accepted only
    // when it cannot mean two tables.
    for (Located located : found) {
      if (located.fqn().equals(fqn)) {
        return usable(located);
      }
    }
    return found.size() == 1 ? usable(found.get(0)) : Optional.empty();
  }

  private static Optional<Located> usable(Located located) {
    return located.sourceId() == null || blank(located.schema()) || blank(located.table())
        ? Optional.empty()
        : Optional.of(located);
  }

  private List<String> columns(String fqn) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT c.name FROM asset_column c
                      JOIN asset a ON a.id = c.asset_id AND a.is_current
                     WHERE a.fqn = :fqn AND c.is_current
                     ORDER BY c.ordinal, c.name
                    """)
                .bind("fqn", fqn)
                .mapTo(String.class)
                .list());
  }

  /**
   * The column as the source spells it. Quoted identifiers are case-sensitive
   * on PostgreSQL, so a policy that wrote {@code Department} for a column
   * called {@code department} must still find it, and must not find
   * something else.
   */
  private static String canonical(List<String> known, String wanted, String mapping) {
    if (wanted != null) {
      for (String name : known) {
        if (name.equals(wanted)) {
          return name;
        }
      }
      String match = null;
      for (String name : known) {
        if (name.equalsIgnoreCase(wanted)) {
          if (match != null) {
            throw refused("The mapping table " + mapping + " has more than one column called "
                + wanted + " in different cases; name it exactly in the policy.");
          }
          match = name;
        }
      }
      if (match != null) {
        return match;
      }
    }
    throw refused("The mapping table " + mapping + " has no column " + wanted
        + "; nothing was run. Ask the policy's author to pick one it has.");
  }

  static boolean listable(String type) {
    String base = baseType(type);
    return base != null && LISTABLE_TYPES.contains(base);
  }

  /** A driver's type name without its size or modifiers: {@code varchar(20)} is varchar. */
  private static String baseType(String type) {
    if (type == null) {
      return null;
    }
    String base = type.trim().toLowerCase(Locale.ROOT);
    int cut = base.length();
    for (char stop : new char[] {' ', '('}) {
      int at = base.indexOf(stop);
      if (at >= 0) {
        cut = Math.min(cut, at);
      }
    }
    return base.substring(0, cut).replace("\"", "");
  }

  static String unpadded(String text) {
    int end = text.length();
    while (end > 0 && text.charAt(end - 1) == ' ') {
      end--;
    }
    return text.substring(0, end);
  }

  private static boolean sameDatabase(String a, String b) {
    if (blank(a) || blank(b)) {
      return blank(a) && blank(b);
    }
    return a.equalsIgnoreCase(b);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static QueryService.RejectedException refused(String message) {
    return new QueryService.RejectedException(message, null, false);
  }

  // ---------------------------------------------------------------- reading

  /** Whether a decision has a lookup the proxy must bind before rendering. */
  public static boolean hasLookup(PolicyDecision decision) {
    if (decision == null || decision.getRowPredicates() == null) {
      return false;
    }
    for (ResolvedRowPredicate predicate : decision.getRowPredicates()) {
      if (predicate != null && predicate.getKind() == ResolvedRowPredicate.Kind.LOOKUP) {
        return true;
      }
    }
    return false;
  }

  /**
   * A lookup in words: where the values come from and what the person is
   * matched by, never the values read. Null when the filter is not a lookup.
   */
  public static String describe(ResolvedRowPredicate predicate) {
    ResolvedLookup lookup = predicate == null ? null : predicate.getLookup();
    if (lookup == null) {
      return null;
    }
    String column = predicate.getColumn() == null ? "the row" : predicate.getColumn();
    String table = lookup.getTable() == null ? "a mapping table" : lookup.getTable();
    String values = lookup.getValueColumn() == null ? "values" : lookup.getValueColumn();
    String matched = matched(lookup.getKeys());
    if (predicate.getKind() == ResolvedRowPredicate.Kind.ALWAYS_FALSE) {
      return "no rows at all: " + table + " gives no " + values
          + (matched.isEmpty() ? "" : " for " + matched);
    }
    return column + " is one of the " + values + " values in " + table
        + (matched.isEmpty() ? "" : " for " + matched)
        + (lookup.getMode() == ResolvedLookup.Mode.READ_VALUES
            ? ", read when the query ran"
            : "");
  }

  private static String matched(List<ResolvedLookupKey> keys) {
    if (keys == null) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    for (ResolvedLookupKey key : keys) {
      if (key == null || key.getColumn() == null) {
        continue;
      }
      List<String> values = key.getValues() == null ? List.of() : key.getValues();
      parts.add(key.getColumn() + " " + String.join(" or ", values));
    }
    return String.join(" and ", parts);
  }
}
