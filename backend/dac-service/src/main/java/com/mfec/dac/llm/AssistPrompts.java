package com.mfec.dac.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

  // ------------------------------------------------------- fix and explain (M26)

  /** How much of a database's error is passed on. The first line is the useful one. */
  public static final int MAX_ERROR = 400;

  /** How much of an explanation is shown. A paragraph and a few steps fit in this. */
  public static final int MAX_EXPLANATION = 4000;

  /**
   * The standing instruction for repairing a statement that failed.
   *
   * <p>Only ever offered for a failure of the statement itself -- a typo, a
   * missing schema, a construct the proxy cannot read, an error from the
   * database. A refusal a policy made is not offered for repair at all, and the
   * prompt says so as well: a model asked to "make this work" after a denial
   * would reach for a different table holding the same data, which is the one
   * suggestion this platform must never make.
   */
  public static String fixSystem(String engine) {
    return "You repair one SQL SELECT statement for a "
        + (notBlank(engine) ? engine : "SQL")
        + " database. It failed, and you are given the error.\n"
        + "Rules:\n"
        + "- Answer with the corrected statement and nothing else. No prose, no fences, no"
        + " explanation.\n"
        + "- Exactly one statement. SELECT, or WITH followed by SELECT. Never INSERT, UPDATE,"
        + " DELETE, MERGE, CREATE, ALTER, DROP, GRANT, REVOKE, TRUNCATE or CALL.\n"
        + "- Change only what the error is about. Keep the columns, filters, grouping and"
        + " ordering the writer chose.\n"
        + "- Use only the tables and columns listed. Write table names exactly as they are"
        + " listed, with their schema. Never invent a column.\n"
        + "- The platform enforces access after you and may mask, hide or refuse. Never try to"
        + " get around that: do not swap a table for another that holds the same data, and do"
        + " not mask, hash or unmask anything yourself.\n"
        + "- If you cannot tell what is wrong, or the fix needs a table or column that is not"
        + " listed, answer with the single word UNANSWERABLE.";
  }

  public static String fixUser(String sql, String error, String brief) {
    return "Tables:\n\n"
        + (notBlank(brief) ? brief : "(none listed)")
        + "\n\nStatement:\n\n"
        + sql.trim()
        + "\n\nError:\n\n"
        + (notBlank(error) ? error : "(no message)");
  }

  /**
   * The standing instruction for explaining a statement.
   *
   * <p>The model is shown the statement and the catalogue, never a result. It
   * therefore explains what the statement asks for, not what it returned, and
   * it is told that it cannot know what this reader will be shown.
   */
  public static String explainSystem(String engine) {
    return "You explain one SQL statement for a "
        + (notBlank(engine) ? engine : "SQL")
        + " database to the person about to run it.\n"
        + "Rules:\n"
        + "- Plain prose. Start with one or two sentences on what the statement answers, then"
        + " at most six short bullet points on how: which tables it reads, how they are"
        + " joined, what it filters, groups and orders by, and what one row of the result"
        + " means.\n"
        + "- Keep it under 200 words. Do not repeat the statement back, and do not rewrite it.\n"
        + "- Use the table and column descriptions listed to say what things mean. If the"
        + " statement names a table or column that is not listed, say so.\n"
        + "- If there is an obvious mistake -- a join with no condition, a filter that can"
        + " never be true, a GROUP BY that does not match the SELECT -- say so in one line at"
        + " the end.\n"
        + "- You have not seen any data and must not guess values. Columns marked with a tag"
        + " are governed: the platform may mask or hide them for this reader when it runs, so"
        + " do not promise what they will show.";
  }

  public static String explainUser(String sql, String brief) {
    StringBuilder out = new StringBuilder();
    if (notBlank(brief)) {
      out.append("Tables:\n\n").append(brief).append("\n\n");
    }
    return out.append("Statement:\n\n").append(sql.trim()).toString();
  }

  /** An explanation, trimmed and capped. It is shown as plain text, never as markup. */
  public static String extractExplanation(String answer) {
    if (answer == null) {
      return "";
    }
    String text = answer.trim();
    return text.length() <= MAX_EXPLANATION
        ? text
        : text.substring(0, MAX_EXPLANATION - 1).trim() + "…";
  }

  /**
   * Whether two statements are the same once spacing, case and a trailing
   * semicolon are set aside -- so that a "fix" that changed nothing is reported
   * as nothing to change rather than offered as a suggestion.
   */
  public static boolean sameStatement(String a, String b) {
    return squash(a).equals(squash(b));
  }

  private static String squash(String sql) {
    String text = sql == null ? "" : sql.trim();
    while (text.endsWith(";")) {
      text = text.substring(0, text.length() - 1).trim();
    }
    return text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  private static final Pattern QUOTED = Pattern.compile("'((?:[^']|'')*)'|\"((?:[^\"]|\"\")*)\"");

  /** A key and its value, as PostgreSQL reports them: {@code Key (id)=(42)}. */
  private static final Pattern KEY_VALUE = Pattern.compile("\\)=\\([^)]*\\)");

  /** Lines a PostgreSQL error adds after the message. DETAIL and WHERE can quote a row. */
  private static final Pattern DETAIL_LINE =
      Pattern.compile("(?m)^\\s*(Detail|DETAIL|Where|WHERE|Internal Query|Position|POSITION):.*$");

  /**
   * A database's error, with anything that could be a value from a row taken
   * out before it goes near a gateway.
   *
   * <p>The rule that the assistant never sees a row covers the error too. The
   * statement failed at the source, and a source reporting why it could not
   * convert a value will quote the value: {@code invalid input syntax for type
   * integer: "abc"} on PostgreSQL, {@code Conversion failed when converting the
   * varchar value 'abc'} on SQL Server. So every quoted token that is not
   * already in the statement or the catalogue brief -- a column name, a table
   * name, a literal the writer typed -- is replaced with {@code '…'}, the detail
   * lines that echo a row are dropped, and what is left is flattened to one
   * short line. What survives is the shape of the error, which is what a repair
   * needs.
   */
  public static String redactError(String error, String sql, String brief) {
    if (error == null || error.isBlank()) {
      return "";
    }
    String known = ((sql == null ? "" : sql) + "\n" + (brief == null ? "" : brief)).toLowerCase(Locale.ROOT);
    String text = DETAIL_LINE.matcher(error).replaceAll("");
    text = KEY_VALUE.matcher(text).replaceAll(")=(…)");

    Matcher quoted = QUOTED.matcher(text);
    StringBuilder out = new StringBuilder();
    while (quoted.find()) {
      boolean single = quoted.group(1) != null;
      String inner = single ? quoted.group(1) : quoted.group(2);
      String keep =
          inner.isEmpty() || known.contains(inner.toLowerCase(Locale.ROOT))
              ? quoted.group()
              : (single ? "'…'" : "\"…\"");
      quoted.appendReplacement(out, Matcher.quoteReplacement(keep));
    }
    quoted.appendTail(out);
    return oneLine(out.toString(), MAX_ERROR);
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

  // ------------------------------------------------------------ policy edits

  /**
   * The longest policy document that is sent to be edited.
   *
   * <p>A policy with a long exemption list and a dozen masks is a few thousand
   * characters. Past this it is not a form anybody filled in, and it is a large
   * bill on somebody's own gateway.
   */
  public static final int MAX_POLICY = 50_000;

  /**
   * What the store owns, and the model is never shown or asked to write: which
   * policy this is, which version, whether it is in force, and who touched it
   * last. The store decides all five on save, so a model that rewrote them would
   * be changing nothing, and one that saw {@code ACTIVE} might take it as an
   * instruction to keep the policy in force.
   */
  static final List<String> STORE_FIELDS =
      List.of("id", "version", "lifecycleState", "updatedAt", "updatedBy");

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * The document as it is in the builder, ready to be put in a prompt: one JSON
   * object, compact, without the fields the store owns.
   *
   * @throws IllegalArgumentException when it is not one JSON object, or is
   *     longer than {@link #MAX_POLICY}
   */
  public static String policyForEdit(String current) {
    if (current == null || current.isBlank()) {
      throw new IllegalArgumentException("There is no policy to change");
    }
    if (current.length() > MAX_POLICY) {
      throw new IllegalArgumentException(
          "The policy is longer than " + MAX_POLICY + " characters, which is more than is sent");
    }
    JsonNode node;
    try {
      node = JSON.readTree(current);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("The policy to change is not a JSON document");
    }
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("The policy to change is not a JSON object");
    }
    ObjectNode document = (ObjectNode) node;
    document.remove(STORE_FIELDS);
    return document.toString();
  }

  /**
   * The standing instruction for changing a policy somebody already has.
   *
   * <p>It differs from {@link #policySystem} in what it protects. A draft is
   * read as a whole by whoever asked for it; a change to an existing policy is
   * read as a diff, and a diff is only reviewable when it is small. So the model
   * is told to touch what it was asked to and leave the rest exactly as it was,
   * down to the name and the author's own wording.
   */
  public static String policyEditSystem(String schemas) {
    return "You change an existing access policy for ARAK, a data access control platform."
        + " You are given the policy as JSON and a sentence saying what to change.\n"
        + "Rules:\n"
        + "- Answer with the whole policy, changed, as one JSON object and nothing else. No"
        + " prose, no fences, no patch.\n"
        + "- It must validate against the schema below.\n"
        + "- Change only what the sentence asks for. Every other field stays exactly as it was:"
        + " same values, same order in lists. Do not tidy, reword or add defaults.\n"
        + "- Keep \"name\" as it is unless you are asked to rename the policy.\n"
        + "- Leave \"description\" alone unless the change makes it wrong. If you were unsure"
        + " about something, add one short sentence to the end of it, so the reviewer knows"
        + " what to check.\n"
        + "- Do not write \"id\", \"version\", \"lifecycleState\", \"updatedAt\" or"
        + " \"updatedBy\". You are writing a proposal for a person to review and save; you never"
        + " activate, disable or archive anything.\n"
        + "- Use only facets and masking functions the schema allows.\n\n"
        + "Schema:\n\n"
        + schemas;
  }

  /**
   * The ask for a change: what to change, then the policy it applies to.
   *
   * @param current a document already passed through {@link #policyForEdit}
   */
  public static String policyEditUser(String intent, String current, String brief) {
    StringBuilder out =
        new StringBuilder("Change this policy: ")
            .append(intent.trim())
            .append("\n\nThe policy now:\n\n")
            .append(current);
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

  // ---------------------------------------------------- column descriptions

  /** The longest draft kept for one column: a sentence, not a paragraph. */
  public static final int MAX_COLUMN_DRAFT = 500;

  /** How many of a table's other column names are listed for context. */
  static final int MAX_CONTEXT_COLUMNS = 300;

  /** A drafted description of one column, under the column's name as the catalogue has it. */
  public record ColumnDraft(String name, String description) {}

  /**
   * The rules for drafting column descriptions.
   *
   * <p>From names, types and tags only: the model has seen no rows, so it is
   * told to say what a column is and never what is in it. An empty answer is
   * asked for when it cannot tell, because a steward reading forty drafts is
   * better served by a gap than by a confident guess that looks like the rest.
   */
  public static String describeColumnsSystem(String language) {
    String written =
        "Thai".equalsIgnoreCase(language == null ? "" : language.trim()) ? "Thai" : "English";
    return "You write short descriptions of database columns for a data catalogue. They are read"
        + " by people asking for access to the table and by the people who approve it.\n"
        + "Rules:\n"
        + "- For each column you are asked about, write one plain sentence of at most 25 words, in "
        + written
        + ", saying what the column holds.\n"
        + "- Work only from the names, types, tags and descriptions given. You have not seen any"
        + " data: never state or guess values, counts or examples of what is in a column.\n"
        + "- Well-known naming conventions are fair to use (SAP field names, or suffixes such as"
        + " _id, _dt, _amt, _cd), but say what the column is, not how you worked it out.\n"
        + "- If you cannot tell what a column holds, leave its description empty. An empty"
        + " description is a better answer than a wrong one.\n"
        + "- Do not repeat the column name as the description, and do not begin with \"This"
        + " column\".\n"
        + "- Answer with JSON only, no prose and no code fence:"
        + " {\"columns\":[{\"name\":\"<the column name exactly as given>\",\"description\":\"<the"
        + " sentence, or empty>\"}]}";
  }

  /** The table, the columns to describe, and the names of the rest for context. */
  public static String describeColumnsUser(
      Table table, List<Column> toDescribe, List<String> others) {
    StringBuilder out = new StringBuilder();
    out.append("Table: ").append(table.fqn()).append('\n');
    if (notBlank(table.description())) {
      out.append("About the table: ").append(oneLine(table.description(), 600)).append('\n');
    }
    if (others != null && !others.isEmpty()) {
      List<String> listed =
          others.size() > MAX_CONTEXT_COLUMNS ? others.subList(0, MAX_CONTEXT_COLUMNS) : others;
      out.append("Its other columns, for context: ").append(String.join(", ", listed)).append('\n');
    }
    out.append("\nDescribe these columns:\n");
    for (Column column : toDescribe) {
      out.append("- ").append(column.name());
      if (notBlank(column.dataType())) {
        out.append(" (").append(column.dataType()).append(')');
      }
      if (column.tags() != null && !column.tags().isEmpty()) {
        out.append(", tagged ").append(String.join(", ", column.tags()));
      }
      if (notBlank(column.description())) {
        out.append(", described now as: ").append(oneLine(column.description(), 300));
      }
      out.append('\n');
    }
    return out.toString().trim();
  }

  /**
   * The drafts in an answer, for the columns that were asked about.
   *
   * <p>Names are matched without regard to case and returned as asked, so a
   * draft always lands on a real column; a name nobody asked about is dropped,
   * as is an empty description. Drafts come back in the order they were asked
   * for. An answer that is not the JSON asked for gives no drafts at all rather
   * than a guess at what it meant.
   */
  public static List<ColumnDraft> extractColumnDrafts(String answer, List<String> asked) {
    if (answer == null || asked == null || asked.isEmpty()) {
      return List.of();
    }
    int start = answer.indexOf('{');
    int end = answer.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return List.of();
    }
    JsonNode root;
    try {
      root = JSON.readTree(answer.substring(start, end + 1));
    } catch (JsonProcessingException e) {
      return List.of();
    }
    JsonNode columns = root == null ? null : root.path("columns");
    if (columns == null || !columns.isArray()) {
      return List.of();
    }
    Map<String, String> names = new LinkedHashMap<>();
    for (String name : asked) {
      if (name != null) {
        names.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
      }
    }
    Map<String, String> drafts = new LinkedHashMap<>();
    for (JsonNode column : columns) {
      String name = names.get(column.path("name").asText("").trim().toLowerCase(Locale.ROOT));
      if (name == null) {
        continue;
      }
      String text = column.path("description").asText("").replaceAll("\\s+", " ").trim();
      if (text.isEmpty()) {
        continue;
      }
      if (text.length() > MAX_COLUMN_DRAFT) {
        text = text.substring(0, MAX_COLUMN_DRAFT - 1).trim() + "…";
      }
      drafts.putIfAbsent(name, text);
    }
    List<ColumnDraft> out = new ArrayList<>();
    for (String name : names.values()) {
      String text = drafts.get(name);
      if (text != null) {
        out.add(new ColumnDraft(name, text));
      }
    }
    return out;
  }

  // ------------------------------------------------------------ find data (M16)

  /** The longest description of what somebody is after that is accepted. */
  public static final int MAX_WANT = 500;

  /** Search words asked for from one sentence. */
  public static final int MAX_FIND_KEYWORDS = 8;

  /** Tables the model may pick for one sentence. */
  public static final int MAX_FOUND_TABLES = 6;

  /** Columns named for one table. */
  public static final int MAX_FOUND_COLUMNS = 8;

  /** The longest reason kept for one table. */
  public static final int MAX_FOUND_WHY = 300;

  /** A keyword: letters, digits and the few marks a table or column name carries. */
  private static final Pattern KEYWORD = Pattern.compile("[\\p{L}\\p{N}_][\\p{L}\\p{N}_ .\\-]{0,39}");

  /** A table the model picked, with the columns it named, under the names the catalogue uses. */
  public record Pick(String fqn, String why, List<String> columns) {}

  /**
   * The rules for turning a sentence into search words.
   *
   * <p>Only the sentence goes with it -- no table, no column, nothing from the
   * catalogue -- because what it produces is used to search, and the search is
   * what applies the asker's permissions. The catalogue's names are mostly
   * English, so a sentence in Thai is translated here rather than searched as
   * it stands.
   */
  public static String findKeywordsSystem() {
    return "You turn a request for data into search words for a data catalogue. Its table and"
        + " column names, descriptions and tags are mostly in English.\n"
        + "Rules:\n"
        + "- Give at most "
        + MAX_FIND_KEYWORDS
        + " keywords, most likely first: single English words or short names a table or column"
        + " would carry, such as customer, invoice, vendor, salary, email.\n"
        + "- Translate from any language to English, and add the abbreviations and synonyms a"
        + " schema tends to use (purchase order: po, purchase_order; employee: emp, staff).\n"
        + "- Say what kind of data it is. Leave out names of people, values, dates and numbers"
        + " from the request.\n"
        + "- Answer with JSON only, no prose and no code fence: {\"keywords\":[\"...\"]}";
  }

  /** The sentence, and nothing else. */
  public static String findKeywordsUser(String want) {
    return "Request: " + oneLine(want == null ? "" : want, MAX_WANT);
  }

  /**
   * The search words in an answer: at most {@link #MAX_FIND_KEYWORDS}, each two
   * to forty characters of the kind a name is made of, no two the same. An
   * answer that is not the JSON asked for gives none, and the caller searches
   * the sentence's own words instead.
   */
  public static List<String> extractKeywords(String answer) {
    String body = extractJson(answer);
    if (body.isEmpty()) {
      return List.of();
    }
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (JsonProcessingException e) {
      return List.of();
    }
    JsonNode keywords = root == null ? null : root.path("keywords");
    if (keywords == null || !keywords.isArray()) {
      return List.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (JsonNode keyword : keywords) {
      String text = keyword.asText("").replaceAll("\\s+", " ").trim();
      if (text.length() < 2 || !KEYWORD.matcher(text).matches()) {
        continue;
      }
      out.putIfAbsent(text.toLowerCase(Locale.ROOT), text);
      if (out.size() >= MAX_FIND_KEYWORDS) {
        break;
      }
    }
    return List.copyOf(out.values());
  }

  /**
   * A sentence's own words, for when the model gave no keywords: the same
   * splitting {@link #mostRelevant} uses, so at least what was typed is
   * searched.
   */
  public static List<String> wordsOf(String want) {
    List<String> out = new ArrayList<>();
    for (String word : words(want)) {
      if (out.size() >= MAX_FIND_KEYWORDS) {
        break;
      }
      out.add(word);
    }
    return out;
  }

  /**
   * The rules for picking, from tables the asker may already use or ask for,
   * the ones that hold what they described.
   *
   * <p>The tables are chosen before the model sees them: every one is readable
   * by the asker or open to their request, and a readable one comes without the
   * columns their decision hides. The model only orders and explains. It is
   * told not to say who can read what, because the page does, from the
   * permission check rather than from a model.
   */
  public static String findDataSystem(String language) {
    String written =
        "Thai".equalsIgnoreCase(language == null ? "" : language.trim()) ? "Thai" : "English";
    return "You help somebody find the tables that hold the data they describe, in a data"
        + " catalogue. You are given their request and some tables, each with its columns, types,"
        + " tags and descriptions. You have not seen any data in them.\n"
        + "Rules:\n"
        + "- Pick the tables that hold what they asked for, best first, at most "
        + MAX_FOUND_TABLES
        + ". Leave out a table that does not fit; an empty list is a fair answer.\n"
        + "- Use table and column names exactly as given. Never invent a table or a column.\n"
        + "- For each table write one or two sentences in "
        + written
        + " saying why it fits, and name the columns that hold what they asked for (at most "
        + MAX_FOUND_COLUMNS
        + "), with a key column to join on when it helps.\n"
        + "- Work only from the names, types, tags and descriptions given. Never state or guess"
        + " values, counts or examples of what is in a table.\n"
        + "- Do not say whether they can read a table, or how to get access; the page shows"
        + " that.\n"
        + "- Answer with JSON only, no prose and no code fence:"
        + " {\"tables\":[{\"fqn\":\"<table name exactly as given>\",\"why\":\"<one or two"
        + " sentences>\",\"columns\":[\"<column name exactly as given>\"]}]}";
  }

  /** The request and the tables to pick from, as the brief every other job uses. */
  public static String findDataUser(String want, List<Table> tables) {
    return "Request: "
        + oneLine(want == null ? "" : want, MAX_WANT)
        + "\n\nTables:\n"
        + schemaBrief(tables);
  }

  /**
   * The tables an answer picked, among the ones it was shown.
   *
   * <p>A name is matched without regard to case and returned as the catalogue
   * has it; a table or a column the model was not shown is dropped, so nothing
   * it invents reaches the page. Empty when the answer is not the JSON asked
   * for, which is how the caller tells "nothing fits" (a list with no tables)
   * from "no answer".
   */
  public static Optional<List<Pick>> extractPicks(String answer, List<Table> shown) {
    String body = extractJson(answer);
    if (body.isEmpty() || shown == null) {
      return Optional.empty();
    }
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (JsonProcessingException e) {
      return Optional.empty();
    }
    JsonNode tables = root == null ? null : root.path("tables");
    if (tables == null || !tables.isArray()) {
      return Optional.empty();
    }
    Map<String, Table> byName = new LinkedHashMap<>();
    for (Table table : shown) {
      byName.putIfAbsent(table.fqn().toLowerCase(Locale.ROOT), table);
    }
    Map<String, Pick> picks = new LinkedHashMap<>();
    for (JsonNode picked : tables) {
      if (picks.size() >= MAX_FOUND_TABLES) {
        break;
      }
      Table table = byName.get(picked.path("fqn").asText("").trim().toLowerCase(Locale.ROOT));
      if (table == null || picks.containsKey(table.fqn())) {
        continue;
      }
      Map<String, String> columnNames = new LinkedHashMap<>();
      for (Column column : table.columns()) {
        columnNames.putIfAbsent(column.name().toLowerCase(Locale.ROOT), column.name());
      }
      Set<String> columns = new LinkedHashSet<>();
      for (JsonNode name : picked.path("columns")) {
        String column = columnNames.get(name.asText("").trim().toLowerCase(Locale.ROOT));
        if (column != null && columns.size() < MAX_FOUND_COLUMNS) {
          columns.add(column);
        }
      }
      String why = picked.path("why").asText("").replaceAll("\\s+", " ").trim();
      if (why.length() > MAX_FOUND_WHY) {
        why = why.substring(0, MAX_FOUND_WHY - 1).trim() + "…";
      }
      picks.put(table.fqn(), new Pick(table.fqn(), why, List.copyOf(columns)));
    }
    return Optional.of(List.copyOf(picks.values()));
  }

  // ------------------------------------------------------- explain a policy (M15)

  /**
   * Another policy that meets this one on the same targets, as the overlap
   * screen already words it. No id: the model has no use for one.
   */
  public record Neighbour(
      String name,
      String scopeLevel,
      String policyType,
      String effect,
      String lifecycleState,
      int sharedTargets,
      String relation,
      String explanation,
      String overrideNote) {}

  /**
   * Everything the model is told about one policy.
   *
   * <p>The document, where it sits, what it lands on and what it meets there.
   * All of it is read from the policy store and the bindings -- none of it from
   * a source database, so there is nothing here a row could have come from.
   *
   * @param document the policy as {@link #policyForExplaining} leaves it
   * @param targets the kind and FQN of some of the tables and columns it lands on
   * @param moreTargets whether it lands on more than {@code targets} lists
   * @param neighbourCount how many other policies meet it, of which {@code neighbours} are the first
   */
  public record PolicyFacts(
      String document,
      String lifecycleState,
      String environment,
      int tableCount,
      int columnCount,
      List<String> targets,
      boolean moreTargets,
      List<Neighbour> neighbours,
      int neighbourCount) {}

  /** How many of the tables and columns a policy lands on are named to the model. */
  public static final int MAX_EXPLAIN_TARGETS = 20;

  /** How many of the policies it meets are described to the model. */
  public static final int MAX_NEIGHBOURS = 10;

  /**
   * The stored document ready for a model to read: without the fields the store
   * owns, and without the people it names.
   *
   * <p>Exemptions and approvers are lists of people, with reasons written about
   * them. What an explanation needs is that they exist, so each list becomes a
   * count. The rest -- selector, subject rule, row filter, masks -- is what the
   * policy is, and is sent as it is; it is the same text anybody signed in reads
   * on the policy's own page.
   *
   * @throws IllegalArgumentException when it is not one JSON object, or is
   *     longer than {@link #MAX_POLICY}
   */
  public static String policyForExplaining(String document) {
    if (document == null || document.isBlank()) {
      throw new IllegalArgumentException("There is no policy to explain");
    }
    JsonNode node;
    try {
      node = JSON.readTree(document);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("The policy is not a JSON document");
    }
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("The policy is not a JSON object");
    }
    ObjectNode out = (ObjectNode) node;
    out.remove(STORE_FIELDS);
    out.remove("id");
    for (String people : List.of("exemptions", "approvers")) {
      JsonNode list = out.remove(people);
      if (list != null && list.isArray() && !list.isEmpty()) {
        out.put(people + "Count", list.size());
      }
    }
    String text = out.toString();
    if (text.length() > MAX_POLICY) {
      throw new IllegalArgumentException(
          "The policy is longer than " + MAX_POLICY + " characters, which is more than is sent");
    }
    return text;
  }

  /**
   * The standing instruction for explaining a policy.
   *
   * <p>The composition rules are FR-5.1 as the engine applies them, so the model
   * explains this policy in the platform's terms rather than in whatever it
   * believes access control usually does. It is told that its answer is a
   * reading aid: the page labels it as the model's, and the Simulator, which
   * runs the engine, is what decides.
   */
  public static String explainPolicySystem(String language) {
    String written =
        "Thai".equalsIgnoreCase(language == null ? "" : language.trim()) ? "Thai" : "English";
    return "You explain one access policy of a data access control platform to the person reading"
        + " it: a data owner, a reviewer or an auditor.\n"
        + "How the platform applies policies -- mention a rule only where it matters to this"
        + " policy:\n"
        + "- Default deny: nobody reads a table unless an ACTIVE subscription policy allows them.\n"
        + "- DENY wins over ALLOW wherever both reach the same table.\n"
        + "- Policies sit in layers ORG, DOMAIN, SERVICE, DATABASE, SCHEMA, TABLE, COLUMN and all"
        + " of them apply together. A lower layer can only make access stricter, unless the higher"
        + " policy sets allowLocalOverride.\n"
        + "- Row filters from different policies are combined with AND: a person sees only the rows"
        + " every filter lets through.\n"
        + "- When several masks reach one column the strictest wins, in the order NULLIFY,"
        + " CONSTANT, HASH, REGEX_REPLACE, PARTIAL, ROUNDING.\n"
        + "- Only an ACTIVE policy takes effect. A DRAFT, PENDING_APPROVAL, DISABLED or ARCHIVED"
        + " one changes nothing for anybody yet.\n"
        + "Rules:\n"
        + "- Write in "
        + written
        + ". Plain prose, no headings, no tables, no code blocks. Start with two or three"
        + " sentences on what the policy does and for whom, then at most eight short bullet"
        + " points: what it selects, who it applies to and when (roles, attributes, time windows,"
        + " network), what it does to rows and columns, what it lands on now, and how it meets"
        + " the other policies listed.\n"
        + "- Keep it under 300 words. Do not repeat the JSON back, and keep field names out"
        + " unless there is no plainer word.\n"
        + "- Use only what you are given. Where something is missing or unclear, say so instead"
        + " of guessing, and never invent policies, tables, people or values.\n"
        + "- You have not seen any data in the tables and must not guess what is in them.\n"
        + "- If something looks like a mistake -- a condition that can never be true, a time"
        + " window that ends before it starts, another policy that means this one grants nothing"
        + " -- say so in one line at the end. Do not otherwise suggest changes.\n"
        + "- This is a reading aid. Do not claim to have checked what any particular person will"
        + " see.";
  }

  /** The policy, where it sits, what it lands on and what it meets there. */
  public static String explainPolicyUser(PolicyFacts facts) {
    StringBuilder out = new StringBuilder();
    out.append("State: ")
        .append(notBlank(facts.lifecycleState()) ? facts.lifecycleState() : "unknown")
        .append(" in the ")
        .append(notBlank(facts.environment()) ? facts.environment() : "default")
        .append(" environment\n\nPolicy:\n")
        .append(facts.document())
        .append("\n\n");

    if (facts.tableCount() == 0 && facts.columnCount() == 0) {
      out.append("It lands on nothing now: its selector matched no table or column when it was")
          .append(" last resolved.\n");
    } else {
      out.append("It lands on ")
          .append(count(facts.tableCount(), "table"))
          .append(" and ")
          .append(count(facts.columnCount(), "column"))
          .append(" now")
          .append(facts.targets().isEmpty() ? ".\n" : ", among them:\n");
      for (String target : facts.targets()) {
        out.append("- ").append(target).append('\n');
      }
      if (facts.moreTargets()) {
        out.append("- and more not listed here\n");
      }
    }

    out.append('\n');
    if (facts.neighbours().isEmpty()) {
      out.append("No other policy is bound to any of the same tables or columns.");
    } else {
      out.append("Other policies bound to some of the same tables or columns, and what the")
          .append(" platform says happens where they meet:\n");
      for (Neighbour other : facts.neighbours()) {
        out.append("- ")
            .append(other.name())
            .append(" (")
            .append(
                String.join(
                    ", ",
                    List.of(
                        orDash(other.scopeLevel()),
                        orDash(other.policyType()),
                        orDash(other.effect()),
                        orDash(other.lifecycleState()))))
            .append(") on ")
            .append(count(other.sharedTargets(), "shared target"))
            .append(": ")
            .append(orDash(other.relation()));
        if (notBlank(other.explanation())) {
          out.append(" -- ").append(oneLine(other.explanation(), 400));
        }
        if (notBlank(other.overrideNote())) {
          out.append(" ").append(oneLine(other.overrideNote(), 300));
        }
        out.append('\n');
      }
      int unlisted = facts.neighbourCount() - facts.neighbours().size();
      if (unlisted > 0) {
        out.append("- and ")
            .append(unlisted == 1 ? "1 more policy" : unlisted + " more policies")
            .append(" not listed here\n");
      }
    }
    return out.toString().trim();
  }

  // ------------------------------------------------ explaining the dashboard (M15)

  /**
   * The standing instruction for explaining the dashboard.
   *
   * <p>What each count means is the dashboard's own definition, so the model
   * reads the page the way it is built rather than the way dashboards usually
   * are. People arrive as {@code [P1]}, {@code [P2]}: the model is told to keep
   * those tags exactly, because the page turns them back into names for the
   * person who asked and nobody else.
   */
  public static String explainDashboardSystem(String language) {
    String written =
        "Thai".equalsIgnoreCase(language == null ? "" : language.trim()) ? "Thai" : "English";
    // Thai for "request" is what the page calls an access request, so a query
    // written that way reads as somebody asking for access.
    String words =
        written.equals("Thai")
            ? " In Thai write a query as \"query\" and an access request as \"คำขอสิทธิ์\";"
                + " never call a query \"คำขอ\"."
            : "";
    return "You explain the access-control dashboard of a data access control platform to the"
        + " person reading it: an administrator, a policy author or an auditor. You are given the"
        + " page's counts, not its data.\n"
        + "What the counts mean:\n"
        + "- Default deny: nobody reads a table unless an ACTIVE subscription policy or a grant"
        + " lets them in. A data policy masks columns or filters rows for those who get in.\n"
        + "- A labelled table carries the sensitive label named in the brief. It is protected when"
        + " an active data policy is bound to it or its columns. An unprotected one somebody can"
        + " get into shows everything unmasked to them; one nobody can get into exposes nothing"
        + " today, but the first grant would open it unmasked.\n"
        + "- Queries are those sent through the platform's query proxy: ran, refused by the"
        + " platform before reaching the source, or failed at the source. Refusal reasons:"
        + " POLICY_DENY a policy said no and the person may ask for access; UNGOVERNED a table"
        + " nothing governs; CANNOT_ENFORCE a policy the proxy cannot write for that engine;"
        + " ALL_COLUMNS_HIDDEN every column hidden from the person; PURPOSE_NOT_ALLOWED the table"
        + " holds sensitive data the purpose named does not allow; UNPARSEABLE, UNQUALIFIED and"
        + " UNSUPPORTED SQL the proxy could not read or rewrite; NOT_READ_ONLY anything but"
        + " SELECT; TOO_COSTLY the source's planner priced it over the ceiling;"
        + " SOURCE_UNAVAILABLE source switched off or missing; BUSY no room for one more read, it"
        + " will likely run later; SOURCE_ERROR the source refused an enforced statement; EMPTY"
        + " nothing sent.\n"
        + "- Policy engine decisions are every access check, including those the proxy and the"
        + " simulator make; answered from cache is normal and fast.\n"
        + "- A grant is access given to one person or group on one table, from a request or by"
        + " hand. Unused means held for over 90 days with no query; open-ended means no end"
        + " date.\n"
        + "- An access request is open while waiting for approval, approved and waiting to be"
        + " carried out, or in progress.\n"
        + "- Enforced objects are secure views and native objects on the sources; DRIFTED means"
        + " somebody changed one by hand, FAILED means it could not be applied, and until it is"
        + " re-applied the source may not enforce the policy. A failed or stale OpenMetadata sync"
        + " means new labels, owners and tables are not reaching policy yet.\n"
        + "- Needs attention items come as SEVERITY KIND: count. HIGH is exposure or enforcement"
        + " that is not working, MEDIUM is something waiting on a person, LOW is housekeeping.\n"
        + "Rules:\n"
        + "- Write in "
        + written
        + ". Plain prose, no headings, no tables, no code blocks. Start with two or three"
        + " sentences on what stands out, then at most eight short bullet points: what looks"
        + " unusual or worth a closer look and why, and which table, person or page to look at"
        + " next.\n"
        + "- Keep it under 300 words. Put the most important thing first.\n"
        + "- Keep queries and access requests apart: a query is a statement somebody sent"
        + " through the proxy, an access request is somebody asking to be let in."
        + words
        + "\n"
        + "- People are written [P1], [P2] and so on. Refer to a person only by that exact tag,"
        + " brackets included, and never guess who they are.\n"
        + "- Use only the numbers you are given; do not add up or estimate figures that are not"
        + " there, and never invent tables, people or values. Where the brief says nothing about"
        + " something, say it was not in what you were shown.\n"
        + "- A count is not a verdict: say what a number might mean and what would confirm it,"
        + " not that something is wrong. A quiet window may simply be quiet.\n"
        + "- You have not seen any data in the tables and must not guess what is in them.\n"
        + "- This is a reading aid. The numbers on the dashboard are what count; do not claim to"
        + " have checked anything beyond them.";
  }

  /** The page's counts, as {@code DashboardBrief} wrote them. */
  public static String explainDashboardUser(String brief) {
    return "The dashboard as it stands now:\n\n" + brief;
  }

  private static String count(int n, String noun) {
    return n + " " + noun + (n == 1 ? "" : "s");
  }

  private static String orDash(String text) {
    return notBlank(text) ? text : "-";
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
