package com.mfec.dac.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mfec.dac.source.jdbc.QueryExecutor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueryResultCacheTest {

  static final UUID SOURCE = UUID.randomUUID();
  static final Instant T0 = Instant.parse("2026-09-27T08:00:00Z");
  static final Instant REGISTERED = Instant.parse("2026-09-01T00:00:00Z");

  final QueryResultCache cache = new QueryResultCache(true, 10, 1_000, Duration.ofSeconds(30));

  static QueryResultCache.Key key(String sql) {
    return key(sql, 200);
  }

  static QueryResultCache.Key key(String sql, int maxRows) {
    return new QueryResultCache.Key(
        SOURCE, "POSTGRES", "db.example.test", 5432, "salesdb", "env:SRC", REGISTERED, sql,
        maxRows);
  }

  static QueryExecutor.Page page(int rows, int columns) {
    List<String> names = new ArrayList<>();
    for (int c = 0; c < columns; c++) {
      names.add("c" + c);
    }
    List<List<Object>> data = new ArrayList<>();
    for (int r = 0; r < rows; r++) {
      List<Object> row = new ArrayList<>();
      for (int c = 0; c < columns; c++) {
        row.add(c == 0 ? null : r * 10 + c);
      }
      data.add(row);
    }
    return new QueryExecutor.Page(names, List.of(), data, false, 42);
  }

  @Test
  @DisplayName("the same enforced statement is answered from memory, with when it was read")
  void hit() {
    QueryExecutor.Page read = page(3, 2);
    assertThat(cache.put(key("SELECT id FROM t"), read, T0, cache.generation())).isTrue();

    var hit = cache.get(key("SELECT id FROM t"), T0.plusSeconds(10));

    assertThat(hit).isPresent();
    assertThat(hit.get().storedAt()).isEqualTo(T0);
    assertThat(hit.get().page().rows()).isEqualTo(read.rows());
    assertThat(hit.get().page().millis()).isEqualTo(42);
    assertThat(cache.stats().hits()).isEqualTo(1);
  }

  @Test
  @DisplayName("a held result cannot be changed by whoever it was handed to")
  void frozen() {
    cache.put(key("SELECT id FROM t"), page(2, 2), T0, cache.generation());
    List<List<Object>> rows = cache.get(key("SELECT id FROM t"), T0).orElseThrow().page().rows();

    assertThatThrownBy(() -> rows.get(0).set(1, "x")).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> rows.remove(0)).isInstanceOf(UnsupportedOperationException.class);
    // A null the source returned survives the copy.
    assertThat(rows.get(0).get(0)).isNull();
  }

  @Test
  @DisplayName("a different statement, cap or registration is a different answer")
  void keyDifferences() {
    cache.put(key("SELECT id FROM t"), page(1, 1), T0, cache.generation());

    assertThat(cache.get(key("SELECT id FROM t WHERE branch = 'BKK'"), T0)).isEmpty();
    assertThat(cache.get(key("SELECT id FROM t", 5_000), T0)).isEmpty();
    assertThat(
            cache.get(
                new QueryResultCache.Key(
                    SOURCE, "POSTGRES", "db.example.test", 5432, "salesdb", "env:OTHER",
                    REGISTERED, "SELECT id FROM t", 200),
                T0))
        .isEmpty();
    assertThat(
            cache.get(
                new QueryResultCache.Key(
                    SOURCE, "POSTGRES", "db.example.test", 5432, "salesdb", "env:SRC",
                    REGISTERED.plusSeconds(1), "SELECT id FROM t", 200),
                T0))
        .isEmpty();
    assertThat(cache.get(key("SELECT id FROM t"), T0)).isPresent();
  }

  @Test
  @DisplayName("a result older than the time-to-live is gone")
  void expires() {
    cache.put(key("SELECT id FROM t"), page(1, 1), T0, cache.generation());

    assertThat(cache.get(key("SELECT id FROM t"), T0.plusSeconds(30))).isEmpty();
    assertThat(cache.stats().stale()).isEqualTo(1);
    assertThat(cache.stats().entries()).isZero();
  }

  @Test
  @DisplayName("a flush empties it, and a read already in flight does not land afterwards")
  void flushAndLap() {
    cache.put(key("SELECT a FROM t"), page(1, 1), T0, cache.generation());
    long readAt = cache.generation();

    cache.invalidateAll("policy edited");

    assertThat(cache.get(key("SELECT a FROM t"), T0)).isEmpty();
    assertThat(cache.put(key("SELECT b FROM t"), page(1, 1), T0, readAt)).isFalse();
    assertThat(cache.stats().lapped()).isEqualTo(1);
    assertThat(cache.stats().lastInvalidationReason()).isEqualTo("policy edited");
    assertThat(cache.stats().cells()).isZero();
  }

  @Test
  @DisplayName("statements whose answer depends on the moment are never stored")
  void volatileStatements() {
    for (String sql :
        List.of(
            "SELECT id, now() FROM t",
            "SELECT id FROM t WHERE created > CURRENT_DATE",
            "SELECT id FROM t WHERE created > current_timestamp - interval '1 day'",
            "SELECT random() FROM t",
            "SELECT TOP 5 id FROM t ORDER BY NEWID()",
            "SELECT GETDATE() AS at",
            "SELECT id FROM t TABLESAMPLE SYSTEM (10)",
            "SELECT id FROM t WHERE created > 'now'::timestamp",
            "SELECT @@SPID",
            "SELECT age(born) FROM t")) {
      assertThat(QueryResultCache.cacheable(sql)).as(sql).isFalse();
      assertThat(cache.put(key(sql), page(1, 1), T0, cache.generation())).as(sql).isFalse();
    }
    assertThat(cache.stats().uncacheable()).isEqualTo(10);
    assertThat(cache.stats().entries()).isZero();
  }

  @Test
  @DisplayName("statements that read who the connection is are never stored")
  void sessionStatements() {
    assertThat(QueryResultCache.cacheable("SELECT current_setting('app.principal', true)")).isFalse();
    assertThat(QueryResultCache.cacheable("SELECT SESSION_CONTEXT(N'principal')")).isFalse();
    assertThat(QueryResultCache.cacheable("SELECT current_user")).isFalse();
    assertThat(QueryResultCache.cacheable("SELECT SUSER_SNAME()")).isFalse();
  }

  @Test
  @DisplayName("an ordinary statement, including columns that merely look like functions, is stored")
  void ordinaryStatements() {
    assertThat(QueryResultCache.cacheable("SELECT id, user_name, age, nowhere FROM t")).isTrue();
    assertThat(
            QueryResultCache.cacheable(
                "SELECT \"id\", CASE WHEN true THEN email ELSE '***' END AS \"email\""
                    + " FROM (SELECT * FROM sales.customer WHERE branch_code IN ('BKK')) c"))
        .isTrue();
    assertThat(QueryResultCache.cacheable(null)).isFalse();
  }

  @Test
  @DisplayName("one wide result cannot take more than a quarter of the budget")
  void oversized() {
    assertThat(cache.put(key("SELECT * FROM wide"), page(51, 5), T0, cache.generation())).isFalse();
    assertThat(cache.stats().oversized()).isEqualTo(1);
    assertThat(cache.put(key("SELECT * FROM narrow"), page(50, 5), T0, cache.generation())).isTrue();
    assertThat(cache.stats().cells()).isEqualTo(250);
  }

  @Test
  @DisplayName("the least recently read result goes first when the cells run out")
  void evictsByCells() {
    long g = cache.generation();
    cache.put(key("SELECT 1"), page(50, 5), T0, g);
    cache.put(key("SELECT 2"), page(50, 5), T0, g);
    cache.put(key("SELECT 3"), page(50, 5), T0, g);
    cache.put(key("SELECT 4"), page(50, 5), T0, g);
    cache.get(key("SELECT 1"), T0);

    cache.put(key("SELECT 5"), page(50, 5), T0, g);

    assertThat(cache.get(key("SELECT 2"), T0)).isEmpty();
    assertThat(cache.get(key("SELECT 1"), T0)).isPresent();
    assertThat(cache.get(key("SELECT 5"), T0)).isPresent();
    assertThat(cache.stats().cells()).isLessThanOrEqualTo(1_000);
    assertThat(cache.stats().evictions()).isEqualTo(1);
  }

  @Test
  @DisplayName("the number of statements held is bounded however small they are")
  void evictsByEntries() {
    long g = cache.generation();
    for (int i = 0; i < 12; i++) {
      cache.put(key("SELECT " + i), page(1, 1), T0, g);
    }
    assertThat(cache.stats().entries()).isEqualTo(10);
    assertThat(cache.get(key("SELECT 0"), T0)).isEmpty();
    assertThat(cache.get(key("SELECT 11"), T0)).isPresent();
  }

  @Test
  @DisplayName("replacing a held result does not count its cells twice")
  void replace() {
    long g = cache.generation();
    cache.put(key("SELECT 1"), page(10, 2), T0, g);
    cache.put(key("SELECT 1"), page(20, 2), T0.plusSeconds(5), g);

    assertThat(cache.stats().cells()).isEqualTo(40);
    assertThat(cache.get(key("SELECT 1"), T0.plusSeconds(6)).orElseThrow().storedAt())
        .isEqualTo(T0.plusSeconds(5));
  }

  @Test
  @DisplayName("turned off, it holds nothing and counts nothing")
  void disabled() {
    QueryResultCache off = QueryResultCache.disabled();
    assertThat(off.put(key("SELECT 1"), page(1, 1), T0, off.generation())).isFalse();
    assertThat(off.get(key("SELECT 1"), T0)).isEmpty();
    assertThat(off.stats().enabled()).isFalse();
    assertThat(off.stats().misses()).isZero();
  }

  @Test
  @DisplayName("an empty result is a result")
  void emptyResult() {
    QueryExecutor.Page none =
        new QueryExecutor.Page(List.of("id"), List.of("int4"), List.of(), false, 3);
    assertThat(cache.put(key("SELECT id FROM t WHERE false"), none, T0, cache.generation())).isTrue();
    assertThat(cache.get(key("SELECT id FROM t WHERE false"), T0).orElseThrow().page().rows())
        .isEmpty();
    assertThat(Arrays.asList(cache.stats().cells())).containsExactly(1L);
  }
}
