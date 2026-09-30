package com.mfec.dac.compiler.sql;

import com.mfec.dac.engine.MaskStrength;
import com.mfec.dac.schema.api.PolicyDecision;
import com.mfec.dac.schema.api.ResolvedColumnMask;
import com.mfec.dac.schema.api.ResolvedRowPredicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The other half of enforcement mode 5.1.2: who gets what (FR-6.1).
 *
 * <p>{@link ViewCompiler} compiles the shape of a policy into a view and stops
 * there, because a view is one object shared by every reader and cannot hold
 * one reader's branch codes. This class produces the rows that finish the
 * sentence — the contents of {@code asset_subscription}, {@code row_entitlement}
 * and {@code column_grant} — from the very {@link PolicyDecision}s the view was
 * compiled from.
 *
 * <p>The pair has to be read together or not at all. The view asks three
 * questions of those tables and this class answers them; an answer that does
 * not fit the question the view asks is not a smaller mistake than a wrong
 * view, it is a larger one, because the view still looks correct in a dry-run
 * and the wrong rows are invisible until somebody reads data they should not
 * have.
 *
 * <h2>Two ways this could leak, and what stops them</h2>
 *
 * <p><b>A treatment the view has never heard of.</b> The view is a {@code CASE}
 * over the treatments the policies asked for, and its {@code ELSE} is the
 * strictest of them. A grant row naming any other treatment matches no branch,
 * so the reader silently falls through to the {@code ELSE} — which is safe
 * here, but would not be if the fallback were ever the weaker one, and either
 * way the platform would be recording a grant that does nothing. This class
 * refuses to emit such a row rather than writing it and hoping. The same
 * refusal covers the genuinely dangerous direction: rows computed from a newer
 * decision than the view was compiled from, where the new treatment is
 * <em>stricter</em> than the installed fallback and falling through means
 * reading more than the policy allows.
 *
 * <p><b>A row filter with no gate in the view.</b> If a decision gates on a key
 * the view does not join on, writing entitlements for it enforces nothing: the
 * rows are simply returned. That is refused too. The opposite case — a gate in
 * the view that this principal has no values for — is allowed and noted,
 * because it closes rather than opens.
 *
 * <h2>Absence means no</h2>
 *
 * <p>Every table here is read by the view as {@code EXISTS}, so a row that
 * should have been deleted and was not is the failure that matters, not a row
 * that should have been written and was not. That is why this class computes a
 * complete desired state per asset and diffs it against what is installed,
 * rather than emitting the inserts it happens to know about: a principal who
 * lost a policy shows up as a deletion, and a run that produces no inserts at
 * all is still a run that has work to do.
 */
public final class RowEntitlementMaintainer {

  /** One principal's permission to read one asset through its view. */
  public record Subscription(String principal, String asset) {}

  /** One value of one gating key that one principal may see. */
  public record Entitlement(String principal, String asset, String entitlementKey, String value) {}

  /** The treatment of one column one principal gets, when it is not the fallback. */
  public record ColumnGrant(String principal, String asset, String column, String treatment) {}

  /**
   * The contents of the three tables for one asset.
   *
   * <p>Sets rather than lists: the tables are keyed, the order of an
   * {@code INSERT} batch means nothing, and a duplicate here would become a
   * primary-key violation halfway through a maintenance run.
   */
  public record Rows(
      Set<Subscription> subscriptions, Set<Entitlement> entitlements, Set<ColumnGrant> grants) {

    public static final Rows NONE = new Rows(Set.of(), Set.of(), Set.of());

    public Rows {
      subscriptions = Set.copyOf(subscriptions == null ? Set.of() : subscriptions);
      entitlements = Set.copyOf(entitlements == null ? Set.of() : entitlements);
      grants = Set.copyOf(grants == null ? Set.of() : grants);
    }

    public boolean isEmpty() {
      return subscriptions.isEmpty() && entitlements.isEmpty() && grants.isEmpty();
    }

    public int size() {
      return subscriptions.size() + entitlements.size() + grants.size();
    }
  }

  /**
   * What a maintenance run would do, laid out for somebody to approve.
   *
   * @param desired the whole state, which is what makes a full reconcile
   *     possible without asking the engine twice
   * @param insert rows to add
   * @param delete rows to remove — the half that revokes, and so the half that
   *     must run even when {@code insert} is empty
   * @param notes what a person has to read before agreeing, in the same spirit
   *     as {@link ViewCompiler.Plan#notes()}
   */
  public record Maintenance(Rows desired, Rows insert, Rows delete, List<String> notes) {

    public Maintenance {
      notes = List.copyOf(notes == null ? List.of() : notes);
    }

    /** True when what is installed already matches the policy. */
    public boolean isSatisfied() {
      return insert.isEmpty() && delete.isEmpty();
    }
  }

  /**
   * Where the values behind an {@code ENTITLEMENT_JOIN} filter come from.
   *
   * <p>Those filters are the case where the engine deliberately does not carry
   * the values in the decision — the mapping is a table because it is too large
   * or too volatile to inline — so the maintainer has to go and read it. An
   * implementation that returns nothing hides every row of that key from that
   * principal, which is the right way for this to fail.
   */
  @FunctionalInterface
  public interface EntitlementSource {

    /** Every value of {@code entitlementKey} this principal may see on this asset. */
    List<String> valuesFor(String principal, String assetKey, String entitlementKey);

    /** A source that knows nothing, for assets whose filters carry their own values. */
    EntitlementSource NONE = (principal, asset, key) -> List.of();
  }

  /**
   * Refused because installing the rows would enforce something other than the
   * policy they came from.
   */
  public static final class MismatchedPlanException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public MismatchedPlanException(String message) {
      super(message);
    }
  }

  private RowEntitlementMaintainer() {}

  /**
   * What to install for one asset, and what to take away.
   *
   * @param shape the plan the view was compiled from. Its
   *     {@link ViewCompiler.Plan#treatments()} and
   *     {@link ViewCompiler.Plan#entitlementKeys()} are the vocabulary the view
   *     understands; anything outside it is refused rather than written.
   * @param assetKey the asset as the view names it —
   *     {@link ViewCompiler.Target#assetKey()}, not the source table name
   * @param decisions one decision per principal who was evaluated. A principal
   *     absent from this list keeps nothing: they are not in the desired state,
   *     so whatever they have is deleted.
   * @param entitlements where {@code ENTITLEMENT_JOIN} values are read from
   * @param installed what the three tables hold now, for the diff
   */
  public static Maintenance maintain(
      ViewCompiler.Plan shape,
      String assetKey,
      List<PolicyDecision> decisions,
      EntitlementSource entitlements,
      Rows installed) {

    Objects.requireNonNull(shape, "shape");
    if (assetKey == null || assetKey.isBlank()) {
      throw new IllegalArgumentException("no asset key; the view keys all three tables on it");
    }
    if (decisions == null) {
      throw new IllegalArgumentException(
          "no decisions; a maintenance run over nothing would revoke everybody, which is a"
              + " different operation and should be asked for by name");
    }

    EntitlementSource source = entitlements == null ? EntitlementSource.NONE : entitlements;
    Rows current = installed == null ? Rows.NONE : installed;

    Map<String, Column> columns = columns(shape);
    Set<String> gates = new LinkedHashSet<>(list(shape.entitlementKeys()));

    Set<Subscription> subscriptions = new LinkedHashSet<>();
    Set<Entitlement> rows = new LinkedHashSet<>();
    Set<ColumnGrant> grants = new LinkedHashSet<>();
    Set<String> notes = new LinkedHashSet<>();

    for (PolicyDecision decision : decisions) {
      if (decision == null) {
        continue;
      }
      String principal = decision.getPrincipal();
      if (principal == null || principal.isBlank()) {
        throw new IllegalArgumentException(
            "a decision carries no principal, so there is no row to key on; the engine was asked"
                + " something this mode cannot store");
      }
      if (!Boolean.TRUE.equals(decision.getAllowed())) {
        // Not a subscription, and not an entitlement or a grant either. Leaving
        // those behind would mean a principal who lost the asset still has rows
        // waiting for the day somebody re-subscribes them by hand.
        notes.add(
            principal
                + " is denied this asset and gets no row in any of the three tables. Through a"
                + " view that reads as the full column list and no rows at all.");
        continue;
      }

      List<Entitlement> mine = new ArrayList<>();
      boolean closed = false;

      for (ResolvedRowPredicate predicate : list(decision.getRowPredicates())) {
        if (predicate == null) {
          continue;
        }
        ResolvedRowPredicate.Kind kind =
            predicate.getKind() == null
                ? ResolvedRowPredicate.Kind.ALWAYS_FALSE
                : predicate.getKind();
        switch (kind) {
          case ALWAYS_FALSE -> {
            closed = true;
            notes.add(
                "A policy hides every row of this asset from "
                    + principal
                    + ", so they get no subscription row at all. The view cannot say it about one"
                    + " reader, and withholding the subscription says exactly the same thing.");
          }
          // Both of these are the same sentence for every reader and were
          // written into the view itself. There is nothing per-principal to
          // store, and storing something would be a second copy to keep true.
          case RAW_PREDICATE -> {}
          // The compiler refused to build a view over a lookup, so the view
          // returns no rows to anybody and there is nothing here to grant.
          case LOOKUP -> {}
          case ENTITLEMENT_JOIN -> {
            String key = gate(shape, predicate, principal);
            for (String value : list(source.valuesFor(principal, assetKey, key))) {
              if (value != null) {
                mine.add(new Entitlement(principal, assetKey, key, value));
              }
            }
          }
          case IN_LIST, ATTRIBUTE_COMPARE -> {
            ResolvedRowPredicate.FacetOperator operator =
                predicate.getOperator() == null
                    ? ResolvedRowPredicate.FacetOperator.IN
                    : predicate.getOperator();
            switch (operator) {
              case IN, EQ, NOT_IN, NE -> {
                String key = gate(shape, predicate, principal);
                for (Object value : list(predicate.getValues())) {
                  if (value != null) {
                    mine.add(new Entitlement(principal, assetKey, key, String.valueOf(value)));
                  }
                }
              }
              // EXISTS and NOT_EXISTS ask nothing about the reader; anything
              // else the compiler already refused to install, and the view it
              // produced returns no rows to anybody.
              default -> {}
            }
          }
        }
      }

      // Said once per gate the principal ends up with no value of, whether
      // because no policy mentioned it or because the entitlement source had
      // nothing. Both read the same way through the view -- no rows -- and an
      // administrator looking at an empty result wants to know which gate.
      Set<String> covered = new LinkedHashSet<>();
      for (Entitlement row : mine) {
        covered.add(row.entitlementKey());
      }
      for (String gate : gates) {
        if (!covered.contains(gate)) {
          notes.add(
              "Nothing gives "
                  + principal
                  + " any value of "
                  + gate
                  + ", and the view gates every reader on it. They will read no rows of this asset"
                  + " until something does.");
        }
      }

      if (closed) {
        continue;
      }
      subscriptions.add(new Subscription(principal, assetKey));
      rows.addAll(mine);
      grants.addAll(grants(columns, assetKey, principal, decision, notes));
    }

    if (!list(shape.unenforceable()).isEmpty()) {
      notes.add(
          "The view for this asset could not express part of its policy and returns no rows to"
              + " anybody. These rows are still correct, and will start mattering the moment the"
              + " asset moves to a mode that can enforce it.");
    }

    Rows desired = new Rows(subscriptions, rows, grants);
    return new Maintenance(
        desired, difference(desired, current), difference(current, desired), List.copyOf(notes));
  }

  // ------------------------------------------------------------------ parts

  /** One column of the view, and every treatment it knows how to apply. */
  private record Column(String spelling, String fallback, Set<String> known) {}

  private static Map<String, Column> columns(ViewCompiler.Plan shape) {
    Map<String, String> spelling = new LinkedHashMap<>();
    Map<String, String> fallback = new LinkedHashMap<>();
    Map<String, Set<String>> known = new LinkedHashMap<>();
    for (ViewCompiler.Treatment treatment : list(shape.treatments())) {
      if (treatment == null || treatment.column() == null) {
        continue;
      }
      String key = treatment.column().toLowerCase(Locale.ROOT);
      spelling.putIfAbsent(key, treatment.column());
      known.computeIfAbsent(key, c -> new LinkedHashSet<>()).add(treatment.key());
      if (treatment.fallback()) {
        fallback.put(key, treatment.key());
      }
    }
    Map<String, Column> out = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> entry : known.entrySet()) {
      String key = entry.getKey();
      String plain = fallback.get(key);
      if (plain == null) {
        throw new MismatchedPlanException(
            "the plan lists treatments for column "
                + spelling.get(key)
                + " but none of them is the fallback, so there is no way to tell which readers"
                + " need a grant row and which already have the treatment the view defaults to");
      }
      out.put(key, new Column(spelling.get(key), plain, Set.copyOf(entry.getValue())));
    }
    return out;
  }

  private static List<ColumnGrant> grants(
      Map<String, Column> columns,
      String assetKey,
      String principal,
      PolicyDecision decision,
      Set<String> notes) {

    Map<String, ResolvedColumnMask> strictest = new LinkedHashMap<>();
    for (ResolvedColumnMask mask : list(decision.getColumnMasks())) {
      if (mask == null || mask.getColumn() == null) {
        continue;
      }
      String key = mask.getColumn().toLowerCase(Locale.ROOT);
      ResolvedColumnMask incumbent = strictest.get(key);
      // Strictest wins, and a tie keeps the incumbent -- the same rule the
      // engine composed the decision with (MaskStrength). A decision should
      // already carry one mask per column; this is here so that a decision
      // carrying two never turns into a grant row that depends on which the
      // engine listed first.
      if (incumbent == null
          || MaskStrength.rank(mask.getMasking()) > MaskStrength.rank(incumbent.getMasking())) {
        strictest.put(key, mask);
      }
    }

    Set<String> hidden = new LinkedHashSet<>();
    for (String column : list(decision.getHiddenColumns())) {
      if (column != null) {
        hidden.add(column.toLowerCase(Locale.ROOT));
      }
    }

    // A mask on a column the view selects as it is. No row can make a view do
    // something it was not compiled to do, so there is nothing to write and
    // nothing that would make this right later.
    for (Map.Entry<String, ResolvedColumnMask> entry : strictest.entrySet()) {
      if (columns.containsKey(entry.getKey()) || hidden.contains(entry.getKey())) {
        continue;
      }
      ResolvedColumnMask mask = entry.getValue();
      if (ViewCompiler.PLAIN.equals(
          ViewCompiler.treatmentKey(mask.getMasking(), mask.getCondition()))) {
        continue;
      }
      throw new MismatchedPlanException(
          "the policy masks column "
              + mask.getColumn()
              + " for "
              + principal
              + ", and the installed view selects that column as it stands: they would read it in"
              + " the clear. Recompile the view from these decisions before maintaining its"
              + " rows.");
    }

    List<ColumnGrant> out = new ArrayList<>();
    for (Map.Entry<String, Column> entry : columns.entrySet()) {
      Column column = entry.getValue();
      if (hidden.contains(entry.getKey())) {
        // Hide beats mask (FR-4.5), but a view is one object and cannot drop a
        // column for one reader. No row leaves them on the fallback, which is
        // the strictest thing this view can do to that column.
        notes.add(
            "Column "
                + column.spelling()
                + " is hidden from "
                + principal
                + " by policy, and a view cannot drop a column for one reader. They are left on "
                + column.fallback()
                + ", the strictest treatment this view applies to it (FR-4.5).");
        continue;
      }
      ResolvedColumnMask mask = strictest.get(entry.getKey());
      String treatment =
          mask == null
              ? ViewCompiler.PLAIN
              : ViewCompiler.treatmentKey(mask.getMasking(), mask.getCondition());
      if (treatment.equals(column.fallback())) {
        // The view already does this to everybody who has no row. Writing it
        // down would double the table to say nothing.
        continue;
      }
      if (!column.known().contains(treatment)) {
        throw new MismatchedPlanException(
            "the policy gives "
                + principal
                + " treatment "
                + treatment
                + " of column "
                + column.spelling()
                + ", which the installed view has no branch for: they would read it as "
                + column.fallback()
                + " instead. Recompile the view from these decisions before maintaining its rows.");
      }
      out.add(new ColumnGrant(principal, assetKey, column.spelling(), treatment));
    }
    return out;
  }

  private static String gate(
      ViewCompiler.Plan shape, ResolvedRowPredicate predicate, String principal) {

    String key =
        predicate.getEntitlementKey() == null || predicate.getEntitlementKey().isBlank()
            ? predicate.getColumn()
            : predicate.getEntitlementKey();
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException(
          "a row filter for " + principal + " carries neither an entitlement key nor a column");
    }
    if (!list(shape.entitlementKeys()).contains(key)) {
      throw new MismatchedPlanException(
          "the policy filters "
              + principal
              + " on "
              + key
              + ", which the installed view does not join on: the rows would be written and then"
              + " returned to them anyway. Recompile the view from these decisions before"
              + " maintaining its rows.");
    }
    return key;
  }

  /** Everything in {@code left} that {@code right} does not have. */
  private static Rows difference(Rows left, Rows right) {
    return new Rows(
        without(left.subscriptions(), right.subscriptions()),
        without(left.entitlements(), right.entitlements()),
        without(left.grants(), right.grants()));
  }

  private static <T> Set<T> without(Set<T> from, Set<T> minus) {
    Set<T> out = new LinkedHashSet<>(from);
    out.removeAll(minus);
    return out;
  }

  private static <T> List<T> list(List<T> maybe) {
    return maybe == null ? List.of() : maybe;
  }
}
