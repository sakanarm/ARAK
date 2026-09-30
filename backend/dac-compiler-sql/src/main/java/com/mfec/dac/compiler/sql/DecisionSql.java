package com.mfec.dac.compiler.sql;

import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedLookup;
import com.mfec.dac.schema.api.ResolvedLookupKey;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.api.Unenforceable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renders one {@link PolicyDecision} as the two pieces of SQL every mode needs:
 * a projection and a row predicate.
 *
 * <p>The secure-view compiler wraps these in {@code CREATE VIEW}, the proxy
 * substitutes them for a table reference, and the native compiler uses the
 * predicate alone. Sharing the rendering is what makes FR-6.0c testable — if
 * the three modes disagree it is about where the SQL is installed, never about
 * what it says.
 *
 * <h2>Fail-closed rendering</h2>
 *
 * <p>Anything that cannot be rendered becomes a restriction, never an
 * omission: an empty {@code IN} list is {@code 1 = 0}, an entitlement join the
 * proxy cannot reach is {@code 1 = 0}, a mask this dialect cannot express
 * becomes {@code NULL}. Each of those is also reported through
 * {@link Result#unenforceable()} so the caller can warn instead of pretending
 * (FR-6.0b).
 */
public final class DecisionSql {

  /**
   * @param columns the projection, one expression per visible column, in the
   *     order the source declares them
   * @param labels the output name for each expression, positionally aligned
   * @param where the row predicate, or null when every row is visible
   */
  public record Result(
      List<String> columns, List<String> labels, String where, List<Unenforceable> unenforceable) {

    public String selectList() {
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < columns.size(); i++) {
        if (i > 0) {
          out.append(", ");
        }
        out.append(columns.get(i));
      }
      return out.toString();
    }
  }

  /** The mapping table's alias inside a lookup subquery. */
  static final String LOOKUP_ALIAS = "arak_lookup";

  private final SqlDialect dialect;

  public DecisionSql(SqlDialect dialect) {
    this.dialect = dialect;
  }

  /**
   * @param sourceColumns every column the source actually has, in ordinal
   *     order. Taken from live introspection rather than the catalog, because
   *     a column the catalog has not heard of is exactly the column no policy
   *     covers (FR-1.6).
   * @param alias table alias to qualify column references with, or null
   */
  public Result render(PolicyDecision decision, List<String> sourceColumns, String alias) {
    List<Unenforceable> unenforceable = new ArrayList<>();

    Set<String> hidden = lowercase(decision.getHiddenColumns());
    Map<String, ResolvedColumnMask> masks = new LinkedHashMap<>();
    if (decision.getColumnMasks() != null) {
      for (ResolvedColumnMask mask : decision.getColumnMasks()) {
        if (mask != null && mask.getColumn() != null) {
          masks.put(mask.getColumn().toLowerCase(Locale.ROOT), mask);
        }
      }
    }

    List<String> expressions = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    for (String column : sourceColumns) {
      String key = column.toLowerCase(Locale.ROOT);
      if (hidden.contains(key)) {
        continue;
      }
      String reference = (alias == null ? "" : dialect.quote(alias) + ".") + dialect.quote(column);
      ResolvedColumnMask mask = masks.get(key);
      String expression = reference;
      if (mask != null) {
        try {
          expression = masked(dialect, reference, mask.getMasking(), mask.getCondition());
        } catch (UnsupportedMaskingException e) {
          // Strictest possible reading of "cannot express it".
          expression = "NULL";
          unenforceable.add(
              new Unenforceable()
                  .withPolicyId(mask.getSourcePolicyId())
                  .withDetail(
                      "column "
                          + column
                          + ": "
                          + e.getMessage()
                          + "; nulled out instead")
                  .withSuggestedMode(Unenforceable.SuggestedMode.PROXY));
        }
      }
      expressions.add(expression.equals(reference) ? reference : expression + " AS " + dialect.quote(column));
      labels.add(column);
    }

    String where = where(decision, alias, unenforceable);
    return new Result(List.copyOf(expressions), List.copyOf(labels), where, List.copyOf(unenforceable));
  }

  /**
   * The expression that reads one column through its mask.
   *
   * <p>Shared with {@link ViewCompiler} so that a column masked in a secure
   * view and the same column masked by the proxy are the same characters. They
   * are compared byte for byte by the cross-mode suite (FR-6.0c), and two
   * copies of this that drifted apart would be the exact failure that suite
   * exists to catch.
   *
   * @param condition a cell mask (FR-4.3): masked only where it holds. It is
   *     authored SQL and is emitted as written, which is why writing one is a
   *     privileged act.
   * @throws UnsupportedMaskingException when the dialect cannot express it
   */
  static String masked(
      SqlDialect dialect, String reference, MaskingSpec spec, String condition) {

    String expression = dialect.mask(reference, spec);
    if (condition == null || condition.isBlank()) {
      return expression;
    }
    return "CASE WHEN (" + condition + ") THEN " + expression + " ELSE " + reference + " END";
  }

  /** The ANDed row predicate, or null when there is none. */
  public String where(
      PolicyDecision decision, String alias, List<Unenforceable> unenforceable) {
    List<ResolvedRowPredicate> predicates = decision.getRowPredicates();
    if (predicates == null || predicates.isEmpty()) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    for (ResolvedRowPredicate predicate : predicates) {
      parts.add(predicate(predicate, alias, unenforceable));
    }
    return String.join(" AND ", parts);
  }

  private String predicate(
      ResolvedRowPredicate predicate, String alias, List<Unenforceable> unenforceable) {

    ResolvedRowPredicate.Kind kind =
        predicate.getKind() == null ? ResolvedRowPredicate.Kind.ALWAYS_FALSE : predicate.getKind();

    return switch (kind) {
      case ALWAYS_FALSE -> "(1 = 0)";
      case RAW_PREDICATE -> "(" + require(predicate.getRawPredicate(), "RAW_PREDICATE") + ")";
      case ENTITLEMENT_JOIN -> {
        // row_entitlement lives in the platform's own database. A secure view
        // can join it because the view is installed next to the data; the
        // proxy cannot, and inventing a cross-database join here would be a
        // silent change of trust boundary.
        unenforceable.add(
            new Unenforceable()
                .withPolicyId(predicate.getSourcePolicyId())
                .withDetail(
                    "entitlement-join row filter on "
                        + predicate.getEntitlementKey()
                        + " needs the entitlement table beside the data; no rows returned")
                .withSuggestedMode(Unenforceable.SuggestedMode.SECURE_VIEW));
        yield "(1 = 0)";
      }
      case IN_LIST, ATTRIBUTE_COMPARE -> compare(predicate, alias, unenforceable);
      case LOOKUP -> lookup(predicate, alias, unenforceable);
    };
  }

  /**
   * A row filter whose allowed values live in a mapping table: the column must
   * hold one of the values the mapping rows matching the reader give.
   *
   * <p>Rendered as {@code col IN (SELECT value FROM mapping WHERE key IN (...))}
   * only once the proxy has bound the mapping table to a physical table on the
   * same source. Anything short of that — unbound, read-values mode that was
   * never read, a key with nothing to match — is no rows, because the only
   * other reading is no filter.
   */
  private String lookup(
      ResolvedRowPredicate predicate, String alias, List<Unenforceable> unenforceable) {

    String column = require(predicate.getColumn(), "row filter column");
    ResolvedLookup lookup = predicate.getLookup();
    if (lookup == null
        || lookup.getMode() != ResolvedLookup.Mode.SUBQUERY
        || blank(lookup.getSchemaName())
        || blank(lookup.getTableName())) {
      unenforceable.add(
          new Unenforceable()
              .withPolicyId(predicate.getSourcePolicyId())
              .withDetail(
                  "row filter on "
                      + column
                      + " reads its values from the mapping table "
                      + (lookup == null ? "(none)" : lookup.getTable())
                      + ", which was not bound to a table on this source; no rows returned")
              .withSuggestedMode(Unenforceable.SuggestedMode.PROXY));
      return "(1 = 0)";
    }
    String valueColumn = require(lookup.getValueColumn(), "lookup value column");
    List<ResolvedLookupKey> keys = lookup.getKeys() == null ? List.of() : lookup.getKeys();
    if (keys.isEmpty()) {
      unenforceable.add(
          new Unenforceable()
              .withPolicyId(predicate.getSourcePolicyId())
              .withDetail(
                  "row filter on " + column + " has a mapping table with no key; no rows returned"));
      return "(1 = 0)";
    }

    // Its own alias, so a key column never resolves against the outer table —
    // including when the mapping table is the filtered table itself.
    String inner = dialect.quote(LOOKUP_ALIAS.equalsIgnoreCase(alias) ? LOOKUP_ALIAS + "2" : LOOKUP_ALIAS);
    List<String> conditions = new ArrayList<>();
    for (ResolvedLookupKey key : keys) {
      List<Object> values =
          key.getValues() == null ? List.of() : new ArrayList<Object>(key.getValues());
      if (values.isEmpty()) {
        unenforceable.add(
            new Unenforceable()
                .withPolicyId(predicate.getSourcePolicyId())
                .withDetail(
                    "row filter on "
                        + column
                        + " had no value to look up "
                        + key.getColumn()
                        + " with; no rows returned"));
        return "(1 = 0)";
      }
      conditions.add(
          inner + "." + dialect.quote(require(key.getColumn(), "lookup key column"))
              + " IN (" + list(values) + ")");
    }
    String value = inner + "." + dialect.quote(valueColumn);
    conditions.add(value + " IS NOT NULL");

    String reference = (alias == null ? "" : dialect.quote(alias) + ".") + dialect.quote(column);
    return "("
        + reference
        + " IN (SELECT "
        + value
        + " FROM "
        + dialect.quote(lookup.getSchemaName())
        + "."
        + dialect.quote(lookup.getTableName())
        + " "
        + inner
        + " WHERE "
        + String.join(" AND ", conditions)
        + "))";
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private String compare(
      ResolvedRowPredicate predicate, String alias, List<Unenforceable> unenforceable) {

    String column = require(predicate.getColumn(), "row filter column");
    String reference = (alias == null ? "" : dialect.quote(alias) + ".") + dialect.quote(column);
    List<Object> values = predicate.getValues() == null ? List.of() : predicate.getValues();

    ResolvedRowPredicate.FacetOperator operator =
        membership(predicate.getKind(), predicate.getOperator(), values.size());

    return switch (operator) {
      case EXISTS -> "(" + reference + " IS NOT NULL)";
      case NOT_EXISTS -> "(" + reference + " IS NULL)";
      case IN, EQ, CONTAINS, STARTS_WITH, MATCHES, NE, NOT_IN, GT, GTE, LT, LTE ->
          withValues(reference, operator, values, unenforceable, predicate);
    };
  }

  /**
   * The operator to render with, given the kind and how many values arrived.
   *
   * <p>Two things are decided here, and both were getting the same answer
   * before: a filter whose kind is {@code IN_LIST} is a membership test, and a
   * comparison handed several values is a membership test whatever it calls
   * itself. The previous default was a flat {@code EQ}, and {@code EQ} reads
   * {@code values.get(0)} — so a policy written as
   * {@code {"kind":"IN_LIST","column":"branch_code","userAttribute":"branch"}},
   * which carries no operator at all, compiled to
   * {@code branch_code = 'BKK-01'} for a principal holding both {@code BKK-01}
   * and {@code CNX-01}. The second branch was dropped silently, and the reader
   * saw fewer rows than the policy grants them. That is FR-4.1's "IN list from
   * a multi-value attribute" failing in the safe direction, which still makes
   * it wrong.
   */
  private static ResolvedRowPredicate.FacetOperator membership(
      ResolvedRowPredicate.Kind kind, ResolvedRowPredicate.FacetOperator operator, int count) {

    if (operator == null) {
      return kind == ResolvedRowPredicate.Kind.IN_LIST
          ? ResolvedRowPredicate.FacetOperator.IN
          : ResolvedRowPredicate.FacetOperator.EQ;
    }
    // An author who wrote EQ against an attribute that turns out to hold more
    // than one value meant "the column matches the reader's value", and the
    // reader has several. Widening beats discarding all but the first.
    if (count > 1 && operator == ResolvedRowPredicate.FacetOperator.EQ) {
      return ResolvedRowPredicate.FacetOperator.IN;
    }
    if (count > 1 && operator == ResolvedRowPredicate.FacetOperator.NE) {
      return ResolvedRowPredicate.FacetOperator.NOT_IN;
    }
    return operator;
  }

  private String withValues(
      String reference,
      ResolvedRowPredicate.FacetOperator operator,
      List<Object> values,
      List<Unenforceable> unenforceable,
      ResolvedRowPredicate predicate) {

    if (values.isEmpty()) {
      // The principal has no value for the attribute this filter compares
      // against. Dropping the filter would show every row to the one person
      // whose directory record is incomplete (FR-4.1).
      unenforceable.add(
          new Unenforceable()
              .withPolicyId(predicate.getSourcePolicyId())
              .withDetail(
                  "row filter on "
                      + predicate.getColumn()
                      + " had no value to compare against; no rows returned"));
      return "(1 = 0)";
    }

    return switch (operator) {
      case IN -> "(" + reference + " IN (" + list(values) + "))";
      case NOT_IN -> "(" + reference + " NOT IN (" + list(values) + "))";
      case EQ -> "(" + reference + " = " + value(values.get(0)) + ")";
      case NE -> "(" + reference + " <> " + value(values.get(0)) + ")";
      case GT -> "(" + reference + " > " + value(values.get(0)) + ")";
      case GTE -> "(" + reference + " >= " + value(values.get(0)) + ")";
      case LT -> "(" + reference + " < " + value(values.get(0)) + ")";
      case LTE -> "(" + reference + " <= " + value(values.get(0)) + ")";
      case CONTAINS ->
          "(" + dialect.toText(reference) + " LIKE " + dialect.literal("%" + text(values.get(0)) + "%") + ")";
      case STARTS_WITH ->
          "(" + dialect.toText(reference) + " LIKE " + dialect.literal(text(values.get(0)) + "%") + ")";
      case MATCHES -> matches(reference, values.get(0), unenforceable, predicate);
      default -> "(1 = 0)";
    };
  }

  private String matches(
      String reference,
      Object pattern,
      List<Unenforceable> unenforceable,
      ResolvedRowPredicate predicate) {
    if ("POSTGRES".equals(dialect.name())) {
      return "(" + dialect.toText(reference) + " ~ " + dialect.literal(text(pattern)) + ")";
    }
    unenforceable.add(
        new Unenforceable()
            .withPolicyId(predicate.getSourcePolicyId())
            .withDetail(
                dialect.name()
                    + " has no regular-expression match for a row filter on "
                    + predicate.getColumn()
                    + "; no rows returned")
            .withSuggestedMode(Unenforceable.SuggestedMode.PROXY));
    return "(1 = 0)";
  }

  private String list(List<Object> values) {
    List<String> out = new ArrayList<>(values.size());
    for (Object value : values) {
      out.add(value(value));
    }
    return String.join(", ", out);
  }

  private String value(Object value) {
    if (value == null) {
      return "NULL";
    }
    if (value instanceof java.math.BigDecimal decimal) {
      // Read from a numeric column. Its own toString can say 1E-7, which SQL
      // Server reads as a float and compares inexactly.
      return decimal.toPlainString();
    }
    if (value instanceof Number || value instanceof Boolean) {
      return String.valueOf(value);
    }
    return dialect.literal(String.valueOf(value));
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static Set<String> lowercase(List<String> values) {
    Set<String> out = new LinkedHashSet<>();
    if (values != null) {
      for (String value : values) {
        if (value != null) {
          out.add(value.toLowerCase(Locale.ROOT));
        }
      }
    }
    return out;
  }

  private static String require(String value, String what) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing " + what);
    }
    return value;
  }
}
