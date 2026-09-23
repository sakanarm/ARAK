package com.mfec.dac.compiler.sql;

import com.mfec.dac.engine.MaskStrength;
import com.mfec.dac.schema.api.MaskingSpec;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import com.mfec.dac.schema.api.Unenforceable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enforcement mode 5.1.2: a view beside the data that applies the policy to
 * whoever is reading it (FR-6.1).
 *
 * <h2>Why this compiler does not bake a principal in</h2>
 *
 * <p>The proxy compiles a statement for the one person who sent it, so it can
 * put their branch codes straight into the {@code WHERE} clause. A view cannot:
 * it is a single object that every reader shares, and a view holding one
 * reader's values would show that reader's rows to everybody else. So the two
 * halves are split. The <em>shape</em> of the policy -- which columns are
 * masked, with which function, gated on which row key -- is compiled into the
 * view and changes only when a policy changes. Who gets what is data, kept in
 * three small tables the view joins at read time:
 *
 * <ul>
 *   <li>{@code acl.asset_subscription} -- may this principal read the asset at
 *       all. A principal with no row sees the columns and no rows, which is
 *       what a denied subscription looks like through a view.
 *   <li>{@code acl.row_entitlement} -- the values of each gating key this
 *       principal may see, one row per value (FR-4.1).
 *   <li>{@code acl.column_grant} -- which treatment of each masked column this
 *       principal gets: plaintext, or one of the masks the view knows how to
 *       apply.
 * </ul>
 *
 * <p>Those tables are written by the maintainer from the very
 * {@link PolicyDecision}s this compiler reads the shape from, which is what
 * keeps the mode honest: the engine still decides everything, and this class
 * only decides where the answer is stored.
 *
 * <p>It also means adding a person, or moving one between branches, is an
 * {@code INSERT} rather than a {@code CREATE VIEW}. On a table with a thousand
 * readers that is the difference between a routine sync and a daily DDL change
 * on production.
 *
 * <h2>Every branch is the strict one</h2>
 *
 * <p>Absence never opens anything. A principal with no subscription row reads
 * nothing; with no entitlement row, no rows of that key; with no column grant,
 * the strictest mask any policy asked for. So a maintainer run that fails
 * halfway leaves readers with too little rather than too much, and a table
 * nobody has synced yet is closed rather than open.
 *
 * <h2>What one shared object cannot do</h2>
 *
 * <p>A view has one column list. A column hidden from anybody is therefore
 * hidden from everybody here (FR-4.5), and that is reported in the notes rather
 * than performed quietly -- it is the one place this mode is stricter than the
 * policy, and whoever approves the DDL has to see it.
 */
public final class ViewCompiler {

  /** The treatment key for a column read in the clear. */
  public static final String PLAIN = "PLAIN";

  /** Objects this platform creates are held to a plain name; see {@link Target}. */
  private static final Pattern OWNED_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

  /** The alias the base table carries inside the view. */
  private static final String BASE = "t";

  /** How the view finds out who is reading it (plan, ส่วนที่ 2). */
  public enum IdentitySource {
    /** The database principal itself -- option A, and the only one safe alone. */
    DB_PRINCIPAL,
    /** A name the proxy set on the session -- option B, and only behind a proxy. */
    SESSION_CONTEXT
  }

  /**
   * Where the view goes and what it reads.
   *
   * <p>The two halves of this record are trusted differently, deliberately. The
   * secure schema, the view name and the ACL schema are names <em>this
   * platform</em> chose, so they are held to a plain identifier: they end up in
   * DDL, and a name that needed quoting to be safe is a name somebody talked us
   * into. The source schema, table and columns come from a customer's database
   * and may be anything at all; they are never checked, only quoted, because
   * refusing to govern a table whose name has a space in it would leave that
   * table ungoverned.
   *
   * @param assetKey what the ACL tables call this asset. Defaults to the
   *     physical {@code schema.table}, lowercased, because whoever is reading
   *     the entitlement rows to debug an access problem is looking at the
   *     database, not at the catalog.
   * @param sourceColumns every column the table actually has, in ordinal order,
   *     from live introspection rather than the catalog: a column the catalog
   *     has not heard of is exactly the column no policy covers (FR-1.6).
   * @param readerRole the role granted SELECT on the view, or null to leave
   *     granting to the cutover step.
   */
  public record Target(
      String sourceSchema,
      String sourceTable,
      String secureSchema,
      String secureView,
      String aclSchema,
      String assetKey,
      List<String> sourceColumns,
      IdentitySource identity,
      String readerRole) {

    public Target {
      sourceSchema = required(sourceSchema, "source schema");
      sourceTable = required(sourceTable, "source table");
      secureSchema = owned(secureSchema, "secure schema");
      secureView = owned(secureView, "secure view name");
      aclSchema = owned(aclSchema == null ? "acl" : aclSchema, "entitlement schema");
      assetKey =
          blank(assetKey)
              ? (sourceSchema + "." + sourceTable).toLowerCase(Locale.ROOT)
              : assetKey.trim();
      if (sourceColumns == null || sourceColumns.isEmpty()) {
        throw new IllegalArgumentException(
            "no columns were introspected for "
                + sourceSchema
                + "."
                + sourceTable
                + "; refusing to build a view over a table we cannot see the shape of");
      }
      sourceColumns = List.copyOf(sourceColumns);
      identity = identity == null ? IdentitySource.DB_PRINCIPAL : identity;
      readerRole = blank(readerRole) ? null : readerRole.trim();
    }

    String qualifiedSource(SqlDialect dialect) {
      return dialect.quote(sourceSchema) + "." + dialect.quote(sourceTable);
    }

    String qualifiedView(SqlDialect dialect) {
      return dialect.quote(secureSchema) + "." + dialect.quote(secureView);
    }

    String acl(SqlDialect dialect, String table) {
      return dialect.quote(aclSchema) + "." + dialect.quote(table);
    }

    private static boolean blank(String value) {
      return value == null || value.isBlank();
    }

    private static String required(String value, String what) {
      if (blank(value)) {
        throw new IllegalArgumentException("missing " + what);
      }
      return value.trim();
    }

    private static String owned(String value, String what) {
      String trimmed = required(value, what);
      if (!OWNED_IDENTIFIER.matcher(trimmed).matches()) {
        throw new IllegalArgumentException(
            "the "
                + what
                + " becomes part of a CREATE statement, so it must be a plain identifier:"
                + " letters, digits and underscores, starting with a letter or underscore");
      }
      return trimmed;
    }
  }

  /**
   * One treatment of one column that the view knows how to apply.
   *
   * @param fallback true for the treatment a principal gets when no grant row
   *     names them. It is always the strictest one the policies asked for.
   */
  public record Treatment(String column, String key, boolean fallback) {}

  /**
   * The statements to run, the statements that undo them, and what a reviewer
   * has to read before agreeing to either.
   *
   * @param treatments every key the maintainer may write into
   *     {@code column_grant} for this asset. A key not in this list has no
   *     branch in the view and would read as the fallback.
   * @param entitlementKeys every key the maintainer must populate in
   *     {@code row_entitlement}. A key left empty hides every row from
   *     everybody, which is safe and very visible.
   */
  public record Plan(
      String viewSql,
      List<String> apply,
      List<String> rollback,
      List<String> notes,
      List<Unenforceable> unenforceable,
      List<Treatment> treatments,
      List<String> entitlementKeys) {

    /** The apply statements as one reviewable script. */
    public String applyScript() {
      return script(apply);
    }

    public String rollbackScript() {
      return script(rollback);
    }

    private static String script(List<String> statements) {
      StringBuilder out = new StringBuilder();
      for (String statement : statements) {
        out.append(statement).append(";\n\n");
      }
      return out.toString().stripTrailing() + "\n";
    }
  }

  private final SqlDialect dialect;

  public ViewCompiler(SqlDialect dialect) {
    this.dialect = Objects.requireNonNull(dialect, "dialect");
  }

  /** Compiles from one decision; see {@link #compile(List, Target)}. */
  public Plan compile(PolicyDecision shape, Target target) {
    return compile(List.of(shape), target);
  }

  /**
   * Compiles the view from the shape of one or more decisions.
   *
   * <p>Pass a decision per principal who reads the asset, or per distinct way
   * of reading it. Only the <em>structure</em> is read -- which column is
   * masked with which function, which key gates the rows -- and the resolved
   * values inside each decision are deliberately ignored, because they belong
   * to one reader and this object serves all of them.
   *
   * <p>Passing fewer decisions than there are readers does not open anything: a
   * mask no decision mentioned has no branch, and a principal granted a
   * treatment the view does not know falls through to the fallback, which is
   * the strictest mask of those it does know.
   */
  public Plan compile(List<PolicyDecision> shapes, Target target) {
    Objects.requireNonNull(target, "target");
    if (shapes == null || shapes.isEmpty()) {
      throw new IllegalArgumentException(
          "no decision to take the shape from; a view compiled from nothing would grant nothing"
              + " and say nothing about why");
    }

    List<Unenforceable> unenforceable = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    String principal =
        target.identity() == IdentitySource.SESSION_CONTEXT
            ? dialect.sessionPrincipal()
            : dialect.currentDbPrincipal();
    if (target.identity() == IdentitySource.SESSION_CONTEXT) {
      notes.add(
          "This view believes app.principal, which the session sets for itself. It is only true"
              + " while every connection to this database comes through the proxy -- anyone who"
              + " can connect directly can name themselves anybody. Firewall the source, or"
              + " switch the asset to DB_PRINCIPAL.");
    }

    Set<String> hidden = new LinkedHashSet<>();
    Map<String, Map<String, MaskCandidate>> masks = new LinkedHashMap<>();
    Map<String, RowGate> gates = new LinkedHashMap<>();
    Set<String> raw = new LinkedHashSet<>();
    Set<String> conditions = new LinkedHashSet<>();
    List<String> direct = new ArrayList<>();
    boolean[] blocked = {false};

    for (PolicyDecision shape : shapes) {
      if (shape == null) {
        continue;
      }
      if (shape.getHiddenColumns() != null) {
        for (String column : shape.getHiddenColumns()) {
          if (column != null) {
            hidden.add(column.toLowerCase(Locale.ROOT));
          }
        }
      }
      if (shape.getColumnMasks() != null) {
        for (ResolvedColumnMask mask : shape.getColumnMasks()) {
          if (mask == null || mask.getColumn() == null) {
            continue;
          }
          MaskCandidate candidate =
              new MaskCandidate(
                  treatmentKey(mask.getMasking(), mask.getCondition()),
                  mask.getMasking(),
                  mask.getCondition(),
                  MaskStrength.rank(mask.getMasking()));
          masks
              .computeIfAbsent(mask.getColumn().toLowerCase(Locale.ROOT), c -> new LinkedHashMap<>())
              .putIfAbsent(candidate.key(), candidate);
          if (candidate.condition() != null && !candidate.condition().isBlank()) {
            conditions.add(mask.getColumn() + ": " + candidate.condition());
          }
        }
      }
      if (shape.getRowPredicates() != null) {
        for (ResolvedRowPredicate predicate : shape.getRowPredicates()) {
          rowFilter(predicate, gates, raw, direct, notes, unenforceable, blocked);
        }
      }
    }

    if (!conditions.isEmpty()) {
      notes.add(
          "A cell mask's condition is authored SQL and goes into the view exactly as written, so"
              + " it has to be valid "
              + dialect.name()
              + " and has to name columns of this table only -- nothing checks it before the"
              + " engine does: "
              + String.join("; ", conditions));
    }

    List<Treatment> treatments = new ArrayList<>();
    List<String> projection =
        projection(target, principal, hidden, masks, treatments, notes, unenforceable);
    if (projection.isEmpty()) {
      throw new IllegalArgumentException(
          "every column of "
              + target.assetKey()
              + " is hidden, so the view would have no columns at all; withhold the subscription"
              + " instead of building it");
    }

    List<String> conjuncts = new ArrayList<>();
    conjuncts.add(
        exists(
            target.acl(dialect, "asset_subscription"),
            "s",
            List.of(
                "s." + dialect.quote("principal") + " = " + principal,
                "s." + dialect.quote("asset") + " = " + dialect.literal(target.assetKey())),
            false,
            "  "));
    for (RowGate gate : gates.values()) {
      conjuncts.add(gate(target, principal, gate));
    }
    for (String predicate : direct) {
      conjuncts.add("(" + predicate + ")");
    }
    if (blocked[0]) {
      conjuncts.add("(1 = 0)");
    }

    String viewSql =
        dialect.createOrReplaceView(target.qualifiedView(dialect), body(target, projection, conjuncts));

    notes.add(
        "Readers need SELECT on "
            + target.qualifiedView(dialect)
            + " and on nothing else. The view reads the entitlement tables as its own owner, so a"
            + " reader granted SELECT on "
            + dialect.quote(target.aclSchema())
            + " could read everybody's entitlements -- never grant on it.");

    return new Plan(
        viewSql,
        List.copyOf(apply(target, viewSql)),
        List.copyOf(rollback(target, notes)),
        List.copyOf(notes),
        List.copyOf(unenforceable),
        List.copyOf(treatments),
        List.copyOf(gates.keySet()));
  }

  // ------------------------------------------------------------ projection

  private record MaskCandidate(String key, MaskingSpec spec, String condition, int rank) {}

  private List<String> projection(
      Target target,
      String principal,
      Set<String> hidden,
      Map<String, Map<String, MaskCandidate>> masks,
      List<Treatment> treatments,
      List<String> notes,
      List<Unenforceable> unenforceable) {

    List<String> out = new ArrayList<>();
    for (String column : target.sourceColumns()) {
      String key = column.toLowerCase(Locale.ROOT);
      if (hidden.contains(key)) {
        notes.add(
            "Column "
                + column
                + " is not in the view at all. One view serves every reader and its column list"
                + " cannot vary, so a column hidden from anybody is hidden from everybody in this"
                + " mode. Readers who were entitled to it lose it here (FR-4.5).");
        continue;
      }
      String reference = dialect.quote(BASE) + "." + dialect.quote(column);
      Map<String, MaskCandidate> candidates = masks.get(key);
      if (candidates == null || candidates.isEmpty()) {
        out.add(reference + " AS " + dialect.quote(column));
        continue;
      }
      out.add(
          maskedColumn(target, principal, column, reference, candidates, treatments, unenforceable));
    }
    return out;
  }

  /**
   * One column rendered as a choice between the treatments any policy asked
   * for.
   *
   * <p>The branches are ordered strictest first and the strictest is also the
   * {@code ELSE}. Ordering that way costs nothing -- the grant table holds one
   * row per principal and column -- but it means that if the maintainer ever
   * writes two rows for one column, the reader gets the stricter of them rather
   * than whichever the engine happened to return first.
   */
  private String maskedColumn(
      Target target,
      String principal,
      String column,
      String reference,
      Map<String, MaskCandidate> candidates,
      List<Treatment> treatments,
      List<Unenforceable> unenforceable) {

    List<MaskCandidate> ordered = new ArrayList<>(candidates.values());
    // Reading the column as it is stored is a treatment like any other, and the
    // weakest one there is: it needs a grant row too.
    ordered.add(new MaskCandidate(PLAIN, null, null, 0));
    ordered.sort(
        Comparator.comparingInt(MaskCandidate::rank).reversed().thenComparing(MaskCandidate::key));

    MaskCandidate fallback = ordered.get(0);
    StringBuilder out = new StringBuilder("CASE\n");
    for (MaskCandidate candidate : ordered) {
      treatments.add(new Treatment(column, candidate.key(), candidate == fallback));
      if (candidate == fallback) {
        continue;
      }
      out.append("    WHEN ")
          .append(grant(target, principal, column, candidate.key()))
          .append(" THEN ")
          .append(expression(reference, candidate, column, unenforceable))
          .append('\n');
    }
    return out.append("    ELSE ")
        .append(expression(reference, fallback, column, unenforceable))
        .append('\n')
        .append("  END AS ")
        .append(dialect.quote(column))
        .toString();
  }

  private String expression(
      String reference, MaskCandidate candidate, String column, List<Unenforceable> unenforceable) {
    if (candidate.spec() == null) {
      return reference;
    }
    try {
      return DecisionSql.masked(dialect, reference, candidate.spec(), candidate.condition());
    } catch (UnsupportedMaskingException e) {
      // The strictest possible reading of "this engine cannot express it", and
      // the same one the proxy takes on the same mask.
      unenforceable.add(
          new Unenforceable()
              .withDetail("column " + column + ": " + e.getMessage() + "; nulled out instead")
              .withSuggestedMode(Unenforceable.SuggestedMode.PROXY));
      return "NULL";
    }
  }

  private String grant(Target target, String principal, String column, String treatment) {
    return exists(
        target.acl(dialect, "column_grant"),
        "g",
        List.of(
            "g." + dialect.quote("principal") + " = " + principal,
            "g." + dialect.quote("asset") + " = " + dialect.literal(target.assetKey()),
            "g." + dialect.quote("column_name") + " = " + dialect.literal(column),
            "g." + dialect.quote("treatment") + " = " + dialect.literal(treatment)),
        false,
        "           ");
  }

  // ------------------------------------------------------------ row filters

  private record RowGate(String key, String column, boolean negated) {}

  private void rowFilter(
      ResolvedRowPredicate predicate,
      Map<String, RowGate> gates,
      Set<String> raw,
      List<String> direct,
      List<String> notes,
      List<Unenforceable> unenforceable,
      boolean[] blocked) {

    if (predicate == null) {
      return;
    }
    ResolvedRowPredicate.Kind kind =
        predicate.getKind() == null ? ResolvedRowPredicate.Kind.ALWAYS_FALSE : predicate.getKind();

    switch (kind) {
      case ALWAYS_FALSE -> {
        // Per-principal, and so not something a shared object can say. "This
        // principal sees no rows" is already what an absent subscription row
        // means here; writing 1 = 0 into the view would say it about everybody
        // instead of about them.
        notes.add(
            "A policy denies every row to at least one principal. In this mode that is enforced by"
                + " leaving them out of asset_subscription, not by the view -- the view would have"
                + " to say it about every reader.");
      }
      case RAW_PREDICATE -> {
        String sql = predicate.getRawPredicate();
        if (sql == null || sql.isBlank()) {
          throw new IllegalArgumentException("a RAW_PREDICATE row filter carries no SQL");
        }
        if (raw.add(sql)) {
          direct.add(sql);
          notes.add(
              "A hand-written row filter goes into the view exactly as authored, and it is the"
                  + " same text for every reader: "
                  + sql
                  + " -- read it before applying. If it was meant to name one principal, it now"
                  + " names them to everybody.");
        }
      }
      case ENTITLEMENT_JOIN -> {
        String key = first(predicate.getEntitlementKey(), predicate.getColumn());
        if (key == null) {
          throw new IllegalArgumentException(
              "an entitlement row filter carries neither an entitlement key nor a column");
        }
        put(gates, new RowGate(key, predicate.getColumn(), false));
      }
      case IN_LIST, ATTRIBUTE_COMPARE -> {
        String column = predicate.getColumn();
        if (column == null || column.isBlank()) {
          throw new IllegalArgumentException("a " + kind + " row filter carries no column");
        }
        ResolvedRowPredicate.FacetOperator operator =
            predicate.getOperator() == null
                ? ResolvedRowPredicate.FacetOperator.IN
                : predicate.getOperator();
        switch (operator) {
          case IN, EQ -> put(gates, new RowGate(key(predicate, column), column, false));
          case NOT_IN, NE -> put(gates, new RowGate(key(predicate, column), column, true));
          // Neither of these asks anything about the reader, so neither needs a
          // lookup: it is the same sentence for everybody.
          case EXISTS -> direct.add(dialect.quote(BASE) + "." + dialect.quote(column) + " IS NOT NULL");
          case NOT_EXISTS -> direct.add(dialect.quote(BASE) + "." + dialect.quote(column) + " IS NULL");
          default -> {
            // An ordering or pattern comparison against the reader's own value.
            // The entitlement table holds the values a principal may see, not
            // bounds, so there is nothing here to compare against -- and
            // guessing would either leak rows or drop the filter silently.
            blocked[0] = true;
            unenforceable.add(
                new Unenforceable()
                    .withPolicyId(predicate.getSourcePolicyId())
                    .withDetail(
                        "row filter "
                            + column
                            + " "
                            + operator
                            + " cannot be expressed as an entitlement lookup, so this view returns"
                            + " no rows to anybody; enforce the asset through the proxy instead")
                    .withSuggestedMode(Unenforceable.SuggestedMode.PROXY));
          }
        }
      }
    }
  }

  private static String key(ResolvedRowPredicate predicate, String column) {
    return first(predicate.getEntitlementKey(), column);
  }

  private static void put(Map<String, RowGate> gates, RowGate gate) {
    RowGate existing = gates.get(gate.key());
    if (existing == null) {
      gates.put(gate.key(), gate);
      return;
    }
    if (existing.negated() != gate.negated()) {
      throw new IllegalArgumentException(
          "row key "
              + gate.key()
              + " is both required and forbidden by different policies; the engine composed"
              + " something this mode cannot install");
    }
  }

  private String gate(Target target, String principal, RowGate gate) {
    List<String> conditions = new ArrayList<>();
    conditions.add("e." + dialect.quote("principal") + " = " + principal);
    conditions.add("e." + dialect.quote("asset") + " = " + dialect.literal(target.assetKey()));
    conditions.add("e." + dialect.quote("entitlement_key") + " = " + dialect.literal(gate.key()));
    if (gate.column() != null && !gate.column().isBlank()) {
      // Cast to the entitlement column's own type rather than through
      // SqlDialect.toText, whose widest-text answer (nvarchar(max) on SQL
      // Server) is not comparable by index against an nvarchar(256) key. This
      // predicate runs once per row of the base table, so losing the seek on
      // row_entitlement's primary key here is the whole of NFR-2.
      conditions.add(
          "e."
              + dialect.quote("value")
              + " = CAST("
              + dialect.quote(BASE)
              + "."
              + dialect.quote(gate.column())
              + " AS "
              + dialect.aclTextType()
              + ")");
    }
    return exists(target.acl(dialect, "row_entitlement"), "e", conditions, gate.negated(), "  ");
  }

  // ---------------------------------------------------------------- render

  /**
   * An {@code EXISTS} over one entitlement table, laid out to be read.
   *
   * <p>The indentation is not decoration. Every one of these statements is put
   * in front of a person during dry-run and they are asked to agree it is safe;
   * a two-hundred-character line gets approved without being read, which is the
   * same as not showing it to them.
   */
  private static String exists(
      String table, String alias, List<String> conditions, boolean negated, String pad) {

    StringBuilder out = new StringBuilder(negated ? "NOT EXISTS (\n" : "EXISTS (\n");
    out.append(pad).append("  SELECT 1 FROM ").append(table).append(' ').append(alias).append('\n');
    for (int i = 0; i < conditions.size(); i++) {
      out.append(pad)
          .append(i == 0 ? "   WHERE " : "     AND ")
          .append(conditions.get(i))
          .append('\n');
    }
    return out.append(pad).append(')').toString();
  }

  private String body(Target target, List<String> projection, List<String> conjuncts) {
    StringBuilder out = new StringBuilder("SELECT\n");
    for (int i = 0; i < projection.size(); i++) {
      out.append("  ").append(projection.get(i)).append(i + 1 < projection.size() ? ",\n" : "\n");
    }
    out.append("FROM ")
        .append(target.qualifiedSource(dialect))
        .append(' ')
        .append(dialect.quote(BASE))
        .append('\n');
    for (int i = 0; i < conjuncts.size(); i++) {
      out.append(i == 0 ? "WHERE " : "  AND ").append(conjuncts.get(i)).append('\n');
    }
    return out.toString().stripTrailing();
  }

  // ------------------------------------------------------------------ plan

  private List<String> apply(Target target, String viewSql) {
    String text = dialect.aclTextType();
    String principal = dialect.quote("principal");
    String asset = dialect.quote("asset");
    List<String> out = new ArrayList<>();

    out.add(dialect.createSchemaIfAbsent(target.aclSchema()));
    out.add(
        dialect.createTableIfAbsent(
            target.acl(dialect, "asset_subscription"),
            "  " + principal + " " + text + " NOT NULL,\n"
                + "  " + asset + " " + text + " NOT NULL,\n"
                + "  PRIMARY KEY (" + principal + ", " + asset + ")"));
    out.add(
        dialect.createTableIfAbsent(
            target.acl(dialect, "row_entitlement"),
            "  " + principal + " " + text + " NOT NULL,\n"
                + "  " + asset + " " + text + " NOT NULL,\n"
                + "  " + dialect.quote("entitlement_key") + " " + text + " NOT NULL,\n"
                + "  " + dialect.quote("value") + " " + text + " NOT NULL,\n"
                + "  PRIMARY KEY (" + principal + ", " + asset + ", "
                + dialect.quote("entitlement_key") + ", " + dialect.quote("value") + ")"));
    out.add(
        dialect.createTableIfAbsent(
            target.acl(dialect, "column_grant"),
            "  " + principal + " " + text + " NOT NULL,\n"
                + "  " + asset + " " + text + " NOT NULL,\n"
                + "  " + dialect.quote("column_name") + " " + text + " NOT NULL,\n"
                + "  " + dialect.quote("treatment") + " " + text + " NOT NULL,\n"
                // One row per column, so that two grants cannot disagree about
                // what a reader may see.
                + "  PRIMARY KEY (" + principal + ", " + asset + ", "
                + dialect.quote("column_name") + ")"));
    out.add(dialect.createSchemaIfAbsent(target.secureSchema()));
    out.add(viewSql);
    if (target.readerRole() != null) {
      out.add(dialect.grantSelect(target.qualifiedView(dialect), target.readerRole()));
    }
    return out;
  }

  private List<String> rollback(Target target, List<String> notes) {
    List<String> out = new ArrayList<>();
    if (target.readerRole() != null) {
      out.add(dialect.revokeAllOn(target.qualifiedView(dialect), target.readerRole()));
    }
    out.add(dialect.dropViewIfExists(target.qualifiedView(dialect)));
    notes.add(
        "Rolling back drops the view and leaves "
            + dialect.quote(target.aclSchema())
            + " where it is: every other secure view on this database reads the same three tables,"
            + " and dropping them to undo one asset would turn all of them off. It also does not"
            + " put back any privilege the cutover revoked on the base table.");
    return out;
  }

  // ------------------------------------------------------------ treatments

  /**
   * The name the grant table calls one treatment of a column.
   *
   * <p>Public because the maintainer writes these rows and this compiler reads
   * them; two implementations of this function that drifted apart would hand
   * every reader the fallback mask and look, from the database, entirely
   * correct. There is one.
   *
   * <p>A digest rather than the parameters themselves: the key sits in a column
   * of a table on somebody else's database, and a masking spec can carry a
   * regular expression or a salt reference that has no business being copied
   * there.
   */
  public static String treatmentKey(MaskingSpec spec, String condition) {
    if (spec == null || spec.getFunction() == null) {
      return PLAIN;
    }
    String canonical =
        String.join(
            "\u001f",
            spec.getFunction().name(),
            text(spec.getConstant()),
            text(spec.getRegex()),
            text(spec.getReplacement()),
            text(spec.getShowFirst()),
            text(spec.getShowLast()),
            text(spec.getRoundTo()),
            text(spec.getSaltRef()),
            text(condition));
    return spec.getFunction().name() + "#" + digest(canonical);
  }

  private static String digest(String canonical) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder(8);
      for (int i = 0; i < 4; i++) {
        out.append(String.format("%02x", bytes[i]));
      }
      return out.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
    }
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String first(String preferred, String fallback) {
    if (preferred != null && !preferred.isBlank()) {
      return preferred;
    }
    return fallback == null || fallback.isBlank() ? null : fallback;
  }
}
