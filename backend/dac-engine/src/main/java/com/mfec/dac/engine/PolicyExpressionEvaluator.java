package com.mfec.dac.engine;

import com.mfec.dac.schema.entity.policy.FacetCondition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code expr} half of a subject rule: the part that compares the two sides
 * against each other (FR-3.2, FR-2A.4).
 *
 * <pre>
 *   user.country == asset.prop('dataResidency')
 *   user.department in asset.domains
 *   user.email in asset.owners
 *   asset.tier == 'Tier1' implies user.clearance &gt;= 'L2'
 * </pre>
 *
 * <p>This is what lets one policy cover an organisation. Without it a rule can
 * only talk about the user or about the asset, and every pairing of the two has
 * to be written out as its own policy — which is the state the platform was in
 * until this class existed, because an evaluator that is not configured makes
 * every expression undecidable and every ALLOW carrying one fail to grant.
 *
 * <h2>A parser, not a grammar file</h2>
 *
 * <p>The plan names ANTLR, and the surrounding stack uses it. A recursive
 * descent parser over this many productions is about as long as the grammar
 * would be and removes a build-time code generation step from the hot path of
 * every query, so the generated parser is not worth its keep yet. The token
 * shapes and precedence below are the specification of the language either way,
 * and swapping in a generated parser later changes nothing a policy can see.
 *
 * <h2>Three values, not two</h2>
 *
 * <p>Evaluation is Kleene logic over {@code TRUE / FALSE / UNKNOWN}, with a
 * fourth outcome for row dependence:
 *
 * <ul>
 *   <li>An operand with no values — a user whose directory record is missing the
 *       attribute, an asset with no such custom property — is
 *       <em>unknown</em>, never false. It propagates out as
 *       {@link ExpressionUnavailableException} so the engine can fail in
 *       whichever direction is safe for the rule carrying it. Reading a missing
 *       attribute as false would let {@code user.country != asset.prop(...)}
 *       stop denying precisely for the user nobody has finished onboarding.
 *   <li>Anything mentioning {@code row.} cannot be settled without a row, and
 *       comes back as {@link Result#ROW_DEPENDENT} so the compilers can render
 *       it into the generated SQL as a cell condition.
 *   <li>{@code UNKNOWN} outranks row dependence when they meet: an expression we
 *       cannot read must not be handed to a compiler to emit.
 * </ul>
 *
 * <p>Short-circuiting still applies to the values that determine an outcome:
 * {@code false && anything} is false even when the right side is unreadable,
 * because it is false for reasons that do not depend on the unreadable part.
 *
 * <p>Comparison is existential over multi-valued operands, exactly as
 * {@link Operators} is for the single-sided conditions, and {@code !=} is the
 * negation of that rather than a separate rule. Two readings of "equals" is how
 * a policy ends up matching an asset but not the user it was written for.
 */
public final class PolicyExpressionEvaluator implements ExpressionEvaluator {

  @Override
  public Result evaluate(
      String expression, Principal principal, AssetContext asset, RequestContext context)
      throws ExpressionUnavailableException {

    if (expression == null || expression.isBlank()) {
      throw new ExpressionUnavailableException(String.valueOf(expression), "the expression is empty");
    }

    Truth truth;
    try {
      Parser parser = new Parser(lex(expression), principal, asset, context);
      truth = parser.parseAll();
    } catch (SyntaxException e) {
      // The text itself is wrong, which is a different problem from a value
      // being absent, but it fails in the same direction: the engine is told it
      // could not be decided and picks the safe side for this policy's effect.
      throw new ExpressionUnavailableException(expression, e.getMessage());
    }

    return switch (truth) {
      case TRUE -> Result.TRUE;
      case FALSE -> Result.FALSE;
      case ROW -> Result.ROW_DEPENDENT;
      case UNKNOWN ->
          throw new ExpressionUnavailableException(
              expression, "an operand had no value for this principal and asset");
    };
  }

  // --------------------------------------------------------------- truth

  /** Kleene logic plus the row-dependent outcome the compilers can still use. */
  private enum Truth {
    TRUE,
    FALSE,
    ROW,
    UNKNOWN;

    static Truth of(boolean value) {
      return value ? TRUE : FALSE;
    }

    Truth negate() {
      return switch (this) {
        case TRUE -> FALSE;
        case FALSE -> TRUE;
        default -> this;
      };
    }

    Truth and(Truth other) {
      if (this == FALSE || other == FALSE) {
        return FALSE;
      }
      if (this == UNKNOWN || other == UNKNOWN) {
        return UNKNOWN;
      }
      return this == ROW || other == ROW ? ROW : TRUE;
    }

    Truth or(Truth other) {
      if (this == TRUE || other == TRUE) {
        return TRUE;
      }
      if (this == UNKNOWN || other == UNKNOWN) {
        return UNKNOWN;
      }
      return this == ROW || other == ROW ? ROW : FALSE;
    }
  }

  // -------------------------------------------------------------- operands

  /**
   * One side of a comparison, flattened to the values it actually has.
   *
   * @param values every value, because both sides can be multi-valued: a user
   *     with two branches, an asset in three domains
   * @param row true when the operand names a column, which no amount of
   *     evaluation here can resolve
   * @param literal true when the operand was written into the policy, so an
   *     empty one is a deliberately empty list rather than a missing value
   */
  private record Operand(List<String> values, boolean row, boolean literal) {

    static Operand of(List<String> values) {
      return new Operand(values, false, false);
    }

    static Operand literal(List<String> values) {
      return new Operand(values, false, true);
    }

    static Operand rowColumn() {
      return new Operand(List.of(), true, false);
    }

    boolean unknown() {
      return !literal && values.isEmpty();
    }
  }

  // ---------------------------------------------------------------- lexer

  private enum Kind {
    IDENT,
    STRING,
    NUMBER,
    PUNCT,
    END
  }

  private record Token(Kind kind, String text, int position) {}

  private static class SyntaxException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SyntaxException(String message) {
      super(message);
    }
  }

  private static final List<String> PUNCTUATION =
      List.of("==", "!=", ">=", "<=", "&&", "||", "(", ")", "[", "]", ",", ".", ">", "<", "!");

  private static List<Token> lex(String text) {
    List<Token> tokens = new ArrayList<>();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (c == '\'' || c == '"') {
        int end = i + 1;
        StringBuilder value = new StringBuilder();
        while (end < text.length() && text.charAt(end) != c) {
          // One escape, for quoting the quote. Backslash escapes are left out
          // on purpose: a policy that needs them is a policy doing something
          // this language should not be asked to express.
          if (text.charAt(end) == '\\' && end + 1 < text.length()) {
            value.append(text.charAt(end + 1));
            end += 2;
            continue;
          }
          value.append(text.charAt(end));
          end++;
        }
        if (end >= text.length()) {
          throw new SyntaxException("a quoted value is never closed");
        }
        tokens.add(new Token(Kind.STRING, value.toString(), i));
        i = end + 1;
        continue;
      }
      if (Character.isDigit(c)) {
        int end = i;
        while (end < text.length()
            && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) {
          end++;
        }
        tokens.add(new Token(Kind.NUMBER, text.substring(i, end), i));
        i = end;
        continue;
      }
      if (Character.isLetter(c) || c == '_') {
        int end = i;
        while (end < text.length()
            && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
          end++;
        }
        tokens.add(new Token(Kind.IDENT, text.substring(i, end), i));
        i = end;
        continue;
      }
      String punct = null;
      for (String candidate : PUNCTUATION) {
        if (text.startsWith(candidate, i)) {
          punct = candidate;
          break;
        }
      }
      if (punct == null) {
        throw new SyntaxException("'" + c + "' is not part of the policy expression language");
      }
      tokens.add(new Token(Kind.PUNCT, punct, i));
      i += punct.length();
    }
    tokens.add(new Token(Kind.END, "", text.length()));
    return tokens;
  }

  // --------------------------------------------------------------- parser

  /**
   * Recursive descent, evaluating as it goes.
   *
   * <p>Precedence, loosest first: {@code implies}, {@code ||}, {@code &&},
   * unary {@code !}, comparison. The word forms {@code and}, {@code or} and
   * {@code not} are accepted alongside the symbols because policy authors are
   * not all programmers and both spellings appear in the plan.
   */
  private static final class Parser {

    private final List<Token> tokens;
    private final Principal principal;
    private final AssetContext asset;
    private final RequestContext context;
    private int at;

    Parser(List<Token> tokens, Principal principal, AssetContext asset, RequestContext context) {
      this.tokens = tokens;
      this.principal = principal;
      this.asset = asset;
      this.context = context;
    }

    Truth parseAll() {
      Truth result = implication();
      if (peek().kind() != Kind.END) {
        throw new SyntaxException(
            "unexpected '" + peek().text() + "' after a complete expression");
      }
      return result;
    }

    /** {@code a implies b} is {@code !a || b}, and it reads better in a policy. */
    private Truth implication() {
      Truth left = disjunction();
      while (isWord("implies")) {
        next();
        Truth right = disjunction();
        left = left.negate().or(right);
      }
      return left;
    }

    private Truth disjunction() {
      Truth left = conjunction();
      while (isPunct("||") || isWord("or")) {
        next();
        left = left.or(conjunction());
      }
      return left;
    }

    private Truth conjunction() {
      Truth left = negation();
      while (isPunct("&&") || isWord("and")) {
        next();
        left = left.and(negation());
      }
      return left;
    }

    private Truth negation() {
      if (isPunct("!") || isNotKeyword()) {
        next();
        return negation().negate();
      }
      return primary();
    }

    /** {@code not} is a negation here, but {@code not in} belongs to a comparison. */
    private boolean isNotKeyword() {
      if (!isWord("not")) {
        return false;
      }
      Token following = tokens.get(Math.min(at + 1, tokens.size() - 1));
      return !(following.kind() == Kind.IDENT && following.text().equalsIgnoreCase("in"));
    }

    private Truth primary() {
      if (isPunct("(")) {
        next();
        Truth inner = implication();
        expectPunct(")");
        return inner;
      }
      return comparison();
    }

    private Truth comparison() {
      int start = at;
      Operand left = operand();

      String operator = comparisonOperator();
      if (operator == null) {
        // A bare true or false is a legitimate expression; anything else on its
        // own is a value where a question was expected, and reading it as true
        // would turn a typo into a grant.
        if (tokens.get(start).kind() == Kind.IDENT && left.literal() && left.values().size() == 1) {
          String only = left.values().get(0);
          if ("true".equalsIgnoreCase(only)) {
            return Truth.TRUE;
          }
          if ("false".equalsIgnoreCase(only)) {
            return Truth.FALSE;
          }
        }
        throw new SyntaxException(
            "expected a comparison after '" + tokens.get(start).text() + "'");
      }

      Operand right = operand();
      return compare(operator, left, right);
    }

    private String comparisonOperator() {
      for (String symbol : List.of("==", "!=", ">=", "<=", ">", "<")) {
        if (isPunct(symbol)) {
          next();
          return symbol;
        }
      }
      if (isWord("in")) {
        next();
        return "in";
      }
      if (isWord("not")) {
        Token following = tokens.get(Math.min(at + 1, tokens.size() - 1));
        if (following.kind() == Kind.IDENT && following.text().equalsIgnoreCase("in")) {
          next();
          next();
          return "not in";
        }
      }
      return null;
    }

    private Truth compare(String operator, Operand left, Operand right) {
      if (left.row() || right.row()) {
        return Truth.ROW;
      }
      if (left.unknown() || right.unknown()) {
        return Truth.UNKNOWN;
      }
      return switch (operator) {
        case "==" -> Truth.of(anyEqual(left, right));
        case "!=" -> Truth.of(!anyEqual(left, right));
        case "in" -> Truth.of(anyEqual(left, right));
        case "not in" -> Truth.of(!anyEqual(left, right));
        default -> Truth.of(anyOrdered(operator, left, right));
      };
    }

    /**
     * Existential on both sides, and FQN-aware so that a domain written as
     * {@code Finance.Risk} compares the way the rest of the engine compares it.
     */
    private static boolean anyEqual(Operand left, Operand right) {
      for (String l : left.values()) {
        for (String r : right.values()) {
          if (com.mfec.dac.common.Fqns.equal(l, r) || Comparisons.equal(l, r)) {
            return true;
          }
        }
      }
      return false;
    }

    private static boolean anyOrdered(String operator, Operand left, Operand right) {
      for (String l : left.values()) {
        for (String r : right.values()) {
          Integer c = Comparisons.compare(l, r);
          if (c == null) {
            continue;
          }
          boolean holds =
              switch (operator) {
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                case "<" -> c < 0;
                case "<=" -> c <= 0;
                default -> false;
              };
          if (holds) {
            return true;
          }
        }
      }
      return false;
    }

    // ------------------------------------------------------------ operands

    private Operand operand() {
      Token token = peek();
      if (token.kind() == Kind.STRING) {
        next();
        return Operand.literal(List.of(token.text()));
      }
      if (token.kind() == Kind.NUMBER) {
        next();
        return Operand.literal(List.of(token.text()));
      }
      if (isPunct("[")) {
        next();
        List<String> values = new ArrayList<>();
        while (!isPunct("]")) {
          Token value = next();
          if (value.kind() != Kind.STRING && value.kind() != Kind.NUMBER) {
            throw new SyntaxException("a list may only hold quoted values and numbers");
          }
          values.add(value.text());
          if (isPunct(",")) {
            next();
          }
        }
        expectPunct("]");
        return Operand.literal(values);
      }
      if (token.kind() == Kind.IDENT) {
        return reference();
      }
      throw new SyntaxException("expected a value or a reference, found '" + token.text() + "'");
    }

    private Operand reference() {
      String root = next().text().toLowerCase(Locale.ROOT);
      switch (root) {
        case "true":
        case "false":
          return Operand.literal(List.of(root));
        case "null":
          return Operand.literal(List.of());
        case "user":
          return user(memberName());
        case "asset":
          return assetFacet(memberName());
        case "row":
          // Resolved by the compiler against the row being returned, which is
          // the only place a row exists.
          memberName();
          return Operand.rowColumn();
        case "context":
          return requestContext(memberName());
        default:
          throw new SyntaxException(
              "'"
                  + root
                  + "' is not something a policy can refer to; use user, asset, row or context");
      }
    }

    /** The member after the dot, plus its argument when it is a call. */
    private String memberName() {
      expectPunct(".");
      Token member = next();
      if (member.kind() != Kind.IDENT) {
        throw new SyntaxException("expected a name after '.'");
      }
      if (isPunct("(")) {
        next();
        Token argument = next();
        if (argument.kind() != Kind.STRING) {
          throw new SyntaxException(
              "the argument of " + member.text() + "() has to be a quoted name");
        }
        expectPunct(")");
        return member.text().toLowerCase(Locale.ROOT) + ":" + argument.text();
      }
      return member.text().toLowerCase(Locale.ROOT);
    }

    private Operand user(String member) {
      if (member.startsWith("attr:")) {
        return Operand.of(principal.attributeValues(member.substring(5), null));
      }
      return switch (member) {
        case "id", "name", "username" -> Operand.of(nonNull(principal.id()));
        case "email" -> {
          List<String> values = new ArrayList<>(nonNull(principal.email()));
          values.addAll(principal.attributeValues("email", null));
          yield Operand.of(values);
        }
        case "roles", "role" -> Operand.of(List.copyOf(principal.roles()));
        case "teams", "team" -> Operand.of(List.copyOf(principal.teams()));
        case "groups", "group" -> Operand.of(List.copyOf(principal.groups()));
        // Anything else is an attribute, which is what makes the language open
        // to whatever the directory carries without a change here.
        default -> Operand.of(principal.attributeValues(member, null));
      };
    }

    private Operand assetFacet(String member) {
      if (member.startsWith("prop:")) {
        return Operand.of(
            values(asset.facetValues(FacetCondition.FacetType.CUSTOM_PROPERTY, member.substring(5))));
      }
      if ("fqn".equals(member)) {
        return Operand.of(nonNull(asset.fqn()));
      }
      FacetCondition.FacetType type = facetType(member);
      if (type == null) {
        throw new SyntaxException("assets have no facet called '" + member + "'");
      }
      return Operand.of(values(asset.facetValues(type, null)));
    }

    private Operand requestContext(String member) {
      return switch (member) {
        case "purpose" -> Operand.of(nonNull(context.purpose()));
        case "ip" -> Operand.of(nonNull(context.ip()));
        default -> throw new SyntaxException("the request context has no '" + member + "'");
      };
    }

    /**
     * Accepts the facet names the selector language uses, singular or plural,
     * so that a policy reads the same in an expression as it does in a selector.
     */
    private static FacetCondition.FacetType facetType(String member) {
      String name =
          switch (member) {
            case "classification" -> "classifications";
            case "tag" -> "tags";
            case "glossary" -> "glossaries";
            case "term" -> "terms";
            case "domain" -> "domains";
            case "dataproduct", "dataproducts" -> "dataProducts";
            case "owner" -> "owners";
            case "columnname" -> "columnName";
            case "datatype" -> "dataType";
            default -> member;
          };
      for (FacetCondition.FacetType type : FacetCondition.FacetType.values()) {
        if (type.value().equalsIgnoreCase(name)) {
          return type;
        }
      }
      return null;
    }

    private static List<String> values(List<FacetValue> facets) {
      List<String> out = new ArrayList<>(facets.size());
      for (FacetValue facet : facets) {
        if (facet.value() != null) {
          out.add(facet.value());
        }
      }
      return out;
    }

    private static List<String> nonNull(String value) {
      return value == null || value.isBlank() ? List.of() : List.of(value);
    }

    // -------------------------------------------------------------- tokens

    private Token peek() {
      return tokens.get(at);
    }

    private Token next() {
      Token token = tokens.get(at);
      if (token.kind() != Kind.END) {
        at++;
      }
      return token;
    }

    private boolean isPunct(String text) {
      Token token = peek();
      return token.kind() == Kind.PUNCT && token.text().equals(text);
    }

    private boolean isWord(String text) {
      Token token = peek();
      return token.kind() == Kind.IDENT && token.text().equalsIgnoreCase(text);
    }

    private void expectPunct(String text) {
      if (!isPunct(text)) {
        throw new SyntaxException("expected '" + text + "', found '" + peek().text() + "'");
      }
      next();
    }
  }
}
