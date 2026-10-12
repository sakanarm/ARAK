package com.mfec.dac.gateway;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reading and writing PostgreSQL frontend/backend protocol 3.0 messages.
 *
 * <p>Only framing lives here: what a message means is {@link PgSession}'s
 * business. Every length a client sends is checked before anything is
 * allocated for it, because the first thing anyone pointing a fuzzer at an
 * open port tries is a length of two gigabytes.
 */
final class PgWire {

  /** Protocol 3.0, as the startup packet spells it. */
  static final int PROTOCOL_3 = 196_608;

  static final int SSL_REQUEST = 80_877_103;
  static final int GSSENC_REQUEST = 80_877_104;
  static final int CANCEL_REQUEST = 80_877_102;

  /** Larger than any statement a tool sends; smaller than a memory problem. */
  static final int MAX_MESSAGE = 16 * 1024 * 1024;

  /** The startup packet is small by definition. */
  static final int MAX_STARTUP = 10_000;

  /** A message from the client: its type byte and its body. */
  record Message(char type, byte[] body) {
    Reader reader() {
      return new Reader(body);
    }
  }

  /** The column a RowDescription describes. */
  record Column(String name, int typeOid, short typeSize, int typeModifier, short format) {}

  /** A protocol rule the client broke; answered with 08P01 and the connection closed. */
  static final class ProtocolException extends IOException {
    ProtocolException(String message) {
      super(message);
    }
  }

  private PgWire() {}

  // ---------------------------------------------------------------- reading

  /** The startup packet: its body after the length, starting with the protocol code. */
  static byte[] readStartup(InputStream in) throws IOException {
    DataInputStream data = new DataInputStream(in);
    int length = data.readInt();
    if (length < 8 || length > MAX_STARTUP) {
      throw new ProtocolException("invalid length of startup packet");
    }
    byte[] body = new byte[length - 4];
    data.readFully(body);
    return body;
  }

  /** The next message, or null when the client closed the connection between messages. */
  static Message read(InputStream in) throws IOException {
    int type = in.read();
    if (type < 0) {
      return null;
    }
    DataInputStream data = new DataInputStream(in);
    int length;
    try {
      length = data.readInt();
    } catch (EOFException e) {
      return null;
    }
    if (length < 4 || length > MAX_MESSAGE) {
      throw new ProtocolException("invalid message length");
    }
    byte[] body = new byte[length - 4];
    data.readFully(body);
    return new Message((char) type, body);
  }

  /** Reads fields out of one message body. */
  static final class Reader {
    private final byte[] body;
    private int at;

    Reader(byte[] body) {
      this.body = body;
    }

    int int32() throws ProtocolException {
      need(4);
      int v =
          ((body[at] & 0xff) << 24)
              | ((body[at + 1] & 0xff) << 16)
              | ((body[at + 2] & 0xff) << 8)
              | (body[at + 3] & 0xff);
      at += 4;
      return v;
    }

    short int16() throws ProtocolException {
      need(2);
      short v = (short) (((body[at] & 0xff) << 8) | (body[at + 1] & 0xff));
      at += 2;
      return v;
    }

    byte int8() throws ProtocolException {
      need(1);
      return body[at++];
    }

    /** A NUL-terminated string. */
    String cstring() throws ProtocolException {
      int end = at;
      while (end < body.length && body[end] != 0) {
        end++;
      }
      if (end >= body.length) {
        throw new ProtocolException("unterminated string in message");
      }
      String s = new String(body, at, end - at, StandardCharsets.UTF_8);
      at = end + 1;
      return s;
    }

    byte[] bytes(int n) throws ProtocolException {
      if (n < 0) {
        throw new ProtocolException("negative length in message");
      }
      need(n);
      byte[] out = new byte[n];
      System.arraycopy(body, at, out, 0, n);
      at += n;
      return out;
    }

    boolean done() {
      return at >= body.length;
    }

    private void need(int n) throws ProtocolException {
      if (at + n > body.length) {
        throw new ProtocolException("message is shorter than its contents");
      }
    }
  }

  // ---------------------------------------------------------------- writing

  /** Writes backend messages; nothing reaches the client until {@link #flush}. */
  static final class Writer {
    private final OutputStream out;

    Writer(OutputStream raw) {
      this.out = new BufferedOutputStream(raw, 64 * 1024);
    }

    void flush() throws IOException {
      out.flush();
    }

    /** The one-byte answer to SSLRequest and GSSENCRequest; not a framed message. */
    void raw(char c) throws IOException {
      out.write(c);
      out.flush();
    }

    void authCleartextPassword() throws IOException {
      message('R', body().int32(3));
    }

    void authOk() throws IOException {
      message('R', body().int32(0));
    }

    void parameterStatus(String name, String value) throws IOException {
      message('S', body().cstring(name).cstring(value));
    }

    void backendKeyData(int pid, int secret) throws IOException {
      message('K', body().int32(pid).int32(secret));
    }

    void negotiateProtocolVersion(int minor, List<String> unrecognised) throws IOException {
      Body b = body().int32((3 << 16) | minor).int32(unrecognised.size());
      for (String option : unrecognised) {
        b.cstring(option);
      }
      message('v', b);
    }

    /** I idle, T in a transaction block, E in a failed one. */
    void readyForQuery(char status) throws IOException {
      message('Z', body().int8(status));
    }

    void rowDescription(List<Column> columns) throws IOException {
      Body b = body().int16(columns.size());
      for (Column c : columns) {
        b.cstring(c.name())
            .int32(0)
            .int16(0)
            .int32(c.typeOid())
            .int16(c.typeSize())
            .int32(c.typeModifier())
            .int16(c.format());
      }
      message('T', b);
    }

    /** One row; a null cell is sent as SQL NULL. */
    void dataRow(byte[][] cells) throws IOException {
      int size = 2;
      for (byte[] cell : cells) {
        size += 4 + (cell == null ? 0 : cell.length);
      }
      writeHeader('D', size);
      writeInt16(cells.length);
      for (byte[] cell : cells) {
        if (cell == null) {
          writeInt32(-1);
        } else {
          writeInt32(cell.length);
          out.write(cell);
        }
      }
    }

    void commandComplete(String tag) throws IOException {
      message('C', body().cstring(tag));
    }

    void emptyQueryResponse() throws IOException {
      message('I', body());
    }

    void parseComplete() throws IOException {
      message('1', body());
    }

    void bindComplete() throws IOException {
      message('2', body());
    }

    void closeComplete() throws IOException {
      message('3', body());
    }

    void noData() throws IOException {
      message('n', body());
    }

    void portalSuspended() throws IOException {
      message('s', body());
    }

    void parameterDescription(int[] oids) throws IOException {
      Body b = body().int16(oids.length);
      for (int oid : oids) {
        b.int32(oid);
      }
      message('t', b);
    }

    void error(PgError e) throws IOException {
      message('E', fields(e.severity(), e.sqlState(), e.getMessage(), e.detail(), e.hint()));
    }

    void notice(String sqlState, String message, String detail) throws IOException {
      message('N', fields("WARNING", sqlState, message, detail, null));
    }

    private Body fields(String severity, String code, String message, String detail, String hint) {
      Body b = body();
      b.int8('S').cstring(severity);
      b.int8('V').cstring(severity);
      b.int8('C').cstring(code);
      b.int8('M').cstring(message == null ? "" : message);
      if (detail != null && !detail.isBlank()) {
        b.int8('D').cstring(detail);
      }
      if (hint != null && !hint.isBlank()) {
        b.int8('H').cstring(hint);
      }
      return b.int8(0);
    }

    private void message(char type, Body b) throws IOException {
      writeHeader(type, b.size());
      b.writeTo(out);
    }

    private void writeHeader(char type, int bodySize) throws IOException {
      out.write(type);
      writeInt32(bodySize + 4);
    }

    private void writeInt32(int v) throws IOException {
      out.write(v >>> 24);
      out.write(v >>> 16);
      out.write(v >>> 8);
      out.write(v);
    }

    private void writeInt16(int v) throws IOException {
      out.write(v >>> 8);
      out.write(v);
    }

    private static Body body() {
      return new Body();
    }
  }

  /** A message body being built. */
  static final class Body {
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(64);

    Body int32(int v) {
      buf.write(v >>> 24);
      buf.write(v >>> 16);
      buf.write(v >>> 8);
      buf.write(v);
      return this;
    }

    Body int16(int v) {
      buf.write(v >>> 8);
      buf.write(v);
      return this;
    }

    Body int8(int v) {
      buf.write(v);
      return this;
    }

    Body cstring(String s) {
      byte[] bytes = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
      for (byte b : bytes) {
        // A NUL inside would end the string early and shift every field after it.
        buf.write(b == 0 ? '?' : b);
      }
      buf.write(0);
      return this;
    }

    int size() {
      return buf.size();
    }

    void writeTo(OutputStream out) throws IOException {
      buf.writeTo(out);
    }
  }
}
