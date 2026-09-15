package com.mfec.dac.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The person or service asking for data, flattened for evaluation.
 *
 * <p>Roles, teams and groups are kept apart rather than merged into one bag of
 * strings: a policy that says {@code team = 'Finance'} must not be satisfied by
 * an Entra security group that happens to be called Finance. They are different
 * authorities and an auditor will ask which one granted access.
 *
 * <p>Names are compared case-insensitively. Directory sources disagree about
 * casing for the same group, and a policy silently failing over {@code FINANCE}
 * against {@code Finance} is an outage that looks like a permissions bug.
 */
public record Principal(
    String id,
    String email,
    Set<String> roles,
    Set<String> teams,
    Set<String> groups,
    List<Attribute> attributes) {

  public Principal {
    roles = normalise(roles);
    teams = normalise(teams);
    groups = normalise(groups);
    attributes = attributes == null ? List.of() : List.copyOf(attributes);
  }

  private static Set<String> normalise(Set<String> in) {
    if (in == null) {
      return Set.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String s : in) {
      if (s != null) {
        out.add(s.toLowerCase(Locale.ROOT));
      }
    }
    return Set.copyOf(out);
  }

  public boolean hasRole(String role) {
    return role != null && roles.contains(role.toLowerCase(Locale.ROOT));
  }

  public boolean hasTeam(String team) {
    return team != null && teams.contains(team.toLowerCase(Locale.ROOT));
  }

  public boolean hasGroup(String group) {
    return group != null && groups.contains(group.toLowerCase(Locale.ROOT));
  }

  /** True when the identifier names this principal, by id or by email. */
  public boolean is(String identifier) {
    if (identifier == null) {
      return false;
    }
    return identifier.equalsIgnoreCase(id) || identifier.equalsIgnoreCase(email);
  }

  /** Every value of an attribute; {@code source} may be null to accept any source. */
  public List<String> attributeValues(String key, com.mfec.dac.schema.entity.policy.AttributeCondition.Source source) {
    List<String> out = new ArrayList<>();
    for (Attribute a : attributes) {
      if (a.key() != null && a.key().equalsIgnoreCase(key) && (source == null || source == a.source())) {
        out.add(a.value());
      }
    }
    return out;
  }

  public static Builder withId(String id) {
    return new Builder(id);
  }

  public static final class Builder {
    private final String id;
    private String email;
    private final Set<String> roles = new LinkedHashSet<>();
    private final Set<String> teams = new LinkedHashSet<>();
    private final Set<String> groups = new LinkedHashSet<>();
    private final List<Attribute> attributes = new ArrayList<>();

    private Builder(String id) {
      this.id = id;
    }

    public Builder email(String value) {
      this.email = value;
      return this;
    }

    public Builder roles(String... values) {
      roles.addAll(List.of(values));
      return this;
    }

    public Builder teams(String... values) {
      teams.addAll(List.of(values));
      return this;
    }

    public Builder groups(String... values) {
      groups.addAll(List.of(values));
      return this;
    }

    public Builder attribute(Attribute value) {
      attributes.add(value);
      return this;
    }

    /** Shorthand for a locally held attribute, which is what tests use. */
    public Builder attribute(String key, Object value) {
      attributes.add(Attribute.local(key, value));
      return this;
    }

    public Principal build() {
      return new Principal(id, email, roles, teams, groups, attributes);
    }
  }
}
