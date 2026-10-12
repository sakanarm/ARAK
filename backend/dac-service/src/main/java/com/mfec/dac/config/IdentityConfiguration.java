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

  /**
   * The Entra app registration people sign in through (FR-2.1). Separate from
   * the fields above, which are for reading the directory, because one login
   * registration is shared by several applications so that nobody is asked to
   * consent again for each of them.
   */
  @JsonProperty("entraLoginTenantId")
  private String entraLoginTenantId;

  @JsonProperty("entraLoginClientId")
  private String entraLoginClientId;

  /** A credential reference or the secret itself from the environment; never logged or returned. */
  @JsonProperty("entraLoginClientSecret")
  private String entraLoginClientSecret;

  /**
   * The address Microsoft sends the browser back to, ending in
   * {@code /api/auth/callback/microsoft-entra-id}. It must be registered on
   * the app registration exactly as written here.
   */
  @JsonProperty("entraLoginRedirectUri")
  private String entraLoginRedirectUri;

  @JsonProperty("entraLoginButtonLabel")
  private String entraLoginButtonLabel = "Sign in with Microsoft";

  /**
   * The shared auth service asked, on every Microsoft sign-in, whether this
   * person may use this application. Unset means ARAK's own accounts and roles
   * decide alone.
   */
  @JsonProperty("authServiceUrl")
  private String authServiceUrl;

  /** This application's key for the auth service; never logged or returned. */
  @JsonProperty("authServiceApiKey")
  private String authServiceApiKey;

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

  /** Microsoft sign-in is offered only when everything it needs is set. */
  public boolean isEntraLoginConfigured() {
    return isSet(entraLoginTenantId)
        && isSet(entraLoginClientId)
        && isSet(entraLoginClientSecret)
        && isSet(entraLoginRedirectUri);
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }
}
