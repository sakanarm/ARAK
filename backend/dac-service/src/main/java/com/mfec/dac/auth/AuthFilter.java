package com.mfec.dac.auth;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import java.security.Principal;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a bearer token into a {@link SecurityContext}, or refuses the request.
 *
 * <p>Bound to {@link Secured}, so it runs only where an endpoint asked for it.
 * The two failure modes are kept distinct because they mean different things to
 * the browser: 401 means the token is missing or no longer valid, and the UI
 * should send the user back to the login screen; 403 means the token is fine
 * and this person simply may not do this, and sending them to login again would
 * be an infinite loop.
 */
@Provider
@Secured
@Priority(Priorities.AUTHENTICATION)
public class AuthFilter implements ContainerRequestFilter {

  private static final String BEARER = "Bearer ";

  private final JwtService tokens;

  @Context private ResourceInfo resourceInfo;

  public AuthFilter(JwtService tokens) {
    this.tokens = tokens;
  }

  @Override
  public void filter(ContainerRequestContext request) {
    String header = request.getHeaderString(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(BEARER)) {
      request.abortWith(unauthorized("a bearer token is required"));
      return;
    }

    Optional<AuthenticatedUser> user = tokens.verify(header.substring(BEARER.length()).trim());
    if (user.isEmpty()) {
      request.abortWith(unauthorized("the session token is invalid or has expired"));
      return;
    }

    AuthenticatedUser caller = user.get();
    Set<String> required = requiredRoles();
    if (!required.isEmpty() && !caller.isPlatformAdmin()
        && required.stream().noneMatch(caller.appRoles()::contains)) {
      request.abortWith(
          error(
              Response.Status.FORBIDDEN,
              "this action requires one of: " + String.join(", ", required)));
      return;
    }

    request.setSecurityContext(securityContext(caller, request.getUriInfo().getRequestUri()
        .getScheme()));
  }

  /**
   * The roles declared on the method, falling back to the ones on the class.
   *
   * <p>A method annotation replaces the class one rather than adding to it, so
   * that a resource guarded broadly can open one endpoint to a wider audience
   * without that endpoint inheriting a restriction nobody reading it can see.
   */
  private Set<String> requiredRoles() {
    if (resourceInfo == null) {
      return Set.of();
    }
    Secured onMethod =
        resourceInfo.getResourceMethod() == null
            ? null
            : resourceInfo.getResourceMethod().getAnnotation(Secured.class);
    if (onMethod != null && onMethod.value().length > 0) {
      return Set.copyOf(Arrays.asList(onMethod.value()));
    }
    if (onMethod != null) {
      return Set.of();
    }
    Secured onClass =
        resourceInfo.getResourceClass() == null
            ? null
            : resourceInfo.getResourceClass().getAnnotation(Secured.class);
    return onClass == null ? Set.of() : Set.copyOf(Arrays.asList(onClass.value()));
  }

  private static SecurityContext securityContext(AuthenticatedUser caller, String scheme) {
    return new SecurityContext() {
      @Override
      public Principal getUserPrincipal() {
        return caller;
      }

      @Override
      public boolean isUserInRole(String role) {
        return caller.isPlatformAdmin() || caller.appRoles().contains(role);
      }

      @Override
      public boolean isSecure() {
        return "https".equals(scheme);
      }

      @Override
      public String getAuthenticationScheme() {
        return "Bearer";
      }
    };
  }

  private static Response unauthorized(String message) {
    return Response.status(Response.Status.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"data-access-control\"")
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("code", 401, "message", message))
        .build();
  }

  private static Response error(Response.Status status, String message) {
    return Response.status(status)
        .type(MediaType.APPLICATION_JSON)
        .entity(Map.of("code", status.getStatusCode(), "message", message))
        .build();
  }
}
