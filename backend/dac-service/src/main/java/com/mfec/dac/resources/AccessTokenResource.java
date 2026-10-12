package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.gateway.AccessTokenStore;
import com.mfec.dac.gateway.AccessTokenStore.Token;
import com.mfec.dac.gateway.AuthEventLog;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Personal access tokens (FR-19, M29a): what a person types as the password
 * when DBeaver, Power BI or psql connects to the ARAK Gateway.
 *
 * <p>A token is shown once, in the response that creates it, and never again:
 * the service keeps only its hash. Each person makes and ends their own; an
 * administrator can list and end anyone's, which is how a lost laptop is dealt
 * with. Tokens are ended, never deleted, so the sign-in audit can always name
 * the token a session used.
 */
@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class AccessTokenResource {

  private final AccessTokenStore tokens;
  private final AuthEventLog events;

  public AccessTokenResource(AccessTokenStore tokens, AuthEventLog events) {
    this.tokens = tokens;
    this.events = events;
  }

  /** A token as the console shows it: never the secret. */
  public record Row(
      UUID id,
      String owner,
      String name,
      String tokenPrefix,
      String status,
      String createdBy,
      Instant createdAt,
      Instant expiresAt,
      Instant lastUsedAt,
      Instant revokedAt,
      String revokedBy,
      String revokeReason) {}

  public record NewToken(String name, Integer days) {}

  /** The one response that carries the secret. */
  public record CreatedRow(Row token, String secret) {}

  public record Revocation(String reason) {}

  @GET
  @Path("/me/tokens")
  public List<Row> mine(@Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    return tokens.forPrincipal(caller.id()).stream().map(AccessTokenResource::row).toList();
  }

  @POST
  @Path("/me/tokens")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response create(NewToken body, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    if (body == null) {
      throw new BadRequestException("Send a name and a number of days");
    }
    int days = body.days() == null ? Math.min(30, tokens.maxDays()) : body.days();
    AccessTokenStore.Created created;
    try {
      created = tokens.create(caller.id(), body.name(), days, caller.username());
    } catch (AccessTokenStore.InvalidTokenRequest e) {
      throw new BadRequestException(e.getMessage());
    }
    events.record(
        AuthEventLog.Event.TOKEN_CREATE, true, caller.username(), created.token().id(),
        caller.username(), "Ends " + created.token().expiresAt(), null, null);
    return Response.status(Response.Status.CREATED)
        .entity(new CreatedRow(row(created.token()), created.secret()))
        .build();
  }

  @POST
  @Path("/me/tokens/{id}/revoke")
  @Consumes(MediaType.APPLICATION_JSON)
  public Row revokeMine(
      @PathParam("id") UUID id, Revocation body, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    Token token = tokens.find(id).orElseThrow(() -> new NotFoundException("No token " + id));
    if (!token.principalId().equals(caller.id())) {
      // The same answer as a token that does not exist: whose a token is, is
      // not something to learn by guessing ids.
      throw new NotFoundException("No token " + id);
    }
    return revoke(token, body, caller);
  }

  @GET
  @Path("/tokens")
  @Secured({"PLATFORM_ADMIN"})
  public List<Row> all() {
    return tokens.all().stream().map(AccessTokenResource::row).toList();
  }

  @POST
  @Path("/tokens/{id}/revoke")
  @Secured({"PLATFORM_ADMIN"})
  @Consumes(MediaType.APPLICATION_JSON)
  public Row revokeAny(
      @PathParam("id") UUID id, Revocation body, @Context SecurityContext security) {
    AuthenticatedUser caller = caller(security);
    Token token = tokens.find(id).orElseThrow(() -> new NotFoundException("No token " + id));
    return revoke(token, body, caller);
  }

  private Row revoke(Token token, Revocation body, AuthenticatedUser caller) {
    Token after;
    try {
      after = tokens.revoke(token.id(), caller.username(), body == null ? null : body.reason());
    } catch (AccessTokenStore.InvalidTokenRequest e) {
      throw new BadRequestException(e.getMessage());
    }
    if (token.revokedAt() == null) {
      events.record(
          AuthEventLog.Event.TOKEN_REVOKE, true, token.principalName(), token.id(),
          caller.username(), after.revokeReason(), null, null);
    }
    return row(after);
  }

  private static Row row(Token t) {
    return new Row(
        t.id(),
        t.principalName(),
        t.name(),
        t.tokenPrefix(),
        t.status(Instant.now()),
        t.createdBy(),
        t.createdAt(),
        t.expiresAt(),
        t.lastUsedAt(),
        t.revokedAt(),
        t.revokedBy(),
        t.revokeReason());
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }
}
