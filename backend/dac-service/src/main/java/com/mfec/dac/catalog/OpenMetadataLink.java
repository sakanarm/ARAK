package com.mfec.dac.catalog;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Where an asset lives in OpenMetadata's own console.
 *
 * <p>ARAK caches what OpenMetadata knows; it does not replace it. The asset
 * page here answers "who may reach this, and why" — and every question it does
 * not answer (lineage, profiles, sample data, the thread hanging off the
 * description) has an answer one click away in the tool that owns it. Without
 * the link, reaching that answer means copying an FQN into another tab, which
 * is the kind of friction that ends with people not checking.
 *
 * <p>The paths are read off the running 2.0.1 console rather than assumed:
 * {@code ROUTES.ENTITY_DETAILS} is {@code /:entityType/:fqn} and
 * {@code ROUTES.SERVICE} is {@code /service/:serviceCategory/:fqn}.
 *
 * <p>Only assets the crawl brought in get a link. One ARAK discovered over JDBC
 * has no page over there, and a link that lands on OpenMetadata's "no such
 * entity" reads as ARAK being broken rather than as the asset being local.
 */
public final class OpenMetadataLink {

  private final Supplier<String> baseUrl;

  /**
   * Reads the console's root at the moment each link is built, so a link is
   * never older than the setting.
   *
   * <p>A supplier rather than a value because the instance is settable while
   * the platform runs: an address captured at startup would keep pointing at
   * the old console until a restart, which is the symptom that made the setting
   * necessary in the first place. A factory rather than a second constructor
   * because {@code new OpenMetadataLink(null)} would otherwise not say which
   * one it meant.
   *
   * @param baseUrl may return null, which means no instance is configured
   */
  public static OpenMetadataLink reading(Supplier<String> baseUrl) {
    return new OpenMetadataLink(baseUrl);
  }

  private OpenMetadataLink(Supplier<String> baseUrl) {
    this.baseUrl = baseUrl;
  }

  /** @param baseUrl a fixed console root, or null when no instance is configured. */
  public OpenMetadataLink(String baseUrl) {
    String fixed = normalise(baseUrl);
    this.baseUrl = () -> fixed;
  }

  private static String normalise(String raw) {
    String trimmed = raw == null ? null : raw.trim();
    while (trimmed != null && trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed == null || trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * The page for one asset, or null if there is not one to link to.
   *
   * @param assetType SERVICE, DATABASE, SCHEMA, TABLE or VIEW
   * @param fqn the OpenMetadata fully qualified name
   * @param fromOpenMetadata whether the crawl is where this asset came from
   */
  public String forAsset(String assetType, String fqn, boolean fromOpenMetadata) {
    String root = normalise(baseUrl.get());
    if (root == null || !fromOpenMetadata || fqn == null || fqn.isBlank()) {
      return null;
    }
    String path =
        switch (assetType == null ? "" : assetType.toUpperCase(Locale.ROOT)) {
          // A view is a table entity in OpenMetadata; only the tableType differs.
          case "TABLE", "VIEW" -> "/table/";
          case "SCHEMA" -> "/databaseSchema/";
          case "DATABASE" -> "/database/";
          // Services are routed by category, and every asset ARAK crawls sits
          // under a database service.
          case "SERVICE" -> "/service/databaseServices/";
          default -> null;
        };
    return path == null ? null : root + path + encode(fqn);
  }

  /**
   * An FQN as a path segment.
   *
   * <p>{@code URLEncoder} is form encoding, so it turns a space into {@code +},
   * which a path reads literally. Dots it leaves alone, which is what keeps the
   * FQN legible in the address bar — and legible matters here, because the
   * first thing anyone does with a link that lands wrong is read the URL.
   */
  private static String encode(String fqn) {
    return URLEncoder.encode(fqn, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
