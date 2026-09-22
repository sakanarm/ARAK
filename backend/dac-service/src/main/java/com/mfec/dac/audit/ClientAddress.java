package com.mfec.dac.audit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The caller's address in a form the {@code inet} columns will accept.
 *
 * <p>Lived inside the query audit writer until a second audit trail needed it
 * (V10, identity changes). Every audit table in this schema stores the address
 * the same way, so the normalisation belongs beside them rather than beside one
 * of their writers.
 */
public final class ClientAddress {

  private static final Logger LOG = LoggerFactory.getLogger(ClientAddress.class);

  /** IPv4 dotted-quad or IPv6 hex-and-colons. Deliberately not a hostname. */
  private static final Pattern LITERAL_ADDRESS =
      Pattern.compile("^(?:[0-9.]+|[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*)$");

  private ClientAddress() {}

  /**
   * The client address in a form {@code inet} will accept, or null.
   *
   * <p>Jetty hands back an IPv6 loopback as {@code [0:0:0:0:0:0:0:1]}, brackets
   * and all, and Postgres rejects that — which meant the insert threw, the catch
   * around it logged, and the audit row was lost while the request itself went
   * through. Losing the address is acceptable; losing the row is not, because
   * the row is the compliance record (FR-8.1, FR-8.2, FR-8.3).
   */
  public static String normalise(String clientIp) {
    if (clientIp == null || clientIp.isBlank()) {
      return null;
    }
    String candidate = clientIp.trim();
    if (candidate.startsWith("[") && candidate.endsWith("]")) {
      candidate = candidate.substring(1, candidate.length() - 1);
    }
    // A zone index (fe80::1%eth0) is part of a scoped IPv6 address and is not
    // part of what inet stores.
    int zone = candidate.indexOf('%');
    if (zone > 0) {
      candidate = candidate.substring(0, zone);
    }
    // Only a literal address goes any further. getByName would resolve a
    // hostname against DNS, and an audit write is the last place that should
    // reach the network — so the shape is checked here first.
    if (!LITERAL_ADDRESS.matcher(candidate).matches()) {
      LOG.warn("Dropping a client address that is not an IP literal from the audit row");
      return null;
    }
    try {
      return InetAddress.getByName(candidate).getHostAddress();
    } catch (UnknownHostException e) {
      LOG.warn("Dropping an unparseable client address from the audit row: {}", clientIp);
      return null;
    }
  }
}
