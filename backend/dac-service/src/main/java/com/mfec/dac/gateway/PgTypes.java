package com.mfec.dac.gateway;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PostgreSQL type OIDs and the binary forms of the common ones.
 *
 * <p>Values reach the gateway as text: the source's own text form, read with
 * {@code getString}. Most tools ask for text and get it unchanged. Npgsql (and
 * so Power BI) asks for binary, which means turning that text back into the
 * binary layout PostgreSQL would have sent. Only the types below have such a
 * layout here; a column of any other type is described to the client as
 * {@code text}, whose binary form is its UTF-8 text, so a client never receives
 * a layout it cannot read.
 */
final class PgTypes {

  static final int BOOL = 16;
  static final int BYTEA = 17;
  static final int CHAR = 18;
  static final int NAME = 19;
  static final int INT8 = 20;
  static final int INT2 = 21;
  static final int INT4 = 23;
  static final int TEXT = 25;
  static final int OID = 26;
  static final int XID = 28;
  static final int CID = 29;
  static final int JSON = 114;
  static final int XML = 142;
  static final int FLOAT4 = 700;
  static final int FLOAT8 = 701;
  static final int UNKNOWN = 705;
  static final int BPCHAR = 1042;
  static final int VARCHAR = 1043;
  static final int DATE = 1082;
  static final int TIME = 1083;
  static final int TIMESTAMP = 1114;
  static final int TIMESTAMPTZ = 1184;
  static final int INTERVAL = 1186;
  static final int TIMETZ = 1266;
  static final int NUMERIC = 1700;
  static final int UUID_OID = 2950;
  static final int JSONB = 3802;

  /** Microseconds between 1970-01-01 and 2000-01-01, PostgreSQL's epoch. */
  private static final long PG_EPOCH_SECONDS = 946_684_800L;

  private static final int PG_EPOCH_DAYS = 10_957;

  private static final Map<String, Integer> BY_NAME =
      Map.ofEntries(
          Map.entry("bool", BOOL),
          Map.entry("boolean", BOOL),
          Map.entry("bytea", BYTEA),
          Map.entry("char", CHAR),
          Map.entry("\"char\"", CHAR),
          Map.entry("name", NAME),
          Map.entry("int8", INT8),
          Map.entry("bigint", INT8),
          Map.entry("bigserial", INT8),
          Map.entry("serial8", INT8),
          Map.entry("int2", INT2),
          Map.entry("smallint", INT2),
          Map.entry("smallserial", INT2),
          Map.entry("serial2", INT2),
          Map.entry("int4", INT4),
          Map.entry("int", INT4),
          Map.entry("integer", INT4),
          Map.entry("serial", INT4),
          Map.entry("serial4", INT4),
          Map.entry("text", TEXT),
          Map.entry("oid", OID),
          Map.entry("json", JSON),
          Map.entry("xml", XML),
          Map.entry("float4", FLOAT4),
          Map.entry("real", FLOAT4),
          Map.entry("float8", FLOAT8),
          Map.entry("double precision", FLOAT8),
          Map.entry("bpchar", BPCHAR),
          Map.entry("character", BPCHAR),
          Map.entry("varchar", VARCHAR),
          Map.entry("character varying", VARCHAR),
          Map.entry("date", DATE),
          Map.entry("time", TIME),
          Map.entry("timestamp", TIMESTAMP),
          Map.entry("timestamptz", TIMESTAMPTZ),
          Map.entry("interval", INTERVAL),
          Map.entry("timetz", TIMETZ),
          Map.entry("numeric", NUMERIC),
          Map.entry("decimal", NUMERIC),
          Map.entry("uuid", UUID_OID),
          Map.entry("jsonb", JSONB));

  private static final DateTimeFormatter TIMESTAMP_TEXT =
      new DateTimeFormatterBuilder()
          .appendPattern("uuuu-MM-dd")
          .optionalStart()
          .appendLiteral(' ')
          .optionalEnd()
          .optionalStart()
          .appendLiteral('T')
          .optionalEnd()
          .appendPattern("HH:mm:ss")
          .optionalStart()
          .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
          .optionalEnd()
          .toFormatter(Locale.ROOT);

  private static final DateTimeFormatter TIMESTAMPTZ_TEXT =
      new DateTimeFormatterBuilder()
          .append(TIMESTAMP_TEXT)
          .optionalStart()
          .appendLiteral(' ')
          .optionalEnd()
          .appendOffset("+HH:mm:ss", "Z")
          .toFormatter(Locale.ROOT);

  private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");

  private PgTypes() {}

  /** The OID for a type name as a driver reports it; {@code text} when unknown. */
  static int oidForName(String typeName) {
    if (typeName == null) {
      return TEXT;
    }
    Integer oid = BY_NAME.get(typeName.toLowerCase(Locale.ROOT));
    return oid == null ? TEXT : oid;
  }

  /** Whether a value of this type can be sent in binary from its text form. */
  static boolean binaryEncodable(int oid) {
    return switch (oid) {
      case BOOL, BYTEA, CHAR, NAME, INT8, INT2, INT4, TEXT, OID, XID, CID, JSON, XML, FLOAT4,
              FLOAT8, UNKNOWN, BPCHAR, VARCHAR, DATE, TIME, TIMESTAMP, TIMESTAMPTZ, NUMERIC,
              UUID_OID, JSONB ->
          true;
      default -> false;
    };
  }

  /** The fixed width of a type's binary form, or -1 when it varies. */
  static short typeSize(int oid) {
    return switch (oid) {
      case BOOL, CHAR -> 1;
      case INT2 -> 2;
      case INT4, OID, XID, CID, FLOAT4, DATE -> 4;
      case INT8, FLOAT8, TIME, TIMESTAMP, TIMESTAMPTZ -> 8;
      case UUID_OID -> 16;
      case NAME -> 64;
      default -> -1;
    };
  }

  /** Whether a parameter of this type, once a number, may go into SQL bare. */
  static boolean numeric(int oid) {
    return switch (oid) {
      case INT2, INT4, INT8, FLOAT4, FLOAT8, NUMERIC, OID -> true;
      default -> false;
    };
  }

  static boolean looksLikeNumber(String text) {
    return text != null && NUMBER.matcher(text).matches();
  }

  // ------------------------------------------------------- result encoding

  /**
   * The text form of a value, as sent for format 0.
   *
   * <p>A boolean read back from a driver may say {@code true} where the server
   * says {@code t}; both parse, but a tool comparing strings sees the server's.
   */
  static byte[] text(int oid, String value) {
    if (value == null) {
      return null;
    }
    if (oid == BOOL) {
      return (truthy(value) ? "t" : "f").getBytes(StandardCharsets.US_ASCII);
    }
    return value.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The binary form of a value given in text.
   *
   * @throws RuntimeException when the text is not a value of the type: a
   *     number or date that does not parse
   */
  static byte[] binary(int oid, String value) {
    if (value == null) {
      return null;
    }
    String v = value.trim();
    return switch (oid) {
      case BOOL -> new byte[] {(byte) (truthy(v) ? 1 : 0)};
      case INT2 -> ByteBuffer.allocate(2).putShort(Short.parseShort(v)).array();
      case INT4 -> ByteBuffer.allocate(4).putInt(Integer.parseInt(v)).array();
      case INT8 -> ByteBuffer.allocate(8).putLong(Long.parseLong(v)).array();
      case OID, XID, CID -> ByteBuffer.allocate(4).putInt((int) Long.parseLong(v)).array();
      case FLOAT4 -> ByteBuffer.allocate(4).putFloat(Float.parseFloat(special(v))).array();
      case FLOAT8 -> ByteBuffer.allocate(8).putDouble(Double.parseDouble(special(v))).array();
      case NUMERIC -> numeric(v);
      case DATE -> ByteBuffer.allocate(4).putInt(date(v)).array();
      case TIME -> ByteBuffer.allocate(8).putLong(LocalTime.parse(v).toNanoOfDay() / 1_000).array();
      case TIMESTAMP -> ByteBuffer.allocate(8).putLong(timestamp(v)).array();
      case TIMESTAMPTZ -> ByteBuffer.allocate(8).putLong(timestamptz(v)).array();
      case UUID_OID -> uuid(v);
      case BYTEA -> bytea(value);
      case CHAR -> {
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        yield b.length == 0 ? new byte[0] : new byte[] {b[0]};
      }
      case JSONB -> {
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[b.length + 1];
        out[0] = 1;
        System.arraycopy(b, 0, out, 1, b.length);
        yield out;
      }
      default -> value.getBytes(StandardCharsets.UTF_8);
    };
  }

  private static boolean truthy(String v) {
    String s = v.trim().toLowerCase(Locale.ROOT);
    return s.equals("t") || s.equals("true") || s.equals("1") || s.equals("y")
        || s.equals("yes") || s.equals("on");
  }

  private static String special(String v) {
    return switch (v.toLowerCase(Locale.ROOT)) {
      case "infinity", "+infinity" -> "Infinity";
      case "-infinity" -> "-Infinity";
      case "nan" -> "NaN";
      default -> v;
    };
  }

  private static int date(String v) {
    String s = v.toLowerCase(Locale.ROOT);
    if (s.equals("infinity")) {
      return Integer.MAX_VALUE;
    }
    if (s.equals("-infinity")) {
      return Integer.MIN_VALUE;
    }
    return (int) (LocalDate.parse(v).toEpochDay() - PG_EPOCH_DAYS);
  }

  private static long timestamp(String v) {
    String s = v.toLowerCase(Locale.ROOT);
    if (s.equals("infinity")) {
      return Long.MAX_VALUE;
    }
    if (s.equals("-infinity")) {
      return Long.MIN_VALUE;
    }
    return micros(LocalDateTime.parse(v, TIMESTAMP_TEXT).toInstant(ZoneOffset.UTC));
  }

  private static long timestamptz(String v) {
    String s = v.toLowerCase(Locale.ROOT);
    if (s.equals("infinity")) {
      return Long.MAX_VALUE;
    }
    if (s.equals("-infinity")) {
      return Long.MIN_VALUE;
    }
    return micros(OffsetDateTime.parse(v, TIMESTAMPTZ_TEXT).toInstant());
  }

  private static long micros(Instant at) {
    return (at.getEpochSecond() - PG_EPOCH_SECONDS) * 1_000_000L + at.getNano() / 1_000;
  }

  private static byte[] uuid(String v) {
    UUID u = UUID.fromString(v);
    return ByteBuffer.allocate(16)
        .putLong(u.getMostSignificantBits())
        .putLong(u.getLeastSignificantBits())
        .array();
  }

  private static byte[] bytea(String v) {
    if (v.startsWith("\\x")) {
      return HexFormat.of().parseHex(v.substring(2));
    }
    return v.getBytes(StandardCharsets.UTF_8);
  }

  /** The binary numeric layout: base-10000 digits, weight, sign and display scale. */
  static byte[] numeric(String v) {
    String s = v.toLowerCase(Locale.ROOT);
    switch (s) {
      case "nan":
        return numericHeader(0, 0, 0xC000, 0, new short[0]);
      case "infinity":
        return numericHeader(0, 0, 0xD000, 0, new short[0]);
      case "-infinity":
        return numericHeader(0, 0, 0xF000, 0, new short[0]);
      default:
        break;
    }
    BigDecimal d = new BigDecimal(v);
    int sign = d.signum() < 0 ? 0x4000 : 0;
    d = d.abs();
    int dscale = Math.max(0, d.scale());
    if (d.signum() == 0) {
      return numericHeader(0, 0, 0, dscale, new short[0]);
    }
    String unscaled = d.unscaledValue().toString();
    int scale = d.scale();
    String intPart;
    String fracPart;
    if (scale > 0) {
      if (unscaled.length() > scale) {
        intPart = unscaled.substring(0, unscaled.length() - scale);
        fracPart = unscaled.substring(unscaled.length() - scale);
      } else {
        intPart = "";
        fracPart = "0".repeat(scale - unscaled.length()) + unscaled;
      }
    } else {
      intPart = unscaled + "0".repeat(-scale);
      fracPart = "";
    }
    intPart = intPart.replaceFirst("^0+", "");
    intPart = "0".repeat((4 - intPart.length() % 4) % 4) + intPart;
    fracPart = fracPart + "0".repeat((4 - fracPart.length() % 4) % 4);
    String all = intPart + fracPart;
    int groups = all.length() / 4;
    short[] digits = new short[groups];
    for (int i = 0; i < groups; i++) {
      digits[i] = Short.parseShort(all.substring(i * 4, i * 4 + 4));
    }
    int weight = intPart.length() / 4 - 1;
    int start = 0;
    while (start < groups && digits[start] == 0) {
      start++;
      weight--;
    }
    int end = groups;
    while (end > start && digits[end - 1] == 0) {
      end--;
    }
    short[] kept = new short[end - start];
    System.arraycopy(digits, start, kept, 0, kept.length);
    return numericHeader(kept.length, kept.length == 0 ? 0 : weight, sign, dscale, kept);
  }

  private static byte[] numericHeader(int ndigits, int weight, int sign, int dscale, short[] digits) {
    ByteBuffer b = ByteBuffer.allocate(8 + 2 * digits.length);
    b.putShort((short) ndigits).putShort((short) weight).putShort((short) sign).putShort((short) dscale);
    for (short digit : digits) {
      b.putShort(digit);
    }
    return b.array();
  }

  // ------------------------------------------------------ parameter decoding

  /**
   * A parameter the client sent in binary, as the text it stands for.
   *
   * @throws PgError when the type has no binary form here, or the bytes do
   *     not fit it
   */
  static String decodeBinary(int oid, byte[] b) throws PgError {
    try {
      ByteBuffer buf = ByteBuffer.wrap(b);
      return switch (oid) {
        case BOOL -> b.length == 1 && b[0] != 0 ? "true" : "false";
        case INT2 -> Short.toString(buf.getShort());
        case INT4 -> Integer.toString(buf.getInt());
        case INT8 -> Long.toString(buf.getLong());
        case OID -> Long.toString(Integer.toUnsignedLong(buf.getInt()));
        case FLOAT4 -> Float.toString(buf.getFloat());
        case FLOAT8 -> Double.toString(buf.getDouble());
        case NUMERIC -> decodeNumeric(buf);
        case TEXT, VARCHAR, BPCHAR, NAME, UNKNOWN, JSON, XML, CHAR, 0 ->
            new String(b, StandardCharsets.UTF_8);
        case JSONB -> new String(b, 1, b.length - 1, StandardCharsets.UTF_8);
        case UUID_OID -> new UUID(buf.getLong(), buf.getLong()).toString();
        case DATE -> LocalDate.ofEpochDay(buf.getInt() + (long) PG_EPOCH_DAYS).toString();
        case TIME -> LocalTime.ofNanoOfDay(buf.getLong() * 1_000).toString();
        case TIMESTAMP -> fromMicros(buf.getLong()).toString().replace('T', ' ');
        case TIMESTAMPTZ -> fromMicros(buf.getLong()).toString().replace('T', ' ') + "+00";
        default ->
            throw new PgError(
                PgError.FEATURE_NOT_SUPPORTED,
                "A parameter of type " + oid + " sent in binary is not supported by the gateway",
                null,
                "Send this parameter as text.");
      };
    } catch (java.nio.BufferUnderflowException
        | java.time.DateTimeException
        | ArithmeticException
        | IndexOutOfBoundsException e) {
      throw new PgError(PgError.PROTOCOL_VIOLATION, "A binary parameter does not fit its type");
    }
  }

  private static LocalDateTime fromMicros(long micros) {
    long seconds = Math.floorDiv(micros, 1_000_000L) + PG_EPOCH_SECONDS;
    long nanos = Math.floorMod(micros, 1_000_000L) * 1_000L;
    return LocalDateTime.ofEpochSecond(seconds, (int) nanos, ZoneOffset.UTC);
  }

  private static String decodeNumeric(ByteBuffer b) {
    int ndigits = b.getShort();
    int weight = b.getShort();
    int sign = b.getShort() & 0xFFFF;
    int dscale = b.getShort();
    if (sign == 0xC000) {
      return "NaN";
    }
    if (sign == 0xD000) {
      return "Infinity";
    }
    if (sign == 0xF000) {
      return "-Infinity";
    }
    BigInteger unscaled = BigInteger.ZERO;
    for (int i = 0; i < ndigits; i++) {
      unscaled = unscaled.multiply(BigInteger.valueOf(10_000)).add(BigInteger.valueOf(b.getShort()));
    }
    // The digits stand for unscaled * 10000^(weight - ndigits + 1).
    BigDecimal value = new BigDecimal(unscaled).scaleByPowerOfTen(4 * (weight - ndigits + 1));
    if (sign == 0x4000) {
      value = value.negate();
    }
    return value.setScale(Math.max(dscale, 0), java.math.RoundingMode.UNNECESSARY).toPlainString();
  }
}
