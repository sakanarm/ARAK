package com.mfec.dac.identity;

import com.mfec.dac.audit.ClientAddress;
import com.mfec.dac.auth.LocalIdentityDao;
import com.mfec.dac.auth.PasswordHasher;
import com.mfec.dac.common.ChangeNotifier;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Writing the parts of identity this platform owns (FR-2.2, FR-2.6).
 *
 * <p>Two different things live here and it is worth saying why they share a
 * class. Creating a local principal is writing to the directory; granting an
 * app role is writing to the platform's own permission table. They belong
 * together because they share the two rules that matter: every change is
 * audited, and no change may leave the deployment without a platform
 * administrator.
 *
 * <p>What this deliberately cannot do is edit a principal that came from
 * somewhere else. Entra and OpenMetadata own their rows, the next sync
 * rewrites them, and an edit here would be undone within the hour without
 * anybody being told. Roles are the exception — {@code app_role_assignment} is
 * ours, so any principal may be granted one, whatever directory they came
 * from.
 */
public class IdentityAdminStore {

  /** Fixed by the CHECK constraint in V2. Re-stated so a bad role fails before the insert. */
  public static final List<String> APP_ROLES =
      List.of("PLATFORM_ADMIN", "POLICY_AUTHOR", "DATA_OWNER", "AUDITOR", "REQUESTER");

  /** Local principals this can create. A GROUP has no password and no login. */
  private static final Set<String> CREATABLE_TYPES = Set.of("USER", "SERVICE");

  /**
   * Usernames that {@code lower()} keeps distinct and a person reading a log
   * can tell apart. No spaces, because the username is what the audit trail
   * and the generated DDL carry.
   */
  private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@-]{1,63}");

  /**
   * What an attribute key may be called.
   *
   * <p>Identifier-shaped, because a key is not just a label: an expression
   * reaches it as {@code user.clearance}, so a key containing a dot or a space
   * is one that parses as something else or does not parse at all. Refusing it
   * here means the rule that cannot be written is refused at the point somebody
   * types the key, rather than at the point somebody writes the policy.
   */
  private static final Pattern ATTRIBUTE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

  /** Long enough for a department name or an FQN, short enough to index. */
  private static final int MAX_ATTRIBUTE_VALUE = 200;

  /**
   * Short enough to type, long enough to be worth 210k PBKDF2 rounds. The
   * upper bound is there because the whole string is hashed and an unbounded
   * one is an invitation to make the login endpoint do arbitrary work.
   */
  private static final int MIN_PASSWORD = 10;

  private static final int MAX_PASSWORD = 200;

  private final Jdbi jdbi;
  private final LocalIdentityDao credentials;
  private final ChangeNotifier changes = new ChangeNotifier();

  /**
   * Announces every write that could change an access decision, so that the
   * decision cache can drop what it is holding (FR-5.5).
   *
   * <p>Published from here rather than from the resource that took the request
   * because writes arrive by more roads than one -- a webhook, a poller, the
   * nightly reconcile -- and an invalidation wired to only some of them is the
   * kind of wrong that never throws.
   */
  public ChangeNotifier changes() {
    return changes;
  }

  public IdentityAdminStore(Jdbi jdbi, LocalIdentityDao credentials) {
    this.jdbi = jdbi;
    this.credentials = credentials;
  }

  // ------------------------------------------------------------------ inputs

  /** One role to hand out, with the scope it applies to. */
  public record RoleRequest(String appRole, String scopeFqn) {}

  /** A local account to create, optionally arriving with its roles already decided. */
  public record NewLocalPrincipal(
      String username,
      String displayName,
      String email,
      String principalType,
      String password,
      List<RoleRequest> roles) {}

  /** A row of the roles screen: who holds what, where, and who said so. */
  public record RoleGrant(
      String id,
      String principalId,
      String username,
      String displayName,
      String principalType,
      String source,
      boolean enabled,
      String appRole,
      String scopeFqn,
      String grantedBy,
      Instant grantedAt) {}

  /** Refused before anything was written: the input itself does not make sense. */
  public static class InvalidPrincipalException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidPrincipalException(String message) {
      super(message);
    }
  }

  /** The input made sense but the directory's current state refuses it. */
  public static class IdentityConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public IdentityConflictException(String message) {
      super(message);
    }
  }

  // ------------------------------------------------------------------ writes

  /**
   * Creates a local account, sets its first password and applies any roles
   * asked for, all in one transaction.
   *
   * <p>One transaction because the three are one act: an account created
   * without its password is an account nobody can use and nobody can see is
   * broken, and an account created without the role it was created for is a
   * person who will be told to log in and find they cannot do the job.
   *
   * <p>The password is always marked {@code must_change}. Whoever types it
   * here knows it, and a password two people know is not a credential.
   */
  public UUID createLocal(NewLocalPrincipal input, String actor, String clientIp) {
    if (input == null) {
      throw new InvalidPrincipalException("Send an account to create");
    }
    String username = required(input.username(), "username").trim();
    if (!USERNAME.matcher(username).matches()) {
      throw new InvalidPrincipalException(
          "A username is 2 to 64 characters of letters, digits, dot, dash, underscore or @");
    }
    String displayName = required(input.displayName(), "display name").trim();
    if (displayName.length() > 200) {
      throw new InvalidPrincipalException("That display name is too long");
    }
    String email = blankToNull(input.email());
    if (email != null && (!email.contains("@") || email.contains(" ") || email.length() > 320)) {
      throw new InvalidPrincipalException("That does not look like an email address");
    }
    String type =
        blankToNull(input.principalType()) == null
            ? "USER"
            : input.principalType().trim().toUpperCase(Locale.ROOT);
    if (!CREATABLE_TYPES.contains(type)) {
      throw new InvalidPrincipalException(
          "A local principal is a USER or a SERVICE account; groups are synced, not created here");
    }
    String password = required(input.password(), "password");
    if (password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
      throw new InvalidPrincipalException(
          "A password is between " + MIN_PASSWORD + " and " + MAX_PASSWORD + " characters");
    }
    if (password.equalsIgnoreCase(username)) {
      throw new InvalidPrincipalException("The password cannot be the username");
    }
    List<RoleRequest> roles = input.roles() == null ? List.of() : input.roles();
    for (RoleRequest role : roles) {
      checkRole(role);
    }

    String ip = ClientAddress.normalise(clientIp);
    UUID created = jdbi.inTransaction(
        handle -> {
          // Checked here as well as by the unique index from V10, so the answer
          // is a sentence rather than a constraint name.
          boolean taken =
              handle
                      .createQuery(
                          """
                          SELECT count(*) FROM principal
                          WHERE source = 'local'
                            AND principal_type <> 'GROUP'
                            AND lower(username) = lower(:username)
                          """)
                      .bind("username", username)
                      .mapTo(Long.class)
                      .one()
                  > 0;
          if (taken) {
            throw new IdentityConflictException(
                "A local account named "
                    + username
                    + " already exists. Names differing only in case count as the same name, "
                    + "because signing in does not distinguish them.");
          }
          UUID id =
              handle
                  .createQuery(
                      """
                      INSERT INTO principal
                          (principal_type, username, email, display_name, source, enabled)
                      VALUES (:type, :username, :email, :displayName, 'local', true)
                      RETURNING id
                      """)
                  .bind("type", type)
                  .bind("username", username)
                  .bind("email", email)
                  .bind("displayName", displayName)
                  .mapTo(UUID.class)
                  .one();

          handle
              .createUpdate(
                  """
                  INSERT INTO local_credential
                      (principal_id, password_hash, must_change, password_changed_at)
                  VALUES (:id, :hash, true, now())
                  """)
              .bind("id", id)
              .bind("hash", PasswordHasher.hash(password.toCharArray()))
              .execute();

          audit(handle, actor, "CREATE_PRINCIPAL", id, username, "local", null, null, null, ip);
          audit(handle, actor, "SET_PASSWORD", id, username, "local", null, null, null, ip);
          for (RoleRequest role : roles) {
            insertGrant(handle, id, username, "local", role, actor, null, ip);
          }
          return id;
        });
    // A name that did not resolve a moment ago now resolves, and the denial
    // cached under it has to go with it.
    changes.fire("local account " + username + " created");
    return created;
  }

  /**
   * Replaces a local account's password.
   *
   * <p>Marked {@code must_change} for the same reason as at creation: this is
   * an administrator handing somebody a password, not a person choosing one.
   */
  public void resetPassword(UUID principalId, String password, String actor, String clientIp) {
    Target target = requireLocal(principalId);
    if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
      throw new InvalidPrincipalException(
          "A password is between " + MIN_PASSWORD + " and " + MAX_PASSWORD + " characters");
    }
    if (password.equalsIgnoreCase(target.username())) {
      throw new InvalidPrincipalException("The password cannot be the username");
    }
    credentials.setPassword(principalId, PasswordHasher.hash(password.toCharArray()), true);
    jdbi.useHandle(
        handle ->
            audit(
                handle,
                actor,
                "SET_PASSWORD",
                principalId,
                target.username(),
                target.source(),
                null,
                null,
                null,
                ClientAddress.normalise(clientIp)));
  }

  /**
   * Turns a local account on or off.
   *
   * <p>Disabling rather than deleting: the audit trail and the
   * {@code granted_by} of every role this person handed out point at a row
   * that has to keep existing.
   */
  public void setEnabled(
      UUID principalId, boolean enabled, String reason, String actor, String clientIp) {
    Target target = requireLocal(principalId);
    String ip = ClientAddress.normalise(clientIp);
    jdbi.useTransaction(
        handle -> {
          if (!enabled) {
            guardLastAdmin(
                handle,
                principalId,
                "Disabling "
                    + target.username()
                    + " would leave the platform with no administrator, and nobody could "
                    + "re-enable them. Grant PLATFORM_ADMIN to somebody else first.");
          }
          handle
              .createUpdate("UPDATE principal SET enabled = :enabled WHERE id = :id")
              .bind("enabled", enabled)
              .bind("id", principalId)
              .execute();
          audit(
              handle,
              actor,
              enabled ? "ENABLE_PRINCIPAL" : "DISABLE_PRINCIPAL",
              principalId,
              target.username(),
              target.source(),
              null,
              null,
              reason,
              ip);
        });
    changes.fire(
        "account " + target.username() + (enabled ? " enabled" : " disabled"));
  }

  /**
   * Grants an app role to any principal, whatever directory they came from.
   *
   * <p>Idempotent: granting a role somebody already holds changes nothing and
   * says so by returning false, rather than failing a screen where two people
   * pressed the same button.
   */
  public boolean grant(
      UUID principalId, RoleRequest role, String reason, String actor, String clientIp) {
    checkRole(role);
    Target target = require(principalId);
    String ip = ClientAddress.normalise(clientIp);
    boolean granted =
        jdbi.inTransaction(
            handle ->
                insertGrant(handle, principalId, target.username(), target.source(), role, actor,
                    reason, ip));
    // Only on a real change. Both of these are idempotent, and a screen where
    // two people pressed the same button should not empty the cache twice.
    if (granted) {
      changes.fire("role " + role.appRole() + " granted to " + target.username());
    }
    return granted;
  }

  /**
   * Withdraws a role.
   *
   * <p>Refuses the one withdrawal that cannot be undone from inside the
   * product: the last global PLATFORM_ADMIN. Everything else on this screen is
   * reversible by somebody; that one locks the door and posts the key through
   * it.
   */
  public boolean revoke(
      UUID principalId, RoleRequest role, String reason, String actor, String clientIp) {
    checkRole(role);
    Target target = require(principalId);
    String scope = blankToNull(role.scopeFqn());
    String ip = ClientAddress.normalise(clientIp);
    boolean revoked =
        jdbi.inTransaction(
        handle -> {
          if ("PLATFORM_ADMIN".equals(role.appRole()) && scope == null) {
            guardLastAdmin(
                handle,
                principalId,
                "This is the last PLATFORM_ADMIN. Removing it would leave nobody able to "
                    + "grant it back, including you. Grant it to somebody else first.");
          }
          int removed =
              handle
                  .createUpdate(
                      """
                      DELETE FROM app_role_assignment
                      WHERE principal_id = :id
                        AND app_role = :role
                        AND scope_fqn IS NOT DISTINCT FROM :scope
                      """)
                  .bind("id", principalId)
                  .bind("role", role.appRole())
                  .bind("scope", scope)
                  .execute();
          if (removed == 0) {
            return false;
          }
          audit(
              handle,
              actor,
              "REVOKE_ROLE",
              principalId,
              target.username(),
              target.source(),
              role.appRole(),
              scope,
              reason,
              ip);
          return true;
        });
    if (revoked) {
      changes.fire("role " + role.appRole() + " revoked from " + target.username());
    }
    return revoked;
  }

  /**
   * Gives somebody an attribute, entered here rather than synced (FR-2.4).
   *
   * <p>This is the half of ABAC a directory usually cannot supply. Entra holds
   * what the HR system happens to carry; it does not hold {@code clearance},
   * and it will not hold the branch a person may see rows for. Without a way to
   * enter those, an attribute rule matches nobody and the failure is silent --
   * the policy saves, activates, and denies everybody.
   *
   * <p>Written as {@code source = 'local'} whoever the principal is, including
   * one synced from Entra or OpenMetadata. That is deliberate and it is what
   * makes it safe: a sync writes only its own rows, so a value entered here is
   * never overwritten by one, and never overwrites one. Somebody may carry
   * {@code department} from both, and both are true -- the engine reads every
   * live row, so the union is what a rule is matched against.
   *
   * <p>Idempotent, and reopens a value that was withdrawn earlier rather than
   * inserting a second row, because the withdrawn row is still there: closing
   * keeps the history a decision made last month has to be explained against.
   *
   * @return false when the principal already carried exactly this value
   */
  public boolean addAttribute(
      UUID principalId, String key, String value, String reason, String actor, String clientIp) {
    Target target = require(principalId);
    String attrKey = checkKey(key);
    String attrValue = checkValue(value);
    String ip = ClientAddress.normalise(clientIp);

    boolean added =
        jdbi.inTransaction(
            handle -> {
              // Three states, not two: absent, live, or withdrawn earlier. The
              // unique constraint spans the closed row as well, so an insert
              // over a withdrawn value would fail rather than restore it.
              Optional<Boolean> live =
                  handle
                      .createQuery(
                          """
                          SELECT valid_to IS NULL FROM principal_attribute
                          WHERE principal_id = :id
                            AND attr_key = :key
                            AND attr_value = :value
                            AND source = 'local'
                          """)
                      .bind("id", principalId)
                      .bind("key", attrKey)
                      .bind("value", attrValue)
                      .mapTo(Boolean.class)
                      .findOne();
              if (live.isPresent() && live.get()) {
                return false;
              }
              if (live.isPresent()) {
                handle
                    .createUpdate(
                        """
                        UPDATE principal_attribute
                           SET valid_to = NULL, valid_from = now()
                         WHERE principal_id = :id
                           AND attr_key = :key
                           AND attr_value = :value
                           AND source = 'local'
                        """)
                    .bind("id", principalId)
                    .bind("key", attrKey)
                    .bind("value", attrValue)
                    .execute();
              } else {
                handle
                    .createUpdate(
                        """
                        INSERT INTO principal_attribute
                            (principal_id, attr_key, attr_value, source)
                        VALUES (:id, :key, :value, 'local')
                        """)
                    .bind("id", principalId)
                    .bind("key", attrKey)
                    .bind("value", attrValue)
                    .execute();
              }
              auditAttribute(
                  handle,
                  actor,
                  "ADD_ATTRIBUTE",
                  principalId,
                  target.username(),
                  target.source(),
                  attrKey,
                  attrValue,
                  reason,
                  ip);
              return true;
            });

    if (added) {
      // An attribute is an input to every cached decision about this person,
      // and the one they are most likely to be waiting on.
      changes.fire(attrKey + " " + attrValue + " added to " + target.username());
    }
    return added;
  }

  /**
   * Withdraws an attribute entered here.
   *
   * <p>Closed with a {@code valid_to} rather than deleted. An access decision
   * made in March was made against the attributes of March, and an audit that
   * replayed it against today's rows would answer a question nobody asked.
   *
   * <p>Only a local row can be withdrawn. A value that arrived from Entra is
   * Entra's to remove; closing it here would leave the next sync to bring it
   * straight back, and the person doing the withdrawing would have no way of
   * knowing that had happened.
   *
   * @return false when the principal was not carrying it
   */
  public boolean removeAttribute(
      UUID principalId, String key, String value, String reason, String actor, String clientIp) {
    Target target = require(principalId);
    String attrKey = checkKey(key);
    String attrValue = checkValue(value);
    String ip = ClientAddress.normalise(clientIp);

    boolean removed =
        jdbi.inTransaction(
            handle -> {
              int closed =
                  handle
                      .createUpdate(
                          """
                          UPDATE principal_attribute
                             SET valid_to = now()
                           WHERE principal_id = :id
                             AND attr_key = :key
                             AND attr_value = :value
                             AND source = 'local'
                             AND valid_to IS NULL
                          """)
                      .bind("id", principalId)
                      .bind("key", attrKey)
                      .bind("value", attrValue)
                      .execute();
              if (closed == 0) {
                // Distinguished from "not there at all", because the two have
                // different remedies and only one of them is this screen's.
                boolean elsewhere =
                    handle
                            .createQuery(
                                """
                                SELECT count(*) FROM principal_attribute
                                WHERE principal_id = :id
                                  AND attr_key = :key
                                  AND attr_value = :value
                                  AND valid_to IS NULL
                                """)
                            .bind("id", principalId)
                            .bind("key", attrKey)
                            .bind("value", attrValue)
                            .mapTo(Long.class)
                            .one()
                        > 0;
                if (elsewhere) {
                  throw new InvalidPrincipalException(
                      attrKey
                          + " "
                          + attrValue
                          + " came from a directory, which owns it. Remove it there -- taking "
                          + "it away here would last until the next sync.");
                }
                return false;
              }
              auditAttribute(
                  handle,
                  actor,
                  "REMOVE_ATTRIBUTE",
                  principalId,
                  target.username(),
                  target.source(),
                  attrKey,
                  attrValue,
                  reason,
                  ip);
              return true;
            });

    if (removed) {
      changes.fire(attrKey + " " + attrValue + " withdrawn from " + target.username());
    }
    return removed;
  }

  // ------------------------------------------------------------------ reads

  /** Every grant in force, for the roles screen. */
  public List<RoleGrant> grants() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT r.id, r.principal_id, p.username, p.display_name, p.principal_type,
                           p.source, p.enabled, r.app_role, r.scope_fqn, r.granted_by,
                           r.granted_at
                    FROM app_role_assignment r
                    JOIN principal p ON p.id = r.principal_id
                    ORDER BY r.app_role, lower(p.display_name), p.username
                    """)
                .map(
                    (rs, ctx) ->
                        new RoleGrant(
                            rs.getString("id"),
                            rs.getString("principal_id"),
                            rs.getString("username"),
                            rs.getString("display_name"),
                            rs.getString("principal_type"),
                            rs.getString("source"),
                            rs.getBoolean("enabled"),
                            rs.getString("app_role"),
                            rs.getString("scope_fqn"),
                            rs.getString("granted_by"),
                            rs.getTimestamp("granted_at") == null
                                ? null
                                : rs.getTimestamp("granted_at").toInstant()))
                .list());
  }

  /**
   * How many enabled principals hold a global PLATFORM_ADMIN.
   *
   * <p>Read by the roles screen so the warning appears before the button is
   * pressed, not after it is refused.
   */
  public int globalAdminCount() {
    return jdbi.withHandle(handle -> countGlobalAdmins(handle, null));
  }

  // ------------------------------------------------------------------ internals

  private record Target(UUID id, String username, String source, boolean enabled) {}

  private Optional<Target> find(UUID principalId) {
    if (principalId == null) {
      return Optional.empty();
    }
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT id, username, source, enabled FROM principal WHERE id = :id")
                .bind("id", principalId)
                .map(
                    (rs, ctx) ->
                        new Target(
                            rs.getObject("id", UUID.class),
                            rs.getString("username"),
                            rs.getString("source"),
                            rs.getBoolean("enabled")))
                .findOne());
  }

  private Target require(UUID principalId) {
    return find(principalId)
        .orElseThrow(() -> new InvalidPrincipalException("No principal " + principalId));
  }

  private Target requireLocal(UUID principalId) {
    Target target = require(principalId);
    if (!"local".equals(target.source())) {
      throw new InvalidPrincipalException(
          target.username()
              + " comes from "
              + target.source()
              + ", which owns that account. Change it there — the next sync would overwrite "
              + "anything changed here.");
    }
    return target;
  }

  private static void checkRole(RoleRequest role) {
    if (role == null || blankToNull(role.appRole()) == null) {
      throw new InvalidPrincipalException("Name the role");
    }
    if (!APP_ROLES.contains(role.appRole())) {
      throw new InvalidPrincipalException("No such role: " + role.appRole());
    }
    String scope = blankToNull(role.scopeFqn());
    // A DATA_OWNER's authority is compared against their scopes, and an owner
    // with none owns nothing (PolicyResource.authorise). Handing out the role
    // with no scope therefore looks like a grant and acts like nothing at all.
    if ("DATA_OWNER".equals(role.appRole()) && scope == null) {
      throw new InvalidPrincipalException(
          "A data owner owns something specific: give the scope it applies to");
    }
    if (!"DATA_OWNER".equals(role.appRole()) && scope != null) {
      throw new InvalidPrincipalException(
          role.appRole() + " applies to the whole platform; only DATA_OWNER takes a scope");
    }
    if (scope != null && scope.length() > 512) {
      throw new InvalidPrincipalException("That scope is too long to be an FQN");
    }
  }

  private static boolean insertGrant(
      Handle handle,
      UUID principalId,
      String username,
      String source,
      RoleRequest role,
      String actor,
      String reason,
      String ip) {
    String scope = blankToNull(role.scopeFqn());
    int inserted =
        handle
            .createUpdate(
                """
                INSERT INTO app_role_assignment (principal_id, app_role, scope_fqn, granted_by)
                VALUES (:id, :role, :scope, :actor)
                ON CONFLICT DO NOTHING
                """)
            .bind("id", principalId)
            .bind("role", role.appRole())
            .bind("scope", scope)
            .bind("actor", actor)
            .execute();
    if (inserted == 0) {
      return false;
    }
    audit(handle, actor, "GRANT_ROLE", principalId, username, source, role.appRole(), scope,
        reason, ip);
    return true;
  }

  /**
   * Refuses a change that would remove the last enabled global administrator.
   *
   * <p>Counted excluding the principal being changed, so the question asked is
   * exactly "who would be left".
   */
  private static void guardLastAdmin(Handle handle, UUID principalId, String message) {
    if (holdsGlobalAdmin(handle, principalId) && countGlobalAdmins(handle, principalId) == 0) {
      throw new IdentityConflictException(message);
    }
  }

  private static boolean holdsGlobalAdmin(Handle handle, UUID principalId) {
    return handle
            .createQuery(
                """
                SELECT count(*) FROM app_role_assignment r
                JOIN principal p ON p.id = r.principal_id
                WHERE r.principal_id = :id
                  AND r.app_role = 'PLATFORM_ADMIN'
                  AND r.scope_fqn IS NULL
                  AND p.enabled = true
                """)
            .bind("id", principalId)
            .mapTo(Integer.class)
            .one()
        > 0;
  }

  private static int countGlobalAdmins(Handle handle, UUID excluding) {
    return handle
        .createQuery(
            """
            SELECT count(*) FROM app_role_assignment r
            JOIN principal p ON p.id = r.principal_id
            WHERE r.app_role = 'PLATFORM_ADMIN'
              AND r.scope_fqn IS NULL
              AND p.enabled = true
              AND (CAST(:excluding AS uuid) IS NULL
                   OR r.principal_id <> CAST(:excluding AS uuid))
            """)
        .bind("excluding", excluding == null ? null : excluding.toString())
        .mapTo(Integer.class)
        .one();
  }

  private static void audit(
      Handle handle,
      String actor,
      String action,
      UUID principalId,
      String username,
      String source,
      String appRole,
      String scopeFqn,
      String reason,
      String ip) {
    handle
        .createUpdate(
            """
            INSERT INTO audit_identity_change
                (actor, action, target_principal_id, target_username, target_source,
                 app_role, scope_fqn, reason, client_ip)
            VALUES (:actor, :action, :target, :username, :source, :role, :scope, :reason,
                    CAST(:ip AS inet))
            """)
        .bind("actor", actor == null ? "unknown" : actor)
        .bind("action", action)
        .bind("target", principalId)
        .bind("username", username)
        .bind("source", source)
        .bind("role", appRole)
        .bind("scope", scopeFqn)
        .bind("reason", blankToNull(reason))
        .bind("ip", ip)
        .execute();
  }

  private static String checkKey(String key) {
    String trimmed = blankToNull(key);
    if (trimmed == null) {
      throw new InvalidPrincipalException("Name the attribute, like clearance or branch");
    }
    if (!ATTRIBUTE_KEY.matcher(trimmed).matches()) {
      throw new InvalidPrincipalException(
          "An attribute name starts with a letter and continues with letters, digits or "
              + "underscores -- it is read in a rule as user."
              + "<name>, so it cannot contain a space or a dot");
    }
    return trimmed;
  }

  private static String checkValue(String value) {
    String trimmed = blankToNull(value);
    if (trimmed == null) {
      // An empty value is not "no attribute": it would be a row that a rule
      // could match on, meaning something nobody intended.
      throw new InvalidPrincipalException("Give the attribute a value, or take it away instead");
    }
    if (trimmed.length() > MAX_ATTRIBUTE_VALUE) {
      throw new InvalidPrincipalException(
          "That value is longer than " + MAX_ATTRIBUTE_VALUE + " characters");
    }
    for (int i = 0; i < trimmed.length(); i++) {
      // A control character in a value is invisible in every screen that shows
      // it, so two values that look identical would not compare equal and the
      // rule that failed would be unreadable.
      if (Character.isISOControl(trimmed.charAt(i))) {
        throw new InvalidPrincipalException("That value contains a character that cannot be shown");
      }
    }
    return trimmed;
  }

  private static void auditAttribute(
      Handle handle,
      String actor,
      String action,
      UUID principalId,
      String username,
      String source,
      String key,
      String value,
      String reason,
      String ip) {
    handle
        .createUpdate(
            """
            INSERT INTO audit_identity_change
                (actor, action, target_principal_id, target_username, target_source,
                 attr_key, attr_value, reason, client_ip)
            VALUES (:actor, :action, :target, :username, :source, :key, :value, :reason,
                    CAST(:ip AS inet))
            """)
        .bind("actor", actor == null ? "unknown" : actor)
        .bind("action", action)
        .bind("target", principalId)
        .bind("username", username)
        .bind("source", source)
        .bind("key", key)
        .bind("value", value)
        .bind("reason", blankToNull(reason))
        .bind("ip", ip)
        .execute();
  }

  private static String required(String value, String what) {
    if (blankToNull(value) == null) {
      throw new InvalidPrincipalException("Give the account a " + what);
    }
    return value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
