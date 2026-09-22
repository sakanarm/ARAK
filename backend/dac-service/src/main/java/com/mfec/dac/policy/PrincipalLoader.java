package com.mfec.dac.policy;

import com.mfec.dac.engine.Attribute;
import com.mfec.dac.engine.Principal;
import com.mfec.dac.schema.entity.policy.AttributeCondition;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jdbi.v3.core.Handle;

/**
 * Reads the identity cache into the flat {@link Principal} the engine evaluates.
 *
 * <p>The engine deliberately knows nothing about tables, so somebody has to
 * turn four tables into one record. Doing it here, once, is what keeps the
 * decision API, the simulator and the query proxy from each building a slightly
 * different idea of who the caller is — the failure mode being a policy that
 * grants in the simulator and denies in production.
 *
 * <h2>Why teams and groups are loaded separately</h2>
 *
 * <p>Both live in {@code group_member}, but a policy that says
 * {@code team = 'Finance'} means the OpenMetadata team, and must not be
 * satisfied by an Entra security group that happens to carry the same name
 * (FR-2.3). The group's own {@code source} decides which bucket it lands in.
 *
 * <h2>Nested membership counts</h2>
 *
 * <p>Membership is walked transitively. OpenMetadata teams nest, Entra groups
 * nest, and a person in {@code Finance.Risk} is in {@code Finance} by every
 * definition an auditor would accept. Resolving only direct edges would deny
 * people access their directory says they have, which reads as a platform bug
 * rather than as a policy decision.
 */
public class PrincipalLoader {

  /** Thrown when a decision is asked about somebody the identity cache has never seen. */
  public static class NoSuchPrincipalException extends RuntimeException {
    public NoSuchPrincipalException(String username) {
      super("no principal named " + username);
    }
  }

  /**
   * Loads one principal by username, or empty when there is no such row.
   *
   * <p>A disabled principal is still returned. Being disabled is a fact the
   * decision should be able to state — "denied, the account is disabled" — and
   * pretending the account does not exist turns that into "no such user", which
   * sends the person to the wrong help desk.
   */
  public Optional<Principal> find(Handle handle, String username) {
    Optional<Row> row =
        handle
            .createQuery(
                """
                SELECT id, username, email, enabled
                FROM principal
                WHERE lower(username) = lower(:username)
                ORDER BY CASE source WHEN 'entra' THEN 0 WHEN 'openmetadata' THEN 1 ELSE 2 END
                LIMIT 1
                """)
            .bind("username", username)
            .map(
                (rs, ctx) ->
                    new Row(
                        rs.getString("id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getBoolean("enabled")))
            .findOne();

    if (row.isEmpty()) {
      return Optional.empty();
    }
    String id = row.get().id();

    Set<String> roles = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    roles.addAll(
        handle
            .createQuery(
                "SELECT app_role FROM app_role_assignment WHERE principal_id = CAST(:id AS uuid)")
            .bind("id", id)
            .mapTo(String.class)
            .list());

    List<Membership> memberships = memberships(handle, id);
    Set<String> teams = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    Set<String> groups = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    for (Membership membership : memberships) {
      if ("openmetadata".equals(membership.source())) {
        teams.add(membership.username());
      } else {
        groups.add(membership.username());
      }
    }

    List<Attribute> attributes =
        handle
            .createQuery(
                """
                SELECT attr_key, attr_value, source
                FROM principal_attribute
                WHERE principal_id = CAST(:id AS uuid)
                  AND valid_to IS NULL
                ORDER BY attr_key, attr_value
                """)
            .bind("id", id)
            .map(
                (rs, ctx) ->
                    new Attribute(
                        rs.getString("attr_key"),
                        rs.getString("attr_value"),
                        source(rs.getString("source"))))
            .list();

    Principal.Builder builder =
        Principal.withId(row.get().username())
            .email(row.get().email())
            .roles(roles.toArray(String[]::new))
            .teams(teams.toArray(String[]::new))
            .groups(groups.toArray(String[]::new));
    for (Attribute attribute : attributes) {
      builder.attribute(attribute);
    }
    return Optional.of(builder.build());
  }

  /**
   * Everyone a decision can be asked about, loaded in four queries rather than
   * four per person.
   *
   * <p>{@link #find} is shaped for one principal at a time, which is right for
   * a decision and wrong for impact analysis (FR-5.3): answering "who does this
   * policy change things for" means evaluating the whole directory, and doing
   * that one {@code find} at a time is four round trips per person for no
   * reason. The shaping is identical -- same buckets, same transitive
   * membership, same attribute filter -- because a person who appears one way
   * in the impact report and another way in the simulator is a bug report
   * nobody can reproduce.
   *
   * <p>Groups are left out. A group is a row in {@code principal} so that
   * membership can nest, but nobody logs in as one, and counting groups among
   * the people affected would inflate every number on the screen.
   *
   * @param limit how many to load, newest-irrelevant, ordered by username so
   *     that a truncated run is at least a stable prefix rather than a
   *     different arbitrary subset each time
   */
  public List<Principal> everyone(Handle handle, int limit) {
    List<Row> rows =
        handle
            .createQuery(
                """
                SELECT id, username, email, enabled
                FROM principal
                WHERE principal_type IN ('USER', 'SERVICE')
                  AND enabled
                ORDER BY lower(username)
                LIMIT :limit
                """)
            .bind("limit", limit)
            .map(
                (rs, ctx) ->
                    new Row(
                        rs.getString("id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getBoolean("enabled")))
            .list();
    if (rows.isEmpty()) {
      return List.of();
    }

    List<String> ids = rows.stream().map(Row::id).toList();

    Map<String, Set<String>> roles = new HashMap<>();
    handle
        .createQuery(
            """
            SELECT principal_id, app_role FROM app_role_assignment
            WHERE principal_id IN (<ids>)
            """)
        .bindList("ids", ids.stream().map(java.util.UUID::fromString).toList())
        .map((rs, ctx) -> new String[] {rs.getString("principal_id"), rs.getString("app_role")})
        .forEach(
            pair ->
                roles
                    .computeIfAbsent(pair[0], key -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                    .add(pair[1]));

    Map<String, List<Membership>> memberships = allMemberships(handle, ids);

    Map<String, List<Attribute>> attributes = new HashMap<>();
    handle
        .createQuery(
            """
            SELECT principal_id, attr_key, attr_value, source
            FROM principal_attribute
            WHERE principal_id IN (<ids>)
              AND valid_to IS NULL
            ORDER BY attr_key, attr_value
            """)
        .bindList("ids", ids.stream().map(java.util.UUID::fromString).toList())
        .map(
            (rs, ctx) ->
                Map.entry(
                    rs.getString("principal_id"),
                    new Attribute(
                        rs.getString("attr_key"),
                        rs.getString("attr_value"),
                        source(rs.getString("source")))))
        .forEach(
            entry ->
                attributes
                    .computeIfAbsent(entry.getKey(), key -> new java.util.ArrayList<>())
                    .add(entry.getValue()));

    List<Principal> out = new java.util.ArrayList<>(rows.size());
    for (Row row : rows) {
      Set<String> teams = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      Set<String> groups = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      for (Membership membership : memberships.getOrDefault(row.id(), List.of())) {
        if ("openmetadata".equals(membership.source())) {
          teams.add(membership.username());
        } else {
          groups.add(membership.username());
        }
      }
      Principal.Builder builder =
          Principal.withId(row.username())
              .email(row.email())
              .roles(roles.getOrDefault(row.id(), Set.of()).toArray(String[]::new))
              .teams(teams.toArray(String[]::new))
              .groups(groups.toArray(String[]::new));
      for (Attribute attribute : attributes.getOrDefault(row.id(), List.of())) {
        builder.attribute(attribute);
      }
      out.add(builder.build());
    }
    return List.copyOf(out);
  }

  /** How many people {@link #everyone} would return without a limit. */
  public int countEveryone(Handle handle) {
    return handle
        .createQuery(
            """
            SELECT count(*) FROM principal
            WHERE principal_type IN ('USER', 'SERVICE') AND enabled
            """)
        .mapTo(Integer.class)
        .one();
  }

  /**
   * The transitive closure of the membership graph, for many members at once.
   *
   * <p>Same walk as {@link #memberships}, seeded from every requested member
   * rather than one, with the seed carried along so each row can be attributed
   * back. {@code UNION} again, for the same reason: a diamond in the graph
   * would otherwise not terminate.
   */
  private static Map<String, List<Membership>> allMemberships(Handle handle, List<String> ids) {
    Map<String, List<Membership>> out = new HashMap<>();
    handle
        .createQuery(
            """
            WITH RECURSIVE reachable(member_id, group_id) AS (
                SELECT member_id, group_id FROM group_member
                WHERE member_id IN (<ids>)
              UNION
                SELECT r.member_id, m.group_id
                FROM group_member m
                JOIN reachable r ON m.member_id = r.group_id
            )
            SELECT r.member_id, p.username, p.source
            FROM reachable r
            JOIN principal p ON p.id = r.group_id
            WHERE p.enabled
            ORDER BY p.username
            """)
        .bindList("ids", ids.stream().map(java.util.UUID::fromString).toList())
        .map(
            (rs, ctx) ->
                Map.entry(
                    rs.getString("member_id"),
                    new Membership(rs.getString("username"), rs.getString("source"))))
        .forEach(
            entry ->
                out.computeIfAbsent(entry.getKey(), key -> new java.util.ArrayList<>())
                    .add(entry.getValue()));
    return out;
  }

  /** Same as {@link #find}, for callers that treat an unknown principal as an error. */
  public Principal require(Handle handle, String username) {
    return find(handle, username).orElseThrow(() -> new NoSuchPrincipalException(username));
  }

  /**
   * Every group above this principal, following nesting to the top.
   *
   * <p>{@code UNION} rather than {@code UNION ALL}: a diamond in the group graph
   * — two teams that both roll up to the same parent — would otherwise walk
   * forever, and Postgres cannot detect that for us.
   */
  private static List<Membership> memberships(Handle handle, String id) {
    return handle
        .createQuery(
            """
            WITH RECURSIVE reachable(group_id) AS (
                SELECT group_id FROM group_member WHERE member_id = CAST(:id AS uuid)
              UNION
                SELECT m.group_id FROM group_member m
                JOIN reachable r ON m.member_id = r.group_id
            )
            SELECT p.username, p.source
            FROM reachable r
            JOIN principal p ON p.id = r.group_id
            WHERE p.enabled
            ORDER BY p.username
            """)
        .bind("id", id)
        .map((rs, ctx) -> new Membership(rs.getString("username"), rs.getString("source")))
        .list();
  }

  private static AttributeCondition.Source source(String value) {
    if (value == null) {
      return AttributeCondition.Source.LOCAL;
    }
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "entra" -> AttributeCondition.Source.ENTRA;
      case "openmetadata" -> AttributeCondition.Source.OPENMETADATA;
      default -> AttributeCondition.Source.LOCAL;
    };
  }

  private record Row(String id, String username, String email, boolean enabled) {}

  private record Membership(String username, String source) {}
}
