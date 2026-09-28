package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.purpose.PurposeStore;
import com.mfec.dac.purpose.PurposeStore.Change;
import com.mfec.dac.purpose.PurposeStore.Details;
import com.mfec.dac.purpose.PurposeStore.LegalBasis;
import com.mfec.dac.purpose.PurposeStore.Purpose;
import com.mfec.dac.purpose.PurposeStore.Refused;
import com.mfec.dac.purpose.PurposeStore.Usage;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The register of purposes (FR-21, M31).
 *
 * <p>Anybody signed in reads it, because everybody picks from it: on the query
 * page, on the request form, in the simulator. Changing it takes the right to
 * author policy -- a platform admin or a policy author -- because a purpose is
 * named by policies across the organisation, the same reasoning as the local
 * vocabulary. Where each purpose is used, and the history of each, is for the
 * people who govern: those, data owners and auditors.
 */
@Path("/v1/purposes")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class PurposeResource {

  public static final int MAX_NAME = 120;
  public static final int MAX_DESCRIPTION = 2000;
  public static final int MAX_OWNER = 200;
  public static final int MAX_REASON = 500;

  /** What a key may look like; the same rule as the table's CHECK. */
  static final Pattern KEY = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,62}$");

  private final PurposeStore purposes;

  public PurposeResource(PurposeStore purposes) {
    this.purposes = purposes;
  }

  /** The register, and whether the caller may change it. */
  public record Listing(List<Purpose> purposes, boolean canEdit) {}

  /**
   * Where each purpose is named. {@code listed} is keyed by the register's key;
   * {@code unlisted} are values in use that the register does not have.
   */
  public record Uses(Map<String, Usage> listed, List<Usage> unlisted) {}

  public record NewPurpose(
      String key,
      String name,
      String description,
      LegalBasis legalBasis,
      Boolean sensitiveAllowed,
      String owner,
      Integer maxDays) {}

  public record Edit(
      String name,
      String description,
      LegalBasis legalBasis,
      Boolean sensitiveAllowed,
      String owner,
      Integer maxDays) {}

  public record Why(String reason) {}

  @GET
  public Listing list(@Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    return new Listing(purposes.list(), mayEdit(caller));
  }

  @GET
  @Path("/usage")
  public Uses usage(@Context SecurityContext security) {
    reviewer(security);
    Map<String, Usage> all = purposes.usage();
    Map<String, Usage> listed = new LinkedHashMap<>();
    for (Purpose purpose : purposes.list()) {
      Usage used = all.remove(purpose.key().toLowerCase(Locale.ROOT));
      listed.put(purpose.key(), used == null ? new Usage(purpose.key(), 0, 0, 0, 0) : used);
    }
    List<Usage> unlisted = new ArrayList<>(all.values().stream().filter(Usage::any).toList());
    return new Uses(listed, unlisted);
  }

  @GET
  @Path("/{key}/history")
  public List<Change> history(@PathParam("key") String key, @Context SecurityContext security) {
    reviewer(security);
    purposes.find(key).orElseThrow(() -> new NotFoundException("No purpose " + key));
    return purposes.history(key);
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response create(NewPurpose ask, @Context SecurityContext security) {
    AuthenticatedUser caller = requireEditor(security);
    if (ask == null) {
      throw new BadRequestException("Say what the purpose is called and what it is for");
    }
    String key = key(ask.key());
    Details details =
        details(
            ask.name(), ask.description(), ask.legalBasis(), ask.sensitiveAllowed(), ask.owner(),
            ask.maxDays());
    Purpose made = guarded(() -> purposes.create(key, details, caller.username()));
    return Response.status(Response.Status.CREATED).entity(made).build();
  }

  @PUT
  @Path("/{key}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Purpose update(
      @PathParam("key") String key, Edit edit, @Context SecurityContext security) {
    AuthenticatedUser caller = requireEditor(security);
    if (edit == null) {
      throw new BadRequestException("Send the purpose as it should read");
    }
    Details details =
        details(
            edit.name(), edit.description(), edit.legalBasis(), edit.sensitiveAllowed(),
            edit.owner(), edit.maxDays());
    return guarded(() -> purposes.update(key, details, caller.username()));
  }

  @POST
  @Path("/{key}/retire")
  @Consumes(MediaType.APPLICATION_JSON)
  public Purpose retire(
      @PathParam("key") String key, Why why, @Context SecurityContext security) {
    AuthenticatedUser caller = requireEditor(security);
    String reason = reason(why, "retiring");
    return guarded(() -> purposes.retire(key, reason, caller.username()));
  }

  @POST
  @Path("/{key}/reinstate")
  @Consumes(MediaType.APPLICATION_JSON)
  public Purpose reinstate(
      @PathParam("key") String key, Why why, @Context SecurityContext security) {
    AuthenticatedUser caller = requireEditor(security);
    String reason = reason(why, "reinstating");
    return guarded(() -> purposes.reinstate(key, reason, caller.username()));
  }

  // ------------------------------------------------------------- validation

  /** Lower case, so what policies store and what the engine compares agree. */
  static String key(String raw) {
    String key = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    if (key.isEmpty()) {
      throw new BadRequestException("A key is required: it is what policies and requests store");
    }
    if (!KEY.matcher(key).matches()) {
      throw new BadRequestException(
          "A key is up to 63 lower-case letters, digits, dots, dashes and underscores,"
              + " starting with a letter or a digit, like fraud-analysis");
    }
    return key;
  }

  static Details details(
      String rawName, String rawDescription, LegalBasis legalBasis, Boolean sensitiveAllowed,
      String rawOwner, Integer maxDays) {
    String name = rawName == null ? "" : rawName.strip();
    if (name.isEmpty()) {
      throw new BadRequestException("A name is required: it is what people pick from");
    }
    if (name.length() > MAX_NAME) {
      throw new BadRequestException("Keep the name to " + MAX_NAME + " characters");
    }
    if (name.codePoints().anyMatch(Character::isISOControl)) {
      throw new BadRequestException("A name cannot contain control characters");
    }
    String description = optional(rawDescription, MAX_DESCRIPTION, "description");
    String owner = optional(rawOwner, MAX_OWNER, "owner");
    if (maxDays != null && (maxDays < 1 || maxDays > 365)) {
      throw new BadRequestException("The longest access for a purpose is between 1 and 365 days");
    }
    return new Details(
        name, description, legalBasis, Boolean.TRUE.equals(sensitiveAllowed), owner, maxDays);
  }

  private static String optional(String raw, int max, String what) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.strip();
    if (value.length() > max) {
      throw new BadRequestException("Keep the " + what + " to " + max + " characters");
    }
    return value;
  }

  private static String reason(Why why, String doing) {
    String reason = why == null || why.reason() == null ? "" : why.reason().strip();
    if (reason.isEmpty()) {
      throw new BadRequestException("Say why: the reason for " + doing + " a purpose is kept");
    }
    if (reason.length() > MAX_REASON) {
      throw new BadRequestException("Keep the reason to " + MAX_REASON + " characters");
    }
    return reason;
  }

  // --------------------------------------------------------------- plumbing

  private static boolean mayEdit(AuthenticatedUser caller) {
    return caller.isPlatformAdmin() || caller.hasAnyRole("POLICY_AUTHOR");
  }

  private static AuthenticatedUser requireEditor(SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (!mayEdit(caller)) {
      throw new ForbiddenException("Changing the purposes takes a platform admin or a policy author");
    }
    return caller;
  }

  private static AuthenticatedUser reviewer(SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (!caller.isPlatformAdmin() && !caller.hasAnyRole("POLICY_AUTHOR", "DATA_OWNER", "AUDITOR")) {
      throw new ForbiddenException(
          "Where purposes are used is for administrators, policy authors, data owners and auditors");
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
      // Two people listing the same purpose at once: the unique key decides.
      if (e.getCause() instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
        throw conflict("That key or name was taken a moment ago");
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
