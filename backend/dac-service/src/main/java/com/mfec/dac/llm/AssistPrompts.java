package com.mfec.dac.llm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the assistant is allowed to say, and what it is allowed to be told
 * (FR-2.6).
 *
 * <p>Everything here is a pure function over strings so that the two rules this
 * feature rests on can be tested without a gateway, a network or a key:
 *
 * <ol>
 *   <li><b>Metadata only, never rows.</b> {@link #schemaBrief} is built from
 *       the catalog cache — table names, column names, types, descriptions and
 *       the tags that reach them. There is no code path from a result set into
 *       a prompt, and there is not meant to be one. A model that has seen one
 *       row of a table it was asked to write SQL for has read data on behalf of
 *       whoever asked, outside every policy this product exists to apply.
 *   <li><b>Drafts only, never an act.</b> The assistant answers with text. A
 *       statement it writes is put in the editor for somebody to read and run;
 *       a policy it writes is put in the builder for somebody to save. Neither
 *       endpoint writes anything, and {@link #looksReadOnly} keeps a suggestion
 *       that is not a query from landing in a box whose button says Run.
 * </ol>
 *
 * <p>The prompts say both rules out loud as well. That is belt and braces: the
 * enforcement is that nothing but metadata is ever passed in, and that nothing
 * the model returns is applied — but a model told what it is for produces
 * better answers, and a reviewer reading the prompt can see the intent.
 */
public final class AssistPrompts {

  private AssistPrompts() {}

  /** One column, as the catalog knows it. No values, by construction. */
  public record Column(String name, String dataType, String description, List<String> tags) {}

  /** One table, as the catalog knows it. No values, by construction. */
  public record Table(String fqn, String description, List<Column> columns) {}

  // ------------------------------------------------------------------ context

  /**
   * The tables in play, written out for a model to read.
   *
   * <p>Deliberately a compact text block rather than JSON: it is read, not
   * parsed, and the same budget spent on braces buys several more columns.
   * Tags are included because they are how a governed column announces itself —
   * a model that can see {@code email PII.Sensitive} stops volunteering it in a
   * {@code SELECT *} and starts naming columns.
   */
  public static String schemaBrief(List<Table> tables) {
    StringBuilder out = new StringBuilder();
    for (Table table : tables) {
      out.append(table.fqn());
      if (notBlank(table.description())) {
        out.append("  -- ").append(oneLine(table.description(), 160));
      }
      out.append('\n');
      for (Column column : table.columns()) {
        out.append("    ")
            .append(column.name())
            .append(' ')
            .append(column.dataType() == null ? "unknown" : column.dataType());
        if (column.tags() != null && !column.tags().isEmpty()) {
          out.append("  [").append(String.join(", ", column.tags())).append(']');
        }
        if (notBlank(column.description())) {
          out.append("  -- ").append(oneLine(column.description(), 120));
        }
        out.append('\n');
      }
      out.append('\n');
    }
    return out.toString().trim();
  }

  // ---------------------------------------------------------------- NL -> SQL

  /**
   * The standing instruction for turning a question into a statement.
   *
   * <p>It does not promise the caller will see what the statement selects, and
   * it must not: the statement goes to the proxy, which rewrites it against
   * that person's policy before it reaches the database. A model that tried to
   * anticipate masking would produce SQL that masks twice.
   */
  public static String sqlSystem(String engine) {
    return "You write one SQL SELECT statement for a "
        + (notBlank(engine) ? engine : "SQL")
        + " database, from a catalogue of table and column names.\n"
        + "Rules:\n"
        + "- Answer with the statement and nothing else. No prose, no fences, no explanation.\n"
        + "- Exactly one statement. SELECT, or WITH followed by SELECT. Never INSERT, UPDATE,"
        + " DELETE, MERGE, CREATE, ALTER, DROP, GRANT, REVOKE, TRUNCATE or CALL.\n"
        + "- Use only the tables and columns listed. Write table names exactly as they are"
        + " listed. Never invent a column.\n"
        + "- Name the columns you want. Avoid SELECT *.\n"
        + "- A column marked with a tag is governed. You may select it; the platform decides"
        + " whether the reader sees it, and will mask or drop it after you. Do not try to mask,"
        + " hash or redact anything yourself.\n"
        + "- If the question cannot be answered from these tables, answer with the single word"
        + " UNANSWERABLE.";
  }

  public static String sqlUser(String question, String brief) {
    return "Tables:\n\n" + brief + "\n\nQuestion: " + question.trim();
  }

  // ------------------------------------------------------------ policy drafts

  /**
   * The standing instruction for drafting a policy.
   *
   * <p>The JSON Schema is handed over verbatim by the caller rather than
   * described here, so the contract the model is held to is the same file the
   * engine's classes are generated from. A prose summary of a schema is a
   * second source of truth, and it would be the one that rots.
   */
  public static String policySystem(String schemas) {
    return "You draft an access policy for ARAK, a data access control platform, as JSON.\n"
        + "Rules:\n"
        + "- Answer with one JSON object and nothing else. No prose, no fences.\n"
        + "- It must validate against the schema below.\n"
        + "- Always set \"lifecycleState\": \"DRAFT\". You are writing a proposal for a person to"
        + " review; you never activate anything, and a draft you mark ACTIVE is rejected.\n"
        + "- Prefer a selector over a list of names: a policy written against tags, terms or"
        + " domains keeps covering assets that are added later.\n"
        + "- Use only facets and masking functions the schema allows.\n"
        + "- Put what you were unsure about in \"description\", in one sentence, so the reviewer"
        + " knows what to check.\n\n"
        + "Schema:\n\n"
        + schemas;
  }

  public static String policyUser(String intent, String brief) {
    StringBuilder out = new StringBuilder("Draft a policy for: ").append(intent.trim());
    if (notBlank(brief)) {
      out.append("\n\nTables it may need to refer to:\n\n").append(brief);
    }
    return out.toString();
  }

  // ------------------------------------------------------------- reading back

  /**
   * Pulls the statement out of an answer.
   *
   * <p>Models fence code even when told not to, and some open with a sentence.
   * Stripping that here rather than showing it to the reader is the difference
   * between an editor with SQL in it and an editor with an apology in it.
   */
  public static String extractSql(String answer) {
    String text = stripFence(answer);
    if (text.isEmpty()) {
      return "";
    }
    // A leading sentence, when one survived: the statement starts at the first
    // word that can begin one, so anything before that is the model talking.
    int start = firstKeyword(text);
    if (start > 0) {
      text = text.substring(start);
    }
    return text.trim();
  }

  /** Pulls the object out of an answer, fenced or not. */
  public static String extractJson(String answer) {
    String text = stripFence(answer);
    int open = text.indexOf('{');
    int close = text.lastIndexOf('}');
    if (open < 0 || close <= open) {
      return "";
    }
    return text.substring(open, close + 1).trim();
  }

  /** True when the model said, in so many words, that it could not answer. */
  public static boolean isRefusal(String sql) {
    return sql.toUpperCase(Locale.ROOT).replace(".", "").trim().equals("UNANSWERABLE");
  }

  /**
   * One read-only statement, or nothing.
   *
   * <p>The proxy refuses anything that is not a query anyway, so this is not
   * the thing standing between a suggestion and a dropped table. It is here
   * because the suggestion lands in a box with a Run button next to it, and an
   * UPDATE sitting there — however certain the refusal downstream — is an
   * invitation nobody should have to decline.
   */
  public static boolean looksReadOnly(String sql) {
    String text = sql.trim();
    if (text.isEmpty()) {
      return false;
    }
    String bare = withoutStringsAndComments(text).toUpperCase(Locale.ROOT);
    for (String forbidden : FORBIDDEN) {
      if (containsWord(bare, forbidden)) {
        return false;
      }
    }
    String head = bare.stripLeading();
    if (!head.startsWith("SELECT") && !head.startsWith("WITH") && !head.startsWith("(")) {
      return false;
    }
    // A second statement is a second chance to do something else. One trailing
    // semicolon is the habit of every SQL tool there is, so it is allowed; a
    // semicolon with anything after it is not.
    int semicolon = bare.indexOf(';');
    return semicolon < 0 || bare.substring(semicolon + 1).isBlank();
  }

  private static final List<String> FORBIDDEN =
      List.of(
          "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "CREATE", "ALTER", "DROP", "TRUNCATE",
          "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "COPY", "VACUUM", "ANALYZE", "SET",
          "COMMIT", "ROLLBACK", "BEGIN", "DO", "INTO");

  // ---------------------------------------------------------------- retrieval

  /**
   * The tables a question is most likely about, best first.
   *
   * <p>Word overlap, not embeddings. Every table in a source would be a better
   * answer if it fitted, and for a small catalogue it does — this exists for
   * the case where it does not, to keep the prompt from being mostly tables
   * nobody asked about. Ties keep catalogue order, so the result is stable.
   */
  public static List<Table> mostRelevant(List<Table> tables, String question, int limit) {
    Set<String> asked = words(question);
    record Scored(Table table, int score, int order) {}
    List<Scored> scored = new ArrayList<>();
    int order = 0;
    for (Table table : tables) {
      int score = 0;
      for (String word : words(table.fqn())) {
        if (asked.contains(word)) {
          // The table's own name is the strongest signal there is; a column
          // named in the question is nearly as good.
          score += 4;
        }
      }
      for (Column column : table.columns()) {
        if (asked.contains(normalise(column.name()))) {
          score += 2;
        }
      }
      for (String word : words(table.description())) {
        if (asked.contains(word)) {
          score += 1;
        }
      }
      scored.add(new Scored(table, score, order++));
    }
    scored.sort((a, b) -> a.score() == b.score() ? a.order() - b.order() : b.score() - a.score());
    List<Table> out = new ArrayList<>();
    for (Scored row : scored) {
      if (out.size() >= limit) {
        break;
      }
      out.add(row.table());
    }
    return out;
  }

  // ------------------------------------------------------------------ helpers

  private static Set<String> words(String text) {
    Set<String> out = new LinkedHashSet<>();
    if (text == null) {
      return out;
    }
    for (String part : text.split("[^\\p{L}\\p{N}]+")) {
      String word = normalise(part);
      if (word.length() > 2) {
        out.add(word);
      }
    }
    return out;
  }

  /** Lower case, and singular-ish: "customers" must find table "customer". */
  private static String normalise(String word) {
    String lower = word == null ? "" : word.toLowerCase(Locale.ROOT);
    return lower.length() > 3 && lower.endsWith("s") ? lower.substring(0, lower.length() - 1) : lower;
  }

  private static String stripFence(String answer) {
    if (answer == null) {
      return "";
    }
    String text = answer.trim();
    int fence = text.indexOf("```");
    if (fence < 0) {
      return text;
    }
    int bodyStart = text.indexOf('\n', fence);
    if (bodyStart < 0) {
      return text.substring(fence + 3).trim();
    }
    int end = text.indexOf("```", bodyStart);
    return (end < 0 ? text.substring(bodyStart) : text.substring(bodyStart, end)).trim();
  }

  private static int firstKeyword(String text) {
    String upper = text.toUpperCase(Locale.ROOT);
    int select = upper.indexOf("SELECT");
    int with = upper.indexOf("WITH");
    if (select < 0) {
      return Math.max(with, 0);
    }
    if (with < 0) {
      return select;
    }
    return Math.min(select, with);
  }

  /**
   * The statement with its literals and comments blanked out.
   *
   * <p>So that {@code WHERE note = 'please update'} is still a SELECT, and a
   * {@code -- drop this later} does not make one look like a DROP.
   */
  private static String withoutStringsAndComments(String sql) {
    StringBuilder out = new StringBuilder(sql.length());
    int i = 0;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      if (c == '\'' || c == '"') {
        char quote = c;
        out.append(' ');
        i++;
        while (i < sql.length()) {
          if (sql.charAt(i) == quote) {
            // Doubled quote: an escaped one, still inside the literal.
            if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
              i += 2;
              continue;
            }
            i++;
            break;
          }
          i++;
        }
        continue;
      }
      if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
        while (i < sql.length() && sql.charAt(i) != '\n') {
          i++;
        }
        out.append('\n');
        continue;
      }
      if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
        int end = sql.indexOf("*/", i + 2);
        i = end < 0 ? sql.length() : end + 2;
        out.append(' ');
        continue;
      }
      out.append(c);
      i++;
    }
    return out.toString();
  }

  private static boolean containsWord(String haystack, String word) {
    int from = 0;
    while (true) {
      int at = haystack.indexOf(word, from);
      if (at < 0) {
        return false;
      }
      boolean beforeOk = at == 0 || !isWordChar(haystack.charAt(at - 1));
      int after = at + word.length();
      boolean afterOk = after >= haystack.length() || !isWordChar(haystack.charAt(after));
      if (beforeOk && afterOk) {
        return true;
      }
      from = at + 1;
    }
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  private static String oneLine(String text, int max) {
    String flat = text.replaceAll("\\s+", " ").trim();
    return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
  }

  private static boolean notBlank(String text) {
    return text != null && !text.isBlank();
  }
}
