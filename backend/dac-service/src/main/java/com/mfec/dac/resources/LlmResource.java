package com.mfec.dac.resources;

import com.mfec.dac.auth.AuthenticatedUser;
import com.mfec.dac.auth.Secured;
import com.mfec.dac.llm.LlmClient;
import com.mfec.dac.llm.LlmGatewayUrl;
import com.mfec.dac.llm.LlmSecretRef;
import com.mfec.dac.llm.LlmSettingStore;
import com.mfec.dac.llm.LlmSettings.EffectiveSetting;
import com.mfec.dac.llm.LlmSettings.Gateway;
import com.mfec.dac.llm.LlmSettings.Provider;
import com.mfec.dac.llm.LlmSettings.ProviderEdit;
import com.mfec.dac.llm.LlmSettings.ProviderView;
import com.mfec.dac.llm.LlmSettings.UserEdit;
import com.mfec.dac.llm.LlmSettings.UserRow;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * LLM assist: each person's own gateway, an optional shared one, and one call
 * to prove the wiring (M11).
 *
 * <p>The authorisation on this resource is the feature's design in one place,
 * and it changed with the correction that each person configures their own
 * gateway rather than being switched on against a central one. The line now
 * falls here:
 *
 * <ul>
 *   <li><b>My own gateway is mine.</b> Any authenticated caller may set their
 *       own base URL, key and model, and read back everything about it except
 *       the key. This is the primary path.
 *   <li><b>The shared gateway is the platform's.</b> Its address, its
 *       credential reference and the two switches are {@code PLATFORM_ADMIN}
 *       only, because it is a credential the deployment pays for and everybody
 *       who has not configured their own falls back to it.
 *   <li><b>Nobody writes anybody else's gateway.</b> Not even an administrator.
 *       {@link #putUser} narrows an administrator's edit to {@code enabled}
 *       before it reaches the store, so the only thing the console's overview
 *       can do to another account is switch the assistant off. An
 *       administrator who could set somebody else's base URL could redirect
 *       that person's prompts to a host of their choosing, and the audit trail
 *       would show the person's own name on every call.
 * </ul>
 *
 * <p>Letting an ordinary account supply a base URL is what makes this feature
 * what was asked for, and it is also a server-side request to an address
 * somebody typed. Every write of one goes through {@link LlmGatewayUrl}, and
 * the platform keeps {@code allowPersonal} as a switch that turns the whole
 * capability off without unpicking anybody's settings.
 */
@Path("/v1/llm")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class LlmResource {

  private final LlmSettingStore store;
  private final LlmClient client;
  private final LlmSecretRef secrets;

  public LlmResource(LlmSettingStore store, LlmClient client, LlmSecretRef secrets) {
    this.store = store;
    this.client = client;
    this.secrets = secrets;
  }

  // ------------------------------------------------------ the shared gateway

  /** The shared gateway as configured, with the key reduced to present / absent. */
  @GET
  @Path("/provider")
  @Secured("PLATFORM_ADMIN")
  public ProviderView provider() {
    return view(store.provider());
  }

  /** Writes the shared gateway. Null fields leave what is already stored alone. */
  @PUT
  @Path("/provider")
  @Secured("PLATFORM_ADMIN")
  public ProviderView putProvider(ProviderEdit edit, @Context SecurityContext security) {
    if (edit == null) {
      throw new BadRequestException("No body");
    }
    Optional<Provider> current = store.provider();
    boolean first = current.isEmpty();

    if (first && (edit.baseUrl() == null || edit.baseUrl().isBlank())) {
      throw new BadRequestException("A gateway base URL is required");
    }
    if (edit.baseUrl() != null && !edit.baseUrl().isBlank()) {
      // The same check an ordinary account's URL gets. An administrator could
      // point this anywhere in any case, but running one rule over both means
      // there is one rule to read, and the error messages match.
      String problem = LlmGatewayUrl.validate(edit.baseUrl());
      if (problem != null) {
        throw new BadRequestException(problem);
      }
    }
    if (first && (edit.credentialRef() == null || edit.credentialRef().isBlank())) {
      throw new BadRequestException("A credential reference is required");
    }
    if (edit.credentialRef() != null && !edit.credentialRef().isBlank()) {
      String problem = LlmSecretRef.validate(edit.credentialRef());
      if (problem != null) {
        throw new BadRequestException(problem);
      }
    }

    store.saveProvider(edit, caller(security).username());
    return view(store.provider());
  }

  /**
   * Asks the shared gateway what it can serve.
   *
   * <p>Behind its own call rather than folded into the settings read, so that
   * opening the page does not block on an unreachable host — the same shape the
   * OpenMetadata settings screen uses for its probe. Deliberately the shared
   * gateway and not {@code gatewayFor}: an administrator checking the shared
   * one must not have their own personal gateway answer in its place.
   */
  @POST
  @Path("/provider/probe")
  @Secured("PLATFORM_ADMIN")
  public Map<String, Object> probe() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("baseUrl", store.provider().map(Provider::baseUrl).orElse(null));
    try {
      List<String> models = client.models(store.sharedGateway());
      body.put("reachable", true);
      body.put("models", models);
      body.put("modelCount", models.size());
    } catch (LlmClient.LlmException e) {
      body.put("reachable", false);
      body.put("models", List.of());
      body.put("problem", e.getMessage());
    }
    return body;
  }

  /**
   * The model list for whichever gateway <em>this caller</em> would reach.
   *
   * <p>Asked of their own gateway when they have one, because the models behind
   * somebody's own endpoint are not the models behind the shared one, and a
   * picker that lists the wrong set is worse than a text field.
   */
  @GET
  @Path("/models")
  public Map<String, Object> models(@Context SecurityContext security) {
    Map<String, Object> body = new LinkedHashMap<>();
    AuthenticatedUser actor = caller(security);
    Gateway gateway;
    try {
      gateway = store.gatewayFor(actor.id());
    } catch (LlmClient.LlmException e) {
      body.put("available", false);
      body.put("models", List.of());
      body.put("problem", e.getMessage());
      return body;
    }
    try {
      List<String> models = client.models(gateway);
      body.put("available", true);
      body.put("personal", gateway.personal());
      body.put("models", models);
    } catch (LlmClient.LlmException e) {
      body.put("available", false);
      body.put("personal", gateway.personal());
      body.put("models", List.of());
      body.put("problem", e.getMessage());
    }
    return body;
  }

  // ------------------------------------------------------------- my settings

  /** What the assistant would do for me right now, and why. */
  @GET
  @Path("/me")
  public EffectiveSetting me(@Context SecurityContext security) {
    return store.effectiveFor(caller(security).id());
  }

  /**
   * Writes my own settings — my gateway, my key, my model, on or off.
   *
   * <p>A blank {@code apiKey} means "leave the stored one alone", which is what
   * lets the console send this form back without ever having received the key.
   * Forgetting a gateway is {@code clearOwnGateway}, said out loud.
   */
  @PUT
  @Path("/me")
  public EffectiveSetting putMe(UserEdit edit, @Context SecurityContext security) {
    if (edit == null) {
      throw new BadRequestException("No body");
    }
    AuthenticatedUser actor = caller(security);
    boolean clearing = Boolean.TRUE.equals(edit.clearOwnGateway());

    if (!clearing && (edit.baseUrl() != null || (edit.apiKey() != null && !edit.apiKey().isBlank()))) {
      if (!store.personalAllowed()) {
        throw new ForbiddenException(
            "This platform does not allow personal gateways. Ask an administrator.");
      }
    }
    if (!clearing && edit.baseUrl() != null && !edit.baseUrl().isBlank()) {
      String problem = LlmGatewayUrl.validate(edit.baseUrl());
      if (problem != null) {
        throw new BadRequestException(problem);
      }
    }
    if (!clearing && edit.apiKey() != null && !edit.apiKey().isBlank()
        && !store.secretBox().available()) {
      // Refused rather than stored in plain. The alternative -- writing the key
      // to a column that was promised to hold ciphertext -- would leave a
      // database whose contents do not match what the schema says about them.
      throw new ServiceUnavailableException(store.secretBox().problem());
    }

    return store.saveUser(actor.id(), edit, actor.username());
  }

  // ------------------------------------------------ everybody, for an admin

  /**
   * Every account and what it has configured, for the administrator's overview.
   *
   * <p>An overview and not a configuration surface: it carries whether each
   * account has a gateway of its own and whether a key is saved, never the key
   * and never anything derived from it.
   */
  @GET
  @Path("/users")
  @Secured("PLATFORM_ADMIN")
  public List<UserRow> users() {
    return store.allUsers();
  }

  /**
   * Writes one account's setting.
   *
   * <p>Somebody writing their own row gets the full edit. An administrator
   * writing somebody else's gets {@code enabled} and nothing more — the edit is
   * narrowed here rather than validated, so that a field added to
   * {@code UserEdit} later cannot quietly become writable across accounts by
   * being forgotten in a check.
   */
  @PUT
  @Path("/users/{principalId}")
  public EffectiveSetting putUser(
      @PathParam("principalId") UUID principalId,
      UserEdit edit,
      @Context SecurityContext security) {
    if (edit == null) {
      throw new BadRequestException("No body");
    }
    AuthenticatedUser actor = caller(security);
    if (actor.id().equals(principalId)) {
      return putMe(edit, security);
    }
    if (!actor.isPlatformAdmin()) {
      throw new ForbiddenException("Only an administrator may change somebody else's setting");
    }
    if (edit.enabled() == null) {
      throw new BadRequestException(
          "An administrator may only switch the assistant on or off for another account");
    }
    UserEdit narrowed = new UserEdit(edit.enabled(), null, null, null, null);
    return store.saveUser(principalId, narrowed, actor.username());
  }

  // --------------------------------------------------------------- one call

  /** A prompt and the answer, for proving the wiring end to end. */
  public record CompleteRequest(String prompt, String model) {}

  /**
   * Sends one prompt as the caller, through the caller's own gateway.
   *
   * <p>The setting is read here rather than trusted from the request: a caller
   * who has the assistant switched off must not be able to reach a gateway by
   * posting to this endpoint directly. {@code model} on the body is an override
   * for the console's "try it" box and is gated by the same check.
   */
  @POST
  @Path("/complete")
  public Map<String, Object> complete(CompleteRequest request, @Context SecurityContext security) {
    if (request == null || request.prompt() == null || request.prompt().isBlank()) {
      throw new BadRequestException("A prompt is required");
    }
    AuthenticatedUser actor = caller(security);
    EffectiveSetting mine = store.effectiveFor(actor.id());
    if (!mine.enabled()) {
      throw new ForbiddenException("The assistant is not switched on for this account");
    }
    if (!mine.available()) {
      throw new ServiceUnavailableException(mine.problem());
    }

    String model =
        request.model() != null && !request.model().isBlank()
            ? request.model().trim()
            : mine.effectiveModel();

    try {
      Gateway gateway = store.gatewayFor(actor.id());
      LlmClient.Completion answer =
          client.complete(
              gateway,
              model,
              // Said here, not in the prompt the caller types, so that it holds
              // for every call this endpoint ever makes.
              "You are an assistant inside ARAK, a data access control platform. "
                  + "You are given metadata only — never rows of data. Anything you "
                  + "draft is a draft for a human to review; you never apply or "
                  + "activate anything. Be concise.",
              request.prompt());
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("model", answer.model());
      body.put("text", answer.text());
      body.put("promptTokens", answer.promptTokens());
      body.put("completionTokens", answer.completionTokens());
      body.put("personal", gateway.personal());
      return body;
    } catch (LlmClient.LlmException e) {
      throw new ServiceUnavailableException(e.getMessage());
    }
  }

  // ------------------------------------------------------------------ helpers

  private ProviderView view(Optional<Provider> provider) {
    if (provider.isEmpty()) {
      // No row yet: the shared gateway is off and unconfigured, and personal
      // gateways are permitted -- the same default the store reads, so the
      // console and the engine agree before anybody has saved anything.
      return new ProviderView(null, null, null, false, true, false, false, null, null, null);
    }
    Provider p = provider.get();
    String problem = secrets.problemWith(p.credentialRef());
    return new ProviderView(
        p.baseUrl(),
        p.credentialRef(),
        p.defaultModel(),
        p.enabled(),
        p.allowPersonal(),
        true,
        problem == null,
        problem,
        p.updatedAt(),
        p.updatedBy());
  }

  private static AuthenticatedUser caller(SecurityContext security) {
    if (security == null || !(security.getUserPrincipal() instanceof AuthenticatedUser user)) {
      throw new ForbiddenException("No caller on this request");
    }
    return user;
  }
}
