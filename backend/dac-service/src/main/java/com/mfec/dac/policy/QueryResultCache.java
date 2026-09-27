package com.mfec.dac.policy;

import com.mfec.dac.source.jdbc.QueryExecutor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Query API's result cache (FR-6.3): the same enforced statement, asked
 * again within seconds, answered from memory instead of from the source.
 *
 * <h2>Why the key has no principal in it, and why that is safe</h2>
 *
 * <p>The statement a result is filed under is the <em>rewritten</em> one, not
 * the one the caller typed. By the time it exists the decision has already been
 * made and written into it: the projection is the columns the principal may
 * see, every mask is an expression in that projection, every row filter is in
 * its {@code WHERE} with the principal's attribute values as literals. A denial
 * never gets this far, because the rewrite throws first. So the rewritten
 * statement is the whole of what the decision contributes to the rows, and two
 * callers whose decisions differ in any way that matters get two different
 * statements.
 *
 * <p>The other half is that the proxy has no session identity. It connects as
 * the source's service account and sets nothing on the connection that says
 * who is asking, so the source cannot answer the same statement differently
 * for different people. Two callers who end up with the same rewritten
 * statement on the same connection get the same rows, whether or not it came
 * from here. {@link #cacheable} refuses any statement that reads session state
 * all the same, so that if the proxy ever starts setting it, the cache stops
 * rather than serving one person's rows to another.
 *
 * <h2>What the cache cannot know</h2>
 *
 * <p>The source's data moves without telling anybody. The time-to-live is the
 * whole answer to that, which is why it is short, why every result says how
 * old it is, and why the console can ask for a fresh read. Statements whose
 * answer depends on the moment they run -- {@code now()}, {@code random()},
 * {@code TABLESAMPLE} -- are never stored at all.
 *
 * <h2>What still happens on a hit</h2>
 *
 * <p>Everything but the read. The statement is parsed, every table is governed
 * and every decision is recorded exactly as on a miss, because the cache sits
 * after the rewrite. It saves the source a query; it does not save the
 * platform an audit row or a decision.
 *
 * <h2>Single process</h2>
 *
 * <p>As with {@link DecisionCache}: the map is in this JVM, so a second
 * instance neither shares its entries nor hears its flushes. Here that costs
 * nothing but hit rate, because nothing a flush announces can change the rows
 * a given statement returns.
 */
public final class QueryResultCache {

  private static final Logger LOG = LoggerFactory.getLogger(QueryResultCache.class);

  public static final int DEFAULT_MAX_ENTRIES = 500;
  public static final long DEFAULT_MAX_CELLS = 1_000_000;
  public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

  /**
   * Functions and keywords whose answer depends on when, or how luckily, the
   * statement runs. Matched loosely on purpose: a column that happens to be
   * called {@code rand} makes a statement uncacheable, which costs a hit; a
   * miss here would serve yesterday's {@code now()}.
   */
  private static final Pattern VOLATILE =
      Pattern.compile(
          "(?i)\\b(?:now|clock_timestamp|statement_timestamp|transaction_timestamp|timeofday"
              + "|getdate|getutcdate|sysdatetime|sysutcdatetime|sysdatetimeoffset|age|random"
              + "|rand|newid|newsequentialid|gen_random_uuid|uuid_generate_v[14]|txid_current"
              + "|pg_backend_pid|setseed|nextval|currval|lastval)\\s*\\("
              + "|(?i)\\b(?:current_timestamp|current_date|current_time|localtime|localtimestamp"
              + "|tablesample)\\b"
              + "|(?i)'\\s*(?:now|today|tomorrow|yesterday)\\s*'"
              + "|(?i)@@\\w+");

  /**
   * Anything that reads who the connection says is asking. The proxy sets none
   * of it today; a statement that reads it is the one statement whose rows
   * could differ between two people who got the same text.
   */
  private static final Pattern SESSION =
      Pattern.compile(
          "(?i)\\b(?:current_setting|session_context|suser_sname|suser_name|user_name|original_login"
              + "|inet_client_addr|app_name|host_name)\\s*\\("
              + "|(?i)\\b(?:current_user|session_user|system_user|current_role)\\b");

  /**
   * Everything that decides which rows a statement returns, other than the
   * statement.
   *
   * <p>Where the source is and whose login reads it, because an edit to either
   * points the same text at different data. {@code sourceUpdatedAt} is there
   * for everything else on the registration that a later change might make
   * matter. {@code maxRows}, because the same statement capped at 200 and at
   * 5,000 are two different answers.
   */
  public record Key(
      UUID sourceId,
      String engine,
      String host,
      int port,
      String database,
      String credentialRef,
      Instant sourceUpdatedAt,
      String sql,
      int maxRows) {}

  /** A result as it was read, and when. */
  public record Hit(QueryExecutor.Page page, Instant storedAt) {}

  /** What an operator needs to see to know whether this is helping. */
  public record Stats(
      boolean enabled,
      int entries,
      int maxEntries,
      long ttlSeconds,
      long cells,
      long maxCells,
      long hits,
      long misses,
      long stale,
      long evictions,
      long invalidations,
      long lapped,
      long uncacheable,
      long oversized,
      long generation,
      Instant lastInvalidatedAt,
      String lastInvalidationReason) {}

  private record Entry(
      QueryExecutor.Page page, Instant storedAt, Instant validUntil, long cells, long generation) {}

  private final boolean enabled;
  private final int maxEntries;
  private final long maxCells;
  private final Duration ttl;

  private final AtomicLong generation = new AtomicLong();
  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong misses = new AtomicLong();
  private final AtomicLong stale = new AtomicLong();
  private final AtomicLong evictions = new AtomicLong();
  private final AtomicLong invalidations = new AtomicLong();
  private final AtomicLong lapped = new AtomicLong();
  private final AtomicLong uncacheable = new AtomicLong();
  private final AtomicLong oversized = new AtomicLong();
  private volatile Instant lastInvalidatedAt;
  private volatile String lastInvalidationReason;

  private long cells;
  private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);

  public QueryResultCache(boolean enabled, int maxEntries, long maxCells, Duration ttl) {
    this.enabled = enabled;
    this.maxEntries = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
    this.maxCells = maxCells > 0 ? maxCells : DEFAULT_MAX_CELLS;
    this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
  }

  /** A cache that never holds anything, for tests and for turning it off. */
  public static QueryResultCache disabled() {
    return new QueryResultCache(false, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_CELLS, DEFAULT_TTL);
  }

  public boolean enabled() {
    return enabled;
  }

  /** Read before the statement is sent, and handed back to {@link #put}. */
  public long generation() {
    return generation.get();
  }

  /**
   * Whether this enforced statement's rows can be reused at all: nothing in it
   * depends on the moment it runs, or on who the connection says is asking.
   */
  public static boolean cacheable(String sql) {
    return sql != null && !VOLATILE.matcher(sql).find() && !SESSION.matcher(sql).find();
  }

  /** The rows held for this statement, if they are held and still young enough. */
  public Optional<Hit> get(Key key, Instant now) {
    if (!enabled || key == null || now == null) {
      return Optional.empty();
    }
    synchronized (entries) {
      Entry entry = entries.get(key);
      if (entry == null) {
        misses.incrementAndGet();
        return Optional.empty();
      }
      if (entry.generation() != generation.get() || !now.isBefore(entry.validUntil())) {
        entries.remove(key);
        cells -= entry.cells();
        stale.incrementAndGet();
        misses.incrementAndGet();
        return Optional.empty();
      }
      hits.incrementAndGet();
      return Optional.of(new Hit(entry.page(), entry.storedAt()));
    }
  }

  /**
   * Holds a result for the time-to-live.
   *
   * @param readAt what {@link #generation()} said before the statement was
   *     sent; a flush since then and the result is dropped rather than filed
   *     under a generation it was not read in
   * @return whether it was stored
   */
  public boolean put(Key key, QueryExecutor.Page page, Instant now, long readAt) {
    if (!enabled || key == null || page == null || now == null) {
      return false;
    }
    if (!cacheable(key.sql())) {
      uncacheable.incrementAndGet();
      return false;
    }
    long size = (long) Math.max(1, page.rows().size()) * Math.max(1, page.columns().size());
    // One result may take a quarter of the budget, so a single wide read
    // cannot empty the cache of everything else to make room for itself.
    if (size > maxCells / 4) {
      oversized.incrementAndGet();
      return false;
    }
    QueryExecutor.Page frozen = freeze(page);
    synchronized (entries) {
      if (readAt != generation.get()) {
        lapped.incrementAndGet();
        return false;
      }
      Entry previous = entries.put(key, new Entry(frozen, now, now.plus(ttl), size, readAt));
      if (previous != null) {
        cells -= previous.cells();
      }
      cells += size;
      var eldest = entries.entrySet().iterator();
      while ((entries.size() > maxEntries || cells > maxCells) && eldest.hasNext()) {
        Map.Entry<Key, Entry> oldest = eldest.next();
        if (oldest.getKey().equals(key)) {
          continue;
        }
        cells -= oldest.getValue().cells();
        eldest.remove();
        evictions.incrementAndGet();
      }
    }
    return true;
  }

  /**
   * Empties the cache because something the platform governs changed.
   *
   * <p>Not needed for correctness in the way the decision cache's flush is: a
   * change that alters any decision alters the rewritten statement, and with it
   * the key. It is done anyway, on the same announcements, so that "an
   * administrator just changed something" is never also "and the explorer is
   * still showing what it showed before", which is true and indistinguishable
   * from a bug to the person looking at it.
   */
  public void invalidateAll(String reason) {
    if (!enabled) {
      return;
    }
    generation.incrementAndGet();
    invalidations.incrementAndGet();
    lastInvalidatedAt = Instant.now();
    lastInvalidationReason = reason;
    int dropped;
    synchronized (entries) {
      dropped = entries.size();
      entries.clear();
      cells = 0;
    }
    if (dropped > 0) {
      LOG.debug("Query result cache flushed: {} entries dropped after {}", dropped, reason);
    }
  }

  public Stats stats() {
    int size;
    long held;
    synchronized (entries) {
      size = entries.size();
      held = cells;
    }
    return new Stats(
        enabled,
        size,
        maxEntries,
        ttl.toSeconds(),
        held,
        maxCells,
        hits.get(),
        misses.get(),
        stale.get(),
        evictions.get(),
        invalidations.get(),
        lapped.get(),
        uncacheable.get(),
        oversized.get(),
        generation.get(),
        lastInvalidatedAt,
        lastInvalidationReason);
  }

  /**
   * A copy nobody can change, since the same instance goes back to every
   * caller who asks. Rows are copied rather than {@code List.copyOf}'d because
   * a row holds nulls wherever the source did.
   */
  private static QueryExecutor.Page freeze(QueryExecutor.Page page) {
    List<List<Object>> rows = new ArrayList<>(page.rows().size());
    for (List<Object> row : page.rows()) {
      rows.add(Collections.unmodifiableList(new ArrayList<>(row)));
    }
    return new QueryExecutor.Page(
        List.copyOf(page.columns()),
        page.columnTypes() == null ? null : Collections.unmodifiableList(new ArrayList<>(page.columnTypes())),
        Collections.unmodifiableList(rows),
        page.truncated(),
        page.millis(),
        page.estimatedCost(),
        page.unpricedBecause());
  }
}
