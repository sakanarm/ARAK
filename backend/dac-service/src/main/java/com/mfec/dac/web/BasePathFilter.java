package com.mfec.dac.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;

/**
 * Lets the app answer at its mount point as well as at the root.
 *
 * <p>Behind the shared proxy the prefix is stripped before a request arrives,
 * and this filter never fires. Reached on its own port — before anyone has
 * added the proxy route, or from the host itself while checking a deploy — the
 * browser asks for {@code /Arak/assets/...} and {@code /Arak/api/...}, because
 * that is what the bundle was built for. Without this the first is a 404 and
 * the second is index.html, and the page renders nothing at all.
 *
 * <p>A forward rather than a redirect, so that the address the browser shows
 * stays the one the bundle expects; and nothing but a forward, so that a
 * request with the prefix reaches exactly what the same request without it
 * would, through the same authentication.
 */
public final class BasePathFilter implements Filter {

  // The methods the app serves. The rest — TRACE above all, which a servlet
  // answers by echoing the request, headers and cookies included — are refused
  // here, because a forwarded request skips the container's own method filter.
  private static final Set<String> METHODS =
      Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

  private final String prefix;

  /** {@code basePath} as configured, e.g. {@code /Arak/}; it must not be the root. */
  public BasePathFilter(String basePath) {
    String trimmed = basePath.startsWith("/") ? basePath : "/" + basePath;
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("a base path of / needs no filter");
    }
    this.prefix = trimmed;
  }

  /** True when a base path is one this filter has anything to do for. */
  public static boolean isNeeded(String basePath) {
    return basePath != null && !basePath.isBlank() && !basePath.replace("/", "").isEmpty();
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest http = (HttpServletRequest) request;
    String path = http.getRequestURI().substring(http.getContextPath().length());
    if (!path.equals(prefix) && !path.startsWith(prefix + "/")) {
      chain.doFilter(request, response);
      return;
    }
    HttpServletResponse reply = (HttpServletResponse) response;
    if (!METHODS.contains(http.getMethod())) {
      reply.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
      return;
    }
    if (path.equals(prefix)) {
      // "/Arak" without its slash: every relative URL in the page would then
      // resolve against "/", so send the browser to the address with it.
      String query = http.getQueryString();
      reply.sendRedirect(
          http.getContextPath() + prefix + "/" + (query == null ? "" : "?" + query));
      return;
    }
    // The query string is not repeated here: a forward keeps the original one
    // when the target names none, and naming it again doubles every parameter.
    http.getRequestDispatcher(path.substring(prefix.length())).forward(request, response);
  }
}
