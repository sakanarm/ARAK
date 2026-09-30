package com.mfec.dac.proxy;

import java.util.Locale;

/**
 * The third gate: the text that is about to be sent must mean to the source
 * what it meant to the parser that checked it.
 *
 * <p>The other two gates reason over the parser's tree. What reaches the source
 * is text, and the source reads it with a lexer of its own. Wherever the two
 * disagree about where a string, a quoted name or a comment ends, a statement
 * can carry a table inside what the parser took for a string: the parser finds
 * nothing to enforce there, and the source runs it. On PostgreSQL {@code
 * E'a\''} is one string to the server and one string plus the start of another
 * to the parser, and everything after it changes sides.
 *
 * <p>So the text is read once more, the way the source reads it, and refused if
 * it holds anything the two could take differently:
 *
 * <ul>
 *   <li>a comment or an optimizer hint. The parser drops a comment, so none a
 *       caller wrote gets this far; a hint it keeps, and a hint is a comment
 *       that PostgreSQL and SQL Server nest and the parser does not, and that
 *       MySQL can change the session from;
 *   <li>{@code #} on MySQL, where it starts a comment and the parser takes it
 *       for part of a name;
 *   <li>{@code $} on PostgreSQL, where it can open a dollar-quoted string;
 *   <li>a string with a prefix other than {@code N}, {@code X} or {@code B}: an
 *       escape string, or a character set introducer;
 *   <li>a backtick where it is not a quote, a JDBC escape, a statement
 *       separator, a stray backslash, and {@code @} where that is a variable.
 * </ul>
 *
 * <p>What is left is plain strings and quoted names, which end at the same
 * character for everybody provided a backslash is an ordinary character inside
 * them. That is the default on SQL Server, and is made true of every session
 * this platform opens on the other two ({@code SourceEngine#sessionSetup}).
 *
 * <p>Refusing is the only thing this does. It does not repair the text: a
 * statement changed after it was checked is a statement nobody checked.
 */
final class LexicalGate {

  private LexicalGate() {}

  private static final String UNREADABLE =
      "This statement could not be read in full, so it cannot be enforced and will not be run";

  /**
   * @param dialect {@code POSTGRES}, {@code SQLSERVER} or {@code MYSQL}, as
   *     {@code SqlDialect#name}; any other is held to every rule here
   * @throws QueryRewriter.RefusedException when the text could be read two ways
   */
  static void check(String dialect, String sql) {
    boolean postgres = "POSTGRES".equals(dialect);
    boolean sqlServer = "SQLSERVER".equals(dialect);
    boolean mySql = "MYSQL".equals(dialect);

    int length = sql.length();
    int i = 0;
    while (i < length) {
      char c = sql.charAt(i);
      char next = i + 1 < length ? sql.charAt(i + 1) : '\0';
      switch (c) {
        case '\'' -> {
          requirePlainString(sql, i);
          i = closing(sql, i, '\'');
        }
        case '"' -> i = closing(sql, i, '"');
        case '`' -> {
          if (!mySql) {
            throw new QueryRewriter.RefusedException(
                "A backtick does not quote a name on this source. Quote names with "
                    + (sqlServer ? "[...]" : "\"...\"")
                    + " and try again");
          }
          i = closing(sql, i, '`');
        }
        case '[' -> i = sqlServer ? closing(sql, i, ']') : i + 1;
        case '-' -> {
          if (next == '-') {
            throw comment();
          }
          i++;
        }
        case '/' -> {
          if (next == '*') {
            throw comment();
          }
          i++;
        }
        case '*' -> {
          if (next == '/') {
            throw comment();
          }
          i++;
        }
        case '#' -> {
          if (!postgres && !sqlServer) {
            throw new QueryRewriter.RefusedException(
                "# starts a comment on this source, so it cannot be used in a name or as an"
                    + " operator. Quote the name and try again");
          }
          i++;
        }
        case '$' -> {
          if (!sqlServer && !mySql) {
            throw new QueryRewriter.RefusedException(
                "$ can start a dollar-quoted string on this source, so it is not allowed outside"
                    + " a string or a quoted name. Write strings as '...' and try again");
          }
          i++;
        }
        case '@' -> {
          if (!postgres) {
            throw new QueryRewriter.RefusedException(
                "Session variables cannot be read or set through the query proxy");
          }
          i++;
        }
        case '{', '}' ->
            throw new QueryRewriter.RefusedException(
                "JDBC escapes ({...}) are not allowed; write the value or call the function"
                    + " directly");
        case ';', '\\' -> throw new QueryRewriter.RefusedException(UNREADABLE);
        default -> i++;
      }
    }
  }

  /**
   * The index just past the quote that closes the one at {@code open}. A
   * doubled closing quote is the quote itself, on every engine here.
   */
  private static int closing(String sql, int open, char close) {
    int i = open + 1;
    while (i < sql.length()) {
      if (sql.charAt(i) == close) {
        if (i + 1 < sql.length() && sql.charAt(i + 1) == close) {
          i += 2;
          continue;
        }
        return i + 1;
      }
      i++;
    }
    throw new QueryRewriter.RefusedException(UNREADABLE);
  }

  /**
   * Refuses a string whose opening quote has letters run up against it, other
   * than the three prefixes that change nothing about where it ends.
   */
  private static void requirePlainString(String sql, int quote) {
    int start = quote;
    while (start > 0 && namePart(sql.charAt(start - 1))) {
      start--;
    }
    String prefix = sql.substring(start, quote).toUpperCase(Locale.ROOT);
    if (prefix.isEmpty() || prefix.equals("N") || prefix.equals("X") || prefix.equals("B")) {
      return;
    }
    throw new QueryRewriter.RefusedException(
        "A string written with a prefix ("
            + sql.substring(start, quote)
            + "'...') is not allowed through the query proxy, because the source may read its"
            + " escapes differently. Write it as a plain '...' string and try again");
  }

  private static boolean namePart(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }

  private static QueryRewriter.RefusedException comment() {
    return new QueryRewriter.RefusedException(
        "Optimizer hints and comments cannot be sent to the source through the query proxy."
            + " Take the hint out and try again");
  }
}
