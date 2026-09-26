package com.mfec.dac.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.access.RequestTemplate.Draft;
import com.mfec.dac.access.RequestTemplate.Form;
import com.mfec.dac.access.RequestTemplate.Stored;
import com.mfec.dac.access.RequestTemplate.Template;
import com.mfec.dac.common.Fqns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

/**
 * The request templates administrators and data owners write, and which one a
 * table's form is rendered from.
 *
 * <p>Of the enabled templates whose scope covers the table, one that names
 * facets the table carries beats one that names none -- "PII tables ask for a
 * reference" is more specific than "everything in Sales" -- then the deepest
 * scope wins, then the name, so the answer never depends on row order. A
 * template naming facets the table does not carry does not apply to it at all.
 * With none, {@link RequestTemplate#builtIn()}.
 *
 * <p>A table carries a facet when the table or any of its columns does, at or
 * under it: a template on {@code PII} covers a table with one column tagged
 * {@code PII.Sensitive}. Asking for a table is asking for all of its columns.
 *
 * <p>Every change is written to {@code audit_access_request_template} with the
 * before and after, in the same transaction.
 */
public class RequestTemplateStore {

  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

  /** The facet types a template may name: the ones that label sensitivity and meaning. */
  static final List<String> FACET_TYPES = List.of("tags", "classifications", "terms", "glossaries");

  private final Jdbi jdbi;
  private final ObjectMapper json;

  public RequestTemplateStore(Jdbi jdbi, ObjectMapper json) {
    this.jdbi = jdbi;
    this.json = json;
  }

  /** Every template, organisation-wide first, then by scope and name. */
  public List<Stored> list() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT * FROM access_request_template ORDER BY scope_fqn NULLS FIRST, lower(name)")
                .map((rs, ctx) -> stored(rs))
                .list());
  }

  public Optional<Stored> find(UUID id) {
    return jdbi.withHandle(handle -> find(handle, id));
  }

  private Optional<Stored> find(Handle handle, UUID id) {
    return handle
        .createQuery("SELECT * FROM access_request_template WHERE id = :id")
        .bind("id", id)
        .map((rs, ctx) -> stored(rs))
        .findOne();
  }

  /** The template a request on this table is asked on. */
  public Template effective(String assetFqn) {
    return jdbi.withHandle(handle -> effective(handle, assetFqn));
  }

  /** The template a request on this table is asked on, read on the caller's handle. */
  public Template effective(Handle handle, String assetFqn) {
    List<Template> covering =
        handle
            .createQuery("SELECT * FROM access_request_template WHERE enabled")
            .map((rs, ctx) -> stored(rs).template())
            .list()
            .stream()
            .filter(t -> t.scopeFqn() == null || (assetFqn != null && Fqns.isDescendantOrSelf(assetFqn, t.scopeFqn())))
            .toList();
    if (covering.isEmpty()) {
      return RequestTemplate.builtIn();
    }
    List<String> carried =
        covering.stream().anyMatch(t -> !t.matchFacets().isEmpty()) ? facets(handle, assetFqn) : List.of();
    return covering.stream()
        .filter(t -> t.matchFacets().isEmpty() || matches(t.matchFacets(), carried))
        .min(
            Comparator.comparing((Template t) -> t.matchFacets().isEmpty())
                .thenComparing(t -> -(t.scopeFqn() == null ? 0 : Fqns.depth(t.scopeFqn())))
                .thenComparing(t -> t.name().toLowerCase(java.util.Locale.ROOT)))
        .orElse(RequestTemplate.builtIn());
  }

  /** The labels a table carries, on itself or on any of its columns. */
  private static List<String> facets(Handle handle, String assetFqn) {
    if (assetFqn == null) {
      return List.of();
    }
    return handle
        .createQuery(
            """
            SELECT DISTINCT f.facet_fqn
              FROM asset_facet f
             WHERE f.facet_type IN (<types>)
               AND (f.target_fqn = :fqn
                    OR (f.column_id IS NOT NULL
                        AND left(f.target_fqn, length(:fqn) + 1) = :fqn || '.'))
            """)
        .bindList("types", FACET_TYPES)
        .bind("fqn", assetFqn)
        .mapTo(String.class)
        .list();
  }

  /** Whether any carried label is one of the wanted ones or under it. */
  static boolean matches(List<String> wanted, List<String> carried) {
    for (String want : wanted) {
      for (String have : carried) {
        if (Fqns.isDescendantOrSelf(have, want)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Creates a template. The draft must already be {@link RequestTemplate#validate validated}. */
  public Stored create(Draft draft, String actor) {
    return unique(
        () ->
            jdbi.inTransaction(
                handle -> {
                  UUID id =
                      handle
                          .createQuery(
                              """
                              INSERT INTO access_request_template
                                (name, description, scope_fqn, match_facets, enabled, form,
                                 created_by, updated_by)
                              VALUES (:name, :description, :scope, CAST(:facets AS jsonb), :enabled,
                                      CAST(:form AS jsonb), :actor, :actor)
                              RETURNING id
                              """)
                          .bind("name", draft.name())
                          .bind("description", draft.description())
                          .bind("scope", draft.scopeFqn())
                          .bind("facets", write(draft.matchFacets()))
                          .bind("enabled", Boolean.TRUE.equals(draft.enabled()))
                          .bind("form", write(draft.form()))
                          .bind("actor", actor)
                          .mapTo(UUID.class)
                          .one();
                  Stored after = find(handle, id).orElseThrow();
                  audit(handle, actor, "CREATE", id, draft.scopeFqn(), null, after.template());
                  return after;
                }),
        draft.name());
  }

  /** Replaces a template. Requests already made keep what they were asked. */
  public Stored update(UUID id, Draft draft, String actor) {
    return unique(
        () ->
            jdbi.inTransaction(
                handle -> {
                  Stored before =
                      handle
                          .createQuery("SELECT * FROM access_request_template WHERE id = :id FOR UPDATE")
                          .bind("id", id)
                          .map((rs, ctx) -> stored(rs))
                          .findOne()
                          .orElseThrow(() -> new NoSuchTemplateException(id));
                  handle
                      .createUpdate(
                          """
                          UPDATE access_request_template
                             SET name = :name, description = :description, scope_fqn = :scope,
                                 match_facets = CAST(:facets AS jsonb), enabled = :enabled,
                                 form = CAST(:form AS jsonb), updated_by = :actor, updated_at = now()
                           WHERE id = :id
                          """)
                      .bind("name", draft.name())
                      .bind("description", draft.description())
                      .bind("scope", draft.scopeFqn())
                      .bind("facets", write(draft.matchFacets()))
                      .bind("enabled", Boolean.TRUE.equals(draft.enabled()))
                      .bind("form", write(draft.form()))
                      .bind("actor", actor)
                      .bind("id", id)
                      .execute();
                  Stored after = find(handle, id).orElseThrow();
                  audit(handle, actor, "UPDATE", id, draft.scopeFqn(), before.template(), after.template());
                  return after;
                }),
        draft.name());
  }

  /** Deletes a template. Requests made on it keep its name and what they answered. */
  public void delete(UUID id, String actor) {
    jdbi.useTransaction(
        handle -> {
          Stored before = find(handle, id).orElseThrow(() -> new NoSuchTemplateException(id));
          handle.createUpdate("DELETE FROM access_request_template WHERE id = :id").bind("id", id).execute();
          audit(handle, actor, "DELETE", id, before.template().scopeFqn(), before.template(), null);
        });
  }

  /** The change history of one template, newest first. */
  public List<Map<String, Object>> history(UUID id) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT id, occurred_at, actor, action, scope_fqn
                    FROM audit_access_request_template WHERE template_id = :id
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

  /** A second template with the same name. */
  public static class NameTakenException extends RuntimeException {
    public NameTakenException(String name) {
      super("A template called \"" + name + "\" already exists; choose another name or edit that one");
    }
  }

  /** No template by that id. */
  public static class NoSuchTemplateException extends RuntimeException {
    public NoSuchTemplateException(UUID id) {
      super("No request template " + id);
    }
  }

  private static <T> T unique(Supplier<T> call, String name) {
    try {
      return call.get();
    } catch (UnableToExecuteStatementException e) {
      if (String.valueOf(e.getMessage()).contains("access_request_template_name_idx")) {
        throw new NameTakenException(name);
      }
      throw e;
    }
  }

  private void audit(
      Handle handle, String actor, String action, UUID id, String scope, Template before, Template after) {
    handle
        .createUpdate(
            """
            INSERT INTO audit_access_request_template (actor, action, template_id, scope_fqn, before, after)
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
      throw new IllegalStateException("Could not write a request template as JSON", e);
    }
  }

  private Stored stored(ResultSet rs) throws SQLException {
    try {
      Template template =
          new Template(
              UUID.fromString(rs.getString("id")),
              rs.getString("name"),
              rs.getString("description"),
              rs.getString("scope_fqn"),
              json.readValue(rs.getString("match_facets"), STRINGS),
              rs.getBoolean("enabled"),
              json.readValue(rs.getString("form"), Form.class));
      return new Stored(
          template,
          rs.getString("created_by"),
          rs.getTimestamp("created_at").toInstant(),
          rs.getString("updated_by"),
          rs.getTimestamp("updated_at").toInstant());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("A stored request template is not readable", e);
    }
  }
}
