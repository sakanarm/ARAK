package com.mfec.dac.gateway;

import com.mfec.dac.audit.ClientAddress;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes {@code audit_auth_event}: every sign-in through the gateway or through
 * Microsoft, and every token made or ended. The table is append-only (V50);
 * this only inserts.
 */
public class AuthEventLog {

  private static final Logger LOG = LoggerFactory.getLogger(AuthEventLog.class);

  public enum Event {
    GATEWAY_LOGIN,
    TOKEN_CREATE,
    TOKEN_REVOKE,
    /** A sign-in to the console through Microsoft (FR-2.1), let in or refused. */
    SSO_LOGIN
  }

  private final Jdbi jdbi;

  public AuthEventLog(Jdbi jdbi) {
    this.jdbi = jdbi;
  }

  /**
   * @param principalName the account named: for a failed sign-in, whatever the
   *     client claimed, which may not exist
   * @param detail why it failed, in words; never the password or token sent
   */
  public void record(
      Event event,
      boolean success,
      String principalName,
      UUID tokenId,
      String actor,
      String detail,
      String clientApp,
      String clientIp) {
    try {
      jdbi.useHandle(
          handle ->
              handle
                  .createUpdate(
                      """
                      INSERT INTO audit_auth_event (event, outcome, principal_name, token_id,
                                                    actor, detail, client_app, client_ip)
                      VALUES (:event, :outcome, :principal, :token, :actor, :detail, :app,
                              CAST(:ip AS inet))
                      """)
                  .bind("event", event.name())
                  .bind("outcome", success ? "SUCCESS" : "FAILURE")
                  .bind("principal", clip(principalName, 200))
                  .bind("token", tokenId)
                  .bind("actor", clip(actor, 200))
                  .bind("detail", clip(detail, 500))
                  .bind("app", clip(clientApp, 64))
                  .bind("ip", ClientAddress.normalise(clientIp))
                  .execute());
    } catch (Exception e) {
      LOG.error("Could not write the sign-in audit row for {}", principalName, e);
    }
  }

  private static String clip(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() > max ? value.substring(0, max) : value;
  }
}
