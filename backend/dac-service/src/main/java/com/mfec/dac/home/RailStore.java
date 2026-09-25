package com.mfec.dac.home;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfec.dac.home.Rail.Layout;
import com.mfec.dac.home.Rail.Section;
import com.mfec.dac.home.Rail.View;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;

/** Reads and writes {@code user_rail} (V26, density V27). Every path in goes through {@link Rail#validate}. */
public class RailStore {

  private static final TypeReference<List<Section>> SECTIONS = new TypeReference<>() {};

  private final Jdbi jdbi;
  private final ObjectMapper json;

  public RailStore(Jdbi jdbi, ObjectMapper json) {
    this.jdbi = jdbi;
    this.json = json;
  }

  public View forPrincipal(UUID principal) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT sections::text, density, updated_at FROM user_rail WHERE principal_id = :p")
                .bind("p", principal)
                .map(
                    (rs, ctx) ->
                        new View(
                            read(rs.getString(1)),
                            rs.getString(2),
                            rs.getTimestamp(3).toInstant()))
                .findOne()
                .orElse(View.unarranged()));
  }

  public View save(UUID principal, Layout layout) {
    List<Section> clean = Rail.validate(layout);
    String density = Rail.density(layout);
    String body;
    try {
      body = json.writeValueAsString(clean);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO user_rail (principal_id, sections, density, updated_at)
                    VALUES (:p, CAST(:body AS jsonb), :density, now())
                    ON CONFLICT (principal_id)
                    DO UPDATE SET sections = EXCLUDED.sections,
                                  density = EXCLUDED.density,
                                  updated_at = now()
                    """)
                .bind("p", principal)
                .bind("body", body)
                .bind("density", density)
                .execute());
    return forPrincipal(principal);
  }

  /** Back to the rail this product ships with. */
  public View reset(UUID principal) {
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate("DELETE FROM user_rail WHERE principal_id = :p")
                .bind("p", principal)
                .execute());
    return View.unarranged();
  }

  private List<Section> read(String body) {
    try {
      return json.readValue(body, SECTIONS);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }
}
