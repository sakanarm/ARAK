package com.mfec.dac.om.events;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Proves a webhook delivery came from our OpenMetadata (FR-1.5, push side).
 *
 * <p>The receiver is the one endpoint on this service that cannot present a
 * session token, because the caller is OpenMetadata and not a person. The shared
 * secret is what stands in for one. Without it the endpoint would let anyone who
 * can reach the port tell the platform that a table's PII tag had been removed,
 * and the cache would believe it until the next full crawl.
 *
 * <p>OpenMetadata signs the request body with HMAC-SHA256 under the
 * subscription's secret key and sends it in {@code X-OM-Event-Signature}. The
 * encoding has changed between releases and the prefix is not always present, so
 * a signature is accepted if it matches the digest in either hex or base64, with
 * or without the {@code sha256=} prefix. That is not a weakening: each form is a
 * lossless spelling of the same digest, and an attacker who could produce one
 * could produce the other.
 */
public final class EventSignature {

  /** The header OpenMetadata puts the HMAC in. */
  public static final String HEADER = "X-OM-Event-Signature";

  private static final String ALGORITHM = "HmacSHA256";
  private static final String PREFIX = "sha256=";

  private EventSignature() {}

  /**
   * Whether {@code presented} is a valid signature of {@code body}.
   *
   * <p>A blank secret returns false rather than true. The caller decides whether
   * an unconfigured receiver should be open — and it should not — but that
   * decision must be made where it is visible, not hidden in a verifier that
   * quietly passes everything.
   */
  public static boolean matches(String secret, String body, String presented) {
    if (secret == null || secret.isBlank() || presented == null || presented.isBlank()) {
      return false;
    }
    byte[] digest = hmac(secret, body == null ? "" : body);
    String offered = presented.trim();
    if (offered.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
      offered = offered.substring(PREFIX.length());
    }
    return constantTimeEquals(HexFormat.of().formatHex(digest), offered.toLowerCase())
        || constantTimeEquals(Base64.getEncoder().encodeToString(digest), offered);
  }

  /** The signature this service would produce, for tests and for documentation. */
  public static String sign(String secret, String body) {
    return PREFIX + HexFormat.of().formatHex(hmac(secret, body == null ? "" : body));
  }

  private static byte[] hmac(String secret, String body) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("HmacSHA256 is required and this JVM has none", e);
    } catch (java.security.InvalidKeyException e) {
      throw new IllegalArgumentException("The OpenMetadata webhook secret is not a usable key", e);
    }
  }

  /**
   * Comparison that does not return early.
   *
   * <p>{@code equals} on a String stops at the first differing character, and
   * the time that takes tells a caller how much of a guess was right. Over
   * enough attempts that is a signature forged one character at a time.
   */
  private static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
