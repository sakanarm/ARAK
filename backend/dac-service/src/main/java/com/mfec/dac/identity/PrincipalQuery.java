package com.mfec.dac.identity;

import java.util.List;
import org.jdbi.v3.core.Jdbi;

/**
 * Reading the identity cache: who exists, what they carry, who they belong to
 * (FR-2.2, FR-2.4, FR-2.5).
 *
 * <p>This is what the subject half of a policy is written against. The Policy
 * Builder needs the real vocabulary — the attribute keys that exist and the
 * values they actually take — because an ABAC rule on {@code clearance >= L2}
 * is worthless if nobody in the directory carries a clearance attribute at all,
 * and a free-text box would let an author write exactly that and find out in
 * production.
 */
public class PrincipalQuery {

  private final Jdbi jdbi;

  public PrincipalQuery(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  public record Principal(
      String id,
      String principalType,
      String username,
      String email,
      String displayName,
      String source,
      boolean enabled,
      int attributeCount,
      int memberCount,
      List<String> appRoles) {}

  /** One attribute key with the values in use, for the builder's value picker. */
  public record AttributeKey(String key, String source, int principals, List<String> values) {}

  public record Attribute(String key, String value, String source) {}

  public record PrincipalDetail(
      Principal principal,
      List<Attribute> attributes,
      List<Principal> groups,
      List<Principal> members) {}

  private static final String PRINCIPAL_COLUMNS =
      """
      SELECT p.id, p.principal_type, p.username, p.email, p.display_name,
             p.source, p.enabled,
             (SELECT count(*) FROM principal_attribute a
               WHERE a.principal_id = p.id AND a.valid_to IS NULL) AS attribute_count,
             (SELECT count(*) FROM group_member g
               WHERE g.group_id = p.id) AS member_count,
             COALESCE((SELECT string_agg(DISTINCT r.app_role, ',')
                       FROM app_role_assignment r
                       WHERE r.principal_id = p.id), '') AS app_roles
      FROM principal p
      """;

  /**
   * Users, groups and service accounts.
   *
   * <p>Groups carry their member count and users their attribute count, because
   * the question this list is opened with is nearly always "is this one
   * populated" — a group that synced empty and an attribute that never arrived
   * look identical from the name alone.
   */
  public List<Principal> list(String principalType, String source, String search, int limit) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    PRINCIPAL_COLUMNS
                        + """
                        WHERE (:principalType IS NULL OR p.principal_type = :principalType)
                          AND (:source IS NULL OR p.source = :source)
                          AND (:search IS NULL
                               OR p.username ILIKE :pattern
                               OR p.display_name ILIKE :pattern
                               OR p.email ILIKE :pattern)
                        ORDER BY p.principal_type, p.username
                        LIMIT :limit
                        """)
                .bind("principalType", blankToNull(principalType))
                .bind("source", blankToNull(source))
                .bind("search", blankToNull(search))
                .bind("pattern", search == null || search.isBlank() ? null : "%" + search + "%")
                .bind("limit", limit)
                .map((rs, ctx) -> principal(rs))
                .list());
  }

  /**
   * Attribute keys with their distinct values.
   *
   * <p>Values are capped per key. Something like an employee number is unique
   * per person, and listing every one would turn a picker into a directory
   * dump; the cap keeps the cases that matter — department, clearance, country
   * — complete, and leaves the rest recognisably truncated.
   */
  public List<AttributeKey> attributeKeys(int valuesPerKey) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT a.attr_key, a.source,
                           count(DISTINCT a.principal_id) AS principals,
                           (SELECT array_agg(top.v)
                              FROM (SELECT DISTINCT a2.attr_value AS v
                                      FROM principal_attribute a2
                                     WHERE a2.attr_key = a.attr_key
                                       AND a2.source = a.source
                                       AND a2.valid_to IS NULL
                                     ORDER BY 1
                                     LIMIT :valuesPerKey) top) AS attr_values
                    FROM principal_attribute a
                    WHERE a.valid_to IS NULL
                    GROUP BY a.attr_key, a.source
                    ORDER BY a.attr_key, a.source
                    """)
                .bind("valuesPerKey", valuesPerKey)
                .map(
                    (rs, ctx) -> {
                      java.sql.Array array = rs.getArray("attr_values");
                      List<String> values =
                          array == null ? List.of() : List.of((String[]) array.getArray());
                      return new AttributeKey(
                          rs.getString("attr_key"),
                          rs.getString("source"),
                          rs.getInt("principals"),
                          values);
                    })
                .list());
  }

  /** One principal with everything that decides what a policy makes of them. */
  public java.util.Optional<PrincipalDetail> detail(String username) {
    return jdbi.withHandle(
        handle -> {
          java.util.Optional<Principal> found =
              handle
                  .createQuery(PRINCIPAL_COLUMNS + " WHERE p.username = :username")
                  .bind("username", username)
                  .map((rs, ctx) -> principal(rs))
                  .findOne();
          if (found.isEmpty()) {
            return java.util.Optional.<PrincipalDetail>empty();
          }
          String id = found.get().id();

          List<Attribute> attributes =
              handle
                  .createQuery(
                      """
                      SELECT attr_key, attr_value, source FROM principal_attribute
                      WHERE principal_id = CAST(:id AS uuid) AND valid_to IS NULL
                      ORDER BY attr_key, attr_value
                      """)
                  .bind("id", id)
                  .map(
                      (rs, ctx) ->
                          new Attribute(
                              rs.getString("attr_key"),
                              rs.getString("attr_value"),
                              rs.getString("source")))
                  .list();

          return java.util.Optional.of(
              new PrincipalDetail(
                  found.get(), attributes, related(handle, id, true), related(handle, id, false)));
        });
  }

  /** The groups this principal belongs to, or the members it contains. */
  private static List<Principal> related(
      org.jdbi.v3.core.Handle handle, String id, boolean groupsOfThisMember) {
    String join =
        groupsOfThisMember
            ? "JOIN group_member g ON g.group_id = p.id WHERE g.member_id = CAST(:id AS uuid)"
            : "JOIN group_member g ON g.member_id = p.id WHERE g.group_id = CAST(:id AS uuid)";
    return handle
        .createQuery(PRINCIPAL_COLUMNS + join + " ORDER BY p.username")
        .bind("id", id)
        .map((rs, ctx) -> principal(rs))
        .list();
  }

  private static Principal principal(java.sql.ResultSet rs) throws java.sql.SQLException {
    String roles = rs.getString("app_roles");
    return new Principal(
        rs.getString("id"),
        rs.getString("principal_type"),
        rs.getString("username"),
        rs.getString("email"),
        rs.getString("display_name"),
        rs.getString("source"),
        rs.getBoolean("enabled"),
        rs.getInt("attribute_count"),
        rs.getInt("member_count"),
        roles == null || roles.isBlank() ? List.of() : List.of(roles.split(",")));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
