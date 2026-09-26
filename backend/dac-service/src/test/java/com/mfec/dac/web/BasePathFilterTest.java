package com.mfec.dac.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

/**
 * The app reached on its own port, where nothing has stripped the prefix.
 *
 * <p>The symptom this exists for is a blank page: the bundle asks for
 * {@code /Arak/assets/...}, nothing answers it, and the page has no script.
 */
class BasePathFilterTest {

  private final BasePathFilter filter = new BasePathFilter("/Arak/");
  private final HttpServletResponse response = mock(HttpServletResponse.class);
  private final FilterChain chain = mock(FilterChain.class);

  @Test
  void anAssetUnderThePrefixIsForwardedWithoutIt() throws Exception {
    HttpServletRequest request = request("GET", "/Arak/assets/index-C0TORp7Z.js", null);
    RequestDispatcher dispatcher = dispatcherFor(request, "/assets/index-C0TORp7Z.js");

    filter.doFilter(request, response, chain);

    verify(dispatcher).forward(request, response);
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void theApiUnderThePrefixReachesTheApi() throws Exception {
    // Not index.html: a POST to the login endpoint answered by the page
    // servlet is a 405, and the sign-in form says nothing useful about it.
    HttpServletRequest request = request("POST", "/Arak/api/v1/auth/login", null);
    RequestDispatcher dispatcher = dispatcherFor(request, "/api/v1/auth/login");

    filter.doFilter(request, response, chain);

    verify(dispatcher).forward(request, response);
  }

  @Test
  void aPathWithoutThePrefixIsLeftAlone() throws Exception {
    // Behind the proxy every request looks like this, and nothing may change.
    HttpServletRequest request = request("GET", "/api/v1/system/version", null);

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verify(request, never()).getRequestDispatcher(anyString());
  }

  @Test
  void aPathThatOnlyStartsWithTheSameLettersIsLeftAlone() throws Exception {
    HttpServletRequest request = request("GET", "/Arakx/assets/a.js", null);

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
  }

  @Test
  void thePrefixWithoutItsSlashRedirectsToTheOneWithIt() throws Exception {
    HttpServletRequest request = request("GET", "/Arak", "tab=policies");

    filter.doFilter(request, response, chain);

    verify(response).sendRedirect("/Arak/?tab=policies");
    verify(chain, never()).doFilter(request, response);
  }

  @Test
  void traceIsRefusedRatherThanForwarded() throws Exception {
    // A forwarded request skips the container's method filter, and a servlet
    // answers TRACE by echoing the request back, cookies and all.
    HttpServletRequest request = request("TRACE", "/Arak/api/v1/system/version", null);

    filter.doFilter(request, response, chain);

    verify(response).sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    verify(request, never()).getRequestDispatcher(anyString());
  }

  @Test
  void theBasePathIsReadWithOrWithoutItsSlashes() throws Exception {
    BasePathFilter bare = new BasePathFilter("Arak");
    HttpServletRequest request = request("GET", "/Arak/login", null);
    RequestDispatcher dispatcher = dispatcherFor(request, "/login");

    bare.doFilter(request, response, chain);

    verify(dispatcher).forward(request, response);
  }

  @Test
  void theRootNeedsNoFilter() {
    assertThat(BasePathFilter.isNeeded("/")).isFalse();
    assertThat(BasePathFilter.isNeeded("")).isFalse();
    assertThat(BasePathFilter.isNeeded(null)).isFalse();
    assertThat(BasePathFilter.isNeeded("/Arak/")).isTrue();
    assertThatThrownBy(() -> new BasePathFilter("/")).isInstanceOf(IllegalArgumentException.class);
  }

  private static HttpServletRequest request(String method, String uri, String query) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn(method);
    when(request.getRequestURI()).thenReturn(uri);
    when(request.getContextPath()).thenReturn("");
    when(request.getQueryString()).thenReturn(query);
    return request;
  }

  private static RequestDispatcher dispatcherFor(HttpServletRequest request, String path) {
    RequestDispatcher dispatcher = mock(RequestDispatcher.class);
    when(request.getRequestDispatcher(path)).thenReturn(dispatcher);
    return dispatcher;
  }
}
