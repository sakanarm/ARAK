package com.mfec.dac.purpose;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * The register of purposes data may be used for (FR-21, M31).
 *
 * <p>A purpose has a key everything else stores -- a policy's {@code
 * subject.context.purpose}, a request template's list, a request, a query --
 * and a name people read. What the PDPA asks of it is recorded beside it: the
 * legal basis, whether special categories may be used for it, who answers for
 * it and how long access for it may last.
 *
 * <p>Nothing is deleted. A purpose nobody should use any more is retired: it is
 * no longer offered and can no longer be declared, and what already names it
 * keeps the name. Every change is written to {@code audit_purpose} with what
 * the purpose said before and after.
 */
public class PurposeStore {

  /** Section 24 of the PDPA, and consent under section 19. */
  public enum LegalBasis {
    CONSENT,
    CONTRACT,
    LEGAL_OBLIGATION,
    VITAL_INTEREST,
    PUBLIC_TASK,
    LEGITIMATE_INTEREST,
    RESEARCH_OR_STATISTICS
  }

  public enum Status {
    ACTIVE,
    RETIRED
  }

  /** One purpose as it stands. */
  public record Purpose(
      String key,
      String name,
      String description,
      LegalBasis legalBasis,
      boolean sensitiveAllowed,
      String owner,
      Integer maxDays,
      Status status,
      String createdBy,
      Instant createdAt,
      String updatedBy,
      Instant updatedAt) {

    public boolean active() {
      return status == Status.ACTIVE;
    }
  }

  /** What a person may set on a purpose; everything but the key and the status. */
  public record Details(
      String name,
      String description,
      LegalBasis legalBasis,
      boolean sensitiveAllowed,
      String owner,
      Integer maxDays) {}

  /** One change to one purpose, oldest last. */
  public record Change(
      long id,
      Instant occurredAt,
      String actor,
      String action,
      String reason,
      JsonNode before,
      JsonNode after) {}

  /**
   * Where a purpose value is named: policies that are not archived, request
   * templates, requests still open, and decisions asked in the last 90 days.
   */
  public record Usage(String value, int policies, int templates, int openRequests, int recentQueries) {

    public boolean any() {
      return policies + templates + openRequests + recentQueries > 0;
    }
  }

  /** Why a change or a declaration was refused; the resource turns each kind into a status. */
  public static final class Refused extends RuntimeException {
    public enum Reason {
      NOT_FOUND,
      INVALID,
      CONFLICT
    }

    private final Reason reason;

    public Refused(Reason reason, String message) {
      super(message);
      this.reason = reason;
    }

    public Reason reason() {
      return reason;
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Jdbi jdbi;

  public PurposeStore(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /** Every purpose, active ones first, each group by name. */
  public List<Purpose> list() {
    return jdbi.withHandle(
        h ->
            h.createQuery(SELECT + " ORDER BY status = 'RETIRED', lower(name)")
                .map((rs, ctx) -> purpose(rs))
                .list());
  }

  /** The purpose with this key, whatever its case. */
  public Optional<Purpose> find(String key) {
    String clean = trim(key);
    if (clean == null) {
      return Optional.empty();
    }
    return jdbi.withHandle(h -> find(h, clean));
  }

  public Purpose create(String key, Details details, String actor) {
    return jdbi.inTransaction(
        h -> {
          Optional<Purpose> taken = find(h, key);
          if (taken.isPresent()) {
            throw new Refused(
                Refused.Reason.CONFLICT,
                "The key " + taken.get().key() + " is already " + taken.get().name()
                    + (taken.get().active() ? "" : ", which was retired; reinstate it instead"));
          }
          refuseNameTaken(h, details.name(), null);
          h.createUpdate(
                  """
                  INSERT INTO purpose (key, name, description, legal_basis, sensitive_allowed,
                                       owner, max_days, created_by, updated_by)
                  VALUES (:key, :name, :description, :legalBasis, :sensitive, :owner,
                          :maxDays, :actor, :actor)
                  """)
              .bind("key", key)
              .bind("name", details.name())
              .bind("description", details.description())
              .bind("legalBasis", details.legalBasis() == null ? null : details.legalBasis().name())
              .bind("sensitive", details.sensitiveAllowed())
              .bind("owner", details.owner())
              .bind("maxDays", details.maxDays())
              .bind("actor", actor)
              .execute();
          Purpose made = find(h, key).orElseThrow();
          audit(h, actor, "CREATE", made.key(), null, null, made);
          return made;
        });
  }

  /**
   * Replaces what a person may set. The key never changes, because stored
   * policies name it. Nothing is written, and nothing audited, when the
   * purpose already says what was asked for.
   */
  public Purpose update(String key, Details details, String actor) {
    return jdbi.inTransaction(
        h -> {
          Purpose before = require(h, key);
          if (same(before, details)) {
            return before;
          }
          refuseNameTaken(h, details.name(), before.key());
          h.createUpdate(
                  """
                  UPDATE purpose SET name = :name, description = :description,
                                     legal_basis = :legalBasis, sensitive_allowed = :sensitive,
                                     owner = :owner, max_days = :maxDays,
                                     updated_by = :actor, updated_at = now()
                   WHERE key = :key
                  """)
              .bind("key", before.key())
              .bind("name", details.name())
              .bind("description", details.description())
              .bind("legalBasis", details.legalBasis() == null ? null : details.legalBasis().name())
              .bind("sensitive", details.sensitiveAllowed())
              .bind("owner", details.owner())
              .bind("maxDays", details.maxDays())
              .bind("actor", actor)
              .execute();
          Purpose after = find(h, before.key()).orElseThrow();
          audit(h, actor, "UPDATE", after.key(), null, before, after);
          return after;
        });
  }

  /** Stops a purpose being offered or declared. What names it keeps the name. */
  public Purpose retire(String key, String reason, String actor) {
    return setStatus(key, Status.RETIRED, reason, actor);
  }

  /** Offers a retired purpose again. */
  public Purpose reinstate(String key, String reason, String actor) {
    return setStatus(key, Status.ACTIVE, reason, actor);
  }

  public List<Change> history(String key) {
    return jdbi.withHandle(
        h ->
            h.createQuery(
                    """
                    SELECT id, occurred_at, actor, action, reason, before, after
                      FROM audit_purpose WHERE lower(purpose_key) = lower(:key)
                     ORDER BY occurred_at DESC, id DESC
                    """)
                .bind("key", key)
                .map(
                    (rs, ctx) ->
                        new Change(
                            rs.getLong("id"),
                            rs.getTimestamp("occurred_at").toInstant(),
                            rs.getString("actor"),
                            rs.getString("action"),
                            rs.getString("reason"),
                            node(rs.getString("before")),
                            node(rs.getString("after"))))
                .list());
  }

  /**
   * Every purpose value named anywhere, keyed by its lower-case spelling, with
   * how often. Policies and templates are what will be enforced or offered
   * next; open requests and recent queries are what people are asking with
   * now. Closed requests and older decisions are history and are left out:
   * nothing anybody does to the register changes them.
   */
  public Map<String, Usage> usage() {
    return jdbi.withHandle(
        h -> {
          Map<String, int[]> counts = new TreeMap<>();
          Map<String, String> spelled = new LinkedHashMap<>();
          count(
              h,
              counts,
              spelled,
              0,
              """
              SELECT btrim(v) AS value, count(DISTINCT p.id) AS n
                FROM policy p,
                     jsonb_array_elements_text(
                       CASE WHEN jsonb_typeof(p.document #> '{subject,context,purpose}') = 'array'
                            THEN p.document #> '{subject,context,purpose}'
                            ELSE '[]'::jsonb END) AS v
               WHERE p.lifecycle_state <> 'ARCHIVED'
               GROUP BY btrim(v)
              """);
          count(
              h,
              counts,
              spelled,
              1,
              """
              SELECT btrim(v) AS value, count(DISTINCT t.id) AS n
                FROM access_request_template t,
                     jsonb_array_elements_text(
                       CASE WHEN jsonb_typeof(t.form -> 'purposes') = 'array'
                            THEN t.form -> 'purposes' ELSE '[]'::jsonb END) AS v
               GROUP BY btrim(v)
              """);
          count(
              h,
              counts,
              spelled,
              2,
              """
              SELECT btrim(purpose) AS value, count(*) AS n
                FROM access_request
               WHERE status IN ('PENDING', 'APPROVED', 'IN_PROGRESS') AND purpose IS NOT NULL
               GROUP BY btrim(purpose)
              """);
          count(
              h,
              counts,
              spelled,
              3,
              """
              SELECT btrim(purpose) AS value, count(*) AS n
                FROM audit_decision
               WHERE purpose IS NOT NULL AND occurred_at > now() - interval '90 days'
               GROUP BY btrim(purpose)
              """);
          Map<String, Usage> out = new LinkedHashMap<>();
          counts.forEach(
              (lower, n) -> out.put(lower, new Usage(spelled.get(lower), n[0], n[1], n[2], n[3])));
          return out;
        });
  }

  // ------------------------------------------------------------ declaring

  /**
   * The purpose somebody declared, as the register spells its key; null when
   * none was. Refused when the register does not list it or it was retired --
   * a purpose declared at query time is what a policy is matched against, and
   * one nobody listed says nothing anybody agreed to.
   */
  public String declared(String raw) {
    String asked = trim(raw);
    if (asked == null) {
      return null;
    }
    return active(asked, "").key();
  }

  /**
   * Every purpose in {@code values} must be listed and active; {@code
   * grandfathered} ones are let through whatever the register says, because
   * they were already stored before and refusing them would make a policy or
   * a template impossible to edit for a reason its editor did not cause.
   *
   * @param where how the refusal names the thing being saved ("the policy")
   */
  public void requireListed(
      Collection<String> values, Collection<String> grandfathered, String where) {
    if (values == null) {
      return;
    }
    List<String> old = new ArrayList<>();
    if (grandfathered != null) {
      grandfathered.stream().map(PurposeStore::trim).filter(Objects::nonNull)
          .forEach(v -> old.add(v.toLowerCase(Locale.ROOT)));
    }
    for (String value : values) {
      String clean = trim(value);
      if (clean == null || old.contains(clean.toLowerCase(Locale.ROOT))) {
        continue;
      }
      active(clean, " on " + where);
    }
  }

  private Purpose active(String asked, String where) {
    Purpose purpose =
        find(asked)
            .orElseThrow(
                () ->
                    new Refused(
                        Refused.Reason.INVALID,
                        "\"" + asked + "\" is not in the register of purposes. Choose one that is,"
                            + " or ask a policy author to list it under Settings, Purposes"));
    if (!purpose.active()) {
      throw new Refused(
          Refused.Reason.INVALID,
          "The purpose " + purpose.name() + " (" + purpose.key() + ") was retired, so it cannot be used"
              + where + " any more; choose another");
    }
    return purpose;
  }

  // --------------------------------------------------------------- plumbing

  private static final String SELECT =
      """
      SELECT key, name, description, legal_basis, sensitive_allowed, owner, max_days, status,
             created_by, created_at, updated_by, updated_at
        FROM purpose
      """;

  /** The purpose with this key, whatever its case, read inside a caller's transaction. */
  public static Optional<Purpose> find(Handle h, String key) {
    return h.createQuery(SELECT + " WHERE lower(key) = lower(:key)")
        .bind("key", key)
        .map((rs, ctx) -> purpose(rs))
        .findOne();
  }

  private static Purpose require(Handle h, String key) {
    return find(h, key)
        .orElseThrow(() -> new Refused(Refused.Reason.NOT_FOUND, "No purpose " + key));
  }

  private Purpose setStatus(String key, Status status, String reason, String actor) {
    return jdbi.inTransaction(
        h -> {
          Purpose before = require(h, key);
          if (before.status() == status) {
            throw new Refused(
                Refused.Reason.CONFLICT,
                before.name() + (status == Status.RETIRED ? " is already retired" : " is not retired"));
          }
          h.createUpdate(
                  "UPDATE purpose SET status = :status, updated_by = :actor, updated_at = now()"
                      + " WHERE key = :key")
              .bind("status", status.name())
              .bind("actor", actor)
              .bind("key", before.key())
              .execute();
          Purpose after = find(h, before.key()).orElseThrow();
          audit(
              h, actor, status == Status.RETIRED ? "RETIRE" : "REINSTATE", after.key(), reason,
              before, after);
          return after;
        });
  }

  /** Two purposes a person cannot tell apart in a picker are one too many. */
  private static void refuseNameTaken(Handle h, String name, String exceptKey) {
    Optional<String> taken =
        h.createQuery(
                "SELECT key FROM purpose WHERE lower(name) = lower(:name)"
                    + " AND (CAST(:except AS text) IS NULL OR key <> :except) LIMIT 1")
            .bind("name", name)
            .bind("except", exceptKey)
            .mapTo(String.class)
            .findOne();
    if (taken.isPresent()) {
      throw new Refused(
          Refused.Reason.CONFLICT, "The purpose " + taken.get() + " is already called " + name);
    }
  }

  private static boolean same(Purpose p, Details d) {
    return Objects.equals(p.name(), d.name())
        && Objects.equals(p.description(), d.description())
        && p.legalBasis() == d.legalBasis()
        && p.sensitiveAllowed() == d.sensitiveAllowed()
        && Objects.equals(p.owner(), d.owner())
        && Objects.equals(p.maxDays(), d.maxDays());
  }

  private static void count(
      Handle h, Map<String, int[]> counts, Map<String, String> spelled, int slot, String sql) {
    h.createQuery(sql)
        .map((rs, ctx) -> Map.entry(rs.getString("value"), rs.getInt("n")))
        .forEach(
            e -> {
              String value = e.getKey();
              if (value == null || value.isEmpty()) {
                return;
              }
              String lower = value.toLowerCase(Locale.ROOT);
              spelled.putIfAbsent(lower, value);
              counts.computeIfAbsent(lower, k -> new int[4])[slot] += e.getValue();
            });
  }

  private static Purpose purpose(java.sql.ResultSet rs) throws java.sql.SQLException {
    String basis = rs.getString("legal_basis");
    int maxDays = rs.getInt("max_days");
    Integer days = rs.wasNull() ? null : maxDays;
    return new Purpose(
        rs.getString("key"),
        rs.getString("name"),
        rs.getString("description"),
        basis == null ? null : LegalBasis.valueOf(basis),
        rs.getBoolean("sensitive_allowed"),
        rs.getString("owner"),
        days,
        Status.valueOf(rs.getString("status")),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("updated_by"),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static void audit(
      Handle h, String actor, String action, String key, String reason, Purpose before,
      Purpose after) {
    h.createUpdate(
            """
            INSERT INTO audit_purpose (actor, action, purpose_key, reason, before, after)
            VALUES (:actor, :action, :key, :reason, CAST(:before AS jsonb), CAST(:after AS jsonb))
            """)
        .bind("actor", actor)
        .bind("action", action)
        .bind("key", key)
        .bind("reason", reason)
        .bind("before", before == null ? null : json(before))
        .bind("after", after == null ? null : json(after))
        .execute();
  }

  private static String json(Purpose p) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("name", p.name());
    fields.put("description", p.description());
    fields.put("legalBasis", p.legalBasis() == null ? null : p.legalBasis().name());
    fields.put("sensitiveAllowed", p.sensitiveAllowed());
    fields.put("owner", p.owner());
    fields.put("maxDays", p.maxDays());
    fields.put("status", p.status().name());
    try {
      return JSON.writeValueAsString(fields);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode node(String json) {
    if (json == null) {
      return null;
    }
    try {
      return JSON.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  static String trim(String value) {
    if (value == null) {
      return null;
    }
    String out = value.strip();
    return out.isEmpty() ? null : out;
  }
}
