package com.mfec.dac.policy;

import com.mfec.dac.engine.Attribute;
import com.mfec.dac.engine.Principal;
import com.mfec.dac.schema.entity.policy.AttributeCondition;
import java.util.List;
import java.util.Locale;
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
