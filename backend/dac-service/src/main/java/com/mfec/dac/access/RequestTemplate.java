package com.mfec.dac.access;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * What the request access form asks, per table.
 *
 * <p>An administrator or a data owner writes a template for a scope, and
 * optionally for the tables under it that carry certain facets ("anything
 * tagged PII"). The form a requester sees is rendered from the template their
 * table resolves to, and {@link #check} holds the request to the same rules
 * when it is stored, so a request sent around the form is refused the same
 * way. With no template, {@link #builtIn()}: what the form asked before
 * templates existed.
 *
 * <p>A template shapes what is asked. It never decides anything: it approves
 * nothing, grants nothing and does not change who is asked to approve.
 */
public final class RequestTemplate {

  /** Longest a template may let anybody ask for; the same bound as a request. */
  public static final int MAX_DAYS = AccessRequestStore.MAX_DAYS;

  static final int MAX_PURPOSES = 30;
  static final int MAX_DURATIONS = 8;
  static final int MAX_FACETS = 30;
  static final int MAX_GUIDANCE = 2_000;
  static final int MAX_REASON_MINIMUM = 500;
  public static final int MAX_REFERENCE = 200;

  private RequestTemplate() {}

  /**
   * The form.
   *
   * @param purposes the purposes a requester picks from; empty lets them type one
   * @param purposeRequired whether a purpose must be given
   * @param durations the day counts offered as one-click choices
   * @param defaultDays chosen when the form opens; null = until revoked
   * @param maxDays the longest they may ask for; null = the platform's limit
   * @param allowUntilRevoked whether asking with no end date is allowed
   * @param referenceLabel what the reference is called ("Change ticket", "DPIA no.");
   *     null = no reference is asked
   * @param referenceRequired whether the reference must be given
   * @param minReasonLength the fewest characters the reason may have
   * @param guidance plain text shown above the form; never rendered as HTML
   */
  public record Form(
      List<String> purposes,
      boolean purposeRequired,
      List<Integer> durations,
      Integer defaultDays,
      Integer maxDays,
      boolean allowUntilRevoked,
      String referenceLabel,
      boolean referenceRequired,
      int minReasonLength,
      String guidance) {

    /** Whether the form asks for a reference at all. */
    public boolean asksReference() {
      return referenceLabel != null;
    }
  }

  /**
   * A template as stored.
   *
   * @param id null for the built-in one
   * @param scopeFqn null for the whole organisation
   * @param matchFacets empty for any table under the scope
   */
  public record Template(
      UUID id,
      String name,
      String description,
      String scopeFqn,
      List<String> matchFacets,
      boolean enabled,
      Form form) {

    public boolean builtIn() {
      return id == null;
    }
  }

  /** What an administrator sends to create or replace a template. */
  public record Draft(
      String name,
      String description,
      String scopeFqn,
      List<String> matchFacets,
      Boolean enabled,
      Form form) {}

  /** The form as it was before templates: nothing asked beyond a reason. */
  public static Template builtIn() {
    return new Template(
        null,
        "Built-in",
        "A reason, and how long. Used on every table no template covers.",
        null,
        List.of(),
        true,
        new Form(List.of(), false, List.of(7, 30, 90), 30, null, true, null, false, 1, null));
  }

  /** Checks and tidies a draft. Throws {@link IllegalArgumentException} with a message for a person. */
  public static Draft validate(Draft draft) {
    if (draft == null) {
      throw new IllegalArgumentException("Send a template");
    }
    String name = trim(draft.name());
    if (name == null) {
      throw new IllegalArgumentException("Name the template");
    }
    if (name.length() > 200) {
      throw new IllegalArgumentException("Keep the name under 200 characters");
    }
    String description = trim(draft.description());
    if (description != null && description.length() > 1_000) {
      throw new IllegalArgumentException("Keep the description under 1,000 characters");
    }

    List<String> facets = new ArrayList<>();
    Set<String> seenFacets = new HashSet<>();
    for (String facet : draft.matchFacets() == null ? List.<String>of() : draft.matchFacets()) {
      String clean = trim(facet);
      if (clean == null) {
        continue;
      }
      if (clean.length() > 500) {
        throw new IllegalArgumentException("A tag or term name is too long: " + clean.substring(0, 40) + "...");
      }
      if (seenFacets.add(clean.toLowerCase(Locale.ROOT))) {
        facets.add(clean);
      }
    }
    if (facets.size() > MAX_FACETS) {
      throw new IllegalArgumentException("Match at most " + MAX_FACETS + " tags or terms");
    }

    return new Draft(
        name,
        description,
        trim(draft.scopeFqn()),
        List.copyOf(facets),
        draft.enabled() == null ? Boolean.TRUE : draft.enabled(),
        form(draft.form()));
  }

  private static Form form(Form form) {
    if (form == null) {
      throw new IllegalArgumentException("Say what the form asks");
    }
    List<String> purposes = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String purpose : form.purposes() == null ? List.<String>of() : form.purposes()) {
      String clean = trim(purpose);
      if (clean == null) {
        continue;
      }
      if (clean.length() > 100) {
        throw new IllegalArgumentException("Keep each purpose under 100 characters");
      }
      if (seen.add(clean.toLowerCase(Locale.ROOT))) {
        purposes.add(clean);
      }
    }
    if (purposes.size() > MAX_PURPOSES) {
      throw new IllegalArgumentException("Offer at most " + MAX_PURPOSES + " purposes");
    }

    Integer maxDays = form.maxDays();
    if (maxDays != null && (maxDays < 1 || maxDays > MAX_DAYS)) {
      throw new IllegalArgumentException("The longest a request may ask for is between 1 and " + MAX_DAYS + " days");
    }
    int ceiling = maxDays == null ? MAX_DAYS : maxDays;

    TreeSet<Integer> durations = new TreeSet<>();
    for (Integer days : form.durations() == null ? List.<Integer>of() : form.durations()) {
      if (days == null) {
        continue;
      }
      if (days < 1 || days > ceiling) {
        throw new IllegalArgumentException(
            "Every suggested duration must be between 1 and " + ceiling + " days; " + days + " is not");
      }
      durations.add(days);
    }
    if (durations.size() > MAX_DURATIONS) {
      throw new IllegalArgumentException("Suggest at most " + MAX_DURATIONS + " durations");
    }

    boolean untilRevoked = form.allowUntilRevoked();
    if (maxDays != null && untilRevoked) {
      // An open-ended request outlasts any limit; a limit with it is no limit.
      throw new IllegalArgumentException(
          "A request cannot both be limited to " + maxDays + " days and last until revoked; choose one");
    }
    Integer defaultDays = form.defaultDays();
    if (defaultDays != null && (defaultDays < 1 || defaultDays > ceiling)) {
      throw new IllegalArgumentException("The duration the form starts on must be between 1 and " + ceiling + " days");
    }
    if (defaultDays == null && !untilRevoked) {
      throw new IllegalArgumentException(
          "Choose the duration the form starts on; it cannot start on \"until revoked\" when that is not allowed");
    }

    String referenceLabel = trim(form.referenceLabel());
    if (referenceLabel != null && referenceLabel.length() > 60) {
      throw new IllegalArgumentException("Keep the reference's label under 60 characters");
    }
    if (referenceLabel == null && form.referenceRequired()) {
      throw new IllegalArgumentException("Name the reference before making it required");
    }

    if (form.minReasonLength() < 1 || form.minReasonLength() > MAX_REASON_MINIMUM) {
      throw new IllegalArgumentException(
          "The shortest reason must be between 1 and " + MAX_REASON_MINIMUM + " characters");
    }

    String guidance = trim(form.guidance());
    if (guidance != null && guidance.length() > MAX_GUIDANCE) {
      throw new IllegalArgumentException("Keep the guidance under " + String.format("%,d", MAX_GUIDANCE) + " characters");
    }

    return new Form(
        List.copyOf(purposes),
        form.purposeRequired(),
        List.copyOf(durations),
        defaultDays,
        maxDays,
        untilRevoked,
        referenceLabel,
        referenceLabel != null && form.referenceRequired(),
        form.minReasonLength(),
        guidance);
  }

  /**
   * What is wrong with a request under this form, or null.
   *
   * <p>Asked of every request before it is stored: the form in the browser is
   * only the friendly half of this.
   */
  public static String check(Form form, String reason, String purpose, Integer days, String reference) {
    String why = trim(reason);
    if (why == null || why.length() < form.minReasonLength()) {
      return form.minReasonLength() <= 1
          ? "Say why you need it; the approvers decide on what you write here"
          : "Say why you need it in at least " + form.minReasonLength() + " characters";
    }
    String asked = trim(purpose);
    if (asked == null && form.purposeRequired()) {
      return "Choose a purpose";
    }
    if (asked != null
        && !form.purposes().isEmpty()
        && form.purposes().stream().noneMatch(p -> p.equalsIgnoreCase(asked))) {
      return "Choose one of the purposes offered: " + String.join(", ", form.purposes());
    }
    if (days == null && !form.allowUntilRevoked()) {
      return "Say how many days; access until revoked is not offered for this table";
    }
    if (days != null && form.maxDays() != null && days > form.maxDays()) {
      return "Ask for at most " + form.maxDays() + " days on this table";
    }
    String ref = trim(reference);
    if (ref != null && !form.asksReference()) {
      return "This table's form does not ask for a reference";
    }
    if (ref == null && form.referenceRequired()) {
      return "Give the " + form.referenceLabel();
    }
    if (ref != null && ref.length() > MAX_REFERENCE) {
      return "Keep the " + form.referenceLabel() + " under " + MAX_REFERENCE + " characters";
    }
    return null;
  }

  /** The purpose as the template spells it, so "FRAUD" and "Fraud" are one purpose. */
  static String canonicalPurpose(Form form, String purpose) {
    String asked = trim(purpose);
    if (asked == null) {
      return null;
    }
    return form.purposes().stream().filter(p -> p.equalsIgnoreCase(asked)).findFirst().orElse(asked);
  }

  static String trim(String value) {
    if (value == null) {
      return null;
    }
    String out = value.strip();
    return out.isEmpty() ? null : out;
  }

  /** When a stored template was written and by whom. */
  public record Stored(
      Template template, String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {}
}
