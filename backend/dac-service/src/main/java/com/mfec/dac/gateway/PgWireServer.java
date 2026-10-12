package com.mfec.dac.gateway;

import com.mfec.dac.config.GatewayConfiguration;
import com.mfec.dac.policy.DecisionService;
import com.mfec.dac.policy.QueryService;
import com.mfec.dac.purpose.PurposeStore;
import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.CredentialResolver;
import io.dropwizard.lifecycle.Managed;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ARAK Gateway's listening port (FR-19, M29a): accepts PostgreSQL clients
 * and hands each connection to a {@link PgSession} on its own virtual thread.
 *
 * <p>Refuses to start rather than start weaker than configured: with
 * {@code requireTls} on and no certificate, or with no catalog sandbox, the
 * service stops with a message that says which setting is missing.
 */
public class PgWireServer implements Managed {

  private static final Logger LOG = LoggerFactory.getLogger(PgWireServer.class);

  /** What a session needs from the service. */
  record Context(
      GatewayConfiguration config,
      SSLContext ssl,
      LoginThrottle throttle,
      AuthEventLog authLog,
      AccessTokenStore tokens,
      DataSourceStore sources,
      UserSlots userSlots,
      PurposeStore purposes,
      SandboxCatalog sandbox,
      QueryService queries) {}

  /** Connections per account, so one person's tool cannot take every connection. */
  static final class UserSlots {
    private final int perUser;
    private final Map<String, Integer> held = new ConcurrentHashMap<>();

    UserSlots(int perUser) {
      this.perUser = perUser;
    }

    boolean acquire(String principal) {
      String key = principal.toLowerCase(java.util.Locale.ROOT);
      boolean[] ok = {false};
      held.compute(
          key,
          (k, n) -> {
            int now = n == null ? 0 : n;
            if (now >= perUser) {
              return n;
            }
            ok[0] = true;
            return now + 1;
          });
      return ok[0];
    }

    void release(String principal) {
      String key = principal.toLowerCase(java.util.Locale.ROOT);
      held.computeIfPresent(key, (k, n) -> n <= 1 ? null : n - 1);
    }
  }

  private final GatewayConfiguration config;
  private final Context context;
  private final Semaphore connections;
  private ServerSocket listener;
  private ExecutorService workers;
  private Thread acceptor;
  private volatile boolean running;

  public PgWireServer(
      GatewayConfiguration config,
      Jdbi app,
      AccessTokenStore tokens,
      AuthEventLog authLog,
      DataSourceStore sources,
      PurposeStore purposes,
      DecisionService decisions,
      QueryService queries,
      CredentialResolver credentials) {
    this.config = config;
    SSLContext ssl = tls(config, credentials);
    if (config.isRequireTls() && ssl == null) {
      throw new IllegalStateException(
          "gateway.requireTls is on but gateway.keyStorePath is not set; give the gateway a "
              + "certificate, or turn requireTls off for a local trial only");
    }
    if (blank(config.getSandboxJdbcUrl()) || blank(config.getSandboxCredentialRef())) {
      throw new IllegalStateException(
          "gateway.sandboxJdbcUrl and gateway.sandboxCredentialRef must both be set: without the "
              + "catalog sandbox, client tools cannot list tables and will not connect");
    }
    CredentialResolver.Credential builder;
    try {
      builder = credentials.resolve(config.getSandboxCredentialRef());
    } catch (CredentialResolver.UnresolvableCredentialException e) {
      throw new IllegalStateException("gateway.sandboxCredentialRef: " + e.getMessage(), e);
    }
    SandboxCatalog sandbox =
        new SandboxCatalog(
            app,
            decisions,
            config.getSandboxJdbcUrl(),
            builder.username(),
            builder.password(),
            config.getSandboxReaderRole(),
            config.getStatementTimeoutSeconds(),
            config.getMaxRows());
    this.context =
        new Context(
            config,
            ssl,
            new LoginThrottle(
                config.getMaxFailuresPerAddress(),
                Duration.ofSeconds(config.getFailureWindowSeconds()),
                Clock.systemUTC()),
            authLog,
            tokens,
            sources,
            new UserSlots(config.getMaxConnectionsPerUser()),
            purposes,
            sandbox,
            queries);
    this.connections = new Semaphore(config.getMaxConnections());
  }

  private static SSLContext tls(GatewayConfiguration config, CredentialResolver credentials) {
    if (blank(config.getKeyStorePath())) {
      return null;
    }
    char[] password;
    try {
      // The reference resolves to user:password; only the password part is used.
      password = credentials.resolve(config.getKeyStorePassword()).password().toCharArray();
    } catch (CredentialResolver.UnresolvableCredentialException e) {
      throw new IllegalStateException("gateway.keyStorePassword: " + e.getMessage(), e);
    }
    try (InputStream in = Files.newInputStream(Path.of(config.getKeyStorePath()))) {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(in, password);
      KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keys.init(store, password);
      SSLContext ssl = SSLContext.getInstance("TLS");
      ssl.init(keys.getKeyManagers(), null, null);
      return ssl;
    } catch (Exception e) {
      throw new IllegalStateException(
          "gateway.keyStorePath could not be loaded as a PKCS#12 keystore: " + e.getMessage(), e);
    } finally {
      java.util.Arrays.fill(password, '\0');
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  /** The port actually bound; differs from the configured one when that is 0. */
  public int port() {
    return listener == null ? -1 : listener.getLocalPort();
  }

  @Override
  public void start() throws IOException {
    listener = new ServerSocket();
    listener.setReuseAddress(true);
    listener.bind(new InetSocketAddress(InetAddress.getByName(config.getBindHost()), config.getPort()), 128);
    workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("arak-gateway-", 0).factory());
    running = true;
    acceptor = Thread.ofPlatform().name("arak-gateway-accept").daemon(true).start(this::accept);
    LOG.info(
        "ARAK gateway listening on {}:{} (TLS {})",
        config.getBindHost(),
        port(),
        context.ssl() == null ? "off" : config.isRequireTls() ? "required" : "offered");
  }

  private void accept() {
    while (running) {
      Socket socket;
      try {
        socket = listener.accept();
      } catch (IOException e) {
        if (running) {
          LOG.warn("Gateway accept failed: {}", e.toString());
        }
        continue;
      }
      if (!connections.tryAcquire()) {
        refuse(socket);
        continue;
      }
      try {
        workers.execute(
            () -> {
              try {
                new PgSession(context, socket).run();
              } finally {
                connections.release();
              }
            });
      } catch (RuntimeException e) {
        connections.release();
        refuse(socket);
      }
    }
  }

  /** Turns a connection away when the gateway is full, as PostgreSQL does. */
  private static void refuse(Socket socket) {
    try (socket) {
      socket.setSoTimeout(2_000);
      PgWire.Writer out = new PgWire.Writer(socket.getOutputStream());
      // Before the startup packet the client cannot read an ErrorResponse over
      // TLS, but a plain one is what PostgreSQL itself sends here.
      out.error(PgError.fatal(PgError.TOO_MANY_CONNECTIONS, "sorry, too many clients already"));
      out.flush();
    } catch (IOException ignored) {
      // Gone already.
    }
  }

  @Override
  public void stop() throws Exception {
    running = false;
    if (listener != null) {
      listener.close();
    }
    if (workers != null) {
      workers.shutdownNow();
      workers.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
