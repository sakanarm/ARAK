package com.mfec.dac.auth;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The caller behind one request.
 *
 * <p>This is the platform's own identity — who is using the console — and it is
 * deliberately not the same thing as the {@code Principal} the policy engine
 * evaluates. The engine one carries department, clearance and team because a
 * policy compares those; this one carries the application roles from
 * {@code app_role_assignment} because they decide who may write a policy at all
 * (FR-2.6). Keeping them apart is what stops someone widening their own data
 * access by editing their console role.
 *
 * @param id the row in {@code principal}
 * @param username unique within its source
 * @param source entra, openmetadata or local
 * @param appRoles PLATFORM_ADMIN, POLICY_AUTHOR, DATA_OWNER, AUDITOR, REQUESTER
 * @param scopes the scope_fqn values attached to those roles; empty means global
 */
public record AuthenticatedUser(
    UUID id,
    String username,
    String email,
    String displayName,
    String source,
    Set<String> appRoles,
    List<String> scopes)
    implements java.security.Principal {

  @Override
  public String getName() {
    return username;
  }

  public boolean hasAnyRole(String... roles) {
    for (String role : roles) {
      if (appRoles.contains(role)) {
        return true;
      }
    }
    return false;
  }

  public boolean isPlatformAdmin() {
    return appRoles.contains("PLATFORM_ADMIN");
  }
}
