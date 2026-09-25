package com.mfec.dac.auth;

import com.mfec.dac.common.Fqns;

/**
 * Whose table this is, as far as the console is concerned (FR-2.6, FR-3.1.2).
 *
 * <p>One answer for every place that acts on an asset on somebody else's
 * behalf -- granting on it, revoking on it, listing who reaches it, simulating
 * another person against it, previewing its secure view. Each of those used to
 * let any holder of {@code DATA_OWNER} act on any table, because the role was
 * checked and its scope was not; {@code PolicyResource} was the only place that
 * compared the scope. A data owner of {@code prod.Sales} is not an owner of
 * {@code prod.HR}, and a check that forgets the scope in one resource is the
 * one somebody finds.
 *
 * <p>The comparison is the one policies use: segment by segment, so owning
 * {@code prod.Sales} is not authority over {@code prod.SalesArchive}, and an
 * owner with no scopes recorded owns nothing rather than everything.
 */
public final class Stewardship {

  private Stewardship() {}

  /**
   * May the caller change who reaches this asset: grant, revoke, preview its
   * enforcement. Platform administrators and policy authors govern everything;
   * a data owner governs their scopes and what is under them.
   */
  public static boolean governs(AuthenticatedUser caller, String assetFqn) {
    if (caller == null) {
      return false;
    }
    if (caller.isPlatformAdmin() || caller.hasAnyRole("POLICY_AUTHOR")) {
      return true;
    }
    if (!caller.hasAnyRole("DATA_OWNER") || assetFqn == null || assetFqn.isBlank()) {
      return false;
    }
    String fqn = assetFqn.trim();
    for (String owned : caller.scopes()) {
      if (owned != null && !owned.isBlank() && Fqns.isDescendantOrSelf(fqn, owned.trim())) {
        return true;
      }
    }
    return false;
  }

  /**
   * May the caller see who reaches this asset and why: everyone who governs it,
   * and an auditor, whose whole job is reading that without changing it.
   */
  public static boolean oversees(AuthenticatedUser caller, String assetFqn) {
    return governs(caller, assetFqn) || (caller != null && caller.hasAnyRole("AUDITOR"));
  }

  /**
   * May the caller read everything anybody holds, on every asset: the roles
   * whose authority is not bounded by a scope.
   */
  public static boolean overseesEverything(AuthenticatedUser caller) {
    return caller != null
        && (caller.isPlatformAdmin() || caller.hasAnyRole("POLICY_AUTHOR", "AUDITOR"));
  }
}
