package com.mfec.dac.llm;

import java.time.Instant;
import java.util.UUID;

/**
 * The shapes the LLM-assist settings travel in, between store, resource and
 * browser.
 *
 * <p>Gathered in one file because they are one idea in two halves, and reading
 * them side by side is what makes the relationship obvious. The halves are:
 *
 * <ul>
 *   <li><b>Each person's own gateway</b> — their base URL, their key, their
 *       model. This is the primary path. A key here is a real secret, stored
 *       encrypted, and never returned in any shape; the view carries
 *       {@code hasOwnKey} and nothing else derived from it.
 *   <li><b>The shared gateway</b> — optional, administrator-owned, and what
 *       somebody falls back to when they have not configured their own. Its key
 *       is a <em>pointer</em> ({@code env:NAME}) rather than a secret, because
 *       one value set once by an operator belongs in the environment.
 * </ul>
 *
 * <p>The two switches on the shared row govern the whole feature: {@code enabled}
 * for the shared gateway, {@code allowPersonal} for everybody's own. Together
 * they give the four states that are wanted — shared only, personal only, both,
 * off — and either can be flipped during an incident without unpicking a single
 * person's settings.
 */
public final class LlmSettings {

  private LlmSettings() {}

  /**
   * The optional shared gateway.
   *
   * @param credentialRef a pointer to the key ({@code env:NAME}), never the key
   * @param allowPersonal whether people may point the assistant somewhere else
   */
  public record Provider(
      String baseUrl,
      String credentialRef,
      String defaultModel,
      boolean enabled,
      boolean allowPersonal,
      Instant updatedAt,
      String updatedBy) {}

  /**
   * What the browser is told about the shared gateway.
   *
   * <p>Carries {@code secretPresent} instead of anything derived from the key
   * itself — not a prefix, not a length. A console that can describe a secret is
   * a console that leaks a little of it into every screenshot and support
   * ticket. Same rule the OpenMetadata settings screen already follows.
   */
  public record ProviderView(
      String baseUrl,
      String credentialRef,
      String defaultModel,
      boolean enabled,
      boolean allowPersonal,
      boolean configured,
      boolean secretPresent,
      String secretProblem,
      Instant updatedAt,
      String updatedBy) {}

  /** What an administrator submits for the shared gateway. */
  public record ProviderEdit(
      String baseUrl,
      String credentialRef,
      String defaultModel,
      Boolean enabled,
      Boolean allowPersonal) {}

  /**
   * One person's row.
   *
   * @param baseUrl their own gateway, or null to use the shared one
   * @param apiKeyCipher their own key, encrypted; never leaves the server
   */
  public record UserSetting(
      UUID principalId,
      boolean enabled,
      String model,
      String baseUrl,
      String apiKeyCipher,
      Instant updatedAt,
      String updatedBy) {}

  /**
   * What a person submits for themselves.
   *
   * @param apiKey the key in plain, on its way to being encrypted. Blank or null
   *     means "leave the stored one alone" — so that changing a model does not
   *     require re-typing the key, and so that a form which never received the
   *     key cannot erase it by echoing back an empty field.
   * @param clearOwnGateway true to forget their URL and key and fall back to the
   *     shared gateway. An explicit flag rather than a magic empty string,
   *     because "unset it" and "do not touch it" must not look alike.
   */
  public record UserEdit(
      Boolean enabled, String model, String baseUrl, String apiKey, Boolean clearOwnGateway) {}

  /**
   * A person's settings with everything folded in that decides what happens when
   * they press the button.
   *
   * @param available the only field the console has to consult to know whether
   *     the assistant works right now
   * @param usingOwnGateway true when the call would go to their gateway, false
   *     when it would go to the shared one
   * @param problem why it is not available, in a sentence fit to show, or null
   */
  public record EffectiveSetting(
      boolean available,
      boolean enabled,
      String model,
      String effectiveModel,
      // Their own gateway.
      String ownBaseUrl,
      boolean hasOwnKey,
      boolean usingOwnGateway,
      // The shared one, and what the platform permits.
      boolean platformEnabled,
      boolean personalAllowed,
      boolean sharedConfigured,
      String defaultModel,
      String problem) {}

  /**
   * One row of the administrator's overview.
   *
   * <p>Deliberately not a control surface for somebody else's gateway: it
   * carries {@code ownBaseUrl} and {@code hasOwnKey} so an administrator can see
   * who has configured what, and no key in any form. The only thing an
   * administrator writes here is {@code enabled}.
   */
  public record UserRow(
      UUID principalId,
      String username,
      String displayName,
      String source,
      boolean enabled,
      String model,
      String ownBaseUrl,
      boolean hasOwnKey,
      Instant updatedAt,
      String updatedBy) {}

  /**
   * A gateway resolved down to the two things one call needs.
   *
   * <p>Handed to {@link LlmClient} in place of either settings record, so the
   * client has no opinion about whose key it is holding and no way to reach for
   * a different one. The token is already in plain here, which is why nothing
   * constructs a {@code Gateway} except at the moment of a call.
   */
  public record Gateway(String baseUrl, String token, boolean personal) {}
}
