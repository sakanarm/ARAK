package com.mfec.dac.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.access.AccessWorkflow.Seat;
import com.mfec.dac.common.Fqns;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jdbi.v3.core.Handle;

/**
 * Turns a stage's seats into the people who are asked (M9 slice 2a).
 *
 * <p>Every seat resolves to enabled people, never to a group: "everyone must
 * approve" has to count heads, and a team that approves as one voice would let
 * its first member speak for all of them.
 *
 * <ul>
 *   <li>{@code USER} -- the person, by username or email;
 *   <li>{@code TEAM} -- every member of every group of that name, however deeply
 *       nested;
 *   <li>{@code ROLE} -- whoever holds the app role everywhere, or over a scope
 *       that covers the table, compared segment by segment;
 *   <li>{@code ASSET_OWNERS} -- the owners OpenMetadata records, a user as that
 *       user and a team through its OpenMetadata membership, which is exactly
 *       how the engine matches {@code assetOwner: true};
 *   <li>{@code DATA_STEWARD}, {@code DATA_CUSTODIAN} -- whoever the table's
 *       custom property of that name holds: a name, an entity reference, or a
 *       list of either.
 * </ul>
 *
 * <p>Nobody is ever asked about their own request. When the seats leave
 * nobody, the platform administrators are asked instead, and the pool says so,
 * because "a table nobody owns" must not become "a table nobody can be let
 * into".
 */
public class ApproverDirectory {

  /** The custom property keys a steward and a custodian are read from, compared without case. */
  static final String STEWARD_KEY = "dataSteward";

  static final String CUSTODIAN_KEY = "dataCustodian";

  private final ObjectMapper json;

  public ApproverDirectory(ObjectMapper json) {
    this.json = json;
  }

  /**
   * One person asked.
   *
   * @param via which seat put them there, as the page names it
   */
  public record Member(String username, String via) {}

  /**
   * The people asked for one stage.
   *
   * @param fallback no seat resolved to anybody but the requester, so these are
   *     the platform administrators
   */
  public record Pool(List<Member> members, boolean fallback) {

    public boolean contains(String username) {
      if (username == null) {
        return false;
      }
      for (Member member : members) {
        if (member.username().equalsIgnoreCase(username)) {
          return true;
        }
      }
      return false;
    }
  }

  /** The people these seats name on this asset, the requester left out, administrators if nobody is left. */
  public Pool pool(Handle handle, List<Seat> seats, String assetFqn, String requester) {
    Map<String, Member> out = new LinkedHashMap<>();
    for (Seat seat : seats) {
      for (Member member : resolve(handle, seat, assetFqn)) {
        if (!member.username().equalsIgnoreCase(requester)) {
          out.putIfAbsent(member.username().toLowerCase(Locale.ROOT), member);
        }
      }
    }
    if (!out.isEmpty()) {
      return new Pool(List.copyOf(out.values()), false);
    }
    List<Member> admins = new ArrayList<>();
    for (String admin : administrators(handle)) {
      if (!admin.equalsIgnoreCase(requester)) {
        admins.add(new Member(admin, "Platform administrator"));
      }
    }
    return new Pool(List.copyOf(admins), true);
  }

  /** Enabled platform administrators with no scope, who may answer for any stage. */
  public List<String> administrators(Handle handle) {
    return handle
        .createQuery(
            """
            SELECT DISTINCT p.username FROM app_role_assignment r
            JOIN principal p ON p.id = r.principal_id
            WHERE r.app_role = 'PLATFORM_ADMIN' AND r.scope_fqn IS NULL
              AND p.enabled AND p.principal_type = 'USER'
            ORDER BY p.username
            """)
        .mapTo(String.class)
        .list();
  }

  /** The people one seat names on this asset, requester included. */
  public List<Member> resolve(Handle handle, Seat seat, String assetFqn) {
    if (seat == null || seat.kind() == null) {
      return List.of();
    }
    String via = seat.describe();
    return switch (seat.kind()) {
      case USER -> as(users(handle, seat.name()), via);
      case TEAM -> as(members(handle, seat.name(), false), via);
      case ROLE -> as(holders(handle, seat.name(), assetFqn), via);
      case ASSET_OWNERS -> owners(handle, assetFqn);
      case DATA_STEWARD -> named(handle, assetFqn, STEWARD_KEY, via);
      case DATA_CUSTODIAN -> named(handle, assetFqn, CUSTODIAN_KEY, via);
    };
  }

  private static List<Member> as(List<String> usernames, String via) {
    return usernames.stream().map(u -> new Member(u, via)).toList();
  }

  private static List<String> users(Handle handle, String name) {
    if (name == null || name.isBlank()) {
      return List.of();
    }
    return handle
        .createQuery(
            """
            SELECT username FROM principal
            WHERE principal_type = 'USER' AND enabled
              AND (lower(username) = lower(:name) OR lower(email) = lower(:name))
            ORDER BY CASE source WHEN 'entra' THEN 0 WHEN 'openmetadata' THEN 1 ELSE 2 END
            LIMIT 1
            """)
        .bind("name", name.trim())
        .mapTo(String.class)
        .list();
  }

  /**
   * Every enabled person in the group, through nested groups.
   *
   * @param openMetadataOnly only a team OpenMetadata syncs, the way an owning
   *     team is matched; an Entra group of the same name is somebody else
   */
  private static List<String> members(Handle handle, String group, boolean openMetadataOnly) {
    if (group == null || group.isBlank()) {
      return List.of();
    }
    return handle
        .createQuery(
            """
            WITH RECURSIVE inside(id) AS (
              SELECT gm.member_id FROM group_member gm
              JOIN principal g ON g.id = gm.group_id
              WHERE g.principal_type = 'GROUP' AND lower(g.username) = lower(:group)
                AND (NOT :omOnly OR g.source = 'openmetadata')
              UNION
              SELECT gm.member_id FROM group_member gm JOIN inside i ON gm.group_id = i.id
            )
            SELECT DISTINCT p.username FROM principal p JOIN inside i ON p.id = i.id
            WHERE p.principal_type = 'USER' AND p.enabled
            ORDER BY p.username
            """)
        .bind("group", group.trim())
        .bind("omOnly", openMetadataOnly)
        .mapTo(String.class)
        .list();
  }

  private static List<String> holders(Handle handle, String role, String assetFqn) {
    if (role == null || role.isBlank()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    handle
        .createQuery(
            """
            SELECT p.username, r.scope_fqn FROM app_role_assignment r
            JOIN principal p ON p.id = r.principal_id
            WHERE r.app_role = :role AND p.enabled AND p.principal_type = 'USER'
            ORDER BY p.username
            """)
        .bind("role", role.trim().toUpperCase(Locale.ROOT))
        .map((rs, ctx) -> new String[] {rs.getString("username"), rs.getString("scope_fqn")})
        .forEach(
            row -> {
              boolean covers =
                  row[1] == null || (assetFqn != null && Fqns.isDescendantOrSelf(assetFqn, row[1]));
              if (covers && out.stream().noneMatch(u -> u.equalsIgnoreCase(row[0]))) {
                out.add(row[0]);
              }
            });
    return out;
  }

  private static List<Member> owners(Handle handle, String assetFqn) {
    List<Member> out = new ArrayList<>();
    handle
        .createQuery(
            """
            SELECT owner_type, owner_name FROM asset_owner
            WHERE target_fqn = :fqn
            ORDER BY is_direct DESC, owner_type DESC, owner_name
            """)
        .bind("fqn", assetFqn)
        .map((rs, ctx) -> new String[] {rs.getString("owner_type"), rs.getString("owner_name")})
        .forEach(
            owner -> {
              // Each only as what it is: a user called "finance" is not the
              // Finance team, and a team called "alice" is not Alice.
              if ("team".equalsIgnoreCase(owner[0])) {
                out.addAll(as(members(handle, owner[1], true), "Owner (team " + owner[1] + ")"));
              } else if ("user".equalsIgnoreCase(owner[0])) {
                out.addAll(as(users(handle, owner[1]), "Owner"));
              }
            });
    return out;
  }

  private List<Member> named(Handle handle, String assetFqn, String key, String via) {
    String props =
        handle
            .createQuery("SELECT custom_properties::text FROM asset WHERE fqn = :fqn AND is_current")
            .bind("fqn", assetFqn)
            .mapTo(String.class)
            .findOne()
            .orElse(null);
    if (props == null) {
      return List.of();
    }
    JsonNode value;
    try {
      value = property(json.readTree(props), key);
    } catch (Exception e) {
      return List.of();
    }
    List<Member> out = new ArrayList<>();
    for (String[] ref : references(value)) {
      String type = ref[0];
      String name = ref[1];
      if ("team".equals(type)) {
        out.addAll(as(members(handle, name, false), via + " (team " + name + ")"));
      } else if ("user".equals(type)) {
        out.addAll(as(users(handle, name), via));
      } else {
        // A bare name: the person if there is one, else the team.
        List<String> people = users(handle, name);
        out.addAll(
            people.isEmpty()
                ? as(members(handle, name, false), via + " (team " + name + ")")
                : as(people, via));
      }
    }
    return out;
  }

  private static JsonNode property(JsonNode props, String key) {
    if (props == null || !props.isObject()) {
      return null;
    }
    Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      if (field.getKey().equalsIgnoreCase(key)) {
        return field.getValue();
      }
    }
    return null;
  }

  /** {type or null, name} for a string, an entity reference, or a list of either. */
  static List<String[]> references(JsonNode value) {
    List<String[]> out = new ArrayList<>();
    if (value == null || value.isNull()) {
      return out;
    }
    if (value.isArray()) {
      for (JsonNode item : value) {
        out.addAll(references(item));
      }
      return out;
    }
    if (value.isTextual()) {
      for (String part : value.asText().split(",")) {
        if (!part.isBlank()) {
          out.add(new String[] {null, part.trim()});
        }
      }
      return out;
    }
    if (value.isObject()) {
      String type = value.path("type").asText("").toLowerCase(Locale.ROOT);
      String name = value.path("name").asText("");
      if (name.isBlank()) {
        name = value.path("fullyQualifiedName").asText("");
      }
      if (!name.isBlank()) {
        out.add(new String[] {type.equals("user") || type.equals("team") ? type : null, name.trim()});
      }
    }
    return out;
  }
}
