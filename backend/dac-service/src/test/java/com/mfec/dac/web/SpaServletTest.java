package com.mfec.dac.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the servlet decides a request is, which is the whole of its job.
 *
 * <p>Every path here is either the app or a file, and getting that wrong is
 * silent both ways: a route answered 404 looks like a broken deploy, and a
 * missing asset answered with HTML looks like a browser bug. Neither shows up
 * in a test of the API, which is how {@link #assetFqnIsARoute} shipped.
 */
class SpaServletTest {

  @TempDir Path root;

  private SpaServlet servlet;

  @BeforeEach
  void layOutABundle() throws IOException {
    Files.writeString(root.resolve("index.html"), "<!doctype html><title>app</title>");
    Files.createDirectory(root.resolve("assets"));
    Files.writeString(root.resolve("assets").resolve("index-C0TORp7Z.js"), "console.log(1)");
    Files.writeString(root.resolve("favicon.png"), "not really a png");
    servlet = new SpaServlet(root, 31536000);
  }

  @Nested
  @DisplayName("a path that is not a file")
  class Routes {

    @Test
    @DisplayName("an asset FQN is a route, not a missing file")
    void assetFqnIsARoute() throws Exception {
      // The bug this exists for: half of this app's routes end in a
      // fully-qualified name, and a dot is not an extension. Read as a file,
      // every asset detail page 404s on refresh and on every bookmark -- on
      // the one page a data owner opens most.
      Reply reply = get("/catalog/prod-pg.SalesDB.dbo.customer");

      assertThat(reply.status).isEqualTo(200);
      assertThat(reply.body).contains("<title>app</title>");
    }

    @Test
    @DisplayName("a route with characters no file name may hold is still the app, not a 500")
    void unusualCharacters() throws Exception {
      // Seen live: a link that resolved an absolute URL as a route produced
      // /catalog/http:/host:8585/..., and Windows' Path.resolve threw on the
      // colon. Whatever the router makes of it, the server must not fall over.
      Reply reply = get("/catalog/http:/om.example.test:8585/database/svc.db");

      assertThat(reply.status).isEqualTo(200);
      assertThat(reply.body).contains("<title>app</title>");
      assertThat(get("/catalog/svc:db/app.js").status).isEqualTo(404);
    }

    @Test
    @DisplayName("a plain route is the app")
    void plainRoute() throws Exception {
      assertThat(get("/policies/new").body).contains("<title>app</title>");
    }

    @Test
    @DisplayName("the root is the app")
    void root() throws Exception {
      assertThat(get("/").body).contains("<title>app</title>");
    }

    @Test
    @DisplayName("index.html is never cached, so a deploy is not stuck behind one")
    void indexIsNotCached() throws Exception {
      assertThat(get("/catalog").cacheControl).isEqualTo("no-store, must-revalidate");
    }
  }

  @Nested
  @DisplayName("a path that is a file")
  class Files_ {

    @Test
    @DisplayName("a fingerprinted asset is served and cached hard")
    void asset() throws Exception {
      Reply reply = get("/assets/index-C0TORp7Z.js");

      assertThat(reply.status).isEqualTo(200);
      assertThat(reply.body).isEqualTo("console.log(1)");
      assertThat(reply.contentType).isEqualTo("text/javascript; charset=utf-8");
      assertThat(reply.cacheControl).contains("immutable");
    }

    @Test
    @DisplayName("a missing script is a 404, not the app")
    void missingScript() throws Exception {
      // Returning index.html here would hand the browser HTML where it asked
      // for a module: it fails to parse, and the page is blank with no clue
      // pointing at the deploy that forgot to copy the assets across.
      assertThat(get("/assets/index-deadbeef.js").status).isEqualTo(404);
    }

    @Test
    @DisplayName("a path that climbs out of the bundle is refused")
    void traversal() throws Exception {
      // The process can read the deployment's whole home directory, and the
      // .env with every credential in it sits one level up.
      assertThat(get("/../.env").status).isEqualTo(404);
      assertThat(get("/assets/../../.env").status).isEqualTo(404);
    }
  }

  private Reply get(String path) throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(path);
    when(request.getMethod()).thenReturn("GET");
    when(request.getDateHeader("If-Modified-Since")).thenReturn(-1L);

    Reply reply = new Reply();
    HttpServletResponse response = mock(HttpServletResponse.class);
    Capture capture = new Capture();
    when(response.getOutputStream()).thenReturn(capture);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              reply.status = invocation.getArgument(0);
              return null;
            })
        .when(response)
        .sendError(org.mockito.ArgumentMatchers.anyInt());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if ("Cache-Control".equals(invocation.getArgument(0))) {
                reply.cacheControl = invocation.getArgument(1);
              }
              return null;
            })
        .when(response)
        .setHeader(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              reply.contentType = invocation.getArgument(0);
              return null;
            })
        .when(response)
        .setContentType(org.mockito.ArgumentMatchers.anyString());

    servlet.doGet(request, response);
    reply.body = capture.written.toString(java.nio.charset.StandardCharsets.UTF_8);
    return reply;
  }

  private static final class Reply {
    int status = 200;
    String body = "";
    String contentType;
    String cacheControl;
  }

  private static final class Capture extends ServletOutputStream {
    final ByteArrayOutputStream written = new ByteArrayOutputStream();

    @Override
    public void write(int b) {
      written.write(b);
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setWriteListener(WriteListener listener) {}
  }
}
