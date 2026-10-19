package com.mfec.dac.common.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Databricks, reached through a SQL warehouse over the Unity Catalog.
 *
 * <p>The first engine here that is not a socket to a database server. A
 * workspace answers on HTTPS, one warehouse of it is picked by an HTTP path,
 * and a table has three names above it rather than two: catalog, schema, table.
 * The catalog is what the other engines call the database, so a source names a
 * catalog in the field they use for a database, and the FQN comes out as
 * {@code service.catalog.schema.table} -- the way OpenMetadata's Databricks
 * connector names it, which is what keeps an import and a crawl of one table
 * one asset.
 *
 * <p>Authenticated with a personal access token or a service principal's token:
 * the user name is the literal {@code token} and the password is the token, both
 * held behind the same credential reference as every other source. OAuth is not
 * offered yet.
 *
 * <p>Everything that goes into the URL is checked here. The driver takes its
 * settings as {@code ;name=value} pairs, so a host or a path that carries a
 * semicolon is a way of adding a setting the operator never saw, and the one
 * that matters most is {@code UseNativeQuery}: it is what stops the driver
 * rewriting a statement after the proxy checked it.
 */
public final class DatabricksEngine implements SourceEngine {

  private static final Pattern HOST = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?");
  private static final Pattern HTTP_PATH = Pattern.compile("/sql/[A-Za-z0-9/._-]+");
  private static final Pattern CATALOG = Pattern.compile("[A-Za-z0-9_-]+");

  @Override
  public String id() {
    return "DATABRICKS";
  }

  @Override
  public String displayName() {
    return "Databricks";
  }

  @Override
  public int defaultPort() {
    return 443;
  }

  @Override
  public boolean supportsSchemas() {
    return true;
  }

  @Override
  public String driverClassName() {
    return "com.databricks.client.jdbc.Driver";
  }

  @Override
  public boolean usesHttpPath() {
    return true;
  }

  @Override
  public void requireComplete(JdbcCoordinates coordinates) {
    if (coordinates.httpPath() == null) {
      throw new IllegalArgumentException(
          "A Databricks source needs the HTTP path of its SQL warehouse, like"
              + " /sql/1.0/warehouses/<id>. It is on the warehouse's Connection details tab.");
    }
    if (coordinates.database() == null) {
      throw new IllegalArgumentException(
          "A Databricks source needs the name of the catalog to govern.");
    }
    if (!HOST.matcher(coordinates.host()).matches()) {
      throw new IllegalArgumentException(
          "The host of a Databricks source is a name like dbc-example.cloud.databricks.com,"
              + " without https:// or a path.");
    }
    if (!HTTP_PATH.matcher(coordinates.httpPath()).matches()) {
      throw new IllegalArgumentException(
          "The HTTP path must start with /sql/ and hold only letters, digits and / . _ -");
    }
    if (!CATALOG.matcher(coordinates.database()).matches()) {
      throw new IllegalArgumentException(
          "The catalog name may hold only letters, digits, _ and -.");
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Every setting is stated rather than left to the driver's default, for
   * the reason SQL Server's TLS choice is: a driver upgrade must not be able to
   * change what the rest of the platform relies on.
   *
   * <ul>
   *   <li>{@code UseNativeQuery=1}: the statement is sent exactly as the proxy
   *       wrote it. Left off, the driver may rewrite the text, and a statement
   *       changed after it was checked is a statement nobody checked.
   *   <li>{@code AuthMech=3} is user name and password, the way a token is sent.
   *   <li>{@code ssl=1}: TLS, and the driver verifies the certificate.
   *   <li>{@code ConnCatalog} is where an unqualified table resolves, which is
   *       the catalog the source names. The proxy sends {@code schema.table}
   *       and relies on it.
   *   <li>{@code IgnoreTransactions=1}: a warehouse has none, and the driver
   *       would otherwise refuse the calls that ask for one.
   *   <li>{@code UserAgentEntry} is what the workspace's query history shows
   *       for this connection.
   * </ul>
   */
  @Override
  public String jdbcUrl(JdbcCoordinates coordinates) {
    StringBuilder url =
        new StringBuilder("jdbc:databricks://")
            .append(coordinates.host())
            .append(':')
            .append(coordinates.port())
            .append("/default;transportMode=http;ssl=1;AuthMech=3");
    if (coordinates.httpPath() != null) {
      url.append(";httpPath=").append(coordinates.httpPath());
    }
    if (coordinates.database() != null) {
      url.append(";ConnCatalog=").append(coordinates.database());
    }
    return url.append(";UseNativeQuery=1;IgnoreTransactions=1;UserAgentEntry=arak-dac").toString();
  }

  /** The driver takes {@code UserAgentEntry} from the URL, and rejects both spellings here. */
  @Override
  public boolean readsApplicationName() {
    return false;
  }

  @Override
  public Map<String, String> credentialProperties(String username, String password) {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("UID", username);
    properties.put("PWD", password);
    return properties;
  }

  @Override
  public String dialectId() {
    return "DATABRICKS";
  }

  @Override
  public Set<String> proxyCapabilities() {
    return Set.of(
        Capability.ROW_FILTER,
        Capability.COLUMN_MASK,
        Capability.CELL_MASK,
        Capability.COLUMN_HIDE);
  }

  /**
   * {@inheritDoc}
   *
   * <p>The session is put in UTC so that {@code current_timestamp()} and a
   * timestamp read back mean the same thing whatever the warehouse's default is.
   */
  @Override
  public List<String> sessionSetup() {
    return List.of("SET TIME ZONE 'UTC'");
  }

  /**
   * {@inheritDoc}
   *
   * <p>Not offered. A secure view here would be a Unity Catalog view with
   * grants to groups, which is a different mechanism from the other engines'
   * and has not been worked out; the query proxy is how a Databricks source is
   * governed.
   */
  @Override
  public boolean supportsSecureViews() {
    return false;
  }
}
