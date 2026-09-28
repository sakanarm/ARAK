package com.mfec.dac.purpose;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.engine.ColumnContext;
import com.mfec.dac.engine.FacetValue;
import com.mfec.dac.schema.entity.policy.FacetCondition;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * What counts as sensitive data (FR-21, M31b), and what happens when a purpose
 * that does not allow it meets a table that holds it.
 *
 * <p>A column is sensitive when one of its labels is. The labels read are
 * classifications, tags, glossaries and glossary terms the catalog confirmed
 * (a label OpenMetadata only suggested counts for nothing here, as it counts
 * for nothing in a policy, FR-1.3a), and only the most specific of them: the
 * cache spreads every tag's ancestors beside it, so the {@code PII} that sits
 * next to {@code PII.NonSensitive} is that tag's parent, not a second label.
 *
 * <p>A label is sensitive when nothing in the "never counts" list names it or
 * anything above it, and either the "counts" list does or, with the built-in
 * rule on, it is a tag under PII or PersonalData or one that calls itself
 * sensitive, confidential, restricted or secret. "Never counts" wins, so a
 * whole classification can be counted with one tag of it left out.
 *
 * <p>A table is sensitive when it or any of its columns is. The same answer is
 * given to the query proxy, to the request form, to an access review and to the
 * dashboard, so none of them can call a table sensitive that another does not.
 */
public class SensitiveData {

  /** What a purpose that does not allow sensitive data meets on a sensitive table. */
  public enum Mode {
    /** Nothing: the purpose is recorded as it always was. */
    OFF,
    /** It goes ahead, with a warning to the person and a mark on the record. */
    WARN,
    /** It is refused, and the refusal names the purpose. */
    ENFORCE
  }

  /** What a label in the lists is: a classification or glossary covers everything in it. */
  public enum Kind {
    CLASSIFICATION,
    TAG,
    GLOSSARY,
    TERM;

    boolean glossary() {
      return this == GLOSSARY || this == TERM;
    }
  }

  public record Label(Kind kind, String fqn) {}

  /** The lists and the mode, without who changed them: what history keeps. */
  public record Settings(boolean builtIn, List<Label> include, List<Label> exclude, Mode mode) {
    public Settings {
      include = include == null ? List.of() : List.copyOf(include);
      exclude = exclude == null ? List.of() : List.copyOf(exclude);
      mode = mode == null ? Mode.WARN : mode;
    }
  }

  /**
   * The rule in force.
   *
   * @param builtIn whether the rule that used to be in the code still counts
   * @param include labels that count, each with everything beneath it
   * @param exclude labels that never count, each with everything beneath it
   */
  public record Rule(
      boolean builtIn,
      List<Label> include,
      List<Label> exclude,
      Mode mode,
      String updatedBy,
      Instant updatedAt) {

    /** What the platform shipped with: the built-in rule alone, warning. */
    public static final Rule BUILT_IN =
        new Rule(true, List.of(), List.of(), Mode.WARN, "system", null);

    public Rule {
      include = include == null ? List.of() : List.copyOf(include);
      exclude = exclude == null ? List.of() : List.copyOf(exclude);
      mode = mode == null ? Mode.WARN : mode;
    }

    public Rule withMode(Mode other) {
      return new Rule(builtIn, include, exclude, other, updatedBy, updatedAt);
    }

    public Settings settings() {
      return new Settings(builtIn, include, exclude, mode);
    }

    /**
     * The labels among these that make their target sensitive, most specific
     * only, in the order met.
     *
     * @param tags classifications and tags, ancestors included or not
     * @param terms glossaries and glossary terms, likewise
     */
    public List<String> labels(Collection<String> tags, Collection<String> terms) {
      List<String> out = new ArrayList<>();
      for (String leaf : leaves(tags)) {
        if (counts(leaf, false)) {
          out.add(leaf);
        }
      }
      for (String leaf : leaves(terms)) {
        if (counts(leaf, true)) {
          out.add(leaf);
        }
      }
      return List.copyOf(out);
    }

    /** The labels that make this column sensitive; empty when it is not. */
    public List<String> labels(ColumnContext column) {
      Map<FacetCondition.FacetType, List<FacetValue>> facets = column.facets();
      List<String> tags = new ArrayList<>();
      confirmed(facets.get(FacetCondition.FacetType.CLASSIFICATIONS), tags);
      confirmed(facets.get(FacetCondition.FacetType.TAGS), tags);
      List<String> terms = new ArrayList<>();
      confirmed(facets.get(FacetCondition.FacetType.GLOSSARIES), terms);
      confirmed(facets.get(FacetCondition.FacetType.TERMS), terms);
      return labels(tags, terms);
    }

    private boolean counts(String leaf, boolean glossary) {
      for (Label never : exclude) {
        if (covers(never, leaf, glossary)) {
          return false;
        }
      }
      for (Label always : include) {
        if (covers(always, leaf, glossary)) {
          return true;
        }
      }
      return builtIn && !glossary && builtInSensitive(leaf);
    }
  }

  /** One kept change. {@code before} is null for the row the platform started with. */
  public record Change(
      long id, Instant at, String actor, String reason, Settings before, Settings after) {}

  /**
   * How much of the catalog a rule covers.
   *
   * @param catalogTables tables and views in the catalog at all, for scale
   * @param examples the covered tables with the most sensitive columns first, at most {@value #EXAMPLES}
   * @param labels the labels doing the covering, most used first, at most {@value #LABELS}
   */
  public record Coverage(
      int tables, int columns, int catalogTables, List<Covered> examples, List<LabelUse> labels) {}

  /** @param columns sensitive columns; zero when only the table itself is labelled */
  public record Covered(String fqn, int columns) {}

  public record LabelUse(String label, int tables, int columns) {}

  /**
   * A purpose meeting sensitive data it does not allow.
   *
   * @param purpose the purpose as stored; null when none was named
   * @param purposeName what people call it; the stored word when the register lacks it
   * @param message the same words whether it warns or refuses
   */
  public record Concern(
      Mode mode,
      String table,
      List<String> labels,
      String purpose,
      String purposeName,
      String message) {

    public boolean refuses() {
      return mode == Mode.ENFORCE;
    }
  }

  static final int EXAMPLES = 50;
  static final int LABELS = 30;

  /** How long a read of the rule is trusted; a change made here is seen at once. */
  static final long HOLD_NANOS = 10_000_000_000L;

  private static final String LABEL_TYPES = "('classifications', 'tags', 'glossaries', 'terms')";

  private final Jdbi jdbi;
  private final ObjectMapper json;
  private final Rule fixed;
  private volatile Held held;

  private record Held(Rule rule, long readAt) {}

  public SensitiveData(Jdbi jdbi, ObjectMapper json) {
    this.jdbi = jdbi;
    this.json = json;
    this.fixed = null;
  }

  private SensitiveData(Rule fixed) {
    this.jdbi = null;
    this.json = new ObjectMapper();
    this.fixed = fixed;
  }

  /** A rule that is not stored anywhere, for code and tests without a database of it. */
  public static SensitiveData fixed(Rule rule) {
    return new SensitiveData(rule);
  }

  /** The built-in rule with no purpose checks: what everything did before this setting. */
  public static SensitiveData off() {
    return fixed(Rule.BUILT_IN.withMode(Mode.OFF));
  }

  // ------------------------------------------------------------------ reading

  public Rule current() {
    if (fixed != null) {
      return fixed;
    }
    Held now = held;
    long at = System.nanoTime();
    if (now != null && at - now.readAt() < HOLD_NANOS) {
      return now.rule();
    }
    Rule rule = jdbi.withHandle(this::read);
    held = new Held(rule, at);
    return rule;
  }

  private Rule read(Handle h) {
    return h.createQuery(
            "SELECT built_in, include::text AS include, exclude::text AS exclude, mode,"
                + " updated_by, updated_at FROM sensitive_data_rule WHERE id = 1")
        .map(
            (rs, ctx) ->
                new Rule(
                    rs.getBoolean("built_in"),
                    labelList(rs.getString("include")),
                    labelList(rs.getString("exclude")),
                    Mode.valueOf(rs.getString("mode")),
                    rs.getString("updated_by"),
                    instant(rs.getTimestamp("updated_at"))))
        .findOne()
        // Somebody deleted the one row; the rule the platform shipped with holds.
        .orElse(Rule.BUILT_IN);
  }

  public List<Change> history() {
    if (fixed != null) {
      return List.of();
    }
    return jdbi.withHandle(
        h ->
            h.createQuery(
                    "SELECT id, occurred_at, actor, reason, before::text AS before,"
                        + " after::text AS after FROM audit_sensitive_data_rule"
                        + " ORDER BY occurred_at DESC, id DESC")
                .map(
                    (rs, ctx) ->
                        new Change(
                            rs.getLong("id"),
                            instant(rs.getTimestamp("occurred_at")),
                            rs.getString("actor"),
                            rs.getString("reason"),
                            settings(rs.getString("before")),
                            settings(rs.getString("after"))))
                .list());
  }

  // ------------------------------------------------------------------ changing

  /**
   * Replaces the rule, keeping what it was and why it changed.
   *
   * @throws IllegalArgumentException when the lists are not a rule, or nothing changes
   */
  public Rule update(Settings wanted, String reason, String actor) {
    if (fixed != null) {
      throw new IllegalStateException("This rule is not stored anywhere");
    }
    Settings clean =
        new Settings(
            wanted.builtIn(),
            clean(wanted.include(), "counts"),
            clean(wanted.exclude(), "never counts"),
            wanted.mode());
    for (Label label : clean.include()) {
      if (clean.exclude().stream().anyMatch(x -> same(x, label))) {
        throw new IllegalArgumentException(
            label.fqn() + " is in both lists; keep it in one");
      }
    }
    Rule after =
        jdbi.inTransaction(
            h -> {
              Rule before = read(h);
              if (before.settings().equals(clean)) {
                throw new IllegalArgumentException("That is the rule already; nothing to change");
              }
              h.createUpdate(
                      """
                      UPDATE sensitive_data_rule
                         SET built_in = :builtIn, include = CAST(:include AS jsonb),
                             exclude = CAST(:exclude AS jsonb), mode = :mode,
                             updated_by = :actor, updated_at = now()
                       WHERE id = 1
                      """)
                  .bind("builtIn", clean.builtIn())
                  .bind("include", write(clean.include()))
                  .bind("exclude", write(clean.exclude()))
                  .bind("mode", clean.mode().name())
                  .bind("actor", actor)
                  .execute();
              h.createUpdate(
                      """
                      INSERT INTO audit_sensitive_data_rule (actor, reason, before, after)
                      VALUES (:actor, :reason, CAST(:before AS jsonb), CAST(:after AS jsonb))
                      """)
                  .bind("actor", actor)
                  .bind("reason", reason)
                  .bind("before", write(before.settings()))
                  .bind("after", write(clean))
                  .execute();
              return read(h);
            });
    held = new Held(after, System.nanoTime());
    return after;
  }

  public static final int MAX_LABELS = 100;
  public static final int MAX_FQN = 256;

  private static List<Label> clean(List<Label> labels, String which) {
    List<Label> out = new ArrayList<>();
    for (Label label : labels == null ? List.<Label>of() : labels) {
      if (label == null || label.kind() == null) {
        throw new IllegalArgumentException(
            "Every label in \"" + which + "\" needs a kind: classification, tag, glossary or term");
      }
      String fqn = label.fqn() == null ? "" : label.fqn().strip();
      if (fqn.isEmpty()) {
        throw new IllegalArgumentException("A label in \"" + which + "\" has no name");
      }
      if (fqn.length() > MAX_FQN || fqn.codePoints().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException(
            "Label names are up to " + MAX_FQN + " characters, without control characters");
      }
      Label tidy = new Label(label.kind(), fqn);
      if (out.stream().noneMatch(o -> same(o, tidy))) {
        out.add(tidy);
      }
    }
    if (out.size() > MAX_LABELS) {
      throw new IllegalArgumentException(
          "Keep \"" + which + "\" to " + MAX_LABELS + " labels; a classification covers every tag in it");
    }
    return List.copyOf(out);
  }

  private static boolean same(Label a, Label b) {
    return a.kind() == b.kind() && a.fqn().equalsIgnoreCase(b.fqn());
  }

  // ----------------------------------------------------------------- judging

  /**
   * Whether naming this purpose on this table is a concern under the rule in
   * force: never when the mode is off or the purpose allows sensitive data,
   * otherwise when the table or one of its columns holds some.
   *
   * <p>A purpose the register lacks -- a word a request template lists of its
   * own -- allows nothing sensitive, because nobody has said it does. No
   * purpose at all allows nothing sensitive either.
   */
  public Optional<Concern> concern(Handle h, String tableFqn, String purpose) {
    Judgement judged = judge(h, tableFqn, purpose);
    return judged == null ? Optional.empty() : Optional.ofNullable(judged.concern());
  }

  /**
   * What the rule says of a table, and of naming this purpose on it.
   *
   * @param concern null when the purpose may be used there, or nothing there is sensitive
   */
  public record Judgement(boolean sensitive, Concern concern) {}

  /** As {@link #concern}, and whether the table is sensitive at all; null when the mode is off. */
  public Judgement judge(Handle h, String tableFqn, String purpose) {
    Rule rule = current();
    if (rule.mode() == Mode.OFF || tableFqn == null || tableFqn.isBlank()) {
      return null;
    }
    List<String> labels = labelsOn(h, tableFqn, rule);
    if (labels.isEmpty()) {
      return new Judgement(false, null);
    }
    String named = purpose == null || purpose.isBlank() ? null : purpose.strip();
    PurposeStore.Purpose listed = named == null ? null : PurposeStore.find(h, named).orElse(null);
    if (listed != null && listed.sensitiveAllowed()) {
      return new Judgement(true, null);
    }
    String name = listed == null ? named : listed.name();
    return new Judgement(
        true,
        new Concern(
            rule.mode(),
            tableFqn,
            labels,
            listed == null ? named : listed.key(),
            name,
            message(tableFqn, labels, name)));
  }

  /** {@link #concern(Handle, String, String)} on a handle of its own. */
  public Optional<Concern> concern(String tableFqn, String purpose) {
    if (current().mode() == Mode.OFF) {
      return Optional.empty();
    }
    if (jdbi == null) {
      throw new IllegalStateException("This rule is not stored anywhere to check against");
    }
    return jdbi.withHandle(h -> concern(h, tableFqn, purpose));
  }

  static String message(String table, List<String> labels, String purposeName) {
    String held = table + " holds sensitive data (" + shown(labels) + ")";
    if (purposeName == null) {
      return held + ", and no purpose was named. Name one that sensitive data may be used for";
    }
    return held
        + ", and "
        + purposeName
        + " is not a purpose sensitive data may be used for. Choose one that is, or ask a"
        + " policy author to allow it under Settings, Purposes";
  }

  private static String shown(List<String> labels) {
    if (labels.size() <= 3) {
      return String.join(", ", labels);
    }
    return String.join(", ", labels.subList(0, 3)) + " and " + (labels.size() - 3) + " more";
  }

  /** The labels that make this table or its columns sensitive, the table's own first. */
  public static List<String> labelsOn(Handle h, String tableFqn, Rule rule) {
    Map<String, Target> targets = new LinkedHashMap<>();
    h.createQuery(
            """
            SELECT f.target_fqn, f.column_id IS NOT NULL AS on_column, f.facet_type, f.facet_fqn
              FROM asset_facet f
             WHERE f.facet_type IN """
                + LABEL_TYPES
                + """

               AND f.om_state IS DISTINCT FROM 'Suggested'
               AND (f.asset_id IN (SELECT id FROM asset WHERE fqn = :fqn AND is_current)
                    OR f.column_id IN (SELECT c.id FROM asset_column c
                                         JOIN asset a ON a.id = c.asset_id
                                        WHERE a.fqn = :fqn AND a.is_current AND c.is_current))
             ORDER BY f.column_id IS NOT NULL, f.target_fqn
            """)
        .bind("fqn", tableFqn)
        .map(
            (rs, ctx) ->
                new FacetRow(
                    tableFqn,
                    rs.getString("target_fqn"),
                    rs.getBoolean("on_column"),
                    rs.getString("facet_type"),
                    rs.getString("facet_fqn")))
        .forEach(row -> add(targets, row));
    Set<String> out = new LinkedHashSet<>();
    for (Target target : targets.values()) {
      out.addAll(rule.labels(target.tags, target.terms));
    }
    return List.copyOf(out);
  }

  /** How much of the catalog this rule covers, for a person deciding whether to save it. */
  public Coverage coverage(Rule rule) {
    return jdbi.withHandle(h -> coverage(h, rule));
  }

  static Coverage coverage(Handle h, Rule rule) {
    Map<String, Target> targets = new LinkedHashMap<>();
    h.createQuery(
            """
            SELECT a.fqn AS table_fqn, f.target_fqn, f.column_id IS NOT NULL AS on_column,
                   f.facet_type, f.facet_fqn
              FROM asset_facet f
              LEFT JOIN asset_column c ON c.id = f.column_id
              JOIN asset a ON a.id = COALESCE(f.asset_id, c.asset_id)
             WHERE a.is_current AND a.asset_type IN ('TABLE', 'VIEW')
               AND (f.column_id IS NULL OR c.is_current)
               AND f.om_state IS DISTINCT FROM 'Suggested'
               AND f.facet_type IN """
                + LABEL_TYPES)
        .map(
            (rs, ctx) ->
                new FacetRow(
                    rs.getString("table_fqn"),
                    rs.getString("target_fqn"),
                    rs.getBoolean("on_column"),
                    rs.getString("facet_type"),
                    rs.getString("facet_fqn")))
        .forEach(row -> add(targets, row));

    Map<String, int[]> tables = new LinkedHashMap<>();
    Map<String, Set<String>> labelTables = new LinkedHashMap<>();
    Map<String, int[]> labelColumns = new LinkedHashMap<>();
    int columns = 0;
    for (Target target : targets.values()) {
      List<String> labels = rule.labels(target.tags, target.terms);
      if (labels.isEmpty()) {
        continue;
      }
      int[] count = tables.computeIfAbsent(target.table, k -> new int[1]);
      if (target.onColumn) {
        count[0]++;
        columns++;
      }
      for (String label : labels) {
        labelTables.computeIfAbsent(label, k -> new LinkedHashSet<>()).add(target.table);
        if (target.onColumn) {
          labelColumns.computeIfAbsent(label, k -> new int[1])[0]++;
        }
      }
    }
    int catalog =
        h.createQuery(
                "SELECT count(*) FROM asset WHERE is_current AND asset_type IN ('TABLE', 'VIEW')")
            .mapTo(Integer.class)
            .one();
    List<Covered> examples =
        tables.entrySet().stream()
            .map(e -> new Covered(e.getKey(), e.getValue()[0]))
            .sorted(Comparator.comparingInt(Covered::columns).reversed().thenComparing(Covered::fqn))
            .limit(EXAMPLES)
            .toList();
    List<LabelUse> labels =
        labelTables.entrySet().stream()
            .map(
                e ->
                    new LabelUse(
                        e.getKey(),
                        e.getValue().size(),
                        labelColumns.getOrDefault(e.getKey(), new int[1])[0]))
            .sorted(
                Comparator.comparingInt(LabelUse::columns)
                    .thenComparingInt(LabelUse::tables)
                    .reversed()
                    .thenComparing(LabelUse::label))
            .limit(LABELS)
            .toList();
    return new Coverage(tables.size(), columns, catalog, examples, labels);
  }

  private record FacetRow(
      String table, String target, boolean onColumn, String facetType, String facetFqn) {}

  private static final class Target {
    private final String table;
    private final boolean onColumn;
    private final List<String> tags = new ArrayList<>();
    private final List<String> terms = new ArrayList<>();

    private Target(String table, boolean onColumn) {
      this.table = table;
      this.onColumn = onColumn;
    }
  }

  private static void add(Map<String, Target> targets, FacetRow row) {
    Target target =
        targets.computeIfAbsent(row.target(), k -> new Target(row.table(), row.onColumn()));
    switch (row.facetType()) {
      case "classifications", "tags" -> target.tags.add(row.facetFqn());
      default -> target.terms.add(row.facetFqn());
    }
  }

  // ---------------------------------------------------------------- the rule

  /**
   * The rule that was written into the code before there was a setting: under
   * the PII or PersonalData classifications, or calling itself sensitive,
   * confidential, restricted or secret -- except one that says it is not
   * ({@code PII.NonSensitive}) or public.
   */
  public static boolean builtInSensitive(String tag) {
    String t = tag.toLowerCase(Locale.ROOT);
    if (t.contains("nonsensitive") || t.contains("non-sensitive") || t.contains("public")) {
      return false;
    }
    if (t.equals("pii") || t.startsWith("pii.") || t.equals("personaldata")
        || t.startsWith("personaldata.")) {
      return true;
    }
    for (String word : List.of("sensitive", "confidential", "restricted", "secret")) {
      if (t.contains(word)) {
        return true;
      }
    }
    return false;
  }

  /** Values none of the others sit beneath, each once, in the order met. */
  static List<String> leaves(Collection<String> values) {
    List<String> distinct = new ArrayList<>();
    for (String value : values == null ? List.<String>of() : values) {
      if (value != null && !value.isBlank()
          && distinct.stream().noneMatch(v -> v.equalsIgnoreCase(value))) {
        distinct.add(value);
      }
    }
    List<String> out = new ArrayList<>();
    for (String value : distinct) {
      String prefix = value.toLowerCase(Locale.ROOT) + ".";
      if (distinct.stream().noneMatch(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix))) {
        out.add(value);
      }
    }
    return out;
  }

  /** Whether the label names this value or something above it, in the same vocabulary. */
  static boolean covers(Label label, String value, boolean glossary) {
    if (label.kind().glossary() != glossary) {
      return false;
    }
    String l = label.fqn().toLowerCase(Locale.ROOT);
    String v = value.toLowerCase(Locale.ROOT);
    return v.equals(l) || v.startsWith(l + ".");
  }

  private static void confirmed(List<FacetValue> values, List<String> into) {
    if (values == null) {
      return;
    }
    for (FacetValue value : values) {
      if (value.value() != null && !value.suggested()) {
        into.add(value.value());
      }
    }
  }

  // ---------------------------------------------------------------- plumbing

  private List<Label> labelList(String text) {
    try {
      return text == null ? List.of() : json.readValue(text, new TypeReference<List<Label>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("The stored sensitive-data rule is not readable", e);
    }
  }

  private Settings settings(String text) {
    try {
      return text == null ? null : json.readValue(text, Settings.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("A kept sensitive-data rule is not readable", e);
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not write the sensitive-data rule as JSON", e);
    }
  }

  private static Instant instant(Timestamp at) {
    return at == null ? null : at.toInstant();
  }
}
