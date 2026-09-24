package com.mfec.dac.catalog;

import com.mfec.dac.audit.ClientAddress;
import com.mfec.dac.config.OpenMetadataConfiguration;
import com.mfec.dac.crypto.SecretBox;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Which OpenMetadata instance the platform reads, settable from the screen
 * (FR-1.1).
 *
 * <p>This used to be environment only, and the failure that exposed it was
 * quiet: an instance moves, the catalog still answers from cache, policies
 * still evaluate, and the only visible symptom is that "Open in OpenMetadata"
 * stops appearing — with nothing on any screen to correct. Where the catalog
 * lives is an operational fact about somebody else's estate, so it belongs in
 * the database, and the running client re-reads it.
 *
 * <p>The configuration file stays as the fallback rather than being copied into
 * the row on first boot. A fresh install then behaves exactly as it always did,
 * and an administrator who has never opened the screen has nothing stored that
 * could disagree with the file.
 *
 * <p>⚠️ Secrets are stored sealed and are <b>never</b> returned. What a reader
 * gets is whether each one is present, which is what a screen needs to say
 * "configured" without a length or a prefix that narrows a guess. Leaving the
 * token field empty on a save keeps the one already in force — an edit of the
 * URL must not silently clear the credential.
 */
public class OmConnectionStore {

  private final Jdbi jdbi;
  private final SecretBox secrets;
  private final OpenMetadataConfiguration configured;

  public OmConnectionStore(Jdbi jdbi, SecretBox secrets, OpenMetadataConfiguration configured) {
    this.jdbi = jdbi;
    this.secrets = secrets;
    this.configured = configured;
  }

  /** Refused when what was typed could not be connected to. */
  public static class InvalidConnectionException extends RuntimeException {
    public InvalidConnectionException(String message) {
      super(message);
    }
  }

  /**
   * The connection in force, with both secrets reduced to present / absent.
   *
   * @param source where each value came from, so the screen can say so
   * @param updatedAt null while the configuration file is still deciding
   */
  public record Connection(
      String baseUrl,
      String expectedVersion,
      boolean failOnVersionMismatch,
      int connectTimeoutMs,
      int readTimeoutMs,
      boolean hasToken,
      boolean hasWebhookSecret,
      String source,
      Instant updatedAt,
      String updatedBy) {

    /** True while nobody has changed it from what the file said. */
    public boolean isDefault() {
      return updatedAt == null;
    }
  }

  /** What an administrator typed. A null field means "leave this as it is". */
  public record Edit(
      String baseUrl,
      String jwtToken,
      String webhookSecret,
      String expectedVersion,
      Boolean failOnVersionMismatch,
      Integer connectTimeoutMs,
      Integer readTimeoutMs,
      String reason) {}

  /** The secrets themselves, for the client alone. Never leaves the backend. */
  public record Credentials(String baseUrl, String jwtToken, String webhookSecret) {}

  // ------------------------------------------------------------------ reads

  /** The connection as the platform is actually running it. */
  public Connection current() {
    return jdbi.withHandle(this::read);
  }

  private Connection read(Handle handle) {
    return handle
        .createQuery(
            """
            SELECT base_url, jwt_token_cipher, webhook_secret_cipher, expected_version,
                   fail_on_version_mismatch, connect_timeout_ms, read_timeout_ms,
                   updated_at, updated_by
              FROM om_connection
            """)
        .map(
            (rs, ctx) ->
                new Connection(
                    rs.getString("base_url"),
                    rs.getString("expected_version"),
                    rs.getBoolean("fail_on_version_mismatch"),
                    rs.getInt("connect_timeout_ms"),
                    rs.getInt("read_timeout_ms"),
                    // A stored row may still be leaning on the environment for
                    // its token, which is how an estate that injects the
                    // credential at deploy time moves the URL without moving
                    // the secret into a form.
                    present(rs.getString("jwt_token_cipher")) || present(configured.getJwtToken()),
                    present(rs.getString("webhook_secret_cipher"))
                        || present(configured.getWebhookSecret()),
                    "database",
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getString("updated_by")))
        .findOne()
        .orElseGet(this::fromConfiguration);
  }

  private Connection fromConfiguration() {
    return new Connection(
        trimUrl(configured.getBaseUrl()),
        configured.getExpectedVersion(),
        configured.isFailOnVersionMismatch(),
        configured.getConnectTimeoutMs(),
        configured.getReadTimeoutMs(),
        present(configured.getJwtToken()),
        present(configured.getWebhookSecret()),
        "configuration file",
        null,
        null);
  }

  /**
   * The connection including its secrets, for handing to the client.
   *
   * <p>A stored row that carries no token falls back to the configured one
   * rather than to nothing: clearing the credential is not what moving the URL
   * meant, and an instance that suddenly answers 401 for every crawl is a
   * failure that takes a day to attribute.
   */
  public Credentials credentials() {
    Optional<String[]> stored =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        "SELECT base_url, jwt_token_cipher, webhook_secret_cipher"
                            + " FROM om_connection")
                    .map(
                        (rs, ctx) ->
                            new String[] {
                              rs.getString("base_url"),
                              rs.getString("jwt_token_cipher"),
                              rs.getString("webhook_secret_cipher")
                            })
                    .findOne());
    if (stored.isEmpty()) {
      return new Credentials(
          trimUrl(configured.getBaseUrl()),
          configured.getJwtToken(),
          configured.getWebhookSecret());
    }
    String[] row = stored.get();
    return new Credentials(
        row[0],
        present(row[1]) ? secrets.open(row[1]) : configured.getJwtToken(),
        present(row[2]) ? secrets.open(row[2]) : configured.getWebhookSecret());
  }

  // ----------------------------------------------------------------- writes

  /**
   * Stores a new connection and returns what is now in force.
   *
   * <p>Whether the instance actually answers is checked by the caller, before
   * this is reached. Two reasons it is not checked here: a probe inside a write
   * transaction holds a connection open for the length of a network timeout,
   * and an administrator repointing at an instance that is temporarily down
   * still has a right to save the correct value.
   */
  public Connection save(Edit edit, String actor, String clientIp) {
    Connection before = current();
    String url = requireUrl(edit.baseUrl() == null ? before.baseUrl() : edit.baseUrl());
    String version =
        edit.expectedVersion() == null ? before.expectedVersion() : blankToNull(edit.expectedVersion());
    boolean strict =
        edit.failOnVersionMismatch() == null
            ? before.failOnVersionMismatch()
            : edit.failOnVersionMismatch();
    int connect = timeout(edit.connectTimeoutMs(), before.connectTimeoutMs(), 100, 120_000, "connect");
    int read = timeout(edit.readTimeoutMs(), before.readTimeoutMs(), 100, 600_000, "read");

    String tokenCipher = sealed(edit.jwtToken());
    String webhookCipher = sealed(edit.webhookSecret());

    jdbi.useTransaction(
        handle -> {
          handle
              .createUpdate(
                  """
                  INSERT INTO om_connection
                      (id, base_url, jwt_token_cipher, webhook_secret_cipher, expected_version,
                       fail_on_version_mismatch, connect_timeout_ms, read_timeout_ms,
                       updated_at, updated_by)
                  VALUES (true, :url, :token, :webhook, :version, :strict, :connect, :read,
                          now(), :actor)
                  ON CONFLICT (id) DO UPDATE
                     SET base_url                 = excluded.base_url,
                         -- COALESCE, not excluded: an edit that leaves the
                         -- token empty means "keep the one in force", and
                         -- overwriting it with null would turn a URL change
                         -- into an outage nobody connected to the URL change.
                         jwt_token_cipher         = COALESCE(:token, om_connection.jwt_token_cipher),
                         webhook_secret_cipher    = COALESCE(:webhook,
                                                             om_connection.webhook_secret_cipher),
                         expected_version         = excluded.expected_version,
                         fail_on_version_mismatch = excluded.fail_on_version_mismatch,
                         connect_timeout_ms       = excluded.connect_timeout_ms,
                         read_timeout_ms          = excluded.read_timeout_ms,
                         updated_at               = now(),
                         updated_by               = excluded.updated_by
                  """)
              .bind("url", url)
              .bind("token", tokenCipher)
              .bind("webhook", webhookCipher)
              .bind("version", version)
              .bind("strict", strict)
              .bind("connect", connect)
              .bind("read", read)
              .bind("actor", actor)
              .execute();

          handle
              .createUpdate(
                  """
                  INSERT INTO audit_connection_change
                      (actor, target, summary, reason, client_ip)
                  VALUES (:actor, 'OPENMETADATA', :summary, :reason, CAST(:ip AS inet))
                  """)
              .bind("actor", actor == null ? "unknown" : actor)
              .bind("summary", summarise(before, url, tokenCipher != null, webhookCipher != null))
              .bind("reason", blankToNull(edit.reason()))
              .bind("ip", ClientAddress.normalise(clientIp))
              .execute();
        });
    return current();
  }

  /**
   * What changed, in words, for the trail.
   *
   * <p>Names the URLs because those are the thing an auditor has to be able to
   * follow, and says only <i>that</i> a credential was replaced, because a
   * trail that quoted one would be a second copy of it in a table nobody
   * thinks of as holding secrets.
   */
  private static String summarise(
      Connection before, String url, boolean newToken, boolean newWebhookSecret) {
    StringBuilder out = new StringBuilder();
    if (!url.equals(before.baseUrl())) {
      out.append("base URL ").append(before.baseUrl()).append(" -> ").append(url);
    }
    if (newToken) {
      out.append(out.isEmpty() ? "" : "; ").append("bot token replaced");
    }
    if (newWebhookSecret) {
      out.append(out.isEmpty() ? "" : "; ").append("webhook secret replaced");
    }
    return out.isEmpty() ? "settings updated at " + url : out.toString();
  }

  private String sealed(String plaintext) {
    if (!present(plaintext)) {
      return null;
    }
    if (!secrets.available()) {
      throw new InvalidConnectionException(
          "This platform cannot store a credential: " + secrets.problem());
    }
    return secrets.seal(plaintext.trim());
  }

  // ------------------------------------------------------------- validation

  /**
   * The console root, checked.
   *
   * <p>Private and loopback addresses are allowed, unlike the personal LLM
   * gateway: this is the platform's own catalog, named by an administrator, and
   * an estate that runs OpenMetadata beside it on the same host is the ordinary
   * case rather than an attempt to reach something internal.
   */
  public static String requireUrl(String raw) {
    String trimmed = trimUrl(raw);
    if (trimmed == null) {
      throw new InvalidConnectionException(
          "An OpenMetadata address is required, like http://openmetadata.example.com:8585");
    }
    URI uri;
    try {
      uri = new URI(trimmed);
    } catch (URISyntaxException e) {
      throw new InvalidConnectionException("'" + raw + "' is not an address");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) {
      throw new InvalidConnectionException(
          "The address must start with http:// or https://, not '" + raw + "'");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new InvalidConnectionException("'" + raw + "' has no host in it");
    }
    if (present(uri.getQuery()) || present(uri.getFragment())) {
      throw new InvalidConnectionException("Give the console root only, with no ? or # on the end");
    }
    // The client appends /api itself, and the asset link needs the root a
    // browser opens. Pasting the API root is the single likeliest mistake --
    // it is the URL a developer has in their history -- and it fails as a
    // 404 on every call rather than as anything that says "wrong URL".
    String path = uri.getPath() == null ? "" : uri.getPath();
    if (path.equals("/api") || path.endsWith("/api")) {
      throw new InvalidConnectionException(
          "Give the console address, without /api on the end: ARAK adds that itself");
    }
    return trimmed;
  }

  private static int timeout(Integer typed, int current, int min, int max, String what) {
    if (typed == null) {
      return current;
    }
    if (typed < min || typed > max) {
      throw new InvalidConnectionException(
          "The " + what + " timeout must be between " + min + " and " + max + " milliseconds");
    }
    return typed;
  }

  /** Trailing slashes off, so the link builder does not produce a double one. */
  static String trimUrl(String raw) {
    String trimmed = raw == null ? null : raw.trim();
    while (trimmed != null && trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return present(trimmed) ? trimmed : null;
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  private static String blankToNull(String value) {
    return present(value) ? value.trim() : null;
  }
}
