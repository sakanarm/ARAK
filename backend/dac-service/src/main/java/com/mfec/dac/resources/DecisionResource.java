package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.auth.Stewardship;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.purpose.PurposeStore;
import com.mfec.dac.schema.api.PolicyDecision;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * "What would this person see?" — the simulator endpoint (FR-5.2).
 *
 * <p>This is the feature that decides whether anyone dares turn enforcement on.
 * A policy author who cannot see the consequences of a policy before publishing
 * it will not publish it, and an estate nobody dares to govern is not governed.
 *
 * <p>Asking about yourself needs nothing. Asking about somebody else needs
 * authority over policy, because the answer describes another person's access
 * and, read across enough assets, is itself a map of the organisation's
 * permissions. A data owner has that authority over the tables they own and no
 * others; an auditor has it everywhere, to read.
 */
@Path("/v1/decisions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class DecisionResource {

  /**
   * @param at ISO-8601. Supplying it is how a time-windowed policy is tested at
   *     20:00 without waiting until 20:00 (plan section 6, step 15).
   */
  public record Ask(
      String principal, String assetFqn, String at, String ip, String purpose, String environment) {}

  private final DecisionService decisions;
  private final PurposeStore purposes;

  public DecisionResource(DecisionService decisions) {
    this(decisions, null);
  }

  /**
   * @param purposes the register a purpose asked about must be in (FR-21);
   *     null takes whatever was typed
   */
  public DecisionResource(DecisionService decisions, PurposeStore purposes) {
    this.decisions = decisions;
    this.purposes = purposes;
  }

  @POST
  public PolicyDecision decide(Ask ask, @Context SecurityContext security) {
    if (ask == null || ask.assetFqn() == null || ask.assetFqn().isBlank()) {
      throw new BadRequestException("assetFqn is required");
    }
    AuthenticatedUser caller = caller(security);
    String subject =
        ask.principal() == null || ask.principal().isBlank() ? caller.getName() : ask.principal();

    if (!subject.equalsIgnoreCase(caller.getName())
        && !Stewardship.oversees(caller, ask.assetFqn())) {
      throw new ForbiddenException(
          "Simulating another principal's access needs POLICY_AUTHOR or AUDITOR, or DATA_OWNER of "
              + ask.assetFqn().trim());
    }

    return decisions.decide(
        new DecisionService.Ask(
            subject, ask.assetFqn(), instant(ask.at()), ask.ip(), purpose(ask.purpose()),
            ask.environment()));
  }

  /** The purpose as the register keys it; one it does not offer is a mistake in the question. */
  private String purpose(String raw) {
    if (purposes == null) {
      return raw;
    }
    try {
      return purposes.declared(raw);
    } catch (PurposeStore.Refused e) {
      throw new BadRequestException(e.getMessage());
    }
  }

  private static Instant instant(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new BadRequestException("at must be an ISO-8601 instant, for example 2026-09-20T13:00:00Z");
    }
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security.getUserPrincipal() instanceof AuthenticatedUser user) {
      return user;
    }
    throw new ForbiddenException("No caller on this request");
  }
}
