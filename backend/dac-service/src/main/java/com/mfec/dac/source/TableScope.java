package com.mfec.dac.source;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which tables of a source are read into the catalog.
 *
 * <p>Two questions, in the order a person asks them: scan every table, or only
 * the ones named; and whichever it is, which names are always left out. A rule
 * compares text rather than running a pattern. A regular expression typed here
 * would be run against every table name on every import, and one badly written
 * expression can take a thread for minutes on a long name. Starts with, ends
 * with, contains and is exactly cover the cases people actually describe —
 * {@code TMP_}, {@code _backup}, one table by name.
 *
 * <p>A rule without a dot is compared with the table name. A rule with a dot is
 * compared with {@code schema.table}, which is how a whole schema is scoped:
 * starts with {@code staging.}. Comparison ignores case, because the engines
 * this reads from mostly do.
 *
 * <p>The scope decides what the import reads, nothing more. It is not a
 * permission: a table left out is not hidden from anyone and not protected by
 * being left out, and live verification before enforcement still reads every
 * column of the table being enforced.
 */
public record TableScope(Mode mode, List<Rule> include, List<Rule> exclude) {

  public enum Mode {
    /** Every table the connector can read, less the exclusions. */
    ALL,
    /** Only tables an include rule names, less the exclusions. */
    ONLY
  }

  public enum Match {
    STARTS_WITH,
    ENDS_WITH,
    CONTAINS,
    EQUALS
  }

  /** One comparison. {@code value} is what the person typed, trimmed. */
  public record Rule(Match match, String value) {

    public boolean matches(String schema, String name) {
      String subject = value.indexOf('.') >= 0 ? schema + "." + name : name;
      String left = subject.toLowerCase(Locale.ROOT);
      String right = value.toLowerCase(Locale.ROOT);
      return switch (match) {
        case STARTS_WITH -> left.startsWith(right);
        case ENDS_WITH -> left.endsWith(right);
        case CONTAINS -> left.contains(right);
        case EQUALS -> left.equals(right);
      };
    }
  }

  /** Enough for any list a person would keep by hand; a longer one is a mistake. */
  public static final int MAX_RULES = 50;

  /** Longer than any identifier either engine allows, qualified. */
  public static final int MAX_VALUE = 256;

  public static final TableScope EVERYTHING = new TableScope(Mode.ALL, List.of(), List.of());

  public TableScope {
    mode = mode == null ? Mode.ALL : mode;
    include = include == null ? List.of() : List.copyOf(include);
    exclude = exclude == null ? List.of() : List.copyOf(exclude);
  }

  /** Thrown with the sentence the form should show. */
  public static class InvalidScopeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidScopeException(String message) {
      super(message);
    }
  }

  public boolean includes(String schema, String name) {
    if (mode == Mode.ONLY && include.stream().noneMatch(rule -> rule.matches(schema, name))) {
      return false;
    }
    return exclude.stream().noneMatch(rule -> rule.matches(schema, name));
  }

  /**
   * True when nothing is left out, which is how a scope is stored as absent.
   *
   * <p>Not named {@code isEverything}: a bean-style name would be served as a
   * property of the scope and then refused when the form sent it back.
   */
  public boolean scansEverything() {
    return mode == Mode.ALL && exclude.isEmpty();
  }

  /**
   * The scope as it will be stored: trimmed, without repeats, and checked.
   *
   * <p>Include rules are dropped when every table is scanned rather than kept
   * for later. A rule that is stored but has no effect reads, the next time the
   * form is opened, as though it did.
   */
  public static TableScope normalise(TableScope raw) {
    if (raw == null) {
      return EVERYTHING;
    }
    List<Rule> include = raw.mode() == Mode.ONLY ? clean(raw.include(), "scan") : List.of();
    List<Rule> exclude = clean(raw.exclude(), "exclude");
    if (raw.mode() == Mode.ONLY && include.isEmpty()) {
      throw new InvalidScopeException(
          "Name at least one table to scan, or choose to scan all tables");
    }
    return new TableScope(raw.mode(), include, exclude);
  }

  private static List<Rule> clean(List<Rule> rules, String what) {
    if (rules.size() > MAX_RULES) {
      throw new InvalidScopeException(
          "Keep the tables to " + what + " to " + MAX_RULES + " rules or fewer");
    }
    Set<String> seen = new LinkedHashSet<>();
    List<Rule> out = new ArrayList<>();
    for (Rule rule : rules) {
      if (rule == null || rule.match() == null) {
        throw new InvalidScopeException("Each rule needs a comparison, such as starts with");
      }
      String value = rule.value() == null ? "" : rule.value().trim();
      if (value.isEmpty()) {
        throw new InvalidScopeException("A rule needs some text to compare table names with");
      }
      if (value.length() > MAX_VALUE) {
        throw new InvalidScopeException(
            "A rule can be at most " + MAX_VALUE + " characters long");
      }
      if (value.chars().anyMatch(Character::isISOControl)) {
        throw new InvalidScopeException("A rule cannot contain line breaks or control characters");
      }
      if (seen.add(rule.match() + "\u0000" + value.toLowerCase(Locale.ROOT))) {
        out.add(new Rule(rule.match(), value));
      }
    }
    return List.copyOf(out);
  }
}
