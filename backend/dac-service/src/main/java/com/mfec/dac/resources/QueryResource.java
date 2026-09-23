package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.policy.QueryService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The query endpoint of enforcement mode 5.2 (FR-6.3, 5.2a).
 *
 * <p>Send SQL, get rows that the policy has already been applied to. Nothing
 * here decides anything: the resource's whole job is to establish who is asking
 * and hand that to {@link QueryService}.
 *
 * <h2>Why a rejection is 403 and not 400</h2>
 *
 * <p>A statement refused by policy is not a malformed request — it is a
 * well-formed one that the caller is not allowed to make, and the difference
 * matters to whoever reads the log afterwards.
 */
@Path("/v1/query")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class QueryResource {

  /**
   * @param asPrincipal run the query as somebody else, for the simulator. It is
   *     the same code path as a real query rather than a preview of it, because
   *     a simulator that approximates enforcement is worse than none: people
   *     trust it and it is wrong.
   */
  public record Ask(
      String sourceId, String sql, String asPrincipal, Integer maxRows, String purpose) {}

  private final QueryService queries;

  public QueryResource(QueryService queries) {
    this.queries = queries;
  }

  @POST
  public Map<String, Object> run(
      Ask ask, @Context SecurityContext security, @Context HttpServletRequest request) {

    if (!(security.getUserPrincipal() instanceof AuthenticatedUser caller)) {
      throw new ForbiddenException("No caller on this request");
    }
    if (ask == null || ask.sql() == null || ask.sql().isBlank()) {
      throw new BadRequestException("Send {\"sourceId\": \"…\", \"sql\": \"SELECT …\"}");
    }
    if (ask.sourceId() == null || ask.sourceId().isBlank()) {
      throw new BadRequestException("Say which source to run against");
    }

    UUID sourceId;
    try {
      sourceId = UUID.fromString(ask.sourceId().trim());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("sourceId must be the UUID of a registered source");
    }

    String principal = caller.getName();
    if (ask.asPrincipal() != null && !ask.asPrincipal().isBlank()) {
      String other = ask.asPrincipal().trim();
      if (!other.equalsIgnoreCase(principal) && !mayImpersonate(caller)) {
        throw new ForbiddenException(
            "Running a query as another principal is reserved for PLATFORM_ADMIN");
      }
      principal = other;
    }

    try {
      QueryService.Result result =
          queries.run(
              sourceId,
              ask.sql(),
              principal,
              ask.maxRows() == null ? 0 : ask.maxRows(),
              clientIp(request),
              ask.purpose());

      Map<String, Object> body = new LinkedHashMap<>();
      body.put("columns", result.columns());
      body.put("columnTypes", result.columnTypes());
      body.put("rows", result.rows());
      body.put("truncated", result.truncated());
      body.put("millis", result.millis());
      body.put("principal", principal);
      body.put("assets", result.assets());
      // The rewritten statement goes back with the rows on purpose: being able
      // to read the SQL that actually ran is what turns "trust us" into
      // something a data owner can check for themselves (FR-5.4).
      body.put("rewrittenSql", result.rewrittenSql());
      // The same decision in words, because a reader who can follow generated
      // SQL is not the only reader this screen has (FR-5.4).
      body.put("explanations", result.explanations());
      body.put("unenforceable", result.unenforceable());
      return body;
    } catch (QueryService.RejectedException e) {
      throw new WebApplicationException(
          Response.status(Response.Status.FORBIDDEN)
              .entity(Map.of("message", e.getMessage()))
              .type(MediaType.APPLICATION_JSON)
              .build());
    }
  }

  /**
   * Who may run a statement under somebody else's name.
   *
   * <p>Platform administrators only. It was four roles, on the argument that
   * verifying a policy before publishing it is the author's job -- but the
   * rows that come back are that principal's rows, so the feature reads data
   * on their behalf, and a policy author holding it can read any table by
   * naming somebody who can. Narrowing it costs an author the shortcut and
   * leaves them the simulator, which answers the same question without
   * returning the data.
   *
   * <p>It is still not a way past a policy: the query is evaluated as the
   * named principal, so an administrator impersonating somebody sees exactly
   * what that person sees and nothing more, and the attempt is audited under
   * the administrator's own name.
   */
  private static boolean mayImpersonate(AuthenticatedUser user) {
    return user.isPlatformAdmin();
  }

  /**
   * The address the connection actually came from.
   *
   * <p>Deliberately not {@code X-Forwarded-For}: a policy can gate on
   * {@code context.ipCidr}, so an IP a caller can set in a header is an IP a
   * caller can use to satisfy a policy. If this is ever put behind a proxy, the
   * proxy has to be the thing that is trusted, configured here explicitly.
   */
  private static String clientIp(HttpServletRequest request) {
    return request == null ? null : request.getRemoteAddr();
  }
}
