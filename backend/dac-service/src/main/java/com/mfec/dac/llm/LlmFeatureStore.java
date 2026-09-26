package com.mfec.dac.llm;

import com.mfec.dac.auth.AuthenticatedUser;
import java.sql.Array;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;

/**
 * Which parts of the assistant each application role may use (V29, M28).
 *
 * <p>A narrowing, never a widening. The per-person switch and the platform's
 * own switch in {@link LlmSettingStore} still decide whether somebody has an
 * assistant at all; this decides which of its jobs they are offered. And none
 * of these jobs reads anything the caller could not read without it -- the
 * catalogue tools are filtered by the caller's own decisions, the query log
 * tool reads the log as the query log page would show it to them -- so turning
 * one off here is a choice about cost and focus, not the thing that keeps data
 * in.
 *
 * <p>A feature nobody has configured is open to everyone, which is how the
 * assistant behaved before this table existed.
 */
public class LlmFeatureStore {

  /** One job the assistant does, as the settings screen names it. */
  public enum Feature {
    CHAT("Chat with ARAK", "The conversation in the assistant panel, on every page."),
    WRITE_SQL("Write queries", "Turn a question into a SELECT statement for the query editor."),
    FIX_SQL("Fix with AI", "Suggest a corrected statement after the proxy refused one."),
    EXPLAIN_SQL("Explain a query", "Say in words what the statement in the editor does."),
    DRAFT_POLICY("Draft policies", "Turn a sentence into a draft policy for the builder."),
    CATALOG_SEARCH(
        "Search the catalogue",
        "Find tables by what they hold. Only tables the person can read or request are shown."),
    INSIGHTS(
        "Query log and dashboard answers",
        "Answer questions about the query log and the dashboard, as each page shows them to the"
            + " person asking.");

    private final String label;
    private final String description;

    Feature(String label, String description) {
      this.label = label;
      this.description = description;
    }

    public String label() {
      return label;
    }

    public String description() {
      return description;
    }
  }

  /** The word that stands for every signed-in account. */
  public static final String EVERYONE = "EVERYONE";

  /** The application roles a feature can be given to. */
  public static final List<String> ROLES =
      List.of("PLATFORM_ADMIN", "POLICY_AUTHOR", "DATA_OWNER", "AUDITOR", "REQUESTER");

  /**
   * One feature as the settings screen shows it.
   *
   * @param roles EVERYONE, or the roles that may use it; empty is off for all
   * @param configured false while nobody has changed it from the default
   */
  public record Access(
      Feature feature,
      String label,
      String description,
      List<String> roles,
      boolean configured,
      Instant updatedAt,
      String updatedBy) {}

  private final Jdbi jdbi;

  public LlmFeatureStore(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /** Every feature, configured or not, in the order the screen lists them. */
  public List<Access> all() {
    Map<Feature, Access> stored = new EnumMap<>(Feature.class);
    jdbi.useHandle(
        handle ->
            handle
                .createQuery("SELECT feature, roles, updated_at, updated_by FROM llm_feature_access")
                .map(
                    (rs, ctx) -> {
                      Feature feature = parse(rs.getString("feature"));
                      if (feature == null) {
                        // A feature a later release removed. Ignored, not an error.
                        return null;
                      }
                      Array array = rs.getArray("roles");
                      List<String> roles = new ArrayList<>();
                      if (array != null) {
                        for (Object role : (Object[]) array.getArray()) {
                          roles.add(String.valueOf(role));
                        }
                      }
                      return new Access(
                          feature,
                          feature.label(),
                          feature.description(),
                          List.copyOf(roles),
                          true,
                          rs.getTimestamp("updated_at").toInstant(),
                          rs.getString("updated_by"));
                    })
                .list()
                .forEach(
                    access -> {
                      if (access != null) {
                        stored.put(access.feature(), access);
                      }
                    }));
    List<Access> out = new ArrayList<>();
    for (Feature feature : Feature.values()) {
      out.add(
          stored.getOrDefault(
              feature,
              new Access(
                  feature,
                  feature.label(),
                  feature.description(),
                  List.of(EVERYONE),
                  false,
                  null,
                  null)));
    }
    return out;
  }

  /** The features this caller is offered. */
  public Set<Feature> allowedFor(AuthenticatedUser caller) {
    Set<Feature> out = new LinkedHashSet<>();
    for (Access access : all()) {
      if (admits(access.roles(), caller)) {
        out.add(access.feature());
      }
    }
    return out;
  }

  public boolean allows(Feature feature, AuthenticatedUser caller) {
    return allowedFor(caller).contains(feature);
  }

  /** Whether a list of roles lets this caller in. Pure, so it is tested alone. */
  public static boolean admits(List<String> roles, AuthenticatedUser caller) {
    if (roles == null || caller == null) {
      return false;
    }
    for (String role : roles) {
      if (EVERYONE.equals(role)) {
        return true;
      }
      if (caller.appRoles() != null && caller.appRoles().contains(role)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Replaces who may use one feature.
   *
   * @return the list as stored: EVERYONE alone if it was given, otherwise the
   *     known roles in their usual order
   */
  public List<String> save(Feature feature, List<String> roles, String actor) {
    List<String> clean = normalise(roles);
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO llm_feature_access (feature, roles, updated_at, updated_by)
                    VALUES (:feature, :roles, now(), :actor)
                    ON CONFLICT (feature) DO UPDATE
                       SET roles = EXCLUDED.roles,
                           updated_at = EXCLUDED.updated_at,
                           updated_by = EXCLUDED.updated_by
                    """)
                .bind("feature", feature.name())
                .bindArray("roles", String.class, clean)
                .bind("actor", actor)
                .execute());
    return clean;
  }

  /**
   * The roles, checked and put in order.
   *
   * @throws IllegalArgumentException naming a role that does not exist
   */
  public static List<String> normalise(List<String> roles) {
    if (roles == null) {
      throw new IllegalArgumentException("roles is required; send [] to switch a feature off");
    }
    Set<String> wanted = new LinkedHashSet<>();
    for (String role : roles) {
      String upper = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
      if (upper.isEmpty()) {
        continue;
      }
      if (!EVERYONE.equals(upper) && !ROLES.contains(upper)) {
        throw new IllegalArgumentException(
            "Unknown role " + role.trim() + "; use EVERYONE or one of " + String.join(", ", ROLES));
      }
      wanted.add(upper);
    }
    if (wanted.contains(EVERYONE)) {
      return List.of(EVERYONE);
    }
    List<String> out = new ArrayList<>();
    for (String role : ROLES) {
      if (wanted.contains(role)) {
        out.add(role);
      }
    }
    return List.copyOf(out);
  }

  public static Feature parse(String name) {
    if (name == null) {
      return null;
    }
    try {
      return Feature.valueOf(name.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
