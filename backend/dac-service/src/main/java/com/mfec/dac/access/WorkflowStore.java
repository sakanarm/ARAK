package com.mfec.dac.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.access.AccessWorkflow.Draft;
import com.mfec.dac.access.AccessWorkflow.Seat;
import com.mfec.dac.access.AccessWorkflow.Stage;
import com.mfec.dac.access.AccessWorkflow.Workflow;
import com.mfec.dac.common.Fqns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

/**
 * The access request workflows administrators and data owners design, and
 * which one a table's requests walk (M9 slice 2a).
 *
 * <p>A workflow applies to every table under its scope, compared segment by
 * segment, and the deepest enabled scope wins: a workflow on
 * {@code prod.Sales} beats the organisation's default and is beaten by one on
 * {@code prod.Sales.dbo}. With none at all, {@link AccessWorkflow#builtIn()}.
 *
 * <p>Every change is written to {@code audit_access_workflow} with the before
 * and after, in the same transaction.
 */
public class WorkflowStore {

  private static final TypeReference<List<Stage>> STAGES = new TypeReference<>() {};
  private static final TypeReference<List<Seat>> SEATS = new TypeReference<>() {};

  private final Jdbi jdbi;
  private final ObjectMapper json;

  public WorkflowStore(Jdbi jdbi, ObjectMapper json) {
    this.jdbi = jdbi;
    this.json = json;
  }

  /** A workflow and who last changed it. */
  public record Stored(
      Workflow workflow, String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {}

  /** Every workflow, the default first, then by scope. */
  public List<Stored> list() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT * FROM access_workflow ORDER BY scope_fqn NULLS FIRST, lower(name)")
                .map((rs, ctx) -> stored(rs))
                .list());
  }

  public Optional<Stored> find(UUID id) {
    return jdbi.withHandle(handle -> find(handle, id));
  }

  private Optional<Stored> find(Handle handle, UUID id) {
    return handle
        .createQuery("SELECT * FROM access_workflow WHERE id = :id")
        .bind("id", id)
        .map((rs, ctx) -> stored(rs))
        .findOne();
  }

  /** The workflow a request on this table walks. */
  public Workflow effective(String assetFqn) {
    return jdbi.withHandle(handle -> effective(handle, assetFqn));
  }

  /** The workflow a request on this table walks, read on the caller's handle. */
  public Workflow effective(Handle handle, String assetFqn) {
    List<Stored> enabled =
        handle
            .createQuery("SELECT * FROM access_workflow WHERE enabled")
            .map((rs, ctx) -> stored(rs))
            .list();
    Workflow best = null;
    int bestDepth = -1;
    for (Stored one : enabled) {
      String scope = one.workflow().scopeFqn();
      int depth;
      if (scope == null) {
        depth = 0;
      } else if (assetFqn != null && Fqns.isDescendantOrSelf(assetFqn, scope)) {
        depth = Fqns.depth(scope);
      } else {
        continue;
      }
      if (depth > bestDepth) {
        best = one.workflow();
        bestDepth = depth;
      }
    }
    return best == null ? AccessWorkflow.builtIn() : best;
  }

  /** Creates a workflow. The draft must already be {@link AccessWorkflow#validate validated}. */
  public Stored create(Draft draft, String actor) {
    return unique(
        () ->
            jdbi.inTransaction(
                handle -> {
                  UUID id =
                      handle
                          .createQuery(
                              """
                              INSERT INTO access_workflow
                                (name, description, scope_fqn, enabled, stages, configurers,
                                 created_by, updated_by)
                              VALUES (:name, :description, :scope, :enabled, CAST(:stages AS jsonb),
                                      CAST(:configurers AS jsonb), :actor, :actor)
                              RETURNING id
                              """)
                          .bind("name", draft.name())
                          .bind("description", draft.description())
                          .bind("scope", draft.scopeFqn())
                          .bind("enabled", Boolean.TRUE.equals(draft.enabled()))
                          .bind("stages", write(draft.stages()))
                          .bind("configurers", write(draft.configurers()))
                          .bind("actor", actor)
                          .mapTo(UUID.class)
                          .one();
                  Stored after = find(handle, id).orElseThrow();
                  audit(handle, actor, "CREATE", id, draft.scopeFqn(), null, after.workflow());
                  return after;
                }),
        draft.scopeFqn());
  }

  /** Replaces a workflow. Requests already walking it keep the copy they took. */
  public Stored update(UUID id, Draft draft, String actor) {
    return unique(
        () ->
            jdbi.inTransaction(
                handle -> {
                  Stored before =
                      handle
                          .createQuery("SELECT * FROM access_workflow WHERE id = :id FOR UPDATE")
                          .bind("id", id)
                          .map((rs, ctx) -> stored(rs))
                          .findOne()
                          .orElseThrow(() -> missing(id));
                  handle
                      .createUpdate(
                          """
                          UPDATE access_workflow
                             SET name = :name, description = :description, scope_fqn = :scope,
                                 enabled = :enabled, stages = CAST(:stages AS jsonb),
                                 configurers = CAST(:configurers AS jsonb),
                                 updated_by = :actor, updated_at = now()
                           WHERE id = :id
                          """)
                      .bind("name", draft.name())
                      .bind("description", draft.description())
                      .bind("scope", draft.scopeFqn())
                      .bind("enabled", Boolean.TRUE.equals(draft.enabled()))
                      .bind("stages", write(draft.stages()))
                      .bind("configurers", write(draft.configurers()))
                      .bind("actor", actor)
                      .bind("id", id)
                      .execute();
                  Stored after = find(handle, id).orElseThrow();
                  audit(
                      handle, actor, "UPDATE", id, draft.scopeFqn(), before.workflow(), after.workflow());
                  return after;
                }),
        draft.scopeFqn());
  }

  /** Deletes a workflow. Requests that walked it keep its name and their copy of its stages. */
  public void delete(UUID id, String actor) {
    jdbi.useTransaction(
        handle -> {
          Stored before = find(handle, id).orElseThrow(() -> missing(id));
          handle.createUpdate("DELETE FROM access_workflow WHERE id = :id").bind("id", id).execute();
          audit(handle, actor, "DELETE", id, before.workflow().scopeFqn(), before.workflow(), null);
        });
  }

  /** The change history of one workflow, newest first. */
  public List<Map<String, Object>> history(UUID id) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT id, occurred_at, actor, action, scope_fqn
                    FROM audit_access_workflow WHERE workflow_id = :id
                    ORDER BY occurred_at DESC, id DESC
                    """)
                .bind("id", id)
                .map(
                    (rs, ctx) -> {
                      Map<String, Object> row = new LinkedHashMap<>();
                      row.put("id", rs.getLong("id"));
                      row.put("occurredAt", rs.getTimestamp("occurred_at").toInstant());
                      row.put("actor", rs.getString("actor"));
                      row.put("action", rs.getString("action"));
                      row.put("scopeFqn", rs.getString("scope_fqn"));
                      return row;
                    })
                .list());
  }

  /** A second workflow on the same scope. */
  public static class ScopeTakenException extends RuntimeException {
    public ScopeTakenException(String scope) {
      super(
          scope == null
              ? "There is already an organisation-wide default workflow; edit that one"
              : "A workflow already covers " + scope + "; edit that one");
    }
  }

  /** No workflow by that id. */
  public static class NoSuchWorkflowException extends RuntimeException {
    public NoSuchWorkflowException(UUID id) {
      super("No access workflow " + id);
    }
  }

  private static NoSuchWorkflowException missing(UUID id) {
    return new NoSuchWorkflowException(id);
  }

  private static <T> T unique(java.util.function.Supplier<T> call, String scope) {
    try {
      return call.get();
    } catch (UnableToExecuteStatementException e) {
      if (String.valueOf(e.getMessage()).contains("access_workflow_scope_idx")) {
        throw new ScopeTakenException(scope);
      }
      throw e;
    }
  }

  private void audit(
      Handle handle,
      String actor,
      String action,
      UUID id,
      String scope,
      Workflow before,
      Workflow after) {
    handle
        .createUpdate(
            """
            INSERT INTO audit_access_workflow (actor, action, workflow_id, scope_fqn, before, after)
            VALUES (:actor, :action, :id, :scope, CAST(:before AS jsonb), CAST(:after AS jsonb))
            """)
        .bind("actor", actor)
        .bind("action", action)
        .bind("id", id)
        .bind("scope", scope)
        .bind("before", before == null ? null : write(before))
        .bind("after", after == null ? null : write(after))
        .execute();
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not write a workflow as JSON", e);
    }
  }

  List<Stage> stages(String text) {
    try {
      return text == null ? List.of() : json.readValue(text, STAGES);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("A stored workflow's stages are not readable", e);
    }
  }

  List<Seat> seats(String text) {
    try {
      return text == null ? List.of() : json.readValue(text, SEATS);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("A stored workflow's seats are not readable", e);
    }
  }

  String seatsJson(List<Seat> seats) {
    return write(seats);
  }

  private Stored stored(ResultSet rs) throws SQLException {
    Workflow workflow =
        new Workflow(
            UUID.fromString(rs.getString("id")),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("scope_fqn"),
            rs.getBoolean("enabled"),
            stages(rs.getString("stages")),
            seats(rs.getString("configurers")));
    return new Stored(
        workflow,
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("updated_by"),
        rs.getTimestamp("updated_at").toInstant());
  }
}
