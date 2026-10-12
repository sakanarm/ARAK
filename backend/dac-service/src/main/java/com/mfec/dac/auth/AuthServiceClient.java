package com.mfec.dac.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the organisation's shared auth service whether a person Microsoft has
 * just signed in may use this application, and has it record the sign-in.
 *
 * <p>One call per Microsoft sign-in: {@code POST /signin} with this
 * application's key. A password sign-in is not reported here, so one person
 * signing in once is one row in the service's log.
 *
 * <p>The answer keeps "not allowed" apart from "could not ask". Microsoft has
 * already proved who the person is; telling them they are unknown because the
 * service did not answer sends them to the wrong person for help.
 */
public class AuthServiceClient {

  private static final Logger LOG = LoggerFactory.getLogger(AuthServiceClient.class);

  public enum Verdict {
    /** The service let them in and recorded it. */
    ALLOWED,
    /** The service knows them as not set up for this application, or blocked. */
    NOT_ALLOWED,
    /** The service could not be asked, or refused this application's key. */
    UNAVAILABLE,
    /** No service is configured; ARAK's own accounts decide alone. */
    NOT_CONFIGURED
  }

  /** The one call this makes; replaced in tests. */
  public interface Transport {
    int postJson(URI uri, String apiKey, String body) throws IOException;
  }

  private final URI signinUri;
  private final String apiKey;
  private final Transport transport;
  private final ObjectMapper json;

  /**
   * @param baseUrl the service's address; blank means there is none
   * @param apiKey this application's key for it
   */
  public AuthServiceClient(String baseUrl, String apiKey, Transport transport, ObjectMapper json) {
    if (baseUrl == null || baseUrl.isBlank()) {
      this.signinUri = null;
    } else {
      String trimmed = baseUrl.trim();
      while (trimmed.endsWith("/")) {
        trimmed = trimmed.substring(0, trimmed.length() - 1);
      }
      this.signinUri = URI.create(trimmed + "/signin");
    }
    this.apiKey = apiKey;
    this.transport = transport;
    this.json = json;
  }

  public boolean isConfigured() {
    return signinUri != null;
  }

  public Verdict signIn(String email) {
    if (signinUri == null) {
      return Verdict.NOT_CONFIGURED;
    }
    if (apiKey == null || apiKey.isBlank()) {
      // Configured to ask but given nothing to ask with: fail closed, and say
      // so where whoever set it up will look.
      LOG.error("AUTH_SERVICE_URL is set but AUTH_SERVICE_API_KEY is not; Microsoft sign-in is refused");
      return Verdict.UNAVAILABLE;
    }
    int status;
    try {
      status = transport.postJson(signinUri, apiKey, json.writeValueAsString(Map.of("email", email)));
    } catch (IOException e) {
      LOG.warn("The auth service could not be reached: {}", e.getMessage());
      return Verdict.UNAVAILABLE;
    }
    if (status == 200) {
      return Verdict.ALLOWED;
    }
    if (status == 401) {
      return Verdict.NOT_ALLOWED;
    }
    if (status == 404) {
      LOG.error(
          "The auth service answered 404 to /signin: it predates that endpoint, or "
              + "AUTH_SERVICE_URL points somewhere else. Microsoft sign-in is refused until it is fixed");
    } else if (status == 403) {
      LOG.error("The auth service refused this application's key (403); check AUTH_SERVICE_API_KEY");
    } else {
      LOG.warn("The auth service answered HTTP {} to /signin", status);
    }
    return Verdict.UNAVAILABLE;
  }

  /** The real transport: the JDK client, no redirects followed, short timeouts. */
  public static Transport httpTransport() {
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    return (uri, apiKey, body) -> {
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .header("x-api-key", apiKey)
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      return EntraLogin.send(client, request).status();
    };
  }
}
