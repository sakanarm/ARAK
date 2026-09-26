package com.mfec.dac.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.access.AccessWorkflow.Kind;
import com.mfec.dac.access.AccessWorkflow.OnReject;
import com.mfec.dac.access.AccessWorkflow.Rule;
import com.mfec.dac.access.AccessWorkflow.Seat;
import com.mfec.dac.access.AccessWorkflow.Stage;
import com.mfec.dac.access.AccessWorkflow.Workflow;
import com.mfec.dac.access.ApproverDirectory.Member;
import com.mfec.dac.access.ApproverDirectory.Pool;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asking for access to a table, the approvals it walks, and configuring it.
 *
 * <p>A request walks the {@link WorkflowStore#effective workflow} of its table,
 * copied when it is made so that editing a workflow never changes the rules of
 * a request already on its way. The workflow is a list of stages; stages that
 * share a step run side by side, steps one after another. When a step opens,
 * each of its stages resolves its seats to the people it asks (its pool), and
 * {@link StageEngine} counts their answers against the stage's rule. The last
 * step passing makes the request APPROVED -- which is not yet access. Somebody
 * then configures it by hand: a grant, or a policy they changed or wrote
 * (MANUAL fulfilment; automatic is on the roadmap).
 *
 * <pre>
 *   PENDING --(last step passes)--> APPROVED --start--> IN_PROGRESS --complete--> COMPLETED
 *      |                               |                    |
 *      +--(a stage fails)--> REJECTED  +----(decline)-------+--> REJECTED
 *   any open state --(the requester)--> WITHDRAWN
 * </pre>
 *
 * <h2>Who takes part</h2>
 *
 * <p>The people in a stage's pool answer for that stage; the configurers the
 * workflow names configure it. Platform administrators may answer for any
 * stage and configure any request, so that a chain nobody can finish is not a
 * table nobody can be let into; an administrator answering for a stage they
 * were not asked in overrides it, and the record says so. Nobody takes part in
 * their own request, administrators included: that is the separation of duty
 * FR-2.6 asks for.
 *
 * <p>Somebody who does not take part in a request is told it does not exist,
 * because it carries the SQL somebody tried to run and the reason they gave.
 *
 * <h2>What configuring cannot do</h2>
 *
 * <p>Whatever a grant cannot do (V11): it enters at the TABLE layer and
 * composes by intersection, so it never passes a DENY or a higher layer that
 * refuses the requester. A policy fulfilment only points at a policy; it never
 * activates one.
 */
public class AccessRequestStore {

  private static final Logger LOG = LoggerFactory.getLogger(AccessRequestStore.class);

  /** The longest a request may ask for, and so the longest a grant from it may give. */
  public static final int MAX_DAYS = 365;

  /** The states a request waits in; one per person per table. */
  static final List<String> OPEN = List.of("PENDING", "APPROVED", "IN_PROGRESS");

  static final List<String> STATUSES =
      List.of("PENDING", "APPROVED", "IN_PROGRESS", "COMPLETED", "REJECTED", "WITHDRAWN");

  static final List<String> FULFILMENTS = List.of("GRANT", "POLICY_UPDATED", "POLICY_CREATED");

  private static final TypeReference<List<Member>> MEMBERS = new TypeReference<>() {};
  private static final TypeReference<List<Seat>> SEATS = new TypeReference<>() {};

  private final Jdbi jdbi;
  private final ObjectMapper json;
  private final GrantStore grants;
  private final WorkflowStore workflows;
  private final ApproverDirectory directory;
  private final RequestTemplateStore templates;

  /** Requests asked on the built-in template only, as before templates existed. */
  public AccessRequestStore(
      Jdbi jdbi, ObjectMapper json, GrantStore grants, WorkflowStore workflows) {
    this(jdbi, json, grants, workflows, null);
  }

  /**
   * @param templates what each table's form asks; a request is checked against
   *     its table's template before it is stored. Null = the built-in one
   */
  public AccessRequestStore(
      Jdbi jdbi,
      ObjectMapper json,
      GrantStore grants,
      WorkflowStore workflows,
      RequestTemplateStore templates) {
    this.jdbi = jdbi;
    this.json = json;
    this.grants = grants;
    this.workflows = workflows;
    this.directory = new ApproverDirectory(json);
    this.templates = templates;
  }

  /** Why a request could not be made or moved, and which HTTP answer that is. */
  public static class RequestException extends RuntimeException {

    /** How the resource should report it. */
    public enum Kind {
      INVALID,
      NOT_FOUND,
      CONFLICT,
      FORBIDDEN
    }

    private final Kind kind;

    public RequestException(Kind kind, String message) {
      super(message);
      this.kind = kind;
    }

    public Kind kind() {
      return kind;
    }
  }

  /** An owner of the asset, as OpenMetadata names them. */
  public record Approver(String type, String name, boolean direct, String inheritedFrom) {}

  /** One answer to one stage. */
  public record VoteView(
      String voter, String decision, boolean override, String note, Instant votedAt) {}

  /**
   * One stage of one request, as the reader sees it.
   *
   * @param pool who was asked; empty until the stage's step opens
   * @param fallback no seat resolved to anybody but the requester, so the pool
   *     is the platform administrators
   * @param stranded nobody, or too few, were asked for the rule ever to pass
   *     without an administrator
   * @param mayVote whether the reader may answer this stage now
   */
  public record StageView(
      int idx,
      int step,
      String name,
      String rule,
      Integer minApprovals,
      String onReject,
      List<Seat> approvers,
      List<Member> pool,
      boolean fallback,
      String status,
      Instant openedAt,
      Instant settledAt,
      List<VoteView> votes,
      int approvals,
      int rejections,
      int needed,
      boolean stranded,
      boolean mayVote) {}

  /**
   * A request as stored, and as this reader sees it.
   *
   * @param approvers the asset's owners today, as OpenMetadata records them
   * @param configurerPool who may configure it once approved; empty before
   * @param mayDecide whether the reader may answer a stage now
   * @param mayConfigure whether the reader may start, complete or decline it now
   * @param stranded it waits for nobody: nobody but the requester could move it
   * @param templateName the request template it was asked on; null before templates
   * @param reference what the template asked to reference (a change ticket, a DPIA number)
   */
  public record StoredRequest(
      UUID id,
      String ticket,
      String assetFqn,
      UUID requesterId,
      String requesterUsername,
      UUID dataSourceId,
      String reason,
      String purpose,
      Integer requestedDays,
      String attemptedSql,
      String deniedBy,
      String status,
      Instant createdAt,
      String decidedBy,
      Instant decidedAt,
      String decisionNote,
      UUID grantId,
      String workflowName,
      Integer currentStep,
      String assignee,
      Instant assignedAt,
      String completedBy,
      Instant completedAt,
      String fulfilment,
      String fulfilmentRef,
      String fulfilmentNote,
      List<Seat> configurers,
      List<Member> configurerPool,
      boolean configurersFallback,
      List<StageView> stages,
      List<Approver> approvers,
      boolean mayDecide,
      boolean mayConfigure,
      boolean stranded,
      String templateName,
      String reference) {

    StoredRequest seen(
        List<StageView> stageViews,
        Pool configuring,
        List<Approver> owners,
        boolean decides,
        boolean configures,
        boolean nobodyElse) {
      return new StoredRequest(
          id, ticket, assetFqn, requesterId, requesterUsername, dataSourceId, reason, purpose,
          requestedDays, attemptedSql, deniedBy, status, createdAt, decidedBy, decidedAt,
          decisionNote, grantId, workflowName, currentStep, assignee, assignedAt, completedBy,
          completedAt, fulfilment, fulfilmentRef, fulfilmentNote, configurers,
          configuring == null ? List.of() : configuring.members(),
          configuring != null && configuring.fallback(),
          stageViews, owners, decides, configures, nobodyElse, templateName, reference);
    }

    public boolean pending() {
      return "PENDING".equals(status);
    }

    /** Waiting for somebody: approvers or a configurer. */
    public boolean open() {
      return OPEN.contains(status);
    }
  }

  /** What a requester sends. */
  public record NewRequest(
      String assetFqn,
      UUID requesterId,
      String requesterUsername,
      UUID dataSourceId,
      String reason,
      String purpose,
      Integer requestedDays,
      String attemptedSql,
      String deniedBy,
      String requesterIp,
      String reference) {

    /** A request with no reference, as before templates asked for one. */
    public NewRequest(
        String assetFqn,
        UUID requesterId,
        String requesterUsername,
        UUID dataSourceId,
        String reason,
        String purpose,
        Integer requestedDays,
        String attemptedSql,
        String deniedBy,
        String requesterIp) {
      this(
          assetFqn, requesterId, requesterUsername, dataSourceId, reason, purpose,
          requestedDays, attemptedSql, deniedBy, requesterIp, null);
    }

    /** A request whose address is not known, as before the review needed one. */
    public NewRequest(
        String assetFqn,
        UUID requesterId,
        String requesterUsername,
        UUID dataSourceId,
        String reason,
        String purpose,
        Integer requestedDays,
        String attemptedSql,
        String deniedBy) {
      this(
          assetFqn, requesterId, requesterUsername, dataSourceId, reason, purpose,
          requestedDays, attemptedSql, deniedBy, null, null);
    }
  }

  /**
   * The person reading or acting, reduced to what deciding needs.
   *
   * @param username as they signed in
   * @param platformAdmin whether they hold PLATFORM_ADMIN everywhere
   */
  public record Actor(String username, boolean platformAdmin) {}

  /**
   * How an approved request was configured.
   *
   * @param fulfilment {@code GRANT}, {@code POLICY_UPDATED} or {@code POLICY_CREATED}
   * @param days for a grant: how long; null means what was asked. Shorter than
   *     asked is allowed, longer is not
   * @param policyId for a policy fulfilment: the policy changed or written
   * @param note required for a policy fulfilment: what was changed
   */
  public record Completion(String fulfilment, Integer days, String policyId, String note) {}

  /** One stage of the route a request on a table would walk. */
  public record RouteStage(
      int step, String name, String rule, Integer minApprovals, String onReject, List<String> approvers) {}

  /** The route a request on a table would walk. */
  public record Route(String workflowName, List<RouteStage> stages) {}

  // --------------------------------------------------------------- reading

  /** The asset's owners, direct and inherited, as the crawl last saw them. */
  public List<Approver> approversFor(String assetFqn) {
    return jdbi.withHandle(handle -> owners(handle, List.of(assetFqn)).getOrDefault(assetFqn, List.of()));
  }

  /** The stages a request on this table would walk, as the page names them. */
  public Route route(String assetFqn) {
    Workflow workflow = workflows.effective(assetFqn);
    List<RouteStage> stages = new ArrayList<>();
    for (Stage stage : workflow.stages()) {
      stages.add(
          new RouteStage(
              stage.step(),
              stage.name(),
              stage.rule().name(),
              stage.minApprovals(),
              stage.onReject().name(),
              stage.approvers().stream().map(Seat::describe).toList()));
    }
    return new Route(workflow.name(), List.copyOf(stages));
  }

  /**
   * Whether a request from this person on this asset would wait for nobody.
   *
   * <p>Its first step would open with pools that leave out the requester; when
   * one of them cannot pass by itself and no other administrator exists to
   * answer for it, the request would sit pending forever while the page says
   * somebody decides -- this is the flag that lets the page say so instead.
   */
  public boolean nobodyElseDecides(String requesterUsername, String assetFqn) {
    return jdbi.withHandle(
        handle -> {
          if (otherAdministrator(directory.administrators(handle), requesterUsername)) {
            return false;
          }
          Workflow workflow = workflows.effective(handle, assetFqn);
          int first = workflow.stages().stream().mapToInt(Stage::step).min().orElse(1);
          for (Stage stage : workflow.stages()) {
            if (stage.step() != first) {
              continue;
            }
            Pool pool = directory.pool(handle, stage.approvers(), assetFqn, requesterUsername);
            if (StageEngine.tally(
                    stage.rule(), stage.minApprovals(), stage.onReject(), usernames(pool.members()), List.of())
                .stranded()) {
              return true;
            }
          }
          return false;
        });
  }

  /** The open request this person already has on this asset, if any. */
  /**
   * The address a request was sent from, for the review's decisions only; null
   * when it is not known.
   */
  public String askedFrom(UUID id) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT requester_ip FROM access_request WHERE id = :id")
                .bind("id", id)
                .mapTo(String.class)
                .findOne()
                .orElse(null));
  }

  /**
   * The same person's other requests for the same table, newest first, for a
   * reviewer deciding this one: whether it was asked before and how that ended.
   */
  public List<StoredRequest> earlier(StoredRequest request, int limit) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT * FROM access_request
                    WHERE asset_fqn = :fqn AND lower(requester_username) = lower(:username)
                      AND id <> :id
                    ORDER BY created_at DESC LIMIT :limit
                    """)
                .bind("fqn", request.assetFqn())
                .bind("username", request.requesterUsername())
                .bind("id", request.id())
                .bind("limit", clamp(limit))
                .map(this::map)
                .list());
  }

  public Optional<StoredRequest> openRequest(String assetFqn, String requesterUsername) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT * FROM access_request
                    WHERE asset_fqn = :fqn AND lower(requester_username) = lower(:who)
                      AND status IN (<open>)
                    """)
                .bind("fqn", assetFqn)
                .bind("who", requesterUsername)
                .bindList("open", OPEN)
                .map(this::map)
                .findOne());
  }

  /** What one person has asked for, newest first. */
  public List<StoredRequest> madeBy(Actor actor, int limit) {
    return jdbi.withHandle(
        handle -> {
          List<StoredRequest> rows =
              handle
                  .createQuery(
                      """
                      SELECT * FROM access_request
                      WHERE lower(requester_username) = lower(:who)
                      ORDER BY created_at DESC
                      LIMIT :limit
                      """)
                  .bind("who", actor.username())
                  .bind("limit", clamp(limit))
                  .map(this::map)
                  .list();
          return view(handle, rows, actor, false).stream().map(Viewed::request).toList();
        });
  }

  /**
   * The requests this person takes part in, other than their own: those they
   * may answer or configure now first, then the rest, newest first.
   *
   * <p>Filtered after reading rather than in SQL because taking part is pool
   * membership as resolved when each step opened, plus the configurers, plus
   * the administrators -- and writing that a second time in SQL is how the
   * inbox and the approval check would one day disagree.
   *
   * @param status one status, or null for every status
   */
  public List<StoredRequest> decidableBy(Actor actor, String status, int limit) {
    String wanted = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
    if (wanted != null && !STATUSES.contains(wanted)) {
      throw new RequestException(RequestException.Kind.INVALID, "Unknown status " + status);
    }
    return jdbi.withHandle(
        handle -> {
          List<StoredRequest> rows =
              handle
                  .createQuery(
                      """
                      SELECT * FROM access_request
                      WHERE (CAST(:status AS text) IS NULL OR status = :status)
                        AND lower(requester_username) <> lower(:who)
                      ORDER BY created_at DESC
                      LIMIT 2000
                      """)
                  .bind("status", wanted)
                  .bind("who", actor.username())
                  .map(this::map)
                  .list();
          List<StoredRequest> mine = new ArrayList<>();
          for (Viewed one : view(handle, rows, actor, false)) {
            if (one.involved()) {
              mine.add(one.request());
            }
          }
          // Stable: within each group the newest stays first.
          mine.sort(Comparator.comparing(r -> !(r.mayDecide() || r.mayConfigure())));
          return List.copyOf(mine.subList(0, Math.min(clamp(limit), mine.size())));
        });
  }

  /** One request, for whoever takes part in it; anybody else is told it does not exist. */
  public StoredRequest find(UUID id, Actor actor) {
    return jdbi.withHandle(
        handle -> {
          StoredRequest row = load(handle, id, false);
          Viewed seen = view(handle, List.of(row), actor, false).get(0);
          if (!seen.involved()) {
            throw notFound(id);
          }
          return seen.request();
        });
  }

  /** How a ticket number is written: {@code REQ-000042}. */
  public static String ticket(long number) {
    return String.format(Locale.ROOT, "REQ-%06d", number);
  }

  private static final Pattern TICKET =
      Pattern.compile("^#?\\s*(?:REQ[\\s-]*)?0*(\\d{1,12})$", Pattern.CASE_INSENSITIVE);

  /**
   * The number in what somebody typed: {@code REQ-000042}, {@code req-42},
   * {@code #42} or {@code 42}. Empty when it is not a ticket number at all.
   */
  public static OptionalLong ticketNumber(String typed) {
    if (typed == null) {
      return OptionalLong.empty();
    }
    Matcher m = TICKET.matcher(typed.trim());
    if (!m.matches()) {
      return OptionalLong.empty();
    }
    long number = Long.parseLong(m.group(1));
    return number > 0 ? OptionalLong.of(number) : OptionalLong.empty();
  }

  /**
   * One request by its ticket number, with exactly the visibility of {@link
   * #find}: a number that exists but is not the reader's business is told
   * apart from one that does not exist by nothing, not even the message.
   */
  public StoredRequest findByTicket(String typed, Actor actor) {
    long number =
        ticketNumber(typed)
            .orElseThrow(
                () ->
                    new RequestException(
                        RequestException.Kind.INVALID,
                        "A ticket number looks like REQ-000042."));
    RequestException missing =
        new RequestException(
            RequestException.Kind.NOT_FOUND, "No access request " + ticket(number));
    Optional<UUID> id =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery("SELECT id FROM access_request WHERE ticket_no = :number")
                    .bind("number", number)
                    .mapTo(UUID.class)
                    .findOne());
    if (id.isEmpty()) {
      throw missing;
    }
    try {
      return find(id.get(), actor);
    } catch (RequestException e) {
      // find names the UUID; a stranger holding only the number must not
      // learn it.
      if (e.kind() == RequestException.Kind.NOT_FOUND) {
        throw missing;
      }
      throw e;
    }
  }

  // --------------------------------------------------------------- asking

  /**
   * Opens a request, and with it the first step of its table's workflow.
   *
   * <p>One open request per person per asset: asking twice is the same question
   * and must not queue twice, so the second attempt is a conflict that names
   * the first.
   */
  public StoredRequest create(NewRequest request, Actor actor) {
    String fqn = request.assetFqn() == null ? "" : request.assetFqn().trim();
    if (fqn.isEmpty()) {
      throw new RequestException(RequestException.Kind.INVALID, "Name the table you are asking for");
    }
    if (request.reason() == null || request.reason().isBlank()) {
      throw new RequestException(
          RequestException.Kind.INVALID,
          "Say why you need it; the approvers decide on what you write here");
    }
    if (request.requestedDays() != null
        && (request.requestedDays() < 1 || request.requestedDays() > MAX_DAYS)) {
      throw new RequestException(
          RequestException.Kind.INVALID, "Ask for between 1 and " + MAX_DAYS + " days, or leave it open");
    }
    if (request.requesterId() == null) {
      throw new RequestException(RequestException.Kind.INVALID, "No requester on this request");
    }

    StoredRequest created;
    try {
      created = insert(request, actor, fqn);
    } catch (UnableToExecuteStatementException e) {
      // Two clicks that both passed the lookup below before either committed:
      // the partial unique index lets one through and refuses the other, and
      // the other is the same "already open" answer, not a server error.
      if (String.valueOf(e.getMessage()).contains("access_request_one_open_idx")) {
        throw new RequestException(
            RequestException.Kind.CONFLICT, "You already have an open request for " + fqn);
      }
      throw e;
    }
    LOG.info(
        "Access request {} on {} by {} walks {}",
        created.id(),
        created.assetFqn(),
        created.requesterUsername(),
        created.workflowName());
    return created;
  }

  private StoredRequest insert(NewRequest request, Actor actor, String fqn) {
    return jdbi.inTransaction(
        handle -> {
          boolean exists =
              handle
                  .createQuery(
                      """
                      SELECT count(*) > 0 FROM asset
                      WHERE fqn = :fqn AND is_current AND asset_type IN ('TABLE', 'VIEW')
                      """)
                  .bind("fqn", fqn)
                  .mapTo(Boolean.class)
                  .one();
          if (!exists) {
            throw new RequestException(
                RequestException.Kind.INVALID,
                fqn + " is not a table or view in the catalog, so there is nothing to grant");
          }

          Optional<UUID> open =
              handle
                  .createQuery(
                      """
                      SELECT id FROM access_request
                      WHERE asset_fqn = :fqn AND requester_id = :who AND status IN (<open>)
                      """)
                  .bind("fqn", fqn)
                  .bind("who", request.requesterId())
                  .bindList("open", OPEN)
                  .mapTo(UUID.class)
                  .findOne();
          if (open.isPresent()) {
            throw new RequestException(
                RequestException.Kind.CONFLICT,
                "You already have an open request for " + fqn + " (" + open.get() + ")");
          }

          // The form is rendered from this template; this is the check behind
          // it, so a request sent around the form meets the same rules.
          RequestTemplate.Template template =
              templates == null ? RequestTemplate.builtIn() : templates.effective(handle, fqn);
          String problem =
              RequestTemplate.check(
                  template.form(),
                  request.reason(),
                  request.purpose(),
                  request.requestedDays(),
                  request.reference());
          if (problem != null) {
            throw new RequestException(RequestException.Kind.INVALID, problem);
          }

          Workflow workflow = workflows.effective(handle, fqn);
          UUID id =
              handle
                  .createQuery(
                      """
                      INSERT INTO access_request
                        (asset_fqn, requester_id, requester_username, data_source_id,
                         reason, purpose, requested_days, attempted_sql, denied_by,
                         workflow_id, workflow_name, configurers, current_step, requester_ip,
                         template_id, template_name, reference)
                      VALUES
                        (:fqn, :who, :username, :source, :reason, :purpose, :days,
                         :sql, :deniedBy, :workflowId, :workflowName,
                         CAST(:configurers AS jsonb), :step, :ip,
                         :templateId, :templateName, :reference)
                      RETURNING id
                      """)
                  .bind("fqn", fqn)
                  .bind("who", request.requesterId())
                  .bind("username", request.requesterUsername())
                  .bind("source", request.dataSourceId())
                  .bind("reason", request.reason().trim())
                  .bind("purpose", RequestTemplate.canonicalPurpose(template.form(), request.purpose()))
                  .bind("days", request.requestedDays())
                  .bind("sql", truncate(request.attemptedSql(), 20_000))
                  .bind("deniedBy", truncate(request.deniedBy(), 2_000))
                  .bind("workflowId", workflow.id())
                  .bind("workflowName", workflow.name())
                  .bind("configurers", write(workflow.configurers()))
                  .bind("step", firstStep(workflow))
                  .bind("ip", truncate(request.requesterIp(), 64))
                  .bind("templateId", template.id())
                  .bind("templateName", template.builtIn() ? null : template.name())
                  .bind("reference", template.form().asksReference() ? RequestTemplate.trim(request.reference()) : null)
                  .mapTo(UUID.class)
                  .one();

          int idx = 0;
          for (Stage stage : workflow.stages()) {
            handle
                .createUpdate(
                    """
                    INSERT INTO access_request_stage
                      (request_id, idx, step, name, rule, min_approvals, on_reject, approvers)
                    VALUES (:id, :idx, :step, :name, :rule, :min, :onReject, CAST(:approvers AS jsonb))
                    """)
                .bind("id", id)
                .bind("idx", idx++)
                .bind("step", stage.step())
                .bind("name", stage.name())
                .bind("rule", stage.rule().name())
                .bind("min", stage.rule() == Rule.AT_LEAST ? stage.minApprovals() : null)
                .bind("onReject", stage.onReject().name())
                .bind("approvers", write(stage.approvers()))
                .execute();
          }
          StoredRequest row = load(handle, id, false);
          openStep(handle, row, firstStep(workflow));
          audit(handle, "REQUEST", request.requesterUsername(), row, null, row.reason(), firstStep(workflow));
          return view(handle, List.of(load(handle, id, false)), actor, true).get(0).request();
        });
  }

  private static int firstStep(Workflow workflow) {
    return workflow.stages().stream().mapToInt(Stage::step).min().orElse(1);
  }

  /** Opens every stage of one step: resolves who is asked, now, and fixes it. */
  private void openStep(Handle handle, StoredRequest row, int step) {
    for (StageRow stage : stageRows(handle, List.of(row.id())).getOrDefault(row.id(), List.of())) {
      if (stage.step() != step) {
        continue;
      }
      Pool pool = directory.pool(handle, stage.approvers(), row.assetFqn(), row.requesterUsername());
      handle
          .createUpdate(
              """
              UPDATE access_request_stage
                 SET status = 'OPEN', opened_at = now(), pool = CAST(:pool AS jsonb),
                     fallback = :fallback
               WHERE request_id = :id AND idx = :idx
              """)
          .bind("pool", write(pool.members()))
          .bind("fallback", pool.fallback())
          .bind("id", row.id())
          .bind("idx", stage.idx())
          .execute();
    }
  }

  // --------------------------------------------------------------- answering

  /**
   * Approves the stages of the current step this person answers for.
   *
   * @param note optional; the requester reads it
   * @param stageIdx one stage, or null for every stage of the step this person
   *     may answer -- somebody asked in two parallel stages answers both
   */
  public StoredRequest approve(UUID id, Actor actor, String note, Integer stageIdx) {
    return vote(id, actor, true, note, stageIdx);
  }

  /** Rejects the stages of the current step this person answers for. A reason is required. */
  public StoredRequest reject(UUID id, Actor actor, String note) {
    return reject(id, actor, note, null);
  }

  /** Rejects one stage, or every stage of the current step this person answers for. */
  public StoredRequest reject(UUID id, Actor actor, String note, Integer stageIdx) {
    return vote(id, actor, false, note, stageIdx);
  }

  private StoredRequest vote(UUID id, Actor actor, boolean approve, String note, Integer stageIdx) {
    if (!approve && blankToNull(note) == null) {
      throw new RequestException(
          RequestException.Kind.INVALID,
          "Say why; the requester reads this and it is the only answer they get");
    }
    StoredRequest decided =
        jdbi.inTransaction(
            handle -> {
              StoredRequest row = load(handle, id, true);
              if (same(row.requesterUsername(), actor.username())) {
                throw new RequestException(
                    RequestException.Kind.FORBIDDEN,
                    "Nobody decides their own request; the approvers or an administrator have to");
              }
              Viewed seen = view(handle, List.of(row), actor, true).get(0);
              if (!seen.involved()) {
                throw notFound(id);
              }
              if (!row.pending() || row.currentStep() == null) {
                throw new RequestException(RequestException.Kind.CONFLICT, already(row));
              }
              int step = row.currentStep();

              List<StageView> open =
                  seen.request().stages().stream()
                      .filter(s -> s.step() == step && "OPEN".equals(s.status()))
                      .toList();
              if (stageIdx != null) {
                StageView target =
                    seen.request().stages().stream()
                        .filter(s -> s.idx() == stageIdx)
                        .findFirst()
                        .orElseThrow(
                            () ->
                                new RequestException(
                                    RequestException.Kind.INVALID,
                                    "This request has no stage " + stageIdx));
                if (!open.contains(target)) {
                  throw new RequestException(
                      RequestException.Kind.CONFLICT,
                      "\"" + target.name() + "\" is not waiting for answers; it is "
                          + target.status().toLowerCase(Locale.ROOT));
                }
                open = List.of(target);
              }
              List<StageView> mine = open.stream().filter(StageView::mayVote).toList();
              if (mine.isEmpty()) {
                List<String> answered =
                    open.stream().filter(s -> votedBy(s, actor.username())).map(StageView::name).toList();
                if (!answered.isEmpty()) {
                  throw new RequestException(
                      RequestException.Kind.CONFLICT,
                      "You already answered " + String.join(", ", answered));
                }
                throw new RequestException(
                    RequestException.Kind.FORBIDDEN,
                    "This step waits for "
                        + String.join(
                            "; ",
                            open.stream()
                                .map(s -> s.name() + " (" + describe(s.approvers()) + ")")
                                .toList()));
              }

              for (StageView stage : mine) {
                boolean override = !contains(stage.pool(), actor.username());
                handle
                    .createUpdate(
                        """
                        INSERT INTO access_request_vote (request_id, idx, voter, decision, override, note)
                        VALUES (:id, :idx, :voter, :decision, :override, :note)
                        """)
                    .bind("id", id)
                    .bind("idx", stage.idx())
                    .bind("voter", actor.username())
                    .bind("decision", approve ? "APPROVE" : "REJECT")
                    .bind("override", override)
                    .bind("note", blankToNull(note))
                    .execute();
                audit(
                    handle,
                    "VOTE",
                    actor.username(),
                    row,
                    null,
                    stage.name() + ": " + (approve ? "approved" : "rejected")
                        + (override ? " for the approvers" : "")
                        + (blankToNull(note) == null ? "" : " — " + note.trim()),
                    step);
              }
              settle(handle, row, actor, step, note);
              return view(handle, List.of(load(handle, id, false)), actor, true).get(0).request();
            });
    LOG.info("Access request {} answered by {}: now {}", id, actor.username(), decided.status());
    return decided;
  }

  /** Counts the current step again and moves the request on if it settled. */
  private void settle(Handle handle, StoredRequest row, Actor actor, int step, String note) {
    Ctx ctx = contexts(handle, List.of(row), true).get(row.id());
    List<StageEngine.Outcome> outcomes = new ArrayList<>();
    boolean more = false;
    for (StageRow stage : ctx.stages()) {
      if (stage.step() > step) {
        more = true;
      }
      if (stage.step() != step) {
        continue;
      }
      StageEngine.Outcome outcome =
          switch (stage.status()) {
            case "APPROVED" -> StageEngine.Outcome.APPROVED;
            case "REJECTED" -> StageEngine.Outcome.REJECTED;
            default -> tally(stage, ctx.votes()).outcome();
          };
      if ("OPEN".equals(stage.status()) && outcome != StageEngine.Outcome.OPEN) {
        handle
            .createUpdate(
                """
                UPDATE access_request_stage SET status = :status, settled_at = now()
                 WHERE request_id = :id AND idx = :idx
                """)
            .bind("status", outcome.name())
            .bind("id", row.id())
            .bind("idx", stage.idx())
            .execute();
      }
      outcomes.add(outcome);
    }

    switch (StageEngine.next(outcomes, more)) {
      case WAIT -> {}
      case REJECT -> {
        handle
            .createUpdate(
                """
                UPDATE access_request
                   SET status = 'REJECTED', decided_by = :by, decided_at = now(),
                       decision_note = :note, current_step = NULL
                 WHERE id = :id
                """)
            .bind("by", actor.username())
            .bind("note", blankToNull(note))
            .bind("id", row.id())
            .execute();
        closeStages(handle, row.id());
        audit(handle, "REJECT", actor.username(), row, null, blankToNull(note), step);
      }
      case ADVANCE -> {
        int next = step + 1;
        handle
            .createUpdate("UPDATE access_request SET current_step = :step WHERE id = :id")
            .bind("step", next)
            .bind("id", row.id())
            .execute();
        openStep(handle, row, next);
        audit(handle, "ADVANCE", actor.username(), row, null, null, next);
      }
      case APPROVE -> {
        List<Seat> seats = row.configurers().isEmpty() ? AccessWorkflow.defaultConfigurers() : row.configurers();
        Pool configuring = directory.pool(handle, seats, row.assetFqn(), row.requesterUsername());
        handle
            .createUpdate(
                """
                UPDATE access_request
                   SET status = 'APPROVED', decided_by = :by, decided_at = now(),
                       decision_note = :note, current_step = NULL,
                       configurer_pool = CAST(:pool AS jsonb)
                 WHERE id = :id
                """)
            .bind("by", actor.username())
            .bind("note", blankToNull(note))
            .bind("pool", write(configuring))
            .bind("id", row.id())
            .execute();
        audit(handle, "APPROVE", actor.username(), row, null, blankToNull(note), step);
      }
    }
  }

  private static void closeStages(Handle handle, UUID id) {
    handle
        .createUpdate(
            """
            UPDATE access_request_stage SET status = 'CLOSED', settled_at = now()
             WHERE request_id = :id AND status IN ('WAITING', 'OPEN')
            """)
        .bind("id", id)
        .execute();
  }

  // ------------------------------------------------------------ configuring

  /**
   * Takes an approved request to configure it, so the other configurers see
   * somebody has it. Taking one's own again is harmless.
   */
  public StoredRequest start(UUID id, Actor actor) {
    return jdbi.inTransaction(
        handle -> {
          StoredRequest row = load(handle, id, true);
          if ("IN_PROGRESS".equals(row.status())) {
            Viewed seen = visible(handle, row, actor);
            if (same(row.assignee(), actor.username())) {
              return seen.request();
            }
            throw new RequestException(
                RequestException.Kind.CONFLICT, row.assignee() + " is already configuring this request");
          }
          configurable(handle, row, actor);
          handle
              .createUpdate(
                  """
                  UPDATE access_request
                     SET status = 'IN_PROGRESS', assignee = :by, assigned_at = now()
                   WHERE id = :id
                  """)
              .bind("by", actor.username())
              .bind("id", id)
              .execute();
          audit(handle, "START", actor.username(), row, null, null, null);
          return view(handle, List.of(load(handle, id, false)), actor, true).get(0).request();
        });
  }

  /**
   * Records how an approved request was configured and closes it.
   *
   * <p>A grant is written here, in the same transaction. A policy is only
   * pointed at: whoever configures changed or wrote it on the policy pages,
   * where it goes through review and activation like any other; completing a
   * request never activates one.
   */
  public StoredRequest complete(UUID id, Actor actor, Completion what) {
    String kind =
        what == null || what.fulfilment() == null
            ? null
            : what.fulfilment().trim().toUpperCase(Locale.ROOT);
    if (kind == null || !FULFILMENTS.contains(kind)) {
      throw new RequestException(
          RequestException.Kind.INVALID,
          "Say how it was configured: a grant, a policy you updated, or a policy you created");
    }
    GrantStore.StoredGrant[] made = new GrantStore.StoredGrant[1];
    StoredRequest done =
        jdbi.inTransaction(
            handle -> {
              StoredRequest row = load(handle, id, true);
              configurable(handle, row, actor);
              Instant now = Instant.now();
              UUID grantId = null;
              String ref = null;
              String note = blankToNull(what.note());
              if ("GRANT".equals(kind)) {
                Integer asked = row.requestedDays();
                Integer granted = what.days() == null ? asked : what.days();
                if (granted != null && (granted < 1 || granted > MAX_DAYS)) {
                  throw new RequestException(
                      RequestException.Kind.INVALID, "Grant for between 1 and " + MAX_DAYS + " days");
                }
                if (asked != null && (granted == null || granted > asked)) {
                  throw new RequestException(
                      RequestException.Kind.INVALID,
                      "The request asked for " + asked + " days; a grant can shorten that, not extend it");
                }
                if (row.requesterId() == null) {
                  throw new RequestException(
                      RequestException.Kind.CONFLICT,
                      row.requesterUsername() + " is no longer in the directory; nothing to grant to");
                }
                Instant until = granted == null ? null : now.plus(Duration.ofDays(granted));
                String why =
                    "Access request " + row.id() + ": " + row.reason()
                        + " — approved by " + row.decidedBy()
                        + (row.decisionNote() == null ? "" : " (" + row.decisionNote() + ")")
                        + (note == null ? "" : "; " + note);
                made[0] =
                    grants.grantForRequest(
                        handle,
                        new GrantStore.NewGrant(
                            row.assetFqn(), row.requesterId(), now, until, why, actor.username()),
                        row.id());
                grantId = made[0].id();
              } else {
                if (what.days() != null) {
                  throw new RequestException(
                      RequestException.Kind.INVALID,
                      "A policy has no length here; set its validity on the policy itself");
                }
                if (note == null) {
                  throw new RequestException(
                      RequestException.Kind.INVALID,
                      "Say what you changed in the policy; the requester and the auditors read it");
                }
                ref = existingPolicy(handle, what.policyId());
              }
              handle
                  .createUpdate(
                      """
                      UPDATE access_request
                         SET status = 'COMPLETED', fulfilment = :kind, fulfilment_ref = :ref,
                             fulfilment_note = :note, grant_id = :grant,
                             completed_by = :by, completed_at = :at,
                             assignee = coalesce(assignee, :by),
                             assigned_at = coalesce(assigned_at, :at)
                       WHERE id = :id
                      """)
                  .bind("kind", kind)
                  .bind("ref", ref)
                  .bind("note", note)
                  .bind("grant", grantId)
                  .bind("by", actor.username())
                  .bind("at", now)
                  .bind("id", id)
                  .execute();
              audit(
                  handle,
                  "COMPLETE",
                  actor.username(),
                  row,
                  grantId,
                  "GRANT".equals(kind) ? note : kind + " " + ref + ": " + note,
                  null);
              return view(handle, List.of(load(handle, id, false)), actor, true).get(0).request();
            });
    // After the commit, never inside it: announcing a grant the requester
    // cannot yet read would clear caches for access that does not exist yet.
    if (made[0] != null) {
      grants.announce(made[0]);
    }
    LOG.info("Access request {} completed by {} as {}", id, actor.username(), kind);
    return done;
  }

  /**
   * Refuses to configure an approved request: the approvers said yes, but the
   * person who would set it up found it cannot or should not be done. A reason
   * is required; the requester reads it.
   */
  public StoredRequest decline(UUID id, Actor actor, String note) {
    if (blankToNull(note) == null) {
      throw new RequestException(
          RequestException.Kind.INVALID,
          "Say why; the requester reads this and it is the only answer they get");
    }
    return jdbi.inTransaction(
        handle -> {
          StoredRequest row = load(handle, id, true);
          configurable(handle, row, actor);
          handle
              .createUpdate(
                  """
                  UPDATE access_request
                     SET status = 'REJECTED', completed_by = :by, completed_at = now(),
                         fulfilment_note = :note
                   WHERE id = :id
                  """)
              .bind("by", actor.username())
              .bind("note", note.trim())
              .bind("id", id)
              .execute();
          audit(handle, "REJECT", actor.username(), row, null, note.trim(), null);
          return view(handle, List.of(load(handle, id, false)), actor, true).get(0).request();
        });
  }

  /** The request, if this reader takes part in it and it is not their own. */
  private Viewed visible(Handle handle, StoredRequest row, Actor actor) {
    if (same(row.requesterUsername(), actor.username())) {
      throw new RequestException(
          RequestException.Kind.FORBIDDEN,
          "Nobody configures their own request; the configurers or an administrator have to");
    }
    Viewed seen = view(handle, List.of(row), actor, true).get(0);
    if (!seen.involved()) {
      throw notFound(row.id());
    }
    return seen;
  }

  /** Checks this reader may configure the locked request now. */
  private Viewed configurable(Handle handle, StoredRequest row, Actor actor) {
    Viewed seen = visible(handle, row, actor);
    if (!"APPROVED".equals(row.status()) && !"IN_PROGRESS".equals(row.status())) {
      throw new RequestException(RequestException.Kind.CONFLICT, already(row));
    }
    if (!seen.request().mayConfigure()) {
      if ("IN_PROGRESS".equals(row.status())) {
        throw new RequestException(
            RequestException.Kind.CONFLICT, row.assignee() + " is configuring this request");
      }
      throw new RequestException(
          RequestException.Kind.FORBIDDEN,
          "Configured by " + describe(row.configurers().isEmpty()
              ? AccessWorkflow.defaultConfigurers()
              : row.configurers()));
    }
    return seen;
  }

  private static String existingPolicy(Handle handle, String policyId) {
    UUID id;
    try {
      id = UUID.fromString(policyId == null ? "" : policyId.trim());
    } catch (IllegalArgumentException e) {
      throw new RequestException(
          RequestException.Kind.INVALID, "Choose the policy you updated or created");
    }
    boolean exists =
        handle
            .createQuery("SELECT count(*) > 0 FROM policy WHERE id = :id")
            .bind("id", id)
            .mapTo(Boolean.class)
            .one();
    if (!exists) {
      throw new RequestException(RequestException.Kind.INVALID, "There is no policy " + id);
    }
    return id.toString();
  }

  // --------------------------------------------------------------- withdrawing

  /** Takes a request back. Only the person who made it can, and only while it waits. */
  public StoredRequest withdraw(UUID id, Actor actor) {
    return jdbi.inTransaction(
        handle -> {
          StoredRequest row = load(handle, id, true);
          if (!same(row.requesterUsername(), actor.username())) {
            // The same answer a stranger gets from find(): whether somebody
            // else has asked for something is not the caller's to learn.
            throw notFound(id);
          }
          if (!row.open()) {
            throw new RequestException(RequestException.Kind.CONFLICT, already(row));
          }
          if (row.pending()) {
            handle
                .createUpdate(
                    """
                    UPDATE access_request
                       SET status = 'WITHDRAWN', decided_by = :by, decided_at = now(),
                           current_step = NULL
                     WHERE id = :id
                    """)
                .bind("by", actor.username())
                .bind("id", id)
                .execute();
            closeStages(handle, id);
          } else {
            handle
                .createUpdate(
                    """
                    UPDATE access_request
                       SET status = 'WITHDRAWN', completed_by = :by, completed_at = now()
                     WHERE id = :id
                    """)
                .bind("by", actor.username())
                .bind("id", id)
                .execute();
          }
          audit(handle, "WITHDRAW", actor.username(), row, null, null, null);
          return view(handle, List.of(load(handle, id, false)), actor, true).get(0).request();
        });
  }

  private static String already(StoredRequest row) {
    String by = row.completedBy() != null ? row.completedBy() : row.decidedBy();
    return switch (row.status()) {
      case "PENDING" -> "This request is still waiting for its approvers";
      case "APPROVED" -> "This request was already approved by " + row.decidedBy()
          + " and waits to be configured";
      case "IN_PROGRESS" -> "This request was already approved and " + row.assignee()
          + " is configuring it";
      case "COMPLETED" -> "This request was already configured by " + row.completedBy();
      case "REJECTED" -> "This request was already rejected by " + by;
      default -> "This request was already withdrawn";
    };
  }

  // --------------------------------------------------------------- seeing

  private record StageRow(
      UUID requestId,
      int idx,
      int step,
      String name,
      Rule rule,
      Integer minApprovals,
      OnReject onReject,
      List<Seat> approvers,
      List<Member> pool,
      boolean fallback,
      String status,
      Instant openedAt,
      Instant settledAt) {

    StageRow withPool(Pool resolved) {
      return new StageRow(
          requestId, idx, step, name, rule, minApprovals, onReject, approvers,
          resolved.members(), resolved.fallback(), status, openedAt, settledAt);
    }
  }

  private record VoteRow(
      UUID requestId, int idx, String voter, boolean approve, boolean override, String note, Instant votedAt) {}

  /**
   * One request with its stages and answers.
   *
   * @param configuring who may configure it; null before it is approved
   * @param legacy for a request decided before workflows, which has no stages:
   *     the table's owners today, who decided it then
   */
  private record Ctx(
      StoredRequest row, List<StageRow> stages, List<VoteRow> votes, Pool configuring, Pool legacy) {

    boolean participant(String username) {
      return tookPart(username) || (legacy != null && legacy.contains(username));
    }

    /**
     * Like {@link #participant}, without standing today's owners in for a
     * request from before workflows: for what the bell says, since an owner
     * added since would otherwise hear about every request the table ever had.
     */
    boolean tookPart(String username) {
      for (StageRow stage : stages) {
        if (contains(stage.pool(), username)) {
          return true;
        }
      }
      for (VoteRow vote : votes) {
        if (same(vote.voter(), username)) {
          return true;
        }
      }
      return (configuring != null && configuring.contains(username))
          || same(row.assignee(), username)
          || same(row.completedBy(), username)
          || same(row.decidedBy(), username);
    }

    /**
     * Whether this person was asked at this step. Nobody was recorded for a
     * request from before workflows, so of its owners only the one who decided it.
     */
    boolean askedAt(int step, String username) {
      if (stages.isEmpty()) {
        return legacy != null && legacy.contains(username) && same(row.decidedBy(), username);
      }
      for (StageRow stage : stages) {
        if (stage.step() == step && contains(stage.pool(), username)) {
          return true;
        }
      }
      return false;
    }
  }

  private record Viewed(StoredRequest request, boolean involved) {}

  /**
   * Loads the stages and answers of these requests in two queries, and fills
   * the pools of open stages that have none: requests that were waiting when
   * workflows arrived, whose pools are resolved the first time anybody looks.
   *
   * @param persist write the pools so resolved, and the configurers of an
   *     approved request that has none; only on a path that changes the request
   */
  private Map<UUID, Ctx> contexts(Handle handle, List<StoredRequest> rows, boolean persist) {
    List<UUID> ids = rows.stream().map(StoredRequest::id).toList();
    Map<UUID, List<StageRow>> stages = stageRows(handle, ids);
    Map<UUID, List<VoteRow>> votes = new LinkedHashMap<>();
    if (!ids.isEmpty()) {
      handle
          .createQuery("SELECT * FROM access_request_vote WHERE request_id IN (<ids>) ORDER BY id")
          .bindList("ids", ids)
          .map(
              (rs, ctx) ->
                  new VoteRow(
                      UUID.fromString(rs.getString("request_id")),
                      rs.getInt("idx"),
                      rs.getString("voter"),
                      "APPROVE".equals(rs.getString("decision")),
                      rs.getBoolean("override"),
                      rs.getString("note"),
                      instant(rs, "voted_at")))
          .forEach(v -> votes.computeIfAbsent(v.requestId(), k -> new ArrayList<>()).add(v));
    }

    Map<UUID, Ctx> out = new LinkedHashMap<>();
    // Requests decided before workflows, per table and requester: an inbox
    // of them would otherwise resolve the same owners once per row.
    Map<String, Pool> owners = new java.util.HashMap<>();
    for (StoredRequest row : rows) {
      List<StageRow> filled = new ArrayList<>();
      for (StageRow stage : stages.getOrDefault(row.id(), List.of())) {
        if ("OPEN".equals(stage.status()) && stage.pool() == null) {
          Pool pool = directory.pool(handle, stage.approvers(), row.assetFqn(), row.requesterUsername());
          if (persist) {
            handle
                .createUpdate(
                    """
                    UPDATE access_request_stage SET pool = CAST(:pool AS jsonb), fallback = :fallback
                     WHERE request_id = :id AND idx = :idx AND pool IS NULL
                    """)
                .bind("pool", write(pool.members()))
                .bind("fallback", pool.fallback())
                .bind("id", row.id())
                .bind("idx", stage.idx())
                .execute();
          }
          stage = stage.withPool(pool);
        }
        filled.add(stage);
      }

      Pool configuring = configurerPool(handle, row, persist);
      Pool legacy =
          filled.isEmpty()
              ? owners.computeIfAbsent(
                  row.assetFqn() + "|" + row.requesterUsername().toLowerCase(Locale.ROOT),
                  k ->
                      directory.pool(
                          handle,
                          List.of(Seat.of(Kind.ASSET_OWNERS)),
                          row.assetFqn(),
                          row.requesterUsername()))
              : null;
      out.put(
          row.id(),
          new Ctx(row, List.copyOf(filled), votes.getOrDefault(row.id(), List.of()), configuring, legacy));
    }
    return out;
  }

  private Pool configurerPool(Handle handle, StoredRequest row, boolean persist) {
    // map() leaves the list null when the column is: never resolved yet.
    if (row.configurerPool() != null) {
      return new Pool(row.configurerPool(), row.configurersFallback());
    }
    if (!"APPROVED".equals(row.status()) && !"IN_PROGRESS".equals(row.status())) {
      return null;
    }
    List<Seat> seats = row.configurers().isEmpty() ? AccessWorkflow.defaultConfigurers() : row.configurers();
    Pool pool = directory.pool(handle, seats, row.assetFqn(), row.requesterUsername());
    if (persist) {
      handle
          .createUpdate(
              "UPDATE access_request SET configurer_pool = CAST(:pool AS jsonb) WHERE id = :id")
          .bind("pool", write(pool))
          .bind("id", row.id())
          .execute();
    }
    return pool;
  }

  private Map<UUID, List<StageRow>> stageRows(Handle handle, List<UUID> ids) {
    Map<UUID, List<StageRow>> out = new LinkedHashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    handle
        .createQuery(
            """
            SELECT request_id, idx, step, name, rule, min_approvals, on_reject,
                   approvers::text AS approvers, pool::text AS pool, fallback, status,
                   opened_at, settled_at
            FROM access_request_stage WHERE request_id IN (<ids>)
            ORDER BY request_id, idx
            """)
        .bindList("ids", ids)
        .map(
            (rs, ctx) -> {
              int min = rs.getInt("min_approvals");
              Integer minApprovals = rs.wasNull() ? null : min;
              String pool = rs.getString("pool");
              return new StageRow(
                  UUID.fromString(rs.getString("request_id")),
                  rs.getInt("idx"),
                  rs.getInt("step"),
                  rs.getString("name"),
                  Rule.valueOf(rs.getString("rule")),
                  minApprovals,
                  OnReject.valueOf(rs.getString("on_reject")),
                  read(rs.getString("approvers"), SEATS),
                  pool == null ? null : read(pool, MEMBERS),
                  rs.getBoolean("fallback"),
                  rs.getString("status"),
                  instant(rs, "opened_at"),
                  instant(rs, "settled_at"));
            })
        .forEach(s -> out.computeIfAbsent(s.requestId(), k -> new ArrayList<>()).add(s));
    return out;
  }

  private static StageEngine.Tally tally(StageRow stage, List<VoteRow> votes) {
    List<StageEngine.Vote> counted = new ArrayList<>();
    for (VoteRow vote : votes) {
      if (vote.idx() == stage.idx()) {
        counted.add(new StageEngine.Vote(vote.voter(), vote.approve(), vote.override()));
      }
    }
    return StageEngine.tally(
        stage.rule(),
        stage.minApprovals(),
        stage.onReject(),
        stage.pool() == null ? List.of() : usernames(stage.pool()),
        counted);
  }

  /**
   * Fills the per-reader fields: the stages as this reader sees them, whether
   * they take part at all, may answer, may configure, and whether the request
   * waits for nobody.
   */
  private List<Viewed> view(Handle handle, List<StoredRequest> rows, Actor actor, boolean persist) {
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<UUID, Ctx> contexts = contexts(handle, rows, persist);
    Map<String, List<Approver>> owners =
        owners(handle, rows.stream().map(StoredRequest::assetFqn).distinct().toList());
    List<String> admins = directory.administrators(handle);
    String me = actor.username();

    List<Viewed> out = new ArrayList<>(rows.size());
    for (StoredRequest row : rows) {
      Ctx ctx = contexts.get(row.id());
      boolean own = same(row.requesterUsername(), me);
      boolean otherAdmin = otherAdministrator(admins, row.requesterUsername());

      List<StageView> stages = new ArrayList<>();
      boolean mayDecide = false;
      boolean stuck = false;
      for (StageRow stage : ctx.stages()) {
        List<VoteView> votes = new ArrayList<>();
        boolean voted = false;
        for (VoteRow vote : ctx.votes()) {
          if (vote.idx() == stage.idx()) {
            votes.add(
                new VoteView(
                    vote.voter(),
                    vote.approve() ? "APPROVE" : "REJECT",
                    vote.override(),
                    vote.note(),
                    vote.votedAt()));
            voted |= same(vote.voter(), me);
          }
        }
        StageEngine.Tally tally = tally(stage, ctx.votes());
        boolean current =
            row.pending()
                && row.currentStep() != null
                && stage.step() == row.currentStep()
                && "OPEN".equals(stage.status());
        boolean mayVote =
            current && !own && !voted && (contains(stage.pool(), me) || actor.platformAdmin());
        mayDecide |= mayVote;
        stuck |= current && tally.stranded();
        stages.add(
            new StageView(
                stage.idx(),
                stage.step(),
                stage.name(),
                stage.rule().name(),
                stage.minApprovals(),
                stage.onReject().name(),
                stage.approvers(),
                stage.pool() == null ? List.of() : stage.pool(),
                stage.fallback(),
                stage.status(),
                stage.openedAt(),
                stage.settledAt(),
                List.copyOf(votes),
                tally.approvals(),
                tally.rejections(),
                tally.needed(),
                current && tally.stranded(),
                mayVote));
      }

      Pool configuring = ctx.configuring();
      boolean configurer = configuring != null && configuring.contains(me);
      boolean mayConfigure =
          !own
              && (("APPROVED".equals(row.status()) && (configurer || actor.platformAdmin()))
                  || ("IN_PROGRESS".equals(row.status())
                      && (same(row.assignee(), me) || actor.platformAdmin())));
      boolean stranded =
          !otherAdmin
              && ((row.pending() && stuck)
                  || ("APPROVED".equals(row.status())
                      && (configuring == null || configuring.members().isEmpty())));
      boolean involved = actor.platformAdmin() || own || ctx.participant(me);

      out.add(
          new Viewed(
              row.seen(
                  List.copyOf(stages),
                  configuring,
                  owners.getOrDefault(row.assetFqn(), List.of()),
                  mayDecide,
                  mayConfigure,
                  stranded),
              involved));
    }
    return out;
  }

  private static boolean otherAdministrator(List<String> admins, String requester) {
    return admins.stream().anyMatch(a -> !same(a, requester));
  }

  private static boolean votedBy(StageView stage, String username) {
    return stage.votes().stream().anyMatch(v -> same(v.voter(), username));
  }

  private static String describe(List<Seat> seats) {
    return String.join(", ", seats.stream().map(Seat::describe).toList());
  }

  private static Map<String, List<Approver>> owners(Handle handle, List<String> assets) {
    Map<String, List<Approver>> out = new LinkedHashMap<>();
    if (assets.isEmpty()) {
      return out;
    }
    handle
        .createQuery(
            """
            SELECT target_fqn, owner_type, owner_name, is_direct, inherited_from
            FROM asset_owner
            WHERE target_fqn IN (<assets>)
            ORDER BY is_direct DESC, owner_type DESC, owner_name
            """)
        .bindList("assets", assets)
        .map(
            (rs, ctx) ->
                Map.entry(
                    rs.getString("target_fqn"),
                    new Approver(
                        rs.getString("owner_type"),
                        rs.getString("owner_name"),
                        rs.getBoolean("is_direct"),
                        rs.getString("inherited_from"))))
        .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
    return out;
  }

  // ---------------------------------------------------------------- notices

  /**
   * One thing that happened to a request, told to somebody who should hear it.
   *
   * @param kind for somebody taking part: {@code REQUESTED} (asked at the first
   *     step), {@code ADVANCED} (asked at a later step), {@code TO_CONFIGURE}
   *     (approved, and they configure it) or {@code WITHDRAWN}; for the
   *     requester: {@code APPROVED}, {@code REJECTED} or {@code COMPLETED}
   * @param side {@code INBOX} when the reader takes part, {@code MINE} when it
   *     is the reader's own request -- which tab of the requests page it opens
   * @param step the step a {@code REQUESTED} or {@code ADVANCED} opened
   */
  public record Notice(
      long id,
      String kind,
      String side,
      UUID requestId,
      String assetFqn,
      String actor,
      String requesterUsername,
      String note,
      Instant occurredAt,
      boolean unseen,
      Integer step) {}

  /**
   * What the header needs: the newest notices, how many are new, and the two
   * counts the requests page puts on its tabs.
   */
  public record Notices(
      int unseen, int inboxPending, int minePending, Instant seenAt, List<Notice> items) {}

  /** How far back the bell looks: enough to count "new" honestly, not the whole history. */
  static final int NOTICE_WINDOW = 500;

  /**
   * What this person should hear about, newest first.
   *
   * <p>Built from {@code audit_access_request}, not stored separately. The
   * reader hears that a step asks them when it opens, that a request they
   * configure was approved, that a request they took part in was withdrawn,
   * and how their own requests were answered. Nobody is told about what they
   * did themselves, except that a request they just approved now waits for
   * them to configure it.
   */
  public Notices notices(Actor actor, int limit) {
    int keep = limit <= 0 ? 20 : Math.min(limit, 50);
    String me = actor.username();
    return jdbi.withHandle(
        handle -> {
          Instant seenAt =
              handle
                  .createQuery(
                      "SELECT seen_at FROM access_request_notice_seen WHERE username = lower(:who)")
                  .bind("who", me)
                  .map((rs, ctx) -> instant(rs, "seen_at"))
                  .findOne()
                  .orElse(null);

          List<Notice> all = new ArrayList<>();
          all.addAll(
              handle
                  .createQuery(
                      """
                      SELECT * FROM audit_access_request
                      WHERE lower(requester_username) = lower(:who)
                        AND action IN ('APPROVE', 'REJECT', 'COMPLETE')
                        AND lower(actor) <> lower(:who)
                      ORDER BY occurred_at DESC, id DESC
                      LIMIT :window
                      """)
                  .bind("who", me)
                  .bind("window", NOTICE_WINDOW)
                  .map((rs, ctx) -> notice(rs, "MINE", seenAt))
                  .list());

          List<Notice> others =
              handle
                  .createQuery(
                      """
                      SELECT * FROM audit_access_request
                      WHERE action IN ('REQUEST', 'ADVANCE', 'APPROVE', 'WITHDRAW')
                        AND lower(requester_username) <> lower(:who)
                      ORDER BY occurred_at DESC, id DESC
                      LIMIT :window
                      """)
                  .bind("who", me)
                  .bind("window", NOTICE_WINDOW)
                  .map((rs, ctx) -> notice(rs, "INBOX", seenAt))
                  .list();
          List<UUID> ids = others.stream().map(Notice::requestId).distinct().toList();
          List<StoredRequest> rows =
              ids.isEmpty()
                  ? List.of()
                  : handle
                      .createQuery("SELECT * FROM access_request WHERE id IN (<ids>)")
                      .bindList("ids", ids)
                      .map(this::map)
                      .list();
          Map<UUID, Ctx> contexts = contexts(handle, rows, false);
          for (Notice one : others) {
            Ctx ctx = contexts.get(one.requestId());
            if (ctx == null) {
              continue;
            }
            boolean hears =
                switch (one.kind()) {
                  case "REQUESTED", "ADVANCED" ->
                      actor.platformAdmin()
                          || ctx.askedAt(one.step() == null ? 1 : one.step(), me);
                  case "TO_CONFIGURE" ->
                      actor.platformAdmin()
                          || (ctx.configuring() != null && ctx.configuring().contains(me));
                  default -> !same(one.actor(), me) && (actor.platformAdmin() || ctx.tookPart(me));
                };
            if (hears) {
              all.add(one);
            }
          }

          // Newest first; the audit id breaks a tie in the same instant.
          all.sort(
              Comparator.comparing(Notice::occurredAt).thenComparingLong(Notice::id).reversed());
          int unseen = (int) all.stream().filter(Notice::unseen).count();

          int minePending =
              handle
                  .createQuery(
                      """
                      SELECT count(*) FROM access_request
                      WHERE lower(requester_username) = lower(:who) AND status IN (<open>)
                      """)
                  .bind("who", me)
                  .bindList("open", OPEN)
                  .mapTo(Integer.class)
                  .one();
          int inboxPending = pendingFor(handle, actor);

          return new Notices(
              unseen,
              inboxPending,
              minePending,
              seenAt,
              List.copyOf(all.subList(0, Math.min(keep, all.size()))));
        });
  }

  /** Everything up to now is read. The bell stops counting it; nothing is deleted. */
  public void markNoticesSeen(Actor actor) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO access_request_notice_seen (username, seen_at)
                    VALUES (lower(:who), now())
                    ON CONFLICT (username) DO UPDATE SET seen_at = EXCLUDED.seen_at
                    """)
                .bind("who", actor.username())
                .execute());
  }

  /** Requests waiting for this person to answer or configure, counted the way the inbox lists them. */
  private int pendingFor(Handle handle, Actor actor) {
    List<StoredRequest> open =
        handle
            .createQuery(
                """
                SELECT * FROM access_request
                WHERE status IN (<open>) AND lower(requester_username) <> lower(:who)
                LIMIT 2000
                """)
            .bindList("open", OPEN)
            .bind("who", actor.username())
            .map(this::map)
            .list();
    return (int)
        view(handle, open, actor, false).stream()
            .map(Viewed::request)
            .filter(r -> r.mayDecide() || r.mayConfigure())
            .count();
  }

  private static Notice notice(ResultSet rs, String side, Instant seenAt) throws SQLException {
    Instant at = instant(rs, "occurred_at");
    String action = rs.getString("action");
    String kind =
        "MINE".equals(side)
            ? switch (action) {
              case "APPROVE" -> "APPROVED";
              case "COMPLETE" -> "COMPLETED";
              default -> "REJECTED";
            }
            : switch (action) {
              case "REQUEST" -> "REQUESTED";
              case "ADVANCE" -> "ADVANCED";
              case "APPROVE" -> "TO_CONFIGURE";
              default -> "WITHDRAWN";
            };
    int step = rs.getInt("step");
    Integer stepOrNull = rs.wasNull() ? null : step;
    return new Notice(
        rs.getLong("id"),
        kind,
        side,
        UUID.fromString(rs.getString("request_id")),
        rs.getString("asset_fqn"),
        rs.getString("actor"),
        rs.getString("requester_username"),
        rs.getString("note"),
        at,
        seenAt == null || at.isAfter(seenAt),
        stepOrNull);
  }

  // --------------------------------------------------------------- plumbing

  private StoredRequest load(Handle handle, UUID id, boolean lock) {
    return handle
        .createQuery("SELECT * FROM access_request WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
        .bind("id", id)
        .map(this::map)
        .findOne()
        .orElseThrow(() -> notFound(id));
  }

  private static RequestException notFound(UUID id) {
    return new RequestException(RequestException.Kind.NOT_FOUND, "No access request " + id);
  }

  private static void audit(
      Handle handle,
      String action,
      String actor,
      StoredRequest row,
      UUID grantId,
      String note,
      Integer step) {
    handle
        .createUpdate(
            """
            INSERT INTO audit_access_request
              (actor, action, request_id, asset_fqn, requester_username, grant_id, note, step)
            VALUES (:actor, :action, :request, :fqn, :requester, :grant, :note, :step)
            """)
        .bind("actor", actor)
        .bind("action", action)
        .bind("request", row.id())
        .bind("fqn", row.assetFqn())
        .bind("requester", row.requesterUsername())
        .bind("grant", grantId)
        .bind("note", note)
        .bind("step", step)
        .execute();
  }

  private StoredRequest map(ResultSet rs, StatementContext ctx) throws SQLException {
    int days = rs.getInt("requested_days");
    Integer requestedDays = rs.wasNull() ? null : days;
    int step = rs.getInt("current_step");
    Integer currentStep = rs.wasNull() ? null : step;
    String pool = rs.getString("configurer_pool");
    Pool configuring = pool == null ? null : read(pool, Pool.class);
    return new StoredRequest(
        UUID.fromString(rs.getString("id")),
        ticket(rs.getLong("ticket_no")),
        rs.getString("asset_fqn"),
        uuidOrNull(rs.getString("requester_id")),
        rs.getString("requester_username"),
        uuidOrNull(rs.getString("data_source_id")),
        rs.getString("reason"),
        rs.getString("purpose"),
        requestedDays,
        rs.getString("attempted_sql"),
        rs.getString("denied_by"),
        rs.getString("status"),
        instant(rs, "created_at"),
        rs.getString("decided_by"),
        instant(rs, "decided_at"),
        rs.getString("decision_note"),
        uuidOrNull(rs.getString("grant_id")),
        rs.getString("workflow_name"),
        currentStep,
        rs.getString("assignee"),
        instant(rs, "assigned_at"),
        rs.getString("completed_by"),
        instant(rs, "completed_at"),
        rs.getString("fulfilment"),
        rs.getString("fulfilment_ref"),
        rs.getString("fulfilment_note"),
        read(rs.getString("configurers"), SEATS),
        configuring == null ? null : configuring.members(),
        configuring != null && configuring.fallback(),
        List.of(),
        List.of(),
        false,
        false,
        false,
        rs.getString("template_name"),
        rs.getString("reference"));
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not write an access request's workflow as JSON", e);
    }
  }

  private <T> T read(String text, TypeReference<T> type) {
    try {
      return json.readValue(text == null ? "[]" : text, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("An access request's stored workflow is not readable", e);
    }
  }

  private <T> T read(String text, Class<T> type) {
    try {
      return json.readValue(text, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("An access request's stored workflow is not readable", e);
    }
  }

  private static List<String> usernames(List<Member> members) {
    return members.stream().map(Member::username).toList();
  }

  private static boolean contains(List<Member> pool, String username) {
    if (pool == null || username == null) {
      return false;
    }
    for (Member member : pool) {
      if (member.username().equalsIgnoreCase(username)) {
        return true;
      }
    }
    return false;
  }

  private static boolean same(String a, String b) {
    return a != null && b != null && a.equalsIgnoreCase(b);
  }

  private static UUID uuidOrNull(String value) {
    return value == null ? null : UUID.fromString(value);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    java.sql.Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static int clamp(int limit) {
    return limit <= 0 ? 100 : Math.min(limit, 500);
  }
}
