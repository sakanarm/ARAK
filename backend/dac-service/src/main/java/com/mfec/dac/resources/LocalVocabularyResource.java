package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.catalog.LocalVocabularyStore;
import com.mfec.dac.catalog.LocalVocabularyStore.Kind;
import com.mfec.dac.catalog.LocalVocabularyStore.Refused;
import com.mfec.dac.catalog.LocalVocabularyStore.Value;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Making classifications and tags in ARAK, for vocabulary OpenMetadata does
 * not have yet (FR-1.7).
 *
 * <p>The vocabulary is organisation-wide: a tag made here can be attached to
 * any table and named by any policy, so making one takes the right to author
 * policy — a platform admin or a policy author — not ownership of one table.
 * Attaching it to a table is still the table's steward's call, through {@link
 * LocalTagResource}.
 *
 * <p>Nothing is written back to OpenMetadata.
 */
@Path("/v1/local-vocabulary")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class LocalVocabularyResource {

  public static final int MAX_NAME = 64;
  public static final int MAX_DISPLAY_NAME = 128;
  public static final int MAX_DESCRIPTION = 2000;

  private final LocalVocabularyStore vocabulary;

  public LocalVocabularyResource(LocalVocabularyStore vocabulary) {
    this.vocabulary = vocabulary;
  }

  /** Whether the caller may make and change vocabulary here. */
  public record Permission(boolean canEdit) {}

  public record NewClassification(
      String name, String displayName, String description, boolean mutuallyExclusive) {}

  public record NewTag(String classificationFqn, String name, String displayName, String description) {}

  /** A change to a value made here; a field left null is left as it is. */
  public record Change(
      Kind kind, String fqn, String displayName, String description, Boolean disabled) {}

  @GET
  public Permission permission(@Context SecurityContext security) {
    return new Permission(mayEdit(caller(security)));
  }

  @POST
  @Path("/classifications")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response createClassification(NewClassification ask, @Context SecurityContext security) {
    AuthenticatedUser caller = requireEditor(security);
    if (ask == null) {
      throw new BadRequestException("Say what the classification is called and what it is for");
    }
    String name = name(ask.name());
    String displayName = displayName(ask.displayName());
    String description = description(ask.description());
    Value made =
        guarded(
            () ->
                vocabulary.createClassification(
                    name, displayName, description, ask.mutuallyExclusive(), caller.username()));
    return Response.status(Response.Status.CREATED).entity(made).build();
  }

  @POST
  @Path("/tags")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response createTag(NewTag ask, @Context SecurityContext security) {
    AuthenticatedUser caller = requireEditor(security);
    if (ask == null || ask.classificationFqn() == null || ask.classificationFqn().isBlank()) {
      throw new BadRequestException("Say which classification the tag goes under");
    }
    String classification = ask.classificationFqn().trim();
    String name = name(ask.name());
    String displayName = displayName(ask.displayName());
    String description = description(ask.description());
    Value made =
        guarded(
            () ->
                vocabulary.createTag(
                    classification, name, displayName, description, caller.username()));
    return Response.status(Response.Status.CREATED).entity(made).build();
  }

  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  public Value update(Change change, @Context SecurityContext security) {
    AuthenticatedUser caller = requireEditor(security);
    if (change == null || change.kind() == null || change.fqn() == null || change.fqn().isBlank()) {
      throw new BadRequestException("Say which classification or tag to change");
    }
    // Null leaves a field alone; a blank display name takes it away.
    String displayName =
        change.displayName() == null
            ? null
            : change.displayName().isBlank() ? "" : displayName(change.displayName());
    String description = change.description() == null ? null : description(change.description());
    return guarded(
        () ->
            vocabulary.update(
                change.kind(), change.fqn().trim(), displayName, description, change.disabled(),
                caller.username()));
  }

  // ------------------------------------------------------------- validation

  /**
   * A name that is one FQN segment as it stands. A dot would make it two
   * segments and a double quote is how OpenMetadata escapes one, so both are
   * refused rather than quoted: a name that needs quoting reads differently in
   * every place it is typed.
   */
  static String name(String raw) {
    String name = raw == null ? "" : raw.strip();
    if (name.isEmpty()) {
      throw new BadRequestException("A name is required");
    }
    if (name.length() > MAX_NAME) {
      throw new BadRequestException("Keep the name to " + MAX_NAME + " characters");
    }
    if (name.contains(".") || name.contains("\"")) {
      throw new BadRequestException("A name cannot contain a dot or a double quote");
    }
    if (name.codePoints().anyMatch(Character::isISOControl)) {
      throw new BadRequestException("A name cannot contain control characters");
    }
    if (name.codePoints().noneMatch(Character::isLetterOrDigit)) {
      throw new BadRequestException("A name needs at least one letter or digit");
    }
    return name;
  }

  private static String displayName(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String name = raw.strip();
    if (name.length() > MAX_DISPLAY_NAME) {
      throw new BadRequestException("Keep the display name to " + MAX_DISPLAY_NAME + " characters");
    }
    return name;
  }

  /** A description is required: it is what a steward reads before attaching the tag. */
  private static String description(String raw) {
    String description = raw == null ? "" : raw.strip();
    if (description.isEmpty()) {
      throw new BadRequestException("Say what it means: a description is required");
    }
    if (description.length() > MAX_DESCRIPTION) {
      throw new BadRequestException("Keep the description to " + MAX_DESCRIPTION + " characters");
    }
    return description;
  }

  // --------------------------------------------------------------- plumbing

  private static boolean mayEdit(AuthenticatedUser caller) {
    return caller.isPlatformAdmin() || caller.hasAnyRole("POLICY_AUTHOR");
  }

  private static AuthenticatedUser requireEditor(SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (!mayEdit(caller)) {
      throw new ForbiddenException(
          "Making classifications and tags takes a platform admin or a policy author");
    }
    return caller;
  }

  private static <T> T guarded(Supplier<T> call) {
    try {
      return call.get();
    } catch (Refused e) {
      switch (e.reason()) {
        case NOT_FOUND -> throw new NotFoundException(e.getMessage());
        case INVALID -> throw new BadRequestException(e.getMessage());
        default -> throw conflict(e.getMessage());
      }
    } catch (org.jdbi.v3.core.statement.UnableToExecuteStatementException e) {
      // Two people making the same value at once: the unique key decides.
      if (e.getCause() instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
        throw conflict("That name was taken a moment ago");
      }
      throw e;
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
}
