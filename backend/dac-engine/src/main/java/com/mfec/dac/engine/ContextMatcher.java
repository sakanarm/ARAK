package com.mfec.dac.engine;

import com.mfec.dac.schema.entity.policy.ContextRule;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Matches the network and purpose conditions of a subject rule.
 *
 * <p>Both fail closed when the request cannot supply what the rule asks about. A
 * policy restricted to the corporate network must not be satisfied by a request
 * that simply neglected to report an address, and one restricted to a declared
 * purpose must not be satisfied by a caller who declared none. The absent value
 * is the untrusted case, not the neutral one.
 */
public final class ContextMatcher {

  private ContextMatcher() {}

  public static boolean matches(ContextRule rule, RequestContext context) {
    if (rule == null) {
      return true;
    }
    if (notEmpty(rule.getIpCidr()) && !ipAllowed(rule.getIpCidr(), context.ip())) {
      return false;
    }
    return !notEmpty(rule.getPurpose()) || purposeAllowed(rule.getPurpose(), context.purpose());
  }

  private static boolean purposeAllowed(List<String> allowed, String declared) {
    if (declared == null || declared.isBlank()) {
      return false;
    }
    for (String p : allowed) {
      if (p != null && p.equalsIgnoreCase(declared.trim())) {
        return true;
      }
    }
    return false;
  }

  private static boolean ipAllowed(List<String> cidrs, String ip) {
    if (ip == null || ip.isBlank()) {
      return false;
    }
    byte[] address = parse(ip.trim());
    if (address == null) {
      return false;
    }
    for (String cidr : cidrs) {
      if (inCidr(address, cidr)) {
        return true;
      }
    }
    return false;
  }

  /** Handles both IPv4 and IPv6; a bare address is treated as a full-length prefix. */
  static boolean inCidr(byte[] address, String cidr) {
    if (cidr == null || cidr.isBlank()) {
      return false;
    }
    String text = cidr.trim();
    int slash = text.lastIndexOf('/');
    String networkPart = slash < 0 ? text : text.substring(0, slash);
    byte[] network = parse(networkPart);
    if (network == null || network.length != address.length) {
      // Comparing a v4 address against a v6 range is not a match, and it is not
      // an error either - a rule may legitimately list both families.
      return false;
    }
    int prefix = network.length * 8;
    if (slash >= 0) {
      try {
        prefix = Integer.parseInt(text.substring(slash + 1).trim());
      } catch (NumberFormatException e) {
        return false;
      }
    }
    if (prefix < 0 || prefix > network.length * 8) {
      return false;
    }
    int fullBytes = prefix / 8;
    for (int i = 0; i < fullBytes; i++) {
      if (address[i] != network[i]) {
        return false;
      }
    }
    int remainingBits = prefix % 8;
    if (remainingBits == 0) {
      return true;
    }
    int mask = (0xFF << (8 - remainingBits)) & 0xFF;
    return (address[fullBytes] & mask) == (network[fullBytes] & mask);
  }

  private static byte[] parse(String value) {
    // Only literal addresses: resolving a hostname here would let a DNS answer
    // decide an access rule, and would put a network round trip in the hot path.
    if (!value.matches("[0-9a-fA-F:.%\\[\\]]+")) {
      return null;
    }
    try {
      return InetAddress.getByName(value).getAddress();
    } catch (UnknownHostException | SecurityException e) {
      return null;
    }
  }

  private static boolean notEmpty(List<String> list) {
    return list != null && !list.isEmpty();
  }
}
