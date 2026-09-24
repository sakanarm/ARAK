package com.mfec.dac.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the built single-page app from a directory, beside the API.
 *
 * <p>Two rules, and everything else follows from them.
 *
 * <p><b>A request for a file that exists is that file.</b> Vite fingerprints
 * every asset it emits ({@code index-a91f3c.js}), so those names are immutable
 * and are cached for a year. {@code index.html} is the one file whose name does
 * not change, so it is never cached — caching it is how a deploy goes out and
 * the browser keeps loading last week's bundle.
 *
 * <p><b>A request for a path that does not exist is the app.</b> The router
 * lives in the browser, so {@code /catalog/table/x} is not a file and never
 * will be; answering 404 there would break every bookmark and every refresh.
 * The exception is a path that names a file type — a missing
 * {@code .js} is a broken deploy and should say so, not return HTML that the
 * browser will then fail to parse as a script.
 *
 * <p>Which makes "names a file type" load-bearing, and it is the one thing
 * here that cannot be decided by looking for a dot. Half the routes in this
 * app end in a fully-qualified name —
 * {@code /catalog/prod-pg.SalesDB.dbo.customer} — and a dot rule reads every
 * one of them as a missing asset. The rule is therefore an extension this
 * servlet can actually serve, which is a closed list a few lines down.
 *
 * <p>Paths under {@code /api} never reach here: Jersey is mounted there by
 * {@code rootPath} and Jetty prefers the more specific mapping.
 */
public class SpaServlet extends HttpServlet {

  private static final long serialVersionUID = 1L;

  private final Path root;
  private final Path index;
  private final int cacheSeconds;

  public SpaServlet(Path root, int cacheSeconds) {
    this.root = root.toAbsolutePath().normalize();
    this.index = this.root.resolve("index.html");
    this.cacheSeconds = cacheSeconds;
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    Path file = resolve(request);

    if (file == null) {
      // Either the path escaped the root or it names a file type that is not
      // there. Both are 404; neither is the app.
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }

    boolean isIndex = file.equals(index);
    if (isIndex) {
      response.setHeader("Cache-Control", "no-store, must-revalidate");
    } else {
      response.setHeader("Cache-Control", "public, max-age=" + cacheSeconds + ", immutable");
    }

    long modified = Files.getLastModifiedTime(file).toMillis();
    // Truncated to the second, because that is the resolution the header has:
    // comparing a millisecond mtime against it makes every request a miss.
    modified -= modified % 1000;
    long since = request.getDateHeader("If-Modified-Since");
    if (!isIndex && since != -1 && modified <= since) {
      response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
      return;
    }
    response.setDateHeader("Last-Modified", modified);
    response.setContentType(contentType(file));
    response.setContentLengthLong(Files.size(file));

    if ("HEAD".equalsIgnoreCase(request.getMethod())) {
      return;
    }
    try (OutputStream out = response.getOutputStream()) {
      Files.copy(file, out);
    }
  }

  @Override
  protected void doHead(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    doGet(request, response);
  }

  /**
   * The file this request should be answered with, or null for a 404.
   *
   * <p>The traversal check is the reason this does not simply concatenate: the
   * servlet is mounted at the root of a process that can read the deployment's
   * whole home directory, and {@code /../../.env} is one string away.
   */
  private Path resolve(HttpServletRequest request) {
    String path = request.getPathInfo();
    if (path == null || path.isEmpty()) {
      path = request.getServletPath();
    }
    if (path == null || path.isEmpty() || "/".equals(path)) {
      return Files.isReadable(index) ? index : null;
    }

    Path candidate;
    try {
      candidate = root.resolve(path.substring(1)).normalize();
    } catch (InvalidPathException e) {
      // Windows refuses characters a route may legitimately hold -- a ':' in
      // an FQN, or a link that pasted a whole URL onto the end of one -- where
      // Linux would simply find no such file. Either way it is not a file, so
      // it is answered the way every other non-file is, not with a 500.
      return looksLikeFile(path) ? null : (Files.isReadable(index) ? index : null);
    }
    if (!candidate.startsWith(root)) {
      return null;
    }
    if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
      return candidate;
    }
    // Not a file. A route gets the app; a missing asset gets a 404.
    return looksLikeFile(path) ? null : (Files.isReadable(index) ? index : null);
  }

  /**
   * True when the last segment names a file this servlet knows how to serve.
   *
   * <p>Deliberately the same list the {@code Content-Type} comes from: a
   * request this cannot name a type for is a request it could not have
   * answered correctly anyway, so there is nothing to be gained by 404ing it
   * instead of handing back the app. What it buys is that an FQN in a route
   * stays a route — {@code .customer} is not a file type, so
   * {@code /catalog/prod-pg.SalesDB.dbo.customer} reaches the router, while a
   * genuinely missing {@code .js} still fails loudly.
   */
  private static boolean looksLikeFile(String path) {
    int slash = path.lastIndexOf('/');
    // The last dot, not the first, and for the same reason contentType uses
    // the last one: the extension of "app.min.js" is "js".
    int dot = path.lastIndexOf('.');
    if (dot <= slash + 1) {
      return false;
    }
    return TYPES.containsKey(path.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  /**
   * Content types by extension.
   *
   * <p>Not {@link Files#probeContentType}: it consults the host's registry, and
   * on Windows it has been known to answer {@code text/plain} for {@code .js} —
   * which a browser refuses to execute. A deploy should not depend on what a
   * developer's machine believes JavaScript is.
   */
  private static String contentType(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    int dot = name.lastIndexOf('.');
    String extension = dot < 0 ? "" : name.substring(dot + 1);
    return TYPES.getOrDefault(extension, "application/octet-stream");
  }

  private static final Map<String, String> TYPES =
      Map.ofEntries(
          Map.entry("html", "text/html; charset=utf-8"),
          Map.entry("js", "text/javascript; charset=utf-8"),
          Map.entry("mjs", "text/javascript; charset=utf-8"),
          Map.entry("css", "text/css; charset=utf-8"),
          Map.entry("json", "application/json; charset=utf-8"),
          Map.entry("map", "application/json; charset=utf-8"),
          Map.entry("svg", "image/svg+xml"),
          Map.entry("png", "image/png"),
          Map.entry("jpg", "image/jpeg"),
          Map.entry("jpeg", "image/jpeg"),
          Map.entry("gif", "image/gif"),
          Map.entry("webp", "image/webp"),
          Map.entry("avif", "image/avif"),
          Map.entry("ico", "image/x-icon"),
          Map.entry("woff", "font/woff"),
          Map.entry("woff2", "font/woff2"),
          Map.entry("ttf", "font/ttf"),
          Map.entry("txt", "text/plain; charset=utf-8"),
          Map.entry("webmanifest", "application/manifest+json"));
}
