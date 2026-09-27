package com.mfec.dac.proxy;

import com.mfec.dac.compiler.sql.DecisionSql;
import com.mfec.dac.compiler.sql.SqlDialect;
import com.mfec.dac.engine.Refusals;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.Unenforceable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.NextValExpression;
import net.sf.jsqlparser.expression.UserVariable;
import net.sf.jsqlparser.expression.VariableAssignment;
import net.sf.jsqlparser.parser.CCJSqlParserTreeConstants;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.Node;
import net.sf.jsqlparser.parser.SimpleNode;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

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
 * the rewrite: every table reference in the parser's own tree of the statement
 * as it arrived — every position, including a subquery in a {@code WHERE},
 * {@code GROUP BY} or {@code ORDER BY}, which the walk does not descend into —
 * must be the very reference the walk replaced or passed as a CTE. The check is
 * by identity, not by name: a second read of a governed table from a position
 * the walk never saw names the same table as the first, and would otherwise
 * pass on the strength of it with no policy in front of it.
 *
 * <p>That gate looks at the statement as it arrived and not at the output. The
 * output still names the physical table — inside the derived table we built,
 * which is the entire point — so counting references there can only pass
 * everything or fail everything.
 *
 * <h2>Functions</h2>
 *
 * <p>The statement runs as the source's own account, and a function is code the
 * rewrite cannot see into: one that takes the text of a query and runs it would
 * read past every policy here. So a call is allowed only to a built-in that
 * computes over the values it is given ({@link ProxyFunctions}). Anything else —
 * anything schema-qualified, a JDBC escape, a sequence, a session variable — is
 * refused before any table is resolved.
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
    private final boolean aboutStatement;

    public RefusedException(String message) {
      this(message, true);
    }

    /**
     * @param aboutStatement true when a different statement could get past this
     *     -- a typo, a bare table name, a construct the proxy does not read --
     *     and false when a policy or the platform itself said no, which no
     *     rewording should ever be offered as a way around
     */
    public RefusedException(String message, boolean aboutStatement) {
      super(message);
      this.aboutStatement = aboutStatement;
    }

    /** Whether rewriting the statement is a sensible thing to suggest (M26). */
    public boolean aboutStatement() {
      return aboutStatement;
    }
  }

  /**
   * Refused because the decision on one governed asset said no -- as opposed to
   * a statement the proxy cannot read or a table it has never heard of.
   *
   * <p>Its own type because it is the one refusal somebody can do something
   * about: the asset has an owner who might grant it, and the caller can only
   * be pointed at them if the refusal says which asset it was.
   */
  public static final class DeniedException extends RefusedException {
    private final String assetFqn;

    public DeniedException(String assetFqn, String message) {
      super(message, false);
      this.assetFqn = assetFqn;
    }

    public String assetFqn() {
      return assetFqn;
    }
  }

  private final SqlDialect dialect;
  private final String defaultSchema;

  public QueryRewriter(SqlDialect dialect, String defaultSchema) {
    this.dialect = dialect;
    this.defaultSchema = defaultSchema == null || defaultSchema.isBlank() ? null : defaultSchema;
  }

  /**
   * Parses as the source's engine writes SQL. On SQL Server {@code [name]} is a
   * quoted name, which is also how its dialect quotes the enforced form; read as
   * an array subscript instead, no statement against that engine gets through.
   */
  private Statement parse(String sql) throws JSQLParserException {
    boolean brackets = "SQLSERVER".equals(dialect.name());
    return CCJSqlParserUtil.parse(sql, parser -> parser.withSquareBracketQuotation(brackets));
  }

  public Rewritten rewrite(String sql, Governance governance) {
    if (sql == null || sql.isBlank()) {
      throw new RefusedException("No SQL was sent", false);
    }
    Statement statement;
    try {
      statement = parse(sql);
    } catch (JSQLParserException e) {
      // The message is deliberately not the parser's: it names positions in a
      // statement the caller may not have written by hand, and it is not worth
      // the risk of echoing a fragment back into a log.
      throw new RefusedException(
          "This statement could not be parsed, so it cannot be enforced and will not be run");
    }
    if (!(statement instanceof Select select)) {
      throw new RefusedException(
          "Only SELECT is allowed through the query proxy; this deployment is read-only (FR-6.3)",
          false);
    }

    // The parser's own tree of the statement as it arrived. The walk below edits
    // the statement in place; this tree keeps pointing at what the caller wrote,
    // every position of it, which is what both gates need.
    SimpleNode tree = treeOf(select);
    screen(tree);

    Set<String> cteNames = new LinkedHashSet<>();
    collectCteNames(select, cteNames);

    Map<String, Governed> governed = new LinkedHashMap<>();
    List<Unenforceable> unenforceable = new ArrayList<>();
    Set<Table> replaced = Collections.newSetFromMap(new IdentityHashMap<>());
    boolean[] restricted = {false};

    walk(select, cteNames, governance, governed, unenforceable, restricted, replaced);

    String rewritten = statement.toString();
    verify(rewritten, tree, replaced);

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
      Set<Table> replaced) {

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
    if (plain.getIntoTables() != null || plain.getIntoTempTable() != null) {
      throw new RefusedException(
          "SELECT ... INTO writes a table, and the query proxy is read-only (FR-6.3)", false);
    }
    if (plain.getForMode() != null || plain.getForUpdateTable() != null) {
      throw new RefusedException(
          "A locking read (FOR UPDATE, FOR SHARE) is not allowed through the read-only query proxy",
          false);
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
      Set<Table> replaced) {

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
      replaced.add(table);
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
      throw new DeniedException(asset.fqn(), reasonFor(asset));
    }

    DecisionSql compiler = new DecisionSql(dialect);
    String innerAlias = "t";
    DecisionSql.Result result = compiler.render(asset.decision(), asset.columns(), innerAlias);
    if (result.columns().isEmpty()) {
      throw new RefusedException(
          "Every column of " + asset.fqn() + " is hidden from this principal", false);
    }
    unenforceable.addAll(result.unenforceable());
    if (result.where() != null || !result.labels().equals(asset.columns())) {
      restricted[0] = true;
    } else if (!asset.decision().getColumnMasks().isEmpty()) {
      restricted[0] = true;
    }
    governed.put(asset.fqn(), asset);
    // This reference, not this name: the gate below must still refuse a second
    // reference to the same table from a position the walk did not reach.
    replaced.add(table);

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
      inner = (Select) parse(sub.toString());
    } catch (JSQLParserException | ClassCastException e) {
      // The compiler generated something this parser will not take back. That
      // is our bug, not the caller's, and the statement must not be sent on the
      // assumption that the database is more forgiving.
      throw new RefusedException(
          "The enforced form of " + asset.fqn() + " could not be built; the query was not run",
          false);
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

  /** The parser's tree for the statement, from its root; refused when there is none. */
  private static SimpleNode treeOf(Select select) {
    SimpleNode node = select.getASTNode();
    if (node == null) {
      throw new RefusedException(
          "This statement could not be read in full, so it cannot be enforced and will not be run");
    }
    while (node.jjtGetParent() instanceof SimpleNode parent) {
      node = parent;
    }
    return node;
  }

  /** Every node of the tree, in no particular order. */
  private static List<SimpleNode> nodesOf(SimpleNode root) {
    List<SimpleNode> out = new ArrayList<>();
    Deque<SimpleNode> todo = new ArrayDeque<>();
    todo.push(root);
    while (!todo.isEmpty()) {
      SimpleNode node = todo.pop();
      out.add(node);
      for (int i = 0; i < node.jjtGetNumChildren(); i++) {
        Node child = node.jjtGetChild(i);
        if (child instanceof SimpleNode simple) {
          todo.push(simple);
        } else if (child != null) {
          throw new RefusedException(
              "This statement could not be read in full, so it cannot be enforced and will not be run");
        }
      }
    }
    return out;
  }

  /**
   * The first gate, before any table is resolved: nothing in the statement may
   * run code the rewrite cannot see into.
   */
  private void screen(SimpleNode tree) {
    for (SimpleNode node : nodesOf(tree)) {
      int id = node.getId();
      Object value = node.jjtGetValue();
      if (id == CCJSqlParserTreeConstants.JJTSEQUENCE
          || id == CCJSqlParserTreeConstants.JJTSYNONYM
          || value instanceof NextValExpression) {
        throw new RefusedException(
            "A sequence cannot be used through the read-only query proxy", false);
      }
      if (value instanceof UserVariable || value instanceof VariableAssignment) {
        throw new RefusedException("Session variables cannot be read or set through the query proxy");
      }
      if (id == CCJSqlParserTreeConstants.JJTFUNCTION && !(value instanceof Function)) {
        throw new RefusedException(
            "A function call in this statement could not be read, so it will not be run");
      }
      // The tree does not always give a call its own node: one that is the only
      // argument of another, as in sum(lower(x)), is folded into its parent. So
      // every call is also read through its arguments, which is where those are.
      if (value instanceof Function function) {
        function.accept(new CallScreen());
      } else if (value instanceof AnalyticExpression analytic) {
        analytic.accept(new CallScreen());
      }
    }
  }

  /** Checks a call and every call among its arguments, however deep. */
  private final class CallScreen extends ExpressionVisitorAdapter {
    @Override
    public void visit(Function function) {
      if (function.isEscaped()) {
        throw new RefusedException(
            "JDBC escape functions ({fn ...}) are not allowed; call the function directly");
      }
      allow(function.getMultipartName(), function.getName());
      super.visit(function);
    }

    @Override
    public void visit(AnalyticExpression analytic) {
      allow(List.of(analytic.getName() == null ? "" : analytic.getName()), analytic.getName());
      super.visit(analytic);
    }

    @Override
    public void visit(UserVariable variable) {
      throw new RefusedException("Session variables cannot be read or set through the query proxy");
    }

    @Override
    public void visit(NextValExpression next) {
      throw new RefusedException(
          "A sequence cannot be used through the read-only query proxy", false);
    }
  }

  private void allow(List<String> name, String written) {
    if (ProxyFunctions.allowed(dialect.name(), name)) {
      return;
    }
    String shown = written == null || written.isBlank() ? "This function" : written;
    if (name != null && name.size() > 1) {
      throw new RefusedException(
          shown
              + " is called with a schema. The query proxy allows only built-in functions, "
              + "called by their plain name");
    }
    throw new RefusedException(
        shown
            + " is not a function the query proxy allows. Only built-in functions that work on "
            + "the values in front of them can be used, because a function runs as the source's "
            + "own account, where no policy can follow it");
  }

  /**
   * The second gate: every table the caller named must be one the walk put a
   * policy in front of — that very reference, not merely one with the same name.
   *
   * @param tree the parser's tree of the statement as it arrived, which holds
   *     every table reference in every position, including the ones the walk
   *     does not descend into
   * @param replaced the references the walk replaced, or passed as a CTE whose
   *     body it rewrote
   */
  private void verify(String rewritten, SimpleNode tree, Set<Table> replaced) {

    // The output has to survive a round trip, because what goes to the source is
    // this text and not the tree it was built from.
    try {
      parse(rewritten);
    } catch (JSQLParserException e) {
      throw new RefusedException("The rewritten statement did not parse; it was not run", false);
    }

    for (SimpleNode node : nodesOf(tree)) {
      if (node.getId() != CCJSqlParserTreeConstants.JJTTABLENAME) {
        continue;
      }
      if (!(node.jjtGetValue() instanceof Table table)) {
        throw new RefusedException(
            "A table in this statement could not be read, so it cannot be enforced");
      }
      if (replaced.contains(table) || qualifies(node, table)) {
        continue;
      }
      throw new RefusedException(
          normalise(table.getFullyQualifiedName())
              + " is read from a position the proxy does not rewrite — a subquery inside an "
              + "expression, most likely — so no policy could be placed in front of it. Rewrite it "
              + "as a join and try again");
    }
  }

  /**
   * Whether this table reference only names the table of a {@code t.*}, which
   * reads nothing of its own: the {@code t} has to be supplied by the FROM
   * clause, which the gate has already been through.
   */
  private static boolean qualifies(SimpleNode node, Table table) {
    if (!(node.jjtGetParent() instanceof SimpleNode parent)) {
      return false;
    }
    Object value = parent.jjtGetValue();
    if (value instanceof SelectItem<?> item) {
      value = item.getExpression();
    }
    return value instanceof AllTableColumns columns && columns.getTable() == table;
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
   * <p>Which reason is the answer is {@link Refusals#blame}'s call, shared with
   * the access-request pages so the two never name different policies for the
   * same refusal. A matched {@code DENY} is named as the refuser; an {@code
   * ALLOW} as the one that did not apply; the engine's composition line as it
   * stands.
   */
  private static String reasonFor(Governed asset) {
    com.mfec.dac.schema.api.DecisionReason reason = Refusals.blame(asset.decision());
    String explanation = reason == null ? null : reason.getExplanation();
    boolean says = explanation != null && !explanation.isBlank();
    if (reason == null || (Refusals.engine(reason) && !says)) {
      return "Access to " + asset.fqn() + " is denied by policy";
    }
    if (Refusals.engine(reason)) {
      return "Access to " + asset.fqn() + " is denied: " + explanation;
    }
    if (Boolean.TRUE.equals(reason.getMatched())) {
      return "Access to "
          + asset.fqn()
          + " is denied by "
          + reason.getPolicyName()
          + (says ? ": " + explanation : "");
    }
    return "Access to "
        + asset.fqn()
        + " is denied. "
        + reason.getPolicyName()
        + " did not apply"
        + (says ? ": " + explanation : "");
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
