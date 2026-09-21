package com.mfec.dac.identity;

import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;

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

  /**
   * What a principal id looks like, so a lookup can tell one from a username
   * without asking the database twice.
   */
  private static final java.util.regex.Pattern UUID_FORM =
      java.util.regex.Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

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
      /** People in this group. Zero for a user, which is not the same question. */
      int memberCount,
      /** Groups this principal belongs to. The one a user's row is read for. */
      int groupCount,
      List<String> appRoles) {}

  /** One value an attribute takes, and how many people carry it. */
  public record AttributeValue(String value, int principals) {}

  /** One attribute key with the values in use, for the builder's value picker. */
  public record AttributeKey(
      String key, String source, int principals, List<AttributeValue> values) {}

  /**
   * One attribute condition on the directory listing, written {@code key} or
   * {@code key=value}.
   *
   * <p>A null value asks who carries the key at all, which is the question
   * behind "is this attribute populated yet" — the one that decides whether a
   * rule depending on it would match anybody.
   */
  public record AttributeFilter(String key, String value) {

    /** At most this many conditions, so one URL cannot compose an unbounded join. */
    public static final int MAX = 10;

    /**
     * Reads {@code key} or {@code key=value}. A trailing {@code =} with nothing
     * after it is read as the key alone rather than as a condition on the empty
     * string, because an attribute with no value is not a thing the directory
     * holds and the other reading would silently match nobody.
     */
    public static AttributeFilter parse(String raw) {
      if (raw == null || raw.isBlank()) {
        return null;
      }
      int split = raw.indexOf('=');
      String key = (split < 0 ? raw : raw.substring(0, split)).trim();
      if (key.isEmpty()) {
        return null;
      }
      String value = split < 0 ? null : raw.substring(split + 1).trim();
      return new AttributeFilter(key, value == null || value.isEmpty() ? null : value);
    }

    /** The requested conditions, in order, ignoring anything unreadable. */
    public static List<AttributeFilter> parseAll(List<String> raw) {
      if (raw == null || raw.isEmpty()) {
        return List.of();
      }
      List<AttributeFilter> filters = new ArrayList<>();
      for (String entry : raw) {
        AttributeFilter filter = parse(entry);
        if (filter != null && !filters.contains(filter)) {
          filters.add(filter);
        }
        if (filters.size() == MAX) {
          break;
        }
      }
      return List.copyOf(filters);
    }
  }

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
             -- Both directions of group_member, because they answer different
             -- questions and only one of them is ever non-zero for a given row:
             -- a group is opened for who is in it, a person for what they are
             -- in. Reporting one of them as the other -- which this column did
             -- until the directory started showing members -- tells every user
             -- they belong to nothing.
             (SELECT count(*) FROM group_member gm
               WHERE gm.group_id = p.id) AS member_count,
             (SELECT count(*) FROM group_member gg
               WHERE gg.member_id = p.id) AS group_count,
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
  public List<Principal> list(
      String principalType,
      String source,
      String search,
      List<AttributeFilter> attributes,
      int limit) {
    List<AttributeFilter> filters = attributes == null ? List.of() : attributes;
    StringBuilder sql =
        new StringBuilder(PRINCIPAL_COLUMNS)
            .append(
                """
                WHERE (:principalType IS NULL OR p.principal_type = :principalType)
                  AND (:source IS NULL OR p.source = :source)
                  AND (:search IS NULL
                       OR p.username ILIKE :pattern
                       OR p.display_name ILIKE :pattern
                       OR p.email ILIKE :pattern)
                """);
    for (int i = 0; i < filters.size(); i++) {
      sql.append(attributeClause(i));
    }
    sql.append(
        """
        ORDER BY p.principal_type, p.username
        LIMIT :limit
        """);

    return jdbi.withHandle(
        handle -> {
          Query query =
              handle
                  .createQuery(sql.toString())
                  .bind("principalType", blankToNull(principalType))
                  .bind("source", blankToNull(source))
                  .bind("search", blankToNull(search))
                  .bind(
                      "pattern", search == null || search.isBlank() ? null : "%" + search + "%")
                  .bind("limit", limit);
          for (int i = 0; i < filters.size(); i++) {
            query.bind("attrKey" + i, filters.get(i).key());
            query.bind("attrValue" + i, filters.get(i).value());
          }
          return query.map((rs, ctx) -> principal(rs)).list();
        });
  }

  /**
   * One attribute condition, as an {@code EXISTS} over the principal's own rows.
   *
   * <p>Several conditions are ANDed, matching how a subject rule reads its own
   * attribute list, so this listing answers the question an author actually has
   * — who would that rule match — rather than a looser version of it.
   *
   * <p>{@code EXISTS} rather than a join, for the two reasons that decide the
   * result: an attribute is multi-valued, so somebody holding both L1 and L2
   * must match a condition on either without being returned twice; and nothing
   * here follows group membership, because {@code PrincipalLoader} does not
   * either. A directory that credited people with their group's attributes
   * would show matches the engine will not make.
   */
  private static String attributeClause(int index) {
    return """
           AND EXISTS (SELECT 1 FROM principal_attribute fa
                        WHERE fa.principal_id = p.id
                          AND fa.valid_to IS NULL
                          AND fa.attr_key = :attrKey%d
                          AND (:attrValue%d IS NULL OR fa.attr_value = :attrValue%d))
           """
        .formatted(index, index, index);
  }

  /**
   * Attribute keys with their distinct values, each with the number of people
   * carrying it.
   *
   * <p>The count per value is the whole point of the list. A key that 40 people
   * carry says nothing about the rule somebody is about to write; {@code
   * clearance=L3 · 1} says it immediately, and says it before the policy is
   * active rather than after somebody reports they cannot see their own data.
   *
   * <p>Values are capped per key. Something like an employee number is unique
   * per person, and listing every one would turn a picker into a directory
   * dump; the cap keeps the cases that matter — department, clearance, country
   * — complete, and leaves the rest recognisably truncated.
   *
   * <p>Two queries rather than one aggregate: counting distinct principals per
   * value and per key at the same time needs either a lateral join or an array
   * of composites, and both read worse than a merge in Java over a list this
   * size.
   */
  public List<AttributeKey> attributeKeys(int valuesPerKey) {
    return jdbi.withHandle(
        handle -> {
          record Slot(String key, String source) {}
          java.util.Map<Slot, List<AttributeValue>> values = new java.util.LinkedHashMap<>();
          handle
              .createQuery(
                  """
                  SELECT attr_key, source, attr_value, people FROM (
                    SELECT a.attr_key, a.source, a.attr_value,
                           count(DISTINCT a.principal_id) AS people,
                           row_number() OVER (PARTITION BY a.attr_key, a.source
                                              ORDER BY a.attr_value) AS rn
                      FROM principal_attribute a
                     WHERE a.valid_to IS NULL
                     GROUP BY a.attr_key, a.source, a.attr_value) ranked
                  WHERE rn <= :valuesPerKey
                  ORDER BY attr_key, source, attr_value
                  """)
              .bind("valuesPerKey", valuesPerKey)
              .map(
                  (rs, ctx) ->
                      java.util.Map.entry(
                          new Slot(rs.getString("attr_key"), rs.getString("source")),
                          new AttributeValue(rs.getString("attr_value"), rs.getInt("people"))))
              .forEach(
                  entry ->
                      values
                          .computeIfAbsent(entry.getKey(), slot -> new ArrayList<>())
                          .add(entry.getValue()));

          return handle
              .createQuery(
                  """
                  SELECT a.attr_key, a.source,
                         count(DISTINCT a.principal_id) AS principals
                  FROM principal_attribute a
                  WHERE a.valid_to IS NULL
                  GROUP BY a.attr_key, a.source
                  ORDER BY a.attr_key, a.source
                  """)
              .map(
                  (rs, ctx) -> {
                    Slot slot = new Slot(rs.getString("attr_key"), rs.getString("source"));
                    return new AttributeKey(
                        slot.key(),
                        slot.source(),
                        rs.getInt("principals"),
                        List.copyOf(values.getOrDefault(slot, List.of())));
                  })
              .list();
        });
  }

  /**
   * One principal with everything that decides what a policy makes of them,
   * found by id or by username.
   *
   * <p>Both, because a username is only unique within a source: an Entra group
   * and an OpenMetadata team may both be called {@code Finance}, and answering
   * that with either an error or a silently chosen one of the two is no way to
   * show somebody the members of a group. The directory links by id for that
   * reason; the username path stays for a URL typed by hand, and takes the
   * first by source rather than failing.
   */
  public java.util.Optional<PrincipalDetail> detail(String idOrUsername) {
    return jdbi.withHandle(
        handle -> {
          boolean byId = UUID_FORM.matcher(idOrUsername).matches();
          java.util.Optional<Principal> found =
              handle
                  .createQuery(
                      PRINCIPAL_COLUMNS
                          + (byId
                              ? " WHERE p.id = CAST(:key AS uuid)"
                              : " WHERE p.username = :key ORDER BY p.source"))
                  .bind("key", idOrUsername)
                  .map((rs, ctx) -> principal(rs))
                  .findFirst();
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
        rs.getInt("group_count"),
        roles == null || roles.isBlank() ? List.of() : List.of(roles.split(",")));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
