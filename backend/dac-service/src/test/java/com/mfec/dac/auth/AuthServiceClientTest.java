package com.mfec.dac.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthServiceClientTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KEY = "test-api-key-not-a-real-one";

  /** Answers with one status and remembers what it was asked. */
  private static final class Answering implements AuthServiceClient.Transport {
    final List<String> calls = new ArrayList<>();
    final int status;

    Answering(int status) {
      this.status = status;
    }

    @Override
    public int postJson(URI uri, String apiKey, String body) {
      calls.add(uri + " " + apiKey + " " + body);
      return status;
    }
  }

  @Test
  @DisplayName("asks POST /signin once, with this application's key and the email")
  void asks() {
    Answering service = new Answering(200);
    AuthServiceClient client =
        new AuthServiceClient("https://auth.example.test/api//", KEY, service, JSON);

    assertThat(client.signIn("analyst_a@example.com")).isEqualTo(AuthServiceClient.Verdict.ALLOWED);
    assertThat(service.calls)
        .containsExactly(
            "https://auth.example.test/api/signin " + KEY + " {\"email\":\"analyst_a@example.com\"}");
  }

  @Test
  @DisplayName("401 means the service knows them as not allowed")
  void notAllowed() {
    assertThat(new AuthServiceClient("https://auth.example.test", KEY, new Answering(401), JSON)
            .signIn("analyst_a@example.com"))
        .isEqualTo(AuthServiceClient.Verdict.NOT_ALLOWED);
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 404, 429, 500, 502, 302})
  @DisplayName("anything else means the service could not be asked, never that they are allowed")
  void unavailable(int status) {
    assertThat(new AuthServiceClient("https://auth.example.test", KEY, new Answering(status), JSON)
            .signIn("analyst_a@example.com"))
        .isEqualTo(AuthServiceClient.Verdict.UNAVAILABLE);
  }

  @Test
  @DisplayName("a service that cannot be reached is unavailable")
  void unreachable() {
    AuthServiceClient.Transport down =
        (uri, apiKey, body) -> {
          throw new IOException("connection refused");
        };
    assertThat(new AuthServiceClient("https://auth.example.test", KEY, down, JSON)
            .signIn("analyst_a@example.com"))
        .isEqualTo(AuthServiceClient.Verdict.UNAVAILABLE);
  }

  @Test
  @DisplayName("an address with no key fails closed without calling anybody")
  void addressWithoutKey() {
    Answering service = new Answering(200);
    AuthServiceClient client = new AuthServiceClient("https://auth.example.test", " ", service, JSON);

    assertThat(client.isConfigured()).isTrue();
    assertThat(client.signIn("analyst_a@example.com"))
        .isEqualTo(AuthServiceClient.Verdict.UNAVAILABLE);
    assertThat(service.calls).isEmpty();
  }

  @Test
  @DisplayName("no address means no service to ask")
  void notConfigured() {
    Answering service = new Answering(200);
    AuthServiceClient client = new AuthServiceClient("  ", KEY, service, JSON);

    assertThat(client.isConfigured()).isFalse();
    assertThat(client.signIn("analyst_a@example.com"))
        .isEqualTo(AuthServiceClient.Verdict.NOT_CONFIGURED);
    assertThat(service.calls).isEmpty();
  }
}
