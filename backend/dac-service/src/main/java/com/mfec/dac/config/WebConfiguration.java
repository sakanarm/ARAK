package com.mfec.dac.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * Where the built single-page app lives, when this process is the one serving it.
 *
 * <p>Blank in development, where Vite serves the app and proxies {@code /api}
 * here. Set in a deployment that has no web server of its own in front of the
 * app — which is the shape of the target estate: one process per application on
 * one port, behind a shared nginx that only routes. Serving the app from the
 * same connector as the API meets that convention without a second process
 * whose only job would be handing over static files.
 *
 * <p>A directory on disk rather than files baked into the jar, deliberately.
 * The front end and the back end are then deployable separately, and a CSS fix
 * does not mean re-running a ten-minute Maven build on a machine that has no
 * Maven.
 */
@Getter
@Setter
public class WebConfiguration {

  /** Absolute path of the directory holding {@code index.html}. Blank disables serving. */
  @JsonProperty("root")
  private String root = "";

  /**
   * The path the app is mounted at as the browser sees it — {@code /Arak/}
   * behind a path-prefix proxy, {@code /} on its own host.
   *
   * <p>This process never sees that prefix, because the proxy strips it, so
   * nothing here routes on it. It is declared so that startup can compare it
   * with what the bundle was actually built for and say so when they disagree.
   * That mismatch is otherwise a blank page with a 404 in the console, and the
   * cause — a build made for the wrong mount point — is nowhere in the symptom.
   */
  @JsonProperty("basePath")
  private String basePath = "/";

  /**
   * How long a fingerprinted asset may be cached, in seconds.
   *
   * <p>A year, because Vite puts a content hash in every emitted filename: the
   * name changes when the bytes do, so the old answer is never the wrong one.
   * {@code index.html} is exempt in the servlet, being the one file whose name
   * stays put.
   */
  @Min(0)
  @JsonProperty("assetCacheSeconds")
  private int assetCacheSeconds = 31_536_000;

  /** True when this process should serve the app as well as the API. */
  public boolean isEnabled() {
    return root != null && !root.isBlank();
  }
}
