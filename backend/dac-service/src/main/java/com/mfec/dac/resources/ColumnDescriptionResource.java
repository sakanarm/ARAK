package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.auth.Stewardship;
import com.mfec.dac.catalog.ColumnDescriptionStore;
import com.mfec.dac.catalog.ColumnDescriptionStore.Entry;
import com.mfec.dac.catalog.ColumnDescriptionStore.Refused;
import com.mfec.dac.catalog.ColumnDescriptionStore.Saved;
import com.mfec.dac.catalog.ColumnDescriptionStore.Written;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;

/**
 * Describing a table's columns in ARAK, for columns OpenMetadata has no
 * description of.
 *
 * <p>Whoever governs the table ({@link Stewardship#governs}) may write them,
 * the same right as tagging it: the words are what a requester and an approver
 * read to decide what a column holds. Everyone who can open the table reads
 * them, on its page and in an access request for it.
 */
@Path("/v1/column-descriptions")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class ColumnDescriptionResource {

  /** More than any table here has columns, few enough that one save stays one small transaction. */
  static final int MAX_ENTRIES = 2000;

  private final ColumnDescriptionStore descriptions;

  public ColumnDescriptionResource(ColumnDescriptionStore descriptions) {
    this.descriptions = descriptions;
  }

  /** The descriptions written here on a table's columns, and whether the caller may change them. */
  public record Listing(boolean canEdit, List<Written> descriptions) {}

  /** Descriptions to write on one table's columns; a blank one takes the written one away. */
  public record Change(String assetFqn, List<Entry> entries) {}

  /** What a save did, and the descriptions as they now stand. */
  public record Result(int set, int cleared, int unchanged, List<Written> descriptions) {}

  @GET
  public Listing list(@QueryParam("asset") String assetFqn, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (assetFqn == null || assetFqn.isBlank()) {
      throw new BadRequestException("Say which table: ?asset=<fqn>");
    }
    String asset = assetFqn.trim();
    return new Listing(Stewardship.governs(caller, asset), descriptions.forAsset(asset));
  }

  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  public Result save(Change change, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (change == null || change.assetFqn() == null || change.assetFqn().isBlank()) {
      throw new BadRequestException("Say which table the columns belong to");
    }
    if (change.entries() == null || change.entries().isEmpty()) {
      throw new BadRequestException("Nothing to save");
    }
    if (change.entries().size() > MAX_ENTRIES) {
      throw new BadRequestException("Save at most " + MAX_ENTRIES + " columns at once");
    }
    String asset = change.assetFqn().trim();
    if (!descriptions.isTable(asset)) {
      throw new NotFoundException("No current table or view " + asset);
    }
    if (!Stewardship.governs(caller, asset)) {
      throw new ForbiddenException(
          "You do not govern " + asset + ", so you cannot describe its columns");
    }
    Saved saved;
    try {
      saved = descriptions.save(asset, change.entries(), caller.username());
    } catch (Refused e) {
      throw new BadRequestException(e.getMessage());
    }
    return new Result(saved.set(), saved.cleared(), saved.unchanged(), descriptions.forAsset(asset));
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }
}
