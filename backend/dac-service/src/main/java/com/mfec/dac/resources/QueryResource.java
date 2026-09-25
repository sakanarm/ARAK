package com.mfec.dac.resources;

import com.mfec.dac.access.AccessEligibility;
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
  private final AccessEligibility eligibility;

  public QueryResource(QueryService queries) {
    this(queries, null);
  }

  /**
   * @param eligibility answers "would asking the owner help" for a refusal that
   *     names a table; null leaves refusals as bare messages
   */
  public QueryResource(QueryService queries, AccessEligibility eligibility) {
    this.queries = queries;
    this.eligibility = eligibility;
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
              caller.getName(),
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
      Map<String, Object> refusal = new LinkedHashMap<>();
      refusal.put("message", e.getMessage());
      // Whether a corrected statement is worth offering (M26): true for a typo
      // or a construct the proxy cannot read, false for anything a policy said.
      // The console shows "Fix with AI" on the first kind only, so a refusal on
      // access is answered with the owner, never with a rewording.
      refusal.put("fixable", e.aboutStatement());
      // A refusal that names a table is the one refusal with a way forward: the
      // table has an owner, and the engine can say whether a grant from them
      // would open it. Only for the caller's own identity -- an administrator
      // running as somebody else is testing, and a button that files a request
      // in their own name for a table they were never refused would be wrong
      // twice over.
      if (e.deniedAsset() != null && principal.equals(caller.getName()) && eligibility != null) {
        AccessEligibility.Verdict verdict =
            eligibility.check(principal, e.deniedAsset(), clientIp(request), ask.purpose());
        refusal.put("assetFqn", verdict.assetFqn());
        refusal.put("requestable", verdict.requestable());
        refusal.put("blockedBy", verdict.blockedBy());
        refusal.put("approvers", verdict.approvers());
        refusal.put("openRequestId", verdict.openRequestId());
        refusal.put("stranded", verdict.stranded());
        refusal.put("route", verdict.route());
      } else if (e.deniedAsset() != null) {
        refusal.put("assetFqn", e.deniedAsset());
        refusal.put("requestable", false);
      }
      throw new WebApplicationException(
          Response.status(Response.Status.FORBIDDEN)
              .entity(refusal)
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
