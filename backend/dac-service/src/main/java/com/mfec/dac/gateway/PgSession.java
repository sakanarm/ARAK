package com.mfec.dac.gateway;

import com.mfec.dac.policy.QueryService;
import com.mfec.dac.purpose.PurposeStore;
import com.mfec.dac.source.DataSourceStore;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import javax.net.ssl.SSLSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One client connection to the gateway, from the startup packet to the end.
 *
 * <p>The client signs in with its ARAK username and a personal access token
 * for a password, names an ARAK data source as its database, and from then on
 * every statement is either answered here (SET, SHOW, BEGIN and the like,
 * which change nothing anywhere), answered by the catalog sandbox (questions
 * about the server, which hold no data), or sent to the query proxy as the
 * signed-in person, where it is governed and audited like any other read.
 * Anything else is refused: the gateway is read-only, and a statement it
 * cannot place is never sent anywhere.
 */
final class PgSession implements Runnable {

  private static final Logger LOG = LoggerFactory.getLogger(PgSession.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  /** Statements that would change something, refused as a read-only server refuses them. */
  private static final java.util.Set<String> WRITES =
      java.util.Set.of(
          "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT",
          "REVOKE", "COPY", "VACUUM", "ANALYZE", "CLUSTER", "REINDEX", "COMMENT", "LOCK", "CALL",
          "DO", "REFRESH", "SECURITY", "IMPORT", "REASSIGN", "UPSERT");

  /** SELECT ... INTO creates a table; a read-only server refuses it. */
  private static final java.util.regex.Pattern INTO =
      java.util.regex.Pattern.compile("(?i)\\bINTO\\b");

  /** Settings a client may not change through the gateway. */
  private static final java.util.Set<String> FORBIDDEN_SETTINGS =
      java.util.Set.of(
          "role", "authorization", "session_authorization", "session_replication_role",
          "transaction_read_only", "default_transaction_read_only", "row_security");

  private final PgWireServer.Context ctx;
  private Socket socket;
  private InputStream in;
  private PgWire.Writer out;
  private final String clientIp;
  private boolean tls;

  private AccessTokenStore.Holder holder;
  private String principal;
  private DataSourceStore.Source source;
  private String purpose;
  private String defaultSchema = "public";
  private String applicationName = "";
  private final Map<String, String> settings = new LinkedHashMap<>();
  private char status = 'I';
  private boolean skipToSync;
  private SandboxCatalog.Session sandbox;
  private boolean holdsUserSlot;
  /** True once AuthenticationOk is sent; before that a ParameterStatus would break the client. */
  private boolean signedIn;

  private final Map<String, Prepared> statements = new HashMap<>();
  private final Map<String, Portal> portals = new HashMap<>();

  PgSession(PgWireServer.Context ctx, Socket socket) {
    this.ctx = ctx;
    this.socket = socket;
    this.clientIp = socket.getInetAddress().getHostAddress();
  }

  @Override
  public void run() {
    try {
      socket.setTcpNoDelay(true);
      socket.setSoTimeout(ctx.config().getAuthTimeoutSeconds() * 1000);
      in = socket.getInputStream();
      out = new PgWire.Writer(socket.getOutputStream());
      if (!startup()) {
        return;
      }
      socket.setSoTimeout(ctx.config().getIdleTimeoutSeconds() * 1000);
      loop();
    } catch (FatalSignal e) {
      fatal((PgError) e.getCause());
    } catch (PgWire.ProtocolException e) {
      fatal(new PgError(PgError.PROTOCOL_VIOLATION, e.getMessage()));
    } catch (SocketTimeoutException e) {
      LOG.debug("Gateway connection timed out");
    } catch (IOException e) {
      LOG.debug("Gateway connection ended: {}", e.toString());
    } catch (RuntimeException e) {
      LOG.error("Gateway session failed", e);
      fatal(new PgError(PgError.INTERNAL, "The gateway could not continue this session"));
    } finally {
      for (Portal p : portals.values()) {
        p.close();
      }
      portals.clear();
      if (sandbox != null) {
        sandbox.close();
      }
      if (holdsUserSlot) {
        ctx.userSlots().release(principal);
      }
      try {
        socket.close();
      } catch (IOException ignored) {
        // Nothing left to tell anyone.
      }
    }
  }

  // ---------------------------------------------------------------- startup

  /** Reads the startup packet and signs the client in; false when the connection is over. */
  private boolean startup() throws IOException {
    Map<String, String> params;
    while (true) {
      byte[] body = PgWire.readStartup(in);
      PgWire.Reader r = new PgWire.Reader(body);
      int code = r.int32();
      if (code == PgWire.SSL_REQUEST) {
        if (tls) {
          throw new PgWire.ProtocolException("SSL was already negotiated");
        }
        if (ctx.ssl() == null) {
          out.raw('N');
          continue;
        }
        out.raw('S');
        SSLSocket secured =
            (SSLSocket)
                ctx.ssl()
                    .getSocketFactory()
                    .createSocket(socket, clientIp, socket.getPort(), true);
        secured.setUseClientMode(false);
        secured.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
        secured.startHandshake();
        socket = secured;
        in = secured.getInputStream();
        out = new PgWire.Writer(secured.getOutputStream());
        tls = true;
        continue;
      }
      if (code == PgWire.GSSENC_REQUEST) {
        out.raw('N');
        continue;
      }
      if (code == PgWire.CANCEL_REQUEST) {
        // Nothing to cancel: a statement here is read and sent in one go.
        return false;
      }
      if ((code >>> 16) != 3) {
        fatal(PgError.fatal(PgError.FEATURE_NOT_SUPPORTED, "unsupported frontend protocol"));
        return false;
      }
      params = new LinkedHashMap<>();
      List<String> unrecognised = new ArrayList<>();
      while (!r.done()) {
        String key = r.cstring();
        if (key.isEmpty()) {
          break;
        }
        String value = r.cstring();
        if (key.startsWith("_pq_.")) {
          unrecognised.add(key);
        } else {
          params.put(key, value);
        }
      }
      if ((code & 0xffff) > 0 || !unrecognised.isEmpty()) {
        out.negotiateProtocolVersion(0, unrecognised);
      }
      break;
    }

    String user = params.get("user");
    if (user == null || user.isBlank()) {
      fatal(PgError.fatal(PgError.INVALID_AUTHORIZATION, "no PostgreSQL user name specified in startup packet"));
      return false;
    }
    String replication = params.get("replication");
    if (replication != null && !replication.equalsIgnoreCase("false") && !replication.equals("0")) {
      fatal(PgError.fatal(PgError.FEATURE_NOT_SUPPORTED, "the ARAK gateway does not support replication"));
      return false;
    }
    String database = params.getOrDefault("database", user);
    applicationName = params.getOrDefault("application_name", "");

    if (ctx.config().isRequireTls() && !tls) {
      fatal(
          PgError.fatal(
              PgError.INVALID_AUTHORIZATION,
              "the ARAK gateway accepts encrypted connections only; connect with sslmode=require"));
      return false;
    }
    if (!ctx.throttle().allowed(clientIp)) {
      ctx.authLog().record(AuthEventLog.Event.GATEWAY_LOGIN, false, user, null, null,
          "Too many failed sign-ins from this address", applicationName, clientIp);
      fatal(PgError.fatal(PgError.INVALID_AUTHORIZATION, "too many failed sign-ins; try again later"));
      return false;
    }

    out.authCleartextPassword();
    out.flush();
    PgWire.Message reply = PgWire.read(in);
    if (reply == null) {
      return false;
    }
    if (reply.type() != 'p') {
      throw new PgWire.ProtocolException("expected a password message");
    }
    String secret = reply.reader().cstring();
    Optional<AccessTokenStore.Holder> found = ctx.tokens().authenticate(secret);
    secret = null;
    if (found.isEmpty() || !found.get().principalName().equalsIgnoreCase(user)) {
      ctx.throttle().fail(clientIp);
      ctx.authLog().record(AuthEventLog.Event.GATEWAY_LOGIN, false, user,
          found.map(AccessTokenStore.Holder::tokenId).orElse(null), null,
          found.isEmpty()
              ? "The token is unknown, revoked or expired, or its account is disabled"
              : "The token belongs to a different account",
          applicationName, clientIp);
      fatal(PgError.fatal(PgError.INVALID_PASSWORD, "password authentication failed for user \"" + user + "\""));
      return false;
    }
    holder = found.get();
    principal = holder.principalName();

    // Only once the client is known is it told whether the database exists,
    // so the port cannot be used to list source names.
    Optional<DataSourceStore.Source> named =
        ctx.sources().list().stream()
            .filter(s -> s.name().equals(database))
            .findFirst();
    if (named.isEmpty()
        || !named.get().enabled()
        || named.get().engine() != DataSourceStore.Engine.POSTGRES) {
      ctx.authLog().record(AuthEventLog.Event.GATEWAY_LOGIN, false, principal, holder.tokenId(), null,
          "No enabled PostgreSQL source is named as the database", applicationName, clientIp);
      fatal(PgError.fatal(PgError.INVALID_CATALOG, "database \"" + database + "\" does not exist"));
      return false;
    }
    source = named.get();

    if (!ctx.userSlots().acquire(principal)) {
      ctx.authLog().record(AuthEventLog.Event.GATEWAY_LOGIN, false, principal, holder.tokenId(), null,
          "Too many gateway connections for this account", applicationName, clientIp);
      fatal(PgError.fatal(PgError.TOO_MANY_CONNECTIONS, "too many connections for role \"" + user + "\""));
      return false;
    }
    holdsUserSlot = true;

    try {
      for (Map.Entry<String, String> p : startupSettings(params).entrySet()) {
        set(p.getKey(), p.getValue());
      }
    } catch (PgError e) {
      ctx.authLog().record(AuthEventLog.Event.GATEWAY_LOGIN, false, principal, holder.tokenId(), null,
          "A setting in the startup packet was refused: " + e.getMessage(), applicationName, clientIp);
      fatal(PgError.fatal(e.sqlState(), e.getMessage()));
      return false;
    }

    ctx.throttle().succeed(clientIp);
    ctx.authLog().record(AuthEventLog.Event.GATEWAY_LOGIN, true, principal, holder.tokenId(), null,
        purpose == null ? null : "Purpose " + purpose, applicationName, clientIp);
    LOG.info("Gateway sign-in by {} to source {}", principal, source.name());

    out.authOk();
    signedIn = true;
    out.parameterStatus("server_version", ctx.config().getServerVersion());
    out.parameterStatus("server_encoding", "UTF8");
    out.parameterStatus("client_encoding", "UTF8");
    out.parameterStatus("DateStyle", "ISO, MDY");
    out.parameterStatus("IntervalStyle", "postgres");
    out.parameterStatus("TimeZone", TimeZone.getDefault().getID());
    out.parameterStatus("integer_datetimes", "on");
    out.parameterStatus("standard_conforming_strings", "on");
    out.parameterStatus("is_superuser", "off");
    out.parameterStatus("session_authorization", user);
    out.parameterStatus("application_name", applicationName);
    out.parameterStatus("default_transaction_read_only", "on");
    out.parameterStatus("in_hot_standby", "off");
    out.backendKeyData(RANDOM.nextInt(1, Integer.MAX_VALUE), RANDOM.nextInt());
    out.readyForQuery(status);
    out.flush();
    return true;
  }

  /** The settings a client sent at startup: its own parameters and any {@code -c name=value} in options. */
  private static Map<String, String> startupSettings(Map<String, String> params) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, String> p : params.entrySet()) {
      String k = p.getKey();
      if (k.equals("user") || k.equals("database") || k.equals("options") || k.equals("replication")) {
        continue;
      }
      out.put(k.toLowerCase(Locale.ROOT), p.getValue());
    }
    String options = params.get("options");
    if (options != null) {
      List<String> words = new ArrayList<>();
      StringBuilder w = new StringBuilder();
      for (int i = 0; i < options.length(); i++) {
        char c = options.charAt(i);
        if (c == '\\' && i + 1 < options.length()) {
          w.append(options.charAt(++i));
        } else if (Character.isWhitespace(c)) {
          if (w.length() > 0) {
            words.add(w.toString());
            w.setLength(0);
          }
        } else {
          w.append(c);
        }
      }
      if (w.length() > 0) {
        words.add(w.toString());
      }
      for (int i = 0; i < words.size(); i++) {
        String word = words.get(i);
        String pair = null;
        if (word.equals("-c") && i + 1 < words.size()) {
          pair = words.get(++i);
        } else if (word.startsWith("-c")) {
          pair = word.substring(2);
        } else if (word.startsWith("--")) {
          pair = word.substring(2);
        }
        if (pair != null && pair.indexOf('=') > 0) {
          int eq = pair.indexOf('=');
          out.put(pair.substring(0, eq).toLowerCase(Locale.ROOT).replace('-', '_'), pair.substring(eq + 1));
        }
      }
    }
    return out;
  }

  // ------------------------------------------------------------- the loop

  private void loop() throws IOException {
    while (true) {
      PgWire.Message m = PgWire.read(in);
      if (m == null || m.type() == 'X') {
        return;
      }
      if (skipToSync && m.type() != 'S') {
        continue;
      }
      try {
        switch (m.type()) {
          case 'Q' -> simpleQuery(m.reader().cstring());
          case 'P' -> parse(m.reader());
          case 'B' -> bind(m.reader());
          case 'D' -> describe(m.reader());
          case 'E' -> execute(m.reader());
          case 'C' -> close(m.reader());
          case 'S' -> sync();
          case 'H' -> out.flush();
          case 'F' -> throw new PgError(PgError.FEATURE_NOT_SUPPORTED, "function calls are not supported by the ARAK gateway");
          case 'd', 'c', 'f' -> throw new PgError(PgError.PROTOCOL_VIOLATION, "COPY is not supported by the ARAK gateway");
          default -> throw new PgWire.ProtocolException("invalid frontend message type " + (int) m.type());
        }
      } catch (PgError e) {
        if (e.isFatal()) {
          fatal(e);
          return;
        }
        out.error(e);
        if (status == 'T') {
          status = 'E';
        }
        skipToSync = true;
      }
    }
  }

  private void sync() throws IOException {
    skipToSync = false;
    if (status != 'T' && status != 'E') {
      closePortals();
    }
    out.readyForQuery(status);
    out.flush();
  }

  // --------------------------------------------------------- simple query

  private void simpleQuery(String sql) throws IOException {
    List<String> list = SqlText.split(sql);
    if (list.isEmpty()) {
      out.emptyQueryResponse();
    }
    for (String statement : list) {
      try {
        checkToken();
        SqlText.Kind kind = SqlText.kind(statement);
        refuseInFailedBlock(kind);
        Answer answer = answer(statement, kind);
        if (answer.rows() != null) {
          try (Rows rows = answer.rows()) {
            List<PgWire.Column> cols = columns(rows, null, false);
            out.rowDescription(cols);
            long n = send(rows, cols, 0);
            finish(rows, n);
          }
        } else {
          out.commandComplete(answer.tag());
        }
      } catch (PgError e) {
        if (e.isFatal()) {
          throw new FatalSignal(e);
        }
        out.error(e);
        if (status == 'T') {
          status = 'E';
        }
        break;
      }
    }
    if (status != 'T' && status != 'E') {
      closePortals();
    }
    out.readyForQuery(status);
    out.flush();
  }

  // ------------------------------------------------------ extended query

  private record Prepared(String sql, SqlText.Kind kind, int[] types) {}

  private final class Portal {
    final Prepared statement;
    final String sql;
    final short[] formats;
    Rows rows;
    List<PgWire.Column> columns;
    boolean done;

    Portal(Prepared statement, String sql, short[] formats) {
      this.statement = statement;
      this.sql = sql;
      this.formats = formats;
    }

    void close() {
      if (rows != null) {
        rows.close();
        rows = null;
      }
    }
  }

  private void parse(PgWire.Reader r) throws IOException, PgError {
    String name = r.cstring();
    String sql = r.cstring();
    int n = r.int16();
    if (n < 0 || n > 65_535) {
      throw new PgWire.ProtocolException("invalid parameter count");
    }
    int[] types = new int[n];
    for (int i = 0; i < n; i++) {
      types[i] = r.int32();
    }
    List<String> list = SqlText.split(sql);
    if (list.size() > 1) {
      throw new PgError(PgError.SYNTAX_ERROR, "cannot insert multiple commands into a prepared statement");
    }
    String statement = list.isEmpty() ? "" : list.get(0);
    if (!name.isEmpty() && statements.containsKey(name)) {
      throw new PgError("42P05", "prepared statement \"" + name + "\" already exists");
    }
    int count = Math.max(n, SqlText.parameterCount(statement));
    int[] all = new int[count];
    System.arraycopy(types, 0, all, 0, n);
    statements.put(name, new Prepared(statement, SqlText.kind(statement), all));
    out.parseComplete();
  }

  private void bind(PgWire.Reader r) throws IOException, PgError {
    String portalName = r.cstring();
    String statementName = r.cstring();
    Prepared p = statements.get(statementName);
    int nFormats = r.int16();
    short[] paramFormats = new short[Math.max(0, nFormats)];
    for (int i = 0; i < paramFormats.length; i++) {
      paramFormats[i] = r.int16();
    }
    int nParams = r.int16();
    if (nParams < 0) {
      throw new PgWire.ProtocolException("invalid parameter count");
    }
    List<String> values = new ArrayList<>(nParams);
    List<Integer> types = new ArrayList<>(nParams);
    for (int i = 0; i < nParams; i++) {
      int len = r.int32();
      byte[] raw = len < 0 ? null : r.bytes(len);
      int oid = p != null && i < p.types().length ? p.types()[i] : 0;
      short format = paramFormats.length == 0 ? 0 : paramFormats.length == 1 ? paramFormats[0] : paramFormats[i];
      String value =
          raw == null
              ? null
              : format == 1 ? PgTypes.decodeBinary(oid, raw) : new String(raw, StandardCharsets.UTF_8);
      values.add(value);
      types.add(oid);
    }
    int nResults = r.int16();
    short[] formats = new short[Math.max(0, nResults)];
    for (int i = 0; i < formats.length; i++) {
      formats[i] = r.int16();
    }
    if (p == null) {
      throw new PgError(PgError.UNDEFINED_PSTATEMENT, "prepared statement \"" + statementName + "\" does not exist");
    }
    int needed = p.types().length;
    if (nParams != needed) {
      throw new PgError(PgError.PROTOCOL_VIOLATION,
          "bind message supplies " + nParams + " parameters, but prepared statement requires " + needed);
    }
    List<String> literals = new ArrayList<>(nParams);
    for (int i = 0; i < nParams; i++) {
      literals.add(SqlText.literal(values.get(i), types.get(i)));
    }
    String sql = nParams == 0 ? p.sql() : SqlText.substitute(p.sql(), literals);
    Portal old = portals.remove(portalName);
    if (old != null) {
      old.close();
    }
    portals.put(portalName, new Portal(p, sql, formats));
    out.bindComplete();
  }

  private void describe(PgWire.Reader r) throws IOException, PgError {
    char what = (char) r.int8();
    String name = r.cstring();
    if (what == 'S') {
      Prepared p = statements.get(name);
      if (p == null) {
        throw new PgError(PgError.UNDEFINED_PSTATEMENT, "prepared statement \"" + name + "\" does not exist");
      }
      int[] reported = new int[p.types().length];
      for (int i = 0; i < reported.length; i++) {
        reported[i] = p.types()[i] == 0 ? PgTypes.TEXT : p.types()[i];
      }
      out.parameterDescription(reported);
      if (p.kind() == SqlText.Kind.SHOW) {
        out.rowDescription(List.of(textColumn(SqlText.parameterOf(p.sql()), (short) 0)));
      } else if (p.kind() == SqlText.Kind.QUERY) {
        checkToken();
        refuseInFailedBlock(p.kind());
        out.rowDescription(describeStatement(p));
      } else {
        out.noData();
      }
      return;
    }
    if (what != 'P') {
      throw new PgWire.ProtocolException("invalid DESCRIBE message subtype");
    }
    Portal portal = portals.get(name);
    if (portal == null) {
      throw new PgError(PgError.UNDEFINED_CURSOR, "portal \"" + name + "\" does not exist");
    }
    if (portal.statement.kind() == SqlText.Kind.QUERY || portal.statement.kind() == SqlText.Kind.SHOW) {
      open(portal);
      out.rowDescription(portal.columns);
    } else {
      out.noData();
    }
  }

  private void execute(PgWire.Reader r) throws IOException, PgError {
    String name = r.cstring();
    int maxRows = r.int32();
    Portal portal = portals.get(name);
    if (portal == null) {
      throw new PgError(PgError.UNDEFINED_CURSOR, "portal \"" + name + "\" does not exist");
    }
    SqlText.Kind kind = portal.statement.kind();
    if (kind == SqlText.Kind.EMPTY) {
      out.emptyQueryResponse();
      return;
    }
    if (kind != SqlText.Kind.QUERY && kind != SqlText.Kind.SHOW) {
      if (portal.done) {
        throw new PgError(PgError.UNDEFINED_CURSOR, "portal \"" + name + "\" cannot be run again");
      }
      checkToken();
      refuseInFailedBlock(kind);
      Answer answer = answer(portal.sql, kind);
      portal.done = true;
      out.commandComplete(answer.tag());
      return;
    }
    if (portal.done) {
      out.commandComplete("SELECT 0");
      return;
    }
    open(portal);
    long n = send(portal.rows, portal.columns, maxRows);
    if (maxRows > 0 && n == maxRows && portal.rows.hasMore()) {
      out.portalSuspended();
      return;
    }
    portal.done = true;
    finish(portal.rows, n);
    portal.close();
  }

  private void close(PgWire.Reader r) throws IOException, PgError {
    char what = (char) r.int8();
    String name = r.cstring();
    if (what == 'S') {
      statements.remove(name);
    } else if (what == 'P') {
      Portal p = portals.remove(name);
      if (p != null) {
        p.close();
      }
    } else {
      throw new PgWire.ProtocolException("invalid CLOSE message subtype");
    }
    out.closeComplete();
  }

  /** Starts a portal's rows if they have not started, and works out its columns. */
  private void open(Portal portal) throws PgError {
    if (portal.rows != null || portal.done) {
      if (portal.columns == null) {
        portal.columns = List.of();
      }
      return;
    }
    checkToken();
    refuseInFailedBlock(portal.statement.kind());
    Answer answer = answer(portal.sql, portal.statement.kind());
    portal.rows = answer.rows();
    portal.columns = columns(portal.rows, portal.formats, true);
  }

  /** The columns a prepared query will return, found without reading any data. */
  private List<PgWire.Column> describeStatement(Prepared p) throws PgError {
    List<String> nulls = new ArrayList<>();
    for (int i = 0; i < p.types().length; i++) {
      nulls.add("NULL");
    }
    String sql = p.types().length == 0 ? p.sql() : SqlText.substitute(p.sql(), nulls);
    SqlText.Route route = SqlText.route(sql);
    Rows rows;
    if (route == SqlText.Route.DATA) {
      rows = data("SELECT * FROM (" + sql + ") AS arak_describe LIMIT 0", 1);
    } else {
      rows = query(sql, route, 1);
    }
    try (rows) {
      return columns(rows, null, true);
    }
  }

  private void closePortals() {
    for (Portal p : portals.values()) {
      p.close();
    }
    portals.clear();
  }

  // ------------------------------------------------------------ answering

  /** What a statement produced: rows to send, or a tag to acknowledge it with. */
  private record Answer(Rows rows, String tag) {}

  private Answer answer(String sql, SqlText.Kind kind) throws PgError {
    switch (kind) {
      case EMPTY:
        return new Answer(null, "");
      case QUERY:
        if (INTO.matcher(SqlText.stripQuoted(sql)).find()) {
          throw refused(sql, new PgError(PgError.READ_ONLY, "cannot execute SELECT INTO in a read-only transaction"));
        }
        return new Answer(query(sql, SqlText.route(sql), 0), null);
      case SHOW:
        return new Answer(show(SqlText.parameterOf(sql)), null);
      case SET:
        SqlText.Setting s = SqlText.parseSet(sql);
        if (s != null) {
          try {
            set(s.name(), s.value());
          } catch (PgError e) {
            throw refused(sql, e);
          }
        }
        return new Answer(null, "SET");
      case RESET:
        reset(SqlText.parameterOf(sql));
        return new Answer(null, "RESET");
      case BEGIN:
        if (status != 'I') {
          notice("25001", "there is already a transaction in progress");
        } else {
          status = 'T';
        }
        return new Answer(null, SqlText.tag(sql, kind));
      case COMMIT:
        String tag = status == 'E' ? "ROLLBACK" : "COMMIT";
        if (status == 'I') {
          notice("25P01", "there is no transaction in progress");
        }
        status = 'I';
        closePortals();
        return new Answer(null, tag);
      case ROLLBACK:
        if (status == 'I') {
          notice("25P01", "there is no transaction in progress");
        }
        status = 'I';
        closePortals();
        return new Answer(null, "ROLLBACK");
      case SAVEPOINT:
        String word = SqlText.leadingWords(sql, 1).get(0);
        if (status == 'I') {
          throw new PgError("25P01", word + " can only be used in transaction blocks");
        }
        if (status == 'E' && (word.equals("ROLLBACK") || word.equals("ABORT"))) {
          status = 'T';
        }
        return new Answer(null, SqlText.tag(sql, kind));
      case IGNORED:
        return new Answer(null, SqlText.tag(sql, kind));
      default:
        List<String> words = SqlText.leadingWords(sql, 1);
        String first = words.isEmpty() ? "this statement" : words.get(0);
        if (WRITES.contains(first)) {
          throw refused(sql, new PgError(PgError.READ_ONLY, "cannot execute " + first + " in a read-only transaction",
              null, "The ARAK gateway only reads; ask the data owner for changes."));
        }
        throw refused(sql, new PgError(PgError.FEATURE_NOT_SUPPORTED, first + " is not supported by the ARAK gateway"));
    }
  }

  private Rows query(String sql, SqlText.Route route, int limit) throws PgError {
    if (route == SqlText.Route.MIXED) {
      throw refused(sql, new PgError(PgError.FEATURE_NOT_SUPPORTED,
          "a statement cannot read data tables and the system catalog together",
          null, "Ask the catalog and the data in separate statements."));
    }
    if (route == SqlText.Route.CATALOG) {
      long started = System.nanoTime();
      SandboxCatalog.Result r;
      try {
        if (sandbox == null) {
          sandbox = ctx.sandbox().open(source, principal, clientIp);
        }
        r = sandbox.query(sql, purpose, defaultSchema, limit);
      } catch (PgError e) {
        auditHere(sql, "FAILED", "Catalog: " + e.getMessage(), null, millisSince(started));
        throw e;
      } catch (RuntimeException e) {
        auditHere(sql, "FAILED", "Catalog: the sandbox could not answer", null, millisSince(started));
        throw e;
      }
      auditHere(sql, "EXECUTED", null, (long) r.rows().size(), millisSince(started));
      return new ListRows(r.columns(), r.rows(), r.capped(), ctx.config().getMaxRows());
    }
    return data(sql, limit);
  }

  /**
   * Audits a statement the gateway answered or refused without the policy
   * engine, which audits everything that reaches it. Every attempt is audited.
   */
  private void auditHere(String sql, String outcome, String reason, Long rows, Integer millis) {
    ctx.queries().auditGateway(source.id(), principal, sql, outcome, reason, rows, millis, clientIp,
        QueryService.Via.gateway(applicationName));
  }

  private PgError refused(String sql, PgError e) {
    auditHere(sql, "REJECTED", e.getMessage(), null, null);
    return e;
  }

  private static int millisSince(long startedNanos) {
    return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000);
  }

  private Rows data(String sql, int limit) throws PgError {
    int max = limit > 0 ? limit : ctx.config().getMaxRows();
    try {
      QueryService.Feed feed =
          ctx.queries()
              .feed(source.id(), sql, principal, clientIp, purpose, defaultSchema,
                  QueryService.Via.gateway(applicationName), max, ctx.config().getStatementTimeoutSeconds());
      return new FeedRows(feed, max);
    } catch (QueryService.RejectedException e) {
      throw new PgError(
          e.deniedAsset() != null || !e.aboutStatement()
              ? PgError.INSUFFICIENT_PRIVILEGE
              : PgError.FEATURE_NOT_SUPPORTED,
          e.getMessage());
    } catch (QueryService.BusyException e) {
      throw new PgError("53000", e.getMessage(), null,
          "Try again in " + e.retryAfterSeconds() + " seconds.");
    } catch (RuntimeException e) {
      LOG.error("Gateway read on {} failed", source.name(), e);
      throw new PgError(PgError.INTERNAL, "The read could not be run");
    }
  }

  // ------------------------------------------------------------- settings

  private void set(String name, String raw) throws PgError {
    String key = name.toLowerCase(Locale.ROOT).replace(' ', '_');
    if (key.equals("time_zone")) {
      key = "timezone";
    }
    String value = SqlText.unquote(raw);
    boolean toDefault = raw == null || raw.isBlank() || raw.strip().equalsIgnoreCase("DEFAULT");
    if (FORBIDDEN_SETTINGS.contains(key)) {
      if ((key.equals("default_transaction_read_only") || key.equals("transaction_read_only"))
          && (toDefault || value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true") || value.equals("1"))) {
        return;
      }
      throw new PgError(PgError.INSUFFICIENT_PRIVILEGE, "permission denied to set parameter \"" + name + "\"");
    }
    switch (key) {
      case "search_path" -> {
        if (toDefault) {
          defaultSchema = "public";
          settings.remove(key);
        } else {
          String first = SqlText.firstSchema(raw);
          defaultSchema = first == null ? "public" : first;
          settings.put(key, raw.strip());
        }
      }
      case "arak.purpose" -> {
        try {
          purpose = toDefault ? null : ctx.purposes().declared(value);
        } catch (PurposeStore.Refused e) {
          throw new PgError(PgError.INVALID_PARAMETER, e.getMessage());
        }
        if (purpose == null) {
          settings.remove(key);
        } else {
          settings.put(key, purpose);
        }
      }
      case "application_name" -> {
        applicationName = toDefault ? "" : value;
        if (applicationName.length() > 64) {
          applicationName = applicationName.substring(0, 64);
        }
        if (signedIn) {
          sendStatus("application_name", applicationName);
        }
      }
      case "client_encoding" -> {
        String v = value.toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (!toDefault && !v.equals("UTF8") && !v.equals("UNICODE")) {
          throw new PgError(PgError.FEATURE_NOT_SUPPORTED, "the ARAK gateway speaks UTF8 only");
        }
      }
      case "datestyle" -> {
        if (!toDefault && !value.toUpperCase(Locale.ROOT).startsWith("ISO")) {
          throw new PgError(PgError.FEATURE_NOT_SUPPORTED, "the ARAK gateway sends dates in ISO style only");
        }
      }
      case "standard_conforming_strings" -> {
        if (!toDefault && !(value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true"))) {
          throw new PgError(PgError.FEATURE_NOT_SUPPORTED, "standard_conforming_strings must stay on");
        }
      }
      default -> {
        if (toDefault) {
          settings.remove(key);
        } else if (settings.size() < 200 || settings.containsKey(key)) {
          settings.put(key, value);
        }
      }
    }
  }

  private void reset(String name) throws PgError {
    if (name.equals("all")) {
      settings.clear();
      defaultSchema = "public";
      purpose = null;
      return;
    }
    set(name, null);
  }

  private Rows show(String name) throws PgError {
    if (name.equals("all")) {
      throw new PgError(PgError.FEATURE_NOT_SUPPORTED, "SHOW ALL is not supported by the ARAK gateway");
    }
    String value = showValue(name);
    if (value == null) {
      throw new PgError(PgError.UNDEFINED_OBJECT, "unrecognized configuration parameter \"" + name + "\"");
    }
    List<String[]> rows = new ArrayList<>();
    rows.add(new String[] {value});
    return new ListRows(List.of(textColumn(name, (short) 0)), rows, false, 1);
  }

  private String showValue(String name) {
    String stored = settings.get(name);
    return switch (name) {
      case "server_version" -> ctx.config().getServerVersion();
      case "server_version_num" -> "160004";
      case "server_encoding", "client_encoding" -> "UTF8";
      case "datestyle" -> "ISO, MDY";
      case "intervalstyle" -> "postgres";
      case "timezone" -> stored != null ? stored : TimeZone.getDefault().getID();
      case "integer_datetimes", "standard_conforming_strings", "transaction_read_only",
          "default_transaction_read_only" -> "on";
      case "is_superuser" -> "off";
      case "application_name" -> applicationName;
      case "search_path" -> stored != null ? stored : "\"$user\", public";
      case "transaction isolation level", "transaction_isolation", "default_transaction_isolation" ->
          "read committed";
      case "max_identifier_length" -> "63";
      case "max_index_keys" -> "32";
      case "block_size" -> "8192";
      case "bytea_output" -> "hex";
      case "lc_collate", "lc_ctype", "lc_messages", "lc_monetary", "lc_numeric", "lc_time" ->
          stored != null ? stored : "C";
      case "extra_float_digits" -> stored != null ? stored : "1";
      case "statement_timeout", "lock_timeout", "idle_in_transaction_session_timeout" ->
          stored != null ? stored : "0";
      case "session_authorization" -> principal;
      case "arak.purpose" -> purpose == null ? "" : purpose;
      default -> stored;
    };
  }

  private void sendStatus(String name, String value) {
    try {
      out.parameterStatus(name, value);
    } catch (IOException e) {
      LOG.debug("Could not report {}: {}", name, e.toString());
    }
  }

  // --------------------------------------------------------------- output

  /** The columns as RowDescription carries them, for the formats asked for. */
  private static List<PgWire.Column> columns(Rows rows, short[] requested, boolean extended) {
    List<PgWire.Column> base = rows.columns();
    List<PgWire.Column> out = new ArrayList<>(base.size());
    for (int i = 0; i < base.size(); i++) {
      PgWire.Column c = base.get(i);
      short format = format(requested, i);
      int oid = c.typeOid();
      // Over the extended protocol a tool may ask for any column in binary,
      // and may decide that before it has seen a single row. A type the
      // gateway cannot write in binary is therefore always described as text,
      // whose binary form is its UTF-8 bytes, so the answer is right either way.
      if (extended && !PgTypes.binaryEncodable(oid)) {
        oid = PgTypes.TEXT;
      }
      out.add(new PgWire.Column(c.name(), oid, PgTypes.typeSize(oid), -1, format));
    }
    return out;
  }

  private static short format(short[] requested, int i) {
    if (requested == null || requested.length == 0) {
      return 0;
    }
    return requested.length == 1 ? requested[0] : i < requested.length ? requested[i] : 0;
  }

  private static PgWire.Column textColumn(String name, short format) {
    return new PgWire.Column(name, PgTypes.TEXT, (short) -1, -1, format);
  }

  /** Sends up to {@code max} rows (all when 0); how many went. */
  private long send(Rows rows, List<PgWire.Column> cols, int max) throws IOException, PgError {
    long n = 0;
    while (max <= 0 || n < max) {
      String[] row = rows.next();
      if (row == null) {
        break;
      }
      byte[][] cells = new byte[cols.size()][];
      for (int i = 0; i < cells.length; i++) {
        String v = i < row.length ? row[i] : null;
        if (v == null) {
          continue;
        }
        PgWire.Column c = cols.get(i);
        try {
          cells[i] = c.format() == 1 ? PgTypes.binary(c.typeOid(), v) : PgTypes.text(c.typeOid(), v);
        } catch (RuntimeException e) {
          rows.stopped("A value in column " + c.name() + " could not be encoded for the client");
          throw new PgError(PgError.INVALID_TEXT,
              "a value in column \"" + c.name() + "\" could not be sent in the format the client asked for");
        }
      }
      out.dataRow(cells);
      n++;
    }
    return n;
  }

  private void finish(Rows rows, long n) throws IOException {
    if (rows.capped()) {
      out.notice("01000",
          "The result was cut off at " + rows.limit() + " rows by the ARAK gateway",
          "Filter or aggregate the query to read less.");
    }
    out.commandComplete("SELECT " + n);
  }

  private void notice(String state, String message) throws PgError {
    try {
      out.notice(state, message, null);
    } catch (IOException e) {
      throw new PgError(PgError.CONNECTION_FAILURE, "connection lost");
    }
  }

  private void fatal(PgError e) {
    try {
      out.error(e.isFatal() ? e : PgError.fatal(e.sqlState(), e.getMessage()));
      out.flush();
    } catch (IOException | RuntimeException ignored) {
      // The client may already be gone.
    }
  }

  private void checkToken() throws PgError {
    if (!ctx.tokens().stillValid(holder.tokenId())) {
      throw PgError.fatal(PgError.INVALID_AUTHORIZATION, "the access token was revoked or has expired");
    }
  }

  private void refuseInFailedBlock(SqlText.Kind kind) throws PgError {
    if (status == 'E' && kind != SqlText.Kind.ROLLBACK && kind != SqlText.Kind.COMMIT
        && kind != SqlText.Kind.SAVEPOINT) {
      throw new PgError(PgError.IN_FAILED_TRANSACTION,
          "current transaction is aborted, commands ignored until end of transaction block");
    }
  }

  /** A fatal error found while answering a simple query; ends the session. */
  private static final class FatalSignal extends RuntimeException {
    FatalSignal(PgError e) {
      super(e.getMessage(), e, false, false);
    }
  }

  // ----------------------------------------------------------------- rows

  /** Rows on their way to the client, from the proxy or the sandbox. */
  private interface Rows extends AutoCloseable {
    List<PgWire.Column> columns();

    String[] next() throws PgError;

    boolean hasMore() throws PgError;

    boolean capped();

    int limit();

    void stopped(String reason);

    @Override
    void close();
  }

  private static final class FeedRows implements Rows {
    private final QueryService.Feed feed;
    private final int limit;
    private final List<PgWire.Column> columns;
    private String[] ahead;

    FeedRows(QueryService.Feed feed, int limit) {
      this.feed = feed;
      this.limit = limit;
      List<String> names = feed.columns();
      List<String> types = feed.columnTypes();
      List<PgWire.Column> cols = new ArrayList<>(names.size());
      for (int i = 0; i < names.size(); i++) {
        int oid = PgTypes.oidForName(i < types.size() ? types.get(i) : null);
        cols.add(new PgWire.Column(names.get(i), oid, PgTypes.typeSize(oid), -1, (short) 0));
      }
      this.columns = cols;
    }

    @Override
    public List<PgWire.Column> columns() {
      return columns;
    }

    @Override
    public String[] next() throws PgError {
      if (ahead != null) {
        String[] r = ahead;
        ahead = null;
        return r;
      }
      return pull();
    }

    @Override
    public boolean hasMore() throws PgError {
      if (ahead == null) {
        ahead = pull();
      }
      return ahead != null;
    }

    private String[] pull() throws PgError {
      try {
        List<String> row = feed.next();
        return row == null ? null : row.toArray(new String[0]);
      } catch (SQLException e) {
        if ("57014".equals(e.getSQLState())) {
          throw new PgError(PgError.QUERY_CANCELED, "The source took too long to answer");
        }
        throw new PgError(PgError.INTERNAL, "The source stopped answering part-way through the result");
      }
    }

    @Override
    public boolean capped() {
      return feed.capped();
    }

    @Override
    public int limit() {
      return limit;
    }

    @Override
    public void stopped(String reason) {
      feed.stopped(reason);
    }

    @Override
    public void close() {
      feed.close();
    }
  }

  private static final class ListRows implements Rows {
    private final List<PgWire.Column> columns;
    private final List<String[]> rows;
    private final boolean capped;
    private final int limit;
    private int at;

    ListRows(List<PgWire.Column> columns, List<String[]> rows, boolean capped, int limit) {
      this.columns = columns;
      this.rows = rows;
      this.capped = capped;
      this.limit = limit;
    }

    @Override
    public List<PgWire.Column> columns() {
      return columns;
    }

    @Override
    public String[] next() {
      return at < rows.size() ? rows.get(at++) : null;
    }

    @Override
    public boolean hasMore() {
      return at < rows.size();
    }

    @Override
    public boolean capped() {
      return capped && at >= rows.size();
    }

    @Override
    public int limit() {
      return limit;
    }

    @Override
    public void stopped(String reason) {}

    @Override
    public void close() {}
  }
}
