package com.mfec.dac.resources;

import com.mfec.dac.access.AccessEligibility;
import com.mfec.dac.access.AccessRequestStore;
import com.mfec.dac.access.AccessReview;
import com.mfec.dac.access.Preauthorization;
import com.mfec.dac.access.RequestStatistics;
import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.auth.Stewardship;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Asking for access to a table, answering the stages of its workflow, and
 * configuring what was approved (FR-7; M9 slice 2a).
 *
 * <p>Open to every signed-in person, because anybody can be refused and
 * anybody may ask. Who may <em>answer</em> or <em>configure</em> is not a
 * console role: it is whoever the workflow's stages and configurers name for
 * that table, or a platform administrator, and {@link AccessRequestStore}
 * checks it on every call rather than trusting the inbox that showed the
 * button.
 */
@Path("/v1/access-requests")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class AccessRequestResource {

  /** At most this many tables in one readability check, which is a suggestion list's worth. */
  static final int MAX_CHECK = 500;

  private final AccessRequestStore requests;
  private final AccessEligibility eligibility;
  private final AccessReview review;
  private final RequestStatistics statistics;
  private final Clock clock;

  public AccessRequestResource(AccessRequestStore requests, AccessEligibility eligibility) {
    this(requests, eligibility, null);
  }

  public AccessRequestResource(
      AccessRequestStore requests, AccessEligibility eligibility, AccessReview review) {
    this(requests, eligibility, review, null, Clock.systemUTC());
  }

  /**
   * @param review null leaves out the review and the check that a grant would
   *     open the table; only tests that exercise neither pass null
   */
  /**
   * @param statistics null leaves out the per-table counts; only tests that do
   *     not read them pass null
   */
  public AccessRequestResource(
      AccessRequestStore requests,
      AccessEligibility eligibility,
      AccessReview review,
      RequestStatistics statistics,
      Clock clock) {
    this.requests = requests;
    this.eligibility = eligibility;
    this.review = review;
    this.statistics = statistics;
    this.clock = clock;
  }

  /**
   * What a requester sends. {@code days} null means "until revoked".
   *
   * @param reference what the table's request template asks to reference, if it does
   * @param kind ASSET (the default) or PREAUTHORIZATION; for a pre-authorization
   *     {@code assetFqn} is the scope and {@code target} names the tables and
   *     the people
   */
  public record Ask(
      String assetFqn,
      String sourceId,
      String reason,
      String purpose,
      Integer days,
      String attemptedSql,
      String deniedBy,
      String reference,
      String kind,
      Preauthorization.Target target) {

    /** An ordinary ask, for one table. */
    public Ask(
        String assetFqn,
        String sourceId,
        String reason,
        String purpose,
        Integer days,
        String attemptedSql,
        String deniedBy,
        String reference) {
      this(assetFqn, sourceId, reason, purpose, days, attemptedSql, deniedBy, reference, null, null);
    }

    /** An ask with no reference, as the form sent before templates. */
    public Ask(
        String assetFqn,
        String sourceId,
        String reason,
        String purpose,
        Integer days,
        String attemptedSql,
        String deniedBy) {
      this(assetFqn, sourceId, reason, purpose, days, attemptedSql, deniedBy, null);
    }
  }

  /**
   * An approver's answer.
   *
   * @param days refused: how long is set when the request is configured
   * @param stageIdx one stage, or null for every stage of the current step the
   *     caller answers for
   */
  public record Decision(Integer days, String note, Integer stageIdx) {}

  /** How an approved request was configured; see {@link AccessRequestStore.Completion}. */
  public record Configure(String fulfilment, Integer days, String policyId, String note) {}

  /** Which tables to check, for the caller only. */
  public record Check(List<String> assetFqns, String purpose) {}

  /** A pre-authorization being filled in: the scope, and which tables for whom. */
  public record Measure(String scopeFqn, Preauthorization.Target target) {}

  /**
   * Opens a request.
   *
   * <p>The engine is asked first whether a grant would open this table for this
   * person. If it would not -- a DENY, or a higher layer that refuses them -- the
   * request is refused here with the policy that is in the way, instead of
   * sitting in an owner's inbox waiting for a "yes" that changes nothing.
   *
   * <p>A pre-authorization skips that question: it is not for the caller, and
   * what fulfils it is a policy the reviewer drafts, not a grant to them.
   */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response create(
      Ask ask, @Context SecurityContext security, @Context HttpServletRequest http) {
    AuthenticatedUser caller = caller(security);
    boolean preauth = ask != null && Preauthorization.KIND.equalsIgnoreCase(trim(ask.kind()));
    if (ask == null || ask.assetFqn() == null || ask.assetFqn().isBlank()) {
      throw new BadRequestException(
          preauth
              ? "Choose the service, database, schema or table it is under"
              : "Name the table you are asking for");
    }
    String fqn = ask.assetFqn().trim();
    UUID sourceId = null;
    if (ask.sourceId() != null && !ask.sourceId().isBlank()) {
      try {
        sourceId = UUID.fromString(ask.sourceId().trim());
      } catch (IllegalArgumentException e) {
        throw new BadRequestException("sourceId must be the UUID of a registered source");
      }
    }

    if (!preauth) {
      AccessEligibility.Verdict verdict =
          eligibility.check(caller.username(), fqn, clientIp(http), ask.purpose());
      if (verdict.readable()) {
        throw conflict("You can already read " + fqn + "; there is nothing to ask for");
      }
      if (!verdict.requestable()) {
        throw conflict(
            "A grant from the owner would not open "
                + fqn
                + " for you, so the request was not sent. Still refusing: "
                + verdict.blockedBy());
      }
    }

    UUID source = sourceId;
    AccessRequestStore.StoredRequest created =
        guarded(
            () ->
                requests.create(
                    new AccessRequestStore.NewRequest(
                        fqn,
                        caller.id(),
                        caller.username(),
                        source,
                        ask.reason(),
                        ask.purpose(),
                        ask.days(),
                        ask.attemptedSql(),
                        ask.deniedBy(),
                        clientIp(http),
                        ask.reference(),
                        preauth ? Preauthorization.KIND : trimOr(ask.kind(), Preauthorization.ASSET),
                        ask.target()),
                    actor(caller)));
    return Response.status(Response.Status.CREATED).entity(created).build();
  }

  /**
   * What a pre-authorization would cover today, while it is being filled in.
   *
   * <p>Counts and table names only, for anybody signed in: the tables are
   * already in the catalog, and the people are counted, never named -- the
   * names are for the reviewer, in the review.
   */
  @POST
  @Path("/preauthorization/coverage")
  @Consumes(MediaType.APPLICATION_JSON)
  public Preauthorization.Coverage coverage(Measure measure, @Context SecurityContext security) {
    caller(security);
    if (review == null) {
      throw new NotFoundException();
    }
    if (measure == null) {
      throw new BadRequestException("Send {\"scopeFqn\": \"…\", \"target\": {…}}");
    }
    return guarded(
        () ->
            review.coverage(
                measure.scopeFqn(), Preauthorization.normalise(measure.target())));
  }

  private static String trim(String s) {
    return s == null ? "" : s.trim();
  }

  private static String trimOr(String s, String fallback) {
    return s == null || s.isBlank() ? fallback : s.trim().toUpperCase(java.util.Locale.ROOT);
  }

  /** What the caller has asked for, newest first. */
  @GET
  @Path("/mine")
  public List<AccessRequestStore.StoredRequest> mine(
      @QueryParam("limit") @DefaultValue("100") int limit, @Context SecurityContext security) {
    return requests.madeBy(actor(caller(security)), limit);
  }

  /** The requests the caller takes part in, those waiting for them first. */
  @GET
  @Path("/inbox")
  public List<AccessRequestStore.StoredRequest> inbox(
      @QueryParam("status") String status,
      @QueryParam("limit") @DefaultValue("100") int limit,
      @Context SecurityContext security) {
    return guarded(() -> requests.decidableBy(actor(caller(security)), status, limit));
  }

  /**
   * Whether the caller can read each of these tables now.
   *
   * <p>The caller's own decisions only -- there is no {@code asPrincipal} here,
   * because a suggestion list that could be asked on somebody else's behalf
   * would be a map of what they can read. The tables themselves are already on
   * the Explorer beside it, so naming them reveals nothing new.
   */
  @POST
  @Path("/check")
  @Consumes(MediaType.APPLICATION_JSON)
  public Map<String, Boolean> check(
      Check check, @Context SecurityContext security, @Context HttpServletRequest http) {
    AuthenticatedUser caller = caller(security);
    if (check == null || check.assetFqns() == null) {
      throw new BadRequestException("Send {\"assetFqns\": [\"…\"]}");
    }
    if (check.assetFqns().size() > MAX_CHECK) {
      throw new BadRequestException("Check at most " + MAX_CHECK + " tables at a time");
    }
    Map<String, Boolean> out = new LinkedHashMap<>();
    for (String fqn : check.assetFqns()) {
      if (fqn != null && !fqn.isBlank() && !out.containsKey(fqn)) {
        out.put(fqn, eligibility.readable(caller.username(), fqn, clientIp(http), check.purpose()));
      }
    }
    return out;
  }

  /** The full answer for one table: readable, requestable, or what is in the way. */
  @GET
  @Path("/eligibility/{fqn: .+}")
  public AccessEligibility.Verdict eligibility(
      @PathParam("fqn") String fqn,
      @QueryParam("purpose") String purpose,
      @Context SecurityContext security,
      @Context HttpServletRequest http) {
    return eligibility.check(caller(security).username(), fqn, clientIp(http), purpose);
  }

  /**
   * The counts across every table in the answer.
   *
   * @param tables how many tables were asked for at least once
   */
  public record StatsTotals(
      int tables, int asked, int open, int completed, int rejected, int declined, int withdrawn) {}

  /**
   * Per-table request counts over the last {@code days}.
   *
   * @param since the start of the window
   * @param total how many tables there are before {@code limit} cut the list
   */
  public record Stats(
      Instant since,
      int days,
      int total,
      StatsTotals totals,
      List<RequestStatistics.TableStats> tables) {}

  /**
   * How often each table is asked for, and how those asks ended (M9 slice 2c).
   *
   * <p>For the people who look after tables, not the people asking for them:
   * an administrator, policy author or auditor counts every table; a data owner
   * counts the tables they own; anyone else gets an empty answer rather than a
   * refusal, so a dashboard card can say "nothing here for you" without the
   * page treating it as an error. Which tables other people keep asking for,
   * and who keeps getting refused, is not a requester's business.
   */
  @GET
  @Path("/stats")
  public Stats stats(
      @QueryParam("days") @DefaultValue("90") int days,
      @QueryParam("assetFqn") String assetFqn,
      @QueryParam("limit") @DefaultValue("50") int limit,
      @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (statistics == null) {
      throw new NotFoundException("Request statistics are not available here");
    }
    if (days < 1 || days > RequestStatistics.MAX_DAYS) {
      throw new BadRequestException(
          "days must be between 1 and " + RequestStatistics.MAX_DAYS + "; got " + days);
    }
    if (limit < 1 || limit > MAX_CHECK) {
      throw new BadRequestException("limit must be between 1 and " + MAX_CHECK + "; got " + limit);
    }
    String fqn = assetFqn == null || assetFqn.isBlank() ? null : assetFqn.trim();
    Instant since = clock.instant().minus(Duration.ofDays(days));
    boolean everything = Stewardship.overseesEverything(caller);
    List<RequestStatistics.TableStats> visible = new ArrayList<>();
    for (RequestStatistics.TableStats table : statistics.perTable(since, fqn)) {
      if (everything || Stewardship.oversees(caller, table.assetFqn())) {
        visible.add(table);
      }
    }
    int asked = 0, open = 0, completed = 0, rejected = 0, declined = 0, withdrawn = 0;
    for (RequestStatistics.TableStats table : visible) {
      asked += table.asked();
      open += table.open();
      completed += table.completed();
      rejected += table.rejected();
      declined += table.declined();
      withdrawn += table.withdrawn();
    }
    return new Stats(
        since,
        days,
        visible.size(),
        new StatsTotals(visible.size(), asked, open, completed, rejected, declined, withdrawn),
        List.copyOf(visible.subList(0, Math.min(limit, visible.size()))));
  }

  /**
   * The header bell: requests for tables the caller decides, answers to the
   * caller's own, how many are new, and the counts the requests page tabs show.
   */
  @GET
  @Path("/notifications")
  public AccessRequestStore.Notices notifications(
      @QueryParam("limit") @DefaultValue("20") int limit, @Context SecurityContext security) {
    return requests.notices(actor(caller(security)), limit);
  }

  /** Marks everything so far as read, for the caller only. */
  @POST
  @Path("/notifications/seen")
  public Response notificationsSeen(@Context SecurityContext security) {
    requests.markNoticesSeen(actor(caller(security)));
    return Response.noContent().build();
  }

  /** One request, for the requester or someone who may decide it. */
  /**
   * One request by its ticket number ({@code REQ-000042}, {@code 42}), for
   * searching and for quoting in an email. Same visibility as by id.
   */
  @GET
  @Path("/ticket/{ticket}")
  public AccessRequestStore.StoredRequest byTicket(
      @PathParam("ticket") String ticket, @Context SecurityContext security) {
    return guarded(() -> requests.findByTicket(ticket, actor(caller(security))));
  }

  @GET
  @Path("/{id}")
  public AccessRequestStore.StoredRequest one(
      @PathParam("id") UUID id, @Context SecurityContext security) {
    return guarded(() -> requests.find(id, actor(caller(security))));
  }

  /**
   * What the people deciding a request should know before they answer it:
   * who asked, what a grant would let them read column by column, the risk,
   * what stands in the way and what they might do instead (M9 slice 2b).
   *
   * <p>Read-only. With {@code policyId}, also what the requester could read
   * with that policy in force -- asked of the engine, never by activating it.
   * The requester gets 403, a stranger the same 404 as the request itself.
   */
  @GET
  @Path("/{id}/review")
  public AccessReview.Review review(
      @PathParam("id") UUID id,
      @QueryParam("policyId") String policyId,
      @Context SecurityContext security) {
    if (review == null) {
      throw new NotFoundException();
    }
    UUID policy = uuidOrNull(policyId);
    return guarded(() -> review.review(id, actor(caller(security)), policy));
  }

  /**
   * Approves the caller's stages of the current step. The last step passing
   * makes the request APPROVED, which is not yet access: it then waits to be
   * configured.
   */
  @POST
  @Path("/{id}/approve")
  @Consumes(MediaType.APPLICATION_JSON)
  public AccessRequestStore.StoredRequest approve(
      @PathParam("id") UUID id, Decision decision, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (decision != null && decision.days() != null) {
      throw new BadRequestException(
          "Approving does not set how long; set it when configuring the request");
    }
    return guarded(
        () ->
            requests.approve(
                id,
                actor(caller),
                decision == null ? null : decision.note(),
                decision == null ? null : decision.stageIdx()));
  }

  /** Rejects the caller's stages of the current step, with a reason the requester reads. */
  @POST
  @Path("/{id}/reject")
  @Consumes(MediaType.APPLICATION_JSON)
  public AccessRequestStore.StoredRequest reject(
      @PathParam("id") UUID id, Decision decision, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    return guarded(
        () ->
            requests.reject(
                id,
                actor(caller),
                decision == null ? null : decision.note(),
                decision == null ? null : decision.stageIdx()));
  }

  /** Takes an approved request to configure it. */
  @POST
  @Path("/{id}/start")
  public AccessRequestStore.StoredRequest start(
      @PathParam("id") UUID id, @Context SecurityContext security) {
    return guarded(() -> requests.start(id, actor(caller(security))));
  }

  /**
   * Records how an approved request was configured: a grant, written here, or
   * a policy the caller changed or wrote, which is only pointed at and never
   * activated from here.
   */
  @POST
  @Path("/{id}/complete")
  @Consumes(MediaType.APPLICATION_JSON)
  public AccessRequestStore.StoredRequest complete(
      @PathParam("id") UUID id, Configure body, @Context SecurityContext security) {
    if (body == null) {
      throw new BadRequestException("Say how it was configured");
    }
    AuthenticatedUser caller = caller(security);
    if ("GRANT".equalsIgnoreCase(body.fulfilment())) {
      refuseAGrantThatWouldNotOpen(id, actor(caller));
    }
    return guarded(
        () ->
            requests.complete(
                id,
                actor(caller),
                new AccessRequestStore.Completion(
                    body.fulfilment(), body.days(), body.policyId(), body.note())));
  }

  /** Refuses to configure an approved request, with a reason the requester reads. */
  @POST
  @Path("/{id}/decline")
  @Consumes(MediaType.APPLICATION_JSON)
  public AccessRequestStore.StoredRequest decline(
      @PathParam("id") UUID id, Decision decision, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    return guarded(
        () -> requests.decline(id, actor(caller), decision == null ? null : decision.note()));
  }

  /** Takes one's own open request back, before or after it was approved. */
  @POST
  @Path("/{id}/withdraw")
  public AccessRequestStore.StoredRequest withdraw(
      @PathParam("id") UUID id, @Context SecurityContext security) {
    return guarded(() -> requests.withdraw(id, actor(caller(security))));
  }

  // --------------------------------------------------------------- plumbing

  /**
   * Refuses to finish a request as a grant when the grant would not let the
   * requester read the table -- a DENY, or a higher layer's policy, still
   * refuses them. Such a grant is not harmless: it waits, and opens the table
   * the day that other policy is relaxed for some unrelated reason, for a
   * request nobody would be deciding then. There is no override; change the
   * policy and finish as "Policy updated", or decline.
   *
   * <p>Only asked of somebody who may configure the request now, so anyone
   * else still gets the store's own answer (not yours, already answered).
   */
  private void refuseAGrantThatWouldNotOpen(UUID id, AccessRequestStore.Actor actor) {
    if (review == null) {
      return;
    }
    AccessRequestStore.StoredRequest request = guarded(() -> requests.find(id, actor));
    boolean configurable =
        "APPROVED".equals(request.status()) || "IN_PROGRESS".equals(request.status());
    if (!configurable || !request.mayConfigure()) {
      return;
    }
    Optional<String> blocker = review.grantWouldNotOpen(request);
    if (blocker.isPresent()) {
      throw conflict(
          "A grant would not let "
              + request.requesterUsername()
              + " read this table. Still refusing: "
              + blocker.get()
              + ". Change that policy and finish as \"Policy updated\", or decline.");
    }
  }

  private static UUID uuidOrNull(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("policyId is not an id");
    }
  }

  static AccessRequestStore.Actor actor(AuthenticatedUser user) {
    return new AccessRequestStore.Actor(user.username(), user.isPlatformAdmin());
  }

  private static <T> T guarded(Supplier<T> call) {
    try {
      return call.get();
    } catch (AccessRequestStore.RequestException e) {
      throw switch (e.kind()) {
        case INVALID -> new BadRequestException(e.getMessage());
        case NOT_FOUND -> new NotFoundException(e.getMessage());
        case FORBIDDEN -> new ForbiddenException(e.getMessage());
        case CONFLICT -> conflict(e.getMessage());
      };
    }
  }

  private static WebApplicationException conflict(String message) {
    return new WebApplicationException(
        Response.status(Response.Status.CONFLICT)
            .entity(Map.of("message", message))
            .type(MediaType.APPLICATION_JSON)
            .build());
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }

  /** The connection's own address; see {@code QueryResource#clientIp} for why not X-Forwarded-For. */
  private static String clientIp(HttpServletRequest request) {
    return request == null ? null : request.getRemoteAddr();
  }
}
