package com.mfec.dac.llm;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Checks a gateway address somebody typed before this service calls it.
 *
 * <p>This class exists because of a change in who supplies the address. While
 * only an administrator could set a base URL, the check was "does it start with
 * https" and that was enough — an administrator who wants to send schema names
 * to a host they control does not need a text field to do it. Now every account
 * can set their own, which makes this a server-side request to an address an
 * ordinary user chose, and that is the shape of a server-side request forgery.
 *
 * <p>The asset being protected is not the gateway call itself but the network
 * position this service sits in. It can reach the application database, the
 * OpenMetadata instance, every registered source database and, on a cloud host,
 * the instance metadata endpoint that hands out role credentials to anything
 * that asks. A URL is therefore refused when it names:
 *
 * <ul>
 *   <li>a loopback address — {@code localhost}, {@code 127.0.0.0/8}, {@code ::1}
 *   <li>a link-local address — {@code 169.254.0.0/16} and {@code fe80::/10},
 *       which is where {@code 169.254.169.254} lives
 *   <li>a wildcard or unspecified address
 * </ul>
 *
 * <p>Private ranges ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}) are
 * deliberately <em>allowed</em>. An on-premises LiteLLM or Ollama on the
 * corporate network is the normal case for this product's users, and refusing it
 * would break the feature for the people who asked for it in order to close a
 * hole that the platform switch already closes properly.
 *
 * <p>Two honest limits, so nobody reads more into this than it does:
 *
 * <ol>
 *   <li>The name is resolved here and again by the HTTP client when the call is
 *       made. A name that answers with a public address now and a loopback
 *       address later defeats this check. Closing that needs a custom socket
 *       factory that validates the address it actually connected to, which is
 *       worth doing if this ever faces an untrusted population.
 *   <li>A host that resolves to nothing right now is allowed through, because a
 *       DNS blip should not make somebody's saved setting unwritable. The call
 *       fails at request time with the resolver's own message.
 * </ol>
 *
 * <p>The control that does hold regardless is {@code llm_provider.allow_personal}:
 * an administrator can switch personal gateways off for the whole deployment
 * without touching anybody's stored settings.
 */
public final class LlmGatewayUrl {

  private LlmGatewayUrl() {}

  /**
   * @return null when the address is acceptable, otherwise the reason to show
   */
  public static String validate(String url) {
    if (url == null || url.isBlank()) {
      return "A gateway address is required.";
    }
    URI uri;
    try {
      uri = new URI(url.trim());
    } catch (URISyntaxException e) {
      return "That is not a valid URL.";
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) {
      return "The gateway address must start with https:// or http://";
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      return "The gateway address has no host in it.";
    }
    String lower = host.toLowerCase(Locale.ROOT);
    if (lower.equals("localhost") || lower.endsWith(".localhost")) {
      return "The gateway cannot be on this server itself.";
    }

    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      // Not a refusal. See the class comment: a name that does not resolve at
      // this instant is a network problem, not a policy problem.
      return null;
    }
    for (InetAddress address : addresses) {
      if (address.isLoopbackAddress()) {
        return "The gateway cannot be on this server itself (" + host + " resolves to "
            + address.getHostAddress() + ").";
      }
      if (address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
        return "The gateway cannot be a link-local address (" + host + " resolves to "
            + address.getHostAddress() + ").";
      }
    }
    return null;
  }

  /** The address as it should be stored: trimmed, without a trailing slash. */
  public static String normalise(String url) {
    if (url == null) {
      return null;
    }
    String trimmed = url.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed.isBlank() ? null : trimmed;
  }
}
