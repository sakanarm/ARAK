package com.mfec.dac.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/** Entra ID is the production identity provider; local users exist for tests and service accounts (FR-2.2). */
@Getter
@Setter
public class IdentityConfiguration {

  @JsonProperty("entraTenantId")
  private String entraTenantId;

  @JsonProperty("entraClientId")
  private String entraClientId;

  @JsonProperty("entraClientSecret")
  private String entraClientSecret;

  @JsonProperty("allowLocalUsers")
  private boolean allowLocalUsers = true;

  /** Cache TTL for group membership and attributes resolved from Graph (FR-2.5). */
  @JsonProperty("attributeCacheTtlSeconds")
  private int attributeCacheTtlSeconds = 300;

  /**
   * HMAC key for the console session token. No default: a signing key with a
   * fallback value is a signing key that reaches production unchanged, and
   * anyone holding it can mint a PLATFORM_ADMIN token.
   */
  @JsonProperty("jwtSecret")
  private String jwtSecret;

  @JsonProperty("jwtIssuer")
  private String jwtIssuer = "data-access-control";

  /**
   * Session lifetime. Short by default because application roles travel inside
   * the token, so a revoked role stays effective until it expires.
   */
  @JsonProperty("jwtTtlSeconds")
  private int jwtTtlSeconds = 3600;

  @JsonProperty("maxFailedLoginAttempts")
  private int maxFailedLoginAttempts = 5;

  @JsonProperty("lockoutSeconds")
  private int lockoutSeconds = 900;

  /**
   * Password for the bootstrap {@code admin} account, applied on startup only
   * while no local credential exists at all.
   *
   * <p>It lives in the environment rather than in a migration on purpose: a
   * password shipped inside the schema reaches every deployment and every fork
   * of the repository, and cannot be rotated without a new migration. Leaving
   * this unset is a valid choice — the platform then starts with no way to log
   * in locally, which is the right posture once Entra ID is the only door.
   */
  @JsonProperty("bootstrapAdminPassword")
  private String bootstrapAdminPassword;
}
