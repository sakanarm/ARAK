package com.mfec.dac.om;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

/**
 * What happens when OpenMetadata is not there.
 *
 * <p>An unreachable catalog must not stop the platform from starting. Policy
 * decisions are served from our own cache precisely so that an outage in the
 * catalog is not an outage in data access (NFR-3), and a service that refuses
 * to boot has already failed that before the first request arrives.
 *
 * <p>The regression these cover is narrow and easy to reintroduce: the
 * generated client reports an HTTP-level problem as {@code ApiException}, but a
 * connection that never opens surfaces as {@code jakarta.ws.rs.ProcessingException},
 * which is unchecked. Catching only the first compiles, passes every test that
 * has a server to talk to, and turns a missing catalog into a crash on startup.
 */
class OpenMetadataClientVersionTest {

  /** A port with nothing behind it, so connecting is refused rather than hanging. */
  private static String deadUrl() throws IOException {
    int port;
    try (ServerSocket probe = new ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    return "http://127.0.0.1:" + port;
  }

  @Test
  void readVersionReturnsNullWhenTheInstanceCannotBeReached() throws IOException {
    OpenMetadataClient client = new OpenMetadataClient(deadUrl(), "", "2.0.1", 1_000, 1_000);

    assertThat(client.readVersion()).isNull();
  }

  @Test
  void checkVersionWarnsRatherThanThrowingWhenNotAskedToFail() throws IOException {
    OpenMetadataClient client = new OpenMetadataClient(deadUrl(), "", "2.0.1", 1_000, 1_000);

    assertThat(client.checkVersion(false)).isNull();
  }

  @Test
  void checkVersionStillFailsWhenTheOperatorAskedItTo() throws IOException {
    OpenMetadataClient client = new OpenMetadataClient(deadUrl(), "", "2.0.1", 1_000, 1_000);

    // The distinction matters: unreachable is tolerated by default, but an
    // operator who pinned the deployment asked to be stopped, not warned.
    assertThatThrownBy(() -> client.checkVersion(true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not report a version");
  }
}
