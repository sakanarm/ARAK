package com.mfec.dac.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * The text of what a client sends: split into statements, parameters put in,
 * and each statement sorted by what the gateway does with it.
 *
 * <p>Everything here reads SQL lexically, the way PostgreSQL's own scanner
 * does: quoted strings, quoted identifiers, dollar-quoted bodies and comments
 * are skipped as units, so a semicolon or a {@code $1} inside one is never
 * taken for a statement end or a parameter. Nothing here decides what a person
 * may read; that is the query proxy's job, and the proxy re-reads every
 * statement sent to it from scratch.
 */
final class SqlText {

  /** What the gateway does with a statement. */
  enum Kind {
    /** Nothing but whitespace and comments. */
    EMPTY,
    /** SELECT, WITH, VALUES or TABLE: answered by the proxy or the sandbox. */
    QUERY,
    /** SET: remembered for the session, never sent anywhere. */
    SET,
    RESET,
    SHOW,
    BEGIN,
    COMMIT,
    ROLLBACK,
    /** SAVEPOINT, RELEASE, ROLLBACK TO: accepted; there is nothing to undo. */
    SAVEPOINT,
    /** DISCARD, DEALLOCATE, LISTEN, UNLISTEN, CLOSE: accepted and ignored. */
    IGNORED,
    /** Everything else, which would change something: refused, read-only. */
    OTHER
  }

  /** Where a query is answered. */
  enum Route {
    /** The source, through the query proxy. */
    DATA,
    /** The sandbox: the system catalog, or no table at all. */
    CATALOG,
    /** Both kinds in one statement: refused, because neither can answer it. */
    MIXED
  }

  private static final Set<String> CATALOG_SCHEMAS = Set.of("pg_catalog", "information_schema");

  private static final Pattern CATALOG_MARK =
      Pattern.compile("(?i)\\b(pg_catalog|information_schema|pg_[a-z_]+)\\b");

  private SqlText() {}

  // ------------------------------------------------------------- splitting

  /** The statements in a simple-query string, without their semicolons; blanks left out. */
  static List<String> split(String sql) {
    List<String> out = new ArrayList<>();
    int start = 0;
    int i = 0;
    int n = sql.length();
    while (i < n) {
      int skip = skipQuoted(sql, i);
      if (skip > i) {
        i = skip;
        continue;
      }
      if (sql.charAt(i) == ';') {
        add(out, sql.substring(start, i));
        start = i + 1;
      }
      i++;
    }
    add(out, sql.substring(start));
    return out;
  }

  private static void add(List<String> out, String statement) {
    if (kind(statement) != Kind.EMPTY) {
      out.add(statement.strip());
    }
  }

  /**
   * If a quoted unit or a comment starts at {@code i}, the index just after it;
   * otherwise {@code i}. An unterminated one runs to the end.
   */
  static int skipQuoted(String s, int i) {
    int n = s.length();
    char c = s.charAt(i);
    if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
      int end = s.indexOf('\n', i);
      return end < 0 ? n : end + 1;
    }
    if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
      int depth = 1;
      int j = i + 2;
      while (j < n && depth > 0) {
        if (s.startsWith("/*", j)) {
          depth++;
          j += 2;
        } else if (s.startsWith("*/", j)) {
          depth--;
          j += 2;
        } else {
          j++;
        }
      }
      return j;
    }
    if (c == '\'') {
      boolean escapes = i > 0 && (s.charAt(i - 1) == 'E' || s.charAt(i - 1) == 'e')
          && (i < 2 || !Character.isLetterOrDigit(s.charAt(i - 2)));
      int j = i + 1;
      while (j < n) {
        char d = s.charAt(j);
        if (escapes && d == '\\') {
          j += 2;
          continue;
        }
        if (d == '\'') {
          if (j + 1 < n && s.charAt(j + 1) == '\'') {
            j += 2;
            continue;
          }
          return j + 1;
        }
        j++;
      }
      return n;
    }
    if (c == '"') {
      int j = i + 1;
      while (j < n) {
        if (s.charAt(j) == '"') {
          if (j + 1 < n && s.charAt(j + 1) == '"') {
            j += 2;
            continue;
          }
          return j + 1;
        }
        j++;
      }
      return n;
    }
    if (c == '$' && (i == 0 || !isIdentChar(s.charAt(i - 1)))) {
      int j = i + 1;
      while (j < n && (Character.isLetter(s.charAt(j)) || s.charAt(j) == '_'
          || (j > i + 1 && Character.isDigit(s.charAt(j))))) {
        j++;
      }
      if (j < n && s.charAt(j) == '$') {
        String tag = s.substring(i, j + 1);
        int end = s.indexOf(tag, j + 1);
        return end < 0 ? n : end + tag.length();
      }
    }
    return i;
  }

  private static boolean isIdentChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }

  // ------------------------------------------------------------- parameters

  /** The highest {@code $n} in a statement, 0 when it has none. */
  static int parameterCount(String sql) {
    int max = 0;
    int i = 0;
    int n = sql.length();
    while (i < n) {
      int skip = skipQuoted(sql, i);
      if (skip > i) {
        i = skip;
        continue;
      }
      int[] p = parameterAt(sql, i);
      if (p != null) {
        max = Math.max(max, p[0]);
        i = p[1];
        continue;
      }
      i++;
    }
    return max;
  }

  /**
   * The statement with each {@code $n} replaced by {@code literals.get(n-1)},
   * which the caller has already made into SQL: a quoted string, a bare
   * number, or NULL.
   */
  static String substitute(String sql, List<String> literals) throws PgError {
    StringBuilder out = new StringBuilder(sql.length() + 32);
    int i = 0;
    int n = sql.length();
    while (i < n) {
      int skip = skipQuoted(sql, i);
      if (skip > i) {
        out.append(sql, i, skip);
        i = skip;
        continue;
      }
      int[] p = parameterAt(sql, i);
      if (p != null) {
        if (p[0] < 1 || p[0] > literals.size()) {
          throw new PgError(
              PgError.PROTOCOL_VIOLATION,
              "There is no parameter $" + p[0] + "; the statement was given " + literals.size());
        }
        // Spaces on both sides, so a value can never join the token before or after it.
        out.append(' ').append(literals.get(p[0] - 1)).append(' ');
        i = p[1];
        continue;
      }
      out.append(sql.charAt(i));
      i++;
    }
    return out.toString();
  }

  /** {number, end} when a {@code $n} parameter starts at {@code i}. */
  private static int[] parameterAt(String s, int i) {
    if (s.charAt(i) != '$' || (i > 0 && isIdentChar(s.charAt(i - 1)))) {
      return null;
    }
    int j = i + 1;
    while (j < s.length() && Character.isDigit(s.charAt(j))) {
      j++;
    }
    if (j == i + 1 || j - i > 6) {
      return null;
    }
    return new int[] {Integer.parseInt(s.substring(i + 1, j)), j};
  }

  /**
   * A value as a SQL literal: bare when the parameter is a number and the
   * value is one, otherwise a standard string with its quotes doubled.
   */
  static String literal(String value, int typeOid) {
    if (value == null) {
      return "NULL";
    }
    if (PgTypes.numeric(typeOid) && PgTypes.looksLikeNumber(value)) {
      return value;
    }
    return "'" + value.replace("'", "''") + "'";
  }

  // ---------------------------------------------------------- classifying

  /** The words a statement starts with, upper-cased, comments and parentheses skipped. */
  static List<String> leadingWords(String sql, int howMany) {
    List<String> words = new ArrayList<>();
    int i = 0;
    int n = sql.length();
    while (i < n && words.size() < howMany) {
      int skip = skipQuoted(sql, i);
      char c = sql.charAt(i);
      if (skip > i) {
        if (c == '-' || c == '/') {
          i = skip;
          continue;
        }
        break;
      }
      if (Character.isWhitespace(c) || c == '(') {
        i++;
        continue;
      }
      if (Character.isLetter(c) || c == '_') {
        int j = i;
        while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
          j++;
        }
        words.add(sql.substring(i, j).toUpperCase(Locale.ROOT));
        i = j;
        continue;
      }
      break;
    }
    return words;
  }

  static Kind kind(String sql) {
    List<String> w = leadingWords(sql, 3);
    if (w.isEmpty()) {
      return isBlank(sql) ? Kind.EMPTY : Kind.OTHER;
    }
    String first = w.get(0);
    String second = w.size() > 1 ? w.get(1) : "";
    return switch (first) {
      case "SELECT", "WITH", "VALUES", "TABLE" -> Kind.QUERY;
      case "SET" -> Kind.SET;
      case "RESET" -> Kind.RESET;
      case "SHOW" -> Kind.SHOW;
      case "BEGIN", "START" -> Kind.BEGIN;
      case "COMMIT", "END" -> Kind.COMMIT;
      case "ROLLBACK", "ABORT" ->
          second.equals("TO") || (second.equals("TRANSACTION") && w.size() > 2 && w.get(2).equals("TO"))
                  || (second.equals("WORK") && w.size() > 2 && w.get(2).equals("TO"))
              ? Kind.SAVEPOINT
              : Kind.ROLLBACK;
      case "SAVEPOINT", "RELEASE" -> Kind.SAVEPOINT;
      case "DISCARD", "DEALLOCATE", "LISTEN", "UNLISTEN", "CLOSE" -> Kind.IGNORED;
      default -> Kind.OTHER;
    };
  }

  private static boolean isBlank(String sql) {
    int i = 0;
    while (i < sql.length()) {
      int skip = skipQuoted(sql, i);
      char c = sql.charAt(i);
      if (skip > i && (c == '-' || c == '/')) {
        i = skip;
        continue;
      }
      if (!Character.isWhitespace(c) && c != ';') {
        return false;
      }
      i++;
    }
    return true;
  }

  /** The tag CommandComplete carries for a statement that is only acknowledged. */
  static String tag(String sql, Kind kind) {
    List<String> w = leadingWords(sql, 2);
    String first = w.isEmpty() ? "" : w.get(0);
    return switch (kind) {
      case BEGIN -> first.equals("START") ? "START TRANSACTION" : "BEGIN";
      case COMMIT -> "COMMIT";
      case ROLLBACK -> "ROLLBACK";
      case SAVEPOINT -> first.equals("ROLLBACK") || first.equals("ABORT") ? "ROLLBACK" : first;
      case SET -> "SET";
      case RESET -> "RESET";
      case IGNORED ->
          first.equals("DISCARD") || first.equals("DEALLOCATE")
              ? (w.size() > 1 && w.get(1).equals("ALL") ? first + " ALL" : first)
              : first;
      default -> first;
    };
  }

  /**
   * Whether a query reads the source or only the catalog.
   *
   * <p>A query naming only {@code pg_catalog}, {@code information_schema} or
   * {@code pg_*} relations, or none at all, is about the server and goes to the
   * sandbox, which holds no data. Anything naming another table goes to the
   * proxy, which refuses what it cannot govern. A query the parser cannot read
   * goes to the sandbox only when it visibly names the catalog; otherwise to
   * the proxy, to be refused there with the parser's own reason.
   */
  static Route route(String sql) {
    Set<String> tables;
    try {
      Statement statement = CCJSqlParserUtil.parse(sql);
      tables = new TablesNamesFinder().getTables(statement);
    } catch (Exception | StackOverflowError e) {
      return CATALOG_MARK.matcher(stripQuoted(sql)).find() ? Route.CATALOG : Route.DATA;
    }
    boolean catalog = false;
    boolean data = false;
    for (String name : tables) {
      if (isCatalogName(name)) {
        catalog = true;
      } else {
        data = true;
      }
    }
    if (catalog && data) {
      return Route.MIXED;
    }
    return data ? Route.DATA : Route.CATALOG;
  }

  static boolean isCatalogName(String qualified) {
    String[] parts = qualified.replace("\"", "").toLowerCase(Locale.ROOT).split("\\.");
    if (parts.length >= 2) {
      return CATALOG_SCHEMAS.contains(parts[parts.length - 2]);
    }
    return parts[0].startsWith("pg_");
  }

  /** The statement with quoted strings and comments blanked, for a lexical look. */
  static String stripQuoted(String sql) {
    StringBuilder out = new StringBuilder(sql.length());
    int i = 0;
    while (i < sql.length()) {
      int skip = skipQuoted(sql, i);
      if (skip > i) {
        char c = sql.charAt(i);
        // A quoted identifier is still a name; only strings and comments go.
        if (c == '"') {
          out.append(sql, i + 1, Math.max(i + 1, skip - 1));
        } else {
          out.append(' ');
        }
        i = skip;
        continue;
      }
      out.append(sql.charAt(i));
      i++;
    }
    return out.toString();
  }

  // -------------------------------------------------------------- SET/SHOW

  /** A SET statement read as a name and a value. */
  record Setting(String name, String value) {}

  private static final Pattern SET =
      Pattern.compile(
          "(?is)^\\s*SET\\s+(?:SESSION\\s+|LOCAL\\s+)?(?!(?:SESSION\\s+)?CHARACTERISTICS\\b|TRANSACTION\\b)"
              + "(TIME\\s+ZONE|[a-z_][a-z0-9_.]*)\\s*(?:TO|=)?\\s*(.*?)\\s*;?\\s*$");

  /** The name and value of a SET; null for SET TRANSACTION and the like, which change nothing here. */
  static Setting parseSet(String sql) {
    Matcher m = SET.matcher(stripComments(sql));
    if (!m.matches()) {
      return null;
    }
    String name = m.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    if (name.equals("timezone")) {
      name = "timezone";
    }
    return new Setting(name, m.group(2));
  }

  /** The parameter a SHOW or RESET names, lower-cased. */
  static String parameterOf(String sql) {
    String rest = stripComments(sql).strip();
    rest = rest.replaceFirst("(?i)^(SHOW|RESET)\\s+", "").replaceFirst(";\\s*$", "").strip();
    rest = rest.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    if (rest.equals("time zone")) {
      return "timezone";
    }
    return rest.replace("\"", "");
  }

  /**
   * The first schema in a {@code search_path} value that is a real schema
   * name, unquoted; null when there is none.
   */
  static String firstSchema(String value) {
    for (String part : value.split(",")) {
      String p = part.strip();
      if (p.startsWith("'") && p.endsWith("'") && p.length() >= 2) {
        p = p.substring(1, p.length() - 1).replace("''", "'");
      }
      if (p.startsWith("\"") && p.endsWith("\"") && p.length() >= 2) {
        p = p.substring(1, p.length() - 1).replace("\"\"", "\"");
      } else {
        p = p.toLowerCase(Locale.ROOT);
      }
      if (p.isEmpty() || p.equals("$user") || p.equals("pg_catalog") || p.equals("pg_temp")) {
        continue;
      }
      return p;
    }
    return null;
  }

  /** A SET value without its quotes: {@code 'abc'} and {@code abc} both read abc. */
  static String unquote(String value) {
    String v = value == null ? "" : value.strip();
    if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
      return v.substring(1, v.length() - 1).replace("''", "'");
    }
    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
      return v.substring(1, v.length() - 1).replace("\"\"", "\"");
    }
    return v;
  }

  private static String stripComments(String sql) {
    StringBuilder out = new StringBuilder(sql.length());
    int i = 0;
    while (i < sql.length()) {
      int skip = skipQuoted(sql, i);
      char c = sql.charAt(i);
      if (skip > i && (c == '-' || c == '/')) {
        out.append(' ');
        i = skip;
        continue;
      }
      if (skip > i) {
        out.append(sql, i, skip);
        i = skip;
        continue;
      }
      out.append(c);
      i++;
    }
    return out.toString();
  }
}
