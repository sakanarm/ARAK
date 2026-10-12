package com.mfec.dac.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * The ARAK Gateway (FR-19, M29a): a port that speaks the PostgreSQL wire
 * protocol, so DBeaver, Power BI, Excel, Tableau and psql can read through the
 * query proxy as the person signed in.
 *
 * <p>Off unless {@code enabled} is set. A listening port is a new way in, so it
 * is opened only on purpose, and only with TLS: {@code keyStorePath} holds the
 * server certificate (PKCS#12) and {@code keyStorePassword} is a credential
 * reference ({@code env:}, {@code fernet:}, {@code vault://}), never the
 * password itself. With {@code requireTls} on, a client that does not ask for
 * TLS is turned away before it can send a password.
 *
 * <p>Tools ask the server about itself and its tables before they read
 * anything: {@code pg_catalog}, {@code information_schema}, {@code version()}.
 * Those questions are answered by a separate, empty PostgreSQL, the sandbox,
 * never by the source: ARAK keeps there one empty database per shape, the
 * tables a person may read with the columns they may see, and runs the
 * question in it. {@code sandboxJdbcUrl} names it; {@code sandboxCredentialRef}
 * holds the account that builds the shapes, and {@code sandboxReaderRole} the
 * role with no rights of its own that the question runs as. With no sandbox,
 * every catalog question is refused, and most tools then cannot connect.
 */
@Getter
@Setter
public class GatewayConfiguration {

  @JsonProperty("enabled")
  private boolean enabled = false;

  @JsonProperty("bindHost")
  private String bindHost = "127.0.0.1";

  @Min(1)
  @Max(65535)
  @JsonProperty("port")
  private int port = 5439;

  /** PKCS#12 file with the server's certificate and key. */
  @JsonProperty("keyStorePath")
  private String keyStorePath;

  /** A credential reference to the keystore's password. */
  @JsonProperty("keyStorePassword")
  private String keyStorePassword;

  @JsonProperty("requireTls")
  private boolean requireTls = true;

  @Min(1)
  @JsonProperty("maxConnections")
  private int maxConnections = 64;

  @Min(1)
  @JsonProperty("maxConnectionsPerUser")
  private int maxConnectionsPerUser = 8;

  /** How long a client has from connecting to finishing the sign-in. */
  @Min(1)
  @JsonProperty("authTimeoutSeconds")
  private int authTimeoutSeconds = 30;

  /** A connection idle this long is closed. */
  @Min(1)
  @JsonProperty("idleTimeoutSeconds")
  private int idleTimeoutSeconds = 1_800;

  /** The most rows one statement sends back; a BI extract is bigger than a screen. */
  @Min(1)
  @JsonProperty("maxRows")
  private int maxRows = 100_000;

  /** How long the source may take to start answering one statement. */
  @Min(1)
  @JsonProperty("statementTimeoutSeconds")
  private int statementTimeoutSeconds = 120;

  /** Failed sign-ins from one address before it is made to wait. */
  @Min(1)
  @JsonProperty("maxFailuresPerAddress")
  private int maxFailuresPerAddress = 10;

  @Min(1)
  @JsonProperty("failureWindowSeconds")
  private int failureWindowSeconds = 900;

  /** The longest a personal access token may live, in days. */
  @Min(1)
  @Max(366)
  @JsonProperty("maxTokenDays")
  private int maxTokenDays = 90;

  @JsonProperty("sandboxJdbcUrl")
  private String sandboxJdbcUrl;

  @JsonProperty("sandboxCredentialRef")
  private String sandboxCredentialRef;

  @JsonProperty("sandboxReaderRole")
  private String sandboxReaderRole = "arak_gateway_reader";

  /** What tools are told the server is; some read features off it. */
  @JsonProperty("serverVersion")
  private String serverVersion = "16.4 (ARAK Gateway)";
}
