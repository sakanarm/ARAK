package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.purpose.SensitiveData;
import com.mfec.dac.purpose.SensitiveData.Change;
import com.mfec.dac.purpose.SensitiveData.Concern;
import com.mfec.dac.purpose.SensitiveData.Coverage;
import com.mfec.dac.purpose.SensitiveData.Label;
import com.mfec.dac.purpose.SensitiveData.Mode;
import com.mfec.dac.purpose.SensitiveData.Rule;
import com.mfec.dac.purpose.SensitiveData.Settings;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;

/**
 * What counts as sensitive data (FR-21, M31b).
 *
 * <p>Anybody signed in reads the rule and asks whether a purpose meets
 * sensitive data on a table, because the request form and the query page tell
 * them before they send. Changing it takes the right to author policy, the
 * same as the purposes it works with. How much of the catalog a rule covers,
 * and what it was before, is for the people who govern.
 */
@Path("/v1/sensitive-data")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class SensitiveDataResource {

  private final SensitiveData sensitive;

  public SensitiveDataResource(SensitiveData sensitive) {
    this.sensitive = sensitive;
  }

  public record Current(Rule rule, boolean canEdit) {}

  /** The rule as it should read, and why; {@code reason} is kept with the change. */
  public record Edit(
      Boolean builtIn, List<Label> include, List<Label> exclude, Mode mode, String reason) {}

  /** A rule to measure before saving it. */
  public record Draft(Boolean builtIn, List<Label> include, List<Label> exclude) {}

  /** @param concern null when the purpose may be used there */
  public record Check(Concern concern) {}

  @GET
  public Current current(@Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    return new Current(sensitive.current(), mayEdit(caller));
  }

  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  public Current update(Edit edit, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (!mayEdit(caller)) {
      throw new ForbiddenException(
          "Changing what counts as sensitive data takes a platform admin or a policy author");
    }
    if (edit == null) {
      throw new BadRequestException("Send the rule as it should read");
    }
    if (edit.mode() == null) {
      throw new BadRequestException("Choose what happens: off, warn or enforce");
    }
    String reason = edit.reason() == null ? "" : edit.reason().strip();
    if (reason.isEmpty()) {
      throw new BadRequestException("Say why: the reason for changing the rule is kept");
    }
    if (reason.length() > PurposeResource.MAX_REASON) {
      throw new BadRequestException(
          "Keep the reason to " + PurposeResource.MAX_REASON + " characters");
    }
    Settings wanted =
        new Settings(
            !Boolean.FALSE.equals(edit.builtIn()), edit.include(), edit.exclude(), edit.mode());
    try {
      return new Current(sensitive.update(wanted, reason, caller.username()), true);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
  }

  /** How much of the catalog this rule would cover, without saving it. */
  @POST
  @Path("/preview")
  @Consumes(MediaType.APPLICATION_JSON)
  public Coverage preview(Draft draft, @Context SecurityContext security) {
    reviewer(security);
    Rule rule =
        draft == null
            ? sensitive.current()
            : new Rule(
                !Boolean.FALSE.equals(draft.builtIn()),
                draft.include(),
                draft.exclude(),
                Mode.WARN,
                null,
                null);
    for (List<Label> list : List.of(rule.include(), rule.exclude())) {
      for (Label label : list) {
        if (label == null || label.kind() == null || label.fqn() == null || label.fqn().isBlank()) {
          throw new BadRequestException("Every label needs a kind and a name");
        }
      }
    }
    if (rule.include().size() > SensitiveData.MAX_LABELS
        || rule.exclude().size() > SensitiveData.MAX_LABELS) {
      throw new BadRequestException("Keep each list to " + SensitiveData.MAX_LABELS + " labels");
    }
    return sensitive.coverage(rule);
  }

  @GET
  @Path("/history")
  public List<Change> history(@Context SecurityContext security) {
    reviewer(security);
    return sensitive.history();
  }

  /**
   * Whether naming this purpose on this table meets sensitive data it does not
   * allow, as the query proxy and the request form would judge it.
   */
  @GET
  @Path("/check")
  public Check check(
      @QueryParam("asset") String asset,
      @QueryParam("purpose") String purpose,
      @Context SecurityContext security) {
    caller(security);
    if (asset == null || asset.isBlank()) {
      throw new BadRequestException("Name the table to check");
    }
    return new Check(sensitive.concern(asset.strip(), purpose).orElse(null));
  }

  // --------------------------------------------------------------- plumbing

  private static boolean mayEdit(AuthenticatedUser caller) {
    return caller.isPlatformAdmin() || caller.hasAnyRole("POLICY_AUTHOR");
  }

  private static AuthenticatedUser reviewer(SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (!caller.isPlatformAdmin() && !caller.hasAnyRole("POLICY_AUTHOR", "DATA_OWNER", "AUDITOR")) {
      throw new ForbiddenException(
          "How far the rule reaches is for administrators, policy authors, data owners and auditors");
    }
    return caller;
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }
}
