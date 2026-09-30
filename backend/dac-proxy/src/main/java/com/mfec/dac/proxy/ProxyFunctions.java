package com.mfec.dac.proxy;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The functions a statement sent through the query proxy may call.
 *
 * <p>An allow-list, not a deny-list, because the proxy runs every statement as
 * the source's own account. A function is code the proxy cannot see into: one
 * that takes the text of a query and runs it reads whatever that account can
 * read, with no policy in front of it, and a deny-list would have to know every
 * such function on every version of every engine, and every one somebody
 * installs later. What is here only computes over the values it is handed --
 * aggregates, windows, text, numbers, dates, JSON -- so it can see nothing the
 * rewritten statement did not already let through.
 *
 * <p>A name is compared the way the engine would resolve it. Unquoted names fold
 * to lower case. A double-quoted name on PostgreSQL is case-sensitive, so it is
 * allowed only when it is spelled exactly as listed; SQL Server and MySQL
 * compare names without regard to case either way. A schema-qualified name is
 * never allowed:
 * the list is of built-ins, and a qualified call is the way to reach one that is
 * not.
 */
final class ProxyFunctions {

  private static final Set<String> COMMON =
      Set.of(
          // aggregates
          "count", "sum", "avg", "min", "max", "string_agg",
          // windows
          "row_number", "rank", "dense_rank", "percent_rank", "cume_dist", "ntile", "lag", "lead",
          "first_value", "last_value",
          // choosing a value
          "coalesce", "nullif",
          // text
          "lower", "upper", "substring", "left", "right", "trim", "ltrim", "rtrim", "replace",
          "concat", "concat_ws", "reverse", "ascii", "translate",
          // numbers
          "abs", "ceiling", "floor", "round", "power", "sqrt", "exp", "log", "log10", "sign", "pi");

  private static final Set<String> POSTGRES =
      Set.of(
          "array_agg", "bool_and", "bool_or", "every", "stddev", "stddev_pop", "stddev_samp",
          "variance", "var_pop", "var_samp", "percentile_cont", "percentile_disc", "mode", "corr",
          "covar_pop", "covar_samp", "json_agg", "jsonb_agg", "json_object_agg", "jsonb_object_agg",
          "nth_value", "greatest", "least", "length", "char_length", "character_length",
          "octet_length", "bit_length", "substr", "btrim", "lpad", "rpad", "position", "strpos",
          "split_part", "initcap", "repeat", "starts_with", "regexp_replace", "regexp_match",
          "regexp_matches", "regexp_like", "regexp_count", "regexp_substr", "regexp_instr",
          "regexp_split_to_array", "md5", "chr", "overlay", "to_char", "to_number", "to_date",
          "to_timestamp", "ceil", "trunc", "mod", "pow", "cbrt", "ln", "div", "degrees", "radians",
          "width_bucket", "now", "date_trunc", "date_part", "date_bin", "age", "make_date",
          "make_time", "make_timestamp", "make_timestamptz", "make_interval", "justify_days",
          "justify_hours", "justify_interval", "isfinite", "timezone", "json_build_object",
          "jsonb_build_object", "json_build_array", "jsonb_build_array", "to_json", "to_jsonb",
          "json_extract_path", "jsonb_extract_path", "json_extract_path_text",
          "jsonb_extract_path_text", "json_array_length", "jsonb_array_length", "json_typeof",
          "jsonb_typeof", "array_length", "array_to_string", "string_to_array", "array_position",
          "cardinality", "unnest");

  private static final Set<String> SQLSERVER =
      Set.of(
          "count_big", "stdev", "stdevp", "var", "varp", "isnull", "iif", "choose", "len",
          "datalength", "charindex", "patindex", "stuff", "replicate", "space", "str", "char",
          "nchar", "unicode", "format", "square", "getdate", "getutcdate", "sysdatetime",
          "sysutcdatetime", "sysdatetimeoffset", "dateadd", "datediff", "datediff_big", "datename",
          "datepart", "datetrunc", "year", "month", "day", "eomonth", "datefromparts",
          "datetimefromparts", "datetime2fromparts", "timefromparts", "isdate", "isnumeric", "cast",
          "convert", "try_cast", "try_convert", "parse", "try_parse", "json_value", "json_query",
          "isjson");

  /**
   * What is left out on purpose matters as much as what is in. Nothing here
   * waits or holds a lock ({@code SLEEP}, {@code BENCHMARK}, {@code GET_LOCK}),
   * reads a file ({@code LOAD_FILE}), or says anything about the server or the
   * account the statement runs as ({@code USER}, {@code DATABASE},
   * {@code VERSION}, {@code CONNECTION_ID}).
   */
  private static final Set<String> MYSQL =
      Set.of(
          "group_concat", "std", "stddev", "stddev_pop", "stddev_samp", "variance", "var_pop",
          "var_samp", "bit_and", "bit_or", "bit_xor", "json_arrayagg", "json_objectagg",
          "nth_value", "greatest", "least", "ifnull", "if", "isnull", "length", "char_length",
          "character_length", "octet_length", "bit_length", "substr", "mid", "lpad", "rpad",
          "locate", "instr", "position", "substring_index", "repeat", "space", "strcmp", "field",
          "find_in_set", "elt", "format", "hex", "lcase", "ucase", "char", "regexp_replace",
          "regexp_like", "regexp_instr", "regexp_substr", "md5", "sha1", "sha2", "crc32", "ceil",
          "truncate", "mod", "pow", "ln", "log2", "degrees", "radians", "now", "curdate",
          "curtime", "sysdate", "utc_date", "utc_time", "utc_timestamp", "date", "time", "year",
          "month", "day", "dayofmonth", "dayofweek", "dayofyear", "dayname", "monthname", "hour",
          "minute", "second", "quarter", "week", "weekday", "weekofyear", "yearweek", "last_day",
          "date_format", "time_format", "str_to_date", "datediff", "timediff", "timestampdiff",
          "timestampadd", "date_add", "date_sub", "adddate", "subdate", "addtime", "subtime",
          "makedate", "maketime", "from_unixtime", "unix_timestamp", "to_days", "from_days",
          "cast", "convert", "json_extract", "json_unquote", "json_value", "json_length",
          "json_type", "json_valid", "json_contains", "json_contains_path", "json_keys",
          "json_object", "json_array");

  private ProxyFunctions() {}

  /**
   * @param dialect {@code POSTGRES}, {@code SQLSERVER} or {@code MYSQL}, as
   *     {@code SqlDialect#name}
   * @param name the name as written, each part of a qualified name in turn
   */
  static boolean allowed(String dialect, List<String> name) {
    if (name == null || name.size() != 1 || name.get(0) == null) {
      return false;
    }
    String written = name.get(0).trim();
    boolean quoted =
        written.length() > 1
            && ((written.startsWith("\"") && written.endsWith("\""))
                || (written.startsWith("[") && written.endsWith("]"))
                || (written.startsWith("`") && written.endsWith("`")));
    String bare = quoted ? written.substring(1, written.length() - 1) : written;
    boolean postgres = "POSTGRES".equals(dialect);
    String key = quoted && postgres ? bare : bare.toLowerCase(Locale.ROOT);
    return listFor(dialect).contains(key);
  }

  private static final Set<String> FOR_POSTGRES = union(COMMON, POSTGRES);
  private static final Set<String> FOR_SQLSERVER = union(COMMON, SQLSERVER);
  private static final Set<String> FOR_MYSQL = union(COMMON, MYSQL);

  private static Set<String> listFor(String dialect) {
    if ("POSTGRES".equals(dialect)) {
      return FOR_POSTGRES;
    }
    if ("SQLSERVER".equals(dialect)) {
      return FOR_SQLSERVER;
    }
    if ("MYSQL".equals(dialect)) {
      return FOR_MYSQL;
    }
    // An engine this list has not been written for gets the functions every
    // engine here spells the same way, and nothing it might mean differently.
    return COMMON;
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    Set<String> out = new HashSet<>(a);
    out.addAll(b);
    return Set.copyOf(out);
  }
}
