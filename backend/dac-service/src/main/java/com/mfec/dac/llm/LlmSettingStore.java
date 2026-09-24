package com.mfec.dac.llm;

import com.mfec.dac.crypto.SecretBox;
import com.mfec.dac.llm.LlmSettings.EffectiveSetting;
import com.mfec.dac.llm.LlmSettings.Gateway;
import com.mfec.dac.llm.LlmSettings.Provider;
import com.mfec.dac.llm.LlmSettings.ProviderEdit;
import com.mfec.dac.llm.LlmSettings.UserEdit;
import com.mfec.dac.llm.LlmSettings.UserRow;
import com.mfec.dac.llm.LlmSettings.UserSetting;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;

/**
 * Reads and writes the LLM-assist settings (V12, V14).
 *
 * <p>Two rules in here carry the whole feature, and both live in exactly one
 * method so that no caller can implement its own version:
 *
 * <ul>
 *   <li>{@link #effectiveFor} decides what a call would do. A person's own
 *       gateway wins over the shared one; the shared one is the fallback, not
 *       the default; and each is gated by its own platform switch, which is an
 *       AND rather than a default. Somebody who configured the assistant last
 *       month does not get it back the moment an administrator switches the
 *       capability off.
 *   <li>{@link #gatewayFor} is the only thing that decrypts a key, and it does
 *       so at the moment of a call. Nothing else in this class selects the
 *       cipher column into anything a caller can see.
 * </ul>
 */
public class LlmSettingStore {

  private final Jdbi jdbi;
  private final SecretBox secrets;
  private final LlmSecretRef secretRefs = new LlmSecretRef();

  public LlmSettingStore(Jdbi jdbi, SecretBox secrets) {
    this.jdbi = jdbi;
    this.secrets = secrets;
  }

  /** Whether a personal key can be stored at all, and why not when it cannot. */
  public SecretBox secretBox() {
    return secrets;
  }

  // ------------------------------------------------- the shared gateway

  public Optional<Provider> provider() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT base_url, credential_ref, default_model, enabled, allow_personal,
                           updated_at, updated_by
                      FROM llm_provider
                     WHERE singleton
                    """)
                .map(
                    (rs, ctx) ->
                        new Provider(
                            rs.getString("base_url"),
                            rs.getString("credential_ref"),
                            rs.getString("default_model"),
                            rs.getBoolean("enabled"),
                            rs.getBoolean("allow_personal"),
                            rs.getTimestamp("updated_at").toInstant(),
                            rs.getString("updated_by")))
                .findOne());
  }

  /** Whether people may point the assistant at a gateway of their own. */
  public boolean personalAllowed() {
    // No row at all means nobody has ever opened this settings page, which must
    // not be the same as "an administrator has forbidden it" -- otherwise the
    // primary path is dead until somebody with admin rights visits a screen.
    return provider().map(Provider::allowPersonal).orElse(true);
  }

  /**
   * Writes the shared gateway, creating the single row the first time.
   *
   * <p>A null field on the edit means "leave it alone", so that turning the
   * assistant off does not require re-sending the credential reference — a
   * round trip that would have put the pointer through the browser for no
   * reason. The first write is the exception: there is nothing to leave alone,
   * so the caller must supply a base URL and a reference, which the resource
   * checks before it gets here.
   */
  public Provider saveProvider(ProviderEdit edit, String actor) {
    Optional<Provider> current = provider();
    String baseUrl =
        LlmGatewayUrl.normalise(
            firstNonBlank(edit.baseUrl(), current.map(Provider::baseUrl).orElse(null)));
    String ref = firstNonBlank(edit.credentialRef(), current.map(Provider::credentialRef).orElse(null));
    String defaultModel =
        edit.defaultModel() != null
            ? blankToNull(edit.defaultModel())
            : current.map(Provider::defaultModel).orElse(null);
    boolean enabled =
        edit.enabled() != null ? edit.enabled() : current.map(Provider::enabled).orElse(false);
    boolean allowPersonal =
        edit.allowPersonal() != null
            ? edit.allowPersonal()
            : current.map(Provider::allowPersonal).orElse(true);

    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO llm_provider
                        (singleton, base_url, credential_ref, default_model, enabled,
                         allow_personal, updated_at, updated_by)
                    VALUES (true, :baseUrl, :ref, :defaultModel, :enabled, :allowPersonal,
                            now(), :actor)
                    ON CONFLICT (singleton) DO UPDATE
                       SET base_url       = EXCLUDED.base_url,
                           credential_ref = EXCLUDED.credential_ref,
                           default_model  = EXCLUDED.default_model,
                           enabled        = EXCLUDED.enabled,
                           allow_personal = EXCLUDED.allow_personal,
                           updated_at     = now(),
                           updated_by     = EXCLUDED.updated_by
                    """)
                .bind("baseUrl", baseUrl)
                .bind("ref", ref)
                .bind("defaultModel", defaultModel)
                .bind("enabled", enabled)
                .bind("allowPersonal", allowPersonal)
                .bind("actor", actor)
                .execute());

    return provider().orElseThrow();
  }

  // ------------------------------------------------------------ per-person

  public Optional<UserSetting> settingFor(UUID principalId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT principal_id, enabled, model, base_url, api_key_cipher,
                           updated_at, updated_by
                      FROM llm_user_setting
                     WHERE principal_id = :id
                    """)
                .bind("id", principalId)
                .map(
                    (rs, ctx) ->
                        new UserSetting(
                            UUID.fromString(rs.getString("principal_id")),
                            rs.getBoolean("enabled"),
                            rs.getString("model"),
                            rs.getString("base_url"),
                            rs.getString("api_key_cipher"),
                            rs.getTimestamp("updated_at").toInstant(),
                            rs.getString("updated_by")))
                .findOne());
  }

  /** The answer the assistant itself asks for: may this person call, and as what. */
  public EffectiveSetting effectiveFor(UUID principalId) {
    Optional<Provider> shared = provider();
    boolean platformEnabled = shared.map(Provider::enabled).orElse(false);
    boolean personalAllowed = shared.map(Provider::allowPersonal).orElse(true);
    boolean sharedConfigured =
        shared.map(p -> p.baseUrl() != null && !p.baseUrl().isBlank()).orElse(false);
    String defaultModel = shared.map(Provider::defaultModel).orElse(null);

    Optional<UserSetting> mine = settingFor(principalId);
    boolean mineEnabled = mine.map(UserSetting::enabled).orElse(false);
    String model = mine.map(UserSetting::model).orElse(null);
    String ownBaseUrl = mine.map(UserSetting::baseUrl).orElse(null);
    boolean hasOwnKey =
        mine.map(s -> s.apiKeyCipher() != null && !s.apiKeyCipher().isBlank()).orElse(false);

    // A personal gateway is only a gateway when both halves are present. Half of
    // one is the state somebody is in mid-form, and treating it as usable would
    // mean their next call goes to the shared gateway carrying a model only
    // their own endpoint serves.
    boolean ownComplete =
        personalAllowed && ownBaseUrl != null && !ownBaseUrl.isBlank() && hasOwnKey;
    boolean sharedUsable = platformEnabled && sharedConfigured;
    boolean usingOwn = ownComplete;

    String effective = model != null && !model.isBlank() ? model : defaultModel;

    String problem = null;
    if (!mineEnabled) {
      problem = "The assistant is switched off for this account.";
    } else if (!ownComplete && !sharedUsable) {
      if (ownBaseUrl != null && !ownBaseUrl.isBlank() && !hasOwnKey) {
        problem = "This account has a gateway address but no API key saved for it.";
      } else if (hasOwnKey && (ownBaseUrl == null || ownBaseUrl.isBlank())) {
        problem = "This account has an API key saved but no gateway address.";
      } else if (!personalAllowed) {
        problem =
            "This platform does not allow personal gateways, and no shared gateway is "
                + "switched on.";
      } else {
        problem =
            "No gateway is configured for this account, and there is no shared one to fall "
                + "back to.";
      }
    } else if (effective == null || effective.isBlank()) {
      problem = "No model has been chosen for this account.";
    }

    return new EffectiveSetting(
        problem == null,
        mineEnabled,
        model,
        effective,
        ownBaseUrl,
        hasOwnKey,
        usingOwn,
        platformEnabled,
        personalAllowed,
        sharedConfigured,
        defaultModel,
        problem);
  }

  /**
   * The gateway one call should go to, with the token already in plain.
   *
   * <p>The only method that decrypts anything. Called on the request path and
   * the result is passed straight into {@link LlmClient}; nothing holds it.
   *
   * @throws LlmClient.LlmException when there is no usable gateway, with a
   *     message fit to show the person who pressed the button
   */
  public Gateway gatewayFor(UUID principalId) throws LlmClient.LlmException {
    Optional<UserSetting> mine = settingFor(principalId);
    boolean personalAllowed = personalAllowed();

    if (personalAllowed && mine.isPresent()) {
      UserSetting setting = mine.get();
      boolean hasUrl = setting.baseUrl() != null && !setting.baseUrl().isBlank();
      boolean hasKey = setting.apiKeyCipher() != null && !setting.apiKeyCipher().isBlank();
      if (hasUrl && hasKey) {
        try {
          return new Gateway(setting.baseUrl(), secrets.open(setting.apiKeyCipher()), true);
        } catch (SecretBox.SecretBoxException e) {
          throw new LlmClient.LlmException(e.getMessage(), e);
        }
      }
    }

    return sharedGateway();
  }

  /**
   * The shared gateway alone, for the administrator's probe.
   *
   * <p>Separate from {@link #gatewayFor} because an administrator checking that
   * the shared gateway works must not have their own personal one answer for
   * it. A probe that silently tested something else is worse than no probe.
   */
  public Gateway sharedGateway() throws LlmClient.LlmException {
    Provider shared =
        provider()
            .filter(Provider::enabled)
            .filter(p -> p.baseUrl() != null && !p.baseUrl().isBlank())
            .orElseThrow(
                () ->
                    new LlmClient.LlmException(
                        "No gateway is configured for this account, and there is no shared one "
                            + "to fall back to."));
    try {
      return new Gateway(shared.baseUrl(), secretRefs.resolve(shared.credentialRef()), false);
    } catch (LlmSecretRef.UnresolvableException e) {
      throw new LlmClient.LlmException(e.getMessage(), e);
    }
  }

  /**
   * Writes one person's settings. Null fields leave what is there alone.
   *
   * <p>The key is the field with a rule of its own: blank means "unchanged", not
   * "erase". A settings form is never sent the stored key, so a form that echoed
   * its empty field back would otherwise delete the key every time somebody
   * changed their model. Erasing is {@code clearOwnGateway}, which says so.
   */
  public EffectiveSetting saveUser(UUID principalId, UserEdit edit, String actor) {
    Optional<UserSetting> current = settingFor(principalId);
    boolean clear = Boolean.TRUE.equals(edit.clearOwnGateway());

    boolean enabled =
        edit.enabled() != null ? edit.enabled() : current.map(UserSetting::enabled).orElse(false);
    String model =
        edit.model() != null
            ? blankToNull(edit.model())
            : current.map(UserSetting::model).orElse(null);

    String baseUrl;
    String cipher;
    if (clear) {
      baseUrl = null;
      cipher = null;
    } else {
      baseUrl =
          edit.baseUrl() != null
              ? LlmGatewayUrl.normalise(edit.baseUrl())
              : current.map(UserSetting::baseUrl).orElse(null);
      cipher =
          edit.apiKey() != null && !edit.apiKey().isBlank()
              ? secrets.seal(edit.apiKey().trim())
              : current.map(UserSetting::apiKeyCipher).orElse(null);
    }

    final String finalBaseUrl = baseUrl;
    final String finalCipher = cipher;
    jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO llm_user_setting
                        (principal_id, enabled, model, base_url, api_key_cipher,
                         updated_at, updated_by)
                    VALUES (:id, :enabled, :model, :baseUrl, :cipher, now(), :actor)
                    ON CONFLICT (principal_id) DO UPDATE
                       SET enabled        = EXCLUDED.enabled,
                           model          = EXCLUDED.model,
                           base_url       = EXCLUDED.base_url,
                           api_key_cipher = EXCLUDED.api_key_cipher,
                           updated_at     = now(),
                           updated_by     = EXCLUDED.updated_by
                    """)
                .bind("id", principalId)
                .bind("enabled", enabled)
                .bind("model", model)
                .bind("baseUrl", finalBaseUrl)
                .bind("cipher", finalCipher)
                .bind("actor", actor)
                .execute());

    return effectiveFor(principalId);
  }

  /**
   * Every human account and what it has configured, for the administrator's
   * overview.
   *
   * <p>A LEFT JOIN, so accounts that have never chosen appear as "not
   * configured" rather than being missing. The administrator's question is "who
   * has this turned on", and a list that silently omits everyone who has not
   * answers a different one.
   *
   * <p>Selects whether a key exists, never the column itself. An overview that
   * carried ciphertext would put every stored key through an HTTP response to
   * satisfy a boolean on a screen.
   */
  public List<UserRow> allUsers() {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT p.id, p.username, p.display_name, p.source,
                           COALESCE(s.enabled, false) AS enabled,
                           s.model, s.base_url,
                           (s.api_key_cipher IS NOT NULL) AS has_key,
                           s.updated_at, s.updated_by
                      FROM principal p
                      LEFT JOIN llm_user_setting s ON s.principal_id = p.id
                     WHERE p.principal_type = 'USER' AND p.enabled
                     ORDER BY lower(p.username)
                    """)
                .map(
                    (rs, ctx) ->
                        new UserRow(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("username"),
                            rs.getString("display_name"),
                            rs.getString("source"),
                            rs.getBoolean("enabled"),
                            rs.getString("model"),
                            rs.getString("base_url"),
                            rs.getBoolean("has_key"),
                            rs.getTimestamp("updated_at") == null
                                ? null
                                : rs.getTimestamp("updated_at").toInstant(),
                            rs.getString("updated_by")))
                .list());
  }

  // ------------------------------------------------------------------ helpers

  private static String firstNonBlank(String a, String b) {
    return a != null && !a.isBlank() ? a.trim() : b;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
