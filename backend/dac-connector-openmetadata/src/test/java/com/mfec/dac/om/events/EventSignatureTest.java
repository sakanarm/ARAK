package com.mfec.dac.om.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * The webhook receiver's only authentication.
 *
 * <p>It is the one endpoint on the platform with no session behind it, so every
 * one of these is a way in: a blank secret that is treated as "accept anything",
 * an encoding OpenMetadata uses that we reject, a body that can be changed after
 * signing.
 */
class EventSignatureTest {

  private static final String SECRET = "unit-test-secret";
  private static final String BODY = "{\"eventType\":\"entityUpdated\"}";

  @Test
  void acceptsWhatItSigns() {
    assertThat(EventSignature.matches(SECRET, BODY, EventSignature.sign(SECRET, BODY))).isTrue();
  }

  @Test
  void acceptsTheSameDigestSpeltInBase64() {
    // OpenMetadata's encoding has varied between releases. Accepting both is not
    // a weakening: each is a lossless spelling of the same HMAC, and rejecting
    // one would take the receiver down on an upgrade.
    assertThat(EventSignature.matches(SECRET, BODY, base64Hmac(SECRET, BODY))).isTrue();
  }

  @Test
  void acceptsHexWithOrWithoutThePrefix() {
    String signed = EventSignature.sign(SECRET, BODY);
    String bare = signed.substring("sha256=".length());

    assertThat(EventSignature.matches(SECRET, BODY, bare)).isTrue();
    assertThat(EventSignature.matches(SECRET, BODY, "SHA256=" + bare.toUpperCase())).isTrue();
  }

  @Test
  void rejectsABodyChangedAfterSigning() {
    String signature = EventSignature.sign(SECRET, BODY);

    // The case that matters: a delivery saying a column's PII tag was removed,
    // signed as something harmless.
    assertThat(EventSignature.matches(SECRET, BODY + " ", signature)).isFalse();
  }

  @Test
  void rejectsADifferentSecret() {
    assertThat(EventSignature.matches("other", BODY, EventSignature.sign(SECRET, BODY))).isFalse();
  }

  @Test
  void rejectsEverythingWhenNoSecretIsConfigured() {
    // Fail closed. An unconfigured receiver that accepted unsigned deliveries
    // would be a hole nothing else on the platform covers, whereas one that
    // accepts nothing only costs the delay until the poller's next tick.
    assertThat(EventSignature.matches(null, BODY, EventSignature.sign(SECRET, BODY))).isFalse();
    assertThat(EventSignature.matches("  ", BODY, "sha256=anything")).isFalse();
  }

  @Test
  void rejectsAMissingSignature() {
    assertThat(EventSignature.matches(SECRET, BODY, null)).isFalse();
    assertThat(EventSignature.matches(SECRET, BODY, "")).isFalse();
  }

  private static String base64Hmac(String secret, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
