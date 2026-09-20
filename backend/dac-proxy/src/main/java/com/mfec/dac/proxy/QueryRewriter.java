package com.mfec.dac.proxy;

import com.mfec.dac.compiler.sql.DecisionSql;
import com.mfec.dac.compiler.sql.SqlDialect;
import com.mfec.dac.engine.PolicyEngine;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.Unenforceable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * Enforcement mode 5.2: the SQL a caller sent, rewritten so that the policy is
 * part of the statement rather than a promise about it (FR-6.3).
 *
 * <p>Every governed table reference is replaced by a derived table that selects
 * the masked projection and carries the row predicate, keeping the original
 * alias so the rest of the statement still parses. {@code SELECT *} then
 * expands against the derived table, which is why a hidden column cannot
 * reappear through a star.
 *
 * <h2>Fail closed, and provably so</h2>
 *
 * <p>Two gates, not one. The first is the walk below, which refuses anything
 * that is not a {@code SELECT}, any table it cannot resolve to an asset, and
 * any asset the principal is not subscribed to. The second runs <em>after</em>
 * the rewrite: every table the parser can find in the statement as it arrived —
 * including inside a subquery in a {@code WHERE} clause, which the walk does not
 * descend into — must be either a CTE whose body the walk rewrote, or a table
 * the walk actually replaced. A reference the walk never saw means a position it
 * does not cover, and the statement is refused rather than sent.
 *
 * <p>That gate looks at the statement as it arrived and not at the output. The
 * output still names the physical table — inside the derived table we built,
 * which is the entire point — so counting references there can only pass
 * everything or fail everything.
 *
 * <p>That second gate is the reason this class can be honest about what it does
 * not yet handle. A subquery in an expression is refused, not leaked, and the
 * refusal names the table so the caller can rewrite it as a join. Being unable
 * to run a query is an inconvenience; running it without the policy is the
 * failure this whole platform exists to prevent.
 */
public final class QueryRewriter {

  /** What the platform knows about one physical table reference. */
  public record Governed(String fqn, PolicyDecision decision, List<String> columns) {}

  /**
   * Resolves a table reference, as written, to a governed asset.
   *
   * @return null when nothing in the catalog matches, which is a refusal and
   *     never an implicit allow
   */
  @FunctionalInterface
  public interface Governance {
    Governed resolve(String schema, String table);
  }

  /**
   * The statement to send, and what it turned out to be governed by.
   *
   * @param governed the decision behind each asset, kept rather than discarded
   *     so the caller can be told which columns were masked and which filter
   *     removed the rows they cannot see. Knowing a statement was governed is
   *     not the same as being able to say how (FR-5.4).
   */
  public record Rewritten(
      String sql,
      List<String> assets,
      List<Governed> governed,
      List<Unenforceable> unenforceable,
      boolean anyRestriction) {}

  /** The statement will not be sent, and why — the message reaches the caller. */
  public static class RefusedException extends RuntimeException {
    public RefusedException(String message) {
      super(message);
    }
  }

  private final SqlDialect dialect;
  private final String defaultSchema;

  public QueryRewriter(SqlDialect dialect, String defaultSchema) {
    this.dialect = dialect;
    this.defaultSchema = defaultSchema == null || defaultSchema.isBlank() ? null : defaultSchema;
  }

  public Rewritten rewrite(String sql, Governance governance) {
    if (sql == null || sql.isBlank()) {
      throw new RefusedException("No SQL was sent");
    }
    Statement statement;
    try {
      statement = CCJSqlParserUtil.parse(sql);
    } catch (JSQLParserException e) {
      // The message is deliberately not the parser's: it names positions in a
      // statement the caller may not have written by hand, and it is not worth
      // the risk of echoing a fragment back into a log.
      throw new RefusedException(
          "This statement could not be parsed, so it cannot be enforced and will not be run");
    }
    if (!(statement instanceof Select select)) {
      throw new RefusedException(
          "Only SELECT is allowed through the query proxy; this deployment is read-only (FR-6.3)");
    }

    Set<String> cteNames = new LinkedHashSet<>();
    collectCteNames(select, cteNames);

    // Taken before the walk, because the walk edits the statement in place and
    // the gate below has to see the references the caller wrote rather than the
    // ones that survived.
    List<String> referenced;
    try {
      referenced = new TablesNamesFinder().getTableList(statement);
    } catch (RuntimeException e) {
      throw new RefusedException(
          "The tables this statement reads could not be listed, so it cannot be enforced");
    }

    Map<String, Governed> governed = new LinkedHashMap<>();
    List<Unenforceable> unenforceable = new ArrayList<>();
    Set<String> replaced = new LinkedHashSet<>();
    boolean[] restricted = {false};

    walk(select, cteNames, governance, governed, unenforceable, restricted, replaced);

    String rewritten = statement.toString();
    verify(rewritten, referenced, cteNames, replaced);

    return new Rewritten(
        rewritten,
        List.copyOf(governed.keySet()),
        List.copyOf(governed.values()),
        List.copyOf(unenforceable),
        restricted[0]);
  }

  // ------------------------------------------------------------------ walk

  private static void collectCteNames(Select select, Set<String> into) {
    List<WithItem> with = select.getWithItemsList();
    if (with != null) {
      for (WithItem item : with) {
        if (item.getAlias() != null && item.getAlias().getName() != null) {
          into.add(item.getAlias().getName().toLowerCase(Locale.ROOT));
        }
        if (item.getSelect() != null) {
          collectCteNames(item.getSelect(), into);
        }
      }
    }
    if (select instanceof SetOperationList list && list.getSelects() != null) {
      for (Select part : list.getSelects()) {
        collectCteNames(part, into);
      }
    }
    if (select instanceof ParenthesedSelect parenthesed && parenthesed.getSelect() != null) {
      collectCteNames(parenthesed.getSelect(), into);
    }
  }

  private void walk(
      Select select,
      Set<String> cteNames,
      Governance governance,
      Map<String, Governed> governed,
      List<Unenforceable> unenforceable,
      boolean[] restricted,
      Set<String> replaced) {

    if (select == null) {
      return;
    }
    List<WithItem> with = select.getWithItemsList();
    if (with != null) {
      for (WithItem item : with) {
        walk(item.getSelect(), cteNames, governance, governed, unenforceable, restricted, replaced);
      }
    }

    if (select instanceof SetOperationList list) {
      if (list.getSelects() != null) {
        for (Select part : list.getSelects()) {
          walk(part, cteNames, governance, governed, unenforceable, restricted, replaced);
        }
      }
      return;
    }

    if (select instanceof ParenthesedSelect parenthesed) {
      walk(parenthesed.getSelect(), cteNames, governance, governed, unenforceable, restricted, replaced);
      return;
    }

    if (!(select instanceof PlainSelect plain)) {
      throw new RefusedException("This form of SELECT is not supported by the query proxy");
    }

    FromItem from = plain.getFromItem();
    FromItem replacedFrom =
        replace(from, cteNames, governance, governed, unenforceable, restricted, replaced);
    if (replacedFrom != from) {
      plain.setFromItem(replacedFrom);
    }

    if (plain.getJoins() != null) {
      for (Join join : plain.getJoins()) {
        FromItem right = join.getRightItem();
        FromItem governedRight =
            replace(right, cteNames, governance, governed, unenforceable, restricted, replaced);
        if (governedRight != right) {
          join.setRightItem(governedRight);
        }
      }
    }
  }

  private FromItem replace(
      FromItem item,
      Set<String> cteNames,
      Governance governance,
      Map<String, Governed> governed,
      List<Unenforceable> unenforceable,
      boolean[] restricted,
      Set<String> replaced) {

    if (item == null) {
      return null;
    }
    if (item instanceof ParenthesedSelect parenthesed) {
      walk(parenthesed.getSelect(), cteNames, governance, governed, unenforceable, restricted, replaced);
      return item;
    }
    if (!(item instanceof Table table)) {
      throw new RefusedException(
          "The query proxy can only read plain tables and subqueries, not " + item);
    }

    String name = unquote(table.getName());
    if (cteNames.contains(name.toLowerCase(Locale.ROOT)) && table.getSchemaName() == null) {
      // A CTE the walk has already rewritten the body of. Rewriting the
      // reference too would enforce the same policy twice.
      return item;
    }

    String schema = unquote(table.getSchemaName());
    if (schema == null) {
      schema = defaultSchema;
    }
    if (schema == null) {
      throw new RefusedException(
          "Qualify "
              + name
              + " with its schema: the proxy resolves a table to an asset by name, and an "
              + "unqualified one could be either of two tables");
    }

    Governed asset = governance.resolve(schema, name);
    if (asset == null) {
      throw new RefusedException(
          schema
              + "."
              + name
              + " is not a governed asset on this source, so no policy could be applied to it");
    }
    if (!Boolean.TRUE.equals(asset.decision().getAllowed())) {
      throw new RefusedException(reasonFor(asset));
    }

    DecisionSql compiler = new DecisionSql(dialect);
    String innerAlias = "t";
    DecisionSql.Result result = compiler.render(asset.decision(), asset.columns(), innerAlias);
    if (result.columns().isEmpty()) {
      throw new RefusedException(
          "Every column of " + asset.fqn() + " is hidden from this principal");
    }
    unenforceable.addAll(result.unenforceable());
    if (result.where() != null || !result.labels().equals(asset.columns())) {
      restricted[0] = true;
    } else if (!asset.decision().getColumnMasks().isEmpty()) {
      restricted[0] = true;
    }
    governed.put(asset.fqn(), asset);
    // Recorded as the caller spelled it, because that is the spelling the gate
    // below gets back from the parser.
    replaced.add(normalise(table.getFullyQualifiedName()));

    StringBuilder sub = new StringBuilder("SELECT ");
    sub.append(result.selectList());
    sub.append(" FROM ")
        .append(dialect.quote(schema))
        .append('.')
        .append(dialect.quote(name))
        .append(' ')
        .append(dialect.quote(innerAlias));
    if (result.where() != null) {
      sub.append(" WHERE ").append(result.where());
    }

    Select inner;
    try {
      inner = (Select) CCJSqlParserUtil.parse(sub.toString());
    } catch (JSQLParserException | ClassCastException e) {
      // The compiler generated something this parser will not take back. That
      // is our bug, not the caller's, and the statement must not be sent on the
      // assumption that the database is more forgiving.
      throw new RefusedException(
          "The enforced form of " + asset.fqn() + " could not be built; the query was not run");
    }

    ParenthesedSelect derived = new ParenthesedSelect();
    derived.setSelect(inner);
    // Keeping the caller's alias — or the bare table name when there was none —
    // is what lets the rest of the statement go on referring to the columns it
    // already named.
    Alias alias = table.getAlias();
    derived.setAlias(alias != null ? alias : new Alias(dialect.quote(name), false));
    return derived;
  }

  /**
   * The second gate: every table the caller named must have been governed.
   *
   * @param referenced every table reference the parser found in the statement as
   *     it arrived, including the positions the walk does not descend into
   * @param replaced the references the walk actually put a policy in front of
   */
  private static void verify(
      String rewritten, List<String> referenced, Set<String> cteNames, Set<String> replaced) {

    // The output has to survive a round trip, because what goes to the source is
    // this text and not the tree it was built from.
    try {
      CCJSqlParserUtil.parse(rewritten);
    } catch (JSQLParserException e) {
      throw new RefusedException("The rewritten statement did not parse; it was not run");
    }

    for (String name : referenced) {
      String normalised = normalise(name);
      if (replaced.contains(normalised)) {
        continue;
      }
      // A CTE is only a CTE unqualified. Schema-qualifying the name makes it a
      // table again, and one this walk has not been through.
      if (!normalised.contains(".") && cteNames.contains(normalised)) {
        continue;
      }
      throw new RefusedException(
          normalised
              + " is read from a position the proxy does not rewrite — a subquery inside an "
              + "expression, most likely — so no policy could be placed in front of it. Rewrite it "
              + "as a join and try again");
    }
  }

  /**
   * One spelling for a table reference, so the gate compares like with like.
   *
   * <p>Splitting on the dot mangles a quoted name that contains one. That is
   * left as it is: a mangled name matches nothing and the statement is refused,
   * which is the direction this class is allowed to be wrong in.
   */
  private static String normalise(String raw) {
    if (raw == null) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (String segment : raw.split("\\.")) {
      if (out.length() > 0) {
        out.append('.');
      }
      out.append(unquote(segment).toLowerCase(Locale.ROOT));
    }
    return out.toString();
  }

  /**
   * Why this principal is not getting these rows, in the words of the decision.
   *
   * <p>A denied decision carries reasons of three kinds, and only one of them
   * answers the question. A policy that <em>matched</em> is only the reason when
   * it is a {@code DENY}; a matched {@code ALLOW} beside a refusal is a policy
   * that would have granted something had another layer agreed, and reporting it
   * produces the sentence "access is denied: subject rule satisfied", which is
   * what this used to say.
   */
  private static String reasonFor(Governed asset) {
    List<com.mfec.dac.schema.api.DecisionReason> reasons = asset.decision().getReasons();
    String refusedBy = null;
    String composition = null;
    if (reasons != null) {
      for (com.mfec.dac.schema.api.DecisionReason reason : reasons) {
        String explanation = reason.getExplanation();
        if (explanation == null || explanation.isBlank()) {
          continue;
        }
        if (Boolean.TRUE.equals(reason.getMatched())) {
          if (reason.getEffect() == com.mfec.dac.schema.api.DecisionReason.Effect.DENY) {
            return "Access to " + asset.fqn() + " is denied by " + name(reason) + ": " + explanation;
          }
          continue;
        }
        if (reason.getPolicyName() == null
            || PolicyEngine.COMPOSITION.equals(reason.getPolicyName())) {
          composition = explanation;
        } else if (refusedBy == null) {
          // The first policy that could have granted and did not: the most
          // actionable sentence, because it names the condition to look at.
          refusedBy = name(reason) + " did not apply: " + explanation;
        }
      }
    }
    if (refusedBy != null) {
      return "Access to " + asset.fqn() + " is denied. " + refusedBy;
    }
    if (composition != null) {
      return "Access to " + asset.fqn() + " is denied: " + composition;
    }
    return "Access to " + asset.fqn() + " is denied by policy";
  }

  private static String name(com.mfec.dac.schema.api.DecisionReason reason) {
    return reason.getPolicyName() == null ? "policy" : reason.getPolicyName();
  }

  private static String unquote(String value) {
    if (value == null) {
      return null;
    }
    String out = value.trim();
    if (out.length() > 1
        && ((out.startsWith("\"") && out.endsWith("\""))
            || (out.startsWith("[") && out.endsWith("]"))
            || (out.startsWith("`") && out.endsWith("`")))) {
      out = out.substring(1, out.length() - 1);
    }
    return out;
  }
}
